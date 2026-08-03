/*
 * ****************************************************************************
 * Program     : TransactionAddServiceTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11
 * Function    : Verifies TransactionAddService against COTRN02C paragraph by
 *               paragraph. Concentrates on the contracts a reader cannot
 *               confirm by inspection: the two distinct numeric intrinsics of
 *               :204 / :218 (plain NUMVAL) against :383 / :456 (NUMVAL-C) and
 *               the IS NOT NUMERIC guards of :197 / :211 that precede them,
 *               the five positional WHEN clauses per date at :353-:366 and
 *               :368-:381, the four character severity window of :66 read at
 *               :397 / :417, the pass through timestamps of :464-:465 that
 *               consult no clock at all, the zero then plus one identifier
 *               default behind the descending browse of :444-:449, the eight
 *               versus nine integer digit display mask asymmetry of :58 / :59
 *               observed through :481, and every exact screen literal.
 * Source      : app/cbl/COTRN02C.cbl (783 lines, 18 paragraphs)
 *               app/cpy-bms/COTRN02.CPY  (21 input fields)
 *               app/cpy/CVTRA05Y.cpy     (TRAN-AMT S9(09)V99, TS X(26))
 *               app/cpy/CVACT03Y.cpy     (XREF 16 + 9 + 11 = 36 bytes)
 *               app/cbl/CSUTLDTC.cbl     (CSUTLDTC-RESULT-SEV-CD X(04))
 *               app/cbl/CBACT04C.cbl:1-21 (the banner convention) @ 7756d89
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cardemo.config.WebConfig;
import com.cardemo.exception.CardDemoException;
import com.cardemo.exception.DuplicateRecordException;
import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.TransactionAddRequest;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.model.entity.Transaction;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CardCrossReferenceRepository;
import com.cardemo.repository.TransactionRepository;
import com.cardemo.service.shared.DateValidationService;
import com.cardemo.service.shared.FileStatusMapper;
import com.cardemo.service.transaction.TransactionAddService;
import com.cardemo.service.transaction.TransactionAddService.AttentionIdentifier;
import com.cardemo.service.transaction.TransactionAddService.Outcome;
import com.cardemo.service.transaction.TransactionAddService.TransactionAddResult;
import com.cardemo.unit.model.FixedClockProvider;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.annotation.Transactional;

/**
 * Unit tests for {@link TransactionAddService}, the Java target of {@code app/cbl/COTRN02C.cbl} and CICS
 * transaction {@code CT02}.
 *
 * <h2>1. What it does</h2>
 *
 * <p>It proves that the transaction-add bean reproduces its system of record rather than merely working.
 * Assertions are grouped to mirror the source and each cites the paragraph or line it defends. The load
 * bearing claims, every one of which a plausible "cleaner" implementation would silently break, are:</p>
 *
 * <ul>
 *   <li><strong>Two numeric intrinsics, never one.</strong> The plain {@code FUNCTION NUMVAL} parses the
 *       account identifier at {@code app/cbl/COTRN02C.cbl:204} and the card number at {@code :218}, while the
 *       currency-tolerant {@code FUNCTION NUMVAL-C} parses the amount at {@code :383} and again at
 *       {@code :456}. Each parse is preceded by its own guard, so the guard and the parse are one inseparable
 *       pair: {@code IS NOT NUMERIC} at {@code :197} and {@code :211} for the identifiers, and the positional
 *       mask at {@code :339-:351} for the amount.</li>
 *   <li><strong>Both identifier parses echo back to the map.</strong> {@code :206-:207} moves the parsed
 *       {@code WS-ACCT-ID-N PIC 9(11)} into both {@code XREF-ACCT-ID} and {@code ACTIDINI}, and
 *       {@code :220-:221} does the same for {@code WS-CARD-NUM-N PIC 9(16)}, so the response carries the
 *       zero-padded normalised value rather than what was keyed.</li>
 *   <li><strong>Five positional {@code WHEN} clauses guard each date before the date service is reached.</strong>
 *       {@code :353-:366} and {@code :368-:381} test positions {@code (1:4)}, {@code (5:1)}, {@code (6:2)},
 *       {@code (8:1)} and {@code (9:2)} individually, so a positionally malformed date never reaches
 *       {@code CALL 'CSUTLDTC'} at {@code :393} or {@code :413} at all.</li>
 *   <li><strong>Only four characters of the eighty character result are consulted.</strong>
 *       {@code CSUTLDTC-RESULT-SEV-CD PIC X(04)} at {@code app/cbl/COTRN02C.cbl:66} is tested against
 *       {@code '0000'} at {@code :397} and {@code :417}; the {@code 2513} tolerance at {@code :399} and
 *       {@code :419} is the only other accepting path.</li>
 *   <li><strong>This program generates no timestamp.</strong> {@code :464-:465} moves the ten character
 *       {@code TORIGDTI} and {@code TPROCDTI} into the twenty-six character {@code TRAN-ORIG-TS} and
 *       {@code TRAN-PROC-TS}, which right-space-pads with exactly sixteen spaces. No clock is consulted, no
 *       time component is synthesised, and neither the batch rendering nor the online rendering applies.</li>
 *   <li><strong>The identifier is the maximum existing key plus one and the race is retained.</strong>
 *       {@code :444-:449} moves {@code HIGH-VALUES} into the key, browses backwards, and adds one; the
 *       end-of-file arm defaults the key to zeros so the first identifier is {@code 1}. A collision surfaces
 *       as {@link DuplicateRecordException} through the shared {@code DUPKEY}/{@code DUPREC} branch at
 *       {@code :735-:736}, and no sequence, retry or counter is substituted.</li>
 *   <li><strong>The edited mask is one integer digit narrower than the numeric it renders.</strong>
 *       {@code WS-TRAN-AMT-N PIC S9(9)V99} at {@code :58} holds nine integer digits and
 *       {@code WS-TRAN-AMT-E PIC +99999999.99} at {@code :59} renders eight, so the high-order digit is
 *       discarded. The mask is asserted as truncating and is never widened.</li>
 *   </ul>
 *
 * <h2>2. How to build, run and test</h2>
 *
 * <p>This class is bound to <strong>Surefire</strong>: the root {@code pom.xml} includes
 * {@code **}{@code /*Test.java} and excludes {@code **}{@code /integration/**} and {@code **}{@code /e2e/**},
 * so a class placed outside {@code src/test/java/com/cardemo/unit/} would match neither plugin's include set
 * and would silently never run. Run it with:</p>
 *
 * <ul>
 *   <li>{@code ./mvnw -B -ntp test} for the whole unit suite;</li>
 *   <li>{@code ./mvnw -B -ntp test -Dtest=TransactionAddServiceTest} for this class alone;</li>
 *   <li>{@code ./mvnw -B -ntp verify} for the gated build, which additionally enforces the JaCoCo line
 *       coverage floor.</li>
 *   </ul>
 *
 * <p>Compilation runs under {@code -Xlint:all -Werror} with {@code failOnWarning}, so a single warning
 * anywhere in this file fails the build.</p>
 *
 * <h2>3. Key configuration and defaults</h2>
 *
 * <ul>
 *   <li><strong>Pure JVM tier.</strong> No Spring context, no container, no database and no network. The three
 *       collaborators that perform I/O are Mockito mocks and {@link FileStatusMapper} is a real instance
 *       because it is a pure function object.</li>
 *   <li><strong>Mockito strict stubs</strong>, declared explicitly through {@code @MockitoSettings}, so an
 *       arrangement the service never reaches fails the test instead of passing unnoticed. That is what makes
 *       "the date service was never called" and "the write was never attempted" provable rather than
 *       asserted.</li>
 *   <li><strong>An injected fixed clock.</strong> {@link FixedClockProvider#canonicalClock()} pins time to
 *       {@code 2022-06-10T19:27:53Z}, deliberately a different date from every request date used here, so the
 *       claim that the persisted timestamps derive only from the request is falsifiable. No current-instant,
 *       current-date, system-clock, system-zone or unseeded-random lookup appears anywhere in this file.</li>
 *   <li><strong>{@code BigDecimal} only.</strong> Amounts are compared with {@code compareTo} rather than
 *       {@code equals} so that scale is asserted separately from value, and no {@code float} or
 *       {@code double} appears in any financial assertion. Rounding is
 *       {@link TransactionAddRequest#AMOUNT_ROUNDING_MODE}, which is {@code HALF_EVEN}.</li>
 *   <li><strong>Full BMS field widths.</strong> Every request value is supplied at its declared symbolic map
 *       width, because {@code app/bms/COTRN02.bms} declares no {@code ATTRB=NUM} and a 3270 therefore
 *       transmits a short field space-padded, which the COBOL numeric class test rejects.</li>
 *   </ul>
 *
 * <h2>4. Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><strong>"warnings found and -Werror specified".</strong> One raw type, one unchecked cast or one
 *       documentation comment attached to no declaration is enough. Fix the warning; never add
 *       {@code @SuppressWarnings} and never relax the compiler configuration. Neither an unused import nor a
 *       malformed Javadoc tag is caught here - {@code javac} 25 has no {@code unused} lint key and does not
 *       validate Javadoc - so those are enforced by review and by the separate doclint command.</li>
 *   <li><strong>Using one numeric parser for identifiers and amounts.</strong> The accepted input sets are
 *       disjoint, so a shared parser changes the outcome of every request. Proved disjoint by
 *       {@link NumericParserContract}.</li>
 *   <li><strong>Widening the {@code +99999999.99} mask.</strong> The truncation is the contract. Widening it
 *       makes {@link AmountDisplayMask} pass for the wrong reason and diverges from the legacy echo.</li>
 *   <li><strong>Formatting or generating the pass-through timestamps.</strong> Any clock reaching this path
 *       breaks {@link PassThroughTimestamps}; the persisted value must be the request's ten characters plus
 *       sixteen spaces.</li>
 *   <li><strong>Substituting a database sequence for the descending browse.</strong> It removes the race but
 *       changes generated values, which breaks the parity comparison against the legacy baseline.</li>
 *   <li><strong>The fixture-name trap.</strong> The mainframe dataset is {@code DALYTRAN} but the ASCII
 *       fixture is spelled {@code dailytran.txt} in full, never {@code dalytran.txt}. No fixture is read by
 *       this class, so the trap is recorded rather than encountered here.</li>
 *   </ul>
 *
 * <h2>5. Findings, by severity, with remediation</h2>
 *
 * <p>Three of these are corrections to the brief this class was written from. The COBOL corpus is the
 * authority, so where the two disagree the source governs and the divergence is recorded rather than
 * silently absorbed.</p>
 *
 * <ul>
 *   <li><strong>Blocker - one numeric parser for both field classes.</strong> Evidence:
 *       {@code app/cbl/COTRN02C.cbl:204} and {@code :218} use {@code FUNCTION NUMVAL} while {@code :383} and
 *       {@code :456} use {@code FUNCTION NUMVAL-C}. Remediation: keep the two converters distinct and keep
 *       each behind its own guard, as {@link NumericParserContract} pins.</li>
 *   <li><strong>High - a generated or formatted timestamp on this path.</strong> Evidence: {@code :464-:465}
 *       are plain alphanumeric moves. Remediation: store the request text padded to twenty-six characters and
 *       hold no {@code Clock} on the bean, as {@link PassThroughTimestamps} pins.</li>
 *   <li><strong>High - a sequence or retry behind identifier generation.</strong> Evidence: {@code :444-:449}
 *       plus the {@code DUPKEY}/{@code DUPREC} branch at {@code :735-:736}. Remediation: retain the browse and
 *       let the primary key report the collision, as {@link IdentifierGeneration} pins.</li>
 *   <li><strong>Medium - the brief's claim that both date-format messages reposition the cursor to the
 *       processing-date field is not what the source does.</strong> Evidence: {@code :362} moves {@code -1}
 *       into {@code TORIGDTL} for {@code 'Orig Date should be in format YYYY-MM-DD'} and {@code :377} moves
 *       {@code -1} into {@code TPROCDTL} for {@code 'Proc Date should be in format YYYY-MM-DD'}. There is no
 *       copy-paste defect: each message repositions to its own field. Remediation: assert the source
 *       behaviour, which {@link PositionalDateGuards} does, and treat the brief's note as superseded.</li>
 *   <li><strong>Medium - the brief's expectation that {@code "1,234.56"} and {@code "$1234.56"} parse
 *       successfully as an amount is unreachable through this service.</strong> Evidence: the positional mask
 *       at {@code :339-:351} requires a sign at position one, eight digits at {@code (2:8)}, a point at
 *       position ten and two digits at {@code (11:2)}, and it runs before {@code FUNCTION NUMVAL-C} at
 *       {@code :383} ever sees the field. Remediation: assert that the guard rejects both spellings with
 *       {@code 'Amount should be in format -99999999.99'}, and leave the converter's currency tolerance to be
 *       asserted where that converter is declared rather than duplicating it here.</li>
 *   <li><strong>Medium - the eight versus nine digit truncation is not reachable from keyed input.</strong>
 *       Evidence: {@code TRNAMTI} is {@code PIC X(12)} at {@code app/cpy-bms/COTRN02.CPY:96}, so the mask
 *       admits at most eight integer digits. It is reachable on the PF5 prefill path, where {@code :481}
 *       moves a stored {@code TRAN-AMT S9(09)V99} straight into the edited field. Remediation: assert the
 *       truncation there, which {@link AmountDisplayMask} does.</li>
 *   <li><strong>Low - the second {@code FUNCTION NUMVAL-C} parse at {@code :456} is redundant.</strong> The
 *       value was already parsed at {@code :383}. It is retained rather than collapsed because collapsing it
 *       would break the paragraph map the coverage gate verifies. Remediation: none; the retention is
 *       tracked, and {@link PassThroughTimestamps} asserts both parses occur.</li>
 *   <li><strong>Low - {@code MOVE SPACES TO CSUTLDTC-RESULT} at {@code :392} rather than
 *       {@code INITIALIZE}.</strong> The Java counterpart returns a fresh immutable result per call, which
 *       clears the buffer by construction. Remediation: none; asserted by independence of the two calls.</li>
 *   <li><strong>Low - a residue of branches that no reachable path can enter.</strong> They are the reason
 *       this class cannot drive its target to full line coverage, and each is intentional rather than
 *       missing: the stage-one defensive blanking of {@code VALIDATE-INPUT-DATA-FIELDS}, unreachable because
 *       every assignment of {@code 'Y'} to {@code WS-ERR-FLG} is immediately followed by
 *       {@code PERFORM SEND-TRNADD-SCREEN}, which ends the task; the not-found and I/O arms of
 *       {@code STARTBR-TRANSACT-FILE} at {@code :655-:667}, unreachable because a browse over a keyed
 *       dataset positioned at {@code HIGH-VALUES} cannot itself fail in the target; the
 *       {@code CDEMO-TO-PROGRAM} fallback of {@code RETURN-TO-PREV-SCREEN} at {@code :502-:503}, unreachable
 *       because both callers set a target first; the record-contract abend guard, unreachable because the
 *       ten-stage cascade admits nothing the {@code CVTRA05Y} contract rejects; the no-cause arms of the two
 *       failure factories, unreachable because every data-access failure carries its cause; and the
 *       out-of-range arms of the fixed-width character helpers, unreachable because every caller passes a
 *       value already taken to its declared width. Remediation: none. Deleting any of them would break the
 *       paragraph body correspondence the coverage gate verifies, and reaching them from a test would need
 *       reflection into private state, which would assert the test's own setup rather than the bean's
 *       behaviour.</li>
 *   </ul>
 *
 * <h2>6. Not available</h2>
 *
 * <p>Whether {@code STARTBR-TRANSACT-FILE}'s not-found arm at {@code :651-:659} or
 * {@code READPREV-TRANSACT-FILE}'s end-of-file arm at {@code :685-:690} fires for a given store state is
 * <strong>Not available</strong> from the corpus: it depends on the browse-positioning semantics of the
 * legacy access method, and settling it would need a running CICS region over a real VSAM cluster. Settling
 * it is therefore not attempted, and the two arms are asserted independently rather than merged.</p>
 *
 * <p>No service-level objective for the transaction-add path is published anywhere in the corpus, so none is
 * asserted or invented. What would be needed to close that item is a measured baseline from the performance
 * gate.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.STRICT_STUBS)
@DisplayName("TransactionAddService - COTRN02C / transaction CT02")
final class TransactionAddServiceTest {

    // ------------------------------------------------------------------------------------------------
    // Symbolic map field values, each filled to its declared width in app/cpy-bms/COTRN02.CPY, because
    // app/bms/COTRN02.bms declares no ATTRB=NUM and a 3270 therefore transmits a short field
    // space-padded, which the COBOL numeric class test rejects.
    // ------------------------------------------------------------------------------------------------

    /** {@code ACTIDINI PIC X(11)}, {@code app/cpy-bms/COTRN02.CPY:60}. */
    private static final String ACCOUNT_ID_TEXT = "00000000011";

    /** The same identifier as the repository sees it, per {@code XREF-ACCT-ID PIC 9(11)}. */
    private static final Long ACCOUNT_ID = 11L;

    /**
     * A synthetic sixteen-digit stand-in for {@code XREF-CARD-NUM PIC X(16)}.
     *
     * <p>It exists to prove that a card number reaches {@code TRAN-CARD-NUM} and reaches nothing else. It is
     * asserted by identity against the projected field only and is never embedded in an assertion
     * description, so a failure report cannot disclose it. The leading digit is deliberately {@code 9}: no
     * card network issues an account number in the {@code 9} range, so the value cannot match a real
     * primary-account-number pattern while still honouring the declared width.</p>
     */
    private static final String SYNTHETIC_CARD_NUMBER = "9999888877776666";

    /** {@code TTYPCDI PIC X(2)}, {@code app/cpy-bms/COTRN02.CPY:72}. */
    private static final String TYPE_CODE_TEXT = "01";

    /** {@code TCATCDI PIC X(4)}, {@code app/cpy-bms/COTRN02.CPY:78}. */
    private static final String CATEGORY_CODE_TEXT = "0005";

    /** {@code TRNSRCI PIC X(10)}, {@code app/cpy-bms/COTRN02.CPY:84}, filled to ten characters. */
    private static final String SOURCE_TEXT = "POS       ";

    /** {@code TDESCI PIC X(60)}, {@code app/cpy-bms/COTRN02.CPY:90}. */
    private static final String DESCRIPTION_TEXT = "GROUND COFFEE";

    /** {@code TRNAMTI PIC X(12)}, {@code app/cpy-bms/COTRN02.CPY:96}, on the edited mask of {@code :59}. */
    private static final String AMOUNT_TEXT = "+00001234.56";

    /** The value {@code FUNCTION NUMVAL-C} yields from {@link #AMOUNT_TEXT}, at scale two. */
    private static final BigDecimal AMOUNT_VALUE = new BigDecimal("1234.56");

    /**
     * {@code TORIGDTI PIC X(10)}, {@code app/cpy-bms/COTRN02.CPY:102}.
     *
     * <p>Deliberately a different date from {@link FixedClockProvider#CANONICAL_INSTANT}, so that "the
     * timestamp came from the request and not from a clock" is a falsifiable claim rather than a
     * coincidence.</p>
     */
    private static final String ORIGINATING_DATE_TEXT = "2023-04-15";

    /**
     * {@code TPROCDTI PIC X(10)}, {@code app/cpy-bms/COTRN02.CPY:108}, deliberately different again so that
     * the two pass-through moves of {@code :464-:465} cannot be confused with one another.
     */
    private static final String PROCESSING_DATE_TEXT = "2023-04-16";

    /** {@code MIDI PIC X(9)}, {@code app/cpy-bms/COTRN02.CPY:114}. */
    private static final String MERCHANT_ID_TEXT = "000000123";

    /** {@code MNAMEI PIC X(30)}, {@code app/cpy-bms/COTRN02.CPY:120}. */
    private static final String MERCHANT_NAME_TEXT = "ACME SUPPLY";

    /** {@code MCITYI PIC X(25)}, {@code app/cpy-bms/COTRN02.CPY:126}. */
    private static final String MERCHANT_CITY_TEXT = "SEATTLE";

    /** {@code MZIPI PIC X(10)}, {@code app/cpy-bms/COTRN02.CPY:132}. */
    private static final String MERCHANT_ZIP_TEXT = "98101-0001";

    /**
     * {@code CURDATEI PIC X(8)}, {@code app/cpy-bms/COTRN02.CPY:36}. The header date arrives on the request
     * and is echoed by {@code POPULATE-HEADER-INFO}; it is never read from a clock, which is what makes this
     * value assertable at all.
     */
    private static final String CURRENT_DATE_TEXT = "04/15/23";

    /** {@code CURTIMEI PIC X(8)}, {@code app/cpy-bms/COTRN02.CPY:54}. Also request-carried, never generated. */
    private static final String CURRENT_TIME_TEXT = "14:30:00";

    /**
     * The placeholder the bean substitutes for a sixteen-digit primary account number before that number
     * could reach a log line, a screen message or a reported failure key. Clause D of the project rule
     * forbids the real value leaving the bean, so the placeholder itself is the assertable contract.
     */
    private static final String CARD_NUMBER_REDACTION = "<redacted-16-digit-card-number>";

    /** {@code CONFIRMI = 'Y'}, the arm of {@code app/cbl/COTRN02C.cbl:174} that performs the add. */
    private static final String CONFIRM_YES = "Y";

    /** A blank {@code CONFIRMI}, the prompt arm of {@code app/cbl/COTRN02C.cbl:181-183}. */
    private static final String CONFIRM_BLANK = " ";

    /** {@code CDEMO-FROM-PROGRAM}, the caller the return path navigates back to. */
    private static final String ORIGIN_PROGRAM = "COMEN01C";

    /**
     * The literal {@code 'COSGN00C'} moved to {@code CDEMO-TO-PROGRAM} on the {@code EIBCALEN = 0} arm at
     * {@code app/cbl/COTRN02C.cbl:115-116}, and the fallback {@code RETURN-TO-PREV-SCREEN} substitutes at
     * {@code :502-503} when no target was carried.
     */
    private static final String SIGN_ON_PROGRAM = "COSGN00C";

    /** {@code WS-DATE-FORMAT PIC X(10) VALUE 'YYYY-MM-DD'}, {@code app/cbl/COTRN02C.cbl:60}. */
    private static final String DATE_FORMAT = TransactionAddRequest.DATE_VALIDATION_FORMAT;

    // ------------------------------------------------------------------------------------------------
    // Byte-exact screen literals. Every one is compared character for character, because the parity gates
    // compare them against the legacy baseline and a reworded, retrimmed or recased variant is a diff.
    // ------------------------------------------------------------------------------------------------

    /** {@code app/cbl/COTRN02C.cbl:199-200}. */
    private static final String MSG_ACCOUNT_ID_NOT_NUMERIC = "Account ID must be Numeric...";

    /** {@code app/cbl/COTRN02C.cbl:213-214}. */
    private static final String MSG_CARD_NUMBER_NOT_NUMERIC = "Card Number must be Numeric...";

    /** {@code app/cbl/COTRN02C.cbl:226-227}, the {@code WHEN OTHER} arm. */
    private static final String MSG_KEY_REQUIRED = "Account or Card Number must be entered...";

    /** {@code app/cbl/COTRN02C.cbl:325-326}. */
    private static final String MSG_TYPE_CODE_NOT_NUMERIC = "Type CD must be Numeric...";

    /** {@code app/cbl/COTRN02C.cbl:331-332}. */
    private static final String MSG_CATEGORY_CODE_NOT_NUMERIC = "Category CD must be Numeric...";

    /** {@code app/cbl/COTRN02C.cbl:345-346}. The mask carries exactly eight integer digits. */
    private static final String MSG_AMOUNT_FORMAT = "Amount should be in format -99999999.99";

    /** {@code app/cbl/COTRN02C.cbl:360-361}. */
    private static final String MSG_ORIGINATING_DATE_FORMAT = "Orig Date should be in format YYYY-MM-DD";

    /** {@code app/cbl/COTRN02C.cbl:375-376}. */
    private static final String MSG_PROCESSING_DATE_FORMAT = "Proc Date should be in format YYYY-MM-DD";

    /** {@code app/cbl/COTRN02C.cbl:401-402}. */
    private static final String MSG_ORIGINATING_DATE_INVALID = "Orig Date - Not a valid date...";

    /** {@code app/cbl/COTRN02C.cbl:421-422}. */
    private static final String MSG_PROCESSING_DATE_INVALID = "Proc Date - Not a valid date...";

    /** {@code app/cbl/COTRN02C.cbl:432-433}. */
    private static final String MSG_MERCHANT_ID_NOT_NUMERIC = "Merchant ID must be Numeric...";

    /** {@code app/cbl/COTRN02C.cbl:254-255}. */
    private static final String MSG_TYPE_CODE_EMPTY = "Type CD can NOT be empty...";

    /** {@code app/cbl/COTRN02C.cbl:278-279}. */
    private static final String MSG_AMOUNT_EMPTY = "Amount can NOT be empty...";

    /** {@code app/cbl/COTRN02C.cbl:284-285}. */
    private static final String MSG_ORIGINATING_DATE_EMPTY = "Orig Date can NOT be empty...";

    /** {@code app/cbl/COTRN02C.cbl:290-291}. */
    private static final String MSG_PROCESSING_DATE_EMPTY = "Proc Date can NOT be empty...";

    /** {@code app/cbl/COTRN02C.cbl:260-261}. */
    private static final String MSG_CATEGORY_CODE_EMPTY = "Category CD can NOT be empty...";

    /** {@code app/cbl/COTRN02C.cbl:266-267}. */
    private static final String MSG_SOURCE_EMPTY = "Source can NOT be empty...";

    /** {@code app/cbl/COTRN02C.cbl:272-273}. */
    private static final String MSG_DESCRIPTION_EMPTY = "Description can NOT be empty...";

    /** {@code app/cbl/COTRN02C.cbl:296-297}. */
    private static final String MSG_MERCHANT_ID_EMPTY = "Merchant ID can NOT be empty...";

    /** {@code app/cbl/COTRN02C.cbl:302-303}. */
    private static final String MSG_MERCHANT_NAME_EMPTY = "Merchant Name can NOT be empty...";

    /** {@code app/cbl/COTRN02C.cbl:308-309}. */
    private static final String MSG_MERCHANT_CITY_EMPTY = "Merchant City can NOT be empty...";

    /** {@code app/cbl/COTRN02C.cbl:314-315}. */
    private static final String MSG_MERCHANT_ZIP_EMPTY = "Merchant Zip can NOT be empty...";

    /** {@code app/cbl/COTRN02C.cbl:178-179}, phase one of the two-phase handshake. */
    private static final String MSG_CONFIRMATION_REQUIRED = "Confirm to add this transaction...";

    /** {@code app/cbl/COTRN02C.cbl:184-185}. */
    private static final String MSG_CONFIRMATION_INVALID = "Invalid value. Valid values are (Y/N)...";

    /** {@code app/cbl/COTRN02C.cbl:593-594}. */
    private static final String MSG_ACCOUNT_NOT_FOUND = "Account ID NOT found...";

    /** {@code app/cbl/COTRN02C.cbl:600-601}. */
    private static final String MSG_CXACAIX_FAILURE = "Unable to lookup Acct in XREF AIX file...";

    /** {@code app/cbl/COTRN02C.cbl:626-627}. */
    private static final String MSG_CARD_NOT_FOUND = "Card Number NOT found...";

    /** {@code app/cbl/COTRN02C.cbl:633-634}. */
    private static final String MSG_CCXREF_FAILURE = "Unable to lookup Card # in XREF file...";

    /** {@code app/cbl/COTRN02C.cbl:693-694}, the {@code READPREV} failure arm. */
    private static final String MSG_TRANSACTION_LOOKUP_FAILURE = "Unable to lookup Transaction...";

    /** {@code app/cbl/COTRN02C.cbl:738-739}, the shared {@code DUPKEY}/{@code DUPREC} branch. */
    private static final String MSG_DUPLICATE_TRANSACTION = "Tran ID already exist...";

    /** {@code app/cbl/COTRN02C.cbl:745-746}. */
    private static final String MSG_ADD_FAILURE = "Unable to Add Transaction...";

    /** {@code app/cpy/CSMSG01Y.cpy:20-21}, used at {@code app/cbl/COTRN02C.cbl:150}. */
    private static final String MSG_INVALID_KEY = "Invalid key pressed. Please see below...";

    // ------------------------------------------------------------------------------------------------
    // Cursor targets: the symbolic map length field the source drives to -1 so the terminal places the
    // cursor there. Each is asserted alongside its message, because the pair is the observable outcome.
    // ------------------------------------------------------------------------------------------------

    /** {@code ACTIDINL}, driven at {@code app/cbl/COTRN02C.cbl:201}, {@code :228} and {@code :595}. */
    private static final String CURSOR_ACCOUNT_ID = "ACTIDINL";

    /** {@code CARDNINL}, driven at {@code app/cbl/COTRN02C.cbl:215}, {@code :628} and {@code :635}. */
    private static final String CURSOR_CARD_NUMBER = "CARDNINL";

    /** {@code TTYPCDL}, driven at {@code app/cbl/COTRN02C.cbl:256} and {@code :327}. */
    private static final String CURSOR_TYPE_CODE = "TTYPCDL";

    /** {@code TCATCDL}, driven at {@code app/cbl/COTRN02C.cbl:262} and {@code :333}. */
    private static final String CURSOR_CATEGORY_CODE = "TCATCDL";

    /** {@code TRNAMTL}, driven at {@code app/cbl/COTRN02C.cbl:280} and {@code :347}. */
    private static final String CURSOR_AMOUNT = "TRNAMTL";

    /**
     * {@code TORIGDTL}, driven at {@code app/cbl/COTRN02C.cbl:286}, {@code :362} and {@code :404}.
     *
     * <p>The brief this class was written from claims that the originating-date format failure repositions to
     * the <em>processing</em>-date field as a copy-paste defect. It does not: {@code :362} names
     * {@code TORIGDTL}. The source governs and the divergence is recorded in the class documentation.</p>
     */
    private static final String CURSOR_ORIGINATING_DATE = "TORIGDTL";

    /** {@code TPROCDTL}, driven at {@code app/cbl/COTRN02C.cbl:292}, {@code :377} and {@code :424}. */
    private static final String CURSOR_PROCESSING_DATE = "TPROCDTL";

    /** {@code MIDL}, driven at {@code app/cbl/COTRN02C.cbl:298} and {@code :434}. */
    private static final String CURSOR_MERCHANT_ID = "MIDL";

    /** {@code CONFIRML}, driven at {@code app/cbl/COTRN02C.cbl:180} and {@code :186}. */
    private static final String CURSOR_CONFIRMATION = "CONFIRML";

    // ------------------------------------------------------------------------------------------------
    // The CSUTLDTC parameter block of app/cbl/COTRN02C.cbl:62-69, whose eighty bytes decompose as
    // SEV-CD X(04) + FILLER X(11) + MSG-NUM X(04) + MSG X(61). Only the first four bytes gate acceptance.
    // ------------------------------------------------------------------------------------------------

    /** {@code CSUTLDTC-RESULT} is eighty characters, {@code app/cbl/COTRN02C.cbl:65-69}. */
    private static final int RESULT_LENGTH = 80;

    /** {@code FILLER PIC X(11)}, {@code app/cbl/COTRN02C.cbl:67}. */
    private static final String RESULT_FILLER = " ".repeat(11);

    /** {@code CSUTLDTC-RESULT-MSG PIC X(61)}, {@code app/cbl/COTRN02C.cbl:69}, left blank throughout. */
    private static final String RESULT_MESSAGE = " ".repeat(61);

    /** The accept token tested at {@code app/cbl/COTRN02C.cbl:397} and {@code :417}. */
    private static final String SEVERITY_ACCEPTED = "0000";

    /** A rejecting severity, any value other than the accept token. */
    private static final String SEVERITY_REJECTED = "0008";

    /** A message number that is not the tolerated one, so the {@code :399} escape does not apply. */
    private static final String MESSAGE_NUMBER_REJECTED = "2521";

    /** {@code IF CSUTLDTC-RESULT-MSG-NUM NOT = '2513'}, {@code app/cbl/COTRN02C.cbl:399} and {@code :419}. */
    private static final String MESSAGE_NUMBER_TOLERATED = "2513";

    /** A neutral message number for an accepted outcome. */
    private static final String MESSAGE_NUMBER_NONE = "0000";

    // ------------------------------------------------------------------------------------------------
    // Fixed-width record geometry of app/cpy/CVTRA05Y.cpy, used to build stored rows and to assert the
    // widening moves of ADD-TRANSACTION.
    // ------------------------------------------------------------------------------------------------

    /** {@code TRAN-ID PIC X(16)}, record bytes 1-16. */
    private static final int TRANSACTION_ID_WIDTH = 16;

    /** {@code TRAN-DESC PIC X(100)}, record bytes 33-132. */
    private static final int DESCRIPTION_RECORD_WIDTH = 100;

    /** {@code TRAN-MERCHANT-NAME PIC X(50)}, record bytes 153-202. */
    private static final int MERCHANT_NAME_RECORD_WIDTH = 50;

    /** {@code TRAN-MERCHANT-CITY PIC X(50)}, record bytes 203-252. */
    private static final int MERCHANT_CITY_RECORD_WIDTH = 50;

    /** {@code TRAN-ORIG-TS} and {@code TRAN-PROC-TS PIC X(26)}, record bytes 279-304 and 305-330. */
    private static final int TIMESTAMP_WIDTH = FixedClockProvider.TIMESTAMP_LENGTH;

    /** {@code TORIGDTI} and {@code TPROCDTI} are {@code PIC X(10)}; the widening move pads by sixteen. */
    private static final int TIMESTAMP_PAD_WIDTH = 16;

    /**
     * The eighteen paragraph labels of {@code app/cbl/COTRN02C.cbl}, in source order, each paired with the
     * private Java method that must reproduce it one for one. The list is the machine-checkable half of the
     * traceability matrix: a consolidation anywhere in the bean makes it fail.
     */
    private static final List<String> PARAGRAPH_METHODS = List.of(
            "mainPara",                  // MAIN-PARA.                   :107
            "processEnterKey",           // PROCESS-ENTER-KEY.           :164
            "validateInputKeyFields",    // VALIDATE-INPUT-KEY-FIELDS.   :193
            "validateInputDataFields",   // VALIDATE-INPUT-DATA-FIELDS.  :235
            "addTransaction",            // ADD-TRANSACTION.             :442
            "copyLastTranData",          // COPY-LAST-TRAN-DATA.         :471
            "returnToPrevScreen",        // RETURN-TO-PREV-SCREEN.       :500
            "sendTrnaddScreen",          // SEND-TRNADD-SCREEN.          :516
            "receiveTrnaddScreen",       // RECEIVE-TRNADD-SCREEN.       :539
            "populateHeaderInfo",        // POPULATE-HEADER-INFO.        :552
            "readCxacaixFile",           // READ-CXACAIX-FILE.           :576
            "readCcxrefFile",            // READ-CCXREF-FILE.            :609
            "startbrTransactFile",       // STARTBR-TRANSACT-FILE.       :642
            "readprevTransactFile",      // READPREV-TRANSACT-FILE.      :673
            "endbrTransactFile",         // ENDBR-TRANSACT-FILE.         :702
            "writeTransactFile",         // WRITE-TRANSACT-FILE.         :711
            "clearCurrentScreen",        // CLEAR-CURRENT-SCREEN.        :754
            "initializeAllFields");      // INITIALIZE-ALL-FIELDS.       :762

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private CardCrossReferenceRepository cardCrossReferenceRepository;

    @Mock
    private DateValidationService dateValidationService;

    /**
     * Builds the bean under test.
     *
     * <p>{@link FileStatusMapper} is a real instance rather than a mock because it is a pure function object
     * over the two-character file status and mocking it would assert nothing. The same reasoning applies to
     * the two numeric converters and the edited-amount printer: they are the real published beans, taken
     * from {@link WebConfig} exactly as Spring would supply them, because substituting a double for a pure
     * parser would assert nothing and would let the test pass against parsing rules the application does
     * not use. The three collaborators that perform I/O are mocks under strict stubs.</p>
     *
     * @return the service, never {@code null}
     */
    private TransactionAddService service() {
        final WebConfig webConfig = new WebConfig();
        return new TransactionAddService(this.transactionRepository, this.cardCrossReferenceRepository,
                this.dateValidationService, new FileStatusMapper(),
                webConfig.strictIdentifierConverter(), webConfig.currencyAwareAmountConverter(),
                webConfig.editedAmountPrinter());
    }

    // ------------------------------------------------------------------------------------------------
    // Fixture construction
    // ------------------------------------------------------------------------------------------------

    /**
     * A mutable stand-in for the twenty-one field symbolic map, so that a test varies exactly the one field
     * it is about and inherits a valid value for the other twenty.
     *
     * <p>It is an instance-free nested type with no static mutable state: every test constructs its own, in
     * keeping with the injection-over-global-state clause of the project rule.</p>
     */
    private static final class RequestBuilder {

        private String transactionName = "";
        private String title01 = "";
        private String currentDate = "";
        private String programName = "";
        private String title02 = "";
        private String currentTime = "";
        private String accountId = ACCOUNT_ID_TEXT;
        private String cardNumber = "";
        private String typeCode = TYPE_CODE_TEXT;
        private String categoryCode = CATEGORY_CODE_TEXT;
        private String source = SOURCE_TEXT;
        private String description = DESCRIPTION_TEXT;
        private String amount = AMOUNT_TEXT;
        private String originatingDate = ORIGINATING_DATE_TEXT;
        private String processingDate = PROCESSING_DATE_TEXT;
        private String merchantId = MERCHANT_ID_TEXT;
        private String merchantName = MERCHANT_NAME_TEXT;
        private String merchantCity = MERCHANT_CITY_TEXT;
        private String merchantZip = MERCHANT_ZIP_TEXT;
        private String confirmation = CONFIRM_YES;
        private String errorMessage = "";

        private RequestBuilder accountId(final String value) {
            this.accountId = value;
            return this;
        }

        private RequestBuilder cardNumber(final String value) {
            this.cardNumber = value;
            return this;
        }

        private RequestBuilder typeCode(final String value) {
            this.typeCode = value;
            return this;
        }

        private RequestBuilder categoryCode(final String value) {
            this.categoryCode = value;
            return this;
        }

        private RequestBuilder source(final String value) {
            this.source = value;
            return this;
        }

        private RequestBuilder description(final String value) {
            this.description = value;
            return this;
        }

        private RequestBuilder amount(final String value) {
            this.amount = value;
            return this;
        }

        private RequestBuilder originatingDate(final String value) {
            this.originatingDate = value;
            return this;
        }

        private RequestBuilder processingDate(final String value) {
            this.processingDate = value;
            return this;
        }

        private RequestBuilder merchantId(final String value) {
            this.merchantId = value;
            return this;
        }

        private RequestBuilder merchantName(final String value) {
            this.merchantName = value;
            return this;
        }

        private RequestBuilder merchantCity(final String value) {
            this.merchantCity = value;
            return this;
        }

        private RequestBuilder merchantZip(final String value) {
            this.merchantZip = value;
            return this;
        }

        private RequestBuilder confirmation(final String value) {
            this.confirmation = value;
            return this;
        }

        private RequestBuilder header(final String date, final String time) {
            this.currentDate = date;
            this.currentTime = time;
            return this;
        }

        private RequestBuilder programName(final String value) {
            this.programName = value;
            return this;
        }

        private TransactionAddRequest build() {
            return new TransactionAddRequest(this.transactionName, this.title01, this.currentDate,
                    this.programName, this.title02, this.currentTime, this.accountId, this.cardNumber,
                    this.typeCode, this.categoryCode, this.source, this.description, this.amount,
                    this.originatingDate, this.processingDate, this.merchantId, this.merchantName,
                    this.merchantCity, this.merchantZip, this.confirmation, this.errorMessage);
        }
    }

    /**
     * @return a builder seeded with a request that passes every validation stage of
     *         {@code VALIDATE-INPUT-KEY-FIELDS} and {@code VALIDATE-INPUT-DATA-FIELDS}
     */
    private static RequestBuilder request() {
        return new RequestBuilder();
    }

    /**
     * Builds a cross-reference row as {@code app/cpy/CVACT03Y.cpy} lays it out: a sixteen character card
     * number, a nine digit customer identifier and an eleven digit account identifier.
     *
     * @return the row the {@code CXACAIX} and {@code CCXREF} reads return
     */
    private static CardCrossReference crossReference() {
        return crossReference(SYNTHETIC_CARD_NUMBER);
    }

    /**
     * Builds a cross-reference row keyed on a caller supplied card number, for the {@code CCXREF} primary key
     * read of {@code READ-CCXREF-FILE}.
     *
     * @param cardNumber the sixteen character {@code XREF-CARD-NUM}
     * @return the row the read returns
     */
    private static CardCrossReference crossReference(final String cardNumber) {
        return new CardCrossReference(cardNumber, 1L, ACCOUNT_ID);
    }

    /**
     * Composes the eighty byte {@code CSUTLDTC-RESULT} area of {@code app/cbl/COTRN02C.cbl:65-69} from its
     * two significant sub-fields, leaving the eleven byte filler and the sixty-one byte message blank.
     *
     * @param severityCode the four character {@code CSUTLDTC-RESULT-SEV-CD} the caller tests at {@code :397}
     * @param messageNumber the four character {@code CSUTLDTC-RESULT-MSG-NUM} tested at {@code :399}
     * @return an accept-or-reject verdict carrying exactly eighty characters
     */
    private static DateValidationService.DateValidationResult dateVerdict(final String severityCode,
            final String messageNumber) {
        final String composed = severityCode + RESULT_FILLER + messageNumber + RESULT_MESSAGE;
        assertThat(composed)
                .as("the CSUTLDTC result area is eighty bytes, app/cbl/COTRN02C.cbl:65-69")
                .hasSize(RESULT_LENGTH);
        final int severity = Integer.parseInt(severityCode);
        return new DateValidationService.DateValidationResult(
                new DateValidationService.FeedbackCode(severity, Integer.parseInt(messageNumber)), composed);
    }

    /**
     * @return the verdict that satisfies {@code IF CSUTLDTC-RESULT-SEV-CD = '0000'}
     */
    private static DateValidationService.DateValidationResult acceptedDate() {
        return dateVerdict(SEVERITY_ACCEPTED, MESSAGE_NUMBER_NONE);
    }

    /**
     * @return a verdict whose severity is not the accept token and whose message number is not the tolerated
     *         one, so both the {@code :397} and the {@code :399} escapes fail
     */
    private static DateValidationService.DateValidationResult rejectedDate() {
        return dateVerdict(SEVERITY_REJECTED, MESSAGE_NUMBER_REJECTED);
    }

    /**
     * Stubs both {@code CALL 'CSUTLDTC'} sites, {@code app/cbl/COTRN02C.cbl:393} and {@code :413}, to accept.
     */
    private void acceptBothDates() {
        when(this.dateValidationService.validate(ORIGINATING_DATE_TEXT, DATE_FORMAT))
                .thenReturn(acceptedDate());
        when(this.dateValidationService.validate(PROCESSING_DATE_TEXT, DATE_FORMAT))
                .thenReturn(acceptedDate());
    }

    /**
     * Stubs the {@code CXACAIX} browse of {@code READ-CXACAIX-FILE} to return one row.
     */
    private void resolveAccountBranch() {
        when(this.cardCrossReferenceRepository.findFirstByAccountIdOrderByCardNumberAsc(ACCOUNT_ID))
                .thenReturn(Optional.of(crossReference()));
    }

    /**
     * Stubs {@code READPREV-TRANSACT-FILE} to report an empty file, the {@code DFHRESP(ENDFILE)} arm of
     * {@code app/cbl/COTRN02C.cbl:682-684}.
     */
    private void emptyTransactionFile() {
        when(this.transactionRepository.findFirstByOrderByTransactionIdDesc()).thenReturn(Optional.empty());
    }

    /**
     * Stubs {@code WRITE-TRANSACT-FILE} to succeed, returning the row it was handed.
     */
    private void acceptTheWrite() {
        when(this.transactionRepository.saveAndFlush(any(Transaction.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    /**
     * Builds a stored {@code TRAN-RECORD} for the {@code READPREV} arm and for the PF5 prefill of
     * {@code COPY-LAST-TRAN-DATA}, honouring every width of {@code app/cpy/CVTRA05Y.cpy}.
     *
     * @param transactionIdentifier the sixteen character {@code TRAN-ID}
     * @param amount the {@code TRAN-AMT S9(09)V99} value, at scale two or less
     * @return the row, never {@code null}
     */
    private static Transaction storedTransaction(final String transactionIdentifier, final BigDecimal amount) {
        return new Transaction(transactionIdentifier,
                TYPE_CODE_TEXT,
                Integer.valueOf(CATEGORY_CODE_TEXT),
                SOURCE_TEXT,
                pad(DESCRIPTION_TEXT, DESCRIPTION_RECORD_WIDTH),
                amount,
                Long.valueOf(MERCHANT_ID_TEXT),
                pad(MERCHANT_NAME_TEXT, MERCHANT_NAME_RECORD_WIDTH),
                pad(MERCHANT_CITY_TEXT, MERCHANT_CITY_RECORD_WIDTH),
                MERCHANT_ZIP_TEXT,
                SYNTHETIC_CARD_NUMBER,
                pad(ORIGINATING_DATE_TEXT, TIMESTAMP_WIDTH),
                pad(PROCESSING_DATE_TEXT, TIMESTAMP_WIDTH));
    }

    /**
     * Right pads with spaces exactly as a COBOL {@code MOVE} to a wider alphanumeric field does.
     *
     * @param value the source value
     * @param width the receiving field width
     * @return {@code value} padded to {@code width} characters
     */
    private static String pad(final String value, final int width) {
        return value + " ".repeat(width - value.length());
    }

    /**
     * Captures the row handed to {@code saveAndFlush}, being the Java form of the {@code EXEC CICS WRITE} at
     * {@code app/cbl/COTRN02C.cbl:713-721}.
     *
     * @return the persisted row
     */
    private Transaction capturePersistedRow() {
        final ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(this.transactionRepository).saveAndFlush(captor.capture());
        return captor.getValue();
    }

    /**
     * Drives the confirmed add path end to end over the account branch, with both dates accepted, an empty
     * transaction file and an accepting write.
     *
     * @param mutated the request to submit
     * @return the result of {@code MAIN-PARA} for the {@code DFHENTER} arm
     */
    private TransactionAddResult addConfirmed(final TransactionAddRequest mutated) {
        resolveAccountBranch();
        acceptBothDates();
        emptyTransactionFile();
        acceptTheWrite();
        return service().submitScreen(AttentionIdentifier.ENTER, mutated, ORIGIN_PROGRAM);
    }

    /**
     * Drives the same path but with a blank confirmation, so {@code PROCESS-ENTER-KEY} stops at the prompt of
     * {@code app/cbl/COTRN02C.cbl:181-183} with the screen intact. This is the only way to observe the echoed
     * screen fields, because the confirmed path runs {@code INITIALIZE-ALL-FIELDS} first and a failing path
     * raises instead of returning.
     *
     * @param mutated the request to submit, whose confirmation this method overrides
     * @return the prompt result, carrying the fully echoed screen
     */
    private TransactionAddResult submitForConfirmation(final RequestBuilder mutated) {
        resolveAccountBranch();
        acceptBothDates();
        return service().submitScreen(AttentionIdentifier.ENTER,
                mutated.confirmation(CONFIRM_BLANK).build(), ORIGIN_PROGRAM);
    }

    /**
     * Drives {@code COPY-LAST-TRAN-DATA}, the {@code DFHPF5} arm of {@code app/cbl/COTRN02C.cbl:146-147},
     * which prefills the map from the highest existing transaction and then falls through into
     * {@code PROCESS-ENTER-KEY}. It is the only path on which a stored nine digit amount meets the eight digit
     * edited picture of {@code :481}.
     *
     * @param storedAmount the {@code TRAN-AMT} of the row the descending browse retrieves
     * @return the prompt result, carrying the prefilled screen
     */
    private TransactionAddResult prefillFromStoredAmount(final BigDecimal storedAmount) {
        resolveAccountBranch();
        when(this.transactionRepository.findFirstByOrderByTransactionIdDesc())
                .thenReturn(Optional.of(storedTransaction("0000000000000009", storedAmount)));
        acceptBothDates();
        return service().submitScreen(AttentionIdentifier.PF5,
                request().confirmation(CONFIRM_BLANK).build(), ORIGIN_PROGRAM);
    }

    // ================================================================================================
    // Phase 1. Two numeric intrinsics, used deliberately.
    // ================================================================================================

    /**
     * {@code FUNCTION NUMVAL} guards and parses the identifiers at {@code app/cbl/COTRN02C.cbl:204} and
     * {@code :218}; {@code FUNCTION NUMVAL-C} parses the amount at {@code :383} and again at {@code :456}.
     *
     * <p>The two are separate code paths and must stay separate. The identifiers are additionally
     * <em>guarded before they are parsed</em>: the {@code IS NOT NUMERIC} class tests at {@code :197} and
     * {@code :211} reject a currency symbol or a thousands separator with a named screen message, so the
     * rejection is never a format exception escaping from a parser.</p>
     */
    @Nested
    @DisplayName("Phase 1 - two numeric intrinsics, used deliberately")
    class NumericParserContract {

        @Test
        @DisplayName("the amount takes the currency-aware path and parses to scale two, :383")
        void theAmountTakesTheCurrencyAwarePath() {
            final TransactionAddResult result = addConfirmed(request().build());

            assertThat(result.outcome()).isEqualTo(Outcome.ADDED);
            assertThat(result.screen().amountValue())
                    .as("FUNCTION NUMVAL-C at app/cbl/COTRN02C.cbl:383 yields WS-TRAN-AMT-N S9(9)V99")
                    .isEqualByComparingTo(AMOUNT_VALUE);
            assertThat(result.screen().amountValue().scale())
                    .as("TRAN-AMT is S9(09)V99, so the parsed value carries exactly two decimal digits")
                    .isEqualTo(TransactionAddRequest.AMOUNT_SCALE);
        }

        @Test
        @DisplayName("the amount rejects a thousands separator through the :339-351 mask")
        void theAmountRejectsAThousandsSeparator() {
            resolveAccountBranch();
            final TransactionAddService service = service();
            final TransactionAddRequest submitted = request().amount("1,234.56").build();

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER, submitted,
                            ORIGIN_PROGRAM))
                    .withMessage(MSG_AMOUNT_FORMAT)
                    .satisfies(failure -> {
                        assertThat(failure.getFieldName()).isEqualTo("amount");
                        assertThat(failure.getFailureKind())
                                .isEqualTo(ValidationException.FailureKind.INVALID);
                        assertThat(failure.getCause())
                                .as("the positional mask is a class test, so there is no wrapped cause")
                                .isNull();
                    });
            verifyNoInteractions(dateValidationService);
        }

        @Test
        @DisplayName("the amount rejects a currency symbol through the :339-351 mask")
        void theAmountRejectsACurrencySymbol() {
            resolveAccountBranch();
            final TransactionAddService service = service();
            final TransactionAddRequest submitted = request().amount("$1234.56").build();

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER, submitted,
                            ORIGIN_PROGRAM))
                    .withMessage(MSG_AMOUNT_FORMAT);
            verify(transactionRepository, never()).saveAndFlush(any(Transaction.class));
        }

        @Test
        @DisplayName("the account identifier rejects a thousands separator with its own literal, :199-200")
        void theAccountIdentifierRejectsAThousandsSeparator() {
            final TransactionAddService service = service();
            final TransactionAddRequest submitted = request().accountId("1,234").build();

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER, submitted,
                            ORIGIN_PROGRAM))
                    .withMessage(MSG_ACCOUNT_ID_NOT_NUMERIC)
                    .satisfies(failure -> assertThat(failure.getFieldName()).isEqualTo("accountId"));

            verifyNoInteractions(cardCrossReferenceRepository, transactionRepository,
                    dateValidationService);
        }

        @Test
        @DisplayName("the account identifier rejects a currency symbol, so the guard precedes the parse")
        void theAccountIdentifierRejectsACurrencySymbol() {
            final TransactionAddService service = service();
            final TransactionAddRequest submitted = request().accountId("$1234").build();

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER, submitted,
                            ORIGIN_PROGRAM))
                    .withMessage(MSG_ACCOUNT_ID_NOT_NUMERIC)
                    .satisfies(failure -> assertThat(failure.getCause())
                            .as("the guard reports the screen message; it does not surface a parse failure")
                            .isNull());
        }

        @Test
        @DisplayName("the card number rejects a thousands separator with 'Card Number must be Numeric...'")
        void theCardNumberRejectsAThousandsSeparator() {
            final TransactionAddService service = service();
            final TransactionAddRequest submitted =
                    request().accountId("").cardNumber("1,234").build();

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER, submitted,
                            ORIGIN_PROGRAM))
                    .withMessage(MSG_CARD_NUMBER_NOT_NUMERIC)
                    .satisfies(failure -> assertThat(failure.getFieldName()).isEqualTo("cardNumber"));
        }

        @Test
        @DisplayName("the card number literal is byte exact, app/cbl/COTRN02C.cbl:213-214")
        void theCardNumberLiteralIsByteExact() {
            final TransactionAddService service = service();
            final TransactionAddRequest submitted =
                    request().accountId("").cardNumber("$1234").build();

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER, submitted,
                            ORIGIN_PROGRAM))
                    .satisfies(failure -> assertThat(failure.getMessage())
                            .as("compared character for character against the source literal")
                            .isEqualTo("Card Number must be Numeric...")
                            .hasSize(30)
                            .endsWith("..."));
        }

        @Test
        @DisplayName("the parsed account identifier is echoed back zero padded to eleven, :205-206")
        void theParsedAccountIdentifierIsEchoedBackZeroPadded() {
            final TransactionAddResult result = submitForConfirmation(request().accountId("000000000110"));

            assertThat(result.outcome()).isEqualTo(Outcome.CONFIRMATION_REQUIRED);
            assertThat(result.screen().transactionIdInput())
                    .as("MOVE WS-ACCT-ID-N TO XREF-ACCT-ID ACTIDINI normalises the screen field")
                    .isEqualTo("00000000011")
                    .hasSize(11)
                    .isNotEqualTo("000000000110");
        }

        @Test
        @DisplayName("the parsed card number is echoed back zero padded to sixteen, :219-220")
        void theParsedCardNumberIsEchoedBackZeroPadded() {
            when(cardCrossReferenceRepository.findById("0000000000000011"))
                    .thenReturn(Optional.of(crossReference("0000000000000011")));
            acceptBothDates();

            final TransactionAddResult result = service().submitScreen(AttentionIdentifier.ENTER,
                    request().accountId("").cardNumber("00000000000000110")
                            .confirmation(CONFIRM_BLANK).build(),
                    ORIGIN_PROGRAM);

            assertThat(result.screen().cardNumber())
                    .as("the seventeenth character is dropped by the MOVE and the rest is normalised")
                    .isEqualTo("0000000000000011")
                    .hasSize(16);
            assertThat(result.screen().transactionIdInput())
                    .as("the cross reference supplies XREF-ACCT-ID for the account field")
                    .isEqualTo("00000000011");
        }

        @Test
        @DisplayName("the edited amount form is rejected by the identifier guard, proving disjoint paths")
        void theEditedAmountFormIsRejectedByTheIdentifierGuard() {
            final TransactionAddService service = service();
            final TransactionAddRequest submitted = request().accountId(AMOUNT_TEXT).build();

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER, submitted,
                            ORIGIN_PROGRAM))
                    .as("'%s' is a valid amount and never a valid identifier", AMOUNT_TEXT)
                    .withMessage(MSG_ACCOUNT_ID_NOT_NUMERIC);
        }

        @Test
        @DisplayName("the identifier form is rejected by the amount mask, proving disjoint paths")
        void theIdentifierFormIsRejectedByTheAmountMaskGuard() {
            resolveAccountBranch();
            final TransactionAddService service = service();
            final TransactionAddRequest submitted = request().amount(ACCOUNT_ID_TEXT).build();

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER, submitted,
                            ORIGIN_PROGRAM))
                    .as("'%s' is a valid identifier and never a valid amount", ACCOUNT_ID_TEXT)
                    .withMessage(MSG_AMOUNT_FORMAT);
        }
    }

    // ================================================================================================
    // Phase 2. The display mask truncation hazard.
    // ================================================================================================

    /**
     * {@code WS-TRAN-AMT-N PIC S9(9)V99} at {@code app/cbl/COTRN02C.cbl:58} holds nine integer digits;
     * {@code WS-TRAN-AMT-E PIC +99999999.99} at {@code :59} renders only eight. The round trip at
     * {@code :385-386} therefore discards the high-order digit of any magnitude at or above one hundred
     * million, and the discarded digit is never recoverable.
     *
     * <p>Screen input cannot reach that magnitude, because {@code TRNAMTI} is {@code PIC X(12)} and the
     * positional mask of {@code :339-351} spends one character on the mandatory sign. The truncation is
     * reached instead through {@code COPY-LAST-TRAN-DATA}, whose prefill at {@code :481} moves the stored
     * {@code TRAN-AMT S9(09)V99} straight into the eight-digit edited field. That is the observation point
     * used below. The mask is asserted, never widened.</p>
     */
    @Nested
    @DisplayName("Phase 2 - the eight versus nine digit display mask")
    class AmountDisplayMask {

        @Test
        @DisplayName("eight integer digits render in full with the mandatory sign, :59")
        void eightIntegerDigitsRenderInFull() {
            final TransactionAddResult result = submitForConfirmation(request().amount("+99999999.99"));

            assertThat(result.screen().amount())
                    .as("the mask's whole integer budget, exercised to its last digit")
                    .isEqualTo("+99999999.99")
                    .hasSize(12);
            assertThat(result.screen().amountValue()).isEqualByComparingTo(new BigDecimal("99999999.99"));
        }

        @Test
        @DisplayName("one hundred million renders truncated to +00000000.00 on the prefill path, :481")
        void oneHundredMillionRendersTruncated() {
            final TransactionAddResult result = prefillFromStoredAmount(new BigDecimal("100000000.00"));

            assertThat(result.screen().amount())
                    .as("the ninth integer digit has no receiving position in PIC +99999999.99")
                    .isEqualTo("+00000000.00");
        }

        @Test
        @DisplayName("the truncation is destructive: the reparsed value is not the stored value")
        void theTruncationIsDestructive() {
            final TransactionAddResult result = prefillFromStoredAmount(new BigDecimal("100000000.00"));

            assertThat(result.screen().amountValue())
                    .as("PROCESS-ENTER-KEY reparses the prefilled text, so the lost digit stays lost")
                    .isEqualByComparingTo(BigDecimal.ZERO)
                    .isNotEqualByComparingTo(new BigDecimal("100000000.00"));
        }

        @Test
        @DisplayName("a nine digit negative keeps its sign and loses only its leading digit")
        void aNineDigitNegativeKeepsItsSign() {
            final TransactionAddResult result = prefillFromStoredAmount(new BigDecimal("-123456789.55"));

            assertThat(result.screen().amount())
                    .as("abs() then remainder against ten to the eighth, with the sign carried separately")
                    .isEqualTo("-23456789.55");
        }

        @Test
        @DisplayName("a negative amount renders with a leading minus")
        void aNegativeAmountRendersWithALeadingMinus() {
            final TransactionAddResult result = submitForConfirmation(request().amount("-00001234.56"));

            assertThat(result.screen().amount()).isEqualTo("-00001234.56").startsWith("-");
            assertThat(result.screen().amountValue()).isEqualByComparingTo(new BigDecimal("-1234.56"));
        }

        @Test
        @DisplayName("the sign is mandatory, so a signed zero normalises to a leading plus")
        void aSignedZeroNormalisesToALeadingPlus() {
            final TransactionAddResult result = submitForConfirmation(request().amount("-00000000.00"));

            assertThat(result.screen().amount())
                    .as("the picture decides the sign from the signum, and zero is not negative")
                    .isEqualTo("+00000000.00")
                    .startsWith("+");
        }

        @Test
        @DisplayName("the round trip writes the masked form back into TRNAMTI, :386")
        void theRoundTripWritesTheMaskedFormBack() {
            final TransactionAddResult result = submitForConfirmation(request().amount("+00000000.05"));

            assertThat(result.screen().amount())
                    .as("MOVE WS-TRAN-AMT-E TO TRNAMTI is what the client redisplays")
                    .isEqualTo("+00000000.05");
            assertThat(result.screen().amount())
                    .as("the receiving screen field is PIC X(12), so the masked form fills it exactly")
                    .hasSize(TransactionAddRequest.AMOUNT_DISPLAY_MASK.length());
        }

        @Test
        @DisplayName("the mask is exactly the source picture and is never widened")
        void theMaskIsExactlyTheSourcePicture() {
            assertThat(TransactionAddRequest.AMOUNT_DISPLAY_MASK)
                    .as("PIC +99999999.99 at app/cbl/COTRN02C.cbl:59")
                    .isEqualTo("+99999999.99");
            assertThat(TransactionAddRequest.AMOUNT_PRECISION)
                    .as("TRAN-AMT S9(09)V99 is NUMERIC(11,2), so the value keeps nine integer digits")
                    .isEqualTo(11);
        }
    }

    // ================================================================================================
    // Phase 3. Explicit positional date guards precede every date service call.
    // ================================================================================================

    /**
     * {@code app/cbl/COTRN02C.cbl:353-366} and {@code :368-381} each run an {@code EVALUATE TRUE} of five
     * {@code WHEN} clauses before any date service is called: the year run, the first separator, the month
     * run, the second separator and the day run, closed by {@code WHEN OTHER CONTINUE}.
     *
     * <p>Each clause is asserted separately for each of the two dates, ten tests in total, because a
     * consolidated regular expression would change which inputs pass. The semantic behaviour of the date
     * service itself is not retested here: it is stubbed and only the interaction is asserted, which keeps
     * the data contract in its own test class.</p>
     */
    @Nested
    @DisplayName("Phase 3 - the five positional clauses of each date guard")
    class PositionalDateGuards {

        @Test
        @DisplayName("orig clause 1 of 5: TORIGDTI(1:4) IS NOT NUMERIC, :354")
        void originatingYearRunMustBeNumeric() {
            expectOriginatingFormatFailure("20X3-04-15");
        }

        @Test
        @DisplayName("orig clause 2 of 5: TORIGDTI(5:1) NOT EQUAL '-', :356")
        void originatingFirstSeparatorMustBeAHyphen() {
            expectOriginatingFormatFailure("2023/04-15");
        }

        @Test
        @DisplayName("orig clause 3 of 5: TORIGDTI(6:2) IS NOT NUMERIC, :358")
        void originatingMonthRunMustBeNumeric() {
            expectOriginatingFormatFailure("2023-0X-15");
        }

        @Test
        @DisplayName("orig clause 4 of 5: TORIGDTI(8:1) NOT EQUAL '-', :360")
        void originatingSecondSeparatorMustBeAHyphen() {
            expectOriginatingFormatFailure("2023-04/15");
        }

        @Test
        @DisplayName("orig clause 5 of 5: TORIGDTI(9:2) IS NOT NUMERIC, :362")
        void originatingDayRunMustBeNumeric() {
            expectOriginatingFormatFailure("2023-04-1X");
        }

        @Test
        @DisplayName("proc clause 1 of 5: TPROCDTI(1:4) IS NOT NUMERIC, :369")
        void processingYearRunMustBeNumeric() {
            expectProcessingFormatFailure("20X3-04-16");
        }

        @Test
        @DisplayName("proc clause 2 of 5: TPROCDTI(5:1) NOT EQUAL '-', :371")
        void processingFirstSeparatorMustBeAHyphen() {
            expectProcessingFormatFailure("2023/04-16");
        }

        @Test
        @DisplayName("proc clause 3 of 5: TPROCDTI(6:2) IS NOT NUMERIC, :373")
        void processingMonthRunMustBeNumeric() {
            expectProcessingFormatFailure("2023-0X-16");
        }

        @Test
        @DisplayName("proc clause 4 of 5: TPROCDTI(8:1) NOT EQUAL '-', :375")
        void processingSecondSeparatorMustBeAHyphen() {
            expectProcessingFormatFailure("2023-04/16");
        }

        @Test
        @DisplayName("proc clause 5 of 5: TPROCDTI(9:2) IS NOT NUMERIC, :377")
        void processingDayRunMustBeNumeric() {
            expectProcessingFormatFailure("2023-04-1X");
        }

        @Test
        @DisplayName("a positionally malformed date never reaches CALL 'CSUTLDTC', :393")
        void aMalformedDateNeverReachesTheDateService() {
            resolveAccountBranch();
            final TransactionAddService service = service();
            final TransactionAddRequest submitted = request().originatingDate("2023-13-4X").build();

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER, submitted,
                            ORIGIN_PROGRAM))
                    .withMessage(MSG_ORIGINATING_DATE_FORMAT);

            verifyNoInteractions(dateValidationService);
        }

        @Test
        @DisplayName("a positionally valid but semantically invalid date does reach the date service")
        void aSemanticallyInvalidDateReachesTheDateService() {
            resolveAccountBranch();
            when(dateValidationService.validate("2024-02-30", DATE_FORMAT)).thenReturn(rejectedDate());
            final TransactionAddService service = service();
            final TransactionAddRequest submitted = request().originatingDate("2024-02-30").build();

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER, submitted,
                            ORIGIN_PROGRAM))
                    .as("the positional gate passes 2024-02-30; only CSUTLDTC knows February has no 30th")
                    .withMessage(MSG_ORIGINATING_DATE_INVALID)
                    .satisfies(failure ->
                            assertThat(failure.getFieldName()).isEqualTo("originatingDate"));

            verify(dateValidationService).validate("2024-02-30", DATE_FORMAT);
        }

        @Test
        @DisplayName("the format argument is the WS-DATE-FORMAT literal of :60, passed on both calls")
        void theFormatArgumentIsTheWorkingStorageLiteral() {
            submitForConfirmation(request());

            assertThat(DATE_FORMAT).isEqualTo("YYYY-MM-DD");
            verify(dateValidationService).validate(ORIGINATING_DATE_TEXT, "YYYY-MM-DD");
            verify(dateValidationService).validate(PROCESSING_DATE_TEXT, "YYYY-MM-DD");
        }

        @Test
        @DisplayName("only the first four characters decide acceptance, :397")
        void onlyTheFirstFourCharactersDecideAcceptance() {
            resolveAccountBranch();
            when(dateValidationService.validate(ORIGINATING_DATE_TEXT, DATE_FORMAT))
                    .thenReturn(dateVerdict(SEVERITY_ACCEPTED, MESSAGE_NUMBER_REJECTED));
            when(dateValidationService.validate(PROCESSING_DATE_TEXT, DATE_FORMAT))
                    .thenReturn(dateVerdict(SEVERITY_ACCEPTED, MESSAGE_NUMBER_REJECTED));

            final TransactionAddResult result = service().submitScreen(AttentionIdentifier.ENTER,
                    request().confirmation(CONFIRM_BLANK).build(), ORIGIN_PROGRAM);

            assertThat(result.outcome())
                    .as("a rejecting message number is irrelevant once SEV-CD is the accept token")
                    .isEqualTo(Outcome.CONFIRMATION_REQUIRED);
        }

        @Test
        @DisplayName("'0000' is the accept token; any other severity is a rejection, :397")
        void theAcceptTokenIsFourZeros() {
            assertThat(SEVERITY_ACCEPTED).isEqualTo("0000").hasSize(4);
            resolveAccountBranch();
            when(dateValidationService.validate(ORIGINATING_DATE_TEXT, DATE_FORMAT))
                    .thenReturn(dateVerdict("0001", MESSAGE_NUMBER_REJECTED));
            final TransactionAddService service = service();
            final TransactionAddRequest submitted = request().build();

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER, submitted,
                            ORIGIN_PROGRAM))
                    .withMessage(MSG_ORIGINATING_DATE_INVALID);
        }

        @Test
        @DisplayName("message number 2513 is tolerated even when the severity rejects, :399")
        void theToleratedMessageNumberIsHonoured() {
            resolveAccountBranch();
            when(dateValidationService.validate(ORIGINATING_DATE_TEXT, DATE_FORMAT))
                    .thenReturn(dateVerdict(SEVERITY_REJECTED, MESSAGE_NUMBER_TOLERATED));
            when(dateValidationService.validate(PROCESSING_DATE_TEXT, DATE_FORMAT))
                    .thenReturn(dateVerdict(SEVERITY_REJECTED, MESSAGE_NUMBER_TOLERATED));

            final TransactionAddResult result = service().submitScreen(AttentionIdentifier.ENTER,
                    request().confirmation(CONFIRM_BLANK).build(), ORIGIN_PROGRAM);

            assertThat(result.outcome())
                    .as("IF CSUTLDTC-RESULT-MSG-NUM NOT = '2513' is the escape at :399 and :419")
                    .isEqualTo(Outcome.CONFIRMATION_REQUIRED);
        }

        @Test
        @DisplayName("the result area is exactly eighty characters, the buffer MOVE SPACES clears, :65-69")
        void theResultAreaIsExactlyEightyCharacters() {
            final DateValidationService.FeedbackCode feedback =
                    new DateValidationService.FeedbackCode(0, 0);

            assertThat(acceptedDate().result()).hasSize(RESULT_LENGTH);
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new DateValidationService.DateValidationResult(feedback, "0000"))
                    .as("a short area could not carry SEV-CD X(4) + FILLER X(11) + MSG-NUM X(4) + MSG X(61)")
                    .withMessageContaining("exactly 80");
        }

        @Test
        @DisplayName("no verdict carries over between the two calls, so each starts from a cleared area")
        void noVerdictCarriesOverBetweenTheTwoCalls() {
            resolveAccountBranch();
            when(dateValidationService.validate(ORIGINATING_DATE_TEXT, DATE_FORMAT))
                    .thenReturn(acceptedDate());
            when(dateValidationService.validate(PROCESSING_DATE_TEXT, DATE_FORMAT))
                    .thenReturn(rejectedDate());
            final TransactionAddService service = service();
            final TransactionAddRequest submitted = request().build();

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER, submitted,
                            ORIGIN_PROGRAM))
                    .as("the accepted originating verdict does not rescue the processing date")
                    .withMessage(MSG_PROCESSING_DATE_INVALID)
                    .satisfies(failure ->
                            assertThat(failure.getFieldName()).isEqualTo("processingDate"));
        }

        @Test
        @DisplayName("the originating date is validated before the processing date, :389 then :409")
        void theOriginatingDateIsValidatedFirst() {
            submitForConfirmation(request());

            final InOrder order = inOrder(dateValidationService);
            order.verify(dateValidationService).validate(ORIGINATING_DATE_TEXT, DATE_FORMAT);
            order.verify(dateValidationService).validate(PROCESSING_DATE_TEXT, DATE_FORMAT);
            order.verifyNoMoreInteractions();
        }

        @Test
        @DisplayName("both format literals are byte exact and each repositions to its own field")
        void bothFormatLiteralsAreByteExactAndTargetTheirOwnField() throws ReflectiveOperationException {
            assertThat(MSG_ORIGINATING_DATE_FORMAT).isEqualTo("Orig Date should be in format YYYY-MM-DD");
            assertThat(MSG_PROCESSING_DATE_FORMAT).isEqualTo("Proc Date should be in format YYYY-MM-DD");

            assertThat(cursorConstant("CURSOR_ORIGINATING_DATE"))
                    .as("app/cbl/COTRN02C.cbl:362 drives TORIGDTL, not TPROCDTL: there is no copy-paste defect")
                    .isEqualTo(CURSOR_ORIGINATING_DATE);
            assertThat(cursorConstant("CURSOR_PROCESSING_DATE"))
                    .as("app/cbl/COTRN02C.cbl:377 drives TPROCDTL")
                    .isEqualTo(CURSOR_PROCESSING_DATE);
            assertThat(cursorConstant("CURSOR_ORIGINATING_DATE"))
                    .isNotEqualTo(cursorConstant("CURSOR_PROCESSING_DATE"));
        }

        /**
         * Asserts the originating date positional guard rejects a value, without the processing date or the
         * date service ever being reached.
         *
         * @param malformed the {@code TORIGDTI} content that breaks exactly one clause
         */
        private void expectOriginatingFormatFailure(final String malformed) {
            resolveAccountBranch();
            final TransactionAddService service = service();
            final TransactionAddRequest submitted = request().originatingDate(malformed).build();

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER, submitted,
                            ORIGIN_PROGRAM))
                    .withMessage(MSG_ORIGINATING_DATE_FORMAT)
                    .satisfies(failure -> {
                        assertThat(failure.getFieldName()).isEqualTo("originatingDate");
                        assertThat(failure.getFailureKind())
                                .isEqualTo(ValidationException.FailureKind.INVALID);
                    });
            verifyNoInteractions(dateValidationService);
        }

        /**
         * Asserts the processing date positional guard rejects a value once the originating date has passed
         * its own guard, so the two guards are proven to be separate gates.
         *
         * @param malformed the {@code TPROCDTI} content that breaks exactly one clause
         */
        private void expectProcessingFormatFailure(final String malformed) {
            resolveAccountBranch();
            final TransactionAddService service = service();
            final TransactionAddRequest submitted = request().processingDate(malformed).build();

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER, submitted,
                            ORIGIN_PROGRAM))
                    .withMessage(MSG_PROCESSING_DATE_FORMAT)
                    .satisfies(failure ->
                            assertThat(failure.getFieldName()).isEqualTo("processingDate"));
            verifyNoInteractions(dateValidationService);
        }
    }

    /**
     * Reads one of the service's private cursor constants, so the symbolic map length field each failure
     * drives to -1 is asserted rather than assumed. The value is a field name, never a field value, so
     * nothing sensitive is exposed.
     *
     * @param name the constant to read
     * @return the symbolic map length field name it holds
     * @throws ReflectiveOperationException if the constant is renamed or removed
     */
    private static String cursorConstant(final String name) throws ReflectiveOperationException {
        final Field constant = TransactionAddService.class.getDeclaredField(name);
        constant.setAccessible(true);
        return (String) constant.get(null);
    }

    // ================================================================================================
    // Phase 4. Pass-through timestamps: raw screen text, no formatting at all.
    // ================================================================================================

    /**
     * {@code app/cbl/COTRN02C.cbl:464-465} moves {@code TORIGDTI} and {@code TPROCDTI}, each
     * {@code PIC X(10)}, straight into {@code TRAN-ORIG-TS} and {@code TRAN-PROC-TS}, each
     * {@code PIC X(26)}. A COBOL alphanumeric move right pads, so the stored value is ten characters of
     * screen text followed by sixteen spaces. There is no time component, no formatting and no timestamp
     * generation on this path at all.
     *
     * <p>This is the third of three mutually incompatible timestamp producers in the corpus, and the only
     * one that consults nothing. A fixed clock whose date differs from both request dates is used as the
     * falsifier: if any clock were consulted, its date would appear.</p>
     */
    @Nested
    @DisplayName("Phase 4 - pass-through timestamps, :464-465")
    class PassThroughTimestamps {

        @Test
        @DisplayName("TRAN-ORIG-TS is TORIGDTI right padded to twenty six")
        void originatingTimestampIsTheRequestDateRightPadded() {
            addConfirmed(request().build());

            assertThat(capturePersistedRow().getOrigTs())
                    .as("MOVE TORIGDTI TO TRAN-ORIG-TS, app/cbl/COTRN02C.cbl:464")
                    .isEqualTo(FixedClockProvider.passThroughTimestamp(ORIGINATING_DATE_TEXT))
                    .isEqualTo(ORIGINATING_DATE_TEXT + " ".repeat(TIMESTAMP_PAD_WIDTH))
                    .hasSize(TIMESTAMP_WIDTH);
        }

        @Test
        @DisplayName("TRAN-PROC-TS is TPROCDTI right padded to twenty six, and is the other date")
        void processingTimestampIsTheOtherRequestDateRightPadded() {
            addConfirmed(request().build());
            final Transaction persisted = capturePersistedRow();

            assertThat(persisted.getProcTs())
                    .as("MOVE TPROCDTI TO TRAN-PROC-TS, app/cbl/COTRN02C.cbl:465")
                    .isEqualTo(PROCESSING_DATE_TEXT + " ".repeat(TIMESTAMP_PAD_WIDTH))
                    .hasSize(TIMESTAMP_WIDTH);
            assertThat(persisted.getProcTs())
                    .as("the two moves are independent, so the two fields must not be interchanged")
                    .isNotEqualTo(persisted.getOrigTs());
        }

        @Test
        @DisplayName("the padding is exactly sixteen spaces, being X(26) less X(10)")
        void thePaddingIsExactlySixteenSpaces() {
            addConfirmed(request().build());

            assertThat(capturePersistedRow().getOrigTs().substring(ORIGINATING_DATE_TEXT.length()))
                    .as("X(26) receiving X(10) leaves sixteen bytes of the receiving field untouched")
                    .isEqualTo(" ".repeat(TIMESTAMP_PAD_WIDTH))
                    .hasSize(TIMESTAMP_PAD_WIDTH)
                    .isBlank();
        }

        @Test
        @DisplayName("no time component is appended: there is no colon, no dot and no hour")
        void noTimeComponentIsAppended() {
            addConfirmed(request().build());
            final Transaction persisted = capturePersistedRow();

            assertThat(persisted.getOrigTs().strip())
                    .as("neither the batch producer's dotted form nor the online producer's colon form")
                    .doesNotContain(":")
                    .doesNotContain(".")
                    .hasSize(ORIGINATING_DATE_TEXT.length());
            assertThat(persisted.getProcTs().strip()).doesNotContain(":").doesNotContain(".");
        }

        @Test
        @DisplayName("an injected fixed clock's date appears in neither timestamp")
        void aFixedClocksDateAppearsInNeitherTimestamp() {
            final Clock frozen = FixedClockProvider.canonicalClock();
            assertThat(frozen.instant())
                    .as("the falsifier is a real, fixed, non-request instant")
                    .isEqualTo(FixedClockProvider.CANONICAL_INSTANT);
            final String clockDate = FixedClockProvider.onlineTimestamp(frozen).substring(0, 10);
            assertThat(clockDate).isNotEqualTo(ORIGINATING_DATE_TEXT).isNotEqualTo(PROCESSING_DATE_TEXT);

            addConfirmed(request().build());
            final Transaction persisted = capturePersistedRow();

            assertThat(persisted.getOrigTs())
                    .as("if any clock were consulted on this path its date would be here")
                    .doesNotContain(clockDate);
            assertThat(persisted.getProcTs()).doesNotContain(clockDate);
        }

        @Test
        @DisplayName("the timestamps are text: X(26) maps to String, never to a temporal type")
        void theTimestampsAreTextRatherThanTemporal() throws ReflectiveOperationException {
            assertThat(Transaction.class.getMethod("getOrigTs").getReturnType())
                    .as("TRAN-ORIG-TS PIC X(26) is CHAR(26), so a temporal type would reformat it")
                    .isEqualTo(String.class);
            assertThat(Transaction.class.getMethod("getProcTs").getReturnType())
                    .isEqualTo(String.class);
        }

        @Test
        @DisplayName("the account branch stores the cross reference's card number, the refreshed CARDNINI")
        void theAccountBranchStoresTheRefreshedScreenField() {
            addConfirmed(request().build());

            assertThat(capturePersistedRow().getCardNumber())
                    .as("READ-CXACAIX-FILE refreshes CARDNINI from XREF-CARD-NUM before :459 moves it")
                    .isEqualTo(SYNTHETIC_CARD_NUMBER)
                    .hasSize(16);
        }

        @Test
        @DisplayName("the card branch stores the keyed screen field itself, not a re-derived value")
        void theCardBranchStoresTheKeyedScreenField() {
            when(cardCrossReferenceRepository.findById(SYNTHETIC_CARD_NUMBER))
                    .thenReturn(Optional.of(crossReference()));
            acceptBothDates();
            emptyTransactionFile();
            acceptTheWrite();

            service().submitScreen(AttentionIdentifier.ENTER,
                    request().accountId("").cardNumber(SYNTHETIC_CARD_NUMBER).build(), ORIGIN_PROGRAM);

            assertThat(capturePersistedRow().getCardNumber())
                    .as("MOVE CARDNINI OF COTRN2AI TO TRAN-CARD-NUM at :459 takes the screen field")
                    .isEqualTo(SYNTHETIC_CARD_NUMBER);
        }

        @Test
        @DisplayName("the amount is parsed a second time from TRNAMTI inside ADD-TRANSACTION, :456")
        void theAmountIsParsedASecondTimeFromTheScreenField() {
            resolveAccountBranch();
            when(transactionRepository.findFirstByOrderByTransactionIdDesc())
                    .thenReturn(Optional.of(storedTransaction("0000000000000009",
                            new BigDecimal("100000000.00"))));
            acceptBothDates();
            acceptTheWrite();

            final TransactionAddResult result = service().submitScreen(AttentionIdentifier.PF5,
                    request().confirmation(CONFIRM_YES).build(), ORIGIN_PROGRAM);

            assertThat(result.outcome()).isEqualTo(Outcome.ADDED);
            assertThat(capturePersistedRow().getAmount())
                    .as("the second FUNCTION NUMVAL-C reads the truncated TRNAMTI, not WS-TRAN-AMT-N")
                    .isEqualByComparingTo(BigDecimal.ZERO)
                    .isNotEqualByComparingTo(new BigDecimal("100000000.00"));
        }

        @Test
        @DisplayName("the prefilled dates reach the record as the ten character screen text they became")
        void thePrefilledDatesReachTheRecordAsScreenText() {
            resolveAccountBranch();
            when(transactionRepository.findFirstByOrderByTransactionIdDesc())
                    .thenReturn(Optional.of(storedTransaction("0000000000000009", AMOUNT_VALUE)));
            acceptBothDates();
            acceptTheWrite();

            service().submitScreen(AttentionIdentifier.PF5,
                    request().confirmation(CONFIRM_YES).build(), ORIGIN_PROGRAM);

            assertThat(capturePersistedRow().getOrigTs())
                    .as("COPY-LAST-TRAN-DATA truncates the stored X(26) to X(10) at :486, and :464 re-pads it")
                    .isEqualTo(ORIGINATING_DATE_TEXT + " ".repeat(TIMESTAMP_PAD_WIDTH));
        }
    }

    // ================================================================================================
    // Phase 5. Identifier generation: the descending browse, its race, and the first identifier.
    // ================================================================================================

    /**
     * {@code app/cbl/COTRN02C.cbl:444-449} moves {@code HIGH-VALUES} into {@code TRAN-ID}, opens a browse,
     * reads backwards once, closes the browse and adds one to what it retrieved. End of file leaves the key
     * at zeros, so the first identifier a fresh file yields is one.
     *
     * <p>The idiom is inherently racy and the race is <em>retained</em>: two concurrent callers can generate
     * the same identifier and the primary key surfaces the collision, which is what the {@code DUPKEY} and
     * {@code DUPREC} branch at {@code :735-741} exists to report. Substituting a database sequence would
     * change every generated value and break comparison against the legacy baseline, so no sequence, no
     * generated value and no retry loop is introduced.</p>
     */
    @Nested
    @DisplayName("Phase 5 - the descending browse and its retained race")
    class IdentifierGeneration {

        @Test
        @DisplayName("an empty file yields the first identifier one, zero padded to sixteen")
        void anEmptyFileYieldsTheFirstIdentifier() {
            final TransactionAddResult result = addConfirmed(request().build());

            assertThat(result.transactionId())
                    .as("DFHRESP(ENDFILE) leaves TRAN-ID at zeros, and ADD 1 makes it one")
                    .isEqualTo("0000000000000001")
                    .hasSize(TRANSACTION_ID_WIDTH);
            assertThat(capturePersistedRow().getTransactionId()).isEqualTo("0000000000000001");
        }

        @Test
        @DisplayName("a populated file yields the retrieved maximum plus one")
        void aPopulatedFileYieldsTheMaximumPlusOne() {
            resolveAccountBranch();
            when(transactionRepository.findFirstByOrderByTransactionIdDesc())
                    .thenReturn(Optional.of(storedTransaction("0000000000000009", AMOUNT_VALUE)));
            acceptBothDates();
            acceptTheWrite();

            final TransactionAddResult result = service().submitScreen(AttentionIdentifier.ENTER,
                    request().build(), ORIGIN_PROGRAM);

            assertThat(result.transactionId()).isEqualTo("0000000000000010");
        }

        @Test
        @DisplayName("the browse is one top-one descending read, never a count and never a sequence")
        void theBrowseIsOneTopOneDescendingRead() {
            addConfirmed(request().build());

            verify(transactionRepository, times(1)).findFirstByOrderByTransactionIdDesc();
            verify(transactionRepository, never()).count();
            verify(transactionRepository, never()).findAll();
        }

        @Test
        @DisplayName("the finder name encodes the deterministic descending order, so no sort is implicit")
        void theFinderNameEncodesTheOrdering() {
            final Set<String> declared = Arrays.stream(TransactionRepository.class.getDeclaredMethods())
                    .map(Method::getName)
                    .collect(Collectors.toSet());

            assertThat(declared)
                    .as("EXEC CICS READPREV from HIGH-VALUES is a descending top-one read")
                    .contains("findFirstByOrderByTransactionIdDesc");
        }

        @Test
        @DisplayName("the race is retained: TRAN-ID carries no generated value and no sequence generator")
        void theRaceIsRetainedRatherThanReplacedBySequence() throws ReflectiveOperationException {
            final Set<String> annotations =
                    Arrays.stream(Transaction.class.getDeclaredField("transactionId").getAnnotations())
                            .map(annotation -> annotation.annotationType().getSimpleName())
                            .collect(Collectors.toSet());

            assertThat(annotations)
                    .as("the identifier is assigned by the browse, exactly as the source assigns it")
                    .contains("Id")
                    .doesNotContain("GeneratedValue", "SequenceGenerator", "TableGenerator");
        }

        @Test
        @DisplayName("a primary key collision surfaces as DuplicateRecordException, :735-741")
        void aCollisionSurfacesAsDuplicateRecordException() {
            resolveAccountBranch();
            acceptBothDates();
            emptyTransactionFile();
            when(transactionRepository.saveAndFlush(any(Transaction.class)))
                    .thenThrow(new DataIntegrityViolationException("tran_id primary key"));
            final TransactionAddService service = service();
            final TransactionAddRequest submitted = request().build();

            assertThatExceptionOfType(DuplicateRecordException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER, submitted,
                            ORIGIN_PROGRAM))
                    .withMessage(MSG_DUPLICATE_TRANSACTION)
                    .satisfies(failure -> {
                        assertThat(failure.getLogicalFile()).isEqualTo("TRANSACT");
                        assertThat(failure.getCollidingKey())
                                .as("the colliding key is a transaction identifier, never a card number")
                                .isEqualTo("0000000000000001");
                        assertThat(failure.getCause())
                                .as("the provider failure is preserved as the root cause")
                                .isInstanceOf(DataIntegrityViolationException.class);
                    });
        }

        @Test
        @DisplayName("no retry loop follows a collision: exactly one write is attempted")
        void noRetryLoopFollowsACollision() {
            resolveAccountBranch();
            acceptBothDates();
            emptyTransactionFile();
            when(transactionRepository.saveAndFlush(any(Transaction.class)))
                    .thenThrow(new DataIntegrityViolationException("tran_id primary key"));
            final TransactionAddService service = service();
            final TransactionAddRequest submitted = request().build();

            assertThatExceptionOfType(DuplicateRecordException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER, submitted,
                            ORIGIN_PROGRAM));

            verify(transactionRepository, times(1)).saveAndFlush(any(Transaction.class));
            verify(transactionRepository, times(1)).findFirstByOrderByTransactionIdDesc();
        }

        @Test
        @DisplayName("an interest style date prefixed identifier dominates the descending browse")
        void anInterestStyleIdentifierDominatesTheBrowse() {
            resolveAccountBranch();
            when(transactionRepository.findFirstByOrderByTransactionIdDesc())
                    .thenReturn(Optional.of(storedTransaction("2023041500000042", AMOUNT_VALUE)));
            acceptBothDates();
            acceptTheWrite();

            final TransactionAddResult result = service().submitScreen(AttentionIdentifier.ENTER,
                    request().build(), ORIGIN_PROGRAM);

            assertThat(result.transactionId())
                    .as("app/cbl/CBACT04C.cbl leads its identifier with the ten character date parameter, so "
                            + "once an interest run has occurred the online path continues from that value")
                    .isEqualTo("2023041500000043");
        }

        @Test
        @DisplayName("the generated identifier is reported in the success message, :726-733")
        void theGeneratedIdentifierIsReportedInTheSuccessMessage() {
            final TransactionAddResult result = addConfirmed(request().build());

            assertThat(result.outcome()).isEqualTo(Outcome.ADDED);
            assertThat(result.message())
                    .as("the two literals of :727-732 are concatenated verbatim around the identifier")
                    .isEqualTo("Transaction added successfully.  Your Tran ID is 0000000000000001.");
        }

        @Test
        @DisplayName("a browse failure surfaces as a typed file access failure, :693-694")
        void aBrowseFailureSurfacesAsATypedFailure() {
            resolveAccountBranch();
            acceptBothDates();
            when(transactionRepository.findFirstByOrderByTransactionIdDesc())
                    .thenThrow(new QueryTimeoutException("READPREV timed out"));
            final TransactionAddService service = service();
            final TransactionAddRequest submitted = request().build();

            assertThatExceptionOfType(CardDemoException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER, submitted,
                            ORIGIN_PROGRAM))
                    .withMessage(MSG_TRANSACTION_LOOKUP_FAILURE)
                    .satisfies(failure -> assertThat(failure.getCause())
                            .as("no I/O failure is swallowed and no root cause is discarded")
                            .isInstanceOf(QueryTimeoutException.class));

            verify(transactionRepository, never()).saveAndFlush(any(Transaction.class));
        }

        @Test
        @DisplayName("a write failure that is not a collision surfaces as 'Unable to Add Transaction...'")
        void aWriteFailureSurfacesAsTheAddFailureLiteral() {
            resolveAccountBranch();
            acceptBothDates();
            emptyTransactionFile();
            when(transactionRepository.saveAndFlush(any(Transaction.class)))
                    .thenThrow(new QueryTimeoutException("WRITE timed out"));
            final TransactionAddService service = service();
            final TransactionAddRequest submitted = request().build();

            assertThatExceptionOfType(CardDemoException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER, submitted,
                            ORIGIN_PROGRAM))
                    .withMessage(MSG_ADD_FAILURE);
        }
    }

    // ================================================================================================
    // Phase 6. The remaining validation cascade and its exact literals.
    // ================================================================================================

    /**
     * Every screen literal the program can emit, compared character for character, and the order in which the
     * cascade emits them when several inputs are wrong at once.
     *
     * <p>Order is behaviour here, not an implementation detail: the source runs the key fields before the data
     * fields, the eleven blank checks before any class test, the class tests on the type and category codes
     * before the amount mask, the amount mask before both date masks, the amount parse before both semantic
     * date validations and the merchant identifier class test last of all. A cascade that reorders these
     * reports a different message for the same input.</p>
     */
    @Nested
    @DisplayName("Phase 6 - the validation cascade and its byte-exact literals")
    class ValidationCascadeLiterals {

        @Test
        @DisplayName("'Account or Card Number must be entered...' when neither key is supplied, :226-227")
        void neitherKeySuppliedReportsTheKeyRequiredLiteral() {
            final TransactionAddService service = service();
            final TransactionAddRequest submitted = request().accountId("").cardNumber("").build();

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER, submitted,
                            ORIGIN_PROGRAM))
                    .withMessage("Account or Card Number must be entered...")
                    .satisfies(failure -> {
                        assertThat(failure.getFieldName()).isEqualTo("accountId");
                        assertThat(failure.getFailureKind())
                                .as("the WHEN OTHER arm is FLG-...-BLANK of CSSETATY, not FLG-...-NOT-OK")
                                .isEqualTo(ValidationException.FailureKind.BLANK);
                    });
        }

        @Test
        @DisplayName("'Type CD can NOT be empty...' :254-255")
        void aBlankTypeCodeReportsItsLiteral() {
            expectDataFieldFailure(request().typeCode(""), MSG_TYPE_CODE_EMPTY, "typeCode");
        }

        @Test
        @DisplayName("'Category CD can NOT be empty...' :260-261")
        void aBlankCategoryCodeReportsItsLiteral() {
            expectDataFieldFailure(request().categoryCode(""), MSG_CATEGORY_CODE_EMPTY, "categoryCode");
        }

        @Test
        @DisplayName("'Source can NOT be empty...' :266-267")
        void aBlankSourceReportsItsLiteral() {
            expectDataFieldFailure(request().source(""), MSG_SOURCE_EMPTY, "source");
        }

        @Test
        @DisplayName("'Description can NOT be empty...' :272-273, checked BEFORE the amount at :276")
        void aBlankDescriptionReportsItsLiteral() {
            expectDataFieldFailure(request().description(""), MSG_DESCRIPTION_EMPTY, "description");
        }

        @Test
        @DisplayName("'Amount can NOT be empty...' :278-279")
        void aBlankAmountReportsItsLiteral() {
            expectDataFieldFailure(request().amount(""), MSG_AMOUNT_EMPTY, "amount");
        }

        @Test
        @DisplayName("'Merchant ID can NOT be empty...' :296-297")
        void aBlankMerchantIdReportsItsLiteral() {
            expectDataFieldFailure(request().merchantId(""), MSG_MERCHANT_ID_EMPTY, "merchantId");
        }

        @Test
        @DisplayName("'Merchant Name can NOT be empty...' :302-303")
        void aBlankMerchantNameReportsItsLiteral() {
            expectDataFieldFailure(request().merchantName(""), MSG_MERCHANT_NAME_EMPTY, "merchantName");
        }

        @Test
        @DisplayName("'Merchant City can NOT be empty...' :308-309")
        void aBlankMerchantCityReportsItsLiteral() {
            expectDataFieldFailure(request().merchantCity(""), MSG_MERCHANT_CITY_EMPTY, "merchantCity");
        }

        @Test
        @DisplayName("'Merchant Zip can NOT be empty...' :314-315, the eleventh and last blank check")
        void aBlankMerchantZipReportsItsLiteral() {
            expectDataFieldFailure(request().merchantZip(""), MSG_MERCHANT_ZIP_EMPTY, "merchantZip");
        }

        @Test
        @DisplayName("'Orig Date can NOT be empty...' :284-285")
        void aBlankOriginatingDateReportsItsLiteral() {
            expectDataFieldFailure(request().originatingDate(""), MSG_ORIGINATING_DATE_EMPTY,
                    "originatingDate");
        }

        @Test
        @DisplayName("'Proc Date can NOT be empty...' :290-291")
        void aBlankProcessingDateReportsItsLiteral() {
            expectDataFieldFailure(request().processingDate(""), MSG_PROCESSING_DATE_EMPTY,
                    "processingDate");
        }

        @Test
        @DisplayName("'Type CD must be Numeric...' :325-326")
        void aNonNumericTypeCodeReportsItsLiteral() {
            expectDataFieldFailure(request().typeCode("XX"), MSG_TYPE_CODE_NOT_NUMERIC, "typeCode");
        }

        @Test
        @DisplayName("'Category CD must be Numeric...' :331-332")
        void aNonNumericCategoryCodeReportsItsLiteral() {
            expectDataFieldFailure(request().categoryCode("00X5"), MSG_CATEGORY_CODE_NOT_NUMERIC,
                    "categoryCode");
        }

        @Test
        @DisplayName("'Merchant ID must be Numeric...' :432-433, the tenth and last stage")
        void aNonNumericMerchantIdReportsItsLiteral() {
            resolveAccountBranch();
            acceptBothDates();
            final TransactionAddService service = service();
            final TransactionAddRequest submitted = request().merchantId("0000001X3").build();

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER, submitted,
                            ORIGIN_PROGRAM))
                    .withMessage("Merchant ID must be Numeric...")
                    .satisfies(failure -> assertThat(failure.getFieldName()).isEqualTo("merchantId"));

            verify(transactionRepository, never()).saveAndFlush(any(Transaction.class));
        }

        @Test
        @DisplayName("'Confirm to add this transaction...' is a prompt, not a failure, :178-180")
        void aBlankConfirmationReportsThePromptLiteral() {
            final TransactionAddResult result = submitForConfirmation(request());

            assertThat(result.outcome()).isEqualTo(Outcome.CONFIRMATION_REQUIRED);
            assertThat(result.message()).isEqualTo("Confirm to add this transaction...");
            assertThat(result.cursorField()).isEqualTo(CURSOR_CONFIRMATION);
            verify(transactionRepository, never()).saveAndFlush(any(Transaction.class));
        }

        @Test
        @DisplayName("'Invalid value. Valid values are (Y/N)...' :184-185")
        void anUnrecognisedConfirmationReportsItsLiteral() {
            resolveAccountBranch();
            acceptBothDates();
            final TransactionAddService service = service();
            final TransactionAddRequest submitted = request().confirmation("X").build();

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER, submitted,
                            ORIGIN_PROGRAM))
                    .withMessage("Invalid value. Valid values are (Y/N)...")
                    .satisfies(failure -> assertThat(failure.getFieldName()).isEqualTo("confirmation"));
        }

        @Test
        @DisplayName("a lower case confirmation is honoured, so the gate is case insensitive")
        void aLowerCaseConfirmationIsHonoured() {
            final TransactionAddResult result =
                    addConfirmed(request().confirmation(CONFIRM_YES.toLowerCase(Locale.ROOT)).build());

            assertThat(result.outcome())
                    .as("WHEN 'Y' and WHEN 'y' are separate arms of the EVALUATE at :174-175")
                    .isEqualTo(Outcome.ADDED);
        }

        @Test
        @DisplayName("'Invalid key pressed. Please see below...' on an unsupported key, :148-151")
        void anUnsupportedKeyReportsItsLiteral() {
            final TransactionAddResult result = service().submitScreen(AttentionIdentifier.OTHER,
                    request().build(), ORIGIN_PROGRAM);

            assertThat(result.outcome()).isEqualTo(Outcome.INVALID_KEY);
            assertThat(result.message()).isEqualTo(MSG_INVALID_KEY);
            verifyNoInteractions(cardCrossReferenceRepository, transactionRepository,
                    dateValidationService);
        }

        @Test
        @DisplayName("'Account ID NOT found...' when the CXACAIX browse returns nothing, :593-595")
        void anAccountMissReportsItsLiteral() {
            when(cardCrossReferenceRepository.findFirstByAccountIdOrderByCardNumberAsc(ACCOUNT_ID))
                    .thenReturn(Optional.empty());
            final TransactionAddService service = service();
            final TransactionAddRequest submitted = request().build();

            assertThatExceptionOfType(RecordNotFoundException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER, submitted,
                            ORIGIN_PROGRAM))
                    .withMessage("Account ID NOT found...")
                    .satisfies(failure -> {
                        assertThat(failure.recordType()).contains("CXACAIX");
                        assertThat(failure.recordKey()).contains(ACCOUNT_ID_TEXT);
                    });
        }

        @Test
        @DisplayName("'Card Number NOT found...' when the CCXREF read misses, :626-628")
        void aCardMissReportsItsLiteral() {
            when(cardCrossReferenceRepository.findById(SYNTHETIC_CARD_NUMBER))
                    .thenReturn(Optional.empty());
            final TransactionAddService service = service();
            final TransactionAddRequest submitted =
                    request().accountId("").cardNumber(SYNTHETIC_CARD_NUMBER).build();

            assertThatExceptionOfType(RecordNotFoundException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER, submitted,
                            ORIGIN_PROGRAM))
                    .withMessage("Card Number NOT found...")
                    .satisfies(failure -> {
                        assertThat(failure.recordType()).contains("CCXREF");
                        assertThat(failure.recordKey()).isPresent();
                        assertThat(failure.recordKey().orElseThrow())
                                .as("the key is masked, so the diagnostic never carries the card number")
                                .doesNotContain(SYNTHETIC_CARD_NUMBER);
                    });
        }

        @Test
        @DisplayName("the two misses are distinguishable by their record type, CXACAIX versus CCXREF")
        void theTwoMissesAreDistinguishable() {
            when(cardCrossReferenceRepository.findFirstByAccountIdOrderByCardNumberAsc(ACCOUNT_ID))
                    .thenReturn(Optional.empty());
            final TransactionAddService accountBranch = service();
            final TransactionAddRequest byAccount = request().build();

            assertThatExceptionOfType(RecordNotFoundException.class)
                    .isThrownBy(() -> accountBranch.submitScreen(AttentionIdentifier.ENTER, byAccount,
                            ORIGIN_PROGRAM))
                    .satisfies(failure -> {
                        assertThat(failure.recordType()).contains("CXACAIX");
                        assertThat(failure.getMessage()).isNotEqualTo(MSG_CARD_NOT_FOUND);
                    });
        }

        @Test
        @DisplayName("'Unable to lookup Acct in XREF AIX file...' on a CXACAIX I/O failure, :600-601")
        void aCxacaixIoFailureReportsItsLiteral() {
            when(cardCrossReferenceRepository.findFirstByAccountIdOrderByCardNumberAsc(ACCOUNT_ID))
                    .thenThrow(new QueryTimeoutException("CXACAIX timed out"));
            final TransactionAddService service = service();
            final TransactionAddRequest submitted = request().build();

            assertThatExceptionOfType(CardDemoException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER, submitted,
                            ORIGIN_PROGRAM))
                    .withMessage(MSG_CXACAIX_FAILURE)
                    .satisfies(failure -> assertThat(failure.getCause())
                            .isInstanceOf(QueryTimeoutException.class));
        }

        @Test
        @DisplayName("'Unable to lookup Card # in XREF file...' on a CCXREF I/O failure, :633-634")
        void aCcxrefIoFailureReportsItsLiteral() {
            when(cardCrossReferenceRepository.findById(SYNTHETIC_CARD_NUMBER))
                    .thenThrow(new QueryTimeoutException("CCXREF timed out"));
            final TransactionAddService service = service();
            final TransactionAddRequest submitted =
                    request().accountId("").cardNumber(SYNTHETIC_CARD_NUMBER).build();

            assertThatExceptionOfType(CardDemoException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER, submitted,
                            ORIGIN_PROGRAM))
                    .withMessage(MSG_CCXREF_FAILURE)
                    .satisfies(failure -> assertThat(failure.getCause())
                            .isInstanceOf(QueryTimeoutException.class));
        }

        @Test
        @DisplayName("the key fields are validated before the data fields, :165 then :166")
        void theKeyFieldsWinOverTheDataFields() {
            final TransactionAddService service = service();
            final TransactionAddRequest submitted =
                    request().accountId("1,234").typeCode("").amount("nonsense").build();

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER, submitted,
                            ORIGIN_PROGRAM))
                    .as("three inputs are wrong; the key field message is the one the screen shows")
                    .withMessage(MSG_ACCOUNT_ID_NOT_NUMERIC);
        }

        @Test
        @DisplayName("the blank checks win over the class tests, :251-320 then :322-337")
        void theBlankChecksWinOverTheClassTests() {
            resolveAccountBranch();
            final TransactionAddService service = service();
            final TransactionAddRequest submitted =
                    request().typeCode("").categoryCode("00X5").build();

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER, submitted,
                            ORIGIN_PROGRAM))
                    .withMessage(MSG_TYPE_CODE_EMPTY);
        }

        @Test
        @DisplayName("the blank checks run in source order, so the type code wins over the amount")
        void theBlankChecksRunInSourceOrder() {
            resolveAccountBranch();
            final TransactionAddService service = service();
            final TransactionAddRequest submitted = request().typeCode("").amount("").build();

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER, submitted,
                            ORIGIN_PROGRAM))
                    .withMessage(MSG_TYPE_CODE_EMPTY);
        }

        @Test
        @DisplayName("the amount mask wins over both date masks, :339 then :353")
        void theAmountMaskWinsOverTheDateMasks() {
            resolveAccountBranch();
            final TransactionAddService service = service();
            final TransactionAddRequest submitted =
                    request().amount("1,234.56").originatingDate("20X3-04-15").build();

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER, submitted,
                            ORIGIN_PROGRAM))
                    .withMessage(MSG_AMOUNT_FORMAT);
        }

        @Test
        @DisplayName("the originating date mask wins over the processing date mask, :353 then :368")
        void theOriginatingDateMaskWinsOverTheProcessingDateMask() {
            resolveAccountBranch();
            final TransactionAddService service = service();
            final TransactionAddRequest submitted =
                    request().originatingDate("20X3-04-15").processingDate("20X3-04-16").build();

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER, submitted,
                            ORIGIN_PROGRAM))
                    .withMessage(MSG_ORIGINATING_DATE_FORMAT);
        }

        @Test
        @DisplayName("the semantic date validations win over the merchant identifier check, :409 then :430")
        void theSemanticDateValidationsWinOverTheMerchantIdentifier() {
            resolveAccountBranch();
            when(dateValidationService.validate(ORIGINATING_DATE_TEXT, DATE_FORMAT))
                    .thenReturn(acceptedDate());
            when(dateValidationService.validate(PROCESSING_DATE_TEXT, DATE_FORMAT))
                    .thenReturn(rejectedDate());
            final TransactionAddService service = service();
            final TransactionAddRequest submitted = request().merchantId("0000001X3").build();

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER, submitted,
                            ORIGIN_PROGRAM))
                    .withMessage(MSG_PROCESSING_DATE_INVALID);
        }

        /**
         * Asserts a data field failure on the account branch, where the key fields have already resolved.
         *
         * @param mutated the request under test
         * @param expectedMessage the byte-exact screen literal the source emits
         * @param expectedField the request component the failure attaches to
         */
        private void expectDataFieldFailure(final RequestBuilder mutated, final String expectedMessage,
                final String expectedField) {
            resolveAccountBranch();
            final TransactionAddService service = service();
            final TransactionAddRequest submitted = mutated.build();

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER, submitted,
                            ORIGIN_PROGRAM))
                    .withMessage(expectedMessage)
                    .satisfies(failure -> assertThat(failure.getFieldName()).isEqualTo(expectedField));

            verify(transactionRepository, never()).saveAndFlush(any(Transaction.class));
        }
    }

    // ================================================================================================
    // Phase 7. Field contracts and precision.
    // ================================================================================================

    /**
     * The request shape comes from {@code app/cpy-bms/COTRN02.CPY}, which declares twenty-one input fields;
     * the record shape comes from {@code app/cpy/CVTRA05Y.cpy}, whose three hundred and fifty bytes lay out as
     * 1-16, 17-18, 19-22, 23-32, 33-132, 133-143, 144-152, 153-202, 203-252, 253-262, 263-278, 279-304,
     * 305-330 and a twenty byte filler at 331-350.
     *
     * <p>{@code TRAN-AMT} is {@code S9(09)V99}, therefore {@code NUMERIC(11,2)} and therefore
     * {@link BigDecimal} at scale two with {@link RoundingMode#HALF_EVEN}. Equality is decided by
     * {@code compareTo}, never by {@code equals}, and no financial field may be a floating point type.</p>
     */
    @Nested
    @DisplayName("Phase 7 - field contracts and decimal precision")
    class FieldContractsAndPrecision {

        @Test
        @DisplayName("the request exposes exactly the twenty-one field contract of COTRN02.CPY")
        void theRequestExposesTheTwentyOneFieldContract() {
            final List<String> components =
                    Arrays.stream(TransactionAddRequest.class.getRecordComponents())
                            .map(RecordComponent::getName)
                            .toList();

            assertThat(components)
                    .as("six header fields plus fourteen body fields plus the error message line")
                    .hasSize(21)
                    .containsExactly("transactionName", "title01", "currentDate", "programName", "title02",
                            "currentTime", "accountId", "cardNumber", "typeCode", "categoryCode", "source",
                            "description", "amount", "originatingDate", "processingDate", "merchantId",
                            "merchantName", "merchantCity", "merchantZip", "confirmation", "errorMessage");
        }

        @Test
        @DisplayName("the persisted amount carries scale two, the V99 of S9(09)V99")
        void thePersistedAmountCarriesScaleTwo() {
            addConfirmed(request().build());

            assertThat(capturePersistedRow().getAmount().scale())
                    .as("NUMERIC(11,2) admits exactly two decimal digits")
                    .isEqualTo(TransactionAddRequest.AMOUNT_SCALE);
        }

        @Test
        @DisplayName("the persisted amount is compared with compareTo, never with equals")
        void thePersistedAmountIsComparedWithCompareTo() {
            addConfirmed(request().build());
            final BigDecimal persisted = capturePersistedRow().getAmount();

            assertThat(persisted).isEqualByComparingTo(AMOUNT_VALUE);
            assertThat(persisted.compareTo(new BigDecimal("1234.5600")))
                    .as("compareTo ignores scale, which is why it is the only safe equality here")
                    .isZero();
            assertThat(persisted.equals(new BigDecimal("1234.5600")))
                    .as("equals compares scale as well, so it reports a false difference")
                    .isFalse();
        }

        @Test
        @DisplayName("the rounding mode is HALF_EVEN and the precision is eleven")
        void theRoundingModeIsHalfEven() {
            assertThat(TransactionAddRequest.AMOUNT_ROUNDING_MODE).isEqualTo(RoundingMode.HALF_EVEN);
            assertThat(TransactionAddRequest.AMOUNT_PRECISION).isEqualTo(11);
            assertThat(TransactionAddRequest.AMOUNT_VALUE_MAX)
                    .as("nine integer digits and two decimals is the S9(09)V99 ceiling")
                    .isEqualByComparingTo(new BigDecimal("999999999.99"));
        }

        @Test
        @DisplayName("no field of the transaction record is a floating point type")
        void noFieldOfTheRecordIsFloatingPoint() {
            final Set<Class<?>> forbidden =
                    Set.of(float.class, double.class, Float.class, Double.class);

            final Set<String> offenders = Arrays.stream(Transaction.class.getDeclaredFields())
                    .filter(field -> forbidden.contains(field.getType()))
                    .map(Field::getName)
                    .collect(Collectors.toSet());

            assertThat(offenders)
                    .as("a binary floating point money field cannot represent a decimal cent exactly")
                    .isEmpty();
        }

        @Test
        @DisplayName("a third decimal digit is rejected rather than silently rounded")
        void aThirdDecimalDigitIsRejected() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> storedTransaction("0000000000000001", new BigDecimal("1.005")))
                    .as("the column would round it, so the record contract refuses it first")
                    .withMessageContaining("scale");
        }

        @Test
        @DisplayName("the category code becomes an Integer from the four character field, bytes 19-22")
        void theCategoryCodeBecomesAnInteger() {
            addConfirmed(request().build());

            assertThat(capturePersistedRow().getCategoryCode())
                    .as("TRAN-CAT-CD PIC 9(04) is a numeric field, so it is not carried as text")
                    .isEqualTo(5);
        }

        @Test
        @DisplayName("the merchant identifier becomes a Long from the nine character field, bytes 144-152")
        void theMerchantIdentifierBecomesALong() {
            addConfirmed(request().build());

            assertThat(capturePersistedRow().getMerchantId()).isEqualTo(123L);
        }

        @Test
        @DisplayName("the description widens from the screen's sixty to the record's hundred, bytes 33-132")
        void theDescriptionWidensToOneHundred() {
            addConfirmed(request().build());

            assertThat(capturePersistedRow().getDescription())
                    .hasSize(DESCRIPTION_RECORD_WIDTH)
                    .startsWith(DESCRIPTION_TEXT);
        }

        @Test
        @DisplayName("the merchant name and city widen to fifty each, bytes 153-202 and 203-252")
        void theMerchantNameAndCityWidenToFifty() {
            addConfirmed(request().build());
            final Transaction persisted = capturePersistedRow();

            assertThat(persisted.getMerchantName())
                    .hasSize(MERCHANT_NAME_RECORD_WIDTH)
                    .startsWith(MERCHANT_NAME_TEXT);
            assertThat(persisted.getMerchantCity())
                    .hasSize(MERCHANT_CITY_RECORD_WIDTH)
                    .startsWith(MERCHANT_CITY_TEXT);
        }

        @Test
        @DisplayName("the type code, source, card number and postal code keep their declared widths")
        void theRemainingTextFieldsKeepTheirDeclaredWidths() {
            addConfirmed(request().build());
            final Transaction persisted = capturePersistedRow();

            assertThat(persisted.getTypeCode()).hasSize(2).isEqualTo(TYPE_CODE_TEXT);
            assertThat(persisted.getTransactionSource()).hasSize(10).isEqualTo(SOURCE_TEXT);
            assertThat(persisted.getCardNumber()).hasSize(16);
            assertThat(persisted.getMerchantZip()).hasSize(10).isEqualTo(MERCHANT_ZIP_TEXT);
            assertThat(persisted.getTransactionId()).hasSize(TRANSACTION_ID_WIDTH);
        }

        @Test
        @DisplayName("the populated bytes of CVTRA05Y sum to three hundred and thirty, plus twenty filler")
        void thePopulatedBytesSumToThreeHundredAndThirty() {
            final int populated = TRANSACTION_ID_WIDTH + 2 + 4 + 10 + DESCRIPTION_RECORD_WIDTH + 11 + 9
                    + MERCHANT_NAME_RECORD_WIDTH + MERCHANT_CITY_RECORD_WIDTH + 10 + 16
                    + TIMESTAMP_WIDTH + TIMESTAMP_WIDTH;

            assertThat(populated)
                    .as("app/cpy/CVTRA05Y.cpy offsets 1-330, with FILLER at 331-350")
                    .isEqualTo(330);
            assertThat(populated + 20)
                    .as("the record length the catalogue and the fixtures both report")
                    .isEqualTo(350);
        }
    }

    // ================================================================================================
    // Phase 8. Paragraph correspondence.
    // ================================================================================================

    /**
     * {@code app/cbl/COTRN02C.cbl} is seven hundred and eighty three lines carrying eighteen paragraph
     * labels. Each label maps to exactly one private Java method and no label is consolidated with another,
     * which is what makes the traceability matrix verifiable by inspection rather than by assertion.
     *
     * <p>The structural claims asserted here are the ones a later refactor would silently break: the
     * paragraph set, the privacy of the paragraph methods, the collaborator set, the absence of a clock and
     * the absence of mutable static state.</p>
     */
    @Nested
    @DisplayName("Phase 8 - one private method per source paragraph")
    class ParagraphCorrespondence {

        @Test
        @DisplayName("the bean declares one method per paragraph, all eighteen of them")
        void theBeanDeclaresOneMethodPerParagraph() {
            final Set<String> declared = Arrays.stream(TransactionAddService.class.getDeclaredMethods())
                    .map(Method::getName)
                    .collect(Collectors.toSet());

            assertThat(PARAGRAPH_METHODS).hasSize(18).doesNotHaveDuplicates();
            assertThat(declared)
                    .as("app/cbl/COTRN02C.cbl labels at :107 :164 :193 :235 :442 :471 :500 :516 :539 :552 "
                            + ":576 :609 :642 :673 :702 :711 :754 :762")
                    .containsAll(PARAGRAPH_METHODS);
        }

        @Test
        @DisplayName("every paragraph method is private, so no paragraph leaks into the public surface")
        void everyParagraphMethodIsPrivate() {
            final Set<String> privateNames = Arrays.stream(TransactionAddService.class.getDeclaredMethods())
                    .filter(method -> Modifier.isPrivate(method.getModifiers()))
                    .map(Method::getName)
                    .collect(Collectors.toSet());

            assertThat(privateNames)
                    .as("the paragraphs are internal control flow; only the four entry points are public")
                    .containsAll(PARAGRAPH_METHODS);
        }

        @Test
        @DisplayName("the four entry points carry @Transactional(rollbackFor = Exception.class)")
        void theFourEntryPointsAreTransactional() throws ReflectiveOperationException {
            assertTransactional(TransactionAddService.class.getDeclaredMethod("submitScreen",
                    AttentionIdentifier.class, TransactionAddRequest.class, String.class));
            assertTransactional(TransactionAddService.class.getDeclaredMethod("addTransaction",
                    TransactionAddRequest.class));
            assertTransactional(TransactionAddService.class.getDeclaredMethod("openScreen",
                    TransactionAddRequest.class, String.class));
            assertTransactional(TransactionAddService.class.getDeclaredMethod("openWithoutCommArea"));
        }

        @Test
        @DisplayName("the constructor takes exactly the seven collaborators the program actually uses")
        void theConstructorTakesExactlySevenCollaborators() {
            final Constructor<?>[] constructors = TransactionAddService.class.getDeclaredConstructors();

            assertThat(constructors).hasSize(1);
            assertThat(constructors[0].getParameterTypes())
                    .as("constructor injection only, so there is no setter and no field injection. The last"
                            + " three are the WebConfig singletons: this class must not construct its own"
                            + " copy of a parsing rule, or the object request binding registers is not the"
                            + " object that parses an amount")
                    .containsExactly(TransactionRepository.class, CardCrossReferenceRepository.class,
                            DateValidationService.class, FileStatusMapper.class,
                            WebConfig.StrictIdentifierConverter.class,
                            WebConfig.CurrencyAwareAmountConverter.class,
                            WebConfig.EditedAmountPrinter.class);
        }

        @Test
        @DisplayName("no converter is constructed locally: the three parsers are injected, never new-ed")
        void theThreeParsersAreInjectedRatherThanConstructed() {
            assertThat(Arrays.stream(TransactionAddService.class.getDeclaredFields())
                    .filter(field -> field.getType().getName().startsWith(WebConfig.class.getName()))
                    .toList())
                    .as("one field per parsing rule, and each an instance field so it can only arrive"
                            + " through the constructor")
                    .hasSize(3)
                    .allSatisfy(field -> {
                        assertThat(java.lang.reflect.Modifier.isStatic(field.getModifiers()))
                                .as("a static holder would be a privately constructed second copy")
                                .isFalse();
                        assertThat(java.lang.reflect.Modifier.isFinal(field.getModifiers())).isTrue();
                    });
        }

        @Test
        @DisplayName("no account repository is injected, because the program never opens ACCTDAT")
        void noAccountRepositoryIsInjected() {
            final Constructor<?> constructor = TransactionAddService.class.getDeclaredConstructors()[0];

            assertThat(Arrays.asList(constructor.getParameterTypes()))
                    .as("WS-ACCTDAT-FILE is declared in the FILE SECTION and never read in the procedure "
                            + "division, so an injected account repository would be unused collaboration")
                    .doesNotContain(AccountRepository.class);
            assertThat(Arrays.stream(TransactionAddService.class.getDeclaredFields())
                    .map(Field::getType)
                    .toList())
                    .doesNotContain(AccountRepository.class);
        }

        @Test
        @DisplayName("the bean holds no clock, so nothing on this path can consult the current time")
        void theBeanHoldsNoClock() {
            assertThat(Arrays.stream(TransactionAddService.class.getDeclaredFields())
                    .map(Field::getType)
                    .toList())
                    .as("the timestamps are pass-through text; a clock field would be unreachable state")
                    .doesNotContain(Clock.class);
        }

        @Test
        @DisplayName("every static field is final, so the bean carries no global mutable state")
        void everyStaticFieldIsFinal() {
            final Set<String> mutableStatics = Arrays.stream(TransactionAddService.class.getDeclaredFields())
                    .filter(field -> Modifier.isStatic(field.getModifiers()))
                    .filter(field -> !Modifier.isFinal(field.getModifiers()))
                    .map(Field::getName)
                    .collect(Collectors.toSet());

            assertThat(mutableStatics)
                    .as("the legacy WORKING-STORAGE numerics became per-invocation state, not statics")
                    .isEmpty();
        }

        @Test
        @DisplayName("this test class itself holds no mutable static state")
        void thisTestClassHoldsNoMutableStaticState() {
            final Set<String> mutableStatics =
                    Arrays.stream(TransactionAddServiceTest.class.getDeclaredFields())
                            .filter(field -> Modifier.isStatic(field.getModifiers()))
                            .filter(field -> !Modifier.isFinal(field.getModifiers()))
                            .map(Field::getName)
                            .collect(Collectors.toSet());

            assertThat(mutableStatics)
                    .as("no shared fixture may leak between tests, which is what keeps them order independent")
                    .isEmpty();
        }

        /**
         * Asserts one entry point declares the transaction boundary the dual write depends on.
         *
         * @param entryPoint the public method to inspect
         */
        private void assertTransactional(final Method entryPoint) {
            final Transactional boundary = entryPoint.getAnnotation(Transactional.class);

            assertThat(boundary)
                    .as("%s must be one unit of work", entryPoint.getName())
                    .isNotNull();
            assertThat(boundary.rollbackFor())
                    .as("a checked failure must roll the write back, not commit half of it")
                    .containsExactly(Exception.class);
        }
    }

    // ================================================================================================
    // Phase 9. Hostile input, treated as untrusted at every boundary.
    // ================================================================================================

    /**
     * Every input is untrusted. The boundaries asserted here are the source's own: the field widths of
     * {@code app/cpy-bms/COTRN02.CPY}, the COBOL {@code MOVE} truncation that a longer value meets, the
     * space padding a shorter value meets, and the class tests and positional masks that reject both.
     *
     * <p>A value shorter than its field is space padded and therefore fails the numeric class test; a value
     * longer than its field is truncated and therefore <em>succeeds</em> against the truncated key. Both are
     * behaviour, and the second is the one an implementation is most likely to get wrong by throwing
     * instead.</p>
     */
    @Nested
    @DisplayName("Phase 9 - hostile input and boundary conditions")
    class HostileInput {

        @Test
        @DisplayName("a null request is an all-blank map, so it reports the key required literal")
        void aNullRequestIsAnAllBlankMap() {
            final TransactionAddService service = service();

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER, null, ORIGIN_PROGRAM))
                    .as("RECEIVE-TRNADD-SCREEN transmitted nothing, which is not an internal error")
                    .withMessage(MSG_KEY_REQUIRED);

            verifyNoInteractions(cardCrossReferenceRepository, transactionRepository,
                    dateValidationService);
        }

        @Test
        @DisplayName("a request whose every field is null reports the key required literal")
        void aRequestOfAllNullsReportsTheKeyRequiredLiteral() {
            final TransactionAddService service = service();
            final TransactionAddRequest submitted = new TransactionAddRequest(null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null);

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER, submitted,
                            ORIGIN_PROGRAM))
                    .withMessage(MSG_KEY_REQUIRED);
        }

        @ParameterizedTest
        @ValueSource(strings = {"", " ", "           "})
        @DisplayName("an unset, blank or fully spaced key pair reaches WHEN OTHER, :225-229")
        void anUnsetOrBlankKeyPairReachesWhenOther(final String blank) {
            final TransactionAddService service = service();
            final TransactionAddRequest submitted = request().accountId(blank).cardNumber(blank).build();

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER, submitted,
                            ORIGIN_PROGRAM))
                    .withMessage(MSG_KEY_REQUIRED);
        }

        @Test
        @DisplayName("an empty field models LOW-VALUES, the documented stand-in for the source's condition")
        void anEmptyFieldModelsLowValues() {
            expectDataFieldRejection(request().typeCode(""), MSG_TYPE_CODE_EMPTY);
        }

        @ParameterizedTest
        @ValueSource(strings = {"", " ", "  "})
        @DisplayName("a blank or spaced type code reaches 'Type CD can NOT be empty...' either way")
        void aBlankOrSpacedTypeCodeIsUnset(final String blank) {
            expectDataFieldRejection(request().typeCode(blank), MSG_TYPE_CODE_EMPTY);
        }

        @Test
        @DisplayName("a ten character account identifier is space padded and therefore not numeric")
        void aTenCharacterAccountIdentifierIsSpacePadded() {
            final TransactionAddService service = service();
            final TransactionAddRequest submitted = request().accountId("0000000001").build();

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER, submitted,
                            ORIGIN_PROGRAM))
                    .as("an incompletely keyed X(11) field carries a trailing space, which is not a digit")
                    .withMessage(MSG_ACCOUNT_ID_NOT_NUMERIC);
        }

        @Test
        @DisplayName("a twelve character account identifier is truncated to eleven and then accepted")
        void aTwelveCharacterAccountIdentifierIsTruncated() {
            resolveAccountBranch();
            acceptBothDates();

            service().submitScreen(AttentionIdentifier.ENTER,
                    request().accountId("000000000110").confirmation(CONFIRM_BLANK).build(),
                    ORIGIN_PROGRAM);

            verify(cardCrossReferenceRepository)
                    .findFirstByAccountIdOrderByCardNumberAsc(ACCOUNT_ID);
        }

        @Test
        @DisplayName("a fifteen character card number is space padded and therefore not numeric")
        void aFifteenCharacterCardNumberIsSpacePadded() {
            final TransactionAddService service = service();
            final TransactionAddRequest submitted =
                    request().accountId("").cardNumber("999988887777666").build();

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER, submitted,
                            ORIGIN_PROGRAM))
                    .withMessage(MSG_CARD_NUMBER_NOT_NUMERIC);
        }

        @Test
        @DisplayName("a seventeen character card number is truncated to sixteen and then accepted")
        void aSeventeenCharacterCardNumberIsTruncated() {
            when(cardCrossReferenceRepository.findById(SYNTHETIC_CARD_NUMBER))
                    .thenReturn(Optional.of(crossReference()));
            acceptBothDates();

            service().submitScreen(AttentionIdentifier.ENTER,
                    request().accountId("").cardNumber(SYNTHETIC_CARD_NUMBER + "5")
                            .confirmation(CONFIRM_BLANK).build(),
                    ORIGIN_PROGRAM);

            verify(cardCrossReferenceRepository).findById(SYNTHETIC_CARD_NUMBER);
        }

        @ParameterizedTest
        @ValueSource(strings = {"+0001234.567", "000001234.56", "+1234.56", "-.00", "+00001234,56"})
        @DisplayName("every malformed amount shape is rejected by the twelve position mask")
        void everyMalformedAmountShapeIsRejected(final String malformed) {
            expectDataFieldRejection(request().amount(malformed), MSG_AMOUNT_FORMAT);
        }

        @Test
        @DisplayName("a negative amount is stored negative, with no absolute value normalisation")
        void aNegativeAmountIsStoredNegative() {
            addConfirmed(request().amount("-00001234.56").build());

            assertThat(capturePersistedRow().getAmount())
                    .as("the sign is data; normalising it would change the cycle the posting job debits")
                    .isEqualByComparingTo(new BigDecimal("-1234.56"))
                    .isNegative();
        }

        @Test
        @DisplayName("a leading plus is accepted, since the mask's sign position is mandatory")
        void aLeadingPlusIsAccepted() {
            addConfirmed(request().amount("+00000001.00").build());

            assertThat(capturePersistedRow().getAmount()).isEqualByComparingTo(new BigDecimal("1.00"));
        }

        @ParameterizedTest
        @ValueSource(strings = {"2023-04-1", "2023-4-15", "23-04-15", "2023--4-15"})
        @DisplayName("every malformed date shape is rejected by the ten position mask")
        void everyMalformedDateShapeIsRejected(final String malformed) {
            expectDataFieldRejection(request().originatingDate(malformed), MSG_ORIGINATING_DATE_FORMAT);
        }

        @Test
        @DisplayName("an eleven character date is truncated to ten before the mask sees it")
        void anElevenCharacterDateIsTruncated() {
            resolveAccountBranch();
            acceptBothDates();

            service().submitScreen(AttentionIdentifier.ENTER,
                    request().originatingDate(ORIGINATING_DATE_TEXT + "5").confirmation(CONFIRM_BLANK)
                            .build(),
                    ORIGIN_PROGRAM);

            verify(dateValidationService).validate(ORIGINATING_DATE_TEXT, DATE_FORMAT);
        }

        @Test
        @DisplayName("a null confirmation is the prompt, never an implicit yes")
        void aNullConfirmationIsThePrompt() {
            resolveAccountBranch();
            acceptBothDates();

            final TransactionAddResult result = service().submitScreen(AttentionIdentifier.ENTER,
                    request().confirmation(null).build(), ORIGIN_PROGRAM);

            assertThat(result.outcome())
                    .as("the gate is never auto-confirmed, so nothing is written")
                    .isEqualTo(Outcome.CONFIRMATION_REQUIRED);
            verify(transactionRepository, never()).saveAndFlush(any(Transaction.class));
        }

        @Test
        @DisplayName("a null attention identifier joins WHEN OTHER rather than defaulting to enter")
        void aNullAttentionIdentifierJoinsWhenOther() {
            final TransactionAddResult result =
                    service().submitScreen(null, request().build(), ORIGIN_PROGRAM);

            assertThat(result.outcome()).isEqualTo(Outcome.INVALID_KEY);
            assertThat(result.message()).isEqualTo(MSG_INVALID_KEY);
            verifyNoInteractions(cardCrossReferenceRepository, transactionRepository,
                    dateValidationService);
        }

        /**
         * Asserts a data field rejection on the account branch, where nothing is written and the date service
         * is never consulted.
         *
         * @param mutated the request under test
         * @param expectedMessage the byte-exact screen literal
         */
        private void expectDataFieldRejection(final RequestBuilder mutated, final String expectedMessage) {
            resolveAccountBranch();
            final TransactionAddService service = service();
            final TransactionAddRequest submitted = mutated.build();

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER, submitted,
                            ORIGIN_PROGRAM))
                    .withMessage(expectedMessage);

            verifyNoInteractions(dateValidationService);
            verify(transactionRepository, never()).saveAndFlush(any(Transaction.class));
        }
    }

    // ================================================================================================
    // Phase 9 continued. Observability and secret hygiene.
    // ================================================================================================

    /**
     * The measurable behaviour the legacy program has no equivalent for, and the disclosure boundary the
     * project rule imposes on it.
     *
     * <p>{@code INITIALIZE-ALL-FIELDS} at {@code app/cbl/COTRN02C.cbl:762} blanks the map before the success
     * message is composed, but {@code WS-TRAN-AMT-N} survives it, which is what makes the total transaction
     * amount observable at the point the counter would record it. Nothing observable anywhere on these paths
     * may carry a full card number, a password, a hash, a national identifier or a date of birth.</p>
     */
    @Nested
    @DisplayName("Phase 9 - observability and secret hygiene")
    class ObservabilityAndSecretHygiene {

        @Test
        @DisplayName("the total transaction amount is observable after the write, for the named counter")
        void theTotalTransactionAmountIsObservableAfterTheWrite() {
            final TransactionAddResult result = addConfirmed(request().build());

            assertThat(result.outcome()).isEqualTo(Outcome.ADDED);
            assertThat(result.screen().amountValue())
                    .as("WS-TRAN-AMT-N is working storage, so INITIALIZE-ALL-FIELDS does not clear it")
                    .isEqualByComparingTo(AMOUNT_VALUE);
        }

        @Test
        @DisplayName("the map is blanked before the success message, :725 then :727")
        void theMapIsBlankedBeforeTheSuccessMessage() {
            final TransactionAddResult result = addConfirmed(request().build());

            assertThat(result.screen().typeCode()).isBlank();
            assertThat(result.screen().description()).isBlank();
            assertThat(result.screen().amount()).isBlank();
            assertThat(result.message()).isNotBlank();
        }

        @Test
        @DisplayName("the added record is reported by identifier only, never by card number")
        void theAddedRecordIsReportedByIdentifierOnly() {
            final TransactionAddResult result = addConfirmed(request().build());

            assertThat(result.message()).doesNotContain(SYNTHETIC_CARD_NUMBER);
            assertThat(result.screen().cardNumber())
                    .as("INITIALIZE-ALL-FIELDS clears CARDNINI before the map is sent back")
                    .isBlank();
        }

        @Test
        @DisplayName("the request's own toString discloses neither the card number nor the amount")
        void theRequestToStringDisclosesNothingSensitive() {
            final String rendered = request().build().toString();

            assertThat(rendered)
                    .doesNotContain(SYNTHETIC_CARD_NUMBER)
                    .doesNotContain(AMOUNT_TEXT)
                    .contains(ACCOUNT_ID_TEXT);
        }

        @Test
        @DisplayName("a card branch failure discloses no card number in its message or its key")
        void aCardBranchFailureDisclosesNoCardNumber() {
            when(cardCrossReferenceRepository.findById(SYNTHETIC_CARD_NUMBER))
                    .thenReturn(Optional.empty());
            final TransactionAddService service = service();
            final TransactionAddRequest submitted =
                    request().accountId("").cardNumber(SYNTHETIC_CARD_NUMBER).build();

            assertThatExceptionOfType(RecordNotFoundException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER, submitted,
                            ORIGIN_PROGRAM))
                    .satisfies(failure -> {
                        assertThat(failure.getMessage()).doesNotContain(SYNTHETIC_CARD_NUMBER);
                        assertThat(failure.recordKey().orElseThrow())
                                .doesNotContain(SYNTHETIC_CARD_NUMBER);
                    });
        }

        @Test
        @DisplayName("the colliding key is a sixteen digit transaction identifier, not a card number")
        void theCollidingKeyIsATransactionIdentifier() {
            resolveAccountBranch();
            acceptBothDates();
            emptyTransactionFile();
            when(transactionRepository.saveAndFlush(any(Transaction.class)))
                    .thenThrow(new DataIntegrityViolationException("tran_id primary key"));
            final TransactionAddService service = service();
            final TransactionAddRequest submitted = request().build();

            assertThatExceptionOfType(DuplicateRecordException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER, submitted,
                            ORIGIN_PROGRAM))
                    .satisfies(failure -> assertThat(failure.getCollidingKey())
                            .hasSize(TRANSACTION_ID_WIDTH)
                            .isNotEqualTo(SYNTHETIC_CARD_NUMBER));
        }

        @Test
        @DisplayName("the bean exposes no setter, so no collaborator can be swapped at runtime")
        void theBeanExposesNoSetter() {
            final Set<String> setters = Arrays.stream(TransactionAddService.class.getDeclaredMethods())
                    .map(Method::getName)
                    .filter(name -> name.startsWith("set"))
                    .collect(Collectors.toSet());

            assertThat(setters)
                    .as("constructor injection only, per the least privilege clause of the project rule")
                    .isEmpty();
        }

        @Test
        @DisplayName("the two date service calls are the only collaboration the validation cascade needs")
        void theValidationCascadeCollaboratesOnlyWithTheDateService() {
            submitForConfirmation(request());

            verify(dateValidationService, times(2)).validate(any(String.class), any(String.class));
            verify(transactionRepository, never()).saveAndFlush(any(Transaction.class));
            verify(transactionRepository, never()).findFirstByOrderByTransactionIdDesc();
        }
    }

    // ====================================================================================================
    // Phase 8 continued, and clause B of the project rule: "Document public APIs: purpose, inputs/outputs,
    // side effects, error modes." A documented entry point that no test ever calls is documented, not
    // verified, so all four public entry points and every arm of MAIN-PARA's EVALUATE EIBAID are exercised
    // here. Source: app/cbl/COTRN02C.cbl MAIN-PARA (:107-159).
    // ====================================================================================================

    @Nested
    @DisplayName("Phase 8 - the four public entry points and every EVALUATE EIBAID arm, :107-159")
    class EntryPointsAndAttentionKeys {

        @Test
        @DisplayName("addTransaction is the re-entered enter path: WHEN DFHENTER at :134-135")
        void addTransactionIsTheReenteredEnterPath() {
            resolveAccountBranch();
            acceptBothDates();
            emptyTransactionFile();
            acceptTheWrite();
            final TransactionAddService service = service();

            final TransactionAddResult result = service.addTransaction(request().build());

            assertThat(result.outcome())
                    .as("the convenience entry point sets CDEMO-PGM-REENTER and raises DFHENTER, so it "
                            + "reaches PROCESS-ENTER-KEY exactly as submitScreen does")
                    .isEqualTo(Outcome.ADDED);
            assertThat(result.transactionId()).isEqualTo("0000000000000001");
        }

        @Test
        @DisplayName("addTransaction carries no origin, so it can never reach the PF3 branch that reads it")
        void addTransactionCarriesNoOrigin() {
            resolveAccountBranch();
            acceptBothDates();
            emptyTransactionFile();
            acceptTheWrite();
            final TransactionAddService service = service();

            final TransactionAddResult result = service.addTransaction(request().build());

            assertThat(result.nextProgram())
                    .as("CDEMO-TO-PROGRAM stays unset on the enter path; only :117, :143 and :673 set it")
                    .isEmpty();
        }

        @Test
        @DisplayName("openScreen on first entry blanks the map and sends it: :120-130")
        void openScreenOnFirstEntryBlanksTheMapAndSendsIt() {
            final TransactionAddService service = service();

            final TransactionAddResult result = service.openScreen(request().build(), null);

            assertThat(result.outcome()).isEqualTo(Outcome.SCREEN_DISPLAYED);
            assertThat(result.cursorField())
                    .as("MOVE -1 TO ACTIDINL at :123 puts the cursor on the account identifier")
                    .isEqualTo(CURSOR_ACCOUNT_ID);
            assertThat(result.screen()).isNotNull();
            assertThat(result.screen().typeCode())
                    .as("MOVE LOW-VALUES TO COTRN2AO at :122 blanks every body field before the send")
                    .isEmpty();
            assertThat(result.screen().amount()).isEmpty();
            assertThat(result.screen().merchantZip()).isEmpty();
            assertThat(result.transactionId())
                    .as("nothing is written on the first-entry arm")
                    .isNull();
            verifyNoInteractions(transactionRepository, cardCrossReferenceRepository, dateValidationService);
        }

        /**
         * {@code POPULATE-HEADER-INFO} at {@code app/cbl/COTRN02C.cbl:552-566} reads the system clock
         * directly, {@code MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA} at {@code :554}, and renders
         * {@code WS-CURDATE-MM-DD-YY} into {@code CURDATEO} at {@code :565}. The Java target relocates that
         * read to the caller: {@code CURDATEI} and {@code CURTIMEI} are request-carried input fields of
         * {@code app/cpy-bms/COTRN02.CPY:36} and {@code :54}, so the service echoes what it received and
         * holds no {@link java.time.Clock}. That relocation is the reason
         * {@link ParagraphCorrespondence#theBeanHoldsNoClock()} can assert what it asserts, and this test
         * pins the other half of the same contract from the outside.
         */
        @Test
        @DisplayName("the header is echoed from the request, so no clock read happens inside the bean: :554")
        void theHeaderIsEchoedFromTheRequestRatherThanFromAClock() {
            final TransactionAddService service = service();
            final TransactionAddRequest submitted =
                    request().header(CURRENT_DATE_TEXT, CURRENT_TIME_TEXT).build();

            final TransactionAddResult result = service.openScreen(submitted, null);

            assertThat(result.screen().currentDate())
                    .as("POPULATE-HEADER-INFO at :518 runs on the first-entry send too")
                    .isEqualTo(CURRENT_DATE_TEXT);
            assertThat(result.screen().currentTime()).isEqualTo(CURRENT_TIME_TEXT);
            assertThat(result.screen().currentDate())
                    .as("the canonical fixed instant renders as 06/10/22 under the MM/DD/YY mask of :561-565,"
                            + " so any clock read inside the bean would be visible right here")
                    .isNotEqualTo("06/10/22");
        }

        @Test
        @DisplayName("a supplied CDEMO-CT02-TRN-SELECTED prefills CARDNINI and runs the enter key: :124-129")
        void aSelectedTransactionPrefillsTheCardNumberAndRunsTheEnterKey() {
            when(cardCrossReferenceRepository.findById(SYNTHETIC_CARD_NUMBER))
                    .thenReturn(Optional.of(crossReference()));
            final TransactionAddService service = service();
            final TransactionAddRequest submitted = request().build();

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.openScreen(submitted, SYNTHETIC_CARD_NUMBER))
                    .as("the body was blanked at :122, so the first blank check at :252 fires immediately")
                    .withMessage(MSG_TYPE_CODE_EMPTY);

            verify(cardCrossReferenceRepository).findById(SYNTHETIC_CARD_NUMBER);
        }

        @Test
        @DisplayName("the selected transaction is padded to CARDNINI's sixteen bytes before the guard runs")
        void theSelectedTransactionIsPaddedToTheCardNumberWidth() {
            final TransactionAddService service = service();
            final TransactionAddRequest submitted = request().build();
            final String fifteenDigits = SYNTHETIC_CARD_NUMBER.substring(0, 15);

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.openScreen(submitted, fifteenDigits))
                    .as("MOVE ... TO CARDNINI at :126-127 is a fixed-width move into PIC X(16), so a "
                            + "fifteen-digit selection arrives space-padded and fails the class test at "
                            + ":212. An unpadded fifteen-digit string would have been numeric and passed, "
                            + "so this failure is the proof that the widening happened")
                    .withMessage(MSG_CARD_NUMBER_NOT_NUMERIC)
                    .satisfies(failure -> assertThat(failure.getFieldName()).isEqualTo("cardNumber"));

            verifyNoInteractions(cardCrossReferenceRepository, transactionRepository, dateValidationService);
        }

        @Test
        @DisplayName("a missing cross reference on the selected transaction redacts the key it reports")
        void aMissingCrossReferenceOnTheSelectedTransactionRedactsTheKey() {
            when(cardCrossReferenceRepository.findById(SYNTHETIC_CARD_NUMBER))
                    .thenReturn(Optional.empty());
            final TransactionAddService service = service();
            final TransactionAddRequest submitted = request().build();

            assertThatExceptionOfType(RecordNotFoundException.class)
                    .isThrownBy(() -> service.openScreen(submitted, SYNTHETIC_CARD_NUMBER))
                    .withMessage(MSG_CARD_NOT_FOUND)
                    .satisfies(failure -> {
                        assertThat(failure.recordKey().orElseThrow())
                                .as("clause D of the project rule: the sixteen-digit key reaches the "
                                        + "repository but never the reported failure")
                                .isEqualTo(CARD_NUMBER_REDACTION)
                                .doesNotContain(SYNTHETIC_CARD_NUMBER);
                        assertThat(failure.recordType().orElseThrow()).isEqualTo("CCXREF");
                    });

            verify(cardCrossReferenceRepository).findById(SYNTHETIC_CARD_NUMBER);
        }

        @Test
        @DisplayName("openWithoutCommArea transfers straight to COSGN00C without sending a map: :115-117")
        void openWithoutCommAreaTransfersToTheSignOnProgram() {
            final TransactionAddService service = service();

            final TransactionAddResult result = service.openWithoutCommArea();

            assertThat(result.outcome()).isEqualTo(Outcome.NAVIGATED_AWAY);
            assertThat(result.nextProgram()).isEqualTo(SIGN_ON_PROGRAM);
            assertThat(result.screen())
                    .as("the EIBCALEN = 0 arm never performs SEND-TRNADD-SCREEN, so no map exists")
                    .isNull();
            verifyNoInteractions(transactionRepository, cardCrossReferenceRepository, dateValidationService);
        }

        @Test
        @DisplayName("PF3 with no carried origin falls back to the literal COMEN01C: :137-138")
        void pf3WithNoCarriedOriginFallsBackToTheMainMenu() {
            final TransactionAddService service = service();

            final TransactionAddResult result =
                    service.submitScreen(AttentionIdentifier.PF3, request().build(), null);

            assertThat(result.outcome()).isEqualTo(Outcome.NAVIGATED_AWAY);
            assertThat(result.nextProgram())
                    .as("SPACES OR LOW-VALUES at :137 selects the literal at :138")
                    .isEqualTo(ORIGIN_PROGRAM);
            assertThat(result.screen()).isNull();
            verifyNoInteractions(transactionRepository, cardCrossReferenceRepository, dateValidationService);
        }

        @Test
        @DisplayName("PF3 with a carried origin transfers to that origin instead: :140-141")
        void pf3WithACarriedOriginTransfersToIt() {
            final TransactionAddService service = service();

            final TransactionAddResult result =
                    service.submitScreen(AttentionIdentifier.PF3, request().build(), "COADM01C");

            assertThat(result.nextProgram())
                    .as("a supplied CDEMO-FROM-PROGRAM becomes CDEMO-TO-PROGRAM at :140-141")
                    .isEqualTo("COADM01C");
        }

        @Test
        @DisplayName("PF3 records this program as the origin for whatever it transfers to: :505-506")
        void pf3RecordsThisProgramAsTheOrigin() {
            final TransactionAddService service = service();

            final TransactionAddResult result =
                    service.submitScreen(AttentionIdentifier.PF3, request().build(), null);

            assertThat(result.outcome())
                    .as("RETURN-TO-PREV-SCREEN moves WS-TRANID and WS-PGMNAME into the carried origin "
                            + "before the XCTL at :508-511, and clears CDEMO-PGM-REENTER at :507")
                    .isEqualTo(Outcome.NAVIGATED_AWAY);
            assertThat(result.message())
                    .as("no message is raised on the navigation path")
                    .isEmpty();
        }

        @Test
        @DisplayName("PF4 blanks every field and re-sends the map: :144-145 and :754-757")
        void pf4BlanksEveryFieldAndResendsTheMap() {
            final TransactionAddService service = service();

            final TransactionAddResult result = service.submitScreen(AttentionIdentifier.PF4,
                    request().build(), ORIGIN_PROGRAM);

            assertThat(result.outcome()).isEqualTo(Outcome.SCREEN_CLEARED);
            assertThat(result.screen()).isNotNull();
            assertThat(result.screen().transactionIdInput())
                    .as("ACTIDINI is carried on the TransactionDto's transactionIdInput component")
                    .isEmpty();
            assertThat(result.screen().cardNumber()).isEmpty();
            assertThat(result.screen().typeCode()).isEmpty();
            assertThat(result.screen().amount()).isEmpty();
            assertThat(result.screen().originatingDate()).isEmpty();
            assertThat(result.screen().processingDate()).isEmpty();
            assertThat(result.screen().merchantZip()).isEmpty();
            assertThat(result.nextProgram())
                    .as("CLEAR-CURRENT-SCREEN is two statements: INITIALIZE-ALL-FIELDS then the send. It "
                            + "never navigates away")
                    .isEmpty();
            verifyNoInteractions(transactionRepository, cardCrossReferenceRepository, dateValidationService);
        }

        @Test
        @DisplayName("PF4 discards the transmitted values rather than echoing them back")
        void pf4DiscardsTheTransmittedValues() {
            final TransactionAddService service = service();

            final TransactionAddResult result = service.submitScreen(AttentionIdentifier.PF4,
                    request().description(DESCRIPTION_TEXT).build(), ORIGIN_PROGRAM);

            assertThat(result.screen().description())
                    .as("RECEIVE-TRNADD-SCREEN at :132 runs first, then INITIALIZE-ALL-FIELDS at :756 "
                            + "overwrites everything it received")
                    .isEmpty();
        }

        @Test
        @DisplayName("PF5 abandons the prefill when the browse itself fails: :477 then the :480 guard")
        void pf5AbandonsThePrefillWhenTheBrowseFails() {
            resolveAccountBranch();
            when(transactionRepository.findFirstByOrderByTransactionIdDesc())
                    .thenThrow(new QueryTimeoutException("transact browse timed out"));
            final TransactionAddService service = service();
            final TransactionAddRequest submitted = request().build();

            assertThatExceptionOfType(CardDemoException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.PF5, submitted,
                            ORIGIN_PROGRAM))
                    .as("READPREV-TRANSACT-FILE sends its own screen, so the IF NOT ERR-FLG-ON prefill at "
                            + ":480-493 is skipped and nothing is written")
                    .withMessage(MSG_TRANSACTION_LOOKUP_FAILURE)
                    .withCauseInstanceOf(QueryTimeoutException.class);

            verify(transactionRepository, never()).saveAndFlush(any(Transaction.class));
            verifyNoInteractions(dateValidationService);
        }

        @Test
        @DisplayName("PF5 validates the key pair FIRST, so a blank pair never reaches the browse: :473")
        void pf5ValidatesTheKeyPairBeforeTheBrowse() {
            final TransactionAddService service = service();
            final TransactionAddRequest submitted = request().accountId("").cardNumber("").build();

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.PF5, submitted,
                            ORIGIN_PROGRAM))
                    .withMessage(MSG_KEY_REQUIRED);

            verify(transactionRepository, never()).findFirstByOrderByTransactionIdDesc();
            verifyNoInteractions(cardCrossReferenceRepository, dateValidationService);
        }
    }
}
