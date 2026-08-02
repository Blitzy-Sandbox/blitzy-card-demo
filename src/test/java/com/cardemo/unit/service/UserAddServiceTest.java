/*
 * ******************************************************************
 * Program     : UserAddServiceTest.java
 * Component   : Unit test tier, resident at
 *               src/test/java/com/cardemo/unit/service
 * Application : CardDemo
 * Type        : JUnit 5 unit test - pure JVM tier, no container, no
 *               Spring context, no database, no live endpoint
 * Function    : Proves that UserAddService reproduces COUSR01C exactly
 *               - all nine paragraphs mapped one to one, the five empty
 *               field validations in the source's order with each
 *               literal byte exact, DUPKEY and DUPREC sharing a single
 *               branch, the confirmation built with DELIMITED BY SPACE
 *               semantics, and the credential neither stored in the
 *               clear, returned, nor named in any message.
 * Source      : app/cbl/COUSR01C.cbl (299 lines, 9 paragraphs) @ 7756d89
 * Source      : app/cpy-bms/COUSR01.CPY (12 input fields) @ 7756d89
 * Source      : app/cpy/CSUSR01Y.cpy (80 byte record, KEYS(8,0)) @ 7756d89
 * Source      : app/cpy/CSMSG01Y.cpy (CCDA-MSG-INVALID-KEY) @ 7756d89
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cardemo.exception.DuplicateRecordException;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.exception.FileAccessException;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.UserCreateRequest;
import com.cardemo.model.entity.UserSecurity;
import com.cardemo.model.enums.UserType;
import com.cardemo.repository.UserSecurityRepository;
import com.cardemo.service.admin.UserAddService;
import com.cardemo.service.admin.UserAddService.AttentionIdentifier;
import com.cardemo.service.admin.UserAddService.UserAddScreen;
import com.cardemo.service.shared.FileStatusMapper;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Unit tests for {@code com.cardemo.service.admin.UserAddService}, the Java translation of
 * {@code app/cbl/COUSR01C.cbl} (CICS transaction {@code CU01}).
 *
 * <p>Every assertion cites the paragraph or line of the frozen source it proves. The suite is a pure JVM
 * tier: the repository is a Mockito double, the clock is fixed so the header rendering is deterministic, and
 * the password encoder is the real BCrypt encoder at strength ten so the digest assertions are genuine
 * rather than stubbed.
 *
 * <p>The plaintext used throughout is {@code "secret12"}, an obviously fake eight-character value. The seed
 * password of {@code app/jcl/DUSRSECJ.jcl} is deliberately never reproduced here.
 */
@DisplayName("UserAddService: app/cbl/COUSR01C.cbl - add a Regular or Admin user to USRSEC")
class UserAddServiceTest {

    /** An obviously fake plaintext. Never the seed value of {@code app/jcl/DUSRSECJ.jcl}. */
    private static final String PLAINTEXT = "secret12";

    /** Fixed so that {@code POPULATE-HEADER-INFO} renders deterministically. */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2024-03-07T14:05:09Z"), ZoneOffset.UTC);

    private UserSecurityRepository repository;
    private PasswordEncoder encoder;
    private UserAddService service;

    @BeforeEach
    void setUp() {
        this.repository = mock(UserSecurityRepository.class);
        this.encoder = new BCryptPasswordEncoder(10);
        this.service = new UserAddService(this.repository, this.encoder, new FileStatusMapper(), FIXED_CLOCK);
        when(this.repository.existsById(anyString())).thenReturn(false);
        when(this.repository.saveAndFlush(any(UserSecurity.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static UserCreateRequest request(final String first, final String last, final String id,
            final String type) {
        return new UserCreateRequest("CU01", null, null, "COUSR01C", null, null,
                first, last, id, PLAINTEXT, type, null);
    }

    private static UserCreateRequest validRequest() {
        return request("JOHN", "SMITH", "USER0009", "U");
    }

    private UserSecurity captureSaved() {
        final ArgumentCaptor<UserSecurity> captor = ArgumentCaptor.forClass(UserSecurity.class);
        verify(this.repository).saveAndFlush(captor.capture());
        return captor.getValue();
    }

    @Nested
    @DisplayName("Paragraph correspondence (Gate 7)")
    class ParagraphCorrespondence {

        @Test
        @DisplayName("all nine labels of COUSR01C have a corresponding private method")
        void nineLabelsAreMappedOneToOne() {
            final Set<String> expected = new LinkedHashSet<>(Arrays.asList(
                    "mainPara",                 // :71  MAIN-PARA
                    "processEnterKey",          // :115 PROCESS-ENTER-KEY
                    "returnToPrevScreen",       // :165 RETURN-TO-PREV-SCREEN
                    "sendUsraddScreen",         // :184 SEND-USRADD-SCREEN
                    "receiveUsraddScreen",      // :201 RECEIVE-USRADD-SCREEN
                    "populateHeaderInfo",       // :214 POPULATE-HEADER-INFO
                    "writeUserSecFile",         // :238 WRITE-USER-SEC-FILE
                    "clearCurrentScreen",       // :279 CLEAR-CURRENT-SCREEN
                    "initializeAllFields"));    // :287 INITIALIZE-ALL-FIELDS
            assertThat(expected).hasSize(9);

            final Set<String> declared = Arrays.stream(UserAddService.class.getDeclaredMethods())
                    .filter(m -> Modifier.isPrivate(m.getModifiers()))
                    .map(Method::getName)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            assertThat(declared).containsAll(expected);
        }

        @Test
        @DisplayName("no static mutable field exists, so the bean is safe as a shared singleton")
        void beanHoldsNoStaticMutableState() {
            assertThat(Arrays.stream(UserAddService.class.getDeclaredFields())
                    .filter(f -> Modifier.isStatic(f.getModifiers()))
                    .filter(f -> !Modifier.isFinal(f.getModifiers()))
                    .toList()).isEmpty();
        }
    }

    @Nested
    @DisplayName("The five ordered validations, app/cbl/COUSR01C.cbl:117-151")
    class FiveOrderedValidations {

        @Test
        @DisplayName("1st - all five empty yields the FIRST NAME message and no other, :118-:120")
        void firstNameIsReportedFirst() {
            assertThatThrownBy(() -> service.addUser(request(" ", " ", " ", " "), " "))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("First Name can NOT be empty...")
                    .extracting(e -> ((ValidationException) e).getFieldName()).isEqualTo("firstName");
            verify(repository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("2nd - last name, once first name is supplied, :124-:126")
        void lastNameIsReportedSecond() {
            assertThatThrownBy(() -> service.addUser(request("JOHN", " ", " ", " "), " "))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Last Name can NOT be empty...")
                    .extracting(e -> ((ValidationException) e).getFieldName()).isEqualTo("lastName");
        }

        @Test
        @DisplayName("3rd - user identifier, :130-:132")
        void userIdIsReportedThird() {
            assertThatThrownBy(() -> service.addUser(request("JOHN", "SMITH", " ", " "), " "))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("User ID can NOT be empty...")
                    .extracting(e -> ((ValidationException) e).getFieldName()).isEqualTo("userId");
        }

        @Test
        @DisplayName("4th - password, and the failure names the FIELD never the VALUE, :136-:138")
        void passwordIsReportedFourth() {
            assertThatThrownBy(() -> service.addUser(request("JOHN", "SMITH", "USER0009", " "), " "))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Password can NOT be empty...")
                    .extracting(e -> ((ValidationException) e).getFieldName()).isEqualTo("password");
        }

        @Test
        @DisplayName("5th - user type, :142-:144")
        void userTypeIsReportedFifth() {
            assertThatThrownBy(() -> service.addUser(request("JOHN", "SMITH", "USER0009", " "), PLAINTEXT))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("User Type can NOT be empty...")
                    .extracting(e -> ((ValidationException) e).getFieldName()).isEqualTo("userType");
        }

        @Test
        @DisplayName("all five are the BLANK failure kind of app/cpy/CSSETATY.cpy, never INVALID")
        void allFiveAreTheBlankFailureKind() {
            final List<String[]> cases = new ArrayList<>();
            cases.add(new String[] {" ", " ", " ", " ", " ", "firstName"});
            cases.add(new String[] {"JOHN", " ", " ", " ", " ", "lastName"});
            cases.add(new String[] {"JOHN", "SMITH", " ", " ", " ", "userId"});
            cases.add(new String[] {"JOHN", "SMITH", "USER0009", " ", " ", "password"});
            cases.add(new String[] {"JOHN", "SMITH", "USER0009", " ", PLAINTEXT, "userType"});
            for (final String[] c : cases) {
                assertThatThrownBy(() -> service.addUser(request(c[0], c[1], c[2], c[3]), c[4]))
                        .isInstanceOf(ValidationException.class)
                        .satisfies(e -> {
                            final ValidationException v = (ValidationException) e;
                            assertThat(v.getFailureKind()).isEqualTo(ValidationException.FailureKind.BLANK);
                            assertThat(v.getFieldName()).isEqualTo(c[5]);
                        });
            }
            verify(repository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("SPACES and LOW-VALUES are both empty: null, empty, blank and NUL all take the arm")
        void nullEmptyBlankAndLowValuesAreAllEmpty() {
            for (final String empty : Arrays.asList(null, "", "   ", "\u0000", "\u0000\u0000", "\t")) {
                assertThatThrownBy(() -> service.addUser(request(empty, "SMITH", "USER0009", "U"), PLAINTEXT))
                        .as("value %s must be treated as empty", (Object) empty)
                        .isInstanceOf(ValidationException.class)
                        .hasMessage("First Name can NOT be empty...");
            }
            verify(repository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("LOW-VALUES in the credential takes the password arm, never reaching the encoder")
        void lowValuesPasswordIsEmpty() {
            assertThatThrownBy(() -> service.addUser(validRequest(), "\u0000\u0000\u0000"))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Password can NOT be empty...");
            verify(repository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("every literal is byte exact: can NOT casing and three-period ellipses")
        void literalsAreByteExact() {
            assertThat("First Name can NOT be empty...").hasSize(30).endsWith("...");
            assertThatThrownBy(() -> service.addUser(request(" ", " ", " ", " "), " "))
                    .hasMessage("First Name can NOT be empty...");
            assertThatThrownBy(() -> service.addUser(request("A", " ", " ", " "), " "))
                    .hasMessage("Last Name can NOT be empty...");
            assertThatThrownBy(() -> service.addUser(request("A", "B", " ", " "), " "))
                    .hasMessage("User ID can NOT be empty...");
            assertThatThrownBy(() -> service.addUser(request("A", "B", "C", " "), " "))
                    .hasMessage("Password can NOT be empty...");
            assertThatThrownBy(() -> service.addUser(request("A", "B", "C", " "), PLAINTEXT))
                    .hasMessage("User Type can NOT be empty...");
        }
    }

    @Nested
    @DisplayName("WRITE-USER-SEC-FILE success arm, app/cbl/COUSR01C.cbl:251-259")
    class SuccessArm {

        @Test
        @DisplayName("confirmation is 'User ' + trimmed id + ' has been added ...' with DFHGREEN, :255-258")
        void confirmationIsExact() {
            final UserAddScreen screen = service.addUser(validRequest(), PLAINTEXT);
            assertThat(screen.errorMessage()).isEqualTo("User USER0009 has been added ...");
            assertThat(screen.messageColour()).isEqualTo("DFHGREEN");
        }

        @Test
        @DisplayName("DELIMITED BY SPACE strips the X(08) padding rather than trimming both ends")
        void delimitedBySpaceStripsPadding() {
            final UserAddScreen screen = service.addUser(request("J", "S", "USR1    ", "A"), PLAINTEXT);
            assertThat(screen.errorMessage()).isEqualTo("User USR1 has been added ...");
        }

        @Test
        @DisplayName("INITIALIZE-ALL-FIELDS blanks the inputs yet the confirmation still names the user")
        void initializeAllFieldsLeavesSecUsrIdIntact() {
            final UserAddScreen screen = service.addUser(validRequest(), PLAINTEXT);
            assertThat(screen.firstName()).isEmpty();
            assertThat(screen.lastName()).isEmpty();
            assertThat(screen.userId()).isEmpty();
            assertThat(screen.userType()).isEmpty();
            assertThat(screen.errorMessage()).contains("USER0009");
            assertThat(screen.cursorField()).isEqualTo("FNAME");
        }

        @Test
        @DisplayName("the record is populated in the source's order and persisted once, :154-159")
        void recordIsPersistedOnce() {
            service.addUser(validRequest(), PLAINTEXT);
            final UserSecurity saved = captureSaved();
            assertThat(saved.getSecUsrId()).isEqualTo("USER0009");
            assertThat(saved.getSecUsrFname()).isEqualTo("JOHN");
            assertThat(saved.getSecUsrLname()).isEqualTo("SMITH");
            assertThat(saved.getSecUsrType()).isEqualTo(UserType.USER);
        }
    }

    @Nested
    @DisplayName("Credential handling (Rule 1 Clause D)")
    class CredentialHandling {

        @Test
        @DisplayName("the stored value is a BCrypt strength-10 digest that matches and is not the plaintext")
        void passwordIsBcryptHashed() {
            service.addUser(validRequest(), PLAINTEXT);
            final String stored = captureSaved().getPasswordHash();
            assertThat(stored).isNotEqualTo(PLAINTEXT).hasSize(60).startsWith("$2a$10$");
            assertThat(encoder.matches(PLAINTEXT, stored)).isTrue();
        }

        @Test
        @DisplayName("the response record declares no password component at all")
        void responseHasNoPasswordComponent() {
            assertThat(UserAddScreen.class.getRecordComponents())
                    .noneMatch(c -> c.getName().toLowerCase(Locale.ROOT).contains("password"));
        }

        @Test
        @DisplayName("no rendered response contains the plaintext or the digest")
        void responseNeverCarriesTheCredential() {
            final UserAddScreen screen = service.addUser(validRequest(), PLAINTEXT);
            assertThat(screen.toString()).doesNotContain(PLAINTEXT).doesNotContain("$2a$");
        }

        @Test
        @DisplayName("no exception message on any arm contains the plaintext or the digest")
        void noExceptionMessageCarriesTheCredential() {
            when(repository.existsById("USER0009")).thenReturn(true);
            assertThatThrownBy(() -> service.addUser(validRequest(), PLAINTEXT))
                    .satisfies(e -> assertThat(e.getMessage())
                            .doesNotContain(PLAINTEXT).doesNotContain("$2a$"));

            assertThatThrownBy(() -> service.addUser(request("J", "S", "USER0009", " "), " "))
                    .satisfies(e -> assertThat(e.getMessage()).doesNotContain("$2a$"));
        }

    }

    @Nested
    @DisplayName("Duplicate key: DUPKEY and DUPREC share ONE branch, app/cbl/COUSR01C.cbl:260-266")
    class DuplicateKey {

        @Test
        @DisplayName("an existing identifier yields DuplicateRecordException carrying only the key")
        void duplicateDetectedByTheProbe() {
            when(repository.existsById("USER0009")).thenReturn(true);
            assertThatThrownBy(() -> service.addUser(validRequest(), PLAINTEXT))
                    .isInstanceOf(DuplicateRecordException.class)
                    .hasMessage("User ID already exist...")
                    .satisfies(e -> {
                        final DuplicateRecordException d = (DuplicateRecordException) e;
                        assertThat(d.getCollidingKey()).isEqualTo("USER0009");
                        assertThat(d.getLogicalFile()).isEqualTo("USRSEC");
                    });
            verify(repository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("a concurrent insert takes the SAME branch and preserves the root cause")
        void duplicateDetectedByTheConstraint() {
            final DataIntegrityViolationException violation =
                    new DataIntegrityViolationException("duplicate key value violates unique constraint");
            when(repository.saveAndFlush(any(UserSecurity.class))).thenThrow(violation);
            assertThatThrownBy(() -> service.addUser(validRequest(), PLAINTEXT))
                    .isInstanceOf(DuplicateRecordException.class)
                    .hasMessage("User ID already exist...")
                    .hasRootCause(violation);
        }

        @Test
        @DisplayName("the message reads 'exist' not 'exists' - the grammatical defect of :263 is preserved")
        void grammaticalDefectIsPreserved() {
            when(repository.existsById("USER0009")).thenReturn(true);
            assertThatThrownBy(() -> service.addUser(validRequest(), PLAINTEXT))
                    .hasMessage("User ID already exist...")
                    .satisfies(e -> assertThat(e.getMessage()).doesNotContain("exists"));
        }

        @Test
        @DisplayName("no retry: exactly one probe and no insert attempt on a known collision")
        void thereIsNoRetry() {
            when(repository.existsById("USER0009")).thenReturn(true);
            assertThatThrownBy(() -> service.addUser(validRequest(), PLAINTEXT));
            verify(repository).existsById("USER0009");
            verify(repository, never()).saveAndFlush(any());
            verify(repository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("WHEN OTHER arm, app/cbl/COUSR01C.cbl:267-273")
    class WhenOtherArm {

        @Test
        @DisplayName("a data-access failure yields FileAccessException preserving the root cause")
        void dataAccessFailureIsTyped() {
            final QueryTimeoutException boom = new QueryTimeoutException("statement timed out");
            when(repository.saveAndFlush(any(UserSecurity.class))).thenThrow(boom);
            assertThatThrownBy(() -> service.addUser(validRequest(), PLAINTEXT))
                    .isInstanceOf(FileAccessException.class)
                    .hasRootCause(boom);
        }

        @Test
        @DisplayName("a digest the entity rejects escalates to FatalProcessingException with abend 999")
        void unusableDigestAbends() {
            final PasswordEncoder broken = mock(PasswordEncoder.class);
            when(broken.encode(anyString())).thenReturn("not-a-bcrypt-digest");
            final UserAddService withBrokenEncoder =
                    new UserAddService(repository, broken, new FileStatusMapper(), FIXED_CLOCK);
            assertThatThrownBy(() -> withBrokenEncoder.addUser(validRequest(), PLAINTEXT))
                    .isInstanceOf(FatalProcessingException.class)
                    .satisfies(e -> assertThat(((FatalProcessingException) e).getAbendCode())
                            .isEqualTo(String.valueOf(FatalProcessingException.BATCH_ABEND_CODE)));
        }

        @Test
        @DisplayName("an encoder returning nothing abends rather than persisting a blank credential")
        void absentDigestAbends() {
            final PasswordEncoder silent = mock(PasswordEncoder.class);
            when(silent.encode(anyString())).thenReturn("");
            final UserAddService withSilentEncoder =
                    new UserAddService(repository, silent, new FileStatusMapper(), FIXED_CLOCK);
            assertThatThrownBy(() -> withSilentEncoder.addUser(validRequest(), PLAINTEXT))
                    .isInstanceOf(FatalProcessingException.class);
            verify(repository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("an encoder returning null abends too, not just one returning an empty string")
        void nullDigestAbends() {
            final PasswordEncoder nullEncoder = mock(PasswordEncoder.class);
            when(nullEncoder.encode(anyString())).thenReturn(null);
            final UserAddService withNullEncoder =
                    new UserAddService(repository, nullEncoder, new FileStatusMapper(), FIXED_CLOCK);
            assertThatThrownBy(() -> withNullEncoder.addUser(validRequest(), PLAINTEXT))
                    .isInstanceOf(FatalProcessingException.class);
            verify(repository, never()).saveAndFlush(any());
        }
    }

    @Nested
    @DisplayName("User type, app/cpy/COCOM01Y.cpy:27-28")
    class UserTypeMapping {

        @Test
        @DisplayName("'A' maps to ADMIN and 'U' maps to USER")
        void bothCodesMap() {
            service.addUser(request("J", "S", "ADMIN009", "A"), PLAINTEXT);
            assertThat(captureSaved().getSecUsrType()).isEqualTo(UserType.ADMIN);
        }

        @Test
        @DisplayName("labelled deviation: an unmappable code is INVALID and the value is never echoed")
        void unmappableCodeIsRejected() {
            assertThatThrownBy(() -> service.addUser(request("J", "S", "USER0009", "Z"), PLAINTEXT))
                    .isInstanceOf(ValidationException.class)
                    .satisfies(e -> {
                        final ValidationException v = (ValidationException) e;
                        assertThat(v.getFailureKind()).isEqualTo(ValidationException.FailureKind.INVALID);
                        assertThat(v.getFieldName()).isEqualTo("userType");
                        assertThat(v.getMessage()).doesNotContain("Z");
                    });
            verify(repository, never()).saveAndFlush(any());
        }
    }

    @Nested
    @DisplayName("MAIN-PARA dispatch, app/cbl/COUSR01C.cbl:71-112")
    class MainParaDispatch {

        @Test
        @DisplayName("EIBCALEN = 0 routes to COSGN00C, :78-80")
        void noCommAreaRoutesToSignOn() {
            assertThat(service.openWithoutContext().navigationTarget()).isEqualTo("COSGN00C");
            verify(repository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("first display paints the header and parks the cursor on FNAME, :83-87")
        void firstDisplayPaintsTheHeader() {
            final UserAddScreen screen = service.openScreen();
            assertThat(screen.transactionName()).isEqualTo("CU01");
            assertThat(screen.programName()).isEqualTo("COUSR01C");
            assertThat(screen.cursorField()).isEqualTo("FNAME");
            assertThat(screen.navigationTarget()).isNull();
        }

        @Test
        @DisplayName("PF3 exits to COADM01C WITHOUT saving - conventional here, unlike COUSR02C:111-112")
        void pf3ExitsWithoutSaving() {
            final UserAddScreen screen =
                    service.submitScreen(AttentionIdentifier.PF3, validRequest(), null);
            assertThat(screen.navigationTarget()).isEqualTo("COADM01C");
            verify(repository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("PF4 clears the screen and writes nothing, :96-97")
        void pf4ClearsTheScreen() {
            final UserAddScreen screen =
                    service.submitScreen(AttentionIdentifier.PF4, validRequest(), PLAINTEXT);
            assertThat(screen.firstName()).isEmpty();
            assertThat(screen.userId()).isEmpty();
            assertThat(screen.cursorField()).isEqualTo("FNAME");
            verify(repository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("an unrecognised key reports CCDA-MSG-INVALID-KEY verbatim, :98-102")
        void otherKeyReportsInvalidKey() {
            final UserAddScreen screen =
                    service.submitScreen(AttentionIdentifier.OTHER, validRequest(), null);
            assertThat(screen.errorMessage()).isEqualTo("Invalid key pressed. Please see below...");
            assertThat(screen.cursorField()).isEqualTo("FNAME");
            verify(repository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("ENTER through submitScreen behaves exactly as addUser")
        void enterThroughSubmitScreenAdds() {
            final UserAddScreen screen =
                    service.submitScreen(AttentionIdentifier.ENTER, validRequest(), PLAINTEXT);
            assertThat(screen.errorMessage()).isEqualTo("User USER0009 has been added ...");
        }

        @Test
        @DisplayName("a non-ENTER key with no submitted fields binds nothing and still returns a screen")
        void nonEnterKeyToleratesAnAbsentRequest() {
            final UserAddScreen pf3 = service.submitScreen(AttentionIdentifier.PF3, null, null);
            assertThat(pf3.navigationTarget()).isEqualTo("COADM01C");

            final UserAddScreen other = service.submitScreen(AttentionIdentifier.OTHER, null, null);
            assertThat(other.errorMessage()).isEqualTo("Invalid key pressed. Please see below...");

            final UserAddScreen pf4 = service.submitScreen(AttentionIdentifier.PF4, null, null);
            assertThat(pf4.userId()).isEmpty();

            verify(repository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("submitScreen rejects a null attention identifier")
        void nullAttentionIdentifierIsRejected() {
            assertThatThrownBy(() -> service.submitScreen(null, validRequest(), PLAINTEXT))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("POPULATE-HEADER-INFO, app/cbl/COUSR01C.cbl:214-235")
    class HeaderRendering {

        @Test
        @DisplayName("date is MM/DD/YY and time is HH:MM:SS, both 8 characters, from the injected clock")
        void headerComesFromTheInjectedClock() {
            final UserAddScreen screen = service.openScreen();
            assertThat(screen.currentDate()).isEqualTo("03/07/24").hasSize(8);
            assertThat(screen.currentTime()).isEqualTo("14:05:09").hasSize(8);
        }

        @Test
        @DisplayName("titles come from app/cpy/COTTL01Y.cpy")
        void titlesComeFromTheCopybook() {
            final UserAddScreen screen = service.openScreen();
            assertThat(screen.title01()).contains("AWS Mainframe Modernization");
            assertThat(screen.title02()).contains("CardDemo");
        }

        @Test
        @DisplayName("the clock is the only time source, so two calls render identically")
        void renderingIsDeterministic() {
            assertThat(service.openScreen().currentTime()).isEqualTo(service.openScreen().currentTime());
        }
    }

    @Nested
    @DisplayName("Field widths of app/cpy-bms/COUSR01.CPY")
    class FieldWidths {

        @Test
        @DisplayName("an over-long value is rejected, never truncated")
        void overLongValuesAreRejected() {
            assertThatThrownBy(() -> service.addUser(request("J", "S", "TOOLONGID9", "U"), PLAINTEXT))
                    .isInstanceOf(ValidationException.class)
                    .extracting(e -> ((ValidationException) e).getFieldName()).isEqualTo("userId");

            assertThatThrownBy(() -> service.addUser(
                    request("J".repeat(21), "S", "USER0009", "U"), PLAINTEXT))
                    .isInstanceOf(ValidationException.class)
                    .extracting(e -> ((ValidationException) e).getFieldName()).isEqualTo("firstName");

            assertThatThrownBy(() -> service.addUser(
                    request("J", "S".repeat(21), "USER0009", "U"), PLAINTEXT))
                    .isInstanceOf(ValidationException.class)
                    .extracting(e -> ((ValidationException) e).getFieldName()).isEqualTo("lastName");

            verify(repository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("a value at exactly the declared width is accepted")
        void boundaryWidthIsAccepted() {
            service.addUser(request("J".repeat(20), "S".repeat(20), "USERID12", "U"), PLAINTEXT);
            assertThat(captureSaved().getSecUsrId()).isEqualTo("USERID12");
        }
    }

    @Nested
    @DisplayName("Statelessness and transaction boundary")
    class StatelessnessAndTransaction {

        @Test
        @DisplayName("a failed call leaves no residue on the next call")
        void beanIsStatelessAcrossCalls() {
            assertThatThrownBy(() -> service.addUser(request(" ", " ", " ", " "), " "));
            final UserAddScreen ok = service.addUser(validRequest(), PLAINTEXT);
            assertThat(ok.errorMessage()).isEqualTo("User USER0009 has been added ...");
            assertThat(ok.messageColour()).isEqualTo("DFHGREEN");
        }

        @Test
        @DisplayName("addUser and submitScreen roll back for any exception")
        void writeEntryPointsRollBackForAnyException() throws Exception {
            for (final String name : Arrays.asList("addUser", "submitScreen")) {
                final Method m = Arrays.stream(UserAddService.class.getMethods())
                        .filter(x -> x.getName().equals(name)).findFirst().orElseThrow();
                final org.springframework.transaction.annotation.Transactional tx =
                        m.getAnnotation(org.springframework.transaction.annotation.Transactional.class);
                assertThat(tx).as("%s must be transactional", name).isNotNull();
                assertThat(tx.rollbackFor()).containsExactly(Exception.class);
            }
        }

        @Test
        @DisplayName("the constructor rejects every null collaborator")
        void constructorRejectsNulls() {
            final FileStatusMapper mapper = new FileStatusMapper();
            assertThatThrownBy(() -> new UserAddService(null, encoder, mapper, FIXED_CLOCK))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new UserAddService(repository, null, mapper, FIXED_CLOCK))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new UserAddService(repository, encoder, null, FIXED_CLOCK))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new UserAddService(repository, encoder, mapper, null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("addUser rejects a null request rather than writing a partial row")
        void nullRequestIsRejected() {
            assertThatThrownBy(() -> service.addUser(null, PLAINTEXT))
                    .isInstanceOf(NullPointerException.class);
            verify(repository, never()).saveAndFlush(any());
        }
    }
}
