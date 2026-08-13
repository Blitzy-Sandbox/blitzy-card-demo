package com.vsergeychik.carddemo.user;

import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.CicsResponse;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.PfKeyResolver;
import com.vsergeychik.carddemo.common.SensitiveDiagnostics;
import com.vsergeychik.carddemo.common.SystemMessages;
import com.vsergeychik.carddemo.user.SecUserRepository.ReadResult;
import com.vsergeychik.carddemo.user.SignOnService.CursorField;
import com.vsergeychik.carddemo.user.SignOnService.MapInputArea;
import com.vsergeychik.carddemo.user.SignOnService.ReceiveOutcome;
import com.vsergeychik.carddemo.user.SignOnService.SignOnInput;
import com.vsergeychik.carddemo.user.SignOnService.SignOnOutcome;
import com.vsergeychik.carddemo.user.SignOnService.Termination;
import com.vsergeychik.carddemo.user.model.SecUserRecord;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

import org.assertj.core.api.Assertions;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link SignOnService} against {@code app/cbl/COSGN00C.cbl} - the decision-logic test of the sign-on
 * transaction {@code CC00}.
 */
@DisplayName("SignOnService - the COSGN00C sign-on transaction")
class SignOnServiceTest {
    private static final Charset CODE_PAGE = StandardCharsets.US_ASCII;

    private static final String SEEDED_ADMIN_ID = "ADMIN001";

    private static final String SEEDED_ADMIN_FNAME = "MARGARET";

    private static final String SEEDED_ADMIN_LNAME = "GOLD";

    private static final String SEEDED_USER_ID = "USER0001";

    private static final String SEEDED_USER_FNAME = "LAWRENCE";

    private static final String SEEDED_USER_LNAME = "THOMAS";

    private static final String STORED_PASSWORD = "PASSWORD";

    private static final String WRONG_PASSWORD = "N0TTHEPW";

    private static final byte NOT_AN_AID = (byte) 0x00;

    private SecUserRepository repository;

    private SignOnService service;

    @BeforeEach
    void setUp() {
        repository = mock(SecUserRepository.class);
        service = new SignOnService(repository);
    }

    private static SecUserRecord storedUser(String userId, String password, String userType) {
        boolean admin = SEEDED_ADMIN_ID.equals(userId);
        return SecUserRecord.of(userId,
                admin ? SEEDED_ADMIN_FNAME : SEEDED_USER_FNAME,
                admin ? SEEDED_ADMIN_LNAME : SEEDED_USER_LNAME,
                password,
                userType,
                CODE_PAGE);
    }

    private static SignOnInput enterKey(String userId, String password) {
        return new SignOnInput(NavigationContext.empty(), CicsAid.DFHENTER, userId, password);
    }

    private static String spaces(int width) {
        return " ".repeat(width);
    }

    private static String lowValues(int width) {
        return "\u0000".repeat(width);
    }

    private static String messageImage(String literal) {
        return literal + spaces(SignOnService.MESSAGE_LENGTH - literal.length());
    }

    private void stubRead(ReadResult result) {
        when(repository.read(anyString())).thenReturn(result);
    }

    @Nested
    @DisplayName("Wiring and the WS-VARIABLES literals, COSGN00C:35-46")
    class WiringAndLiterals {
        @Test
        @DisplayName("the repository is required, and is the only collaborator")
        void repositoryIsRequired() {
            Assertions.assertThatThrownBy(() -> new SignOnService(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("USRSEC");
            Assertions.assertThat(service.secUserRepository()).isSameAs(repository);
        }

        @Test
        @DisplayName("the file name matches the repository's, trailing spaces included")
        void fileNameAgreesWithTheRepository() {
            Assertions.assertThat(SignOnService.USRSEC_FILE_NAME)
                    .isEqualTo(SecUserRepository.CICS_FILE_NAME_IMAGE)
                    .hasSize(SecUserRepository.CICS_FILE_NAME_LENGTH)
                    .isEqualTo("USRSEC  ");
        }

        @Test
        @DisplayName("the identifiers and widths come from the copybooks")
        void identifiersAndWidths() {
            Assertions.assertThat(SignOnService.PROGRAM_NAME).isEqualTo("COSGN00C")
                    .hasSize(NavigationContext.FROM_PROGRAM_LENGTH);
            Assertions.assertThat(SignOnService.TRANSACTION_ID).isEqualTo("CC00")
                    .hasSize(NavigationContext.FROM_TRANID_LENGTH);
            Assertions.assertThat(SignOnService.MAPSET_NAME).isEqualTo("COSGN00");
            Assertions.assertThat(SignOnService.MAP_NAME).isEqualTo("COSGN0A");
            Assertions.assertThat(SignOnService.ADMIN_PROGRAM).isEqualTo("COADM01C")
                    .hasSize(SignOnService.NEXT_PROGRAM_LENGTH);
            Assertions.assertThat(SignOnService.USER_PROGRAM).isEqualTo("COMEN01C")
                    .hasSize(SignOnService.NEXT_PROGRAM_LENGTH);
            Assertions.assertThat(SignOnService.MESSAGE_LENGTH).isEqualTo(80);
            Assertions.assertThat(SignOnService.USER_ID_LENGTH).isEqualTo(SecUserRecord.KEY_LENGTH);
            Assertions.assertThat(SignOnService.PASSWORD_LENGTH)
                    .isEqualTo(SecUserRecord.SEC_USR_PWD_LENGTH);
            Assertions.assertThat(SignOnService.ROLE_LENGTH)
                    .isEqualTo(SecUserRecord.SEC_USR_TYPE_LENGTH);
            Assertions.assertThat(SignOnService.ERR_FLG_ON).isEqualTo("Y");
            Assertions.assertThat(SignOnService.ERR_FLG_OFF).isEqualTo("N");
            Assertions.assertThat(SignOnService.CURSOR_POSITION).isEqualTo(-1);
        }

        @Test
        @DisplayName("the two response arms are the raw literals 0 and 13 the source compares")
        void responseArmsAreTheRawLiterals() {
            Assertions.assertThat(SignOnService.RESP_NORMAL).isEqualTo(0).isEqualTo(FileStatus.NORMAL);
            Assertions.assertThat(SignOnService.RESP_NOTFND).isEqualTo(13).isEqualTo(FileStatus.NOTFND);

            Assertions.assertThat(FileStatus.outcomeOfCicsResp(SignOnService.RESP_NORMAL))
                    .isEqualTo(FileStatus.Outcome.OK);
            Assertions.assertThat(FileStatus.outcomeOfCicsResp(SignOnService.RESP_NOTFND))
                    .isEqualTo(FileStatus.Outcome.NOT_FOUND);
        }

        @Test
        @DisplayName("the CSD binds transaction CC00 to program COSGN00C and names the USRSEC file")
        void theCsdBindingsAgreeWithTheConstants() {
            Assertions.assertThat(SignOnService.PROGRAM_NAME).isEqualTo("COSGN00C");
            Assertions.assertThat(SignOnService.TRANSACTION_ID).isEqualTo("CC00");

            Assertions.assertThat(SignOnService.USRSEC_FILE_NAME)
                    .startsWith(SecUserRepository.CICS_FILE_NAME)
                    .isEqualTo(SecUserRepository.CICS_FILE_NAME_IMAGE);
            Assertions.assertThat(SecUserRepository.CICS_FILE_NAME).isEqualTo("USRSEC");
        }
    }

    @Nested
    @DisplayName("The five message literals, byte for byte")
    class MessageLiterals {
        @Test
        @DisplayName("each literal is exactly what the source writes, ellipsis spacing included")
        void literalsAreByteExact() {
            Assertions.assertThat(SignOnService.MSG_ENTER_USER_ID)
                    .isEqualTo("Please enter User ID ...").hasSize(24);
            Assertions.assertThat(SignOnService.MSG_ENTER_PASSWORD)
                    .isEqualTo("Please enter Password ...").hasSize(25);
            Assertions.assertThat(SignOnService.MSG_WRONG_PASSWORD)
                    .isEqualTo("Wrong Password. Try again ...").hasSize(29);
            Assertions.assertThat(SignOnService.MSG_USER_NOT_FOUND)
                    .isEqualTo("User not found. Try again ...").hasSize(29);
            Assertions.assertThat(SignOnService.MSG_UNABLE_TO_VERIFY)
                    .isEqualTo("Unable to verify the User ...").hasSize(29);
        }

        @ParameterizedTest
        @ValueSource(strings = {"Please enter User ID ...", "Please enter Password ...",
                "Wrong Password. Try again ...", "User not found. Try again ...",
                "Unable to verify the User ..."})
        @DisplayName("every literal has a space before its ellipsis and no double space")
        void everyLiteralEndsWithSpaceThenEllipsis(String literal) {
            Assertions.assertThat(literal).endsWith(" ...").doesNotContain("  ");
        }

        @Test
        @DisplayName("the thank-you and invalid-key texts are the CSMSG01Y ones, not redeclared here")
        void copybookMessagesComeFromSystemMessages() {
            Assertions.assertThat(SystemMessages.CCDA_MSG_THANK_YOU)
                    .hasSize(SystemMessages.MESSAGE_LENGTH)
                    .startsWith("Thank you for using CardDemo application...");
            Assertions.assertThat(SystemMessages.CCDA_MSG_INVALID_KEY)
                    .hasSize(SystemMessages.MESSAGE_LENGTH)
                    .startsWith("Invalid key pressed. Please see below...");
        }
    }

    @Nested
    @DisplayName("First entry with no communication area, COSGN00C:80-83")
    class FirstEntry {
        @Test
        @DisplayName("paints the screen, clears the output fields, puts the cursor on the user id")
        void paintsTheSignonScreen() {
            SignOnOutcome outcome = service.handle(
                    SignOnInput.withoutCommarea(CicsAid.DFHENTER, SEEDED_ADMIN_ID, STORED_PASSWORD));

            Assertions.assertThat(outcome.screenPainted()).isTrue();
            Assertions.assertThat(outcome.resetAllOutputFields()).isTrue();
            Assertions.assertThat(outcome.plainTextSent()).isFalse();
            Assertions.assertThat(outcome.cursorField()).isEqualTo(CursorField.USER_ID);
            Assertions.assertThat(outcome.message()).isEqualTo(spaces(SignOnService.MESSAGE_LENGTH));
            Assertions.assertThat(outcome.errorFlag()).isFalse();
            Assertions.assertThat(outcome.errFlgImage()).isEqualTo(SignOnService.ERR_FLG_OFF);
            Assertions.assertThat(outcome.signedOn()).isFalse();
            Assertions.assertThat(outcome.hasNextProgram()).isFalse();
            Assertions.assertThat(outcome.nextProgram())
                    .isEqualTo(spaces(SignOnService.NEXT_PROGRAM_LENGTH));
            Assertions.assertThat(outcome.role()).isEqualTo(spaces(SignOnService.ROLE_LENGTH));
            Assertions.assertThat(outcome.isAdminRole()).isFalse();
            Assertions.assertThat(outcome.termination()).isEqualTo(Termination.RETURN_TRANSID);
            Assertions.assertThat(outcome.returnTransid())
                    .contains(SignOnService.TRANSACTION_ID);
            Assertions.assertThat(outcome.navigationContext()).isEqualTo(NavigationContext.empty());
            Assertions.assertThat(outcome.receive()).isEqualTo(ReceiveOutcome.NOT_PERFORMED);
            Assertions.assertThat(outcome.readOutcome()).isEmpty();
        }

        @Test
        @DisplayName("validates nothing and reads nothing, whatever the fields hold")
        void neitherValidatesNorReads() {
            SignOnOutcome blankFields =
                    service.handle(SignOnInput.withoutCommarea(CicsAid.DFHENTER, null, null));

            Assertions.assertThat(blankFields.message())
                    .isEqualTo(spaces(SignOnService.MESSAGE_LENGTH));
            Assertions.assertThat(blankFields.errorFlag()).isFalse();
            verifyNoInteractions(repository);
        }

        @ParameterizedTest
        @ValueSource(bytes = {125, -13, 0, -3})
        @DisplayName("diverts before the EVALUATE, so the attention identifier is immaterial")
        void divertsWhateverTheKey(byte eibAid) {
            SignOnOutcome outcome =
                    service.handle(SignOnInput.withoutCommarea(eibAid, SEEDED_ADMIN_ID, STORED_PASSWORD));

            Assertions.assertThat(outcome.resetAllOutputFields()).isTrue();
            Assertions.assertThat(outcome.cursorField()).isEqualTo(CursorField.USER_ID);
            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("an absent area is EIBCALEN = 0; an initialised area is not")
        void absenceIsDistinctFromAnInitialisedArea() {
            Assertions.assertThat(
                    SignOnInput.withoutCommarea(CicsAid.DFHENTER, "A", "B").isCommareaPresent())
                    .isFalse();
            Assertions.assertThat(enterKey("A", "B").isCommareaPresent()).isTrue();
        }
    }

    @Nested
    @DisplayName("EVALUATE EIBAID, COSGN00C:85-95")
    class AttentionIdentifierArms {
        @Test
        @DisplayName("PF3 sends plain text, ends the conversation and sets no error flag")
        void pf3SignsOff() {
            NavigationContext inbound = NavigationContext.empty().withUserId(SEEDED_ADMIN_ID);

            SignOnOutcome outcome = service.handle(
                    new SignOnInput(inbound, CicsAid.DFHPF3, SEEDED_ADMIN_ID, STORED_PASSWORD));

            Assertions.assertThat(outcome.plainTextSent()).isTrue();
            Assertions.assertThat(outcome.screenPainted()).isFalse();
            Assertions.assertThat(outcome.resetAllOutputFields()).isFalse();
            Assertions.assertThat(outcome.message())
                    .isEqualTo(messageImage(SystemMessages.CCDA_MSG_THANK_YOU))
                    .hasSize(SignOnService.MESSAGE_LENGTH);
            Assertions.assertThat(outcome.errorFlag()).isFalse();
            Assertions.assertThat(outcome.cursorField()).isEqualTo(CursorField.NONE);
            Assertions.assertThat(outcome.termination()).isEqualTo(Termination.RETURN_NO_TRANSID);
            Assertions.assertThat(outcome.termination().conversationContinues()).isFalse();
            Assertions.assertThat(outcome.returnTransid()).isEmpty();
            Assertions.assertThat(outcome.navigationContext()).isEqualTo(inbound);
            Assertions.assertThat(outcome.readOutcome()).isEmpty();
            Assertions.assertThat(outcome.resolvedAid()).contains(PfKeyResolver.AidKey.PFK03);
            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("PF4 - a mapped key the program does not handle - takes WHEN OTHER")
        void aMappedButUnhandledKeyIsInvalid() {
            SignOnOutcome outcome = service.handle(
                    new SignOnInput(NavigationContext.empty(), CicsAid.DFHPF4, SEEDED_ADMIN_ID,
                            STORED_PASSWORD));

            assertInvalidKey(outcome);
            Assertions.assertThat(outcome.resolvedAid()).contains(PfKeyResolver.AidKey.PFK04);
        }

        @Test
        @DisplayName("PF15 resolves to PFK03 like PF3 but is still invalid - the token is not the "
                + "dispatcher")
        void pf15DoesNotBehaveAsPf3() {
            Assertions.assertThat(PfKeyResolver.resolve(CicsAid.DFHPF15))
                    .contains(PfKeyResolver.AidKey.PFK03);

            SignOnOutcome outcome = service.handle(
                    new SignOnInput(NavigationContext.empty(), CicsAid.DFHPF15, SEEDED_ADMIN_ID,
                            STORED_PASSWORD));

            assertInvalidKey(outcome);
            Assertions.assertThat(outcome.plainTextSent()).isFalse();
            Assertions.assertThat(outcome.resolvedAid()).contains(PfKeyResolver.AidKey.PFK03);
        }

        @Test
        @DisplayName("PA3 - a real AID that CSSTRPFY maps to nothing - takes WHEN OTHER")
        void aResolverNoMatchIsInvalid() {
            Assertions.assertThat(PfKeyResolver.resolve(CicsAid.DFHPA3)).isEmpty();

            SignOnOutcome outcome = service.handle(
                    new SignOnInput(NavigationContext.empty(), CicsAid.DFHPA3, SEEDED_ADMIN_ID,
                            STORED_PASSWORD));

            assertInvalidKey(outcome);
            Assertions.assertThat(outcome.resolvedAid()).isEmpty();
        }

        @Test
        @DisplayName("a byte that is no attention identifier at all takes WHEN OTHER too")
        void anUnknownByteIsInvalid() {
            Assertions.assertThat(PfKeyResolver.resolve(NOT_AN_AID)).isEmpty();

            SignOnOutcome outcome = service.handle(
                    new SignOnInput(NavigationContext.empty(), NOT_AN_AID, SEEDED_ADMIN_ID,
                            STORED_PASSWORD));

            assertInvalidKey(outcome);
            Assertions.assertThat(outcome.resolvedAid()).isEmpty();
        }

        @Test
        @DisplayName("CLEAR and PA1 are invalid keys here as well")
        void clearAndPa1AreInvalid() {
            assertInvalidKey(service.handle(new SignOnInput(NavigationContext.empty(),
                    CicsAid.DFHCLEAR, SEEDED_ADMIN_ID, STORED_PASSWORD)));
            assertInvalidKey(service.handle(new SignOnInput(NavigationContext.empty(),
                    CicsAid.DFHPA1, SEEDED_ADMIN_ID, STORED_PASSWORD)));
        }

        private void assertInvalidKey(SignOnOutcome outcome) {
            Assertions.assertThat(outcome.errorFlag()).isTrue();
            Assertions.assertThat(outcome.errFlgImage()).isEqualTo(SignOnService.ERR_FLG_ON);
            Assertions.assertThat(outcome.message())
                    .isEqualTo(messageImage(SystemMessages.CCDA_MSG_INVALID_KEY))
                    .hasSize(SignOnService.MESSAGE_LENGTH);
            Assertions.assertThat(outcome.screenPainted()).isTrue();
            Assertions.assertThat(outcome.resetAllOutputFields()).isFalse();
            Assertions.assertThat(outcome.cursorField()).isEqualTo(CursorField.NONE);
            Assertions.assertThat(outcome.cursorField().isPositioned()).isFalse();
            Assertions.assertThat(outcome.signedOn()).isFalse();
            Assertions.assertThat(outcome.termination()).isEqualTo(Termination.RETURN_TRANSID);
            Assertions.assertThat(outcome.receive()).isEqualTo(ReceiveOutcome.NOT_PERFORMED);
            Assertions.assertThat(outcome.readOutcome()).isEmpty();
            verifyNoInteractions(repository);
        }
    }

    @Nested
    @DisplayName("PROCESS-ENTER-KEY validation, COSGN00C:117-130")
    class ValidationArms {
        @ParameterizedTest
        @ValueSource(strings = {"        ", "", "   "})
        @DisplayName("an all-spaces user id asks for the user id")
        void spacesUserId(String userId) {
            assertBlankUserId(service.handle(enterKey(userId, STORED_PASSWORD)));
        }

        @Test
        @DisplayName("a user id the terminal never transmitted is LOW-VALUES and asks for the user id")
        void lowValuesUserId() {
            assertBlankUserId(service.handle(enterKey(null, STORED_PASSWORD)));
            assertBlankUserId(service.handle(
                    enterKey(lowValues(SignOnService.USER_ID_LENGTH), STORED_PASSWORD)));
        }

        @ParameterizedTest
        @ValueSource(strings = {"        ", "", "  "})
        @DisplayName("an all-spaces password asks for the password")
        void spacesPassword(String password) {
            assertBlankPassword(service.handle(enterKey(SEEDED_ADMIN_ID, password)));
        }

        @Test
        @DisplayName("a password the terminal never transmitted is LOW-VALUES and asks for the password")
        void lowValuesPassword() {
            assertBlankPassword(service.handle(enterKey(SEEDED_ADMIN_ID, null)));
            assertBlankPassword(service.handle(
                    enterKey(SEEDED_ADMIN_ID, lowValues(SignOnService.PASSWORD_LENGTH))));
        }

        @Test
        @DisplayName("EVALUATE stops at the first match, so both blank asks for the user id only")
        void bothBlankAsksForTheUserIdFirst() {
            assertBlankUserId(service.handle(enterKey(null, null)));
            assertBlankUserId(service.handle(enterKey("   ", "   ")));
        }

        @Test
        @DisplayName("a field mixing low-values and spaces satisfies neither test and is used as data")
        void aMixedFieldIsData() {
            stubRead(ReadResult.notFound());
            String mixed = lowValues(4) + spaces(4);

            SignOnOutcome outcome = service.handle(enterKey(mixed, STORED_PASSWORD));

            Assertions.assertThat(outcome.message())
                    .isEqualTo(messageImage(SignOnService.MSG_USER_NOT_FOUND));
            verify(repository).read(mixed);
        }

        @Test
        @DisplayName("the uppercase normalisation runs on the blank-password path, and the read does "
                + "not - COSGN00C:130-140")
        void normalisationHappensEvenWhenValidationFailed() {
            SignOnOutcome outcome = service.handle(enterKey("adm1", "   "));

            Assertions.assertThat(outcome.navigationContext().userId()).isEqualTo("ADM1    ");
            Assertions.assertThat(outcome.message())
                    .isEqualTo(messageImage(SignOnService.MSG_ENTER_PASSWORD));
            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("the normalisation runs on the blank-user-id path too, storing LOW-VALUES")
        void normalisationOnTheBlankUserIdPath() {
            SignOnOutcome fromNull = service.handle(enterKey(null, STORED_PASSWORD));
            SignOnOutcome fromSpaces = service.handle(enterKey("", STORED_PASSWORD));

            Assertions.assertThat(fromNull.navigationContext().userId())
                    .isEqualTo(lowValues(SignOnService.USER_ID_LENGTH));
            Assertions.assertThat(fromSpaces.navigationContext().userId())
                    .isEqualTo(spaces(SignOnService.USER_ID_LENGTH));
            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("neither validation arm touches the rest of the communication area")
        void validationLeavesTheRestOfTheAreaAlone() {
            NavigationContext inbound = NavigationContext.empty()
                    .withFromProgram("COMEN01C")
                    .withUserType(NavigationContext.USER_TYPE_ADMIN)
                    .withPgmReenter()
                    .withAcctId(12345678901L);

            SignOnOutcome outcome = service.handle(
                    new SignOnInput(inbound, CicsAid.DFHENTER, "", STORED_PASSWORD));

            Assertions.assertThat(outcome.navigationContext())
                    .isEqualTo(inbound.withUserId(spaces(SignOnService.USER_ID_LENGTH)));
            Assertions.assertThat(outcome.navigationContext().fromProgram()).isEqualTo("COMEN01C");
            Assertions.assertThat(outcome.navigationContext().acctId()).isEqualTo(12345678901L);
            Assertions.assertThat(outcome.navigationContext().isReenter()).isTrue();
            Assertions.assertThat(outcome.role()).isEqualTo(spaces(SignOnService.ROLE_LENGTH));
        }

        @Test
        @DisplayName("WHEN OTHER is a bare CONTINUE, so two present fields fall straight through to "
                + "the read - COSGN00C:128-129")
        void bothFieldsPresentFallThroughToTheRead() {
            stubRead(ReadResult.notFound());

            SignOnOutcome outcome = service.handle(enterKey(SEEDED_ADMIN_ID, STORED_PASSWORD));

            verify(repository).read(SEEDED_ADMIN_ID);
            Assertions.assertThat(outcome.readOutcome()).contains(FileStatus.Outcome.NOT_FOUND);
            Assertions.assertThat(outcome.message())
                    .isEqualTo(messageImage(SignOnService.MSG_USER_NOT_FOUND))
                    .isNotEqualTo(messageImage(SignOnService.MSG_ENTER_USER_ID))
                    .isNotEqualTo(messageImage(SignOnService.MSG_ENTER_PASSWORD));
        }

        @Test
        @DisplayName("the fall-through leaves the error flag off up to the point the read decides it")
        void theFallThroughArmSetsNoErrorFlagOfItsOwn() {
            stubRead(ReadResult.found(storedUser(SEEDED_ADMIN_ID, STORED_PASSWORD,
                    NavigationContext.USER_TYPE_ADMIN)));

            SignOnOutcome outcome = service.handle(enterKey(SEEDED_ADMIN_ID, WRONG_PASSWORD));

            Assertions.assertThat(outcome.errorFlag()).isFalse();
            Assertions.assertThat(outcome.errFlgImage()).isEqualTo(SignOnService.ERR_FLG_OFF);
            Assertions.assertThat(outcome.message())
                    .isEqualTo(messageImage(SignOnService.MSG_WRONG_PASSWORD));
        }

        @Test
        @DisplayName("the RECEIVE ran on this path, and its response is carried untested")
        void theReceiveIsPerformedAndCarried() {
            SignOnOutcome outcome = service.handle(enterKey("", ""));

            Assertions.assertThat(outcome.receive()).isEqualTo(ReceiveOutcome.normal());
            Assertions.assertThat(outcome.receive().performed()).isTrue();
            Assertions.assertThat(outcome.receive().respCode()).isEqualTo(SignOnService.RESP_NORMAL);
            Assertions.assertThat(outcome.receive().reasonCode())
                    .isEqualTo(FileStatus.NO_REASON_CODE);
        }

        private void assertBlankUserId(SignOnOutcome outcome) {
            Assertions.assertThat(outcome.message())
                    .isEqualTo(messageImage(SignOnService.MSG_ENTER_USER_ID))
                    .hasSize(SignOnService.MESSAGE_LENGTH);
            Assertions.assertThat(outcome.errorFlag()).isTrue();
            Assertions.assertThat(outcome.cursorField()).isEqualTo(CursorField.USER_ID);
            Assertions.assertThat(outcome.cursorField().lengthItemName()).contains("USERIDL");
            Assertions.assertThat(outcome.screenPainted()).isTrue();
            Assertions.assertThat(outcome.resetAllOutputFields()).isFalse();
            Assertions.assertThat(outcome.signedOn()).isFalse();
            Assertions.assertThat(outcome.termination()).isEqualTo(Termination.RETURN_TRANSID);
            Assertions.assertThat(outcome.readOutcome()).isEmpty();
            verifyNoInteractions(repository);
        }

        private void assertBlankPassword(SignOnOutcome outcome) {
            Assertions.assertThat(outcome.message())
                    .isEqualTo(messageImage(SignOnService.MSG_ENTER_PASSWORD))
                    .hasSize(SignOnService.MESSAGE_LENGTH);
            Assertions.assertThat(outcome.errorFlag()).isTrue();
            Assertions.assertThat(outcome.cursorField()).isEqualTo(CursorField.PASSWORD);
            Assertions.assertThat(outcome.cursorField().lengthItemName()).contains("PASSWDL");
            Assertions.assertThat(outcome.screenPainted()).isTrue();
            Assertions.assertThat(outcome.signedOn()).isFalse();
            Assertions.assertThat(outcome.readOutcome()).isEmpty();
            verifyNoInteractions(repository);
        }
    }

    @Nested
    @DisplayName("READ-USER-SEC-FILE, COSGN00C:211-257")
    class ReadArms {
        @Test
        @DisplayName("WHEN 0 with a matching password signs an administrator on to COADM01C")
        void administratorSignsOn() {
            stubRead(ReadResult.found(storedUser(SEEDED_ADMIN_ID, STORED_PASSWORD,
                    NavigationContext.USER_TYPE_ADMIN)));

            SignOnOutcome outcome = service.handle(enterKey("admin001", STORED_PASSWORD));

            Assertions.assertThat(outcome.signedOn()).isTrue();
            Assertions.assertThat(outcome.termination()).isEqualTo(Termination.XCTL);
            Assertions.assertThat(outcome.termination().conversationContinues()).isFalse();
            Assertions.assertThat(outcome.returnTransid()).isEmpty();
            Assertions.assertThat(outcome.nextProgram()).isEqualTo("COADM01C");
            Assertions.assertThat(outcome.hasNextProgram()).isTrue();
            Assertions.assertThat(outcome.role()).isEqualTo(NavigationContext.USER_TYPE_ADMIN);
            Assertions.assertThat(outcome.isAdminRole()).isTrue();
            Assertions.assertThat(outcome.message()).isEqualTo(spaces(SignOnService.MESSAGE_LENGTH));
            Assertions.assertThat(outcome.errorFlag()).isFalse();
            Assertions.assertThat(outcome.cursorField()).isEqualTo(CursorField.NONE);
            Assertions.assertThat(outcome.screenPainted()).isFalse();
            Assertions.assertThat(outcome.plainTextSent()).isFalse();
            Assertions.assertThat(outcome.readOutcome()).contains(FileStatus.Outcome.OK);

            NavigationContext area = outcome.navigationContext();
            Assertions.assertThat(area.fromTranid()).isEqualTo(SignOnService.TRANSACTION_ID);
            Assertions.assertThat(area.fromProgram()).isEqualTo(SignOnService.PROGRAM_NAME);
            Assertions.assertThat(area.userId()).isEqualTo(SEEDED_ADMIN_ID);
            Assertions.assertThat(area.userType()).isEqualTo(NavigationContext.USER_TYPE_ADMIN);
            Assertions.assertThat(area.isAdmin()).isTrue();
            Assertions.assertThat(area.isUser()).isFalse();
            Assertions.assertThat(area.pgmContext()).isEqualTo(NavigationContext.PGM_CONTEXT_ENTER);
            Assertions.assertThat(area.isEnter()).isTrue();
            Assertions.assertThat(area.isReenter()).isFalse();
        }

        @Test
        @DisplayName("a regular user signs on to COMEN01C")
        void regularUserSignsOn() {
            stubRead(ReadResult.found(storedUser(SEEDED_USER_ID, STORED_PASSWORD,
                    NavigationContext.USER_TYPE_USER)));

            SignOnOutcome outcome = service.handle(enterKey(SEEDED_USER_ID, STORED_PASSWORD));

            Assertions.assertThat(outcome.signedOn()).isTrue();
            Assertions.assertThat(outcome.nextProgram()).isEqualTo("COMEN01C");
            Assertions.assertThat(outcome.role()).isEqualTo(NavigationContext.USER_TYPE_USER);
            Assertions.assertThat(outcome.isAdminRole()).isFalse();
            Assertions.assertThat(outcome.navigationContext().isUser()).isTrue();
            Assertions.assertThat(outcome.navigationContext().isAdmin()).isFalse();
        }

        @ParameterizedTest
        @ValueSource(strings = {" ", "X", "a", "0"})
        @DisplayName("any user type but 'A' takes the ELSE branch to COMEN01C - a two-way split")
        void anyNonAdminTypeGoesToTheUserMenu(String userType) {
            stubRead(ReadResult.found(storedUser(SEEDED_USER_ID, STORED_PASSWORD, userType)));

            SignOnOutcome outcome = service.handle(enterKey(SEEDED_USER_ID, STORED_PASSWORD));

            Assertions.assertThat(outcome.signedOn()).isTrue();
            Assertions.assertThat(outcome.nextProgram()).isEqualTo("COMEN01C");
            Assertions.assertThat(outcome.role()).isEqualTo(userType);
            Assertions.assertThat(outcome.isAdminRole()).isFalse();
        }

        @Test
        @DisplayName("the comparison is case-insensitive on the submitted side only")
        void caseFoldingAppliesToTheSubmittedSideOnly() {
            stubRead(ReadResult.found(storedUser(SEEDED_ADMIN_ID, STORED_PASSWORD,
                    NavigationContext.USER_TYPE_ADMIN)));

            Assertions.assertThat(service.handle(enterKey(SEEDED_ADMIN_ID, "password")).signedOn())
                    .isTrue();
        }

        @ParameterizedTest
        @CsvSource({"admin001,password", "AdMiN001,PaSsWoRd", "ADMIN001,PASSWORD", "aDMIN001,passworD"})
        @DisplayName("however the two fields are typed, the key read is ADMIN001 and the value compared "
                + "is PASSWORD - COSGN00C:132-137")
        void everyCasingNormalisesToTheSameKeyAndTheSameComparand(String typedId, String typedPassword) {
            stubRead(ReadResult.found(storedUser(SEEDED_ADMIN_ID, STORED_PASSWORD,
                    NavigationContext.USER_TYPE_ADMIN)));
            ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);

            SignOnOutcome outcome = service.handle(enterKey(typedId, typedPassword));

            verify(repository).read(key.capture());
            Assertions.assertThat(key.getValue())
                    .isEqualTo(SEEDED_ADMIN_ID)
                    .isEqualTo(SignOnService.upperCase(typedId))
                    .hasSize(SignOnService.USER_ID_LENGTH);

            Assertions.assertThat(outcome.signedOn()).isTrue();
            Assertions.assertThat(outcome.navigationContext().userId()).isEqualTo(SEEDED_ADMIN_ID);
        }

        @Test
        @DisplayName("the comparison is on the clear text: the seeded literal matches, a different "
                + "eight-character value does not - COSGN00C:223")
        void theComparisonIsPlaintext() {
            stubRead(ReadResult.found(storedUser(SEEDED_ADMIN_ID, STORED_PASSWORD,
                    NavigationContext.USER_TYPE_ADMIN)));

            Assertions.assertThat(service.handle(enterKey(SEEDED_ADMIN_ID, STORED_PASSWORD)).signedOn())
                    .isTrue();

            SignOnOutcome mismatch = service.handle(enterKey(SEEDED_ADMIN_ID, WRONG_PASSWORD));
            Assertions.assertThat(WRONG_PASSWORD)
                    .hasSameSizeAs(STORED_PASSWORD)
                    .isNotEqualTo(STORED_PASSWORD);
            Assertions.assertThat(mismatch.signedOn()).isFalse();
            Assertions.assertThat(mismatch.message())
                    .isEqualTo(messageImage(SignOnService.MSG_WRONG_PASSWORD));
        }

        @Test
        @DisplayName("a ninth character cannot reach the comparison at all, because PASSWDI is "
                + "PIC X(8) - so a trailing '1' is truncated away and the sign-on still succeeds")
        void aNinthPasswordCharacterIsTruncatedBeforeTheComparison() {
            stubRead(ReadResult.found(storedUser(SEEDED_ADMIN_ID, STORED_PASSWORD,
                    NavigationContext.USER_TYPE_ADMIN)));

            SignOnOutcome outcome = service.handle(enterKey(SEEDED_ADMIN_ID, STORED_PASSWORD + "1"));

            Assertions.assertThat(outcome.signedOn()).isTrue();
            Assertions.assertThat(outcome.nextProgram()).isEqualTo(SignOnService.ADMIN_PROGRAM);

            Assertions.assertThat(STORED_PASSWORD + "1")
                    .hasSize(SignOnService.PASSWORD_LENGTH + 1)
                    .startsWith(STORED_PASSWORD);
        }

        @Test
        @DisplayName("a stored lower-case password can never be matched, because it is used verbatim")
        void aStoredLowerCasePasswordNeverMatches() {
            stubRead(ReadResult.found(storedUser(SEEDED_ADMIN_ID, "password",
                    NavigationContext.USER_TYPE_ADMIN)));

            SignOnOutcome submittedLower = service.handle(enterKey(SEEDED_ADMIN_ID, "password"));
            SignOnOutcome submittedUpper = service.handle(enterKey(SEEDED_ADMIN_ID, "PASSWORD"));

            Assertions.assertThat(submittedLower.signedOn()).isFalse();
            Assertions.assertThat(submittedUpper.signedOn()).isFalse();
        }

        @Test
        @DisplayName("trailing spaces are part of the comparison, so a prefix does not match")
        void aPrefixDoesNotMatch() {
            stubRead(ReadResult.found(storedUser(SEEDED_ADMIN_ID, STORED_PASSWORD,
                    NavigationContext.USER_TYPE_ADMIN)));

            Assertions.assertThat(service.handle(enterKey(SEEDED_ADMIN_ID, "PASS")).signedOn()).isFalse();
        }

        @Test
        @DisplayName("WHEN 0 with a wrong password produces a message but NO error flag - :241-246")
        void wrongPasswordDoesNotSetTheErrorFlag() {
            stubRead(ReadResult.found(storedUser(SEEDED_ADMIN_ID, STORED_PASSWORD,
                    NavigationContext.USER_TYPE_ADMIN)));

            SignOnOutcome outcome = service.handle(enterKey(SEEDED_ADMIN_ID, "WRONG"));

            Assertions.assertThat(outcome.message())
                    .isEqualTo(messageImage(SignOnService.MSG_WRONG_PASSWORD))
                    .hasSize(SignOnService.MESSAGE_LENGTH);
            Assertions.assertThat(outcome.errorFlag()).isFalse();
            Assertions.assertThat(outcome.errFlgImage()).isEqualTo(SignOnService.ERR_FLG_OFF);
            Assertions.assertThat(outcome.cursorField()).isEqualTo(CursorField.PASSWORD);
            Assertions.assertThat(outcome.screenPainted()).isTrue();
            Assertions.assertThat(outcome.signedOn()).isFalse();
            Assertions.assertThat(outcome.nextProgram())
                    .isEqualTo(spaces(SignOnService.NEXT_PROGRAM_LENGTH));
            Assertions.assertThat(outcome.role()).isEqualTo(spaces(SignOnService.ROLE_LENGTH));
            Assertions.assertThat(outcome.termination()).isEqualTo(Termination.RETURN_TRANSID);
            Assertions.assertThat(outcome.readOutcome()).contains(FileStatus.Outcome.OK);
            Assertions.assertThat(outcome.navigationContext().userId()).isEqualTo(SEEDED_ADMIN_ID);
            Assertions.assertThat(outcome.navigationContext().userType())
                    .isEqualTo(spaces(SignOnService.ROLE_LENGTH));
        }

        @Test
        @DisplayName("WHEN 13 - the user id is not in USRSEC")
        void notFound() {
            stubRead(ReadResult.notFound());

            SignOnOutcome outcome = service.handle(enterKey("nobody", STORED_PASSWORD));

            Assertions.assertThat(outcome.message())
                    .isEqualTo(messageImage(SignOnService.MSG_USER_NOT_FOUND));
            Assertions.assertThat(outcome.errorFlag()).isTrue();
            Assertions.assertThat(outcome.errFlgImage()).isEqualTo(SignOnService.ERR_FLG_ON);
            Assertions.assertThat(outcome.cursorField()).isEqualTo(CursorField.USER_ID);
            Assertions.assertThat(outcome.screenPainted()).isTrue();
            Assertions.assertThat(outcome.signedOn()).isFalse();
            Assertions.assertThat(outcome.readOutcome()).contains(FileStatus.Outcome.NOT_FOUND);
        }

        @Test
        @DisplayName("WHEN OTHER covers end-of-file, which is neither 0 nor 13")
        void endOfFileTakesWhenOther() {
            stubRead(ReadResult.endOfFile());

            assertUnableToVerify(service.handle(enterKey(SEEDED_ADMIN_ID, STORED_PASSWORD)),
                    FileStatus.Outcome.END_OF_FILE);
        }

        @Test
        @DisplayName("WHEN OTHER covers a duplicate classification too")
        void duplicateTakesWhenOther() {
            stubRead(ReadResult.of(FileStatus.DUPLICATE, CicsResponse.of(FileStatus.DUPREC)));

            assertUnableToVerify(service.handle(enterKey(SEEDED_ADMIN_ID, STORED_PASSWORD)),
                    FileStatus.Outcome.DUPLICATE);
        }

        @Test
        @DisplayName("WHEN OTHER covers a backend refusal")
        void aRefusalTakesWhenOther() {
            stubRead(ReadResult.of(SecUserRepository.PERMANENT_ERROR_STATUS, CicsResponse.none()));

            assertUnableToVerify(service.handle(enterKey(SEEDED_ADMIN_ID, STORED_PASSWORD)),
                    FileStatus.Outcome.OTHER);
        }

        @Test
        @DisplayName("the key handed to the repository is the upper-cased eight-character RIDFLD")
        void theKeyIsTheNormalisedRidfld() {
            stubRead(ReadResult.notFound());
            ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);

            service.handle(enterKey("ad", STORED_PASSWORD));

            verify(repository).read(key.capture());
            Assertions.assertThat(key.getValue())
                    .isEqualTo("AD      ")
                    .hasSize(SignOnService.USER_ID_LENGTH);
        }

        @Test
        @DisplayName("a wiring failure propagates rather than becoming 'Unable to verify the User ...'")
        void aWiringFailurePropagates() {
            when(repository.read(anyString()))
                    .thenThrow(new IllegalStateException("USRSEC is unconfigured"));

            Assertions.assertThatThrownBy(() -> service.handle(enterKey(SEEDED_ADMIN_ID, STORED_PASSWORD)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("USRSEC");
        }

        private void assertUnableToVerify(SignOnOutcome outcome, FileStatus.Outcome expected) {
            Assertions.assertThat(outcome.message())
                    .isEqualTo(messageImage(SignOnService.MSG_UNABLE_TO_VERIFY));
            Assertions.assertThat(outcome.errorFlag()).isTrue();
            Assertions.assertThat(outcome.cursorField()).isEqualTo(CursorField.USER_ID);
            Assertions.assertThat(outcome.screenPainted()).isTrue();
            Assertions.assertThat(outcome.signedOn()).isFalse();
            Assertions.assertThat(outcome.termination()).isEqualTo(Termination.RETURN_TRANSID);
            Assertions.assertThat(outcome.readOutcome()).contains(expected);
        }
    }

    @Nested
    @DisplayName("resolveNextProgram, COSGN00C:230-240")
    class RoleRouting {
        @Test
        @DisplayName("only an exact 'A' routes to the administrator menu")
        void adminRoutesToAdminMenu() {
            Assertions.assertThat(service.resolveNextProgram(
                    NavigationContext.empty().withUserTypeAdmin()))
                    .isEqualTo(SignOnService.ADMIN_PROGRAM);
        }

        @ParameterizedTest
        @ValueSource(strings = {"U", " ", "a", "Z", "1"})
        @DisplayName("every other value routes to the main menu - no third branch")
        void everythingElseRoutesToTheMainMenu(String userType) {
            Assertions.assertThat(service.resolveNextProgram(
                    NavigationContext.empty().withUserType(userType)))
                    .isEqualTo(SignOnService.USER_PROGRAM)
                    .hasSize(SignOnService.NEXT_PROGRAM_LENGTH);
        }

        @Test
        @DisplayName("a blank area - which satisfies neither 88-level - still routes somewhere")
        void aBlankAreaRoutesToTheMainMenu() {
            NavigationContext blank = NavigationContext.empty();
            Assertions.assertThat(blank.isAdmin()).isFalse();
            Assertions.assertThat(blank.isUser()).isFalse();
            Assertions.assertThat(service.resolveNextProgram(blank))
                    .isEqualTo(SignOnService.USER_PROGRAM);
        }

        @Test
        @DisplayName("a communication area is required")
        void areaIsRequired() {
            Assertions.assertThatThrownBy(() -> service.resolveNextProgram(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("CDEMO-USER-TYPE");
        }
    }

    @Nested
    @DisplayName("FUNCTION UPPER-CASE, COSGN00C:132 and :135")
    class UpperCase {
        @ParameterizedTest
        @ValueSource(strings = {"admin001", "ADMIN001", "AdMiN001", "aDmIn001"})
        @DisplayName("folds a to z and leaves digits alone")
        void foldsLettersOnly(String value) {
            Assertions.assertThat(SignOnService.upperCase(value)).isEqualTo(SEEDED_ADMIN_ID);
        }

        @ParameterizedTest
        @ValueSource(strings = {"        ", "\u0000\u0000", "12345678", "@[`{|}~", "-_. ,;"})
        @DisplayName("leaves every character outside a to z exactly as it is")
        void leavesOtherCharactersUntouched(String value) {
            Assertions.assertThat(SignOnService.upperCase(value)).isEqualTo(value);
        }

        @Test
        @DisplayName("preserves length, where String.toUpperCase would not")
        void preservesLength() {
            String sharpS = "stra\u00dfe  ";
            Assertions.assertThat(sharpS).hasSize(SignOnService.USER_ID_LENGTH);
            Assertions.assertThat(SignOnService.upperCase(sharpS))
                    .hasSize(SignOnService.USER_ID_LENGTH)
                    .isEqualTo("STRA\u00dfE  ");
            Assertions.assertThat(sharpS.toUpperCase(java.util.Locale.ROOT))
                    .hasSizeGreaterThan(SignOnService.USER_ID_LENGTH);
        }

        @Test
        @DisplayName("the boundary characters either side of a to z are not folded")
        void boundariesAreExclusive() {
            Assertions.assertThat(SignOnService.upperCase("`")).isEqualTo("`");
            Assertions.assertThat(SignOnService.upperCase("a")).isEqualTo("A");
            Assertions.assertThat(SignOnService.upperCase("z")).isEqualTo("Z");
            Assertions.assertThat(SignOnService.upperCase("{")).isEqualTo("{");
        }

        @Test
        @DisplayName("an empty field is legal and a null one is not")
        void emptyIsLegalNullIsNot() {
            Assertions.assertThat(SignOnService.upperCase("")).isEmpty();
            Assertions.assertThatThrownBy(() -> SignOnService.upperCase(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("UPPER-CASE");
        }

        @Test
        @DisplayName("an over-long submitted user id is truncated on the right, not rejected")
        void anOverLongUserIdIsTruncatedOnTheRight() {
            stubRead(ReadResult.notFound());

            service.handle(enterKey("verylonguserid", STORED_PASSWORD));

            verify(repository).read("VERYLONG");
        }
    }

    @Nested
    @DisplayName("Statelessness and confidentiality")
    class StatelessnessAndConfidentiality {
        @Test
        @DisplayName("two successive calls cannot influence one another")
        void noStateSurvivesACall() {
            stubRead(ReadResult.found(storedUser(SEEDED_ADMIN_ID, STORED_PASSWORD,
                    NavigationContext.USER_TYPE_ADMIN)));

            SignOnOutcome first = service.handle(enterKey(SEEDED_ADMIN_ID, STORED_PASSWORD));
            SignOnOutcome second = service.handle(enterKey("", ""));
            SignOnOutcome third = service.handle(enterKey(SEEDED_ADMIN_ID, STORED_PASSWORD));

            Assertions.assertThat(first.signedOn()).isTrue();
            Assertions.assertThat(second.signedOn()).isFalse();
            Assertions.assertThat(second.role()).isEqualTo(spaces(SignOnService.ROLE_LENGTH));
            Assertions.assertThat(second.message())
                    .isEqualTo(messageImage(SignOnService.MSG_ENTER_USER_ID));
            Assertions.assertThat(second.navigationContext().fromProgram())
                    .isEqualTo(spaces(NavigationContext.FROM_PROGRAM_LENGTH));
            Assertions.assertThat(third).isEqualTo(first);
        }

        @Test
        @DisplayName("the inbound communication area is never mutated")
        void theInboundAreaIsNeverMutated() {
            stubRead(ReadResult.found(storedUser(SEEDED_ADMIN_ID, STORED_PASSWORD,
                    NavigationContext.USER_TYPE_ADMIN)));
            NavigationContext inbound = NavigationContext.empty();

            SignOnOutcome outcome = service.handle(
                    new SignOnInput(inbound, CicsAid.DFHENTER, SEEDED_ADMIN_ID, STORED_PASSWORD));

            Assertions.assertThat(inbound).isEqualTo(NavigationContext.empty());
            Assertions.assertThat(outcome.navigationContext()).isNotEqualTo(inbound);
        }

        @Test
        @DisplayName("the input's rendering redacts the password")
        void theInputRedactsThePassword() {
            String fakePassword = "N0TAREAL";

            String rendered = enterKey(SEEDED_ADMIN_ID, fakePassword).toString();

            Assertions.assertThat(rendered)
                    .doesNotContain(fakePassword)
                    .contains(NavigationContext.REDACTED)
                    .contains(SEEDED_ADMIN_ID)
                    .contains("eibAid=0x7D");
            Assertions.assertThat(rendered.lines()).hasSize(1);
        }

        @Test
        @DisplayName("the attention identifier renders as an unsigned byte, not a sign-extended int")
        void theAidRendersUnsigned() {
            String rendered = new SignOnInput(NavigationContext.empty(), CicsAid.DFHPF3, "A", "B")
                    .toString();

            Assertions.assertThat(rendered).contains("eibAid=0xF3").doesNotContain("FFFFFFF3");
        }

        @Test
        @DisplayName("the outcome carries no password component, and its rendering leaks none")
        void theOutcomeCarriesNoPassword() {
            String fakePassword = "N0TAREAL";
            stubRead(ReadResult.found(storedUser(SEEDED_ADMIN_ID, fakePassword,
                    NavigationContext.USER_TYPE_ADMIN)));

            SignOnOutcome outcome = service.handle(enterKey(SEEDED_ADMIN_ID, fakePassword));

            Assertions.assertThat(outcome.signedOn()).isTrue();
            Assertions.assertThat(outcome.toString()).doesNotContain(fakePassword);
            for (RecordComponent component : SignOnOutcome.class.getRecordComponents()) {
                Assertions.assertThat(component.getName().toLowerCase(Locale.ROOT))
                        .doesNotContain("pwd")
                        .doesNotContain("password")
                        .doesNotContain("secret")
                        .doesNotContain("credential");
            }
        }

        @Test
        @DisplayName("the service declares no hashing, token or filter-chain collaborator")
        void noSecurityCollaboratorIsIntroduced() {
            Assertions.assertThat(SignOnService.class.getDeclaredFields())
                    .allSatisfy(field -> Assertions.assertThat(field.getType().getName())
                            .doesNotContain("security")
                            .doesNotContain("crypto")
                            .doesNotContain("Encoder")
                            .doesNotContain("Digest"));
        }
    }

    @Nested
    @DisplayName("Governing-practice guards")
    class GoverningPracticeGuards {
        @Test
        @DisplayName("both states of both condition names are driven, and 'Y' and 'N' are the only two "
                + "images WS-ERR-FLG ever holds - COSGN00C:40-42")
        void bothStatesOfTheOnlyTwoConditionNamesAreDriven() {
            stubRead(ReadResult.found(storedUser(SEEDED_ADMIN_ID, STORED_PASSWORD,
                    NavigationContext.USER_TYPE_ADMIN)));

            SignOnOutcome firstEntry = service.handle(SignOnInput.withoutCommarea(
                    CicsAid.DFHENTER, SEEDED_ADMIN_ID, STORED_PASSWORD));
            SignOnOutcome signOff = service.handle(new SignOnInput(
                    NavigationContext.empty(), CicsAid.DFHPF3, SEEDED_ADMIN_ID, STORED_PASSWORD));
            SignOnOutcome signedOn = service.handle(enterKey(SEEDED_ADMIN_ID, STORED_PASSWORD));
            SignOnOutcome wrongPassword = service.handle(enterKey(SEEDED_ADMIN_ID, WRONG_PASSWORD));

            Assertions.assertThat(firstEntry.errorFlag()).isFalse();
            Assertions.assertThat(signOff.errorFlag()).isFalse();
            Assertions.assertThat(signedOn.errorFlag()).isFalse();
            Assertions.assertThat(wrongPassword.errorFlag()).isFalse();
            Assertions.assertThat(firstEntry.errFlgImage())
                    .isEqualTo(signOff.errFlgImage())
                    .isEqualTo(signedOn.errFlgImage())
                    .isEqualTo(wrongPassword.errFlgImage())
                    .isEqualTo(SignOnService.ERR_FLG_OFF);

            SignOnOutcome invalidKey = service.handle(new SignOnInput(
                    NavigationContext.empty(), CicsAid.DFHPF4, SEEDED_ADMIN_ID, STORED_PASSWORD));
            SignOnOutcome blankUserId = service.handle(enterKey("", STORED_PASSWORD));
            SignOnOutcome blankPassword = service.handle(enterKey(SEEDED_ADMIN_ID, ""));

            Assertions.assertThat(invalidKey.errorFlag()).isTrue();
            Assertions.assertThat(blankUserId.errorFlag()).isTrue();
            Assertions.assertThat(blankPassword.errorFlag()).isTrue();

            stubRead(ReadResult.notFound());
            SignOnOutcome notFound = service.handle(enterKey(SEEDED_USER_ID, STORED_PASSWORD));
            stubRead(ReadResult.endOfFile());
            SignOnOutcome unableToVerify = service.handle(enterKey(SEEDED_USER_ID, STORED_PASSWORD));

            Assertions.assertThat(notFound.errorFlag()).isTrue();
            Assertions.assertThat(unableToVerify.errorFlag()).isTrue();
            Assertions.assertThat(invalidKey.errFlgImage())
                    .isEqualTo(blankUserId.errFlgImage())
                    .isEqualTo(blankPassword.errFlgImage())
                    .isEqualTo(notFound.errFlgImage())
                    .isEqualTo(unableToVerify.errFlgImage())
                    .isEqualTo(SignOnService.ERR_FLG_ON);

            Assertions.assertThat(SignOnService.ERR_FLG_ON).isEqualTo("Y").hasSize(1);
            Assertions.assertThat(SignOnService.ERR_FLG_OFF).isEqualTo("N").hasSize(1);
            Assertions.assertThat(SignOnService.ERR_FLG_ON).isNotEqualTo(SignOnService.ERR_FLG_OFF);
        }

        @Test
        @DisplayName("no clock, no calendar and no randomness is in the decision graph, so no fixed "
                + "Clock has to be injected")
        void nothingTimeDependentReachesADecision() {
            for (Field field : SignOnService.class.getDeclaredFields()) {
                Assertions.assertThat(field.getType().getName())
                        .as("field %s of SignOnService", field.getName())
                        .doesNotStartWith("java.time")
                        .doesNotContain("Random")
                        .doesNotContain("Clock")
                        .doesNotContain("DateHeader");
            }
            for (RecordComponent component : SignOnOutcome.class.getRecordComponents()) {
                Assertions.assertThat(component.getType().getName())
                        .as("component %s of SignOnOutcome", component.getName())
                        .doesNotStartWith("java.time");
            }
            for (RecordComponent component : SignOnInput.class.getRecordComponents()) {
                Assertions.assertThat(component.getType().getName())
                        .as("component %s of SignOnInput", component.getName())
                        .doesNotStartWith("java.time");
            }

            stubRead(ReadResult.found(storedUser(SEEDED_ADMIN_ID, STORED_PASSWORD,
                    NavigationContext.USER_TYPE_ADMIN)));
            SignOnInput input = enterKey(SEEDED_ADMIN_ID, STORED_PASSWORD);

            Assertions.assertThat(service.handle(input)).isEqualTo(service.handle(input));
        }

        @Test
        @DisplayName("the collaborator set is exactly the repository, injected through the constructor")
        void theOnlyCollaboratorIsTheRepositoryAndItArrivesByConstructor() {
            Field[] instanceFields = Arrays.stream(SignOnService.class.getDeclaredFields())
                    .filter(field -> !field.isSynthetic())
                    .filter(field -> !Modifier.isStatic(field.getModifiers()))
                    .toArray(Field[]::new);

            Assertions.assertThat(instanceFields).hasSize(1);
            Assertions.assertThat(instanceFields[0].getType()).isEqualTo(SecUserRepository.class);
            Assertions.assertThat(Modifier.isFinal(instanceFields[0].getModifiers()))
                    .as("the collaborator field is final")
                    .isTrue();

            Assertions.assertThat(SignOnService.class.getDeclaredConstructors()).hasSize(1);
            Assertions.assertThat(SignOnService.class.getDeclaredConstructors()[0].getParameterTypes())
                    .containsExactly(SecUserRepository.class);

            for (Field field : SignOnService.class.getDeclaredFields()) {
                for (Annotation annotation : field.getAnnotations()) {
                    Assertions.assertThat(annotation.annotationType().getSimpleName())
                            .as("annotation on field %s", field.getName())
                            .isNotIn("Autowired", "Inject", "Resource", "Value");
                }
            }
        }

        @Test
        @DisplayName("no static mutable state exists, so two concurrent sign-ons cannot see each other")
        void noStaticMutableStateExists() {
            assertEveryDeclaredStaticFieldIsFinal(SignOnService.class);
            assertEveryDeclaredStaticFieldIsFinal(SignOnServiceTest.class);
        }

        private void assertEveryDeclaredStaticFieldIsFinal(Class<?> type) {
            for (Field field : type.getDeclaredFields()) {
                if (field.isSynthetic() || !Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                Assertions.assertThat(Modifier.isFinal(field.getModifiers()))
                        .as("static field %s of %s is final", field.getName(), type.getSimpleName())
                        .isTrue();
            }
        }

        @Test
        @DisplayName("conversation state travels in the payload: no session, cache or thread-local "
                + "collaborator exists")
        void conversationStateTravelsInThePayloadAndNowhereElse() {
            for (Field field : SignOnService.class.getDeclaredFields()) {
                Assertions.assertThat(field.getType().getName())
                        .as("field %s of SignOnService", field.getName())
                        .doesNotContain("Session")
                        .doesNotContain("session")
                        .doesNotContain("servlet")
                        .doesNotContain("Cache")
                        .doesNotContain("ThreadLocal")
                        .doesNotContain("Request")
                        .doesNotContain("Holder");
            }

            stubRead(ReadResult.found(storedUser(SEEDED_USER_ID, STORED_PASSWORD,
                    NavigationContext.USER_TYPE_USER)));

            SignOnOutcome outcome = service.handle(enterKey(SEEDED_USER_ID, STORED_PASSWORD));

            NavigationContext area = outcome.navigationContext();
            Assertions.assertThat(area.fromTranid()).isEqualTo(SignOnService.TRANSACTION_ID);
            Assertions.assertThat(area.fromProgram()).isEqualTo(SignOnService.PROGRAM_NAME);
            Assertions.assertThat(area.userId()).isEqualTo(SEEDED_USER_ID);
            Assertions.assertThat(area.userType()).isEqualTo(NavigationContext.USER_TYPE_USER);
            Assertions.assertThat(area.pgmContext()).isEqualTo(NavigationContext.PGM_CONTEXT_ENTER);

            SignOnService another = new SignOnService(repository);
            Assertions.assertThat(another.handle(enterKey(SEEDED_USER_ID, STORED_PASSWORD)))
                    .isEqualTo(outcome);
        }

        @Test
        @DisplayName("the seed fixtures trace to app/jcl/DUSRSECJ.jcl, and the loader's 57 characters "
                + "reach the copybook's 80")
        void theSeedFixturesTraceToTheLoader() {
            int seededPrefix = SecUserRecord.SEC_USR_ID_LENGTH
                    + SecUserRecord.SEC_USR_FNAME_LENGTH
                    + SecUserRecord.SEC_USR_LNAME_LENGTH
                    + SecUserRecord.SEC_USR_PWD_LENGTH
                    + SecUserRecord.SEC_USR_TYPE_LENGTH;
            Assertions.assertThat(seededPrefix).isEqualTo(57);
            Assertions.assertThat(seededPrefix + SecUserRecord.SEC_USR_FILLER_LENGTH)
                    .isEqualTo(SecUserRecord.RECORD_LENGTH)
                    .isEqualTo(80);

            SecUserRecord admin = storedUser(SEEDED_ADMIN_ID, STORED_PASSWORD,
                    NavigationContext.USER_TYPE_ADMIN);
            SecUserRecord user = storedUser(SEEDED_USER_ID, STORED_PASSWORD,
                    NavigationContext.USER_TYPE_USER);

            Assertions.assertThat(admin.secUsrId()).isEqualTo("ADMIN001");
            Assertions.assertThat(admin.secUsrFname()).startsWith("MARGARET");
            Assertions.assertThat(admin.secUsrLname()).startsWith("GOLD");
            Assertions.assertThat(admin.secUsrPwd()).isEqualTo("PASSWORD");
            Assertions.assertThat(admin.secUsrType()).isEqualTo("A");
            Assertions.assertThat(user.secUsrId()).isEqualTo("USER0001");
            Assertions.assertThat(user.secUsrFname()).startsWith("LAWRENCE");
            Assertions.assertThat(user.secUsrLname()).startsWith("THOMAS");
            Assertions.assertThat(user.secUsrPwd()).isEqualTo("PASSWORD");
            Assertions.assertThat(user.secUsrType()).isEqualTo("U");

            Assertions.assertThat(admin.secUsrPwd()).isEqualTo(user.secUsrPwd());
        }
    }

    @Nested
    @DisplayName("The carriers")
    class Carriers {
        @Test
        @DisplayName("CursorField names the length item the source writes -1 into")
        void cursorFieldNamesItsLengthItem() {
            Assertions.assertThat(CursorField.NONE.lengthItemName()).isEmpty();
            Assertions.assertThat(CursorField.NONE.isPositioned()).isFalse();
            Assertions.assertThat(CursorField.USER_ID.lengthItemName()).contains("USERIDL");
            Assertions.assertThat(CursorField.USER_ID.isPositioned()).isTrue();
            Assertions.assertThat(CursorField.PASSWORD.lengthItemName()).contains("PASSWDL");
            Assertions.assertThat(CursorField.PASSWORD.isPositioned()).isTrue();
        }

        @ParameterizedTest
        @EnumSource(Termination.class)
        @DisplayName("only RETURN TRANSID continues the conversation")
        void onlyReturnTransidContinues(Termination termination) {
            boolean expected = termination == Termination.RETURN_TRANSID;
            Assertions.assertThat(termination.conversationContinues()).isEqualTo(expected);
            Assertions.assertThat(termination.returnTransid().isPresent()).isEqualTo(expected);
            if (expected) {
                Assertions.assertThat(termination.returnTransid())
                        .contains(SignOnService.TRANSACTION_ID);
            }
        }

        @Test
        @DisplayName("ReceiveOutcome distinguishes a receive that happened from one that did not")
        void receiveOutcome() {
            Assertions.assertThat(ReceiveOutcome.NOT_PERFORMED.performed()).isFalse();
            Assertions.assertThat(ReceiveOutcome.NOT_PERFORMED.respCode()).isZero();
            Assertions.assertThat(ReceiveOutcome.NOT_PERFORMED.reasonCode()).isZero();
            Assertions.assertThat(ReceiveOutcome.normal().performed()).isTrue();
            Assertions.assertThat(ReceiveOutcome.normal().respCode())
                    .isEqualTo(SignOnService.RESP_NORMAL);
            Assertions.assertThat(ReceiveOutcome.normal())
                    .isNotEqualTo(ReceiveOutcome.NOT_PERFORMED);
        }

        @Test
        @DisplayName("MapInputArea carries the two spans COSGN0AO REDEFINES COSGN0AI makes one")
        void mapInputArea() {
            MapInputArea received = MapInputArea.received("ADMIN001", "PASSWORD");

            Assertions.assertThat(received.useridi()).isEqualTo("ADMIN001");
            Assertions.assertThat(received.passwdi()).isEqualTo("PASSWORD");
            Assertions.assertThat(received).isEqualTo(MapInputArea.received("ADMIN001", "PASSWORD"));

            Assertions.assertThat(MapInputArea.UNTRANSMITTED.useridi())
                    .isEqualTo(lowValues(SignOnService.USER_ID_LENGTH));
            Assertions.assertThat(MapInputArea.UNTRANSMITTED.passwdi())
                    .isEqualTo(lowValues(SignOnService.PASSWORD_LENGTH));
            Assertions.assertThat(MapInputArea.UNTRANSMITTED)
                    .isNotEqualTo(MapInputArea.received(spaces(8), spaces(8)));
        }

        @Test
        @DisplayName("MapInputArea withholds the password span from its rendering, and keeps it in equals")
        void mapInputAreaRendering() {
            MapInputArea received = MapInputArea.received("ADMIN001", "ZQX7PW  ");

            Assertions.assertThat(received.toString())
                    .as("the span reaches a 3270 as dark field data; a log line is not a 3270 - CWE-532")
                    .contains("ADMIN001")
                    .doesNotContain("ZQX7PW")
                    .contains(SensitiveDiagnostics.REDACTED);
            Assertions.assertThat(received)
                    .as("equals is value semantics and discloses nothing, so it keeps the span")
                    .isNotEqualTo(MapInputArea.received("ADMIN001", "OTHERPW "));
        }

        @ParameterizedTest
        @CsvSource({"USERIDI, 7", "USERIDI, 9", "PASSWDI, 7", "PASSWDI, 9"})
        @DisplayName("MapInputArea rejects a span that departs from its declared width")
        void mapInputAreaRejectsWrongWidths(String item, int width) {
            String wrong = spaces(width);
            boolean userId = "USERIDI".equals(item);
            ThrowingCallable construction = userId
                    ? () -> MapInputArea.received(wrong, spaces(8))
                    : () -> MapInputArea.received(spaces(8), wrong);

            Assertions.assertThatThrownBy(construction)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(item);
        }

        @Test
        @DisplayName("MapInputArea rejects a null span: an untransmitted field is LOW-VALUES, not null")
        void mapInputAreaRejectsNull() {
            Assertions.assertThatThrownBy(() -> MapInputArea.received(null, spaces(8)))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("USERIDI");
            Assertions.assertThatThrownBy(() -> MapInputArea.received(spaces(8), null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("PASSWDI");
        }

        @Test
        @DisplayName("the outcome requires every reference component")
        void theOutcomeRequiresEveryReference() {
            Assertions.assertThatThrownBy(() -> outcome(false, " ", spaces(8), null,
                    Termination.RETURN_TRANSID)).isInstanceOf(NullPointerException.class);
            Assertions.assertThatThrownBy(() -> outcome(false, null, spaces(8),
                    spaces(80), Termination.RETURN_TRANSID))
                    .isInstanceOf(NullPointerException.class);
            Assertions.assertThatThrownBy(() -> outcome(false, " ", null,
                    spaces(80), Termination.RETURN_TRANSID))
                    .isInstanceOf(NullPointerException.class);
            Assertions.assertThatThrownBy(() -> outcome(false, " ", spaces(8), spaces(80), null))
                    .isInstanceOf(NullPointerException.class);
            Assertions.assertThatThrownBy(() -> new SignOnOutcome(false, " ", spaces(8), spaces(80),
                    false, null, true, false, false, Termination.RETURN_TRANSID,
                    NavigationContext.empty(), ReceiveOutcome.NOT_PERFORMED,
                    MapInputArea.UNTRANSMITTED, Optional.empty(), Optional.empty()))
                    .isInstanceOf(NullPointerException.class);
            Assertions.assertThatThrownBy(() -> new SignOnOutcome(false, " ", spaces(8), spaces(80),
                    false, CursorField.NONE, true, false, false, Termination.RETURN_TRANSID,
                    null, ReceiveOutcome.NOT_PERFORMED, MapInputArea.UNTRANSMITTED, Optional.empty(),
                    Optional.empty()))
                    .isInstanceOf(NullPointerException.class);
            Assertions.assertThatThrownBy(() -> new SignOnOutcome(false, " ", spaces(8), spaces(80),
                    false, CursorField.NONE, true, false, false, Termination.RETURN_TRANSID,
                    NavigationContext.empty(), null, MapInputArea.UNTRANSMITTED, Optional.empty(),
                    Optional.empty()))
                    .isInstanceOf(NullPointerException.class);
            Assertions.assertThatThrownBy(() -> new SignOnOutcome(false, " ", spaces(8), spaces(80),
                    false, CursorField.NONE, true, false, false, Termination.RETURN_TRANSID,
                    NavigationContext.empty(), ReceiveOutcome.NOT_PERFORMED, MapInputArea.UNTRANSMITTED, null,
                    Optional.empty()))
                    .isInstanceOf(NullPointerException.class);
            Assertions.assertThatThrownBy(() -> new SignOnOutcome(false, " ", spaces(8), spaces(80),
                    false, CursorField.NONE, true, false, false, Termination.RETURN_TRANSID,
                    NavigationContext.empty(), ReceiveOutcome.NOT_PERFORMED, MapInputArea.UNTRANSMITTED,
                    Optional.empty(), null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("the outcome rejects a width that departs from its copybook")
        void theOutcomeRejectsWrongWidths() {
            Assertions.assertThatThrownBy(() -> outcome(false, "AB", spaces(8), spaces(80),
                    Termination.RETURN_TRANSID))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("CDEMO-USER-TYPE");
            Assertions.assertThatThrownBy(() -> outcome(false, " ", spaces(7), spaces(80),
                    Termination.RETURN_TRANSID))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("XCTL target");
            Assertions.assertThatThrownBy(() -> outcome(false, " ", spaces(8), spaces(79),
                    Termination.RETURN_TRANSID))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("WS-MESSAGE");
        }

        @Test
        @DisplayName("a sign-on is an XCTL that names its target, and nothing else is")
        void theOutcomeTiesSuccessToTheExit() {
            Assertions.assertThatCode(() -> outcome(false, " ", spaces(8), spaces(80),
                    Termination.RETURN_TRANSID)).doesNotThrowAnyException();
            Assertions.assertThatCode(() -> outcome(true, "A", "COADM01C", spaces(80),
                    Termination.XCTL)).doesNotThrowAnyException();

            Assertions.assertThatThrownBy(() -> outcome(true, "A", "COADM01C", spaces(80),
                    Termination.RETURN_TRANSID))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("XCTL");
            Assertions.assertThatThrownBy(() -> outcome(false, " ", spaces(8), spaces(80),
                    Termination.XCTL))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("XCTL");

            Assertions.assertThatThrownBy(() -> outcome(false, " ", "COADM01C", spaces(80),
                    Termination.RETURN_TRANSID))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("names its target");
            Assertions.assertThatThrownBy(() -> outcome(true, "A", spaces(8), spaces(80),
                    Termination.XCTL))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("names its target");
        }

        @Test
        @DisplayName("errFlgImage, hasNextProgram and isAdminRole read the stored values")
        void derivedAccessors() {
            SignOnOutcome signedOn = outcome(true, "A", "COADM01C", spaces(80), Termination.XCTL);
            SignOnOutcome failed = new SignOnOutcome(false, "U", spaces(8), spaces(80), true,
                    CursorField.USER_ID, true, false, false, Termination.RETURN_TRANSID,
                    NavigationContext.empty(), ReceiveOutcome.NOT_PERFORMED,
                    MapInputArea.UNTRANSMITTED, Optional.empty(), Optional.empty());

            Assertions.assertThat(signedOn.errFlgImage()).isEqualTo(SignOnService.ERR_FLG_OFF);
            Assertions.assertThat(signedOn.hasNextProgram()).isTrue();
            Assertions.assertThat(signedOn.isAdminRole()).isTrue();
            Assertions.assertThat(signedOn.returnTransid()).isEmpty();

            Assertions.assertThat(failed.errFlgImage()).isEqualTo(SignOnService.ERR_FLG_ON);
            Assertions.assertThat(failed.hasNextProgram()).isFalse();
            Assertions.assertThat(failed.isAdminRole()).isFalse();
            Assertions.assertThat(failed.returnTransid()).contains(SignOnService.TRANSACTION_ID);
        }

        @Test
        @DisplayName("an invocation is required")
        void anInvocationIsRequired() {
            Assertions.assertThatThrownBy(() -> service.handle(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("invocation");
        }

        private SignOnOutcome outcome(boolean signedOn,
                                      String role,
                                      String nextProgram,
                                      String message,
                                      Termination termination) {
            return new SignOnOutcome(signedOn, role, nextProgram, message, false, CursorField.NONE,
                    true, false, false, termination, NavigationContext.empty(),
                    ReceiveOutcome.NOT_PERFORMED, MapInputArea.UNTRANSMITTED, Optional.empty(),
                    Optional.empty());
        }
    }
}
