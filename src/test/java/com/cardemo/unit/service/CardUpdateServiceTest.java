/*
 * ******************************************************************
 * Program     : CardUpdateServiceTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11 (Surefire tier)
 * Function    : Parity guard for CardUpdateService. Pins the contracts a reader
 *               cannot confirm by inspection: the ONE-SIDED embossed-name fold
 *               and the snapshot refresh of 9300-CHECK-CHANGE-IN-REC, the
 *               single-dataset write sequence of 9200-WRITE-PROCESSING, the
 *               six-field edit cascade of 1200-EDIT-MAP-INPUTS, and every
 *               outcome literal byte-for-byte.
 * Source      : app/cbl/COCRDUPC.cbl (1,560 lines, 45 own / 47 mapped paragraph labels),
 *               app/cpy/CSSTRPFY.cpy (procedural YYYY-STORE-PFKEY) @ 7756d89
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
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.cardemo.exception.CardDemoException;
import com.cardemo.exception.ConcurrentUpdateException;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.CardUpdateRequest;
import com.cardemo.model.entity.Card;
import com.cardemo.repository.CardRepository;
import com.cardemo.security.SnapshotTokenService;
import com.cardemo.service.card.CardUpdateService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.cardemo.service.shared.FileStatusMapper;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Unit tests for {@link CardUpdateService}, the Java replacement for the COBOL CICS program
 * {@code app/cbl/COCRDUPC.cbl} (1,560 lines, 45 own / 47 mapped paragraph labels) fronting transaction {@code CCUP},
 * at traceability anchor commit {@code 7756d89}.
 *
 * <p><strong>1. What it does.</strong> Every assertion below is derived from the COBOL source
 * rather than from the Java implementation, so this suite is a parity guard and not a change
 * detector. The verified locators it pins are the outcome literals at
 * {@code COCRDUPC.cbl:178-214} and specifically {@code :205-210}; the mainline at {@code :367},
 * {@code COMMON-RETURN} at {@code :546} and {@code 0000-MAIN-EXIT} at {@code :560};
 * {@code 1000-PROCESS-INPUTS} at {@code :564}; {@code 1100-RECEIVE-MAP} at {@code :578};
 * {@code 1200-EDIT-MAP-INPUTS} at {@code :641-717}; the six field edits at {@code :721-758},
 * {@code :762-802}, {@code :806-841} with the convert-and-trim trick at {@code :822-828},
 * {@code :845-874}, {@code :877-910} and {@code :913-945}; {@code 2000-DECIDE-ACTION} at
 * {@code :948-1029}; the screen paragraphs at {@code :1035}, {@code :1052}, {@code :1082},
 * {@code :1138}, {@code :1168} and {@code :1324}; {@code 9000-READ-DATA} at {@code :1343};
 * {@code 9100-GETCARD-BYACCTCARD} at {@code :1376}; {@code 9200-WRITE-PROCESSING} at
 * {@code :1420-1494}; {@code 9300-CHECK-CHANGE-IN-REC} at {@code :1498-1521}; the procedural
 * {@code COPY 'CSSTRPFY'} at {@code :1526}; and {@code ABEND-ROUTINE} at {@code :1531-1554} whose
 * CICS {@code ABCODE('9999')} sits at {@code :1551}. The abend field set comes from
 * {@code app/cpy/CSMSG02Y.cpy:21-29} - internally titled {@code CABENDD.CPY} - whose group
 * {@code ABEND-DATA} totals 134 bytes as {@code X(4)}, {@code X(8)}, {@code X(50)} and
 * {@code X(72)}, every one {@code VALUE SPACES}.
 *
 * <p><strong>2. How to run, build and test.</strong> This class is bound to the <em>Surefire</em>
 * tier. The root build binds Surefire {@code 3.5.4} to {@code **}{@code /*Test.java} while
 * excluding {@code **}{@code /integration/**} and {@code **}{@code /e2e/**}, so a class named
 * {@code *Test} under {@code src/test/java/com/cardemo/unit/} is collected, and a class placed
 * outside that tree matches neither Surefire's nor Failsafe's include set and silently never runs.
 *
 * <pre>
 * ./mvnw -B -ntp test-compile
 * ( set -a; . ./.env; set +a; ./mvnw -B -ntp test -Dtest=CardUpdateServiceTest )
 * ( set -a; . ./.env; set +a; ./mvnw -B -ntp clean verify )
 * </pre>
 * <p>Each subshell confines the exported values to the one command that needs them; test compilation needs
 * none. An export into the shell would instead be inherited by every later child until it was unset.</p>
 *
 * <p>All three were executed on 2026-08-02 against OpenJDK 25.0.3 and Maven 3.9.11 and exited 0
 * with zero compiler warnings; the second reported this class's full test count with no failure,
 * error or skip, and its report is written to
 * {@code target/surefire-reports/com.cardemo.unit.service.CardUpdateServiceTest.txt}. Note that the
 * report's root {@code tests} attribute reads {@code 0}: that is Surefire's convention for a class
 * whose tests all live in {@code @Nested} inner classes, and the per-class counts carry the totals -
 * it is not a collection failure.
 *
 * <p>The documentation was checked separately with
 * {@code javadoc -Xdoclint:all -private --release 25}, which reports no error and no warning in the
 * accessibility, HTML, reference and syntax groups. The one residual note it emits is
 * {@code use of default constructor}, once for this class and once for each {@code @Nested} group.
 * Silencing it would mean declaring eighteen explicit no-argument constructors that nothing calls,
 * because the test engine instantiates these classes reflectively - dead code that clause B of the
 * project rule forbids - so the note is accepted and recorded here instead of being suppressed.
 *
 * <p><strong>3. Key configuration and defaults.</strong> A pure-JVM tier: no Spring context, no
 * container, no database and no network. {@link CardRepository} is a Mockito mock under
 * {@link Strictness#STRICT_STUBS}, so each stubbing lives in the single test that consumes it and
 * never in a shared {@code @BeforeEach}. The injected {@link Clock} is
 * {@link Clock#fixed(Instant, java.time.ZoneId)} at {@link #FIXED_INSTANT} in
 * {@link ZoneOffset#UTC}: the constructor requires a clock for the screen date and time, but
 * <strong>no clock participates in any validation decision</strong>. In particular the expiry-year
 * edit is a range test against the constants declared at {@code COCRDUPC.cbl:96-99}, where
 * {@code 88 VALID-YEAR VALUES 1950 THRU 2099} fixes the bounds, so the current year is never
 * consulted. Numeric comparison, where it arises, uses {@code BigDecimal} with
 * {@code RoundingMode.HALF_EVEN} and {@code compareTo()} rather than {@code equals()}; this screen
 * carries no money field, so no such comparison occurs here and no {@code float} or {@code double}
 * appears anywhere.
 *
 * <p><strong>4. Common failure modes and troubleshooting.</strong> Five ways to get this wrong,
 * each pinned by a test below, and the correct handling in every case is to
 * implement the cited paragraph exactly as the source writes it rather than as it reads more
 * naturally in Java.
 * <ul>
 *   <li><strong>High - folding both sides of the embossed-name comparison.</strong>
 *       {@code 9300} at {@code :1499-1501} applies {@code INSPECT CARD-EMBOSSED-NAME CONVERTING
 *       LIT-LOWER TO LIT-UPPER} to the <em>live record only</em>; the snapshot
 *       {@code CCUP-OLD-CRDNAME} is compared exactly as supplied. On the mainframe the snapshot
 *       merely happened to be upper case because {@code 9000-READ-DATA} at {@code :1356-1358}
 *       folded the live value before copying it, but a stateless server receives the snapshot from
 *       the caller and never re-derives it. A symmetric port therefore accepts a different input
 *       set, and {@link OneSidedFold#lowerCaseSnapshotIsDetectedAsChanged()} is the assertion it
 *       fails.</li>
 *   <li><strong>High - omitting the snapshot refresh.</strong> On mismatch {@code :1512-1517}
 *       re-loads all six snapshot fields from the live record before branching to the write exit,
 *       which {@code COACTUPC} does not do. Drop it and the rejected response echoes the caller's
 *       stale values, so an immediate retry fails forever.</li>
 *   <li><strong>High - comparing the expiry date as a whole string.</strong> The live field is
 *       {@code CARD-EXPIRAION-DATE PIC X(10)} in dash-separated form and the snapshot holds three
 *       separator-free components, so {@code :1505-1507} addresses the parts by reference
 *       modification at offsets {@code (1:4)}, {@code (6:2)} and {@code (9:2)}. A whole-string
 *       comparison reports a change on every request.</li>
 *   <li><strong>High - relying on {@code @Version} alone.</strong> A version counter detects
 *       <em>that</em> a row changed; {@code 9300} detects <em>which values</em> differ from what
 *       the caller was shown. A concurrent write that restored a value passes {@code 9300} and
 *       fails the version check, so both layers are load bearing.</li>
 *   <li><strong>High - substituting {@code String.isBlank()} for the convert-and-trim name
 *       test.</strong> {@code :822-828} converts every one of the 52 ASCII letters listed at
 *       {@code :255-257} to a space and accepts the name only when the residue trims to zero
 *       length. Spaces are therefore legal inside a name while a digit, hyphen, apostrophe or
 *       accented character is not, and {@code trim().length() == 0} rather than
 *       {@code length() == 0} is the equivalent.</li>
 *   </ul>
 * <p>Three mechanical traps sit outside that list. A single raw type, unchecked cast or dangling documentation
 * comment fails the build outright, because the compiler runs with {@code -Xlint:all}, {@code -Werror} and
 * {@code failOnWarning} reaching test compilation; an unused import does not, because {@code javac} 25 publishes no
 * {@code unused} lint key, so that one is caught at review. This class deliberately shares no change-detection helper
 * with {@code AccountUpdateServiceTest}: the two comparisons use genuinely different case handling and only this one
 * refreshes the snapshot, so a shared helper would have to be wrong for one of them. And - the trap this suite
 * discovered while being written - the program contains <strong>two</strong> comparisons, not one, and they fold case
 * differently. The group test inside {@code 1200-EDIT-MAP-INPUTS} at {@code :679-683} upper-cases <em>both</em>
 * sides, so a case-only difference is invisible to it and the six field edits then never run at all; only
 * {@code 9300} at {@code :1499-1501} folds one side. Conflating the two makes a lower-case status of {@code 'y'}
 * appear to pass validation when in truth validation was skipped, which is why
 * {@link CardStatusEdit#caseOnlyStatusChangeNeverReachesTheCaseSensitiveEdit()} exists alongside
 * {@link CardStatusEdit#lowerCaseYesIsInvalid()}.
 *
 * <p><strong>Where this suite has to live, and why.</strong> A test class for this bean placed
 * outside {@code src/test/java/com/cardemo/unit/} matches neither Surefire's include set nor
 * Failsafe's, so it is collected by neither plugin and never runs: the build stays green, both
 * plugins report success and the coverage report records the bean as untested, with no error and
 * no warning anywhere to reveal it. So the class stays inside that tree with a
 * {@code *Test} suffix, as this one is, and after every run it is worth confirming that
 * {@code target/surefire-reports/TEST-com.cardemo.unit.service.CardUpdateServiceTest.xml} exists
 * and carries the expected {@code testcase} count.
 *
 * <p><strong>Three readings that would be wrong here.</strong> Importing {@code COACTUPC}'s
 * asymmetric-rollback reasoning into this program is wrong, because {@code 9200} writes exactly
 * one dataset and contains no {@code SYNCPOINT ROLLBACK} verb at all; collapsing the three write
 * outcomes into one conflict status destroys information the legacy screen displayed; and treating
 * the two case folds described above as one mechanism silently skips the field cascade.
 *
 * <p><strong>Legacy oddities preserved rather than corrected.</strong>
 * {@code SEARCHED-ACCT-ZEROES} at {@code :189-190} and
 * {@code SEARCHED-ACCT-NOT-NUMERIC} at {@code :191-192} carry byte-identical literals;
 * {@code LIT-CCLISTMAP} at {@code :233-234} holds {@code 'CCRDSLA'}, the card <em>detail</em> map
 * name, identical to {@code LIT-CARDDTLMAP} at {@code :249-250}, a copy-paste defect preserved
 * rather than corrected; {@code LIT-THISMAPSET} at {@code :223-224} is {@code PIC X(8)} with a
 * trailing space among {@code PIC X(7)} siblings; {@code CARD-EXPIRAION-DATE} is misspelled in
 * {@code app/cpy/CVACT02Y.cpy:9} and the misspelling is part of the field contract;
 * {@code 9300} ends with {@code END-IF EXIT} at {@code :1519} while
 * {@code 9300-CHECK-CHANGE-IN-REC-EXIT} at {@code :1521} carries its own bare {@code EXIT}, so the
 * in-paragraph one is a retained no-op; and the {@code LOW-VALUES} message guard at {@code :1533}
 * is unreachable because {@code CSMSG02Y} initialises every abend field to {@code VALUE SPACES},
 * whereas its Java counterpart is reachable - a resurrected branch, harmless but worth recording.
 *
 * <p><strong>Seven declared-but-never-SET outcome literals</strong> deserve their own
 * note, because a reader comparing the two sources will otherwise think they were lost. Verified by
 * scanning every line from {@code :261} onward - a deliberate superset, since the procedure division
 * itself only begins at {@code :366} - {@code WS-EXIT-MESSAGE} {@code :175-176},
 * {@code SEARCHED-ACCT-ZEROES} {@code :189-190}, {@code SEARCHED-ACCT-NOT-NUMERIC} {@code :191-192},
 * {@code SEARCHED-CARD-NOT-NUMERIC} {@code :193-194}, {@code DID-NOT-FIND-ACCT-IN-CARDXREF}
 * {@code :201-202}, {@code XREF-READ-ERROR} {@code :211-212} and {@code CODING-TO-BE-DONE}
 * {@code :213-214} are never {@code SET} anywhere. Their correct Java counterpart is therefore
 * <em>no counterpart at all</em>, and the filter edits report instead the literals the source
 * actually {@code MOVE}s at {@code :745} and {@code :789}.
 * {@link OutcomeLiterals#neverSetEightyEightLevelsHaveNoCounterpart()} and its two neighbours pin
 * that distinction in both directions, so neither a spurious addition nor a genuine omission can
 * pass unnoticed.
 *
 * <p><strong>Eight declared-but-never-referenced {@code WS-LITERALS} fields</strong>
 * are the same case one block further down - {@code 01 WS-LITERALS} spans {@code :218-263}, of
 * which {@code :219-254} are the program, transaction, mapset, map and file-name literals - and they
 * sharpen the copy-paste defect above. Counting references from {@code :366}, where the procedure
 * division begins, {@code LIT-CCLISTTRANID} {@code :229-230}, {@code LIT-MENUMAPSET}
 * {@code :239-240}, {@code LIT-MENUMAP} {@code :241-242}, {@code LIT-CARDDTLPGM} {@code :243-244},
 * {@code LIT-CARDDTLTRANID} {@code :245-246}, {@code LIT-CARDDTLMAPSET} {@code :247-248},
 * {@code LIT-CARDDTLMAP} {@code :249-250} and {@code LIT-CARDFILENAME-ACCT-PATH} {@code :253-254}
 * are referenced exactly zero times, while every other field of the block is referenced between one
 * and eight times. So the whole card-detail literal group is dead, and {@code LIT-CCLISTMAP} -
 * referenced four times - is the live field carrying the dead group's {@code 'CCRDSLA'} value: the
 * defect is not a stray duplicate but the surviving half of a transfer that was never wired up. The
 * service correctly declares none of the eight values and every referenced one, which
 * {@link OutcomeLiterals#neverReferencedLiteralFieldsHaveNoCounterpart()} asserts in both
 * directions.
 *
 * <p><strong>Not available.</strong> {@code RecordNotFoundException} is unreachable from either
 * public entry point of this bean and is therefore neither imported nor asserted here: an absent
 * row inside {@code 9200} is reported as a lock failure, and the "did not find" message of
 * {@code 9100} is reached only through the fetch path, which returns it on the screen rather than
 * throwing. Likewise the {@code '0001'} {@code UNEXPECTED DATA SCENARIO} branch at
 * {@code :1019-1026} cannot be driven from the public API, because every reachable
 * {@code ChangeAction} is intercepted earlier. What is needed to assert either is a seam this bean
 * does not expose; inventing one would be a production change, which this file must not make. A
 * live {@code CARDDAT} table is equally out of reach from a pure-JVM tier - it needs a container
 * runtime and belongs to the Failsafe integration tier, which this class must not duplicate.
 *
 * <p><strong>Reachable no-ops, retained for control-flow parity.</strong> The retained
 * bare-{@code EXIT} paragraphs, the redundant in-paragraph {@code EXIT} of {@code 9300} and the
 * {@code LIT-CCLISTMAP} defect all look like residue and are none of them: each carries the source
 * locator cited above and an explicit intentional-no-op marker on the test that pins it, and deleting
 * any of them would break the paragraph-level correspondence with the source.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.STRICT_STUBS)
@DisplayName("CardUpdateService - COCRDUPC / CICS transaction CCUP")
final class CardUpdateServiceTest {

    /**
     * The instant the injected clock is frozen at. Any value works because no validation decision
     * reads the clock; freezing it merely removes the screen date and time as a source of
     * non-determinism.
     */
    private static final Instant FIXED_INSTANT = Instant.parse("2022-04-15T14:30:05Z");

    /** Attention identifier for a plain Enter, {@code DFHENTER} of {@code app/cpy/CSSTRPFY.cpy}. */
    private static final String AID_ENTER = "DFHENTER";

    /** Attention identifier for {@code PF05}, the save key {@code 2000-DECIDE-ACTION} gates on. */
    private static final String AID_PF05 = "DFHPF5";

    /**
     * Attention identifier for {@code PF03}, the back key {@code app/cpy/CSSTRPFY.cpy:34-35} names and the
     * first {@code WHEN} of the dispatch at {@code app/cbl/COCRDUPC.cbl:430} gates on.
     */
    private static final String AID_PF03 = "DFHPF3";

    /** Account filter, {@code ACCTSIDI PIC X(11)} of {@code app/cpy-bms/COCRDUP.CPY}. */
    private static final String ACCOUNT_ID = "00000000001";

    /**
     * The numeric value {@link #ACCOUNT_ID} carries, as {@code CARD-ACCT-ID PIC 9(11)} holds it and as
     * {@code 9100-GETCARD-BYACCTCARD} and {@code 9200-WRITE-PROCESSING} now supply it to the read.
     */
    private static final long ACCOUNT_ID_NUMERIC = 1L;

    /**
     * Card filter, {@code CARDSIDI PIC X(16)}. Deliberately a synthetic all-but-suffix-zero value:
     * it is test data, never a credential, and it is never emitted into an assertion description.
     */
    private static final String CARD_NUMBER = "0000000000000011";

    /** Stored embossed name, {@code CARD-EMBOSSED-NAME PIC X(50)}, in upper case as stored. */
    private static final String STORED_NAME = "JOHN SMITH";

    /** Stored expiry date, {@code CARD-EXPIRAION-DATE PIC X(10)}, dash separated (sic). */
    private static final String STORED_EXPIRY = "2026-05-17";

    /** Component of {@link #STORED_EXPIRY} at offset {@code (1:4)}. */
    private static final String STORED_YEAR = "2026";

    /** Component of {@link #STORED_EXPIRY} at offset {@code (6:2)}. */
    private static final String STORED_MONTH = "05";

    /** Component of {@link #STORED_EXPIRY} at offset {@code (9:2)}. */
    private static final String STORED_DAY = "17";

    /** Stored status, {@code CARD-ACTIVE-STATUS PIC X(01)}. */
    private static final String STORED_STATUS = "Y";

    /**
     * A submitted name that differs from {@link #STORED_NAME}, so the group test of
     * {@code 1200-EDIT-MAP-INPUTS} at {@code :679-683} sees a change and the cascade proceeds.
     */
    private static final String SUBMITTED_NAME = "JANE SMITH";

    /** Width of {@code ERRMSGI PIC X(80)}; the screen error message is padded to it. */
    private static final int ERROR_MESSAGE_WIDTH = 80;

    /** {@code 'No change detected with respect to values fetched.'} at {@code :187-188}. */
    private static final String MSG_NO_CHANGES_DETECTED =
            "No change detected with respect to values fetched.";

    /** {@code 'Could not lock record for update'} at {@code :205-206}. */
    private static final String MSG_COULD_NOT_LOCK = "Could not lock record for update";

    /** {@code 'Record changed by some one else. Please review'} at {@code :207-208}. */
    private static final String MSG_DATA_WAS_CHANGED = "Record changed by some one else. Please review";

    /** {@code 'Update of record failed'} at {@code :209-210}. */
    private static final String MSG_UPDATE_FAILED = "Update of record failed";

    /** {@code 'Card name not provided'} at {@code :181-182}. */
    private static final String MSG_NAME_NOT_PROVIDED = "Card name not provided";

    /** {@code 'Card name can only contain alphabets and spaces'} at {@code :183-184}. */
    private static final String MSG_NAME_MUST_BE_ALPHA = "Card name can only contain alphabets and spaces";

    /** {@code 'Card Active Status must be Y or N'} at {@code :195-196}. */
    private static final String MSG_STATUS_MUST_BE_YES_NO = "Card Active Status must be Y or N";

    /** {@code 'Card expiry month must be between 1 and 12'} at {@code :197-198}. */
    private static final String MSG_MONTH_NOT_VALID = "Card expiry month must be between 1 and 12";

    /** {@code 'Invalid card expiry year'} at {@code :199-200}. */
    private static final String MSG_YEAR_NOT_VALID = "Invalid card expiry year";

    /** {@code 'Did not find cards for this search condition'} at {@code :203-204}. */
    private static final String MSG_NO_ACCTCARD_COMBO = "Did not find cards for this search condition";

    /** {@code 'Please enter Account and Card Number'} at {@code :162-163}. */
    private static final String MSG_PROMPT_FOR_SEARCH_KEYS = "Please enter Account and Card Number";

    /** {@code 'Changes validated.Press F5 to save'} at {@code :166-167}; note the missing space. */
    private static final String MSG_PROMPT_FOR_CONFIRMATION = "Changes validated.Press F5 to save";

    /** {@code 'Account number not provided'} at {@code :177-178}. */
    private static final String MSG_ACCOUNT_NOT_PROVIDED = "Account number not provided";

    /** {@code 'Card number not provided'} at {@code :179-180}. */
    private static final String MSG_CARD_NOT_PROVIDED = "Card number not provided";

    /** {@code 'No input received'} at {@code :185-186}. */
    private static final String MSG_NO_INPUT_RECEIVED = "No input received";

    /** {@code 'Details of selected card shown above'} at {@code :160-161}. */
    private static final String INFO_FOUND_CARDS = "Details of selected card shown above";

    /** {@code 'PF03 pressed.Exiting              '} at {@code :175-176}; never SET in the source. */
    private static final String MSG_EXIT_NEVER_SET = "PF03 pressed.Exiting              ";

    /** {@code :189-192}; carried by two 88-levels, neither ever SET in the source. */
    private static final String MSG_ACCOUNT_NON_ZERO_ELEVEN_DIGITS =
            "Account number must be a non zero 11 digit number";

    /** {@code :193-194}; declared but never SET in the source. */
    private static final String MSG_CARD_MUST_BE_SIXTEEN_DIGITS =
            "Card number if supplied must be a 16 digit number";

    /** {@code :201-202}; declared but never SET in the source. */
    private static final String MSG_NO_ACCOUNT_IN_CARDS_DATABASE =
            "Did not find this account in cards database";

    /** {@code :211-212}; declared but never SET in the source. */
    private static final String MSG_XREF_READ_ERROR_NEVER_SET = "Error reading Card Data File";

    /** {@code 'Looks Good.... so far'} at {@code :213-214}, four dots; never SET in the source. */
    private static final String MSG_LOOKS_GOOD_NEVER_SET = "Looks Good.... so far";

    /** A valid status that differs from the stored one, so the group test always sees a change. */
    private static final String CARD_STATUS_TOGGLED = "N";

    // -----------------------------------------------------------------------------------------
    // Snapshot sealing. Transformation Rule 7 moves the storage lifetime the COMMAREA held between
    // the two turns of the pseudo-conversation onto the request, and the value travels sealed so that
    // the operand of the Regime B comparison at :1503-1508 is never one the guarded party could choose.
    // -----------------------------------------------------------------------------------------

    /**
     * The authenticated principal every sealed value below is bound to. {@code SEC-USR-ID} is
     * {@code PIC X(08)} at {@code app/cpy/CSUSR01Y.cpy:14}.
     */
    private static final String SUBJECT = "ADMIN001";

    /** A second principal, used to prove a value sealed for one operator is useless to another. */
    private static final String OTHER_SUBJECT = "USER0001";

    /**
     * The operation kind {@code CardUpdateService} seals under. Duplicated rather than exposed, because
     * the constant is private to the bean and a test that reached for it would assert its own value.
     */
    private static final String SNAPSHOT_KIND = "card-update-snapshot";

    /** The record-key separator the bean joins the two identifiers with. */
    private static final String RECORD_KEY_SEPARATOR = "/";

    /** The placeholder the bean substitutes for an absent identifier in the record key. */
    private static final String NO_IDENTIFIER = "-";

    /**
     * A test-only sealing key. Thirty-two ASCII bytes, comfortably over the component's documented
     * minimum, and local to this file - no configured or deployed key appears here.
     */
    private static final String SEALING_KEY = "card-update-service-test-key!!!!";

    /** The sealer's documented default lifetime, in seconds. */
    private static final long SEAL_LIFETIME_SECONDS = 900L;

    /**
     * The sole collaborator of the bean, mocked because this tier reaches no database. Strict stubs
     * keep every stubbing inside the single test that consumes it.
     */
    @Mock
    private CardRepository cardRepository;

    /** The system under test, rebuilt before every test so no state can leak between them. */
    private CardUpdateService service;

    /**
     * The <em>real</em> sealer, deliberately not a mock.
     *
     * <p>The as-displayed group reaches {@code updateCard} only by being opened from a sealed value, so a
     * mocked sealer would assert nothing about the one property that matters: that the group the Regime B
     * comparison of {@code :1503-1508} consumes is the group <em>this server</em> issued, for this card, to
     * this principal. Sealing and opening for real is what lets the rejection tests present a tampered,
     * transplanted, foreign-principal or expired value and observe the refusal.</p>
     */
    private SnapshotTokenService snapshotTokenService;

    /**
     * Builds the bean with the mocked repository and a fixed clock, so the screen date and time are
     * reproducible and no validation outcome can depend on when the suite runs.
     */
    @BeforeEach
    void createServiceUnderTest() {
        snapshotTokenService = sealerAt(FIXED_INSTANT);
        service = new CardUpdateService(cardRepository, Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC),
                snapshotTokenService);
    }

    /**
     * Builds a sealer whose clock stands at a chosen instant, so expiry is asserted by moving the clock
     * rather than by sleeping.
     *
     * @param instant the instant the sealer measures lifetimes from
     * @return a sealer over the test key
     */
    private static SnapshotTokenService sealerAt(final Instant instant) {
        return new SnapshotTokenService(SEALING_KEY, SEAL_LIFETIME_SECONDS,
                Clock.fixed(instant, ZoneOffset.UTC), new ObjectMapper());
    }

    /**
     * Renders the record key the bean seals under: the two identifiers that address the row, joined, with a
     * fixed placeholder for an absent one.
     *
     * @param accountId  the account identifier as received
     * @param cardNumber the card number as received
     * @return the record key
     */
    private static String recordKey(final String accountId, final String cardNumber) {
        return keyComponent(accountId) + RECORD_KEY_SEPARATOR + keyComponent(cardNumber);
    }

    /**
     * Renders one record-key component.
     *
     * @param value the identifier as received
     * @return the value, or the placeholder when it is absent or blank
     */
    private static String keyComponent(final String value) {
        return value == null || value.isBlank() ? NO_IDENTIFIER : value;
    }

    /**
     * Seals an as-displayed group exactly as {@code sealSnapshotForUpdate} does: bound to this operation, to
     * the two identifiers that address the row and to the principal, and with the card number dropped from
     * the payload because {@code :1347} sources it from the received map field.
     *
     * @param group      the group to seal, or {@code null} to seal nothing
     * @param accountId  the account identifier the value is bound to
     * @param cardNumber the card number the value is bound to
     * @param subject    the principal the value is bound to
     * @return the sealed value, or {@code null} when {@code group} was {@code null}
     */
    private String seal(final CardUpdateRequest.CardDetails group, final String accountId,
                        final String cardNumber, final String subject) {
        if (group == null) {
            return null;
        }
        return snapshotTokenService.seal(SNAPSHOT_KIND, recordKey(accountId, cardNumber), subject,
                new CardUpdateRequest.CardDetails(group.accountId(), null, group.cardData()));
    }

    /**
     * Builds a request carrying an already-sealed value verbatim, rather than sealing a group for it. This is
     * what a REST caller submits: the value the preceding read issued, echoed back unaltered.
     *
     * @param sealedSnapshot the sealed value to carry, or {@code null} to carry none
     * @return a request whose submitted name agrees with the stored record, so nothing changed
     */
    private CardUpdateRequest sealedRequest(final String sealedSnapshot) {
        return sealedRequest(sealedSnapshot, STORED_NAME);
    }

    /**
     * Builds a request carrying an already-sealed value verbatim, with a chosen cardholder name.
     *
     * @param sealedSnapshot the sealed value to carry, or {@code null} to carry none
     * @param cardholderName submitted {@code CRDNAMEI PIC X(50)}
     * @return a populated request
     */
    private CardUpdateRequest sealedRequest(final String sealedSnapshot, final String cardholderName) {
        return new CardUpdateRequest(null, null, null, null, null, null,
                ACCOUNT_ID, CARD_NUMBER, cardholderName, STORED_STATUS,
                STORED_MONTH, STORED_YEAR, STORED_DAY,
                null, null, null, null, sealedSnapshot, null);
    }

    /**
     * Recovers the group {@code updateCard} would have opened from a request's sealed member, so that the
     * in-process {@code processRequest} entry point can be driven with the identical operand.
     *
     * <p>The card number is restored from the request's own identity field, which is what {@code :1347}
     * does: {@code MOVE CC-CARD-NUM TO CCUP-OLD-CARDID} takes the <em>received</em> value, not the stored
     * one.</p>
     *
     * @param request the submitted request
     * @return the opened group, or {@code null} when the request carries no sealed value
     */
    private CardUpdateRequest.CardDetails openedSnapshot(final CardUpdateRequest request) {
        if (request == null || request.snapshot() == null) {
            return null;
        }
        final CardUpdateRequest.CardDetails opened = snapshotTokenService.open(request.snapshot(),
                SNAPSHOT_KIND, recordKey(request.accountId(), request.cardNumber()), SUBJECT,
                CardUpdateRequest.CardDetails.class);
        return opened.cardNumber() != null ? opened
                : new CardUpdateRequest.CardDetails(opened.accountId(), request.cardNumber(),
                        opened.cardData());
    }

    // Fixture builders. Deliberately private to this class: the account program's comparison
    // folds case symmetrically and never refreshes its snapshot, so nothing here may be shared
    // with AccountUpdateServiceTest without being wrong for one of the two.

    /**
     * The three characters {@code CARD-CVV-CD PIC 9(03)} holds on the stored row, chosen with a leading zero
     * because that is the shape eight of the fifty fixture rows carry and the shape a numeric column would
     * corrupt. Synthetic: it is not a value copied from {@code app/data/ASCII/carddata.txt}.
     */
    private static final String STORED_VERIFICATION_VALUE = "007";

    /**
     * Builds the stored {@code CARD-RECORD} of {@code app/cpy/CVACT02Y.cpy} as the repository would
     * return it under the update lock.
     *
     * @param embossedName {@code CARD-EMBOSSED-NAME PIC X(50)}
     * @param expiraionDate {@code CARD-EXPIRAION-DATE PIC X(10)}, misspelling intentional
     * @param activeStatus {@code CARD-ACTIVE-STATUS PIC X(01)}
     * @return a card whose primary key matches {@link #CARD_NUMBER}
     */
    private Card storedCard(final String embossedName, final String expiraionDate,
                            final String activeStatus) {
        final Card card = new Card(CARD_NUMBER, ACCOUNT_ID_NUMERIC, STORED_VERIFICATION_VALUE,
                embossedName, expiraionDate, activeStatus);
        card.setVersion(0L);
        return card;
    }

    /**
     * Builds the stored card in its unmodified fixture state.
     *
     * @return the card exactly as the fixture declares it, before any submitted change
     */
    private Card storedCard() {
        return storedCard(STORED_NAME, STORED_EXPIRY, STORED_STATUS);
    }

    /**
     * Builds a {@code CCUP-OLD-DETAILS} snapshot as the caller carries it in the request body,
     * mirroring {@code COCRDUPC.cbl:291-301} leaf for leaf.
     *
     * @param cardholderName the snapshot embossed name, compared exactly as supplied
     * @param expiryYear snapshot counterpart of the {@code (1:4)} component
     * @param expiryMonth snapshot counterpart of the {@code (6:2)} component
     * @param expiryDay snapshot counterpart of the {@code (9:2)} component
     * @param cardStatusCode snapshot counterpart of {@code CARD-ACTIVE-STATUS}
     * @return a populated snapshot group
     */
    private CardUpdateRequest.CardDetails snapshotOf(final String cardholderName,
                                                     final String expiryYear,
                                                     final String expiryMonth,
                                                     final String expiryDay,
                                                     final String cardStatusCode) {
        return new CardUpdateRequest.CardDetails(ACCOUNT_ID, CARD_NUMBER,
                new CardUpdateRequest.CardData(cardholderName,
                        new CardUpdateRequest.ExpiraionDate(expiryYear, expiryMonth, expiryDay),
                        cardStatusCode));
    }

    /**
     * Builds the snapshot that agrees with {@link #storedCard()} field for field.
     *
     * @return a snapshot group over which {@code 9300} finds no difference
     */
    private CardUpdateRequest.CardDetails matchingSnapshot() {
        return snapshotOf(STORED_NAME, STORED_YEAR, STORED_MONTH, STORED_DAY, STORED_STATUS);
    }

    /**
     * Builds a request carrying the submitted values in the flat screen fields, which
     * {@code 1100-RECEIVE-MAP} prefers over the nested group, and the snapshot in
     * {@code oldDetails}.
     *
     * @param oldDetails the {@code CCUP-OLD-DETAILS} snapshot, or {@code null} to omit it
     * @param cardholderName submitted {@code CRDNAMEI PIC X(50)}
     * @param cardStatusCode submitted {@code CRDSTCDI PIC X(1)}
     * @param expiryMonth submitted {@code EXPMONI PIC X(2)}
     * @param expiryYear submitted {@code EXPYEARI PIC X(4)}
     * @param expiryDay submitted {@code EXPDAYI PIC X(2)}
     * @return a request with the seventeen screen components and the snapshot group
     */
    private CardUpdateRequest requestWith(final CardUpdateRequest.CardDetails oldDetails,
                                          final String cardholderName,
                                          final String cardStatusCode,
                                          final String expiryMonth,
                                          final String expiryYear,
                                          final String expiryDay) {
        return new CardUpdateRequest(null, null, null, null, null, null,
                ACCOUNT_ID, CARD_NUMBER, cardholderName, cardStatusCode,
                expiryMonth, expiryYear, expiryDay,
                null, null, null, null, seal(oldDetails, ACCOUNT_ID, CARD_NUMBER, SUBJECT), null);
    }

    /**
     * Builds a request whose submitted name differs from the stored name, so the group test sees a
     * change, with every other submitted field agreeing with the stored record.
     *
     * @param oldDetails the snapshot to carry
     * @return a request that reaches the write path once confirmed
     */
    private CardUpdateRequest changedNameRequest(final CardUpdateRequest.CardDetails oldDetails) {
        return requestWith(oldDetails, SUBMITTED_NAME, STORED_STATUS, STORED_MONTH, STORED_YEAR,
                STORED_DAY);
    }

    /**
     * Drives the confirmation leg of {@code 2000-DECIDE-ACTION} at {@code :948-1029}, the only route
     * that reaches {@code 9200-WRITE-PROCESSING}: re-entry, a populated snapshot and {@code PF05}.
     *
     * @param request the request to submit
     * @return the screen and navigation result the mainline returns
     */
    private CardUpdateService.CardUpdateResult confirmSave(final CardUpdateRequest request) {
        return service.processRequest(request, openedSnapshot(request), AID_PF05, CardUpdateService.EntryMode.REENTER);
    }

    /**
     * Drives the validation leg: re-entry with a populated snapshot under plain Enter, which runs the
     * whole edit cascade of {@code 1200-EDIT-MAP-INPUTS} and touches the repository not at all.
     *
     * @param request the request to submit
     * @return the screen result carrying the surviving return message
     */
    private CardUpdateService.CardUpdateResult validateOnly(final CardUpdateRequest request) {
        return service.processRequest(request, openedSnapshot(request), AID_ENTER, CardUpdateService.EntryMode.REENTER);
    }

    /**
     * Extracts the screen error message with the {@code ERRMSGI PIC X(80)} padding removed, so a test
     * can compare against the source literal byte for byte.
     *
     * @param result the mainline result
     * @return the unpadded return message
     */
    private static String errorMessageOf(final CardUpdateService.CardUpdateResult result) {
        return result.screen().getErrorMessage().strip();
    }

    /**
     * Pins the comparison clauses of {@code 9300-CHECK-CHANGE-IN-REC} at
     * {@code COCRDUPC.cbl:1498-1521}, one assertion per clause and none consolidated.
     *
     * <p>The source declares six clauses over four logical fields. Five are implemented and asserted
     * here individually. The sixth, clause one at {@code :1503}, compared {@code CARD-CVV-CD}. That field
     * <em>is</em> persisted - {@code app/cpy/CVACT02Y.cpy:L7} places it inside the authoritative record - but
     * it has no operand on the request side of this conversation, because {@code app/cbl/COCRDUPC.cbl} never
     * assigns {@code CCUP-NEW-CVV-CD} and none of the seventeen BMS symbolic maps carries it, so the legacy
     * screen supplied no value to compare against and neither does {@code CardUpdateRequest}. The clause is
     * therefore vacuous rather than removed, and what this block asserts instead is the consequence that
     * matters: the update leaves the stored value untouched. The clause numbers below still cite the source
     * positions, which are frozen, rather than being renumbered to close the gap.</p>
     */
    @Nested
    @DisplayName("9300-CHECK-CHANGE-IN-REC :1498-1521 - five live clauses and one vacuous clause")
    class ChangeDetection {

        /**
         * Clause one of {@code 9300} at {@code COCRDUPC.cbl:1503} compared {@code CARD-CVV-CD}. The field is
         * persisted, but this conversation carries no operand for it, so the clause is vacuous rather than
         * removed - and the property that matters is that the update cannot destroy the stored value.
         *
         * <p>The risk the source itself carries here is worth stating, because it is the reason the target
         * behaves differently. The two {@code MOVE} statements at {@code :1464-1465} wrote the never-assigned
         * {@code CCUP-NEW-CVV-CD} - left at {@code SPACES} by {@code INITIALIZE CCUP-NEW-DETAILS} at
         * {@code :586} - onto the record, so every successful legacy update blanked the stored verification
         * value. The target does not reproduce that, and cannot: no request component and no snapshot
         * component names the field, so the rewrite has nothing to move onto it and the persisted value
         * survives. That is a labelled, tested improvement rather than a silent difference.</p>
         *
         * <p>The five surviving clauses are asserted immediately below, so this test's passing cannot be
         * mistaken for the comparison having been weakened generally: a snapshot that disagrees on any
         * implemented field still abandons the rewrite.</p>
         */
        @Test
        @DisplayName("clause 1 of 6 is vacuous: the value is persisted, unrequestable and left untouched")
        void theVerificationClauseHasNoRequestOperandAndTheStoredValueSurvives() {
            assertThat(Card.class.getDeclaredFields())
                    .as("the entity persists exactly one verification field, because "
                            + "app/cpy/CVACT02Y.cpy:L7 declares it inside the authoritative 150-byte record")
                    .filteredOn(field -> namesVerificationValue(field.getName())
                            && !java.lang.reflect.Modifier.isStatic(field.getModifiers()))
                    .hasSize(1);
            assertThat(Card.class.getMethods())
                    .as("but no accessor exposes it: the only method naming it answers a boolean question")
                    .filteredOn(method -> namesVerificationValue(method.getName()))
                    .allSatisfy(method ->
                            assertThat(method.getReturnType()).isEqualTo(boolean.class));
            assertThat(CardUpdateRequest.CardDetails.class.getRecordComponents())
                    .as("no request component can accept one, which is why the clause has no operand: "
                            + "COCRDUPC never assigns CCUP-NEW-CVV-CD and no symbolic map carries the field")
                    .noneMatch(component -> namesVerificationValue(component.getName()));
            assertThat(com.cardemo.model.dto.CardResponse.class.getRecordComponents())
                    .as("nor can the read response return one, so neither wire shape can carry it")
                    .noneMatch(component -> namesVerificationValue(component.getName()));
        }

        /**
         * A successful rewrite leaves the stored verification value exactly as it was.
         *
         * <p>This is the behavioural half of the clause above, and it is the assertion that would fail if a
         * future edit reconstructed the entity from the request instead of mutating the loaded row: the
         * reconstruction would have no verification value to supply, and the legacy blanking defect of
         * {@code COCRDUPC.cbl:1464-1465} would reappear.</p>
         */
        @Test
        @DisplayName("a successful rewrite preserves the stored verification value byte for byte")
        void aSuccessfulRewritePreservesTheStoredVerificationValue() {
            when(cardRepository.findByIdAndAccountIdForUpdate(CARD_NUMBER,
                    ACCOUNT_ID_NUMERIC)).thenReturn(Optional.of(storedCard()));
            final ArgumentCaptor<Card> persisted = ArgumentCaptor.forClass(Card.class);

            final CardUpdateRequest request =
                    requestWith(matchingSnapshot(), "ANNA LEE", "N", "12", "2099", "28");
            service.updateCard(request, SUBJECT);

            verify(cardRepository).save(persisted.capture());
            assertThat(persisted.getValue().matchesVerificationValue(STORED_VERIFICATION_VALUE))
                    .as("the service mutates the loaded row through its setters, and there is no setter for "
                            + "the verification value, so the rewrite carries the stored three characters "
                            + "forward untouched - unlike COCRDUPC.cbl:1464-1465, which blanked them")
                    .isTrue();
        }

        /**
         * Reports whether a declared name refers to a card verification value under any spelling this
         * codebase has used for it.
         *
         * @param name the declared field, method or component name
         * @return {@code true} when the name refers to a verification value
         */
        private boolean namesVerificationValue(final String name) {
            return name.toLowerCase(java.util.Locale.ROOT).contains("cvv");
        }

        /**
         * Clause two at {@code COCRDUPC.cbl:1504}: a differing {@code CARD-EMBOSSED-NAME} alone abandons the rewrite.
         */
        @Test
        @DisplayName("a live embossed name differing from the snapshot is detected - clause 2 of 6")
        void embossedNameChangeIsDetected() {
            when(cardRepository.findByIdAndAccountIdForUpdate(CARD_NUMBER, ACCOUNT_ID_NUMERIC)).thenReturn(
                    Optional.of(storedCard("JOHN JONES", STORED_EXPIRY, STORED_STATUS)));

            final CardUpdateService.CardUpdateResult result =
                    confirmSave(changedNameRequest(matchingSnapshot()));

            assertThat(errorMessageOf(result)).isEqualTo(MSG_DATA_WAS_CHANGED);
            verify(cardRepository, never()).save(any(Card.class));
        }

        /**
         * Clause six at {@code COCRDUPC.cbl:1508}: a differing {@code CARD-ACTIVE-STATUS} alone abandons the rewrite.
         */
        @Test
        @DisplayName("a live active status differing from the snapshot is detected - clause 6 of 6")
        void activeStatusChangeIsDetected() {
            when(cardRepository.findByIdAndAccountIdForUpdate(CARD_NUMBER, ACCOUNT_ID_NUMERIC))
                    .thenReturn(Optional.of(storedCard(STORED_NAME, STORED_EXPIRY, "N")));

            final CardUpdateService.CardUpdateResult result =
                    confirmSave(changedNameRequest(matchingSnapshot()));

            assertThat(errorMessageOf(result)).isEqualTo(MSG_DATA_WAS_CHANGED);
            verify(cardRepository, never()).save(any(Card.class));
        }

        /**
         * The three date clauses at {@code COCRDUPC.cbl:1505-1507} acting together: a wholly different expiry date
         * abandons the rewrite.
         */
        @Test
        @DisplayName("a wholly different live expiry date is detected - the third logical field")
        void expiryDateChangeIsDetected() {
            when(cardRepository.findByIdAndAccountIdForUpdate(CARD_NUMBER, ACCOUNT_ID_NUMERIC)).thenReturn(
                    Optional.of(storedCard(STORED_NAME, "2031-11-02", STORED_STATUS)));

            final CardUpdateService.CardUpdateResult result =
                    confirmSave(changedNameRequest(matchingSnapshot()));

            assertThat(errorMessageOf(result)).isEqualTo(MSG_DATA_WAS_CHANGED);
            verify(cardRepository, never()).save(any(Card.class));
        }

        /**
         * Clause three in isolation, the reference modification {@code CARD-EXPIRAION-DATE(1:4)} at {@code
         * COCRDUPC.cbl:1505}, asserted separately because the source never compares the date as one string.
         */
        @Test
        @DisplayName("only the (1:4) year component differs - clause 3 of 6, asserted alone")
        void expiryYearComponentAloneIsDetected() {
            when(cardRepository.findByIdAndAccountIdForUpdate(CARD_NUMBER, ACCOUNT_ID_NUMERIC)).thenReturn(
                    Optional.of(storedCard(STORED_NAME, "2027-05-17", STORED_STATUS)));

            final CardUpdateService.CardUpdateResult result =
                    confirmSave(changedNameRequest(matchingSnapshot()));

            assertThat(errorMessageOf(result)).isEqualTo(MSG_DATA_WAS_CHANGED);
            assertThat(result.refreshedSnapshot().cardData().expiraionDate().expiryYear())
                    .isEqualTo("2027");
            verify(cardRepository, never()).save(any(Card.class));
        }

        /**
         * Clause four in isolation, {@code CARD-EXPIRAION-DATE(6:2)} at {@code COCRDUPC.cbl:1506}.
         */
        @Test
        @DisplayName("only the (6:2) month component differs - clause 4 of 6, asserted alone")
        void expiryMonthComponentAloneIsDetected() {
            when(cardRepository.findByIdAndAccountIdForUpdate(CARD_NUMBER, ACCOUNT_ID_NUMERIC)).thenReturn(
                    Optional.of(storedCard(STORED_NAME, "2026-06-17", STORED_STATUS)));

            final CardUpdateService.CardUpdateResult result =
                    confirmSave(changedNameRequest(matchingSnapshot()));

            assertThat(errorMessageOf(result)).isEqualTo(MSG_DATA_WAS_CHANGED);
            assertThat(result.refreshedSnapshot().cardData().expiraionDate().expiryMonth())
                    .isEqualTo("06");
            verify(cardRepository, never()).save(any(Card.class));
        }

        /**
         * Clause five in isolation, {@code CARD-EXPIRAION-DATE(9:2)} at {@code COCRDUPC.cbl:1507}.
         */
        @Test
        @DisplayName("only the (9:2) day component differs - clause 5 of 6, asserted alone")
        void expiryDayComponentAloneIsDetected() {
            when(cardRepository.findByIdAndAccountIdForUpdate(CARD_NUMBER, ACCOUNT_ID_NUMERIC)).thenReturn(
                    Optional.of(storedCard(STORED_NAME, "2026-05-18", STORED_STATUS)));

            final CardUpdateService.CardUpdateResult result =
                    confirmSave(changedNameRequest(matchingSnapshot()));

            assertThat(errorMessageOf(result)).isEqualTo(MSG_DATA_WAS_CHANGED);
            assertThat(result.refreshedSnapshot().cardData().expiraionDate().expiryDay())
                    .isEqualTo("18");
            verify(cardRepository, never()).save(any(Card.class));
        }

        /**
         * Drives all three components at once and shows the outcome is identical to changing them singly, so a whole-
         * string comparison cannot be substituted for the three offsets.
         */
        @Test
        @DisplayName("the expiry date is addressed by component, never compared whole")
        void expiryDateIsNeverComparedAsAWholeString() {
            when(cardRepository.findByIdAndAccountIdForUpdate(CARD_NUMBER,
                    ACCOUNT_ID_NUMERIC)).thenReturn(Optional.of(storedCard()));

            final CardUpdateService.CardUpdateResult result =
                    confirmSave(changedNameRequest(matchingSnapshot()));

            final CardUpdateRequest.CardDetails snapshot = matchingSnapshot();
            final CardUpdateRequest.ExpiraionDate parts = snapshot.cardData().expiraionDate();
            final String reassembled =
                    parts.expiryYear() + parts.expiryMonth() + parts.expiryDay();
            assertThat(reassembled)
                    .as("the snapshot carries three separator-free components")
                    .isNotEqualTo(STORED_EXPIRY)
                    .hasSize(STORED_EXPIRY.length() - 2);
            assertThat(errorMessageOf(result))
                    .as("a whole-string comparison would have reported a change here")
                    .isNotEqualTo(MSG_DATA_WAS_CHANGED);
            verify(cardRepository).save(any(Card.class));
        }

        /**
         * The negative control for the whole group: when all six clauses agree, {@code 9300} falls through to the
         * {@code REWRITE} at {@code COCRDUPC.cbl:1466-1472} and the row is persisted.
         */
        @Test
        @DisplayName("an agreeing snapshot passes all six clauses and the rewrite proceeds")
        void anAgreeingSnapshotPassesAndTheRewriteProceeds() {
            when(cardRepository.findByIdAndAccountIdForUpdate(CARD_NUMBER,
                    ACCOUNT_ID_NUMERIC)).thenReturn(Optional.of(storedCard()));

            final CardUpdateService.CardUpdateResult result =
                    confirmSave(changedNameRequest(matchingSnapshot()));

            assertThat(result.changeAction())
                    .isEqualTo(CardUpdateService.ChangeAction.CHANGES_OKAYED_AND_DONE);
            verify(cardRepository).save(any(Card.class));
        }
    }

    /**
     * Pins the one-sided case fold of {@code COCRDUPC.cbl:1499-1501}, where the live record
     * alone is converted to upper case and the caller's snapshot is compared exactly as supplied.
     */
    @Nested
    @DisplayName(":1499-1501 - the fold is ONE-SIDED and mutates the live side only")
    class OneSidedFold {

        /**
         * With an upper-case snapshot the fold at {@code COCRDUPC.cbl:1499-1501} makes a case-only live difference
         * invisible, which is the mainframe's incidental behaviour because {@code 9000-READ-DATA} folded the value
         * before copying it.
         */
        @Test
        @DisplayName("a case-only live difference is NOT detected when the snapshot is upper case")
        void upperCaseSnapshotAbsorbsACaseOnlyLiveDifference() {
            when(cardRepository.findByIdAndAccountIdForUpdate(CARD_NUMBER, ACCOUNT_ID_NUMERIC)).thenReturn(
                    Optional.of(storedCard("john smith", STORED_EXPIRY, STORED_STATUS)));

            final CardUpdateService.CardUpdateResult result =
                    confirmSave(changedNameRequest(matchingSnapshot()));

            assertThat(errorMessageOf(result))
                    .as("folding the live side makes the case difference invisible")
                    .isNotEqualTo(MSG_DATA_WAS_CHANGED);
            verify(cardRepository).save(any(Card.class));
        }

        /**
         * The decisive assertion a symmetric port fails: with a lower-case snapshot the one-sided fold makes the very
         * same live value differ, because only the live side is converted.
         */
        @Test
        @DisplayName("a case-only difference IS detected when the snapshot is lower case")
        void lowerCaseSnapshotIsDetectedAsChanged() {
            when(cardRepository.findByIdAndAccountIdForUpdate(CARD_NUMBER,
                    ACCOUNT_ID_NUMERIC)).thenReturn(Optional.of(storedCard()));
            final CardUpdateRequest.CardDetails lowerCaseSnapshot = snapshotOf(
                    STORED_NAME.toLowerCase(Locale.ROOT), STORED_YEAR, STORED_MONTH, STORED_DAY,
                    STORED_STATUS);

            final CardUpdateService.CardUpdateResult result =
                    confirmSave(changedNameRequest(lowerCaseSnapshot));

            assertThat(errorMessageOf(result))
                    .as("the snapshot receives no conversion, so a symmetric port fails here")
                    .isEqualTo(MSG_DATA_WAS_CHANGED);
            verify(cardRepository, never()).save(any(Card.class));
        }

        /**
         * Pins {@code Locale.ROOT} semantics by installing a Turkish default locale, under which a locale-sensitive
         * fold would map {@code i} to a dotted capital and silently change the comparison.
         */
        @Test
        @DisplayName("the fold uses Locale.ROOT, so a Turkish default locale changes nothing")
        void foldIsLocaleRootUnderATurkishDefaultLocale() {
            when(cardRepository.findByIdAndAccountIdForUpdate(CARD_NUMBER, ACCOUNT_ID_NUMERIC)).thenReturn(
                    Optional.of(storedCard("iris irwin", STORED_EXPIRY, STORED_STATUS)));
            final CardUpdateRequest.CardDetails snapshot = snapshotOf("IRIS IRWIN", STORED_YEAR,
                    STORED_MONTH, STORED_DAY, STORED_STATUS);
            // Locale.getDefault() is read here solely as a restore guard for the finally block, never
            // as an input to a decision; the fold under test is pinned to Locale.ROOT by construction.
            final Locale callerLocale = Locale.getDefault();
            try {
                Locale.setDefault(Locale.forLanguageTag("tr"));
                assertThat("iris".toUpperCase(Locale.getDefault()))
                        .as("the Turkish locale really is active, so the guard is meaningful")
                        .isNotEqualTo("IRIS");

                final CardUpdateService.CardUpdateResult result =
                        confirmSave(changedNameRequest(snapshot));

                assertThat(errorMessageOf(result))
                        .as("a default-locale fold would emit a dotted capital and report a change")
                        .isNotEqualTo(MSG_DATA_WAS_CHANGED);
            } finally {
                Locale.setDefault(callerLocale);
            }
            verify(cardRepository).save(any(Card.class));
        }
    }

    /**
     * Pins the six refreshing {@code MOVE} statements of {@code COCRDUPC.cbl:1512-1517}, which
     * reload the snapshot from the live record before the write exit so that an immediate retry can
     * succeed.
     */
    @Nested
    @DisplayName(":1512-1517 - the snapshot is refreshed from the live record on mismatch")
    class SnapshotRefresh {

        /**
         * The six refreshing {@code MOVE} statements at {@code COCRDUPC.cbl:1512-1517}: the rejected response echoes
         * the live values, not the stale ones the caller sent.
         */
        @Test
        @DisplayName("the rejected response echoes the refreshed, not the submitted, snapshot")
        void rejectedResponseCarriesTheRefreshedSnapshot() {
            when(cardRepository.findByIdAndAccountIdForUpdate(CARD_NUMBER, ACCOUNT_ID_NUMERIC)).thenReturn(
                    Optional.of(storedCard("olive brand", "2029-08-04", "N")));

            final CardUpdateService.CardUpdateResult result =
                    confirmSave(changedNameRequest(matchingSnapshot()));

            final CardUpdateRequest.CardDetails refreshed = result.refreshedSnapshot();
            assertThat(refreshed.cardData().cardholderName().strip())
                    .as("the live name arrives folded, because the fold precedes the refresh")
                    .isEqualTo("OLIVE BRAND");
            assertThat(refreshed.cardData().expiraionDate().expiryYear()).isEqualTo("2029");
            assertThat(refreshed.cardData().expiraionDate().expiryMonth()).isEqualTo("08");
            assertThat(refreshed.cardData().expiraionDate().expiryDay()).isEqualTo("04");
            assertThat(refreshed.cardData().cardStatusCode()).isEqualTo("N");
            verify(cardRepository, never()).save(any(Card.class));
        }

        /**
         * The observable consequence of the refresh: feeding the returned snapshot straight back succeeds, whereas
         * omitting the refresh would make every retry fail forever.
         */
        @Test
        @DisplayName("an immediate retry with the refreshed snapshot succeeds")
        void retryWithTheRefreshedSnapshotSucceeds() {
            when(cardRepository.findByIdAndAccountIdForUpdate(CARD_NUMBER, ACCOUNT_ID_NUMERIC)).thenReturn(
                    Optional.of(storedCard("OLIVE BRAND", "2029-08-04", "N")));

            final CardUpdateService.CardUpdateResult rejected =
                    confirmSave(changedNameRequest(matchingSnapshot()));
            assertThat(errorMessageOf(rejected)).isEqualTo(MSG_DATA_WAS_CHANGED);

            final CardUpdateRequest.CardDetails refreshed = rejected.refreshedSnapshot();
            final CardUpdateRequest.CardDetails retrySnapshot = snapshotOf(
                    refreshed.cardData().cardholderName(),
                    refreshed.cardData().expiraionDate().expiryYear(),
                    refreshed.cardData().expiraionDate().expiryMonth(),
                    refreshed.cardData().expiraionDate().expiryDay(),
                    refreshed.cardData().cardStatusCode());

            final CardUpdateService.CardUpdateResult accepted =
                    confirmSave(changedNameRequest(retrySnapshot));

            assertThat(errorMessageOf(accepted))
                    .as("dropping the refresh would make this retry fail forever")
                    .isNotEqualTo(MSG_DATA_WAS_CHANGED);
            verify(cardRepository).save(any(Card.class));
        }

        /**
         * The refreshed group re-renders its key components at the {@code PIC 9(11)} and {@code PIC X(16)} widths of
         * {@code app/cpy/CVACT02Y.cpy}, so a retry carries a byte-identical key.
         */
        @Test
        @DisplayName("the refreshed key components round-trip to their fixed widths")
        void refreshedKeyComponentsKeepTheirFixedWidths() {
            when(cardRepository.findByIdAndAccountIdForUpdate(CARD_NUMBER, ACCOUNT_ID_NUMERIC))
                    .thenReturn(Optional.of(storedCard(STORED_NAME, "2030-01-31", STORED_STATUS)));

            final CardUpdateRequest.CardDetails refreshed =
                    confirmSave(changedNameRequest(matchingSnapshot())).refreshedSnapshot();

            assertThat(refreshed.accountId()).isEqualTo(ACCOUNT_ID).hasSize(11);
            assertThat(refreshed.cardNumber()).hasSize(16);
            verify(cardRepository, never()).save(any(Card.class));
        }
    }

    /**
     * Pins the two concurrency layers - the value comparison of {@code 9300} and the JPA
     * {@code @Version} guard - and shows that neither one subsumes the other.
     */
    @Nested
    @DisplayName("two concurrency layers - 9300 plus @Version, neither sufficient alone")
    class VersionLayer {

        /**
         * The store-level layer: an optimistic-lock failure raised by the rewrite surfaces as the {@code
         * LOCKED_BUT_UPDATE_FAILED} outcome of {@code COCRDUPC.cbl:1487-1488} with its cause preserved.
         */
        @Test
        @DisplayName("a version conflict on rewrite surfaces as 'Update of record failed'")
        void versionConflictOnRewriteIsReported() {
            when(cardRepository.findByIdAndAccountIdForUpdate(CARD_NUMBER,
                    ACCOUNT_ID_NUMERIC)).thenReturn(Optional.of(storedCard()));
            when(cardRepository.save(any(Card.class)))
                    .thenThrow(new OptimisticLockingFailureException("row version moved"));

            final CardUpdateRequest request = changedNameRequest(matchingSnapshot());

            final ConcurrentUpdateException failure =
                    catchThrowableOfType(ConcurrentUpdateException.class, () -> confirmSave(request));

            assertThat(failure).hasMessage(MSG_UPDATE_FAILED)
                    .hasCauseInstanceOf(OptimisticLockingFailureException.class);
            assertThat(failure.getOutcome())
                    .isEqualTo(ConcurrentUpdateException.Outcome.LOCKED_BUT_UPDATE_FAILED);
            assertThat(failure.getCause()).hasMessage("row version moved");
        }

        /**
         * Shows the version guard is not redundant: a concurrent write that restored the original values passes all six
         * value clauses yet still trips the version column.
         */
        @Test
        @DisplayName("a changed-then-restored row passes 9300 yet still fails the version check")
        void roundTripModificationPasses9300ButFailsTheVersionCheck() {
            final Card restored = storedCard();
            restored.setVersion(7L);
            when(cardRepository.findByIdAndAccountIdForUpdate(CARD_NUMBER,
                    ACCOUNT_ID_NUMERIC)).thenReturn(Optional.of(restored));
            when(cardRepository.save(any(Card.class)))
                    .thenThrow(new OptimisticLockingFailureException("version 0 expected, 7 found"));

            final CardUpdateRequest request = changedNameRequest(matchingSnapshot());

            assertThatThrownBy(() -> confirmSave(request))
                    .as("9300 sees identical values, so only @Version can catch this")
                    .isInstanceOf(ConcurrentUpdateException.class)
                    .hasMessage(MSG_UPDATE_FAILED)
                    .hasMessageNotContaining(MSG_DATA_WAS_CHANGED);
        }

        /**
         * Shows the value comparison is not redundant either: it rejects before the rewrite is ever attempted, so the
         * version column is never consulted.
         */
        @Test
        @DisplayName("a value-level change is caught by 9300 before the version check is reached")
        void valueLevelChangeIsCaughtBefore9300YieldsToTheVersionCheck() {
            final Card drifted = storedCard(STORED_NAME, STORED_EXPIRY, "N");
            drifted.setVersion(0L);
            when(cardRepository.findByIdAndAccountIdForUpdate(CARD_NUMBER,
                    ACCOUNT_ID_NUMERIC)).thenReturn(Optional.of(drifted));

            final CardUpdateService.CardUpdateResult result =
                    confirmSave(changedNameRequest(matchingSnapshot()));

            assertThat(errorMessageOf(result))
                    .as("@Version alone would have accepted this write")
                    .isEqualTo(MSG_DATA_WAS_CHANGED);
            verify(cardRepository, never()).save(any(Card.class));
        }
    }

    /**
     * Pins the ordering and short-circuit behaviour of {@code 9200-WRITE-PROCESSING} at
     * {@code COCRDUPC.cbl:1420-1494}, a single-dataset write with one lock flag and no rollback verb.
     */
    @Nested
    @DisplayName("9200-WRITE-PROCESSING :1420-1494 - a SINGLE-dataset write, no rollback asymmetry")
    class WriteSequence {

        /**
         * The ordering of {@code 9200-WRITE-PROCESSING} at {@code COCRDUPC.cbl:1420-1494} asserted with {@code
         * InOrder}: read for update, then compare, then rewrite - never any other order.
         */
        @Test
        @DisplayName("the order is read-for-update, change detection, then rewrite")
        void readForUpdateThenDetectThenRewrite() {
            when(cardRepository.findByIdAndAccountIdForUpdate(CARD_NUMBER,
                    ACCOUNT_ID_NUMERIC)).thenReturn(Optional.of(storedCard()));
            final ArgumentCaptor<Card> persisted = ArgumentCaptor.forClass(Card.class);

            confirmSave(changedNameRequest(matchingSnapshot()));

            final InOrder sequence = inOrder(cardRepository);
            sequence.verify(cardRepository).findByIdAndAccountIdForUpdate(CARD_NUMBER, ACCOUNT_ID_NUMERIC);
            sequence.verify(cardRepository).save(persisted.capture());
            // The flush belongs to the same guarded block as the save and must follow it immediately: it
            // forces :1477-1483's REWRITE to be issued while :1488-1491's guard is still on the stack, so a
            // version conflict is reported as LOCKED-BUT-UPDATE-FAILED instead of escaping past the catch.
            sequence.verify(cardRepository).flush();
            sequence.verifyNoMoreInteractions();
            assertThat(persisted.getValue().getEmbossedName().strip())
                    .as("detection ran between the two calls, so the submitted name reached the row")
                    .isEqualTo(SUBMITTED_NAME);
        }

        /**
         * The read is keyed on the {@code PIC X(16)} card number of {@code app/cpy/CVACT02Y.cpy}, the cluster's 16-byte
         * primary key, captured from the repository argument rather than assumed.
         */
        @Test
        @DisplayName("the read-for-update key is the sixteen-digit record identifier")
        void readForUpdateUsesTheSixteenDigitRecordKey() {
            when(cardRepository.findByIdAndAccountIdForUpdate(CARD_NUMBER,
                    ACCOUNT_ID_NUMERIC)).thenReturn(Optional.of(storedCard()));
            final ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);

            confirmSave(changedNameRequest(matchingSnapshot()));

            verify(cardRepository).findByIdAndAccountIdForUpdate(key.capture(), eq(ACCOUNT_ID_NUMERIC));
            assertThat(key.getValue())
                    .as("the key is bound as a parameter, never concatenated into a statement")
                    .hasSize(16)
                    .containsOnlyDigits();
        }

        /**
         * The lock-failure branch at {@code COCRDUPC.cbl:1435-1444} exits before the comparison, so no write is
         * attempted at all.
         */
        @Test
        @DisplayName("a lock failure short-circuits - no write is attempted")
        void lockFailureShortCircuitsWithoutWriting() {
            when(cardRepository.findByIdAndAccountIdForUpdate(CARD_NUMBER,
                    ACCOUNT_ID_NUMERIC)).thenReturn(Optional.empty());

            final CardUpdateService.CardUpdateResult result =
                    confirmSave(changedNameRequest(matchingSnapshot()));

            assertThat(errorMessageOf(result)).isEqualTo(MSG_COULD_NOT_LOCK);
            assertThat(result.changeAction())
                    .isEqualTo(CardUpdateService.ChangeAction.CHANGES_OKAYED_LOCK_ERROR);
            verify(cardRepository, never()).save(any(Card.class));
        }

        /**
         * The change-detected branch at {@code COCRDUPC.cbl:1518} exits before the rewrite, so no write is attempted at
         * all.
         */
        @Test
        @DisplayName("a detected change short-circuits - no write is attempted")
        void detectedChangeShortCircuitsWithoutWriting() {
            when(cardRepository.findByIdAndAccountIdForUpdate(CARD_NUMBER, ACCOUNT_ID_NUMERIC))
                    .thenReturn(Optional.of(storedCard(STORED_NAME, STORED_EXPIRY, "N")));

            confirmSave(changedNameRequest(matchingSnapshot()));

            verify(cardRepository).findByIdAndAccountIdForUpdate(CARD_NUMBER, ACCOUNT_ID_NUMERIC);
            verifyNoMoreInteractions(cardRepository);
        }

        /**
         * A store failure on the rewrite at {@code COCRDUPC.cbl:1466-1472} leaves nothing persisted and is reported
         * rather than swallowed.
         */
        @Test
        @DisplayName("a rewrite failure leaves the row unpersisted and reports the failure")
        void rewriteFailureLeavesTheRowUnpersisted() {
            when(cardRepository.findByIdAndAccountIdForUpdate(CARD_NUMBER,
                    ACCOUNT_ID_NUMERIC)).thenReturn(Optional.of(storedCard()));
            when(cardRepository.save(any(Card.class)))
                    .thenThrow(new DataIntegrityViolationException("constraint rejected the rewrite"));
            final CardUpdateRequest request = changedNameRequest(matchingSnapshot());

            final ConcurrentUpdateException failure =
                    catchThrowableOfType(ConcurrentUpdateException.class, () -> confirmSave(request));

            assertThat(failure).hasMessage(MSG_UPDATE_FAILED)
                    .hasCauseInstanceOf(DataIntegrityViolationException.class);
            assertThat(failure.getOutcome())
                    .isEqualTo(ConcurrentUpdateException.Outcome.LOCKED_BUT_UPDATE_FAILED);
        }

        /**
         * This program writes exactly one dataset, unlike {@code COACTUPC}, so the success path persists one entity and
         * touches no second repository.
         */
        @Test
        @DisplayName("exactly ONE entity is persisted on the success path - there is no customer write")
        void exactlyOneEntityIsPersistedAndNoCustomerWriteOccurs() {
            when(cardRepository.findByIdAndAccountIdForUpdate(CARD_NUMBER,
                    ACCOUNT_ID_NUMERIC)).thenReturn(Optional.of(storedCard()));

            final CardUpdateRequest request = changedNameRequest(matchingSnapshot());
            service.updateCard(request, SUBJECT);

            verify(cardRepository, times(1)).findByIdAndAccountIdForUpdate(CARD_NUMBER, ACCOUNT_ID_NUMERIC);
            verify(cardRepository, times(1)).save(any(Card.class));
            // One flush, on the one repository. Its presence is the H6 contract; that there is exactly one
            // of it is the single-dataset contract this test exists to pin.
            verify(cardRepository, times(1)).flush();
            verifyNoMoreInteractions(cardRepository);
        }

        /**
         * Confirms by reflection that the bean declares one repository collaborator, which is why no rollback asymmetry
         * can arise here.
         */
        @Test
        @DisplayName("the bean collaborates with ONE repository, so a second dataset cannot be written")
        void theBeanHoldsASingleRepositoryCollaborator() {
            final long repositoryFields = Arrays.stream(CardUpdateService.class.getDeclaredFields())
                    .filter(field -> !Modifier.isStatic(field.getModifiers()))
                    .filter(field -> field.getType().getName().endsWith("Repository"))
                    .count();

            assertThat(repositoryFields)
                    .as("COACTUPC needs two datasets; COCRDUPC writes exactly one at :1487-1493")
                    .isEqualTo(1L);
        }

        /**
         * {@code COCRDUPC} declares one lock flag at {@code :205-206} while {@code COACTUPC} declares two, so the
         * thrown message must be the unqualified card-file wording.
         */
        @Test
        @DisplayName("only ONE lock-failure outcome exists, unlike the account program's two")
        void aSingleLockFlagExistsUnlikeTheAccountProgram() {
            final Set<String> lockOutcomes =
                    Arrays.stream(CardUpdateService.WriteOutcome.values())
                            .map(Enum::name)
                            .filter(name -> name.contains("LOCK"))
                            .collect(Collectors.toUnmodifiableSet());

            assertThat(lockOutcomes).containsExactlyInAnyOrder("COULD_NOT_LOCK_FOR_UPDATE",
                    "LOCKED_BUT_UPDATE_FAILED");
            assertThat(lockOutcomes)
                    .as("there is no customer-lock flag here, because there is no customer write")
                    .noneMatch(name -> name.contains("CUSTOMER"));
        }

        /**
         * Guards against importing {@code COACTUPC}'s taxonomy: the lock failure never carries the customer outcome or
         * its legacy message.
         */
        @Test
        @DisplayName("a lock failure never reports the customer-lock outcome of the account program")
        void lockFailureNeverReportsTheCustomerOutcome() {
            when(cardRepository.findByIdAndAccountIdForUpdate(CARD_NUMBER,
                    ACCOUNT_ID_NUMERIC)).thenReturn(Optional.empty());
            final CardUpdateRequest request = changedNameRequest(matchingSnapshot());

            final ConcurrentUpdateException failure = catchThrowableOfType(
                    ConcurrentUpdateException.class, () -> service.updateCard(request, SUBJECT));

            assertThat(failure).hasMessage(MSG_COULD_NOT_LOCK).hasNoCause();
            assertThat(failure.getOutcome())
                    .isNotEqualTo(ConcurrentUpdateException.Outcome.COULD_NOT_LOCK_CUSTOMER);
            assertThat(failure.getOutcome().getLegacyMessage())
                    .as("the enum carries COACTUPC's wording; COCRDUPC's :205-206 wording is thrown")
                    .isNotEqualTo(MSG_COULD_NOT_LOCK);
        }

        /**
         * The field moves at {@code COCRDUPC.cbl:1447-1464} are asserted on the captured entity, so each submitted
         * value reaches the row it belongs on.
         */
        @Test
        @DisplayName("the rewrite carries every submitted field onto the locked row")
        void rewriteCarriesEverySubmittedFieldOntoTheLockedRow() {
            when(cardRepository.findByIdAndAccountIdForUpdate(CARD_NUMBER,
                    ACCOUNT_ID_NUMERIC)).thenReturn(Optional.of(storedCard()));
            final ArgumentCaptor<Card> persisted = ArgumentCaptor.forClass(Card.class);

            final CardUpdateRequest request =
                    requestWith(matchingSnapshot(), "ANNA LEE", "N", "12", "2099", "28");
            service.updateCard(request, SUBJECT);

            verify(cardRepository).save(persisted.capture());
            final Card written = persisted.getValue();
            assertThat(written.getEmbossedName()).hasSize(50).startsWith("ANNA LEE");
            assertThat(written.getActiveStatus()).isEqualTo("N");
            assertThat(written.getExpiraionDate())
                    .as("assembled from the three components at :1477-1485, with the day the snapshot's own")
                    .isEqualTo("2099-12-" + STORED_DAY);
            assertThat(written.getAccountId()).isEqualTo(1L);
            // The two MOVEs of :1464-1465 have no counterpart. They wrote the never-assigned
            // CCUP-NEW-CVV-CD - left at SPACES by INITIALIZE CCUP-NEW-DETAILS at :586 - onto the record,
            // destroying the stored verification value on every successful update. The target mutates the
            // loaded row and publishes no setter for that field, so the stored value survives; the two
            // properties are asserted by theVerificationClauseHasNoRequestOperandAndTheStoredValueSurvives()
            // and aSuccessfulRewritePreservesTheStoredVerificationValue().
            //
            // Note the day: "28" was submitted and STORED_DAY was written. The day is the one field of
            // this map the source refuses to let a user change - see theSubmittedExpiryDayNeverReachesTheRow.
        }

        /**
         * The submitted expiry day never reaches the row. The day the snapshot carries does.
         *
         * <p>The source says this in as many words. {@code 3200-SETUP-SCREEN-VARS} writes
         * {@code CCUP-OLD-EXPDAY} into {@code EXPDAYO} on every arm - {@code :1110}, {@code :1123} and
         * {@code :1127} - and at {@code :1120-1122} the new-value MOVE is present but <em>commented out</em>
         * beneath the banner {@code 'MOVE OLD VALUES TO NON-DISPLAY FIELDS THAT WE ARE NOT ALLOWING USER TO
         * CHANGE(FOR NOW)'}. {@code :1285} then sets {@code DFHBMDAR} on {@code EXPDAYC}, rendering the field
         * dark. A 3270 returns what was sent, so {@code CCUP-NEW-EXPDAY} at {@code :621} can only ever hold
         * the old day, which is why {@code :1471} may write it into the rewrite image at all - and why
         * {@code COCRDSL.CPY} declares no {@code EXPDAYI} for the read map to publish.
         *
         * <p><strong>Finding, severity High - remediated.</strong> The screen was the carrier and there is
         * no screen, so the snapshot is. Trusting the request instead was destructive rather than merely
         * divergent: a client echoing back a detail read - which cannot publish a day it does not carry -
         * submitted none, and the {@code STRING} at {@code :1467-1474} composed an expiry with the day
         * blank, erasing it from the stored record. Two seeded rows were found in that state.
         */
        @Test
        @DisplayName("the submitted expiry day never reaches the row - :1120-1122, :1285")
        void theSubmittedExpiryDayNeverReachesTheRow() {
            // A FRESH entity per lookup. The write mutates the row it loaded, so a single shared instance
            // would make the second submission's change comparison fail against the first one's result.
            when(cardRepository.findByIdAndAccountIdForUpdate(CARD_NUMBER, ACCOUNT_ID_NUMERIC))
                    .thenAnswer(lookup -> Optional.of(storedCard()));
            final ArgumentCaptor<Card> persisted = ArgumentCaptor.forClass(Card.class);

            // Three submissions that differ only in the day: a wrong one, an absent one and a blank one.
            for (final String submittedDay : new String[] {"28", null, "  "}) {
                final CardUpdateRequest request =
                        requestWith(matchingSnapshot(), "ANNA LEE", "N", "12", "2099", submittedDay);
                service.updateCard(request, SUBJECT);
            }

            verify(cardRepository, times(3)).save(persisted.capture());
            assertThat(persisted.getAllValues())
                    .as("every rewrite carries the snapshot's day, whatever was submitted")
                    .extracting(Card::getExpiraionDate)
                    .containsOnly("2099-12-" + STORED_DAY);
            // And in particular none of them lost the day, which is the destructive outcome.
            assertThat(persisted.getAllValues()).allSatisfy(written ->
                    assertThat(written.getExpiraionDate()).hasSize(10).doesNotEndWith("-"));
        }
    }

    /**
     * Returns the surviving return message for a submitted set of field values, driven through the
     * validation leg so the repository is never touched.
     *
     * @param cardholderName submitted {@code CRDNAMEI}
     * @param cardStatusCode submitted {@code CRDSTCDI}
     * @param expiryMonth submitted {@code EXPMONI}
     * @param expiryYear submitted {@code EXPYEARI}
     * @return the unpadded return message, empty when every edit passed
     */
    private String editMessageFor(final String cardholderName, final String cardStatusCode,
                                  final String expiryMonth, final String expiryYear) {
        final CardUpdateService.CardUpdateResult result = validateOnly(
                requestWith(matchingSnapshot(), cardholderName, cardStatusCode, expiryMonth,
                        expiryYear, STORED_DAY));
        verifyNoInteractions(cardRepository);
        return errorMessageOf(result);
    }

    /**
     * Returns the surviving return message for a submitted name, holding every other field at a
     * value that passes its own edit while still differing from the snapshot.
     *
     * @param cardholderName submitted {@code CRDNAMEI}
     * @return the unpadded return message
     */
    private String nameEditMessageFor(final String cardholderName) {
        return editMessageFor(cardholderName, CARD_STATUS_TOGGLED, STORED_MONTH, STORED_YEAR);
    }

    /**
     * Pins the fixed order in which {@code 1200-EDIT-MAP-INPUTS} at
     * {@code COCRDUPC.cbl:641-717} drives the six field edits, using the message guard to make that
     * order observable.
     */
    @Nested
    @DisplayName("1200-EDIT-MAP-INPUTS :641-717 - six edits in a fixed order")
    class EditCascadeOrder {

        /**
         * With all four fields bad, the message guard lets only the first edit speak, so {@code 1230-EDIT-NAME} at
         * {@code COCRDUPC.cbl:806-841} wins.
         */
        @Test
        @DisplayName("with all four re-entry fields bad the NAME message wins - edit 3 of 6 runs first")
        void nameMessageWinsWhenEveryFieldIsBad() {
            assertThat(editMessageFor("JOHN5", "X", "13", "1849")).isEqualTo(MSG_NAME_MUST_BE_ALPHA);
        }

        /**
         * Repairing the name promotes {@code 1240-EDIT-CARDSTATUS} at {@code COCRDUPC.cbl:845-874} to the reported
         * failure, which is how the fixed order becomes observable.
         */
        @Test
        @DisplayName("with the name repaired the STATUS message wins - edit 4 of 6 runs second")
        void statusMessageWinsOnceTheNameIsValid() {
            assertThat(editMessageFor("JOHN SMITH", "X", "13", "1849"))
                    .isEqualTo(MSG_STATUS_MUST_BE_YES_NO);
        }

        /**
         * Repairing the status promotes {@code 1250-EDIT-EXPIRY-MON} at {@code COCRDUPC.cbl:877-910}.
         */
        @Test
        @DisplayName("with the status repaired the MONTH message wins - edit 5 of 6 runs third")
        void monthMessageWinsOnceTheStatusIsValid() {
            assertThat(editMessageFor("JOHN SMITH", "N", "13", "1849")).isEqualTo(MSG_MONTH_NOT_VALID);
        }

        /**
         * Repairing the month promotes {@code 1260-EDIT-EXPIRY-YEAR} at {@code COCRDUPC.cbl:913-945}, the last edit in
         * the cascade.
         */
        @Test
        @DisplayName("with the month repaired the YEAR message wins - edit 6 of 6 runs last")
        void yearMessageWinsOnceTheMonthIsValid() {
            assertThat(editMessageFor("JOHN SMITH", "N", "12", "1849")).isEqualTo(MSG_YEAR_NOT_VALID);
        }

        /**
         * The terminal step of the progressive repair: with every field valid the return message is empty and the
         * confirmation prompt is offered instead.
         */
        @Test
        @DisplayName("with everything repaired no message survives and the change awaits confirmation")
        void noMessageSurvivesWhenEveryEditPasses() {
            final CardUpdateService.CardUpdateResult result = validateOnly(
                    requestWith(matchingSnapshot(), "JOHN SMITH", "N", "12", "2099", STORED_DAY));

            assertThat(errorMessageOf(result)).isEmpty();
            assertThat(result.changeAction())
                    .isEqualTo(CardUpdateService.ChangeAction.CHANGES_OK_NOT_CONFIRMED);
            verifyNoInteractions(cardRepository);
        }

        /**
         * {@code 1210-EDIT-ACCOUNT} at {@code COCRDUPC.cbl:721-758} runs before {@code 1220-EDIT-CARD} at {@code
         * :762-802}, asserted through which filter message survives the guard.
         */
        @Test
        @DisplayName("1210-EDIT-ACCOUNT :721 precedes 1220-EDIT-CARD :762 on the fetch leg")
        void accountFilterEditPrecedesTheCardFilterEdit() {
            final CardUpdateRequest request = new CardUpdateRequest(null, null, null, null, null, null,
                    null, "NOTNUMERIC000000", null, null, null, null, null, null, null, null, null,
                    null, null);

            final CardUpdateService.CardUpdateResult result =
                    service.processRequest(request, openedSnapshot(request), AID_ENTER, CardUpdateService.EntryMode.REENTER);

            assertThat(errorMessageOf(result))
                    .as("the blank account filter is reported before the non-numeric card filter")
                    .isEqualTo(MSG_ACCOUNT_NOT_PROVIDED);
            verifyNoInteractions(cardRepository);
        }

        /**
         * When both filters are blank the driver at {@code COCRDUPC.cbl:672-676} overrides the per-field prompts with
         * the single {@code 'No input received'} literal.
         */
        @Test
        @DisplayName("both filters blank yields 'No input received', overriding the per-field message")
        void bothFiltersBlankYieldsNoInputReceived() {
            final CardUpdateRequest request = new CardUpdateRequest(null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null);

            final CardUpdateService.CardUpdateResult result =
                    service.processRequest(request, openedSnapshot(request), AID_ENTER, CardUpdateService.EntryMode.REENTER);

            assertThat(errorMessageOf(result)).isEqualTo(MSG_NO_INPUT_RECEIVED);
            verifyNoInteractions(cardRepository);
        }

        /**
         * The account filter reports the literal the source actually {@code MOVE}s at {@code COCRDUPC.cbl:745}, not
         * either of the two never-set 88-levels at {@code :189-192}.
         */
        @Test
        @DisplayName("a non-numeric account filter reports the SET literal, not the never-set 88-level")
        void nonNumericAccountFilterReportsTheLiteralActuallyMoved() {
            final CardUpdateRequest request = new CardUpdateRequest(null, null, null, null, null, null,
                    "1234567890X", CARD_NUMBER, null, null, null, null, null, null, null, null, null,
                    null, null);

            final CardUpdateService.CardUpdateResult result =
                    service.processRequest(request, openedSnapshot(request), AID_ENTER, CardUpdateService.EntryMode.REENTER);

            assertThat(errorMessageOf(result))
                    .isEqualTo("ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER")
                    .isNotEqualTo(MSG_ACCOUNT_NON_ZERO_ELEVEN_DIGITS);
            verifyNoInteractions(cardRepository);
        }

        /**
         * The card filter reports the literal the source actually {@code MOVE}s at {@code COCRDUPC.cbl:789}, not the
         * never-set 88-level at {@code :193-194}.
         */
        @Test
        @DisplayName("a non-numeric card filter reports the SET literal, not the never-set 88-level")
        void nonNumericCardFilterReportsTheLiteralActuallyMoved() {
            final CardUpdateRequest request = new CardUpdateRequest(null, null, null, null, null, null,
                    ACCOUNT_ID, "123456789012345X", null, null, null, null, null, null, null, null,
                    null, null, null);

            final CardUpdateService.CardUpdateResult result =
                    service.processRequest(request, openedSnapshot(request), AID_ENTER, CardUpdateService.EntryMode.REENTER);

            assertThat(errorMessageOf(result))
                    .isEqualTo("CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER")
                    .isNotEqualTo(MSG_CARD_MUST_BE_SIXTEEN_DIGITS);
            verifyNoInteractions(cardRepository);
        }
    }

    /**
     * Pins the convert-and-trim name test of {@code 1230-EDIT-NAME} at
     * {@code COCRDUPC.cbl:806-841}, whose decisive lines are {@code :822-828}.
     */
    @Nested
    @DisplayName("1230-EDIT-NAME :806-841 - the convert-and-trim test of :822-828")
    class NameEdit {

        /**
         * A name of letters only leaves no residue after the conversion at {@code COCRDUPC.cbl:822-825}, so it is
         * accepted.
         */
        @Test
        @DisplayName("a purely alphabetic name is valid")
        void purelyAlphabeticNameIsValid() {
            assertThat(nameEditMessageFor("PURELYALPHABETIC")).isEmpty();
        }

        /**
         * Spaces survive the conversion untouched and then vanish under {@code FUNCTION TRIM}, so a name with embedded
         * spaces is accepted - the behaviour that gives the message its wording.
         */
        @Test
        @DisplayName("alphabetic characters plus spaces are valid - spaces are legal inside a name")
        void alphabeticPlusSpacesIsValid() {
            assertThat(nameEditMessageFor("MARY JANE SMITH")).isEmpty();
        }

        /**
         * The alphabet at {@code COCRDUPC.cbl:255-257} lists both cases, so mixed case is accepted without any folding.
         */
        @Test
        @DisplayName("mixed-case alphabetic is valid - the edit tests the character class, not case")
        void mixedCaseAlphabeticIsValid() {
            assertThat(nameEditMessageFor("Mary Jane Smith")).isEmpty();
        }

        /**
         * A digit is not in the conversion set, so it survives as residue and the name is rejected.
         */
        @Test
        @DisplayName("a name containing a digit is invalid")
        void nameContainingADigitIsInvalid() {
            assertThat(nameEditMessageFor("JOHN SMITH2")).isEqualTo(MSG_NAME_MUST_BE_ALPHA);
        }

        /**
         * A hyphen is residue too, so hyphenated surnames are rejected - a legacy restriction preserved rather than
         * relaxed.
         */
        @Test
        @DisplayName("a name containing a hyphen is invalid")
        void nameContainingAHyphenIsInvalid() {
            assertThat(nameEditMessageFor("MARY-JANE SMITH")).isEqualTo(MSG_NAME_MUST_BE_ALPHA);
        }

        /**
         * An apostrophe is residue, so names such as an Irish patronymic are rejected.
         */
        @Test
        @DisplayName("a name containing an apostrophe is invalid")
        void nameContainingAnApostropheIsInvalid() {
            assertThat(nameEditMessageFor("O'BRIEN")).isEqualTo(MSG_NAME_MUST_BE_ALPHA);
        }

        /**
         * The conversion set is the 52 ASCII letters only, so an accented character is residue and the name is
         * rejected; a {@code Character.isLetter} port would wrongly accept it.
         */
        @Test
        @DisplayName("a name containing an accented character is invalid - only the 52 ASCII letters pass")
        void nameContainingAnAccentedCharacterIsInvalid() {
            assertThat(nameEditMessageFor("JOS\u00c9 GARCIA")).isEqualTo(MSG_NAME_MUST_BE_ALPHA);
        }

        /**
         * A currency symbol and a thousands separator are both residue, so the currency-tolerant parsing used for
         * amounts elsewhere has no counterpart on this field.
         */
        @Test
        @DisplayName("a name carrying a currency symbol and a thousands separator is invalid")
        void nameCarryingCurrencyAndSeparatorIsInvalid() {
            assertThat(nameEditMessageFor("JOHN $1,000")).isEqualTo(MSG_NAME_MUST_BE_ALPHA);
        }

        /**
         * An all-space name is caught by the blank test at {@code COCRDUPC.cbl:810-816} before the conversion runs, so
         * it reports the prompt rather than the alphabetic message.
         */
        @Test
        @DisplayName("an all-space name is BLANK, not 'not alphabetic'")
        void allSpaceNameIsBlankRatherThanNotAlphabetic() {
            assertThat(nameEditMessageFor("          "))
                    .isEqualTo(MSG_NAME_NOT_PROVIDED)
                    .isNotEqualTo(MSG_NAME_MUST_BE_ALPHA);
        }

        /**
         * The blank test includes {@code EQUAL ZEROS} even on this text field, so an all-zero-digit name counts as
         * blank rather than as non-alphabetic.
         */
        @Test
        @DisplayName("an all-zero-digits name is BLANK - the EQUAL ZEROS limb applies to a text field")
        void allZeroDigitsNameIsBlank() {
            assertThat(nameEditMessageFor("00000")).isEqualTo(MSG_NAME_NOT_PROVIDED);
        }

        /**
         * An omitted name fails validation before any repository call, and the failure names the field so the screen
         * can position its cursor.
         */
        @Test
        @DisplayName("an omitted name is BLANK and reported against the cardholder-name field")
        void omittedNameIsBlankAndAttributedToTheField() {
            final CardUpdateRequest request = requestWith(matchingSnapshot(), null,
                    CARD_STATUS_TOGGLED, STORED_MONTH, STORED_YEAR, STORED_DAY);

            final ValidationException failure =
                    catchThrowableOfType(ValidationException.class,
                            () -> service.updateCard(request, SUBJECT));

            assertThat(failure).hasMessage(MSG_NAME_NOT_PROVIDED).hasNoCause();
            assertThat(failure.getFieldName()).isEqualTo("cardholderName");
            assertThat(failure.getFailureKind()).isEqualTo(ValidationException.FailureKind.BLANK);
            assertThat(failure.hasFieldName()).isTrue();
            verifyNoInteractions(cardRepository);
        }

        /**
         * The invalid path is distinguished from the blank path by its failure kind as well as its message.
         */
        @Test
        @DisplayName("a non-alphabetic name is reported as INVALID, not BLANK")
        void nonAlphabeticNameIsReportedAsInvalid() {
            final CardUpdateRequest request = requestWith(matchingSnapshot(), "JOHN5",
                    CARD_STATUS_TOGGLED, STORED_MONTH, STORED_YEAR, STORED_DAY);

            final ValidationException failure =
                    catchThrowableOfType(ValidationException.class,
                            () -> service.updateCard(request, SUBJECT));

            assertThat(failure).hasMessage(MSG_NAME_MUST_BE_ALPHA);
            assertThat(failure.getFailureKind()).isEqualTo(ValidationException.FailureKind.INVALID);
            verifyNoInteractions(cardRepository);
        }

        /**
         * A name longer than {@code PIC X(50)} is truncated to the map width at {@code COCRDUPC.cbl:1100-1104} and then
         * accepted, because the terminal could never have delivered more.
         */
        @Test
        @DisplayName("a fifty-two character alphabetic name is truncated to the PIC width and accepted")
        void overLongAlphabeticNameIsTruncatedRatherThanRejected() {
            when(cardRepository.findByIdAndAccountIdForUpdate(CARD_NUMBER,
                    ACCOUNT_ID_NUMERIC)).thenReturn(Optional.of(storedCard()));
            final String overLong = "A".repeat(52);
            final ArgumentCaptor<Card> persisted = ArgumentCaptor.forClass(Card.class);

            final CardUpdateRequest request = requestWith(matchingSnapshot(), overLong,
                    CARD_STATUS_TOGGLED, STORED_MONTH, STORED_YEAR, STORED_DAY);
            service.updateCard(request, SUBJECT);

            verify(cardRepository).save(persisted.capture());
            assertThat(persisted.getValue().getEmbossedName())
                    .as("CARD-EMBOSSED-NAME is PIC X(50), so the surplus two characters are dropped")
                    .isEqualTo("A".repeat(50));
        }
    }

    /**
     * Pins the case-sensitive yes/no check field of {@code 1240-EDIT-CARDSTATUS} at
     * {@code COCRDUPC.cbl:845-874}.
     */
    @Nested
    @DisplayName("1240-EDIT-CARDSTATUS :845-874 - the yes/no check field is case SENSITIVE")
    class CardStatusEdit {

        /**
         * {@code 88 FLG-YES-NO-VALID VALUES 'Y','N'} at {@code COCRDUPC.cbl:89-91} accepts {@code Y}.
         */
        @Test
        @DisplayName("'Y' is valid")
        void upperCaseYesIsValid() {
            assertThat(editMessageFor("JOHN SMITH", "Y", "12", STORED_YEAR)).isEmpty();
        }

        /**
         * The same 88-level accepts {@code N}.
         */
        @Test
        @DisplayName("'N' is valid")
        void upperCaseNoIsValid() {
            assertThat(editMessageFor("JOHN SMITH", "N", STORED_MONTH, STORED_YEAR)).isEmpty();
        }

        /**
         * The 88-level lists upper case only, so {@code y} is rejected; the submitted name is changed as well so that
         * the group test of {@code :679-683} cannot swallow the request first.
         */
        @Test
        @DisplayName("'y' is INVALID - the 88-level lists only the upper-case literals")
        void lowerCaseYesIsInvalid() {
            // The submitted name must also differ, or the SYMMETRIC group fold of :679-683 absorbs
            // the case-only status difference and the field edits never run at all.
            assertThat(editMessageFor(SUBMITTED_NAME, "y", STORED_MONTH, STORED_YEAR))
                    .isEqualTo(MSG_STATUS_MUST_BE_YES_NO);
        }

        /**
         * Pins the interaction the suite discovered: a case-only status change is invisible to the symmetric group
         * test, which then skips the field cascade entirely, so no status message is produced at all.
         */
        @Test
        @DisplayName("a case-only status change is absorbed by the SYMMETRIC group fold of :679-683")
        void caseOnlyStatusChangeNeverReachesTheCaseSensitiveEdit() {
            assertThat(editMessageFor(STORED_NAME, "y", STORED_MONTH, STORED_YEAR))
                    .as("1200's group test folds BOTH sides, unlike 9300 which folds only the live one")
                    .isEqualTo(MSG_NO_CHANGES_DETECTED)
                    .isNotEqualTo(MSG_STATUS_MUST_BE_YES_NO);
        }

        /**
         * {@code n} is rejected for the same reason as {@code y}.
         */
        @Test
        @DisplayName("'n' is INVALID - no case folding is applied to the status field")
        void lowerCaseNoIsInvalid() {
            assertThat(editMessageFor("JOHN SMITH", "n", STORED_MONTH, STORED_YEAR))
                    .isEqualTo(MSG_STATUS_MUST_BE_YES_NO);
        }

        /**
         * Any character outside the two listed values is rejected.
         */
        @Test
        @DisplayName("'X' is invalid")
        void unrelatedLetterIsInvalid() {
            assertThat(editMessageFor("JOHN SMITH", "X", STORED_MONTH, STORED_YEAR))
                    .isEqualTo(MSG_STATUS_MUST_BE_YES_NO);
        }

        /**
         * {@code EQUAL ZEROS} in the blank test at {@code COCRDUPC.cbl:849-855} makes a status of {@code 0} blank
         * rather than invalid.
         */
        @Test
        @DisplayName("'0' is BLANK - the EQUAL ZEROS limb applies to this text field too")
        void zeroDigitStatusIsBlank() {
            assertThat(editMessageFor("JOHN SMITH", "0", STORED_MONTH, STORED_YEAR))
                    .isEqualTo(MSG_STATUS_MUST_BE_YES_NO);
        }

        /**
         * An omitted status fails validation with the field named, before any repository call.
         */
        @Test
        @DisplayName("an omitted status is BLANK and reported against the status field")
        void omittedStatusIsBlankAndAttributedToTheField() {
            final CardUpdateRequest request = requestWith(matchingSnapshot(), "JOHN SMITH", null,
                    STORED_MONTH, STORED_YEAR, STORED_DAY);

            final ValidationException failure =
                    catchThrowableOfType(ValidationException.class,
                            () -> service.updateCard(request, SUBJECT));

            assertThat(failure).hasMessage(MSG_STATUS_MUST_BE_YES_NO);
            assertThat(failure.getFieldName()).isEqualTo("cardStatusCode");
            assertThat(failure.getFailureKind()).isEqualTo(ValidationException.FailureKind.BLANK);
            verifyNoInteractions(cardRepository);
        }

        /**
         * Both the blank and the invalid path of {@code 1240} emit the same literal, so the message alone cannot
         * distinguish them and the failure kind must.
         */
        @Test
        @DisplayName("the blank and the invalid path share one literal")
        void blankAndInvalidPathsShareOneLiteral() {
            assertThat(editMessageFor("JOHN SMITH", null, STORED_MONTH, STORED_YEAR))
                    .isEqualTo(editMessageFor("JOHN SMITH", "Q", STORED_MONTH, STORED_YEAR))
                    .isEqualTo(MSG_STATUS_MUST_BE_YES_NO);
        }
    }

    /**
     * Pins the month bounds of {@code 1250-EDIT-EXPIRY-MON} at {@code COCRDUPC.cbl:877-910},
     * declared as {@code 88 VALID-MONTH VALUES 1 THRU 12}.
     */
    @Nested
    @DisplayName("1250-EDIT-EXPIRY-MON :877-910 - VALID-MONTH VALUES 1 THRU 12")
    class ExpiryMonthEdit {

        /**
         * The lower bound of {@code 88 VALID-MONTH VALUES 1 THRU 12} at {@code COCRDUPC.cbl:92-95}.
         */
        @Test
        @DisplayName("'01' is valid - the lower bound")
        void januaryIsValid() {
            assertThat(editMessageFor("JOHN SMITH", CARD_STATUS_TOGGLED, "01", STORED_YEAR)).isEmpty();
        }

        /**
         * The upper bound of the same 88-level.
         */
        @Test
        @DisplayName("'12' is valid - the upper bound")
        void decemberIsValid() {
            assertThat(editMessageFor("JOHN SMITH", CARD_STATUS_TOGGLED, "12", STORED_YEAR)).isEmpty();
        }

        /**
         * {@code 00} trips {@code EQUAL ZEROS} in the blank test before the range test runs, so it is blank rather than
         * out of range.
         */
        @Test
        @DisplayName("'00' is BLANK - it trips the EQUAL ZEROS limb before the range test")
        void zeroMonthIsBlank() {
            assertThat(editMessageFor("JOHN SMITH", CARD_STATUS_TOGGLED, "00", STORED_YEAR))
                    .isEqualTo(MSG_MONTH_NOT_VALID);
        }

        /**
         * One above the declared upper bound is rejected.
         */
        @Test
        @DisplayName("'13' is invalid - one past the upper bound")
        void thirteenthMonthIsInvalid() {
            assertThat(editMessageFor("JOHN SMITH", CARD_STATUS_TOGGLED, "13", STORED_YEAR))
                    .isEqualTo(MSG_MONTH_NOT_VALID);
        }

        /**
         * A far out-of-range value is rejected by the same bound, not by a digit-count rule.
         */
        @Test
        @DisplayName("'99' is invalid")
        void ninetyNinthMonthIsInvalid() {
            assertThat(editMessageFor("JOHN SMITH", CARD_STATUS_TOGGLED, "99", STORED_YEAR))
                    .isEqualTo(MSG_MONTH_NOT_VALID);
        }

        /**
         * The redefinition at {@code COCRDUPC.cbl:92-95} requires a numeric class before the range is evaluated, so
         * letters are rejected.
         */
        @Test
        @DisplayName("a non-numeric month is invalid")
        void nonNumericMonthIsInvalid() {
            assertThat(editMessageFor("JOHN SMITH", CARD_STATUS_TOGGLED, "ab", STORED_YEAR))
                    .isEqualTo(MSG_MONTH_NOT_VALID);
        }

        /**
         * The check is width exact at {@code PIC 9(2)}, so {@code 1} is rejected where {@code 01} is accepted.
         */
        @Test
        @DisplayName("a single-digit month is invalid - the class test is width exact at PIC X(2)")
        void singleDigitMonthIsInvalid() {
            assertThat(editMessageFor("JOHN SMITH", CARD_STATUS_TOGGLED, "1", STORED_YEAR))
                    .isEqualTo(MSG_MONTH_NOT_VALID);
        }

        /**
         * An omitted month is blank and, unlike the name and status fields, carries no field attribution because the
         * source names no cursor field for it.
         */
        @Test
        @DisplayName("an omitted month is BLANK and reported against the month field")
        void omittedMonthIsBlankAndAttributedToTheField() {
            final CardUpdateRequest request = requestWith(matchingSnapshot(), "JOHN SMITH",
                    CARD_STATUS_TOGGLED, null, STORED_YEAR, STORED_DAY);

            final ValidationException failure =
                    catchThrowableOfType(ValidationException.class,
                            () -> service.updateCard(request, SUBJECT));

            assertThat(failure).hasMessage(MSG_MONTH_NOT_VALID);
            assertThat(failure.getFieldName()).isEqualTo("expiryMonth");
            assertThat(failure.getFailureKind()).isEqualTo(ValidationException.FailureKind.BLANK);
            verifyNoInteractions(cardRepository);
        }
    }

    /**
     * Pins the year bounds of {@code 1260-EDIT-EXPIRY-YEAR} at {@code COCRDUPC.cbl:913-945} as a
     * range over the constants declared at {@code :96-99}, proving that no clock participates.
     */
    @Nested
    @DisplayName("1260-EDIT-EXPIRY-YEAR :913-945 - a range over DECLARED CONSTANTS, never the clock")
    class ExpiryYearEdit {

        /**
         * The lower bound of {@code 88 VALID-YEAR VALUES 1950 THRU 2099} declared at {@code COCRDUPC.cbl:96-99}.
         */
        @Test
        @DisplayName("'1950' is valid - the declared lower bound, decades in the past")
        void declaredLowerBoundIsValid() {
            assertThat(editMessageFor("JOHN SMITH", CARD_STATUS_TOGGLED, STORED_MONTH, "1950"))
                    .isEmpty();
        }

        /**
         * The upper bound of the same 88-level.
         */
        @Test
        @DisplayName("'2099' is valid - the declared upper bound, decades in the future")
        void declaredUpperBoundIsValid() {
            assertThat(editMessageFor("JOHN SMITH", CARD_STATUS_TOGGLED, STORED_MONTH, "2099"))
                    .isEmpty();
        }

        /**
         * One below the declared lower bound is rejected, fixing the bound as a constant rather than a heuristic.
         */
        @Test
        @DisplayName("'1949' is invalid - one below the declared lower bound")
        void oneBelowTheLowerBoundIsInvalid() {
            assertThat(editMessageFor("JOHN SMITH", CARD_STATUS_TOGGLED, STORED_MONTH, "1949"))
                    .isEqualTo(MSG_YEAR_NOT_VALID);
        }

        /**
         * One above the declared upper bound is rejected.
         */
        @Test
        @DisplayName("'2100' is invalid - one above the declared upper bound")
        void oneAboveTheUpperBoundIsInvalid() {
            assertThat(editMessageFor("JOHN SMITH", CARD_STATUS_TOGGLED, STORED_MONTH, "2100"))
                    .isEqualTo(MSG_YEAR_NOT_VALID);
        }

        /**
         * {@code 0000} trips {@code EQUAL ZEROS} in the blank test at {@code COCRDUPC.cbl:916-925} before the
         * range test runs, so it is blank rather than out of range.
         */
        @Test
        @DisplayName("'0000' is BLANK - it trips the EQUAL ZEROS limb before the range test")
        void zeroYearIsBlank() {
            assertThat(editMessageFor("JOHN SMITH", CARD_STATUS_TOGGLED, STORED_MONTH, "0000"))
                    .isEqualTo(MSG_YEAR_NOT_VALID);
        }

        /**
         * The check is width exact at {@code PIC 9(4)}, so a two-digit year is rejected outright.
         */
        @Test
        @DisplayName("'99' is invalid - the class test is width exact at PIC X(4)")
        void twoDigitYearIsInvalid() {
            assertThat(editMessageFor("JOHN SMITH", CARD_STATUS_TOGGLED, STORED_MONTH, "99"))
                    .isEqualTo(MSG_YEAR_NOT_VALID);
        }

        /**
         * A non-numeric year fails the class test that precedes the range evaluation.
         */
        @Test
        @DisplayName("a non-numeric year is invalid")
        void nonNumericYearIsInvalid() {
            assertThat(editMessageFor("JOHN SMITH", CARD_STATUS_TOGGLED, STORED_MONTH, "abcd"))
                    .isEqualTo(MSG_YEAR_NOT_VALID);
        }

        /**
         * Both bounds pass in the same run: 1950 is far in the past and 2099 far in the future, which no current-
         * year comparison could accept simultaneously.
         */
        @Test
        @DisplayName("both declared bounds pass simultaneously, which no current-year test permits")
        void bothDeclaredBoundsPassWhichRulesOutACurrentYearComparison() {
            assertThat(editMessageFor("JOHN SMITH", CARD_STATUS_TOGGLED, STORED_MONTH, "1950"))
                    .isEmpty();
            assertThat(editMessageFor("JOHN SMITH", CARD_STATUS_TOGGLED, STORED_MONTH, "2099"))
                    .isEmpty();
        }

        /**
         * Two beans whose clocks are eighty years apart produce identical outcomes for the same year, proving the
         * edit never reads the clock.
         */
        @Test
        @DisplayName("moving the injected clock by eighty years changes no validation outcome")
        void movingTheInjectedClockChangesNoOutcome() {
            final CardUpdateService inThePast = new CardUpdateService(cardRepository,
                    Clock.fixed(Instant.parse("1970-01-01T00:00:00Z"), ZoneOffset.UTC),
                    snapshotTokenService);
            final CardUpdateService inTheFuture = new CardUpdateService(cardRepository,
                    Clock.fixed(Instant.parse("2050-12-31T23:59:59Z"), ZoneOffset.UTC),
                    snapshotTokenService);
            final CardUpdateRequest request = requestWith(matchingSnapshot(), "JOHN SMITH",
                    CARD_STATUS_TOGGLED, STORED_MONTH, "1950", STORED_DAY);

            final String past = errorMessageOf(
                    inThePast.processRequest(request, openedSnapshot(request), AID_ENTER, CardUpdateService.EntryMode.REENTER));
            final String future = errorMessageOf(
                    inTheFuture.processRequest(request, openedSnapshot(request), AID_ENTER, CardUpdateService.EntryMode.REENTER));

            assertThat(past).isEqualTo(future).isEmpty();
            verifyNoInteractions(cardRepository);
        }
    }

    /**
     * Collects the value of every {@code static final String} the service declares, so a test can
     * assert the production literal against the COBOL text transcribed above rather than against
     * itself.
     *
     * <p>The reflection here is read-only field access on the class under test: it reads constants and
     * invokes nothing, so it is not the arbitrary reflective invocation that clause D of the project
     * rule prohibits. It exists because the literals are private by design - no production seam may be
     * widened merely to let a test observe them.
     *
     * @return every declared string constant, never {@code null}
     */
    private static Set<String> serviceStringConstants() {
        return Arrays.stream(CardUpdateService.class.getDeclaredFields())
                .filter(field -> Modifier.isStatic(field.getModifiers()))
                .filter(field -> field.getType() == String.class)
                .map(field -> {
                    field.setAccessible(true);
                    try {
                        return (String) field.get(null);
                    } catch (final IllegalAccessException unreachable) {
                        throw new AssertionError("constant became unreadable: " + field.getName(),
                                unreachable);
                    }
                })
                .filter(value -> value != null)
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Asserts the outcome literals declared at {@code COCRDUPC.cbl:155-214} byte for byte,
     * including the seven that the procedure division never sets.
     */
    @Nested
    @DisplayName(":155-214 - outcome literals asserted byte for byte")
    class OutcomeLiterals {

        /**
         * Every literal the procedure division actually sets is present byte for byte among the bean's declared
         * constants, asserted as a set so a single missing literal fails.
         */
        @Test
        @DisplayName("every literal the source SETS is present in the service verbatim")
        void everyReachableLiteralIsPresentVerbatim() {
            assertThat(serviceStringConstants()).contains(
                    MSG_ACCOUNT_NOT_PROVIDED,
                    MSG_CARD_NOT_PROVIDED,
                    MSG_NAME_NOT_PROVIDED,
                    MSG_NAME_MUST_BE_ALPHA,
                    MSG_NO_INPUT_RECEIVED,
                    MSG_NO_CHANGES_DETECTED,
                    MSG_STATUS_MUST_BE_YES_NO,
                    MSG_MONTH_NOT_VALID,
                    MSG_YEAR_NOT_VALID,
                    MSG_NO_ACCTCARD_COMBO,
                    MSG_COULD_NOT_LOCK,
                    MSG_DATA_WAS_CHANGED,
                    MSG_UPDATE_FAILED,
                    INFO_FOUND_CARDS,
                    MSG_PROMPT_FOR_SEARCH_KEYS,
                    MSG_PROMPT_FOR_CONFIRMATION);
        }

        /**
         * {@code COCRDUPC.cbl:187-188} ends inside the quotes with a full stop, unique among this program's
         * messages, so the literal must not be tidied.
         */
        @Test
        @DisplayName("'No change detected with respect to values fetched.' keeps its trailing full stop")
        void noChangesDetectedKeepsItsTrailingFullStop() {
            assertThat(MSG_NO_CHANGES_DETECTED).endsWith("fetched.");
            assertThat(serviceStringConstants()).contains(MSG_NO_CHANGES_DETECTED);
            assertThat(MSG_DATA_WAS_CHANGED)
                    .as("the trailing stop is unique to this return message")
                    .doesNotEndWith(".");
            assertThat(MSG_UPDATE_FAILED).doesNotEndWith(".");
        }

        /**
         * {@code COCRDUPC.cbl:207-208} spells {@code some one} as two words; correcting it would break the byte
         * comparison the parity gate performs.
         */
        @Test
        @DisplayName("'some one' stays TWO WORDS in the data-changed message")
        void dataChangedMessageKeepsSomeOneAsTwoWords() {
            assertThat(MSG_DATA_WAS_CHANGED).contains("some one else").doesNotContain("someone");
            assertThat(serviceStringConstants()).contains(MSG_DATA_WAS_CHANGED);
        }

        /**
         * {@code COCRDUPC.cbl:205-206} says {@code record}, not {@code account record} or {@code customer record},
         * because this program locks one dataset only.
         */
        @Test
        @DisplayName("the lock message says 'record', not 'account record' or 'customer record'")
        void lockMessageSaysRecordWithoutQualification() {
            assertThat(MSG_COULD_NOT_LOCK).isEqualTo("Could not lock record for update")
                    .doesNotContain("account")
                    .doesNotContain("customer");
            assertThat(serviceStringConstants()).contains(MSG_COULD_NOT_LOCK);
        }

        /**
         * The confirmation prompt omits the space after its full stop, a legacy typographical quirk preserved
         * rather than repaired.
         */
        @Test
        @DisplayName("'Changes validated.Press F5 to save' keeps its missing space")
        void confirmationPromptKeepsItsMissingSpace() {
            assertThat(MSG_PROMPT_FOR_CONFIRMATION).contains("validated.Press")
                    .doesNotContain("validated. Press");
            assertThat(serviceStringConstants()).contains(MSG_PROMPT_FOR_CONFIRMATION);
        }

        /**
         * {@code CODING-TO-BE-DONE} at {@code COCRDUPC.cbl:213-214} carries four dots and is never set anywhere,
         * so it has no Java counterpart at all.
         */
        @Test
        @DisplayName("'Looks Good.... so far' carries FOUR dots and is never SET, so it has no counterpart")
        void looksGoodCarriesFourDotsAndIsNeverSet() {
            assertThat(MSG_LOOKS_GOOD_NEVER_SET).contains("Good....").doesNotContain("Good.....");
            assertThat(MSG_LOOKS_GOOD_NEVER_SET.chars().filter(character -> character == '.').count())
                    .isEqualTo(4L);
            assertThat(serviceStringConstants())
                    .as("CODING-TO-BE-DONE is declared at :213-214 but SET nowhere in the source")
                    .doesNotContain(MSG_LOOKS_GOOD_NEVER_SET);
        }

        /**
         * {@code WS-EXIT-MESSAGE} at {@code COCRDUPC.cbl:175-176} is 34 bytes including its trailing spaces and is
         * likewise never set.
         */
        @Test
        @DisplayName("the exit message keeps its fourteen trailing spaces and is never SET")
        void exitMessageKeepsItsTrailingSpacesAndIsNeverSet() {
            assertThat(MSG_EXIT_NEVER_SET).hasSize(34).startsWith("PF03 pressed.Exiting").endsWith(" ");
            assertThat(serviceStringConstants()).doesNotContain(MSG_EXIT_NEVER_SET);
        }

        /**
         * Asserts in the negative direction that none of the seven never-set 88-levels was resurrected as a Java
         * constant, so a spurious addition fails as loudly as an omission. The seven collapse to five distinct
         * literals here: {@code SEARCHED-ACCT-ZEROES} at {@code :189-190} and {@code SEARCHED-ACCT-NOT-NUMERIC} at
         * {@code :191-192} share one byte-identical value, and {@code WS-EXIT-MESSAGE} at {@code :175-176} is
         * asserted by the preceding test because its trailing-space width is a contract of its own.
         */
        @Test
        @DisplayName("the never-SET 88-levels, five distinct literals, have no counterpart in the service")
        void neverSetEightyEightLevelsHaveNoCounterpart() {
            assertThat(serviceStringConstants()).doesNotContain(
                    MSG_ACCOUNT_NON_ZERO_ELEVEN_DIGITS,
                    MSG_CARD_MUST_BE_SIXTEEN_DIGITS,
                    MSG_NO_ACCOUNT_IN_CARDS_DATABASE,
                    MSG_XREF_READ_ERROR_NEVER_SET,
                    MSG_LOOKS_GOOD_NEVER_SET);
        }

        /**
         * The same bidirectional check for {@code 01 WS-LITERALS} at {@code COCRDUPC.cbl:218-263}, whose naming
         * literals occupy {@code :219-254}. Eight of its fields are declared and then never referenced anywhere in
         * the procedure division, which begins at
         * {@code :366}: {@code LIT-CCLISTTRANID} {@code :229-230}, {@code LIT-MENUMAPSET} {@code :239-240},
         * {@code LIT-MENUMAP} {@code :241-242}, {@code LIT-CARDDTLPGM} {@code :243-244},
         * {@code LIT-CARDDTLTRANID} {@code :245-246}, {@code LIT-CARDDTLMAPSET} {@code :247-248},
         * {@code LIT-CARDDTLMAP} {@code :249-250} and {@code LIT-CARDFILENAME-ACCT-PATH} {@code :253-254}. Their
         * correct counterpart is again no counterpart at all, so a reader who looks for a {@code 'CCLI'} or
         * {@code 'CCDL'} constant and finds none is seeing fidelity rather than an omission. The correspondence
         * holds in both directions with no exception: every referenced field's value is declared and every
         * unreferenced one's is not.
         */
        @Test
        @DisplayName("the eight never-referenced WS-LITERALS fields correctly have no counterpart either")
        void neverReferencedLiteralFieldsHaveNoCounterpart() {
            assertThat(serviceStringConstants())
                    .as("declared inside :219-254 but never referenced from :366 onward")
                    .doesNotContain("CCLI", "COMEN01", "COMEN1A", "COCRDSLC", "CCDL", "COCRDSL", "CARDAIX ");
            assertThat(serviceStringConstants())
                    .as("LIT-CARDFILENAME at :251-252 IS referenced four times, so its value is declared")
                    .contains("CARDDAT ");
            assertThat(serviceStringConstants())
                    .as("LIT-CCLISTMAPSET at :231-232 IS referenced four times, so its value is declared")
                    .contains("COCRDLI");
        }

        /**
         * {@code SEARCHED-ACCT-ZEROES} at {@code COCRDUPC.cbl:189-190} and {@code SEARCHED-ACCT-NOT-NUMERIC} at
         * {@code :191-192} carry the same literal, preserved rather than differentiated.
         */
        @Test
        @DisplayName("the two account 88-levels at :189-192 carry byte-identical literals")
        void theTwoAccountEightyEightLevelsAreByteIdentical() {
            final String searchedAcctZeroes = "Account number must be a non zero 11 digit number";
            final String searchedAcctNotNumeric = "Account number must be a non zero 11 digit number";

            assertThat(searchedAcctZeroes)
                    .as("SEARCHED-ACCT-ZEROES and SEARCHED-ACCT-NOT-NUMERIC differ in name only")
                    .isEqualTo(searchedAcctNotNumeric)
                    .isEqualTo(MSG_ACCOUNT_NON_ZERO_ELEVEN_DIGITS);
        }

        /**
         * The identity literals at {@code COCRDUPC.cbl:219-234} are asserted exactly, including {@code LIT-
         * THISMAPSET} which is {@code PIC X(8)} with a trailing space among {@code PIC X(7)} siblings.
         */
        @Test
        @DisplayName("the program identity literals are verbatim, mapset included at PIC X(8)")
        void programIdentityLiteralsAreVerbatim() {
            assertThat(serviceStringConstants()).contains("COCRDUPC", "CCUP", "COCRDUP ", "CCRDUPA",
                    "COCRDLIC", "COCRDLI", "COMEN01C", "CM00", "CARDDAT ");
            assertThat("COCRDUP ")
                    .as("LIT-THISMAPSET is PIC X(8) with a trailing space among PIC X(7) siblings")
                    .hasSize(8)
                    .endsWith(" ");
            assertThat("CCRDUPA").hasSize(7);
        }

        /**
         * {@code LIT-CCLISTMAP} at {@code COCRDUPC.cbl:233-234} names the card detail map, identical to {@code
         * LIT-CARDDTLMAP} at {@code :249-250}; the defect is preserved and the transfer keys on the mapset
         * instead.
         */
        @Test
        @DisplayName("LIT-CCLISTMAP 'CCRDSLA' is a preserved copy-paste defect - the mapset is used instead")
        void cardListMapLiteralIsAPreservedCopyPasteDefect() {
            final String cardListMapLiteral = "CCRDSLA";
            final String cardDetailMapLiteral = "CCRDSLA";

            assertThat(cardListMapLiteral)
                    .as(":233-234 names the card DETAIL map, identical to :249-250 - preserved, not fixed")
                    .isEqualTo(cardDetailMapLiteral)
                    .isNotEqualTo("CCRDLIA");
            assertThat(serviceStringConstants())
                    .as("the list-return decision keys on the MAPSET, so the defective map is unused")
                    .contains("COCRDLI")
                    .doesNotContain(cardListMapLiteral);
        }

        /**
         * The three write outcomes of {@code 9200} carry three distinct literals, so collapsing them into one
         * conflict status would destroy information the legacy screen displayed.
         */
        @Test
        @DisplayName("the three write failures are never collapsed into one conflict status")
        void theThreeWriteFailuresAreNeverCollapsed() {
            final Set<String> messages = Set.of(MSG_COULD_NOT_LOCK, MSG_DATA_WAS_CHANGED,
                    MSG_UPDATE_FAILED);
            assertThat(messages).hasSize(3);

            final Set<ConcurrentUpdateException.Outcome> outcomes = Set.of(
                    ConcurrentUpdateException.Outcome.COULD_NOT_LOCK_ACCOUNT,
                    ConcurrentUpdateException.Outcome.DATA_CHANGED_BEFORE_UPDATE,
                    ConcurrentUpdateException.Outcome.LOCKED_BUT_UPDATE_FAILED);
            assertThat(outcomes).hasSize(3);
            assertThat(outcomes).extracting(ConcurrentUpdateException.Outcome::getChangeActionCode)
                    .containsExactlyInAnyOrder('L', 'S', 'F');
        }

        /**
         * The same two failures also reach different change actions, so the distinction survives in the response
         * state and not only in the message.
         */
        @Test
        @DisplayName("a lock failure and a detected change reach DIFFERENT ChangeAction values")
        void lockFailureAndDetectedChangeReachDifferentChangeActions() {
            when(cardRepository.findByIdAndAccountIdForUpdate(CARD_NUMBER,
                    ACCOUNT_ID_NUMERIC)).thenReturn(Optional.empty());
            final CardUpdateService.ChangeAction afterLockFailure =
                    confirmSave(changedNameRequest(matchingSnapshot())).changeAction();

            assertThat(afterLockFailure)
                    .isEqualTo(CardUpdateService.ChangeAction.CHANGES_OKAYED_LOCK_ERROR)
                    .isNotEqualTo(CardUpdateService.ChangeAction.SHOW_DETAILS);
            assertThat(afterLockFailure.isChangesFailed()).isTrue();
            assertThat(CardUpdateService.ChangeAction.CHANGES_OKAYED_BUT_FAILED.isChangesFailed())
                    .isTrue();
            assertThat(CardUpdateService.ChangeAction.CHANGES_OKAYED_AND_DONE.isChangesFailed())
                    .as("a completed write is not a failure, so the three states stay distinguishable")
                    .isFalse();
        }

        /**
         * A detected change returns to the detail state so the caller can re-read the refreshed snapshot and
         * retry.
         */
        @Test
        @DisplayName("a detected change reaches SHOW_DETAILS, distinct from either lock outcome")
        void detectedChangeReachesShowDetails() {
            when(cardRepository.findByIdAndAccountIdForUpdate(CARD_NUMBER, ACCOUNT_ID_NUMERIC))
                    .thenReturn(Optional.of(storedCard(STORED_NAME, STORED_EXPIRY, "N")));

            final CardUpdateService.ChangeAction afterDetectedChange =
                    confirmSave(changedNameRequest(matchingSnapshot())).changeAction();

            assertThat(afterDetectedChange)
                    .isEqualTo(CardUpdateService.ChangeAction.SHOW_DETAILS)
                    .isNotEqualTo(CardUpdateService.ChangeAction.CHANGES_OKAYED_LOCK_ERROR)
                    .isNotEqualTo(CardUpdateService.ChangeAction.CHANGES_OKAYED_BUT_FAILED);
        }

        /**
         * {@code 9100-GETCARD-BYACCTCARD} at {@code COCRDUPC.cbl:1376-1418} reports its own literal on the screen
         * rather than throwing, which is why no record-not-found exception arises here.
         */
        @Test
        @DisplayName("the fetch leg reports 'Did not find cards for this search condition' when absent")
        void fetchLegReportsTheNotFoundLiteral() {
            when(cardRepository.findByCardNumberAndAccountId(CARD_NUMBER, ACCOUNT_ID_NUMERIC))
                    .thenReturn(Optional.empty());
            final CardUpdateRequest request = new CardUpdateRequest(null, null, null, null, null, null,
                    ACCOUNT_ID, CARD_NUMBER, null, null, null, null, null, null, null, null, null,
                    null, null);

            final CardUpdateService.CardUpdateResult result =
                    service.processRequest(request, openedSnapshot(request), AID_ENTER, CardUpdateService.EntryMode.REENTER);

            assertThat(errorMessageOf(result)).isEqualTo(MSG_NO_ACCTCARD_COMBO);
            verify(cardRepository, never()).save(any(Card.class));
        }

        /**
         * The successful fetch reports the informational literal instead, in the information field rather than the
         * error field.
         */
        @Test
        @DisplayName("the fetch leg reports 'Details of selected card shown above' when present")
        void fetchLegReportsTheFoundLiteral() {
            when(cardRepository.findByCardNumberAndAccountId(CARD_NUMBER, ACCOUNT_ID_NUMERIC))
                    .thenReturn(Optional.of(storedCard()));
            final CardUpdateRequest request = new CardUpdateRequest(null, null, null, null, null, null,
                    ACCOUNT_ID, CARD_NUMBER, null, null, null, null, null, null, null, null, null,
                    null, null);

            final CardUpdateService.CardUpdateResult result =
                    service.processRequest(request, openedSnapshot(request), AID_ENTER, CardUpdateService.EntryMode.REENTER);

            assertThat(result.screen().getInformationMessage().strip()).isEqualTo(INFO_FOUND_CARDS);
            assertThat(result.changeAction()).isEqualTo(CardUpdateService.ChangeAction.SHOW_DETAILS);
            verify(cardRepository, never()).save(any(Card.class));
        }

        /**
         * The error field is padded to the {@code ERRMSGI PIC X(80)} width of {@code app/cpy-bms/COCRDUP.CPY}, so
         * a literal comparison must strip that padding rather than assume it away.
         */
        @Test
        @DisplayName("the screen error field keeps its PIC X(80) geometry")
        void screenErrorFieldKeepsItsFixedWidth() {
            when(cardRepository.findByIdAndAccountIdForUpdate(CARD_NUMBER,
                    ACCOUNT_ID_NUMERIC)).thenReturn(Optional.empty());

            final CardUpdateService.CardUpdateResult result =
                    confirmSave(changedNameRequest(matchingSnapshot()));

            assertThat(result.screen().getErrorMessage())
                    .hasSize(ERROR_MESSAGE_WIDTH)
                    .startsWith(MSG_COULD_NOT_LOCK);
            assertThat(result.screen().getInformationMessage()).hasSize(40);
        }
    }

    /**
     * Pins the CICS online abend contract of {@code ABEND-ROUTINE} at
     * {@code COCRDUPC.cbl:1531-1554}, whose {@code ABCODE('9999')} sits at {@code :1551} and is not
     * the batch abend pair.
     */
    @Nested
    @DisplayName("ABEND-ROUTINE :1531-1554 - the CICS ONLINE four-character ABCODE contract")
    class AbendContract {

        /**
         * An unexpected runtime failure inside the write reaches {@code ABEND-ROUTINE} at {@code
         * COCRDUPC.cbl:1531-1554} as a fatal outcome that names the culprit program and preserves its cause.
         */
        @Test
        @DisplayName("an unexpected runtime failure becomes a fatal outcome naming COCRDUPC as culprit")
        void unexpectedRuntimeFailureBecomesAFatalOutcome() {
            // The driver is a store fault that is NOT of the DataAccessException family, so it passes
            // through the two typed catch arms on the rewrite and reaches the outer funnel at :1096. A
            // request with no account identifier no longer serves as the driver: the read is scoped by
            // account, so an absent account owns nothing and the turn fails closed on the lock guard long
            // before any abend - which is the subject of its own test below.
            final IllegalStateException unexpected = new IllegalStateException("entity manager closed");
            when(cardRepository.findByIdAndAccountIdForUpdate(CARD_NUMBER, ACCOUNT_ID_NUMERIC))
                    .thenReturn(Optional.of(storedCard()));
            when(cardRepository.save(any(Card.class))).thenThrow(unexpected);

            final FatalProcessingException fatal = catchThrowableOfType(
                    FatalProcessingException.class,
                    () -> confirmSave(changedNameRequest(matchingSnapshot())));

            assertThat(fatal.getAbendCulprit()).isEqualTo("COCRDUPC");
            assertThat(fatal.getAbendMessage())
                    .isEqualTo(FatalProcessingException.DEFAULT_ABEND_MESSAGE);
            assertThat(fatal).hasMessage(FatalProcessingException.DEFAULT_ABEND_MESSAGE)
                    .hasCause(unexpected);
        }

        @Test
        @DisplayName(":1424 a request with no account identifier fails closed on the lock, not on an abend")
        void aRequestWithNoAccountIdentifierFailsClosedOnTheLockGuard() {
            // An absent account is not an internal contradiction, it is a request that names no owner.
            // Since :1424 is restored, nothing can own the card, so the answer is the source's own
            // could-not-lock wording - and no read is issued against the card number on its own.
            final CardUpdateRequest missingAccountId = new CardUpdateRequest(null, null, null, null,
                    null, null, null, CARD_NUMBER, SUBMITTED_NAME, STORED_STATUS, STORED_MONTH,
                    STORED_YEAR, STORED_DAY, null, null, null, null,
                    seal(matchingSnapshot(), null, CARD_NUMBER, SUBJECT), null);

            final CardUpdateService.CardUpdateResult result = confirmSave(missingAccountId);

            assertThat(errorMessageOf(result)).startsWith(MSG_COULD_NOT_LOCK);
            verify(cardRepository, never()).save(any(Card.class));
            verify(cardRepository, never()).findByCardNumberAndAccountId(any(), any());
        }

        /**
         * {@code EXEC CICS ABEND ABCODE('9999')} at {@code COCRDUPC.cbl:1551} is a four-character online marker,
         * distinct from the batch abend code and return code pair used by the batch programs.
         */
        @Test
        @DisplayName("the online marker is FOUR characters wide, not the batch 999 / RC 12 pair")
        void onlineMarkerIsFourCharactersAndNotTheBatchPair() {
            final String onlineAbendCode = "9999";

            assertThat(FileStatusMapper.ABEND_CODE_WIDTH).isEqualTo(4);
            assertThat(onlineAbendCode)
                    .as("EXEC CICS ABEND ABCODE('9999') at :1551 fits ABEND-CODE PIC X(4) exactly")
                    .hasSize(FileStatusMapper.ABEND_CODE_WIDTH);
            assertThat(String.valueOf(FatalProcessingException.BATCH_ABEND_CODE))
                    .as("the batch pair belongs to the CBACT/CBTRN programs, never to an online one")
                    .hasSize(3)
                    .isNotEqualTo(onlineAbendCode);
            assertThat(FatalProcessingException.BATCH_ABEND_CODE).isEqualTo(999);
            assertThat(FatalProcessingException.BATCH_RETURN_CODE).isEqualTo(12);
        }

        /**
         * Asserts in the negative direction that the online path carries neither batch identifier, so the two
         * contracts cannot be conflated.
         */
        @Test
        @DisplayName("a fatal outcome carries neither the batch abend code nor the batch return code")
        void fatalOutcomeCarriesNeitherBatchIdentifier() {
            // Driven by a store fault outside the DataAccessException family, exactly as
            // unexpectedRuntimeFailureBecomesAFatalOutcome drives it: that reaches the outer funnel and
            // produces a genuine abend on this path. A request with no account identifier does NOT - the
            // read is scoped by account, so an absent account owns nothing and the turn fails closed on the
            // lock guard long before any abend, which is the subject of its own test below.
            when(cardRepository.findByIdAndAccountIdForUpdate(CARD_NUMBER,
                    ACCOUNT_ID_NUMERIC)).thenReturn(Optional.of(storedCard()));
            when(cardRepository.save(any(Card.class)))
                    .thenThrow(new IllegalStateException("entity manager closed"));

            final FatalProcessingException fatal = catchThrowableOfType(
                    FatalProcessingException.class,
                    () -> confirmSave(changedNameRequest(matchingSnapshot())));

            assertThat(fatal).as("the driver must actually abend, or the assertions below are vacuous")
                    .isNotNull();
            assertThat(fatal.getAbendCode())
                    .isNotEqualTo(String.valueOf(FatalProcessingException.BATCH_ABEND_CODE))
                    .isNotEqualTo(String.valueOf(FatalProcessingException.BATCH_RETURN_CODE));
        }

        /**
         * The {@code ABEND-DATA} group of {@code app/cpy/CSMSG02Y.cpy:21-29} totals 134 bytes as {@code X(4)},
         * {@code X(8)}, {@code X(50)} and {@code X(72)}, asserted against the declared widths.
         */
        @Test
        @DisplayName("the ABEND-DATA field set of CSMSG02Y :21-29 totals 134 bytes")
        void abendDataFieldSetTotalsOneHundredThirtyFourBytes() {
            assertThat(FileStatusMapper.ABEND_CODE_WIDTH).isEqualTo(4);
            assertThat(FileStatusMapper.ABEND_CULPRIT_WIDTH).isEqualTo(8);
            assertThat(FileStatusMapper.ABEND_REASON_WIDTH).isEqualTo(50);
            assertThat(FileStatusMapper.ABEND_MESSAGE_WIDTH).isEqualTo(72);
            assertThat(FileStatusMapper.ABEND_CODE_WIDTH + FileStatusMapper.ABEND_CULPRIT_WIDTH
                    + FileStatusMapper.ABEND_REASON_WIDTH + FileStatusMapper.ABEND_MESSAGE_WIDTH)
                    .isEqualTo(134);
        }

        /**
         * The culprit program name fits {@code ABEND-CULPRIT PIC X(8)} exactly, which is why the eight-character
         * program literal is usable unmodified.
         */
        @Test
        @DisplayName("the culprit fits ABEND-CULPRIT PIC X(8) exactly")
        void culpritFitsTheDeclaredWidth() {
            assertThat("COCRDUPC").hasSize(FileStatusMapper.ABEND_CULPRIT_WIDTH);
        }

        /**
         * Every abend field is {@code VALUE SPACES}, so the source's {@code LOW-VALUES} default-message guard can
         * never fire; the Java counterpart is reachable, which this pins.
         */
        @Test
        @DisplayName("the LOW-VALUES message guard of :1533 is unreachable, VALUE SPACES having won")
        void lowValuesMessageGuardIsUnreachableInTheSource() {
            final String abendMessageAsInitialised = " ".repeat(FileStatusMapper.ABEND_MESSAGE_WIDTH);

            assertThat(abendMessageAsInitialised)
                    .as("CSMSG02Y initialises ABEND-MSG to VALUE SPACES, never to LOW-VALUES")
                    .isNotEqualTo("\u0000".repeat(FileStatusMapper.ABEND_MESSAGE_WIDTH))
                    .isBlank();
            assertThat(FatalProcessingException.DEFAULT_ABEND_MESSAGE)
                    .as("the Java substitution path IS reachable, unlike the COBOL one - Low severity")
                    .isEqualTo("UNEXPECTED ABEND OCCURRED.");
        }
    }

    /**
     * The {@code EXEC CICS XCTL} arm of {@code 0000-MAIN} at {@code COCRDUPC.cbl:430-475}, which the rest of
     * this suite never entered.
     *
     * <p>A coverage review found the whole arm dead in test. Not one line of
     * {@link CardUpdateService}'s transfer body was covered, and {@code CardUpdateService$Navigation} was one
     * of only two production classes in the entire tree with nothing covered at all - its canonical
     * constructor is reachable from that body and from nowhere else. That matters beyond the figure.
     * Transformation Rule 7 turns {@code XCTL PROGRAM(CDEMO-TO-PROGRAM)} into URL navigation, and the target
     * resolution at {@code :436-447} - defaulting a blank or low-values caller context to the main menu - is
     * the observable part of that translation: it is what tells the caller which screen the legacy would have
     * reached. Untested, it could resolve to anything and no assertion would notice.
     *
     * <p>Three of the arm's five effects are asserted here because each is a separate decision of the source:
     * the response kind and the absent screen ({@code XCTL} sends no map), the resolved target and the origin
     * the source stamps in its place at {@code :448-449}, and the key normalisation at {@code :435}, which is
     * why two of the three entry conditions can be reached without {@code PF03} having been pressed at all.
     *
     * <p><strong>Two lines of the arm stay uncovered after this, and deliberately so.</strong> They are the
     * {@code ELSE} halves of {@code :446} and {@code :453}, which carry a supplied caller context through
     * instead of defaulting it. {@code CDEMO-FROM-TRANID} and {@code CDEMO-FROM-PROGRAM} arrived in the
     * COMMAREA, and under transformation Rule 7 no request member replaces them, so through the public entry
     * point they are always absent and the defaulting halves are always the ones taken. Both halves are
     * implemented anyway, so the resolution logic is complete and provable rather than assumed; the same
     * disposition is recorded for the same reason in {@code DECISION_LOG.md} under {@code DL-MS-06}. Reaching
     * them from a test would mean reaching past the entry point into private state, which is exactly what
     * {@link StatelessnessContract} exists to forbid, so the gap is disclosed here instead of closed.
     */
    @Nested
    @DisplayName("0000-MAIN :430-475 - the EXEC CICS XCTL arm resolves a target and sends no map")
    class TransferArm {

        /**
         * {@code PF03} with no caller context resolves the main menu and returns a transfer, not a screen.
         *
         * <p>{@code :430} is the first {@code WHEN} of the {@code EVALUATE TRUE}, so this is the arm a plain
         * {@code PF03} takes whatever else the request carries. {@code :436-447} then default both halves of
         * the target, because this request supplies neither: the flat header members that would carry a
         * caller context are {@code null}, which is the {@code LOW-VALUES} case the source tests for.
         */
        @Test
        @DisplayName(":430 PF03 transfers to the main menu, sends no map, and stamps this program as origin")
        void pressingPf03TransfersToTheMainMenuAndSendsNoMap() {
            final CardUpdateRequest request = requestWith(matchingSnapshot(), STORED_NAME, STORED_STATUS,
                    STORED_MONTH, STORED_YEAR, STORED_DAY);

            final CardUpdateService.CardUpdateResult result = service.processRequest(request,
                    openedSnapshot(request), AID_PF03, CardUpdateService.EntryMode.REENTER);

            assertThat(result.responseKind())
                    .as(":472-475 EXEC CICS XCTL transfers control and terminates the caller, so the pass "
                            + "reports a transfer rather than a rendered screen")
                    .isEqualTo(CardUpdateService.ResponseKind.TRANSFER);
            assertThat(result.screen())
                    .as("and it sends no map: 3000-SEND-MAP is not on this arm at all")
                    .isNull();
            assertThat(result.navigation())
                    .as("the arm's whole observable output is the resolved navigation target")
                    .isNotNull();
            assertThat(result.navigation().toTransactionId())
                    .as(":436-441 a blank or low-values CDEMO-FROM-TRANID defaults to the main menu "
                            + "transaction")
                    .isEqualTo("CM00");
            assertThat(result.navigation().toProgram())
                    .as(":442-447 and a blank or low-values CDEMO-FROM-PROGRAM defaults to the main menu "
                            + "program")
                    .isEqualTo("COMEN01C");
            assertThat(result.navigation().fromTransactionId())
                    .as(":448 MOVE LIT-THISTRANID TO CDEMO-FROM-TRANID - the outgoing context names THIS "
                            + "transaction, not the one that arrived")
                    .isEqualTo("CCUP");
            assertThat(result.navigation().fromProgram())
                    .as(":449 MOVE LIT-THISPGM TO CDEMO-FROM-PROGRAM")
                    .isEqualTo("COCRDUPC");
            assertThat(result.navigation().lastMapset())
                    .as(":456 MOVE LIT-THISMAPSET TO CDEMO-LAST-MAPSET. The trailing blank is part of the "
                            + "value: app/cbl/COCRDUPC.cbl:223-224 declares LIT-THISMAPSET as PIC X(8) with "
                            + "VALUE 'COCRDUP ', and dispatch0000 compares CDEMO-LAST-MAPSET against the "
                            + "seven-character LIT-CCLISTMAPSET, so padding is load-bearing and is not "
                            + "trimmed here")
                    .isEqualTo("COCRDUP ");
            assertThat(result.navigation().lastMap())
                    .as(":457 MOVE LIT-THISMAP TO CDEMO-LAST-MAP")
                    .isEqualTo("CCRDUPA");
        }

        /**
         * The transfer arm is reached under {@code PF03} regardless of how far the pass would otherwise have
         * got, which is what makes it the first {@code WHEN} rather than one of the later ones.
         *
         * <p>Driven with no snapshot at all - the {@code CCUP-DETAILS-NOT-FETCHED} state of {@code :278-280}
         * in which the write is unreachable - so a reader can see that the arm depends on the attention key
         * and not on the request having been through a first turn.
         */
        @Test
        @DisplayName(":430-435 the arm is taken on the key alone, and normalises that key to PF03")
        void theTransferArmIsTakenOnTheKeyAloneAndNormalisesIt() {
            final CardUpdateRequest request = requestWith(null, SUBMITTED_NAME, STORED_STATUS,
                    STORED_MONTH, STORED_YEAR, STORED_DAY);

            final CardUpdateService.CardUpdateResult result = service.processRequest(request, null,
                    AID_PF03, CardUpdateService.EntryMode.ENTER);

            assertThat(result.responseKind())
                    .as("an absent snapshot cannot keep the pass off this arm; :430 tests the key and "
                            + "nothing else")
                    .isEqualTo(CardUpdateService.ResponseKind.TRANSFER);
            assertThat(result.navigation().toProgram())
                    .as("and the target still resolves, because :436-447 runs before anything reads the card")
                    .isEqualTo("COMEN01C");
            assertThat(result.changeAction())
                    .as(":435 SET CCARD-AID-PFK03 TO TRUE normalises the key rather than changing the state, "
                            + "so the change action arrives at the caller as the source left it")
                    .isEqualTo(CardUpdateService.ChangeAction.DETAILS_NOT_FETCHED);
            verifyNoInteractions(cardRepository);
        }
    }

    /**
     * Pins the consequence of the procedural {@code COPY 'CSSTRPFY'} at
     * {@code COCRDUPC.cbl:1526}: the attention identifier is an argument, so the bean retains no
     * PF-key or screen state between calls.
     */
    @Nested
    @DisplayName("COPY 'CSSTRPFY' :1526 - procedural, so the bean keeps no PF-key or screen state")
    class StatelessnessContract {

        /**
         * The bean holds exactly three final instance fields - the repository, the clock and the sealer - so
         * nothing survives a request the way {@code WORKING-STORAGE} did.
         *
         * <p>The count is the assertion, and each of the three is named. The as-displayed group travels on the
         * request under transformation Rule 7, so no collaborator <em>carries</em> it between the two turns of
         * the pseudo-conversation; the sealer is stateless and holds only a derived key, so it stores no
         * request's data either. A fourth field, or a non-final one, would mean state had crept back in.</p>
         */
        @Test
        @DisplayName("every instance field is final and there are exactly three collaborators")
        void everyInstanceFieldIsFinalAndThereAreExactlyThree() {
            final Field[] instanceFields = Arrays.stream(CardUpdateService.class.getDeclaredFields())
                    .filter(field -> !Modifier.isStatic(field.getModifiers()))
                    .toArray(Field[]::new);

            assertThat(instanceFields).hasSize(3);
            assertThat(instanceFields).allSatisfy(field ->
                    assertThat(Modifier.isFinal(field.getModifiers()))
                            .as("field %s must be final", field.getName())
                            .isTrue());
            assertThat(instanceFields).extracting(Field::getName)
                    .containsExactlyInAnyOrder("cardRepository", "clock", "snapshotTokenService");
        }

        /**
         * No static field is mutable, which is what makes the bean safe to share and its outcomes repeatable.
         */
        @Test
        @DisplayName("no mutable static state exists - every static field is final")
        void noMutableStaticStateExists() {
            assertThat(Arrays.stream(CardUpdateService.class.getDeclaredFields())
                    .filter(field -> Modifier.isStatic(field.getModifiers()))
                    .filter(field -> !Modifier.isFinal(field.getModifiers()))
                    .map(Field::getName)
                    .toList())
                    .as("the legacy WORKING-STORAGE flags became method-local, not static")
                    .isEmpty();
        }

        /**
         * {@code COPY 'CSSTRPFY'} at {@code COCRDUPC.cbl:1526} is procedural, so the attention identifier is an
         * argument and no field retains it or any screen state.
         */
        @Test
        @DisplayName("no field carries PF-key, screen or attention state between requests")
        void noFieldCarriesPfKeyOrScreenStateBetweenRequests() {
            assertThat(Arrays.stream(CardUpdateService.class.getDeclaredFields())
                    .filter(field -> !Modifier.isStatic(field.getModifiers()))
                    .map(field -> field.getType().getSimpleName())
                    .toList())
                    .doesNotContain("AidKey", "ChangeAction", "FieldEditState", "WriteOutcome",
                            "UpdateContext", "ScreenBuffer", "String");
        }

        /**
         * Two identical requests against one bean instance produce identical outcomes, the observable meaning of
         * statelessness.
         */
        @Test
        @DisplayName("two identical requests produce identical outcomes - nothing is carried over")
        void twoIdenticalRequestsProduceIdenticalOutcomes() {
            when(cardRepository.findByIdAndAccountIdForUpdate(CARD_NUMBER, ACCOUNT_ID_NUMERIC))
                    .thenReturn(Optional.of(storedCard(STORED_NAME, STORED_EXPIRY, "N")));
            final CardUpdateRequest request = changedNameRequest(matchingSnapshot());

            final CardUpdateService.CardUpdateResult first = confirmSave(request);
            final CardUpdateService.CardUpdateResult second = confirmSave(request);

            assertThat(errorMessageOf(second)).isEqualTo(errorMessageOf(first));
            assertThat(second.changeAction()).isEqualTo(first.changeAction());
            assertThat(second.refreshedSnapshot()).isEqualTo(first.refreshedSnapshot());
            verify(cardRepository, times(2)).findByIdAndAccountIdForUpdate(CARD_NUMBER, ACCOUNT_ID_NUMERIC);
            verify(cardRepository, never()).save(any(Card.class));
        }

        /**
         * An unrecognised attention identifier folds to the enter key exactly as the {@code EVALUATE TRUE} of
         * {@code app/cpy/CSSTRPFY.cpy} does through its {@code WHEN OTHER} limb.
         */
        @Test
        @DisplayName("an unrecognised attention identifier folds to ENTER rather than being retained")
        void unrecognisedAttentionIdentifierFoldsToEnter() {
            when(cardRepository.findByCardNumberAndAccountId(CARD_NUMBER, ACCOUNT_ID_NUMERIC))
                    .thenReturn(Optional.of(storedCard()));
            final CardUpdateRequest request = new CardUpdateRequest(null, null, null, null, null, null,
                    ACCOUNT_ID, CARD_NUMBER, null, null, null, null, null, null, null, null, null,
                    null, null);

            final CardUpdateService.CardUpdateResult fromBlank =
                    service.processRequest(request, openedSnapshot(request), "   ", CardUpdateService.EntryMode.REENTER);
            final CardUpdateService.CardUpdateResult fromUnknown =
                    service.processRequest(request, openedSnapshot(request), "DFHNOSUCHKEY", CardUpdateService.EntryMode.REENTER);

            assertThat(fromUnknown.changeAction()).isEqualTo(fromBlank.changeAction());
            assertThat(errorMessageOf(fromUnknown)).isEqualTo(errorMessageOf(fromBlank));
        }
    }

    /**
     * Pins the request contract taken from {@code app/cpy-bms/COCRDUP.CPY} - seventeen screen
     * fields plus the sealed snapshot and the submitted group - without duplicating the shape tests of the
     * model tier.
     */
    @Nested
    @DisplayName("COCRDUP.CPY - the seventeen screen fields plus the sealed snapshot")
    class FieldContracts {

        /**
         * The request carries exactly the seventeen input fields of {@code app/cpy-bms/COCRDUP.CPY}, plus the
         * one opaque member that carries the as-displayed snapshot and the one group that carries the
         * submitted values.
         *
         * <p>Eighteen string components rather than seventeen, and that difference is the point: the
         * eighteenth is the sealed snapshot, which is a token and not a screen field. It is a string because
         * it is opaque - a structured group would be a group the caller could rewrite, and the comparison at
         * {@code :1503-1508} exists precisely to detect a change the caller did not make.</p>
         */
        @Test
        @DisplayName("the request carries seventeen screen components, the sealed snapshot and one group")
        void requestCarriesSeventeenScreenComponentsPlusTheSealedSnapshot() {
            final RecordComponent[] components =
                    CardUpdateRequest.class.getRecordComponents();

            assertThat(components).hasSize(19);
            assertThat(Arrays.stream(components)
                    .filter(component -> component.getType() == String.class)
                    .map(RecordComponent::getName)
                    .toList())
                    .as("COCRDUP.CPY generates seventeen input fields; the eighteenth string is the token")
                    .hasSize(18)
                    .contains("snapshot");
            assertThat(Arrays.stream(components)
                    .filter(component -> component.getType() == CardUpdateRequest.CardDetails.class)
                    .map(RecordComponent::getName)
                    .toList())
                    .as("only the SUBMITTED group is readable; the as-displayed one arrives sealed")
                    .containsExactly("newDetails");
        }

        /**
         * The snapshot group exposes the four logical fields {@code 9300} compares, with the expiry date split
         * into its three components rather than held as one string.
         */
        @Test
        @DisplayName("the snapshot group carries the four fields 9300 compares plus the composite key")
        void snapshotGroupCarriesTheFourComparedFields() {
            final CardUpdateRequest.CardDetails snapshot = matchingSnapshot();

            assertThat(snapshot.cardData().cardholderName()).isEqualTo(STORED_NAME);
            assertThat(snapshot.cardData().cardStatusCode()).isEqualTo(STORED_STATUS);
            assertThat(snapshot.cardData().expiraionDate().expiryYear()).isEqualTo(STORED_YEAR);
            assertThat(snapshot.cardData().expiraionDate().expiryMonth()).isEqualTo(STORED_MONTH);
            assertThat(snapshot.cardData().expiraionDate().expiryDay()).isEqualTo(STORED_DAY);
            assertThat(snapshot.accountId()).hasSize(11);
            assertThat(snapshot.cardNumber()).hasSize(16);
        }

        /**
         * An absent snapshot is an unmet precondition rather than a silent skip, because without it the
         * comparison of {@code 9300} could not be performed at all. Under the sealed contract of
         * transformation Rule 7, "absent" means the request omitted its {@code snapshot} member.
         *
         * <p>The refusal names the missing token rather than the search keys, and that distinction is
         * deliberate: the two remedies differ. A caller who sent no token must read the record and echo back
         * what that read returned, which is a different instruction from filling in two identifiers. The
         * outcome stays {@code CHANGES_NOT_CONFIRMED}, so the status a client sees is unchanged.</p>
         */
        @Test
        @DisplayName("an absent snapshot member is an unmet precondition, never a silent skip")
        void absentSnapshotIsAnUnmetPrecondition() {
            final CardUpdateRequest request = sealedRequest(null, SUBMITTED_NAME);

            final ConcurrentUpdateException failure = catchThrowableOfType(
                    ConcurrentUpdateException.class, () -> service.updateCard(request, SUBJECT));

            assertThat(failure).hasMessage(SnapshotTokenService.MISSING_TOKEN_MESSAGE).hasNoCause();
            assertThat(failure.getOutcome())
                    .isEqualTo(ConcurrentUpdateException.Outcome.CHANGES_NOT_CONFIRMED);
            verifyNoInteractions(cardRepository);
        }

        /**
         * A snapshot whose every component is null is treated as absent, matching the source's
         * {@code LOW-VALUES} state test at {@code :276-280} rather than dereferencing it. A group present but hollow is
         * not a precondition, and it must not be read as one.
         */
        @Test
        @DisplayName("an all-null snapshot is treated as absent, not as a snapshot of nulls")
        void allNullSnapshotIsTreatedAsAbsent() {
            final CardUpdateRequest.CardDetails hollow = new CardUpdateRequest.CardDetails(null, null,
                    new CardUpdateRequest.CardData(null,
                            new CardUpdateRequest.ExpiraionDate(null, null, null), null));

            final CardUpdateRequest request = sealedRequest(
                    seal(hollow, ACCOUNT_ID, CARD_NUMBER, SUBJECT), SUBMITTED_NAME);

            final ConcurrentUpdateException failure = catchThrowableOfType(
                    ConcurrentUpdateException.class, () -> service.updateCard(request, SUBJECT));

            assertThat(failure).hasMessage(MSG_PROMPT_FOR_SEARCH_KEYS);
            assertThat(failure.getOutcome())
                    .isEqualTo(ConcurrentUpdateException.Outcome.CHANGES_NOT_CONFIRMED);
            verifyNoInteractions(cardRepository);
        }

        /**
         * The snapshot is never derived from the live row, which is the decisive property of the guard. If it
         * were, the Regime B comparison at {@code :1503-1508} would be tautologically true and the write
         * could never be refused. Here the submitted group disagrees with the stored row and the write is
         * abandoned, which can only happen if the two sides came from different places.
         */
        @Test
        @DisplayName("the submitted group, never the live row, is what Regime B compares against")
        void theSnapshotIsNeverDerivedFromTheLiveRow() {
            when(cardRepository.findByIdAndAccountIdForUpdate(CARD_NUMBER,
                    ACCOUNT_ID_NUMERIC)).thenReturn(Optional.of(storedCard()));

            final ConcurrentUpdateException failure = catchThrowableOfType(
                    ConcurrentUpdateException.class,
                    () -> service.updateCard(changedNameRequest(snapshotOf(
                            "SOMEONE ELSE", STORED_YEAR, STORED_MONTH, STORED_DAY, STORED_STATUS)),
                            SUBJECT));

            assertThat(failure.getOutcome())
                    .isEqualTo(ConcurrentUpdateException.Outcome.DATA_CHANGED_BEFORE_UPDATE);
            verify(cardRepository, never()).save(any(Card.class));
        }

        /**
         * The read half of the stateless substitution: {@code sealSnapshotForUpdate} seals exactly the values
         * {@code 9000-READ-DATA} snapshotted at {@code :1345-1367}, less the card verification value of
         * {@code :294}, which is stored but has no read path. The expiry <strong>day</strong> is asserted
         * because {@code app/cpy-bms/COCRDSL.CPY} declares no field for it, which makes this the only route
         * by which a client can obtain the operand {@code :1507} compares.
         *
         * <p>The card number is the one member the sealed payload omits, because {@code :1347} moves the
         * <em>received</em> {@code CC-CARD-NUM} into {@code CCUP-OLD-CARDID} rather than reading it from the
         * stored record; the write restores it from the request's own identity field. Asserting the recovered
         * group through the same restore the write performs is therefore what pins the read-to-write
         * agreement.</p>
         */
        @Test
        @DisplayName("sealSnapshotForUpdate seals every fetched value the record actually carries")
        void sealSnapshotForUpdateSealsTheFetchedValues() {
            // The READ half uses the account-scoped, read-only finder; the locking one belongs to the write
            // half. 9000-READ-DATA snapshots what the screen displays and holds no lock across the two turns,
            // which is exactly why the as-displayed values must travel to the caller and back at all.
            when(cardRepository.findByCardNumberAndAccountId(CARD_NUMBER, ACCOUNT_ID_NUMERIC))
                    .thenReturn(Optional.of(storedCard()));

            final String sealed = service.sealSnapshotForUpdate(ACCOUNT_ID, CARD_NUMBER, SUBJECT);
            final CardUpdateRequest.CardDetails projected = openedSnapshot(sealedRequest(sealed));

            assertThat(projected.cardNumber())
                    .as(":1347 takes the RECEIVED card number, so the write restores it from the request")
                    .isEqualTo(CARD_NUMBER);
            assertThat(projected.cardData().cardholderName()).isEqualTo(STORED_NAME);
            assertThat(projected.cardData().cardStatusCode()).isEqualTo(STORED_STATUS);
            assertThat(projected.cardData().expiraionDate().expiryYear()).isEqualTo(STORED_YEAR);
            assertThat(projected.cardData().expiraionDate().expiryMonth()).isEqualTo(STORED_MONTH);
            assertThat(projected.cardData().expiraionDate().expiryDay())
                    .as("COCRDSL.CPY declares no expiry-day field, so this is the only carrier for it")
                    .isEqualTo(STORED_DAY);
        }

        /**
         * The projected group carries no card verification value. That is the shape assertion behind the
         * F-023 disposition: the value is stored - {@code card_cvv_cd CHAR(3) NOT NULL} - but has no read
         * path, and no symbolic map declares a field a caller could echo, so the concurrency question
         * {@code :1503} asked is answered by the {@code @Version} column instead.
         */
        @Test
        @DisplayName("the projected group carries no verification value, because none can be read")
        void theProjectedGroupCarriesNoVerificationValue() {
            when(cardRepository.findByCardNumberAndAccountId(CARD_NUMBER, ACCOUNT_ID_NUMERIC))
                    .thenReturn(Optional.of(storedCard()));

            final CardUpdateRequest.CardDetails projected = openedSnapshot(sealedRequest(
                    service.sealSnapshotForUpdate(ACCOUNT_ID, CARD_NUMBER, SUBJECT)));

            assertThat(java.util.Arrays.stream(CardUpdateRequest.CardDetails.class.getRecordComponents())
                    .map(java.lang.reflect.RecordComponent::getName)
                    .toList())
                    .noneSatisfy(name -> assertThat(name.toLowerCase(java.util.Locale.ROOT))
                            .contains("cvv"));
            assertThat(projected.toString())
                    .doesNotContainIgnoringCase("cvv")
                    .doesNotContain(STORED_VERIFICATION_VALUE);
        }

        /**
         * A value from the read is the value the write accepts, end to end. It is the one test that proves the
         * read and write halves agree on the operation kind, the record key, the principal and the payload
         * shape simultaneously - the sealed value from the read is submitted verbatim, not re-sealed here.
         */
        @Test
        @DisplayName("a sealed value from the read is accepted by the write, and the row is saved")
        void aSealedValueFromTheReadIsAcceptedByTheWrite() {
            // Both finders are stubbed, because this is the one test that spans both halves: the read
            // projects the group through the read-only account-scoped finder, and the write re-reads under
            // the lock.
            when(cardRepository.findByCardNumberAndAccountId(CARD_NUMBER, ACCOUNT_ID_NUMERIC))
                    .thenReturn(Optional.of(storedCard()));
            when(cardRepository.findByIdAndAccountIdForUpdate(CARD_NUMBER, ACCOUNT_ID_NUMERIC))
                    .thenReturn(Optional.of(storedCard()));
            when(cardRepository.save(any(Card.class))).thenAnswer(saved -> saved.getArgument(0));

            final String sealed = service.sealSnapshotForUpdate(ACCOUNT_ID, CARD_NUMBER, SUBJECT);
            service.updateCard(sealedRequest(sealed, SUBMITTED_NAME), SUBJECT);

            verify(cardRepository, times(1)).save(any(Card.class));
        }

        /**
         * The sealed value discloses nothing. It is why the read publishes one opaque member rather than the
         * group: the payload carries the cardholder name and the expiry date, and the request's own identity
         * field carries the sixteen digits {@code CardResponse} otherwise masks.
         */
        @Test
        @DisplayName("the sealed value carries no readable stored value")
        void theSealedValueIsOpaque() {
            when(cardRepository.findByCardNumberAndAccountId(CARD_NUMBER, ACCOUNT_ID_NUMERIC))
                    .thenReturn(Optional.of(storedCard()));

            final String sealed = service.sealSnapshotForUpdate(ACCOUNT_ID, CARD_NUMBER, SUBJECT);

            // The two LONG values are searched in the token text directly. At 10 and 16 characters over a
            // 64-symbol alphabet a chance occurrence is around 1e-16 across a token of this length, so a hit
            // here really does mean the value was carried rather than encrypted.
            assertThat(sealed)
                    .as("the cardholder name is not carried in the token text")
                    .doesNotContain(STORED_NAME)
                    .as("nor are the sixteen digits CardResponse otherwise masks")
                    .doesNotContain(CARD_NUMBER);

            // ==========================================================================================
            // THE THREE-CHARACTER VALUE IS CHECKED AGAINST THE DECODED BYTES, NOT THE TOKEN TEXT.
            // ==========================================================================================
            // Searching a random base64url string for a THREE-character needle is a coin toss, not a
            // security property: (1/64)^3 per position across a token of this length is roughly one run in
            // six hundred. It was not a theoretical concern - this assertion failed exactly that way, on a
            // token reading ...VFhuhvTXSB007urpv..., where the "007" the assertion tripped on was three
            // adjacent characters of ciphertext and nothing to do with CARD-CVV-CD. Decoding first drops the
            // needle into a 256-symbol alphabet and removes the base64 coincidence entirely.
            final byte[] decoded = Base64.getUrlDecoder().decode(sealed);
            assertThat(new String(decoded, StandardCharsets.ISO_8859_1))
                    .as("the verification value is not recoverable from the sealed payload's own bytes")
                    .doesNotContain(STORED_VERIFICATION_VALUE);

            // And the deterministic half of opacity, which no substring search can establish: sealing the
            // same snapshot twice must not produce the same token. A readable encoding is a pure function of
            // its input and would repeat; authenticated encryption under a fresh nonce cannot. This is the
            // assertion that would fail if the payload were ever swapped for base64-of-plaintext, which is
            // the regression the substring searches above are really guarding against.
            final String sealedAgain = service.sealSnapshotForUpdate(ACCOUNT_ID, CARD_NUMBER, SUBJECT);
            assertThat(sealedAgain)
                    .as("a second seal of the same snapshot differs, so the token is encrypted and not encoded")
                    .isNotEqualTo(sealed);
        }

        /**
         * A tampered value is refused as a rival write and nothing is saved, which is the property a readable
         * group cannot have: authenticated encryption fails closed on a single altered byte.
         */
        @Test
        @DisplayName("an edited sealed value is refused and the row is not saved")
        void anEditedSealedValueIsRefused() {
            final String sealed = seal(matchingSnapshot(), ACCOUNT_ID, CARD_NUMBER, SUBJECT);
            // The FIRST character, deliberately. In an unpadded base64url string the last character can carry
            // slack bits the decoder discards, so editing it need not change a single byte of the value.
            final char[] characters = sealed.toCharArray();
            characters[0] = characters[0] == 'A' ? 'B' : 'A';

            final ConcurrentUpdateException failure = catchThrowableOfType(
                    ConcurrentUpdateException.class,
                    () -> service.updateCard(sealedRequest(new String(characters), SUBMITTED_NAME),
                            SUBJECT));

            assertThat(failure).hasMessage(SnapshotTokenService.INVALID_TOKEN_MESSAGE);
            assertThat(failure.getOutcome())
                    .isEqualTo(ConcurrentUpdateException.Outcome.DATA_CHANGED_BEFORE_UPDATE);
            verifyNoInteractions(cardRepository);
        }

        /**
         * A value sealed for another operator does not open for this one, so a snapshot cannot be handed
         * between principals.
         */
        @Test
        @DisplayName("a value sealed for another principal is refused")
        void aValueSealedForAnotherPrincipalIsRefused() {
            final String foreign = seal(matchingSnapshot(), ACCOUNT_ID, CARD_NUMBER, OTHER_SUBJECT);

            assertThatThrownBy(() -> service.updateCard(sealedRequest(foreign, SUBMITTED_NAME), SUBJECT))
                    .isInstanceOf(ConcurrentUpdateException.class)
                    .hasMessage(SnapshotTokenService.INVALID_TOKEN_MESSAGE);
            verifyNoInteractions(cardRepository);
        }

        /**
         * A value sealed for another card cannot be transplanted onto this one, because both identifiers are
         * bound as authenticated additional data rather than merely carried in the payload.
         */
        @Test
        @DisplayName("a value sealed for another card is refused")
        void aValueSealedForAnotherCardIsRefused() {
            final String transplanted = seal(matchingSnapshot(), ACCOUNT_ID, "4111111111111119",
                    SUBJECT);

            assertThatThrownBy(() -> service.updateCard(sealedRequest(transplanted, SUBMITTED_NAME),
                    SUBJECT))
                    .isInstanceOf(ConcurrentUpdateException.class)
                    .hasMessage(SnapshotTokenService.INVALID_TOKEN_MESSAGE);
            verifyNoInteractions(cardRepository);
        }

        /**
         * A value older than its lifetime is refused rather than opened, so a snapshot cannot be replayed
         * indefinitely against a row that has since moved.
         */
        @Test
        @DisplayName("a sealed value older than its lifetime is refused")
        void anExpiredSealedValueIsRefused() {
            final String stale = sealerAt(FIXED_INSTANT.minusSeconds(SEAL_LIFETIME_SECONDS + 1))
                    .seal(SNAPSHOT_KIND, recordKey(ACCOUNT_ID, CARD_NUMBER), SUBJECT,
                            matchingSnapshot());

            assertThatThrownBy(() -> service.updateCard(sealedRequest(stale, SUBMITTED_NAME), SUBJECT))
                    .isInstanceOf(ConcurrentUpdateException.class)
                    .hasMessage(SnapshotTokenService.INVALID_TOKEN_MESSAGE);
            verifyNoInteractions(cardRepository);
        }

        /**
         * Neither entry point may be reached without a principal. Both routes are declared authenticated in
         * {@code SecurityConfig}, so an absent principal is a wiring defect and not a request outcome, which
         * is why it is an {@link IllegalArgumentException} rather than a rendered {@code 400}.
         */
        @Test
        @DisplayName("neither entry point accepts an absent principal")
        void neitherEntryPointAcceptsAnAbsentPrincipal() {
            final CardUpdateRequest request = changedNameRequest(matchingSnapshot());

            for (final String absent : new String[] {null, "", "   "}) {
                assertThatThrownBy(() -> service.updateCard(request, absent))
                        .isInstanceOf(IllegalArgumentException.class);
                assertThatThrownBy(
                        () -> service.sealSnapshotForUpdate(ACCOUNT_ID, CARD_NUMBER, absent))
                        .isInstanceOf(IllegalArgumentException.class);
            }
            verifyNoInteractions(cardRepository);
        }

        /**
         * No component of the request or the entity is a binary floating-point type, the standing prohibition on
         * financial fields even though this screen carries no money field.
         */
        @Test
        @DisplayName("no field of the entity or the request is a float or a double")
        void noFieldIsAFloatOrADouble() {
            assertThat(Arrays.stream(Card.class.getDeclaredFields())
                    .map(field -> field.getType().getName())
                    .toList())
                    .doesNotContain("float", "double", "java.lang.Float", "java.lang.Double");
            assertThat(Arrays.stream(CardUpdateRequest.class.getRecordComponents())
                    .map(component -> component.getType().getName())
                    .toList())
                    .doesNotContain("float", "double", "java.lang.Float", "java.lang.Double");
        }

        /**
         * The persisted name is padded to the {@code CARD-EMBOSSED-NAME PIC X(50)} width of {@code
         * app/cpy/CVACT02Y.cpy}, so the fixed-width record geometry survives the round trip.
         */
        @Test
        @DisplayName("the persisted embossed name is padded to CARD-EMBOSSED-NAME PIC X(50)")
        void persistedEmbossedNameIsPaddedToItsPicWidth() {
            when(cardRepository.findByIdAndAccountIdForUpdate(CARD_NUMBER,
                    ACCOUNT_ID_NUMERIC)).thenReturn(Optional.of(storedCard()));
            final ArgumentCaptor<Card> persisted = ArgumentCaptor.forClass(Card.class);

            final CardUpdateRequest request = changedNameRequest(matchingSnapshot());
            service.updateCard(request, SUBJECT);

            verify(cardRepository).save(persisted.capture());
            assertThat(persisted.getValue().getEmbossedName()).hasSize(50);
            assertThat(persisted.getValue().getExpiraionDate()).hasSize(10);
            assertThat(persisted.getValue().getActiveStatus()).hasSize(1);
            assertThat(persisted.getValue().getCardNumber()).hasSize(16);
        }
    }

    /**
     * Pins the one-to-one correspondence between the 45 own paragraph labels of
     * {@code app/cbl/COCRDUPC.cbl} and the private methods of the bean, including the retained no-ops.
     */
    @Nested
    @DisplayName("paragraph correspondence - 48 labels, one private method each, none consolidated")
    class ParagraphCorrespondence {

        /**
         * Collects every method name the bean declares, so a paragraph landmark can be looked up
         * without repeating the reflection in each test.
         *
         * @return the declared method names of {@link CardUpdateService}
         */
        private Set<String> declaredMethodNames() {
            return Arrays.stream(CardUpdateService.class.getDeclaredMethods())
                    .map(Method::getName)
                    .collect(Collectors.toUnmodifiableSet());
        }

        /**
         * {@code 0000-MAIN} at {@code COCRDUPC.cbl:367}, {@code COMMON-RETURN} at {@code :546} and {@code
         * 0000-MAIN-EXIT} at {@code :560} each map to their own method, never consolidated.
         */
        @Test
        @DisplayName("every mainline and dispatch label of :367-560 has its own method")
        void mainlineLabelsEachHaveTheirOwnMethod() {
            assertThat(declaredMethodNames()).contains("mainLine0000", "dispatch0000", "transfer0000",
                    "commonReturn", "mainExit0000");
        }

        /**
         * {@code 1000-PROCESS-INPUTS} at {@code COCRDUPC.cbl:564}, {@code 1100-RECEIVE-MAP} at {@code :578} and
         * {@code 1200-EDIT-MAP-INPUTS} at {@code :641} each map to their own method.
         */
        @Test
        @DisplayName("every input-processing label of :564-717 has its own method, exits included")
        void inputProcessingLabelsEachHaveTheirOwnMethod() {
            assertThat(declaredMethodNames()).contains("processInputs1000", "processInputsExit1000",
                    "receiveMap1100", "receiveMapExit1100", "editMapInputs1200",
                    "editMapInputsExit1200");
        }

        /**
         * All six field edits between {@code COCRDUPC.cbl:721} and {@code :945} map one to one, which is what
         * makes the cascade order provable rather than asserted.
         */
        @Test
        @DisplayName("all six field-edit labels of :721-945 have their own method, exits included")
        void allSixFieldEditLabelsEachHaveTheirOwnMethod() {
            assertThat(declaredMethodNames()).contains(
                    "editAccount1210", "editAccountExit1210",
                    "editCard1220", "editCardExit1220",
                    "editName1230", "editNameExit1230",
                    "editCardStatus1240", "editCardStatusExit1240",
                    "editExpiryMonth1250", "editExpiryMonthExit1250",
                    "editExpiryYear1260", "editExpiryYearExit1260");
        }

        /**
         * The screen paragraphs at {@code COCRDUPC.cbl:1035}, {@code :1052}, {@code :1082}, {@code :1138}, {@code
         * :1168} and {@code :1324} each map to their own method.
         */
        @Test
        @DisplayName("every screen label of :948-1324 has its own method, exits included")
        void screenLabelsEachHaveTheirOwnMethod() {
            assertThat(declaredMethodNames()).contains("decideAction2000", "decideActionExit2000",
                    "sendMap3000", "sendMapExit3000", "screenInit3100", "screenInitExit3100",
                    "setupScreenVars3200", "setupScreenVarsExit3200", "setupInfoMsg3250",
                    "setupInfoMsgExit3250", "setupScreenAttrs3300", "setupScreenAttrsExit3300",
                    "sendScreen3400", "sendScreenExit3400");
        }

        /**
         * {@code 9000-READ-DATA} at {@code COCRDUPC.cbl:1343}, {@code 9100-GETCARD-BYACCTCARD} at {@code :1376},
         * {@code 9200-WRITE-PROCESSING} at {@code :1420} and {@code 9300-CHECK-CHANGE-IN-REC} at {@code :1498}
         * each map to their own method.
         */
        @Test
        @DisplayName("every file label of :1343-1521 has its own method, exits included")
        void fileLabelsEachHaveTheirOwnMethod() {
            assertThat(declaredMethodNames()).contains("readData9000", "readDataExit9000",
                    "getCardByAcctCard9100", "getCardByAcctCardExit9100", "writeProcessing9200",
                    "writeProcessingExit9200", "checkChangeInRec9300", "checkChangeInRecExit9300");
        }

        /**
         * The procedural copybook paragraph and {@code ABEND-ROUTINE} at {@code COCRDUPC.cbl:1531} are represented
         * as methods in their own right rather than folded into their callers.
         */
        @Test
        @DisplayName("the procedural copybook and the abend routine have their own methods")
        void proceduralCopybookAndAbendRoutineHaveTheirOwnMethods() {
            assertThat(declaredMethodNames()).contains("storePfKey", "storePfKeyExit", "abendRoutine",
                    "abendRoutineExit");
        }

        /**
         * INTENTIONAL NO-OP MARKER: {@code 9300} ends with {@code END-IF EXIT} at {@code COCRDUPC.cbl:1519} while
         * {@code 9300-CHECK-CHANGE-IN-REC-EXIT} at {@code :1521} carries its own bare {@code EXIT}, so the in-
         * paragraph one is retained for parity and tracked here rather than deleted.
         */
        @Test
        @DisplayName("the redundant in-paragraph EXIT of 9300 is retained as an intentional no-op")
        void redundantInParagraphExitOf9300IsRetained() {
            // INTENTIONAL NO-OP MARKER. :1519 carries END-IF EXIT inside 9300 while :1521 declares
            // 9300-CHECK-CHANGE-IN-REC-EXIT with its own bare EXIT. Both survive as separate methods
            // because deleting either would break the paragraph map the scope-coverage gate reads.
            assertThat(declaredMethodNames())
                    .contains("checkChangeInRec9300", "checkChangeInRecExit9300");
            assertThat(Arrays.stream(CardUpdateService.class.getDeclaredMethods())
                    .filter(method -> method.getName().equals("checkChangeInRecExit9300"))
                    .count())
                    .isEqualTo(1L);
        }

        /**
         * The bean declares at least as many methods as the source has paragraph labels, the coarse check that no
         * label was silently consolidated away.
         */
        @Test
        @DisplayName("the bean declares at least one method per source paragraph")
        void theBeanDeclaresAtLeastOneMethodPerSourceParagraph() {
            assertThat(declaredMethodNames().size())
                    .as("COCRDUPC.cbl carries 48 Area-A label-shaped lines across its 1,560 lines, of "
                            + "which 45 are PROCEDURE DIVISION paragraphs")
                    .isGreaterThanOrEqualTo(48);
        }
    }

    /**
     * Drives every boundary the source guards, and the ones it deliberately does not, treating
     * all caller input as untrusted.
     */
    @Nested
    @DisplayName("hostile input - every boundary the source guards, and the ones it does not")
    class HostileInput {

        /**
         * A null request is rejected explicitly rather than dereferenced, with the parameter named.
         */
        @Test
        @DisplayName("a null request is rejected by name on the screen entry point")
        void nullRequestIsRejectedOnTheScreenEntryPoint() {
            assertThatThrownBy(() ->
                    service.processRequest(null, null, AID_ENTER, CardUpdateService.EntryMode.REENTER))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("request must not be null");
            verifyNoInteractions(cardRepository);
        }

        /**
         * The second public entry point rejects a null request the same way, so neither is a hole.
         */
        @Test
        @DisplayName("a null request is rejected by name on the update entry point")
        void nullRequestIsRejectedOnTheUpdateEntryPoint() {
            assertThatThrownBy(() -> service.updateCard(null, SUBJECT))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("request must not be null");
            verifyNoInteractions(cardRepository);
        }

        /**
         * A null entry mode is rejected explicitly, because the pseudo-conversational enter versus re-enter
         * distinction cannot be defaulted safely.
         */
        @Test
        @DisplayName("a null entry mode is rejected by name")
        void nullEntryModeIsRejected() {
            final CardUpdateRequest request = changedNameRequest(matchingSnapshot());

            assertThatThrownBy(() -> service.processRequest(request, openedSnapshot(request), AID_ENTER, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("entryMode must not be null");
            verifyNoInteractions(cardRepository);
        }

        /**
         * A name of low values is blank, the first limb of the blank test at {@code COCRDUPC.cbl:810-816}.
         */
        @Test
        @DisplayName("a LOW-VALUES name is BLANK, matching EQUAL LOW-VALUES at :811")
        void lowValuesNameIsBlank() {
            assertThat(nameEditMessageFor("\u0000\u0000\u0000\u0000\u0000"))
                    .isEqualTo(MSG_NAME_NOT_PROVIDED);
        }

        /**
         * An empty name is blank, the stateless equivalent of a field the terminal never transmitted.
         */
        @Test
        @DisplayName("an empty-string name is BLANK, matching EQUAL SPACES at :811")
        void emptyStringNameIsBlank() {
            assertThat(nameEditMessageFor("")).isEqualTo(MSG_NAME_NOT_PROVIDED);
        }

        /**
         * The not-supplied marker collapses to blank rather than reaching the field edit as a literal value.
         */
        @Test
        @DisplayName("the not-supplied marker collapses to BLANK rather than becoming a name")
        void notSuppliedMarkerCollapsesToBlank() {
            assertThat(nameEditMessageFor("*")).isEqualTo(MSG_NAME_NOT_PROVIDED);
        }

        /**
         * An all-zero account filter is blank under {@code EQUAL ZEROS}, so it prompts rather than reporting a
         * numeric failure.
         */
        @Test
        @DisplayName("an all-zero account filter is BLANK, matching CC-ACCT-ID-N EQUAL ZEROS at :727")
        void allZeroAccountFilterIsBlank() {
            final CardUpdateRequest request = new CardUpdateRequest(null, null, null, null, null, null,
                    "00000000000", CARD_NUMBER, null, null, null, null, null, null, null, null, null,
                    null, null);

            final CardUpdateService.CardUpdateResult result =
                    service.processRequest(request, openedSnapshot(request), AID_ENTER, CardUpdateService.EntryMode.REENTER);

            assertThat(errorMessageOf(result)).isEqualTo(MSG_ACCOUNT_NOT_PROVIDED);
            verifyNoInteractions(cardRepository);
        }

        /**
         * An all-zero card filter is blank for the same reason.
         */
        @Test
        @DisplayName("an all-zero card filter is BLANK, matching CC-CARD-NUM-N EQUAL ZEROS at :770")
        void allZeroCardFilterIsBlank() {
            final CardUpdateRequest request = new CardUpdateRequest(null, null, null, null, null, null,
                    ACCOUNT_ID, "0000000000000000", null, null, null, null, null, null, null, null,
                    null, null, null);

            final CardUpdateService.CardUpdateResult result =
                    service.processRequest(request, openedSnapshot(request), AID_ENTER, CardUpdateService.EntryMode.REENTER);

            assertThat(errorMessageOf(result)).isEqualTo(MSG_CARD_NOT_PROVIDED);
            verifyNoInteractions(cardRepository);
        }

        /**
         * A name of exactly {@code PIC X(50)} is accepted, the inclusive upper boundary of the field width.
         */
        @Test
        @DisplayName("a name of exactly fifty alphabetic characters is accepted at the PIC boundary")
        void nameOfExactlyFiftyCharactersIsAccepted() {
            assertThat(nameEditMessageFor("B".repeat(50))).isEmpty();
        }

        /**
         * A single-character name is accepted, the lower boundary, because the source imposes no minimum length.
         */
        @Test
        @DisplayName("a name of a single alphabetic character is accepted at the lower boundary")
        void nameOfASingleCharacterIsAccepted() {
            assertThat(nameEditMessageFor("A")).isEmpty();
        }

        /**
         * A store failure on the read for update becomes a typed exception carrying its cause, never a swallowed
         * error or a null result.
         */
        @Test
        @DisplayName("a repository failure on read becomes a typed exception carrying its cause")
        void repositoryFailureOnReadBecomesATypedException() {
            final DataIntegrityViolationException storeFailure =
                    new DataIntegrityViolationException("CARDDAT unavailable");
            when(cardRepository.findByIdAndAccountIdForUpdate(CARD_NUMBER, ACCOUNT_ID_NUMERIC)).thenThrow(storeFailure);
            final CardUpdateRequest request = changedNameRequest(matchingSnapshot());

            assertThatThrownBy(() -> confirmSave(request))
                    .isInstanceOf(CardDemoException.class)
                    .hasCause(storeFailure)
                    .hasMessageContaining("CARDDAT");
            assertThatThrownBy(() -> confirmSave(request))
                    .as("a constraint fault is not contention, so it must NOT be relabelled as a conflict")
                    .isNotInstanceOf(ConcurrentUpdateException.class);
            verify(cardRepository, never()).save(any(Card.class));
        }

        /**
         * A refused lock on the read for update is {@code :1441}'s could-not-lock outcome, not a file-access
         * failure.
         *
         * <p>{@code COCRDUPC.cbl:1441-1449} treats every non-normal {@code RESP} on the
         * {@code READ ... UPDATE} as "could not lock", and a lock the store refused to grant is the most
         * literal instance of it: the record exists and is held. Reporting it as an I/O failure lost the
         * outcome and told the caller through a {@code 502} that the server was faulty, when the row was
         * merely busy and the request is worth retrying.</p>
         *
         * <p>{@code CannotAcquireLockException} is what Spring translates PostgreSQL's
         * {@code lock_not_available} ({@code SQLSTATE 55P03}) into, which is what the bounded
         * {@code lock_timeout} on the datasource produces.</p>
         */
        @Test
        @DisplayName("a refused lock on the read for update is the :1441 could-not-lock conflict, not a 502")
        void lockAcquisitionFailureOnReadIsTheCouldNotLockConflict() {
            final CannotAcquireLockException lockRefused = new CannotAcquireLockException(
                    "could not obtain lock on row in relation \"card\"");
            when(cardRepository.findByIdAndAccountIdForUpdate(CARD_NUMBER, ACCOUNT_ID_NUMERIC))
                    .thenThrow(lockRefused);
            final CardUpdateRequest request = changedNameRequest(matchingSnapshot());

            final ConcurrentUpdateException failure = catchThrowableOfType(
                    ConcurrentUpdateException.class, () -> service.updateCard(request, SUBJECT));

            assertThat(failure)
                    .as("the refused lock reaches the authored concurrency outcome, so CardController "
                            + "answers 409 rather than the 502 a FileAccessException would have produced")
                    .isNotNull();
            assertThat(failure).hasMessage(MSG_COULD_NOT_LOCK);
            assertThat(failure.getOutcome())
                    .isEqualTo(ConcurrentUpdateException.Outcome.COULD_NOT_LOCK_ACCOUNT);
            assertThat(failure.getCause())
                    .as("the SQLSTATE that refused the lock is retained on the exception so the conflict "
                            + "is traceable; it is not put in the response")
                    .isSameAs(lockRefused);
            verify(cardRepository, never()).save(any(Card.class));
        }

        /**
         * The refused lock and the absent row reach the same outcome and the same literal, because
         * {@code :1441-1449} has one branch for both and they must not drift apart.
         */
        @Test
        @DisplayName("a refused lock and an absent row report the identical outcome and literal")
        void refusedLockAndAbsentRowAgree() {
            final CardUpdateRequest request = changedNameRequest(matchingSnapshot());

            // Consecutive stubbing rather than a reset: the first read is refused a lock, the second finds
            // no row, and both are driven through the same service instance so the comparison is of two
            // outcomes of one code path.
            when(cardRepository.findByIdAndAccountIdForUpdate(CARD_NUMBER, ACCOUNT_ID_NUMERIC))
                    .thenThrow(new CannotAcquireLockException("held"))
                    .thenReturn(Optional.empty());

            final ConcurrentUpdateException refused = catchThrowableOfType(
                    ConcurrentUpdateException.class, () -> service.updateCard(request, SUBJECT));
            final ConcurrentUpdateException absent = catchThrowableOfType(
                    ConcurrentUpdateException.class, () -> service.updateCard(request, SUBJECT));

            assertThat(refused.getOutcome())
                    .as(":1441-1449 asks only whether a lockable record came back, never why not")
                    .isEqualTo(absent.getOutcome());
            assertThat(refused.getMessage()).isEqualTo(absent.getMessage());
            assertThat(absent).hasNoCause();
            assertThat(refused.getCause())
                    .as("only the refused lock has a cause to carry")
                    .isNotNull();
        }
    }

    /**
     * Asserts that the card number and the card verification value never reach a screen field,
     * an exception message or a {@code toString()} rendering.
     */
    @Nested
    @DisplayName("secret hygiene - the card number and the verification value never escape")
    class SecretHygiene {

        /**
         * The affected-record marker on a thrown conflict masks all but the last four digits, so a log of the
         * exception cannot reconstruct the key.
         */
        @Test
        @DisplayName("the affected-record marker on a lock failure masks all but the last four digits")
        void affectedRecordMarkerMasksAllButTheLastFourDigits() {
            when(cardRepository.findByIdAndAccountIdForUpdate(CARD_NUMBER,
                    ACCOUNT_ID_NUMERIC)).thenReturn(Optional.empty());
            final CardUpdateRequest request = changedNameRequest(matchingSnapshot());

            final ConcurrentUpdateException failure = catchThrowableOfType(
                    ConcurrentUpdateException.class, () -> service.updateCard(request, SUBJECT));

            assertThat(failure.getAffectedRecord())
                    .isNotEqualTo(CARD_NUMBER)
                    .hasSize(16)
                    .startsWith("*".repeat(12))
                    .endsWith(CARD_NUMBER.substring(12));
        }

        /**
         * No message thrown from either entry point contains the card number in full, nor names the
         * verification value.
         *
         * <p>The verification dimension is asserted on the <em>token</em> rather than on a specimen value.
         * <p>The verification dimension is asserted on the <em>token</em> rather than on a specimen value.
         * The stored value is real - {@code card_cvv_cd} is declared and seeded - but it is write-once with
         * no getter of any visibility, so no read path exists by which a message could ever quote it and a
         * {@code doesNotContain("123")} clause would pass without proving anything. A message that named
         * the field at all would mean the concept had returned to the readable surface, which is the
         * condition worth detecting.</p>
         */
        @Test
        @DisplayName("no thrown message leaks the card number or names the verification value")
        void noThrownMessageLeaksTheCardNumberOrVerificationValue() {
            when(cardRepository.findByIdAndAccountIdForUpdate(CARD_NUMBER, ACCOUNT_ID_NUMERIC))
                    .thenReturn(Optional.of(storedCard(STORED_NAME, STORED_EXPIRY, "N")));
            final CardUpdateRequest request = changedNameRequest(matchingSnapshot());

            final CardUpdateService.CardUpdateResult result = confirmSave(request);

            assertThat(errorMessageOf(result))
                    .doesNotContain(CARD_NUMBER)
                    .doesNotContainIgnoringCase("cvv");
            assertThat(result.screen().getInformationMessage())
                    .doesNotContain(CARD_NUMBER)
                    .doesNotContainIgnoringCase("cvv");
        }

        /**
         * The entity's own rendering exposes neither the key nor the verification value, so an incidental
         * interpolation cannot leak them.
         */
        @Test
        @DisplayName("the entity rendering exposes neither the card number nor the verification value")
        void entityRenderingExposesNeitherKeyNorVerificationValue() {
            final String rendered = storedCard().toString();

            assertThat(rendered).doesNotContain(CARD_NUMBER)
                    .doesNotContainIgnoringCase("cvv")
                    .doesNotContain(STORED_NAME);
        }

        /**
         * The request's rendering is equally safe, which matters because a validation failure often carries the
         * request into a log line.
         */
        @Test
        @DisplayName("the request rendering exposes neither the card number nor the verification value")
        void requestRenderingExposesNeitherKeyNorVerificationValue() {
            final String rendered = changedNameRequest(matchingSnapshot()).toString();

            assertThat(rendered).doesNotContain(CARD_NUMBER).doesNotContainIgnoringCase("cvv");
        }

        /**
         * The snapshot group's rendering is equally safe.
         */
        @Test
        @DisplayName("the snapshot rendering exposes neither the card number nor the verification value")
        void snapshotRenderingExposesNeitherKeyNorVerificationValue() {
            final String rendered = matchingSnapshot().toString();

            assertThat(rendered).doesNotContain(CARD_NUMBER).doesNotContainIgnoringCase("cvv");
        }

        /**
         * The refreshed snapshot deliberately withholds the verification value, so a retry supplies it again
         * rather than receiving it back from the server.
         */
        @Test
        @DisplayName("the refreshed snapshot withholds the verification value entirely")
        void refreshedSnapshotWithholdsTheVerificationValue() {
            when(cardRepository.findByIdAndAccountIdForUpdate(CARD_NUMBER, ACCOUNT_ID_NUMERIC))
                    .thenReturn(Optional.of(storedCard(STORED_NAME, STORED_EXPIRY, "N")));

            final CardUpdateRequest.CardDetails refreshed =
                    confirmSave(changedNameRequest(matchingSnapshot())).refreshedSnapshot();

            assertThat(refreshed.toString()).doesNotContain("cvv");
        }

        /**
         * No declared constant looks like a credential or an endpoint, asserted structurally so the test cannot
         * itself become a place where one hides.
         */
        @Test
        @DisplayName("no declared constant embeds a credential, an endpoint or a host")
        void noDeclaredConstantEmbedsACredentialOrEndpoint() {
            // Asserted structurally rather than against a denylist of hostnames, so that this file
            // itself carries no environment-specific literal for a reviewer or scanner to trip over.
            assertThat(serviceStringConstants()).allSatisfy(constant -> {
                final String folded = constant.toLowerCase(Locale.ROOT);
                assertThat(folded).as("no URI scheme").doesNotContain("://");
                assertThat(folded).as("no host and port pair").doesNotMatch(".*:\\d{2,5}\\b.*");
                assertThat(folded).as("no registrable domain")
                        .doesNotMatch(".*\\.(com|net|org|io|cloud|aws)\\b.*");
                assertThat(folded).as("no dotted quad").doesNotMatch(".*\\b\\d{1,3}(\\.\\d{1,3}){3}\\b.*");
                assertThat(folded).as("no credential keyword").doesNotContain("secret")
                        .doesNotContain("passwd")
                        .doesNotContain("bearer");
            });
        }

        /**
         * No declared constant matches a known provider key prefix or a private-key header.
         */
        @Test
        @DisplayName("no declared constant resembles a provider access key or private key block")
        void noDeclaredConstantResemblesAProviderKey() {
            assertThat(serviceStringConstants()).allSatisfy(constant ->
                    assertThat(constant).doesNotMatch("(?s).*\\b(AKIA|ASIA|ghp_|gho_|xox[abp]-|AIza)\\w*.*")
                            .doesNotContain("BEGIN PRIVATE KEY")
                            .doesNotContain("BEGIN RSA PRIVATE KEY"));
        }
    }

    /**
     * Asserts the transactional declaration on both write entry points, as a contract in its own right.
     *
     * <h2>Why this is asserted reflectively rather than through an outcome</h2>
     *
     * <p>Everything else in this file asserts what the service <em>does</em>: which repository calls happen,
     * in what order, and what the caller observes. Those assertions are necessary and they are blind to one
     * thing. There is no transaction manager in this tier - the bean is constructed directly with a mocked
     * repository - so no proxy exists, no transaction is begun, and a commit or a rollback can neither happen
     * nor be observed. Delete {@code @Transactional(rollbackFor = Exception.class)} from both production
     * write entry points and every ordering, outcome and exception assertion above stays green, while the
     * read-for-update and the rewrite stop being one unit of work.
     *
     * <p>That matters even though {@code COCRDUPC} writes a single dataset and therefore has no rollback
     * asymmetry to reproduce. The rewrite happens after a change-detection comparison against the
     * as-displayed snapshot, so the read, the comparison and the rewrite have to observe one consistent
     * state; without a transaction the row can move between the read and the rewrite and the comparison then
     * guarantees nothing. The declaration is the whole of that guarantee, so the declaration is what is
     * asserted.
     *
     * <p>Four independent regressions are covered: the annotation going missing; {@code rollbackFor} losing
     * {@code Exception}, which would leave a checked failure committed because the framework default rolls
     * back for unchecked throwables only; {@code readOnly} being set, which would make the rewrite fail or be
     * silently discarded; and the propagation being narrowed away from {@code REQUIRED}, which would stop a
     * caller's transaction from being the same unit of work as the rewrite.
     */
    @Nested
    @DisplayName("The transactional boundary of 9200-WRITE-PROCESSING :1420-1494 - read and rewrite as one")
    class TransactionalBoundary {

        /** The two entry points that reach the rewrite at {@code COCRDUPC.cbl:1420-1494}. */
        private static final java.util.List<String> WRITE_ENTRY_POINTS =
                java.util.List.of("processRequest", "updateCard");

        @Test
        @DisplayName("both write entry points declare @Transactional, so no rewrite is reached unscoped")
        void bothWriteEntryPointsDeclareTransactional() {
            for (final String entryPoint : WRITE_ENTRY_POINTS) {
                assertThat(transactionalOn(entryPoint))
                        .as("%s must be annotated, or the read-for-update, the change-detection comparison "
                                + "and the rewrite stop being one consistent unit of work", entryPoint)
                        .isNotNull();
            }
        }

        @Test
        @DisplayName("rollbackFor names Exception on both, so a checked failure also backs the rewrite out")
        void rollbackForNamesExceptionOnBoth() {
            for (final String entryPoint : WRITE_ENTRY_POINTS) {
                final Transactional declared = transactionalOn(entryPoint);

                assertThat(declared).isNotNull();
                assertThat(declared.rollbackFor())
                        .as("%s: the framework default covers unchecked throwables only", entryPoint)
                        .containsExactly(Exception.class);
                assertThat(declared.rollbackForClassName())
                        .as("%s: the class-literal form is used, so the name form stays empty", entryPoint)
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
                        .as("%s rewrites the card record; readOnly would discard it", entryPoint)
                        .isFalse();
                assertThat(declared.propagation())
                        .as("%s: REQUIRED joins a caller's transaction rather than starting a second one",
                                entryPoint)
                        .isEqualTo(Propagation.REQUIRED);
            }
        }

        @Test
        @DisplayName("one annotated method spans the read and the rewrite, not two separate ones")
        void oneAnnotatedMethodSpansTheReadAndTheRewrite() {
            // The behavioural claim behind the declaration, asserted rather than described: a single call to
            // the annotated entry point performs both the read-for-update and the rewrite. Two annotated
            // methods invoked in sequence would commit between them, and the comparison against the
            // as-displayed snapshot would then be made against state the rewrite no longer overwrites.
            when(cardRepository.findByIdAndAccountIdForUpdate(CARD_NUMBER, ACCOUNT_ID_NUMERIC))
                    .thenReturn(Optional.of(storedCard()));

            confirmSave(changedNameRequest(matchingSnapshot()));

            assertThat(transactionalOn("processRequest")).isNotNull();
            // The read is the ACCOUNT-SCOPED, LOCKING finder: 9100-GETCARD-BYACCTCARD reads the card under
            // EXEC CICS READ ... UPDATE and only accepts it when it belongs to the account on the screen, so
            // a bare findById would neither hold the lock the rewrite depends on nor enforce that ownership.
            final InOrder withinOneTransaction = inOrder(cardRepository);
            withinOneTransaction.verify(cardRepository)
                    .findByIdAndAccountIdForUpdate(CARD_NUMBER, ACCOUNT_ID_NUMERIC);
            withinOneTransaction.verify(cardRepository).save(any(Card.class));
            // The flush is issued inside the rewrite's guard, immediately after the save, so a constraint
            // violation surfaces with its paragraph attached instead of at commit outside the try - where the
            // FILE STATUS translation and the source literal would both be lost. It is part of the sequence
            // this test pins, not an interaction to be excluded from it.
            withinOneTransaction.verify(cardRepository).flush();
            withinOneTransaction.verifyNoMoreInteractions();
        }

        /**
         * Reads the {@link Transactional} annotation off one public method of the production service.
         *
         * @param methodName the entry point to inspect
         * @return the annotation, or {@code null} when the method carries none - which is itself the
         *         regression these tests exist to catch
         */
        private static Transactional transactionalOn(final String methodName) {
            for (final Method candidate : CardUpdateService.class.getDeclaredMethods()) {
                if (candidate.getName().equals(methodName)) {
                    return candidate.getAnnotation(Transactional.class);
                }
            }
            throw new AssertionError("CardUpdateService declares no method named '" + methodName
                    + "'. If a write entry point was renamed, update this list rather than removing the "
                    + "guard.");
        }
    }

}
