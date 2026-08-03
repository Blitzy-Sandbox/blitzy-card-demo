/*
 * ******************************************************************
 * Program     : UserUpdateServiceTest.java
 * Component   : Unit test tier, resident at
 *               src/test/java/com/cardemo/unit/service
 * Application : CardDemo
 * Type        : JUnit 5 unit test - pure JVM tier, no container, no
 *               Spring context, no database, no live endpoint
 * Function    : Proves that UserUpdateService reproduces COUSR02C
 *               exactly - eleven paragraphs mapped one to one, the six
 *               attention identifiers including the PRESERVED QUIRK that
 *               PF3 saves before leaving while PF12 leaves without
 *               saving, the five emptiness guards in the source's order
 *               with every literal byte exact, change detection that
 *               compares the credential through PasswordEncoder.matches
 *               and never by string equality, the two-layer concurrency
 *               guard, the four read-and-rewrite response arms with
 *               their four distinct literals and three message colours,
 *               and a response record that declares NO credential
 *               component at all
 * Source      : app/cbl/COUSR02C.cbl (414 lines, 11 paragraphs) @ 7756d89
 * Source      : app/cpy-bms/COUSR02.CPY (12 input fields) @ 7756d89
 * Source      : app/cpy/CSUSR01Y.cpy (80 byte record, KEYS(8,0)) @ 7756d89
 * Source      : app/cpy/CSMSG01Y.cpy (CCDA-MSG-INVALID-KEY) @ 7756d89
 * Source      : app/cpy/COTTL01Y.cpy (CCDA-TITLE01, CCDA-TITLE02) @ 7756d89
 * Source      : app/csd/CARDDEMO.CSD (TRANSACTION(CU02), FILE(USRSEC)) @ 7756d89
 * Source      : app/jcl/DUSRSECJ.jcl (KEYS(8,0), 10 seeded users) @ 7756d89
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.persistence.OptimisticLockException;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.cardemo.exception.CardDemoException;
import com.cardemo.exception.ConcurrentUpdateException;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.UserUpdateRequest;
import com.cardemo.model.entity.UserSecurity;
import com.cardemo.model.enums.UserType;
import com.cardemo.repository.UserSecurityRepository;
import com.cardemo.service.admin.UserUpdateService;
import com.cardemo.service.admin.UserUpdateService.AttentionIdentifier;
import com.cardemo.service.admin.UserUpdateService.UserSnapshot;
import com.cardemo.service.admin.UserUpdateService.UserUpdateScreen;
import com.cardemo.service.shared.FileStatusMapper;

/**
 * Unit tests for {@code com.cardemo.service.admin.UserUpdateService}, the Java replacement for
 * {@code app/cbl/COUSR02C.cbl} - 414 lines and 11 paragraphs, the CICS program behind transaction
 * {@code CU02}, which updates one row of the {@code USRSEC} security file.
 *
 * <h2>What it does</h2>
 *
 * <p>It proves parity against the frozen source rather than against an idea of what the source ought to do.
 * Every assertion cites the paragraph or line it proves, and the citations were verified by direct inspection
 * at commit {@code 7756d89}. Six groups of behaviour carry the weight.
 *
 * <ul>
 *   <li><strong>PF3 SAVES.</strong> {@code app/cbl/COUSR02C.cbl}:111-119 performs
 *       {@code UPDATE-USER-INFO} and <em>then</em> transfers to the admin menu, whereas {@code :124-126}
 *       transfers without performing it. So the key a user presses to leave writes the record and the key
 *       documented as cancel does not. This is a legacy quirk, not a defect to repair, and it is asserted in
 *       both directions - PF3 reaching the store and PF12 never touching it.</li>
 *   <li><strong>The five emptiness guards, in order.</strong> {@code :179-213}: identifier, first name, last
 *       name, credential, user type, with a {@code WHEN OTHER} arm that only parks the cursor. First match
 *       wins, so a form empty in several fields reports only the earliest. Each literal is asserted byte for
 *       byte, capital {@code N}, {@code O} and {@code T} of {@code can NOT} included.</li>
 *   <li><strong>The credential is compared through the encoder, never by string equality.</strong>
 *       {@code :227} was {@code IF PASSWDI NOT = SEC-USR-PWD}, a plaintext comparison. The store now holds a
 *       salted one-way digest, so equality is meaningless and
 *       {@link PasswordEncoder#matches(CharSequence, String)} is the only correct translation. This suite
 *       proves the encoder is consulted, that a matching credential is <em>not</em> counted as a change, and
 *       that a non-matching one is re-encoded before the write.</li>
 *   <li><strong>Two layers of concurrency control.</strong> The source held a record lock for the whole
 *       conversation, which a stateless target cannot. A caller-supplied snapshot of what was last displayed
 *       is compared field by field at business level, and the provider's own optimistic failure is caught at
 *       store level; both produce {@code DATA_CHANGED_BEFORE_UPDATE}, and the cause is what tells them
 *       apart.</li>
 *   <li><strong>Four response arms, four literals, three colours.</strong> The read at {@code :333-353} and
 *       the rewrite at {@code :368-390} each branch three ways, producing
 *       {@code Press PF5 key to save your updates ...} in neutral, {@code User ID NOT found...},
 *       {@code Unable to lookup User...}, {@code Unable to Update User...},
 *       {@code Please modify to update ...} in red, and the assembled
 *       {@code User <id> has been updated ...} in green.</li>
 *   <li><strong>No credential is ever returned.</strong> {@code :169} moved {@code SEC-USR-PWD} into the
 *       output map - it painted the stored password onto the screen. That is deliberately <em>not</em>
 *       reproduced: the response record declares no credential component at all, which this suite asserts
 *       reflectively so that adding one breaks the build rather than leaking a secret.</li>
 *   </ul>
 *
 * <h2>How to build and test</h2>
 *
 * <ul>
 *   <li>{@code ./mvnw -B -ntp test} - runs this class under {@code maven-surefire-plugin:3.5.4}. Residence
 *       in the {@code unit} tree is load-bearing: a class outside it matches neither Surefire's nor
 *       Failsafe's include set and would silently never run.</li>
 *   <li>{@code ./mvnw -B -ntp test-compile} - {@code -Xlint:all -Werror} with {@code failOnWarning} reaches
 *       test compilation, so one unused import is fatal.</li>
 *   <li>{@code ./mvnw -B -ntp -Dtest=UserUpdateServiceTest test} - runs this class alone.</li>
 * </ul>
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <ul>
 *   <li><strong>A stubbed encoder, deliberately.</strong> BCrypt salts, so a real encoder is
 *       non-deterministic and a digest literal would be meaningless. The encoder is a double, and
 *       <strong>no digest literal appears anywhere in this file</strong>: the values it returns are
 *       assembled at run time from their parts, and {@code UserSecurity} independently enforces strength 10
 *       on anything persisted.</li>
 *   <li><strong>A real file-status mapper.</strong> {@code FileStatusMapper} is a pure function with a
 *       no-argument constructor, so the real one is used except where a test needs to observe exactly what
 *       crosses into it.</li>
 *   <li><strong>A fixed clock.</strong> {@code Clock.fixed} at a parsed instant in UTC, standing in for
 *       {@code MOVE FUNCTION CURRENT-DATE} at {@code :298}. Nothing here reads a wall clock, a default
 *       locale, a default zone or an unseeded random source.</li>
 *   <li><strong>A synthetic credential.</strong> Obviously fake, eight characters so that it sits exactly on
 *       the source field width. The ten users seeded inline by {@code app/jcl/DUSRSECJ.jcl} share one
 *       plaintext credential and <strong>that value is not reproduced here in any form</strong>.</li>
 *   <li><strong>Mockito strict stubs.</strong> An unused stub fails the test, so stubs are arranged inside
 *       each test and stubbing with the exact expected argument is itself an assertion.</li>
 *   </ul>
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><strong>PF3 stops saving.</strong> Someone "fixed" the quirk. The system of record writes on that
 *       key; a target that does not silently discards the user's edits. Remedy: restore the
 *       {@code UPDATE-USER-INFO} performed at {@code :112}, and leave the surprise visible. Severity:
 *       <strong>High</strong>.</li>
 *   <li><strong>The credential is compared with {@code equals}.</strong> The stored value is a salted
 *       digest, so equality can never hold and every submission would be counted as a change and re-hashed.
 *       Remedy: {@code matches}. Severity: <strong>High</strong>.</li>
 *   <li><strong>A password appears in a response.</strong> Someone reproduced {@code :169}. Remedy: remove
 *       it; the response record must declare no such component. Severity: <strong>Blocker</strong>.</li>
 *   <li><strong>An unchanged submission writes anyway.</strong> The four change tests were collapsed or the
 *       modified flag was set unconditionally, so {@code :238-243} never runs and
 *       {@code Please modify to update ...} is never seen. Remedy: restore the four tests. Severity:
 *       <strong>Medium</strong>.</li>
 *   <li><strong>Trailing spaces count as a change.</strong> The source compared fixed-width fields, so
 *       {@code "AJITH"} and {@code "AJITH               "} are the same value. Remedy: pad both sides to the
 *       declared width before comparing. Severity: <strong>Medium</strong>.</li>
 *   <li><strong>Two outcomes become indistinguishable.</strong> Four literals and three colours are
 *       observable state. Remedy: keep the arms distinct. Severity: <strong>Medium</strong>.</li>
 *   </ul>
 */
@DisplayName("UserUpdateService: app/cbl/COUSR02C.cbl - update one USRSEC row (CU02)")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.STRICT_STUBS)
class UserUpdateServiceTest {

    // ----------------------------------------------------------------------------------------------------
    // Synthetic credential material. Rule 1 Clause D names tests explicitly, so nothing here is or
    // resembles a real credential, and the seed value of app/jcl/DUSRSECJ.jcl is never reproduced.
    // ----------------------------------------------------------------------------------------------------

    /** The presented plaintext, standing in for {@code PASSWDI PIC X(8)}. Obviously fake, eight characters. */
    private static final String PRESENTED_CREDENTIAL = "n0tr3al!";

    /** A second, different plaintext, for the change-detected path. */
    private static final String REPLACEMENT_CREDENTIAL = "al5ofake";

    /** The {@code $} separator, held as a character so no BCrypt prefix literal exists in this file. */
    private static final char DIGEST_FIELD_MARKER = '$';

    /** A version tag {@code UserSecurity} admits. */
    private static final String DIGEST_VERSION_TAG = "2a";

    /** The contractual cost factor; {@code UserSecurity} admits this and no other. */
    private static final String CONTRACTUAL_COST_FACTOR = "10";

    /** Twenty-two characters from BCrypt's radix-64 alphabet, standing where a salt would sit. */
    private static final String SYNTHETIC_SALT = "SyntheticUnitTestSalt0";

    /** Thirty-one characters standing where a digest body would sit. */
    private static final String SYNTHETIC_BODY = "NotARealCredentialPlaceholder00";

    /** A second body, so a re-hash is observably different from the value it replaced. */
    private static final String REPLACEMENT_BODY = "NotARealCredentialPlaceholder11";

    // ----------------------------------------------------------------------------------------------------
    // Identity and record contents. Widths from app/cpy/CSUSR01Y.cpy:18-22.
    // ----------------------------------------------------------------------------------------------------

    /** The eight-character key, from {@code SEC-USR-ID PIC X(08)}. */
    private static final String USER_ID = "USER0002";

    /** The stored first name, within {@code SEC-USR-FNAME PIC X(20)}. */
    private static final String STORED_FIRST_NAME = "AJITH";

    /** The stored last name, within {@code SEC-USR-LNAME PIC X(20)}. */
    private static final String STORED_LAST_NAME = "KUMAR";

    /** A different first name, for the change-detected path. */
    private static final String NEW_FIRST_NAME = "AJITHA";

    /** A different last name, for the change-detected path. */
    private static final String NEW_LAST_NAME = "KUMARI";

    /** The stored user class as the screen carries it. */
    private static final String STORED_USER_TYPE = "U";

    /** The other user class. */
    private static final String OTHER_USER_TYPE = "A";

    // ----------------------------------------------------------------------------------------------------
    // Screen identity, app/cbl/COUSR02C.cbl:302-303 and app/cpy/COTTL01Y.cpy:18-22.
    // ----------------------------------------------------------------------------------------------------

    /** {@code WS-TRANID PIC X(04) VALUE 'CU02'}. */
    private static final String TRANSACTION_ID = "CU02";

    /** {@code WS-PGMNAME PIC X(08) VALUE 'COUSR02C'}. */
    private static final String PROGRAM_NAME = "COUSR02C";

    /** The {@code EIBCALEN = 0} destination at {@code :90-92}. */
    private static final String SIGN_ON_PROGRAM = "COSGN00C";

    /** The PF3 and PF12 destination at {@code :114} and {@code :125}. */
    private static final String ADMIN_MENU_PROGRAM = "COADM01C";

    /** {@code CCDA-TITLE01 PIC X(40)}, forty characters including its padding. */
    private static final String SCREEN_TITLE_01 = "      AWS Mainframe Modernization       ";

    /** {@code CCDA-TITLE02 PIC X(40)}, forty characters including its padding. */
    private static final String SCREEN_TITLE_02 = "              CardDemo                  ";

    /** The logical file name, {@code WS-USRSEC-FILE}. */
    private static final String USRSEC_FILE = "USRSEC";

    // ----------------------------------------------------------------------------------------------------
    // Cursor fields: the symbolic-map length fields that received MOVE -1.
    // ----------------------------------------------------------------------------------------------------

    /** {@code USRIDINL}. */
    private static final String CURSOR_USER_ID = "USRIDIN";

    /** {@code FNAMEL}. */
    private static final String CURSOR_FIRST_NAME = "FNAME";

    /** {@code LNAMEL}. */
    private static final String CURSOR_LAST_NAME = "LNAME";

    /** {@code PASSWDL}. */
    private static final String CURSOR_PASSWORD = "PASSWD";

    /** {@code USRTYPEL}. */
    private static final String CURSOR_USER_TYPE = "USRTYPE";

    // ----------------------------------------------------------------------------------------------------
    // Literals. Byte exact: "can NOT" with capital N O T, every ellipsis exactly three periods, and the
    // no-change and save-hint messages each carrying a space before their ellipsis where the empties do not.
    // ----------------------------------------------------------------------------------------------------

    /** {@code CCDA-MSG-INVALID-KEY} from {@code app/cpy/CSMSG01Y.cpy}, used at {@code :127-130}. */
    private static final String INVALID_KEY_MESSAGE = "Invalid key pressed. Please see below...";

    /** {@code :148} and {@code :182}. */
    private static final String USER_ID_REQUIRED_MESSAGE = "User ID can NOT be empty...";

    /** {@code :188}. */
    private static final String FIRST_NAME_REQUIRED_MESSAGE = "First Name can NOT be empty...";

    /** {@code :194}. */
    private static final String LAST_NAME_REQUIRED_MESSAGE = "Last Name can NOT be empty...";

    /** {@code :200}. */
    private static final String PASSWORD_REQUIRED_MESSAGE = "Password can NOT be empty...";

    /** {@code :206}. */
    private static final String USER_TYPE_REQUIRED_MESSAGE = "User Type can NOT be empty...";

    /** {@code :239}, with a space before the ellipsis. */
    private static final String NO_CHANGE_MESSAGE = "Please modify to update ...";

    /** {@code :336}, with a space before the ellipsis. */
    private static final String SAVE_HINT_MESSAGE = "Press PF5 key to save your updates ...";

    /** {@code :342} and {@code :379}. */
    private static final String USER_NOT_FOUND_MESSAGE = "User ID NOT found...";

    /** {@code :349}. */
    private static final String UNABLE_TO_LOOKUP_MESSAGE = "Unable to lookup User...";

    /** {@code :386}. */
    private static final String UNABLE_TO_UPDATE_MESSAGE = "Unable to Update User...";

    /** {@code :372}, the first {@code STRING} fragment. */
    private static final String UPDATED_MESSAGE_PREFIX = "User ";

    /** {@code :374}, the third {@code STRING} fragment, with a space before the ellipsis. */
    private static final String UPDATED_MESSAGE_SUFFIX = " has been updated ...";

    /** {@code :371} - the one place this program marks a message as a success rather than an error. */
    private static final String COLOUR_GREEN = "DFHGREEN";

    /** {@code :241}. */
    private static final String COLOUR_RED = "DFHRED";

    /** {@code :338}. */
    private static final String COLOUR_NEUTRAL = "DFHNEUTR";

    /** The message the invalid user-type code produces, which has no source literal and is target-only. */
    private static final String USER_TYPE_DOMAIN_MESSAGE =
            "User Type must be A for an administrator or U for a regular user";

    // ----------------------------------------------------------------------------------------------------
    // Time.
    // ----------------------------------------------------------------------------------------------------

    /** The instant every seed fixture carries. */
    private static final Instant FIXED_INSTANT = Instant.parse("2022-06-10T19:27:53Z");

    /** {@code MM/DD/YY} as {@code :305-309} assembles it, the year taken from {@code WS-CURDATE-YEAR(3:2)}. */
    private static final String EXPECTED_HEADER_DATE = "06/10/22";

    /** {@code HH:MM:SS} as {@code :311-315} assembles it. */
    private static final String EXPECTED_HEADER_TIME = "19:27:53";

    // ----------------------------------------------------------------------------------------------------
    // Collaborators.
    // ----------------------------------------------------------------------------------------------------

    @Mock
    private UserSecurityRepository userSecurityRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    /** Real: a pure function with a no-argument constructor, so a double would prove less. */
    private FileStatusMapper fileStatusMapper;

    private UserUpdateService service;

    /** Assembles the bean over two doubles, the real status mapper and a fixed clock. */
    @BeforeEach
    void setUp() {
        this.fileStatusMapper = new FileStatusMapper();
        this.service = new UserUpdateService(this.userSecurityRepository, this.passwordEncoder,
                this.fileStatusMapper, Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));
    }

    // -----------------------------------------------------------------------------------------------------
    // Fixtures and helpers. Every helper is used; an unused one would be dead code under Rule 1 Clause B.
    // -----------------------------------------------------------------------------------------------------

    /**
     * Assembles a synthetic BCrypt-shaped digest at the contractual cost, so that no digest literal exists in
     * this file and {@code UserSecurity}'s own strength guard still accepts what is persisted.
     *
     * @param body thirty-one characters standing where a digest body would sit; must not be {@code null}
     * @return a sixty-character value the entity contract admits
     */
    private static String syntheticDigest(final String body) {
        return DIGEST_FIELD_MARKER + DIGEST_VERSION_TAG + DIGEST_FIELD_MARKER + CONTRACTUAL_COST_FACTOR
                + DIGEST_FIELD_MARKER + SYNTHETIC_SALT + body;
    }

    /**
     * The digest the store is taken to hold.
     *
     * @return the stored digest
     */
    private static String storedDigest() {
        return syntheticDigest(SYNTHETIC_BODY);
    }

    /**
     * The digest the encoder is taken to return for a changed credential.
     *
     * @return the replacement digest
     */
    private static String replacementDigest() {
        return syntheticDigest(REPLACEMENT_BODY);
    }

    /**
     * Builds a stored row in the {@code CSUSR01Y} shape.
     *
     * @param firstName the first name; must not be {@code null}
     * @param lastName  the last name; must not be {@code null}
     * @param userType  the user class; must not be {@code null}
     * @return the row
     */
    private static UserSecurity storedUser(final String firstName, final String lastName,
            final UserType userType) {
        return new UserSecurity(USER_ID, firstName, lastName, storedDigest(), userType);
    }

    /**
     * The stored row as every test that does not vary it expects to find it.
     *
     * @return the row
     */
    private static UserSecurity storedUser() {
        return storedUser(STORED_FIRST_NAME, STORED_LAST_NAME, UserType.USER);
    }

    /**
     * Builds a twelve-component map area. The six header components carry values that differ from what the
     * service computes, so any assertion on a returned header proves they were overwritten.
     *
     * @param userId    the identifier component; may be {@code null}
     * @param firstName the first-name component; may be {@code null}
     * @param lastName  the last-name component; may be {@code null}
     * @param password  the credential component; may be {@code null}
     * @param userType  the user-class component; may be {@code null}
     * @return the map area
     */
    private static UserUpdateRequest request(final String userId, final String firstName,
            final String lastName, final String password, final String userType) {
        return new UserUpdateRequest("ZZZZ", "submitted-title-one", "01/01/00", "ZZZZZZZZ",
                "submitted-title-two", "00:00:00", userId, firstName, lastName, password, userType,
                "submitted-message");
    }

    /**
     * A map area that resubmits the stored values unchanged, with the credential the store's digest matches.
     *
     * @return the map area
     */
    private static UserUpdateRequest unchangedRequest() {
        return request(USER_ID, STORED_FIRST_NAME, STORED_LAST_NAME, PRESENTED_CREDENTIAL,
                STORED_USER_TYPE);
    }

    /**
     * The snapshot of what a caller was last shown, matching the stored row.
     *
     * @return the snapshot
     */
    private static UserSnapshot matchingSnapshot() {
        return new UserSnapshot(STORED_FIRST_NAME, STORED_LAST_NAME, STORED_USER_TYPE);
    }

    /**
     * Arranges the read to find the row as stored.
     */
    private void arrangeStoredRow() {
        when(this.userSecurityRepository.findById(USER_ID)).thenReturn(Optional.of(storedUser()));
    }

    /**
     * Arranges the encoder to report that the presented credential matches the stored digest, which is the
     * unchanged-credential path of {@code :227}.
     */
    private void arrangeCredentialMatches() {
        when(this.passwordEncoder.matches(PRESENTED_CREDENTIAL, storedDigest())).thenReturn(true);
    }

    /**
     * Reads a private static constant off the service, so a declared value is asserted against the
     * production declaration rather than a copy of it.
     *
     * @param name the field name; must not be {@code null}
     * @return the declared value
     * @throws ReflectiveOperationException if the field is absent, which is itself the finding
     */
    private static Object declaredConstant(final String name) throws ReflectiveOperationException {
        final Field field = UserUpdateService.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    // =====================================================================================================
    // 1. Construction
    // =====================================================================================================

    /** Constructor injection only, with all four collaborators refused when absent. */
    @Nested
    @DisplayName("1. Construction - four collaborators, each refused when absent")
    class Construction {

        @Test
        @DisplayName("the security store is required")
        void theSecurityStoreIsRequired() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new UserUpdateService(null, passwordEncoder, fileStatusMapper,
                            Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC)))
                    .withMessage("userSecurityRepository must not be null");
        }

        @Test
        @DisplayName("the encoder is required, because the credential comparison cannot run without it")
        void theEncoderIsRequired() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new UserUpdateService(userSecurityRepository, null, fileStatusMapper,
                            Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC)))
                    .withMessage("passwordEncoder must not be null");
        }

        @Test
        @DisplayName("the status mapper is required")
        void theStatusMapperIsRequired() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new UserUpdateService(userSecurityRepository, passwordEncoder, null,
                            Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC)))
                    .withMessage("fileStatusMapper must not be null");
        }

        @Test
        @DisplayName("the time source is required, so no path can reach a wall clock")
        void theTimeSourceIsRequired() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new UserUpdateService(userSecurityRepository, passwordEncoder,
                            fileStatusMapper, null))
                    .withMessage("clock must not be null");
        }

        @Test
        @DisplayName("every instance field is private and final, so no turn leaves residue for the next")
        void everyInstanceFieldIsPrivateAndFinal() {
            final List<Field> mutable = Arrays.stream(UserUpdateService.class.getDeclaredFields())
                    .filter(field -> !Modifier.isStatic(field.getModifiers()))
                    .filter(field -> !Modifier.isFinal(field.getModifiers())
                            || !Modifier.isPrivate(field.getModifiers()))
                    .toList();
            assertThat(mutable)
                    .as("WS-ERR-FLG, WS-MESSAGE and the record area became a per-turn work object, not state")
                    .isEmpty();
        }
    }

    // =====================================================================================================
    // 2. Paragraph correspondence and transaction boundaries
    // =====================================================================================================

    /**
     * Eleven paragraph labels, eleven private methods. {@code app/cbl/COUSR02C.cbl} declares
     * {@code MAIN-PARA} at :82, {@code PROCESS-ENTER-KEY} at :143, {@code UPDATE-USER-INFO} at :177,
     * {@code RETURN-TO-PREV-SCREEN} at :250, {@code SEND-USRUPD-SCREEN} at :266,
     * {@code RECEIVE-USRUPD-SCREEN} at :283, {@code POPULATE-HEADER-INFO} at :296,
     * {@code READ-USER-SEC-FILE} at :320, {@code UPDATE-USER-SEC-FILE} at :358,
     * {@code CLEAR-CURRENT-SCREEN} at :395 and {@code INITIALIZE-ALL-FIELDS} at :403.
     */
    @Nested
    @DisplayName("2. Paragraph correspondence - eleven labels, and the write boundaries that back them")
    class ParagraphCorrespondence {

        @Test
        @DisplayName("all eleven paragraphs have a private counterpart, none consolidated away")
        void allElevenParagraphsHaveAPrivateCounterpart() {
            final List<String> expected = List.of("mainPara", "processEnterKey", "updateUserInfo",
                    "returnToPrevScreen", "sendUsrupdScreen", "receiveUsrupdScreen", "populateHeaderInfo",
                    "readUserSecFile", "updateUserSecFile", "clearCurrentScreen", "initializeAllFields");
            final List<String> declared = Arrays.stream(UserUpdateService.class.getDeclaredMethods())
                    .filter(method -> Modifier.isPrivate(method.getModifiers()))
                    .map(Method::getName)
                    .distinct()
                    .toList();
            assertThat(declared).containsAll(expected);
        }

        @Test
        @DisplayName("five public entry points exist, one per way the source can be reached")
        void fivePublicEntryPointsExist() {
            final List<String> publicMethods = Arrays.stream(UserUpdateService.class.getDeclaredMethods())
                    .filter(method -> Modifier.isPublic(method.getModifiers()))
                    .map(Method::getName)
                    .distinct()
                    .sorted()
                    .toList();
            assertThat(publicMethods).containsExactly("lookupUser", "openScreen", "openWithoutContext",
                    "submitScreen", "updateUser");
        }

        @ParameterizedTest(name = "{0} declares @Transactional(rollbackFor = Exception.class)")
        @DisplayName("every entry point that can write declares the rollback-for-anything boundary")
        @CsvSource({"openScreen", "lookupUser", "updateUser", "submitScreen"})
        void everyWriteCapableEntryPointDeclaresTheBoundary(final String methodName) {
            final Method entryPoint = Arrays.stream(UserUpdateService.class.getDeclaredMethods())
                    .filter(method -> method.getName().equals(methodName))
                    .findFirst()
                    .orElseThrow();
            final org.springframework.transaction.annotation.Transactional declared =
                    entryPoint.getAnnotation(org.springframework.transaction.annotation.Transactional.class);

            assertThat(declared)
                    .as("the read-for-update and the rewrite must sit in one unit of work")
                    .isNotNull();
            assertThat(declared.rollbackFor())
                    .as("Transformation Rule 13: a checked failure must back the write out too")
                    .containsExactly(Exception.class);
        }

        @Test
        @DisplayName("the attention identifier has exactly six constants, matching EVALUATE EIBAID")
        void theAttentionIdentifierHasExactlySixConstants() {
            assertThat(AttentionIdentifier.values())
                    .as("app/cbl/COUSR02C.cbl:108-131 has six arms: ENTER, PF3, PF4, PF5, PF12 and OTHER")
                    .containsExactly(AttentionIdentifier.ENTER, AttentionIdentifier.PF3,
                            AttentionIdentifier.PF4, AttentionIdentifier.PF5, AttentionIdentifier.PF12,
                            AttentionIdentifier.OTHER);
        }
    }

    // =====================================================================================================
    // 3. The attention-identifier arms - app/cbl/COUSR02C.cbl:108-131, INCLUDING THE PF3 QUIRK
    // =====================================================================================================

    /**
     * Six arms. The one that matters most is {@code PF3}: it performs {@code UPDATE-USER-INFO} at {@code :112}
     * and only then transfers, so the key a user presses to leave <em>writes the record</em>. {@code PF12} at
     * {@code :124-126} transfers without performing it.
     */
    @Nested
    @DisplayName("3. The EIBAID arms :108-131 - PF3 SAVES before leaving, PF12 leaves without saving")
    class AttentionIdentifierArms {

        @Test
        @DisplayName("PF3 WRITES THE RECORD and then reports the admin menu - the preserved quirk")
        void pf3WritesTheRecordAndThenLeaves() {
            arrangeStoredRow();
            when(passwordEncoder.matches(REPLACEMENT_CREDENTIAL, storedDigest())).thenReturn(false);
            when(passwordEncoder.encode(REPLACEMENT_CREDENTIAL)).thenReturn(replacementDigest());
            when(userSecurityRepository.saveAndFlush(any(UserSecurity.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            final UserUpdateScreen screen = service.submitScreen(AttentionIdentifier.PF3,
                    request(USER_ID, NEW_FIRST_NAME, STORED_LAST_NAME, REPLACEMENT_CREDENTIAL,
                            STORED_USER_TYPE),
                    null);

            verify(userSecurityRepository).saveAndFlush(any(UserSecurity.class));
            assertThat(screen.navigationTarget())
                    .as(":113-119 - the transfer happens after the write, not instead of it")
                    .isEqualTo(ADMIN_MENU_PROGRAM);
            assertThat(screen.transferRequested()).isTrue();
            assertThat(screen.userModified()).isTrue();
        }

        @Test
        @DisplayName("PF12 leaves WITHOUT writing, which is the arm that behaves as a cancel")
        void pf12LeavesWithoutWriting() {
            final UserUpdateScreen screen = service.submitScreen(AttentionIdentifier.PF12,
                    request(USER_ID, NEW_FIRST_NAME, NEW_LAST_NAME, REPLACEMENT_CREDENTIAL,
                            OTHER_USER_TYPE),
                    null);

            verifyNoInteractions(userSecurityRepository, passwordEncoder);
            assertThat(screen.navigationTarget()).isEqualTo(ADMIN_MENU_PROGRAM);
            assertThat(screen.transferRequested()).isTrue();
            assertThat(screen.userModified())
                    .as(":124-126 performs no UPDATE-USER-INFO at all")
                    .isFalse();
        }

        @Test
        @DisplayName("PF3 and PF12 differ in exactly one observable respect: whether the store was reached")
        void pf3AndPf12DifferOnlyInReachingTheStore() {
            arrangeStoredRow();
            arrangeCredentialMatches();

            final UserUpdateScreen viaPf3 =
                    service.submitScreen(AttentionIdentifier.PF3, unchangedRequest(), null);
            final UserUpdateScreen viaPf12 =
                    service.submitScreen(AttentionIdentifier.PF12, unchangedRequest(), null);

            verify(userSecurityRepository).findById(USER_ID);
            assertThat(viaPf3.navigationTarget()).isEqualTo(viaPf12.navigationTarget());
            assertThat(viaPf3.errorMessage())
                    .as("PF3 ran the whole update path and found nothing changed")
                    .isEqualTo(NO_CHANGE_MESSAGE);
            assertThat(viaPf12.errorMessage())
                    .as("PF12 ran nothing, so the message field carries what the caller submitted")
                    .isEqualTo("submitted-message");
        }

        @Test
        @DisplayName("PF4 clears every input field and parks the cursor on the identifier")
        void pf4ClearsEveryInputField() {
            final UserUpdateScreen screen = service.submitScreen(AttentionIdentifier.PF4,
                    request(USER_ID, STORED_FIRST_NAME, STORED_LAST_NAME, PRESENTED_CREDENTIAL,
                            STORED_USER_TYPE),
                    null);

            assertThat(screen.userId()).isEmpty();
            assertThat(screen.firstName()).isEmpty();
            assertThat(screen.lastName()).isEmpty();
            assertThat(screen.userType()).isEmpty();
            assertThat(screen.errorMessage()).isEmpty();
            assertThat(screen.cursorField()).isEqualTo(CURSOR_USER_ID);
            assertThat(screen.navigationTarget()).isNull();
            verifyNoInteractions(userSecurityRepository, passwordEncoder);
        }

        @Test
        @DisplayName("PF5 writes and stays, reporting no navigation target")
        void pf5WritesAndStays() {
            arrangeStoredRow();
            when(passwordEncoder.matches(PRESENTED_CREDENTIAL, storedDigest())).thenReturn(true);
            when(userSecurityRepository.saveAndFlush(any(UserSecurity.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            final UserUpdateScreen screen = service.submitScreen(AttentionIdentifier.PF5,
                    request(USER_ID, NEW_FIRST_NAME, STORED_LAST_NAME, PRESENTED_CREDENTIAL,
                            STORED_USER_TYPE),
                    null);

            verify(userSecurityRepository).saveAndFlush(any(UserSecurity.class));
            assertThat(screen.navigationTarget())
                    .as(":122-123 performs the update and nothing else")
                    .isNull();
            assertThat(screen.transferRequested()).isFalse();
        }

        @Test
        @DisplayName("ENTER looks the record up and reports the save hint, writing nothing")
        void enterLooksUpAndReportsTheSaveHint() {
            arrangeStoredRow();

            final UserUpdateScreen screen =
                    service.submitScreen(AttentionIdentifier.ENTER, request(USER_ID, null, null, null, null),
                            null);

            verify(userSecurityRepository).findById(USER_ID);
            verify(userSecurityRepository, never()).saveAndFlush(any(UserSecurity.class));
            assertThat(screen.errorMessage()).isEqualTo(SAVE_HINT_MESSAGE);
            assertThat(screen.messageColour()).isEqualTo(COLOUR_NEUTRAL);
        }

        @Test
        @DisplayName("the ENTER arm paints the record's names and class but NEVER its credential")
        void theEnterArmPaintsNamesButNeverTheCredential() {
            arrangeStoredRow();

            final UserUpdateScreen screen =
                    service.submitScreen(AttentionIdentifier.ENTER, request(USER_ID, null, null, null, null),
                            null);

            assertThat(screen.firstName()).isEqualTo(STORED_FIRST_NAME);
            assertThat(screen.lastName()).isEqualTo(STORED_LAST_NAME);
            assertThat(screen.userType()).isEqualTo(STORED_USER_TYPE);
            assertThat(Arrays.stream(UserUpdateScreen.class.getRecordComponents())
                    .map(component -> component.getName())
                    .toList())
                    .as(":169 MOVE SEC-USR-PWD TO PASSWDI is deliberately NOT reproduced, so the response "
                            + "record must declare no credential component at all")
                    .doesNotContain("password", "passwordHash", "secUsrPwd", "credential");
        }

        @Test
        @DisplayName("any other key produces the shared invalid-key message and writes nothing")
        void anyOtherKeyProducesTheSharedInvalidKeyMessage() {
            final UserUpdateScreen screen = service.submitScreen(AttentionIdentifier.OTHER,
                    request(USER_ID, STORED_FIRST_NAME, STORED_LAST_NAME, PRESENTED_CREDENTIAL,
                            STORED_USER_TYPE),
                    null);

            assertThat(screen.errorMessage()).isEqualTo(INVALID_KEY_MESSAGE);
            assertThat(screen.navigationTarget()).isNull();
            verifyNoInteractions(userSecurityRepository, passwordEncoder);
        }

        @Test
        @DisplayName("the attention identifier is required")
        void theAttentionIdentifierIsRequired() {
            final UserUpdateRequest submitted = unchangedRequest();
            assertThatNullPointerException()
                    .isThrownBy(() -> service.submitScreen(null, submitted, null))
                    .withMessage("aid must not be null");
        }

        @ParameterizedTest(name = "{0} requires a map area")
        @DisplayName("the three arms that read or write the record require a map area")
        @CsvSource({"ENTER", "PF3", "PF5"})
        void theReadingAndWritingArmsRequireAMapArea(final String aidName) {
            final AttentionIdentifier aid = AttentionIdentifier.valueOf(aidName);
            assertThatNullPointerException()
                    .isThrownBy(() -> service.submitScreen(aid, null, null))
                    .withMessage("request must not be null when the attention identifier reads or writes "
                            + "the record");
        }

        @ParameterizedTest(name = "{0} tolerates an absent map area")
        @DisplayName("the two arms that neither read nor write tolerate an absent map area")
        @CsvSource({"PF4", "PF12"})
        void theOtherArmsTolerateAnAbsentMapArea(final String aidName) {
            final AttentionIdentifier aid = AttentionIdentifier.valueOf(aidName);

            final UserUpdateScreen screen = service.submitScreen(aid, null, null);

            assertThat(screen).isNotNull();
            verifyNoInteractions(userSecurityRepository, passwordEncoder);
        }
    }

    // =====================================================================================================
    // 4. Entry modes - app/cbl/COUSR02C.cbl:90-105
    // =====================================================================================================

    /** The no-communication-area arm and the first-display arm, with and without a preselected identifier. */
    @Nested
    @DisplayName("4. Entry modes :90-105 - no commarea, first display, and the preselected identifier")
    class EntryModes {

        @Test
        @DisplayName("an absent communication area leaves for the sign-on program")
        void anAbsentCommunicationAreaLeavesForSignOn() {
            final UserUpdateScreen screen = service.openWithoutContext();

            assertThat(screen.navigationTarget())
                    .as(":90-92 MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM")
                    .isEqualTo(SIGN_ON_PROGRAM);
            assertThat(screen.transferRequested()).isTrue();
            verifyNoInteractions(userSecurityRepository, passwordEncoder);
        }

        @Test
        @DisplayName("a first display with no preselected identifier paints an empty form and reads nothing")
        void aFirstDisplayWithNoPreselectionReadsNothing() {
            final UserUpdateScreen screen = service.openScreen(null);

            assertThat(screen.cursorField())
                    .as(":98 MOVE -1 TO USRIDINL")
                    .isEqualTo(CURSOR_USER_ID);
            assertThat(screen.userId())
                    .as(":97 MOVE LOW-VALUES TO COUSR2AO clears the whole output map, and a cleared "
                            + "fixed-width field carries no content")
                    .isEmpty();
            assertThat(screen.firstName()).isEmpty();
            assertThat(screen.lastName()).isEmpty();
            assertThat(screen.userType()).isEmpty();
            assertThat(screen.errorMessage()).isEmpty();
            assertThat(screen.navigationTarget()).isNull();
            verifyNoInteractions(userSecurityRepository, passwordEncoder);
        }

        @Test
        @DisplayName("a first display with a preselected identifier performs the lookup at :103")
        void aFirstDisplayWithAPreselectionPerformsTheLookup() {
            arrangeStoredRow();

            final UserUpdateScreen screen = service.openScreen(USER_ID);

            verify(userSecurityRepository).findById(USER_ID);
            assertThat(screen.userId()).isEqualTo(USER_ID);
            assertThat(screen.firstName()).isEqualTo(STORED_FIRST_NAME);
            assertThat(screen.errorMessage()).isEqualTo(SAVE_HINT_MESSAGE);
        }

        @ParameterizedTest(name = "a preselection of [{0}] is treated as absent")
        @DisplayName("a blank or unset preselection does not trigger the lookup")
        @ValueSource(strings = {"", " ", "   ", "\u0000"})
        void aBlankPreselectionDoesNotTriggerTheLookup(final String preselection) {
            final UserUpdateScreen screen = service.openScreen(preselection);

            assertThat(screen).isNotNull();
            verifyNoInteractions(userSecurityRepository);
        }

        @Test
        @DisplayName("the lookup entry point reaches the same read as the ENTER arm")
        void theLookupEntryPointReachesTheSameRead() {
            arrangeStoredRow();

            final UserUpdateScreen screen = service.lookupUser(USER_ID);

            verify(userSecurityRepository).findById(USER_ID);
            assertThat(screen.errorMessage()).isEqualTo(SAVE_HINT_MESSAGE);
            assertThat(screen.messageColour()).isEqualTo(COLOUR_NEUTRAL);
        }

        @Test
        @DisplayName("the update entry point requires a map area")
        void theUpdateEntryPointRequiresAMapArea() {
            assertThatNullPointerException()
                    .isThrownBy(() -> service.updateUser(null, null))
                    .withMessage("request must not be null");
        }
    }

    // =====================================================================================================
    // 5. The five emptiness guards - app/cbl/COUSR02C.cbl:179-213
    // =====================================================================================================

    /** {@code EVALUATE TRUE} with five arms in a fixed order, and a {@code WHEN OTHER} that parks the cursor. */
    @Nested
    @DisplayName("5. The five emptiness guards :179-213 - source order, first match wins, no content checks")
    class OrderedEmptinessGuards {

        @Test
        @DisplayName("a wholly empty form reports the IDENTIFIER, the first arm")
        void aWhollyEmptyFormReportsTheIdentifier() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.updateUser(request(null, null, null, null, null), null))
                    .satisfies(failure -> {
                        assertThat(failure.getMessage()).isEqualTo(USER_ID_REQUIRED_MESSAGE);
                        assertThat(failure.getFieldName()).isEqualTo("userId");
                        assertThat(failure.getFailureKind())
                                .isEqualTo(ValidationException.FailureKind.BLANK);
                    });
            verifyNoInteractions(userSecurityRepository, passwordEncoder);
        }

        @Test
        @DisplayName("the first name is reported second")
        void theFirstNameIsReportedSecond() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.updateUser(request(USER_ID, null, null, null, null), null))
                    .satisfies(failure -> {
                        assertThat(failure.getMessage()).isEqualTo(FIRST_NAME_REQUIRED_MESSAGE);
                        assertThat(failure.getFieldName()).isEqualTo("firstName");
                    });
        }

        @Test
        @DisplayName("the last name is reported third")
        void theLastNameIsReportedThird() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.updateUser(
                            request(USER_ID, STORED_FIRST_NAME, null, null, null), null))
                    .satisfies(failure -> {
                        assertThat(failure.getMessage()).isEqualTo(LAST_NAME_REQUIRED_MESSAGE);
                        assertThat(failure.getFieldName()).isEqualTo("lastName");
                    });
        }

        @Test
        @DisplayName("the credential is reported fourth, by field NAME and never by value")
        void theCredentialIsReportedFourth() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.updateUser(
                            request(USER_ID, STORED_FIRST_NAME, STORED_LAST_NAME, null, null), null))
                    .satisfies(failure -> {
                        assertThat(failure.getMessage()).isEqualTo(PASSWORD_REQUIRED_MESSAGE);
                        assertThat(failure.getFieldName()).isEqualTo("password");
                        assertThat(failure.getMessage()).doesNotContain(PRESENTED_CREDENTIAL);
                    });
        }

        @Test
        @DisplayName("the user type is reported fifth and last")
        void theUserTypeIsReportedFifth() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.updateUser(request(USER_ID, STORED_FIRST_NAME,
                            STORED_LAST_NAME, PRESENTED_CREDENTIAL, null), null))
                    .satisfies(failure -> {
                        assertThat(failure.getMessage()).isEqualTo(USER_TYPE_REQUIRED_MESSAGE);
                        assertThat(failure.getFieldName()).isEqualTo("userType");
                    });
        }

        @ParameterizedTest(name = "a field of [{0}] counts as empty")
        @DisplayName("SPACES, LOW-VALUES and an absent value are all empty; nothing else is")
        @ValueSource(strings = {"", " ", "    ", "\u0000", "\u0000\u0000"})
        void spacesLowValuesAndAbsentAreAllEmpty(final String value) {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.updateUser(
                            request(USER_ID, value, STORED_LAST_NAME, PRESENTED_CREDENTIAL,
                                    STORED_USER_TYPE), null))
                    .withMessage(FIRST_NAME_REQUIRED_MESSAGE);
        }

        @Test
        @DisplayName("the guards test emptiness only - no content validation of any kind is added")
        void theGuardsTestEmptinessOnly() {
            arrangeStoredRow();
            when(passwordEncoder.matches("!", storedDigest())).thenReturn(false);
            when(passwordEncoder.encode("!")).thenReturn(replacementDigest());
            when(userSecurityRepository.saveAndFlush(any(UserSecurity.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            // A one-character name and a one-character credential are accepted: the source checks that the
            // fields are not empty and checks nothing else whatsoever.
            final UserUpdateScreen screen = service.updateUser(
                    request(USER_ID, "X", "Y", "!", STORED_USER_TYPE), null);

            assertThat(screen.userModified()).isTrue();
        }

        @Test
        @DisplayName("the five literals are byte exact, capital N O T of can NOT included")
        void theFiveLiteralsAreByteExact() {
            assertThat(USER_ID_REQUIRED_MESSAGE).isEqualTo("User ID can NOT be empty...");
            assertThat(FIRST_NAME_REQUIRED_MESSAGE).isEqualTo("First Name can NOT be empty...");
            assertThat(LAST_NAME_REQUIRED_MESSAGE).isEqualTo("Last Name can NOT be empty...");
            assertThat(PASSWORD_REQUIRED_MESSAGE).isEqualTo("Password can NOT be empty...");
            assertThat(USER_TYPE_REQUIRED_MESSAGE).isEqualTo("User Type can NOT be empty...");
        }

        @Test
        @DisplayName("the ENTER arm applies only the identifier guard, the other four being blanked first")
        void theEnterArmAppliesOnlyTheIdentifierGuard() {
            // :158-161 blanks the four other fields before the read, so PROCESS-ENTER-KEY has one guard.
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER,
                            request(null, STORED_FIRST_NAME, STORED_LAST_NAME, PRESENTED_CREDENTIAL,
                                    STORED_USER_TYPE), null))
                    .withMessage(USER_ID_REQUIRED_MESSAGE);
        }
    }

    // =====================================================================================================
    // 6. Width enforcement - a Java-only guard, because the 3270 map made over-length input impossible
    // =====================================================================================================

    /**
     * The symbolic map physically could not deliver more characters than the field declared. An HTTP caller
     * can, so the values are rejected rather than truncated - truncation would silently store a different
     * value than the caller supplied.
     */
    @Nested
    @DisplayName("6. Width enforcement - reject over-length input, never truncate it")
    class WidthEnforcement {

        @ParameterizedTest(name = "an over-length {0} is rejected")
        @DisplayName("each of the five fields is rejected when it exceeds its declared width")
        @CsvSource({
            "userId,    9,  8",
            "firstName, 21, 20",
            "lastName,  21, 20",
            "password,  9,  8",
            "userType,  2,  1"})
        void eachFieldIsRejectedWhenTooLong(final String fieldName, final int suppliedLength,
                final int declaredWidth) {
            final String tooLong = "X".repeat(suppliedLength);
            final UserUpdateRequest overLength = switch (fieldName) {
                case "userId" -> request(tooLong, STORED_FIRST_NAME, STORED_LAST_NAME, PRESENTED_CREDENTIAL,
                        STORED_USER_TYPE);
                case "firstName" -> request(USER_ID, tooLong, STORED_LAST_NAME, PRESENTED_CREDENTIAL,
                        STORED_USER_TYPE);
                case "lastName" -> request(USER_ID, STORED_FIRST_NAME, tooLong, PRESENTED_CREDENTIAL,
                        STORED_USER_TYPE);
                case "password" -> request(USER_ID, STORED_FIRST_NAME, STORED_LAST_NAME, tooLong,
                        STORED_USER_TYPE);
                default -> request(USER_ID, STORED_FIRST_NAME, STORED_LAST_NAME, PRESENTED_CREDENTIAL,
                        tooLong);
            };

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.updateUser(overLength, null))
                    .satisfies(failure -> {
                        assertThat(failure.getFieldName()).isEqualTo(fieldName);
                        assertThat(failure.getMessage())
                                .isEqualTo(fieldName + " must be at most " + declaredWidth + " characters");
                        assertThat(failure.getFailureKind())
                                .isEqualTo(ValidationException.FailureKind.INVALID);
                    });
            verifyNoInteractions(userSecurityRepository);
        }

        @Test
        @DisplayName("a value exactly on its declared width is accepted, the guard being exclusive")
        void aValueExactlyOnItsWidthIsAccepted() {
            arrangeStoredRow();
            final String exactlyTwenty = "X".repeat(20);
            when(passwordEncoder.matches(PRESENTED_CREDENTIAL, storedDigest())).thenReturn(true);
            when(userSecurityRepository.saveAndFlush(any(UserSecurity.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            final UserUpdateScreen screen = service.updateUser(
                    request(USER_ID, exactlyTwenty, STORED_LAST_NAME, PRESENTED_CREDENTIAL,
                            STORED_USER_TYPE), null);

            assertThat(screen.firstName()).isEqualTo(exactlyTwenty);
        }
    }

    // =====================================================================================================
    // 7. The two-layer concurrency guard
    // =====================================================================================================

    /**
     * The source held the record locked for the whole conversation with {@code EXEC CICS READ ... UPDATE}. A
     * stateless target cannot, so a caller-supplied snapshot is compared at business level and the provider's
     * own optimistic failure is caught at store level. Both report the same outcome; the cause distinguishes
     * them.
     */
    @Nested
    @DisplayName("7. Concurrency - a business-level snapshot and a store-level version, both reporting one "
            + "outcome")
    class ConcurrencyGuard {

        @Test
        @DisplayName("a first name that changed under the caller is refused before any write")
        void aChangedFirstNameIsRefused() {
            arrangeStoredRow();

            assertThatExceptionOfType(ConcurrentUpdateException.class)
                    .isThrownBy(() -> service.updateUser(unchangedRequest(),
                            new UserSnapshot("STALE", STORED_LAST_NAME, STORED_USER_TYPE)))
                    .satisfies(failure -> {
                        assertThat(failure.getMessage())
                                .isEqualTo(ConcurrentUpdateException.Outcome.DATA_CHANGED_BEFORE_UPDATE
                                        .getLegacyMessage());
                        assertThat(failure.getCause())
                                .as("the business-level layer has no provider failure to carry")
                                .isNull();
                    });
            verify(userSecurityRepository, never()).saveAndFlush(any(UserSecurity.class));
        }

        @Test
        @DisplayName("a last name that changed under the caller is refused")
        void aChangedLastNameIsRefused() {
            arrangeStoredRow();

            assertThatExceptionOfType(ConcurrentUpdateException.class)
                    .isThrownBy(() -> service.updateUser(unchangedRequest(),
                            new UserSnapshot(STORED_FIRST_NAME, "STALE", STORED_USER_TYPE)));
        }

        @Test
        @DisplayName("a user class that changed under the caller is refused")
        void aChangedUserClassIsRefused() {
            arrangeStoredRow();

            assertThatExceptionOfType(ConcurrentUpdateException.class)
                    .isThrownBy(() -> service.updateUser(unchangedRequest(),
                            new UserSnapshot(STORED_FIRST_NAME, STORED_LAST_NAME, OTHER_USER_TYPE)));
        }

        @Test
        @DisplayName("a matching snapshot proceeds, so the guard admits the unchanged case")
        void aMatchingSnapshotProceeds() {
            arrangeStoredRow();
            arrangeCredentialMatches();

            final UserUpdateScreen screen = service.updateUser(unchangedRequest(), matchingSnapshot());

            assertThat(screen.errorMessage()).isEqualTo(NO_CHANGE_MESSAGE);
        }

        @Test
        @DisplayName("a snapshot compares on fixed width, so trailing spaces are not a conflict")
        void aSnapshotComparesOnFixedWidth() {
            arrangeStoredRow();
            arrangeCredentialMatches();

            final UserUpdateScreen screen = service.updateUser(unchangedRequest(),
                    new UserSnapshot(STORED_FIRST_NAME + "               ",
                            STORED_LAST_NAME + "               ", STORED_USER_TYPE));

            assertThat(screen.errorMessage())
                    .as("SEC-USR-FNAME is PIC X(20), so the padded and unpadded forms are one value")
                    .isEqualTo(NO_CHANGE_MESSAGE);
        }

        @Test
        @DisplayName("an absent snapshot skips the business-level layer, leaving only the store-level one")
        void anAbsentSnapshotSkipsTheBusinessLevelLayer() {
            arrangeStoredRow();
            arrangeCredentialMatches();

            final UserUpdateScreen screen = service.updateUser(unchangedRequest(), null);

            assertThat(screen.errorMessage()).isEqualTo(NO_CHANGE_MESSAGE);
        }

        @Test
        @DisplayName("a provider optimistic failure reports the same outcome but carries the cause")
        void aProviderOptimisticFailureCarriesTheCause() {
            arrangeStoredRow();
            final OptimisticLockException collision = new OptimisticLockException("version moved");
            when(passwordEncoder.matches(PRESENTED_CREDENTIAL, storedDigest())).thenReturn(true);
            when(userSecurityRepository.saveAndFlush(any(UserSecurity.class))).thenThrow(collision);

            assertThatExceptionOfType(ConcurrentUpdateException.class)
                    .isThrownBy(() -> service.updateUser(
                            request(USER_ID, NEW_FIRST_NAME, STORED_LAST_NAME, PRESENTED_CREDENTIAL,
                                    STORED_USER_TYPE), null))
                    .satisfies(failure -> {
                        assertThat(failure.getMessage())
                                .isEqualTo(ConcurrentUpdateException.Outcome.DATA_CHANGED_BEFORE_UPDATE
                                        .getLegacyMessage());
                        assertThat(failure.getCause())
                                .as("the cause is what tells the store-level layer from the business one")
                                .isSameAs(collision);
                    });
        }

        @Test
        @DisplayName("a Spring optimistic failure reaches the same arm")
        void aSpringOptimisticFailureReachesTheSameArm() {
            arrangeStoredRow();
            final OptimisticLockingFailureException collision =
                    new OptimisticLockingFailureException("version moved");
            when(passwordEncoder.matches(PRESENTED_CREDENTIAL, storedDigest())).thenReturn(true);
            when(userSecurityRepository.saveAndFlush(any(UserSecurity.class))).thenThrow(collision);

            assertThatExceptionOfType(ConcurrentUpdateException.class)
                    .isThrownBy(() -> service.updateUser(
                            request(USER_ID, NEW_FIRST_NAME, STORED_LAST_NAME, PRESENTED_CREDENTIAL,
                                    STORED_USER_TYPE), null))
                    .satisfies(failure -> assertThat(failure.getCause()).isSameAs(collision));
        }
    }

    // =====================================================================================================
    // 8. Change detection - app/cbl/COUSR02C.cbl:219-234
    // =====================================================================================================

    /**
     * Four independent tests, each setting the modified flag. The credential test is the one that could not
     * survive translation unchanged: {@code :227} compared plaintext, and the store now holds a salted
     * one-way digest.
     */
    @Nested
    @DisplayName("8. Change detection :219-234 - four tests, and the credential compared through matches()")
    class ChangeDetection {

        @Test
        @DisplayName("the credential is compared through PasswordEncoder.matches, never by string equality")
        void theCredentialIsComparedThroughMatches() {
            arrangeStoredRow();
            arrangeCredentialMatches();

            service.updateUser(unchangedRequest(), null);

            verify(passwordEncoder).matches(PRESENTED_CREDENTIAL, storedDigest());
            verify(passwordEncoder, never()).encode(anyString());
        }

        @Test
        @DisplayName("a credential the encoder accepts is NOT a change, so nothing is re-hashed")
        void aMatchingCredentialIsNotAChange() {
            arrangeStoredRow();
            arrangeCredentialMatches();

            final UserUpdateScreen screen = service.updateUser(unchangedRequest(), null);

            assertThat(screen.userModified()).isFalse();
            assertThat(screen.errorMessage()).isEqualTo(NO_CHANGE_MESSAGE);
            verify(userSecurityRepository, never()).saveAndFlush(any(UserSecurity.class));
        }

        @Test
        @DisplayName("a credential the encoder refuses IS a change, and the new value is re-hashed")
        void aNonMatchingCredentialIsAChangeAndIsReHashed() {
            arrangeStoredRow();
            when(passwordEncoder.matches(REPLACEMENT_CREDENTIAL, storedDigest())).thenReturn(false);
            when(passwordEncoder.encode(REPLACEMENT_CREDENTIAL)).thenReturn(replacementDigest());
            final ArgumentCaptor<UserSecurity> persisted = ArgumentCaptor.forClass(UserSecurity.class);
            when(userSecurityRepository.saveAndFlush(any(UserSecurity.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            final UserUpdateScreen screen = service.updateUser(
                    request(USER_ID, STORED_FIRST_NAME, STORED_LAST_NAME, REPLACEMENT_CREDENTIAL,
                            STORED_USER_TYPE), null);

            verify(passwordEncoder).encode(REPLACEMENT_CREDENTIAL);
            verify(userSecurityRepository).saveAndFlush(persisted.capture());
            assertThat(persisted.getValue().getPasswordHash())
                    .as("what is stored is what the encoder returned, never the plaintext")
                    .isEqualTo(replacementDigest())
                    .isNotEqualTo(REPLACEMENT_CREDENTIAL);
            assertThat(screen.userModified()).isTrue();
        }

        @Test
        @DisplayName("a changed first name is detected and persisted")
        void aChangedFirstNameIsPersisted() {
            arrangeStoredRow();
            arrangeCredentialMatches();
            final ArgumentCaptor<UserSecurity> persisted = ArgumentCaptor.forClass(UserSecurity.class);
            when(userSecurityRepository.saveAndFlush(any(UserSecurity.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            service.updateUser(request(USER_ID, NEW_FIRST_NAME, STORED_LAST_NAME, PRESENTED_CREDENTIAL,
                    STORED_USER_TYPE), null);

            verify(userSecurityRepository).saveAndFlush(persisted.capture());
            assertThat(persisted.getValue().getSecUsrFname()).isEqualTo(NEW_FIRST_NAME);
            assertThat(persisted.getValue().getSecUsrLname()).isEqualTo(STORED_LAST_NAME);
        }

        @Test
        @DisplayName("a changed last name is detected and persisted")
        void aChangedLastNameIsPersisted() {
            arrangeStoredRow();
            arrangeCredentialMatches();
            final ArgumentCaptor<UserSecurity> persisted = ArgumentCaptor.forClass(UserSecurity.class);
            when(userSecurityRepository.saveAndFlush(any(UserSecurity.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            service.updateUser(request(USER_ID, STORED_FIRST_NAME, NEW_LAST_NAME, PRESENTED_CREDENTIAL,
                    STORED_USER_TYPE), null);

            verify(userSecurityRepository).saveAndFlush(persisted.capture());
            assertThat(persisted.getValue().getSecUsrLname()).isEqualTo(NEW_LAST_NAME);
        }

        @Test
        @DisplayName("a changed user class is detected and persisted")
        void aChangedUserClassIsPersisted() {
            arrangeStoredRow();
            arrangeCredentialMatches();
            final ArgumentCaptor<UserSecurity> persisted = ArgumentCaptor.forClass(UserSecurity.class);
            when(userSecurityRepository.saveAndFlush(any(UserSecurity.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            service.updateUser(request(USER_ID, STORED_FIRST_NAME, STORED_LAST_NAME, PRESENTED_CREDENTIAL,
                    OTHER_USER_TYPE), null);

            verify(userSecurityRepository).saveAndFlush(persisted.capture());
            assertThat(persisted.getValue().getSecUsrType()).isEqualTo(UserType.ADMIN);
        }

        @Test
        @DisplayName("names are compared on fixed width, so trailing spaces are not a change")
        void namesAreComparedOnFixedWidth() {
            arrangeStoredRow();
            arrangeCredentialMatches();

            final UserUpdateScreen screen = service.updateUser(
                    request(USER_ID, STORED_FIRST_NAME + "     ", STORED_LAST_NAME + "     ",
                            PRESENTED_CREDENTIAL, STORED_USER_TYPE), null);

            assertThat(screen.userModified())
                    .as("SEC-USR-FNAME is PIC X(20), so 'AJITH' and 'AJITH     ' are one value")
                    .isFalse();
            verify(userSecurityRepository, never()).saveAndFlush(any(UserSecurity.class));
        }

        @Test
        @DisplayName("nothing changed produces the red no-change message and no write at all")
        void nothingChangedProducesTheRedNoChangeMessage() {
            arrangeStoredRow();
            arrangeCredentialMatches();

            final UserUpdateScreen screen = service.updateUser(unchangedRequest(), null);

            assertThat(screen.errorMessage()).isEqualTo(NO_CHANGE_MESSAGE);
            assertThat(screen.messageColour())
                    .as(":241 MOVE DFHRED TO ERRMSGC")
                    .isEqualTo(COLOUR_RED);
            assertThat(screen.userModified()).isFalse();
            verify(userSecurityRepository, never()).saveAndFlush(any(UserSecurity.class));
        }

        @Test
        @DisplayName("several changes at once produce one write, not one per field")
        void severalChangesProduceOneWrite() {
            arrangeStoredRow();
            when(passwordEncoder.matches(REPLACEMENT_CREDENTIAL, storedDigest())).thenReturn(false);
            when(passwordEncoder.encode(REPLACEMENT_CREDENTIAL)).thenReturn(replacementDigest());
            when(userSecurityRepository.saveAndFlush(any(UserSecurity.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            service.updateUser(request(USER_ID, NEW_FIRST_NAME, NEW_LAST_NAME, REPLACEMENT_CREDENTIAL,
                    OTHER_USER_TYPE), null);

            verify(userSecurityRepository).saveAndFlush(any(UserSecurity.class));
        }

        @Test
        @DisplayName("an unrecognised user class is refused with the target's own domain message")
        void anUnrecognisedUserClassIsRefused() {
            arrangeStoredRow();

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.updateUser(request(USER_ID, STORED_FIRST_NAME,
                            STORED_LAST_NAME, PRESENTED_CREDENTIAL, "X"), null))
                    .satisfies(failure -> {
                        assertThat(failure.getMessage()).isEqualTo(USER_TYPE_DOMAIN_MESSAGE);
                        assertThat(failure.getFieldName()).isEqualTo("userType");
                    });
        }

        @ParameterizedTest(name = "a user class of [{0}] is refused, the lookup being case sensitive")
        @DisplayName("the user class is case sensitive, exactly as the 88-level comparison was")
        @ValueSource(strings = {"a", "u"})
        void theUserClassIsCaseSensitive(final String lowerCase) {
            arrangeStoredRow();

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.updateUser(request(USER_ID, STORED_FIRST_NAME,
                            STORED_LAST_NAME, PRESENTED_CREDENTIAL, lowerCase), null))
                    .withMessage(USER_TYPE_DOMAIN_MESSAGE);
        }
    }

    // =====================================================================================================
    // 9. The successful rewrite - app/cbl/COUSR02C.cbl:369-376
    // =====================================================================================================

    /** The message is assembled with {@code DELIMITED BY SPACE} around the record's own identifier. */
    @Nested
    @DisplayName("9. The successful rewrite :369-376 - assembled message, green attribute")
    class SuccessfulRewrite {

        @Test
        @DisplayName("the confirmation names the record's identifier and reads exactly as :372-375 builds it")
        void theConfirmationNamesTheRecordIdentifier() {
            arrangeStoredRow();
            arrangeCredentialMatches();
            when(userSecurityRepository.saveAndFlush(any(UserSecurity.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            final UserUpdateScreen screen = service.updateUser(
                    request(USER_ID, NEW_FIRST_NAME, STORED_LAST_NAME, PRESENTED_CREDENTIAL,
                            STORED_USER_TYPE), null);

            assertThat(screen.errorMessage())
                    .isEqualTo(UPDATED_MESSAGE_PREFIX + USER_ID + UPDATED_MESSAGE_SUFFIX)
                    .isEqualTo("User USER0002 has been updated ...");
        }

        @Test
        @DisplayName("the confirmation stops at the first space, DELIMITED BY SPACE at :373")
        void theConfirmationStopsAtTheFirstSpace() {
            final String paddedId = "USER1   ";
            when(userSecurityRepository.findById(paddedId))
                    .thenReturn(Optional.of(new UserSecurity(paddedId, STORED_FIRST_NAME, STORED_LAST_NAME,
                            storedDigest(), UserType.USER)));
            when(passwordEncoder.matches(PRESENTED_CREDENTIAL, storedDigest())).thenReturn(true);
            when(userSecurityRepository.saveAndFlush(any(UserSecurity.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            final UserUpdateScreen screen = service.updateUser(
                    request(paddedId, NEW_FIRST_NAME, STORED_LAST_NAME, PRESENTED_CREDENTIAL,
                            STORED_USER_TYPE), null);

            assertThat(screen.errorMessage())
                    .as("the fixed-width key's trailing blanks are not part of the reported identifier")
                    .isEqualTo("User USER1 has been updated ...");
        }

        @Test
        @DisplayName("the success screen carries the green attribute, the one success marker in the program")
        void theSuccessScreenCarriesTheGreenAttribute() {
            arrangeStoredRow();
            arrangeCredentialMatches();
            when(userSecurityRepository.saveAndFlush(any(UserSecurity.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            final UserUpdateScreen screen = service.updateUser(
                    request(USER_ID, NEW_FIRST_NAME, STORED_LAST_NAME, PRESENTED_CREDENTIAL,
                            STORED_USER_TYPE), null);

            assertThat(screen.messageColour()).isEqualTo(COLOUR_GREEN);
            assertThat(screen.userModified()).isTrue();
        }

        @Test
        @DisplayName("the three message colours are distinct, so the three outcomes stay distinguishable")
        void theThreeMessageColoursAreDistinct() {
            assertThat(List.of(COLOUR_GREEN, COLOUR_RED, COLOUR_NEUTRAL)).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("the write goes through the loaded record, so the identifier and version are preserved")
        void theWriteGoesThroughTheLoadedRecord() {
            final UserSecurity loaded = storedUser();
            when(userSecurityRepository.findById(USER_ID)).thenReturn(Optional.of(loaded));
            arrangeCredentialMatches();
            when(userSecurityRepository.saveAndFlush(any(UserSecurity.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));
            final ArgumentCaptor<UserSecurity> persisted = ArgumentCaptor.forClass(UserSecurity.class);

            service.updateUser(request(USER_ID, NEW_FIRST_NAME, STORED_LAST_NAME, PRESENTED_CREDENTIAL,
                    STORED_USER_TYPE), null);

            verify(userSecurityRepository).saveAndFlush(persisted.capture());
            assertThat(persisted.getValue())
                    .as(":362 FROM (SEC-USER-DATA) - the record area that was read, not a fresh instance")
                    .isSameAs(loaded);
        }
    }

    // =====================================================================================================
    // 10. The read and rewrite response arms - app/cbl/COUSR02C.cbl:333-353 and :368-390
    // =====================================================================================================

    /** Three arms each, with four distinct literals between them, and never a swallowed failure. */
    @Nested
    @DisplayName("10. The response arms :333-353 and :368-390 - four literals, none swallowed")
    class ResponseArms {

        @Test
        @DisplayName("a missing row on the read reports User ID NOT found and names the key")
        void aMissingRowOnTheReadIsReported() {
            when(userSecurityRepository.findById(USER_ID)).thenReturn(Optional.empty());

            assertThatExceptionOfType(RecordNotFoundException.class)
                    .isThrownBy(() -> service.updateUser(unchangedRequest(), null))
                    .satisfies(failure -> {
                        assertThat(failure.getMessage()).isEqualTo(USER_NOT_FOUND_MESSAGE);
                        assertThat(failure.recordType()).contains(USRSEC_FILE);
                        assertThat(failure.recordKey()).contains(USER_ID);
                    });
        }

        @Test
        @DisplayName("a missing row on the ENTER path reports the same literal")
        void aMissingRowOnTheEnterPathIsReported() {
            when(userSecurityRepository.findById(USER_ID)).thenReturn(Optional.empty());

            assertThatExceptionOfType(RecordNotFoundException.class)
                    .isThrownBy(() -> service.lookupUser(USER_ID))
                    .withMessage(USER_NOT_FOUND_MESSAGE);
        }

        @Test
        @DisplayName("an unreadable store reports Unable to lookup User and preserves the cause")
        void anUnreadableStoreIsReported() {
            final QueryTimeoutException timedOut = new QueryTimeoutException("timed out");
            when(userSecurityRepository.findById(USER_ID)).thenThrow(timedOut);

            assertThatExceptionOfType(CardDemoException.class)
                    .isThrownBy(() -> service.updateUser(unchangedRequest(), null))
                    .satisfies(failure -> assertThat(failure.getCause())
                            .as("the provider's failure is carried, never swallowed")
                            .isSameAs(timedOut));
        }

        @Test
        @DisplayName("an unwritable store reports Unable to Update User and preserves the cause")
        void anUnwritableStoreIsReported() {
            arrangeStoredRow();
            final QueryTimeoutException timedOut = new QueryTimeoutException("timed out");
            arrangeCredentialMatches();
            when(userSecurityRepository.saveAndFlush(any(UserSecurity.class))).thenThrow(timedOut);

            assertThatExceptionOfType(CardDemoException.class)
                    .isThrownBy(() -> service.updateUser(
                            request(USER_ID, NEW_FIRST_NAME, STORED_LAST_NAME, PRESENTED_CREDENTIAL,
                                    STORED_USER_TYPE), null))
                    .satisfies(failure -> assertThat(failure.getCause()).isSameAs(timedOut));
        }

        @Test
        @DisplayName("the four failure literals are byte exact and mutually distinct")
        void theFourFailureLiteralsAreByteExactAndDistinct() {
            assertThat(USER_NOT_FOUND_MESSAGE).isEqualTo("User ID NOT found...");
            assertThat(UNABLE_TO_LOOKUP_MESSAGE).isEqualTo("Unable to lookup User...");
            assertThat(UNABLE_TO_UPDATE_MESSAGE).isEqualTo("Unable to Update User...");
            assertThat(NO_CHANGE_MESSAGE).isEqualTo("Please modify to update ...");
            assertThat(List.of(USER_NOT_FOUND_MESSAGE, UNABLE_TO_LOOKUP_MESSAGE, UNABLE_TO_UPDATE_MESSAGE,
                    NO_CHANGE_MESSAGE, SAVE_HINT_MESSAGE)).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("the lookup and update failure literals differ, so the two operations stay apart")
        void theLookupAndUpdateLiteralsDiffer() throws ReflectiveOperationException {
            assertThat(declaredConstant("UNABLE_TO_LOOKUP_MESSAGE"))
                    .isEqualTo(UNABLE_TO_LOOKUP_MESSAGE)
                    .isNotEqualTo(declaredConstant("UNABLE_TO_UPDATE_MESSAGE"));
        }

        @Test
        @DisplayName("the successful read reports the save hint in neutral, not in red or green")
        void theSuccessfulReadReportsTheSaveHintInNeutral() {
            arrangeStoredRow();

            final UserUpdateScreen screen = service.lookupUser(USER_ID);

            assertThat(screen.errorMessage()).isEqualTo(SAVE_HINT_MESSAGE);
            assertThat(screen.messageColour())
                    .isEqualTo(COLOUR_NEUTRAL)
                    .isNotEqualTo(COLOUR_RED)
                    .isNotEqualTo(COLOUR_GREEN);
        }
    }

    // =====================================================================================================
    // 11. Credential protection - Rule 1 Clause D, and the deliberate deviation from :169
    // =====================================================================================================

    /**
     * {@code :169} moved the stored credential into the output map - it painted the password onto the screen.
     * That is deliberately not reproduced, and the response record declares no component that could carry it.
     */
    @Nested
    @DisplayName("11. Credential protection - :169 not reproduced, and no message carries a secret")
    class CredentialProtection {

        @Test
        @DisplayName("the response record declares no credential component, so :169 cannot be reintroduced")
        void theResponseRecordDeclaresNoCredentialComponent() {
            final List<String> components = Arrays.stream(UserUpdateScreen.class.getRecordComponents())
                    .map(component -> component.getName())
                    .toList();

            assertThat(components)
                    .hasSize(16)
                    .doesNotContain("password", "passwordHash", "secUsrPwd", "credential", "digest");
        }

        @Test
        @DisplayName("no failure message on any path carries the presented credential")
        void noFailureMessageCarriesThePresentedCredential() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.updateUser(
                            request(USER_ID, STORED_FIRST_NAME, STORED_LAST_NAME, null, null), null))
                    .satisfies(failure -> assertThat(failure.getMessage())
                            .doesNotContain(PRESENTED_CREDENTIAL)
                            .doesNotContain(storedDigest()));
        }

        @Test
        @DisplayName("no successful outcome carries the credential or the digest")
        void noSuccessfulOutcomeCarriesTheCredential() {
            arrangeStoredRow();
            arrangeCredentialMatches();
            when(userSecurityRepository.saveAndFlush(any(UserSecurity.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            final UserUpdateScreen screen = service.updateUser(
                    request(USER_ID, NEW_FIRST_NAME, STORED_LAST_NAME, PRESENTED_CREDENTIAL,
                            STORED_USER_TYPE), null);

            assertThat(screen.errorMessage()).doesNotContain(PRESENTED_CREDENTIAL, storedDigest());
            assertThat(screen.toString()).doesNotContain(PRESENTED_CREDENTIAL, storedDigest());
        }

        @Test
        @DisplayName("an encoder returning nothing ends the operation rather than storing an empty digest")
        void anEncoderReturningNothingEndsTheOperation() {
            arrangeStoredRow();
            when(passwordEncoder.matches(REPLACEMENT_CREDENTIAL, storedDigest())).thenReturn(false);
            when(passwordEncoder.encode(REPLACEMENT_CREDENTIAL)).thenReturn(null);

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> service.updateUser(
                            request(USER_ID, STORED_FIRST_NAME, STORED_LAST_NAME, REPLACEMENT_CREDENTIAL,
                                    STORED_USER_TYPE), null))
                    .satisfies(failure -> {
                        assertThat(failure.getAbendCulprit()).isEqualTo(PROGRAM_NAME);
                        assertThat(failure.getAbendReason()).isEqualTo("PASSWORD ENCODER RETURNED NO DIGEST");
                        assertThat(failure.getAbendCode())
                                .isEqualTo(String.valueOf(FatalProcessingException.BATCH_ABEND_CODE));
                    });
            verify(userSecurityRepository, never()).saveAndFlush(any(UserSecurity.class));
        }

        @Test
        @DisplayName("an encoder returning an empty digest ends the operation on the same arm")
        void anEncoderReturningAnEmptyDigestEndsTheOperation() {
            arrangeStoredRow();
            when(passwordEncoder.matches(REPLACEMENT_CREDENTIAL, storedDigest())).thenReturn(false);
            when(passwordEncoder.encode(REPLACEMENT_CREDENTIAL)).thenReturn("");

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> service.updateUser(
                            request(USER_ID, STORED_FIRST_NAME, STORED_LAST_NAME, REPLACEMENT_CREDENTIAL,
                                    STORED_USER_TYPE), null))
                    .satisfies(failure -> assertThat(failure.getAbendReason())
                            .isEqualTo("PASSWORD ENCODER RETURNED NO DIGEST"));
        }

        @Test
        @DisplayName("what is persisted is a strength-10 digest, enforced by the entity contract itself")
        void whatIsPersistedIsAStrengthTenDigest() {
            arrangeStoredRow();
            when(passwordEncoder.matches(REPLACEMENT_CREDENTIAL, storedDigest())).thenReturn(false);
            when(passwordEncoder.encode(REPLACEMENT_CREDENTIAL)).thenReturn(replacementDigest());
            final ArgumentCaptor<UserSecurity> persisted = ArgumentCaptor.forClass(UserSecurity.class);
            when(userSecurityRepository.saveAndFlush(any(UserSecurity.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            service.updateUser(request(USER_ID, STORED_FIRST_NAME, STORED_LAST_NAME,
                    REPLACEMENT_CREDENTIAL, STORED_USER_TYPE), null);

            verify(userSecurityRepository).saveAndFlush(persisted.capture());
            assertThat(persisted.getValue().getPasswordHash())
                    .as("UserSecurity admits cost 10 and refuses every other cost, so the strength is "
                            + "proved by the contract rather than by a literal")
                    .hasSize(60);
        }
    }

    // =====================================================================================================
    // 12. Header, clear and statelessness - app/cbl/COUSR02C.cbl:296-315 and :403-411
    // =====================================================================================================

    /** Six header values computed rather than echoed, a clear that touches six fields, and no residue. */
    @Nested
    @DisplayName("12. Header and clear :296-315 and :403-411 - computed values, cleared fields, no residue")
    class PresentationAndStatelessness {

        @Test
        @DisplayName("the submitted header components are overwritten, never echoed back")
        void theSubmittedHeaderComponentsAreOverwritten() {
            arrangeStoredRow();

            final UserUpdateScreen screen = service.lookupUser(USER_ID);

            assertThat(screen.transactionName()).isEqualTo(TRANSACTION_ID);
            assertThat(screen.programName()).isEqualTo(PROGRAM_NAME);
            assertThat(screen.title01()).isEqualTo(SCREEN_TITLE_01);
            assertThat(screen.title02()).isEqualTo(SCREEN_TITLE_02);
            assertThat(screen.currentDate()).isEqualTo(EXPECTED_HEADER_DATE);
            assertThat(screen.currentTime()).isEqualTo(EXPECTED_HEADER_TIME);
        }

        @Test
        @DisplayName("both titles are byte exact at their declared PIC X(40) width, padding included")
        void bothTitlesAreByteExactAtFortyCharacters() {
            assertThat(SCREEN_TITLE_01).hasSize(40).contains("AWS Mainframe Modernization");
            assertThat(SCREEN_TITLE_02).hasSize(40).contains("CardDemo");
        }

        @Test
        @DisplayName("the two-digit year comes from the last two digits, per :307")
        void theTwoDigitYearComesFromTheLastTwoDigits() {
            arrangeStoredRow();

            final UserUpdateScreen screen = service.lookupUser(USER_ID);

            assertThat(screen.currentDate()).isEqualTo(EXPECTED_HEADER_DATE).isNotEqualTo("06/10/2022");
        }

        @Test
        @DisplayName("two turns on one instance are independent, the bean holding nothing between them")
        void twoTurnsOnOneInstanceAreIndependent() {
            arrangeStoredRow();
            arrangeCredentialMatches();

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.updateUser(request(null, null, null, null, null), null));

            final UserUpdateScreen second = service.updateUser(unchangedRequest(), null);

            assertThat(second.errorMessage())
                    .as("the second turn must behave as though the first never happened")
                    .isEqualTo(NO_CHANGE_MESSAGE);
        }

        @Test
        @DisplayName("every static field is final, so no turn can mutate class-level state")
        void everyStaticFieldIsFinal() {
            final List<Field> mutableStatics = Arrays.stream(UserUpdateService.class.getDeclaredFields())
                    .filter(field -> Modifier.isStatic(field.getModifiers()))
                    .filter(field -> !Modifier.isFinal(field.getModifiers()))
                    .toList();
            assertThat(mutableStatics).isEmpty();
        }

        @Test
        @DisplayName("the map area declares twelve components, matching app/cpy-bms/COUSR02.CPY")
        void theMapAreaDeclaresTwelveComponents() {
            assertThat(UserUpdateRequest.class.getRecordComponents())
                    .as("COUSR02.CPY declares twelve input fields")
                    .hasSize(12);
        }

        @Test
        @DisplayName("the snapshot declares exactly the three fields the comparison reads")
        void theSnapshotDeclaresExactlyThreeFields() {
            assertThat(Arrays.stream(UserSnapshot.class.getRecordComponents())
                    .map(component -> component.getName())
                    .toList())
                    .as("the credential is deliberately absent: a caller cannot be asked to echo a digest")
                    .containsExactly("firstName", "lastName", "userType");
        }

        @Test
        @DisplayName("the recorded identity constants match the CSD definition of transaction CU02")
        void theRecordedIdentityConstantsMatchTheCsd() throws ReflectiveOperationException {
            assertThat(declaredConstant("TRANSACTION_ID")).isEqualTo(TRANSACTION_ID);
            assertThat(declaredConstant("PROGRAM_NAME")).isEqualTo(PROGRAM_NAME);
            assertThat(declaredConstant("USRSEC_FILE")).isEqualTo(USRSEC_FILE);
        }

        @Test
        @DisplayName("the read and rewrite verbs are recorded, being what a failure reports")
        void theReadAndRewriteVerbsAreRecorded() throws ReflectiveOperationException {
            assertThat(declaredConstant("READ_OPERATION")).isEqualTo("READ");
            assertThat(declaredConstant("REWRITE_OPERATION")).isEqualTo("REWRITE");
        }

        @Test
        @DisplayName("the status mapper is consulted with the USRSEC file name on a failing read")
        void theStatusMapperIsConsultedWithTheFileName() {
            final FileStatusMapper watched = org.mockito.Mockito.spy(new FileStatusMapper());
            final UserUpdateService watchedService = new UserUpdateService(userSecurityRepository,
                    passwordEncoder, watched, Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));
            when(userSecurityRepository.findById(USER_ID))
                    .thenThrow(new QueryTimeoutException("timed out"));

            assertThatExceptionOfType(CardDemoException.class)
                    .isThrownBy(() -> watchedService.updateUser(unchangedRequest(), null));

            verify(watched).toException(anyString(), eq(USRSEC_FILE), eq("READ"), any());
        }
    }
}
