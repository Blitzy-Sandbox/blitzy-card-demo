package com.vsergeychik.carddemo.user;

import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.CicsResponse;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.PfKeyResolver;
import com.vsergeychik.carddemo.common.SystemMessages;
import com.vsergeychik.carddemo.user.SecUserRepository.ReadResult;
import com.vsergeychik.carddemo.user.SignOnService.CursorField;
import com.vsergeychik.carddemo.user.SignOnService.ReceiveOutcome;
import com.vsergeychik.carddemo.user.SignOnService.SignOnInput;
import com.vsergeychik.carddemo.user.SignOnService.SignOnOutcome;
import com.vsergeychik.carddemo.user.SignOnService.Termination;
import com.vsergeychik.carddemo.user.model.SecUserRecord;

import java.lang.reflect.RecordComponent;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link SignOnService} against {@code app/cbl/COSGN00C.cbl}.
 *
 * <p>The service is exercised as a plain object with a stubbed repository: no Spring context, no
 * {@code MockMvc}, no servlet and no {@code JobLauncher}. That is the point of putting the program's
 * decisions in a service, and it is what makes every branch below reachable.
 *
 * <p>Eight paths exist through the program and each one is asserted whole - message, error flag, cursor,
 * paint flags, termination and the returned communication area - rather than one field at a time, because
 * a path that produces the right message with the wrong error flag is still a parity failure.
 */
@DisplayName("SignOnService - the COSGN00C sign-on transaction")
class SignOnServiceTest {

    /** A single-byte code page, named explicitly rather than taken from the platform. */
    private static final Charset CODE_PAGE = StandardCharsets.US_ASCII;

    /** The seeded administrator password from {@code app/jcl/DUSRSECJ.jcl:35-39}. */
    private static final String STORED_PASSWORD = "PASSWORD";

    /** A byte that is not an attention identifier at all, let alone a mapped one. */
    private static final byte NOT_AN_AID = (byte) 0x00;

    private SecUserRepository repository;

    private SignOnService service;

    @BeforeEach
    void setUp() {
        repository = mock(SecUserRepository.class);
        service = new SignOnService(repository);
    }

    // =============================================================================================
    // Helpers.
    // =============================================================================================

    private static SecUserRecord storedUser(String userId, String password, String userType) {
        return SecUserRecord.of(userId, "FIRSTNAME", "LASTNAME", password, userType, CODE_PAGE);
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

    /** The 80-character {@code WS-MESSAGE} image a {@code PIC X(50)} or shorter literal produces. */
    private static String messageImage(String literal) {
        return literal + spaces(SignOnService.MESSAGE_LENGTH - literal.length());
    }

    private void stubRead(ReadResult result) {
        when(repository.read(anyString())).thenReturn(result);
    }

    // =============================================================================================
    // Wiring and the WS-VARIABLES literals.
    // =============================================================================================

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
        }
    }

    // =============================================================================================
    // The five message literals.
    // =============================================================================================

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

    // =============================================================================================
    // MAIN-PARA :80-83 - the EIBCALEN = 0 path.
    // =============================================================================================

    @Nested
    @DisplayName("First entry with no communication area, COSGN00C:80-83")
    class FirstEntry {

        @Test
        @DisplayName("paints the screen, clears the output fields, puts the cursor on the user id")
        void paintsTheSignonScreen() {
            SignOnOutcome outcome = service.handle(
                    SignOnInput.withoutCommarea(CicsAid.DFHENTER, "ADMIN001", STORED_PASSWORD));

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
                    service.handle(SignOnInput.withoutCommarea(eibAid, "ADMIN001", STORED_PASSWORD));

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

    // =============================================================================================
    // MAIN-PARA :85-95 - EVALUATE EIBAID.
    // =============================================================================================

    @Nested
    @DisplayName("EVALUATE EIBAID, COSGN00C:85-95")
    class AttentionIdentifierArms {

        @Test
        @DisplayName("PF3 sends plain text, ends the conversation and sets no error flag")
        void pf3SignsOff() {
            NavigationContext inbound = NavigationContext.empty().withUserId("ADMIN001");

            SignOnOutcome outcome = service.handle(
                    new SignOnInput(inbound, CicsAid.DFHPF3, "ADMIN001", STORED_PASSWORD));

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
                    new SignOnInput(NavigationContext.empty(), CicsAid.DFHPF4, "ADMIN001",
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
                    new SignOnInput(NavigationContext.empty(), CicsAid.DFHPF15, "ADMIN001",
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
                    new SignOnInput(NavigationContext.empty(), CicsAid.DFHPA3, "ADMIN001",
                            STORED_PASSWORD));

            assertInvalidKey(outcome);
            Assertions.assertThat(outcome.resolvedAid()).isEmpty();
        }

        @Test
        @DisplayName("a byte that is no attention identifier at all takes WHEN OTHER too")
        void anUnknownByteIsInvalid() {
            Assertions.assertThat(PfKeyResolver.resolve(NOT_AN_AID)).isEmpty();

            SignOnOutcome outcome = service.handle(
                    new SignOnInput(NavigationContext.empty(), NOT_AN_AID, "ADMIN001",
                            STORED_PASSWORD));

            assertInvalidKey(outcome);
            Assertions.assertThat(outcome.resolvedAid()).isEmpty();
        }

        @Test
        @DisplayName("CLEAR and PA1 are invalid keys here as well")
        void clearAndPa1AreInvalid() {
            assertInvalidKey(service.handle(new SignOnInput(NavigationContext.empty(),
                    CicsAid.DFHCLEAR, "ADMIN001", STORED_PASSWORD)));
            assertInvalidKey(service.handle(new SignOnInput(NavigationContext.empty(),
                    CicsAid.DFHPA1, "ADMIN001", STORED_PASSWORD)));
        }

        private void assertInvalidKey(SignOnOutcome outcome) {
            Assertions.assertThat(outcome.errorFlag()).isTrue();
            Assertions.assertThat(outcome.errFlgImage()).isEqualTo(SignOnService.ERR_FLG_ON);
            Assertions.assertThat(outcome.message())
                    .isEqualTo(messageImage(SystemMessages.CCDA_MSG_INVALID_KEY))
                    .hasSize(SignOnService.MESSAGE_LENGTH);
            Assertions.assertThat(outcome.screenPainted()).isTrue();
            Assertions.assertThat(outcome.resetAllOutputFields()).isFalse();
            // :91-94 contains no MOVE -1, so no field is repositioned on this arm.
            Assertions.assertThat(outcome.cursorField()).isEqualTo(CursorField.NONE);
            Assertions.assertThat(outcome.cursorField().isPositioned()).isFalse();
            Assertions.assertThat(outcome.signedOn()).isFalse();
            Assertions.assertThat(outcome.termination()).isEqualTo(Termination.RETURN_TRANSID);
            Assertions.assertThat(outcome.receive()).isEqualTo(ReceiveOutcome.NOT_PERFORMED);
            Assertions.assertThat(outcome.readOutcome()).isEmpty();
            verifyNoInteractions(repository);
        }
    }

    // =============================================================================================
    // PROCESS-ENTER-KEY :117-130 - the ordered EVALUATE TRUE.
    // =============================================================================================

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
            assertBlankPassword(service.handle(enterKey("ADMIN001", password)));
        }

        @Test
        @DisplayName("a password the terminal never transmitted is LOW-VALUES and asks for the password")
        void lowValuesPassword() {
            assertBlankPassword(service.handle(enterKey("ADMIN001", null)));
            assertBlankPassword(service.handle(
                    enterKey("ADMIN001", lowValues(SignOnService.PASSWORD_LENGTH))));
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

            // :132-134 ran although :123-127 had already flagged the error and painted the screen.
            Assertions.assertThat(outcome.navigationContext().userId()).isEqualTo("ADM1    ");
            Assertions.assertThat(outcome.message())
                    .isEqualTo(messageImage(SignOnService.MSG_ENTER_PASSWORD));
            // :138 gated the read on the error flag.
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
            // The role on the outcome is blank because no sign-on established one, even though the
            // inbound area happened to carry an administrator type.
            Assertions.assertThat(outcome.role()).isEqualTo(spaces(SignOnService.ROLE_LENGTH));
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

    // =============================================================================================
    // READ-USER-SEC-FILE :211-257 - the three-arm response split.
    // =============================================================================================

    @Nested
    @DisplayName("READ-USER-SEC-FILE, COSGN00C:211-257")
    class ReadArms {

        @Test
        @DisplayName("WHEN 0 with a matching password signs an administrator on to COADM01C")
        void administratorSignsOn() {
            stubRead(ReadResult.found(storedUser("ADMIN001", STORED_PASSWORD,
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

            // :224-228, the five moves into the communication area.
            NavigationContext area = outcome.navigationContext();
            Assertions.assertThat(area.fromTranid()).isEqualTo(SignOnService.TRANSACTION_ID);
            Assertions.assertThat(area.fromProgram()).isEqualTo(SignOnService.PROGRAM_NAME);
            Assertions.assertThat(area.userId()).isEqualTo("ADMIN001");
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
            stubRead(ReadResult.found(storedUser("USER0001", STORED_PASSWORD,
                    NavigationContext.USER_TYPE_USER)));

            SignOnOutcome outcome = service.handle(enterKey("USER0001", STORED_PASSWORD));

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
            stubRead(ReadResult.found(storedUser("USER0001", STORED_PASSWORD, userType)));

            SignOnOutcome outcome = service.handle(enterKey("USER0001", STORED_PASSWORD));

            Assertions.assertThat(outcome.signedOn()).isTrue();
            Assertions.assertThat(outcome.nextProgram()).isEqualTo("COMEN01C");
            Assertions.assertThat(outcome.role()).isEqualTo(userType);
            Assertions.assertThat(outcome.isAdminRole()).isFalse();
        }

        @Test
        @DisplayName("the comparison is case-insensitive on the submitted side only")
        void caseFoldingAppliesToTheSubmittedSideOnly() {
            stubRead(ReadResult.found(storedUser("ADMIN001", STORED_PASSWORD,
                    NavigationContext.USER_TYPE_ADMIN)));

            // :135-136 upper-cases what was typed, so a lower-case submission matches.
            Assertions.assertThat(service.handle(enterKey("ADMIN001", "password")).signedOn()).isTrue();
        }

        @Test
        @DisplayName("a stored lower-case password can never be matched, because it is used verbatim")
        void aStoredLowerCasePasswordNeverMatches() {
            stubRead(ReadResult.found(storedUser("ADMIN001", "password",
                    NavigationContext.USER_TYPE_ADMIN)));

            SignOnOutcome submittedLower = service.handle(enterKey("ADMIN001", "password"));
            SignOnOutcome submittedUpper = service.handle(enterKey("ADMIN001", "PASSWORD"));

            Assertions.assertThat(submittedLower.signedOn()).isFalse();
            Assertions.assertThat(submittedUpper.signedOn()).isFalse();
        }

        @Test
        @DisplayName("trailing spaces are part of the comparison, so a prefix does not match")
        void aPrefixDoesNotMatch() {
            stubRead(ReadResult.found(storedUser("ADMIN001", STORED_PASSWORD,
                    NavigationContext.USER_TYPE_ADMIN)));

            Assertions.assertThat(service.handle(enterKey("ADMIN001", "PASS")).signedOn()).isFalse();
        }

        @Test
        @DisplayName("WHEN 0 with a wrong password produces a message but NO error flag - :241-246")
        void wrongPasswordDoesNotSetTheErrorFlag() {
            stubRead(ReadResult.found(storedUser("ADMIN001", STORED_PASSWORD,
                    NavigationContext.USER_TYPE_ADMIN)));

            SignOnOutcome outcome = service.handle(enterKey("ADMIN001", "WRONG"));

            Assertions.assertThat(outcome.message())
                    .isEqualTo(messageImage(SignOnService.MSG_WRONG_PASSWORD))
                    .hasSize(SignOnService.MESSAGE_LENGTH);
            // The asymmetry the source has and this class preserves: every other failure sets 'Y'.
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
            // The area still carries the normalised user id from :132-134, but no role.
            Assertions.assertThat(outcome.navigationContext().userId()).isEqualTo("ADMIN001");
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

            assertUnableToVerify(service.handle(enterKey("ADMIN001", STORED_PASSWORD)),
                    FileStatus.Outcome.END_OF_FILE);
        }

        @Test
        @DisplayName("WHEN OTHER covers a duplicate classification too")
        void duplicateTakesWhenOther() {
            stubRead(ReadResult.of(FileStatus.DUPLICATE, CicsResponse.of(FileStatus.DUPREC)));

            assertUnableToVerify(service.handle(enterKey("ADMIN001", STORED_PASSWORD)),
                    FileStatus.Outcome.DUPLICATE);
        }

        @Test
        @DisplayName("WHEN OTHER covers a backend refusal")
        void aRefusalTakesWhenOther() {
            stubRead(ReadResult.of(SecUserRepository.PERMANENT_ERROR_STATUS, CicsResponse.none()));

            assertUnableToVerify(service.handle(enterKey("ADMIN001", STORED_PASSWORD)),
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

            Assertions.assertThatThrownBy(() -> service.handle(enterKey("ADMIN001", STORED_PASSWORD)))
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

    // =============================================================================================
    // :230-240 - the two-way role split, on its own.
    // =============================================================================================

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

    // =============================================================================================
    // FUNCTION UPPER-CASE, :132 and :135.
    // =============================================================================================

    @Nested
    @DisplayName("FUNCTION UPPER-CASE, COSGN00C:132 and :135")
    class UpperCase {

        @ParameterizedTest
        @ValueSource(strings = {"admin001", "ADMIN001", "AdMiN001", "aDmIn001"})
        @DisplayName("folds a to z and leaves digits alone")
        void foldsLettersOnly(String value) {
            Assertions.assertThat(SignOnService.upperCase(value)).isEqualTo("ADMIN001");
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
            // The sharp s upper-cases to two characters under String.toUpperCase, which would widen an
            // eight-character field to nine and break both the RIDFLD width and CDEMO-USER-ID.
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

    // =============================================================================================
    // Statelessness and confidentiality.
    // =============================================================================================

    @Nested
    @DisplayName("Statelessness and confidentiality")
    class StatelessnessAndConfidentiality {

        @Test
        @DisplayName("two successive calls cannot influence one another")
        void noStateSurvivesACall() {
            stubRead(ReadResult.found(storedUser("ADMIN001", STORED_PASSWORD,
                    NavigationContext.USER_TYPE_ADMIN)));

            SignOnOutcome first = service.handle(enterKey("ADMIN001", STORED_PASSWORD));
            SignOnOutcome second = service.handle(enterKey("", ""));
            SignOnOutcome third = service.handle(enterKey("ADMIN001", STORED_PASSWORD));

            Assertions.assertThat(first.signedOn()).isTrue();
            // The second call sees none of the first call's role, message or user id.
            Assertions.assertThat(second.signedOn()).isFalse();
            Assertions.assertThat(second.role()).isEqualTo(spaces(SignOnService.ROLE_LENGTH));
            Assertions.assertThat(second.message())
                    .isEqualTo(messageImage(SignOnService.MSG_ENTER_USER_ID));
            Assertions.assertThat(second.navigationContext().fromProgram())
                    .isEqualTo(spaces(NavigationContext.FROM_PROGRAM_LENGTH));
            // And the third call reproduces the first exactly, so the second left nothing behind.
            Assertions.assertThat(third).isEqualTo(first);
        }

        @Test
        @DisplayName("the inbound communication area is never mutated")
        void theInboundAreaIsNeverMutated() {
            stubRead(ReadResult.found(storedUser("ADMIN001", STORED_PASSWORD,
                    NavigationContext.USER_TYPE_ADMIN)));
            NavigationContext inbound = NavigationContext.empty();

            SignOnOutcome outcome = service.handle(
                    new SignOnInput(inbound, CicsAid.DFHENTER, "ADMIN001", STORED_PASSWORD));

            Assertions.assertThat(inbound).isEqualTo(NavigationContext.empty());
            Assertions.assertThat(outcome.navigationContext()).isNotEqualTo(inbound);
        }

        @Test
        @DisplayName("the input's rendering redacts the password")
        void theInputRedactsThePassword() {
            String fakePassword = "N0TAREAL";

            String rendered = enterKey("ADMIN001", fakePassword).toString();

            Assertions.assertThat(rendered)
                    .doesNotContain(fakePassword)
                    .contains(NavigationContext.REDACTED)
                    .contains("ADMIN001")
                    .contains("eibAid=0x7D");
            // One line whatever it holds, or a stored control character could append a log entry of the
            // writer's choosing after it.
            Assertions.assertThat(rendered.lines()).hasSize(1);
        }

        @Test
        @DisplayName("the attention identifier renders as an unsigned byte, not a sign-extended int")
        void theAidRendersUnsigned() {
            // PF3 is 0xF3, which as a Java byte is negative; a sign-extended rendering would show
            // FFFFFFF3 and make a raw byte comparison look wrong to a reader.
            String rendered = new SignOnInput(NavigationContext.empty(), CicsAid.DFHPF3, "A", "B")
                    .toString();

            Assertions.assertThat(rendered).contains("eibAid=0xF3").doesNotContain("FFFFFFF3");
        }

        @Test
        @DisplayName("the outcome carries no password component, and its rendering leaks none")
        void theOutcomeCarriesNoPassword() {
            // Upper case throughout, so that :135-136's fold leaves it matchable and the sign-on
            // actually succeeds - the path on which a leak would be most likely. An obviously synthetic
            // value: no real credential belongs in a test source.
            String fakePassword = "N0TAREAL";
            stubRead(ReadResult.found(storedUser("ADMIN001", fakePassword,
                    NavigationContext.USER_TYPE_ADMIN)));

            SignOnOutcome outcome = service.handle(enterKey("ADMIN001", fakePassword));

            Assertions.assertThat(outcome.signedOn()).isTrue();
            Assertions.assertThat(outcome.toString()).doesNotContain(fakePassword);
            for (RecordComponent component : SignOnOutcome.class.getRecordComponents()) {
                Assertions.assertThat(component.getName().toLowerCase(java.util.Locale.ROOT))
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

    // =============================================================================================
    // The carriers, on their own.
    // =============================================================================================

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
                    NavigationContext.empty(), ReceiveOutcome.NOT_PERFORMED, Optional.empty(),
                    Optional.empty())).isInstanceOf(NullPointerException.class);
            Assertions.assertThatThrownBy(() -> new SignOnOutcome(false, " ", spaces(8), spaces(80),
                    false, CursorField.NONE, true, false, false, Termination.RETURN_TRANSID,
                    null, ReceiveOutcome.NOT_PERFORMED, Optional.empty(), Optional.empty()))
                    .isInstanceOf(NullPointerException.class);
            Assertions.assertThatThrownBy(() -> new SignOnOutcome(false, " ", spaces(8), spaces(80),
                    false, CursorField.NONE, true, false, false, Termination.RETURN_TRANSID,
                    NavigationContext.empty(), null, Optional.empty(), Optional.empty()))
                    .isInstanceOf(NullPointerException.class);
            Assertions.assertThatThrownBy(() -> new SignOnOutcome(false, " ", spaces(8), spaces(80),
                    false, CursorField.NONE, true, false, false, Termination.RETURN_TRANSID,
                    NavigationContext.empty(), ReceiveOutcome.NOT_PERFORMED, null, Optional.empty()))
                    .isInstanceOf(NullPointerException.class);
            Assertions.assertThatThrownBy(() -> new SignOnOutcome(false, " ", spaces(8), spaces(80),
                    false, CursorField.NONE, true, false, false, Termination.RETURN_TRANSID,
                    NavigationContext.empty(), ReceiveOutcome.NOT_PERFORMED, Optional.empty(), null))
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
            // Both consistent combinations are legal.
            Assertions.assertThatCode(() -> outcome(false, " ", spaces(8), spaces(80),
                    Termination.RETURN_TRANSID)).doesNotThrowAnyException();
            Assertions.assertThatCode(() -> outcome(true, "A", "COADM01C", spaces(80),
                    Termination.XCTL)).doesNotThrowAnyException();

            // signedOn without an XCTL, and an XCTL without signedOn.
            Assertions.assertThatThrownBy(() -> outcome(true, "A", "COADM01C", spaces(80),
                    Termination.RETURN_TRANSID))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("XCTL");
            Assertions.assertThatThrownBy(() -> outcome(false, " ", spaces(8), spaces(80),
                    Termination.XCTL))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("XCTL");

            // A named target without a sign-on, and a sign-on without a named target.
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
                    NavigationContext.empty(), ReceiveOutcome.NOT_PERFORMED, Optional.empty(),
                    Optional.empty());

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
                    ReceiveOutcome.NOT_PERFORMED, Optional.empty(), Optional.empty());
        }
    }
}
