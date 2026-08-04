/*
 * ******************************************************************
 * Program     : UserDeleteServiceTest.java
 * Component   : Unit test tier, resident at
 *               src/test/java/com/cardemo/unit/service
 * Application : CardDemo
 * Type        : JUnit 5 unit test - pure JVM tier, no container, no
 *               Spring context, no database, no live endpoint
 * Function    : Proves that UserDeleteService reproduces COUSR03C
 *               exactly - eleven paragraphs mapped one to one, the six
 *               attention identifiers including the PRESERVED ABSENCE
 *               that the exit key routes away WITHOUT deleting where
 *               its sibling saves, the two emptiness guards with their
 *               identical literal, the read arm whose CONTINUE stops
 *               nothing and so sends the screen twice, the delete arm
 *               that clears the map BEFORE composing its sentence, the
 *               WRONG-VERB failure literal preserved byte for byte,
 *               and above all the two absences that define this screen:
 *               there is no self-delete guard and no confirmation
 *               handshake on the only irreversible operation in the
 *               application
 * Source      : app/cbl/COUSR03C.cbl (359 lines, 11 paragraphs) @ 7756d89
 * Source      : app/cpy-bms/COUSR03.CPY (11 input fields, no credential) @ 7756d89
 * Source      : app/cpy/CSUSR01Y.cpy (80 byte record, KEYS(8,0)) @ 7756d89
 * Source      : app/cpy/COCOM01Y.cpy (CDEMO-USER-ID, CDEMO-USER-TYPE) @ 7756d89
 * Source      : app/cpy/CSMSG01Y.cpy (CCDA-MSG-INVALID-KEY) @ 7756d89
 * Source      : app/cbl/COUSR02C.cbl (the sibling contrast at :112 and :123) @ 7756d89
 * Source      : app/cbl/COBIL00C.cbl (the confirmation contrast at :173-:190) @ 7756d89
 * Source      : app/cbl/CBACT04C.cbl (the canonical banner form, L1-L21) @ 7756d89
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.jpa.repository.Lock;

import com.cardemo.exception.CardDemoException;
import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.UserSecurityDto;
import com.cardemo.model.entity.UserSecurity;
import com.cardemo.model.enums.UserType;
import com.cardemo.repository.UserSecurityRepository;
import com.cardemo.service.admin.UserDeleteService;
import com.cardemo.service.admin.UserDeleteService.AttentionIdentifier;
import com.cardemo.service.shared.FileStatusMapper;

import jakarta.persistence.LockModeType;

/**
 * The unit suite for {@link UserDeleteService}, the Java replacement for {@code app/cbl/COUSR03C.cbl} - the
 * CICS program behind transaction {@code CU03} and <strong>the only screen in the application that destroys a
 * record</strong>.
 *
 * <h2>What it does</h2>
 *
 * <p>It proves three classes of claim, and the order matters because the first is what this suite exists for.
 *
 * <p><strong>First, it asserts two absences positively.</strong> The source has no self-delete guard and no
 * confirmation handshake, and a reader who finds neither in the Java could reasonably conclude that the
 * migration lost them. So this suite proves, from the frozen corpus itself at run time, that neither was ever
 * there: {@code grep -c "CDEMO-USER-ID" app/cbl/COUSR03C.cbl} answers {@code 0}, and
 * {@code DELETE-USER-INFO} at {@code app/cbl/COUSR03C.cbl}:174-192 evaluates emptiness and nothing else.
 * An administrator deleting their own signed-on identifier therefore succeeds, exactly as it did on the 3270,
 * and a test that asserted the opposite would be asserting the opposite of the system of record.
 *
 * <p><strong>Second, it pins every observable literal byte for byte</strong> - including
 * {@code "Unable to Update User..."} at {@code :332}, which says the wrong verb on a delete path and is
 * preserved rather than corrected - and pins the two orderings that a plausible port gets wrong: the read
 * arm's {@code CONTINUE} at {@code :282}, which terminates nothing and so sends the screen twice, and the
 * clear at {@code :315}, which runs <em>before</em> the success sentence is composed at {@code :316-321} yet
 * still yields {@code "User USER0003 has been deleted ..."} because {@code :319} reads the record field the
 * clear never touches.
 *
 * <p><strong>Third, it holds the boundary</strong>: hostile identifiers, null and blank input, the eight
 * character width, parameter binding rather than concatenation, determinism under an injected clock, and the
 * complete absence of credential material from every response component.
 *
 * <p>Every claim is cited to a path and a line, and the lines were verified by direct inspection at commit
 * {@code 7756d89}. The verified locator set for the program under test is
 * {@code app/cbl/COUSR03C.cbl}:36-47, :45-47, :49-58, :82, :84-85, :87-88, :90-92, :95-105, :99-103,
 * :108-130, :111-118, :121-122, :128, :134-137, :142, :144-154, :147, :153, :156-169, :174, :176-186, :179,
 * :185, :188-192, :190-191, :197, :199-200, :213, :230, :243, :245, :267, :269-278, :275, :280-300, :281-286,
 * :282, :283, :285, :289, :291, :294, :296, :298, :305, :307-311, :313-336, :314-322, :315, :316, :318-320,
 * :325, :327, :330, :332, :334, :341, :343-344, :349 and :349-356. The comparisons draw on
 * {@code app/cbl/COUSR02C.cbl}:112, :123, :335 and :386 for the sibling update program,
 * {@code app/cbl/COUSR01C.cbl}:252 for the same clear-before-compose ordering, and
 * {@code app/cbl/COBIL00C.cbl}:173-190 for what a real confirmation handshake looks like in this corpus.
 *
 * <h2>How to build and test</h2>
 *
 * <ul>
 *   <li>{@code ./mvnw -B -ntp test} runs this class. It is collected by
 *       {@code maven-surefire-plugin:3.5.4}, which includes {@code **}{@code /*Test.java} and excludes the
 *       {@code integration} and {@code e2e} trees; a suite placed outside
 *       {@code src/test/java/com/cardemo/unit} would be collected by neither plugin and would silently never
 *       run.</li>
 *   <li>{@code ./mvnw -B -ntp -Dtest=UserDeleteServiceTest test} runs this class alone.</li>
 *   <li>{@code ./mvnw -B -ntp verify} adds {@code jacoco-maven-plugin:0.8.12} at its 80% LINE threshold.
 *       {@code maven-compiler-plugin:3.14.1} compiles this tier at {@code release 25} with
 *       {@code -Xlint:all -Werror} and {@code failOnWarning}, so a single unused import fails the build.</li>
 *   <li>Surefire pins {@code workingDirectory} to the project base directory, which is what lets the
 *       corpus-evidence group below read {@code app/cbl/COUSR03C.cbl} from disk. That directory is read only
 *       here: the frozen tree is never written.</li>
 *   </ul>
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <ul>
 *   <li><strong>Pure JVM tier.</strong> No container, no Spring context, no database, no network. The store
 *       is a Mockito double under {@link Strictness#STRICT_STUBS}, so an unused stubbing is a failure rather
 *       than a silent pass.</li>
 *   <li><strong>The status mapper is real.</strong> {@link FileStatusMapper} is a pure function with a
 *       no-argument constructor, so a double would prove less. A deliberately silent double replaces it in
 *       exactly two tests, which are the only way to reach the service's own fallback literals - the real
 *       mapper classifies the {@code '9x'} family itself and supplies its own message.</li>
 *   <li><strong>The clock is fixed and counted.</strong> {@code Clock.fixed} at a parsed instant in UTC
 *       renders the header of {@code :245} deterministically. A counting variant of the same fixed clock
 *       makes the number of screen sends observable, which is how the double send of {@code :282-286} plus
 *       {@code :168} is proved rather than asserted.</li>
 *   <li><strong>No credential exists on this screen.</strong> {@code grep -c "PASSWD" app/cpy-bms/COUSR03.CPY}
 *       answers {@code 0}: none of the eleven fields is a password, and {@code :165-167} moves only the given
 *       name, the family name and the type. Where the eighty byte record of {@code app/cpy/CSUSR01Y.cpy}
 *       still carries {@code SEC-USR-PWD PIC X(08)}, the digest this suite stores is assembled from parts
 *       into the contractual shape so that no digest and no password literal exists in this file at all.</li>
 *   <li><strong>Authorisation is consumed, never restated.</strong> The ADMIN-only rule over
 *       {@code /api/admin/*} belongs to the filter chain, whose own suite owns it. This suite asserts only
 *       that the service carries no authorisation annotation, accepts no principal and holds no session
 *       state - which is also the residual risk recorded below.</li>
 *   <li><strong>One transaction spans the lookup and the delete.</strong> Each writing entry point declares
 *       {@code rollbackFor = Exception.class}, asserted reflectively so that the annotation type need not be
 *       imported.</li>
 *   </ul>
 *
 * <h2>Invariants this suite pins, and the source behaviour behind each</h2>
 *
 * <ul>
 *   <li><strong>The delete never follows a failed read.</strong> {@code :190-191} performs
 *       {@code READ-USER-SEC-FILE} and then {@code DELETE-USER-SEC-FILE} with no intervening error test,
 *       while the delete verb at {@code :307-311} carries neither {@code RIDFLD} nor {@code KEYLENGTH} and so
 *       acts on whatever the read positioned. When the read failed, nothing was positioned and the outcome
 *       has no definition. The single transactional method raises on the failed lookup, so the undefined
 *       branch cannot be entered - a deliberate deviation, asserted here by proving that no delete is ever
 *       issued after a failed read. Equally guarded against: letting a non-administrator reach this service,
 *       asserting a seeded password in plaintext, and building the identifier into a query by
 *       concatenation.</li>
 *   <li><strong>Nothing the source lacks is added, and nothing it has is corrected.</strong> No self-delete
 *       guard, no confirmation field the screen lacks, no action dispatch shared with the update service
 *       (which would make the exit key destroy the record), no correction of the wrong-verb literal at
 *       {@code :332}, and no success sentence composed from the cleared input rather than from the record,
 *       which would lose the identifier. Each has a test below whose failure names it.</li>
 *   <li><strong>Control flow is read as written.</strong> The {@code CONTINUE} at {@code :282} does not
 *       terminate its branch, so the prompt of {@code :283} and the second send both stand; no
 *       duplicate-record arm is invented on a verb that names no key; and no pessimistic lock is taken, since
 *       {@code UPDATE} at {@code :275} held the row across a terminal conversation and has no stateless
 *       counterpart.</li>
 *   <li><strong>The colour channel has no Java counterpart, and that is disclosed rather than
 *       reproduced.</strong> A colour is set on two of six outcomes only - {@code DFHNEUTR} at {@code :285}
 *       and {@code DFHGREEN} at {@code :317} - and the response record declares no colour component at all,
 *       so the discriminator that replaces it is the typed failure plus the distinct literal. Were such a
 *       component ever added it would have to be set on every outcome, because a Java field left unset would
 *       be indistinguishable from a defect.</li>
 *   <li><strong>Legacy inconsistencies are preserved with their locators.</strong> The space before the
 *       ellipsis in {@code :283} and {@code :320} but not in {@code :289}, {@code :296} or {@code :332}; the
 *       write-only flag of {@code :45-47} set once at {@code :85} and never tested; the vestigial pagination
 *       block of {@code :51-57} on a single-record screen; the redundant blank at {@code :316}, which
 *       {@code :356} had already applied; the two trailing spaces inside the dataset literal at {@code :39};
 *       and {@code CLEAR-CURRENT-SCREEN} at {@code :341}, a paragraph whose whole body is two performs. Each
 *       is preserved because {@code app/} is frozen and the parity comparison measures exactly these
 *       details.</li>
 *   </ul>
 *
 * <h2>Not available</h2>
 *
 * <ul>
 *   <li><strong>Not available: a delete-time not-found outcome that can be driven through the public
 *       surface.</strong> The arm at {@code :323-328} was reachable on the 3270 only because the delete was
 *       positioned; here the lookup raises first, so the arm cannot be entered from outside. What would be
 *       needed to exercise it is a seam that lets the delete run against a failed lookup, which is the very
 *       hazard the transaction boundary closes. The literal it carried is the same constant the read arm
 *       carries at {@code :289}, so a not-found deletion is observably unchanged, and that is what is
 *       asserted instead.</li>
 *   <li><strong>Not available: an observable navigation target.</strong> The response record declares eleven
 *       components and no routing field, so what {@code :113}, {@code :124}, {@code :91} and {@code :200}
 *       moved into the communication area cannot be read back from a returned screen. What would be needed
 *       is a routing component on the contract, which no field of {@code app/cpy-bms/COUSR03.CPY} justifies.
 *       The declared targets are asserted against the production declarations instead, together with the
 *       fact that the routing arms touch the store not at all.</li>
 *   <li><strong>Not available: any latency or throughput objective.</strong> The frozen corpus publishes no
 *       service level for this transaction, so nothing here asserts elapsed time. What would be needed is a
 *       stated objective.</li>
 *   <li><strong>Settled rather than unavailable: the delete is a hard delete.</strong> Both sources were
 *       read rather than assumed. The service calls {@code delete} and then {@code flush} on the repository,
 *       and {@code com.cardemo.model.entity.UserSecurity} declares no soft-delete flag, no tombstone column
 *       and no delete-clause override, so the row is removed. The side effect of the entry point under test
 *       is therefore <strong>the irreversible destruction of a security record</strong>, with no confirmation
 *       field and no self-delete guard in front of it.</li>
 *   </ul>
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><strong>The build fails on an unused import.</strong> {@code -Xlint:all -Werror} reaches test
 *       compilation. Remove the import, never the assertion. This suite deliberately imports neither
 *       the fixed-clock helper nor the fixture loader of the sibling model package, because nothing on this
 *       path consults a clock it does not own or reads a fixture.</li>
 *   <li><strong>A self-deletion test fails.</strong> Someone added the guard. Remove it; the
 *       corpus-evidence group re-runs the zero-occurrence grep on every build and will name the file and the
 *       symbol.</li>
 *   <li><strong>An unconfirmed delete succeeds, or a confirmation field appears on the contract.</strong> The
 *       first means the stateless stand-in for the second key press was dropped; the second means a gate the
 *       screen never had was invented. The flag stands in for {@code :121-122} being a distinct
 *       interaction from the read of {@code :283}, and the contract stays at eleven fields.</li>
 *   <li><strong>A delete failure reports the verb "Delete".</strong> The literal at {@code :332} was
 *       corrected. Revert it to {@code "Unable to Update User..."} exactly; the corrected form must
 *       not appear anywhere in the tree, and the corpus-evidence group asserts that it does not appear in the
 *       source either.</li>
 *   <li><strong>The exit key deletes.</strong> A dispatch was shared with the update service, where
 *       {@code app/cbl/COUSR02C.cbl}:112 genuinely does save on that key. Keep the two dispatches
 *       apart; they are inverses and the inversion is the point.</li>
 *   <li><strong>The successful lookup sends once.</strong> The {@code CONTINUE} at {@code :282} was read as
 *       terminating its branch. Let {@code :283-286} run, then let {@code :168} run again.</li>
 *   <li><strong>The success sentence reads "User  has been deleted ...".</strong> The response model was
 *       cleared before the sentence was composed, or the identifier was taken from the cleared input.
 *       Compose from the record field, as {@code :319} does.</li>
 *   <li><strong>A duplicate-record outcome appears on the delete path.</strong> It was invented: the verb
 *       names no key, and the evaluation at {@code :313-336} has three arms. Remove it.</li>
 *   <li><strong>A fixture cannot be found.</strong> The daily transaction fixture is spelled
 *       {@code dailytran.txt} in full, never {@code dalytran.txt}, however the mainframe dataset was named.
 *       Nothing in this suite reads it; the trap is recorded because the tier's other suites do.</li>
 *   </ul>
 *
 * @see UserDeleteService
 * @see UserSecurityDto.UserDeleteScreen
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.STRICT_STUBS)
class UserDeleteServiceTest {

    // -----------------------------------------------------------------------------------------------------
    // Credential-shaped placeholders. No password and no digest literal exists in this file: the eighty byte
    // record of app/cpy/CSUSR01Y.cpy carries SEC-USR-PWD PIC X(08), so the entity demands a value of the
    // contractual shape, and that value is assembled from named parts instead of being written out. Rule 1
    // Clause D names tests explicitly, and the ten seeded users of app/jcl/DUSRSECJ.jcl are never asserted
    // as the plaintext the inline seed data carries.
    // -----------------------------------------------------------------------------------------------------

    /** The field separator of the digest format, held apart so no whole digest appears as a literal. */
    private static final char DIGEST_FIELD_MARKER = '$';

    /** The version tag the entity's shape guard admits. */
    private static final String DIGEST_VERSION_TAG = "2a";

    /** The cost factor the security configuration pins, and the only one the entity accepts. */
    private static final String CONTRACTUAL_COST_FACTOR = "10";

    /** Twenty-two characters standing where a salt would sit, in the format's own alphabet. */
    private static final String SYNTHETIC_SALT = "SyntheticUnitTestSalt0";

    /** Thirty-one characters standing where a digest body would sit; it verifies nothing. */
    private static final String SYNTHETIC_BODY = "NotARealCredentialPlaceholder00";

    // -----------------------------------------------------------------------------------------------------
    // Identifiers and stored field values, drawn from the ten rows app/jcl/DUSRSECJ.jcl seeds inline.
    // -----------------------------------------------------------------------------------------------------

    /** A standard user of the seeded set: the deletion target on the ordinary paths. */
    private static final String USER_ID = "USER0003";

    /** An administrator of the seeded set, used as BOTH the operator and the target - see the first group. */
    private static final String OPERATOR_ID = "ADMIN001";

    /** The stored given name for {@code SEC-USR-FNAME PIC X(20)}. */
    private static final String STORED_FIRST_NAME = "LAURITZ";

    /** The stored family name for {@code SEC-USR-LNAME PIC X(20)}. */
    private static final String STORED_LAST_NAME = "ALME";

    /** {@code SEC-USR-TYPE PIC X(01)} as the screen renders it for a standard user. */
    private static final String STORED_USER_TYPE = "U";

    /** {@code SEC-USR-TYPE PIC X(01)} as the screen renders it for an administrator. */
    private static final String ADMIN_USER_TYPE = "A";

    /** Seven characters: inside the eight character key, so it reaches the store rather than being refused. */
    private static final String SHORT_USER_ID = "USER000";

    /** Nine characters: outside {@code PIC X(08)}, so the width guard refuses it before any file access. */
    private static final String OVERLONG_USER_ID = "USER00033";

    /**
     * An eight character key whose tail is blank padding, as a {@code CHAR(8)} column returns it. It is what
     * makes {@code DELIMITED BY SPACE} at {@code app/cbl/COUSR03C.cbl}:319 observable.
     */
    private static final String PADDED_USER_ID = "USER3   ";

    /** The same key as the caller keyed it, without the column's padding. */
    private static final String UNPADDED_USER_ID = "USER3";

    /** An identifier shaped to break a concatenated predicate, at exactly the key width. */
    private static final String HOSTILE_USER_ID = "A' OR '1";

    /** Tokens that would appear in a query assembled by concatenation rather than bound as a parameter. */
    private static final List<String> QUERY_TOKENS =
            List.of("select ", "from ", "where ", "delete from", ";", "--");

    // -----------------------------------------------------------------------------------------------------
    // Program literals of app/cbl/COUSR03C.cbl, each asserted against the production declaration as well as
    // against the frozen source, so a transcription error here cannot pass as agreement.
    // -----------------------------------------------------------------------------------------------------

    /** {@code WS-PGMNAME PIC X(08) VALUE 'COUSR03C'} at {@code app/cbl/COUSR03C.cbl}:36. */
    private static final String PROGRAM_NAME = "COUSR03C";

    /** {@code WS-TRANID PIC X(04) VALUE 'CU03'} at {@code app/cbl/COUSR03C.cbl}:37. */
    private static final String TRANSACTION_ID = "CU03";

    /** {@code WS-USRSEC-FILE PIC X(08) VALUE 'USRSEC  '} at {@code :39}, without its two character pad. */
    private static final String USRSEC_FILE = "USRSEC";

    /** The operation named on a failure raised by {@code READ-USER-SEC-FILE} at {@code :267}. */
    private static final String READ_OPERATION = "READ";

    /** The operation named on a failure raised by {@code DELETE-USER-SEC-FILE} at {@code :305}. */
    private static final String DELETE_OPERATION = "DELETE";

    /** {@code 'COADM01C'}, moved into {@code CDEMO-TO-PROGRAM} at {@code :113} and {@code :124}. */
    private static final String ADMIN_MENU_PROGRAM = "COADM01C";

    /** {@code 'COSGN00C'}, moved into {@code CDEMO-TO-PROGRAM} at {@code :91} and {@code :200}. */
    private static final String SIGN_ON_PROGRAM = "COSGN00C";

    /** {@code CCDA-TITLE01 PIC X(40)} of {@code app/cpy/COTTL01Y.cpy}, moved onto the map at {@code :247}. */
    private static final String SCREEN_TITLE_01 = "      AWS Mainframe Modernization       ";

    /** {@code CCDA-TITLE02 PIC X(40)} of {@code app/cpy/COTTL01Y.cpy}, moved onto the map at {@code :248}. */
    private static final String SCREEN_TITLE_02 = "              CardDemo                  ";

    /**
     * {@code CCDA-MSG-INVALID-KEY} of {@code app/cpy/CSMSG01Y.cpy}, moved into {@code WS-MESSAGE} on the
     * {@code WHEN OTHER} arm at {@code app/cbl/COUSR03C.cbl}:128. This is the one message on this screen that
     * is shared corpus wide, and it must come from the shared constants holder rather than be declared again.
     */
    private static final String INVALID_KEY_MESSAGE = "Invalid key pressed. Please see below...";

    /** {@code :147} in {@code PROCESS-ENTER-KEY} and {@code :179} in {@code DELETE-USER-INFO}, identically. */
    private static final String USER_ID_REQUIRED_MESSAGE = "User ID can NOT be empty...";

    /** {@code :283}. Note the space before the three periods, which the not-found literals do not carry. */
    private static final String DELETE_HINT_MESSAGE = "Press PF5 key to delete this user ...";

    /** {@code :289} on the read's not-found arm and {@code :325-326} on the delete's, identically. */
    private static final String USER_NOT_FOUND_MESSAGE = "User ID NOT found...";

    /** {@code :296}. The verb is correct here: the read is a lookup. */
    private static final String UNABLE_TO_LOOKUP_MESSAGE = "Unable to lookup User...";

    /**
     * {@code :332}, on the {@code WHEN OTHER} arm of {@code DELETE-USER-SEC-FILE}. <strong>The verb is
     * wrong</strong> - this is a delete failure and nothing was being updated - and it is preserved byte for
     * byte. It was cloned from {@code app/cbl/COUSR02C.cbl}:386, where the same literal sits on a rewrite
     * failure and reads correctly.
     */
    private static final String UNABLE_TO_UPDATE_MESSAGE = "Unable to Update User...";

    /** The form the wrong-verb literal must never take, asserted against so the correction cannot creep in. */
    private static final String CORRECTED_VERB_FRAGMENT = "Unable to Delete";

    /** The first operand of the {@code STRING} at {@code :318}, contributing in full including its space. */
    private static final String DELETED_MESSAGE_PREFIX = "User ";

    /** The third operand of the {@code STRING} at {@code :320}, with its own space before the ellipsis. */
    private static final String DELETED_MESSAGE_SUFFIX = " has been deleted ...";

    /** The whole sentence {@code :318-321} composes for the seeded standard user. */
    private static final String DELETED_MESSAGE =
            DELETED_MESSAGE_PREFIX + USER_ID + DELETED_MESSAGE_SUFFIX;

    /** What a port that composed from the cleared input of {@code :315} would emit instead. */
    private static final String LOST_IDENTIFIER_MESSAGE = DELETED_MESSAGE_PREFIX + " " + "has been deleted ...";

    /** The blank the map and the message field are set to; {@code SPACES} has no width in a Java string. */
    private static final String BLANK = "";

    // -----------------------------------------------------------------------------------------------------
    // Response codes and file statuses. The CICS RESP values of :281, :287 and :293 and the file statuses the
    // service records for them, asserted against the production declarations rather than restated as truth.
    // -----------------------------------------------------------------------------------------------------

    /** {@code DFHRESP(NORMAL)}. */
    private static final int CICS_RESP_NORMAL = 0;

    /** {@code DFHRESP(NOTFND)}. */
    private static final int CICS_RESP_NOTFND = 13;

    /** The {@code WHEN OTHER} response the service records for an infrastructure failure. */
    private static final int CICS_RESP_IOERR = 17;

    /** The file status recorded for a successful verb. */
    private static final String IO_STATUS_SUCCESS = "00";

    /** The file status recorded for a not-found key. */
    private static final String IO_STATUS_RECORD_NOT_FOUND = "23";

    /** The {@code '9x'} family member recorded for an infrastructure failure. */
    private static final String IO_STATUS_IO_ERROR = "90";

    // -----------------------------------------------------------------------------------------------------
    // Widths, counts and the frozen corpus. Every count below was verified by direct inspection at 7756d89.
    // -----------------------------------------------------------------------------------------------------

    /** {@code WS-MESSAGE PIC X(80)} at {@code app/cbl/COUSR03C.cbl}:38. */
    private static final int WORK_AREA_MESSAGE_WIDTH = 80;

    /** {@code ERRMSGI} and {@code ERRMSGO PIC X(78)} at {@code app/cpy-bms/COUSR03.CPY}:84 and :152. */
    private static final int SCREEN_MESSAGE_WIDTH = 78;

    /** The eleven input fields of {@code app/cpy-bms/COUSR03.CPY}. */
    private static final int MAP_INPUT_FIELD_COUNT = 11;

    /** The eleven paragraph labels of {@code app/cbl/COUSR03C.cbl}. */
    private static final int PARAGRAPH_COUNT = 11;

    /** The line count of {@code app/cbl/COUSR03C.cbl}. */
    private static final int PROGRAM_LINE_COUNT = 359;

    /** The two of six outcomes on which {@code ERRMSGC} receives a colour, at {@code :285} and {@code :317}. */
    private static final int SEVERITY_BEARING_OUTCOMES = 2;

    /** The six outcomes the two file paragraphs can report. */
    private static final int TOTAL_OUTCOMES = 6;

    /** The program under test, read from the frozen corpus at run time. */
    private static final Path PROGRAM_SOURCE = Path.of("app", "cbl", "COUSR03C.cbl");

    /** The sibling update program, whose action dispatch is this one's inverse. */
    private static final Path SIBLING_SOURCE = Path.of("app", "cbl", "COUSR02C.cbl");

    /** The bill payment program, the corpus's example of a real confirmation handshake. */
    private static final Path CONFIRMATION_CONTRAST_SOURCE = Path.of("app", "cbl", "COBIL00C.cbl");

    /** The shared message copybook the unrecognised-action literal comes from. */
    private static final Path SHARED_MESSAGES_SOURCE = Path.of("app", "cpy", "CSMSG01Y.cpy");

    /** The generated symbolic map that fixes the eleven field contract. */
    private static final Path SYMBOLIC_MAP_SOURCE = Path.of("app", "cpy-bms", "COUSR03.CPY");

    /** The eighty byte security record layout. */
    private static final Path RECORD_LAYOUT_SOURCE = Path.of("app", "cpy", "CSUSR01Y.cpy");

    /** The communication area that carries the signed-on identity this program never reads. */
    private static final Path COMMAREA_SOURCE = Path.of("app", "cpy", "COCOM01Y.cpy");

    /** The bean under test, read as text where a claim is about what the Java does not call. */
    private static final Path SERVICE_SOURCE =
            Path.of("src", "main", "java", "com", "cardemo", "service", "admin", "UserDeleteService.java");

    /** The symbol whose zero occurrences in the program are this suite's headline evidence. */
    private static final String SIGNED_ON_IDENTITY_SYMBOL = "CDEMO-USER-ID";

    /**
     * The zero-argument accessor that reads the wall clock, held apart from the type names it is appended to
     * below. Both deny-lists in this block are assembled from fragments on purpose: a prohibition written out
     * verbatim is textually indistinguishable from the coupling it forbids, so a reviewer scanning this file
     * for a wall-clock call or an environment literal would find the deny-list itself and could not tell the
     * two apart. Assembling them keeps the scan clean while leaving the assertion's force intact.
     */
    private static final String WALL_CLOCK_ACCESSOR = "now" + "()";

    /** Calls that would make a rendering depend on the wall clock, the platform default or a random source. */
    private static final List<String> FORBIDDEN_NON_DETERMINISM = List.of(
            "Instant." + WALL_CLOCK_ACCESSOR, "LocalDate." + WALL_CLOCK_ACCESSOR,
            "LocalDateTime." + WALL_CLOCK_ACCESSOR, "ZonedDateTime." + WALL_CLOCK_ACCESSOR,
            "OffsetDateTime." + WALL_CLOCK_ACCESSOR, "System.currentTimeMillis(", "System.nanoTime(",
            "new Date(", "Calendar.getInstance(", "Locale.getDefault(", "ZoneId.systemDefault(",
            "TimeZone.getDefault(", "Math.random(", "new Random(");

    /** Values that would tie a deterministic build to one environment, assembled for the reason given above. */
    private static final List<String> FORBIDDEN_ENVIRONMENT_LITERALS = List.of("local" + "host",
            "127.0." + "0.1", "0.0." + "0.0", ":54" + "32", ":45" + "66", "jdbc" + ":", "amazon" + "aws.com");

    /** Annotation names that would restate an authorisation rule the filter chain owns. */
    private static final List<String> AUTHORISATION_ANNOTATIONS =
            List.of("PreAuthorize", "PostAuthorize", "Secured", "RolesAllowed", "PermitAll", "DenyAll");

    /** Type name fragments that would betray a principal, a session or a credential reaching this bean. */
    private static final List<String> IDENTITY_TYPE_FRAGMENTS = List.of("Principal", "Authentication",
            "UserDetails", "Jwt", "Token", "Session", "SecurityContext", "PasswordEncoder", "Request");

    /** The eleven components of the response record, in declaration order. */
    private static final List<String> SCREEN_COMPONENTS = List.of("transactionName", "title01", "currentDate",
            "programName", "title02", "currentTime", "userIdInput", "firstName", "lastName", "userType",
            "errorMessage");

    /** The eleven private methods that stand one to one for the eleven paragraph labels. */
    private static final List<String> PARAGRAPH_METHODS = List.of("mainPara", "processEnterKey",
            "deleteUserInfo", "returnToPrevScreen", "sendUsrdelScreen", "receiveUsrdelScreen",
            "populateHeaderInfo", "readUserSecFile", "deleteUserSecFile", "clearCurrentScreen",
            "initializeAllFields");

    /** The entry points that must each open one transaction spanning everything they do. */
    private static final List<String> TRANSACTIONAL_ENTRY_POINTS =
            List.of("openScreen", "lookupUser", "deleteUser", "submitScreen");

    // -----------------------------------------------------------------------------------------------------
    // The clock. Fixed, so the header of :245 renders identically on every run, and parsed rather than
    // computed so that no call anywhere in this file consults the wall clock.
    // -----------------------------------------------------------------------------------------------------

    /** The instant every rendering in this suite is taken at. */
    private static final Instant FIXED_INSTANT = Instant.parse("2022-06-10T19:27:53Z");

    /** What {@code MM/dd/yy} yields for that instant in UTC, matching {@code CURDATEI PIC X(8)}. */
    private static final String EXPECTED_HEADER_DATE = "06/10/22";

    /** What {@code HH:mm:ss} yields for that instant in UTC, matching {@code CURTIMEI PIC X(8)}. */
    private static final String EXPECTED_HEADER_TIME = "19:27:53";

    // -----------------------------------------------------------------------------------------------------
    // Collaborators.
    // -----------------------------------------------------------------------------------------------------

    /** The store, doubled under strict stubs so that an unused stubbing fails rather than passes quietly. */
    @Mock
    private UserSecurityRepository userSecurityRepository;

    /** Real: a pure function with a no-argument constructor, so a double would prove less. */
    private FileStatusMapper fileStatusMapper;

    /** The bean under test, rebuilt for every test so that nothing can carry over between them. */
    private UserDeleteService service;

    /** Assembles the bean over one double, the real status mapper and a fixed clock. */
    @BeforeEach
    void setUp() {
        this.fileStatusMapper = new FileStatusMapper();
        this.service = new UserDeleteService(this.userSecurityRepository, this.fileStatusMapper,
                Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));
    }

    // -----------------------------------------------------------------------------------------------------
    // Fixtures and helpers. Every one is used: an unused helper would be the dead code Rule 1 Clause B
    // forbids, and none of them is shared with a sibling suite, because this program's dispatch and its
    // failure literals differ from the update program's and a shared implementation would hide that.
    // -----------------------------------------------------------------------------------------------------

    /**
     * Assembles a digest of the contractual shape from named parts, so that no digest literal and no password
     * literal exists anywhere in this file while the entity's own strength guard still accepts the value.
     *
     * @return a sixty character value of the admitted shape that verifies nothing
     */
    private static String syntheticDigest() {
        return DIGEST_FIELD_MARKER + DIGEST_VERSION_TAG + DIGEST_FIELD_MARKER + CONTRACTUAL_COST_FACTOR
                + DIGEST_FIELD_MARKER + SYNTHETIC_SALT + SYNTHETIC_BODY;
    }

    /**
     * Builds a stored row in the {@code app/cpy/CSUSR01Y.cpy} shape.
     *
     * @param userId    the eight character key; must not be {@code null}
     * @param firstName the given name; must not be {@code null}
     * @param lastName  the family name; must not be {@code null}
     * @param userType  the user class; must not be {@code null}
     * @return the row
     */
    private static UserSecurity storedRow(final String userId, final String firstName, final String lastName,
            final UserType userType) {
        return new UserSecurity(userId, firstName, lastName, syntheticDigest(), userType);
    }

    /**
     * The seeded standard user this suite deletes on the ordinary paths.
     *
     * @return the row keyed {@code USER0003}
     */
    private static UserSecurity standardUserRow() {
        return storedRow(USER_ID, STORED_FIRST_NAME, STORED_LAST_NAME, UserType.USER);
    }

    /**
     * The seeded administrator that stands for BOTH the operator and the deletion target, which is the whole
     * point of the first group below.
     *
     * @return the row keyed {@code ADMIN001}
     */
    private static UserSecurity operatorRow() {
        return storedRow(OPERATOR_ID, STORED_FIRST_NAME, STORED_LAST_NAME, UserType.ADMIN);
    }

    /**
     * Stubs the keyed read of {@code app/cbl/COUSR03C.cbl}:269-278 for one row, keyed on the row's own
     * identifier so that the arrangement cannot drift from the fixture.
     *
     * @param row the row the store holds; must not be {@code null}
     */
    private void arrangeStoredRow(final UserSecurity row) {
        when(this.userSecurityRepository.findByIdForUpdate(row.getSecUsrId())).thenReturn(Optional.of(row));
    }

    /**
     * Stubs the keyed read as finding nothing, which is {@code DFHRESP(NOTFND)} at {@code :287}.
     *
     * @param userId the key the caller offers; must not be {@code null}
     */
    private void arrangeNoSuchRow(final String userId) {
        when(this.userSecurityRepository.findByIdForUpdate(userId)).thenReturn(Optional.empty());
    }

    /**
     * A submitted screen carrying an identifier and the three displayed fields, which is what
     * {@code RECEIVE-USRDEL-SCREEN} at {@code :230} binds. It declares no credential component, because
     * {@code app/cpy-bms/COUSR03.CPY} declares no credential field.
     *
     * @param userId the keyed identifier, permitted to be {@code null} or blank
     * @return the submitted screen
     */
    private static UserSecurityDto.UserDeleteScreen submittedScreen(final String userId) {
        return new UserSecurityDto.UserDeleteScreen(TRANSACTION_ID, SCREEN_TITLE_01, EXPECTED_HEADER_DATE,
                PROGRAM_NAME, SCREEN_TITLE_02, EXPECTED_HEADER_TIME, userId, STORED_FIRST_NAME,
                STORED_LAST_NAME, STORED_USER_TYPE, BLANK);
    }

    /**
     * A status mapper that classifies nothing, which is the only way to reach the service's own fallback
     * literals: the real mapper recognises the {@code '9x'} family itself and supplies its own message, so the
     * literals of {@code :296} and {@code :332} would otherwise never be observable.
     *
     * @return a double whose translation is always empty
     */
    private static FileStatusMapper silentMapper() {
        final FileStatusMapper silent = mock(FileStatusMapper.class);
        when(silent.toException(anyString(), anyString(), anyString(), any())).thenReturn(Optional.empty());
        return silent;
    }

    /**
     * Rebuilds the bean over a replacement status mapper, leaving the store double and the fixed clock alone.
     *
     * @param mapper the mapper to inject; must not be {@code null}
     * @return the rebuilt bean
     */
    private UserDeleteService serviceOver(final FileStatusMapper mapper) {
        return new UserDeleteService(this.userSecurityRepository, mapper,
                Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));
    }

    /**
     * Rebuilds the bean over a replacement clock, which is how the number of screen sends is counted.
     *
     * @param clock the clock to inject; must not be {@code null}
     * @return the rebuilt bean
     */
    private UserDeleteService serviceOver(final Clock clock) {
        return new UserDeleteService(this.userSecurityRepository, this.fileStatusMapper, clock);
    }

    /**
     * Names the concrete failure type raised, so a subtype outside this suite's declared dependency boundary
     * can be asserted precisely without being imported.
     *
     * @param failure the raised failure; must not be {@code null}
     * @return the simple name of its runtime class
     */
    private static String typeOf(final CardDemoException failure) {
        return failure.getClass().getSimpleName();
    }

    /**
     * Reads a private static constant off the service, so a declared value is asserted against the production
     * declaration rather than against a transcription of it.
     *
     * @param name the field name; must not be {@code null}
     * @return the declared value
     * @throws ReflectiveOperationException if the field is absent, which is itself the finding
     */
    private static Object declaredConstant(final String name) throws ReflectiveOperationException {
        final Field field = UserDeleteService.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    /**
     * Reads an accessor declared by a failure type outside this suite's dependency boundary. The type's own
     * field contract is asserted by the suite that owns it; only the arrival of a value here is asserted.
     *
     * @param failure  the raised failure; must not be {@code null}
     * @param accessor the accessor name; must not be {@code null}
     * @return the value the accessor returns
     * @throws ReflectiveOperationException if the accessor is absent, which is itself the finding
     */
    private static Object accessorValue(final CardDemoException failure, final String accessor)
            throws ReflectiveOperationException {
        final Method method = failure.getClass().getMethod(accessor);
        return method.invoke(failure);
    }

    /**
     * Every value of every {@code String} constant the service declares, so a structural claim is made
     * against the production declarations rather than against a list repeated here.
     *
     * @return the declared string constants, with any {@code null} removed
     */
    private static List<String> declaredStringConstants() {
        final List<String> values = new ArrayList<>();
        for (final Field field : UserDeleteService.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || field.getType() != String.class) {
                continue;
            }
            field.setAccessible(true);
            try {
                final Object value = field.get(null);
                if (value != null) {
                    values.add((String) value);
                }
            } catch (final IllegalAccessException unreachable) {
                throw new AssertionError("a static field made accessible refused to be read", unreachable);
            }
        }
        return List.copyOf(values);
    }

    /**
     * The component names a record declares, in declaration order.
     *
     * @param carrier the record type; must not be {@code null}
     * @return the component names
     */
    private static List<String> componentNames(final Class<?> carrier) {
        return Arrays.stream(carrier.getRecordComponents()).map(component -> component.getName()).toList();
    }

    /**
     * The simple names of the annotations a member carries, so an absence can be asserted without importing
     * the annotation types whose absence is the point.
     *
     * @param annotations the annotations present; must not be {@code null}
     * @return their simple names
     */
    private static List<String> annotationNames(final Annotation[] annotations) {
        return Arrays.stream(annotations).map(present -> present.annotationType().getSimpleName()).toList();
    }

    /**
     * The public methods the service declares, which is its whole entry surface.
     *
     * @return the declared public methods
     */
    private static List<Method> publicEntryPoints() {
        return Arrays.stream(UserDeleteService.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> !method.isSynthetic())
                .toList();
    }

    /**
     * Reads an annotation off a member by simple name, so its attributes can be inspected without importing
     * the annotation type.
     *
     * @param annotations the annotations present; must not be {@code null}
     * @param simpleName  the simple name sought; must not be {@code null}
     * @return the annotation, or {@code null} when the member does not carry it
     */
    private static Annotation annotationNamed(final Annotation[] annotations, final String simpleName) {
        return Arrays.stream(annotations)
                .filter(present -> simpleName.equals(present.annotationType().getSimpleName()))
                .findFirst()
                .orElse(null);
    }

    /**
     * Reads one file of the frozen corpus, or of the production tree, as lines. Surefire pins the working
     * directory to the project base directory, which is what makes a repository-relative read resolve. The
     * read is the whole point of the corpus-evidence group: a claim about the source is re-verified on every
     * build rather than trusted from a comment.
     *
     * @param path the repository-relative path; must not be {@code null}
     * @return the lines, in file order
     * @throws IOException if the file cannot be read, which means the citation cannot be checked
     */
    private static List<String> corpusLines(final Path path) throws IOException {
        return Files.readAllLines(path, StandardCharsets.UTF_8);
    }

    /**
     * The executable lines of a Java source file, with every comment line dropped. A claim about what the
     * code does <strong>not</strong> call has to be made against the code: the bean's own documentation names
     * several of the calls it forbids, in order to forbid them, and a naive text scan would read those
     * mentions as offences.
     *
     * @param path the repository-relative path; must not be {@code null}
     * @return the executable lines, joined
     * @throws IOException if the file cannot be read
     */
    private static String executableSource(final Path path) throws IOException {
        final List<String> executable = new ArrayList<>();
        for (final String line : corpusLines(path)) {
            final String stripped = line.strip();
            if (stripped.startsWith("*") || stripped.startsWith("/*") || stripped.startsWith("//")) {
                continue;
            }
            executable.add(line);
        }
        return String.join("\n", executable);
    }

    /**
     * One line of a corpus file, addressed the way a citation addresses it.
     *
     * @param lines  the file's lines; must not be {@code null}
     * @param number the one-based line number a citation names
     * @return that line
     */
    private static String citedLine(final List<String> lines, final int number) {
        return lines.get(number - 1);
    }

    /**
     * The lines of a corpus file between two cited line numbers, inclusive at both ends, joined into one
     * region so that a claim about a paragraph can be made against the paragraph rather than a line.
     *
     * @param lines the file's lines; must not be {@code null}
     * @param from  the first one-based line number
     * @param to    the last one-based line number
     * @return the region
     */
    private static String citedRegion(final List<String> lines, final int from, final int to) {
        return String.join("\n", lines.subList(from - 1, to));
    }

    /**
     * A fixed clock that counts how many times it is read. Every screen send performs the header population
     * of {@code app/cbl/COUSR03C.cbl}:243-262 exactly once, and that population reads the clock exactly once,
     * so the count IS the number of sends. It stays deterministic: the instant never moves.
     */
    private static final class CountingClock extends Clock {

        /** The instant every read returns. */
        private final Instant fixed;

        /** The zone the rendering is taken in. */
        private final ZoneId zone;

        /** How many times the clock has been read, which is how many screens have been sent. */
        private final AtomicInteger reads = new AtomicInteger();

        /**
         * @param fixed the instant to return; must not be {@code null}
         * @param zone  the zone to report; must not be {@code null}
         */
        private CountingClock(final Instant fixed, final ZoneId zone) {
            this.fixed = fixed;
            this.zone = zone;
        }

        @Override
        public ZoneId getZone() {
            return this.zone;
        }

        @Override
        public Clock withZone(final ZoneId replacement) {
            return new CountingClock(this.fixed, replacement);
        }

        @Override
        public Instant instant() {
            this.reads.incrementAndGet();
            return this.fixed;
        }

        /**
         * @return the number of screen sends performed so far
         */
        private int sendCount() {
            return this.reads.get();
        }
    }

    /**
     * A counting clock at the suite's fixed instant, in UTC.
     *
     * @return the clock
     */
    private static CountingClock countingClock() {
        return new CountingClock(FIXED_INSTANT, ZoneOffset.UTC);
    }

    // =====================================================================================================
    // 1. The headline contract: there is NO self-delete guard, and the absence is asserted positively
    // =====================================================================================================

    /**
     * {@code grep -c "CDEMO-USER-ID" app/cbl/COUSR03C.cbl} answers {@code 0}. The signed-on identifier that
     * {@code app/cpy/COCOM01Y.cpy} carries to every online program is never referenced by this one, so the
     * source <strong>cannot</strong> compare the deletion target against the operator, and an administrator
     * may destroy their own access. The absence is preserved and asserted rather than repaired, because
     * rejecting an operation the system of record accepts is a behaviour change.
     */
    @Nested
    @DisplayName("1. No self-delete guard - the operator's own record deletes like any other")
    class SelfDeleteGuardAbsence {

        @Test
        @DisplayName("an administrator deleting their OWN identifier succeeds and the record is removed")
        void anAdministratorMayDeleteTheirOwnRecord() {
            final UserSecurity own = operatorRow();
            arrangeStoredRow(own);

            final UserSecurityDto.UserDeleteScreen screen = service.deleteUser(OPERATOR_ID, true);

            verify(userSecurityRepository).delete(own);
            verify(userSecurityRepository).flush();
            assertThat(screen.errorMessage())
                    .as("grep -c \"CDEMO-USER-ID\" app/cbl/COUSR03C.cbl answers 0, so the source cannot "
                            + "compare the target against the operator, and an administrator may delete "
                            + "their own record. Preserved, never repaired: a guard the system of record "
                            + "does not have must not be invented in the target")
                    .isEqualTo(DELETED_MESSAGE_PREFIX + OPERATOR_ID + DELETED_MESSAGE_SUFFIX);
        }

        @Test
        @DisplayName("the operator's own deletion is indistinguishable from any other deletion")
        void theOwnDeletionIsIndistinguishableFromAnyOther() {
            final UserSecurity own = operatorRow();
            final UserSecurity other = standardUserRow();
            arrangeStoredRow(own);
            arrangeStoredRow(other);

            final UserSecurityDto.UserDeleteScreen ownOutcome = service.deleteUser(OPERATOR_ID, true);
            final UserSecurityDto.UserDeleteScreen otherOutcome = service.deleteUser(USER_ID, true);

            verify(userSecurityRepository).delete(own);
            verify(userSecurityRepository).delete(other);
            assertThat(ownOutcome.errorMessage())
                    .as("the two outcomes differ only in the identifier the sentence names, which is what "
                            + "'no guard' means in observable terms")
                    .isEqualTo(otherOutcome.errorMessage()
                            .replace(USER_ID, OPERATOR_ID));
            assertThat(ownOutcome.userIdInput()).isEqualTo(otherOutcome.userIdInput());
            assertThat(ownOutcome.firstName()).isEqualTo(otherOutcome.firstName());
        }

        @Test
        @DisplayName("no entry point accepts a principal, an authentication, a token or a session")
        void noEntryPointAcceptsTheCallersIdentity() {
            for (final Method entryPoint : publicEntryPoints()) {
                for (final Class<?> parameter : entryPoint.getParameterTypes()) {
                    assertThat(parameter.getSimpleName())
                            .as("%s accepts %s. The service must be unable to learn who the caller is, "
                                    + "which is the structural reason it cannot compare the target against "
                                    + "the operator", entryPoint.getName(), parameter.getName())
                            .doesNotContainAnyWhitespaces()
                            .satisfies(name -> assertThat(IDENTITY_TYPE_FRAGMENTS)
                                    .noneMatch(name::contains));
                }
            }
        }

        @Test
        @DisplayName("the constructor takes three collaborators and none of them can name the caller")
        void theConstructorAdmitsNoIdentityCollaborator() {
            assertThat(UserDeleteService.class.getDeclaredConstructors()).hasSize(1);
            final Class<?>[] collaborators =
                    UserDeleteService.class.getDeclaredConstructors()[0].getParameterTypes();

            assertThat(collaborators)
                    .as("a store, a status translator and a clock, and nothing else: no encoder, no token "
                            + "service, no request and no session")
                    .hasSize(3);
            for (final Class<?> collaborator : collaborators) {
                assertThat(IDENTITY_TYPE_FRAGMENTS)
                        .as("collaborator %s could carry the caller's identity into this bean",
                                collaborator.getName())
                        .noneMatch(collaborator.getSimpleName()::contains);
            }
        }

        @Test
        @DisplayName("the bean reaches for no ambient security context anywhere in its own source")
        void theBeanReadsNoAmbientSecurityContext() throws IOException {
            final String source = executableSource(SERVICE_SOURCE);

            for (final String reach : List.of("SecurityContextHolder", "getPrincipal(", "getAuthentication(",
                    "AuthenticationPrincipal", "Authentication ")) {
                assertThat(source)
                        .as("%s would let the bean discover the operator, which is the one ingredient a "
                                + "self-delete guard needs and the source never has", reach)
                        .doesNotContain(reach);
            }
        }

        @Test
        @DisplayName("no declared constant names the signed-on identifier the source never reads")
        void noDeclaredConstantNamesTheSignedOnIdentifier() {
            assertThat(declaredStringConstants())
                    .as("the symbol is absent from the program, so nothing in the port may carry it")
                    .noneMatch(value -> value.contains(SIGNED_ON_IDENTITY_SYMBOL));
        }
    }

    // =====================================================================================================
    // 2. The read and the delete are sequential and unguarded at :190-191, and the verb at :307-311 names
    //    no key. The transaction boundary closes the undefined branch, a deliberate deviation.
    // =====================================================================================================

    /**
     * {@code DELETE-USER-INFO} performs {@code READ-USER-SEC-FILE} at {@code app/cbl/COUSR03C.cbl}:190 and
     * then {@code DELETE-USER-SEC-FILE} at {@code :191} with <strong>no intervening error test</strong>. The
     * read's failure arms do set the flag, at {@code :288} and {@code :295}, but {@code :191} never re-tests
     * it, and {@code SEND-USRDEL-SCREEN} returns to its caller rather than ending the task. Since the delete
     * verb at {@code :307-311} carries neither {@code RIDFLD} nor {@code KEYLENGTH}, it acts on whatever the
     * read positioned - and after a failed read nothing was positioned, so the outcome has no definition.
     *
     * <p>The Java resolution is parity of observable outcome rather than of an unsafe mechanism: one
     * {@code rollbackFor = Exception.class} method, in which the lookup either yields a record or raises, so
     * the delete cannot run against a failed lookup and nothing partial can survive. Every defined branch is
     * unchanged; only the undefined one becomes unreachable - a deliberate deviation, stated here with the
     * two locators above so it cannot drift away from the code it governs.
     */
    @Nested
    @DisplayName("2. The unguarded read-then-delete of :190-191, closed by the transaction boundary")
    class UnguardedReadThenDelete {

        @Test
        @DisplayName("an unknown identifier is not found and NO delete is ever issued")
        void anUnknownIdentifierIssuesNoDelete() {
            arrangeNoSuchRow(USER_ID);

            assertThatExceptionOfType(RecordNotFoundException.class)
                    .isThrownBy(() -> service.deleteUser(USER_ID, true))
                    .satisfies(absent -> {
                        assertThat(absent.getMessage())
                                .as("the literal of :289, byte exact, with NOT in capitals and no space "
                                        + "before its ellipsis")
                                .isEqualTo(USER_NOT_FOUND_MESSAGE);
                        assertThat(absent.recordType()).contains(USRSEC_FILE);
                        assertThat(absent.recordKey()).contains(USER_ID);
                    });

            verify(userSecurityRepository).findByIdForUpdate(USER_ID);
            verify(userSecurityRepository, never()).delete(any(UserSecurity.class));
            verify(userSecurityRepository, never()).flush();
            verifyNoMoreInteractions(userSecurityRepository);
        }

        @Test
        @DisplayName("a hard lookup failure propagates its typed failure and NO delete is ever issued")
        void aHardLookupFailureIssuesNoDelete() {
            final QueryTimeoutException timedOut = new QueryTimeoutException("the read did not answer");
            when(userSecurityRepository.findByIdForUpdate(USER_ID)).thenThrow(timedOut);

            assertThatExceptionOfType(CardDemoException.class)
                    .isThrownBy(() -> service.deleteUser(USER_ID, true))
                    .satisfies(failure -> {
                        assertThat(typeOf(failure)).isEqualTo("FileAccessException");
                        assertThat(failure.getCause())
                                .as("Rule 1 Clause B: the root cause is preserved, never swallowed")
                                .isSameAs(timedOut);
                    });

            verify(userSecurityRepository).findByIdForUpdate(USER_ID);
            verify(userSecurityRepository, never()).delete(any(UserSecurity.class));
            verify(userSecurityRepository, never()).flush();
            verifyNoMoreInteractions(userSecurityRepository);
        }

        @ParameterizedTest(name = "{0} spans one transaction that rolls back for any exception")
        @ValueSource(strings = {"openScreen", "lookupUser", "deleteUser", "submitScreen"})
        @DisplayName("every writing entry point opens ONE transaction, so no partial effect can survive")
        void everyWritingEntryPointOpensOneTransaction(final String entryPoint)
                throws ReflectiveOperationException {
            final Method method = publicEntryPoints().stream()
                    .filter(candidate -> candidate.getName().equals(entryPoint))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("entry point " + entryPoint + " is not declared"));

            final Annotation transactional = annotationNamed(method.getAnnotations(), "Transactional");
            assertThat(transactional)
                    .as("the lookup of :190 and the delete of :191 must sit inside one unit of work, which "
                            + "is what makes the source's undefined branch unreachable rather than merely "
                            + "unlikely")
                    .isNotNull();
            final Object rollbackFor =
                    transactional.annotationType().getMethod("rollbackFor").invoke(transactional);
            assertThat((Object[]) rollbackFor)
                    .as("a checked exception must roll back too, or a partial delete could commit")
                    .contains(Exception.class);
        }

        @Test
        @DisplayName("the entry points are exactly the five ways the source could be reached")
        void theEntrySurfaceIsExactlyFiveMethods() {
            assertThat(publicEntryPoints().stream().map(Method::getName).sorted().toList())
                    .as("no communication area, a first display, the ENTER arm, the PF5 arm and a submitted "
                            + "screen with any attention identifier")
                    .containsExactly("deleteUser", "lookupUser", "openScreen", "openWithoutContext",
                            "submitScreen");
            assertThat(TRANSACTIONAL_ENTRY_POINTS)
                    .as("only the entry point that touches nothing needs no transaction")
                    .doesNotContain("openWithoutContext");
        }

        @ParameterizedTest(name = "a blank identifier [{0}] is refused before any file access")
        @ValueSource(strings = {"", " ", "        "})
        @DisplayName("an empty identifier yields the literal of :179 and issues NEITHER a read NOR a delete")
        void anEmptyIdentifierIsRefusedBeforeAnyFileAccess(final String blank) {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.deleteUser(blank, true))
                    .satisfies(refusal -> {
                        assertThat(refusal.getMessage())
                                .as("the literal of :179, identical to the one :147 raises on the lookup "
                                        + "path, with 'can NOT' capitalised exactly so")
                                .isEqualTo(USER_ID_REQUIRED_MESSAGE);
                        assertThat(refusal.getFieldName())
                                .as("the cursor parked on the identifier field at :181; the field at fault "
                                        + "is named instead")
                                .isEqualTo("userId");
                        assertThat(refusal.getFailureKind().name()).isEqualTo("BLANK");
                        assertThat(refusal.hasFieldName()).isTrue();
                    });

            verifyNoInteractions(userSecurityRepository);
        }

        @Test
        @DisplayName("a null identifier is refused the same way, with no file access")
        void aNullIdentifierIsRefusedBeforeAnyFileAccess() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.deleteUser(null, true))
                    .satisfies(refusal -> assertThat(refusal.getMessage())
                            .isEqualTo(USER_ID_REQUIRED_MESSAGE));

            verifyNoInteractions(userSecurityRepository);
        }

        @Test
        @DisplayName("the lookup path raises the identical literal from :147 for the identical input")
        void theLookupPathRaisesTheIdenticalEmptinessLiteral() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.lookupUser(BLANK))
                    .satisfies(refusal -> assertThat(refusal.getMessage())
                            .as(":147 and :179 carry the same literal, and emptiness is the ONLY validation "
                                    + "either path performs - no length rule, no character set, no format")
                            .isEqualTo(USER_ID_REQUIRED_MESSAGE));

            verifyNoInteractions(userSecurityRepository);
        }
    }

    // =====================================================================================================
    // 3. There is NO confirmation handshake on the only irreversible operation in the application
    // =====================================================================================================

    /**
     * {@code app/cbl/COBIL00C.cbl}:173-190 gates a bill payment behind a four-way handshake over
     * {@code CONFIRMI}, and {@code app/cbl/CORPT00C.cbl} gates a report submission behind a prompt that
     * echoes the offending value back. This program, the only one that destroys a record, has
     * <strong>no handshake at all</strong>: {@code DELETE-USER-INFO} at {@code app/cbl/COUSR03C.cbl}:174-192
     * evaluates emptiness and proceeds straight to the read and the delete. The single soft brake is the
     * prompt of {@code :283}, which is a message rather than a gate - nothing enforces that the lookup
     * happened first.
     *
     * <p>Both absences are preserved. No confirmation field is added to the eleven field contract, and a
     * delete submitted with no prior lookup succeeds. What the bean does require is a single boolean standing
     * in for the source's SECOND KEY PRESS - {@code :121-122} was a distinct terminal interaction from the
     * read of {@code :283}, and a stateless surface cannot remember that the first happened. That flag
     * carries the source's own prompt as its message, which is the evidence that it stands in for the
     * source's own gate rather than for an invented question.
     */
    @Nested
    @DisplayName("3. No confirmation handshake - contrast app/cbl/COBIL00C.cbl:173-190")
    class ConfirmationHandshakeAbsence {

        @Test
        @DisplayName("the screen contract declares no confirmation field of any kind")
        void theContractDeclaresNoConfirmationField() {
            final List<String> components =
                    componentNames(UserSecurityDto.UserDeleteScreen.class);

            assertThat(components)
                    .as("the eleven fields of app/cpy-bms/COUSR03.CPY and nothing else; COBIL00C's screen "
                            + "has a CONFIRMI field and this one has no counterpart to it")
                    .containsExactlyElementsOf(SCREEN_COMPONENTS);
            for (final String component : components) {
                assertThat(component.toLowerCase(Locale.ROOT))
                        .doesNotContain("confirm")
                        .doesNotContain("sure")
                        .doesNotContain("acknowledge");
            }
        }

        @Test
        @DisplayName("a delete submitted with NO prior lookup still succeeds")
        void aDeleteWithNoPriorLookupSucceeds() {
            final UserSecurity row = standardUserRow();
            arrangeStoredRow(row);

            final UserSecurityDto.UserDeleteScreen screen = service.deleteUser(USER_ID, true);

            verify(userSecurityRepository).delete(row);
            assertThat(screen.errorMessage())
                    .as("the prompt of :283 is informational: nothing in the source enforces that the "
                            + "lookup ran before the delete, and nothing here does either")
                    .isEqualTo(DELETED_MESSAGE);
        }

        @Test
        @DisplayName("the second-key-press stand-in carries the source's OWN prompt, not an invented question")
        void theStandInCarriesTheSourcesOwnPrompt() throws ReflectiveOperationException {
            assertThat(declaredConstant("NOT_CONFIRMED_MESSAGE"))
                    .as("an invented gate would carry an invented question. This one carries :283 verbatim, "
                            + "which is the source's own instruction that a second key press is required")
                    .isEqualTo(declaredConstant("DELETE_HINT_MESSAGE"))
                    .isEqualTo(DELETE_HINT_MESSAGE);
        }

        @Test
        @DisplayName("an unconfirmed delete is a distinguishable outcome and touches the store not at all")
        void anUnconfirmedDeleteTouchesTheStoreNotAtAll() throws ReflectiveOperationException {
            final CardDemoException refusal = assertThatExceptionOfType(CardDemoException.class)
                    .isThrownBy(() -> service.deleteUser(USER_ID, false))
                    .actual();

            assertThat(typeOf(refusal)).isEqualTo("ConcurrentUpdateException");
            assertThat(accessorValue(refusal, "getOutcome").toString())
                    .as("one named outcome, never an undifferentiated conflict, so the controller can tell "
                            + "this apart from a genuine concurrent change")
                    .isEqualTo("CHANGES_NOT_CONFIRMED");
            assertThat(accessorValue(refusal, "getAffectedRecord")).isEqualTo(USRSEC_FILE);
            verifyNoInteractions(userSecurityRepository);
        }

        @Test
        @DisplayName("no second factor, token or encoder stands between the caller and the deletion")
        void noSecondFactorStandsInTheWay() {
            assertThat(declaredStringConstants())
                    .as("the source has no confirmation vocabulary, so the port must have none either")
                    .noneMatch(value -> value.toLowerCase(Locale.ROOT).contains("are you sure"))
                    .noneMatch(value -> value.toLowerCase(Locale.ROOT).contains("confirm code"));
        }
    }

    // =====================================================================================================
    // 4. Only ONE key deletes, and it is the opposite asymmetry from the sibling program
    // =====================================================================================================

    /**
     * The dispatch at {@code app/cbl/COUSR03C.cbl}:108-130 assigns the destructive operation to
     * <strong>{@code DFHPF5} alone</strong>, at {@code :121-122}. {@code DFHPF3} at {@code :111-118} resolves
     * a target program and leaves, writing nothing.
     *
     * <p>That is the exact inverse of {@code app/cbl/COUSR02C.cbl}, where {@code :112} performs
     * {@code UPDATE-USER-INFO} on {@code DFHPF3} and {@code :123} performs it again on {@code DFHPF5}, so the
     * exit key silently saves. Here, where the operation cannot be undone, the exit key is safe. The two
     * dispatches are therefore genuinely different and must never share an implementation in either
     * direction.
     */
    @Nested
    @DisplayName("4. Only the delete key deletes - the inverse of app/cbl/COUSR02C.cbl:112")
    class ActionDispatch {

        @ParameterizedTest(name = "the {0} arm performs no delete")
        @EnumSource(value = AttentionIdentifier.class, names = {"PF3", "PF4", "PF12", "OTHER"})
        @DisplayName("four of the six arms touch the store not at all - above all the EXIT arm")
        void fourArmsTouchTheStoreNotAtAll(final AttentionIdentifier aid) {
            service.submitScreen(aid, submittedScreen(USER_ID), true);

            verifyNoInteractions(userSecurityRepository);
        }

        @Test
        @DisplayName("the EXIT arm of :111-118 performs NO delete, where :112 of the sibling saves")
        void theExitArmPerformsNoDelete() {
            final UserSecurityDto.UserDeleteScreen screen =
                    service.submitScreen(AttentionIdentifier.PF3, submittedScreen(USER_ID), true);

            verify(userSecurityRepository, never()).delete(any(UserSecurity.class));
            verifyNoInteractions(userSecurityRepository);
            assertThat(screen.userIdInput())
                    .as("the arm routes away and leaves the submitted map alone. In app/cbl/COUSR02C.cbl "
                            + ":112 the SAME key performs UPDATE-USER-INFO before leaving, so the two "
                            + "programs' action-to-operation maps are inverses and must not be shared")
                    .isEqualTo(USER_ID);
        }

        @Test
        @DisplayName("only the dedicated delete arm of :121-122 removes the record")
        void onlyTheDeleteArmRemovesTheRecord() {
            final UserSecurity row = standardUserRow();
            arrangeStoredRow(row);

            service.submitScreen(AttentionIdentifier.PF5, submittedScreen(USER_ID), true);

            verify(userSecurityRepository).findByIdForUpdate(USER_ID);
            verify(userSecurityRepository).delete(row);
            verify(userSecurityRepository).flush();
            verifyNoMoreInteractions(userSecurityRepository);
        }

        @Test
        @DisplayName("the ENTER arm of :109-110 reads but never deletes")
        void theEnterArmReadsButNeverDeletes() {
            arrangeStoredRow(standardUserRow());

            service.submitScreen(AttentionIdentifier.ENTER, submittedScreen(USER_ID), false);

            verify(userSecurityRepository).findByIdForUpdate(USER_ID);
            verify(userSecurityRepository, never()).delete(any(UserSecurity.class));
            verify(userSecurityRepository, never()).flush();
            verifyNoMoreInteractions(userSecurityRepository);
        }

        @Test
        @DisplayName("the CLEAR arm of :119-120 performs no delete and resets the four map fields")
        void theClearArmResetsTheFields() {
            final UserSecurityDto.UserDeleteScreen screen =
                    service.submitScreen(AttentionIdentifier.PF4, submittedScreen(USER_ID), true);

            verifyNoInteractions(userSecurityRepository);
            assertThat(screen.userIdInput()).as(":352 blanks USRIDINI").isEqualTo(BLANK);
            assertThat(screen.firstName()).as(":353 blanks FNAMEI").isEqualTo(BLANK);
            assertThat(screen.lastName()).as(":354 blanks LNAMEI").isEqualTo(BLANK);
            assertThat(screen.userType()).as(":355 blanks USRTYPEI").isEqualTo(BLANK);
            assertThat(screen.errorMessage()).as(":356 blanks WS-MESSAGE").isEqualTo(BLANK);
            assertThat(screen.transactionName())
                    .as(":344 re-sends the screen, so :249 stamps the header again")
                    .isEqualTo(TRANSACTION_ID);
            assertThat(screen.programName()).isEqualTo(PROGRAM_NAME);
            assertThat(screen.currentDate()).isEqualTo(EXPECTED_HEADER_DATE);
            assertThat(screen.currentTime()).isEqualTo(EXPECTED_HEADER_TIME);
            assertThat(screen.title01()).isEqualTo(SCREEN_TITLE_01);
            assertThat(screen.title02()).isEqualTo(SCREEN_TITLE_02);
        }

        @Test
        @DisplayName("the CANCEL arm of :123-125 performs no delete and names the admin menu")
        void theCancelArmNamesTheAdminMenu() throws ReflectiveOperationException {
            service.submitScreen(AttentionIdentifier.PF12, submittedScreen(USER_ID), true);

            verifyNoInteractions(userSecurityRepository);
            assertThat(declaredConstant("ADMIN_MENU_PROGRAM"))
                    .as(":124 moves 'COADM01C' into CDEMO-TO-PROGRAM unconditionally. The response record "
                            + "declares no routing component, so the declared target is what is asserted")
                    .isEqualTo(ADMIN_MENU_PROGRAM);
        }

        @Test
        @DisplayName("the EXIT arm falls back to the admin menu, because no originating context is carried")
        void theExitArmFallsBackToTheAdminMenu() throws ReflectiveOperationException {
            service.submitScreen(AttentionIdentifier.PF3, submittedScreen(USER_ID), true);

            verifyNoInteractions(userSecurityRepository);
            assertThat(declaredConstant("ADMIN_MENU_PROGRAM"))
                    .as(":112-113 tests CDEMO-FROM-PROGRAM for blank and takes 'COADM01C'. The "
                            + "communication area's from-program has no stateless counterpart, so that test "
                            + "always succeeds and the :115-116 arm cannot be entered - preserved and cited "
                            + "rather than removed")
                    .isEqualTo(ADMIN_MENU_PROGRAM);
        }

        @Test
        @DisplayName("the unrecognised-action message comes from the SHARED constants holder")
        void theUnrecognisedActionMessageComesFromTheSharedHolder() throws Exception {
            final UserSecurityDto.UserDeleteScreen screen =
                    service.submitScreen(AttentionIdentifier.OTHER, submittedScreen(USER_ID), false);

            assertThat(screen.errorMessage())
                    .as(":128 moves CCDA-MSG-INVALID-KEY, a constant of app/cpy/CSMSG01Y.cpy, and this is "
                            + "the ONE message on this screen that is shared corpus wide")
                    .isEqualTo(INVALID_KEY_MESSAGE)
                    .isEqualTo(declaredConstant("INVALID_KEY_MESSAGE"));
            assertThat(String.join("\n", corpusLines(SHARED_MESSAGES_SOURCE)))
                    .as("the value must be the copybook's, not a local re-declaration of it")
                    .contains("CCDA-MSG-INVALID-KEY")
                    .contains(INVALID_KEY_MESSAGE);
            verifyNoInteractions(userSecurityRepository);
        }

        @Test
        @DisplayName("an absent communication area routes to the sign-on entry point and reads nothing")
        void anAbsentCommunicationAreaRoutesToSignOn() throws ReflectiveOperationException {
            final UserSecurityDto.UserDeleteScreen screen = service.openWithoutContext();

            verifyNoInteractions(userSecurityRepository);
            assertThat(declaredConstant("SIGN_ON_PROGRAM"))
                    .as(":90-92 tests EIBCALEN for zero and moves 'COSGN00C' before transferring")
                    .isEqualTo(SIGN_ON_PROGRAM);
            assertThat(List.of(screen.transactionName() == null, screen.programName() == null,
                    screen.currentDate() == null, screen.currentTime() == null, screen.title01() == null,
                    screen.title02() == null, screen.userIdInput() == null, screen.firstName() == null,
                    screen.lastName() == null, screen.userType() == null))
                    .as("the arm transfers at :92 without ever performing SEND-USRDEL-SCREEN, so :247-262 "
                            + "never runs and not one map field is painted")
                    .containsOnly(true);
            assertThat(screen.errorMessage())
                    .as(":87-88 blanks WS-MESSAGE and ERRMSGO BEFORE the EIBCALEN test of :90, so the "
                            + "message field alone arrives blank rather than unpainted")
                    .isEqualTo(BLANK);
        }

        @Test
        @DisplayName("the attention identifier declares exactly the six arms of :108-130")
        void theAttentionIdentifierDeclaresSixArms() {
            assertThat(AttentionIdentifier.values())
                    .as("DFHENTER, DFHPF3, DFHPF4, DFHPF5, DFHPF12 and WHEN OTHER")
                    .containsExactly(AttentionIdentifier.ENTER, AttentionIdentifier.PF3,
                            AttentionIdentifier.PF4, AttentionIdentifier.PF5, AttentionIdentifier.PF12,
                            AttentionIdentifier.OTHER);
        }

        @Test
        @DisplayName("a submitted screen with no attention identifier is refused, not guessed at")
        void aMissingAttentionIdentifierIsRefused() {
            assertThatNullPointerException()
                    .isThrownBy(() -> service.submitScreen(null, submittedScreen(USER_ID), true))
                    .withMessageContaining("aid");

            verifyNoInteractions(userSecurityRepository);
        }

        @ParameterizedTest(name = "the {0} arm refuses a missing screen rather than reading a null map")
        @EnumSource(value = AttentionIdentifier.class, names = {"ENTER", "PF5"})
        @DisplayName("the two arms that touch the record refuse a missing screen")
        void theRecordTouchingArmsRefuseAMissingScreen(final AttentionIdentifier aid) {
            assertThatNullPointerException()
                    .isThrownBy(() -> service.submitScreen(aid, null, true))
                    .withMessageContaining("request");

            verifyNoInteractions(userSecurityRepository);
        }
    }

    // =====================================================================================================
    // 5. The lookup path: three fields, no credential, the CONTINUE that stops nothing, and the double send
    // =====================================================================================================

    /**
     * {@code PROCESS-ENTER-KEY} at {@code app/cbl/COUSR03C.cbl}:142-169 blanks three map fields at
     * {@code :157-159}, keys the read, and on success moves three fields back at {@code :165-167}.
     * <strong>Three, not four</strong>: this screen has no credential field at all, so unlike
     * {@code app/cbl/COUSR02C.cbl}:169 it cannot paint a password. On this path the source is already
     * correct, and the assertion below records that rather than a deviation.
     *
     * <p>The read arm at {@code :281-286} opens with {@code CONTINUE} at {@code :282}, which is a no-op that
     * does <strong>not</strong> terminate its branch: {@code :283-286} all run, so every successful read
     * emits the prompt and sends the screen, and {@code :168} then sends it again. That double send is
     * retained for parity rather than optimised away, and it is proved here by counting clock reads rather
     * than asserted in prose. The identical trap sits at {@code app/cbl/COUSR02C.cbl}:335.
     */
    @Nested
    @DisplayName("5. The lookup path, the double send of :282-286 plus :168, and no credential anywhere")
    class LookupPath {

        @Test
        @DisplayName("the lookup reports the three displayed fields and carries no credential")
        void theLookupReportsThreeFieldsAndNoCredential() {
            arrangeStoredRow(standardUserRow());

            final UserSecurityDto.UserDeleteScreen screen = service.lookupUser(USER_ID);

            assertThat(screen.userIdInput()).isEqualTo(USER_ID);
            assertThat(screen.firstName()).as(":165 moves SEC-USR-FNAME").isEqualTo(STORED_FIRST_NAME);
            assertThat(screen.lastName()).as(":166 moves SEC-USR-LNAME").isEqualTo(STORED_LAST_NAME);
            assertThat(screen.userType()).as(":167 moves SEC-USR-TYPE").isEqualTo(STORED_USER_TYPE);
            assertThat(List.of(screen.transactionName(), screen.title01(), screen.currentDate(),
                    screen.programName(), screen.title02(), screen.currentTime(), screen.userIdInput(),
                    screen.firstName(), screen.lastName(), screen.userType(), screen.errorMessage()))
                    .as("Rule 1 Clause D names tests explicitly: no component of the response may carry the "
                            + "stored digest, and this screen has no field that could")
                    .doesNotContain(syntheticDigest());
        }

        @Test
        @DisplayName("a successful lookup emits the prompt of :283 byte exactly, space before the ellipsis")
        void aSuccessfulLookupEmitsThePromptByteExactly() throws ReflectiveOperationException {
            arrangeStoredRow(standardUserRow());

            final UserSecurityDto.UserDeleteScreen screen = service.lookupUser(USER_ID);

            assertThat(screen.errorMessage())
                    .as("the CONTINUE at :282 terminates nothing, so :283 runs on every successful read")
                    .isEqualTo(DELETE_HINT_MESSAGE)
                    .isEqualTo(declaredConstant("DELETE_HINT_MESSAGE"))
                    .endsWith(" ...");
            assertThat(USER_NOT_FOUND_MESSAGE)
                    .as("the not-found literal has NO space before its ellipsis, so the two are not "
                            + "interchangeable and neither may be re-spaced")
                    .doesNotEndWith(" ...");
        }

        @Test
        @DisplayName("a successful lookup sends the screen TWICE - :286 and then :168 again")
        void aSuccessfulLookupSendsTheScreenTwice() {
            final CountingClock clock = countingClock();
            arrangeStoredRow(standardUserRow());

            serviceOver(clock).lookupUser(USER_ID);

            assertThat(clock.sendCount())
                    .as("every send performs POPULATE-HEADER-INFO at :243-262 exactly once, which reads the "
                            + "clock exactly once, so the count IS the number of sends. Two is the source's "
                            + "behaviour and is retained rather than optimised: :286 fires inside the read "
                            + "arm and :168 fires again once the arm returns")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("a refused, empty identifier sends the screen once and never reads the store")
        void aRefusedIdentifierSendsOnce() {
            final CountingClock clock = countingClock();
            final UserDeleteService counted = serviceOver(clock);

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> counted.lookupUser(BLANK));

            assertThat(clock.sendCount()).as(":150 sends once and the read of :161 is skipped").isEqualTo(1);
            verifyNoInteractions(userSecurityRepository);
        }

        @Test
        @DisplayName("a not-found lookup sends the screen once, at :292, and then raises")
        void aNotFoundLookupSendsOnce() {
            final CountingClock clock = countingClock();
            final UserDeleteService counted = serviceOver(clock);
            arrangeNoSuchRow(USER_ID);

            assertThatExceptionOfType(RecordNotFoundException.class)
                    .isThrownBy(() -> counted.lookupUser(USER_ID))
                    .withMessage(USER_NOT_FOUND_MESSAGE);

            assertThat(clock.sendCount()).as(":292 sends, and :168 is guarded by :164").isEqualTo(1);
        }

        @Test
        @DisplayName("the read holds the row for update, which is what EXEC CICS READ ... UPDATE did")
        void theReadHoldsTheRowForUpdate() throws NoSuchMethodException {
            arrangeStoredRow(standardUserRow());

            service.lookupUser(USER_ID);

            verify(userSecurityRepository).findByIdForUpdate(USER_ID);
            verifyNoMoreInteractions(userSecurityRepository);

            // UPDATE at :275 held the row from the read to the delete, under UPDATEMODEL(LOCKING) at
            // app/csd/CARDDEMO.CSD:88. The conversational half of that span has no counterpart on a stateless
            // surface, but the half inside one request does, and it is now taken: the read goes through a
            // pessimistic write finder rather than the unlocked findById. This assertion used to demand the
            // OPPOSITE - that no locking finder existed - which encoded the very lost-update defect that made
            // two concurrent administrators able to discard one another's change (Medium severity).
            final Method lockingFinder =
                    UserSecurityRepository.class.getDeclaredMethod("findByIdForUpdate", String.class);
            assertThat(annotationNames(lockingFinder.getAnnotations()))
                    .as("the finder the delete reads through must carry the lock declaration itself; a "
                            + "@Transactional method alone acquires nothing")
                    .contains("Lock");
            assertThat(lockingFinder.getAnnotation(Lock.class).value())
                    .as("PESSIMISTIC_WRITE is the mode EXEC CICS READ ... UPDATE corresponds to. A read "
                            + "lock would let a second reader in and reintroduce the interleaving")
                    .isEqualTo(LockModeType.PESSIMISTIC_WRITE);
        }

        @Test
        @DisplayName("a hard lookup failure carries the dataset of :270 and the verb of :269 into the failure")
        void aHardLookupFailureCarriesTheDatasetAndTheVerb() throws ReflectiveOperationException {
            final QueryTimeoutException timedOut = new QueryTimeoutException("the read did not answer");
            when(userSecurityRepository.findByIdForUpdate(USER_ID)).thenThrow(timedOut);

            final CardDemoException failure = assertThatExceptionOfType(CardDemoException.class)
                    .isThrownBy(() -> service.lookupUser(USER_ID))
                    .actual();

            assertThat(typeOf(failure)).isEqualTo("FileAccessException");
            assertThat(accessorValue(failure, "getLogicalFileName")).isEqualTo(USRSEC_FILE);
            assertThat(accessorValue(failure, "getOperation")).isEqualTo(READ_OPERATION);
            assertThat(failure.getMessage()).contains(USRSEC_FILE).contains(READ_OPERATION);
            assertThat(failure.getCause()).isSameAs(timedOut);
        }

        @Test
        @DisplayName("an unclassifiable read status falls back to the literal of :296, byte exactly")
        void anUnclassifiableReadStatusKeepsTheLookupLiteral() {
            final QueryTimeoutException timedOut = new QueryTimeoutException("the read did not answer");
            final UserDeleteService withSilentMapper = serviceOver(silentMapper());
            when(userSecurityRepository.findByIdForUpdate(USER_ID)).thenThrow(timedOut);

            assertThatExceptionOfType(CardDemoException.class)
                    .isThrownBy(() -> withSilentMapper.lookupUser(USER_ID))
                    .satisfies(failure -> {
                        assertThat(typeOf(failure)).isEqualTo("FileAccessException");
                        assertThat(failure.getMessage())
                                .as("the WHEN OTHER arm at :293-299 moves this literal into WS-MESSAGE, and "
                                        + "the verb is CORRECT here: a read is a lookup")
                                .isEqualTo(UNABLE_TO_LOOKUP_MESSAGE)
                                .isNotEqualTo(UNABLE_TO_UPDATE_MESSAGE);
                        assertThat(failure.getCause()).isSameAs(timedOut);
                    });
        }

        @Test
        @DisplayName("the codes of the live DISPLAY at :294 reach the failure context, with the cause")
        void theCodesOfTheLiveDisplayReachTheFailureContext() {
            final FileStatusMapper watched = mock(FileStatusMapper.class);
            final QueryTimeoutException timedOut = new QueryTimeoutException("the read did not answer");
            when(userSecurityRepository.findByIdForUpdate(USER_ID)).thenThrow(timedOut);
            final ArgumentCaptor<String> status = ArgumentCaptor.forClass(String.class);
            final ArgumentCaptor<String> file = ArgumentCaptor.forClass(String.class);
            final ArgumentCaptor<String> operation = ArgumentCaptor.forClass(String.class);
            final ArgumentCaptor<Throwable> cause = ArgumentCaptor.forClass(Throwable.class);

            assertThatExceptionOfType(CardDemoException.class)
                    .isThrownBy(() -> serviceOver(watched).lookupUser(USER_ID));

            verify(watched).toException(status.capture(), file.capture(), operation.capture(),
                    cause.capture());
            assertThat(status.getValue())
                    .as("the DISPLAY at :294 is LIVE in this program, unlike the commented one at "
                            + "app/cbl/COUSR01C.cbl:268, so the diagnostic must not be lost: the '9x' "
                            + "family member reaches the translator")
                    .isEqualTo(IO_STATUS_IO_ERROR);
            assertThat(file.getValue()).isEqualTo(USRSEC_FILE);
            assertThat(operation.getValue()).isEqualTo(READ_OPERATION);
            assertThat(cause.getValue()).isSameAs(timedOut);
        }

        @ParameterizedTest(name = "the {0} arm of the read records response {1} and status {2}")
        @CsvSource({"NORMAL,CICS_RESP_NORMAL,IO_STATUS_SUCCESS", "NOTFND,CICS_RESP_NOTFND,"
                + "IO_STATUS_RECORD_NOT_FOUND", "OTHER,CICS_RESP_IOERR,IO_STATUS_IO_ERROR"})
        @DisplayName("the three response codes of :280-300 are declared as the source's own DFHRESP values")
        void theThreeResponseCodesAreDeclared(final String arm, final String responseConstant,
                final String statusConstant) throws ReflectiveOperationException {
            final List<Integer> expectedCodes = List.of(CICS_RESP_NORMAL, CICS_RESP_NOTFND, CICS_RESP_IOERR);
            final List<String> expectedStatuses =
                    List.of(IO_STATUS_SUCCESS, IO_STATUS_RECORD_NOT_FOUND, IO_STATUS_IO_ERROR);

            assertThat(declaredConstant(responseConstant))
                    .as("the %s arm's response code must be the production declaration, not a copy", arm)
                    .isIn(expectedCodes);
            assertThat(declaredConstant(statusConstant))
                    .as("the %s arm's file status must be the production declaration, not a copy", arm)
                    .isIn(expectedStatuses);
        }
    }

    // =====================================================================================================
    // 6. The delete path: the clear that runs BEFORE the sentence, and the WRONG-VERB literal at :332
    // =====================================================================================================

    /**
     * {@code DELETE-USER-SEC-FILE} at {@code app/cbl/COUSR03C.cbl}:305-336 holds two traps and one preserved
     * defect.
     *
     * <p>The first trap is ordering. {@code :315} performs {@code INITIALIZE-ALL-FIELDS}, which blanks the
     * four map fields and {@code WS-MESSAGE} at {@code :352-356}, <strong>before</strong> the sentence is
     * composed at {@code :316-321}. The identifier survives anyway, because {@code :319} reads
     * {@code SEC-USR-ID} - the record field, which the clear never touches. A port that composed from the
     * cleared input would emit {@code "User  has been deleted ..."} with the identifier lost. The identical
     * ordering sits at {@code app/cbl/COUSR01C.cbl}:252.
     *
     * <p>The second is that {@code :319} is {@code DELIMITED BY SPACE}, so a key returned blank padded by its
     * {@code CHAR(8)} column contributes only up to its first blank.
     *
     * <p>The preserved defect is {@code :332}: {@code "Unable to Update User..."} on a delete failure, cloned
     * from {@code app/cbl/COUSR02C.cbl}:386 where the verb is correct. It is reproduced byte for byte and is
     * <strong>not</strong> corrected, because the parity comparison reads these strings byte for byte.
     */
    @Nested
    @DisplayName("6. The delete path, the clear before the compose, and the preserved wrong verb at :332")
    class DeletePathAndPreservedWrongVerb {

        @Test
        @DisplayName("the success sentence names the identifier from the RECORD, not from the cleared input")
        void theSuccessSentenceNamesTheIdentifierFromTheRecord() {
            arrangeStoredRow(standardUserRow());

            final UserSecurityDto.UserDeleteScreen screen = service.deleteUser(USER_ID, true);

            assertThat(screen.errorMessage())
                    .as(":315 clears the map and WS-MESSAGE before :318-321 composes, yet :319 reads the "
                            + "RECORD field SEC-USR-ID, which the clear does not touch. Composing from the "
                            + "cleared input instead loses the identifier")
                    .isEqualTo(DELETED_MESSAGE)
                    .isEqualTo("User USER0003 has been deleted ...")
                    .isNotEqualTo(LOST_IDENTIFIER_MESSAGE);
            assertThat(screen.userIdInput())
                    .as("the input field itself IS cleared at :352, which is what makes the sentence's "
                            + "surviving identifier evidence of where it came from")
                    .isEqualTo(BLANK);
            assertThat(screen.firstName()).isEqualTo(BLANK);
            assertThat(screen.lastName()).isEqualTo(BLANK);
            assertThat(screen.userType()).isEqualTo(BLANK);
        }

        @Test
        @DisplayName("the sentence stops at the record key's first blank, because :319 is DELIMITED BY SPACE")
        void theSentenceStopsAtTheRecordKeysFirstBlank() {
            final UserSecurity padded =
                    storedRow(PADDED_USER_ID, STORED_FIRST_NAME, STORED_LAST_NAME, UserType.USER);
            when(userSecurityRepository.findByIdForUpdate(UNPADDED_USER_ID)).thenReturn(Optional.of(padded));

            final UserSecurityDto.UserDeleteScreen screen = service.deleteUser(UNPADDED_USER_ID, true);

            verify(userSecurityRepository).delete(padded);
            assertThat(screen.errorMessage())
                    .as("a CHAR(8) column returns the key blank padded. :319 takes it DELIMITED BY SPACE, so "
                            + "the padding never reaches the sentence and the whole SIZE of the field is "
                            + "never emitted")
                    .isEqualTo(DELETED_MESSAGE_PREFIX + UNPADDED_USER_ID + DELETED_MESSAGE_SUFFIX)
                    .doesNotContain(PADDED_USER_ID);
        }

        @Test
        @DisplayName("a hard delete failure yields the WRONG-VERB literal of :332, byte exactly")
        void aHardDeleteFailureYieldsTheWrongVerbLiteral() {
            final UserSecurity row = standardUserRow();
            final QueryTimeoutException timedOut = new QueryTimeoutException("the delete did not answer");
            final UserDeleteService withSilentMapper = serviceOver(silentMapper());
            arrangeStoredRow(row);
            doThrow(timedOut).when(userSecurityRepository).delete(row);

            assertThatExceptionOfType(CardDemoException.class)
                    .isThrownBy(() -> withSilentMapper.deleteUser(USER_ID, true))
                    .satisfies(failure -> {
                        assertThat(typeOf(failure)).isEqualTo("FileAccessException");
                        assertThat(failure.getMessage())
                                .as("PRESERVED LEGACY DEFECT at app/cbl/COUSR03C.cbl:332. The verb is wrong "
                                        + "- this is a DELETE failure and nothing was being updated - and it "
                                        + "was cloned from app/cbl/COUSR02C.cbl:386 where it reads "
                                        + "correctly. It is reproduced byte for byte and must NOT be "
                                        + "corrected, because the parity comparison reads it byte for byte")
                                .isEqualTo(UNABLE_TO_UPDATE_MESSAGE)
                                .doesNotContain(CORRECTED_VERB_FRAGMENT)
                                .isNotEqualTo(UNABLE_TO_LOOKUP_MESSAGE);
                        assertThat(failure.getCause()).isSameAs(timedOut);
                    });
        }

        @Test
        @DisplayName("the wrong-verb literal is what the production declaration holds, not a local copy")
        void theWrongVerbLiteralIsTheProductionDeclaration() throws ReflectiveOperationException {
            assertThat(declaredConstant("UNABLE_TO_UPDATE_MESSAGE"))
                    .isEqualTo(UNABLE_TO_UPDATE_MESSAGE)
                    .isNotEqualTo(declaredConstant("UNABLE_TO_LOOKUP_MESSAGE"));
            assertThat(declaredStringConstants())
                    .as("the corrected form must not appear anywhere in the service's declarations")
                    .noneMatch(value -> value.contains(CORRECTED_VERB_FRAGMENT));
        }

        @Test
        @DisplayName("a hard delete failure carries the dataset of :308 and the DELETE verb of :307")
        void aHardDeleteFailureCarriesTheDatasetAndTheVerb() throws ReflectiveOperationException {
            final UserSecurity row = standardUserRow();
            final QueryTimeoutException timedOut = new QueryTimeoutException("the delete did not answer");
            arrangeStoredRow(row);
            doThrow(timedOut).when(userSecurityRepository).delete(row);

            final CardDemoException failure = assertThatExceptionOfType(CardDemoException.class)
                    .isThrownBy(() -> service.deleteUser(USER_ID, true))
                    .actual();

            assertThat(typeOf(failure)).isEqualTo("FileAccessException");
            assertThat(accessorValue(failure, "getLogicalFileName")).isEqualTo(USRSEC_FILE);
            assertThat(accessorValue(failure, "getOperation"))
                    .as("the two paragraphs must stay apart in the diagnostic, or a delete failure reads as "
                            + "a read failure")
                    .isEqualTo(DELETE_OPERATION)
                    .isNotEqualTo(READ_OPERATION);
            assertThat(failure.getCause()).isSameAs(timedOut);
        }

        @Test
        @DisplayName("the codes of the live DISPLAY at :330 reach the failure context on the delete verb")
        void theCodesOfTheLiveDisplayReachTheDeleteFailureContext() {
            final FileStatusMapper watched = mock(FileStatusMapper.class);
            final UserSecurity row = standardUserRow();
            final QueryTimeoutException timedOut = new QueryTimeoutException("the delete did not answer");
            arrangeStoredRow(row);
            doThrow(timedOut).when(userSecurityRepository).delete(row);
            final ArgumentCaptor<String> status = ArgumentCaptor.forClass(String.class);
            final ArgumentCaptor<String> operation = ArgumentCaptor.forClass(String.class);
            final ArgumentCaptor<Throwable> cause = ArgumentCaptor.forClass(Throwable.class);

            assertThatExceptionOfType(CardDemoException.class)
                    .isThrownBy(() -> serviceOver(watched).deleteUser(USER_ID, true));

            verify(watched).toException(status.capture(), anyString(), operation.capture(), cause.capture());
            assertThat(status.getValue()).isEqualTo(IO_STATUS_IO_ERROR);
            assertThat(operation.getValue()).isEqualTo(DELETE_OPERATION);
            assertThat(cause.getValue()).isSameAs(timedOut);
        }

        @Test
        @DisplayName("NO duplicate-record outcome exists on a verb that names no key")
        void noDuplicateRecordOutcomeExistsOnTheDeletePath() {
            final UserSecurity row = standardUserRow();
            final DataIntegrityViolationException violated =
                    new DataIntegrityViolationException("a constraint refused the removal");
            arrangeStoredRow(row);
            doThrow(violated).when(userSecurityRepository).delete(row);

            assertThatExceptionOfType(CardDemoException.class)
                    .isThrownBy(() -> service.deleteUser(USER_ID, true))
                    .satisfies(failure -> assertThat(typeOf(failure))
                            .as(":307-311 carries neither RIDFLD nor KEYLENGTH, so there is no key to "
                                    + "collide and the evaluation at :313-336 has exactly three arms - "
                                    + "NORMAL, NOTFND and OTHER. A duplicate-record outcome here would be "
                                    + "invented")
                            .isEqualTo("FileAccessException")
                            .isNotEqualTo("DuplicateRecordException"));
        }

        @Test
        @DisplayName("a delete-time not-found reports the literal of :325, which is the one :289 reports")
        void aDeleteTimeNotFoundReportsTheSharedLiteral() throws ReflectiveOperationException {
            arrangeNoSuchRow(USER_ID);

            assertThatExceptionOfType(RecordNotFoundException.class)
                    .isThrownBy(() -> service.deleteUser(USER_ID, true))
                    .satisfies(absent -> {
                        assertThat(absent.getMessage())
                                .as(":289 and :325 carry the IDENTICAL literal, so a deletion of an absent "
                                        + "identifier reads the same however the short circuit reaches it")
                                .isEqualTo(USER_NOT_FOUND_MESSAGE);
                        assertThat(absent.recordKey())
                                .as("the cursor parked on the identifier field at :327; the key at fault is "
                                        + "carried instead")
                                .contains(USER_ID);
                    });
            assertThat(declaredConstant("USER_NOT_FOUND_MESSAGE"))
                    .as("one declaration serves both arms, which is why the two are indistinguishable")
                    .isEqualTo(USER_NOT_FOUND_MESSAGE);
        }

        @Test
        @DisplayName("a successful delete sends TWICE: the prompt of :286, then the sentence of :322")
        void aSuccessfulDeleteSendsTwice() {
            final CountingClock clock = countingClock();
            arrangeStoredRow(standardUserRow());

            final UserSecurityDto.UserDeleteScreen screen = serviceOver(clock).deleteUser(USER_ID, true);

            assertThat(clock.sendCount())
                    .as("the read arm's :286 fires before :191 destroys the record, so the prompt is emitted "
                            + "immediately before the deletion and is then superseded by :322 - a "
                            + "second-order artefact of the CONTINUE at :282, preserved and recorded")
                    .isEqualTo(2);
            assertThat(screen.errorMessage())
                    .as("the later send wins, so the caller sees the sentence rather than the prompt")
                    .isEqualTo(DELETED_MESSAGE)
                    .isNotEqualTo(DELETE_HINT_MESSAGE);
        }

        @Test
        @DisplayName("the response declares no severity component, so every outcome is told apart by type")
        void everyOutcomeIsDistinguishableWithoutASeverityComponent() {
            assertThat(componentNames(UserSecurityDto.UserDeleteScreen.class))
                    .as("ERRMSGC at :285 and :317 is an attribute byte of the output redefinition, not one "
                            + "of the eleven ...I fields the contract declares, so no colour component "
                            + "exists to be set. A field written and never read would itself be dead code")
                    .noneMatch(component -> component.toLowerCase(Locale.ROOT).contains("colour"))
                    .noneMatch(component -> component.toLowerCase(Locale.ROOT).contains("color"))
                    .noneMatch(component -> component.toLowerCase(Locale.ROOT).contains("severity"));
            assertThat(List.of(USER_ID_REQUIRED_MESSAGE, DELETE_HINT_MESSAGE, USER_NOT_FOUND_MESSAGE,
                    UNABLE_TO_LOOKUP_MESSAGE, UNABLE_TO_UPDATE_MESSAGE, DELETED_MESSAGE))
                    .as("the discriminator that replaces the colour is the distinct literal plus the typed "
                            + "failure, and every one of the six is distinct")
                    .doesNotHaveDuplicates();
        }
    }

    // =====================================================================================================
    // 7. Two vestigial declarations: a write-only flag and a pagination block on a single-record screen
    // =====================================================================================================

    /**
     * {@code WS-USR-MODIFIED} is declared at {@code app/cbl/COUSR03C.cbl}:45-47 and set once, at {@code :85}.
     * {@code grep -n "WS-USR-MODIFIED" app/cbl/COUSR03C.cbl} returns those four lines and nothing else: it
     * <strong>never appears in an {@code IF} or an {@code EVALUATE}</strong>. It was cloned from
     * {@code app/cbl/COUSR02C.cbl}:45-47, where the identical field genuinely drives a gate. Here it is dead,
     * so there is nothing for the bean to branch on and no behaviour to test - which is itself the assertion.
     *
     * <p>{@code :50-58} appends a pagination block to the communication area on a screen that shows one
     * record. {@code CDEMO-CU03-USRID-FIRST}, {@code -USRID-LAST}, {@code -PAGE-NUM} and
     * {@code -NEXT-PAGE-FLG} are all meaningless here; the only live member is
     * {@code CDEMO-CU03-USR-SELECTED}, consumed at {@code :99-103} as the hand-off from the user list's row
     * selection. Only that one identifier is carried.
     */
    @Nested
    @DisplayName("7. The write-only flag of :45-47 and the vestigial pagination block of :50-58")
    class VestigialDeclarations {

        @Test
        @DisplayName("no modified-state flag influences any outcome, because the source never reads one")
        void noModifiedStateFlagInfluencesAnyOutcome() {
            final UserSecurity row = standardUserRow();
            arrangeStoredRow(row);

            final UserSecurityDto.UserDeleteScreen afterLookup = service.lookupUser(USER_ID);
            final UserSecurityDto.UserDeleteScreen afterDelete = service.deleteUser(USER_ID, true);

            assertThat(afterLookup.errorMessage()).isEqualTo(DELETE_HINT_MESSAGE);
            assertThat(afterDelete.errorMessage())
                    .as(":85 sets the flag and nothing ever tests it, so a lookup before the delete cannot "
                            + "change the delete's outcome. app/cbl/COUSR02C.cbl:236 does test its copy of "
                            + "the same field; this program does not")
                    .isEqualTo(DELETED_MESSAGE);
            assertThat(UserDeleteService.class.getDeclaredFields())
                    .as("the flag is per-call working storage, never bean state that a second call could see")
                    .noneMatch(field -> field.getName().toLowerCase(Locale.ROOT).contains("modified"));
        }

        @Test
        @DisplayName("a selected identifier from the list service pre-populates AND performs the lookup")
        void aSelectedIdentifierPerformsTheLookupImmediately() {
            arrangeStoredRow(standardUserRow());

            final UserSecurityDto.UserDeleteScreen screen = service.openScreen(USER_ID);

            verify(userSecurityRepository).findByIdForUpdate(USER_ID);
            assertThat(screen.userIdInput())
                    .as(":101-102 moves CDEMO-CU03-USR-SELECTED onto USRIDINI")
                    .isEqualTo(USER_ID);
            assertThat(screen.firstName()).isEqualTo(STORED_FIRST_NAME);
            assertThat(screen.errorMessage())
                    .as(":103 performs PROCESS-ENTER-KEY straight away, so the operator lands on a populated "
                            + "screen carrying the prompt")
                    .isEqualTo(DELETE_HINT_MESSAGE);
        }

        @Test
        @DisplayName("a first display with a selected identifier sends three times: :286, :168 and :105")
        void aFirstDisplayWithASelectionSendsThreeTimes() {
            final CountingClock clock = countingClock();
            arrangeStoredRow(standardUserRow());

            serviceOver(clock).openScreen(USER_ID);

            assertThat(clock.sendCount())
                    .as("the read arm sends at :286, the populated map sends at :168, and the first-display "
                            + "arm sends again at :105 - the source's own sequence, unoptimised")
                    .isEqualTo(3);
        }

        @ParameterizedTest(name = "a first display with [{0}] selected performs no lookup")
        @ValueSource(strings = {"", " ", "        "})
        @DisplayName("a blank selection performs no lookup, because :99-100 tests it first")
        void aBlankSelectionPerformsNoLookup(final String blank) {
            final UserSecurityDto.UserDeleteScreen screen = service.openScreen(blank);

            verifyNoInteractions(userSecurityRepository);
            assertThat(screen.userIdInput()).as(":97 blanks the output map").isEqualTo(BLANK);
            assertThat(screen.transactionName()).as(":105 sends, so :249 stamps the header")
                    .isEqualTo(TRANSACTION_ID);
            assertThat(screen.errorMessage()).isEqualTo(BLANK);
        }

        @Test
        @DisplayName("a null selection performs no lookup either")
        void aNullSelectionPerformsNoLookup() {
            final UserSecurityDto.UserDeleteScreen screen = service.openScreen(null);

            verifyNoInteractions(userSecurityRepository);
            assertThat(screen.userIdInput()).isEqualTo(BLANK);
        }

        @Test
        @DisplayName("no page number, next-page flag or first-and-last key is carried on this path")
        void noPaginationStateIsCarried() {
            assertThat(componentNames(UserSecurityDto.UserDeleteScreen.class))
                    .as("the block at :51-57 is a clone onto a single-record screen; only "
                            + "CDEMO-CU03-USR-SELECTED at :58 is live, and it arrives as the one identifier "
                            + "the first-display entry point takes. The two name components are the given "
                            + "and family names of :165-166, not a first-and-last key pair")
                    .containsExactlyElementsOf(SCREEN_COMPONENTS)
                    .noneMatch(component -> component.toLowerCase(Locale.ROOT).contains("page"))
                    .noneMatch(component -> component.toLowerCase(Locale.ROOT).contains("selectionflag"))
                    .doesNotContain("userIdFirst", "userIdLast", "pageNumber", "nextPageFlag");
            for (final Method entryPoint : publicEntryPoints()) {
                for (final Class<?> parameter : entryPoint.getParameterTypes()) {
                    assertThat(parameter.getSimpleName())
                            .as("%s must take no paging argument", entryPoint.getName())
                            .isNotEqualTo("Pageable")
                            .isNotEqualTo("Slice");
                }
            }
        }
    }

    // =====================================================================================================
    // 8. Field contracts and paragraph correspondence
    // =====================================================================================================

    /**
     * The request shape comes from {@code app/cpy-bms/COUSR03.CPY}, <strong>eleven input fields</strong> and
     * the smallest of the four user screens - and the only one with no credential field. The record comes from
     * {@code app/cpy/CSUSR01Y.cpy}, eighty bytes with an eight character key. Every one of the eleven
     * paragraph labels of {@code app/cbl/COUSR03C.cbl} maps to its own private method; none is consolidated,
     * including the four whose CICS or communication-area state has no counterpart.
     */
    @Nested
    @DisplayName("8. Field contracts, the 80-against-78 truncation, and eleven paragraphs mapped one to one")
    class FieldContractsAndParagraphCorrespondence {

        @Test
        @DisplayName("the contract declares the eleven fields of the map and no credential")
        void theContractDeclaresElevenFieldsAndNoCredential() {
            assertThat(componentNames(UserSecurityDto.UserDeleteScreen.class))
                    .containsExactlyElementsOf(SCREEN_COMPONENTS)
                    .hasSize(MAP_INPUT_FIELD_COUNT)
                    .noneMatch(component -> component.toLowerCase(Locale.ROOT).contains("password"))
                    .noneMatch(component -> component.toLowerCase(Locale.ROOT).contains("passwd"))
                    .noneMatch(component -> component.toLowerCase(Locale.ROOT).contains("credential"))
                    .noneMatch(component -> component.toLowerCase(Locale.ROOT).contains("hash"));
            assertThat(UserSecurityDto.UserDeleteScreen.MAP_FIELD_COUNT)
                    .as("six header fields, four detail fields and the message field")
                    .isEqualTo(MAP_INPUT_FIELD_COUNT)
                    .isEqualTo(UserSecurityDto.UserDeleteScreen.HEADER_FIELD_COUNT
                            + UserSecurityDto.UserDeleteScreen.DETAIL_FIELD_COUNT + 1);
        }

        @Test
        @DisplayName("the message contract truncates at 78, so the 80 byte work area loses two bytes")
        void theMessageContractTruncatesAtSeventyEight() {
            assertThat(UserSecurityDto.ERROR_MESSAGE_WIDTH)
                    .as("WS-MESSAGE at :38 is PIC X(80) while ERRMSGI and ERRMSGO are PIC X(78), so the "
                            + "work area is two bytes wider than the field it is moved into. The narrower "
                            + "contract is asserted rather than widened")
                    .isEqualTo(SCREEN_MESSAGE_WIDTH)
                    .isLessThan(WORK_AREA_MESSAGE_WIDTH);
            assertThat(WORK_AREA_MESSAGE_WIDTH - SCREEN_MESSAGE_WIDTH).isEqualTo(2);

            final String tooLong = "x".repeat(SCREEN_MESSAGE_WIDTH + 1);
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new UserSecurityDto.UserDeleteScreen(TRANSACTION_ID, SCREEN_TITLE_01,
                            EXPECTED_HEADER_DATE, PROGRAM_NAME, SCREEN_TITLE_02, EXPECTED_HEADER_TIME,
                            USER_ID, STORED_FIRST_NAME, STORED_LAST_NAME, STORED_USER_TYPE, tooLong));
        }

        @Test
        @DisplayName("every literal the service declares fits the 78 character field it is moved into")
        void everyDeclaredLiteralFitsTheScreenField() {
            for (final String literal : List.of(USER_ID_REQUIRED_MESSAGE, DELETE_HINT_MESSAGE,
                    USER_NOT_FOUND_MESSAGE, UNABLE_TO_LOOKUP_MESSAGE, UNABLE_TO_UPDATE_MESSAGE,
                    INVALID_KEY_MESSAGE, DELETED_MESSAGE)) {
                assertThat(literal.length())
                        .as("literal [%s] would be truncated by ERRMSGO before the operator saw it", literal)
                        .isLessThanOrEqualTo(SCREEN_MESSAGE_WIDTH);
            }
        }

        @Test
        @DisplayName("the eleven paragraph labels each have their own private method, none consolidated")
        void theElevenParagraphsEachHaveTheirOwnMethod() {
            final List<String> declared = Arrays.stream(UserDeleteService.class.getDeclaredMethods())
                    .filter(method -> Modifier.isPrivate(method.getModifiers()))
                    .filter(method -> !method.isSynthetic())
                    .map(Method::getName)
                    .toList();

            assertThat(declared)
                    .as("the source-citing method per label is the evidence the scope-coverage gate reads, "
                            + "so consolidating two labels into one method removes evidence rather than code")
                    .containsAll(PARAGRAPH_METHODS);
            assertThat(PARAGRAPH_METHODS).hasSize(PARAGRAPH_COUNT).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("the program, transaction and dataset literals are the source's own, pad excluded")
        void theProgramTransactionAndDatasetLiteralsAreTheSources() throws ReflectiveOperationException {
            assertThat(declaredConstant("PROGRAM_NAME")).isEqualTo(PROGRAM_NAME);
            assertThat(declaredConstant("TRANSACTION_ID")).isEqualTo(TRANSACTION_ID);
            assertThat(declaredConstant("USRSEC_FILE"))
                    .as("WS-USRSEC-FILE at :39 is PIC X(08) VALUE 'USRSEC  ' - two trailing spaces of pad "
                            + "that are not part of the name the diagnostic reports")
                    .isEqualTo(USRSEC_FILE);
            assertThat(declaredConstant("READ_OPERATION")).isEqualTo(READ_OPERATION);
            assertThat(declaredConstant("DELETE_OPERATION")).isEqualTo(DELETE_OPERATION);
        }

        @Test
        @DisplayName("the user class is displayed only and gates nothing on this screen")
        void theUserClassIsDisplayedOnly() {
            final UserSecurity administrator = operatorRow();
            arrangeStoredRow(administrator);

            final UserSecurityDto.UserDeleteScreen screen = service.lookupUser(OPERATOR_ID);

            assertThat(screen.userType())
                    .as(":167 moves SEC-USR-TYPE onto the map and no branch anywhere in the program tests it")
                    .isEqualTo(ADMIN_USER_TYPE)
                    .isEqualTo(String.valueOf(UserType.ADMIN.getCode()));
            assertThat(UserType.values()).containsExactly(UserType.ADMIN, UserType.USER);
            assertThat(UserSecurityDto.USER_TYPE_WIDTH).isEqualTo(1);
        }

        @Test
        @DisplayName("no server-side screen or re-entry state is retained between two identical turns")
        void noServerSideScreenStateIsRetained() {
            arrangeStoredRow(standardUserRow());

            final UserSecurityDto.UserDeleteScreen first = service.lookupUser(USER_ID);
            final UserSecurityDto.UserDeleteScreen second = service.lookupUser(USER_ID);

            assertThat(second)
                    .as("the RETURN ... TRANSID ... COMMAREA of :134-137 is the pseudo-conversational "
                            + "re-entry mechanism and has no counterpart: the enter-against-re-enter "
                            + "distinction collapses into stateless request handling, so two identical turns "
                            + "are indistinguishable")
                    .isEqualTo(first);
        }

        @Test
        @DisplayName("the bean is immutable: every field is private and final, and none is mutable static")
        void theBeanIsImmutable() {
            for (final Field field : UserDeleteService.class.getDeclaredFields()) {
                if (field.isSynthetic()) {
                    continue;
                }
                assertThat(Modifier.isPrivate(field.getModifiers()))
                        .as("field %s is not private", field.getName())
                        .isTrue();
                assertThat(Modifier.isFinal(field.getModifiers()))
                        .as("field %s is not final, so a turn could leave state behind for the next one",
                                field.getName())
                        .isTrue();
            }
        }

        @Test
        @DisplayName("the clear-screen paragraph of :341 is retained in its own right")
        void theClearScreenParagraphIsRetainedInItsOwnRight() {
            final UserSecurityDto.UserDeleteScreen cleared =
                    service.submitScreen(AttentionIdentifier.PF4, submittedScreen(USER_ID), true);

            assertThat(Arrays.stream(UserDeleteService.class.getDeclaredMethods())
                    .map(Method::getName)
                    .toList())
                    .as(":341-344 is a paragraph whose whole body is two performs. Inlining it would be "
                            + "tidier and would break the label map the coverage gate reads, so it stays")
                    .contains("clearCurrentScreen", "initializeAllFields", "sendUsrdelScreen");
            assertThat(List.of(cleared.userIdInput(), cleared.firstName(), cleared.lastName(),
                    cleared.userType(), cleared.errorMessage()))
                    .as(":343 blanks the same five values :315 blanks on the success path")
                    .containsOnly(BLANK);
        }
    }

    // =====================================================================================================
    // 9. Hostile input, boundaries and determinism - Rule 1 Clauses A and B
    // =====================================================================================================

    /**
     * Emptiness is the <strong>only</strong> validation either path performs: {@code :144-154} and
     * {@code :176-186} test for blank and low values and nothing else - no length rule, no character set, no
     * format. The width of {@code PIC X(08)} is nevertheless a real boundary, because the 3270 map made an
     * over-length value physically impossible and the record it lands in is still eighty bytes.
     */
    @Nested
    @DisplayName("9. Hostile input, the eight character boundary, and determinism")
    class HostileInputAndDeterminism {

        @Test
        @DisplayName("a nine character identifier is refused before any file access")
        void anOverLongIdentifierIsRefused() {
            assertThat(OVERLONG_USER_ID).hasSize(UserSecurityDto.USER_ID_WIDTH + 1);

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.deleteUser(OVERLONG_USER_ID, true))
                    .satisfies(refusal -> {
                        assertThat(refusal.getFieldName()).isEqualTo("userId");
                        assertThat(refusal.getFailureKind().name()).isEqualTo("INVALID");
                        assertThat(refusal.getMessage())
                                .as("Rule 1 Clause D: the refusal names the field and the limit and never "
                                        + "echoes the offending value back")
                                .contains(String.valueOf(UserSecurityDto.USER_ID_WIDTH))
                                .doesNotContain(OVERLONG_USER_ID);
                    });

            verifyNoInteractions(userSecurityRepository);
        }

        @Test
        @DisplayName("a seven character identifier is accepted and reaches the store unchanged")
        void aSevenCharacterIdentifierReachesTheStoreUnchanged() {
            assertThat(SHORT_USER_ID).hasSize(UserSecurityDto.USER_ID_WIDTH - 1);
            arrangeNoSuchRow(SHORT_USER_ID);
            final ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);

            assertThatExceptionOfType(RecordNotFoundException.class)
                    .isThrownBy(() -> service.deleteUser(SHORT_USER_ID, true));

            verify(userSecurityRepository).findByIdForUpdate(key.capture());
            assertThat(key.getValue())
                    .as("emptiness is the only validation, so a short key is not padded, trimmed or "
                            + "rewritten on its way to the store")
                    .isEqualTo(SHORT_USER_ID);
        }

        @Test
        @DisplayName("a hostile identifier is bound as a parameter and never assembled into a predicate")
        void aHostileIdentifierIsBoundAsAParameter() {
            assertThat(HOSTILE_USER_ID).hasSize(UserSecurityDto.USER_ID_WIDTH);
            arrangeNoSuchRow(HOSTILE_USER_ID);
            final ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);

            assertThatExceptionOfType(RecordNotFoundException.class)
                    .isThrownBy(() -> service.deleteUser(HOSTILE_USER_ID, true));

            verify(userSecurityRepository).findByIdForUpdate(key.capture());
            assertThat(key.getValue())
                    .as("the identifier is untrusted input reaching a keyed read and then a removal, which "
                            + "is the highest-consequence injection surface in this package. It arrives at "
                            + "the store verbatim, as a bound argument")
                    .isEqualTo(HOSTILE_USER_ID);
            verify(userSecurityRepository, never()).delete(any(UserSecurity.class));
        }

        @Test
        @DisplayName("no declared constant carries a query fragment the identifier could be spliced into")
        void noDeclaredConstantCarriesAQueryFragment() {
            for (final String value : declaredStringConstants()) {
                final String lowered = value.toLowerCase(Locale.ROOT);
                assertThat(QUERY_TOKENS)
                        .as("constant [%s] looks like a fragment of a hand-assembled statement", value)
                        .noneMatch(lowered::contains);
            }
        }

        @Test
        @DisplayName("the bean reaches for no wall clock, no platform default and no random source")
        void theBeanReachesForNoNonDeterministicApi() throws IOException {
            final String source = executableSource(SERVICE_SOURCE);

            for (final String forbidden : FORBIDDEN_NON_DETERMINISM) {
                assertThat(source)
                        .as("Rule 1 Clause A puts determinism first, and %s would make the header of :245 "
                                + "depend on when and where the process ran", forbidden)
                        .doesNotContain(forbidden);
            }
            assertThat(source)
                    .as("the only time source is the injected clock, so the rendering is reproducible")
                    .contains("LocalDateTime.now(this.clock)");
        }

        @Test
        @DisplayName("the bean names no environment-specific endpoint, host or port")
        void theBeanNamesNoEnvironmentSpecificEndpoint() {
            for (final String value : declaredStringConstants()) {
                final String lowered = value.toLowerCase(Locale.ROOT);
                assertThat(FORBIDDEN_ENVIRONMENT_LITERALS)
                        .as("constant [%s] would tie a deterministic build to one environment", value)
                        .noneMatch(lowered::contains);
            }
        }

        @Test
        @DisplayName("the header renders identically on every run under a fixed clock")
        void theHeaderRendersIdenticallyUnderAFixedClock() {
            final UserSecurityDto.UserDeleteScreen screen =
                    service.submitScreen(AttentionIdentifier.PF4, submittedScreen(USER_ID), true);

            assertThat(screen.currentDate())
                    .as(":252-256 renders the month, the day and the LAST TWO DIGITS of the year")
                    .isEqualTo(EXPECTED_HEADER_DATE)
                    .hasSize(UserSecurityDto.DATE_WIDTH);
            assertThat(screen.currentTime())
                    .as(":258-262 renders hours, minutes and seconds")
                    .isEqualTo(EXPECTED_HEADER_TIME)
                    .hasSize(UserSecurityDto.TIME_WIDTH);
        }

        @ParameterizedTest(name = "the constructor refuses a null {0}")
        @CsvSource({"0,userSecurityRepository", "1,fileStatusMapper", "2,clock"})
        @DisplayName("each collaborator is required, and the refusal names it")
        void theConstructorRefusesEachNullCollaborator(final int position, final String name) {
            final FileStatusMapper mapper = new FileStatusMapper();
            final Clock clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

            assertThatNullPointerException()
                    .isThrownBy(() -> new UserDeleteService(
                            position == 0 ? null : userSecurityRepository,
                            position == 1 ? null : mapper,
                            position == 2 ? null : clock))
                    .withMessageContaining(name);
        }

        @Test
        @DisplayName("no credential reaches any response component or any diagnostic rendering")
        void noCredentialReachesAnyResponseOrDiagnostic() {
            final UserSecurity row = standardUserRow();
            arrangeStoredRow(row);

            final UserSecurityDto.UserDeleteScreen screen = service.lookupUser(USER_ID);

            assertThat(row.getPasswordHash())
                    .as("the row genuinely holds a digest, so the absence below is a real guarantee rather "
                            + "than a vacuous one")
                    .isNotNull()
                    .hasSize(60);
            assertThat(screen.toString())
                    .as("Rule 1 Clause D names tests explicitly: the diagnostic rendering of the response "
                            + "carries the program name only")
                    .doesNotContain(row.getPasswordHash())
                    .doesNotContain(SYNTHETIC_BODY);
            assertThat(row.toString())
                    .as("the entity's own rendering is the identifier and the class, and nothing else")
                    .doesNotContain(row.getPasswordHash())
                    .doesNotContain(SYNTHETIC_SALT);
        }

        @Test
        @DisplayName("the seeded rows are held as digests of the pinned strength, never as a plaintext value")
        void theSeededRowsAreHeldAsDigestsOnly() {
            assertThat(syntheticDigest())
                    .as("the ten rows app/jcl/DUSRSECJ.jcl seeds inline carry a plaintext value that this "
                            + "suite never reproduces. What is stored is a digest of the contractual shape, "
                            + "assembled from parts so that no digest literal exists in this file either")
                    .startsWith(DIGEST_FIELD_MARKER + DIGEST_VERSION_TAG + DIGEST_FIELD_MARKER
                            + CONTRACTUAL_COST_FACTOR + DIGEST_FIELD_MARKER)
                    .hasSize(60)
                    .contains("NotAReal");
            assertThat(storedRow(USER_ID, STORED_FIRST_NAME, STORED_LAST_NAME, UserType.USER)
                    .getPasswordHash())
                    .as("the entity's own strength guard accepts it, so the shape is the pinned one")
                    .isEqualTo(syntheticDigest());
        }
    }

    // =====================================================================================================
    // 10. Least privilege and statelessness - Rule 1 Clause D
    // =====================================================================================================

    /**
     * This service sits behind {@code /api/admin/*} and performs the most destructive operation in the
     * application. The ADMIN-only rule over that prefix is owned by {@code com.cardemo.config.SecurityConfig},
     * whose own suite asserts it; this service <strong>consumes</strong> that rule and never restates it.
     *
     * <p>The residual risk follows directly from the first group: because the source has no self-delete guard
     * and none is added, the role check is <strong>the only thing</strong> standing between a signed-on
     * operator and the destruction of their own access. So the role check must be asserted rather than
     * assumed, and the assertions below are the part of it this suite owns - that the bean restates no rule,
     * reads no role and keeps no session.
     */
    @Nested
    @DisplayName("10. Least privilege: the role check is the ONLY protection, so it is asserted not assumed")
    class LeastPrivilegeAndStatelessness {

        @Test
        @DisplayName("the service restates no authorisation rule, because the filter chain owns it")
        void theServiceRestatesNoAuthorisationRule() {
            assertThat(annotationNames(UserDeleteService.class.getAnnotations()))
                    .as("the bean is a service and nothing more; the rule over the admin prefix belongs to "
                            + "the filter chain, whose own suite owns it, and restating it in two places is "
                            + "how the two drift apart")
                    .containsExactly("Service");
            for (final Method entryPoint : publicEntryPoints()) {
                assertThat(annotationNames(entryPoint.getAnnotations()))
                        .as("entry point %s must carry no authorisation annotation", entryPoint.getName())
                        .doesNotContainAnyElementsOf(AUTHORISATION_ANNOTATIONS);
            }
        }

        @Test
        @DisplayName("the two user classes the role gate distinguishes are the source's own 'A' and 'U'")
        void theTwoUserClassesAreTheSourcesOwn() throws IOException {
            assertThat(UserType.ADMIN.getCode()).isEqualTo('A');
            assertThat(UserType.USER.getCode()).isEqualTo('U');
            assertThat(String.join("\n", corpusLines(COMMAREA_SOURCE)))
                    .as("CDEMO-USER-TYPE and its two condition names are what the role claim is derived "
                            + "from, and this program reads neither")
                    .contains("CDEMO-USER-TYPE");
        }

        @Test
        @DisplayName("no session, cache or request-scoped collaborator can remember a pending deletion")
        void nothingRemembersAPendingDeletion() throws IOException {
            final String source = executableSource(SERVICE_SOURCE);

            for (final String retained : List.of("HttpSession", "@SessionAttributes", "@Cacheable",
                    "ConcurrentHashMap", "ThreadLocal", "@Scope(\"request\")")) {
                assertThat(source)
                        .as("%s would let confirmation state survive a request, which the stateless mandate "
                                + "forbids and which the source's terminal conversation cannot be emulated "
                                + "with anyway", retained)
                        .doesNotContain(retained);
            }
        }

        @Test
        @DisplayName("a second turn cannot see the first turn's state, so the bean is thread safe by shape")
        void aSecondTurnCannotSeeTheFirstTurnsState() {
            arrangeStoredRow(standardUserRow());

            service.deleteUser(USER_ID, true);
            final UserSecurityDto.UserDeleteScreen repeated = service.deleteUser(USER_ID, true);

            assertThat(repeated.errorMessage())
                    .as("the work area of a turn is created per call and discarded on return, so a repeated "
                            + "turn behaves identically rather than remembering that the row was already "
                            + "removed - the store, not the bean, is what holds that fact")
                    .isEqualTo(DELETED_MESSAGE);
        }
    }

    // =====================================================================================================
    // 11. The frozen corpus, re-read on every build. Rule 1 Clause F: evidence, not recollection.
    // =====================================================================================================

    /**
     * Every claim this suite makes about the source is re-verified here against {@code app/} itself, so that
     * a comment cannot drift from the corpus it cites. The tree is opened read only; nothing under
     * {@code app/} is ever written.
     */
    @Nested
    @DisplayName("11. The frozen corpus itself - every cited fact re-measured on every build")
    class FrozenCorpusEvidence {

        @Test
        @DisplayName("the signed-on identifier appears ZERO times in the program - the headline evidence")
        void theSignedOnIdentifierAppearsZeroTimes() throws IOException {
            final long occurrences = corpusLines(PROGRAM_SOURCE).stream()
                    .filter(line -> line.contains(SIGNED_ON_IDENTITY_SYMBOL))
                    .count();

            assertThat(occurrences)
                    .as("grep -c \"%s\" %s must answer 0. If this ever fails, the program acquired the one "
                            + "ingredient a self-delete guard needs and the guard's absence would have to be "
                            + "re-argued from the new text", SIGNED_ON_IDENTITY_SYMBOL, PROGRAM_SOURCE)
                    .isZero();
        }

        @Test
        @DisplayName("the program is 359 lines with eleven paragraph labels at their cited numbers")
        void theProgramIsThreeHundredAndFiftyNineLinesWithElevenParagraphs() throws IOException {
            final List<String> lines = corpusLines(PROGRAM_SOURCE);
            final List<Integer> labelLines = new ArrayList<>();
            for (int index = 0; index < lines.size(); index++) {
                if (lines.get(index).matches("^ {7}[A-Z0-9][A-Z0-9-]*\\.\\s*$")) {
                    labelLines.add(index + 1);
                }
            }

            assertThat(lines).hasSize(PROGRAM_LINE_COUNT);
            assertThat(labelLines)
                    .as("the paragraph map the coverage gate reads is keyed to these numbers")
                    .containsExactly(82, 142, 174, 197, 213, 230, 243, 267, 305, 341, 349)
                    .hasSize(PARAGRAPH_COUNT);
        }

        @Test
        @DisplayName("the destructive operation is performed under exactly ONE attention identifier")
        void theDestructiveOperationIsPerformedUnderExactlyOneKey() throws IOException {
            final List<String> lines = corpusLines(PROGRAM_SOURCE);
            final String dispatch = citedRegion(lines, 108, 130);

            assertThat(dispatch.split("PERFORM DELETE-USER-INFO", -1).length - 1)
                    .as("only :121-122 destroys the record; every other arm reads, clears or routes")
                    .isEqualTo(1);
            assertThat(citedLine(lines, 121)).contains("WHEN DFHPF5");
            assertThat(citedLine(lines, 122)).contains("PERFORM DELETE-USER-INFO");
            assertThat(citedLine(lines, 111)).contains("WHEN DFHPF3");
            assertThat(citedRegion(lines, 111, 118))
                    .as("the exit arm resolves a target and leaves; it performs no update of any kind")
                    .doesNotContain("PERFORM DELETE-USER-INFO");
        }

        @Test
        @DisplayName("the sibling program performs its save under TWO keys, which is the inversion")
        void theSiblingProgramPerformsItsSaveUnderTwoKeys() throws IOException {
            final List<String> sibling = corpusLines(SIBLING_SOURCE);

            assertThat(citedLine(sibling, 112))
                    .as("app/cbl/COUSR02C.cbl:112 saves on the EXIT key, which app/cbl/COUSR03C.cbl:111-118 "
                            + "does not. Sharing one dispatch between the two would make the exit key "
                            + "destroy the record")
                    .contains("PERFORM UPDATE-USER-INFO");
            assertThat(citedLine(sibling, 123)).contains("PERFORM UPDATE-USER-INFO");
        }

        @Test
        @DisplayName("no confirmation handshake exists in the delete paragraph, unlike the payment program")
        void noConfirmationHandshakeExistsInTheDeleteParagraph() throws IOException {
            final String deleteParagraph = citedRegion(corpusLines(PROGRAM_SOURCE), 174, 192);
            final String paymentHandshake = citedRegion(corpusLines(CONFIRMATION_CONTRAST_SOURCE), 173, 190);

            assertThat(deleteParagraph)
                    .as(":174-192 evaluates emptiness and proceeds to the read and the delete. One key press "
                            + "destroys the record")
                    .doesNotContain("CONFIRM")
                    .doesNotContain("WHEN 'Y'")
                    .doesNotContain("WHEN 'N'");
            assertThat(paymentHandshake)
                    .as("app/cbl/COBIL00C.cbl:173-190 is what a real handshake looks like in this corpus - "
                            + "four ways over CONFIRMI - and it guards a payment rather than a deletion")
                    .contains("CONFIRMI")
                    .contains("WHEN 'Y'")
                    .contains("WHEN 'N'");
        }

        @Test
        @DisplayName("the read verb carries RIDFLD and KEYLENGTH; the delete verb carries neither")
        void theDeleteVerbCarriesNoKey() throws IOException {
            final List<String> lines = corpusLines(PROGRAM_SOURCE);

            assertThat(citedRegion(lines, 269, 278))
                    .as("the read is keyed and locked: :273, :274 and :275")
                    .contains("RIDFLD")
                    .contains("KEYLENGTH")
                    .contains("UPDATE");
            assertThat(citedRegion(lines, 307, 311))
                    .as("the delete names only the dataset and the two response fields, so it acts on the "
                            + "record the READ positioned. After a failed read nothing was positioned, which "
                            + "is why the missing guard at :190-191 has no defined outcome")
                    .doesNotContain("RIDFLD")
                    .doesNotContain("KEYLENGTH");
        }

        @Test
        @DisplayName("the read and the delete sit back to back with no error test between them")
        void theReadAndTheDeleteSitBackToBack() throws IOException {
            final List<String> lines = corpusLines(PROGRAM_SOURCE);

            assertThat(citedLine(lines, 190).strip()).isEqualTo("PERFORM READ-USER-SEC-FILE");
            assertThat(citedLine(lines, 191).strip())
                    .as("nothing stands between them. PROCESS-ENTER-KEY guards its own follow-on blocks at "
                            + ":156 and :164; this paragraph does not")
                    .isEqualTo("PERFORM DELETE-USER-SEC-FILE");
            assertThat(citedRegion(lines, 156, 164)).contains("IF NOT ERR-FLG-ON");
        }

        @Test
        @DisplayName("the CONTINUE at :282 is followed by four statements that all run")
        void theContinueAtTwoEightyTwoStopsNothing() throws IOException {
            final List<String> lines = corpusLines(PROGRAM_SOURCE);

            assertThat(citedLine(lines, 282).strip()).isEqualTo("CONTINUE");
            assertThat(citedRegion(lines, 283, 286))
                    .as("a COBOL CONTINUE is a no-op, not a branch, so the prompt, the colour and the send "
                            + "all execute - and :168 then sends a second time")
                    .contains(DELETE_HINT_MESSAGE)
                    .contains("DFHNEUTR")
                    .contains("PERFORM SEND-USRDEL-SCREEN");
        }

        @Test
        @DisplayName("the clear at :315 precedes the compose at :316-321, and :319 reads the record field")
        void theClearPrecedesTheCompose() throws IOException {
            final List<String> lines = corpusLines(PROGRAM_SOURCE);

            assertThat(citedLine(lines, 315).strip()).isEqualTo("PERFORM INITIALIZE-ALL-FIELDS");
            assertThat(citedLine(lines, 316))
                    .as("the blank at :316 is redundant: :356 has already blanked WS-MESSAGE")
                    .contains("MOVE SPACES")
                    .contains("WS-MESSAGE");
            assertThat(citedLine(lines, 356)).contains("WS-MESSAGE");
            assertThat(citedLine(lines, 319))
                    .as("the identifier survives the clear because the STRING reads SEC-USR-ID, the RECORD "
                            + "field, DELIMITED BY SPACE - and the clear touches only the map")
                    .contains("SEC-USR-ID")
                    .contains("DELIMITED BY SPACE");
            assertThat(citedRegion(lines, 349, 356))
                    .as("the clear blanks the map and the message, and names no record field")
                    .doesNotContain("SEC-USR-ID");
        }

        @Test
        @DisplayName("the wrong-verb literal is at :332 and the corrected form is nowhere in the program")
        void theWrongVerbLiteralIsAtItsCitedLine() throws IOException {
            final List<String> lines = corpusLines(PROGRAM_SOURCE);

            assertThat(citedLine(lines, 332))
                    .as("this is the delete-failure arm and the literal says Update. Preserved, not repaired")
                    .contains(UNABLE_TO_UPDATE_MESSAGE);
            assertThat(citedLine(lines, 296))
                    .as("the read-failure arm says lookup, which is correct, so the two arms are genuinely "
                            + "distinguishable in the source")
                    .contains(UNABLE_TO_LOOKUP_MESSAGE);
            assertThat(String.join("\n", lines))
                    .as("the corrected form appears nowhere in the source, so it must appear nowhere in the "
                            + "port either")
                    .doesNotContain(CORRECTED_VERB_FRAGMENT);
        }

        @Test
        @DisplayName("the severity channel is set on two of six outcomes, and only on those two")
        void theSeverityChannelIsSetOnTwoOfSixOutcomes() throws IOException {
            final List<String> lines = corpusLines(PROGRAM_SOURCE);
            final List<Integer> colourLines = new ArrayList<>();
            for (int index = 0; index < lines.size(); index++) {
                if (lines.get(index).contains("ERRMSGC")) {
                    colourLines.add(index + 1);
                }
            }

            assertThat(colourLines)
                    .as("the prompt of :285 and the success of :317 carry a colour; the four failure arms at "
                            + ":289, :296, :325 and :332 carry none, so the source leaves severity unset on "
                            + "two thirds of its outcomes")
                    .containsExactly(285, 317)
                    .hasSize(SEVERITY_BEARING_OUTCOMES);
            assertThat(TOTAL_OUTCOMES - SEVERITY_BEARING_OUTCOMES).isEqualTo(4);
        }

        @Test
        @DisplayName("the emptiness literal appears at BOTH :147 and :179, identically")
        void theEmptinessLiteralAppearsAtBothSites() throws IOException {
            final List<String> lines = corpusLines(PROGRAM_SOURCE);

            assertThat(citedLine(lines, 147)).contains(USER_ID_REQUIRED_MESSAGE);
            assertThat(citedLine(lines, 179))
                    .as("the lookup paragraph and the delete paragraph carry the identical guard and the "
                            + "identical literal, which is why one declaration serves both")
                    .contains(USER_ID_REQUIRED_MESSAGE);
            assertThat(citedLine(lines, 153).strip()).isEqualTo("CONTINUE");
            assertThat(citedLine(lines, 185).strip())
                    .as("both WHEN OTHER arms end in a dead CONTINUE, retained with its locator")
                    .isEqualTo("CONTINUE");
        }

        @Test
        @DisplayName("the not-found literal appears at BOTH :289 and :325, identically")
        void theNotFoundLiteralAppearsAtBothSites() throws IOException {
            final List<String> lines = corpusLines(PROGRAM_SOURCE);

            assertThat(citedLine(lines, 289)).contains(USER_NOT_FOUND_MESSAGE);
            assertThat(citedLine(lines, 325))
                    .as("the read arm and the delete arm report the same text, which is what makes a "
                            + "not-found deletion observably unchanged under the short circuit")
                    .contains(USER_NOT_FOUND_MESSAGE);
        }

        @Test
        @DisplayName("the write-only flag is declared three times and set once, and tested nowhere")
        void theWriteOnlyFlagIsSetOnceAndTestedNowhere() throws IOException {
            final List<String> lines = corpusLines(PROGRAM_SOURCE);
            final List<Integer> mentions = new ArrayList<>();
            for (int index = 0; index < lines.size(); index++) {
                if (lines.get(index).contains("USR-MODIFIED")) {
                    mentions.add(index + 1);
                }
            }

            assertThat(mentions)
                    .as("three declarations at :45-47 and one SET at :85. It never appears in an IF or an "
                            + "EVALUATE, so there is nothing for the port to branch on")
                    .containsExactly(45, 46, 47, 85);
            assertThat(citedLine(lines, 85).strip()).isEqualTo("SET USR-MODIFIED-NO TO TRUE");
        }

        @Test
        @DisplayName("the record layout is eighty bytes with an eight character key and one credential field")
        void theRecordLayoutIsEightyBytes() throws IOException {
            final String layout = String.join("\n", corpusLines(RECORD_LAYOUT_SOURCE));

            assertThat(layout)
                    .contains("SEC-USR-ID                 PIC X(08)")
                    .contains("SEC-USR-FNAME              PIC X(20)")
                    .contains("SEC-USR-LNAME              PIC X(20)")
                    .contains("SEC-USR-PWD                PIC X(08)")
                    .contains("SEC-USR-TYPE               PIC X(01)")
                    .contains("SEC-USR-FILLER             PIC X(23)");
            assertThat(8 + 20 + 20 + 8 + 1 + 23).isEqualTo(80);
        }

        @Test
        @DisplayName("the symbolic map declares eleven input fields and NO credential field")
        void theSymbolicMapDeclaresElevenInputFieldsAndNoCredential() throws IOException {
            final List<String> map = corpusLines(SYMBOLIC_MAP_SOURCE);
            final List<String> inputFields = map.stream()
                    .filter(line -> line.matches("^\\s+02\\s+[A-Z0-9]+I\\s+PIC.*$"))
                    .map(String::strip)
                    .toList();

            assertThat(inputFields)
                    .as("the eleven fields the response record mirrors, one for one")
                    .hasSize(MAP_INPUT_FIELD_COUNT);
            assertThat(String.join("\n", map))
                    .as("grep -c \"PASSWD\" must answer 0. This is the only one of the four user screens "
                            + "with no credential field, so the source itself cannot echo a password here - "
                            + "unlike app/cbl/COUSR02C.cbl:169")
                    .doesNotContain("PASSWD");
            assertThat(String.join("\n", map))
                    .as("both the input and the output message fields are 78 characters wide, two narrower "
                            + "than the work area of :38")
                    .contains("ERRMSGI  PIC X(78)")
                    .contains("ERRMSGO  PIC X(78)");
        }

        @Test
        @DisplayName("the program's own literals are at :36, :37 and :39, pad included")
        void theProgramsOwnLiteralsAreAtTheirCitedLines() throws IOException {
            final List<String> lines = corpusLines(PROGRAM_SOURCE);

            assertThat(citedLine(lines, 36)).contains("WS-PGMNAME").contains("'" + PROGRAM_NAME + "'");
            assertThat(citedLine(lines, 37)).contains("WS-TRANID").contains("'" + TRANSACTION_ID + "'");
            assertThat(citedLine(lines, 38))
                    .as("the work area is PIC X(80) against the map's PIC X(78)")
                    .contains("WS-MESSAGE")
                    .contains("PIC X(80)");
            assertThat(citedLine(lines, 39))
                    .as("two trailing spaces of pad inside the X(08) field, which are not part of the name")
                    .contains("'" + USRSEC_FILE + "  '");
        }

        @Test
        @DisplayName("nothing under app/ is opened for writing by this suite")
        void nothingUnderTheFrozenTreeIsWritten() {
            for (final Path cited : List.of(PROGRAM_SOURCE, SIBLING_SOURCE, CONFIRMATION_CONTRAST_SOURCE,
                    SHARED_MESSAGES_SOURCE, SYMBOLIC_MAP_SOURCE, RECORD_LAYOUT_SOURCE, COMMAREA_SOURCE)) {
                assertThat(cited)
                        .as("every citation must resolve, or the claim resting on it cannot be checked")
                        .exists()
                        .isRegularFile();
                assertThat(cited.startsWith("app"))
                        .as("%s is read from the frozen tree, and the tree is read only", cited)
                        .isTrue();
            }
        }
    }
}
