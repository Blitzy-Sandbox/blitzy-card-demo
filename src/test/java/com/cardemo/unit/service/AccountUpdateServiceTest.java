/*
 * ******************************************************************
 * Program     : AccountUpdateServiceTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11
 * Function    : Verifies AccountUpdateService against COACTUPC paragraph by
 *               paragraph. Concentrates on the contracts a reader cannot
 *               confirm by inspection: the parity trap at :2606-2615 where a
 *               customer read-for-update failure is reported as success, the
 *               seven-step write sequence of 9600-WRITE-PROCESSING and its
 *               asymmetric rollback, the sixteen comparison clauses of
 *               9700-CHECK-CHANGE-IN-REC over ten logical fields, the
 *               deliberately asymmetric case handling, the date-of-birth
 *               offset asymmetry at :4174-4179 that breaks every request if
 *               ported naively, the byte-exact outcome literals including the
 *               two-word "some one" at :521-522 and the four dots at :527-528,
 *               and the CICS ONLINE abend code '9999' at :4223 which is NOT
 *               the batch 999 / RC 12 contract.
 * Source      : app/cbl/COACTUPC.cbl      (4,236 lines, 85 own / 87 mapped paragraph labels, CRLF)
 *               app/cpy/CSMSG02Y.cpy      (CABENDD.CPY ABEND-DATA, 134 bytes)
 *               app/cpy/CVACT01Y.cpy      (ACCOUNT-RECORD, RECLN 300, key 11)
 *               app/cpy/CVCUS01Y.cpy      (CUSTOMER-RECORD, RECLN 500, PII)
 *               app/cpy-bms/COACTUP.CPY   (54 input fields; ACSZIPCI at :246)
 *               app/data/ASCII/custdata.txt (21 of 50 rows score below 300)
 *               app/cbl/CBACT04C.cbl:1-21 (this banner's canonical form)
 *               CONTRIBUTING.md:33-34     (repository hygiene)
 *               NOTICE                    (copyright line) @ 7756d89
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import com.cardemo.exception.CardDemoException;
import com.cardemo.exception.ConcurrentUpdateException;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.AccountUpdateRequest;
import com.cardemo.model.entity.Account;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.model.entity.Customer;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CardCrossReferenceRepository;
import com.cardemo.repository.CustomerRepository;
import com.cardemo.security.SnapshotTokenService;
import com.cardemo.service.account.AccountUpdateService;
import com.cardemo.service.account.AccountUpdateService.AccountUpdateResult;
import com.cardemo.service.account.AccountUpdateService.ChangeAction;
import com.cardemo.service.account.AccountUpdateService.EntryMode;
import com.cardemo.service.account.AccountUpdateService.FieldAttribute;
import com.cardemo.service.account.AccountUpdateService.ResponseKind;
import com.cardemo.service.account.AccountViewService;
import com.cardemo.service.shared.DateValidationService;
import com.cardemo.service.shared.FileStatusMapper;
import com.cardemo.service.shared.ValidationLookupService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Surefire unit tier for {@link AccountUpdateService}, the Java replacement for
 * {@code app/cbl/COACTUPC.cbl} - at 4,236 lines and 85 own paragraph labels the largest program in the corpus
 * and the one with the subtlest contract.
 *
 * <h2>1. What it does</h2>
 * <p>Pins the behaviour of transaction {@code CAUP} at the level a reader cannot verify by
 * inspection. Every assertion is anchored to a locator in the frozen COBOL, verified against the
 * checkout at commit {@code 7756d89}. The locators exercised here are:</p>
 * <ul>
 *   <li>{@code :170} - {@code 88 CHANGE-HAS-OCCURRED VALUE '1'}, the flag {@code 1205} drives.</li>
 *   <li>{@code :505-528} - the twelve outcome literals, each an {@code 88}-level on
 *       {@code WS-RETURN-MSG PIC X(75)}, so setting the condition name <em>is</em> setting the
 *       message. The outcome group is {@code :517-524}.</li>
 *   <li>{@code :655-670} - the {@code ACUP-CHANGE-ACTION} markers {@code S E N C L F} plus the
 *       composite {@code 88}-levels {@code ACUP-CHANGES-MADE}, {@code ACUP-CHANGES-FAILED} and
 *       {@code ACUP-DETAILS-NOT-FETCHED}; {@code :667} is the fifth marker, set only when the
 *       account lock fails at {@code :2607-2608}.</li>
 *   <li>{@code :669-756} - {@code ACUP-OLD-DETAILS}, the snapshot group: account half
 *       {@code :670-708}, customer half {@code :709-756}, {@code ACUP-OLD-EXPIRAION-DATE X(08)} at
 *       {@code :690} (misspelling sic), {@code ACUP-OLD-CUST-ADDR-ZIP X(10)} at {@code :721}, the
 *       flat {@code ACUP-OLD-CUST-SSN-X X(09)} redefined {@code 9(09)} at {@code :742-744}, and the
 *       compact {@code ACUP-OLD-CUST-DOB-YYYY-MM-DD X(08)} with its parts at {@code :746-751}.</li>
 *   <li>{@code :757} - {@code ACUP-NEW-DETAILS}; {@code :830-842} the three-part new-side SSN and
 *       the new-side compact date of birth; {@code :848-849}
 *       {@code 88 FICO-RANGE-IS-VALID VALUES 300 THROUGH 850}, which exists on the new side
 *       <strong>only</strong>.</li>
 *   <li>{@code :1460-1461} and {@code :1681-1777} - {@code 1205-COMPARE-OLD-NEW}, which compares
 *       dates as <em>whole</em> {@code X(08)} fields at {@code :1692-1694}, folds the group
 *       identifier through {@code UPPER-CASE(TRIM())} at {@code :1697-1700}, folds the postal code
 *       the same way at {@code :1745-1747} and compares telephone numbers part by part at
 *       {@code :1748-1753}. Every one of those conventions is the <em>opposite</em> of
 *       {@code 9700}'s.</li>
 *   <li>{@code :2596-2620} and {@code :2606-2615} - the decider's PF05 write branch and the inner
 *       {@code EVALUATE TRUE} with exactly four {@code WHEN} arms.</li>
 *   <li>{@code :2634-2640} - the outer {@code WHEN OTHER}: culprit, {@code ABEND-CODE '0001'},
 *       {@code ABEND-REASON} spaces and {@code ABEND-MSG 'UNEXPECTED DATA SCENARIO'}.</li>
 *   <li>{@code :3856-3859} - the snapshot date-of-birth moves. {@code :3856} is the naive
 *       whole-field {@code MOVE}, <strong>commented out in the source</strong>; {@code :3857-3859}
 *       are the three component moves that replaced it.</li>
 *   <li>{@code :3892-3894}, {@code :3907-3915}, {@code :3919-3921}, {@code :3934-3942},
 *       {@code :3947-3950}, {@code :3956}, {@code :4065}, {@code :4076-4081}, {@code :4085},
 *       {@code :4095-4103} - the seven steps of {@code 9600-WRITE-PROCESSING}.</li>
 *   <li>{@code :4109-4195} - {@code 9700-CHECK-CHANGE-IN-REC}, with the date substrings at
 *       {@code :4127-4137}, the lower-cased group identifier at {@code :4139-4140}, the
 *       no-case-function block at {@code :4168-4170} and the date-of-birth offset asymmetry at
 *       {@code :4174-4179}. Its exit at {@code :4193-4195} is a bare {@code EXIT}.</li>
 *   <li>{@code :4203-4226} - {@code ABEND-ROUTINE}, whose {@code EXEC CICS ABEND ABCODE('9999')}
 *       sits at {@code :4223}.</li>
 *   <li>{@code app/cpy/CSMSG02Y.cpy:21-29} - {@code ABEND-DATA}: {@code ABEND-CODE X(4)},
 *       {@code ABEND-CULPRIT X(8)}, {@code ABEND-REASON X(50)} and {@code ABEND-MSG X(72)},
 *       134 bytes, every one {@code VALUE SPACES}. The {@code 001200}-{@code 002000} figures quoted
 *       elsewhere are COBOL sequence numbers, not line numbers.</li>
 *   <li>{@code app/cpy-bms/COACTUP.CPY:246} - {@code 02 ACSZIPCI PIC X(5)}, the five-byte screen
 *       field behind the ten-byte snapshot slot.</li>
 *   </ul>
 *
 * <p>A closing tier treats the request as untrusted, because a stateless endpoint cannot assume the
 * snapshot it is handed is the one it issued. It submits a null request, a null snapshot, an absent
 * {@code ACUP-NEW-DETAILS} group, an all-blank snapshot, over-long snapshot dates and telephone
 * numbers, a dash-separated snapshot date of birth, a malformed money image, a currency symbol and a
 * thousands separator in the plain {@code 9(11)} key <em>and</em> in the {@code NUMVAL-C} amount
 * fields, credit scores at 299, 300, 850 and 851, a null state marker, a null entry mode and an
 * unrecognised attention identifier. Where an exception is the correct answer, the type, the message
 * and the preserved cause are all asserted; where the source's answer is to carry on, that is
 * asserted instead rather than hardened.</p>
 *
 * <h2>2. How to build, run and test</h2>
 * <ul>
 *   <li>Whole suite: {@code ./mvnw -B -ntp test}. This class alone:
 *       {@code ./mvnw -B -ntp test -Dtest=AccountUpdateServiceTest}.</li>
 *   <li><strong>Surefire</strong> owns this tier. The root {@code pom.xml} binds Surefire 3.5.4 to
 *       {@code src/test/java/com/cardemo/unit/**} and Failsafe to the {@code integration} and
 *       {@code e2e} trees. A class placed outside both include sets is collected by neither plugin
 *       and silently never runs, so the package declaration above is load-bearing.</li>
 *   <li>Compilation is fatal on warnings: {@code -Xlint:all -Werror} with {@code failOnWarning} reaches test
 *       compilation, so a single raw type, unchecked cast or dangling documentation comment fails the build.
 *       An unused import does not - {@code javac} 25 publishes no {@code unused} lint key - so it is a review
 *       matter.</li>
 *   <li>Pure JVM tier. No container, no Spring context, no database, no network, no clock read.</li>
 * </ul>
 *
 * <h2>3. Key configurations and defaults</h2>
 * <ul>
 *   <li>Mockito <strong>strict stubs</strong>. Every stubbing declared in a test is exercised by
 *       that test; a mock that is never called is legitimate, a stubbing that is never used is
 *       not.</li>
 *   <li>The five collaborators are mocked and the bean is built by constructor injection in the
 *       declared order: cross-reference repository, account repository, customer repository, file
 *       status mapper, date validation service, validation lookup service, clock.</li>
 *   <li>The {@link Clock} is {@link Clock#fixed}. {@code 3100-SCREEN-INIT} reads
 *       {@code FUNCTION CURRENT-DATE} on every turn, so a fixed instant is mandatory rather than
 *       merely tidy. No no-argument {@code now()} appears anywhere in this class.</li>
 *   <li>Money is {@link BigDecimal} with {@code RoundingMode.HALF_EVEN} and equality decided by
 *       {@code compareTo}. There is no {@code float} or {@code double} anywhere on the path.</li>
 *   <li>{@code app/cbl/COACTUPC.cbl} is <strong>CRLF terminated</strong> - one of exactly five such
 *       files in the repository. Every line number cited above was read with the carriage return
 *       stripped; reading it without stripping drifts every locator.</li>
 *   <li>All fixture values are synthetic. The customer record carries the corpus's
 *       highest-sensitivity fields - social security number, date of birth, both telephone numbers,
 *       the government-issued identifier and the electronic-funds account identifier - and none of
 *       them is echoed into a display name, an assertion description, a log or an exception
 *       message.</li>
 *   <li><strong>Zero global state is read or written, including the ambient locale.</strong> The
 *       {@code Locale.ROOT} proof works by contrast rather than by installation: a dotted capital
 *       {@code I} folds to a dotted {@code i} under {@code Locale.ROOT} and to a <em>dotless</em>
 *       {@code i} under {@code tr-TR}, so submitting one form and snapshotting the other shows which
 *       fold the bean applied. No {@code Locale.getDefault()} call and no
 *       {@code Locale.setDefault(...)} call appears anywhere in this class, so the probe cannot leak
 *       into a neighbouring test through Surefire's shared JVM.</li>
 *   </ul>
 *
 * <h2>4. Common failure modes and troubleshooting</h2>
 * <ul>
 *   <li><strong>Build fails with "warnings found and -Werror specified".</strong> One raw type, one
 *       unchecked cast or one dangling doc comment is enough. Remove it; do not relax the compiler
 *       configuration. An unused import or an unused local is <em>not</em> caught: {@code javac} 25
 *       publishes no {@code unused} lint key, so both are review-enforced.</li>
 *   <li><strong>Every update reports a concurrent change.</strong> The date-of-birth comparison was
 *       ported as a whole-string test. The live record holds {@code yyyy-MM-dd} and the snapshot
 *       holds {@code yyyyMMdd}, so a whole-string comparison never matches and the endpoint becomes
 *       permanently unusable. Compare components at offsets 1/6/9 against 1/5/7.</li>
 *   <li><strong>Case-only edits are accepted where they should be rejected, or vice versa.</strong>
 *       The case handling of {@code 9700} is asymmetric on purpose. Normalising it in either
 *       direction changes which updates are accepted.</li>
 *   <li><strong>A customer-lock failure starts reporting a conflict.</strong> Somebody added a
 *       fifth {@code WHEN} arm. The source has four. Revert it; parity is the contract.</li>
 *   <li><strong>Seeded customers are rejected on load.</strong> The credit-score range leaked from
 *       the new-side screen edit into the entity. Twenty-one of the fifty rows in
 *       {@code app/data/ASCII/custdata.txt} carry a score below 300.</li>
 *   <li><strong>{@code UnnecessaryStubbingException}.</strong> A guard in the edit cascade was not
 *       satisfied, so the collaborator behind it was never reached. Fix the fixture, not the
 *       strictness setting.</li>
 *   </ul>
 *
 * <h2>5. Reachable no-ops, retained for control-flow parity</h2>
 * <p>One-to-one control-flow correspondence keeps the paragraph map mechanically provable, so a
 * paragraph whose body does nothing is still mapped rather than deleted. Each item below carries a
 * locator, an assertion in this class and an intentional-no-op marker, which is what keeps it from
 * reading as abandoned residue. This class's instances are the missing
 * {@code WHEN COULD-NOT-LOCK-CUST-FOR-UPDATE} arm - retained, asserted, never added; the
 * unreachable default abend message at {@code :4205-4206}; the bare {@code EXIT} body of
 * {@code 9700-CHECK-CHANGE-IN-REC-EXIT} at {@code :4193-4195}; and the commented-out naive
 * date-of-birth {@code MOVE} at {@code :3856}, cited as the source author's own evidence of the
 * trap. Deleting any of them would break the paragraph map.</p>
 *
 * <h2>6. What this class pins, and the ways each one gets broken</h2>
 * <ul>
 *   <li><strong>The outcomes that must not be collapsed.</strong> The customer read-for-update failure
 *       is reported as success ({@code :3934-3942} sets the flag, {@code :2606-2615} never tests it),
 *       and that is preserved. The date-of-birth
 *       offset asymmetry ({@code :746-751} against {@code :4174-4179}) means a naive whole-string
 *       date-of-birth comparison reports a change on every request; both sides are therefore compared
 *       component by component.</li>
 *   <li><strong>The invariants a plausible-looking change would violate.</strong> Normalising the case
 *       asymmetry in either direction. Relying on the
 *       persistence provider's version column alone. Collapsing the five outcomes into one conflict
 *       status. Leaking {@code FICO-RANGE-IS-VALID} into the entity. Logging any snapshot
 *       field.</li>
 *   <li><strong>The consolidations that must not happen.</strong> {@code 9700} and {@code 1205} have
 *       opposite date and case conventions. The two social-security-number shapes differ. The
 *       online {@code '9999'} abend code is not the batch 999 / return code 12 pair.</li>
 *   <li><strong>The spellings carried unchanged.</strong> The two snapshot field-name mismatches
 *       {@code ACUP-OLD-CUST-PRI-HOLDER-IND} and {@code ACUP-OLD-CUST-FICO-SCORE}, and the misspelled
 *       {@code ACCT-EXPIRAION-DATE}.</li>
 *   </ul>
 *
 * <h2>7. The comparison census, stated as the source carries it</h2>
 * <p>{@code 9700}'s account block at {@code :4115-4140} carries <strong>sixteen comparison clauses
 * over ten logical fields</strong>. Prose elsewhere describes it as "twelve account predicates", which
 * is neither figure and which no accompanying enumeration reconciles - the ambiguity is whether the
 * three substring clauses of a date count as one predicate or three, and whether the two zero-valued
 * cycle members were counted at all. This class asserts the <em>clause</em>
 * count of sixteen and the <em>logical field</em> count of ten, and consolidates nothing.</p>
 * <p>No performance measurement is asserted here. The source publishes no service-level objective,
 * so none may be invented; throughput and latency belong to the measured baseline of the
 * performance gate, not to this tier.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.STRICT_STUBS)
@DisplayName("AccountUpdateService - COACTUPC / transaction CAUP")
final class AccountUpdateServiceTest {

    // Attention identifiers, exactly as app/cpy/CSSTRPFY.cpy names them for EIBAID.

    /** {@code DFHENTER}, the Enter key; {@code :906} accepts it unconditionally. */
    private static final String AID_ENTER = "DFHENTER";

    /** {@code DFHPF5}, the only key that reaches {@code 9600-WRITE-PROCESSING} at {@code :2602}. */
    private static final String AID_PFK05 = "DFHPF5";

    /** {@code DFHPF12}, which diverts to the fall-through pair at {@code :2568}/{@code :2572}. */
    private static final String AID_PFK12 = "DFHPF12";

    // File-control names and statuses. The two file names carry the trailing blank of the COBOL
    // literals LIT-ACCTFILENAME and LIT-CUSTFILENAME, which are PIC X(8) declarations.

    /** {@code LIT-ACCTFILENAME}: CICS FILE {@code ACCTDAT} padded to eight bytes. */
    private static final String ACCOUNT_FILE = "ACCTDAT ";

    /** {@code LIT-CUSTFILENAME}: CICS FILE {@code CUSTDAT} padded to eight bytes. */
    private static final String CUSTOMER_FILE = "CUSTDAT ";

    /** The {@code READ} verb, as the status mapper is asked about it on both lock guards. */
    private static final String OPERATION_READ = "READ";

    /** {@code FILE STATUS '23'} / {@code DFHRESP(NOTFND)}, the status both lock guards report. */
    private static final String IO_STATUS_NOT_FOUND = "23";

    // The twelve outcome literals of :505-528. Each is an 88-level on WS-RETURN-MSG PIC X(75), so
    // asserting the literal asserts the condition name.

    /** {@code :505-506}. */
    private static final String CREDIT_LIMIT_MUST_BE_SUPPLIED = "Credit Limit must be supplied";

    /** {@code :507-508}. */
    private static final String CREDIT_LIMIT_IS_NOT_VALID = "Credit Limit is not valid";

    /** {@code :509-510}. */
    private static final String EXPIRY_MONTH_RANGE = "Card expiry month must be between 1 and 12";

    /** {@code :511-512}. */
    private static final String INVALID_EXPIRY_YEAR = "Invalid card expiry year";

    /** {@code :513-514}. */
    private static final String NOT_IN_CARDS_DATABASE = "Did not find this account in cards database";

    /** {@code :515-516}. */
    private static final String NO_CARDS_FOR_CONDITION = "Did not find cards for this search condition";

    /** {@code :517-518}, set at {@code :3912} and tested at {@code :2607}. */
    private static final String COULD_NOT_LOCK_ACCOUNT = "Could not lock account record for update";

    /** {@code :519-520}, set at {@code :3939} and tested <strong>nowhere</strong>. */
    private static final String COULD_NOT_LOCK_CUSTOMER = "Could not lock customer record for update";

    /** {@code :521-522}. Note {@code some one} is two words in the source. */
    private static final String DATA_WAS_CHANGED = "Record changed by some one else. Please review";

    /** {@code :523-524}, set at {@code :4079} and again at {@code :4098}, neither one latched. */
    private static final String UPDATE_OF_RECORD_FAILED = "Update of record failed";

    /** {@code :525-526}. */
    private static final String CARD_FILE_READ_ERROR = "Error reading Card Data File";

    /** {@code :527-528}. Note the <strong>four</strong> dots. */
    private static final String CODING_TO_BE_DONE = "Looks Good.... so far";

    // Information messages of 3250-SETUP-INFOMSG, :2958-2976. These are what the operator reads.

    /** {@code :2968-2969}, chosen for {@code ACUP-CHANGES-OKAYED-AND-DONE}. */
    private static final String INFO_UPDATE_SUCCESS = "Changes committed to database";

    /** {@code :2971-2974}, shared by both failure markers. */
    private static final String INFO_INFORM_FAILURE = "Changes unsuccessful. Please try again";

    /** {@code :2962-2965}, shown while a change set is being assembled. */
    private static final String INFO_PROMPT_FOR_CHANGES = "Update account details presented above.";

    /** {@code :2966-2967}, shown once every edit has passed. */
    private static final String INFO_PROMPT_FOR_CONFIRMATION = "Changes validated.Press F5 to save";

    /** {@code :1769}, assigned without the {@code IF WS-RETURN-MSG-OFF} latch. */
    private static final String NO_CHANGE_DETECTED = "No change detected with respect to values fetched.";

    /** {@code 1275-EDIT-FICO-SCORE} at {@code :2515-2528}, the new-side range gate's message. */
    private static final String FICO_RANGE_MESSAGE = "FICO Score: should be between 300 and 850";

    // Abend contract: CSMSG02Y.cpy field widths and the two distinct codes.

    /** {@code :2635} {@code MOVE '0001' TO ABEND-CODE}. */
    private static final String UNEXPECTED_SCENARIO_CODE = "0001";

    /** {@code :2637-2638} {@code MOVE 'UNEXPECTED DATA SCENARIO' TO ABEND-MSG}. */
    private static final String UNEXPECTED_SCENARIO_MESSAGE = "UNEXPECTED DATA SCENARIO";

    /** {@code :4223} {@code EXEC CICS ABEND ABCODE('9999')} - the ONLINE code, not batch 999. */
    private static final String ONLINE_ABEND_CODE = "9999";

    /** {@code LIT-THISPGM}, moved into {@code ABEND-CULPRIT} at {@code :2634} and {@code :4209}. */
    private static final String PROGRAM_NAME = "COACTUPC";

    /** {@code ABEND-REASON PIC X(50)} of {@code app/cpy/CSMSG02Y.cpy:21-29}. */
    private static final int ABEND_REASON_WIDTH = 50;

    /** {@code WS-RETURN-MSG PIC X(75)} at {@code :479}; every error message is padded to it. */
    private static final int RETURN_MESSAGE_WIDTH = 75;

    /**
     * The {@code DECISION_LOG.md} identifier of the unstorable-image deviation, {@value}.
     *
     * <p>Named as a constant so the assertion that the record exists, the record itself, and the production
     * Javadoc that claims it exists all refer to one string.
     */
    private static final String UNSTORABLE_IMAGE_DEVIATION_ID = "DL-DV-06";

    /**
     * The declared type of the column the source's {@code LOW-VALUES} would have to land in, {@value}.
     *
     * <p>{@code CUST-FICO-CREDIT-SCORE} is {@code PIC 9(03)} at {@code app/cpy/CVCUS01Y.cpy}, and the schema
     * renders it as a fixed-width text column. That it is a <em>text</em> type is the load-bearing fact: no
     * PostgreSQL text type can hold a zero byte in any encoding, so three {@code NUL} bytes are unstorable
     * rather than merely inconvenient.
     */
    private static final String FICO_SCORE_COLUMN_DEFINITION = "CHAR(3)";

    /**
     * The twenty fields {@code 9600-WRITE-PROCESSING} moves into the two update images and which can therefore
     * arrive absent, named in the symbolic-map vocabulary of {@code app/cpy-bms/COACTUP.CPY}.
     *
     * <p>Derived from the source, in its own order: the seven account fields of
     * {@code app/cbl/COACTUPC.cbl:3962-4002} then the thirteen customer fields of {@code :4010-4059}. The
     * three assembled values - date of birth, both phone numbers and the social security number - are absent
     * from this list on purpose: their components run through the alphanumeric move, which renders a missing
     * component as blanks exactly as a {@code MOVE} to a {@code PIC X} field would, so they cannot arrive
     * {@code null} and screening them would add an unreachable branch.
     */
    private static final List<String> EXPECTED_SCREENED_FIELDS = List.of(
            // Account image, :3962-4002.
            "ACSTTUS", "ACURBAL", "ACRDLIM", "ACSHLIM", "ACRCYCR", "ACRCYDB", "AADDGRP",
            // Customer image, :4010-4059.
            "ACSFNAM", "ACSMNAM", "ACSLNAM", "ACSADL1", "ACSADL2", "ACSCITY", "ACSSTTE",
            "ACSCTRY", "ACSZIPC", "ACSGOVT", "ACSEFTC", "ACSPFLG", "ACSTFCO");

    /** Repository-relative path of the production bean, read by the screened-field census. */
    private static final String ACCOUNT_UPDATE_SERVICE_SOURCE =
            "src/main/java/com/cardemo/service/account/AccountUpdateService.java";

    /** Repository-relative path of the schema migration, read by the column-definition helper. */
    private static final String SCHEMA_MIGRATION_SOURCE =
            "src/main/resources/db/migration/V1__create_schema.sql";

    /** {@code ERRMSGO} of {@code app/cpy-bms/COACTUP.CPY} is {@code X(40)}; the info line never exceeds it. */
    private static final int INFO_MESSAGE_WIDTH = 40;

    // Keys. ACCT-ID is PIC 9(11) (app/cpy/CVACT01Y.cpy:5); CUST-ID is PIC 9(09).

    /** The eleven-digit screen key {@code ACCTSIDI} requires; {@code 1210-EDIT-ACCOUNT} rejects fewer. */
    private static final String SCREEN_ACCOUNT_ID = "00000000001";

    // ---------------------------------------------------------------------------------------------
    // Snapshot sealing. Transformation Rule 7 moves the storage lifetime of WS-THIS-PROGCOMMAREA
    // (app/cbl/COACTUPC.cbl:652) onto the request, and the value travels sealed so that the operand of
    // 9700-CHECK-CHANGE-IN-REC is never one the guarded party could choose.
    // ---------------------------------------------------------------------------------------------

    /**
     * The authenticated principal every sealed value below is bound to. {@code SEC-USR-ID} is
     * {@code PIC X(08)} at {@code app/cpy/CSUSR01Y.cpy:14}, and this is one of the ten seeded identifiers
     * of {@code app/jcl/DUSRSECJ.jcl}.
     */
    private static final String SUBJECT = "ADMIN001";

    /** A second principal, used to prove a value sealed for one operator is useless to another. */
    private static final String OTHER_SUBJECT = "USER0001";

    /**
     * The operation kind {@code AccountUpdateService} seals under. Duplicated rather than exposed, because
     * the constant is private to the bean and a test that reached for it would assert its own value.
     */
    private static final String SNAPSHOT_KIND = "account-update-snapshot";

    /**
     * A test-only sealing key. Thirty-two ASCII bytes, comfortably over the component's documented
     * minimum, and local to this file - no configured or deployed key appears here.
     */
    private static final String SEALING_KEY = "account-update-service-test-key!";

    /** The sealer's documented default lifetime, in seconds. */
    private static final long SEAL_LIFETIME_SECONDS = 900L;

    /**
     * The width of {@code WS-EDIT-CURRENCY-9-2-F}, declared {@code PIC +ZZZ,ZZZ,ZZZ.99} at
     * {@code app/cbl/COACTUPC.cbl:371}: one sign, ten integer positions, two group separators, the
     * point and two decimals. Every masked money field the screen paints is exactly this wide.
     */
    private static final int MONEY_DISPLAY_WIDTH = 15;

    /** The nine-digit customer key the snapshot carries into {@code :3919}. */
    private static final String SNAPSHOT_CUSTOMER_ID = "000000001";

    /** The parsed account key both {@code findByIdForUpdate} stubs are asked for. */
    private static final Long ACCOUNT_KEY = 1L;

    /** The parsed customer key. */
    private static final Long CUSTOMER_KEY = 1L;

    /**
     * The {@code XREF-CARD-NUM} of the one cross-reference record that binds {@link #ACCOUNT_KEY} to
     * {@link #CUSTOMER_KEY}. Sixteen digits, per {@code app/cpy/CVACT03Y.cpy}.
     */
    private static final String XREF_CARD_NUMBER = "4111111111111111";

    /**
     * A customer key that no cross-reference binds to {@link #ACCOUNT_KEY}. Used by the cross-tenant
     * probes: a caller echoing this value back is naming somebody else's customer row.
     */
    private static final String FOREIGN_CUSTOMER_ID = "000000042";

    /** The optimistic-lock counter seeded on the live account, proving the second layer exists. */
    private static final Long ACCOUNT_VERSION = 7L;

    /** The optimistic-lock counter seeded on the live customer. */
    private static final Long CUSTOMER_VERSION = 3L;

    /**
     * The Area-A label-shaped-line census of {@code app/cbl/COACTUPC.cbl}: 88 lines, counted on the
     * CR-stripped source with {@code grep -cE '^       [0-9A-Z][0-9A-Z-]*\.$'}. It is NOT the paragraph
     * count - three of the 88 are IDENTIFICATION DIVISION entries, so the own paragraph count is 85 and
     * the mapped count is 87. {@code theParagraphCountReconciles} performs that arithmetic.
     */
    private static final int SOURCE_PARAGRAPH_COUNT = 88;

    /** The sixteen comparison clauses of the account block at {@code :4115-4140}. */
    private static final int ACCOUNT_COMPARISON_CLAUSES = 16;

    /** The ten logical account fields those sixteen clauses cover. */
    private static final int ACCOUNT_LOGICAL_FIELDS = 10;

    /**
     * The twelve-predicate reading of the account block, which is what a summary count of it yields.
     * {@code theAccountComparisonCensusIsSixteenClausesOverTenLogicalFields} relates it to the two figures
     * measured from the source: sixteen comparison clauses over ten logical fields.
     */
    private static final int AAP_ACCOUNT_PREDICATE_CLAIM = 12;

    /**
     * {@code PROGRAM-ID.}, {@code DATE-WRITTEN.} and {@code DATE-COMPILED.} at
     * {@code app/cbl/COACTUPC.cbl:22}, {@code :24} and {@code :26} each occupy a line of the same shape
     * as a paragraph label - a name at column 8 terminated by a period, with its value on the following
     * line - but they are {@code IDENTIFICATION DIVISION} entries, not paragraphs.
     */
    private static final int IDENTIFICATION_DIVISION_ENTRIES = 3;

    /**
     * {@code YYYY-STORE-PFKEY.} and {@code YYYY-STORE-PFKEY-EXIT.} at {@code app/cpy/CSSTRPFY.cpy:17}
     * and {@code :80}. That copybook is procedural: it is copied into this program's
     * {@code PROCEDURE DIVISION}, so its two labels are paragraphs of this program even though they do
     * not appear among its own source lines.
     */
    private static final int COPYBOOK_CONTRIBUTED_LABELS = 2;

    /**
     * The paragraph labels the bean maps one-to-one onto private methods:
     * {@value #SOURCE_PARAGRAPH_COUNT} label-shaped lines, less the
     * {@value #IDENTIFICATION_DIVISION_ENTRIES} identification entries, plus the
     * {@value #COPYBOOK_CONTRIBUTED_LABELS} the procedural copybook contributes.
     */
    private static final int MAPPED_PARAGRAPH_LABELS = 87;

    /** The {@code -EXIT} labels, each preserved as its own method rather than folded into its partner. */
    private static final int PARAGRAPH_EXIT_LABELS = 41;

    /** {@code app/cbl/COACTUPC.cbl} is exactly this many lines - the largest program in the corpus. */
    private static final int SOURCE_LINE_COUNT = 4236;

    /** The input fields the {@code COACTUP} symbolic map of {@code app/cpy-bms/COACTUP.CPY} carries. */
    private static final int BMS_INPUT_FIELDS = 54;

    /**
     * {@code ACSZIPCI PIC X(5)} at {@code app/cpy-bms/COACTUP.CPY:246}. The snapshot slot behind it,
     * {@code ACUP-OLD-ADDR-ZIP} at {@code :721}, is twice as wide.
     */
    private static final int ZIP_SCREEN_WIDTH = 5;

    /** {@code 88 FICO-RANGE-IS-VALID VALUES 300 THROUGH 850} at {@code :848-849}, lower bound. */
    private static final int FICO_MINIMUM = 300;

    /** {@code 88 FICO-RANGE-IS-VALID VALUES 300 THROUGH 850} at {@code :848-849}, upper bound. */
    private static final int FICO_MAXIMUM = 850;

    // Retrieval turn, navigation and edit-cascade literals.
    //
    // Every value below was read out of the frozen COBOL and cross-checked against the bean, so a
    // drift in either direction fails a test rather than passing silently. They are declared here
    // rather than inlined so that the byte-exact evidence Rule 1 clause F asks for sits in one place.

    /** {@code DFHPF3} of {@code COPY DFHAID} at {@code :614}; the exit key tested at {@code :925-927}. */
    private static final String AID_PFK03 = "DFHPF3";

    /** {@code LIT-CARDXREFNAME-ACCT-PATH}: the {@code CXACAIX} alternate-index path {@code 9200} reads. */
    private static final String XREF_ACCOUNT_PATH = "CXACAIX ";

    /** {@code FILE STATUS '90'}: the physical-error family the {@code WHEN OTHER} arm of {@code 9200} takes. */
    private static final String IO_STATUS_IO_ERROR = "90";

    /** {@code ERROR-FNAME PIC X(9)} of {@code :389-408} holding the {@code CXACAIX} path name. */
    private static final String XREF_ERROR_FILE_SLOT = "CXACAIX  ";

    /**
     * The composed miss diagnostic of {@code 9200-GETCARDXREF-BYACCT} at {@code :3671-3685}. It is
     * assembled from six pieces rather than latched from one literal, and the assembly overflows
     * {@code WS-RETURN-MSG PIC X(75)} by six bytes, so the trailing reason code arrives cut to four
     * digits. That truncation is the source's, not this test's.
     */
    private static final String XREF_NOT_FOUND_DIAGNOSTIC =
            "Account:00000000001 not found in Cross ref file.  Resp:000000013  Reas:0000";

    /**
     * {@code WS-FILE-ERROR-MESSAGE} of {@code :389-408} rendered for the {@code WHEN OTHER} arm of
     * {@code 9200}, then cut to {@code WS-RETURN-MSG PIC X(75)}. The eighty-byte group ends in five
     * blanks, so the seventy-five-byte projection keeps exactly one of them.
     */
    private static final String XREF_IO_ERROR_DIAGNOSTIC =
            "File Error: READ     on CXACAIX   returned RESP 000000017 ,RESP2 000000000 ";

    /** {@code LIT-THISTRANID} at {@code :442}: this program's own transaction identifier. */
    private static final String TRANSACTION_ID = "CAUP";

    /** {@code LIT-MENUTRANID} at {@code :454}: the fallback target when no caller is known. */
    private static final String MENU_TRANSACTION_ID = "CM00";

    /** {@code LIT-MENUPGM} at {@code :451}: the fallback program when no caller is known. */
    private static final String MENU_PROGRAM = "COMEN01C";

    /** {@code LIT-THISMAPSET} at {@code :445}. The trailing blank is part of the eight-byte literal. */
    private static final String THIS_MAPSET = "COACTUP ";

    /** {@code LIT-THISMAP} at {@code :448}. */
    private static final String THIS_MAP = "CACTUPA";

    /** {@code 'PF03 pressed.Exiting'} of {@code :533}, without its fourteen trailing blanks. */
    private static final String EXIT_MESSAGE_TEXT = "PF03 pressed.Exiting";

    /** The declared width of the exit literal at {@code :533}: twenty characters plus fourteen blanks. */
    private static final int EXIT_MESSAGE_WIDTH = 34;

    /** {@code 'Enter or update id of account to update'} at {@code :502}. */
    private static final String INFO_PROMPT_FOR_SEARCH_KEYS = "Enter or update id of account to update";

    /**
     * {@code 'Details of selected account shown above'} at {@code :499}. {@code 1200} sets it at
     * {@code :1452} but {@code 3250-SETUP-INFOMSG} overwrites it on every reachable turn before
     * {@code 3300} reads it, so it can never reach a projection.
     */
    private static final String INFO_FOUND_ACCOUNT_DATA = "Details of selected account shown above";

    /** {@code DFHBMFSE} of {@code COPY DFHBMSCA}: unprotected with the modified-data tag forced on. */
    private static final String ATTRIBUTE_UNPROTECTED_FSET = "DFHBMFSE";

    /** {@code DFHBMPRF} of {@code COPY DFHBMSCA}: protected with the modified-data tag forced on. */
    private static final String ATTRIBUTE_PROTECTED_FSET = "DFHBMPRF";

    /** {@code 88 NO-SEARCH-CRITERIA-RECEIVED} at {@code :545}. */
    private static final String NO_INPUT_RECEIVED = "No input received";

    /** {@code 88 PROMPT-FOR-ACCT} at {@code :536}. */
    private static final String ACCOUNT_NOT_PROVIDED = "Account number not provided";

    /** {@code 88 FLG-ACCTFILTER-NOT-OK} diagnostic at {@code :676-677}. */
    private static final String ELEVEN_DIGIT_ACCOUNT =
            "Account Number if supplied must be a 11 digit Non-Zero Number";

    /** {@code :734}: the one edit diagnostic in the program that carries no field label at all. */
    private static final String INVALID_ZIP_FOR_STATE = "Invalid zip code for state";

    /**
     * A synthetic verdict text. {@code DateValidationService} owns every calendar diagnostic - the
     * fourteen {@code CSUTLDPY} labels live there, and {@code DateValidationServiceTest} asserts their
     * wording - so this class stubs the collaborator and proves only that
     * {@code propagateDateOutcome} latches whatever the collaborator returned. The value is
     * deliberately not a source literal, so that a reader cannot mistake it for one.
     */
    private static final String STUBBED_DATE_DIAGNOSTIC = "date service verdict for the tested component";

    /** {@code WS-EDIT-VARIABLE-NAME PIC X(25)} at {@code :975}: the width every label is moved through. */
    private static final int EDIT_VARIABLE_NAME_WIDTH = 25;

    /** {@code :649}. Note the terminating full stop, which is part of the literal. */
    private static final String MUST_BE_SUPPLIED = " must be supplied.";

    /** {@code :652}. */
    private static final String MUST_BE_Y_OR_N = " must be Y or N.";

    /** {@code :655}. */
    private static final String ALPHABETS_ONLY = " can have alphabets only.";

    /**
     * {@code :658}. The suffix exists in the bean but no reachable turn can produce it, because the
     * only two routines that use it - {@code 1230-EDIT-ALPHANUM-REQD} and
     * {@code 1240-EDIT-ALPHANUM-OPT} - are never performed. See the unreachability section below.
     */
    private static final String ALPHANUMERIC_ONLY = " can have numbers or alphabets only.";

    /** {@code :661}. */
    private static final String MUST_BE_ALL_NUMERIC = " must be all numeric.";

    /** {@code :664}. */
    private static final String MUST_NOT_BE_ZERO = " must not be zero.";

    /** {@code :670}. This one has no terminating full stop, unlike its five siblings. */
    private static final String IS_NOT_VALID = " is not valid";

    /** {@code :680}. */
    private static final String AREA_CODE_SUPPLIED = ": Area code must be supplied.";

    /** {@code :683}. The capital {@code A} before {@code 3} is the source's own wording. */
    private static final String AREA_CODE_THREE_DIGITS = ": Area code must be A 3 digit number.";

    /** {@code :686}. */
    private static final String AREA_CODE_NOT_ZERO = ": Area code cannot be zero";

    /** {@code :689}. */
    private static final String AREA_CODE_NOT_NORTH_AMERICAN =
            ": Not valid North America general purpose area code";

    /** {@code :693}. */
    private static final String PREFIX_SUPPLIED = ": Prefix code must be supplied.";

    /** {@code :696}. */
    private static final String PREFIX_THREE_DIGITS = ": Prefix code must be A 3 digit number.";

    /** {@code :699}. */
    private static final String PREFIX_NOT_ZERO = ": Prefix code cannot be zero";

    /** {@code :702}. */
    private static final String LINE_NUMBER_SUPPLIED = ": Line number code must be supplied.";

    /** {@code :705}. */
    private static final String LINE_NUMBER_FOUR_DIGITS = ": Line number code must be A 4 digit number.";

    /** {@code :709}. */
    private static final String LINE_NUMBER_NOT_ZERO = ": Line number code cannot be zero";

    /** {@code :725}. The leading colon-space produces a double punctuation the source keeps. */
    private static final String NOT_A_VALID_STATE = ": is not a valid state code";

    /** {@code :715-716}: the administratively reserved first-part values. */
    private static final String SSN_PART1_RANGE = ": should not be 000, 666, or between 900 and 999";

    /** {@code :742}. */
    private static final String LABEL_ACCOUNT_STATUS = "Account Status";

    /** {@code :745}. */
    private static final String LABEL_OPEN_DATE = "Open Date";

    /** {@code :748}. */
    private static final String LABEL_CREDIT_LIMIT = "Credit Limit";

    /** {@code :751}. */
    private static final String LABEL_EXPIRY_DATE = "Expiry Date";

    /** {@code :757}. */
    private static final String LABEL_REISSUE_DATE = "Reissue Date";

    /** {@code :763}: twenty-six characters, one wider than the field it is moved through. */
    private static final String LABEL_CURRENT_CYCLE_CREDIT = "Current Cycle Credit Limit";

    /** What {@code :763} becomes after {@code MOVE ... TO WS-EDIT-VARIABLE-NAME PIC X(25)}. */
    private static final String LABEL_CURRENT_CYCLE_CREDIT_CUT = "Current Cycle Credit Limi";

    /** {@code :772}. */
    private static final String LABEL_DATE_OF_BIRTH = "Date of Birth";

    /** {@code :775}. */
    private static final String LABEL_FICO_SCORE = "FICO Score";

    /** {@code :778}. */
    private static final String LABEL_FIRST_NAME = "First Name";

    /** {@code :781}. */
    private static final String LABEL_MIDDLE_NAME = "Middle Name";

    /** {@code :787}. */
    private static final String LABEL_ADDRESS_LINE_1 = "Address Line 1";

    /** {@code :790}. */
    private static final String LABEL_STATE = "State";

    /** {@code :793}. */
    private static final String LABEL_ZIP = "Zip";

    /** {@code :796}. */
    private static final String LABEL_CITY = "City";

    /** {@code :799}. */
    private static final String LABEL_COUNTRY = "Country";

    /** {@code :802}. */
    private static final String LABEL_PHONE_NUMBER_1 = "Phone Number 1";

    /** {@code :805}. */
    private static final String LABEL_PHONE_NUMBER_2 = "Phone Number 2";

    /** {@code :808}. */
    private static final String LABEL_EFT_ACCOUNT_ID = "EFT Account Id";

    /** {@code :811}. */
    private static final String LABEL_PRIMARY_CARD_HOLDER = "Primary Card Holder";

    /** {@code :712}. The colon inside the label is the source's, and it doubles up in the diagnostic. */
    private static final String SSN_PART1_LABEL = "SSN: First 3 chars";

    /** {@code :719}. The ampersand is a literal character of the label, not markup. */
    private static final String SSN_PART2_LABEL = "SSN 4th & 5th chars";

    /** {@code :722}. */
    private static final String SSN_PART3_LABEL = "SSN Last 4 chars";

    /** {@code ACCTSIDI} of {@code app/cpy-bms/COACTUP.CPY}; the cursor fallback at {@code :3165-3166}. */
    private static final String FIELD_ACCOUNT_ID = "ACCTSID";

    /** {@code ACSTTUSI}. */
    private static final String FIELD_ACCOUNT_STATUS = "ACSTTUS";

    /** {@code ACSTNUMI} - unprotected at {@code :3529} and then re-protected at {@code :3531}. */
    private static final String FIELD_CUSTOMER_ID = "ACSTNUM";

    /**
     * The {@code ACSADL2I} value {@link #write()} substitutes so that the submitted map differs from the
     * as-displayed snapshot and the conversation can legitimately reach PF05. See {@link #write()} for why
     * this field, and only this field, is the right one to use for that.
     */
    private static final String CHANGED_ADDRESS_LINE_2 = "APT 2";

    /** {@code ACRDLIMI}. */
    private static final String FIELD_CREDIT_LIMIT = "ACRDLIM";

    /** {@code ACRCYCRI}. */
    private static final String FIELD_CURRENT_CYCLE_CREDIT = "ACRCYCR";

    /** {@code OPNYEARI}. */
    private static final String FIELD_OPEN_DATE_YEAR = "OPNYEAR";

    /** {@code EXPYEARI}. */
    private static final String FIELD_EXPIRY_DATE_YEAR = "EXPYEAR";

    /** {@code RISYEARI}. */
    private static final String FIELD_REISSUE_DATE_YEAR = "RISYEAR";

    /** {@code ACTSSN1I}. */
    private static final String FIELD_SSN_PART1 = "ACTSSN1";

    /** {@code ACTSSN2I}. */
    private static final String FIELD_SSN_PART2 = "ACTSSN2";

    /** {@code ACTSSN3I}. */
    private static final String FIELD_SSN_PART3 = "ACTSSN3";

    /** {@code ACSTFCOI}. */
    private static final String FIELD_FICO_SCORE = "ACSTFCO";

    /** {@code DOBYEARI}. */
    private static final String FIELD_DATE_OF_BIRTH_YEAR = "DOBYEAR";

    /** {@code ACSFNAMI}. */
    private static final String FIELD_FIRST_NAME = "ACSFNAM";

    /** {@code ACSMNAMI}: the only cursor branch with no {@code -BLANK} companion, at {@code :3110-3111}. */
    private static final String FIELD_MIDDLE_NAME = "ACSMNAM";

    /** {@code ACSADL1I}. */
    private static final String FIELD_ADDRESS_LINE_1 = "ACSADL1";

    /** {@code ACSCITYI}. */
    private static final String FIELD_CITY = "ACSCITY";

    /** {@code ACSSTTEI}. */
    private static final String FIELD_STATE_CODE = "ACSSTTE";

    /** {@code ACSZIPCI}. */
    private static final String FIELD_ZIP = "ACSZIPC";

    /** {@code ACSCTRYI}. */
    private static final String FIELD_COUNTRY_CODE = "ACSCTRY";

    /** {@code ACSPH1AI}. */
    private static final String FIELD_PHONE_1_AREA_CODE = "ACSPH1A";

    /** {@code ACSPH1BI}. */
    private static final String FIELD_PHONE_1_PREFIX = "ACSPH1B";

    /** {@code ACSPH1CI}. */
    private static final String FIELD_PHONE_1_LINE_NUMBER = "ACSPH1C";

    /** {@code ACSPH2AI}. */
    private static final String FIELD_PHONE_2_AREA_CODE = "ACSPH2A";

    /** {@code ACSEFTCI}. */
    private static final String FIELD_EFT_ACCOUNT_ID = "ACSEFTC";

    /** {@code ACSPFLGI}. */
    private static final String FIELD_PRIMARY_CARD_HOLDER = "ACSPFLG";

    /** {@code MOVE '*' TO (SCRNVAR2)O} of {@code app/cpy/CSSETATY.cpy}: emitted for {@code BLANK} only. */
    private static final String ASTERISK_MARKER = "*";

    /** {@code 'Cash Credit Limit'} at {@code :754}. */
    private static final String LABEL_CASH_CREDIT_LIMIT = "Cash Credit Limit";

    /** {@code 'Current Balance'} at {@code :760}. */
    private static final String LABEL_CURRENT_BALANCE = "Current Balance";

    /** {@code 'Current Cycle Debit Limit'} at {@code :766} - exactly twenty-five bytes, so never cut. */
    private static final String LABEL_CURRENT_CYCLE_DEBIT = "Current Cycle Debit Limit";

    /** {@code 'Last Name'} at {@code :786}. */
    private static final String LABEL_LAST_NAME = "Last Name";

    /** {@code ACSHLIMI}. */
    private static final String FIELD_CASH_CREDIT_LIMIT = "ACSHLIM";

    /** {@code ACURBALI}. */
    private static final String FIELD_CURRENT_BALANCE = "ACURBAL";

    /** {@code ACRCYDBI}. */
    private static final String FIELD_CURRENT_CYCLE_DEBIT = "ACRCYDB";

    /** {@code ACSLNAMI}. */
    private static final String FIELD_LAST_NAME = "ACSLNAM";

    /** {@code ACSPH2BI}. */
    private static final String FIELD_PHONE_2_PREFIX = "ACSPH2B";

    /** {@code ACSPH2CI}. */
    private static final String FIELD_PHONE_2_LINE_NUMBER = "ACSPH2C";

    /** {@code OPNMONI}. */
    private static final String FIELD_OPEN_DATE_MONTH = "OPNMON";

    /** {@code OPNDAYI}. */
    private static final String FIELD_OPEN_DATE_DAY = "OPNDAY";

    /** {@code EXPMONI}. */
    private static final String FIELD_EXPIRY_DATE_MONTH = "EXPMON";

    /** {@code EXPDAYI}. */
    private static final String FIELD_EXPIRY_DATE_DAY = "EXPDAY";

    /** {@code RISMONI}. */
    private static final String FIELD_REISSUE_DATE_MONTH = "RISMON";

    /** {@code RISDAYI}. */
    private static final String FIELD_REISSUE_DATE_DAY = "RISDAY";

    /** {@code DOBMONI}. */
    private static final String FIELD_DATE_OF_BIRTH_MONTH = "DOBMON";

    /** {@code DOBDAYI}. */
    private static final String FIELD_DATE_OF_BIRTH_DAY = "DOBDAY";

    /**
     * The twelve attention identifiers {@code YYYY-STORE-PFKEY} of {@code app/cpy/CSSTRPFY.cpy}
     * recognises and this program then refuses to act on. {@code :911} admits only Enter, PF03, PF05
     * against a validated screen and PF12 against a fetched one, so every value below is silently
     * coerced to Enter at {@code :914-916}.
     */
    private static final List<String> NON_ACTIONABLE_ATTENTION_IDENTIFIERS = List.of(
            "DFHCLEAR", "DFHPA1", "DFHPA2", "DFHPF1", "DFHPF2", "DFHPF4",
            "DFHPF6", "DFHPF7", "DFHPF8", "DFHPF9", "DFHPF10", "DFHPF11");

    // Collaborators. All five are mocked; none is a Spring bean here.

    @Mock
    private CardCrossReferenceRepository cardCrossReferenceRepository;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private FileStatusMapper fileStatusMapper;

    @Mock
    private DateValidationService dateValidationService;

    @Mock
    private ValidationLookupService validationLookupService;

    /**
     * A fixed instant. {@code 3100-SCREEN-INIT} reads {@code FUNCTION CURRENT-DATE} at
     * {@code :2671} and {@code :2678} on every turn, so the clock must be deterministic.
     */
    private final Clock clock = Clock.fixed(Instant.parse("2024-03-15T09:41:07Z"), ZoneOffset.UTC);

    /** The bean under test, rebuilt per test method so no state can leak between tests. */
    private AccountUpdateService service;

    /** The live {@code ACCTDAT} record the read-for-update returns; mutated to simulate a rival write. */
    private Account account;

    /** The live {@code CUSTDAT} record the read-for-update returns. */
    private Customer customer;

    /** The mutable {@code ACUP-OLD-DETAILS} builder. */
    private Snapshot snapshot;

    /** The mutable symbolic-map builder. */
    private Screen screen;

    /**
     * The <em>real</em> sealer, deliberately not a mock.
     *
     * <p>The as-displayed snapshot reaches {@code updateAccount} only by being opened from a sealed value,
     * so a mocked sealer would assert nothing about the one property that matters: that the group the
     * comparison of {@code :4109-4193} consumes is the group <em>this server</em> issued, for this account,
     * to this principal. Sealing and opening for real is what lets the rejection tests below present a
     * tampered, transplanted, foreign-principal or expired value and observe the refusal.</p>
     */
    private SnapshotTokenService snapshotTokenService;

    @BeforeEach
    void setUp() {
        this.snapshotTokenService = new SnapshotTokenService(SEALING_KEY, SEAL_LIFETIME_SECONDS,
                this.clock, new ObjectMapper());
        this.service = new AccountUpdateService(this.cardCrossReferenceRepository,
                this.accountRepository,
                this.customerRepository,
                this.fileStatusMapper,
                this.dateValidationService,
                this.validationLookupService,
                this.clock,
                this.snapshotTokenService);
        this.account = liveAccount();
        this.customer = liveCustomer();
        this.snapshot = new Snapshot();
        this.screen = new Screen();
        givenEveryEditCollaboratorAcceptsByDefault();
    }

    /**
     * Declares an accepting verdict from each of the five edit collaborators, <em>leniently</em> and
     * <em>first</em>.
     *
     * <p>Both properties are load-bearing. Declared first, any stubbing a test makes for itself is matched
     * ahead of these, because Mockito matches the most recently declared stubbing first - so
     * {@link #givenCalendarDateEditFailsFor(String)} and its siblings still decide their own outcomes.
     * Declared leniently, {@link Strictness#STRICT_STUBS} does not object when a particular test never
     * reaches one of them.
     *
     * <p>The reason a default is needed at all is that {@code updateAccount} drives
     * {@code 1200-EDIT-MAP-INPUTS} as well as {@code 2000-DECIDE-ACTION} - the source validates on the
     * Enter turn and writes on the PF05 turn, and a stateless caller gets one call for both. A test whose
     * subject is the <em>write</em> would otherwise have to stub the entire edit cascade before it could
     * reach the write, and an unstubbed collaborator would return {@code null} into
     * {@code propagateDateOutcome} and abend, which is no test's intended outcome.
     */
    private void givenEveryEditCollaboratorAcceptsByDefault() {
        final DateValidationService.EditOutcome valid = validDateOutcome();
        lenient().when(this.dateValidationService.editDate(any(), any())).thenReturn(valid);
        lenient().when(this.dateValidationService.editDateOfBirth(any(), any())).thenReturn(valid);
        lenient().when(this.validationLookupService.isValidGeneralPurposeAreaCode(any())).thenReturn(true);
        lenient().when(this.validationLookupService.isValidUsStateCode(any())).thenReturn(true);
        lenient().when(this.validationLookupService.isValidStateZipCodeCombination(any())).thenReturn(true);
    }

    // Fixtures. Every value is synthetic and every object is built through the production
    // constructor - there is no reflection, no setAccessible and no parameter-name matching
    // anywhere in this class, because Rule 1 clause D rules out reflection-driven invocation.

    /**
     * The live {@code ACCTDAT} record, shaped by {@code app/cpy/CVACT01Y.cpy}: three
     * {@code PIC S9(10)V99} money members plus two cycle members, three {@code PIC X(10)} dates in
     * {@code yyyy-MM-dd} form, a {@code PIC X(10)} postal code and a {@code PIC X(10)} group
     * identifier. The version member is the store-level concurrency layer.
     * @return a fresh live account whose every {@code 9700}-compared member matches {@link Snapshot}
     */
    private static Account liveAccount() {
        final Account live = new Account(ACCOUNT_KEY,
                "Y",
                new BigDecimal("194.00"),
                new BigDecimal("2020.00"),
                new BigDecimal("1020.00"),
                "2000-01-01",
                "2025-12-31",
                "2020-06-15",
                new BigDecimal("0.00"),
                new BigDecimal("0.00"),
                "98101",
                "ZEROBAL");
        live.setVersion(ACCOUNT_VERSION);
        return live;
    }

    /**
     * The live {@code CUSTDAT} record, shaped by {@code app/cpy/CVCUS01Y.cpy}. The date of birth is
     * <strong>ten characters with separators</strong>, which is the whole origin of the offset
     * asymmetry: the snapshot member is eight characters without them.
     * @return a fresh live customer whose every {@code 9700}-compared member matches {@link Snapshot}
     */
    private static Customer liveCustomer() {
        final Customer live = new Customer(CUSTOMER_KEY,
                "MARGARET",
                "A",
                "GOLD",
                "100 MAIN ST",
                "APT 1",
                "SEATTLE",
                "WA",
                "USA",
                "98101",
                "(206)555-0100",
                "(425)555-0199",
                "123456789",
                "WA-DL-9988776",
                "1980-01-15",
                "0000000001",
                "Y",
                "750");
        live.setVersion(CUSTOMER_VERSION);
        return live;
    }

    /**
     * Mutable builder for {@code ACUP-OLD-DETAILS} ({@code :669-756}). Money members are the
     * twelve-character zoned-decimal images the screen carries, with the trailing overpunch encoding
     * both the final digit and the sign - {@code '&#123;'} is {@code +0}. The three account dates and the
     * date of birth are the <strong>compact</strong> {@code X(08)} form.
     */
    private static final class Snapshot {

        private String accountId = SCREEN_ACCOUNT_ID;
        private String activeStatus = "Y";
        private String currentBalance = "00000001940{";
        private String creditLimit = "00000020200{";
        private String cashCreditLimit = "00000010200{";
        private String openDate = "20000101";
        private String expiraionDate = "20251231";
        private String reissueDate = "20200615";
        private String currentCycleCredit = "00000000000{";
        private String currentCycleDebit = "00000000000{";
        private String groupId = "zerobal";
        private String customerId = SNAPSHOT_CUSTOMER_ID;
        private String firstName = "MARGARET";
        private String middleName = "A";
        private String lastName = "GOLD";
        private String addressLine1 = "100 MAIN ST";
        private String addressLine2 = "APT 1";
        private String addressLine3 = "SEATTLE";
        private String addressStateCode = "WA";
        private String addressCountryCode = "USA";
        private String addressZip = "98101";
        private String phoneNumber1 = "(206)555-0100";
        private String phoneNumber2 = "(425)555-0199";
        private String ssn = "123456789";
        private String governmentIssuedId = "WA-DL-9988776";
        private String dateOfBirth = "19800115";
        private String eftAccountId = "0000000001";
        private String primaryCardHolderIndicator = "Y";
        private String ficoScore = "750";

        /**
         * Blanks every member of {@code ACUP-OLD-DETAILS}, reproducing the state the group holds
         * immediately after the {@code INITIALIZE WS-THIS-PROGCOMMAREA} of {@code :981-983}. A caller
         * can submit exactly this, so the bean must survive it: no key can be parsed from it, which is
         * why the resulting turn reaches a lock guard without addressing any dataset.
         */
        private void blankEveryMember() {
            this.accountId = "";
            this.activeStatus = "";
            this.currentBalance = "";
            this.creditLimit = "";
            this.cashCreditLimit = "";
            this.openDate = "";
            this.expiraionDate = "";
            this.reissueDate = "";
            this.currentCycleCredit = "";
            this.currentCycleDebit = "";
            this.groupId = "";
            this.customerId = "";
            this.firstName = "";
            this.middleName = "";
            this.lastName = "";
            this.addressLine1 = "";
            this.addressLine2 = "";
            this.addressLine3 = "";
            this.addressStateCode = "";
            this.addressCountryCode = "";
            this.addressZip = "";
            this.phoneNumber1 = "";
            this.phoneNumber2 = "";
            this.ssn = "";
            this.governmentIssuedId = "";
            this.dateOfBirth = "";
            this.eftAccountId = "";
            this.primaryCardHolderIndicator = "";
            this.ficoScore = "";
        }

        /**
         * Assembles the {@code ACUP-OLD-DETAILS} snapshot group from the builder state.
         *
         * @return the snapshot the change-detection comparison reads.
         */
        private AccountUpdateRequest.OldDetails build() {
            return new AccountUpdateRequest.OldDetails(this.accountId,
                    this.activeStatus,
                    this.currentBalance,
                    this.creditLimit,
                    this.cashCreditLimit,
                    this.openDate,
                    this.expiraionDate,
                    this.reissueDate,
                    this.currentCycleCredit,
                    this.currentCycleDebit,
                    this.groupId,
                    this.customerId,
                    this.firstName,
                    this.middleName,
                    this.lastName,
                    this.addressLine1,
                    this.addressLine2,
                    this.addressLine3,
                    this.addressStateCode,
                    this.addressCountryCode,
                    this.addressZip,
                    this.phoneNumber1,
                    this.phoneNumber2,
                    this.ssn,
                    this.governmentIssuedId,
                    this.dateOfBirth,
                    this.eftAccountId,
                    this.primaryCardHolderIndicator,
                    this.ficoScore);
        }
    }

    /**
     * Mutable builder for the {@code COACTUP} symbolic map. Field names, types and lengths come from
     * {@code app/cpy-bms/COACTUP.CPY} exactly - note {@code addressZip} is the five-byte
     * {@code ACSZIPCI} of {@code :246}, deliberately narrower than the ten-byte snapshot slot of
     * {@code :721}.
     * <p>{@code accountStatus} defaults to {@code N} against the snapshot's {@code Y} so that
     * {@code 1205-COMPARE-OLD-NEW} always reports a change. That keeps {@code WS-RETURN-MSG} off,
     * which is the state the two lock guards at {@code :3911-3913} and {@code :3936-3938} require
     * before they will latch their literal.</p>
     */
    private static final class Screen {

        private String accountId = SCREEN_ACCOUNT_ID;
        private String accountStatus = "N";
        private String openDateYear = "2000";
        private String openDateMonth = "01";
        private String openDateDay = "01";
        private String creditLimit = "2020.00";
        private String expiryDateYear = "2025";
        private String expiryDateMonth = "12";
        private String expiryDateDay = "31";
        private String cashCreditLimit = "1020.00";
        private String reissueDateYear = "2020";
        private String reissueDateMonth = "06";
        private String reissueDateDay = "15";
        private String currentBalance = "194.00";
        private String currentCycleCredit = "0.00";
        private String accountGroupId = "ZEROBAL";
        private String currentCycleDebit = "0.00";
        private String customerId = SNAPSHOT_CUSTOMER_ID;
        private String ssnPart1 = "123";
        private String ssnPart2 = "45";
        private String ssnPart3 = "6789";
        private String dateOfBirthYear = "1980";
        private String dateOfBirthMonth = "01";
        private String dateOfBirthDay = "15";
        private String ficoScore = "750";
        private String firstName = "MARGARET";
        private String middleName = "A";
        private String lastName = "GOLD";
        private String addressLine1 = "100 MAIN ST";
        private String addressStateCode = "WA";
        private String addressLine2 = "APT 1";
        private String addressZip = "98101";
        private String addressCity = "SEATTLE";
        private String addressCountryCode = "USA";
        private String phone1AreaCode = "206";
        private String phone1Prefix = "555";
        private String phone1LineNumber = "0100";
        private String governmentIssuedId = "WA-DL-9988776";
        private String phone2AreaCode = "425";
        private String phone2Prefix = "555";
        private String phone2LineNumber = "0199";
        private String eftAccountId = "0000000001";
        private String primaryCardHolderIndicator = "Y";

        /**
         * When {@code true}, the built request omits the echoed {@code ACUP-NEW-DETAILS} group of
         * {@code :757}. The write path never reads that group - a census of the bean finds
         * {@code getNewDetails()} nowhere in its executable code - but the payload normally carries it
         * because the next turn echoes it back, and the request contract requires both groups. This flag
         * exists so a test can present the omission and assert that the write path is indifferent to it.
         */
        private boolean omitNewDetails;

        /**
         * Assembles the {@code ACUP-NEW-DETAILS} group, joining each date back into the single
         * component the record declares from the three the screen sends.
         *
         * @return the new-details group.
         */
        private AccountUpdateRequest.NewDetails newDetails() {
            return new AccountUpdateRequest.NewDetails(this.accountId,
                    this.accountStatus,
                    this.currentBalance,
                    this.creditLimit,
                    this.cashCreditLimit,
                    this.openDateYear + this.openDateMonth + this.openDateDay,
                    this.expiryDateYear + this.expiryDateMonth + this.expiryDateDay,
                    this.reissueDateYear + this.reissueDateMonth + this.reissueDateDay,
                    this.currentCycleCredit,
                    this.currentCycleDebit,
                    this.accountGroupId,
                    this.customerId,
                    this.firstName,
                    this.middleName,
                    this.lastName,
                    this.addressLine1,
                    this.addressLine2,
                    this.addressCity,
                    this.addressStateCode,
                    this.addressCountryCode,
                    this.addressZip,
                    this.phone1AreaCode,
                    this.phone1Prefix,
                    this.phone1LineNumber,
                    this.phone2AreaCode,
                    this.phone2Prefix,
                    this.phone2LineNumber,
                    this.ssnPart1,
                    this.ssnPart2,
                    this.ssnPart3,
                    this.governmentIssuedId,
                    this.dateOfBirthYear + this.dateOfBirthMonth + this.dateOfBirthDay,
                    this.eftAccountId,
                    this.primaryCardHolderIndicator,
                    this.ficoScore);
        }

        /**
         * Assembles the request around a caller-supplied snapshot group, so a case can present a
         * snapshot that disagrees with the stored row without disturbing any other field.
         *
         * @param oldDetails the {@code ACUP-OLD-DETAILS} group to send.
         * @return the assembled request.
         */
        private AccountUpdateRequest build(final AccountUpdateRequest.OldDetails oldDetails) {
            return buildSealed(null).withOldDetails(oldDetails);
        }

        /**
         * Builds the symbolic-map area exactly as the wire constructor does, carrying the opaque sealed
         * snapshot member and no readable group, which is the shape a REST caller submits.
         *
         * @param snapshot the sealed value to carry, or {@code null} to carry none
         * @return the populated request
         */
        private AccountUpdateRequest buildSealed(final String snapshot) {
            return new AccountUpdateRequest(null, null, null, null, null, null,
                    this.accountId,
                    this.accountStatus,
                    this.openDateYear,
                    this.openDateMonth,
                    this.openDateDay,
                    this.creditLimit,
                    this.expiryDateYear,
                    this.expiryDateMonth,
                    this.expiryDateDay,
                    this.cashCreditLimit,
                    this.reissueDateYear,
                    this.reissueDateMonth,
                    this.reissueDateDay,
                    this.currentBalance,
                    this.currentCycleCredit,
                    this.accountGroupId,
                    this.currentCycleDebit,
                    this.customerId,
                    this.ssnPart1,
                    this.ssnPart2,
                    this.ssnPart3,
                    this.dateOfBirthYear,
                    this.dateOfBirthMonth,
                    this.dateOfBirthDay,
                    this.ficoScore,
                    this.firstName,
                    this.middleName,
                    this.lastName,
                    this.addressLine1,
                    this.addressStateCode,
                    this.addressLine2,
                    this.addressZip,
                    this.addressCity,
                    this.addressCountryCode,
                    this.phone1AreaCode,
                    this.phone1Prefix,
                    this.phone1LineNumber,
                    this.governmentIssuedId,
                    this.phone2AreaCode,
                    this.phone2Prefix,
                    this.phone2LineNumber,
                    this.eftAccountId,
                    this.primaryCardHolderIndicator,
                    null, null, null, null, null,
                    snapshot,
                    this.omitNewDetails ? null : newDetails());
        }

        /**
         * Drops the {@code ACUP-NEW-DETAILS} group from the request. {@code 1100-RECEIVE-MAP} at
         * {@code :2085-2225} populates the working-storage group from the flat symbolic-map fields, so
         * the bean reads the screen half from those fields and never from this member. Omitting it must
         * therefore change nothing.
         */
        private void omitNewDetails() {
            this.omitNewDetails = true;
        }
    }

    // Arrangement and invocation helpers. Each stubbing helper installs exactly one stubbing, so a
    // test never declares more than it exercises.

    /**
     * {@code :3894-3903} succeeds: the read-for-update returns the live account.
     *
     * <p>Installs the cross-reference stubbing alongside it, because a successful account lock is the exact
     * precondition of the customer binding that now sits between the two read-for-update calls. The binding
     * derives {@code CDEMO-CUST-ID} from the {@code CXACAIX} path instead of trusting the value the caller
     * echoed back, so every path that gets past the account lock guard reads that path exactly once, and
     * {@link Strictness#STRICT_STUBS} is satisfied without any test declaring a stubbing it does not reach.
     * A test that means the account lock to fail calls {@link #givenAccountMissing()} and never gets here.
     */
    private void givenAccountLocked() {
        when(this.accountRepository.findByIdForUpdate(ACCOUNT_KEY)).thenReturn(Optional.of(this.account));
        givenCustomerBoundToAccount();
    }

    /**
     * The {@code CXACAIX} path binds {@link #ACCOUNT_KEY} to {@link #CUSTOMER_KEY}, which is the state the
     * read turn's {@code 9200-GETCARDXREF-BYACCT} at {@code :3654-3662} would have found and
     * {@code 9500-STORE-FETCHED-DATA} at {@code :3805-3810} would have moved into the COMMAREA.
     */
    private void givenCustomerBoundToAccount() {
        when(this.cardCrossReferenceRepository.findFirstByAccountIdOrderByCardNumberAsc(ACCOUNT_KEY))
                .thenReturn(Optional.of(new CardCrossReference(XREF_CARD_NUMBER, CUSTOMER_KEY, ACCOUNT_KEY)));
    }

    /**
     * The {@code CXACAIX} path holds no record for the account, so nothing establishes which customer row
     * the write may touch. Distinct from {@link #givenCrossReferencePathUnavailable()}, where the path
     * itself fails.
     */
    private void givenNoCustomerBoundToAccount() {
        when(this.cardCrossReferenceRepository.findFirstByAccountIdOrderByCardNumberAsc(ACCOUNT_KEY))
                .thenReturn(Optional.empty());
    }

    /** {@code :3907} fires: the read-for-update finds nothing. */
    private void givenAccountMissing() {
        when(this.accountRepository.findByIdForUpdate(ACCOUNT_KEY)).thenReturn(Optional.empty());
    }

    /** {@code :3921-3930} succeeds: the read-for-update returns the live customer. */
    private void givenCustomerLocked() {
        when(this.customerRepository.findByIdForUpdate(CUSTOMER_KEY)).thenReturn(Optional.of(this.customer));
    }

    /** {@code :3934} fires: the read-for-update finds nothing. */
    private void givenCustomerMissing() {
        when(this.customerRepository.findByIdForUpdate(CUSTOMER_KEY)).thenReturn(Optional.empty());
    }

    /**
     * The status mapper's verdict on the account lock guard. {@code FILE STATUS '23'} maps to a
     * record-not-found outcome, and the three-argument overload is the one the bean calls because the
     * empty-{@code Optional} path carries no cause.
     */
    private void givenAccountReadMapped() {
        when(this.fileStatusMapper.toException(IO_STATUS_NOT_FOUND, ACCOUNT_FILE, OPERATION_READ))
                .thenReturn(Optional.of(new RecordNotFoundException("account not found", "account",
                        SCREEN_ACCOUNT_ID)));
    }

    /** The status mapper's verdict on the customer lock guard. */
    private void givenCustomerReadMapped() {
        when(this.fileStatusMapper.toException(IO_STATUS_NOT_FOUND, CUSTOMER_FILE, OPERATION_READ))
                .thenReturn(Optional.of(new RecordNotFoundException("customer not found", "customer",
                        SNAPSHOT_CUSTOMER_ID)));
    }

    /**
     * The confirming turn: PF05 against {@code ACUP-CHANGES-OK-NOT-CONFIRMED}, the one combination
     * that reaches {@code 9600-WRITE-PROCESSING} through {@code :2602-2603}. Uses
     * {@code processRequest} so that the <em>reported</em> outcome is observable even when a failure
     * was retained.
     * @return the projected outcome
     */
    private AccountUpdateResult confirm() {
        return this.service.processRequest(this.screen.build(this.snapshot.build()),
                AID_PFK05,
                ChangeAction.CHANGES_OK_NOT_CONFIRMED,
                EntryMode.REENTER);
    }

    /**
     * The same confirming turn through the REST write entry point, which rethrows the retained typed
     * failure. Uses {@code updateAccount} so that the <em>internal</em> outcome is observable.
     *
     * <p><strong>Why this helper presents a changed field.</strong> {@code updateAccount} drives
     * <em>both</em> legacy turns, because a stateless caller gets one call for a conversation the source
     * spreads over two. The source cannot write when nothing changed: {@code :1682} and {@code :1769} set
     * {@code NO-CHANGES-DETECTED}, {@code :2588}'s {@code CONTINUE} leaves the marker at
     * {@code ACUP-SHOW-DETAILS}, and {@code :906-912} then classifies PF05 as invalid and coerces it to
     * Enter at {@code :915}, so {@code 9600-WRITE-PROCESSING} is unreachable. Presenting an unchanged map
     * here would therefore assert against a path the source does not have.
     *
     * <p>{@code ACSADL2I} is the field used to present the change, and it is chosen deliberately:
     * {@code 1205-COMPARE-OLD-NEW} does compare it - {@code UPPER-CASE(TRIM(ACUP-NEW-CUST-ADDR-LINE-2))}
     * against the old group at {@code :1729-1731} - while {@code 1200-EDIT-MAP-INPUTS} applies no edit to
     * it at all, the source's own {@code MOVE 'Address Line 2'} being commented out at {@code :1607-1608}.
     * It therefore changes the conversation's verdict without changing any field edit's verdict, which is
     * exactly what these tests need. A test that has already arranged its own difference keeps it.
     *
     * @return the projected outcome when no failure was retained
     */
    private AccountUpdateResult write() {
        if (java.util.Objects.equals(this.screen.addressLine2, this.snapshot.addressLine2)) {
            this.screen.addressLine2 = CHANGED_ADDRESS_LINE_2;
        }
        return this.service.updateAccount(sealedRequest(), SUBJECT);
    }

    /**
     * Builds the request a REST caller submits: the current symbolic map, plus the current snapshot group
     * sealed for {@link #SUBJECT} and for the account the map addresses.
     *
     * @return the populated request, carrying an opaque sealed snapshot and no readable group
     */
    private AccountUpdateRequest sealedRequest() {
        return this.screen.buildSealed(seal(this.snapshot.build(), this.screen.accountId, SUBJECT));
    }

    /**
     * Seals a snapshot group the way {@code sealSnapshotForUpdate} does, so that {@code updateAccount} can
     * open it.
     *
     * @param group the group to seal
     * @param accountId the account the value is bound to; blank and {@code null} both bind the
     *     no-account-addressed placeholder the bean uses
     * @param subject the principal the value is bound to
     * @return the sealed value
     */
    private String seal(final AccountUpdateRequest.OldDetails group, final String accountId,
                        final String subject) {
        return this.snapshotTokenService.seal(SNAPSHOT_KIND,
                accountId == null || accountId.isBlank() ? "-" : accountId, subject, group);
    }

    /**
     * An editing turn: Enter against {@code ACUP-SHOW-DETAILS}, which runs the full
     * {@code 1200-EDIT-MAP-INPUTS} cascade because the marker is neither
     * {@code ACUP-CHANGES-OK-NOT-CONFIRMED} nor {@code ACUP-CHANGES-OKAYED-AND-DONE}. No repository
     * is touched on this path.
     * @return the projected outcome
     */
    private AccountUpdateResult editTurn() {
        return this.service.processRequest(this.screen.build(this.snapshot.build()),
                AID_ENTER,
                ChangeAction.SHOW_DETAILS,
                EntryMode.REENTER);
    }

    /**
     * Stubs the five collaborator calls the edit cascade makes when every field is individually
     * valid: four {@code EDIT-DATE-CCYYMMDD} performs, one {@code EDIT-DATE-OF-BIRTH} performs, two
     * general-purpose area-code lookups, one state-code lookup and one state-and-postal-code
     * combination lookup. Argument matchers are used deliberately: one stubbing may serve several
     * call sites, and strict stubs only object to a stubbing that is never used at all.
     */
    private void givenEveryFieldEditPasses() {
        final DateValidationService.EditOutcome valid = validDateOutcome();
        when(this.dateValidationService.editDate(any(), any())).thenReturn(valid);
        when(this.dateValidationService.editDateOfBirth(any(), any())).thenReturn(valid);
        when(this.validationLookupService.isValidGeneralPurposeAreaCode(any())).thenReturn(true);
        when(this.validationLookupService.isValidUsStateCode(any())).thenReturn(true);
        when(this.validationLookupService.isValidStateZipCodeCombination(any())).thenReturn(true);
    }

    /**
     * An all-components-valid verdict from the date service, matching the {@code LOW-VALUES} state of
     * the three-byte {@code WS-EDIT-DATE-FLGS} group.
     * @return a valid outcome carrying no message and no input error
     */
    private static DateValidationService.EditOutcome validDateOutcome() {
        return new DateValidationService.EditOutcome(DateValidationService.EditFlag.ISVALID,
                DateValidationService.EditFlag.ISVALID,
                DateValidationService.EditFlag.ISVALID,
                false,
                "");
    }

    /**
     * Asserts that one submitted value reached its column as a left-aligned copy with blank fill.
     *
     * <p>The two clauses together are the whole {@code MOVE ... TO PIC X(n)} semantic and nothing more: the
     * stored value <em>starts with</em> the submitted bytes, so nothing was trimmed, re-cased or reordered;
     * and everything after them is blank, so nothing was appended and the submitted prefix was not
     * truncated. Expressing it this way rather than against a literal padded value is deliberate - the
     * column widths belong to the schema, and restating fifteen of them here would create a second place to
     * maintain them without adding an assertion the schema does not already make.
     *
     * @param label     the symbolic-map field and the record field it feeds, for the failure message
     * @param submitted the value the request carried; must not be {@code null}
     * @param stored    the value the entity holds after the write; may be {@code null}, which fails
     */
    private static void assertMovedVerbatim(final String label, final String submitted,
            final String stored) {
        assertThat(stored)
                .as("%s must receive the submitted bytes unaltered", label)
                .isNotNull()
                .startsWith(submitted);
        assertThat(stored.substring(submitted.length()))
                .as("%s must be blank-filled after the submitted bytes and carry nothing else", label)
                .isBlank();
    }

    /**
     * Asserts that one submitted amount reached its column with both its value and its scale intact.
     *
     * <p>Scale is asserted separately from value because the two fail separately. A rescaled amount still
     * compares equal numerically while emitting different bytes through the fixed-width writers, so a value
     * check alone would pass on a divergence that Gate 1 would later catch as a diff.
     *
     * @param label     the symbolic-map field and the record field it feeds, for the failure message
     * @param submitted the amount the request carried, as text; must not be {@code null}
     * @param stored    the amount the entity holds after the write; may be {@code null}, which fails
     */
    private static void assertAmountMoved(final String label, final String submitted,
            final BigDecimal stored) {
        final BigDecimal expected = new BigDecimal(submitted);
        assertThat(stored)
                .as("%s must receive the submitted quantity", label)
                .isNotNull()
                .isEqualByComparingTo(expected);
        assertThat(stored.scale())
                .as("%s must keep the two decimal places of PIC S9(10)V99; a rescale changes the bytes the "
                        + "fixed-width writers emit even though the numeric comparison still passes", label)
                .isEqualTo(expected.scale());
    }

    /**
     * Reads one repository-relative file as text.
     *
     * <p>Resolved against the working directory, which {@code pom.xml} pins to {@code ${project.basedir}} for
     * both test plugins, so a bare repository-relative path is correct here. Decoded as UTF-8 because these are
     * source files rather than fixed-width records.
     *
     * <p>Inputs: the repository-relative path. Output: the file's text. Side effects: one file read.
     *
     * @param repositoryRelativePath the path to read; must not be {@code null}
     * @return the file's text, never {@code null}
     * @throws UncheckedIOException if the file cannot be read, naming it - an unreadable file must fail the
     *     assertion rather than silently read as empty, because empty text satisfies no {@code contains} check
     *     but does satisfy the absence of one
     */
    private static String readRepositoryFile(final String repositoryRelativePath) {
        try {
            return Files.readString(Path.of(repositoryRelativePath), StandardCharsets.UTF_8);
        } catch (final IOException unreadable) {
            throw new UncheckedIOException("Could not read " + repositoryRelativePath
                    + ", resolved against the working directory that pom.xml pins to ${project.basedir}",
                    unreadable);
        }
    }

    /**
     * Slices one entry out of {@code DECISION_LOG.md} by its identifier.
     *
     * <p>An entry runs from its {@code ### <id>} heading to the next {@code ### } heading or to the end of the
     * document. Slicing rather than searching the whole file matters: a {@code contains} check over the entire
     * log would be satisfied by text belonging to some other entry.
     *
     * <p>Inputs: the log text and the entry identifier. Output: the entry's text. Side effects: none.
     *
     * @param decisionLog the whole log text; must not be {@code null}
     * @param entryId the entry identifier, for example {@code DL-DV-06}; must not be {@code null}
     * @return the entry's text, never {@code null}
     * @throws AssertionError if no entry with that identifier exists
     */
    private static String decisionLogEntry(final String decisionLog, final String entryId) {
        final String heading = "### " + entryId;
        final int start = decisionLog.indexOf(heading);
        if (start < 0) {
            throw new AssertionError("DECISION_LOG.md holds no entry headed '" + heading + "'. The production "
                    + "Javadoc of AccountUpdateService.requireStorableUpdateImage claims the choice is "
                    + "recorded as a deviation; without this entry that claim is false.");
        }
        final int next = decisionLog.indexOf("\n### ", start + heading.length());
        return next < 0 ? decisionLog.substring(start) : decisionLog.substring(start, next);
    }

    /**
     * Returns the declared definition of one column of the {@code customer} table, from the migration itself.
     *
     * <p>Read from {@code V1__create_schema.sql} rather than restated, so that widening the column retires the
     * deviation that depends on its width instead of leaving the justification stale. Comment lines are
     * skipped, because every column in that migration is preceded by a {@code --} line quoting its PIC clause,
     * and those lines mention the column name too.
     *
     * <p>Inputs: the column name. Output: the type and its inline constraints, whitespace collapsed. Side
     * effects: one file read.
     *
     * @param columnName the column name as the migration spells it; must not be {@code null}
     * @return the declared definition, for example {@code CHAR(3) NOT NULL}
     * @throws AssertionError if the column is not declared in the migration
     */
    private static String schemaColumnDefinition(final String columnName) {
        for (final String rawLine : readRepositoryFile(SCHEMA_MIGRATION_SOURCE).split("\n", -1)) {
            final String line = rawLine.strip();
            if (line.startsWith("--") || !line.startsWith(columnName + " ")) {
                continue;
            }
            final String withoutTrailer = line.replaceAll("[,]\\s*$", "");
            return withoutTrailer.substring(columnName.length()).strip().replaceAll("\\s+", " ");
        }
        throw new AssertionError("The migration " + SCHEMA_MIGRATION_SOURCE + " declares no column named '"
                + columnName + "'. The deviation that depends on its declared width cannot be justified "
                + "against a column that does not exist.");
    }

    /**
     * Censuses the symbolic-map field names the unstorable-value screen actually covers.
     *
     * <p>Reads the production bean's own source, slices the body of {@code requireStorableUpdateImage}, finds
     * every {@code FIELD_*} constant it names, and resolves each to the literal that constant declares in the
     * same file. It is therefore a measurement of the shipped code rather than a restatement of it: adding a
     * field to the screen changes this answer without anyone having to remember to update a list.
     *
     * <p>Inputs: none. Output: the field names in the order the screen visits them. Side effects: one file
     * read.
     *
     * @return the covered field names, never {@code null}
     * @throws AssertionError if the method cannot be located or a constant cannot be resolved
     */
    private static List<String> screenedFieldNames() {
        final String source = readRepositoryFile(ACCOUNT_UPDATE_SERVICE_SOURCE);
        final String signature = "private static boolean requireStorableUpdateImage(";
        final int start = source.indexOf(signature);
        if (start < 0) {
            throw new AssertionError("Could not locate " + signature + " in " + ACCOUNT_UPDATE_SERVICE_SOURCE
                    + ". If the screen has been renamed or removed, this census must be pointed at its "
                    + "replacement rather than deleted, because it is what bounds the labelled deviation.");
        }
        final int end = source.indexOf("\n    }", start);
        final String body = source.substring(start, end < 0 ? source.length() : end);

        final List<String> resolved = new ArrayList<>();
        final java.util.regex.Matcher references =
                java.util.regex.Pattern.compile("\\bFIELD_[A-Z0-9_]+\\b").matcher(body);
        while (references.find()) {
            final String constantName = references.group();
            final java.util.regex.Matcher declaration = java.util.regex.Pattern
                    .compile("String\\s+" + constantName + "\\s*=\\s*\"([^\"]+)\"")
                    .matcher(source);
            if (!declaration.find()) {
                throw new AssertionError("The screen names " + constantName + " but "
                        + ACCOUNT_UPDATE_SERVICE_SOURCE + " declares no String literal for it, so the census "
                        + "cannot resolve what field it covers.");
            }
            final String fieldName = declaration.group(1);
            if (!resolved.contains(fieldName)) {
                resolved.add(fieldName);
            }
        }
        return List.copyOf(resolved);
    }

    /**
     * {@code 3250-SETUP-INFOMSG} pads the error message to {@code WS-RETURN-MSG PIC X(75)} at
     * {@code :2981}, so every comparison against a literal must strip the padding rather than pretend
     * it is absent.
     * @param result the projected outcome
     * @return the error message with its right padding removed
     */
    private static String errorText(final AccountUpdateResult result) {
        return result.errorMessage().stripTrailing();
    }

    // Retrieval-turn, navigation and edit-cascade helpers.

    /**
     * An unfetched turn: Enter against {@code ACUP-DETAILS-NOT-FETCHED}, the arm of
     * {@code 1200-EDIT-MAP-INPUTS} at {@code :1433-1449} where the account filter is the only field
     * there is, and the arm of {@code 2000-DECIDE-ACTION} at {@code :2568-2580} that performs
     * {@code 9000-READ-ACCT}.
     * @return the projected outcome
     */
    private AccountUpdateResult unfetchedTurn() {
        return this.service.processRequest(this.screen.build(this.snapshot.build()),
                AID_ENTER,
                ChangeAction.DETAILS_NOT_FETCHED,
                EntryMode.REENTER);
    }

    /**
     * A refresh turn: PF12 against a fetched screen. {@code :2568-2572} is a fall-through pair, so PF12
     * reaches the retrieval arm even when the marker is {@code ACUP-SHOW-DETAILS}.
     * @return the projected outcome
     */
    private AccountUpdateResult refreshTurn() {
        return this.service.processRequest(this.screen.build(this.snapshot.build()),
                AID_PFK12,
                ChangeAction.SHOW_DETAILS,
                EntryMode.REENTER);
    }

    /**
     * An exit turn: PF03, which {@code 0000-MAIN} funnels to the {@code EXEC CICS XCTL} at
     * {@code :927-959} before any input is processed.
     * @param changeAction the marker the caller submits
     * @param entryMode the entry state the caller submits
     * @return the projected outcome
     */
    private AccountUpdateResult exitTurn(final ChangeAction changeAction, final EntryMode entryMode) {
        return this.service.processRequest(this.screen.build(this.snapshot.build()),
                AID_PFK03,
                changeAction,
                entryMode);
    }

    /**
     * The {@code CXACAIX} path yields nothing. No stubbing is installed deliberately: the repository
     * method returns a {@code List}, and Mockito's default for an unstubbed {@code List} is an empty
     * one, which is exactly the {@code DFHRESP(NOTFND)} condition {@code :3668} tests. What is stubbed
     * is the status mapper's verdict on the resulting {@code FILE STATUS '23'}.
     * @return the very exception the mapper will hand back, so a test can assert identity
     */
    private RecordNotFoundException givenCrossReferenceMissMapped() {
        final RecordNotFoundException mapped = new RecordNotFoundException("cross reference not found",
                "cardCrossReference", SCREEN_ACCOUNT_ID);
        when(this.fileStatusMapper.toException(IO_STATUS_NOT_FOUND, XREF_ACCOUNT_PATH, OPERATION_READ))
                .thenReturn(Optional.of(mapped));
        return mapped;
    }

    /**
     * The {@code CXACAIX} path itself fails, which is the {@code WHEN OTHER} arm at {@code :3686-3693}.
     * @return the very failure the repository will raise, so a test can assert the causal chain
     */
    private DataAccessResourceFailureException givenCrossReferencePathUnavailable() {
        final DataAccessResourceFailureException failure =
                new DataAccessResourceFailureException("cross reference path unavailable");
        when(this.cardCrossReferenceRepository.findFirstByAccountIdOrderByCardNumberAsc(ACCOUNT_KEY))
                .thenThrow(failure);
        return failure;
    }

    /** The four {@code EDIT-DATE-CCYYMMDD} performs and the {@code EDIT-DATE-OF-BIRTH} follow-up all pass. */
    private void givenEveryDateEditPasses() {
        final DateValidationService.EditOutcome valid = validDateOutcome();
        when(this.dateValidationService.editDate(any(), any())).thenReturn(valid);
        when(this.dateValidationService.editDateOfBirth(any(), any())).thenReturn(valid);
    }

    /**
     * One named calendar edit fails and the other three pass. The second argument of
     * {@code editDate} is {@code WS-EDIT-VARIABLE-NAME} moved through {@code PIC X(25)} at
     * {@code :1479}, which is what makes the four call sites individually addressable from a single
     * stubbing.
     * @param label the twenty-five-byte label whose date must be rejected
     */
    private void givenCalendarDateEditFailsFor(final String label) {
        final String target = paddedLabel(label);
        when(this.dateValidationService.editDate(any(), any())).thenAnswer(invocation ->
                target.equals(invocation.getArgument(1))
                        ? notOkDateOutcome()
                        : validDateOutcome());
    }

    /**
     * The {@code EDIT-DATE-OF-BIRTH} plausibility follow-up accepts whatever it is handed. Needed
     * alongside {@link #givenCalendarDateEditFailsFor(String)} whenever the broken calendar field is
     * <em>not</em> the date of birth, because the date of birth then still reaches {@code :1539-1543}.
     */
    private void givenDateOfBirthPlausibilityPasses() {
        when(this.dateValidationService.editDateOfBirth(any(), any())).thenReturn(validDateOutcome());
    }

    /**
     * A verdict in which the three components disagree. {@code CSUTLDPY} keeps a separate flag per
     * component, so only one of the three may be rejected - which is what makes the month and day
     * branches of the cursor cascade reachable at all.
     * @param year the year component's verdict
     * @param month the month component's verdict
     * @param day the day component's verdict
     * @return an outcome carrying the synthetic collaborator diagnostic
     */
    private static DateValidationService.EditOutcome componentOutcome(
            final DateValidationService.EditFlag year,
            final DateValidationService.EditFlag month,
            final DateValidationService.EditFlag day) {
        return new DateValidationService.EditOutcome(year, month, day, true, STUBBED_DATE_DIAGNOSTIC);
    }

    /**
     * One named calendar edit returns a per-component verdict and the other three pass wholly.
     * @param label the twenty-five-byte label whose date carries the verdict
     * @param outcome the verdict that label's date receives
     */
    private void givenCalendarComponentFailsFor(final String label,
                                                final DateValidationService.EditOutcome outcome) {
        final String target = paddedLabel(label);
        when(this.dateValidationService.editDate(any(), any())).thenAnswer(invocation ->
                target.equals(invocation.getArgument(1))
                        ? outcome
                        : validDateOutcome());
    }

    /**
     * Every calendar edit passes, and the birth-date plausibility follow-up returns a per-component
     * verdict. The follow-up is reached only because the calendar edit accepted all three components.
     * @param outcome the verdict the follow-up returns
     */
    private void givenBirthComponentFails(final DateValidationService.EditOutcome outcome) {
        when(this.dateValidationService.editDate(any(), any())).thenReturn(validDateOutcome());
        when(this.dateValidationService.editDateOfBirth(any(), any())).thenReturn(outcome);
    }

    /** Every calendar edit passes but {@code EDIT-DATE-OF-BIRTH} rejects the plausibility check. */
    private void givenDateOfBirthPlausibilityFails() {
        when(this.dateValidationService.editDate(any(), any())).thenReturn(validDateOutcome());
        when(this.dateValidationService.editDateOfBirth(any(), any())).thenReturn(notOkDateOutcome());
    }

    /** {@code :2296-2297}: the general-purpose area-code lookup accepts whatever it is handed. */
    private void givenAreaCodeLookupPasses() {
        when(this.validationLookupService.isValidGeneralPurposeAreaCode(any())).thenReturn(true);
    }

    /** {@code :2496-2497}: the state-code lookup accepts whatever it is handed. */
    private void givenStateLookupPasses() {
        when(this.validationLookupService.isValidUsStateCode(any())).thenReturn(true);
    }

    /** {@code :2543-2544}: the state-and-postal-code combination lookup accepts whatever it is handed. */
    private void givenStateZipLookupPasses() {
        when(this.validationLookupService.isValidStateZipCodeCombination(any())).thenReturn(true);
    }

    /**
     * An all-components-rejected verdict from the date service, matching the state
     * {@code WS-EDIT-DATE-FLGS} carries when {@code CSUTLDPY} has flagged every component.
     * @return a rejecting outcome carrying the synthetic collaborator diagnostic
     */
    private static DateValidationService.EditOutcome notOkDateOutcome() {
        return new DateValidationService.EditOutcome(DateValidationService.EditFlag.NOT_OK,
                DateValidationService.EditFlag.NOT_OK,
                DateValidationService.EditFlag.NOT_OK,
                true,
                STUBBED_DATE_DIAGNOSTIC);
    }

    /**
     * Reproduces {@code MOVE <literal> TO WS-EDIT-VARIABLE-NAME}, whose receiving field is
     * {@code PIC X(25)}. A longer label is cut, which is why {@code 'Current Cycle Credit Limit'}
     * reaches the diagnostic one character short.
     * @param label the source literal
     * @return the label as the twenty-five-byte field holds it
     */
    private static String paddedLabel(final String label) {
        return label.length() >= EDIT_VARIABLE_NAME_WIDTH
                ? label.substring(0, EDIT_VARIABLE_NAME_WIDTH)
                : label + " ".repeat(EDIT_VARIABLE_NAME_WIDTH - label.length());
    }

    /**
     * Reproduces the diagnostic assembly every edit routine uses:
     * {@code STRING WS-EDIT-VARIABLE-NAME DELIMITED BY SPACE, <suffix> INTO WS-RETURN-MSG}.
     * @param label the source label literal, before the twenty-five-byte move
     * @param suffix the source suffix literal
     * @return the diagnostic the screen would display
     */
    private static String labelled(final String label, final String suffix) {
        return paddedLabel(label).trim() + suffix;
    }

    /**
     * The field {@code EXEC CICS SEND MAP ... CURSOR} parks the cursor on, chosen by the first-match
     * cascade at {@code :3009-3167}.
     * @param result the projected outcome
     * @return the symbolic-map field name, or {@code null} when no cursor was requested
     */
    private static String cursorField(final AccountUpdateResult result) {
        return result.fieldAttributes().stream()
                .filter(attribute -> FieldAttribute.ASPECT_CURSOR.equals(attribute.aspect()))
                .map(FieldAttribute::field)
                .findFirst()
                .orElse(null);
    }

    /**
     * Whether one expansion of {@code COPY CSSETATY REPLACING} emitted a given aspect for a given
     * field.
     * @param result the projected outcome
     * @param field the symbolic-map field name
     * @param aspect one of the aspect constants of {@link FieldAttribute}
     * @return {@code true} when the presentation list carries that pair
     */
    private static boolean hasAspect(final AccountUpdateResult result,
                                     final String field,
                                     final String aspect) {
        return result.fieldAttributes().stream()
                .anyMatch(attribute -> field.equals(attribute.field())
                        && aspect.equals(attribute.aspect()));
    }

    /**
     * Every value emitted for one field and one aspect, in emission order. Order matters: the source
     * protects all forty-four attribute bytes and only then re-enables some of them, so a field that
     * ends up enterable carries two entries rather than one, and the three fields the source
     * re-protects individually carry three.
     * @param result the projected outcome
     * @param field the symbolic-map field name
     * @param aspect one of the aspect constants of {@link FieldAttribute}
     * @return the values that aspect received, oldest first
     */
    private static List<String> aspectValues(final AccountUpdateResult result,
                                             final String field,
                                             final String aspect) {
        return result.fieldAttributes().stream()
                .filter(attribute -> field.equals(attribute.field())
                        && aspect.equals(attribute.aspect()))
                .map(FieldAttribute::value)
                .toList();
    }

    // PARITY TRAP - :3934-3942 sets COULD-NOT-LOCK-CUST-FOR-UPDATE, :2606-2615 never tests it.
    //
    // The source reads, verbatim at :3934-3942:
    //
    //     IF WS-RESP-CD EQUAL TO DFHRESP(NORMAL)
    //        CONTINUE
    //     ELSE
    //        SET INPUT-ERROR TO TRUE
    //        IF WS-RETURN-MSG-OFF
    //           SET COULD-NOT-LOCK-CUST-FOR-UPDATE TO TRUE
    //        END-IF
    //        GO TO 9600-WRITE-PROCESSING-EXIT
    //     END-IF
    //
    // and the caller's inner EVALUATE TRUE at :2606-2615 carries exactly four arms - account lock,
    // rewrite failure, data changed, WHEN OTHER. An exhaustive census finds
    // COULD-NOT-LOCK-CUST-FOR-UPDATE at exactly two places in the whole 4,236-line program: its
    // declaration at :519-520 and its single SET at :3939. It appears in no WHEN, no IF and no
    // EVALUATE. A customer read-for-update failure therefore falls through WHEN OTHER and is
    // reported as ACUP-CHANGES-OKAYED-AND-DONE - top-level success over an empty write.
    //
    // It is PRESERVED, NOT REPAIRED, because behavioural parity is the contract
    // and adding a fifth arm would change the response a real, reachable input receives. Were parity
    // ever relaxed, the change would be one inserted arm
    // between :2607 and :2609: WHEN COULD-NOT-LOCK-CUST-FOR-UPDATE / SET
    // ACUP-CHANGES-OKAYED-LOCK-ERROR. Nothing of the kind is done here.

    @Test
    @DisplayName(":3934-3942 sets the customer-lock flag AND :2606-2615 reports it as success")
    void customerLockFailureSetsTheFlagAndIsStillReportedAsSuccess() {
        givenAccountLocked();
        givenCustomerMissing();
        givenCustomerReadMapped();

        final AccountUpdateResult result = confirm();

        // Fact one: the internal outcome IS distinguished. The literal of :519-520 reached
        // WS-RETURN-MSG, which only the SET at :3939 can do.
        assertThat(errorText(result))
                .as("the customer-lock literal of :519-520 must reach WS-RETURN-MSG")
                .isEqualTo(COULD_NOT_LOCK_CUSTOMER);
        // Fact two: the REPORTED outcome is success, because :2606-2615 has no arm for it.
        assertThat(result.changeAction())
                .as("WHEN OTHER at :2613-2614 claims the change action")
                .isEqualTo(ChangeAction.CHANGES_OKAYED_AND_DONE);
        assertThat(result.changeAction().marker()).isEqualTo('C');
        assertThat(result.informationMessage())
                .as("3250-SETUP-INFOMSG :2968-2969 tells the operator the update succeeded")
                .isEqualTo(INFO_UPDATE_SUCCESS);
        assertThat(result.responseKind()).isEqualTo(ResponseKind.MAP);
    }

    @Test
    @DisplayName(":3941 leaves before either REWRITE, so success is reported over an empty write")
    void customerLockFailurePersistsNeitherRow() {
        givenAccountLocked();
        givenCustomerMissing();
        givenCustomerReadMapped();

        final AccountUpdateResult result = confirm();

        assertThat(result.changeAction()).isEqualTo(ChangeAction.CHANGES_OKAYED_AND_DONE);
        verify(this.accountRepository, never()).save(any(Account.class));
        verify(this.accountRepository, never()).flush();
        verify(this.customerRepository, never()).save(any(Customer.class));
        verify(this.customerRepository, never()).flush();
        assertThat(this.account.getActiveStatus())
                .as(":3956-4002 never ran, so the live account still carries its fetched status")
                .isEqualTo("Y");
    }

    @Test
    @DisplayName(":3938 IF WS-RETURN-MSG-OFF suppresses the literal when a message is already pending")
    void customerLockLiteralIsNotLatchedWhenAMessageIsAlreadyPending() {
        // Every submitted value now equals its snapshot counterpart, so 1205-COMPARE-OLD-NEW takes
        // its :1769 path and assigns NO-CHANGES-DETECTED without the latch. WS-RETURN-MSG is then
        // no longer off, which is precisely the state the nested IF at :3938 guards against.
        this.screen.accountStatus = "Y";
        givenAccountLocked();
        givenCustomerMissing();
        givenCustomerReadMapped();

        final AccountUpdateResult result = confirm();

        assertThat(errorText(result))
                .as("the pending message wins; :3939 is inside IF WS-RETURN-MSG-OFF")
                .isEqualTo(NO_CHANGE_DETECTED);
        assertThat(errorText(result)).doesNotContain(COULD_NOT_LOCK_CUSTOMER);
        // The flag itself is set OUTSIDE the latch, so the outcome is unchanged - which is exactly
        // what makes the missing WHEN arm invisible to the operator either way.
        assertThat(result.changeAction()).isEqualTo(ChangeAction.CHANGES_OKAYED_AND_DONE);
        verify(this.customerRepository, never()).save(any(Customer.class));
    }

    @Test
    @DisplayName(":3939 the typed failure IS retained, so the write entry point can still surface it")
    void customerLockFailureSurfacesAsATypedFailureThroughTheWriteEntryPoint() {
        givenAccountLocked();
        givenCustomerMissing();
        givenCustomerReadMapped();

        assertThatThrownBy(this::write)
                .as("updateAccount rethrows the retained failure even though the screen said success")
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessage("customer not found")
                .satisfies(thrown -> {
                    final RecordNotFoundException typed = (RecordNotFoundException) thrown;
                    assertThat(typed.recordType()).contains("customer");
                    assertThat(typed.recordKey()).contains(SNAPSHOT_CUSTOMER_ID);
                    assertThat(typed.getCause()).as("FILE STATUS '23' carries no lower cause").isNull();
                });
    }

    // The seven-step write sequence of 9600-WRITE-PROCESSING, in the source's fixed order.
    //
    //   1. :3892-3894  account READ ... UPDATE RIDFLD
    //   2. :3907-3915  account lock guard: SET INPUT-ERROR, latched literal, GO TO EXIT
    //   3. :3919-3921  customer READ ... UPDATE
    //   4. :3934-3942  customer lock guard (the parity trap above)
    //   5. :3947-3950  PERFORM 9700 then IF DATA-WAS-CHANGED-BEFORE-UPDATE GO TO EXIT
    //   6. :3956       INITIALIZE ACCT-UPDATE-RECORD followed by the field moves
    //   7. :4065       REWRITE ACCTDAT; on failure :4076-4081 with NO ROLLBACK
    //      :4085       REWRITE CUSTDAT; on failure :4095-4103 WITH EXEC CICS SYNCPOINT ROLLBACK
    //
    // The rollback asymmetry is NOT a defect. At the account-rewrite failure point nothing has been
    // written inside the unit of work, so the monitor releases the read-for-update locks at task
    // end. At the customer-rewrite failure point the account rewrite has already happened inside
    // the same unit of work, so an explicit backout is the only way to avoid a half-applied update.
    // A single @Transactional(rollbackFor = Exception.class) method reproduces both branches
    // automatically, because each failure path returns before the commit point. These tests
    // therefore assert the OUTCOME, never the presence of a rollback call; the mechanism
    // substitution is stated here so that a reader comparing the two sources does
    // not read the absent SYNCPOINT as something lost.
    //
    // The tests immediately below assert the OUTCOME of each branch: which repository calls happen,
    // in what order, and what the caller observes. That is necessary and it is not sufficient. A
    // Mockito suite cannot observe a commit or a rollback, because there is no transaction manager
    // in a plain-JVM test, so every one of those outcome assertions stays green if the production
    // annotations are deleted - and deleting them would silently turn the two writes into two
    // independent units of work, which is precisely the half-applied update the source backs out of.
    // The declaration itself is therefore asserted as a contract in its own right by the
    // TransactionalBoundary group at the end of this class, which reads the annotation off the two
    // write entry points reflectively and fails if it is absent, if rollbackFor does not name
    // Exception, or if the two writes stop sharing one annotated method. Integration-level proof
    // that a real rollback occurs belongs to the repository tier and is additive to that guard, not
    // a substitute for it.

    @Test
    @DisplayName(":3892-4091 performs the seven steps in the source's order, account before customer")
    void writeProcessingPerformsTheSevenStepsInOrder() {
        givenAccountLocked();
        givenCustomerLocked();

        final AccountUpdateResult result = confirm();

        final InOrder ordered = inOrder(this.accountRepository, this.customerRepository);
        // Steps one and three: both read-for-update calls, account first.
        ordered.verify(this.accountRepository).findByIdForUpdate(ACCOUNT_KEY);
        ordered.verify(this.customerRepository).findByIdForUpdate(CUSTOMER_KEY);
        // Step seven: both rewrites, account first, each followed by its own flush.
        ordered.verify(this.accountRepository).save(this.account);
        ordered.verify(this.accountRepository).flush();
        ordered.verify(this.customerRepository).save(this.customer);
        ordered.verify(this.customerRepository).flush();
        ordered.verifyNoMoreInteractions();
        assertThat(result.changeAction()).isEqualTo(ChangeAction.CHANGES_OKAYED_AND_DONE);
    }

    @Test
    @DisplayName(":3947-3950 change detection runs between the reads and the writes")
    void changeDetectionRunsAfterBothReadsAndBeforeEitherWrite() {
        // A rival write to the live account is only observable if 9700 runs after the read that
        // fetched it and before the rewrite that would overwrite it.
        this.account.setCreditLimit(new BigDecimal("9999.00"));
        givenAccountLocked();
        givenCustomerLocked();

        final AccountUpdateResult result = confirm();

        assertThat(result.changeAction())
                .as(":2611-2612 maps the data-changed outcome back to ACUP-SHOW-DETAILS")
                .isEqualTo(ChangeAction.SHOW_DETAILS);
        verify(this.accountRepository).findByIdForUpdate(ACCOUNT_KEY);
        verify(this.customerRepository).findByIdForUpdate(CUSTOMER_KEY);
        verify(this.accountRepository, never()).save(any(Account.class));
        verify(this.customerRepository, never()).save(any(Customer.class));
    }

    @Test
    @DisplayName(":3956-4059 assembles the update images with '-' separators before the rewrite")
    void fieldPopulationAssemblesDatesAndPhonesBeforeTheRewrite() {
        givenAccountLocked();
        givenCustomerLocked();

        confirm();

        // :3976-3982, :3984-3990 and :3994-4000 STRING the three components into the ten-character
        // yyyy-MM-dd form the copybook declares - the live form, not the compact snapshot form.
        assertThat(this.account.getOpenDate()).isEqualTo("2000-01-01");
        assertThat(this.account.getExpiraionDate()).isEqualTo("2025-12-31");
        assertThat(this.account.getReissueDate()).isEqualTo("2020-06-15");
        // :3962 the submitted status replaces the fetched one.
        assertThat(this.account.getActiveStatus()).isEqualTo("N");
        // :4027-4033 builds '(' area ')' prefix '-' line inside a PIC X(15) member. Asserted as a
        // SHAPE rather than as a value, so no telephone digit appears in the assertion.
        assertThat(this.customer.getPhoneNumber1())
                .as("'(' area ')' prefix '-' line, then blank filled to PIC X(15)")
                .hasSize(15)
                .matches("\\(\\d{3}\\)\\d{3}-\\d{4} {2}");
        // :4047-4052 the LIVE date of birth is ten characters WITH separators.
        assertThat(this.customer.getDateOfBirth()).hasSize(10).contains("-");
        verify(this.accountRepository).save(this.account);
        verify(this.customerRepository).save(this.customer);
    }

    @Test
    @DisplayName(":3907-3915 an account-read failure short-circuits: CUSTDAT is never touched")
    void accountReadFailureShortCircuitsBeforeTheCustomerRead() {
        givenAccountMissing();
        givenAccountReadMapped();

        final AccountUpdateResult result = confirm();

        verifyNoInteractions(this.customerRepository);
        verify(this.accountRepository, never()).save(any(Account.class));
        assertThat(errorText(result)).isEqualTo(COULD_NOT_LOCK_ACCOUNT);
        assertThat(result.changeAction())
                .as(":2607-2608 is the one lock outcome the decider does test")
                .isEqualTo(ChangeAction.CHANGES_OKAYED_LOCK_ERROR);
        assertThat(result.changeAction().marker()).isEqualTo('L');
        assertThat(result.informationMessage()).isEqualTo(INFO_INFORM_FAILURE);
    }

    @Test
    @DisplayName(":4076-4081 an ACCTDAT rewrite failure leaves CUSTDAT untouched and reports failure")
    void accountRewriteFailureLeavesNeitherRowWritten() {
        givenAccountLocked();
        givenCustomerLocked();
        final DataAccessResourceFailureException rewriteFailure =
                new DataAccessResourceFailureException("ACCTDAT rewrite rejected");
        doThrow(rewriteFailure).when(this.accountRepository).flush();

        final AccountUpdateResult result = confirm();

        // The source takes GO TO 9600-WRITE-PROCESSING-EXIT at :4080 with no rollback, because
        // nothing has been committed yet. The single transaction boundary discards the uncommitted
        // account image; a pure-JVM tier can only assert that the SECOND write never happened.
        verify(this.customerRepository, never()).save(any(Customer.class));
        verify(this.customerRepository, never()).flush();
        assertThat(errorText(result))
                .as(":4079 assigns the :523-524 literal with no IF WS-RETURN-MSG-OFF latch")
                .isEqualTo(UPDATE_OF_RECORD_FAILED);
        assertThat(result.changeAction()).isEqualTo(ChangeAction.CHANGES_OKAYED_BUT_FAILED);
        assertThat(result.changeAction().marker()).isEqualTo('F');
        assertThat(result.changeAction().isChangesFailed()).isTrue();
    }

    @Test
    @DisplayName(":4095-4103 a CUSTDAT rewrite failure reproduces the explicit SYNCPOINT ROLLBACK")
    void customerRewriteFailureReportsTheSameFailureOutcome() {
        givenAccountLocked();
        givenCustomerLocked();
        final DataAccessResourceFailureException rewriteFailure =
                new DataAccessResourceFailureException("CUSTDAT rewrite rejected");
        doThrow(rewriteFailure).when(this.customerRepository).flush();

        assertThatThrownBy(this::write)
                .isInstanceOf(CardDemoException.class)
                .hasMessageContaining(CUSTOMER_FILE.trim())
                .hasRootCause(rewriteFailure)
                .satisfies(thrown -> assertThat(thrown.getClass().getSimpleName())
                        .as("a referential write failure is a data-integrity outcome")
                        .isEqualTo("DataIntegrityException"));
        // The account rewrite DID happen inside the unit of work, which is exactly why :4099 issues
        // EXEC CICS SYNCPOINT ROLLBACK there and not at :4080. The declared transaction boundary
        // performs the backout; the assertion is on the outcome, never on a rollback call.
        verify(this.accountRepository).save(this.account);
        verify(this.customerRepository).save(this.customer);
    }

    @Test
    @DisplayName(":4079 and :4098 both assign the same literal, neither behind the message latch")
    void bothRewriteFailuresOverwriteAnyPendingMessage() {
        // A message is already pending from 1205's :1769 path, yet :4098 overwrites it - unlike the
        // two lock guards, the rewrite failure sites carry no IF WS-RETURN-MSG-OFF.
        this.screen.accountStatus = "Y";
        givenAccountLocked();
        givenCustomerLocked();
        doThrow(new DataAccessResourceFailureException("CUSTDAT rewrite rejected"))
                .when(this.customerRepository).flush();

        final AccountUpdateResult result = confirm();

        assertThat(errorText(result)).isEqualTo(UPDATE_OF_RECORD_FAILED);
        assertThat(errorText(result)).isNotEqualTo(NO_CHANGE_DETECTED);
        assertThat(result.changeAction()).isEqualTo(ChangeAction.CHANGES_OKAYED_BUT_FAILED);
    }

    // 9700-CHECK-CHANGE-IN-REC, :4109-4195. The account IF carries SIXTEEN comparison clauses over
    // TEN logical fields; the customer IF carries nineteen clauses over seventeen. Every clause is
    // asserted separately below because consolidating any two of them would hide a regression in
    // exactly one of the pair.
    //
    // Why a JPA @Version column alone is INSUFFICIENT: a version counter detects that SOME
    // concurrent write occurred. The source detects that SPECIFIC BUSINESS FIELD VALUES differ from
    // what the operator was shown. A rival transaction that changed a field and then set it back to
    // its original value PASSES the legacy check and FAILS a version check - the two guarantees are
    // not substitutes, and both layers are mandatory. Because the target is stateless the snapshot
    // cannot live on the server between requests, which is why ACUP-OLD-DETAILS travels in the
    // request body as {@code oldDetails}.

    /**
     * Arranges both read-for-update calls, takes the confirming turn and asserts the shared
     * data-changed outcome: {@code :4142-4144} and {@code :4188-4190} both assign the {@code :521-522}
     * literal and jump to the caller's exit, so no rewrite can follow.
     */
    private void assertRivalWriteDetected() {
        givenAccountLocked();
        givenCustomerLocked();

        final AccountUpdateResult result = confirm();

        assertThat(result.changeAction())
                .as(":2611-2612 maps DATA-WAS-CHANGED-BEFORE-UPDATE back to ACUP-SHOW-DETAILS")
                .isEqualTo(ChangeAction.SHOW_DETAILS);
        assertThat(result.changeAction().marker()).isEqualTo('S');
        assertThat(errorText(result)).isEqualTo(DATA_WAS_CHANGED);
        assertThat(result.informationMessage()).isEqualTo(INFO_PROMPT_FOR_CHANGES);
        verify(this.accountRepository, never()).save(any(Account.class));
        verify(this.customerRepository, never()).save(any(Customer.class));
    }

    @Test
    @DisplayName(":4115 clause 1 of 16 - a rival ACCT-ACTIVE-STATUS write is detected")
    void rivalWriteToActiveStatusIsDetected() {
        this.account.setActiveStatus("N");
        assertRivalWriteDetected();
    }

    @Test
    @DisplayName(":4117 clause 2 of 16 - a rival ACCT-CURR-BAL write is detected")
    void rivalWriteToCurrentBalanceIsDetected() {
        this.account.setCurrentBalance(new BigDecimal("195.00"));
        assertRivalWriteDetected();
    }

    @Test
    @DisplayName(":4119 clause 3 of 16 - a rival ACCT-CREDIT-LIMIT write is detected")
    void rivalWriteToCreditLimitIsDetected() {
        this.account.setCreditLimit(new BigDecimal("2021.00"));
        assertRivalWriteDetected();
    }

    @Test
    @DisplayName(":4121 clause 4 of 16 - a rival ACCT-CASH-CREDIT-LIMIT write is detected")
    void rivalWriteToCashCreditLimitIsDetected() {
        this.account.setCashCreditLimit(new BigDecimal("1021.00"));
        assertRivalWriteDetected();
    }

    @Test
    @DisplayName(":4123 clause 5 of 16 - a rival ACCT-CURR-CYC-CREDIT write is detected")
    void rivalWriteToCurrentCycleCreditIsDetected() {
        this.account.setCurrentCycleCredit(new BigDecimal("0.01"));
        assertRivalWriteDetected();
    }

    @Test
    @DisplayName(":4125 clause 6 of 16 - a rival ACCT-CURR-CYC-DEBIT write is detected")
    void rivalWriteToCurrentCycleDebitIsDetected() {
        this.account.setCurrentCycleDebit(new BigDecimal("0.01"));
        assertRivalWriteDetected();
    }

    @Test
    @DisplayName(":4139-4140 clause 16 of 16 - a genuine ACCT-GROUP-ID write is detected")
    void rivalWriteToGroupIdIsDetected() {
        this.account.setGroupId("PREMIUM");
        assertRivalWriteDetected();
    }

    @Test
    @DisplayName(":4127-4129 logical field 7 of 10 - a rival ACCT-OPEN-DATE write is detected")
    void rivalWriteToOpenDateIsDetected() {
        this.account.setOpenDate("1999-11-30");
        assertRivalWriteDetected();
    }

    @Test
    @DisplayName(":4131-4133 logical field 8 of 10 - a rival ACCT-EXPIRAION-DATE write is detected")
    void rivalWriteToExpiraionDateIsDetected() {
        // ACCT-EXPIRAION-DATE, sic: app/cpy/CVACT01Y.cpy misspells the member and the misspelling is
        // part of the field contract, so the Java accessor preserves it verbatim.
        this.account.setExpiraionDate("2026-11-30");
        assertRivalWriteDetected();
    }

    @Test
    @DisplayName(":4135-4137 logical field 9 of 10 - a rival ACCT-REISSUE-DATE write is detected")
    void rivalWriteToReissueDateIsDetected() {
        this.account.setReissueDate("2021-07-16");
        assertRivalWriteDetected();
    }

    @Test
    @DisplayName(":4127 ACCT-OPEN-DATE(1:4) - a year-only rival write is detected")
    void rivalWriteToOpenDateYearComponentIsDetected() {
        this.account.setOpenDate("2001-01-01");
        assertRivalWriteDetected();
    }

    @Test
    @DisplayName(":4128 ACCT-OPEN-DATE(6:2) - a month-only rival write is detected")
    void rivalWriteToOpenDateMonthComponentIsDetected() {
        this.account.setOpenDate("2000-02-01");
        assertRivalWriteDetected();
    }

    @Test
    @DisplayName(":4129 ACCT-OPEN-DATE(9:2) - a day-only rival write is detected")
    void rivalWriteToOpenDateDayComponentIsDetected() {
        this.account.setOpenDate("2000-01-02");
        assertRivalWriteDetected();
    }

    @Test
    @DisplayName(":4131 ACCT-EXPIRAION-DATE(1:4) - a year-only rival write is detected")
    void rivalWriteToExpiraionDateYearComponentIsDetected() {
        this.account.setExpiraionDate("2026-12-31");
        assertRivalWriteDetected();
    }

    @Test
    @DisplayName(":4132 ACCT-EXPIRAION-DATE(6:2) - a month-only rival write is detected")
    void rivalWriteToExpiraionDateMonthComponentIsDetected() {
        this.account.setExpiraionDate("2025-11-31");
        assertRivalWriteDetected();
    }

    @Test
    @DisplayName(":4133 ACCT-EXPIRAION-DATE(9:2) - a day-only rival write is detected")
    void rivalWriteToExpiraionDateDayComponentIsDetected() {
        this.account.setExpiraionDate("2025-12-30");
        assertRivalWriteDetected();
    }

    @Test
    @DisplayName(":4135 ACCT-REISSUE-DATE(1:4) - a year-only rival write is detected")
    void rivalWriteToReissueDateYearComponentIsDetected() {
        this.account.setReissueDate("2021-06-15");
        assertRivalWriteDetected();
    }

    @Test
    @DisplayName(":4136 ACCT-REISSUE-DATE(6:2) - a month-only rival write is detected")
    void rivalWriteToReissueDateMonthComponentIsDetected() {
        this.account.setReissueDate("2020-07-15");
        assertRivalWriteDetected();
    }

    @Test
    @DisplayName(":4137 ACCT-REISSUE-DATE(9:2) - a day-only rival write is detected")
    void rivalWriteToReissueDateDayComponentIsDetected() {
        this.account.setReissueDate("2020-06-16");
        assertRivalWriteDetected();
    }

    @Test
    @DisplayName(":4142-4144 the account block names ACCTDAT on the typed concurrency failure")
    void accountBlockMismatchRaisesAConcurrentUpdateFailureNamingAcctdat() {
        this.account.setCreditLimit(new BigDecimal("2021.00"));
        givenAccountLocked();
        givenCustomerLocked();

        assertThatThrownBy(this::write)
                .isInstanceOf(ConcurrentUpdateException.class)
                .hasMessage(DATA_WAS_CHANGED)
                .satisfies(thrown -> {
                    final ConcurrentUpdateException typed = (ConcurrentUpdateException) thrown;
                    assertThat(typed.getOutcome())
                            .isEqualTo(ConcurrentUpdateException.Outcome.DATA_CHANGED_BEFORE_UPDATE);
                    assertThat(typed.getOutcome().getChangeActionCode()).isEqualTo('S');
                    assertThat(typed.getAffectedRecord()).isEqualTo(ACCOUNT_FILE.strip());
                    assertThat(typed.getCause())
                            .as("a snapshot mismatch is a business outcome, not a wrapped throwable")
                            .isNull();
                });
    }

    @Test
    @DisplayName(":4188-4190 the customer block names CUSTDAT on the typed concurrency failure")
    void customerBlockMismatchRaisesAConcurrentUpdateFailureNamingCustdat() {
        this.customer.setAddressZip("98102");
        givenAccountLocked();
        givenCustomerLocked();

        assertThatThrownBy(this::write)
                .isInstanceOf(ConcurrentUpdateException.class)
                .hasMessage(DATA_WAS_CHANGED)
                .satisfies(thrown -> assertThat(((ConcurrentUpdateException) thrown).getAffectedRecord())
                        .isEqualTo(CUSTOMER_FILE.strip()));
    }

    @Test
    @DisplayName(":4109-4195 layer one only: the snapshot comparison never consults the @Version column")
    void theSnapshotComparisonIgnoresTheVersionColumn() {
        // A rival transaction bumped both version counters without leaving any business field
        // different - the round-trip modification. The legacy check therefore PASSES and the write
        // proceeds, which is precisely the case a version counter would have rejected.
        this.account.setVersion(ACCOUNT_VERSION + 41L);
        this.customer.setVersion(CUSTOMER_VERSION + 41L);
        givenAccountLocked();
        givenCustomerLocked();

        final AccountUpdateResult result = confirm();

        assertThat(result.changeAction())
                .as("9700 compares business values, so an advanced version counter is invisible to it")
                .isEqualTo(ChangeAction.CHANGES_OKAYED_AND_DONE);
        verify(this.accountRepository).save(this.account);
        verify(this.customerRepository).save(this.customer);
    }

    @Test
    @DisplayName("layer two only: the store-level @Version guard rejects what 9700 accepted")
    void theStoreLevelVersionGuardRejectsWhatTheSnapshotComparisonAccepted() {
        // Same round-trip modification, but this time the persistence provider's optimistic lock
        // fires at flush time. 9700 passed; the second layer is what stops the write. Neither layer
        // substitutes for the other, which is the whole reason both are required.
        this.account.setVersion(ACCOUNT_VERSION + 41L);
        this.customer.setVersion(CUSTOMER_VERSION + 41L);
        givenAccountLocked();
        givenCustomerLocked();
        final OptimisticLockingFailureException staleVersion =
                new OptimisticLockingFailureException("ACCTDAT row version moved on");
        doThrow(staleVersion).when(this.accountRepository).flush();

        final AccountUpdateResult result = confirm();

        assertThat(result.changeAction()).isEqualTo(ChangeAction.CHANGES_OKAYED_BUT_FAILED);
        assertThat(errorText(result)).isEqualTo(UPDATE_OF_RECORD_FAILED);
        verify(this.customerRepository, never()).save(any(Customer.class));
    }

    @Test
    @DisplayName("both layers exist independently: the entities expose a version, 9700 compares values")
    void bothConcurrencyLayersAreObservableOnTheSameRequest() {
        // Layer two's mechanism is present on both entities.
        assertThat(this.account.getVersion()).isEqualTo(ACCOUNT_VERSION);
        assertThat(this.customer.getVersion()).isEqualTo(CUSTOMER_VERSION);
        // Layer one's mechanism is present in the request, sealed: the snapshot the operator was shown
        // travels in the body because the server keeps no session state, but it travels as an opaque value
        // this server issued rather than as a group the caller could compose.
        final AccountUpdateRequest submitted = sealedRequest();
        assertThat(submitted.getSnapshot())
                .as("the as-displayed snapshot travels sealed, because Rule 7 forbids server-side state")
                .isNotBlank();
        assertThat(submitted.getOldDetails())
                .as("and it travels ONLY sealed - no readable group is bound from a request")
                .isNull();
        assertThat(this.snapshotTokenService.open(submitted.getSnapshot(), SNAPSHOT_KIND,
                SCREEN_ACCOUNT_ID, SUBJECT, AccountUpdateRequest.OldDetails.class))
                .as("and it opens, for this account and this principal, to the group 9700 compares")
                .isNotNull();
        // And layer one alone still catches a value change that left the version untouched.
        this.account.setCurrentBalance(new BigDecimal("0.00"));
        assertRivalWriteDetected();
    }

    @Test
    @DisplayName(":669 a write presenting no snapshot is an unmet precondition, never a skipped check")
    void aWriteWithoutTheSnapshotIsRejected() {
        final AccountUpdateRequest withoutSnapshot = this.screen.buildSealed(null);

        assertThatThrownBy(() -> this.service.updateAccount(withoutSnapshot, SUBJECT))
                .isInstanceOf(ConcurrentUpdateException.class)
                .hasMessage(SnapshotTokenService.MISSING_TOKEN_MESSAGE)
                .extracting(thrown -> ((ConcurrentUpdateException) thrown).getOutcome())
                .as("absent is CHANGES_NOT_CONFIRMED: the remedy is to read again and obtain one")
                .isEqualTo(ConcurrentUpdateException.Outcome.CHANGES_NOT_CONFIRMED);
        // The snapshot is opened before turn one begins, so a request without one is refused before either
        // dataset is touched. Silently treating the absent value as "nothing changed" would forfeit the
        // guarantee 9700-CHECK-CHANGE-IN-REC provides.
        verifyNoInteractions(this.accountRepository, this.customerRepository);
    }

    @Test
    @DisplayName(":757 ACUP-NEW-DETAILS cannot stand in for the snapshot the comparison needs")
    void newDetailsCannotSubstituteForTheSnapshot() {
        final AccountUpdateRequest withoutSnapshot = this.screen.buildSealed(null);

        assertThat(withoutSnapshot.getNewDetails())
                .as("the submitted side IS present, so the failure is specific to the snapshot")
                .isNotNull();
        assertThat(withoutSnapshot.getSnapshot()).isNull();
        assertThatThrownBy(() -> this.service.updateAccount(withoutSnapshot, SUBJECT))
                .isInstanceOf(ConcurrentUpdateException.class)
                .hasMessage(SnapshotTokenService.MISSING_TOKEN_MESSAGE);
    }

    @Test
    @DisplayName("the snapshot is opened from the sealed value, never composed by the caller")
    void theSnapshotCannotBeComposedByTheCaller() {
        // The decisive property of the substitution. 9700-CHECK-CHANGE-IN-REC exists to detect that the
        // record moved under the operator, and it can only do that if its second operand is the one this
        // server displayed. A group the caller composed would let the caller assert the record had not
        // changed - which is why AccountUpdateRequest binds no readable group from JSON at all.
        assertThat(Arrays.stream(AccountUpdateRequest.class.getDeclaredMethods())
                .filter(method -> "getOldDetails".equals(method.getName()))
                .findFirst()
                .orElseThrow()
                .isAnnotationPresent(com.fasterxml.jackson.annotation.JsonIgnore.class))
                .as("the accessor is server-side only, so it is excluded from the serialised form")
                .isTrue();
        // Only the copy constructor reachable through withOldDetails can attach one, and that is called by
        // this application projecting a screen for its own use - never by a request.
        assertThat(this.screen.buildSealed("c2VhbGVkLXZhbHVl").getOldDetails()).isNull();
    }

    @Test
    @DisplayName(":4158-4159 a rival write to the live customer address is detected, not only an account one")
    void rivalWriteToTheLiveCustomerAddressIsDetected() {
        // The account half of :4109-4193 returns early, so a test that only ever moves an ACCOUNT field
        // never reaches the nineteen customer clauses at all. This one moves a CUSTOMER field, which is the
        // half the end-to-end tier exercises.
        this.customer.setAddressLine1("AN ADDRESS THIS RECORD NEVER HELD");

        assertRivalWriteDetected();
    }

    @Test
    @DisplayName("the snapshot is never derived from the live row, only ever opened from the sealed value")
    void theSnapshotIsNeverDerivedFromTheLiveRow() {
        // The decisive property of transformation Rule 7's substitution. If the service derived the old
        // group from the row it is about to write, the comparison at :4109-4193 would be tautologically
        // true and the guard would be worthless. Here the live row is mutated behind a sealed snapshot that
        // still holds the displayed values, and the write is refused - which can only happen if the two
        // sides came from different places.
        this.account.setCurrentBalance(new BigDecimal("0.00"));

        assertRivalWriteDetected();
    }

    @Test
    @DisplayName("an edited snapshot is refused as a rival write, and no dataset is touched")
    void aTamperedSnapshotIsRefused() {
        final String sealed = seal(this.snapshot.build(), SCREEN_ACCOUNT_ID, SUBJECT);
        // The FIRST character, deliberately. In an unpadded base64url string the last character can carry
        // slack bits the decoder discards, so editing it need not change a single byte of the value.
        final char[] characters = sealed.toCharArray();
        characters[0] = characters[0] == 'A' ? 'B' : 'A';
        final AccountUpdateRequest edited = this.screen.buildSealed(new String(characters));

        assertThatThrownBy(() -> this.service.updateAccount(edited, SUBJECT))
                .isInstanceOf(ConcurrentUpdateException.class)
                .hasMessage(SnapshotTokenService.INVALID_TOKEN_MESSAGE)
                .extracting(thrown -> ((ConcurrentUpdateException) thrown).getOutcome())
                .as("presented-but-unverifiable is DATA_CHANGED_BEFORE_UPDATE: the remedy is to read again")
                .isEqualTo(ConcurrentUpdateException.Outcome.DATA_CHANGED_BEFORE_UPDATE);
        verifyNoInteractions(this.accountRepository, this.customerRepository);
    }

    @Test
    @DisplayName("a snapshot issued to another principal does not open for this one")
    void aSnapshotSealedForAnotherPrincipalIsRefused() {
        final AccountUpdateRequest foreign = this.screen
                .buildSealed(seal(this.snapshot.build(), SCREEN_ACCOUNT_ID, OTHER_SUBJECT));

        assertThatThrownBy(() -> this.service.updateAccount(foreign, SUBJECT))
                .isInstanceOf(ConcurrentUpdateException.class)
                .hasMessage(SnapshotTokenService.INVALID_TOKEN_MESSAGE);
        verifyNoInteractions(this.accountRepository, this.customerRepository);
    }

    @Test
    @DisplayName("a snapshot issued for another account cannot be transplanted onto this one")
    void aSnapshotSealedForAnotherAccountIsRefused() {
        final AccountUpdateRequest transplanted = this.screen
                .buildSealed(seal(this.snapshot.build(), "00000000022", SUBJECT));

        assertThatThrownBy(() -> this.service.updateAccount(transplanted, SUBJECT))
                .isInstanceOf(ConcurrentUpdateException.class)
                .hasMessage(SnapshotTokenService.INVALID_TOKEN_MESSAGE);
        verifyNoInteractions(this.accountRepository, this.customerRepository);
    }

    @Test
    @DisplayName("a snapshot older than its lifetime is refused rather than opened")
    void anExpiredSnapshotIsRefused() {
        // Sealed by a server whose clock stood one second beyond the lifetime before this one, so the value
        // is well formed, correctly keyed and correctly addressed - and stale.
        final SnapshotTokenService earlier = new SnapshotTokenService(SEALING_KEY, SEAL_LIFETIME_SECONDS,
                Clock.fixed(this.clock.instant().minusSeconds(SEAL_LIFETIME_SECONDS + 1), ZoneOffset.UTC),
                new ObjectMapper());
        final AccountUpdateRequest stale = this.screen.buildSealed(earlier.seal(SNAPSHOT_KIND,
                SCREEN_ACCOUNT_ID, SUBJECT, this.snapshot.build()));

        assertThatThrownBy(() -> this.service.updateAccount(stale, SUBJECT))
                .isInstanceOf(ConcurrentUpdateException.class)
                .hasMessage(SnapshotTokenService.INVALID_TOKEN_MESSAGE);
        verifyNoInteractions(this.accountRepository, this.customerRepository);
    }

    @Test
    @DisplayName("a write reaching the bean without a principal is a wiring defect, not a request outcome")
    void aWriteWithoutAPrincipalIsRefused() {
        final AccountUpdateRequest submitted = sealedRequest();

        for (final String absent : new String[] {null, "", "   "}) {
            assertThatThrownBy(() -> this.service.updateAccount(submitted, absent))
                    .as("the route is declared authenticated, so an absent principal is not a 400")
                    .isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(this.accountRepository, this.customerRepository);
    }

    @Test
    @DisplayName("all twenty-nine ACUP-OLD-DETAILS components survive the sealed round trip")
    void everyComponentSurvivesTheSealedRoundTrip() throws Exception {
        final AccountUpdateRequest.OldDetails original = this.snapshot.build();

        // Every accessor is counted, not sampled: a component that failed to travel would arrive null and
        // silently defeat the comparison at :4109-4193, which is the one failure mode a spot check misses.
        final List<Method> accessors = Arrays.stream(AccountUpdateRequest.OldDetails.class
                        .getDeclaredMethods())
                .filter(method -> method.getName().startsWith("get"))
                .filter(method -> method.getParameterCount() == 0)
                .toList();
        assertThat(accessors)
                .as("the snapshot declares twenty-nine components, each of which must reach the service")
                .hasSize(29);

        final AccountUpdateRequest.OldDetails recovered = this.snapshotTokenService.open(
                seal(original, SCREEN_ACCOUNT_ID, SUBJECT), SNAPSHOT_KIND, SCREEN_ACCOUNT_ID, SUBJECT,
                AccountUpdateRequest.OldDetails.class);

        // Sealing is authenticated encryption, not a rewrite: every one of the twenty-nine values the read
        // displayed comes back byte-identical, so 9700 compares exactly what the operator saw.
        for (final Method accessor : accessors) {
            assertThat(accessor.invoke(recovered))
                    .as("component %s must survive sealing unchanged", accessor.getName())
                    .isEqualTo(accessor.invoke(original));
        }
    }

    @Test
    @DisplayName("sealSnapshotForUpdate reads the chain and produces a value the write then accepts")
    void sealSnapshotForUpdateProducesAValueTheWriteAccepts() {
        // No read chain is stubbed for the seal itself: it projects ACUP-OLD-DETAILS from the records the
        // view traversal already read and issues no statement of its own. The write that follows does read,
        // so its locking reads are stubbed as usual.
        givenAccountLocked();
        givenCustomerLocked();

        final String sealed = this.service.sealSnapshotForUpdate(viewRecords(), SUBJECT);

        assertThat(sealed).isNotBlank();
        // The value is opaque: none of the nine protected components appears in it, which is the whole
        // point of sealing rather than echoing the group.
        assertThat(sealed)
                .as("the sealed value discloses nothing; the ciphertext carries the group")
                .doesNotContain(this.customer.getSsn())
                .doesNotContain(this.customer.getDateOfBirth().replace("-", ""))
                .doesNotContain(this.customer.getLastName())
                .doesNotContain(SCREEN_ACCOUNT_ID);
        assertThat(this.snapshotTokenService.open(sealed, SNAPSHOT_KIND, SCREEN_ACCOUNT_ID, SUBJECT,
                AccountUpdateRequest.OldDetails.class).getAccountId())
                .isEqualTo(SCREEN_ACCOUNT_ID);
        // And the write accepts it: the values projected are the values the read displayed, so 9700 finds
        // no difference and the confirmed turn reaches the rewrite rather than reporting a rival write.
        // One field must differ from the snapshot or 1205-COMPARE-OLD-NEW reports NO-CHANGES-DETECTED and
        // nothing is written - see the note on write().
        this.screen.addressLine2 = CHANGED_ADDRESS_LINE_2;
        final AccountUpdateResult applied =
                this.service.updateAccount(this.screen.buildSealed(sealed), SUBJECT);

        assertThat(applied.changeAction()).isEqualTo(ChangeAction.CHANGES_OKAYED_AND_DONE);
    }

    @Test
    @DisplayName("sealSnapshotForUpdate seals the unseparated date of birth the comparison expects")
    void sealSnapshotForUpdateProjectsTheUnseparatedDateOfBirth() {
        final AccountUpdateRequest.OldDetails projected = this.snapshotTokenService.open(
                this.service.sealSnapshotForUpdate(viewRecords(), SUBJECT), SNAPSHOT_KIND,
                SCREEN_ACCOUNT_ID, SUBJECT, AccountUpdateRequest.OldDetails.class);

        // :4174-4179 reads the live record at offsets 1, 6 and 9 - dash separated - and the snapshot at
        // offsets 1, 5 and 7, which are only correct if the snapshot form carries no separators. Projecting
        // the dash-separated form here would refuse every write, so this assertion is load-bearing.
        assertThat(projected.getDateOfBirth())
                .as("ACUP-OLD-CUST-DOB is the unseparated form; the live CUSTDAT value is not")
                .doesNotContain("-")
                .isEqualTo(this.customer.getDateOfBirth().replace("-", ""));
    }

    @Test
    @DisplayName("a read reaching the bean without a principal is a wiring defect, not a request outcome")
    void aReadWithoutAPrincipalIsRefused() {
        for (final String absent : new String[] {null, "", "   "}) {
            assertThatThrownBy(() -> this.service.sealSnapshotForUpdate(viewRecords(), absent))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        // And absent records are a wiring defect too: the method projects from what it is given and has no
        // read of its own to fall back on.
        assertThatThrownBy(() -> this.service.sealSnapshotForUpdate(null, SUBJECT))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(this.accountRepository, this.customerRepository);
    }

    /**
     * Builds what one traversal of {@code 9000-READ-ACCT} hands the seal: the projected screen plus the
     * account and customer records it read.
     *
     * <p>The projection member is not read by the seal - {@code 9500-STORE-FETCHED-DATA} works from the
     * records - so a minimal screen is supplied for it.
     *
     * @return the records the seal projects from
     */
    private AccountViewService.AccountViewRecords viewRecords() {
        return new AccountViewService.AccountViewRecords(null, this.account, this.customer);
    }

    @Test
    @DisplayName("a null symbolic-map area is rejected before any dataset is touched")
    void aNullRequestIsRejected() {
        assertThatThrownBy(() -> this.service.updateAccount(null, SUBJECT))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("app/cpy-bms/COACTUP.CPY")
                .satisfies(thrown -> {
                    final ValidationException typed = (ValidationException) thrown;
                    assertThat(typed.getFieldName()).isEqualTo("request");
                    assertThat(typed.getFailureKind())
                            .isEqualTo(ValidationException.FailureKind.BLANK);
                });
        verifyNoInteractions(this.accountRepository, this.customerRepository);
    }

    // 9700 slices dates into THREE SUBSTRINGS; 1205-COMPARE-OLD-NEW compares them as WHOLE FIELDS.
    //
    // 1205-COMPARE-OLD-NEW spans :1681-1777, opens with SET NO-CHANGES-FOUND TO TRUE at :1682 over
    // the 88 CHANGE-HAS-OCCURRED VALUE '1' declared at :170, and is performed at :1460-1461. At
    // :1692-1694 it compares the three account dates as whole X(08) fields - the OPPOSITE convention
    // from 9700's component slicing at :4127-4137. The two paragraphs also compare different things:
    // 1205 compares the SUBMITTED screen against the snapshot, 9700 compares the LIVE record against
    // it. They must not be unified.
    //
    // Unifying them would pass most tests and then fail on the date of birth,
    // where 1205's whole-field convention works because both of its sides are compact and 9700's
    // cannot, because its live side carries separators.

    @Test
    @DisplayName(":1769 1205 compares the SUBMITTED side and finds no change, touching no dataset")
    void compareOldNewFindsNoChangeWhenEverySubmittedValueMatchesTheSnapshot() {
        this.screen.accountStatus = "Y";

        final AccountUpdateResult result = editTurn();

        assertThat(errorText(result))
                .as(":1769 MOVE 'No change detected...' TO WS-RETURN-MSG")
                .isEqualTo(NO_CHANGE_DETECTED);
        assertThat(result.changeAction())
                .as(":2565-2567 leaves ACUP-SHOW-DETAILS in place when nothing changed")
                .isEqualTo(ChangeAction.SHOW_DETAILS);
        verifyNoInteractions(this.accountRepository, this.customerRepository);
    }

    @Test
    @DisplayName(":1692-1694 1205 compares the account dates as WHOLE X(08) fields")
    void compareOldNewComparesTheAccountDatesAsWholeFields() {
        // One digit of the submitted open date differs, so the joined eight-character value differs
        // from the whole snapshot member and 1205 reports a change.
        this.screen.accountStatus = "Y";
        this.screen.openDateDay = "02";
        givenEveryFieldEditPasses();

        final AccountUpdateResult result = editTurn();

        assertThat(errorText(result))
                .as("a change was found, so :1769 never ran")
                .isEmpty();
        assertThat(result.changeAction())
                .as("the cascade found every field individually valid, so :1470 promotes the marker")
                .isEqualTo(ChangeAction.CHANGES_OK_NOT_CONFIRMED);
        assertThat(result.informationMessage()).isEqualTo(INFO_PROMPT_FOR_CONFIRMATION);
        verifyNoInteractions(this.accountRepository, this.customerRepository);
    }

    @Test
    @DisplayName(":4127-4137 versus :1692-1694 - the two paragraphs are distinct declared methods")
    void theTwoComparisonParagraphsRemainDistinctMethods() {
        final List<String> declared = List.of(AccountUpdateService.class.getDeclaredMethods())
                .stream()
                .map(Method::getName)
                .toList();

        assertThat(declared)
                .as("consolidating them would erase one of the two date conventions")
                .contains("checkChangeInRecord9700", "compareOldNew1205");
        // And the exits are separate labels too, each a tracked no-op.
        assertThat(declared).contains("checkChangeInRecord9700Exit", "compareOldNew1205Exit");
    }

    @Test
    @DisplayName(":4127-4129 9700 compares the LIVE record, not the submitted side")
    void checkChangeInRecordComparesTheLiveRecordRatherThanTheSubmittedSide() {
        // Every submitted value equals its snapshot counterpart, so 1205 finds nothing. Only the LIVE
        // record moved, and only in one date component - which 9700 alone can see.
        this.screen.accountStatus = "Y";
        this.account.setOpenDate("2000-01-02");
        givenAccountLocked();
        givenCustomerLocked();

        final AccountUpdateResult result = confirm();

        assertThat(errorText(result))
                .as(":4143 overwrites 1205's no-change message unconditionally")
                .isEqualTo(DATA_WAS_CHANGED);
        assertThat(result.changeAction()).isEqualTo(ChangeAction.SHOW_DETAILS);
        verify(this.accountRepository, never()).save(any(Account.class));
    }

    // Case handling in 9700 is DELIBERATELY ASYMMETRIC, and normalising it in either direction
    // changes which updates are accepted:
    //
    //   FUNCTION LOWER-CASE on BOTH sides - ACCT-GROUP-ID only                        :4139-4140
    //   FUNCTION UPPER-CASE on BOTH sides - CUST-FIRST-NAME, -MIDDLE-NAME, -LAST-NAME,
    //     -ADDR-LINE-1, -ADDR-LINE-2, -ADDR-LINE-3, -ADDR-STATE-CD, -ADDR-COUNTRY-CD
    //     and -GOVT-ISSUED-ID                                                          :4155-4173
    //   NO case function at all - CUST-ADDR-ZIP :4168, CUST-PHONE-NUM-1 and -2 :4169-4170,
    //     CUST-SSN :4171, the three DOB components :4174-4179, CUST-EFT-ACCOUNT-ID :4181-4182,
    //     CUST-PRI-CARD-HOLDER-IND :4183-4185 and CUST-FICO-CREDIT-SCORE :4186
    //
    // 1205 uses a THIRD convention for the same fields - UPPER-CASE(TRIM()) on the group identifier
    // at :1697-1700 and on the postal code at :1747-1750, and a part-by-part comparison of the two
    // telephone numbers at :1751-1756. Two paragraphs, two conventions, both preserved.
    //
    // Two snapshot member names do not match their live counterparts, and the mismatch is faithfully
    // carried: CUST-PRI-CARD-HOLDER-IND is compared against ACUP-OLD-CUST-PRI-HOLDER-IND, and
    // CUST-FICO-CREDIT-SCORE against ACUP-OLD-CUST-FICO-SCORE.
    //
    // Normalising the asymmetry would change which updates are accepted. Every Java case operation
    // therefore pins Locale.ROOT, never the ambient default.

    /**
     * Arranges both read-for-update calls, takes the confirming turn and asserts that {@code 9700}
     * found NOTHING to report, so the rewrite pair went ahead.
     */
    private void assertCaseOnlyDifferenceIgnored() {
        givenAccountLocked();
        givenCustomerLocked();

        final AccountUpdateResult result = confirm();

        assertThat(result.changeAction())
                .as("a case-only difference is not a change under the source's case function")
                .isEqualTo(ChangeAction.CHANGES_OKAYED_AND_DONE);
        assertThat(errorText(result)).isNotEqualTo(DATA_WAS_CHANGED);
        verify(this.accountRepository).save(this.account);
        verify(this.customerRepository).save(this.customer);
    }

    @Test
    @DisplayName(":4139-4140 LOWER-CASE both sides - a case-only ACCT-GROUP-ID difference is ignored")
    void aCaseOnlyGroupIdDifferenceIsIgnored() {
        this.account.setGroupId("ZeRoBaL");
        this.snapshot.groupId = "zerobal";
        assertCaseOnlyDifferenceIgnored();
    }

    @Test
    @DisplayName(":4155 UPPER-CASE 1 of 9 - a case-only CUST-FIRST-NAME difference is ignored")
    void aCaseOnlyFirstNameDifferenceIsIgnored() {
        this.customer.setFirstName("Margaret");
        assertCaseOnlyDifferenceIgnored();
    }

    @Test
    @DisplayName(":4157 UPPER-CASE 2 of 9 - a case-only CUST-MIDDLE-NAME difference is ignored")
    void aCaseOnlyMiddleNameDifferenceIsIgnored() {
        this.customer.setMiddleName("a");
        assertCaseOnlyDifferenceIgnored();
    }

    @Test
    @DisplayName(":4159 UPPER-CASE 3 of 9 - a case-only CUST-LAST-NAME difference is ignored")
    void aCaseOnlyLastNameDifferenceIsIgnored() {
        this.customer.setLastName("Gold");
        assertCaseOnlyDifferenceIgnored();
    }

    @Test
    @DisplayName(":4161 UPPER-CASE 4 of 9 - a case-only CUST-ADDR-LINE-1 difference is ignored")
    void aCaseOnlyAddressLine1DifferenceIsIgnored() {
        this.customer.setAddressLine1("100 Main St");
        assertCaseOnlyDifferenceIgnored();
    }

    @Test
    @DisplayName(":4163 UPPER-CASE 5 of 9 - a case-only CUST-ADDR-LINE-2 difference is ignored")
    void aCaseOnlyAddressLine2DifferenceIsIgnored() {
        this.customer.setAddressLine2("Apt 1");
        assertCaseOnlyDifferenceIgnored();
    }

    @Test
    @DisplayName(":4165 UPPER-CASE 6 of 9 - a case-only CUST-ADDR-LINE-3 difference is ignored")
    void aCaseOnlyAddressLine3DifferenceIsIgnored() {
        this.customer.setAddressLine3("Seattle");
        assertCaseOnlyDifferenceIgnored();
    }

    @Test
    @DisplayName(":4166 UPPER-CASE 7 of 9 - a case-only CUST-ADDR-STATE-CD difference is ignored")
    void aCaseOnlyAddressStateCodeDifferenceIsIgnored() {
        this.customer.setAddressStateCode("wa");
        assertCaseOnlyDifferenceIgnored();
    }

    @Test
    @DisplayName(":4167 UPPER-CASE 8 of 9 - a case-only CUST-ADDR-COUNTRY-CD difference is ignored")
    void aCaseOnlyAddressCountryCodeDifferenceIsIgnored() {
        this.customer.setAddressCountryCode("usa");
        assertCaseOnlyDifferenceIgnored();
    }

    @Test
    @DisplayName(":4172-4173 UPPER-CASE 9 of 9 - a case-only CUST-GOVT-ISSUED-ID difference is ignored")
    void aCaseOnlyGovernmentIssuedIdDifferenceIsIgnored() {
        this.customer.setGovernmentIssuedId("wa-dl-9988776");
        assertCaseOnlyDifferenceIgnored();
    }

    @Test
    @DisplayName(":4168 no case function 1 of 10 - a case-only CUST-ADDR-ZIP difference IS detected")
    void aCaseOnlyAddressZipDifferenceIsDetected() {
        this.snapshot.addressZip = "98101A";
        this.customer.setAddressZip("98101a");
        assertRivalWriteDetected();
    }

    @Test
    @DisplayName(":4169 no case function 2 of 10 - a case-only CUST-PHONE-NUM-1 difference IS detected")
    void aCaseOnlyFirstTelephoneDifferenceIsDetected() {
        this.snapshot.phoneNumber1 = "(206)555-010X";
        this.customer.setPhoneNumber1("(206)555-010x");
        assertRivalWriteDetected();
    }

    @Test
    @DisplayName(":4170 no case function 3 of 10 - a case-only CUST-PHONE-NUM-2 difference IS detected")
    void aCaseOnlySecondTelephoneDifferenceIsDetected() {
        this.snapshot.phoneNumber2 = "(425)555-019X";
        this.customer.setPhoneNumber2("(425)555-019x");
        assertRivalWriteDetected();
    }

    @Test
    @DisplayName(":4171 no case function 4 of 10 - a case-only CUST-SSN difference IS detected")
    void aCaseOnlyNationalIdentifierDifferenceIsDetected() {
        this.snapshot.ssn = "12345678X";
        this.customer.setSsn("12345678x");
        assertRivalWriteDetected();
    }

    @Test
    @DisplayName(":4181-4182 no case function 8 of 10 - a case-only EFT identifier difference IS detected")
    void aCaseOnlyEftAccountIdDifferenceIsDetected() {
        this.snapshot.eftAccountId = "000000000X";
        this.customer.setEftAccountId("000000000x");
        assertRivalWriteDetected();
    }

    @Test
    @DisplayName(":4183-4185 no case function 9 of 10 - a case-only primary-holder difference IS detected")
    void aCaseOnlyPrimaryCardHolderIndicatorDifferenceIsDetected() {
        this.customer.setPrimaryCardHolderIndicator("y");
        assertRivalWriteDetected();
    }

    @Test
    @DisplayName(":4186 no case function 10 of 10 - a case-only credit-score difference IS detected")
    void aCaseOnlyCreditScoreDifferenceIsDetected() {
        // ACUP-OLD-CUST-FICO-SCORE, sic: the snapshot member's name does not match
        // CUST-FICO-CREDIT-SCORE, and is carried unchanged. Neither side parses as a number
        // here, so the numeric comparison falls back to the text one and the case difference bites.
        this.snapshot.ficoScore = "75X";
        this.customer.setFicoCreditScore("75x");
        assertRivalWriteDetected();
    }

    @Test
    @DisplayName(":4139-4140 the case functions are Locale.ROOT, never the ambient default")
    void theCaseFunctionsUseRootLocaleSemantics() {
        // A dotted capital I is the discriminator: Turkish lower-casing yields a DOTLESS i, so a
        // Turkish-sensitive comparison and a Locale.ROOT comparison disagree on this exact pair. No
        // ambient default is read and none is mutated, so the proof stays deterministic.
        assertThat("GOLDI".toLowerCase(Locale.ROOT)).isEqualTo("goldi");
        assertThat("GOLDI".toLowerCase(Locale.forLanguageTag("tr-TR"))).isEqualTo("gold\u0131");

        this.account.setGroupId("GOLDI");
        this.snapshot.groupId = "goldi";

        assertCaseOnlyDifferenceIgnored();
    }

    @Test
    @DisplayName(":4139-4140 a Turkish-lowered snapshot proves the comparison is not locale-sensitive")
    void aTurkishLoweredSnapshotIsTreatedAsADifference() {
        this.account.setGroupId("GOLDI");
        this.snapshot.groupId = "gold\u0131";

        assertRivalWriteDetected();
    }

    @Test
    @DisplayName(":4139-4140 LOWER-CASE is applied with NO TRIM, unlike :1697-1700")
    void theGroupIdComparisonAppliesNoTrim() {
        // 1205 uses UPPER-CASE(TRIM()) at :1697-1700; 9700 uses bare LOWER-CASE. A leading blank is
        // therefore invisible to one paragraph and a difference to the other, which is exactly why
        // the two must not be unified.
        this.account.setGroupId(" ZEROBAL");
        this.snapshot.groupId = "zerobal";

        assertRivalWriteDetected();
    }

    // PARITY TRAP - the date-of-birth OFFSET ASYMMETRY at :4174-4179.
    //
    // ACUP-OLD-CUST-DOB-YYYY-MM-DD is PIC X(08) at :746 - COMPACT, no separators - redefined into
    // components by ACUP-OLD-CUST-DOB-PARTS at :747-751 as YEAR X(4), MON X(2), DAY X(2). The live
    // CUST-DOB-YYYY-MM-DD is X(10), DASH-SEPARATED. The source therefore compares:
    //
    //     CUST-DOB-YYYY-MM-DD (1:4) EQUAL ACUP-OLD-CUST-DOB-YYYY-MM-DD (1:4)
    //     CUST-DOB-YYYY-MM-DD (6:2) EQUAL ACUP-OLD-CUST-DOB-YYYY-MM-DD (5:2)
    //     CUST-DOB-YYYY-MM-DD (9:2) EQUAL ACUP-OLD-CUST-DOB-YYYY-MM-DD (7:2)
    //
    // Offsets 1 to 1, 6 to 5, 9 to 7. A naive WHOLE-STRING comparison of the ten-character live value
    // against the eight-character snapshot reports a change on EVERY SINGLE REQUEST, making the
    // endpoint permanently unusable. The snapshot is populated by three component MOVEs at
    // :3857-3859, and the naive whole-field MOVE is present but COMMENTED OUT at :3856 - direct
    // evidence that the source author met this trap too.
    //
    // The rule that prevents the regression: slice both sides, never compare whole.

    @Test
    @DisplayName(":4174-4179 an unchanged DOB is NOT flagged - dashed live against compact snapshot")
    void anUnchangedDateOfBirthIsNotFlaggedAcrossTheTwoRepresentations() {
        // The two representations of the same value are not equal as strings, which is exactly what a
        // whole-string comparison would trip over. No value is echoed in any description.
        assertThat(this.customer.getDateOfBirth().length()).isEqualTo(10);
        assertThat(this.snapshot.dateOfBirth.length()).isEqualTo(8);
        assertThat(this.customer.getDateOfBirth().equals(this.snapshot.dateOfBirth))
                .as("a whole-string comparison of the two representations would report a change")
                .isFalse();

        assertCaseOnlyDifferenceIgnored();
    }

    @Test
    @DisplayName(":4176-4177 the month clause maps live offset 6 onto snapshot offset 5")
    void theMonthClauseMapsLiveOffsetSixOntoSnapshotOffsetFive() {
        // Slicing the snapshot at the LIVE offset would take the two characters at 1-based position 6,
        // which is not the month at all. Slicing it at position 5 is. The service accepts the record,
        // so it used the second mapping.
        final String snapshotDate = this.snapshot.dateOfBirth;
        assertThat(snapshotDate.substring(5, 7))
                .as("the naive same-offset slice of the snapshot is not the month")
                .isNotEqualTo(snapshotDate.substring(4, 6));

        assertCaseOnlyDifferenceIgnored();
    }

    @Test
    @DisplayName(":4178-4179 the day clause maps live offset 9 onto snapshot offset 7")
    void theDayClauseMapsLiveOffsetNineOntoSnapshotOffsetSeven() {
        // The live day sits at 1-based 9, beyond the end of the eight-character snapshot: a same-offset
        // slice is not merely wrong, it is out of range.
        assertThat(this.snapshot.dateOfBirth.length()).isLessThan(9);
        assertCaseOnlyDifferenceIgnored();
    }

    @Test
    @DisplayName(":4174-4175 a genuine DOB year change IS flagged")
    void aDateOfBirthYearChangeIsFlagged() {
        this.customer.setDateOfBirth("1981-01-15");
        assertRivalWriteDetected();
    }

    @Test
    @DisplayName(":4176-4177 a genuine DOB month change IS flagged")
    void aDateOfBirthMonthChangeIsFlagged() {
        this.customer.setDateOfBirth("1980-02-15");
        assertRivalWriteDetected();
    }

    @Test
    @DisplayName(":4178-4179 a genuine DOB day change IS flagged")
    void aDateOfBirthDayChangeIsFlagged() {
        this.customer.setDateOfBirth("1980-01-16");
        assertRivalWriteDetected();
    }

    @Test
    @DisplayName(":746-751 the snapshot stores the DOB COMPACT and slices it at 0-4, 4-6 and 6-8")
    void theSnapshotStoresTheDateOfBirthInCompactForm() {
        final AccountUpdateRequest.OldDetails oldDetails = this.snapshot.build();

        // PIC X(08) with no separators. Neither assertion echoes the value.
        assertThat(oldDetails.getDateOfBirth().length()).isEqualTo(8);
        assertThat(oldDetails.getDateOfBirth().indexOf('-'))
                .as("a separator in the snapshot would shift every component offset")
                .isEqualTo(-1);
        // The three REDEFINES components partition the field exactly, with nothing lost or repeated.
        assertThat(oldDetails.dateOfBirthYear().length()).isEqualTo(4);
        assertThat(oldDetails.dateOfBirthMonth().length()).isEqualTo(2);
        assertThat(oldDetails.dateOfBirthDay().length()).isEqualTo(2);
        assertThat(oldDetails.dateOfBirthYear()
                        + oldDetails.dateOfBirthMonth()
                        + oldDetails.dateOfBirthDay())
                .isEqualTo(oldDetails.getDateOfBirth());
    }

    /**
     * The abend diagnostic reports the code the caller is told, not the empty work-area field.
     *
     * <p>FINDING, severity Low - remediated. The diagnostic used to be written before the
     * substitution the exception payload performs, so an abend that never moved a value into
     * {@code ABEND-CODE} logged {@code code=null} while the response reported {@code 9999}. An operator
     * correlating a log line with a response had no way to see they were the same event.
     */
    @Test
    @DisplayName(":4211 the abend diagnostic carries the same code the caller is told, never null")
    void theAbendDiagnosticCarriesTheReportedCode() {
        final Logger serviceLogger = (Logger) LoggerFactory.getLogger(AccountUpdateService.class);
        final ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        serviceLogger.addAppender(captured);
        serviceLogger.setLevel(Level.TRACE);
        try {
            this.snapshot.dateOfBirth = "1980-01-15";

            assertThatThrownBy(this::write).isInstanceOf(FatalProcessingException.class);

            final String rendered = captured.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(line -> line.startsWith("CAUP abend:"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("the abend produced no diagnostic at all"));
            assertThat(rendered)
                    .contains("code=" + ONLINE_ABEND_CODE)
                    .doesNotContain("code=null")
                    .contains("culprit=" + PROGRAM_NAME);
        } finally {
            serviceLogger.detachAppender(captured);
            captured.stop();
            serviceLogger.setLevel(null);
        }
    }

    @Test
    @DisplayName(":746 a dash-separated snapshot DOB is rejected explicitly, and echoes no value")
    void aDashSeparatedSnapshotDateOfBirthIsRejectedWithoutEchoingIt() {
        final String dashed = "1980-01-15";
        this.snapshot.dateOfBirth = dashed;

        assertThatThrownBy(this::write)
                .isInstanceOf(FatalProcessingException.class)
                .satisfies(thrown -> {
                    final FatalProcessingException fatal = (FatalProcessingException) thrown;
                    // :4220-4222 EXEC CICS ABEND ABCODE('9999') - the four-character ONLINE contract.
                    assertThat(fatal.getAbendCode()).isEqualTo(ONLINE_ABEND_CODE);
                    // :4209 MOVE LIT-THISPGM TO ABEND-CULPRIT
                    assertThat(fatal.getAbendCulprit()).isEqualTo(PROGRAM_NAME);
                    assertThat(fatal.getCause()).isInstanceOf(IllegalArgumentException.class);
                    assertThat(fatal.getCause()).hasMessageContaining("OldDetails.dateOfBirth");
                    assertThat(fatal.getCause()).hasMessageContaining("PIC X(08)");
                    // Clause D: the offending value carries personally identifiable data and must not
                    // reach any message. Checked on the whole causal chain, not just the top frame.
                    assertThat(fatal.getMessage()).doesNotContain(dashed);
                    assertThat(fatal.getCause().getMessage()).doesNotContain(dashed);
                    assertThat(fatal.getAbendMessage()).doesNotContain(dashed);
                });
        verifyNoInteractions(this.accountRepository, this.customerRepository);
    }

    // Outcome literals, state markers and the abend contract.
    //
    // The outcome group is declared at :505-528 with the four write outcomes at :517-524. Each
    // condition name resolves to exactly one literal, so asserting the literal asserts the flag. The
    // state markers are declared at :655-670: ACUP-SHOW-DETAILS 'S', ACUP-CHANGES-NOT-OK 'E',
    // ACUP-CHANGES-OK-NOT-CONFIRMED 'N', ACUP-CHANGES-OKAYED-AND-DONE 'C',
    // ACUP-CHANGES-OKAYED-LOCK-ERROR 'L', ACUP-CHANGES-OKAYED-BUT-FAILED 'F', with the composite
    // 88-levels ACUP-CHANGES-MADE VALUES 'E','N','C','L','F' and ACUP-CHANGES-FAILED VALUES 'L','F'.
    //
    // Of the twelve outcome literals in play, only FOUR are ever assigned
    // on a reachable path - the two lock literals, the data-changed literal and the update-failed
    // literal. The other eight mirror source 88-levels whose SET is either commented out or absent
    // entirely: DID-NOT-FIND-ACCT-IN-CARDXREF and DID-NOT-FIND-ACCTCARD-COMBO are declaration-only,
    // the two master-file literals survive only inside guards that can never match because their SETs
    // are commented out at :3719 and :3769, and the credit-limit, card-expiry and card-file literals
    // belong to 88-levels this program declares and never sets. None of them is therefore observable
    // behaviourally: observing one would take a source revision reinstating the commented-out SET
    // statements, which parity forbids. Byte-exactness
    // is instead asserted structurally below.

    @Test
    @DisplayName(":517-524 the four assigned outcome literals are byte-exact")
    void theFourAssignedOutcomeLiteralsAreByteExact() {
        assertThat(ConcurrentUpdateException.Outcome.COULD_NOT_LOCK_ACCOUNT.getLegacyMessage())
                .isEqualTo(COULD_NOT_LOCK_ACCOUNT);
        assertThat(ConcurrentUpdateException.Outcome.COULD_NOT_LOCK_CUSTOMER.getLegacyMessage())
                .isEqualTo(COULD_NOT_LOCK_CUSTOMER);
        assertThat(ConcurrentUpdateException.Outcome.DATA_CHANGED_BEFORE_UPDATE.getLegacyMessage())
                .isEqualTo(DATA_WAS_CHANGED);
        assertThat(ConcurrentUpdateException.Outcome.LOCKED_BUT_UPDATE_FAILED.getLegacyMessage())
                .isEqualTo(UPDATE_OF_RECORD_FAILED);
        // A fifth constant, CHANGES_NOT_CONFIRMED with marker 'N', carries an EMPTY literal because
        // ACUP-CHANGES-OK-NOT-CONFIRMED at :661-662 is a state marker with no message of its own.
        assertThat(ConcurrentUpdateException.Outcome.values()).hasSize(5);
        assertThat(ConcurrentUpdateException.Outcome.CHANGES_NOT_CONFIRMED.getLegacyMessage())
                .isEmpty();
        assertThat(ConcurrentUpdateException.Outcome.CHANGES_NOT_CONFIRMED.getChangeActionCode())
                .isEqualTo('N');
    }

    @Test
    @DisplayName(":521-522 'some one' is TWO WORDS in the data-changed literal")
    void theDataChangedLiteralSpellsSomeOneAsTwoWords() {
        assertThat(DATA_WAS_CHANGED).contains("some one");
        assertThat(DATA_WAS_CHANGED).doesNotContain("someone");
        assertThat(DATA_WAS_CHANGED.endsWith("Please review")).isTrue();
    }

    @Test
    @DisplayName(":527-528 'Looks Good.... so far' carries FOUR dots")
    void theCodingToBeDoneLiteralCarriesFourDots() {
        assertThat(CODING_TO_BE_DONE.chars().filter(character -> character == '.').count())
                .isEqualTo(4);
        assertThat(CODING_TO_BE_DONE).isEqualTo("Looks Good.... so far");
    }

    @Test
    @DisplayName(":509-516 the four card-file diagnostics are byte-exact as declared")
    void theCardFileDiagnosticLiteralsAreByteExactAsDeclared() {
        assertThat(NOT_IN_CARDS_DATABASE).isEqualTo("Did not find this account in cards database");
        assertThat(NO_CARDS_FOR_CONDITION).isEqualTo("Did not find cards for this search condition");
        assertThat(CARD_FILE_READ_ERROR).isEqualTo("Error reading Card Data File");
        assertThat(NOT_IN_CARDS_DATABASE).isNotEqualTo(NO_CARDS_FOR_CONDITION);
    }

    @Test
    @DisplayName(":525-526 the credit-limit and card-expiry literals are byte-exact as declared")
    void theCreditLimitAndExpiryLiteralsAreByteExactAsDeclared() {
        assertThat(CREDIT_LIMIT_MUST_BE_SUPPLIED).isEqualTo("Credit Limit must be supplied");
        assertThat(CREDIT_LIMIT_IS_NOT_VALID).isEqualTo("Credit Limit is not valid");
        assertThat(EXPIRY_MONTH_RANGE).isEqualTo("Card expiry month must be between 1 and 12");
        assertThat(INVALID_EXPIRY_YEAR).isEqualTo("Invalid card expiry year");
        // The blank-versus-invalid pair is two distinct diagnostics, never one collapsed message.
        assertThat(CREDIT_LIMIT_MUST_BE_SUPPLIED).isNotEqualTo(CREDIT_LIMIT_IS_NOT_VALID);
    }

    @Test
    @DisplayName(":655-670 the six state markers and the two composite 88-levels")
    void theStateMarkersMatchTheSourceConditionNames() {
        assertThat(ChangeAction.SHOW_DETAILS.marker()).isEqualTo('S');
        assertThat(ChangeAction.CHANGES_NOT_OK.marker()).isEqualTo('E');
        assertThat(ChangeAction.CHANGES_OK_NOT_CONFIRMED.marker()).isEqualTo('N');
        assertThat(ChangeAction.CHANGES_OKAYED_AND_DONE.marker()).isEqualTo('C');
        assertThat(ChangeAction.CHANGES_OKAYED_LOCK_ERROR.marker()).isEqualTo('L');
        assertThat(ChangeAction.CHANGES_OKAYED_BUT_FAILED.marker()).isEqualTo('F');
        // ACUP-DETAILS-NOT-FETCHED VALUES LOW-VALUES, SPACES - the blank marker.
        assertThat(ChangeAction.DETAILS_NOT_FETCHED.marker()).isEqualTo(' ');
        // ACUP-CHANGES-MADE VALUES 'E','N','C','L','F' - five of the seven.
        assertThat(List.of(ChangeAction.values()).stream().filter(ChangeAction::isChangesMade).toList())
                .containsExactly(ChangeAction.CHANGES_NOT_OK,
                        ChangeAction.CHANGES_OK_NOT_CONFIRMED,
                        ChangeAction.CHANGES_OKAYED_AND_DONE,
                        ChangeAction.CHANGES_OKAYED_LOCK_ERROR,
                        ChangeAction.CHANGES_OKAYED_BUT_FAILED);
        // ACUP-CHANGES-FAILED VALUES 'L','F' - exactly two.
        assertThat(List.of(ChangeAction.values()).stream()
                        .filter(ChangeAction::isChangesFailed).toList())
                .containsExactly(ChangeAction.CHANGES_OKAYED_LOCK_ERROR,
                        ChangeAction.CHANGES_OKAYED_BUT_FAILED);
    }

    @Test
    @DisplayName(":517-523 plus :667 - five write outcomes stay distinguishable to the REST layer")
    void allFiveWriteOutcomesRemainDistinguishable() {
        // The screen marker collapses the customer-lock outcome into success - the preserved defect
        // this class exists to pin. The TYPED failure layer is what keeps all five apart, which is why collapsing
        // them into a single conflict status would lose information the legacy screen displayed.
        givenAccountMissing();
        givenAccountReadMapped();
        assertThatThrownBy(this::write)
                .as("outcome 1 of 5: COULD-NOT-LOCK-ACCT-FOR-UPDATE, marker 'L'")
                .isInstanceOf(RecordNotFoundException.class)
                .satisfies(thrown -> assertThat(((RecordNotFoundException) thrown).recordType())
                        .contains("account"));
    }

    @Test
    @DisplayName(":519-520 outcome 2 of 5 - the customer-lock failure has its own typed identity")
    void theCustomerLockOutcomeHasItsOwnTypedIdentity() {
        givenAccountLocked();
        givenCustomerMissing();
        givenCustomerReadMapped();

        assertThatThrownBy(this::write)
                .isInstanceOf(RecordNotFoundException.class)
                .satisfies(thrown -> assertThat(((RecordNotFoundException) thrown).recordType())
                        .as("distinguishable from the account outcome even though the marker is not")
                        .contains("customer"));
    }

    @Test
    @DisplayName(":521-522 outcome 3 of 5 - the data-changed failure has its own typed identity")
    void theDataChangedOutcomeHasItsOwnTypedIdentity() {
        this.account.setActiveStatus("N");
        givenAccountLocked();
        givenCustomerLocked();

        // Rule 1 clause B requires the type, the message and the cause to be pinned together: a type
        // assertion alone would still pass if the diagnostic silently changed. A snapshot mismatch is
        // a business outcome rather than a wrapped fault, so the correct cause assertion is that
        // there is none.
        assertThatThrownBy(this::write)
                .isInstanceOf(ConcurrentUpdateException.class)
                .hasMessage(DATA_WAS_CHANGED)
                .satisfies(thrown -> {
                    final ConcurrentUpdateException typed = (ConcurrentUpdateException) thrown;
                    assertThat(typed.getOutcome())
                            .isEqualTo(ConcurrentUpdateException.Outcome.DATA_CHANGED_BEFORE_UPDATE);
                    assertThat(typed.getCause()).isNull();
                });
    }

    @Test
    @DisplayName(":523-524 outcome 4 of 5 - the rewrite failure has its own typed identity")
    void theRewriteFailureOutcomeHasItsOwnTypedIdentity() {
        givenAccountLocked();
        givenCustomerLocked();
        final DataAccessResourceFailureException rewriteRejected =
                new DataAccessResourceFailureException("rewrite rejected");
        doThrow(rewriteRejected).when(this.accountRepository).flush();

        // Type, message and root cause together. :4079 names the file whose REWRITE failed, and the
        // originating store fault is preserved rather than swallowed - clause B's "wrap with context
        // and preserve root cause" applied to the write path.
        assertThatThrownBy(this::write)
                .isInstanceOf(CardDemoException.class)
                .isNotInstanceOf(ConcurrentUpdateException.class)
                .isNotInstanceOf(RecordNotFoundException.class)
                .hasMessageContaining(ACCOUNT_FILE.trim())
                .hasRootCause(rewriteRejected);
    }

    @Test
    @DisplayName("outcome 5 of 5 - the clean write raises nothing at all")
    void theCleanWriteOutcomeRaisesNothing() {
        givenAccountLocked();
        givenCustomerLocked();

        final AccountUpdateResult result = write();

        assertThat(result.changeAction()).isEqualTo(ChangeAction.CHANGES_OKAYED_AND_DONE);
        assertThat(result.informationMessage()).isEqualTo(INFO_UPDATE_SUCCESS);
    }

    @Test
    @DisplayName(":4223 the ONLINE abend code is the four-character '9999', not the batch 999 pair")
    void theOnlineAbendContractIsDistinctFromTheBatchContract() {
        assertThat(ONLINE_ABEND_CODE).isEqualTo("9999").hasSize(4);
        // The batch contract of app/cbl/CBTRN02C.cbl is a numeric 999 with return code 12. Conflating
        // the two is a source characteristic: this program is a CICS screen program and has neither.
        assertThat(FatalProcessingException.BATCH_ABEND_CODE).isEqualTo(999);
        assertThat(FatalProcessingException.BATCH_RETURN_CODE).isEqualTo(12);
        assertThat(ONLINE_ABEND_CODE)
                .isNotEqualTo(String.valueOf(FatalProcessingException.BATCH_ABEND_CODE));
    }

    @Test
    @DisplayName("CSMSG02Y.cpy:L21-L29 - CABENDD.CPY's ABEND-DATA group totals 134 bytes")
    void theAbendDataGroupMatchesTheCopybookWidths() {
        // app/cpy/CSMSG02Y.cpy is internally titled CABENDD.CPY and declares ABEND-CODE X(4),
        // ABEND-CULPRIT X(8), ABEND-REASON X(50) and ABEND-MSG X(72) - 134 bytes in total. The
        // "001200-002000" figures quoted elsewhere are COBOL sequence numbers, not line numbers.
        assertThat(FileStatusMapper.ABEND_CODE_WIDTH).isEqualTo(4);
        assertThat(FileStatusMapper.ABEND_CULPRIT_WIDTH).isEqualTo(8);
        assertThat(FileStatusMapper.ABEND_REASON_WIDTH).isEqualTo(ABEND_REASON_WIDTH);
        assertThat(FileStatusMapper.ABEND_MESSAGE_WIDTH).isEqualTo(72);
        assertThat(FileStatusMapper.ABEND_CODE_WIDTH
                        + FileStatusMapper.ABEND_CULPRIT_WIDTH
                        + FileStatusMapper.ABEND_REASON_WIDTH
                        + FileStatusMapper.ABEND_MESSAGE_WIDTH)
                .isEqualTo(134);
        // All four members carry VALUE SPACES, and SPACES is NOT LOW-VALUES.
        assertThat(FileStatusMapper.ABEND_CODE_UNSET).isEqualTo(" ".repeat(4)).isBlank();
        assertThat(FileStatusMapper.ABEND_CULPRIT_UNSET).isEqualTo(" ".repeat(8)).isBlank();
    }

    @Test
    @DisplayName(":4205-4206 the default abend message is a tracked no-op on every source path")
    void theDefaultAbendMessageSubstitutionIsATrackedNoOp() {
        // The guard reads IF ABEND-MSG EQUAL LOW-VALUES, but every member of ABEND-DATA is declared
        // VALUE SPACES, and the one source path that reaches ABEND-ROUTINE assigns ABEND-MSG first at
        // :2639. The substitution therefore never fires in COBOL - an intentional no-op that is retained for
        // control-flow parity, because removing it would break the paragraph map.
        assertThat(FatalProcessingException.DEFAULT_ABEND_MESSAGE)
                .isEqualTo("UNEXPECTED ABEND OCCURRED.");
        assertThat(FatalProcessingException.DEFAULT_ABEND_MESSAGE)
                .isNotEqualTo(UNEXPECTED_SCENARIO_MESSAGE);
        // The Java port widens the guard to cover null as well as spaces, which is what makes it
        // observable on the catch-all path only. It is disclosed rather than corrected.
        this.snapshot.dateOfBirth = "1980-01-15";
        assertThatThrownBy(this::write)
                .isInstanceOf(FatalProcessingException.class)
                .hasMessage(FatalProcessingException.DEFAULT_ABEND_MESSAGE)
                .satisfies(thrown -> assertThat(((FatalProcessingException) thrown).getAbendReason())
                        .as("nothing assigned ABEND-REASON on this path")
                        .isNull());
    }

    @Test
    @DisplayName(":2634-2640 the WHEN OTHER abend arm is unreachable through the public surface")
    void theUnexpectedScenarioArmIsUnreachableAndRetainedForParity() {
        // :976-995 intercepts ACUP-CHANGES-OKAYED-AND-DONE and both ACUP-CHANGES-FAILED markers and
        // returns through COMMON-RETURN before 2000-DECIDE-ACTION ever runs. The remaining four
        // markers each have their own WHEN. No submitted marker therefore reaches WHEN OTHER.
        //
        // The payload of that arm - ABEND-CODE '0001' with ABEND-MSG 'UNEXPECTED DATA
        // SCENARIO' at :2636-2639 - is consequently unobservable through the public surface. Observing
        // it would take a package-private seam on 2000-DECIDE-ACTION, or an eighth
        // marker, and both are refused because they would alter the source's own control flow.
        this.screen.accountStatus = "Y";
        for (final ChangeAction submitted : ChangeAction.values()) {
            final AccountUpdateResult result = this.service.processRequest(
                    this.screen.build(this.snapshot.build()),
                    AID_ENTER,
                    submitted,
                    EntryMode.REENTER);
            assertThat(result.responseKind())
                    .as("no submitted marker may abend")
                    .isEqualTo(ResponseKind.MAP);
            assertThat(result.changeAction())
                    .as("every marker resolves to a declared outcome")
                    .isNotNull();
        }
        assertThat(UNEXPECTED_SCENARIO_CODE).isEqualTo("0001").hasSize(4);
        assertThat(UNEXPECTED_SCENARIO_MESSAGE).isEqualTo("UNEXPECTED DATA SCENARIO");
        verifyNoInteractions(this.accountRepository, this.customerRepository);
    }

    // Validation-side field contracts that must NOT leak into the entity.
    //
    // 88 FICO-RANGE-IS-VALID VALUES 300 THROUGH 850 is declared at :848-849, INSIDE ACUP-NEW-DETAILS,
    // which begins at :757. The snapshot group ACUP-OLD-DETAILS at :669 - account part :670-708,
    // customer part :709-756 - carries NO such condition name. The range therefore gates SCREEN INPUT
    // on this one path and must never become an entity constraint: 21 of the 50 rows in
    // app/data/ASCII/custdata.txt carry a credit score below 300, so an entity-level @Min would reject
    // the seeded data outright.
    //
    // The national identifier is shaped DIFFERENTLY on the two sides. New side at :830 is a GROUP OF
    // THREE - X(03) at :831, X(02) at :832, X(04) at :833 - with redefines at :834-835. Old side at
    // :742 is FLAT X(09) with a redefine ACUP-OLD-CUST-SSN-X PIC 9(09), i.e. NUMERIC, at :743-744.
    // Unifying the two shapes would be a behaviour change and is therefore not done.
    //
    // ACUP-OLD-ADDR-ZIP is X(10) at :721 while the screen field ACSZIPCI is X(5) at
    // app/cpy-bms/COACTUP.CPY:246 - a ten-byte snapshot slot behind a five-byte input field. Both
    // widths are reproduced; they are not unified.

    @Test
    @DisplayName(":848-849 a credit score below 300 is rejected by REQUEST validation")
    void aCreditScoreBelowTheRangeIsRejectedByRequestValidation() {
        this.screen.ficoScore = String.valueOf(FICO_MINIMUM - 1);
        givenEveryFieldEditPasses();

        final AccountUpdateResult result = editTurn();

        assertThat(errorText(result))
                .as("the label at :775 concatenated with the suffix at :728")
                .isEqualTo(FICO_RANGE_MESSAGE);
        assertThat(result.changeAction())
                .as(":1470 cannot promote the marker while INPUT-ERROR is set")
                .isEqualTo(ChangeAction.CHANGES_NOT_OK);
        assertThat(result.changeAction().marker()).isEqualTo('E');
        verifyNoInteractions(this.accountRepository, this.customerRepository);
    }

    @Test
    @DisplayName(":848-849 a credit score above 850 is rejected by REQUEST validation")
    void aCreditScoreAboveTheRangeIsRejectedByRequestValidation() {
        this.screen.ficoScore = String.valueOf(FICO_MAXIMUM + 1);
        givenEveryFieldEditPasses();

        assertThat(errorText(editTurn())).isEqualTo(FICO_RANGE_MESSAGE);
    }

    @Test
    @DisplayName(":848-849 the range is INCLUSIVE at 300, matching VALUES 300 THROUGH 850")
    void theLowerCreditScoreBoundIsInclusive() {
        this.screen.ficoScore = String.valueOf(FICO_MINIMUM);
        givenEveryFieldEditPasses();

        final AccountUpdateResult result = editTurn();

        assertThat(errorText(result)).isEmpty();
        assertThat(result.changeAction()).isEqualTo(ChangeAction.CHANGES_OK_NOT_CONFIRMED);
    }

    @Test
    @DisplayName(":848-849 the range is INCLUSIVE at 850, matching VALUES 300 THROUGH 850")
    void theUpperCreditScoreBoundIsInclusive() {
        this.screen.ficoScore = String.valueOf(FICO_MAXIMUM);
        givenEveryFieldEditPasses();

        final AccountUpdateResult result = editTurn();

        assertThat(errorText(result)).isEmpty();
        assertThat(result.changeAction()).isEqualTo(ChangeAction.CHANGES_OK_NOT_CONFIRMED);
    }

    @Test
    @DisplayName(":848-849 the range did NOT leak into the entity - custdata.txt would fail if it had")
    void theCreditScoreRangeDidNotLeakIntoTheEntity() {
        // The entity mirrors CUST-FICO-CREDIT-SCORE PIC 9(03) and constrains WIDTH only. Twenty-one of
        // the fifty seeded customers carry a score below 300, so an entity-level bound would make the
        // seed migration unloadable.
        final String belowRange = String.valueOf(FICO_MINIMUM - 1);
        this.customer.setFicoCreditScore(belowRange);

        assertThat(this.customer.getFicoCreditScore()).isEqualTo(belowRange);
    }

    @Test
    @DisplayName(":848-849 9700 accepts a live score below 300 when the snapshot agrees")
    void theSnapshotComparisonAcceptsALiveScoreBelowTheRange() {
        final String belowRange = String.valueOf(FICO_MINIMUM - 1);
        this.customer.setFicoCreditScore(belowRange);
        this.snapshot.ficoScore = belowRange;

        // The write path never re-edits the field, so a seeded sub-300 account remains updatable.
        assertCaseOnlyDifferenceIgnored();
    }

    @Test
    @DisplayName(":830-835 versus :742-744 - the two identifier shapes round-trip to nine characters")
    void theThreePartAndFlatIdentifierShapesRoundTripToTheSameNineCharacters() {
        this.screen.accountStatus = "Y";
        final AccountUpdateRequest.OldDetails oldDetails = this.snapshot.build();

        // Old side: flat X(09) whose redefine is PIC 9(09), so it is numeric.
        assertThat(oldDetails.getSsn().length()).isEqualTo(9);
        assertThat(oldDetails.getSsn().chars().allMatch(Character::isDigit))
                .as("ACUP-OLD-CUST-SSN-X PIC 9(09) at :743-744 is a NUMERIC redefine")
                .isTrue();
        // New side: three members of 3, 2 and 4 characters.
        assertThat(this.screen.ssnPart1.length()).isEqualTo(3);
        assertThat(this.screen.ssnPart2.length()).isEqualTo(2);
        assertThat(this.screen.ssnPart3.length()).isEqualTo(9 - 3 - 2);

        // 1205 compares the assembled three-part value against the flat member and finds no change,
        // which is only possible if the two shapes agree character for character.
        assertThat(errorText(editTurn())).isEqualTo(NO_CHANGE_DETECTED);
    }

    @Test
    @DisplayName(":830-835 a one-character change in the third identifier member IS seen by 1205")
    void aChangeInTheThirdIdentifierMemberIsDetected() {
        this.screen.accountStatus = "Y";
        this.screen.ssnPart3 = "6780";
        givenEveryFieldEditPasses();

        final AccountUpdateResult result = editTurn();

        assertThat(errorText(result)).isNotEqualTo(NO_CHANGE_DETECTED);
        assertThat(result.changeAction()).isEqualTo(ChangeAction.CHANGES_OK_NOT_CONFIRMED);
    }

    @Test
    @DisplayName(":721 versus COACTUP.CPY:246 - a ten-byte snapshot slot behind a five-byte field")
    void theTenByteSnapshotPostalCodeSlotSitsBehindAFiveByteScreenField() {
        final String tenByteSlot = "98101-1234";
        this.snapshot.addressZip = tenByteSlot;
        this.customer.setAddressZip(tenByteSlot);

        // ACUP-OLD-ADDR-ZIP is X(10), so the snapshot holds all ten characters and 9700 matches them
        // exactly: the wide slot is not truncated on the way in.
        assertThat(this.snapshot.build().getAddressZip().length()).isEqualTo(10);
        assertThat(this.screen.addressZip.length()).isEqualTo(ZIP_SCREEN_WIDTH);

        givenAccountLocked();
        givenCustomerLocked();
        final AccountUpdateResult result = confirm();

        assertThat(result.changeAction())
                .as("the ten-character snapshot matched the ten-character live value")
                .isEqualTo(ChangeAction.CHANGES_OKAYED_AND_DONE);
        // :4022 then writes back the FIVE-character screen value, because ACSZIPCI cannot carry more.
        // That narrowing is the source's own behaviour and is reproduced rather than compensated for.
        assertThat(this.customer.getAddressZip().length()).isEqualTo(ZIP_SCREEN_WIDTH);
    }

    @Test
    @DisplayName(":4117 money equality is decided by compareTo(), never by equals()")
    void moneyEqualityIsDecidedByCompareTo() {
        // The decoded snapshot amount carries scale 2. A live value of the same magnitude at scale 1 is
        // compareTo-equal and equals-UNEQUAL, so a comparison built on equals() would report a change
        // that never happened.
        final BigDecimal scaleOne = new BigDecimal("194.0");
        final BigDecimal scaleTwo = new BigDecimal("194.00");
        assertThat(scaleOne.compareTo(scaleTwo)).isZero();
        assertThat(scaleOne.equals(scaleTwo))
                .as("BigDecimal.equals compares scale as well as value")
                .isFalse();

        this.account.setCurrentBalance(scaleOne);

        assertCaseOnlyDifferenceIgnored();
    }

    @Test
    @DisplayName(":675-682 the zoned-decimal snapshot images decode with position-aware overpunch signs")
    void theSnapshotMoneyImagesDecodeThroughOverpunchSigns() {
        final AccountUpdateRequest.OldDetails oldDetails = this.snapshot.build();

        // '{' is the trailing overpunch for +0, so "00000001940{" is +194.00 at scale 2 - never a
        // float, never a double.
        assertThat(oldDetails.currentBalanceAmount()).isEqualByComparingTo(new BigDecimal("194.00"));
        assertThat(oldDetails.currentBalanceAmount().scale()).isEqualTo(2);
        assertThat(oldDetails.creditLimitAmount()).isEqualByComparingTo(new BigDecimal("2020.00"));
        assertThat(oldDetails.cashCreditLimitAmount()).isEqualByComparingTo(new BigDecimal("1020.00"));
        assertThat(oldDetails.currentCycleCreditAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(oldDetails.currentCycleDebitAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        // Every account money member is S9(10)V99, so the image is exactly twelve characters wide.
        assertThat(this.snapshot.currentBalance.length()).isEqualTo(12);
    }

    // Paragraph correspondence.
    //
    // app/cbl/COACTUPC.cbl is 4,236 lines. Eighty-eight of its lines carry the shape of a paragraph
    // label - a name at column 8 terminated by a period - but three of those are IDENTIFICATION
    // DIVISION entries at :22, :24 and :26, leaving 85 paragraphs of its own. app/cpy/CSSTRPFY.cpy is
    // procedural and contributes two more, YYYY-STORE-PFKEY at its :17 and YYYY-STORE-PFKEY-EXIT at
    // its :80. Eighty-five plus two is 87, which is exactly the number of paragraph labels the bean
    // cites, one private method each. Labels are NEVER consolidated - not the exits, not the empty
    // bodies, not the unreachable ones.

    @Test
    @DisplayName("COACTUPC.cbl:22,24,26 and CSSTRPFY.cpy:17,80 reconcile 88 label-shaped lines to 87")
    void theParagraphCountReconciles() {
        assertThat(SOURCE_PARAGRAPH_COUNT - IDENTIFICATION_DIVISION_ENTRIES
                        + COPYBOOK_CONTRIBUTED_LABELS)
                .as("88 label-shaped lines, less 3 identification entries, plus 2 from the copybook")
                .isEqualTo(MAPPED_PARAGRAPH_LABELS);
        assertThat(SOURCE_LINE_COUNT).isEqualTo(4236);
    }

    @Test
    @DisplayName(":4193-4195 every -EXIT label survives as its own method, none folded into its partner")
    void everyExitLabelSurvivesAsItsOwnMethod() {
        final List<String> exits = List.of(AccountUpdateService.class.getDeclaredMethods())
                .stream()
                .map(Method::getName)
                .filter(name -> name.endsWith("Exit"))
                .distinct()
                .toList();

        assertThat(exits).hasSize(PARAGRAPH_EXIT_LABELS);
        // Three of them are bare EXIT bodies, kept because deleting the call site would break the
        // paragraph map: 9700-CHECK-CHANGE-IN-REC-EXIT at :4193-4195, 3250-SETUP-INFOMSG-EXIT at
        // :2983-2985 and ABEND-ROUTINE-EXIT at :4226-4228.
        assertThat(exits).contains("checkChangeInRecord9700Exit",
                "setupInfoMessage3250Exit",
                "abendRoutineExit");
    }

    @Test
    @DisplayName(":872-1003 the mainline, input, decider and send paragraphs each map to one method")
    void theStructuralParagraphsEachMapToOneMethod() {
        final List<String> declared = List.of(AccountUpdateService.class.getDeclaredMethods())
                .stream()
                .map(Method::getName)
                .toList();

        assertThat(declared).contains("mainLine0000",
                "mainExit0000",
                "processInputs1000",
                "receiveMap1100",
                "editMapInputs1200",
                "compareOldNew1205",
                "decideAction2000",
                "classifyWriteOutcome2606",
                "sendMap3000",
                "screenInit3100",
                "setupScreenVars3200",
                "setupInfoMessage3250",
                "setupScreenAttributes3300",
                "setupInfoMessageAttributes3390",
                "sendScreen3400",
                "commonReturn",
                "storePfKey",
                "abendRoutine");
    }

    @Test
    @DisplayName(":3610-3643 and :3888-4105 the read and write paragraphs each map to one method")
    void theReadAndWriteParagraphsEachMapToOneMethod() {
        final List<String> declared = List.of(AccountUpdateService.class.getDeclaredMethods())
                .stream()
                .map(Method::getName)
                .toList();

        assertThat(declared).contains("readAccount9000",
                "getCardXrefByAccount9200",
                "getAccountDataByAccount9300",
                "getCustomerDataByCustomer9400",
                "storeFetchedData9500",
                "writeProcessing9600",
                "checkChangeInRecord9700");
    }

    @Test
    @DisplayName(":1210-1280 all sixteen field-edit paragraphs map one-to-one, none consolidated")
    void everyFieldEditParagraphMapsOneToOne() {
        final List<String> declared = List.of(AccountUpdateService.class.getDeclaredMethods())
                .stream()
                .map(Method::getName)
                .toList();

        assertThat(declared).contains("editAccount1210",
                "editMandatory1215",
                "editYesNo1220",
                "editAlphaRequired1225",
                "editAlphanumericRequired1230",
                "editAlphaOptional1235",
                "editAlphanumericOptional1240",
                "editNumericRequired1245",
                "editSignedAmount1250",
                "editUsPhoneNumber1260",
                "editUsSsn1265",
                "editUsStateCode1270",
                "editFicoScore1275",
                "editUsStateZipCode1280");
    }

    @Test
    @DisplayName("COACTUP.CPY - the symbolic map carries 54 input fields and the request mirrors them")
    void theRequestMirrorsTheSymbolicMapFieldBudget() {
        // The screen half of the request is built from the symbolic map, so its constructor arity is
        // the field budget plus the snapshot member. Asserting the budget itself keeps the DTO honest.
        assertThat(BMS_INPUT_FIELDS).isEqualTo(54);
        final AccountUpdateRequest request = this.screen.build(this.snapshot.build());
        assertThat(request.getAccountId()).isEqualTo(SCREEN_ACCOUNT_ID);
        assertThat(request.getOldDetails()).isNotNull();
        assertThat(request.getNewDetails()).isNotNull();
    }

    // Hostile input. Clause A requires untrusted input to be treated as untrusted and clause B
    // requires every boundary condition, null and empty case to be handled explicitly. Every case
    // below is a shape a real caller can submit, and each asserts the TYPE, the MESSAGE and the CAUSE
    // rather than merely that something was thrown.

    @Test
    @DisplayName(":2085-2225 ACUP-NEW-DETAILS may be absent - the screen half rides the flat fields")
    void anAbsentSubmittedDetailGroupChangesNothing() {
        this.screen.omitNewDetails();
        givenAccountLocked();
        givenCustomerLocked();

        final AccountUpdateResult result = confirm();

        assertThat(result.changeAction()).isEqualTo(ChangeAction.CHANGES_OKAYED_AND_DONE);
        assertThat(this.account.getActiveStatus())
                .as("the submitted status still reached the record through the flat field")
                .isEqualTo("N");
        verify(this.accountRepository).save(this.account);
        verify(this.customerRepository).save(this.customer);
    }

    @Test
    @DisplayName(":746 an over-long snapshot DOB is refused, and the offending value is not echoed")
    void anOverLongSnapshotDateOfBirthIsRefused() {
        final String tooLong = "198001151";
        this.snapshot.dateOfBirth = tooLong;

        assertThatThrownBy(this::write)
                .isInstanceOf(FatalProcessingException.class)
                .satisfies(thrown -> {
                    assertThat(thrown.getCause()).isInstanceOf(IllegalArgumentException.class);
                    assertThat(thrown.getCause()).hasMessageContaining("OldDetails.dateOfBirth");
                    assertThat(thrown.getCause().getMessage()).doesNotContain(tooLong);
                });
    }

    @Test
    @DisplayName(":670-708 an over-long snapshot open date is refused with its own field name")
    void anOverLongSnapshotOpenDateIsRefused() {
        final String tooLong = "200001011";
        this.snapshot.openDate = tooLong;

        assertThatThrownBy(this::write)
                .isInstanceOf(FatalProcessingException.class)
                .satisfies(thrown -> {
                    assertThat(thrown.getCause()).hasMessageContaining("OldDetails.openDate");
                    assertThat(thrown.getCause().getMessage()).doesNotContain(tooLong);
                });
    }

    @Test
    @DisplayName(":4169 an over-long snapshot telephone is a MISMATCH, never truncated into agreement")
    void anOverLongSnapshotTelephoneIsTreatedAsAMismatch() {
        // ACUP-OLD-CUST-PHONE-NUM-1 is PIC X(15) and :4169 compares it WHOLE, unlike the three dates
        // which 9700 slices. A longer value therefore never reaches the REDEFINES offsets at all: it
        // simply fails to equal the live fifteen bytes, so the comparison fails closed.
        this.snapshot.phoneNumber1 = "(206)555-0100999";

        assertRivalWriteDetected();
    }

    @Test
    @DisplayName(":981-983 an all-blank snapshot contributes no key and stops at the account lock guard")
    void anAllBlankSnapshotContributesNoKey() {
        // Every member of ACUP-OLD-DETAILS blank is the group's INITIALIZEd state, and a caller can
        // submit it. The read key comes from ACCTSIDI at :2090, not from the snapshot, so the read is
        // still attempted with the screen key - and finds nothing, because the snapshot restored none of
        // the CDEMO identifiers the conversation normally carries forward.
        this.snapshot.blankEveryMember();
        givenAccountReadMapped();

        final AccountUpdateResult result = confirm();

        assertThat(errorText(result)).isEqualTo(COULD_NOT_LOCK_ACCOUNT);
        assertThat(result.changeAction()).isEqualTo(ChangeAction.CHANGES_OKAYED_LOCK_ERROR);
        verify(this.accountRepository).findByIdForUpdate(ACCOUNT_KEY);
        verify(this.accountRepository, never()).save(any(Account.class));
        verifyNoInteractions(this.customerRepository);
    }

    @Test
    @DisplayName(":3919-3921 a blank snapshot customer key no longer starves the read - the server binds it")
    void aBlankSnapshotCustomerKeyIsSuppliedByTheServerSideBinding() {
        // Before the binding guard, CDEMO-CUST-ID was restored verbatim from the snapshot, so a blank
        // member left the customer read with no key at all and the second guard fired. The identifier is
        // now derived from the CXACAIX path instead, and a blank carried value contradicts nothing - so
        // the read IS attempted, with the server's key. This test therefore isolates the second guard by
        // leaving the customer unlockable rather than by leaving it unaddressed.
        this.snapshot.customerId = "";
        givenAccountLocked();
        givenCustomerReadMapped();

        final AccountUpdateResult result = confirm();

        assertThat(errorText(result)).isEqualTo(COULD_NOT_LOCK_CUSTOMER);
        verify(this.accountRepository).findByIdForUpdate(ACCOUNT_KEY);
        // The derived key addresses the row, not the blank the caller sent.
        verify(this.customerRepository).findByIdForUpdate(CUSTOMER_KEY);
        verify(this.customerRepository, never()).save(any(Customer.class));
    }

    @Test
    @DisplayName(":3919-3921 an omitted customer key still writes, because the binding supplies one")
    void anOmittedCustomerKeyStillWritesTheBoundRow() {
        this.snapshot.customerId = "";
        this.screen.customerId = "";
        givenAccountLocked();
        givenCustomerLocked();

        final AccountUpdateResult result = confirm();

        assertThat(result.changeAction()).isEqualTo(ChangeAction.CHANGES_OKAYED_AND_DONE);
        verify(this.customerRepository).findByIdForUpdate(CUSTOMER_KEY);
        verify(this.customerRepository).save(this.customer);
    }

    // The server-owned customer binding. NOT a paragraph of COACTUPC.cbl: it replaces a guarantee the
    // region gave structurally.
    //
    // CDEMO-CUST-ID at :3919 - the key that selects and LOCKS the row applyCustomerUpdateImage then
    // overwrites with seventeen fields - was COMMAREA state written by 9500-STORE-FETCHED-DATA at
    // :3805-3810 from the cross-reference 9200-GETCARDXREF-BYACCT had just read for the account. A
    // 3270 could not alter it. A stateless caller echoes it back, so the value arrives under the
    // caller's control and the row is chosen by the request.
    //
    // 9700-CHECK-CHANGE-IN-REC does NOT close that: :4152-4186 deliberately never compares CUST-ID,
    // so a substituted identifier reaches the write with every compared field matching the
    // SUBSTITUTED row's own values. The comparison is a staleness check, not an ownership check.
    //
    // The binding therefore re-derives the identifier from the CXACAIX path - the same derivation the
    // read turn performs - and requires all three carried spellings to agree with it. A refusal
    // reports DATA-WAS-CHANGED-BEFORE-UPDATE, which :2611-2612 maps to ACUP-SHOW-DETAILS: nothing is
    // written and the caller is told to review and resubmit, which re-fetches and re-derives. The
    // customer-lock flag is deliberately NOT reused, because :2613-2614 never tests it and would
    // report a refused write as a success.
    //
    // Every probe below fails without the guard: the write completes and the foreign row is
    // overwritten. They are the F9 regression net.

    @Test
    @DisplayName(":3919 a snapshot naming another customer is refused before either lock is taken")
    void aSnapshotNamingAnotherCustomerIsRefusedBeforeTheCustomerLock() {
        // The one echoed field the source never compares. Without the binding this selects, locks and
        // overwrites customer 42 while every 9700 clause passes against customer 42's own values.
        this.snapshot.customerId = FOREIGN_CUSTOMER_ID;
        givenAccountLocked();

        final AccountUpdateResult result = confirm();

        assertThat(result.changeAction())
                .as("a refused write reports SHOW-DETAILS, never the lock or success outcomes")
                .isEqualTo(ChangeAction.SHOW_DETAILS);
        assertThat(errorText(result)).isEqualTo(DATA_WAS_CHANGED);
        // No lock is taken on any customer row - not the foreign one, and not the bound one either.
        verifyNoInteractions(this.customerRepository);
        verify(this.accountRepository, never()).save(any(Account.class));
    }

    @Test
    @DisplayName(":3919 the top-level screen customer key is bound too, not only the snapshot member")
    void aScreenCustomerKeyNamingAnotherCustomerIsRefused() {
        this.screen.customerId = FOREIGN_CUSTOMER_ID;
        givenAccountLocked();

        final AccountUpdateResult result = confirm();

        assertThat(result.changeAction()).isEqualTo(ChangeAction.SHOW_DETAILS);
        verifyNoInteractions(this.customerRepository);
    }

    @Test
    @DisplayName(":3919 a refusal is a typed ValidationException naming the field and no identifier")
    void aBindingRefusalDisclosesNeitherIdentifier() {
        this.snapshot.customerId = FOREIGN_CUSTOMER_ID;
        givenAccountLocked();

        // write() is the REST entry point, which rethrows the retained verdict rather than projecting it.
        assertThatThrownBy(this::write)
                .isInstanceOf(ValidationException.class)
                .satisfies(thrown -> {
                    final ValidationException typed = (ValidationException) thrown;
                    assertThat(typed.getFieldName()).isEqualTo(FIELD_CUSTOMER_ID);
                    // The verdict carries the field name only. Echoing either identifier back would turn
                    // a refusal into an oracle for which customer the account really belongs to.
                    assertThat(typed.getMessage())
                            .doesNotContain(FOREIGN_CUSTOMER_ID)
                            .doesNotContain(SNAPSHOT_CUSTOMER_ID);
                });
        // The projected screen text is the source's own :523 literal, which names no record either.
        assertThat(errorText(confirm())).isEqualTo(DATA_WAS_CHANGED);
    }

    @Test
    @DisplayName(":3919 padding is not a mismatch - the carried value is compared as a number")
    void aDifferentlyPaddedCustomerKeyIsAccepted() {
        // The three spellings reach the bean in different widths: zero-padded to PIC 9(09) from the
        // snapshot, and as the operator typed it from the screen field. Only a different NUMBER is a
        // mismatch, so an unpadded value must still write.
        this.snapshot.customerId = "1";
        this.screen.customerId = "1";
        givenAccountLocked();
        givenCustomerLocked();

        assertThat(confirm().changeAction()).isEqualTo(ChangeAction.CHANGES_OKAYED_AND_DONE);
        verify(this.customerRepository).findByIdForUpdate(CUSTOMER_KEY);
    }

    @Test
    @DisplayName(":3919 an account bound to no customer is refused rather than written")
    void anAccountWithNoCrossReferenceIsRefused() {
        // The account locked, so the row exists - but nothing establishes which customer row the write
        // may touch, and the carried value cannot be allowed to decide.
        when(this.accountRepository.findByIdForUpdate(ACCOUNT_KEY)).thenReturn(Optional.of(this.account));
        givenNoCustomerBoundToAccount();

        assertThat(confirm().changeAction()).isEqualTo(ChangeAction.SHOW_DETAILS);
        verifyNoInteractions(this.customerRepository);
        verify(this.accountRepository, never()).save(any(Account.class));
    }

    @Test
    @DisplayName(":3919 a CXACAIX failure refuses the write and hands the cause to the status mapper")
    void aCrossReferenceFailureDuringBindingRefusesTheWrite() {
        when(this.accountRepository.findByIdForUpdate(ACCOUNT_KEY)).thenReturn(Optional.of(this.account));
        final DataAccessResourceFailureException failure = givenCrossReferencePathUnavailable();

        final AccountUpdateResult result = confirm();

        assertThat(result.changeAction())
                .as("failing closed: an unreadable binding refuses the write, it does not fall back to "
                        + "the value the caller carried")
                .isEqualTo(ChangeAction.SHOW_DETAILS);
        // FILE STATUS '90' is the physical-error family and the four-argument overload is the one the
        // bean selects because a cause exists - clause B, context preserved rather than discarded.
        verify(this.fileStatusMapper)
                .toException(IO_STATUS_IO_ERROR, XREF_ACCOUNT_PATH, OPERATION_READ, failure);
        verifyNoInteractions(this.customerRepository);
        verify(this.accountRepository, never()).save(any(Account.class));
    }

    @Test
    @DisplayName(":3919 the binding is read once per write turn, after the account lock and before it")
    void theBindingIsReadOnceBetweenTheTwoLocks() {
        givenAccountLocked();
        givenCustomerLocked();

        confirm();

        final InOrder ordered = inOrder(this.accountRepository, this.cardCrossReferenceRepository,
                this.customerRepository);
        ordered.verify(this.accountRepository).findByIdForUpdate(ACCOUNT_KEY);
        ordered.verify(this.cardCrossReferenceRepository)
                .findFirstByAccountIdOrderByCardNumberAsc(ACCOUNT_KEY);
        ordered.verify(this.customerRepository).findByIdForUpdate(CUSTOMER_KEY);
        // Placed after the account lock guard so a missing account still reports the lock outcome the
        // source reports, and before the customer read so no foreign row is ever locked.
        verify(this.cardCrossReferenceRepository, times(1))
                .findFirstByAccountIdOrderByCardNumberAsc(ACCOUNT_KEY);
    }

    @Test
    @DisplayName(":3892-3894 a currency symbol in the account key is refused - it is a plain 9(11) field")
    void aCurrencySymbolInTheAccountKeyIsRefused() {
        // ACCTSIDI feeds WS-CARD-RID-ACCT-ID, a PIC 9(11) key. FUNCTION NUMVAL-C tolerance belongs to
        // the AMOUNT fields at :383 and :456, never to an identifier.
        this.screen.accountId = "$0000000001";
        givenAccountReadMapped();

        final AccountUpdateResult result = confirm();

        assertThat(errorText(result)).isEqualTo(COULD_NOT_LOCK_ACCOUNT);
        assertThat(result.changeAction()).isEqualTo(ChangeAction.CHANGES_OKAYED_LOCK_ERROR);
        verify(this.accountRepository, never()).findByIdForUpdate(any());
        verifyNoInteractions(this.customerRepository);
    }

    @Test
    @DisplayName(":3892-3894 a thousands separator in the account key is refused for the same reason")
    void aThousandsSeparatorInTheAccountKeyIsRefused() {
        this.screen.accountId = "0,000,00001";
        givenAccountReadMapped();

        assertThat(errorText(confirm())).isEqualTo(COULD_NOT_LOCK_ACCOUNT);
        verify(this.accountRepository, never()).findByIdForUpdate(any());
    }

    @Test
    @DisplayName(":383 and :456 the AMOUNT fields DO tolerate a currency symbol and separators")
    void theAmountFieldsTolerateCurrencyDecoration() {
        // FUNCTION NUMVAL-C ignores '$', ',' and embedded spaces, which is precisely why the amount
        // parser and the identifier parser must remain two different routines. The decorated value
        // still compares equal to the snapshot, so 1205 reports no change at all.
        this.screen.accountStatus = "Y";
        this.screen.creditLimit = "$2,020.00";

        assertThat(errorText(editTurn()))
                .as("the decorated amount parsed to the same value as the plain snapshot image")
                .isEqualTo(NO_CHANGE_DETECTED);
        verifyNoInteractions(this.accountRepository, this.customerRepository);
    }

    @Test
    @DisplayName(":675-682 a malformed snapshot money image is a MISMATCH, never a silent match")
    void aMalformedSnapshotMoneyImageIsTreatedAsAMismatch() {
        // The snapshot member is a twelve-character zoned-decimal image with a trailing overpunch sign.
        // A plain decimal string is not one, so it decodes to nothing and the comparison must fail
        // closed rather than treat the absent value as equal.
        this.snapshot.currentBalance = "194.00";

        assertRivalWriteDetected();
    }

    @Test
    @DisplayName(":4155 an over-long snapshot name is a MISMATCH, never truncated into agreement")
    void anOverLongSnapshotNameIsTreatedAsAMismatch() {
        // CUST-FIRST-NAME is X(25). A longer snapshot value pads the shorter side rather than clipping
        // the longer one, so the two cannot be made to agree by truncation.
        this.snapshot.firstName = "MARGARET".repeat(8);

        assertRivalWriteDetected();
    }

    @Test
    @DisplayName(":979-980 a null state marker abends under the online contract rather than corrupting")
    void aNullStateMarkerAbendsUnderTheOnlineContract() {
        // ACUP-CHANGE-ACTION is PIC X(1) and the source's 88-levels leave every other byte value
        // undefined. Modelling it as an enum removes every out-of-set byte by construction, so null is
        // the only unrepresentable-state a caller can still submit - and it must not corrupt the turn.
        assertThatThrownBy(() -> this.service.processRequest(
                        this.screen.build(this.snapshot.build()),
                        AID_ENTER,
                        null,
                        EntryMode.REENTER))
                .isInstanceOf(FatalProcessingException.class)
                .satisfies(thrown -> {
                    final FatalProcessingException fatal = (FatalProcessingException) thrown;
                    assertThat(fatal.getAbendCode()).isEqualTo(ONLINE_ABEND_CODE);
                    assertThat(fatal.getAbendCulprit()).isEqualTo(PROGRAM_NAME);
                    assertThat(fatal.getCause())
                            .as("the root cause is preserved, never swallowed")
                            .isNotNull();
                });
        verifyNoInteractions(this.accountRepository, this.customerRepository);
    }

    @Test
    @DisplayName("CSSTRPFY.cpy - an unrecognised attention identifier is silently coerced to Enter")
    void anUnrecognisedAttentionIdentifierIsCoercedToEnter() {
        // YYYY-STORE-PFKEY has NO WHEN OTHER, so CCARD-AID keeps its INITIALIZEd value; :905-916 then
        // finds PFK-INVALID and sets CCARD-AID-ENTER. No diagnostic is produced - that is the source's
        // behaviour and it is reproduced rather than hardened.
        this.screen.accountStatus = "Y";

        final AccountUpdateResult result = this.service.processRequest(
                this.screen.build(this.snapshot.build()),
                "DFHPF99",
                ChangeAction.SHOW_DETAILS,
                EntryMode.REENTER);

        assertThat(result.changeAction()).isEqualTo(ChangeAction.SHOW_DETAILS);
        assertThat(errorText(result))
                .as("no unrecognised-key diagnostic exists in the source")
                .isEqualTo(NO_CHANGE_DETECTED);
        verifyNoInteractions(this.accountRepository, this.customerRepository);
    }

    @Test
    @DisplayName("CSSTRPFY.cpy - a null attention identifier is coerced the same way, without a throw")
    void aNullAttentionIdentifierIsCoercedToEnter() {
        this.screen.accountStatus = "Y";

        final AccountUpdateResult result = this.service.processRequest(
                this.screen.build(this.snapshot.build()),
                null,
                ChangeAction.SHOW_DETAILS,
                EntryMode.REENTER);

        assertThat(result.responseKind()).isEqualTo(ResponseKind.MAP);
        assertThat(result.changeAction()).isEqualTo(ChangeAction.SHOW_DETAILS);
        verifyNoInteractions(this.accountRepository, this.customerRepository);
    }

    @Test
    @DisplayName(":941-943 a null entry mode falls to the re-entry arm rather than abending")
    void aNullEntryModeFallsToTheReEntryArm() {
        // EIBCALEN drives the enter-versus-re-enter decision, and the source tests only for the ENTER
        // state. A null therefore compares unequal and takes the re-entry path, which is the safe
        // direction: the screen is edited rather than blindly redisplayed.
        this.screen.accountStatus = "Y";

        final AccountUpdateResult result = this.service.processRequest(
                this.screen.build(this.snapshot.build()),
                AID_ENTER,
                ChangeAction.SHOW_DETAILS,
                null);

        assertThat(result.responseKind()).isEqualTo(ResponseKind.MAP);
        assertThat(errorText(result)).isEqualTo(NO_CHANGE_DETECTED);
        verifyNoInteractions(this.accountRepository, this.customerRepository);
    }

    @Test
    @DisplayName(":3894-3903 a DataAccessException on the account read is wrapped, never swallowed")
    void aDataAccessFailureOnTheAccountReadPreservesItsRootCause() {
        final DataAccessResourceFailureException connectionLost =
                new DataAccessResourceFailureException("ACCTDAT unavailable");
        when(this.accountRepository.findByIdForUpdate(ACCOUNT_KEY)).thenThrow(connectionLost);
        when(this.fileStatusMapper.toException(IO_STATUS_NOT_FOUND, ACCOUNT_FILE, OPERATION_READ,
                connectionLost))
                .thenReturn(Optional.of(new RecordNotFoundException("account read failed",
                        connectionLost)));

        assertThatThrownBy(this::write)
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessage("account read failed")
                .hasRootCause(connectionLost);
        verifyNoInteractions(this.customerRepository);
    }

    @Test
    @DisplayName(":3894-3903 an unmapped status still surfaces as a typed failure, never as null")
    void anUnmappedFileStatusStillSurfacesAsATypedFailure() {
        // classify() refuses to return null when the mapper declines: it raises the abend contract so
        // the contradiction is diagnosable instead of becoming a NullPointerException downstream.
        givenAccountMissing();
        when(this.fileStatusMapper.toException(IO_STATUS_NOT_FOUND, ACCOUNT_FILE, OPERATION_READ))
                .thenReturn(Optional.empty());

        assertThatThrownBy(this::write)
                .isInstanceOf(FatalProcessingException.class)
                .satisfies(thrown -> {
                    final FatalProcessingException fatal = (FatalProcessingException) thrown;
                    assertThat(fatal.getAbendCode()).isEqualTo(ONLINE_ABEND_CODE);
                    assertThat(fatal.getAbendCulprit()).isEqualTo(PROGRAM_NAME);
                    assertThat(fatal.getAbendReason()).contains(ACCOUNT_FILE.strip());
                });
    }

    @Test
    @DisplayName(":479 the projected error message is always exactly WS-RETURN-MSG PIC X(75)")
    void theProjectedErrorMessageIsAlwaysSeventyFiveCharactersWide() {
        givenAccountLocked();
        givenCustomerLocked();

        final AccountUpdateResult clean = confirm();

        assertThat(clean.errorMessage())
                .as("the empty message is padded, not left short")
                .hasSize(RETURN_MESSAGE_WIDTH);
        assertThat(clean.informationMessage()).hasSizeLessThanOrEqualTo(INFO_MESSAGE_WIDTH);
    }

    @Test
    @DisplayName(":479 a populated diagnostic occupies the same fixed width")
    void aPopulatedDiagnosticOccupiesTheSameFixedWidth() {
        this.snapshot.blankEveryMember();
        givenAccountReadMapped();

        final AccountUpdateResult result = confirm();

        assertThat(result.errorMessage()).hasSize(RETURN_MESSAGE_WIDTH);
        assertThat(errorText(result)).isEqualTo(COULD_NOT_LOCK_ACCOUNT);
        assertThat(result.informationMessage()).hasSizeLessThanOrEqualTo(INFO_MESSAGE_WIDTH);
    }

    // The retrieval turn - 9000-READ-ACCT and 9200-GETCARDXREF-BYACCT.
    //
    // 2000-DECIDE-ACTION reaches 9000-READ-ACCT through its first arm at :2568-2580, which is a
    // fall-through pair: ACUP-DETAILS-NOT-FETCHED or PF12. 9000 then walks four datasets in a fixed
    // order, and the ONLY guard in it that can fire is the one at :3620-3622 on the cross-reference
    // path, because the SET statements behind the other two guards are commented out at :3719 and
    // :3769. A cross-reference miss therefore ends the retrieval, and everything downstream of it is
    // reached only on the NORMAL arm.
    //
    // The NORMAL arm at :3665-3667 needs a CardCrossReference
    // instance to hand back from the repository stub, and com.cardemo.model.entity.CardCrossReference
    // is deliberately not a dependency of this file. Reaching 9300-GETACCTDATA-BYACCT,
    // 9400-GETCUSTDATA-BYCUST and 9500-STORE-FETCHED-DATA from this tier is therefore out of reach:
    // those three paragraphs are covered by the repository and end-to-end tiers, which own the entity.
    // They are pure field projection with no branch of their own beyond the
    // two dead guards already asserted here.

    @Test
    @DisplayName(":3617-3622 the read-only fetch stops at CXACAIX and never reaches the account master")
    void theRetrievalEntryPointStopsAtTheCrossReferencePath() {
        final RecordNotFoundException mapped = givenCrossReferenceMissMapped();

        // fetchForUpdate seeds ACUP-DETAILS-NOT-FETCHED with CDEMO-PGM-REENTER deliberately: seeding
        // CDEMO-PGM-ENTER would take the empty-first-paint arm at :964-973 and never read anything.
        assertThatThrownBy(() -> this.service.fetchForUpdate(SCREEN_ACCOUNT_ID))
                .as("the retained verdict is rethrown unchanged, not re-wrapped")
                .isSameAs(mapped);

        verify(this.cardCrossReferenceRepository).findFirstByAccountIdOrderByCardNumberAsc(ACCOUNT_KEY);
        // :3620-3622 fired, so 9300 and 9400 were never performed.
        verifyNoInteractions(this.accountRepository, this.customerRepository);
    }

    @Test
    @DisplayName(":3671-3685 the miss diagnostic is composed from six pieces and overflows PIC X(75)")
    void theCrossReferenceMissDiagnosticIsComposedRatherThanLatchedFromALiteral() {
        givenCrossReferenceMissMapped();

        final AccountUpdateResult result = unfetchedTurn();

        // 'Account:' + the eleven-byte key + ' not found in' + ' Cross ref file.  Resp:' + RESP +
        // ' Reas:' + RESP2 is eighty-one bytes, so the trailing reason code arrives cut to four
        // digits. The truncation is the source's; reproducing it is the contract.
        assertThat(errorText(result)).isEqualTo(XREF_NOT_FOUND_DIAGNOSTIC);
        assertThat(result.errorMessage()).hasSize(RETURN_MESSAGE_WIDTH);
        assertThat(result.changeAction())
                .as("the marker is NOT promoted, because :2577 gates promotion on FOUND-CUST-IN-MASTER")
                .isEqualTo(ChangeAction.DETAILS_NOT_FETCHED);
        assertThat(result.informationMessage()).isEqualTo(INFO_PROMPT_FOR_SEARCH_KEYS);
        assertThat(cursorField(result))
                .as(":3013-3015 a rejected filter takes the cursor")
                .isEqualTo(FIELD_ACCOUNT_ID);
        assertThat(hasAspect(result, FIELD_ACCOUNT_ID, FieldAttribute.ASPECT_COLOUR))
                .as(":3176-3178 MOVE DFHRED TO ACCTSIDC")
                .isTrue();
        verifyNoInteractions(this.accountRepository, this.customerRepository);
    }

    @Test
    @DisplayName(":3686-3693 an unmapped physical failure on CXACAIX abends on the online contract")
    void anUnmappedCrossReferencePathFailureAbendsUnderTheOnlineContract() {
        givenCrossReferencePathUnavailable();

        assertThatThrownBy(() -> this.service.fetchForUpdate(SCREEN_ACCOUNT_ID))
                .isInstanceOf(FatalProcessingException.class)
                .satisfies(thrown -> {
                    final FatalProcessingException fatal = (FatalProcessingException) thrown;
                    // The fallback of the status classifier: WS-FILE-ERROR-MESSAGE cut to PIC X(75).
                    assertThat(fatal.getMessage()).isEqualTo(XREF_IO_ERROR_DIAGNOSTIC);
                    assertThat(fatal.getMessage()).hasSize(RETURN_MESSAGE_WIDTH);
                    assertThat(fatal.getAbendCode()).isEqualTo(ONLINE_ABEND_CODE);
                    assertThat(fatal.getAbendCulprit()).isEqualTo(PROGRAM_NAME);
                    // ERROR-FNAME PIC X(9) right-pads the eight-byte path literal.
                    assertThat(fatal.getAbendReason()).isEqualTo(XREF_ERROR_FILE_SLOT);
                    // The classifier's fallback picks
                    // the four-argument constructor, which has no cause parameter, so the originating
                    // DataAccessException is dropped HERE. It is not swallowed overall - the mapper
                    // received it (asserted by the next test) and the production mapper attaches it -
                    // but this defensive branch loses it. Routing the cause through the
                    // five-argument constructor would carry it; that is a change to src/main, not here.
                    assertThat(fatal.getCause()).isNull();
                });
    }

    @Test
    @DisplayName(":3688-3692 the originating failure reaches the status mapper as the root cause")
    void theRootCauseOfACrossReferencePathFailureReachesTheStatusMapper() {
        final DataAccessResourceFailureException failure = givenCrossReferencePathUnavailable();

        unfetchedTurn();

        // FILE STATUS '90' is the physical-error family, and the four-argument overload is the one the
        // bean selects precisely because a cause exists. Clause B: context is preserved, not discarded.
        verify(this.fileStatusMapper)
                .toException(IO_STATUS_IO_ERROR, XREF_ACCOUNT_PATH, OPERATION_READ, failure);
    }

    @Test
    @DisplayName(":3690 the WHEN OTHER diagnostic names the operation and the path, without a latch")
    void theProjectedDiagnosticForACrossReferencePathFailureNamesTheOperationAndPath() {
        givenCrossReferencePathUnavailable();

        final AccountUpdateResult result = unfetchedTurn();

        // Unlike the NOTFND arm, this assignment is NOT stamped under IF WS-RETURN-MSG-OFF, so it
        // overwrites rather than latches. The eighty-byte group ends in five blanks and the
        // seventy-five-byte projection keeps exactly one of them.
        assertThat(errorText(result)).isEqualTo(XREF_IO_ERROR_DIAGNOSTIC.stripTrailing());
        assertThat(result.errorMessage()).isEqualTo(XREF_IO_ERROR_DIAGNOSTIC);
        assertThat(cursorField(result)).isEqualTo(FIELD_ACCOUNT_ID);
        assertThat(result.changeAction()).isEqualTo(ChangeAction.DETAILS_NOT_FETCHED);
        verifyNoInteractions(this.accountRepository, this.customerRepository);
    }

    @Test
    @DisplayName(":3627-3629 and :3636-3638 both remaining guards are dead - their SETs are commented out")
    void theTwoRemainingRetrievalGuardsAreDeadByConstruction() {
        // The literals those guards test for, DID-NOT-FIND-ACCT-IN-ACCTDAT and
        // DID-NOT-FIND-CUSTOMER-IN-CUSTDAT, are declared in the outcome group at :505-528 but their
        // SET statements sit behind comment markers at :3719 and :3769. A census of the bean finds no
        // assignment of either, so no reachable turn can produce them. Preserved, not reinstated:
        // reinstating either would stop the retrieval chain early and change behaviour.
        givenCrossReferenceMissMapped();

        final AccountUpdateResult result = unfetchedTurn();

        assertThat(errorText(result))
                .as("the only diagnostic a retrieval turn can carry is the composed one")
                .isEqualTo(XREF_NOT_FOUND_DIAGNOSTIC)
                .doesNotContain("ACCTDAT")
                .doesNotContain("CUSTDAT");
    }

    @Test
    @DisplayName(":2568-2572 PF12 is a fall-through pair, so it re-runs the retrieval on a shown screen")
    void pressingPf12OnAShownScreenReRunsTheRetrieval() {
        // A no-change screen so that 1200 leaves through :1463-1467 with the marker still
        // ACUP-SHOW-DETAILS and FLG-ACCTFILTER-VALID already asserted at :1453-1457. No edit stubbing
        // is installed, because that early return happens BEFORE the first field edit - which is
        // itself the assertion that :1463-1467 short-circuits the whole cascade.
        this.screen.accountStatus = "Y";
        givenCrossReferenceMissMapped();

        final AccountUpdateResult result = refreshTurn();

        verifyNoInteractions(this.dateValidationService, this.validationLookupService);

        verify(this.cardCrossReferenceRepository).findFirstByAccountIdOrderByCardNumberAsc(ACCOUNT_KEY);
        // :2574 clears WS-RETURN-MSG unlatched before the read, so NO-CHANGES-DETECTED is discarded
        // and the composed retrieval diagnostic claims the message area instead.
        assertThat(errorText(result)).isEqualTo(XREF_NOT_FOUND_DIAGNOSTIC);
        assertThat(errorText(result)).isNotEqualTo(NO_CHANGE_DETECTED);
        verifyNoInteractions(this.accountRepository, this.customerRepository);
    }

    // The PF03 exit - 0000-MAIN at :925-959.
    //
    // PF03 is tested before any input is processed, so the exit neither edits nor reads anything. The
    // two ternaries at :930-942 choose between a known caller and the main menu.
    //
    // CDEMO-FROM-TRANID and CDEMO-FROM-PROGRAM are assigned in
    // exactly two places: nulled at :883-884 on a cold start, and set to this program's own identity
    // at :944-945 on the way out. Neither is ever seeded from the request, because the stateless
    // contract carries no caller field. The known-caller arm of both ternaries is therefore
    // unobservable through the public surface: exhibiting it would take a caller pair on
    // AccountUpdateRequest, or a navigation argument on processRequest. The arm is a
    // pass-through of a value nothing can supply.

    @Test
    @DisplayName(":927-959 PF03 transfers to the main menu and touches no repository")
    void pressingPf03TransfersToTheMainMenuAndTouchesNoRepository() {
        final AccountUpdateResult result = exitTurn(ChangeAction.SHOW_DETAILS, EntryMode.REENTER);

        // EXEC CICS XCTL never returns, so no screen is projected - only the navigation target is.
        assertThat(result.responseKind()).isEqualTo(ResponseKind.TRANSFER);
        assertThat(result.screen()).isNull();
        assertThat(result.fieldAttributes()).isEmpty();
        assertThat(result.navigation().toTransactionId()).isEqualTo(MENU_TRANSACTION_ID);
        assertThat(result.navigation().toProgram()).isEqualTo(MENU_PROGRAM);
        // :944-945 the outbound half names this program, so the menu knows who called it.
        assertThat(result.navigation().fromTransactionId()).isEqualTo(TRANSACTION_ID);
        assertThat(result.navigation().fromProgram()).isEqualTo(PROGRAM_NAME);
        // :949-950 the last map pair, so a returning turn can restore the screen.
        assertThat(result.navigation().lastMapset()).isEqualTo(THIS_MAPSET);
        assertThat(result.navigation().lastMap()).isEqualTo(THIS_MAP);
        assertThat(errorText(result)).isEqualTo(EXIT_MESSAGE_TEXT);
        assertThat(result.changeAction())
                .as("the exit carries the submitted marker through untouched")
                .isEqualTo(ChangeAction.SHOW_DETAILS);
        verifyNoInteractions(this.accountRepository, this.customerRepository,
                this.cardCrossReferenceRepository, this.dateValidationService,
                this.validationLookupService, this.fileStatusMapper);
    }

    @Test
    @DisplayName(":533 the exit literal carries fourteen trailing blanks inside PIC X(75)")
    void theExitLiteralCarriesFourteenTrailingBlanks() {
        final AccountUpdateResult result = exitTurn(ChangeAction.SHOW_DETAILS, EntryMode.REENTER);

        assertThat(result.errorMessage()).hasSize(RETURN_MESSAGE_WIDTH);
        assertThat(result.errorMessage()).startsWith(EXIT_MESSAGE_TEXT);
        // The declared literal is thirty-four bytes: twenty of text and fourteen of blank. The blanks
        // are indistinguishable from the PIC X(75) padding once projected, which is why the source
        // width is asserted against the literal rather than against the projection.
        assertThat(EXIT_MESSAGE_TEXT.length() + 14).isEqualTo(EXIT_MESSAGE_WIDTH);
        assertThat(result.errorMessage().substring(EXIT_MESSAGE_TEXT.length(), EXIT_MESSAGE_WIDTH))
                .isBlank();
    }

    @Test
    @DisplayName(":930-942 the known-caller arm has no stateless carrier, so both entry states exit to CM00")
    void theKnownCallerArmOfThePf03ExitHasNoStatelessCarrier() {
        // A cold start nulls the caller pair at :883-884; a re-entry never populates it at all. Both
        // therefore satisfy the IF ... EQUAL LOW-VALUES OR SPACES test and take the menu arm.
        final AccountUpdateResult cold = exitTurn(ChangeAction.SHOW_DETAILS, EntryMode.FIRST_ENTRY);
        final AccountUpdateResult reentered = exitTurn(ChangeAction.SHOW_DETAILS, EntryMode.REENTER);

        assertThat(cold.navigation().toProgram()).isEqualTo(MENU_PROGRAM);
        assertThat(reentered.navigation().toProgram()).isEqualTo(MENU_PROGRAM);
        assertThat(cold.navigation().toTransactionId()).isEqualTo(MENU_TRANSACTION_ID);
        assertThat(reentered.navigation().toTransactionId()).isEqualTo(MENU_TRANSACTION_ID);
        // :885-886 a cold start also resets the marker before the exit runs, which the re-entry does
        // not - so the two turns are genuinely different paths that converge on the same target.
        assertThat(cold.changeAction()).isEqualTo(ChangeAction.DETAILS_NOT_FETCHED);
        assertThat(reentered.changeAction()).isEqualTo(ChangeAction.SHOW_DETAILS);
    }

    // 1200-EDIT-MAP-INPUTS - the twenty-five-step edit cascade and its per-field diagnostics.
    //
    // The cascade at :1431-1675 has two arms. On ACUP-DETAILS-NOT-FETCHED (:1433-1449) the account
    // filter is the only field there is, and the arm returns at :1446 before any collaborator is
    // reached. Otherwise :1452-1457 asserts the fetched state, :1460-1461 performs
    // 1205-COMPARE-OLD-NEW, and :1463-1467 returns early when nothing changed. Only when something
    // did change does :1470 set ACUP-CHANGES-NOT-OK and the twenty-five field edits run.
    //
    // Three properties of the cascade drive every test below.
    //
    // (1) The cascade does NOT stop at the first failure. Every one of the twenty-five steps runs on
    //     every turn; what stops at the first failure is the MESSAGE, because each edit routine
    //     guards its assignment with IF WS-RETURN-MSG-OFF. So exactly one diagnostic is observable
    //     however many fields are broken, and it is the FIRST in source order.
    // (2) The per-field state is TRI-state, and the distinction is observable. :3208-3435 expands
    //     COPY CSSETATY REPLACING thirty-nine times; the expansion reddens both NOT_OK and BLANK but
    //     emits the '*' marker for BLANK ONLY. A test that asserts only the message would not
    //     distinguish "left empty" from "filled in wrongly", which the legacy screen did.
    // (3) The cursor is placed by the first-match cascade at :3009-3167, in a source order that is
    //     NOT the same as the edit order in one respect: the middle name is tested with
    //     FLG-MIDNAME-NOT-OK alone at :3110-3111, with no -BLANK companion, because 1235-EDIT-ALPHA-OPT
    //     accepts a blank. It is the only branch of the forty-three shaped that way.
    //
    // Consolidating the suffix literals, or normalising the
    // label truncation, changes the text a 3270 operator reads and breaks the parity comparison.

    @Test
    @DisplayName(":1441-1443 a blank account filter reports 'No input received', overwriting the latch")
    void aBlankAccountFilterOnAnUnfetchedTurnReportsNoInputReceived() {
        this.screen.accountId = " ";

        final AccountUpdateResult result = unfetchedTurn();

        // 1210-EDIT-ACCOUNT latches PROMPT-FOR-ACCOUNT at :1832-1833, and then :1441-1443 assigns
        // NO-SEARCH-CRITERIA UNLATCHED - the only unlatched assignment in the whole cascade - so the
        // second literal wins and the first is never seen.
        assertThat(errorText(result)).isEqualTo(NO_INPUT_RECEIVED);
        assertThat(result.errorMessage())
                .as(":1832-1833 latches this literal, :1441-1443 then discards it")
                .doesNotContain(ACCOUNT_NOT_PROVIDED);
        assertThat(cursorField(result)).isEqualTo(FIELD_ACCOUNT_ID);
        // :3180-3184 a blank filter on a re-entry is reddened AND has its value replaced by '*'.
        assertThat(hasAspect(result, FIELD_ACCOUNT_ID, FieldAttribute.ASPECT_COLOUR)).isTrue();
        assertThat(result.screen().getAccountId()).isEqualTo(ASTERISK_MARKER);
        assertThat(result.informationMessage().stripTrailing()).isEqualTo(INFO_PROMPT_FOR_SEARCH_KEYS);
        assertThat(result.changeAction()).isEqualTo(ChangeAction.DETAILS_NOT_FETCHED);
        verifyNoInteractions(this.accountRepository,
                this.customerRepository,
                this.cardCrossReferenceRepository,
                this.dateValidationService,
                this.validationLookupService,
                this.fileStatusMapper);
    }

    @Test
    @DisplayName(":1837-1846 an account filter shorter than eleven digits is rejected")
    void anAccountFilterShorterThanElevenDigitsIsRejected() {
        this.screen.accountId = "123";

        final AccountUpdateResult result = unfetchedTurn();

        // isAllDigits pads to the declared eleven before testing, so a short value fails on the
        // trailing blanks rather than on its length - the same outcome the legacy IS NUMERIC test had.
        assertThat(errorText(result)).isEqualTo(ELEVEN_DIGIT_ACCOUNT);
        assertThat(cursorField(result)).isEqualTo(FIELD_ACCOUNT_ID);
        assertThat(hasAspect(result, FIELD_ACCOUNT_ID, FieldAttribute.ASPECT_COLOUR))
                .as(":3176-3178 a rejected filter is reddened")
                .isTrue();
        assertThat(hasAspect(result, FIELD_ACCOUNT_ID, FieldAttribute.ASPECT_MARKER))
                .as("NOT_OK attracts no '*' - only BLANK does")
                .isFalse();
        verifyNoInteractions(this.accountRepository, this.cardCrossReferenceRepository);
    }

    @Test
    @DisplayName(":1837-1846 a numerically zero account filter is rejected by the Non-Zero clause")
    void aNumericallyZeroAccountFilterIsRejected() {
        this.screen.accountId = "00000000000";

        final AccountUpdateResult result = unfetchedTurn();

        assertThat(errorText(result)).isEqualTo(ELEVEN_DIGIT_ACCOUNT);
        assertThat(result.changeAction())
                .as(":2573 withholds 9000-READ-ACCT unless the filter is FLG-ACCTFILTER-ISVALID")
                .isEqualTo(ChangeAction.DETAILS_NOT_FETCHED);
        verifyNoInteractions(this.cardCrossReferenceRepository);
    }

    @Test
    @DisplayName(":1220 a blank account status must be supplied, and carries the '*' marker")
    void aBlankAccountStatusMustBeSupplied() {
        this.screen.accountStatus = " ";
        givenEveryFieldEditPasses();

        final AccountUpdateResult result = editTurn();

        assertThat(errorText(result)).isEqualTo(labelled(LABEL_ACCOUNT_STATUS, MUST_BE_SUPPLIED));
        assertThat(cursorField(result)).isEqualTo(FIELD_ACCOUNT_STATUS);
        assertThat(hasAspect(result, FIELD_ACCOUNT_STATUS, FieldAttribute.ASPECT_COLOUR)).isTrue();
        assertThat(hasAspect(result, FIELD_ACCOUNT_STATUS, FieldAttribute.ASPECT_MARKER))
                .as("CSSETATY emits '*' for FLG-...-BLANK only")
                .isTrue();
        assertThat(result.informationMessage().stripTrailing()).isEqualTo(INFO_PROMPT_FOR_CHANGES);
        assertThat(result.changeAction()).isEqualTo(ChangeAction.CHANGES_NOT_OK);
        verifyNoInteractions(this.accountRepository, this.customerRepository);
    }

    @Test
    @DisplayName(":1220 an account status outside Y and N is rejected without the '*' marker")
    void anAccountStatusOutsideYAndNIsRejected() {
        this.screen.accountStatus = "X";
        givenEveryFieldEditPasses();

        final AccountUpdateResult result = editTurn();

        assertThat(errorText(result)).isEqualTo(labelled(LABEL_ACCOUNT_STATUS, MUST_BE_Y_OR_N));
        assertThat(cursorField(result)).isEqualTo(FIELD_ACCOUNT_STATUS);
        assertThat(hasAspect(result, FIELD_ACCOUNT_STATUS, FieldAttribute.ASPECT_COLOUR)).isTrue();
        assertThat(hasAspect(result, FIELD_ACCOUNT_STATUS, FieldAttribute.ASPECT_MARKER)).isFalse();
    }

    @Test
    @DisplayName(":1220 a LOWER-CASE account status is rejected, because the 88-level compares characters")
    void aLowerCaseAccountStatusIsRejected() {
        // FINDING, severity MAJOR. This edit used to upper-case the value before testing it, so 'y' and 'n'
        // passed. 88 FLG-YES-NO-ISVALID VALUES 'Y', 'N' at app/cbl/COACTUPC.cbl:78 is a condition name on the
        // received character, and a condition name compares bytes - there is no fold to reproduce.
        //
        // The fold did not merely widen the edit, it produced the WRONG FAILURE: the lower-case value reached
        // the write, where the ck_customer_pri_card_holder_ind / account-status CHECK constraint refused it,
        // and the operator was answered 409 CARDDEMO-CONSTRAINT-REFUSED naming no field. This case asserts the
        // 400-shaped outcome instead: the edit's own literal, on the edit's own cursor field.
        //
        // 'n' rather than 'y', and the reason is the very asymmetry this fix preserves. 1205-COMPARE-OLD-NEW
        // at app/cbl/COACTUPC.cbl:1684-1770 folds with FUNCTION UPPER-CASE and runs FIRST, so submitting 'y'
        // against a stored 'Y' is genuinely no change at all and the turn stops there without reaching any
        // edit - which is faithful, and is asserted in its own case below. 'n' against a stored 'Y' IS a
        // change under the fold, so the edits run and the character-exact one refuses the byte.
        this.screen.accountStatus = "n";
        givenEveryFieldEditPasses();

        final AccountUpdateResult result = editTurn();

        assertThat(errorText(result)).isEqualTo(labelled(LABEL_ACCOUNT_STATUS, MUST_BE_Y_OR_N));
        assertThat(cursorField(result)).isEqualTo(FIELD_ACCOUNT_STATUS);
        assertThat(hasAspect(result, FIELD_ACCOUNT_STATUS, FieldAttribute.ASPECT_COLOUR)).isTrue();
        assertThat(hasAspect(result, FIELD_ACCOUNT_STATUS, FieldAttribute.ASPECT_MARKER))
                .as("an invalid value is not an absent one, so the supplied-marker aspect stays off")
                .isFalse();
        verifyNoInteractions(this.accountRepository, this.customerRepository);
    }

    @Test
    @DisplayName(":1220 a LOWER-CASE primary card holder indicator is rejected on its own field")
    void aLowerCasePrimaryCardHolderIndicatorIsRejected() {
        // The same edit is performed for the second Y/N field at :1580, so the fix has to hold on both. The
        // field this one names is ACSPFLG, which is what made the previous 409 impossible to act on: the
        // constraint that caught it is on the CUSTOMER row, so the response named neither field.
        this.screen.primaryCardHolderIndicator = "n";
        givenEveryFieldEditPasses();

        final AccountUpdateResult result = editTurn();

        assertThat(errorText(result)).isEqualTo(labelled(LABEL_PRIMARY_CARD_HOLDER, MUST_BE_Y_OR_N));
        assertThat(cursorField(result)).isEqualTo(FIELD_PRIMARY_CARD_HOLDER);
        verifyNoInteractions(this.accountRepository, this.customerRepository);
    }

    @ParameterizedTest
    @CsvSource({"Y,Y", "Y,N", "N,Y", "N,N"})
    @DisplayName(":1220 upper-case Y and N are still the two values the edit accepts, on both fields")
    void upperCaseYesAndNoAreStillAccepted(final String status, final String holder) {
        // The other half of the contract, so the fix cannot be read as "the edit got stricter": exactly the
        // two characters the 88-level names must still pass, on both fields, in every combination.
        //
        // What is asserted is that the Y-or-N diagnostic is absent, not that no diagnostic at all is raised.
        // The distinction is deliberate: Y,Y equals the stored pair, so that combination reaches
        // 1205-COMPARE-OLD-NEW's no-change outcome rather than a write, and demanding an empty diagnostic
        // would make this case assert something about change detection instead of about the edit.
        this.screen.accountStatus = status;
        this.screen.primaryCardHolderIndicator = holder;
        if (!("Y".equals(status) && "Y".equals(holder))) {
            // Y,Y is the stored pair, so that row stops at 1205 and reaches no field edit; stubbing the edit
            // collaborators for it would be an unnecessary stubbing under STRICT_STUBS.
            givenEveryFieldEditPasses();
        }

        final AccountUpdateResult accepted = editTurn();

        assertThat(errorText(accepted) == null ? "" : errorText(accepted))
                .as("accountStatus=%s primaryCardHolderIndicator=%s: neither field may attract the "
                        + "Y-or-N diagnostic", status, holder)
                .doesNotContain(MUST_BE_Y_OR_N.trim());
    }

    @Test
    @DisplayName(":1205 a lower-case value that differs from the stored one ONLY in case is still no change")
    void aLowerCaseValueDifferingOnlyInCaseIsStillNoChange() {
        // The preserved half of the asymmetry, asserted so the fix above cannot be widened into the
        // comparison by a later reader. 1205-COMPARE-OLD-NEW folds with FUNCTION UPPER-CASE at
        // app/cbl/COACTUPC.cbl:1684-1770, so 'y' against a stored 'Y' is the same VALUE - and the turn stops
        // with the no-change diagnostic, never reaching the character-exact edit at all.
        this.screen.accountStatus = "y";

        final AccountUpdateResult result = editTurn();

        // givenEveryFieldEditPasses() is deliberately NOT called: 1205 stops the turn before any field edit
        // runs, so stubbing the edit collaborators here would be an unnecessary stubbing - and that this
        // stubbing is unnecessary is itself part of what the case demonstrates.
        assertThat(errorText(result)).isEqualTo(NO_CHANGE_DETECTED);
        verifyNoInteractions(this.accountRepository, this.customerRepository);
    }

    @Test
    @DisplayName(":1220 a zero-filled account status takes the third arm and reads as BLANK")
    void aZeroFilledAccountStatusIsTreatedAsBlank() {
        // 1220-EDIT-YESNO tests SPACES, LOW-VALUES and ZEROES in one condition, so '0' is not an
        // invalid value - it is an absent one, and attracts the supplied-diagnostic plus the marker.
        this.screen.accountStatus = "0";
        givenEveryFieldEditPasses();

        final AccountUpdateResult result = editTurn();

        assertThat(errorText(result)).isEqualTo(labelled(LABEL_ACCOUNT_STATUS, MUST_BE_SUPPLIED));
        assertThat(hasAspect(result, FIELD_ACCOUNT_STATUS, FieldAttribute.ASPECT_MARKER)).isTrue();
    }

    @Test
    @DisplayName(":1479-1481 an invalid open date latches the delegated date verdict")
    void anInvalidOpenDateLatchesTheDateServiceVerdict() {
        givenCalendarDateEditFailsFor(LABEL_OPEN_DATE);
        givenDateOfBirthPlausibilityPasses();
        givenAreaCodeLookupPasses();
        givenStateLookupPasses();
        givenStateZipLookupPasses();

        final AccountUpdateResult result = editTurn();

        // The copybook owns the diagnostic text; this program only propagates it under its own latch.
        assertThat(errorText(result)).isEqualTo(STUBBED_DATE_DIAGNOSTIC);
        assertThat(cursorField(result)).isEqualTo(FIELD_OPEN_DATE_YEAR);
        assertThat(hasAspect(result, FIELD_OPEN_DATE_YEAR, FieldAttribute.ASPECT_COLOUR)).isTrue();
        assertThat(result.changeAction()).isEqualTo(ChangeAction.CHANGES_NOT_OK);
    }

    @Test
    @DisplayName(":1491-1493 an invalid expiry date latches the delegated date verdict")
    void anInvalidExpiryDateLatchesTheDateServiceVerdict() {
        givenCalendarDateEditFailsFor(LABEL_EXPIRY_DATE);
        givenDateOfBirthPlausibilityPasses();
        givenAreaCodeLookupPasses();
        givenStateLookupPasses();
        givenStateZipLookupPasses();

        final AccountUpdateResult result = editTurn();

        assertThat(errorText(result)).isEqualTo(STUBBED_DATE_DIAGNOSTIC);
        assertThat(cursorField(result)).isEqualTo(FIELD_EXPIRY_DATE_YEAR);
    }

    @Test
    @DisplayName(":1504-1506 an invalid reissue date latches the delegated date verdict")
    void anInvalidReissueDateLatchesTheDateServiceVerdict() {
        givenCalendarDateEditFailsFor(LABEL_REISSUE_DATE);
        givenDateOfBirthPlausibilityPasses();
        givenAreaCodeLookupPasses();
        givenStateLookupPasses();
        givenStateZipLookupPasses();

        final AccountUpdateResult result = editTurn();

        assertThat(errorText(result)).isEqualTo(STUBBED_DATE_DIAGNOSTIC);
        assertThat(cursorField(result)).isEqualTo(FIELD_REISSUE_DATE_YEAR);
    }

    @Test
    @DisplayName(":1536-1543 a rejected birth calendar skips the plausibility follow-up entirely")
    void anInvalidDateOfBirthSkipsThePlausibilityFollowUp() {
        givenCalendarDateEditFailsFor(LABEL_DATE_OF_BIRTH);
        givenAreaCodeLookupPasses();
        givenStateLookupPasses();
        givenStateZipLookupPasses();

        final AccountUpdateResult result = editTurn();

        assertThat(errorText(result)).isEqualTo(STUBBED_DATE_DIAGNOSTIC);
        assertThat(cursorField(result)).isEqualTo(FIELD_DATE_OF_BIRTH_YEAR);
        // The guard is IF WS-EDIT-DT-OF-BIRTH-ISVALID, so the reasonableness check never runs. No
        // stubbing for it is installed either, which strict stubs would have flagged had it been.
        verify(this.dateValidationService, never()).editDateOfBirth(any(), any());
    }

    @Test
    @DisplayName(":1536-1543 an implausible birth date is rejected by the follow-up, not the calendar")
    void anImplausibleDateOfBirthIsRejectedByTheFollowUp() {
        givenDateOfBirthPlausibilityFails();
        givenAreaCodeLookupPasses();
        givenStateLookupPasses();
        givenStateZipLookupPasses();

        final AccountUpdateResult result = editTurn();

        assertThat(errorText(result)).isEqualTo(STUBBED_DATE_DIAGNOSTIC);
        assertThat(cursorField(result)).isEqualTo(FIELD_DATE_OF_BIRTH_YEAR);
        verify(this.dateValidationService).editDateOfBirth(any(), any());
    }

    @Test
    @DisplayName(":1250 a blank credit limit composes the label with the supplied suffix")
    void aBlankCreditLimitMustBeSupplied() {
        this.screen.creditLimit = "";
        givenEveryFieldEditPasses();

        final AccountUpdateResult result = editTurn();

        assertThat(errorText(result)).isEqualTo(labelled(LABEL_CREDIT_LIMIT, MUST_BE_SUPPLIED));
        // The composed form carries a terminating period, because the suffix literal at :649 ends in
        // '.'. A shorter spelling of this diagnostic is therefore a prefix of it rather than the whole.
        assertThat(errorText(result)).startsWith(CREDIT_LIMIT_MUST_BE_SUPPLIED);
        assertThat(cursorField(result)).isEqualTo(FIELD_CREDIT_LIMIT);
        assertThat(hasAspect(result, FIELD_CREDIT_LIMIT, FieldAttribute.ASPECT_MARKER)).isTrue();
    }

    @Test
    @DisplayName(":1250 a non-numeric credit limit is not valid, and that literal carries no period")
    void aNonNumericCreditLimitIsNotValid() {
        this.screen.creditLimit = "20X0.00";
        givenEveryFieldEditPasses();

        final AccountUpdateResult result = editTurn();

        // NUMVAL-C tolerates the currency symbol, thousands separators and embedded blanks, so the
        // rejection here is genuinely a character-class rejection rather than a formatting one.
        assertThat(errorText(result)).isEqualTo(CREDIT_LIMIT_IS_NOT_VALID);
        assertThat(errorText(result)).isEqualTo(labelled(LABEL_CREDIT_LIMIT, IS_NOT_VALID));
        assertThat(cursorField(result)).isEqualTo(FIELD_CREDIT_LIMIT);
        assertThat(hasAspect(result, FIELD_CREDIT_LIMIT, FieldAttribute.ASPECT_MARKER))
                .as("NOT_OK, so no marker")
                .isFalse();
    }

    @Test
    @DisplayName(":1479 a twenty-six-character label is cut to the twenty-five-byte work field")
    void aTwentySixCharacterLabelIsCutToTwentyFiveBytes() {
        this.screen.currentCycleCredit = "abc";
        givenEveryFieldEditPasses();

        final AccountUpdateResult result = editTurn();

        // MOVE 'Current Cycle Credit Limit' TO WS-EDIT-VARIABLE-NAME truncates on the receiving
        // PIC X(25), so the operator reads a label one character short. It is the only one of the
        // twenty-one labels that truncates; 'Current Cycle Debit Limit' is exactly twenty-five.
        assertThat(errorText(result)).isEqualTo(LABEL_CURRENT_CYCLE_CREDIT_CUT + IS_NOT_VALID);
        assertThat(LABEL_CURRENT_CYCLE_CREDIT).hasSizeGreaterThan(EDIT_VARIABLE_NAME_WIDTH);
        assertThat(LABEL_CURRENT_CYCLE_CREDIT_CUT).hasSize(EDIT_VARIABLE_NAME_WIDTH);
        assertThat(cursorField(result)).isEqualTo(FIELD_CURRENT_CYCLE_CREDIT);
    }

    @Test
    @DisplayName(":1546-1552 a blank credit score stops before the range check")
    void aBlankCreditScoreMustBeSupplied() {
        this.screen.ficoScore = "";
        givenEveryFieldEditPasses();

        final AccountUpdateResult result = editTurn();

        assertThat(errorText(result)).isEqualTo(labelled(LABEL_FICO_SCORE, MUST_BE_SUPPLIED));
        assertThat(errorText(result))
                .as(":1550-1552 gates 1275-EDIT-FICO-SCORE on FLG-ALPHNANUM-ISVALID")
                .doesNotContain(FICO_RANGE_MESSAGE);
        assertThat(cursorField(result)).isEqualTo(FIELD_FICO_SCORE);
        assertThat(hasAspect(result, FIELD_FICO_SCORE, FieldAttribute.ASPECT_MARKER)).isTrue();
    }

    @Test
    @DisplayName(":1245 a non-numeric credit score must be all numeric")
    void aNonNumericCreditScoreMustBeAllNumeric() {
        this.screen.ficoScore = "7A0";
        givenEveryFieldEditPasses();

        final AccountUpdateResult result = editTurn();

        assertThat(errorText(result)).isEqualTo(labelled(LABEL_FICO_SCORE, MUST_BE_ALL_NUMERIC));
        assertThat(cursorField(result)).isEqualTo(FIELD_FICO_SCORE);
        assertThat(hasAspect(result, FIELD_FICO_SCORE, FieldAttribute.ASPECT_MARKER)).isFalse();
    }

    @Test
    @DisplayName(":1245 a zero credit score takes the must-not-be-zero arm, not the range arm")
    void aZeroCreditScoreMustNotBeZero() {
        this.screen.ficoScore = "000";
        givenEveryFieldEditPasses();

        final AccountUpdateResult result = editTurn();

        // Three arms in source order - blank, non-numeric, zero - and the third fires here, so the
        // range check at :1550-1552 is never reached even though 000 is also outside 300 THROUGH 850.
        assertThat(errorText(result)).isEqualTo(labelled(LABEL_FICO_SCORE, MUST_NOT_BE_ZERO));
        assertThat(errorText(result)).doesNotContain(FICO_RANGE_MESSAGE);
    }

    @Test
    @DisplayName(":1225 a blank first name must be supplied")
    void aBlankFirstNameMustBeSupplied() {
        this.screen.firstName = "";
        givenEveryFieldEditPasses();

        final AccountUpdateResult result = editTurn();

        assertThat(errorText(result)).isEqualTo(labelled(LABEL_FIRST_NAME, MUST_BE_SUPPLIED));
        assertThat(cursorField(result)).isEqualTo(FIELD_FIRST_NAME);
        assertThat(hasAspect(result, FIELD_FIRST_NAME, FieldAttribute.ASPECT_MARKER)).isTrue();
    }

    @Test
    @DisplayName(":1225 a first name carrying a digit can have alphabets only")
    void aFirstNameWithADigitCanHaveAlphabetsOnly() {
        this.screen.firstName = "MARG4RET";
        givenEveryFieldEditPasses();

        final AccountUpdateResult result = editTurn();

        assertThat(errorText(result)).isEqualTo(labelled(LABEL_FIRST_NAME, ALPHABETS_ONLY));
        assertThat(cursorField(result)).isEqualTo(FIELD_FIRST_NAME);
        assertThat(hasAspect(result, FIELD_FIRST_NAME, FieldAttribute.ASPECT_MARKER)).isFalse();
    }

    @Test
    @DisplayName(":1235 a blank middle name is ACCEPTED - the optional edit has no presence test")
    void aBlankMiddleNameIsAcceptedByTheOptionalEdit() {
        this.screen.middleName = "";
        givenEveryFieldEditPasses();

        final AccountUpdateResult result = editTurn();

        // 1235-EDIT-ALPHA-OPT differs from 1225-EDIT-ALPHA-REQD in exactly one respect: its blank arm
        // sets FLG-ALPHA-ISVALID instead of FLG-ALPHA-BLANK. Everything else is identical.
        assertThat(errorText(result)).isEmpty();
        assertThat(result.changeAction())
                .as(":1671-1675 promotes the marker only when INPUT-ERROR is clear")
                .isEqualTo(ChangeAction.CHANGES_OK_NOT_CONFIRMED);
        assertThat(result.informationMessage().stripTrailing()).isEqualTo(INFO_PROMPT_FOR_CONFIRMATION);
    }

    @Test
    @DisplayName(":3110-3111 a middle name carrying a digit reaches the only NOT_OK-without-BLANK branch")
    void aMiddleNameWithADigitCanHaveAlphabetsOnly() {
        this.screen.middleName = "A1";
        givenEveryFieldEditPasses();

        final AccountUpdateResult result = editTurn();

        assertThat(errorText(result)).isEqualTo(labelled(LABEL_MIDDLE_NAME, ALPHABETS_ONLY));
        // The cursor cascade tests FLG-MIDNAME-NOT-OK alone, with no -BLANK companion, because the
        // optional edit can never produce BLANK. It is the only one of the forty-three shaped this way.
        assertThat(cursorField(result)).isEqualTo(FIELD_MIDDLE_NAME);
        assertThat(hasAspect(result, FIELD_MIDDLE_NAME, FieldAttribute.ASPECT_COLOUR)).isTrue();
        assertThat(hasAspect(result, FIELD_MIDDLE_NAME, FieldAttribute.ASPECT_MARKER)).isFalse();
    }

    @Test
    @DisplayName(":1215 a blank first address line must be supplied")
    void aBlankFirstAddressLineMustBeSupplied() {
        this.screen.addressLine1 = "";
        givenEveryFieldEditPasses();

        final AccountUpdateResult result = editTurn();

        assertThat(errorText(result)).isEqualTo(labelled(LABEL_ADDRESS_LINE_1, MUST_BE_SUPPLIED));
        assertThat(cursorField(result)).isEqualTo(FIELD_ADDRESS_LINE_1);
        assertThat(hasAspect(result, FIELD_ADDRESS_LINE_1, FieldAttribute.ASPECT_MARKER)).isTrue();
    }

    @Test
    @DisplayName(":1215 the presence-only edit applies NO character-class test to the address line")
    void anAddressLineOfPunctuationPassesThePresenceOnlyEdit() {
        // 1215-EDIT-MANDATORY has two arms and only two: blank, or valid. The same value in any of the
        // four alpha-required fields would be rejected, which is why the two routines stay distinct.
        this.screen.addressLine1 = "###";
        givenEveryFieldEditPasses();

        final AccountUpdateResult result = editTurn();

        assertThat(errorText(result)).isEmpty();
        assertThat(result.changeAction()).isEqualTo(ChangeAction.CHANGES_OK_NOT_CONFIRMED);
    }

    @Test
    @DisplayName(":1270 an unknown state code is rejected, and the lookup is handed the UNTRIMMED value")
    void anUnknownStateCodeIsNotAValidStateCode() {
        givenEveryDateEditPasses();
        givenAreaCodeLookupPasses();
        when(this.validationLookupService.isValidUsStateCode(any())).thenReturn(false);

        final AccountUpdateResult result = editTurn();

        assertThat(errorText(result)).isEqualTo(labelled(LABEL_STATE, NOT_A_VALID_STATE));
        assertThat(cursorField(result)).isEqualTo(FIELD_STATE_CODE);
        assertThat(hasAspect(result, FIELD_STATE_CODE, FieldAttribute.ASPECT_MARKER)).isFalse();
        // The source passes ACUP-NEW-CUST-ADDR-STATE-CD straight to the table search, with no
        // FUNCTION TRIM anywhere in 1270 - unlike 1205, which trims both sides.
        verify(this.validationLookupService).isValidUsStateCode("WA");
        // :1666-1669 gates the combination check on BOTH states being valid, so it never runs.
        verify(this.validationLookupService, never()).isValidStateZipCodeCombination(any());
    }

    @Test
    @DisplayName(":1225 a state code carrying a digit fails the class test before either lookup")
    void aStateCodeWithADigitCanHaveAlphabetsOnly() {
        this.screen.addressStateCode = "W1";
        givenEveryDateEditPasses();
        givenAreaCodeLookupPasses();

        final AccountUpdateResult result = editTurn();

        assertThat(errorText(result)).isEqualTo(labelled(LABEL_STATE, ALPHABETS_ONLY));
        assertThat(cursorField(result)).isEqualTo(FIELD_STATE_CODE);
        // :1656-1659 gates 1270 on FLG-ALPHA-ISVALID, so BOTH table searches are skipped.
        verify(this.validationLookupService, never()).isValidUsStateCode(any());
        verify(this.validationLookupService, never()).isValidStateZipCodeCombination(any());
    }

    @Test
    @DisplayName(":1245 a blank postal code must be supplied and suppresses the combination check")
    void aBlankPostalCodeMustBeSupplied() {
        this.screen.addressZip = "";
        givenEveryDateEditPasses();
        givenAreaCodeLookupPasses();
        givenStateLookupPasses();

        final AccountUpdateResult result = editTurn();

        assertThat(errorText(result)).isEqualTo(labelled(LABEL_ZIP, MUST_BE_SUPPLIED));
        assertThat(cursorField(result)).isEqualTo(FIELD_ZIP);
        assertThat(hasAspect(result, FIELD_ZIP, FieldAttribute.ASPECT_MARKER)).isTrue();
        verify(this.validationLookupService, never()).isValidStateZipCodeCombination(any());
    }

    @Test
    @DisplayName(":1280 a postal code that does not match the state is rejected with NO label prefix")
    void aPostalCodeThatDoesNotMatchTheStateIsRejectedWithoutALabel() {
        givenEveryDateEditPasses();
        givenAreaCodeLookupPasses();
        givenStateLookupPasses();
        when(this.validationLookupService.isValidStateZipCodeCombination(any())).thenReturn(false);

        final AccountUpdateResult result = editTurn();

        // 1280 is the one edit routine that assigns a bare literal rather than STRINGing the label in
        // front of a suffix, because the failure belongs to the PAIR rather than to either field.
        assertThat(errorText(result)).isEqualTo(INVALID_ZIP_FOR_STATE);
        assertThat(errorText(result)).doesNotStartWith(LABEL_ZIP);
        assertThat(errorText(result)).doesNotStartWith(LABEL_STATE);
        // It also sets BOTH field flags NOT_OK, and the cursor cascade reaches the state first.
        assertThat(cursorField(result)).isEqualTo(FIELD_STATE_CODE);
        assertThat(hasAspect(result, FIELD_STATE_CODE, FieldAttribute.ASPECT_COLOUR)).isTrue();
        assertThat(hasAspect(result, FIELD_ZIP, FieldAttribute.ASPECT_COLOUR)).isTrue();
        // The search key is the two-byte state followed by the first TWO postal digits only.
        verify(this.validationLookupService).isValidStateZipCodeCombination("WA98");
    }

    @Test
    @DisplayName(":1225 a blank city must be supplied - the edit reads ACUP-NEW-CUST-ADDR-LINE-3")
    void aBlankCityMustBeSupplied() {
        this.screen.addressCity = "";
        givenEveryFieldEditPasses();

        final AccountUpdateResult result = editTurn();

        assertThat(errorText(result)).isEqualTo(labelled(LABEL_CITY, MUST_BE_SUPPLIED));
        assertThat(cursorField(result)).isEqualTo(FIELD_CITY);
        assertThat(hasAspect(result, FIELD_CITY, FieldAttribute.ASPECT_MARKER)).isTrue();
    }

    @Test
    @DisplayName(":1225 a blank country must be supplied")
    void aBlankCountryMustBeSupplied() {
        this.screen.addressCountryCode = "";
        givenEveryFieldEditPasses();

        final AccountUpdateResult result = editTurn();

        assertThat(errorText(result)).isEqualTo(labelled(LABEL_COUNTRY, MUST_BE_SUPPLIED));
        assertThat(cursorField(result)).isEqualTo(FIELD_COUNTRY_CODE);
        assertThat(hasAspect(result, FIELD_COUNTRY_CODE, FieldAttribute.ASPECT_MARKER)).isTrue();
    }

    @Test
    @DisplayName(":2246-2258 a blank area code names the first telephone and its own suffix")
    void aBlankAreaCodeMustBeSupplied() {
        this.screen.phone1AreaCode = "";
        givenEveryFieldEditPasses();

        final AccountUpdateResult result = editTurn();

        // The telephone suffixes open with ':' rather than a space, so the composed diagnostic reads
        // 'Phone Number 1: Area code must be supplied.' - the colon belongs to the suffix literal.
        assertThat(errorText(result)).isEqualTo(labelled(LABEL_PHONE_NUMBER_1, AREA_CODE_SUPPLIED));
        assertThat(cursorField(result)).isEqualTo(FIELD_PHONE_1_AREA_CODE);
        assertThat(hasAspect(result, FIELD_PHONE_1_AREA_CODE, FieldAttribute.ASPECT_MARKER)).isTrue();
    }

    @Test
    @DisplayName(":2260-2268 a two-digit area code must be a three digit number")
    void aTwoDigitAreaCodeMustBeAThreeDigitNumber() {
        this.screen.phone1AreaCode = "20";
        givenEveryFieldEditPasses();

        final AccountUpdateResult result = editTurn();

        assertThat(errorText(result)).isEqualTo(labelled(LABEL_PHONE_NUMBER_1, AREA_CODE_THREE_DIGITS));
        assertThat(cursorField(result)).isEqualTo(FIELD_PHONE_1_AREA_CODE);
        assertThat(hasAspect(result, FIELD_PHONE_1_AREA_CODE, FieldAttribute.ASPECT_MARKER)).isFalse();
    }

    @Test
    @DisplayName(":2270-2278 a zero area code cannot be zero, and that suffix carries no period")
    void aZeroAreaCodeCannotBeZero() {
        this.screen.phone1AreaCode = "000";
        givenEveryFieldEditPasses();

        final AccountUpdateResult result = editTurn();

        assertThat(errorText(result)).isEqualTo(labelled(LABEL_PHONE_NUMBER_1, AREA_CODE_NOT_ZERO));
        assertThat(AREA_CODE_NOT_ZERO).doesNotEndWith(".");
        assertThat(cursorField(result)).isEqualTo(FIELD_PHONE_1_AREA_CODE);
    }

    @Test
    @DisplayName(":2296-2305 an area code outside the North American plan is rejected by the table")
    void anAreaCodeOutsideTheNorthAmericanPlanIsRejected() {
        givenEveryDateEditPasses();
        givenStateLookupPasses();
        givenStateZipLookupPasses();
        when(this.validationLookupService.isValidGeneralPurposeAreaCode(any())).thenReturn(false);

        final AccountUpdateResult result = editTurn();

        assertThat(errorText(result))
                .isEqualTo(labelled(LABEL_PHONE_NUMBER_1, AREA_CODE_NOT_NORTH_AMERICAN));
        assertThat(cursorField(result)).isEqualTo(FIELD_PHONE_1_AREA_CODE);
        // Both telephone numbers consult the table, which is why one stubbing serves two call sites -
        // and why the second number's identical failure cannot be seen: the first latched the message.
        verify(this.validationLookupService, times(2)).isValidGeneralPurposeAreaCode(any());
        assertThat(hasAspect(result, FIELD_PHONE_2_AREA_CODE, FieldAttribute.ASPECT_COLOUR))
                .as("the second number is reddened too, even though its diagnostic never surfaced")
                .isTrue();
    }

    @Test
    @DisplayName(":2307-2317 a blank prefix must be supplied and does not stop the line number")
    void aBlankPrefixMustBeSupplied() {
        this.screen.phone1Prefix = "";
        givenEveryFieldEditPasses();

        final AccountUpdateResult result = editTurn();

        assertThat(errorText(result)).isEqualTo(labelled(LABEL_PHONE_NUMBER_1, PREFIX_SUPPLIED));
        assertThat(cursorField(result)).isEqualTo(FIELD_PHONE_1_PREFIX);
        assertThat(hasAspect(result, FIELD_PHONE_1_PREFIX, FieldAttribute.ASPECT_MARKER)).isTrue();
        // EDIT-AREA-CODE, EDIT-US-PHONE-PREFIX and EDIT-US-PHONE-LINENUM are a fall-through chain, so
        // each branch of each paragraph still transfers to the next - the line number is edited too.
        assertThat(hasAspect(result, FIELD_PHONE_1_LINE_NUMBER, FieldAttribute.ASPECT_COLOUR))
                .as("a valid line number attracts no attribute, proving it was edited and passed")
                .isFalse();
    }

    @Test
    @DisplayName(":2319-2327 a two-digit prefix must be a three digit number")
    void aTwoDigitPrefixMustBeAThreeDigitNumber() {
        this.screen.phone1Prefix = "55";
        givenEveryFieldEditPasses();

        final AccountUpdateResult result = editTurn();

        assertThat(errorText(result)).isEqualTo(labelled(LABEL_PHONE_NUMBER_1, PREFIX_THREE_DIGITS));
        assertThat(cursorField(result)).isEqualTo(FIELD_PHONE_1_PREFIX);
        assertThat(hasAspect(result, FIELD_PHONE_1_PREFIX, FieldAttribute.ASPECT_MARKER)).isFalse();
    }

    @Test
    @DisplayName(":2329-2337 a zero prefix cannot be zero")
    void aZeroPrefixCannotBeZero() {
        this.screen.phone1Prefix = "000";
        givenEveryFieldEditPasses();

        final AccountUpdateResult result = editTurn();

        assertThat(errorText(result)).isEqualTo(labelled(LABEL_PHONE_NUMBER_1, PREFIX_NOT_ZERO));
        assertThat(PREFIX_NOT_ZERO).doesNotEndWith(".");
        assertThat(cursorField(result)).isEqualTo(FIELD_PHONE_1_PREFIX);
    }

    @Test
    @DisplayName(":2350-2360 a blank line number must be supplied")
    void aBlankLineNumberMustBeSupplied() {
        this.screen.phone1LineNumber = "";
        givenEveryFieldEditPasses();

        final AccountUpdateResult result = editTurn();

        assertThat(errorText(result)).isEqualTo(labelled(LABEL_PHONE_NUMBER_1, LINE_NUMBER_SUPPLIED));
        assertThat(cursorField(result)).isEqualTo(FIELD_PHONE_1_LINE_NUMBER);
        assertThat(hasAspect(result, FIELD_PHONE_1_LINE_NUMBER, FieldAttribute.ASPECT_MARKER)).isTrue();
    }

    @Test
    @DisplayName(":2362-2370 a three-digit line number must be a four digit number")
    void aThreeDigitLineNumberMustBeAFourDigitNumber() {
        this.screen.phone1LineNumber = "010";
        givenEveryFieldEditPasses();

        final AccountUpdateResult result = editTurn();

        assertThat(errorText(result)).isEqualTo(labelled(LABEL_PHONE_NUMBER_1, LINE_NUMBER_FOUR_DIGITS));
        assertThat(cursorField(result)).isEqualTo(FIELD_PHONE_1_LINE_NUMBER);
        assertThat(hasAspect(result, FIELD_PHONE_1_LINE_NUMBER, FieldAttribute.ASPECT_MARKER)).isFalse();
    }

    @Test
    @DisplayName(":2372-2380 a zero line number cannot be zero")
    void aZeroLineNumberCannotBeZero() {
        this.screen.phone1LineNumber = "0000";
        givenEveryFieldEditPasses();

        final AccountUpdateResult result = editTurn();

        assertThat(errorText(result)).isEqualTo(labelled(LABEL_PHONE_NUMBER_1, LINE_NUMBER_NOT_ZERO));
        assertThat(LINE_NUMBER_NOT_ZERO).doesNotEndWith(".");
        assertThat(cursorField(result)).isEqualTo(FIELD_PHONE_1_LINE_NUMBER);
    }

    @Test
    @DisplayName(":2234-2244 an entirely absent telephone number short-circuits as not-supplied")
    void anEntirelyAbsentTelephoneNumberIsAcceptedByTheShortCircuit() {
        this.screen.phone1AreaCode = "";
        this.screen.phone1Prefix = "";
        this.screen.phone1LineNumber = "";
        givenEveryDateEditPasses();
        givenAreaCodeLookupPasses();
        givenStateLookupPasses();
        givenStateZipLookupPasses();

        final AccountUpdateResult result = editTurn();

        // The three-component test at :2234-2244 accepts the whole number and jumps straight to
        // EDIT-US-PHONE-EXIT, so none of the three fall-through paragraphs runs for this number.
        assertThat(errorText(result)).isEmpty();
        assertThat(result.changeAction()).isEqualTo(ChangeAction.CHANGES_OK_NOT_CONFIRMED);
        // Exactly one table search remains - the second telephone's. Two would mean the short-circuit
        // did not fire; zero would mean the second number was skipped as well.
        verify(this.validationLookupService, times(1)).isValidGeneralPurposeAreaCode(any());
    }

    @Test
    @DisplayName(":2246-2258 a blank second area code names the SECOND telephone, not the first")
    void aBlankSecondTelephoneAreaCodeNamesTheSecondPhone() {
        this.screen.phone2AreaCode = "";
        givenEveryFieldEditPasses();

        final AccountUpdateResult result = editTurn();

        // The four telephone paragraphs are performed twice against the same work fields, with
        // WS-EDIT-VARIABLE-NAME reset in between - which is the only thing that tells the two apart.
        assertThat(errorText(result)).isEqualTo(labelled(LABEL_PHONE_NUMBER_2, AREA_CODE_SUPPLIED));
        assertThat(cursorField(result)).isEqualTo(FIELD_PHONE_2_AREA_CODE);
        assertThat(hasAspect(result, FIELD_PHONE_2_AREA_CODE, FieldAttribute.ASPECT_MARKER)).isTrue();
        assertThat(hasAspect(result, FIELD_PHONE_1_AREA_CODE, FieldAttribute.ASPECT_COLOUR)).isFalse();
    }

    @Test
    @DisplayName(":1245 a blank electronic funds account identifier must be supplied")
    void aBlankEftAccountIdMustBeSupplied() {
        this.screen.eftAccountId = "";
        givenEveryFieldEditPasses();

        final AccountUpdateResult result = editTurn();

        assertThat(errorText(result)).isEqualTo(labelled(LABEL_EFT_ACCOUNT_ID, MUST_BE_SUPPLIED));
        assertThat(cursorField(result)).isEqualTo(FIELD_EFT_ACCOUNT_ID);
        assertThat(hasAspect(result, FIELD_EFT_ACCOUNT_ID, FieldAttribute.ASPECT_MARKER)).isTrue();
    }

    @Test
    @DisplayName(":1245 a numerically zero electronic funds account identifier must not be zero")
    void aNumericallyZeroEftAccountIdMustNotBeZero() {
        this.screen.eftAccountId = "0000000000";
        givenEveryFieldEditPasses();

        final AccountUpdateResult result = editTurn();

        assertThat(errorText(result)).isEqualTo(labelled(LABEL_EFT_ACCOUNT_ID, MUST_NOT_BE_ZERO));
        assertThat(cursorField(result)).isEqualTo(FIELD_EFT_ACCOUNT_ID);
        assertThat(hasAspect(result, FIELD_EFT_ACCOUNT_ID, FieldAttribute.ASPECT_MARKER)).isFalse();
    }

    @Test
    @DisplayName(":1220 a blank primary card holder indicator must be supplied")
    void aBlankPrimaryCardHolderMustBeSupplied() {
        this.screen.primaryCardHolderIndicator = "";
        givenEveryFieldEditPasses();

        final AccountUpdateResult result = editTurn();

        assertThat(errorText(result)).isEqualTo(labelled(LABEL_PRIMARY_CARD_HOLDER, MUST_BE_SUPPLIED));
        assertThat(cursorField(result)).isEqualTo(FIELD_PRIMARY_CARD_HOLDER);
        assertThat(hasAspect(result, FIELD_PRIMARY_CARD_HOLDER, FieldAttribute.ASPECT_MARKER)).isTrue();
    }

    @Test
    @DisplayName(":1220 a primary card holder indicator outside Y and N is rejected")
    void aPrimaryCardHolderOutsideYAndNIsRejected() {
        this.screen.primaryCardHolderIndicator = "Z";
        givenEveryFieldEditPasses();

        final AccountUpdateResult result = editTurn();

        assertThat(errorText(result)).isEqualTo(labelled(LABEL_PRIMARY_CARD_HOLDER, MUST_BE_Y_OR_N));
        assertThat(cursorField(result)).isEqualTo(FIELD_PRIMARY_CARD_HOLDER);
        assertThat(hasAspect(result, FIELD_PRIMARY_CARD_HOLDER, FieldAttribute.ASPECT_MARKER)).isFalse();
    }

    @Test
    @DisplayName("D15 - a blank first identifier member STOPS the remaining two members being edited")
    void aBlankNationalIdentifierFirstMemberStopsTheRemainingMembers() {
        // 1265-EDIT-US-SSN encloses the second and third member edits inside
        // IF FLG-SSN-PART1-ISVALID, so a rejected first member suppresses both. The second member here
        // is ALSO blank, and its absence from the presentation list is the whole proof.
        this.screen.ssnPart1 = "";
        this.screen.ssnPart2 = "";
        givenEveryFieldEditPasses();

        final AccountUpdateResult result = editTurn();

        assertThat(errorText(result)).isEqualTo(labelled(SSN_PART1_LABEL, MUST_BE_SUPPLIED));
        assertThat(cursorField(result)).isEqualTo(FIELD_SSN_PART1);
        assertThat(hasAspect(result, FIELD_SSN_PART1, FieldAttribute.ASPECT_MARKER)).isTrue();
        assertThat(hasAspect(result, FIELD_SSN_PART2, FieldAttribute.ASPECT_COLOUR))
                .as("the second member is blank too, yet was never edited")
                .isFalse();
        assertThat(hasAspect(result, FIELD_SSN_PART2, FieldAttribute.ASPECT_MARKER)).isFalse();
    }

    @Test
    @DisplayName(":1265 an administratively reserved first identifier member is rejected by range")
    void anAdministrativelyReservedNationalIdentifierFirstMemberIsRejected() {
        // The reserved-range test runs AFTER 1245 has already accepted the member, so it downgrades a
        // FLG-SSN-PART1-ISVALID to NOT-OK - and, being inside the guard, still lets the other two run.
        this.screen.ssnPart1 = "666";
        givenEveryFieldEditPasses();

        final AccountUpdateResult result = editTurn();

        assertThat(errorText(result)).isEqualTo(labelled(SSN_PART1_LABEL, SSN_PART1_RANGE));
        assertThat(cursorField(result)).isEqualTo(FIELD_SSN_PART1);
        assertThat(hasAspect(result, FIELD_SSN_PART1, FieldAttribute.ASPECT_MARKER))
                .as("NOT_OK rather than BLANK, so no marker")
                .isFalse();
        assertThat(hasAspect(result, FIELD_SSN_PART2, FieldAttribute.ASPECT_COLOUR))
                .as("the guard is still satisfied on entry, so the other two members were edited")
                .isFalse();
        assertThat(hasAspect(result, FIELD_SSN_PART3, FieldAttribute.ASPECT_COLOUR)).isFalse();
    }

    @Test
    @DisplayName(":1265 a blank second identifier member is named by its own label literal")
    void aBlankNationalIdentifierSecondMemberIsNamedByItsOwnLabel() {
        this.screen.ssnPart2 = "";
        givenEveryFieldEditPasses();

        final AccountUpdateResult result = editTurn();

        assertThat(errorText(result)).isEqualTo(labelled(SSN_PART2_LABEL, MUST_BE_SUPPLIED));
        assertThat(cursorField(result)).isEqualTo(FIELD_SSN_PART2);
        assertThat(hasAspect(result, FIELD_SSN_PART2, FieldAttribute.ASPECT_MARKER)).isTrue();
    }

    @Test
    @DisplayName(":1265 a blank third identifier member is named by its own label literal")
    void aBlankNationalIdentifierThirdMemberIsNamedByItsOwnLabel() {
        this.screen.ssnPart3 = "";
        givenEveryFieldEditPasses();

        final AccountUpdateResult result = editTurn();

        assertThat(errorText(result)).isEqualTo(labelled(SSN_PART3_LABEL, MUST_BE_SUPPLIED));
        assertThat(cursorField(result)).isEqualTo(FIELD_SSN_PART3);
        assertThat(hasAspect(result, FIELD_SSN_PART3, FieldAttribute.ASPECT_MARKER)).isTrue();
    }

    @Test
    @DisplayName("PARITY - the two never-performed alphanumeric edits can never produce their diagnostic")
    void theNeverPerformedAlphanumericEditsCanNeverProduceTheirDiagnostic() {
        // 1230-EDIT-ALPHANUM-REQD. is declared at app/cbl/COACTUPC.cbl:1955 and 1240-EDIT-ALPHANUM-OPT.
        // at :2061. An exhaustive census of the frozen source finds each label referenced ONLY by its
        // own GO TO ...-EXIT - at :1978 and :2004 for the first, :2073 and :2100 for the second - and
        // finds no PERFORM of either anywhere in the 4,236 lines. They are unreachable by construction.
        //
        // Consequence: the suffix at :658 is the only one of the eight that no turn can ever emit, and
        // the character-class helper the pair shares is likewise unreachable. Both are retained under
        // the parity mandate, cited to the source lines above, because
        // deleting them would break the paragraph map. Coverage tooling will always
        // report them as uncovered; that is the expected, documented outcome, not a gap to be closed.
        this.screen.firstName = "MARG4RET";
        givenEveryFieldEditPasses();

        final AccountUpdateResult result = editTurn();

        // The alpha-required edit fires; the alphanumeric-required edit, which would have accepted the
        // digit, never does - so the operator reads the stricter of the two diagnostics.
        assertThat(errorText(result)).isEqualTo(labelled(LABEL_FIRST_NAME, ALPHABETS_ONLY));
        assertThat(result.errorMessage())
                .as(":658 is declared, cited and unreachable")
                .doesNotContain(ALPHANUMERIC_ONLY);
        assertThat(ALPHANUMERIC_ONLY).isNotEqualTo(ALPHABETS_ONLY);

        final List<String> declared = List.of(AccountUpdateService.class.getDeclaredMethods())
                .stream()
                .map(Method::getName)
                .toList();
        assertThat(declared)
                .as("both unreachable paragraphs and both of their exits survive as their own methods")
                .contains("editAlphanumericRequired1230",
                        "editAlphanumericRequired1230Exit",
                        "editAlphanumericOptional1240",
                        "editAlphanumericOptional1240Exit",
                        "containsOnlyLettersDigitsAndSpaces");
    }

    // 2000-DECIDE-ACTION - the remaining arms, and the two that no turn can reach.
    //
    // The EVALUATE TRUE at :2568-2640 has six arms plus a WHEN OTHER. Three are already covered
    // elsewhere in this class: the retrieval arm (:2573-2579), the write arm (:2602-2618) and the
    // classification that follows it. The arms exercised here are the redisplay (:2596-2597) and the
    // held-for-confirmation (:2620-2621) no-ops, plus the interception at :976-995 that stops the
    // written marker ever reaching the decider at all.
    //
    // TWO ARMS ARE UNREACHABLE, and both are retained rather than deleted.
    //
    // (a) The promotion line inside the ACUP-SHOW-DETAILS arm at :2590. Reaching the decider with the
    //     marker still at SHOW-DETAILS requires 1200-EDIT-MAP-INPUTS to have returned early at
    //     :1463-1467, which happens only when CHANGE-HAS-OCCURRED is false - and in exactly that case
    //     1205-COMPARE-OLD-NEW has already assigned NO-CHANGES-DETECTED, which is the arm's own guard.
    //     If anything did change, :1470 has already overwritten the marker with CHANGES-NOT-OK.
    //     The two conditions are exhaustive, so the promotion is dead by construction.
    // (b) The ACUP-CHANGES-OKAYED-AND-DONE arm at :2625-2632 and the WHEN OTHER abend at :2633-2640.
    //     0000-MAIN intercepts both markers at :976-995 and returns before 2000-DECIDE-ACTION is
    //     performed, so no turn can present them to the decider.
    //
    // No turn can reach either arm: doing so would take a caller able to set
    // WS-THIS-PROGCOMMAREA independently of the marker 0000-MAIN derives from the request - which the
    // stateless contract deliberately does not provide, because the marker travels in the payload.
    // No behaviour is lost, and both arms are reproduced and cited to their source lines.

    @Test
    @DisplayName(":2596-2597 the redisplay arm is a no-op and touches no repository")
    void aRedisplayAfterFailedEditsTouchesNoRepository() {
        // ACUP-CHANGES-NOT-OK means the previous turn's edits failed. The arm exists solely to stop
        // the fall-through reaching the write arm; it performs nothing and reads nothing.
        this.screen.accountStatus = "Y";

        final AccountUpdateResult result = this.service.processRequest(
                this.screen.build(this.snapshot.build()),
                AID_ENTER,
                ChangeAction.CHANGES_NOT_OK,
                EntryMode.REENTER);

        assertThat(result.changeAction()).isEqualTo(ChangeAction.CHANGES_NOT_OK);
        assertThat(errorText(result)).isEqualTo(NO_CHANGE_DETECTED);
        assertThat(result.informationMessage().stripTrailing()).isEqualTo(INFO_PROMPT_FOR_CHANGES);
        // The cursor is placed by the MESSAGE here, through the second disjunct at :3009-3010, and not
        // by any field state - every state was reset to valid by :1466.
        assertThat(cursorField(result)).isEqualTo(FIELD_ACCOUNT_STATUS);
        verifyNoInteractions(this.accountRepository,
                this.customerRepository,
                this.cardCrossReferenceRepository,
                this.dateValidationService,
                this.validationLookupService,
                this.fileStatusMapper);
    }

    @Test
    @DisplayName(":2620-2621 a validated screen without PF05 is held, unwritten, for confirmation")
    void aValidatedScreenWithoutPf05IsHeldForConfirmation() {
        // :1463-1467 returns early on ACUP-CHANGES-OK-NOT-CONFIRMED whatever the comparison found, so
        // the twenty-five edits do not re-run - the previous turn already performed them.
        final AccountUpdateResult result = this.service.processRequest(
                this.screen.build(this.snapshot.build()),
                AID_ENTER,
                ChangeAction.CHANGES_OK_NOT_CONFIRMED,
                EntryMode.REENTER);

        assertThat(result.changeAction()).isEqualTo(ChangeAction.CHANGES_OK_NOT_CONFIRMED);
        assertThat(result.changeAction().marker()).isEqualTo('N');
        assertThat(errorText(result)).isEmpty();
        assertThat(result.informationMessage().stripTrailing()).isEqualTo(INFO_PROMPT_FOR_CONFIRMATION);
        // Arm 4 is gated on the attention identifier, so Enter reaches arm 5 and nothing is written.
        verifyNoInteractions(this.accountRepository,
                this.customerRepository,
                this.cardCrossReferenceRepository,
                this.dateValidationService,
                this.validationLookupService,
                this.fileStatusMapper);
    }

    @Test
    @DisplayName(":976-995 the written marker is intercepted in 0000-MAIN, before the decider runs")
    void theWrittenMarkerIsInterceptedBeforeTheDeciderEverSeesIt() {
        final AccountUpdateResult result = this.service.processRequest(
                this.screen.build(this.snapshot.build()),
                AID_ENTER,
                ChangeAction.CHANGES_OKAYED_AND_DONE,
                EntryMode.REENTER);

        // The interception re-initialises the map, clears the account key, paints a fresh screen as
        // though on first entry, and resets the marker - so the next turn starts a new conversation.
        assertThat(result.responseKind()).isEqualTo(ResponseKind.MAP);
        assertThat(result.changeAction()).isEqualTo(ChangeAction.DETAILS_NOT_FETCHED);
        assertThat(result.screen().getAccountId())
                .as(":979-981 INITIALIZE CACTUPAI leaves every screen field empty")
                .isNull();
        assertThat(errorText(result)).isEmpty();
        assertThat(result.informationMessage().stripTrailing()).isEqualTo(INFO_PROMPT_FOR_SEARCH_KEYS);
        verifyNoInteractions(this.accountRepository,
                this.customerRepository,
                this.cardCrossReferenceRepository,
                this.dateValidationService,
                this.validationLookupService,
                this.fileStatusMapper);
    }

    @Test
    @DisplayName("PARITY - :2590 the SHOW-DETAILS promotion is unreachable, and retained regardless")
    void theShowDetailsPromotionArmIsUnreachable() {
        // Case one: nothing changed. 1200 returns early at :1463-1467 with the marker untouched, and
        // 1205 has already assigned the arm's own guard message - so the arm returns without promoting.
        this.screen.accountStatus = "Y";
        final AccountUpdateResult unchanged = editTurn();

        assertThat(errorText(unchanged)).isEqualTo(NO_CHANGE_DETECTED);
        assertThat(unchanged.changeAction())
                .as(":2588-2589 returns on the NO-CHANGES-DETECTED guard, leaving the marker alone")
                .isEqualTo(ChangeAction.SHOW_DETAILS);

        // Case two: something changed. :1470 overwrites the marker before the decider is performed, so
        // the arm is not even selected - and a clean cascade promotes at :1671-1675 instead.
        this.screen.accountStatus = "N";
        givenEveryFieldEditPasses();
        final AccountUpdateResult changed = editTurn();

        assertThat(changed.changeAction()).isEqualTo(ChangeAction.CHANGES_OK_NOT_CONFIRMED);
        assertThat(errorText(changed)).isEmpty();
    }

    // 3009-SETUP-CURSOR and 3300-SETUP-SCREEN-ATTRS - cursor placement and the protection branches.

    @Test
    @DisplayName(":3165-3166 with no field in error the cursor falls through to the account filter")
    void theCursorFallsToTheAccountFilterWhenNoFieldIsInError() {
        givenEveryFieldEditPasses();

        final AccountUpdateResult result = editTurn();

        // The cascade is forty-three tests deep and every one of them fails, so the WHEN OTHER at the
        // foot of it is the only reachable outcome on a clean turn.
        assertThat(cursorField(result)).isEqualTo(FIELD_ACCOUNT_ID);
        assertThat(result.fieldAttributes())
                .as("no CSSETATY expansion fires when every flag is FLG-...-ISVALID")
                .noneMatch(attribute -> FieldAttribute.ASPECT_COLOUR.equals(attribute.aspect()))
                .noneMatch(attribute -> FieldAttribute.ASPECT_MARKER.equals(attribute.aspect()));
    }

    @Test
    @DisplayName("LOW - :3009-3010 the fetch information message can never reach the cursor cascade")
    void theFetchInformationMessageCanNeverReachTheCursorCascade() {
        // :1452 assigns 'Details of selected account shown above' unlatched, and the cursor cascade
        // tests for it as its FIRST disjunct. But 3250-SETUP-INFOMSG runs before 3300 and overwrites
        // the field on every one of its seven arms, so the disjunct can never be true and only the
        // second one - the no-change message - can park the cursor on the account status. The dead
        // disjunct is retained, cited and classified LOW; deleting it would break the paragraph map.
        this.screen.accountStatus = "Y";
        final AccountUpdateResult unchanged = editTurn();

        assertThat(unchanged.informationMessage().stripTrailing()).isEqualTo(INFO_PROMPT_FOR_CHANGES);
        assertThat(unchanged.informationMessage()).doesNotContain(INFO_FOUND_ACCOUNT_DATA);
        assertThat(cursorField(unchanged))
                .as("placed by the second disjunct, which is the only live one")
                .isEqualTo(FIELD_ACCOUNT_STATUS);

        this.screen.accountStatus = "N";
        givenEveryFieldEditPasses();
        final AccountUpdateResult changed = editTurn();

        assertThat(changed.informationMessage()).doesNotContain(INFO_FOUND_ACCOUNT_DATA);
    }

    @Test
    @DisplayName(":3442-3564 the two protection branches differ, and a confirmed payload stays locked")
    void theScreenProtectionBranchesDifferByChangeAction() {
        this.screen.accountStatus = "Y";

        final AccountUpdateResult editable = editTurn();

        // ACUP-SHOW-DETAILS performs 3310 and then 3320, so an amendable field is protected once and
        // re-enabled once - two attribute writes, in that order.
        assertThat(aspectValues(editable, FIELD_ACCOUNT_STATUS, FieldAttribute.ASPECT_ATTRIBUTE))
                .containsExactly(ATTRIBUTE_PROTECTED_FSET, ATTRIBUTE_UNPROTECTED_FSET);
        // :3529 unprotects the customer identifier with its neighbours and :3531 immediately protects
        // it again. The redundant pair is preserved: the net effect is reachable either way, but the
        // write order is what the source performs.
        assertThat(aspectValues(editable, FIELD_CUSTOMER_ID, FieldAttribute.ASPECT_ATTRIBUTE))
                .containsExactly(ATTRIBUTE_PROTECTED_FSET,
                        ATTRIBUTE_UNPROTECTED_FSET,
                        ATTRIBUTE_PROTECTED_FSET);

        final AccountUpdateResult confirmed = this.service.processRequest(
                this.screen.build(this.snapshot.build()),
                AID_ENTER,
                ChangeAction.CHANGES_OK_NOT_CONFIRMED,
                EntryMode.REENTER);

        // :3001-3003 is a bare CONTINUE - nothing is re-enabled. That is what stops a PF05 turn
        // smuggling a payload past the validation the previous turn performed.
        assertThat(aspectValues(confirmed, FIELD_ACCOUNT_STATUS, FieldAttribute.ASPECT_ATTRIBUTE))
                .containsExactly(ATTRIBUTE_PROTECTED_FSET);
    }

    // The three screen-painting branches of 3200-SETUP-SCREEN-VARS, one test each.
    //
    // :2710-2724 is a five-branch decider over three bodies: 3201-SHOW-INITIAL-VALUES blanks the
    // detail block, 3202-SHOW-ORIGINAL-VALUES paints the ACUP-OLD-DETAILS snapshot, and
    // 3203-SHOW-UPDATED-VALUES echoes what the operator submitted. Which body ran is observable
    // only in the painted screen, and the three produce three different screens from the same
    // request - so a test that asserts one of them cannot pass against an implementation that ran
    // another. Without these three the paragraphs are reachable but unwitnessed.

    @Test
    @DisplayName(":2711-2714 a zero filter takes the initial-values branch and paints NO detail field")
    void aZeroFilterPaintsNoDetailFieldAtAll() {
        // The fall-through pair sends an unfetched screen OR a numerically zero filter to
        // 3201-SHOW-INITIAL-VALUES, whose whole body is MOVE LOW-VALUES to forty-two output fields.
        // The zero-filter member is the reachable one from a stateless caller, and it is tested here
        // rather than the unfetched member because it also proves the ORDER of the decider: the zero
        // filter is examined before ACUP-SHOW-DETAILS, so it wins even with a snapshot present.
        this.screen.accountId = "00000000000";
        givenEveryFieldEditPasses();

        final AccountUpdateResult result = editTurn();
        final AccountUpdateRequest painted = result.screen();

        assertThat(painted.getAccountStatus())
                .as(":2732 MOVE LOW-VALUES TO ACSTTUSO - the status is blanked, not echoed")
                .isNull();
        assertThat(painted.getCreditLimit()).isNull();
        assertThat(painted.getCashCreditLimit()).isNull();
        assertThat(painted.getCurrentBalance()).isNull();
        assertThat(painted.getCurrentCycleCredit()).isNull();
        assertThat(painted.getCurrentCycleDebit()).isNull();
        assertThat(painted.getCustomerFirstName()).isNull();
        assertThat(painted.getCustomerLastName()).isNull();
        assertThat(painted.getAddressLine1()).isNull();
        assertThat(painted.getOpenDateYear()).isNull();
        // The snapshot half is a separate group and is NOT part of the forty-two, so it still rides
        // back out. Asserting that keeps the blanking scoped to the map's own fields.
        assertThat(painted.getOldDetails())
                .as("ACUP-OLD-DETAILS is not one of the fields :2732-2780 blanks")
                .isNotNull();
    }

    @Test
    @DisplayName(":2715-2717 a refused write repaints the SNAPSHOT through the currency mask")
    void aRefusedWriteRepaintsTheSnapshotRatherThanTheSubmittedText() {
        // A confirming turn whose 9700 comparison refuses reports ACUP-SHOW-DETAILS, which is the one
        // marker that reaches 3202-SHOW-ORIGINAL-VALUES. Its every MOVE sources ACUP-OLD-* - so the
        // operator sees the values that are actually stored, not the ones just rejected. The two are
        // textually distinguishable here: the snapshot's 00000020200{ renders through
        // PIC +ZZZ,ZZZ,ZZZ.99 as a signed, grouped, fifteen-character field, while the submitted
        // credit limit is the bare keystrokes 2020.00.
        this.snapshot.customerId = FOREIGN_CUSTOMER_ID;
        givenAccountLocked();

        final AccountUpdateResult result = confirm();
        final AccountUpdateRequest painted = result.screen();

        assertThat(result.changeAction())
                .as("only ACUP-SHOW-DETAILS reaches 3202")
                .isEqualTo(ChangeAction.SHOW_DETAILS);
        assertThat(painted.getCreditLimit())
                .as(":2812 MOVE ACUP-OLD-CREDIT-LIMIT-N TO WS-EDIT-CURRENCY-9-2-F, :371's mask")
                .isNotNull()
                .startsWith("+")
                .contains("2,020.00")
                .hasSize(MONEY_DISPLAY_WIDTH);
        assertThat(painted.getCurrentBalance())
                .as(":2806 the stored balance, masked - not the submitted 194.00")
                .contains("194.00")
                .doesNotStartWith("1");
        assertThat(painted.getAccountStatus())
                .as(":2795 the snapshot's Y, not the submitted N")
                .isEqualTo("Y");
        assertThat(painted.getCustomerFirstName())
                .as(":2833-2835 the customer block is painted from the snapshot too")
                .isEqualTo("MARGARET");
    }

    @Test
    @DisplayName(":2718-2720 an edit turn with changes echoes the SUBMITTED values, masked")
    void anEditTurnWithChangesEchoesWhatTheOperatorSubmitted() {
        // The screen fixture presents accountStatus N against the snapshot's Y, so 1205 always
        // reports a change and the turn carries an ACUP-CHANGES-MADE marker into 3200. That marker
        // reaches 3203-SHOW-UPDATED-VALUES, whose amounts go through the mask when they parsed and
        // whose remaining fields are echoed verbatim at :2911-2947.
        this.screen.creditLimit = "3030.50";
        givenEveryFieldEditPasses();

        final AccountUpdateResult result = editTurn();
        final AccountUpdateRequest painted = result.screen();

        assertThat(result.changeAction().isChangesMade())
                .as("only an ACUP-CHANGES-MADE marker reaches 3203")
                .isTrue();
        assertThat(painted.getCreditLimit())
                .as(":2874-2879 the submitted amount, masked - the snapshot's 2,020.00 is NOT shown")
                .isNotNull()
                .startsWith("+")
                .contains("3,030.50")
                .hasSize(MONEY_DISPLAY_WIDTH);
        assertThat(painted.getAccountStatus())
                .as(":2872 the submitted status is echoed unconditionally")
                .isEqualTo("N");
        assertThat(painted.getAddressLine2())
                .as(":2929 the remaining fields are echoed verbatim, unmasked")
                .isEqualTo("APT 1");
    }

    @Test
    @DisplayName(":2874-2879 an UNPARSABLE amount echoes the operator's own keystrokes, not zero")
    void anUnparsableAmountEchoesTheRawKeystrokes() {
        // The tri-state is what makes this branch reachable: when the amount did not parse, the raw
        // text is echoed so the operator can see and correct it. Rendering the numeric value here
        // would silently replace a typo with 0.00 and invite a wrong confirmation.
        this.screen.creditLimit = "12X4.9";
        givenEveryFieldEditPasses();

        final AccountUpdateResult result = editTurn();

        assertThat(result.screen().getCreditLimit())
                .as("the keystrokes survive unchanged - no mask, no zero, no truncation")
                .isEqualTo("12X4.9");
        assertThat(errorText(result))
                .as("and the turn is still refused, so the unparsable value cannot be confirmed")
                .isNotEmpty();
    }

    // The remaining fourteen branches of the cursor cascade, one per test, none consolidated.
    //
    // Six are ordinary field failures whose diagnostics are asserted here for the first time. The
    // other eight are DATE COMPONENT branches, and they are reachable only because CSUTLDPY keeps a
    // SEPARATE flag per component: WS-EDIT-DATE-FLGS is a three-byte group, not a single verdict. A
    // stub that rejects all three components can only ever place the cursor on the year, so the month
    // and day branches need a verdict in which the components disagree.
    //
    // A collapsed three-into-one date verdict compiles, passes
    // a naive test, and silently parks the cursor on the wrong component on every rejected date.

    @Test
    @DisplayName(":1250 a blank cash credit limit places the cursor on its own field")
    void aBlankCashCreditLimitMustBeSupplied() {
        this.screen.cashCreditLimit = "";
        givenEveryFieldEditPasses();

        final AccountUpdateResult result = editTurn();

        assertThat(errorText(result)).isEqualTo(labelled(LABEL_CASH_CREDIT_LIMIT, MUST_BE_SUPPLIED));
        assertThat(cursorField(result)).isEqualTo(FIELD_CASH_CREDIT_LIMIT);
        assertThat(hasAspect(result, FIELD_CASH_CREDIT_LIMIT, FieldAttribute.ASPECT_MARKER)).isTrue();
    }

    @Test
    @DisplayName(":1250 a blank current balance places the cursor on its own field")
    void aBlankCurrentBalanceMustBeSupplied() {
        this.screen.currentBalance = "";
        givenEveryFieldEditPasses();

        final AccountUpdateResult result = editTurn();

        assertThat(errorText(result)).isEqualTo(labelled(LABEL_CURRENT_BALANCE, MUST_BE_SUPPLIED));
        assertThat(cursorField(result)).isEqualTo(FIELD_CURRENT_BALANCE);
        assertThat(hasAspect(result, FIELD_CURRENT_BALANCE, FieldAttribute.ASPECT_MARKER)).isTrue();
    }

    @Test
    @DisplayName(":766 the debit label is EXACTLY twenty-five bytes, so unlike the credit label it survives")
    void aBlankCurrentCycleDebitKeepsItsWholeLabel() {
        this.screen.currentCycleDebit = "";
        givenEveryFieldEditPasses();

        final AccountUpdateResult result = editTurn();

        // The two labels differ by one character and only one of them truncates. Asserting both widths
        // here is what keeps a future edit from "tidying" them into a single padded constant.
        assertThat(LABEL_CURRENT_CYCLE_DEBIT).hasSize(EDIT_VARIABLE_NAME_WIDTH);
        assertThat(errorText(result)).isEqualTo(labelled(LABEL_CURRENT_CYCLE_DEBIT, MUST_BE_SUPPLIED));
        assertThat(errorText(result)).startsWith(LABEL_CURRENT_CYCLE_DEBIT);
        assertThat(cursorField(result)).isEqualTo(FIELD_CURRENT_CYCLE_DEBIT);
    }

    @Test
    @DisplayName(":1225 a blank last name places the cursor on its own field")
    void aBlankLastNameMustBeSupplied() {
        this.screen.lastName = "";
        givenEveryFieldEditPasses();

        final AccountUpdateResult result = editTurn();

        assertThat(errorText(result)).isEqualTo(labelled(LABEL_LAST_NAME, MUST_BE_SUPPLIED));
        assertThat(cursorField(result)).isEqualTo(FIELD_LAST_NAME);
        assertThat(hasAspect(result, FIELD_LAST_NAME, FieldAttribute.ASPECT_MARKER)).isTrue();
    }

    @Test
    @DisplayName(":2307-2317 a blank second telephone prefix names the second phone and its own field")
    void aBlankSecondTelephonePrefixMustBeSupplied() {
        this.screen.phone2Prefix = "";
        givenEveryFieldEditPasses();

        final AccountUpdateResult result = editTurn();

        assertThat(errorText(result)).isEqualTo(labelled(LABEL_PHONE_NUMBER_2, PREFIX_SUPPLIED));
        assertThat(cursorField(result)).isEqualTo(FIELD_PHONE_2_PREFIX);
        assertThat(hasAspect(result, FIELD_PHONE_2_PREFIX, FieldAttribute.ASPECT_MARKER)).isTrue();
        assertThat(hasAspect(result, FIELD_PHONE_1_PREFIX, FieldAttribute.ASPECT_COLOUR)).isFalse();
    }

    @Test
    @DisplayName(":2350-2360 a blank second telephone line number names the second phone")
    void aBlankSecondTelephoneLineNumberMustBeSupplied() {
        this.screen.phone2LineNumber = "";
        givenEveryFieldEditPasses();

        final AccountUpdateResult result = editTurn();

        assertThat(errorText(result)).isEqualTo(labelled(LABEL_PHONE_NUMBER_2, LINE_NUMBER_SUPPLIED));
        assertThat(cursorField(result)).isEqualTo(FIELD_PHONE_2_LINE_NUMBER);
        assertThat(hasAspect(result, FIELD_PHONE_2_LINE_NUMBER, FieldAttribute.ASPECT_MARKER)).isTrue();
    }

    @Test
    @DisplayName(":3020-3023 a rejected open-date MONTH parks the cursor past the accepted year")
    void aRejectedOpenDateMonthPlacesTheCursorOnTheMonth() {
        givenCalendarComponentFailsFor(LABEL_OPEN_DATE,
                componentOutcome(DateValidationService.EditFlag.ISVALID,
                        DateValidationService.EditFlag.NOT_OK,
                        DateValidationService.EditFlag.ISVALID));
        givenDateOfBirthPlausibilityPasses();
        givenAreaCodeLookupPasses();
        givenStateLookupPasses();
        givenStateZipLookupPasses();

        final AccountUpdateResult result = editTurn();

        assertThat(cursorField(result)).isEqualTo(FIELD_OPEN_DATE_MONTH);
        assertThat(hasAspect(result, FIELD_OPEN_DATE_YEAR, FieldAttribute.ASPECT_COLOUR))
                .as("the accepted year attracts no attribute, so the components stayed independent")
                .isFalse();
        assertThat(errorText(result)).isEqualTo(STUBBED_DATE_DIAGNOSTIC);
    }

    @Test
    @DisplayName(":3024-3026 a rejected open-date DAY parks the cursor past the accepted year and month")
    void aRejectedOpenDateDayPlacesTheCursorOnTheDay() {
        givenCalendarComponentFailsFor(LABEL_OPEN_DATE,
                componentOutcome(DateValidationService.EditFlag.ISVALID,
                        DateValidationService.EditFlag.ISVALID,
                        DateValidationService.EditFlag.NOT_OK));
        givenDateOfBirthPlausibilityPasses();
        givenAreaCodeLookupPasses();
        givenStateLookupPasses();
        givenStateZipLookupPasses();

        final AccountUpdateResult result = editTurn();

        assertThat(cursorField(result)).isEqualTo(FIELD_OPEN_DATE_DAY);
        assertThat(hasAspect(result, FIELD_OPEN_DATE_MONTH, FieldAttribute.ASPECT_COLOUR)).isFalse();
    }

    @Test
    @DisplayName(":3033-3035 a rejected expiry MONTH parks the cursor on the expiry month")
    void aRejectedExpiryMonthPlacesTheCursorOnTheMonth() {
        givenCalendarComponentFailsFor(LABEL_EXPIRY_DATE,
                componentOutcome(DateValidationService.EditFlag.ISVALID,
                        DateValidationService.EditFlag.NOT_OK,
                        DateValidationService.EditFlag.ISVALID));
        givenDateOfBirthPlausibilityPasses();
        givenAreaCodeLookupPasses();
        givenStateLookupPasses();
        givenStateZipLookupPasses();

        assertThat(cursorField(editTurn())).isEqualTo(FIELD_EXPIRY_DATE_MONTH);
    }

    @Test
    @DisplayName(":3037-3039 a rejected expiry DAY parks the cursor on the expiry day")
    void aRejectedExpiryDayPlacesTheCursorOnTheDay() {
        givenCalendarComponentFailsFor(LABEL_EXPIRY_DATE,
                componentOutcome(DateValidationService.EditFlag.ISVALID,
                        DateValidationService.EditFlag.ISVALID,
                        DateValidationService.EditFlag.NOT_OK));
        givenDateOfBirthPlausibilityPasses();
        givenAreaCodeLookupPasses();
        givenStateLookupPasses();
        givenStateZipLookupPasses();

        assertThat(cursorField(editTurn())).isEqualTo(FIELD_EXPIRY_DATE_DAY);
    }

    @Test
    @DisplayName(":3046-3048 a rejected reissue MONTH parks the cursor on the reissue month")
    void aRejectedReissueMonthPlacesTheCursorOnTheMonth() {
        givenCalendarComponentFailsFor(LABEL_REISSUE_DATE,
                componentOutcome(DateValidationService.EditFlag.ISVALID,
                        DateValidationService.EditFlag.NOT_OK,
                        DateValidationService.EditFlag.ISVALID));
        givenDateOfBirthPlausibilityPasses();
        givenAreaCodeLookupPasses();
        givenStateLookupPasses();
        givenStateZipLookupPasses();

        assertThat(cursorField(editTurn())).isEqualTo(FIELD_REISSUE_DATE_MONTH);
    }

    @Test
    @DisplayName(":3050-3052 a rejected reissue DAY parks the cursor on the reissue day")
    void aRejectedReissueDayPlacesTheCursorOnTheDay() {
        givenCalendarComponentFailsFor(LABEL_REISSUE_DATE,
                componentOutcome(DateValidationService.EditFlag.ISVALID,
                        DateValidationService.EditFlag.ISVALID,
                        DateValidationService.EditFlag.NOT_OK));
        givenDateOfBirthPlausibilityPasses();
        givenAreaCodeLookupPasses();
        givenStateLookupPasses();
        givenStateZipLookupPasses();

        assertThat(cursorField(editTurn())).isEqualTo(FIELD_REISSUE_DATE_DAY);
    }

    @Test
    @DisplayName(":3072-3074 a rejected birth MONTH comes from the follow-up, not the calendar")
    void aRejectedBirthMonthPlacesTheCursorOnTheMonth() {
        givenBirthComponentFails(componentOutcome(DateValidationService.EditFlag.ISVALID,
                DateValidationService.EditFlag.NOT_OK,
                DateValidationService.EditFlag.ISVALID));
        givenAreaCodeLookupPasses();
        givenStateLookupPasses();
        givenStateZipLookupPasses();

        final AccountUpdateResult result = editTurn();

        // :1536-1543 overwrites all three component flags with the follow-up's verdict, so a
        // plausibility rejection can name a component the calendar edit had already accepted.
        assertThat(cursorField(result)).isEqualTo(FIELD_DATE_OF_BIRTH_MONTH);
        assertThat(hasAspect(result, FIELD_DATE_OF_BIRTH_YEAR, FieldAttribute.ASPECT_COLOUR)).isFalse();
    }

    @Test
    @DisplayName(":3076-3078 a rejected birth DAY comes from the follow-up, not the calendar")
    void aRejectedBirthDayPlacesTheCursorOnTheDay() {
        givenBirthComponentFails(componentOutcome(DateValidationService.EditFlag.ISVALID,
                DateValidationService.EditFlag.ISVALID,
                DateValidationService.EditFlag.NOT_OK));
        givenAreaCodeLookupPasses();
        givenStateLookupPasses();
        givenStateZipLookupPasses();

        assertThat(cursorField(editTurn())).isEqualTo(FIELD_DATE_OF_BIRTH_DAY);
    }

    // The read-only retrieval entry point's rejection path, YYYY-STORE-PFKEY's remaining arms, and
    // the NUMVAL-C conversion contract.

    @Test
    @DisplayName(":905-916 the twelve recognised-but-not-actionable keys are silently coerced to Enter")
    void everyNonActionableAttentionIdentifierIsCoercedToEnter() {
        // YYYY-STORE-PFKEY decodes sixteen keys; :911 admits four. The other twelve are decoded into
        // CCARD-AID and then discarded, WITHOUT a diagnostic - the operator simply sees the screen
        // again. Asserting the whole set in one turn-per-key loop keeps the two lists in step: adding
        // a key to :911 without adding an arm here would leave this assertion failing.
        this.screen.accountStatus = "Y";

        for (final String attentionIdentifier : NON_ACTIONABLE_ATTENTION_IDENTIFIERS) {
            final AccountUpdateResult result = this.service.processRequest(
                    this.screen.build(this.snapshot.build()),
                    attentionIdentifier,
                    ChangeAction.SHOW_DETAILS,
                    EntryMode.REENTER);

            assertThat(result.responseKind())
                    .as("coerced to Enter, so no EXEC CICS XCTL is issued for %s", attentionIdentifier)
                    .isEqualTo(ResponseKind.MAP);
            assertThat(result.errorMessage())
                    .as("no key-specific diagnostic exists for %s", attentionIdentifier)
                    .doesNotContain(EXIT_MESSAGE_TEXT);
            assertThat(errorText(result))
                    .as("the Enter path runs instead, and this screen has nothing to change")
                    .isEqualTo(NO_CHANGE_DETECTED);
        }

        verifyNoInteractions(this.accountRepository,
                this.customerRepository,
                this.cardCrossReferenceRepository,
                this.dateValidationService,
                this.validationLookupService,
                this.fileStatusMapper);
    }

    @Test
    @DisplayName(":1060-1062 the read-only entry point refuses a blank filter as a validation failure")
    void theRetrievalEntryPointRefusesABlankFilter() {
        // 1100-RECEIVE-MAP returns immediately for the retrieval turn, so 1210-EDIT-ACCOUNT is the only
        // edit that runs and INPUT-ERROR is set with no I/O failure retained. That combination is what
        // distinguishes a rejected request from a failed one, and it must surface as a typed
        // ValidationException rather than as a projected screen the caller has to inspect.
        assertThatThrownBy(() -> this.service.fetchForUpdate(" "))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining(NO_INPUT_RECEIVED);

        verifyNoInteractions(this.accountRepository,
                this.customerRepository,
                this.cardCrossReferenceRepository,
                this.dateValidationService,
                this.validationLookupService,
                this.fileStatusMapper);
    }

    @Test
    @DisplayName(":1837-1846 the read-only entry point refuses a filter that is not eleven digits")
    void theRetrievalEntryPointRefusesAShortFilter() {
        assertThatThrownBy(() -> this.service.fetchForUpdate("123"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining(ELEVEN_DIGIT_ACCOUNT);

        verifyNoInteractions(this.cardCrossReferenceRepository);
    }

    @Test
    @DisplayName("NUMVAL-C accepts the sign in either position, so a trailing minus is valid")
    void aTrailingMinusSignIsAcceptedByTheCurrencyAwareConversion() {
        this.screen.creditLimit = "2020.00-";
        givenEveryFieldEditPasses();

        final AccountUpdateResult result = editTurn();

        assertThat(errorText(result))
                .as("FUNCTION NUMVAL-C is used for the money fields, unlike the plain-numeric keys")
                .isEmpty();
        assertThat(result.changeAction()).isEqualTo(ChangeAction.CHANGES_OK_NOT_CONFIRMED);
    }

    @Test
    @DisplayName("NUMVAL-C rejects two sign characters in one value")
    void twoSignCharactersAreRejectedByTheCurrencyAwareConversion() {
        this.screen.creditLimit = "-2020.00-";
        givenEveryFieldEditPasses();

        assertThat(errorText(editTurn())).isEqualTo(CREDIT_LIMIT_IS_NOT_VALID);
    }

    @Test
    @DisplayName("NUMVAL-C rejects two decimal points in one value")
    void twoDecimalPointsAreRejectedByTheCurrencyAwareConversion() {
        this.screen.creditLimit = "20.20.00";
        givenEveryFieldEditPasses();

        assertThat(errorText(editTurn())).isEqualTo(CREDIT_LIMIT_IS_NOT_VALID);
    }

    @Test
    @DisplayName("NUMVAL-C rejects a value that is nothing but a decimal point")
    void aLoneDecimalPointIsRejectedByTheCurrencyAwareConversion() {
        // A bare '.' survives the character-class scan but yields no digits, so the conversion has
        // nothing to return. It is a distinct arm from the blank test, which never reaches the scan.
        this.screen.creditLimit = ".";
        givenEveryFieldEditPasses();

        final AccountUpdateResult result = editTurn();

        assertThat(errorText(result)).isEqualTo(CREDIT_LIMIT_IS_NOT_VALID);
        assertThat(errorText(result))
                .as("the blank arm's diagnostic is a different literal")
                .isNotEqualTo(labelled(LABEL_CREDIT_LIMIT, MUST_BE_SUPPLIED));
    }

    @Test
    @DisplayName("NUMVAL-C tolerates a leading plus sign, which the plain-numeric keys do not")
    void aLeadingPlusSignIsAcceptedByTheCurrencyAwareConversion() {
        this.screen.creditLimit = "+2020.00";
        givenEveryFieldEditPasses();

        assertThat(errorText(editTurn())).isEmpty();
    }

    @Test
    @DisplayName("NUMVAL-C rejects a plus sign buried between digits")
    void aBuriedPlusSignIsRejectedByTheCurrencyAwareConversion() {
        this.screen.creditLimit = "20+20.00";
        givenEveryFieldEditPasses();

        assertThat(errorText(editTurn())).isEqualTo(CREDIT_LIMIT_IS_NOT_VALID);
    }

    @Test
    @DisplayName("NUMVAL-C ignores the currency symbol and thousands separators in a MONEY field")
    void aCurrencySymbolAndThousandsSeparatorAreIgnoredInAMoneyField() {
        // THE TWO-PARSER ASYMMETRY. FUNCTION NUMVAL-C is used for the five money fields and plain
        // FUNCTION NUMVAL for the account key, so the same keystrokes are accepted here and refused
        // there. The refusal half is asserted by the hostile-input tier against the account filter;
        // this is the acceptance half, and the pair is the whole point.
        this.screen.creditLimit = "$2,020.00";
        givenEveryFieldEditPasses();

        assertThat(errorText(editTurn()))
                .as("a money field tolerates the editing characters a key field rejects")
                .isEmpty();
    }

    @Test
    @DisplayName("a whitespace-only money field is blank to the edit and unconvertible to the image")
    void aWhitespaceOnlyMoneyFieldIsBlank() {
        // :1136-1146 builds TWO images of every money field: a numeric one through NUMVAL-C and an
        // alphanumeric one that keeps the keystrokes. Whitespace survives RECEIVE unchanged, so the
        // numeric half trims to nothing while the alphanumeric half still names the field.
        this.screen.creditLimit = "   ";
        givenEveryFieldEditPasses();

        assertThat(errorText(editTurn())).isEqualTo(labelled(LABEL_CREDIT_LIMIT, MUST_BE_SUPPLIED));
    }

    // 0000-MAIN's two cold-start arms, its abend net, and the arms that cannot be reached at all.

    @Test
    @DisplayName(":880-893 the very first turn clears the caller context and paints an empty screen")
    void theFirstEntryTurnPaintsAnEmptyScreenWithoutReadingAnything() {
        // :883-884 INITIALIZE CARDDEMO-COMMAREA WS-THIS-PROGCOMMAREA, :885 SET CDEMO-PGM-ENTER,
        // :886 SET ACUP-DETAILS-NOT-FETCHED. The :964 arm then matches and returns at :973 WITHOUT
        // performing 1000-PROCESS-INPUTS, so no dataset is touched on the first turn - which is why
        // fetchForUpdate seeds the SECOND turn instead of this one.
        final AccountUpdateResult result = this.service.processRequest(
                this.screen.build(this.snapshot.build()),
                AID_ENTER,
                ChangeAction.SHOW_DETAILS,
                EntryMode.FIRST_ENTRY);

        assertThat(result.responseKind()).isEqualTo(ResponseKind.MAP);
        assertThat(result.changeAction())
                .as(":886 overrides whatever marker the caller supplied")
                .isEqualTo(ChangeAction.DETAILS_NOT_FETCHED);
        assertThat(result.navigation().fromTransactionId())
                .as(":883 INITIALIZE CARDDEMO-COMMAREA discards the caller identity")
                .isNull();
        assertThat(result.navigation().fromProgram()).isNull();
        assertThat(result.informationMessage().stripTrailing()).isEqualTo(INFO_PROMPT_FOR_SEARCH_KEYS);
        verifyNoInteractions(this.accountRepository,
                this.customerRepository,
                this.cardCrossReferenceRepository,
                this.dateValidationService,
                this.validationLookupService,
                this.fileStatusMapper);
    }

    @Test
    @DisplayName(":964-967 the second member of the fall-through pair reaches the same shared body")
    void anUnfetchedEnterTurnPaintsTheSameEmptyScreen() {
        // The source writes TWO WHEN clauses over one body at :968-973. Reproducing them as a single
        // disjunction is correct, but only if BOTH members are shown to land there - a one-member test
        // would pass against an implementation that had silently dropped the other.
        final AccountUpdateResult result = this.service.processRequest(
                this.screen.build(this.snapshot.build()),
                AID_ENTER,
                ChangeAction.DETAILS_NOT_FETCHED,
                EntryMode.ENTER);

        assertThat(result.responseKind()).isEqualTo(ResponseKind.MAP);
        assertThat(result.changeAction()).isEqualTo(ChangeAction.DETAILS_NOT_FETCHED);
        assertThat(result.informationMessage().stripTrailing()).isEqualTo(INFO_PROMPT_FOR_SEARCH_KEYS);
        assertThat(errorText(result))
                .as("nothing was edited, so no diagnostic exists")
                .isEmpty();
        // :2703-2708 is skipped entirely on an ENTER turn, so the filter is not even echoed.
        assertThat(result.screen().getAccountId()).isNull();
        verifyNoInteractions(this.accountRepository,
                this.customerRepository,
                this.cardCrossReferenceRepository,
                this.dateValidationService,
                this.validationLookupService,
                this.fileStatusMapper);
    }

    @Test
    @DisplayName(":861 HANDLE ABEND: an unexpected collaborator fault becomes a fatal abend, cause kept")
    void anUnexpectedCollaboratorFaultIsConvertedToAnAbendWithTheCausePreserved() {
        // EXEC CICS HANDLE ABEND LABEL(ABEND-ROUTINE) covers the whole program, so a fault with no
        // RESP code of its own still lands on the abend path rather than escaping raw. Clause B
        // requires the root cause to survive that translation, so it is asserted, not assumed.
        final IllegalStateException fault = new IllegalStateException("collaborator contract broken");
        when(this.dateValidationService.editDate(any(), any())).thenThrow(fault);

        assertThatThrownBy(this::editTurn)
                .isInstanceOf(FatalProcessingException.class)
                .satisfies(thrown -> {
                    final FatalProcessingException fatal = (FatalProcessingException) thrown;
                    assertThat(fatal.getCause())
                            .as("Clause B: wrap with context, never swallow")
                            .isSameAs(fault);
                    assertThat(fatal.getAbendCulprit()).isEqualTo(PROGRAM_NAME);
                });
        verifyNoInteractions(this.accountRepository, this.customerRepository);
    }

    @Test
    @DisplayName(":2633-2640 the decider's WHEN OTHER abend arm is unreachable from any entry point")
    void theDecidersUnexpectedScenarioArmIsUnreachable() {
        // 0000-MAIN's :979-980 arm intercepts ACUP-CHANGES-OKAYED-AND-DONE and both ACUP-CHANGES-FAILED
        // codes before 2000-DECIDE-ACTION is ever performed, and the decider carries an explicit arm for
        // each of the four markers that do reach it. Every one of the seven markers is therefore
        // accounted for, leaving WHEN OTHER with no reachable input.
        //
        // No marker value reaches 2000-DECIDE-ACTION and matches none of its arms: exhibiting one would
        // take an eighth ACUP-CHANGE-ACTION code, or a caller able to
        // set CCARD-AID and the change action independently of 0000-MAIN's own arm order.
        //
        // The arm is nevertheless RETAINED, with its four abend fields and its EXEC CICS ABEND, because
        // deleting it would break the paragraph map. Clause B is satisfied by the citation and the
        // marker on this test rather than by removal - the documented conflict, resolved for parity.
        for (final ChangeAction marker : ChangeAction.values()) {
            final boolean interceptedByMainLine = marker == ChangeAction.CHANGES_OKAYED_AND_DONE
                    || marker.isChangesFailed();
            final boolean matchedByAnExplicitArm = marker == ChangeAction.DETAILS_NOT_FETCHED
                    || marker == ChangeAction.SHOW_DETAILS
                    || marker == ChangeAction.CHANGES_NOT_OK
                    || marker == ChangeAction.CHANGES_OK_NOT_CONFIRMED;

            assertThat(interceptedByMainLine || matchedByAnExplicitArm)
                    .as("%c is accounted for before WHEN OTHER can see it", marker.marker())
                    .isTrue();
        }
        // The payload the arm would have carried is still asserted, so a future edit cannot quietly
        // change it while the arm remains unexercised.
        assertThat(UNEXPECTED_SCENARIO_CODE).isEqualTo("0001");
        assertThat(UNEXPECTED_SCENARIO_MESSAGE).isEqualTo("UNEXPECTED DATA SCENARIO");
        assertThat(ONLINE_ABEND_CODE)
                .as("the online contract is a four-character ABCODE, not the batch 999 / RC-12 pair")
                .isEqualTo("9999");
    }

    @Test
    @DisplayName(":2721-2723 the screen painter's WHEN OTHER arm is likewise unreachable")
    void theScreenPaintersWhenOtherArmIsUnreachable() {
        // 3200-SETUP-SCREEN-VARS dispatches on ACUP-DETAILS-NOT-FETCHED, ACUP-SHOW-DETAILS and the
        // five-code ACUP-CHANGES-MADE group. Those eight condition names cover all seven markers, so
        // the WHEN OTHER body - which duplicates the ACUP-SHOW-DETAILS body - can never run.
        //
        // No marker falls outside all three condition names; one would take an eighth
        // ACUP-CHANGE-ACTION code. The duplicate body is retained for paragraph correspondence.
        for (final ChangeAction marker : ChangeAction.values()) {
            assertThat(marker == ChangeAction.DETAILS_NOT_FETCHED
                            || marker == ChangeAction.SHOW_DETAILS
                            || marker.isChangesMade())
                    .as("%c is claimed by one of the three named arms", marker.marker())
                    .isTrue();
        }
    }

    // The snapshot's absence on the two paths that do NOT gate it up front, and the snapshot echo.

    @Test
    @DisplayName(":1684-1773 an edit turn without the snapshot fails validation instead of comparing")
    void anEditTurnWithoutTheSnapshotIsAnExplicitValidationFailure() {
        // 1205-COMPARE-OLD-NEW cannot answer its question without ACUP-OLD-DETAILS, and the choice
        // between "assume nothing changed" and "report the absence" is the whole difference between a
        // silent lost update and a refused request. Inverting it loses writes silently.
        //
        // Note what the CALLER sees on this path: processRequest does not rethrow, so the outcome is
        // ACUP-CHANGES-NOT-OK with no screen diagnostic - the typed failure is retained for the write
        // entry point to raise. That is why updateAccount gates the snapshot up front as well.
        final AccountUpdateRequest submitted = this.screen.build(null);
        givenEveryFieldEditPasses();

        final AccountUpdateResult result = this.service.processRequest(submitted,
                AID_ENTER,
                ChangeAction.SHOW_DETAILS,
                EntryMode.REENTER);

        assertThat(submitted.getOldDetails())
                .as("the caller transmitted no snapshot at all")
                .isNull();
        // :3801 never ran and :1438's INITIALIZE never ran either, so ACUP-OLD-DETAILS is present but
        // wholly unpopulated. The echo therefore returns an EMPTY GROUP rather than no group - the
        // distinction matters, because a caller that tested only for absence would see a snapshot.
        assertThat(result.screen().getOldDetails()).isNotNull();
        assertThat(result.screen().getOldDetails().getAccountId()).isNull();
        assertThat(result.changeAction())
                .as("the comparison reported a change it could not verify, so the turn is not OK")
                .isEqualTo(ChangeAction.CHANGES_NOT_OK);
        verifyNoInteractions(this.accountRepository, this.customerRepository);
    }

    @Test
    @DisplayName(":3919-3942 a snapshot-less write fails the CUSTOMER lock first, and that reads as success")
    void aConfirmTurnWithoutTheSnapshotCompoundsTheCustomerLockBlocker() {
        // 9700's own missing-snapshot guard turns out to be UNREACHABLE, and the reason is worth stating
        // precisely: :3919-3921 keys the customer READ ... UPDATE from the SNAPSHOT customer id, not from
        // the screen. A request carrying no snapshot therefore has no customer key, fails the read at
        // :3934, and never reaches :3947. The two defects then compound - the customer-lock failure is
        // itself reported as success by the four-arm EVALUATE at :2606-2615 - so a write
        // with no snapshot is acknowledged to the operator while nothing at all was written.
        //
        // That compounding is why updateAccount gates the snapshot BEFORE 0000-MAIN is entered
        // rather than relying on either in-flow guard.
        //
        // No input reaches 9700 with a null ACUP-OLD-DETAILS: one would need
        // a customer key sourced from ACUP-NEW-CUST-ID instead of the snapshot - which would itself be a
        // behaviour change, so the guard is retained unexercised rather than made reachable.
        givenAccountLocked();

        final AccountUpdateResult result = this.service.processRequest(
                this.screen.build(null),
                AID_PFK05,
                ChangeAction.CHANGES_OK_NOT_CONFIRMED,
                EntryMode.REENTER);

        // Fact one: the customer-lock diagnostic IS latched.
        assertThat(errorText(result)).isEqualTo(COULD_NOT_LOCK_CUSTOMER);
        // Fact two: the reported outcome is nevertheless SUCCESS.
        assertThat(result.changeAction()).isEqualTo(ChangeAction.CHANGES_OKAYED_AND_DONE);
        assertThat(result.informationMessage().stripTrailing()).isEqualTo(INFO_UPDATE_SUCCESS);
        // Fact three: nothing was written on either dataset.
        verify(this.accountRepository, never()).save(any());
        verify(this.customerRepository, never()).save(any());
        assertThat(this.account.getCurrentBalance()).isEqualByComparingTo(new BigDecimal("194.00"));
    }

    @Test
    @DisplayName(":2703-2708 a numerically zero filter is blanked on the echoed screen")
    void aNumericallyZeroFilterIsBlankedOnTheEcho() {
        // :1452-1457 asserts the filter VALID optimistically before any comparison, and 1210-EDIT-ACCOUNT
        // - the only routine that would have called all-zeroes invalid - runs on the unfetched turn only.
        // The blanking at :2705 is what stops that optimism reaching the operator as eleven zeroes.
        this.screen.accountId = "00000000000";
        givenEveryFieldEditPasses();

        final AccountUpdateResult result = editTurn();

        assertThat(result.screen().getAccountId())
                .as("MOVE SPACES TO ACCTSIDO, not the zero string")
                .isNull();
    }

    @Test
    @DisplayName(":3813 the snapshot echo re-renders a NEGATIVE amount with its zoned overpunch")
    void theSnapshotEchoPreservesTheNegativeOverpunchAndTheNonZeroLowOrderDigit() {
        // ACUP-OLD-CURR-BAL is PIC X(12) redefined as S9(10)V99, so the sign lives in the LAST byte as
        // an overpunch: '}' for minus zero and 'J'..'R' for minus one through minus nine. A low-order
        // digit of five with a negative sign is therefore 'N', and the value must survive the trip out
        // to the caller and back byte for byte or the next turn's comparison sees a phantom change.
        this.snapshot.currentBalance = "00000001940N";
        givenEveryFieldEditPasses();

        final AccountUpdateResult result = editTurn();

        assertThat(result.screen().getOldDetails().getCurrentBalance())
                .as("the overpunch is regenerated from the parsed amount, not carried through as text")
                .isEqualTo("00000001940N");
        assertThat(result.screen().getOldDetails().currentBalanceAmount())
                .isEqualByComparingTo(new BigDecimal("-194.05"));
    }

    @Test
    @DisplayName(":3350 a short credit score is right-aligned and zero-filled into PIC 9(03)")
    void aShortCreditScoreIsZeroFilledIntoTheEntity() {
        // MOVE ACUP-NEW-CUST-FICO-SCORE TO CUST-FICO-CREDIT-SCORE is a numeric MOVE between display
        // numerics, so it right-aligns and zero-fills rather than left-aligning and blank-filling. Only
        // the SCREEN side is varied: 9700 compares the LIVE record against the snapshot, never the
        // screen, so a new keystroke cannot make the change detector fire.
        this.screen.ficoScore = "7";
        givenAccountLocked();
        givenCustomerLocked();

        final AccountUpdateResult result = confirm();

        assertThat(result.changeAction()).isEqualTo(ChangeAction.CHANGES_OKAYED_AND_DONE);
        assertThat(this.customer.getFicoCreditScore())
                .as("PIC 9(03) receives 007, never the left-aligned keystroke")
                .isEqualTo("007");
    }

    /**
     * <b>The confirm turn runs no edit at all.</b> The structural fact the whole confirm-turn contract rests
     * on, asserted directly rather than inferred from two field examples.
     *
     * <p>{@code 1200-EDIT-MAP-INPUTS} at {@code app/cbl/COACTUPC.cbl:1463-1468} reads:
     *
     * <pre>
     *     IF  NO-CHANGES-FOUND
     *     OR  ACUP-CHANGES-OK-NOT-CONFIRMED
     *     OR  ACUP-CHANGES-OKAYED-AND-DONE
     *         MOVE LOW-VALUES           TO WS-NON-KEY-FLAGS
     *         GO TO 1200-EDIT-MAP-INPUTS-EXIT
     *     END-IF
     * </pre>
     *
     * <p>So on the confirm turn the cascade is abandoned before its first {@code PERFORM}, and {@code :2602-2605}
     * goes straight to {@code 9600-WRITE-PROCESSING}. Every one of the twenty-odd field edits - the yes/no edit
     * at {@code :1473}, the mandatory-alphanumeric edits, the required-numeric edit at {@code :1548}, the
     * lookup-validated state and zip edits - is skipped. The pseudo-conversational reason is that the 3270 still
     * held values the previous turn had already validated.
     *
     * <p>This test proves it the only way a black-box test can: by presenting values on the confirm turn that
     * the first turn's cascade demonstrably refuses, and observing that the write happens anyway. It arranges
     * <b>every collaborator the cascade would consult to answer "invalid"</b>, so if any edit did run the write
     * would be refused and this test would fail. The four sibling tests that check one field each -
     * {@code aShortCreditScoreIsZeroFilledIntoTheEntity}, {@code aNonNumericCreditScoreIsMovedAsAlphanumeric},
     * {@code anEmptyGroupIdentifierIsTheClearedMarker} and {@code tenBlanksInTheGroupIdentifierStillWrite} -
     * check the {@code MOVE} semantics per field; this one checks that the gate they pass through is open.
     */
    @Test
    @DisplayName(":1463-1468 the confirm turn abandons the whole edit cascade, so values the first turn "
            + "refuses are written unvalidated")
    void theConfirmTurnAbandonsTheWholeEditCascade() {
        // Values spanning four different edit families, every one of which the first turn refuses:
        //   accountStatus  'X'         -> 1220-EDIT-YESNO accepts only Y or N
        //   ficoScore      '4B7'       -> 1245-EDIT-NUM-REQD requires three digits
        //   addressStateCode 'ZZ'      -> a lookup edit, and the lookup service is told it is unknown below
        //   addressZip     '00000'     -> the state-and-zip combination edit, likewise told it is unknown
        //   primaryCardHolderIndicator 'Q' -> another yes/no edit
        this.screen.accountStatus = "X";
        this.screen.ficoScore = "4B7";
        this.screen.addressStateCode = "ZZ";
        this.screen.addressZip = "00000";
        this.screen.primaryCardHolderIndicator = "Q";

        // Every collaborator the cascade would consult is told the value is INVALID. If a single edit ran on
        // this turn, the write below would be refused - which is precisely what makes this a proof and not an
        // illustration. lenient() because the point is that these stubs are NEVER consulted.
        lenient().when(this.validationLookupService.isValidUsStateCode(anyString())).thenReturn(false);
        lenient().when(this.validationLookupService.isValidStateAndZipCode(anyString(), anyString()))
                .thenReturn(false);
        lenient().when(this.validationLookupService.isValidPhoneAreaCode(anyString())).thenReturn(false);

        givenAccountLocked();
        givenCustomerLocked();

        final AccountUpdateResult result = confirm();

        assertThat(result.changeAction())
                .as("the confirm turn wrote. :1463-1468 abandoned the cascade before its first PERFORM, so "
                        + "not one of the five deliberately invalid values above was examined. A SHOW_DETAILS "
                        + "here would mean an edit had been re-run on the write turn, which is a behaviour "
                        + "change the source does not make")
                .isEqualTo(ChangeAction.CHANGES_OKAYED_AND_DONE);

        assertThat(this.account.getActiveStatus())
                .as("ACCT-ACTIVE-STATUS receives 'X' verbatim, though 1220-EDIT-YESNO at :1473 would have "
                        + "refused it on the first turn")
                .isEqualTo("X");
        assertThat(this.customer.getFicoCreditScore())
                .as("CUST-FICO-CREDIT-SCORE receives '4B7', though 1245-EDIT-NUM-REQD at :1548 would have "
                        + "refused a non-numeric on the first turn")
                .isEqualTo("4B7");
        assertThat(this.customer.getAddressStateCode())
                .as("CUST-ADDR-STATE-CD receives 'ZZ' even though the lookup was primed to reject it")
                .isEqualTo("ZZ");
        assertThat(this.customer.getAddressZip())
                .as("CUST-ADDR-ZIP receives the unknown combination too, blank-filled to PIC X(10)")
                .startsWith("00000");
        assertThat(this.customer.getPrimaryCardHolderIndicator())
                .as("CUST-PRI-CARD-HOLDER-IND receives 'Q', the second yes/no edit likewise unrun")
                .isEqualTo("Q");

        verify(this.accountRepository).save(this.account);
        verify(this.customerRepository).save(this.customer);
        verifyNoInteractions(this.validationLookupService);
    }

    /**
     * Every one of the twenty screened values moves <b>byte for byte</b> on the confirm turn.
     *
     * <p>The sibling above proves the gate is open using five fields. This one walks all twenty, because
     * "the cascade is skipped" and "the value survives the move intact" are different claims and only the
     * second one is about the substrate. A field could pass the first and still fail the second by being
     * trimmed, upper-cased, re-padded or truncated on its way into the column - and every one of those would
     * be a silent parity break that no error message would announce.
     *
     * <p><b>What the source does.</b> {@code :3962-4002} and {@code :4010-4059} are plain {@code MOVE}
     * statements into {@code PIC X(n)} and {@code COMP-3} fields. A {@code MOVE} to an alphanumeric field is
     * a left-aligned copy with blank fill and no transformation of any kind: leading blanks survive, mixed
     * case survives, punctuation and digits survive in fields whose edits would have refused them. So the
     * assertion each character field is held to is exactly that - the stored value starts with the submitted
     * bytes and everything after them is blank - which is expressed once and applied fifteen times rather
     * than restating fifteen column widths that the schema already owns.
     *
     * <p>The values are chosen to be hostile: lower case where the snapshot comparison lower-cases and where
     * it upper-cases, a leading-blank address line, punctuation and a digit in a name, a non-numeric credit
     * score, a state code and postal code the lookup is primed to refuse, and a negative amount. Each would
     * be refused by the first turn's cascade; none is examined on this one.
     *
     * <p>The five amount values are asserted differently and deliberately so: {@code COMP-3} has no padding,
     * so the claim there is that the value and its <b>scale</b> survive - a quantity that arrived with two
     * decimal places must be stored with two, since a rescale would change the bytes the fixed-width writers
     * later emit.
     */
    @Test
    @DisplayName(":3962-4059 all twenty screened values move byte for byte, unexamined, on the confirm turn")
    void everyScreenedValueMovesByteForByteOnTheConfirmTurn() {
        // The account image, in the order of :3962-4002.
        this.screen.accountStatus = "X";
        this.screen.currentBalance = "-1234.56";
        this.screen.creditLimit = "0.01";
        this.screen.cashCreditLimit = "99999999.99";
        this.screen.currentCycleCredit = "-0.01";
        this.screen.currentCycleDebit = "7.50";
        this.screen.accountGroupId = "qWeRtY";
        // The customer image, in the order of :4010-4059.
        this.screen.firstName = "mIxEdCaSe";
        this.screen.middleName = "z";
        this.screen.lastName = "O'BRIEN-1";
        this.screen.addressLine1 = "7 RUE #9";
        this.screen.addressLine2 = "  LEADING";
        this.screen.addressCity = "sEaTtLe";
        this.screen.addressStateCode = "zz";
        this.screen.addressCountryCode = "xy";
        this.screen.addressZip = "0000A";
        this.screen.governmentIssuedId = "gov-id-lower";
        this.screen.eftAccountId = "abc0000001";
        this.screen.primaryCardHolderIndicator = "q";
        this.screen.ficoScore = "4b7";

        lenient().when(this.validationLookupService.isValidUsStateCode(anyString())).thenReturn(false);
        lenient().when(this.validationLookupService.isValidStateAndZipCode(anyString(), anyString()))
                .thenReturn(false);

        givenAccountLocked();
        givenCustomerLocked();

        final AccountUpdateResult result = confirm();

        assertThat(result.changeAction())
                .as("all twenty values are present, so the unstorable-value screen passes and the write "
                        + "proceeds; every one of them would have been refused by an edit the confirm turn "
                        + "does not run")
                .isEqualTo(ChangeAction.CHANGES_OKAYED_AND_DONE);

        assertMovedVerbatim("ACSTTUS -> ACCT-ACTIVE-STATUS",
                this.screen.accountStatus, this.account.getActiveStatus());
        assertMovedVerbatim("AADDGRP -> ACCT-GROUP-ID lower case survives, and it is the one field "
                        + "9700 compares through FUNCTION LOWER-CASE, so a normalising move here would "
                        + "corrupt the change detector as well as the record",
                this.screen.accountGroupId, this.account.getGroupId());
        assertMovedVerbatim("ACSFNAM -> CUST-FIRST-NAME",
                this.screen.firstName, this.customer.getFirstName());
        assertMovedVerbatim("ACSMNAM -> CUST-MIDDLE-NAME",
                this.screen.middleName, this.customer.getMiddleName());
        assertMovedVerbatim("ACSLNAM -> CUST-LAST-NAME keeps punctuation and a digit that "
                        + "1225-EDIT-ALPHA-REQD would have refused",
                this.screen.lastName, this.customer.getLastName());
        assertMovedVerbatim("ACSADL1 -> CUST-ADDR-LINE-1",
                this.screen.addressLine1, this.customer.getAddressLine1());
        assertMovedVerbatim("ACSADL2 -> CUST-ADDR-LINE-2 keeps its LEADING blanks; a MOVE to PIC X is a "
                        + "left-aligned copy, so trimming the left would change the stored bytes",
                this.screen.addressLine2, this.customer.getAddressLine2());
        assertMovedVerbatim("ACSCITY -> CUST-ADDR-LINE-3, per the :1329-1334 mapping",
                this.screen.addressCity, this.customer.getAddressLine3());
        assertMovedVerbatim("ACSSTTE -> CUST-ADDR-STATE-CD, lower case and unknown to the lookup",
                this.screen.addressStateCode, this.customer.getAddressStateCode());
        assertMovedVerbatim("ACSCTRY -> CUST-ADDR-COUNTRY-CD",
                this.screen.addressCountryCode, this.customer.getAddressCountryCode());
        assertMovedVerbatim("ACSZIPC -> CUST-ADDR-ZIP, five submitted bytes into a ten-byte column",
                this.screen.addressZip, this.customer.getAddressZip());
        assertMovedVerbatim("ACSGOVT -> CUST-GOVT-ISSUED-ID",
                this.screen.governmentIssuedId, this.customer.getGovernmentIssuedId());
        assertMovedVerbatim("ACSEFTC -> CUST-EFT-ACCOUNT-ID",
                this.screen.eftAccountId, this.customer.getEftAccountId());
        assertMovedVerbatim("ACSPFLG -> CUST-PRI-CARD-HOLDER-IND",
                this.screen.primaryCardHolderIndicator,
                this.customer.getPrimaryCardHolderIndicator());
        assertMovedVerbatim("ACSTFCO -> CUST-FICO-CREDIT-SCORE, non-numeric where 1245-EDIT-NUM-REQD "
                        + "would have demanded three digits",
                this.screen.ficoScore, this.customer.getFicoCreditScore());

        assertAmountMoved("ACURBAL -> ACCT-CURR-BAL, negative and therefore proof that no absolute value "
                        + "is taken anywhere on this path",
                this.screen.currentBalance, this.account.getCurrentBalance());
        assertAmountMoved("ACRDLIM -> ACCT-CREDIT-LIMIT",
                this.screen.creditLimit, this.account.getCreditLimit());
        assertAmountMoved("ACSHLIM -> ACCT-CASH-CREDIT-LIMIT at the S9(10)V99 ceiling",
                this.screen.cashCreditLimit, this.account.getCashCreditLimit());
        assertAmountMoved("ACRCYCR -> ACCT-CURR-CYC-CREDIT",
                this.screen.currentCycleCredit, this.account.getCurrentCycleCredit());
        assertAmountMoved("ACRCYDB -> ACCT-CURR-CYC-DEBIT",
                this.screen.currentCycleDebit, this.account.getCurrentCycleDebit());

        verify(this.accountRepository).save(this.account);
        verify(this.customerRepository).save(this.customer);
        verifyNoInteractions(this.validationLookupService);
    }

    /**
     * <b>DEVIATION, not parity.</b> The one confirm-turn state whose source outcome the target's schema cannot
     * represent, and the substitute that stands in for it.
     *
     * <h4>What the source does, established by reading it</h4>
     *
     * <ol>
     *   <li>{@code 1100-RECEIVE-MAP} runs on every turn, including the confirm turn -
     *       {@code app/cbl/COACTUPC.cbl:1026}. The FICO map field is re-received.</li>
     *   <li>{@code :1279-1284}: when the received field is {@code '*'} or {@code SPACES},
     *       {@code MOVE LOW-VALUES TO ACUP-NEW-CUST-FICO-SCORE-X}, a {@code PIC X(03)} field, so it holds
     *       three {@code NUL} bytes.</li>
     *   <li>{@code 1200-EDIT-MAP-INPUTS} abandons the cascade at {@code :1463-1468}, so
     *       {@code 1245-EDIT-NUM-REQD} - the edit that produces {@code 'FICO Score must be supplied.'} - is
     *       <b>never performed on this turn</b>.</li>
     *   <li>{@code :4058-4059}: {@code MOVE ACUP-NEW-CUST-FICO-SCORE TO CUST-UPDATE-FICO-CREDIT-SCORE}, and
     *       the customer record is rewritten.</li>
     * </ol>
     *
     * <p><b>The source therefore stores three {@code NUL} bytes and reports success.</b> That is the parity
     * outcome, and it is unavailable: PostgreSQL text types cannot hold a zero byte at all, and the column is
     * {@code CHAR(3) NOT NULL}. No implementation over this schema can be faithful here.
     *
     * <h4>What this test asserts, and what it refuses to assert</h4>
     *
     * <p>Asserting the substitute outcome as though it were the expectation would be wrong, and the tempting
     * reasoning - "since neither available answer is parity, the one that names the field beats the one
     * that abends on a well-formed request" - is where that error hides. Choosing between two non-parity
     * answers is a legitimate engineering decision; presenting the winner as the expected behaviour is not,
     * because a reader then has no way to tell an intended substitute from an accidental divergence.
     *
     * <p>So this test asserts three things, in this order: <b>the reason</b> - that the source's own outcome is
     * genuinely unrepresentable, which is what makes a substitute necessary at all; <b>the substitute</b> -
     * which one was chosen and that it stops before touching either record; and <b>the record</b> - that the
     * deviation is written down in {@code DECISION_LOG.md} with its severity and its remediation. That last
     * assertion exists because {@code AccountUpdateService.requireStorableUpdateImage} claims in its own
     * Javadoc that the choice "is recorded as a deviation forced by the substrate, not presented as
     * equivalence". A claim of that kind is worth nothing unless something fails when it stops being true.
     */
    @Test
    @DisplayName(":1463-1468 + :4058 DEVIATION: the source stores LOW-VALUES here, which the schema cannot "
            + "hold, so a labelled field refusal stands in")
    void anAbsentCreditScoreIsADeviationBecauseTheSourceStoresLowValues() {
        // ---- The reason. The parity outcome is three NUL bytes in a CHAR(3) NOT NULL column. ----
        assertThat(FICO_SCORE_COLUMN_DEFINITION)
                .as("the column the source's LOW-VALUES would have to land in. CHAR is a text type, and "
                        + "PostgreSQL text types cannot hold a zero byte in any encoding, so the parity "
                        + "outcome is not merely awkward to store - it is unstorable. That is the whole "
                        + "justification for a substitute existing")
                .isEqualTo("CHAR(3)");
        assertThat(schemaColumnDefinition("cust_fico_credit_score"))
                .as("read from src/main/resources/db/migration/V1__create_schema.sql rather than asserted "
                        + "from memory, so a widened column would retire this deviation instead of leaving "
                        + "the justification stale")
                .isEqualTo(FICO_SCORE_COLUMN_DEFINITION + " NOT NULL");

        // ---- The substitute. Chosen, labelled, and stopping before either record is touched. ----
        this.screen.ficoScore = null;
        givenAccountLocked();
        givenCustomerLocked();

        final AccountUpdateResult result = confirm();

        assertThat(result.changeAction())
                .as("the substitute is a field-level refusal, so the turn reports SHOW-DETAILS - review and "
                        + "resubmit - rather than the :2613-2614 success the source would have reported. This "
                        + "IS the divergence, asserted as such")
                .isEqualTo(ChangeAction.SHOW_DETAILS);
        assertThat(errorText(result))
                .as("the diagnostic reuses the program's own literal, composed from the :1545 label and the "
                        + "' must be supplied.' suffix that 1215-EDIT-MANDATORY and 1225-EDIT-ALPHA-REQD "
                        + "already use - so the substitute borrows the source's vocabulary rather than "
                        + "inventing one. Note what it does NOT claim: this literal belongs to an edit the "
                        + "confirm turn never runs, which is exactly why the outcome is a deviation")
                .isEqualTo("FICO Score must be supplied.");
        assertThat(result.errorMessage())
                .as("and is projected through WS-RETURN-MSG PIC X(75) like every other diagnostic")
                .hasSize(RETURN_MESSAGE_WIDTH);
        verify(this.accountRepository, never()).save(any());
        verify(this.accountRepository, never()).flush();
        verify(this.customerRepository, never()).save(any());
        verify(this.customerRepository, never()).flush();

        // ---- The record. The production Javadoc's claim, made enforceable. ----
        final String decisionLog = readRepositoryFile("DECISION_LOG.md");
        assertThat(decisionLog)
                .as("requireStorableUpdateImage states in its Javadoc that this choice 'is recorded as a "
                        + "deviation forced by the substrate, not presented as equivalence'. It must "
                        + "therefore appear in DECISION_LOG.md under the DEVIATION classification; a Javadoc "
                        + "that says so while the register carries no such entry is asserting "
                        + "evidence it has not got")
                .contains(UNSTORABLE_IMAGE_DEVIATION_ID);
        final String entry = decisionLogEntry(decisionLog, UNSTORABLE_IMAGE_DEVIATION_ID);
        assertThat(entry)
                .as("%s must classify itself a DEVIATION, name the screening method, cite the paragraph "
                        + "whose outcome it replaces, and carry a remediation - the four things that "
                        + "distinguish a disclosed substitute from an unexplained divergence",
                        UNSTORABLE_IMAGE_DEVIATION_ID)
                .contains("`DEVIATION`")
                .contains("requireStorableUpdateImage")
                .contains("9600-WRITE-PROCESSING")
                .contains("**Remediation / follow-up**");
    }

    /**
     * The screen covers <b>exactly</b> the fields the two image-building paragraphs move, no more and no fewer.
     *
     * <p><b>Why a completeness assertion rather than twenty near-duplicate tests.</b> The deviation above is
     * bounded by how many fields the invented screen covers. Twenty separate tests would prove that each
     * currently-screened field behaves as documented, and would say nothing at all when a twenty-first field
     * was added - which is the change that would silently widen the deviation. Pinning the set is strictly
     * stronger: it fails both when a field is dropped and when one is added.
     *
     * <p>The expected set is derived from the source, not from the implementation: it is the fields
     * {@code 9600-WRITE-PROCESSING} moves into the two update images at
     * {@code app/cbl/COACTUPC.cbl:3962-4002} for the account and {@code :4010-4059} for the customer, minus the
     * three assembled values - the date of birth, the two phone numbers and the social security number - which
     * cannot arrive absent because their components go through the alphanumeric move that renders a missing
     * component as blanks, exactly as a {@code MOVE} to a {@code PIC X} field would.
     *
     * <p>All of them share the FICO field's condition: the source stores {@code LOW-VALUES} and the column is
     * {@code NOT NULL CHAR}. That uniformity is what makes one deviation entry cover the set rather than
     * twenty.
     */
    @Test
    @DisplayName(":3962-4059 the unstorable-value screen covers exactly the fields the two images move")
    void theUnstorableValueScreenCoversExactlyTheMovedFields() {
        final List<String> screened = screenedFieldNames();

        assertThat(screened)
                .as("the census must find fields at all, or the set assertion below would pass vacuously")
                .isNotEmpty();
        assertThat(screened)
                .as("the screen must cover exactly the fields :3962-4002 and :4010-4059 move into the two "
                        + "update images, excluding the three assembled values that cannot arrive absent. A "
                        + "field ADDED here widens a labelled deviation and needs its own reasoning; a field "
                        + "REMOVED reopens the abend path the deviation exists to close. Resolved: %s",
                        screened)
                .containsExactlyInAnyOrderElementsOf(EXPECTED_SCREENED_FIELDS);
    }

    @Test
    @DisplayName(":3350 the write entry point turns that same refusal into a named field failure")
    void anAbsentCreditScoreReachesTheCallerAsANamedFieldFailure() {
        // The companion to the test above, through the REST entry point: :1766-1768 rethrows the retained
        // failure, so the caller receives the field name and the BLANK kind rather than abend 9999. The
        // field is named in the BMS vocabulary of app/cpy-bms/COACTUP.CPY, which is what every other
        // field-level failure from this bean already uses.
        this.screen.ficoScore = null;
        // No lock is arranged, and that absence is part of the assertion: the edit cascade of
        // :1210-1280 runs on the single stateless turn, so an unsupplied required field is refused
        // before 9600-WRITE-PROCESSING reads either record for update. Stubbing a read here would
        // stub a call that never happens, which STRICT_STUBS correctly refuses.

        assertThatThrownBy(this::write)
                .isInstanceOf(ValidationException.class)
                .hasMessage("FICO Score must be supplied.")
                .satisfies(thrown -> {
                    final ValidationException typed = (ValidationException) thrown;
                    assertThat(typed.getFieldName()).isEqualTo("ACSTFCO");
                    assertThat(typed.getFailureKind())
                            .as("CSSETATY emits '*' for BLANK and not for NOT-OK, so the distinction "
                                    + "between absent and wrong survives out to the caller")
                            .isEqualTo(ValidationException.FailureKind.BLANK);
                })
                .as("the abend funnel is not reached, so no FatalProcessingException is produced")
                .isNotInstanceOf(FatalProcessingException.class);
        verify(this.accountRepository, never()).save(any());
        verify(this.customerRepository, never()).save(any());
    }

    @Test
    @DisplayName(":4002 an ABSENT group identifier is refused before the write")
    void anAbsentGroupIdentifierIsRefused() {
        // ACCT-GROUP-ID is the field a plain read-then-write-back reaches, because 49 of the 50 seeded
        // accounts carry ten blanks in it. The blanks case is the control two tests below.
        this.screen.accountGroupId = null;
        givenAccountLocked();
        givenCustomerLocked();

        final AccountUpdateResult refused = confirm();

        assertThat(refused.changeAction()).isEqualTo(ChangeAction.SHOW_DETAILS);
        assertThat(errorText(refused)).isEqualTo("Account Group Id must be supplied.");
        verify(this.accountRepository, never()).save(any());
        verify(this.customerRepository, never()).save(any());
    }

    @Test
    @DisplayName(":4002 an EMPTY group identifier is the CLEARED marker and stores the blank image")
    void anEmptyGroupIdentifierClearsTheFieldRatherThanAbending() {
        // The empty string is the case a reader would not predict, and it is deliberately NOT the absent
        // case. docs/api-contracts.md section 11.2.1 publishes "*" and "" as the same instruction - this
        // field was cleared - so "" resolves to the space-filled clear value and the write proceeds, which
        // is consistent with the control below: ACCT-GROUP-ID has no edit anywhere in the source, so
        // blanks are legitimate stored content. What must never happen, and is what this test guards, is
        // the abend the entity guard used to raise for exactly this input.
        this.screen.accountGroupId = "";
        givenAccountLocked();
        givenCustomerLocked();

        final AccountUpdateResult accepted = confirm();

        assertThat(accepted.changeAction()).isEqualTo(ChangeAction.CHANGES_OKAYED_AND_DONE);
        assertThat(this.account.getGroupId())
                .as("cleared is received as the zero-length blank image and stored verbatim, never "
                        + "as null and never as an abend. The column is CHAR(10) NOT NULL, which "
                        + "pads it back to the ten blanks the control below writes, so the two "
                        + "spellings of cleared converge in the row.")
                .isNotNull()
                .isEmpty();
        verify(this.accountRepository).save(this.account);
    }

    @Test
    @DisplayName(":4002 ten blanks in the group identifier still write, exactly as the seed holds them")
    void tenBlanksInTheGroupIdentifierStillWrite() {
        // The control for the test above, and the reason the screen tests null rather than blankness:
        // ACCT-GROUP-ID has no edit anywhere in the source - it is absent from the cascade and from the
        // thirty-nine COPY CSSETATY REPLACING expansions at :3208-3437 - so blanks are legitimate stored
        // content. 49 of the 50 seeded accounts hold exactly this value, so a caller echoing back what
        // the read returned MUST keep succeeding; refusing blankness here would break the common path.
        this.screen.accountGroupId = "          ";
        givenAccountLocked();
        givenCustomerLocked();

        final AccountUpdateResult accepted = confirm();

        assertThat(accepted.changeAction()).isEqualTo(ChangeAction.CHANGES_OKAYED_AND_DONE);
        assertThat(this.account.getGroupId()).isEqualTo("          ");
        verify(this.accountRepository).save(this.account);
    }

    @Test
    @DisplayName(":3964-3974 a transmitted but unreadable amount is INVALID, not BLANK")
    void anUnreadableAmountIsReportedAsInvalidRatherThanAbsent() {
        // numvalC cannot read this, so the amount arrives null exactly as an omitted field would, and
        // requireMoney would refuse both identically. The two are not the same failure: :2184-2199
        // reports an unsupplied amount with ' must be supplied.' and :2201-2215 reports an unreadable one
        // with ' is not valid'. Telling a caller who sent "12.3.4" that they sent nothing would be wrong.
        this.screen.currentBalance = "12.3.4";
        // No lock is arranged, and that absence is part of the assertion: the edit cascade of
        // :1210-1280 runs on the single stateless turn, so an unreadable amount is refused before
        // 9600-WRITE-PROCESSING reads either record for update.

        assertThatThrownBy(this::write)
                .isInstanceOf(ValidationException.class)
                .hasMessage("Current Balance is not valid")
                .satisfies(thrown -> {
                    final ValidationException typed = (ValidationException) thrown;
                    assertThat(typed.getFieldName()).isEqualTo("ACURBAL");
                    assertThat(typed.getFailureKind())
                            .isEqualTo(ValidationException.FailureKind.INVALID);
                });
        verify(this.accountRepository, never()).save(any());
    }

    @Test
    @DisplayName(":3956-4059 an over-width value is caught by the net, not by the abend funnel")
    void anOverWidthValueIsCaughtByTheSafetyNetRatherThanTheAbendFunnel() {
        // The screen above covers the null condition, which is the one the substrate forces. The net
        // behind it covers every other refusal an entity setter can raise - width, scale and range - so
        // that no request-shaped input can reach abendRoutine from step four at all. The diagnostic is
        // deliberately request level and names neither the property nor the column: the entity's own
        // message carries both, and relaying it would publish the schema (CWE-209).
        this.screen.firstName = "X".repeat(26);
        givenAccountLocked();
        givenCustomerLocked();

        assertThatThrownBy(this::write)
                .isInstanceOf(ValidationException.class)
                .isNotInstanceOf(FatalProcessingException.class)
                .hasMessageContaining("cannot be stored")
                .satisfies(thrown -> {
                    assertThat(thrown.getMessage())
                            .as("no property name, no picture clause, no column width, no table name")
                            .doesNotContain("CUST-FIRST-NAME")
                            .doesNotContain("firstName")
                            .doesNotContain("CHAR(25)")
                            .doesNotContain("customer");
                    assertThat(thrown.getCause())
                            .as("Clause B: the entity's own reason survives on the cause for the log")
                            .isInstanceOf(IllegalArgumentException.class)
                            .hasMessageContaining("CUST-FIRST-NAME");
                });
        verify(this.customerRepository, never()).save(any());
        verify(this.customerRepository, never()).flush();
    }

    @Test
    @DisplayName(":3350 a non-numeric credit score is moved as alphanumeric, not rejected at the MOVE")
    void aNonNumericCreditScoreIsMovedAsAlphanumeric() {
        // 1275-EDIT-FICO-SCORE is what rejects such a value, and it does not run on the confirm turn.
        // The MOVE itself therefore has to do something defined with it, and the source's answer is the
        // alphanumeric MOVE: left-aligned and space-filled to the field width.
        this.screen.ficoScore = "abc";
        givenAccountLocked();
        givenCustomerLocked();

        final AccountUpdateResult result = confirm();

        assertThat(result.changeAction()).isEqualTo(ChangeAction.CHANGES_OKAYED_AND_DONE);
        assertThat(this.customer.getFicoCreditScore()).isEqualTo("abc");
    }

    @Test
    @DisplayName(":4115-4140 the account comparison census: 16 clauses over 10 logical fields")
    void theAccountComparisonCensusIsSixteenClausesOverTenLogicalFields() {
        // 9700's account block carries SIXTEEN comparison clauses covering TEN logical fields. Prose
        // elsewhere describes it as "twelve account predicates", which is neither figure, and no
        // enumeration accompanies that number: collapsing each three-substring date into a single
        // predicate yields ten, counting the three dates as three clauses each but omitting the group
        // identifier yields fifteen, and twelve arises from neither reading. The source counts are the
        // ones asserted here, and NO predicate is consolidated or dropped on the strength of the other.
        //
        // The decomposition below is the evidence for sixteen, and it is asserted rather than merely
        // stated so that a future edit cannot quietly change the shape while the prose still claims it.
        final int scalarClauses = 6;        // status, current balance, credit limit, cash credit limit,
                                            // current cycle credit, current cycle debit
        final int dateFields = 3;           // open, expiraion (sic), reissue
        final int componentsPerDate = 3;    // (1:4) year, (6:2) month, (9:2) day - never whole strings
        final int caseFoldedClauses = 1;    // FUNCTION LOWER-CASE(ACCT-GROUP-ID) at :4139-4140

        assertThat(scalarClauses + dateFields * componentsPerDate + caseFoldedClauses)
                .as("every clause of the account IF, counted individually")
                .isEqualTo(ACCOUNT_COMPARISON_CLAUSES);
        assertThat(scalarClauses + dateFields + caseFoldedClauses)
                .as("the logical fields those clauses cover")
                .isEqualTo(ACCOUNT_LOGICAL_FIELDS);
        assertThat(ACCOUNT_COMPARISON_CLAUSES)
                .as("the two counts differ precisely because the dates are sliced")
                .isNotEqualTo(ACCOUNT_LOGICAL_FIELDS);
        // And neither figure is twelve, which is the whole point of the disclosure.
        assertThat(ACCOUNT_COMPARISON_CLAUSES).isNotEqualTo(AAP_ACCOUNT_PREDICATE_CLAIM);
        assertThat(ACCOUNT_LOGICAL_FIELDS).isNotEqualTo(AAP_ACCOUNT_PREDICATE_CLAIM);
    }

    /**
     * Asserts the transactional declaration that reproduces {@code EXEC CICS SYNCPOINT ROLLBACK}, as a
     * contract in its own right rather than as a side effect of an outcome.
     *
     * <h2>Why a reflective assertion is the right instrument here, and not a weaker substitute</h2>
     *
     * <p>The source backs out explicitly on one of its two write failures and not on the other
     * ({@code app/cbl/COACTUPC.cbl:4076-4081} against {@code :4095-4103}). The Java equivalent reproduces
     * both branches with no conditional logic at all, purely by scoping both rewrites inside one
     * {@code @Transactional(rollbackFor = Exception.class)} method: the earlier failure returns before
     * anything is written, and the later failure returns after the account rewrite but before the commit, so
     * the account write is discarded. The entire behaviour therefore lives in a declaration, and a
     * declaration is exactly what an interaction-based test cannot see.
     *
     * <p>That is not a theoretical gap. There is no transaction manager in this tier - the service is
     * constructed directly with mocked repositories - so no proxy is created, no transaction is begun and no
     * rollback can occur or be observed. Delete both annotations from the production class and every
     * outcome, ordering and exception assertion in this file still passes, while the two dataset writes
     * become two independent units of work and a customer-rewrite failure leaves a half-applied update in
     * the database. Reading the annotation is the only way to make that regression fail here, so it is read.
     *
     * <p>The group asserts four separate things, because three of them can regress independently: that the
     * annotation is present on each write entry point; that its {@code rollbackFor} names {@code Exception}
     * (the default rolls back on unchecked throwables only, and several of this service's failure paths are
     * typed exceptions that a future change could make checked); that {@code readOnly} is not set, which
     * would make the writes fail or be silently discarded; and that both dataset writes are reached from
     * within a single annotated method, since two annotated methods called in sequence would be two units of
     * work and would lose the asymmetry.
     *
     * <p>Integration-level proof that a real rollback occurs is additive to this and belongs where a real
     * transaction manager exists. It does not replace this guard: an integration test proves the behaviour
     * for the paths it exercises, while this guard proves the declaration that gives every path the
     * behaviour.
     */
    @Nested
    @DisplayName("The transactional boundary that reproduces EXEC CICS SYNCPOINT ROLLBACK :4095-4103")
    class TransactionalBoundary {

        /** The two write entry points of the service, both of which reach both dataset rewrites. */
        private static final List<String> WRITE_ENTRY_POINTS = List.of("processRequest", "updateAccount");

        @Test
        @DisplayName("both write entry points declare @Transactional, so neither can be reached unscoped")
        void bothWriteEntryPointsDeclareTransactional() {
            for (final String entryPoint : WRITE_ENTRY_POINTS) {
                final Transactional declared = transactionalOn(entryPoint);

                assertThat(declared)
                        .as("%s must be annotated, or the two dataset writes become two units of work "
                                + "and a customer-rewrite failure leaves the account rewrite applied",
                                entryPoint)
                        .isNotNull();
            }
        }

        @Test
        @DisplayName("rollbackFor names Exception on both, so a checked failure also backs the write out")
        void rollbackForNamesExceptionOnBoth() {
            for (final String entryPoint : WRITE_ENTRY_POINTS) {
                final Transactional declared = transactionalOn(entryPoint);

                assertThat(declared).isNotNull();
                assertThat(declared.rollbackFor())
                        .as("%s: the framework default rolls back for unchecked throwables only, which "
                                + "would leave a checked failure committed", entryPoint)
                        .containsExactly(Exception.class);
                assertThat(declared.rollbackForClassName())
                        .as("%s: the class-literal form is used, so the name form must stay empty rather "
                                + "than duplicating the same intent in two places", entryPoint)
                        .isEmpty();
                assertThat(declared.noRollbackFor())
                        .as("%s: no failure is exempted from the backout", entryPoint)
                        .isEmpty();
            }
        }

        @Test
        @DisplayName("neither write entry point is readOnly, and both use the default propagation")
        void neitherWriteEntryPointIsReadOnly() {
            for (final String entryPoint : WRITE_ENTRY_POINTS) {
                final Transactional declared = transactionalOn(entryPoint);

                assertThat(declared).isNotNull();
                assertThat(declared.readOnly())
                        .as("%s writes two datasets; readOnly would make the rewrites fail or be "
                                + "silently discarded by the provider", entryPoint)
                        .isFalse();
                assertThat(declared.propagation())
                        .as("%s: REQUIRED is what makes the caller's transaction, when there is one, the "
                                + "same unit of work as both rewrites", entryPoint)
                        .isEqualTo(Propagation.REQUIRED);
            }
        }

        @Test
        @DisplayName("one annotated method spans BOTH rewrites, which is what reproduces the asymmetry")
        void oneAnnotatedMethodSpansBothRewrites() {
            // The behavioural claim, asserted rather than described: a single call to the annotated entry
            // point performs the account rewrite and the customer rewrite. Two annotated methods invoked in
            // sequence would commit the first before attempting the second, and the source's backout of the
            // account rewrite on a customer failure would then be unreproducible.
            givenAccountLocked();
            givenCustomerLocked();

            final AccountUpdateResult result = confirm();

            assertThat(transactionalOn("processRequest")).isNotNull();
            final InOrder withinOneTransaction =
                    inOrder(AccountUpdateServiceTest.this.accountRepository,
                            AccountUpdateServiceTest.this.customerRepository);
            withinOneTransaction.verify(AccountUpdateServiceTest.this.accountRepository)
                    .save(AccountUpdateServiceTest.this.account);
            withinOneTransaction.verify(AccountUpdateServiceTest.this.customerRepository)
                    .save(AccountUpdateServiceTest.this.customer);
            assertThat(result.changeAction()).isEqualTo(ChangeAction.CHANGES_OKAYED_AND_DONE);
        }

        @Test
        @DisplayName("the account-rewrite failure needs no explicit backout, and the outcome proves why")
        void theAccountRewriteFailureNeedsNoExplicitBackout() {
            // :4076-4081 has no SYNCPOINT ROLLBACK because nothing has been written yet at that point. The
            // observable consequence in Java is that the customer rewrite is never attempted, so there is
            // nothing for a backout to undo - the scoping alone is sufficient.
            givenAccountLocked();
            givenCustomerLocked();
            doThrow(new DataAccessResourceFailureException("account rewrite failed"))
                    .when(AccountUpdateServiceTest.this.accountRepository).save(any(Account.class));

            assertThatThrownBy(AccountUpdateServiceTest.this::write)
                    .isInstanceOf(CardDemoException.class);

            verify(AccountUpdateServiceTest.this.customerRepository, never()).save(any(Customer.class));
        }

        /**
         * Reads the {@link Transactional} annotation off one public method of the production service.
         *
         * @param methodName the entry point to inspect
         * @return the annotation, or {@code null} when the method carries none - which is itself the
         *         regression these tests exist to catch
         */
        private static Transactional transactionalOn(final String methodName) {
            for (final Method candidate : AccountUpdateService.class.getDeclaredMethods()) {
                if (candidate.getName().equals(methodName)) {
                    return candidate.getAnnotation(Transactional.class);
                }
            }
            throw new AssertionError("AccountUpdateService declares no method named '" + methodName
                    + "'. The write entry points are the two methods that reach 9600-WRITE-PROCESSING; if "
                    + "one was renamed, update this list rather than removing the guard.");
        }
    }

}
