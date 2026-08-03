/*
 * ****************************************************************************
 * Program     : AccountViewServiceTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11
 * Function    : Verifies AccountViewService against COACTVWC paragraph by
 *               paragraph. Concentrates on the contracts a reader cannot
 *               confirm by inspection: the three-step lookup chain order, the
 *               TWO DEAD early-exit guards whose SET statements are commented
 *               out at :792 and :842, the byte-exact diagnostic literals
 *               including the double space at :673, the duplicated
 *               0000-MAIN-EXIT label at :408 and :411, and the CICS ONLINE
 *               abend code '9999' at :935 which is NOT the batch 999 / RC 12
 *               contract.
 * Source      : app/cbl/COACTVWC.cbl      (941 lines, 38 paragraphs)
 *               app/cpy-bms/COACTVW.CPY   (37 input fields; ACCTSIDI at :60)
 *               app/cpy/CVACT01Y.cpy      (ACCOUNT-RECORD, RECLN 300, key 11)
 *               app/cpy/CVACT03Y.cpy      (CARD-XREF-RECORD, 36 of 50 bytes)
 *               app/cpy/CVCUS01Y.cpy      (CUSTOMER-RECORD, RECLN 500, PII)
 *               app/cbl/CBACT04C.cbl:1-21 (this banner's canonical form)
 *               CONTRIBUTING.md:33-34     (repository hygiene)
 *               NOTICE                    (copyright line) @ 7756d89
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
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import com.cardemo.service.account.AccountViewService;
import com.cardemo.service.account.AccountViewService.AccountFilterState;
import com.cardemo.service.account.AccountViewService.AccountViewResult;
import com.cardemo.service.account.AccountViewService.AidKey;
import com.cardemo.service.account.AccountViewService.EntryMode;
import com.cardemo.service.account.AccountViewService.ResponseKind;
import com.cardemo.service.shared.FileStatusMapper;
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
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.LoggerFactory;
import org.springframework.dao.QueryTimeoutException;

/**
 * Unit tests for {@link AccountViewService}, the Java replacement for CICS transaction {@code CAVW} and the
 * program it fronts, {@code app/cbl/COACTVWC.cbl}. Every locator below is keyed to the traceability anchor
 * commit {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, short {@code 7756d89}. A bare {@code :NNN}
 * citation refers to {@code app/cbl/COACTVWC.cbl}; any other member is named.
 *
 * <h2>1. What it does</h2>
 *
 * <p>It pins the behaviour of the account-view conversation at the points where a plausible implementation
 * silently diverges from the system of record. The verified locators it asserts against are:</p>
 *
 * <ul>
 *   <li>{@code :59-61} - {@code WS-EDIT-ACCT-FLAG} is a <em>three</em>-valued flag:
 *       {@code FLG-ACCTFILTER-NOT-OK VALUE '0'}, {@code FLG-ACCTFILTER-ISVALID VALUE '1'} and
 *       {@code FLG-ACCTFILTER-BLANK VALUE ' '}. The blank state is a SPACE, not a zero, and it is observable
 *       because only it emits the {@code '*'} marker at {@code :563}.</li>
 *   <li>{@code :86} - the eighty-byte composite {@code WS-FILE-ERROR-MESSAGE} group, moved into the
 *       seventy-five byte {@code WS-RETURN-MSG} at {@code :766}, {@code :816} and {@code :865}.</li>
 *   <li>{@code :129-134} - the three {@code DID-NOT-FIND-*} names are message-literal {@code 88}-levels on
 *       {@code WS-RETURN-MSG}, not booleans. See section 5.</li>
 *   <li>{@code :262} {@code 0000-MAIN.}, {@code :394} {@code COMMON-RETURN.}, {@code :408} and {@code :411}
 *       the <strong>duplicated</strong> {@code 0000-MAIN-EXIT.}, {@code :416} {@code 1000-SEND-MAP.},
 *       {@code :431} {@code 1100-SCREEN-INIT.}, {@code :460} {@code 1200-SETUP-SCREEN-VARS.}, {@code :541}
 *       {@code 1300-SETUP-SCREEN-ATTRS.}, {@code :577} {@code 1400-SEND-SCREEN.}, {@code :596}
 *       {@code 2000-PROCESS-INPUTS.}, {@code :610} {@code 2100-RECEIVE-MAP.}, {@code :622}
 *       {@code 2200-EDIT-MAP-INPUTS.}, {@code :877} {@code SEND-PLAIN-TEXT.} and {@code :896}
 *       {@code SEND-LONG-TEXT.}</li>
 *   <li>{@code :649-683} {@code 2210-EDIT-ACCOUNT.} - the blank-versus-invalid split and the two-space
 *       literal at {@code :673}.</li>
 *   <li>{@code :687-720} {@code 9000-READ-ACCT.} - the three-step chain and its three guards.</li>
 *   <li>{@code :723-771} {@code 9200-GETCARDXREF-BYACCT.} - the alternate-index read through
 *       {@code CXACAIX}, declared {@code PIC X(8) VALUE 'CXACAIX '} at {@code :192-193}.</li>
 *   <li>{@code :774-821} {@code 9300-GETACCTDATA-BYACCT.} and {@code :825-870}
 *       {@code 9400-GETCUSTDATA-BYCUST.} - the two dead guards' host paragraphs.</li>
 *   <li>{@code :916} {@code ABEND-ROUTINE.} and {@code :935}
 *       {@code EXEC CICS ABEND ABCODE('9999')} - the ONLINE four-character contract.</li>
 *   <li>{@code app/cpy-bms/COACTVW.CPY:60} - {@code 02 ACCTSIDI PIC 99999999999.}, the expanded
 *       non-parenthesised eleven-digit PICTURE that makes the input-field count 37 rather than 36.</li>
 *   </ul>
 *
 * <h2>2. How to build, run and test</h2>
 *
 * <p>Run this class with {@code ./mvnw -B -ntp test -Dtest=AccountViewServiceTest} from the repository root,
 * or the whole tier with {@code ./mvnw -B -ntp test}; {@code ./mvnw -B -ntp verify} additionally applies the
 * JaCoCo line-coverage floor. This is a <strong>Surefire</strong> tier: {@code pom.xml} binds
 * {@code maven-surefire-plugin} 3.5.4 to {@code **}{@code /*Test.java} while excluding
 * {@code **}{@code /integration/**} and {@code **}{@code /e2e/**}, so a class moved out of
 * {@code src/test/java/com/cardemo/unit/} would be collected by neither Surefire nor Failsafe and would
 * silently never run. The report lands in {@code target/surefire-reports/}. Nothing here needs a container,
 * a Spring context, a database or a network socket.</p>
 *
 * <h2>3. Key configurations and defaults</h2>
 *
 * <ul>
 *   <li>Mockito <strong>strict stubs</strong>, declared explicitly through {@code @MockitoSettings}. Every
 *       {@code (fileStatusMapper, ioStatus, logicalFileName, operation)} triple a test drives must be stubbed
 *       in that test, because an unmatched argument set raises {@code PotentialStubbingProblem} and an unused
 *       stubbing raises {@code UnnecessaryStubbingException}. That strictness is the point: it turns the
 *       logical file names {@code CXACAIX }, {@code ACCTDAT } and {@code CUSTDAT } into asserted contracts
 *       rather than incidental arguments.</li>
 *   <li>{@link FileStatusMapper} is a <em>double</em>, not a real instance. The status-to-exception table is
 *       owned by {@code FileStatusMapperTest} and is deliberately not re-asserted here; only the outcome the
 *       mapper returns is stubbed.</li>
 *   <li>A fixed {@link Clock} at {@link #FIXED_INSTANT} on {@link ZoneOffset#UTC}. No no-argument
 *       {@code now()} of any kind, no default locale, no default zone and no unseeded randomness appears in
 *       this file, so the two header fields {@code :434} and {@code :441} derive from are reproducible.</li>
 *   <li>Money is {@link BigDecimal} at the {@code PIC S9(10)V99} scale of two, rounded
 *       {@link RoundingMode#HALF_EVEN}, and equality is decided by {@code compareTo} - never by
 *       {@code equals}, which is scale-sensitive. No {@code float} and no {@code double} appears anywhere.</li>
 *   </ul>
 *
 * <h2>4. Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><strong>The build fails with "warnings found and -Werror specified".</strong> One raw type,
 *       unchecked cast or dangling documentation comment is enough: {@code maven-compiler-plugin} runs
 *       {@code -Xlint:all -Werror} with {@code failOnWarning} at TEST compilation too. An unused import is
 *       <em>not</em> caught, because {@code javac} 25 publishes no {@code unused} lint key; remove it
 *       because Rule 1 Clause B requires it. Nor does this gate reject an unbalanced {@code <ul>}, an
 *       unescaped angle bracket or a stray leading {@code @} in Javadoc: <strong>doclint is not part of the
 *       Maven build at all</strong> and is the separate explicit command published in
 *       {@code docs/technical-specifications.md}.</li>
 *   <li><strong>Someone "fixes" the two dead guards.</strong> Adding an early exit after the account-master
 *       or customer-master read makes {@link #accountMasterMissStillAttemptsTheCustomerLookup()} fail. That
 *       failure is correct: the source keeps reading. Read section 5 before changing production code.</li>
 *   <li><strong>Someone consolidates the duplicated {@code 0000-MAIN-EXIT}.</strong>
 *       {@link #bothDuplicatedMainExitLabelsMapToDistinctMethods()} then fails, and so does the
 *       scope-coverage gate that reads the paragraph map.</li>
 *   <li><strong>Someone conflates the abend contracts.</strong> The online abend is the four-character
 *       literal {@code '9999'} of {@code :935}. The batch contract - abend code 999 with return code 12 -
 *       belongs to the eight {@code ABCODE}-declaring batch programs and to
 *       {@code FatalProcessingException.BATCH_ABEND_CODE}, and must not be reused here.</li>
 *   <li><strong>Someone normalises the account-filter message.</strong> {@code :673} carries two consecutive
 *       spaces between {@code must} and {@code be}. An editor that collapses whitespace breaks
 *       {@link #invalidFilterCarriesTheDoubleSpacedAccountFilterMessage()}.</li>
 *   </ul>
 *
 * <h2>5. The documented conflict - parity governs</h2>
 *
 * <p>Rule 1 Clause B forbids dead code; the parity mandate requires the source's control flow to be
 * reproduced one-to-one. They collide in exactly two places in this program, and parity governs - satisfied
 * by Clause B's own wording, which prohibits artefacts <em>without an owner or tracking reference</em>. Both
 * carry a source locator and both are marked here as intentional retained no-ops, pinned by the assertions
 * below:</p>
 *
 * <ul>
 *   <li><strong>Intentional retained no-op - the dead early-exit guards.</strong> The guards at
 *       {@code :704-706} and
 *       {@code :713-715} test {@code DID-NOT-FIND-ACCT-IN-ACCTDAT} ({@code :131-132}) and
 *       {@code DID-NOT-FIND-CUST-IN-CUSTDAT} ({@code :133-134}). Those are message literals, and their only
 *       {@code SET} statements are commented out - verbatim
 *       {@code *           SET DID-NOT-FIND-ACCT-IN-ACCTDAT TO TRUE} at {@code :792} and
 *       {@code *           SET DID-NOT-FIND-CUST-IN-CUSTDAT TO TRUE} at {@code :842}. Neither literal is
 *       ever assigned, so neither guard can fire, and an account-master miss falls through into the customer
 *       read at {@code :708-711}. The third literal, {@code DID-NOT-FIND-ACCT-IN-CARDXREF} at
 *       {@code :129-130}, is neither set nor tested; its {@code IF} is commented out at {@code :696} and the
 *       author substituted {@code IF FLG-ACCTFILTER-NOT-OK} at {@code :697}, which is the one live guard.</li>
 *   <li><strong>Intentional retained no-op - the duplicated label.</strong> {@code 0000-MAIN-EXIT.} is
 *       declared
 *       twice, at {@code :408} and again at {@code :411}. Both map to their own method; emitting one for the
 *       two labels would break the paragraph map.</li>
 *   </ul>
 *
 * <h2>6. What this class pins, and why each one is easy to get wrong</h2>
 *
 * <ul>
 *   <li><strong>The two dead early-exit guards</strong>, because a naive port short-circuits where the
 *       source does not; <strong>the online {@code '9999'} abend</strong>, which is not the batch abend
 *       contract; and <strong>every protected field of the five-hundred-byte customer record</strong>, none
 *       of which may reach a log, an assertion message or an exception message.</li>
 *   <li><strong>The {@code COACTVW} input-field count is 37</strong>, not the 36 that older prose records,
 *       because of the expanded {@code PIC 99999999999} at
 *       {@code app/cpy-bms/COACTVW.CPY:60}; and the duplicated {@code 0000-MAIN-EXIT} must not be
 *       consolidated.</li>
 *   <li><strong>Two indistinguishable condition names and one case divergence.</strong>
 *       {@code SEARCHED-ACCT-ZEROES} ({@code :125-126}) and
 *       {@code SEARCHED-ACCT-NOT-NUMERIC} ({@code :127-128}) carry an identical {@code VALUE}, so the two
 *       are indistinguishable; and the reason-code label diverges in case between
 *       {@code ' Reas:'} in {@code 9200}/{@code 9300} and {@code ' REAS:'} in {@code 9400}.</li>
 *   </ul>
 *
 * <h2>7. Boundaries of this tier</h2>
 *
 * <p>Two assertions cannot be made from a pure-JVM unit test of this bean, and are named rather than faked.
 * Both need {@code com.cardemo.config.SecurityConfig}, which is not a dependency of this
 * file. First, that {@code /api/admin/*} is restricted to the administrator role - {@code COACTVWC} exposes
 * no administrative endpoint at all, since {@code app/csd/CARDDEMO.CSD} maps {@code CU00} through
 * {@code CU03} to the four {@code COUSR*} programs and not to this one. Second, that the HTTP session policy
 * is {@code STATELESS}. What <em>is</em> observable here, and is asserted, is the property those two rules
 * exist to protect: this bean keeps no server-side state, holds no mutable field, and leaks nothing from one
 * call into the next. The other two belong to a security-tier test that may import
 * {@code SecurityConfig}.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.STRICT_STUBS)
@DisplayName("AccountViewService - COACTVWC / transaction CAVW")
final class AccountViewServiceTest {

    /**
     * The instant every header field derives from. Chosen to match the version stamp
     * {@code Date: 2022-07-19 23:12:32 CDT} in the source trailer at {@code :940}, so the fixture is traceable
     * rather than arbitrary.
     */
    private static final Instant FIXED_INSTANT = Instant.parse("2022-07-19T23:12:32Z");

    /** {@code LIT-CARDXREFNAME-ACCT-PATH PIC X(8) VALUE 'CXACAIX '} at {@code :192-193}. */
    private static final String XREF_ACCOUNT_PATH_NAME = "CXACAIX ";

    /** {@code LIT-ACCTFILENAME PIC X(8) VALUE 'ACCTDAT '} at {@code :184-185}. */
    private static final String ACCOUNT_FILE_NAME = "ACCTDAT ";

    /** {@code LIT-CUSTFILENAME PIC X(8) VALUE 'CUSTDAT '} at {@code :188-189}. */
    private static final String CUSTOMER_FILE_NAME = "CUSTDAT ";

    /** {@code MOVE 'READ' TO ERROR-OPNAME} at {@code :762}, {@code :812} and {@code :861}. */
    private static final String OPERATION_READ = "READ";

    /** File status for a successful read; the only status the guards let through unremarked. */
    private static final String STATUS_SUCCESS = "00";

    /** File status {@code '23'}: the {@code DFHRESP(NOTFND)} arms of {@code 9200}, {@code 9300}, {@code 9400}. */
    private static final String STATUS_RECORD_NOT_FOUND = "23";

    /** File status {@code '9x'}: the {@code WHEN OTHER} arms at {@code :759}, {@code :809} and {@code :858}. */
    private static final String STATUS_IO_ERROR = "90";

    /** {@code EIBAID} symbol {@code DFHENTER}, the identifier {@code :307} accepts alongside PF03. */
    private static final String ENTER_KEY = "DFHENTER";

    /** {@code EIBAID} symbol {@code DFHPF3}, the exit key resolved at {@code :328-339}. */
    private static final String PF03_KEY = "DFHPF3";

    /** {@code CC-ACCT-ID PIC X(11)} width, and therefore {@code CDEMO-ACCT-ID PIC 9(11)}. */
    private static final int ACCOUNT_ID_WIDTH = 11;

    /** A well-formed eleven-digit filter whose numeric value is eleven. */
    private static final String VALID_FILTER = "00000000011";

    /** {@code CDEMO-ACCT-ID} after {@code :678} has moved {@link #VALID_FILTER} into it. */
    private static final long VALID_ACCOUNT_ID = 11L;

    /** {@code XREF-CUST-ID PIC 9(09)}, the identifier {@code :739} hands to the customer read. */
    private static final long XREF_CUSTOMER_ID = 888L;

    /**
     * A synthetic {@code XREF-CARD-NUM PIC X(16)}. Deliberately not a well-formed payment-card number, and
     * asserted never to reach a log, an assertion message or an exception message.
     */
    private static final String CARD_NUMBER = "9999888877776666";

    /**
     * A synthetic {@code CUST-SSN PIC 9(09)} at record offset 280-288. The 900 area range is never issued, so
     * this cannot collide with a real identifier. Protected: never logged, never quoted.
     */
    private static final String SSN = "999999999";

    /**
     * The rendered form of {@link #SSN} that the screen carries, produced by the {@code CUST-SSN} formatter
     * of {@code :506}: the same reserved nine-digit value with the conventional two group separators
     * inserted. A derived form of a protected field is still protected, so it
     * is checked for alongside the undelimited digits.
     */
    private static final String SSN_DISPLAY = "999-99-9999";

    /**
     * A synthetic {@code CUST-PHONE-NUM-1} at record offset 250-264, declared {@code PIC X(15)} at
     * {@code app/cpy/CVCUS01Y.cpy}:15. EXACTLY FIFTEEN CHARACTERS, deliberately: the narrowing this suite
     * asserts drops exactly two bytes, and a fourteen-character fixture would drop one and prove the wrong
     * arithmetic. Protected.
     */
    private static final String PHONE_1 = "(555) 555-01001";

    /**
     * A synthetic {@code CUST-PHONE-NUM-2} at record offset 265-279, declared {@code PIC X(15)} at
     * {@code app/cpy/CVCUS01Y.cpy}:16. Fifteen characters, for the same reason as {@link #PHONE_1}.
     * Protected.
     */
    private static final String PHONE_2 = "(555) 555-01992";

    /**
     * {@link #PHONE_1} as the screen actually carries it: its LEFT thirteen characters.
     *
     * <p>The record field is {@code PIC X(15)} at {@code app/cpy/CVCUS01Y.cpy}:15 while the screen field is
     * {@code ACSPHN1O PIC X(13)} at {@code app/cpy-bms/COACTVW.CPY}:428, so
     * {@code MOVE CUST-PHONE-NUM-1 TO ACSPHN1O} at {@code app/cbl/COACTVWC.cbl}:517 narrows the field and
     * discards its final two bytes. The output field is the one the {@code MOVE} names; the input twin
     * {@code ACSPHN1I} at {@code :204} carries the same {@code PIC X(13)} and is not the target.
     *
     * <p>COBOL alphanumeric moves left-justify and truncate on the RIGHT, so the retained bytes are the
     * leading thirteen and never the trailing thirteen. A truncated protected value is still a protected
     * value, so it is checked for in its own right.
     */
    private static final String PHONE_1_DISPLAY = "(555) 555-010";

    /**
     * {@link #PHONE_2} narrowed by {@code ACSPHN2O PIC X(13)} at {@code app/cpy-bms/COACTVW.CPY}:440, by the
     * {@code MOVE} at {@code app/cbl/COACTVWC.cbl}:518. Protected.
     */
    private static final String PHONE_2_DISPLAY = "(555) 555-019";

    /** A synthetic {@code CUST-DOB-YYYY-MM-DD PIC X(10)} at record offset 309-318. Protected. */
    private static final String DATE_OF_BIRTH = "1970-01-01";

    /** A synthetic {@code CUST-GOVT-ISSUED-ID PIC X(20)}. Protected. */
    private static final String GOVERNMENT_ID = "SYNTHETIC-ID-000001";

    /** A synthetic {@code CUST-EFT-ACCOUNT-ID PIC X(10)}. Protected. */
    private static final String EFT_ACCOUNT_ID = "EFT0000001";

    /**
     * {@code MOVE 'Account Filter must  be a non-zero 11 digit number' TO WS-RETURN-MSG} at {@code :671-673}.
     * Two consecutive spaces separate {@code must} from {@code be}. Byte-exact by construction: the double
     * space is written as two separate one-character literals so that no editor can silently collapse it.
     */
    private static final String ACCOUNT_FILTER_MESSAGE =
            "Account Filter must" + " " + " " + "be a non-zero 11 digit number";

    /** {@code 88 WS-PROMPT-FOR-ACCT VALUE 'Account number not provided'} at {@code :121-122}. */
    private static final String PROMPT_FOR_ACCOUNT_MESSAGE = "Account number not provided";

    /** {@code 88 NO-SEARCH-CRITERIA-RECEIVED VALUE 'No input received'} at {@code :123-124}. */
    private static final String NO_SEARCH_CRITERIA_MESSAGE = "No input received";

    /**
     * {@code 88 SEARCHED-ACCT-ZEROES} at {@code :125-126} and {@code 88 SEARCHED-ACCT-NOT-NUMERIC} at
     * {@code :127-128} - two condition names over one identical literal, and a different string from
     * {@link #ACCOUNT_FILTER_MESSAGE}.
     */
    private static final String SEARCHED_ACCT_MESSAGE = "Account number must be a non zero 11 digit number";

    /** {@code 88 DID-NOT-FIND-ACCT-IN-CARDXREF} at {@code :129-130}: never set, never tested. */
    private static final String DID_NOT_FIND_ACCOUNT_IN_CARDXREF =
            "Did not find this account in account card xref file";

    /** {@code 88 DID-NOT-FIND-ACCT-IN-ACCTDAT} at {@code :131-132}: the first dead guard's literal. */
    private static final String DID_NOT_FIND_ACCOUNT_IN_ACCTDAT =
            "Did not find this account in account master file";

    /** {@code 88 DID-NOT-FIND-CUST-IN-CUSTDAT} at {@code :133-134}: the second dead guard's literal. */
    private static final String DID_NOT_FIND_CUSTOMER_IN_CUSTDAT =
            "Did not find associated customer in master file";

    /** {@code 88 XREF-READ-ERROR VALUE 'Error reading account card xref File'} at {@code :135-136}. */
    private static final String XREF_READ_ERROR_MESSAGE = "Error reading account card xref File";

    /**
     * {@code 88 CODING-TO-BE-DONE VALUE 'Looks Good.... so far'} at {@code :137-138}. Exactly
     * <strong>four</strong> full stops, written as four separate one-character literals so the count cannot be
     * mistaken for an ellipsis.
     */
    private static final String CODING_TO_BE_DONE_MESSAGE =
            "Looks Good" + "." + "." + "." + "." + " so far";

    /** {@code 88 WS-PROMPT-FOR-INPUT} on {@code WS-INFO-MSG}, stamped at {@code :463} and {@code :529}. */
    private static final String PROMPT_FOR_INPUT_MESSAGE = "Enter or update id of account to display";

    /** The plain text {@code SEND-PLAIN-TEXT} carries on the {@code WHEN OTHER} arm at {@code :375-382}. */
    private static final String UNEXPECTED_DATA_SCENARIO_MESSAGE = "UNEXPECTED DATA SCENARIO";

    /** {@code EXEC CICS ABEND ABCODE('9999')} at {@code :935}: four characters, filling {@code PIC X(4)}. */
    private static final String ONLINE_ABEND_CODE = "9999";

    /** {@code MOVE LIT-THISPGM TO ABEND-CULPRIT} at {@code :922}. */
    private static final String PROGRAM_NAME = "COACTVWC";

    /** {@code LIT-THISTRANID}, stamped into {@code WS-TRANID} at {@code :274}. */
    private static final String TRANSACTION_ID = "CAVW";

    /** {@code LIT-MENUTRANID}, the PF03 fallback of {@code :328-333}. */
    private static final String MENU_TRANSACTION_ID = "CM00";

    /** {@code LIT-MENUPGM}, the PF03 fallback of {@code :334-339}. */
    private static final String MENU_PROGRAM = "COMEN01C";

    /** {@code LIT-THISMAPSET PIC X(8) VALUE 'COACTVW '} - the trailing blank is part of the value. */
    private static final String THIS_MAPSET = "COACTVW ";

    /** {@code LIT-THISMAP VALUE 'CACTVWA'}. */
    private static final String THIS_MAP = "CACTVWA";

    /** {@code MOVE DFHRED TO ACCTSIDC} at {@code :558} and {@code :565}. */
    private static final String COLOUR_RED = "DFHRED";

    /** {@code MOVE DFHDFCOL TO ACCTSIDC} at {@code :555}. */
    private static final String COLOUR_DEFAULT = "DFHDFCOL";

    /** {@code MOVE '*' TO ACCTSIDO} at {@code :563} - emitted for the blank state only. */
    private static final String ASTERISK = "*";

    /** {@code MOVE -1 TO ACCTSIDL} - the cursor request all three arms of {@code :546-552} issue. */
    private static final int CURSOR_ON_ACCOUNT_FILTER = -1;

    /**
     * The thirty-seven private methods {@link AccountViewService} emits, one per source paragraph: thirty-five
     * Area-A labels declared in {@code app/cbl/COACTVWC.cbl} plus the two that {@code COPY 'CSSTRPFY'} at
     * {@code :913} contributes. The {@code COPY} directive itself is not a paragraph body and emits no method,
     * which is how thirty-eight paragraphs become thirty-seven methods. Each entry pairs the method name with
     * its source locator.
     */
    private static final List<String> PARAGRAPH_METHODS = List.of(
            "mainLine0000:262",
            "commonReturn:394",
            "mainExit0000AtLine408:408",
            "mainExit0000AtLine411:411",
            "sendMap1000:416",
            "sendMap1000Exit:427",
            "screenInit1100:431",
            "screenInit1100Exit:457",
            "setupScreenVars1200:460",
            "setupScreenVars1200Exit:537",
            "setupScreenAttrs1300:541",
            "setupScreenAttrs1300Exit:574",
            "sendScreen1400:577",
            "sendScreen1400Exit:592",
            "processInputs2000:596",
            "processInputs2000Exit:607",
            "receiveMap2100:610",
            "receiveMap2100Exit:619",
            "editMapInputs2200:622",
            "editMapInputs2200Exit:645",
            "editAccount2210:649",
            "editAccount2210Exit:683",
            "readAcct9000:687",
            "readAcct9000Exit:720",
            "getCardXrefByAcct9200:723",
            "getCardXrefByAcct9200Exit:771",
            "getAcctDataByAcct9300:774",
            "getAcctDataByAcct9300Exit:821",
            "getCustDataByCust9400:825",
            "getCustDataByCust9400Exit:870",
            "sendPlainText:877",
            "sendPlainTextExit:888",
            "sendLongText:896",
            "sendLongTextExit:907",
            "abendRoutine:916",
            "storePfKey:app/cpy/CSSTRPFY.cpy:17",
            "storePfKeyExit:app/cpy/CSSTRPFY.cpy:80");

    /**
     * Every protected component of the five-hundred-byte {@code CUSTOMER-RECORD} plus the sixteen-byte
     * {@code XREF-CARD-NUM}. No log event, no assertion description and no exception message may contain any
     * of them.
     */
    private static final List<String> PROTECTED_VALUES = List.of(
            CARD_NUMBER, SSN, SSN_DISPLAY, PHONE_1, PHONE_2, PHONE_1_DISPLAY, PHONE_2_DISPLAY,
            DATE_OF_BIRTH, GOVERNMENT_ID, EFT_ACCOUNT_ID);

    /** {@code WS-RETURN-MSG PIC X(75)} at {@code :117} - every diagnostic is truncated to this width. */
    private static final int RETURN_MESSAGE_WIDTH = 75;

    /** {@code STRING 'Account:'} - the leading fragment of the {@code 9200} and {@code 9300} diagnostics. */
    private static final String MESSAGE_ACCOUNT_PREFIX = "Account:";

    /** {@code STRING ' not found in'} - shared by {@code 9200} at {@code :750} and {@code 9300}. */
    private static final String MESSAGE_NOT_FOUND_IN = " not found in";

    /**
     * {@code STRING ' Cross ref file.  Resp:'} from {@code 9200}. <strong>Two</strong> spaces follow the full
     * stop. Written as an explicit two-character literal so no formatter can collapse it silently.
     */
    private static final String MESSAGE_XREF_FILE = " Cross ref file." + "  " + "Resp:";

    /**
     * {@code STRING ' Acct Master file.Resp:'} from {@code 9300}. <strong>No</strong> space follows the full
     * stop - the divergence from {@link #MESSAGE_XREF_FILE} is the source's, and is preserved.
     */
    private static final String MESSAGE_ACCOUNT_MASTER_FILE = " Acct Master file." + "Resp:";

    /** {@code STRING ' Reas:'} - mixed case in {@code 9200} and {@code 9300}. Compare {@link #MESSAGE_REASON_UPPER}. */
    private static final String MESSAGE_REASON = " Reas:";

    /** {@code STRING 'CustId:'} - the leading fragment of the {@code 9400} diagnostic. */
    private static final String MESSAGE_CUSTOMER_PREFIX = "CustId:";

    /** {@code STRING ' not found'} from {@code 9400} - shorter than {@link #MESSAGE_NOT_FOUND_IN}. */
    private static final String MESSAGE_NOT_FOUND = " not found";

    /**
     * {@code STRING ' in customer master.Resp: '} from {@code 9400}. No space follows the full stop but a
     * <strong>trailing</strong> space closes the literal, so the response digits are pushed one byte right.
     */
    private static final String MESSAGE_CUSTOMER_MASTER = " in customer master." + "Resp:" + " ";

    /** {@code STRING ' REAS:'} from {@code 9400} - upper case, unlike {@link #MESSAGE_REASON}. */
    private static final String MESSAGE_REASON_UPPER = " REAS:";

    /** {@code ERROR-RESP PIC X(10)} holding {@code DFHRESP(NOTFND)}, numeric 13, zero-filled to nine digits. */
    private static final String RENDERED_RESP_NOT_FOUND = "000000013" + " ";

    /** {@code ERROR-RESP PIC X(10)} holding {@code DFHRESP(IOERR)}, numeric 17. */
    private static final String RENDERED_RESP_IO_ERROR = "000000017" + " ";

    /** {@code ERROR-RESP2 PIC X(10)} holding {@code EIBRESP2} zero. */
    private static final String RENDERED_REASON_NONE = "000000000" + " ";

    /** {@code WS-CARD-RID-ACCT-ID-X}, the eleven-digit rendering of {@link #VALID_ACCOUNT_ID}. */
    private static final String RENDERED_ACCOUNT_RID = "00000000011";

    /** {@code WS-CARD-RID-CUST-ID-X}, the nine-digit rendering of {@link #XREF_CUSTOMER_ID}. */
    private static final String RENDERED_CUSTOMER_RID = "000000888";

    /** The {@code 9200} not-found diagnostic exactly as {@code :747-757} builds it, then truncated at {@code :117}. */
    private static final String XREF_NOT_FOUND_DIAGNOSTIC = truncateToReturnMessageWidth(
            MESSAGE_ACCOUNT_PREFIX + RENDERED_ACCOUNT_RID + MESSAGE_NOT_FOUND_IN + MESSAGE_XREF_FILE
                    + RENDERED_RESP_NOT_FOUND + MESSAGE_REASON + RENDERED_REASON_NONE);

    /** The {@code 9300} not-found diagnostic exactly as {@code :797-807} builds it. */
    private static final String ACCOUNT_NOT_FOUND_DIAGNOSTIC = truncateToReturnMessageWidth(
            MESSAGE_ACCOUNT_PREFIX + RENDERED_ACCOUNT_RID + MESSAGE_NOT_FOUND_IN
                    + MESSAGE_ACCOUNT_MASTER_FILE + RENDERED_RESP_NOT_FOUND + MESSAGE_REASON
                    + RENDERED_REASON_NONE);

    /** The {@code 9400} not-found diagnostic exactly as {@code :846-856} builds it. */
    private static final String CUSTOMER_NOT_FOUND_DIAGNOSTIC = truncateToReturnMessageWidth(
            MESSAGE_CUSTOMER_PREFIX + RENDERED_CUSTOMER_RID + MESSAGE_NOT_FOUND + MESSAGE_CUSTOMER_MASTER
                    + RENDERED_RESP_NOT_FOUND + MESSAGE_REASON_UPPER + RENDERED_REASON_NONE);

    /** {@code FILLER PIC X(12) VALUE 'File Error: '} of the composite group at {@code :86-106}. */
    private static final String FILE_ERROR_PREFIX = "File Error: ";

    /** {@code FILLER PIC X(4) VALUE ' on '} of the composite group. */
    private static final String FILE_ERROR_ON = " on ";

    /** {@code FILLER PIC X(15) VALUE ' returned RESP '} of the composite group. */
    private static final String FILE_ERROR_RETURNED_RESP = " returned RESP ";

    /** {@code FILLER PIC X(7) VALUE ',RESP2 '} of the composite group. */
    private static final String FILE_ERROR_RESP2 = ",RESP2 ";

    /** {@code ERROR-OPNAME PIC X(8)}, always {@code READ} on every path in this program. */
    private static final String RENDERED_OPERATION = "READ" + "    ";

    @Mock
    private CardCrossReferenceRepository cardCrossReferenceRepository;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private FileStatusMapper fileStatusMapper;

    private AccountViewService service;

    private Logger logger;

    private Level originalLevel;

    private ListAppender<ILoggingEvent> appender;

    /**
     * Assembles the bean by constructor injection over four doubles and a fixed {@link Clock}, then attaches an
     * in-memory appender at {@code TRACE} so the data-protection assertions can inspect every line the bean
     * emits. The logger's original level is captured for restoration, because a level left raised would leak
     * into whatever class Surefire runs next.
     */
    @BeforeEach
    void setUp() {
        this.service = new AccountViewService(this.cardCrossReferenceRepository,
                this.accountRepository,
                this.customerRepository,
                this.fileStatusMapper,
                Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));
        this.logger = (Logger) LoggerFactory.getLogger(AccountViewService.class);
        this.originalLevel = this.logger.getLevel();
        this.appender = new ListAppender<>();
        this.appender.start();
        this.logger.addAppender(this.appender);
        this.logger.setLevel(Level.TRACE);
    }

    /** Detaches the appender and restores the captured level, leaving global logging state untouched. */
    @AfterEach
    void tearDown() {
        this.logger.setLevel(this.originalLevel);
        this.logger.detachAppender(this.appender);
        this.appender.stop();
    }

    // -----------------------------------------------------------------------------------------------------
    // Fixtures and stubbing helpers. Every helper is used; an unused one would be dead code under Clause B
    // and an unused stubbing would additionally fail the strict-stub check.
    // -----------------------------------------------------------------------------------------------------

    /**
     * Truncates a composed diagnostic to {@code WS-RETURN-MSG PIC X(75)}. {@code STRING ... DELIMITED BY SIZE}
     * contributes every fragment's full declared width, so the assembled text overflows and the {@code MOVE}
     * into the seventy-five byte field drops the tail. The truncation is behaviour, not an accident.
     *
     * @param composed the concatenation of the paragraph's {@code STRING} fragments; must not be {@code null}
     * @return the first seventy-five characters, or the whole value when it is shorter
     */
    private static String truncateToReturnMessageWidth(final String composed) {
        return composed.length() <= RETURN_MESSAGE_WIDTH
                ? composed
                : composed.substring(0, RETURN_MESSAGE_WIDTH);
    }

    /**
     * Builds {@code WS-FILE-ERROR-MESSAGE} of {@code :86-106} for a given logical file, then truncates it. The
     * group is eighty bytes wide and its final {@code FILLER PIC X(5)} therefore never survives the
     * {@code MOVE} at {@code :766}, {@code :816} or {@code :865}.
     *
     * @param logicalFileName the {@code ERROR-FILE PIC X(9)} value; must not be {@code null}
     * @return the composite message as it reaches the screen; never {@code null}
     */
    private static String compositeFileErrorMessage(final String logicalFileName) {
        final String paddedFile = logicalFileName.length() >= 9
                ? logicalFileName.substring(0, 9)
                : logicalFileName + " ".repeat(9 - logicalFileName.length());
        return truncateToReturnMessageWidth(FILE_ERROR_PREFIX + RENDERED_OPERATION + FILE_ERROR_ON
                + paddedFile + FILE_ERROR_RETURNED_RESP + RENDERED_RESP_IO_ERROR + FILE_ERROR_RESP2
                + RENDERED_REASON_NONE);
    }

    /**
     * Builds the {@code CARD-XREF-RECORD} of {@code app/cpy/CVACT03Y.cpy}: sixteen bytes of card number, nine
     * of customer identifier and eleven of account identifier - thirty-six populated bytes inside a fifty-byte
     * slot whose fourteen-byte {@code FILLER} is deliberately not modelled.
     *
     * @return the cross-reference record; never {@code null}
     */
    private static CardCrossReference crossReference() {
        return new CardCrossReference(CARD_NUMBER, XREF_CUSTOMER_ID, VALID_ACCOUNT_ID);
    }

    /**
     * Builds the {@code ACCOUNT-RECORD} of {@code app/cpy/CVACT01Y.cpy} with a caller-chosen current balance,
     * so a single fixture serves both the projection tests and the decimal-comparison tests.
     *
     * @param currentBalance {@code ACCT-CURR-BAL PIC S9(10)V99}; must not be {@code null} and must have a
     *                       scale of two or less, which the entity enforces
     * @return the account record; never {@code null}
     */
    private static Account account(final BigDecimal currentBalance) {
        return new Account(VALID_ACCOUNT_ID,
                "Y",
                currentBalance,
                new BigDecimal("2020.00"),
                new BigDecimal("1020.00"),
                "2015-07-01",
                "2025-06-30",
                "2020-07-01",
                new BigDecimal("0.00"),
                new BigDecimal("0.00"),
                "0000012345",
                "DEFAULT   ");
    }

    /**
     * Builds the {@code CUSTOMER-RECORD} of {@code app/cpy/CVCUS01Y.cpy}. Every protected component is
     * synthetic and unusable: the social-security area {@code 999} is never issued, both telephone numbers sit
     * in the reserved {@code 555-01xx} fiction range, and the government-issued identifier is self-labelling.
     *
     * @return the customer record; never {@code null}
     */
    private static Customer customer() {
        return new Customer(XREF_CUSTOMER_ID,
                "SYNTHETICA",
                "Q",
                "TESTCASE",
                "1 SAMPLE STREET",
                "SUITE 100",
                "SPRINGFIELD",
                "IL",
                "USA",
                "0000012345",
                PHONE_1,
                PHONE_2,
                SSN,
                GOVERNMENT_ID,
                DATE_OF_BIRTH,
                EFT_ACCOUNT_ID,
                "Y",
                "750");
    }

    /**
     * Stubs the mapper's verdict for a {@code DFHRESP(NORMAL)} read of one logical file. The logical file name
     * is an exact stubbing argument rather than a matcher, so the CICS literal each paragraph passes is itself
     * under test: a paragraph that named the base cluster instead of the alternate-index path would raise
     * {@code PotentialStubbingProblem} rather than pass quietly.
     *
     * @param logicalFileName one of the three {@code LIT-*} names; must not be {@code null}
     */
    private void stubSuccessfulRead(final String logicalFileName) {
        when(this.fileStatusMapper.toException(STATUS_SUCCESS, logicalFileName, OPERATION_READ))
                .thenReturn(Optional.empty());
    }

    /**
     * Stubs the mapper's verdict for a {@code DFHRESP(NOTFND)} read. The status-to-exception table itself is
     * owned by {@code FileStatusMapperTest} and is not re-asserted here; only the verdict is supplied.
     *
     * @param logicalFileName one of the three {@code LIT-*} names; must not be {@code null}
     * @param recordType      the record type for the exception payload; must not be {@code null}
     */
    private void stubRecordNotFound(final String logicalFileName, final String recordType) {
        when(this.fileStatusMapper.toException(STATUS_RECORD_NOT_FOUND, logicalFileName, OPERATION_READ))
                .thenReturn(Optional.of(new RecordNotFoundException(
                        "record not found in " + logicalFileName.strip(), recordType, "redacted")));
    }

    /**
     * Stubs the mapper's verdict for a {@code 9x} physical failure, on the four-argument overload. Passing the
     * cause instance as an exact stubbing argument is what proves the adapter threads the infrastructure
     * throwable through to the mapper instead of discarding it.
     *
     * @param logicalFileName one of the three {@code LIT-*} names; must not be {@code null}
     * @param cause           the infrastructure throwable the repository raised; must not be {@code null}
     * @return the typed exception the mapper will return, so a test can assert identity
     */
    private CardDemoException stubIoError(final String logicalFileName, final Throwable cause) {
        final CardDemoException verdict =
                new CardDemoException("physical I/O failure on " + logicalFileName.strip(), cause);
        when(this.fileStatusMapper.toException(STATUS_IO_ERROR, logicalFileName, OPERATION_READ, cause))
                .thenReturn(Optional.of(verdict));
        return verdict;
    }

    /** Stubs a successful cross-reference read: one row, {@code DFHRESP(NORMAL)}. */
    private void stubCrossReferenceFound() {
        when(this.cardCrossReferenceRepository.findFirstByAccountIdOrderByCardNumberAsc(VALID_ACCOUNT_ID))
                .thenReturn(Optional.of(crossReference()));
        stubSuccessfulRead(XREF_ACCOUNT_PATH_NAME);
    }

    /** Stubs an empty cross-reference result, which the adapter renders as file status {@code '23'}. */
    private void stubCrossReferenceMissing() {
        when(this.cardCrossReferenceRepository.findFirstByAccountIdOrderByCardNumberAsc(VALID_ACCOUNT_ID))
                .thenReturn(Optional.empty());
        stubRecordNotFound(XREF_ACCOUNT_PATH_NAME, "CARD-XREF-RECORD");
    }

    /**
     * Stubs a successful account-master read.
     *
     * @param currentBalance the balance the fixture carries; must not be {@code null}
     */
    private void stubAccountFound(final BigDecimal currentBalance) {
        when(this.accountRepository.findById(VALID_ACCOUNT_ID))
                .thenReturn(Optional.of(account(currentBalance)));
        stubSuccessfulRead(ACCOUNT_FILE_NAME);
    }

    /** Stubs an absent account master row, which the adapter renders as file status {@code '23'}. */
    private void stubAccountMissing() {
        when(this.accountRepository.findById(VALID_ACCOUNT_ID)).thenReturn(Optional.empty());
        stubRecordNotFound(ACCOUNT_FILE_NAME, "ACCOUNT-RECORD");
    }

    /** Stubs a successful customer-master read for the identifier the cross-reference supplied. */
    private void stubCustomerFound() {
        when(this.customerRepository.findById(XREF_CUSTOMER_ID)).thenReturn(Optional.of(customer()));
        stubSuccessfulRead(CUSTOMER_FILE_NAME);
    }

    /** Stubs an absent customer master row, which the adapter renders as file status {@code '23'}. */
    private void stubCustomerMissing() {
        when(this.customerRepository.findById(XREF_CUSTOMER_ID)).thenReturn(Optional.empty());
        stubRecordNotFound(CUSTOMER_FILE_NAME, "CUSTOMER-RECORD");
    }

    /** Stubs all three reads of the chain as successful, with a zero current balance. */
    private void stubCompleteChain() {
        stubCrossReferenceFound();
        stubAccountFound(new BigDecimal("0.00"));
        stubCustomerFound();
    }

    /**
     * Drives one {@code CDEMO-PGM-REENTER} request with {@code EIBAID} of {@code DFHENTER}, which is the arm
     * at {@code :361-373} and therefore the only arm that reads any file.
     *
     * @param accountFilter {@code ACCTSIDI OF CACTVWAI}; permitted to be {@code null}
     * @return the assembled response; never {@code null}
     */
    private AccountViewResult reenter(final String accountFilter) {
        return this.service.processRequest(accountFilter, ENTER_KEY, EntryMode.REENTER);
    }

    /**
     * Every log line the bean emitted during the current test, already interpolated.
     *
     * @return the rendered messages in emission order; never {@code null}
     */
    private List<String> loggedMessages() {
        final List<String> rendered = new ArrayList<>(this.appender.list.size());
        for (final ILoggingEvent event : this.appender.list) {
            rendered.add(event.getFormattedMessage());
        }
        return rendered;
    }

    // -----------------------------------------------------------------------------------------------------
    // 9000-READ-ACCT: the three-step lookup chain, :687-720
    // -----------------------------------------------------------------------------------------------------

    @Test
    @DisplayName(":693-711 reads the cross-reference, then the account master, then the customer master")
    void chainReadsCrossReferenceThenAccountThenCustomerInThatExactOrder() {
        stubCompleteChain();

        final AccountViewResult result = reenter(VALID_FILTER);

        final InOrder order =
                inOrder(this.cardCrossReferenceRepository, this.accountRepository, this.customerRepository);
        order.verify(this.cardCrossReferenceRepository)
                .findFirstByAccountIdOrderByCardNumberAsc(VALID_ACCOUNT_ID);
        order.verify(this.accountRepository).findById(VALID_ACCOUNT_ID);
        order.verify(this.customerRepository).findById(XREF_CUSTOMER_ID);
        order.verifyNoMoreInteractions();
        assertThat(result.accountFound()).as(":788 SET FOUND-ACCT-IN-MASTER TO TRUE").isTrue();
        assertThat(result.customerFound()).as(":838 SET FOUND-CUST-IN-MASTER TO TRUE").isTrue();
        assertThat(result.inputError()).as(":624 SET INPUT-OK, never overturned on a clean chain").isFalse();
    }

    @Test
    @DisplayName(":739 and :708 the customer read keys on XREF-CUST-ID, not on any caller-supplied value")
    void customerLookupUsesTheIdentifierReturnedByTheCrossReferenceRead() {
        stubCompleteChain();

        final AccountViewResult result = reenter(VALID_FILTER);

        verify(this.customerRepository).findById(XREF_CUSTOMER_ID);
        assertThat(result.screen().customerId())
                .as(":508 MOVE CUST-ID TO ACSTNUMO, nine digits per app/cpy/CVCUS01Y.cpy")
                .isEqualTo(RENDERED_CUSTOMER_RID);
        assertThat(XREF_CUSTOMER_ID)
                .as("the cross-reference identifier is deliberately unrelated to the account filter")
                .isNotEqualTo(VALID_ACCOUNT_ID);
    }

    @Test
    @DisplayName(":697-699 a cross-reference miss short-circuits: the live FLG-ACCTFILTER-NOT-OK guard fires")
    void crossReferenceMissShortCircuitsBeforeEitherMasterIsRead() {
        stubCrossReferenceMissing();

        final AccountViewResult result = reenter(VALID_FILTER);

        verifyNoInteractions(this.accountRepository, this.customerRepository);
        assertThat(result.accountFound()).isFalse();
        assertThat(result.customerFound()).isFalse();
        assertThat(result.attributes().filterState())
                .as(":743 SET FLG-ACCTFILTER-NOT-OK TO TRUE")
                .isEqualTo(AccountFilterState.NOT_OK);
    }

    @Test
    @DisplayName(":727-735 the cross-reference read goes through the CXACAIX alternate-index path")
    void crossReferenceReadNamesTheAlternateIndexPathNotTheBaseCluster() {
        stubCompleteChain();

        reenter(VALID_FILTER);

        verify(this.fileStatusMapper).toException(STATUS_SUCCESS, XREF_ACCOUNT_PATH_NAME, OPERATION_READ);
        verify(this.fileStatusMapper).toException(STATUS_SUCCESS, ACCOUNT_FILE_NAME, OPERATION_READ);
        verify(this.fileStatusMapper).toException(STATUS_SUCCESS, CUSTOMER_FILE_NAME, OPERATION_READ);
        assertThat(XREF_ACCOUNT_PATH_NAME)
                .as("LIT-CARDXREFNAME-ACCT-PATH PIC X(8) VALUE 'CXACAIX ' at :192-193 - the blank is data")
                .hasSize(8)
                .isNotEqualTo("CCXREF  ");
    }

    @Test
    @DisplayName("a read-only view never takes the pessimistic write lock of findByIdForUpdate")
    void theViewNeverAcquiresAPessimisticWriteLock() {
        stubCompleteChain();

        reenter(VALID_FILTER);

        verify(this.accountRepository, never()).findByIdForUpdate(VALID_ACCOUNT_ID);
        verify(this.customerRepository, never()).findByIdForUpdate(XREF_CUSTOMER_ID);
    }

    // -----------------------------------------------------------------------------------------------------
    // The two dead early-exit guards at :704-706 and :713-715
    // -----------------------------------------------------------------------------------------------------

    @Test
    @DisplayName(":704-706 DEAD GUARD - an account-master miss still reads the customer master")
    void accountMasterMissStillAttemptsTheCustomerLookup() {
        stubCrossReferenceFound();
        stubAccountMissing();
        stubCustomerFound();

        final AccountViewResult result = reenter(VALID_FILTER);

        verify(this.customerRepository)
                .findById(XREF_CUSTOMER_ID);
        assertThat(result.accountFound())
                .as(":788 is not reached, so FOUND-ACCT-IN-MASTER stays '0'")
                .isFalse();
        assertThat(result.customerFound())
                .as(":838 IS reached, because the guard at :704 tests a literal that :792 never assigns")
                .isTrue();
    }

    @Test
    @DisplayName(":704-706 DEAD GUARD - the miss is reported through INPUT-ERROR and the 9300 diagnostic")
    void accountMasterMissIsStillReportedAsAnErrorCarryingThe9300Diagnostic() {
        stubCrossReferenceFound();
        stubAccountMissing();
        stubCustomerFound();

        final AccountViewResult result = reenter(VALID_FILTER);

        assertThat(result.inputError()).as(":790 SET INPUT-ERROR TO TRUE").isTrue();
        assertThat(result.attributes().filterState())
                .as(":791 SET FLG-ACCTFILTER-NOT-OK TO TRUE")
                .isEqualTo(AccountFilterState.NOT_OK);
        assertThat(result.screen().errorMessage())
                .as(":797-807 the STRING diagnostic, not the :131-132 condition-name literal")
                .isEqualTo(ACCOUNT_NOT_FOUND_DIAGNOSTIC)
                .isNotEqualTo(DID_NOT_FIND_ACCOUNT_IN_ACCTDAT);
    }

    @Test
    @DisplayName(":713-715 DEAD GUARD - a customer-master miss still falls through to the chain's tail")
    void customerMasterMissStillCompletesTheChainRatherThanExitingEarly() {
        stubCrossReferenceFound();
        stubAccountFound(new BigDecimal("0.00"));
        stubCustomerMissing();

        final AccountViewResult result = reenter(VALID_FILTER);

        assertThat(loggedMessages())
                .as(":716-719 is reached only by fall-through, so its presence proves the guard did not fire")
                .anySatisfy(message -> assertThat(message).contains("chain complete"));
        assertThat(result.accountFound()).isTrue();
        assertThat(result.customerFound()).isFalse();
        assertThat(result.screen().errorMessage())
                .as(":846-856 the STRING diagnostic, not the :133-134 condition-name literal")
                .isEqualTo(CUSTOMER_NOT_FOUND_DIAGNOSTIC)
                .isNotEqualTo(DID_NOT_FIND_CUSTOMER_IN_CUSTDAT);
    }

    @Test
    @DisplayName(":129-134 none of the three DID-NOT-FIND literals is ever assigned on any miss path")
    void theThreeDidNotFindLiteralsAreNeverEmitted() {
        stubCrossReferenceMissing();

        final AccountViewResult result = reenter(VALID_FILTER);

        assertThat(result.screen().errorMessage())
                .as(":696 the CARDXREF condition name is neither set nor tested; its IF is commented out")
                .isNotEqualTo(DID_NOT_FIND_ACCOUNT_IN_CARDXREF)
                .isNotEqualTo(DID_NOT_FIND_ACCOUNT_IN_ACCTDAT)
                .isNotEqualTo(DID_NOT_FIND_CUSTOMER_IN_CUSTDAT)
                .isEqualTo(XREF_NOT_FOUND_DIAGNOSTIC);
    }

    @Test
    @DisplayName("defect V1 - customer data renders with no account data, the combination the guards hid")
    void anAccountMissWithACustomerHitRendersCustomerFieldsAndNoAccountFields() {
        stubCrossReferenceFound();
        stubAccountMissing();
        stubCustomerFound();

        final AccountDto screen = reenter(VALID_FILTER).screen();

        assertThat(screen.customerId())
                .as(":493-523 the eighteen customer fields render on FOUND-CUST-IN-MASTER alone")
                .isEqualTo(RENDERED_CUSTOMER_RID);
        assertThat(screen.accountStatus())
                .as(":471-491 guarded on the ACCOUNT-RECORD that :788 never populated")
                .isNull();
        assertThat(screen.currentBalance()).isNull();
        assertThat(screen.creditLimit()).isNull();
    }

    // -----------------------------------------------------------------------------------------------------
    // 2210-EDIT-ACCOUNT and the diagnostic literals, :649-683 and :129-138
    // -----------------------------------------------------------------------------------------------------

    @Test
    @DisplayName(":671-673 the account-filter message carries TWO spaces between 'must' and 'be'")
    void invalidFilterCarriesTheDoubleSpacedAccountFilterMessage() {
        final AccountViewResult result = reenter("1234567890A");

        verifyNoInteractions(this.cardCrossReferenceRepository, this.accountRepository,
                this.customerRepository, this.fileStatusMapper);
        assertThat(result.screen().errorMessage()).isEqualTo(ACCOUNT_FILTER_MESSAGE);
        final int gap = result.screen().errorMessage().indexOf("must");
        assertThat(result.screen().errorMessage().charAt(gap + 4))
                .as("first of the two spaces at :673")
                .isEqualTo(' ');
        assertThat(result.screen().errorMessage().charAt(gap + 5))
                .as("second of the two spaces at :673 - collapsing it is a parity break")
                .isEqualTo(' ');
        assertThat(result.screen().errorMessage().charAt(gap + 6)).isEqualTo('b');
    }

    @Test
    @DisplayName(":747-757 the 9200 diagnostic keeps TWO spaces after 'Cross ref file.'")
    void crossReferenceMissDiagnosticKeepsTwoSpacesAfterCrossRefFile() {
        stubCrossReferenceMissing();

        final AccountViewResult result = reenter(VALID_FILTER);

        assertThat(result.screen().errorMessage())
                .isEqualTo(XREF_NOT_FOUND_DIAGNOSTIC)
                .hasSize(RETURN_MESSAGE_WIDTH)
                .contains("Cross ref file." + "  " + "Resp:")
                .contains(MESSAGE_REASON);
    }

    @Test
    @DisplayName(":797-807 the 9300 diagnostic has NO space after 'Acct Master file.'")
    void accountMasterMissDiagnosticHasNoSpaceAfterAcctMasterFile() {
        stubCrossReferenceFound();
        stubAccountMissing();
        stubCustomerFound();

        final AccountViewResult result = reenter(VALID_FILTER);

        assertThat(result.screen().errorMessage())
                .isEqualTo(ACCOUNT_NOT_FOUND_DIAGNOSTIC)
                .hasSize(RETURN_MESSAGE_WIDTH)
                .contains("Acct Master file." + "Resp:")
                .doesNotContain("Acct Master file." + " ");
    }

    @Test
    @DisplayName(":846-856 the 9400 diagnostic keeps a TRAILING space inside the literal")
    void customerMissDiagnosticKeepsTheTrailingSpaceInsideTheLiteral() {
        stubCrossReferenceFound();
        stubAccountFound(new BigDecimal("0.00"));
        stubCustomerMissing();

        final AccountViewResult result = reenter(VALID_FILTER);

        assertThat(result.screen().errorMessage())
                .isEqualTo(CUSTOMER_NOT_FOUND_DIAGNOSTIC)
                .hasSize(RETURN_MESSAGE_WIDTH)
                .contains("in customer master." + "Resp:" + " " + RENDERED_RESP_NOT_FOUND.strip());
    }

    @Test
    @DisplayName("severity Low - the reason label is ' Reas:' in 9200 and 9300 but ' REAS:' in 9400")
    void theReasonLabelCaseDivergesBetweenParagraph9200And9400() {
        assertThat(MESSAGE_REASON).isNotEqualTo(MESSAGE_REASON_UPPER);
        assertThat(XREF_NOT_FOUND_DIAGNOSTIC)
                .as(":755 mixed case")
                .contains(MESSAGE_REASON)
                .doesNotContain(MESSAGE_REASON_UPPER);
        assertThat(ACCOUNT_NOT_FOUND_DIAGNOSTIC)
                .as(":805 mixed case")
                .contains(MESSAGE_REASON)
                .doesNotContain(MESSAGE_REASON_UPPER);
        assertThat(CUSTOMER_NOT_FOUND_DIAGNOSTIC)
                .as(":854 upper case - the divergence is the source's and is preserved")
                .contains(MESSAGE_REASON_UPPER)
                .doesNotContain(MESSAGE_REASON);
    }

    @Test
    @DisplayName(":639-642 the cross-field edit overwrites the prompt with 'No input received', unlatched")
    void blankFilterIsReportedAsNoInputReceivedRatherThanThePromptMessage() {
        final AccountViewResult result = reenter(null);

        verifyNoInteractions(this.cardCrossReferenceRepository, this.accountRepository,
                this.customerRepository, this.fileStatusMapper);
        assertThat(result.screen().errorMessage())
                .as(":641 SET NO-SEARCH-CRITERIA-RECEIVED sits outside any WS-RETURN-MSG-OFF latch")
                .isEqualTo(NO_SEARCH_CRITERIA_MESSAGE)
                .isNotEqualTo(PROMPT_FOR_ACCOUNT_MESSAGE);
    }

    @Test
    @DisplayName(":759-768 WHEN OTHER on the cross-reference read emits the :86 composite, not a diagnostic")
    void crossReferenceIoErrorProducesTheCompositeFileErrorMessage() {
        final QueryTimeoutException cause = new QueryTimeoutException("read timed out");
        when(this.cardCrossReferenceRepository.findFirstByAccountIdOrderByCardNumberAsc(VALID_ACCOUNT_ID))
                .thenThrow(cause);
        stubIoError(XREF_ACCOUNT_PATH_NAME, cause);

        final AccountViewResult result = reenter(VALID_FILTER);

        assertThat(result.screen().errorMessage())
                .isEqualTo(compositeFileErrorMessage(XREF_ACCOUNT_PATH_NAME))
                .hasSize(RETURN_MESSAGE_WIDTH)
                .startsWith(FILE_ERROR_PREFIX)
                .contains(FILE_ERROR_RETURNED_RESP)
                .contains(FILE_ERROR_RESP2)
                .isNotEqualTo(XREF_NOT_FOUND_DIAGNOSTIC);
    }

    @Test
    @DisplayName(":809-818 WHEN OTHER on the account-master read emits the :86 composite")
    void accountMasterIoErrorProducesTheCompositeFileErrorMessage() {
        final QueryTimeoutException cause = new QueryTimeoutException("read timed out");
        stubCrossReferenceFound();
        when(this.accountRepository.findById(VALID_ACCOUNT_ID)).thenThrow(cause);
        stubIoError(ACCOUNT_FILE_NAME, cause);
        stubCustomerFound();

        final AccountViewResult result = reenter(VALID_FILTER);

        assertThat(result.screen().errorMessage())
                .isEqualTo(compositeFileErrorMessage(ACCOUNT_FILE_NAME))
                .contains(ACCOUNT_FILE_NAME.strip());
    }

    @Test
    @DisplayName(":858-867 WHEN OTHER on the customer-master read emits the :86 composite")
    void customerMasterIoErrorProducesTheCompositeFileErrorMessage() {
        final QueryTimeoutException cause = new QueryTimeoutException("read timed out");
        stubCrossReferenceFound();
        stubAccountFound(new BigDecimal("0.00"));
        when(this.customerRepository.findById(XREF_CUSTOMER_ID)).thenThrow(cause);
        stubIoError(CUSTOMER_FILE_NAME, cause);

        final AccountViewResult result = reenter(VALID_FILTER);

        assertThat(result.screen().errorMessage())
                .isEqualTo(compositeFileErrorMessage(CUSTOMER_FILE_NAME))
                .contains(CUSTOMER_FILE_NAME.strip());
    }

    @Test
    @DisplayName(":135-136 defect V2 - XREF-READ-ERROR is declared but its assignment is commented out")
    void theXrefReadErrorLiteralIsNeverEmitted() {
        final QueryTimeoutException cause = new QueryTimeoutException("read timed out");
        when(this.cardCrossReferenceRepository.findFirstByAccountIdOrderByCardNumberAsc(VALID_ACCOUNT_ID))
                .thenThrow(cause);
        stubIoError(XREF_ACCOUNT_PATH_NAME, cause);

        final AccountViewResult result = reenter(VALID_FILTER);

        assertThat(result.screen().errorMessage())
                .as(":767-768 both the WS-LONG-MSG assignment and the SEND-LONG-TEXT call are commented out")
                .isNotEqualTo(XREF_READ_ERROR_MESSAGE)
                .doesNotContain(XREF_READ_ERROR_MESSAGE);
    }

    @Test
    @DisplayName(":137-138 CODING-TO-BE-DONE carries four dots and is never assigned anywhere")
    void theCodingToBeDoneLiteralCarriesFourDotsAndIsNeverEmitted() {
        assertThat(CODING_TO_BE_DONE_MESSAGE)
                .as("'Looks Good' then four consecutive full stops then ' so far'")
                .isEqualTo("Looks Good.... so far")
                .contains("....")
                .doesNotContain(".....");
        stubCompleteChain();

        final AccountViewResult result = reenter(VALID_FILTER);

        assertThat(result.screen().errorMessage()).isNotEqualTo(CODING_TO_BE_DONE_MESSAGE);
    }

    @Test
    @DisplayName("severity Low - :125-128 the two SEARCHED-ACCT condition names share one literal")
    void theTwinSearchedAccountLiteralsAreIdenticalAndDistinctFromTheEditMessage() {
        assertThat(SEARCHED_ACCT_MESSAGE)
                .as("SEARCHED-ACCT-ZEROES and SEARCHED-ACCT-NOT-NUMERIC are indistinguishable by value")
                .isEqualTo("Account number must be a non zero 11 digit number")
                .isNotEqualTo(ACCOUNT_FILTER_MESSAGE)
                .doesNotContain("must  be");

        final AccountViewResult result = reenter("1234567890A");

        assertThat(result.screen().errorMessage())
                .as(":671-673 uses the OTHER literal - neither 88-level is ever set")
                .isEqualTo(ACCOUNT_FILTER_MESSAGE)
                .isNotEqualTo(SEARCHED_ACCT_MESSAGE);
    }

    // -----------------------------------------------------------------------------------------------------
    // WS-EDIT-ACCT-FLAG: a three-valued flag, :59-61, rendered by 1300-SETUP-SCREEN-ATTRS at :541-574
    // -----------------------------------------------------------------------------------------------------

    @Test
    @DisplayName(":59-61 the filter flag has exactly three states and BLANK is a SPACE, not a zero")
    void theFilterFlagHasExactlyThreeStatesMatchingTheThree88Levels() {
        assertThat(AccountFilterState.values())
                .as("FLG-ACCTFILTER-NOT-OK '0', FLG-ACCTFILTER-ISVALID '1', FLG-ACCTFILTER-BLANK ' '")
                .hasSize(3)
                .containsExactlyInAnyOrder(AccountFilterState.NOT_OK,
                        AccountFilterState.VALID,
                        AccountFilterState.BLANK);
        assertThat(AccountFilterState.BLANK)
                .as("the blank state is its own value - a two-valued boolean cannot model it")
                .isNotEqualTo(AccountFilterState.NOT_OK)
                .isNotEqualTo(AccountFilterState.VALID);
    }

    @Test
    @DisplayName(":561-566 the BLANK state alone emits the '*' marker in red")
    void aBlankFilterYieldsTheBlankStateWithTheAsteriskMarkerAndRedColour() {
        final AccountViewResult result = reenter("   ");

        assertThat(result.attributes().filterState()).isEqualTo(AccountFilterState.BLANK);
        assertThat(result.attributes().accountFilterMarker())
                .as(":563 MOVE '*' TO ACCTSIDO")
                .isEqualTo(ASTERISK);
        assertThat(result.attributes().accountFilterColour())
                .as(":564 MOVE DFHRED TO ACCTSIDC")
                .isEqualTo(COLOUR_RED);
        assertThat(result.screen().accountId())
                .as(":562 the screen field itself carries the marker")
                .isEqualTo(ASTERISK);
    }

    @Test
    @DisplayName(":555 the VALID state keeps the default colour and emits no marker")
    void aWellFormedFilterYieldsTheValidStateWithTheDefaultColour() {
        stubCompleteChain();

        final AccountViewResult result = reenter(VALID_FILTER);

        assertThat(result.attributes().filterState()).isEqualTo(AccountFilterState.VALID);
        assertThat(result.attributes().accountFilterColour()).isEqualTo(COLOUR_DEFAULT);
        assertThat(result.attributes().accountFilterMarker()).isNull();
        assertThat(result.screen().accountId()).isEqualTo(VALID_FILTER);
    }

    @Test
    @DisplayName(":557-559 the NOT_OK state is red but carries no marker")
    void anInvalidFilterYieldsTheNotOkStateWithRedAndNoMarker() {
        final AccountViewResult result = reenter("00000000000");

        assertThat(result.attributes().filterState())
                .as(":665 CC-ACCT-ID EQUAL ZEROES is rejected as well as non-numeric")
                .isEqualTo(AccountFilterState.NOT_OK);
        assertThat(result.attributes().accountFilterColour()).isEqualTo(COLOUR_RED);
        assertThat(result.attributes().accountFilterMarker()).isNull();
    }

    @Test
    @DisplayName(":546-552 every arm of the redundant decision requests the cursor on the account field")
    void everyFilterStateRequestsTheCursorOnTheAccountField() {
        stubCompleteChain();

        assertThat(reenter("   ").attributes().accountFilterCursorPosition())
                .isEqualTo(CURSOR_ON_ACCOUNT_FILTER);
        assertThat(reenter("1234567890A").attributes().accountFilterCursorPosition())
                .isEqualTo(CURSOR_ON_ACCOUNT_FILTER);
        assertThat(reenter(VALID_FILTER).attributes().accountFilterCursorPosition())
                .as("the IF and ELSE arms at :546-552 both move -1, which is a preserved redundancy")
                .isEqualTo(CURSOR_ON_ACCOUNT_FILTER);
    }

    @Test
    @DisplayName(":543 the filter attribute is always the unprotected, modified-data-tag byte")
    void theFilterAttributeIsAlwaysTheUnprotectedFsetByte() {
        final AccountViewResult result = reenter("   ");

        assertThat(result.attributes().accountFilterAttribute()).isEqualTo("DFHBMFSE");
    }

    // -----------------------------------------------------------------------------------------------------
    // 0000-MAIN-EXIT. is declared twice, at :408 and :411
    // -----------------------------------------------------------------------------------------------------

    @Test
    @DisplayName(":408 and :411 - both declarations of 0000-MAIN-EXIT map to their own distinct method")
    void bothDuplicatedMainExitLabelsMapToDistinctMethods() throws NoSuchMethodException {
        final Method atLine408 = AccountViewService.class.getDeclaredMethod("mainExit0000AtLine408");
        final Method atLine411 = AccountViewService.class.getDeclaredMethod("mainExit0000AtLine411");

        assertThat(atLine408)
                .as("consolidating the duplicate would break the paragraph map the coverage gate reads")
                .isNotEqualTo(atLine411);
        assertThat(atLine408.getName()).isNotEqualTo(atLine411.getName());
        assertThat(Modifier.isPrivate(atLine408.getModifiers())).isTrue();
        assertThat(Modifier.isPrivate(atLine411.getModifiers())).isTrue();
        assertThat(atLine408.getParameterCount()).isZero();
        assertThat(atLine411.getParameterCount()).isZero();
        assertThat(atLine408.getReturnType()).isEqualTo(void.class);
        assertThat(atLine411.getReturnType()).isEqualTo(void.class);
    }

    @Test
    @DisplayName("all 38 source paragraphs map 1:1 to 37 private methods, never consolidated")
    void everySourceParagraphMapsToItsOwnPrivateMethod() {
        final List<String> declared = Arrays.stream(AccountViewService.class.getDeclaredMethods())
                .filter(method -> Modifier.isPrivate(method.getModifiers()))
                .map(Method::getName)
                .toList();

        assertThat(PARAGRAPH_METHODS)
                .as("35 Area-A labels in COACTVWC plus the 2 that COPY 'CSSTRPFY' at :913 contributes")
                .hasSize(37);
        for (final String entry : PARAGRAPH_METHODS) {
            final int split = entry.indexOf(':');
            final String methodName = entry.substring(0, split);
            final String locator = entry.substring(split + 1);
            assertThat(declared)
                    .as("the paragraph at %s has no private method named %s", locator, methodName)
                    .contains(methodName);
        }
    }

    // -----------------------------------------------------------------------------------------------------
    // ABEND-ROUTINE at :916 and the CICS ONLINE abend contract at :935
    // -----------------------------------------------------------------------------------------------------

    @Test
    @DisplayName(":935 an unexpected runtime failure abends with the FOUR-character online code '9999'")
    void unexpectedRuntimeFailureAbendsWithTheOnlineFourCharacterCode() {
        final IllegalStateException fault = new IllegalStateException("simulated non-persistence fault");
        when(this.cardCrossReferenceRepository.findFirstByAccountIdOrderByCardNumberAsc(VALID_ACCOUNT_ID))
                .thenThrow(fault);

        assertThatExceptionOfType(FatalProcessingException.class)
                .isThrownBy(() -> reenter(VALID_FILTER))
                .satisfies(abend -> {
                    assertThat(abend.getAbendCode())
                            .as(":935 EXEC CICS ABEND ABCODE('9999') fills ABEND-CODE PIC X(4)")
                            .isEqualTo(ONLINE_ABEND_CODE)
                            .hasSize(FileStatusMapper.ABEND_CODE_WIDTH);
                    assertThat(abend.getAbendCulprit())
                            .as(":922 MOVE LIT-THISPGM TO ABEND-CULPRIT")
                            .isEqualTo(PROGRAM_NAME);
                    assertThat(abend.getAbendReason())
                            .as("ABEND-REASON PIC X(50) of app/cpy/CSMSG02Y.cpy, left as SPACES")
                            .hasSize(FileStatusMapper.ABEND_REASON_WIDTH)
                            .isBlank();
                    assertThat(abend.getAbendMessage())
                            .as(":918-920 the substituted default, because ABEND-MSG is assigned nowhere")
                            .isEqualTo(FatalProcessingException.DEFAULT_ABEND_MESSAGE);
                    assertThat(abend.getCause())
                            .as("Clause B - the root cause is preserved, never swallowed")
                            .isSameAs(fault);
                });
    }

    @Test
    @DisplayName("severity High - the online '9999' abend is NOT the batch 999 / RC 12 contract")
    void theOnlineAbendCodeIsNotTheBatchAbendContract() {
        assertThat(ONLINE_ABEND_CODE)
                .as(":935 a four-character CICS literal")
                .hasSize(FileStatusMapper.ABEND_CODE_WIDTH)
                .isEqualTo("9999")
                .isNotEqualTo(Integer.toString(FatalProcessingException.BATCH_ABEND_CODE));
        assertThat(FatalProcessingException.BATCH_ABEND_CODE)
                .as("the batch contract belongs to the eight ABCODE-declaring CB* programs, not to COACTVWC")
                .isEqualTo(999);
        assertThat(FatalProcessingException.BATCH_RETURN_CODE)
                .as("COACTVWC is a CICS online program and sets no job return code at all")
                .isEqualTo(12);
    }

    // -----------------------------------------------------------------------------------------------------
    // 0000-MAIN dispatch arms, :323-383
    // -----------------------------------------------------------------------------------------------------

    @Test
    @DisplayName(":328-352 PF03 transfers to the menu without reading any file")
    void pf03NavigatesToTheMenuWithoutReadingAnyFile() {
        final AccountViewResult result = this.service.processRequest(VALID_FILTER, PF03_KEY, EntryMode.REENTER);

        verifyNoInteractions(this.cardCrossReferenceRepository, this.accountRepository,
                this.customerRepository, this.fileStatusMapper);
        assertThat(result.kind()).isEqualTo(ResponseKind.TRANSFER);
        assertThat(result.attentionKey()).isEqualTo(AidKey.PFK03);
        assertThat(result.screen()).as("EXEC CICS XCTL sends no map").isNull();
        assertThat(result.navigation().toTransactionId()).isEqualTo(MENU_TRANSACTION_ID);
        assertThat(result.navigation().toProgram()).isEqualTo(MENU_PROGRAM);
        assertThat(result.navigation().fromTransactionId()).isEqualTo(TRANSACTION_ID);
        assertThat(result.navigation().fromProgram()).isEqualTo(PROGRAM_NAME);
        assertThat(result.navigation().mapset()).isEqualTo(THIS_MAPSET);
        assertThat(result.navigation().map()).isEqualTo(THIS_MAP);
        assertThat(result.navigation().regularUserContext())
                .as(":344 SET CDEMO-USRTYP-USER TO TRUE, asserted unconditionally")
                .isTrue();
        assertThat(result.nextEntryMode()).as(":345 SET CDEMO-PGM-ENTER TO TRUE").isEqualTo(EntryMode.ENTER);
    }

    @Test
    @DisplayName(":375-382 defect V6 - an unexpected context yields plain text and never abends")
    void unexpectedEntryModeYieldsPlainTextWithoutTouchingAnyRepository() {
        final AccountViewResult result = this.service.processRequest(VALID_FILTER, ENTER_KEY, null);

        verifyNoInteractions(this.cardCrossReferenceRepository, this.accountRepository,
                this.customerRepository, this.fileStatusMapper);
        assertThat(result.kind()).isEqualTo(ResponseKind.PLAIN_TEXT);
        assertThat(result.screen()).isNull();
        assertThat(result.plainText())
                .as(":880 EXEC CICS SEND FROM(WS-RETURN-MSG) LENGTH(LENGTH OF WS-RETURN-MSG)")
                .hasSize(RETURN_MESSAGE_WIDTH)
                .isEqualTo(UNEXPECTED_DATA_SCENARIO_MESSAGE
                        + " ".repeat(RETURN_MESSAGE_WIDTH - UNEXPECTED_DATA_SCENARIO_MESSAGE.length()));
    }

    @Test
    @DisplayName(":353-360 first entry prompts for input and reads nothing")
    void firstEntryPromptsForInputWithoutReadingAnyFile() {
        final AccountViewResult result =
                this.service.processRequest(null, ENTER_KEY, EntryMode.FIRST_ENTRY);

        verifyNoInteractions(this.cardCrossReferenceRepository, this.accountRepository,
                this.customerRepository, this.fileStatusMapper);
        assertThat(result.kind()).isEqualTo(ResponseKind.MAP);
        assertThat(result.screen().informationMessage())
                .as(":463 SET WS-PROMPT-FOR-INPUT TO TRUE")
                .isEqualTo(PROMPT_FOR_INPUT_MESSAGE);
        assertThat(result.screen().accountId())
                .as(":466-529 the else arm is not taken on first entry, so the field stays unset")
                .isNull();
        assertThat(result.inputError()).isFalse();
    }

    @Test
    @DisplayName(":306-314 an unsupported or absent EIBAID is silently coerced to ENTER")
    void anUnsupportedAttentionIdentifierIsCoercedToEnter() {
        stubCompleteChain();

        assertThat(this.service.processRequest(VALID_FILTER, null, EntryMode.REENTER).attentionKey())
                .as("CSSTRPFY matches no arm for a null EIBAID, and :313 substitutes CCARD-AID-ENTER")
                .isEqualTo(AidKey.ENTER);
        assertThat(this.service.processRequest(VALID_FILTER, "PFK07", EntryMode.REENTER).attentionKey())
                .as("PFK07 resolves but is neither ENTER nor PF03, so :306-314 coerces it as well")
                .isEqualTo(AidKey.ENTER);
    }

    // -----------------------------------------------------------------------------------------------------
    // Field contracts: app/cpy-bms/COACTVW.CPY and the decimal discipline
    // -----------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("severity Medium - the response carries the 37-field symbolic-map contract, not 36")
    void theResponseCarriesTheThirtySevenFieldSymbolicMapContract() {
        stubCompleteChain();

        final AccountDto screen = reenter(VALID_FILTER).screen();

        final RecordComponent[] components = screen.getClass().getRecordComponents();
        assertThat(components)
                .as("app/cpy-bms/COACTVW.CPY declares 37 input fields; the plan's 36 is wrong because "
                        + "ACCTSIDI at :60 uses the expanded PIC 99999999999 form")
                .hasSize(37);
        assertThat(AccountDto.FIELD_COUNT)
                .as("the declared budget and the actual component count must agree")
                .isEqualTo(components.length);
        assertThat(Arrays.stream(components).map(RecordComponent::getName).limit(6).toList())
                .as("the six-field BMS header sextuple, in symbolic-map order")
                .containsExactly("transactionName", "title01", "currentDate", "programName", "title02",
                        "currentTime");
        assertThat(Arrays.stream(components).map(RecordComponent::getType).distinct().toList())
                .as("every symbolic-map field is transported as text, including the numeric-PIC ACCTSIDI")
                .containsExactly(String.class);
    }

    /**
     * The declared width of {@code CUST-PHONE-NUM-1} and {@code CUST-PHONE-NUM-2}, read from
     * {@code app/cpy/CVCUS01Y.cpy}:15 and {@code :16}. Held as a constant so the two-byte loss below is
     * arithmetic over two source-declared widths rather than a hand-counted literal.
     */
    private static final int CUST_PHONE_NUM_WIDTH = 15;

    @Test
    @DisplayName(":517-518 the screen NARROWS X(15) telephone fields into X(13), dropping exactly two bytes")
    void theScreenNarrowsTheFifteenByteTelephoneFieldsIntoThirteenByteScreenFields() {
        // THE EXACT SOURCE DECISION, in four locators rather than a remembered width.
        //   CUST-PHONE-NUM-1 PIC X(15)  app/cpy/CVCUS01Y.cpy:15
        //   CUST-PHONE-NUM-2 PIC X(15)  app/cpy/CVCUS01Y.cpy:16
        //   ACSPHN1O         PIC X(13)  app/cpy-bms/COACTVW.CPY:428
        //   ACSPHN2O         PIC X(13)  app/cpy-bms/COACTVW.CPY:440
        // and the two MOVEs that join them, app/cbl/COACTVWC.cbl:517 and :518. An earlier revision of this
        // test cited ACSPHN1I - the INPUT twin at :204 - and put both MOVEs at :516, which is
        // CUST-ADDR-COUNTRY-CD TO ACSCTRYO. Both widths happened to be right, so the numbers passed while the
        // evidence pointed at the wrong field and the wrong line.
        stubCompleteChain();

        final AccountDto screen = reenter(VALID_FILTER).screen();

        assertThat(PHONE_1)
                .as("the fixture must BE the declared width, or the loss below is not 15-to-13")
                .hasSize(CUST_PHONE_NUM_WIDTH);
        assertThat(PHONE_2).hasSize(CUST_PHONE_NUM_WIDTH);
        assertThat(CUST_PHONE_NUM_WIDTH - AccountDto.PHONE_NUMBER_LENGTH)
                .as("X(15) into X(13) discards exactly two bytes")
                .isEqualTo(2);

        assertThat(screen.phoneNumber1())
                .as("ACSPHN1O PIC X(13) at app/cpy-bms/COACTVW.CPY:428 - the MOVE truncates, never widens")
                .isEqualTo(PHONE_1_DISPLAY)
                .hasSize(AccountDto.PHONE_NUMBER_LENGTH)
                .isEqualTo(PHONE_1.substring(0, AccountDto.PHONE_NUMBER_LENGTH));
        assertThat(screen.phoneNumber2())
                .as("ACSPHN2O PIC X(13) at app/cpy-bms/COACTVW.CPY:440")
                .isEqualTo(PHONE_2_DISPLAY)
                .hasSize(AccountDto.PHONE_NUMBER_LENGTH)
                .isEqualTo(PHONE_2.substring(0, AccountDto.PHONE_NUMBER_LENGTH));

        // COBOL alphanumeric MOVE left-justifies and truncates on the RIGHT. Asserting which bytes SURVIVE is
        // what distinguishes a correct truncation from a right-anchored one that would also be 13 long.
        assertThat(screen.phoneNumber1())
                .as("the LEADING thirteen bytes survive; the trailing two are the ones discarded")
                .isEqualTo(PHONE_1.substring(0, AccountDto.PHONE_NUMBER_LENGTH))
                .isNotEqualTo(PHONE_1.substring(CUST_PHONE_NUM_WIDTH - AccountDto.PHONE_NUMBER_LENGTH));
    }

    @Test
    @DisplayName(":481 money equality is decided by compareTo - scale 0 and scale 2 render identically")
    void renderedBalanceIsIdenticalForScaleZeroAndScaleTwoZero() {
        final BigDecimal scaleZero = new BigDecimal("0");
        final BigDecimal scaleTwo = new BigDecimal("0.00");
        assertThat(scaleZero.equals(scaleTwo))
                .as("BigDecimal.equals is scale-sensitive, which is exactly why it must not decide money")
                .isFalse();
        assertThat(scaleZero.compareTo(scaleTwo)).as("compareTo is the only correct comparison").isZero();
        stubCrossReferenceFound();
        when(this.accountRepository.findById(VALID_ACCOUNT_ID))
                .thenReturn(Optional.of(account(scaleZero)))
                .thenReturn(Optional.of(account(scaleTwo)));
        stubSuccessfulRead(ACCOUNT_FILE_NAME);
        stubCustomerFound();

        final String fromScaleZero = reenter(VALID_FILTER).screen().currentBalance();
        final String fromScaleTwo = reenter(VALID_FILTER).screen().currentBalance();

        assertThat(fromScaleZero)
                .as("the +ZZZ,ZZZ,ZZZ.99 mask of app/cpy-bms/COACTVW.CPY suppresses every leading zero")
                .isEqualTo(fromScaleTwo)
                .isEqualTo("+" + " ".repeat(11) + ".00")
                .hasSize(AccountDto.MONEY_DISPLAY_LENGTH);
    }

    @Test
    @DisplayName("the money mask normalises scale to two, half-even, with no floating-point step")
    void renderedAmountIsNormalisedToTwoDecimalPlaces() {
        stubCrossReferenceFound();
        stubAccountFound(new BigDecimal("1234.5"));
        stubCustomerFound();

        final AccountDto screen = reenter(VALID_FILTER).screen();

        assertThat(screen.currentBalance())
                .isEqualTo("+" + " ".repeat(6) + "1,234.50")
                .hasSize(AccountDto.MONEY_DISPLAY_LENGTH);
        assertThat(AccountDto.MONEY_ROUNDING)
                .as("ACCT-CURR-BAL PIC S9(10)V99 rounds half-even, never half-up")
                .isEqualTo(RoundingMode.HALF_EVEN);
        assertThat(AccountDto.MONEY_SCALE).isEqualTo(2);
    }

    @Test
    @DisplayName("no field and no response component uses float or double")
    void noFieldOrResponseComponentUsesFloatingPointArithmetic() {
        for (final Field field : AccountViewService.class.getDeclaredFields()) {
            assertThat(field.getType())
                    .as("field %s must not be a floating-point type", field.getName())
                    .isNotIn(float.class, double.class, Float.class, Double.class);
        }
        for (final RecordComponent component : AccountDto.class.getRecordComponents()) {
            assertThat(component.getType())
                    .as("response component %s must not be a floating-point type", component.getName())
                    .isNotIn(float.class, double.class, Float.class, Double.class);
        }
    }

    // -----------------------------------------------------------------------------------------------------
    // Clause A, security by default: hostile and boundary input
    // -----------------------------------------------------------------------------------------------------

    @Test
    @DisplayName(":652-661 a null filter is the blank path, not a null-pointer failure")
    void aNullFilterIsTreatedAsAbsentRatherThanFailing() {
        final AccountViewResult result = reenter(null);

        assertThat(result.attributes().filterState()).isEqualTo(AccountFilterState.BLANK);
        assertThat(result.inputError()).isTrue();
        assertThat(result.kind()).isEqualTo(ResponseKind.MAP);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "           ", "*", "*   "})
    @DisplayName(":627-633 and :652-661 blank, all-blank and the single-asterisk marker are all absent")
    void absentFilterVariantsAllYieldTheBlankState(final String filter) {
        final AccountViewResult result = reenter(filter);

        verifyNoInteractions(this.cardCrossReferenceRepository, this.accountRepository,
                this.customerRepository, this.fileStatusMapper);
        assertThat(result.attributes().filterState()).isEqualTo(AccountFilterState.BLANK);
        assertThat(result.screen().errorMessage()).isEqualTo(NO_SEARCH_CRITERIA_MESSAGE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"00000000000", "0", "1234567890", "1234567890A", "$1234567890", "1,234,567,8",
            "***", "0000000000 ", "-0000000001"})
    @DisplayName(":664-676 every malformed filter is rejected with the two-space message and a zeroed id")
    void malformedFilterVariantsAllYieldTheNotOkState(final String filter) {
        final AccountViewResult result = reenter(filter);

        verifyNoInteractions(this.cardCrossReferenceRepository, this.accountRepository,
                this.customerRepository, this.fileStatusMapper);
        assertThat(result.attributes().filterState()).isEqualTo(AccountFilterState.NOT_OK);
        assertThat(result.inputError()).isTrue();
        assertThat(result.screen().errorMessage()).isEqualTo(ACCOUNT_FILTER_MESSAGE);
    }

    @Test
    @DisplayName("a currency symbol or a thousands separator is rejected: this is a plain-numeric field")
    void currencyFormattingIsRejectedBecauseTheFieldIsNotACurrencyField() {
        assertThat(reenter("$1,234,5678").screen().errorMessage())
                .as("COACTVWC uses no currency-aware conversion anywhere; only COTRN02C does")
                .isEqualTo(ACCOUNT_FILTER_MESSAGE);
        assertThat(reenter("12,345,6789").attributes().filterState())
                .isEqualTo(AccountFilterState.NOT_OK);
        verifyNoInteractions(this.cardCrossReferenceRepository, this.accountRepository,
                this.customerRepository, this.fileStatusMapper);
    }

    @Test
    @DisplayName(":653 a LOW-VALUES filter is absent, exactly as EQUAL LOW-VALUES OR SPACES specifies")
    void aLowValuesFilterIsTreatedAsAbsent() {
        final String lowValues = String.valueOf((char) 0).repeat(ACCOUNT_ID_WIDTH);

        final AccountViewResult result = reenter(lowValues);

        verifyNoInteractions(this.cardCrossReferenceRepository, this.accountRepository,
                this.customerRepository, this.fileStatusMapper);
        assertThat(result.attributes().filterState()).isEqualTo(AccountFilterState.BLANK);
        assertThat(result.screen().errorMessage()).isEqualTo(NO_SEARCH_CRITERIA_MESSAGE);
    }

    @Test
    @DisplayName(":665 an embedded control character fails the IS NOT NUMERIC test")
    void anEmbeddedControlCharacterIsRejected() {
        final String withControlCharacter = "1234" + (char) 1 + "567890";

        final AccountViewResult result = reenter(withControlCharacter);

        verifyNoInteractions(this.cardCrossReferenceRepository, this.accountRepository,
                this.customerRepository, this.fileStatusMapper);
        assertThat(withControlCharacter).hasSize(ACCOUNT_ID_WIDTH);
        assertThat(result.attributes().filterState()).isEqualTo(AccountFilterState.NOT_OK);
        assertThat(result.screen().errorMessage()).isEqualTo(ACCOUNT_FILTER_MESSAGE);
    }

    @Test
    @DisplayName(":632 an over-long filter is TRUNCATED to X(11) by the MOVE and then accepted")
    void anOverLongFilterIsTruncatedToElevenCharactersAndAccepted() {
        when(this.cardCrossReferenceRepository.findFirstByAccountIdOrderByCardNumberAsc(12345678901L))
                .thenReturn(Optional.empty());
        stubRecordNotFound(XREF_ACCOUNT_PATH_NAME, "CARD-XREF-RECORD");

        final AccountViewResult result = reenter("123456789012");

        verify(this.cardCrossReferenceRepository).findFirstByAccountIdOrderByCardNumberAsc(12345678901L);
        assertThat(result.screen().accountId())
                .as("the twelfth character is discarded by the X(11) MOVE, not reported as an error")
                .isEqualTo("12345678901");
    }

    // -----------------------------------------------------------------------------------------------------
    // viewAccount: the REST entry point's failure contract, type AND message AND cause
    // -----------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("viewAccount reports an absent filter as a BLANK validation failure")
    void viewAccountReportsAnAbsentFilterAsABlankValidationFailure() {
        assertThatExceptionOfType(ValidationException.class)
                .isThrownBy(() -> this.service.viewAccount(null))
                .satisfies(failure -> {
                    assertThat(failure.getMessage()).isEqualTo(NO_SEARCH_CRITERIA_MESSAGE);
                    assertThat(failure.getFailureKind()).isEqualTo(ValidationException.FailureKind.BLANK);
                    assertThat(failure.hasFieldName()).isTrue();
                    assertThat(failure.getFieldName()).isEqualTo("accountFilter");
                    assertThat(failure.getCause()).as("a validation failure has no root cause").isNull();
                });
    }

    @Test
    @DisplayName("viewAccount reports a malformed filter as an INVALID validation failure")
    void viewAccountReportsAMalformedFilterAsAnInvalidValidationFailure() {
        assertThatExceptionOfType(ValidationException.class)
                .isThrownBy(() -> this.service.viewAccount("1234567890A"))
                .satisfies(failure -> {
                    assertThat(failure.getMessage()).isEqualTo(ACCOUNT_FILTER_MESSAGE);
                    assertThat(failure.getFailureKind()).isEqualTo(ValidationException.FailureKind.INVALID);
                    assertThat(failure.getFieldName()).isEqualTo("accountFilter");
                });
    }

    @Test
    @DisplayName("viewAccount surfaces the retained typed failure ahead of the validation flag")
    void viewAccountPropagatesTheTypedFailureRatherThanTheValidationError() {
        stubCrossReferenceMissing();

        assertThatExceptionOfType(RecordNotFoundException.class)
                .isThrownBy(() -> this.service.viewAccount(VALID_FILTER))
                .satisfies(failure -> {
                    assertThat(failure.getMessage()).isEqualTo("record not found in " + "CXACAIX");
                    assertThat(failure.recordType()).contains("CARD-XREF-RECORD");
                    assertThat(failure.recordKey())
                            .as("Clause D - no record key that could carry a card number is retained")
                            .contains("redacted");
                });
    }

    @Test
    @DisplayName("viewAccount returns the projected screen on a clean chain")
    void viewAccountReturnsTheProjectedScreenOnACleanChain() {
        stubCompleteChain();

        final AccountDto screen = this.service.viewAccount(VALID_FILTER);

        assertThat(screen.accountId()).isEqualTo(VALID_FILTER);
        assertThat(screen.accountStatus()).as(":472 MOVE ACCT-ACTIVE-STATUS TO ACSTTUSO").isEqualTo("Y");
        assertThat(screen.customerId()).isEqualTo(RENDERED_CUSTOMER_RID);
        assertThat(screen.errorMessage()).isEmpty();
    }

    @Test
    @DisplayName("a 9x status is mapped through the FOUR-argument overload so the root cause survives")
    void theStatusMapperReceivesTheInfrastructureCauseOnTheFourArgumentOverload() {
        final QueryTimeoutException cause = new QueryTimeoutException("read timed out");
        when(this.cardCrossReferenceRepository.findFirstByAccountIdOrderByCardNumberAsc(VALID_ACCOUNT_ID))
                .thenThrow(cause);
        final CardDemoException verdict = stubIoError(XREF_ACCOUNT_PATH_NAME, cause);

        assertThatExceptionOfType(CardDemoException.class)
                .isThrownBy(() -> this.service.viewAccount(VALID_FILTER))
                .satisfies(failure -> {
                    assertThat(failure).isSameAs(verdict);
                    assertThat(failure.getMessage()).isEqualTo("physical I/O failure on " + "CXACAIX");
                    assertThat(failure.getCause()).isSameAs(cause);
                });
        verify(this.fileStatusMapper)
                .toException(STATUS_IO_ERROR, XREF_ACCOUNT_PATH_NAME, OPERATION_READ, cause);
        verify(this.fileStatusMapper, never())
                .toException(STATUS_IO_ERROR, XREF_ACCOUNT_PATH_NAME, OPERATION_READ);
    }

    // -----------------------------------------------------------------------------------------------------
    // Clause A determinism, Clause B state discipline, Clause D data protection
    // -----------------------------------------------------------------------------------------------------

    @Test
    @DisplayName(":434 and :441 both header fields derive from the injected clock, never from the wall clock")
    void theTwoHeaderFieldsDeriveFromTheInjectedClock() {
        final AccountDto screen =
                this.service.processRequest(null, ENTER_KEY, EntryMode.FIRST_ENTRY).screen();

        assertThat(screen.currentDate())
                .as("CURDATE X(8) rendered MM/dd/yy from " + FIXED_INSTANT)
                .isEqualTo("07/19/22")
                .hasSize(AccountDto.CURRENT_DATE_LENGTH);
        assertThat(screen.currentTime())
                .as("CURTIME X(8) rendered HH:mm:ss from the same instant")
                .isEqualTo("23:12:32")
                .hasSize(AccountDto.CURRENT_TIME_LENGTH);
        assertThat(screen.transactionName()).isEqualTo(TRANSACTION_ID);
        assertThat(screen.programName()).isEqualTo(PROGRAM_NAME);
        assertThat(screen.title01()).hasSize(AccountDto.TITLE_LENGTH);
        assertThat(screen.title02()).hasSize(AccountDto.TITLE_LENGTH);
    }

    @Test
    @DisplayName("the same request yields a byte-identical response on every call")
    void theSameRequestProducesAnIdenticalResponseOnEveryCall() {
        stubCompleteChain();

        final AccountViewResult first = reenter(VALID_FILTER);
        final AccountViewResult second = reenter(VALID_FILTER);

        assertThat(second)
                .as("no wall clock, no default locale, no default zone and no randomness is involved")
                .isEqualTo(first);
    }

    @Test
    @DisplayName("Clause B - every field of the singleton is final, so no request can corrupt another")
    void theBeanHoldsNoMutableState() {
        for (final Field field : AccountViewService.class.getDeclaredFields()) {
            if (field.isSynthetic()) {
                continue;
            }
            assertThat(Modifier.isFinal(field.getModifiers()))
                    .as("field %s must be final: the WORKING-STORAGE flags live on a per-request work area, "
                            + "never on the bean", field.getName())
                    .isTrue();
            assertThat(Modifier.isPublic(field.getModifiers()))
                    .as("field %s must not be public", field.getName())
                    .isFalse();
        }
    }

    @Test
    @DisplayName("Clause B - no state survives from a rejected request into the next accepted one")
    void noStateSurvivesFromOneCallIntoTheNext() {
        stubCompleteChain();

        final AccountViewResult rejected = reenter(null);
        final AccountViewResult accepted = reenter(VALID_FILTER);

        assertThat(rejected.screen().errorMessage()).isEqualTo(NO_SEARCH_CRITERIA_MESSAGE);
        assertThat(rejected.inputError()).isTrue();
        assertThat(accepted.screen().errorMessage())
                .as(":278 SET WS-RETURN-MSG-OFF is re-applied per request, so nothing leaks forward")
                .isEmpty();
        assertThat(accepted.inputError()).isFalse();
    }

    @Test
    @DisplayName("severity High - no protected field of the 500-byte customer record reaches the log")
    void noProtectedCustomerFieldReachesTheLog() {
        stubCompleteChain();

        reenter(VALID_FILTER);

        final List<String> messages = loggedMessages();
        assertThat(messages).as("the bean must log something, or the assertion proves nothing").isNotEmpty();
        for (final String protectedValue : PROTECTED_VALUES) {
            assertThat(messages)
                    .as("a protected field of app/cpy/CVCUS01Y.cpy escaped into the log")
                    .allSatisfy(message -> assertThat(message).doesNotContain(protectedValue));
        }
        assertThat(messages)
                .as(":716-719 logs XREF-CARD-NUM only through maskTail")
                .anySatisfy(message -> assertThat(message).contains("*".repeat(12) + "6666"));
    }

    @Test
    @DisplayName("severity High - no protected field reaches the response object's toString")
    void theResponseToStringOmitsEveryProtectedField() {
        stubCompleteChain();

        final AccountDto screen = reenter(VALID_FILTER).screen();

        assertThat(screen.customerSsn())
                .as(":506 the field is on the wire, formatted, and therefore must be omitted from toString")
                .isEqualTo(SSN_DISPLAY);
        assertThat(screen.phoneNumber1())
                .as(":516 the field is on the wire too, so its absence from toString is what is under test")
                .isNotNull();
        for (final String protectedValue : PROTECTED_VALUES) {
            assertThat(screen.toString())
                    .as("a protected field escaped through toString")
                    .doesNotContain(protectedValue);
        }
    }

    @Test
    @DisplayName("severity High - no diagnostic message interpolates a protected field")
    void noDiagnosticMessageInterpolatesAProtectedField() {
        stubCrossReferenceFound();
        stubAccountFound(new BigDecimal("0.00"));
        stubCustomerMissing();

        final String message = reenter(VALID_FILTER).screen().errorMessage();

        for (final String protectedValue : PROTECTED_VALUES) {
            assertThat(message)
                    .as(":846-856 interpolates only the record identification field, never a record field")
                    .doesNotContain(protectedValue);
        }
        assertThat(message).contains(RENDERED_CUSTOMER_RID);
    }

    @Test
    @DisplayName("Clause D - identifiers are bound as typed parameters, never concatenated into text")
    void repositoryReadsBindTypedIdentifiersRatherThanConcatenatedText() throws NoSuchMethodException {
        stubCompleteChain();

        reenter(VALID_FILTER);

        verify(this.cardCrossReferenceRepository).findFirstByAccountIdOrderByCardNumberAsc(VALID_ACCOUNT_ID);
        verify(this.accountRepository).findById(VALID_ACCOUNT_ID);
        verify(this.customerRepository).findById(XREF_CUSTOMER_ID);
        final Method alternateIndexFinder = CardCrossReferenceRepository.class
                .getMethod("findFirstByAccountIdOrderByCardNumberAsc", Long.class);
        assertThat(alternateIndexFinder.getParameterTypes())
                .as("a typed Long parameter cannot smuggle a fragment of a query")
                .containsExactly(Long.class);
    }
}
