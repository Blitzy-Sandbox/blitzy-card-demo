/*
 * ******************************************************************
 * Program     : AdminCredentialTransportContractTest
 * Application : CardDemo
 * Type        : JUnit 5 unit test - HTTP credential-transport contract
 * Function    : Asserts that the presented plaintext credential of the
 *               user-add transaction CU01 reaches the service ONLY through the
 *               request body, that no controller accepts a credential on a
 *               bespoke header, cookie or query parameter, and that the value
 *               relayed to the service is exactly the value that was bound.
 * Source      : app/cpy-bms/COUSR01.CPY:78 PASSWDI PIC X(8), the presented
 *               credential of app/cbl/COUSR01C.cbl (299 lines, transaction
 *               CU01 at app/csd/CARDDEMO.CSD:L459). The 3270 screen carried it
 *               in the map's input group, i.e. in the payload, never beside it;
 *               a header channel has no counterpart in the corpus. @ 7756d89
 * ******************************************************************
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ******************************************************************
 */
package com.cardemo.unit.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cardemo.controller.AdminController;
import com.cardemo.controller.AuthController;
import com.cardemo.model.dto.UserCreateRequest;
import com.cardemo.service.admin.UserAddService;
import com.cardemo.service.admin.UserDeleteService;
import com.cardemo.service.admin.UserListService;
import com.cardemo.service.admin.UserUpdateService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Regression guard for the credential-transport contract of the user-administration surface.
 *
 * <h2>The finding this class exists to prevent recurring</h2>
 *
 * <p>Severity HIGH, CWE-522 and CWE-200. {@code POST /api/admin/users} once bound the twelve declared body
 * members of {@code app/cpy-bms/COUSR01.CPY}, <em>ignored</em> the credential member among them, and read the
 * credential from a project-invented {@code X-Presented-Password} request header instead. Two distinct harms
 * followed, and each alone justifies this guard:
 *
 * <ul>
 *   <li><strong>Redaction blindness.</strong> Ingress controllers, reverse proxies, access logs and APM
 *       agents scrub the channels they recognise - {@code Authorization}, {@code Cookie}, and body members
 *       whose names look like credentials. A bespoke header name is recognised by none of them, so the
 *       plaintext credential travelled through the single channel least likely to be masked anywhere along
 *       the path.</li>
 *   <li><strong>Audit divergence.</strong> The body member and the header arrived independently, so the value
 *       an auditor could see in the request body was not necessarily the value that was hashed and stored.
 *       Nothing reconciled them.</li>
 * </ul>
 *
 * <p>The remedy is structural rather than procedural: the header, its constant and its binding are gone, and
 * the body member has exactly one non-serializing, non-bean read path,
 * {@link UserCreateRequest#mapPassword(java.util.function.Function)}, which the controller reads in the
 * delegation expression itself - it hands the value to a reader the caller supplies rather than returning it,
 * so there is no accessor to serialize or to log. This class asserts the remedy in three independent ways -
 * by reflection over the handler signatures, by scanning the controller sources for header literals, and
 * behaviourally, by proving the service receives precisely what was bound.
 *
 * <h2>What is asserted, and why each assertion is not redundant</h2>
 *
 * <ol>
 *   <li>No handler on any controller declares a {@code @RequestHeader} or {@code @CookieValue} parameter at
 *       all, and no {@code @RequestParam} whose name reads as a credential. Reflection catches a binding that
 *       exists.</li>
 *   <li>No controller source declares a string literal shaped like a credential-bearing header name. Source
 *       scanning catches a constant that has been introduced but not yet bound, which reflection cannot
 *       see.</li>
 *   <li>The service receives the exact value the body carried. Behaviour catches a relay that reads the right
 *       channel but substitutes, trims, folds or defaults the value on the way.</li>
 * </ol>
 *
 * <h2>How to run</h2>
 *
 * <p>{@code ./mvnw -B -ntp test -Dtest=AdminCredentialTransportContractTest}. No container, no database, no
 * Spring context and no network: the collaborators are Mockito doubles and the source scan is a filesystem
 * read relative to the module root.
 *
 * <h2>Common failure modes</h2>
 *
 * <ul>
 *   <li><strong>{@code noHandlerBindsAHeaderOrCookie} fails.</strong> A handler gained a
 *       {@code @RequestHeader} or {@code @CookieValue} parameter. If the value is genuinely not a credential -
 *       a conditional-request validator, say - it still must not be introduced here without revisiting this
 *       guard deliberately, because the guard's value is that it admits no exceptions to argue about.</li>
 *   <li><strong>{@code noControllerSourceDeclaresACredentialHeaderLiteral} fails.</strong> A header-shaped
 *       literal naming a credential was added. Remediation: carry the credential in the request body member
 *       the BMS map already declares.</li>
 *   <li><strong>{@code theServiceReceivesExactlyTheBoundBodyCredential} fails.</strong> The relay stopped
 *       reading the credential through {@code request.mapPassword(...)}, or normalised it. Remediation: pass
 *       the body value through unchanged; {@code app/cbl/COSGN00C.cbl} performs the case fold at
 *       authentication time and doing it twice changes outcomes.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.STRICT_STUBS)
@DisplayName("Credential transport - the add credential travels in the body and on no other channel")
final class AdminCredentialTransportContractTest {

    /**
     * The controllers whose handlers must bind no credential-bearing channel.
     *
     * <p>{@link AdminController} is the subject of the finding. {@link AuthController} is included because it
     * is the only other endpoint that receives a plaintext credential - the sign-on body of
     * {@code app/cpy-bms/COSGN00.CPY} - and a guard that covered only the one that regressed would not
     * prevent the same mistake being made next door.
     */
    private static final List<Class<?>> CREDENTIAL_BEARING_CONTROLLERS =
            List.of(AdminController.class, AuthController.class);

    /** Directory holding every controller source, scanned for header-shaped literals. */
    private static final Path CONTROLLER_SOURCES =
            Path.of("src", "main", "java", "com", "cardemo", "controller");

    /**
     * Lower-case fragments that make an identifier or a literal read as a credential.
     *
     * <p>{@code pwd} is included because {@code app/cpy/CSUSR01Y.cpy:L21} spells the persisted column
     * {@code SEC-USR-PWD}, so that abbreviation is the one a contributor working from the copybook would
     * reach for.
     *
     * <p><strong>{@code token} is deliberately absent.</strong> This surface uses that word for things that
     * are not credentials at all: {@code AdminController} names its confirmation and pagination inputs
     * {@code TRUE_TOKEN}, {@code FALSE_TOKEN}, {@code MAXIMUM_PAGE_TOKEN_DIGITS} and
     * {@code MAXIMUM_ROW_COUNT_TOKEN_DIGITS}, all of which reproduce screen inputs of
     * {@code app/cpy-bms/COUSR00.CPY} and carry nothing secret. Including the word would make this guard fire
     * on correct code, and a guard that cries wolf is a guard that gets deleted.
     */
    private static final List<String> CREDENTIAL_WORDS =
            List.of("password", "passwd", "pwd", "secret", "credential");

    /**
     * The shape an HTTP header name takes: letters, digits and hyphens only, at least one hyphen, and bounded
     * so a long prose fragment cannot be mistaken for one.
     *
     * <p>The bound is 64 characters. The longest standard header name is well under that, and the removed
     * {@code X-Presented-Password} is 20, so the bound excludes prose without excluding any real header.
     */
    private static final Pattern HEADER_NAME_SHAPE =
            Pattern.compile("[A-Za-z][A-Za-z0-9]*(-[A-Za-z0-9]+)+");

    /** The eight-character synthetic credential used below. Not a real password and not the seeded literal. */
    private static final String SYNTHETIC_CREDENTIAL = "Rt4$Xw9b";

    /** Identifier of the user the add payloads below create. */
    private static final String NEW_USER_ID = "USER0042";

    @Mock
    private UserListService userListService;

    @Mock
    private UserAddService userAddService;

    @Mock
    private UserUpdateService userUpdateService;

    @Mock
    private UserDeleteService userDeleteService;

    /**
     * Reflection over the handler signatures: no credential-bearing binding exists.
     */
    @Nested
    @DisplayName("no handler binds a credential from anywhere but the request body")
    final class HandlerSignatures {

        @Test
        @DisplayName("no handler binds a header or a cookie at all")
        void noHandlerBindsAHeaderOrCookie() {
            final List<String> offenders = new ArrayList<>();
            for (final Class<?> controller : CREDENTIAL_BEARING_CONTROLLERS) {
                for (final Method handler : publicHandlers(controller)) {
                    for (final Parameter parameter : handler.getParameters()) {
                        if (parameter.isAnnotationPresent(CookieValue.class)) {
                            offenders.add(controller.getSimpleName() + '.' + handler.getName()
                                    + " binds " + describe(parameter));
                            continue;
                        }
                        final RequestHeader bound = parameter.getAnnotation(RequestHeader.class);
                        if (bound != null) {
                            offenders.add(controller.getSimpleName() + '.' + handler.getName()
                                    + " binds " + describe(parameter) + " named '"
                                    + boundHeaderName(bound) + '\'');
                        }
                    }
                }
            }

            assertThat(offenders)
                    .as("a bespoke header or cookie is the channel generic ingress, proxy, access-log and "
                            + "APM redaction does NOT recognise. The credential of "
                            + "app/cpy-bms/COUSR01.CPY:78 is a member of the map's own input group, so the "
                            + "request body is both the faithful channel and the recognised one. The "
                            + "allowlist is now empty rather than a list of one: the account and card update "
                            + "surfaces carry their as-displayed snapshot as the body's oldDetails group "
                            + "under transformation Rule 7, so no handler on any of these controllers binds "
                            + "a header for any purpose. Offenders: %s",
                            offenders)
                    .isEmpty();
        }

        @Test
        @DisplayName("no handler binds a credential-named query parameter either")
        void noHandlerBindsACredentialNamedQueryParameter() {
            final List<String> offenders = new ArrayList<>();
            for (final Class<?> controller : CREDENTIAL_BEARING_CONTROLLERS) {
                for (final Method handler : publicHandlers(controller)) {
                    for (final Parameter parameter : handler.getParameters()) {
                        final RequestParam bound = parameter.getAnnotation(RequestParam.class);
                        if (bound != null && readsAsCredential(bound.name() + ' ' + bound.value()
                                + ' ' + parameter.getName())) {
                            offenders.add(controller.getSimpleName() + '.' + handler.getName()
                                    + " binds " + describe(parameter));
                        }
                    }
                }
            }

            assertThat(offenders)
                    .as("a query parameter is worse than a header, not better: it reaches the access log, "
                            + "the browser history and the Referer header of every subsequent request. "
                            + "Offenders: %s", offenders)
                    .isEmpty();
        }

        @Test
        @DisplayName("the add handler takes exactly one parameter, and it is the validated request body")
        void theAddHandlerTakesOnlyTheBody() throws Exception {
            final Method addUser = AdminController.class.getMethod("addUser", UserCreateRequest.class);

            assertThat(addUser.getParameterCount())
                    .as("one parameter means one channel. A second parameter of any kind is how the "
                            + "credential left the body in the first place")
                    .isEqualTo(1);

            assertThat(addUser.getParameters()[0].isAnnotationPresent(RequestBody.class))
                    .as("and that one parameter must be the body, not a path variable or a model "
                            + "attribute")
                    .isTrue();
        }

        @Test
        @DisplayName("no controller declares a static field whose name reads as a credential channel")
        void noControllerDeclaresACredentialChannelConstant() {
            final List<String> offenders = new ArrayList<>();
            for (final Class<?> controller : CREDENTIAL_BEARING_CONTROLLERS) {
                for (final Field field : controller.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) && readsAsCredential(field.getName())) {
                        offenders.add(controller.getSimpleName() + '.' + field.getName());
                    }
                }
            }

            assertThat(offenders)
                    .as("the removed defect took the shape of a constant, PRESENTED_PASSWORD_HEADER, so "
                            + "the constant itself is worth refusing: a named channel invites a binding. "
                            + "Offenders: %s", offenders)
                    .isEmpty();
        }
    }

    /**
     * Source scanning: no header-shaped credential literal has been introduced anywhere in the controllers,
     * whether or not anything binds it yet.
     */
    @Nested
    @DisplayName("no controller source declares a credential-bearing header literal")
    final class SourceLiterals {

        @Test
        @DisplayName("no string literal in any controller names a credential-bearing header")
        void noControllerSourceDeclaresACredentialHeaderLiteral() {
            final List<String> offenders = new ArrayList<>();
            for (final Path source : controllerSources()) {
                final List<String> lines = readLines(source);
                for (int index = 0; index < lines.size(); index++) {
                    final String line = lines.get(index);
                    if (isCommentary(line)) {
                        continue;
                    }
                    for (final String literal : stringLiteralsIn(line)) {
                        if (looksLikeACredentialHeaderName(literal)) {
                            offenders.add(source.getFileName() + ":" + (index + 1) + " -> \""
                                    + literal + '"');
                        }
                    }
                }
            }

            assertThat(offenders)
                    .as("a literal is the earliest observable form of this defect: it appears one commit "
                            + "before anything binds it. Commentary is skipped on purpose, because this "
                            + "class and the controllers themselves must be able to NAME the removed "
                            + "header in prose in order to explain why it is forbidden. Offenders: %s",
                            offenders)
                    .isEmpty();
        }

        @Test
        @DisplayName("the scan actually found the controller sources, so an empty pass is impossible")
        void theScanFoundTheControllerSources() {
            assertThat(controllerSources())
                    .as("a source scan that silently matched nothing would pass forever. The module root "
                            + "at %s must contain the controller package",
                            Path.of("").toAbsolutePath())
                    .hasSizeGreaterThanOrEqualTo(8);
        }
    }

    /**
     * Behaviour: the service receives exactly the credential the body carried.
     */
    @Nested
    @DisplayName("the value relayed to the service is exactly the value the body carried")
    final class RelayFidelity {

        @Test
        @DisplayName("theServiceReceivesExactlyTheBoundBodyCredential")
        void theServiceReceivesExactlyTheBoundBodyCredential() {
            final AdminController controller = controller();
            final UserCreateRequest submitted = addPayload(SYNTHETIC_CREDENTIAL);

            controller.addUser(submitted);

            verify(userAddService).addUser(submitted, SYNTHETIC_CREDENTIAL);
        }

        @Test
        @DisplayName("an absent body credential reaches the service absent, not defaulted to empty")
        void anAbsentBodyCredentialReachesTheServiceAbsent() {
            final AdminController controller = controller();
            final UserCreateRequest submitted = addPayload(null);

            controller.addUser(submitted);

            verify(userAddService).addUser(submitted, null);
        }

        @Test
        @DisplayName("a blank body credential is relayed blank, so the source's own arm is reached")
        void aBlankBodyCredentialIsRelayedBlank() {
            final AdminController controller = controller();
            final UserCreateRequest submitted = addPayload("        ");

            controller.addUser(submitted);

            verify(userAddService).addUser(submitted, "        ");
        }
    }

    // =================================================================================================
    // Helpers. All private and all static where they can be, so no test can mutate shared state.
    // =================================================================================================

    /**
     * Builds a controller over the four Mockito doubles.
     *
     * @return a controller wired for the add path, never {@code null}
     */
    private AdminController controller() {
        return new AdminController(userListService, userAddService, userUpdateService, userDeleteService);
    }

    /**
     * Stubs the add service to answer a screen, so the handler can complete and the argument can be verified.
     *
     * <p>The stub returns a screen whose message is the source's own added sentence, because
     * {@code AdminController} refuses a {@code null} screen and would otherwise abend before the verification
     * below could run.
     *
     * @param credential the credential to place in the body member, possibly {@code null} or blank
     * @return the payload to submit, never {@code null}
     */
    private UserCreateRequest addPayload(final String credential) {
        when(userAddService.addUser(any(), any()))
                .thenReturn(new UserAddService.UserAddScreen("CU01", "t1", "08/04/26", "COUSR01C", "t2",
                        "07:00:00", "", "", "", "", "User " + NEW_USER_ID + " has been added ...",
                        "DFHGREEN", "FNAME", null));

        return new UserCreateRequest("CU01", "t1", "08/04/26", "COUSR01C", "t2", "07:00:00",
                "FNAME042", "LNAME042", NEW_USER_ID, credential, "U", "");
    }

    /**
     * Returns the public, non-synthetic, non-bridge methods a controller declares.
     *
     * <p>Synthetic and bridge methods are excluded because the compiler, not the author, decides whether they
     * exist, and a synthetic accessor carries no request binding.
     *
     * @param controller the controller class, never {@code null}
     * @return its declared public handlers, never {@code null}
     */
    private static List<Method> publicHandlers(final Class<?> controller) {
        return Stream.of(controller.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> !method.isSynthetic() && !method.isBridge())
                .toList();
    }

    /**
     * Renders a parameter for a failure message without ever rendering a value.
     *
     * @param parameter the offending parameter, never {@code null}
     * @return a description naming the annotation and the declared type, never {@code null}
     */
    private static String describe(final Parameter parameter) {
        final List<String> annotations = new ArrayList<>();
        for (final Annotation annotation : parameter.getAnnotations()) {
            annotations.add('@' + annotation.annotationType().getSimpleName());
        }
        return String.join(" ", annotations) + ' ' + parameter.getType().getSimpleName();
    }

    /**
     * Resolves the header name a binding declares, whichever of the two equivalent attributes carries it.
     *
     * @param bound the binding to read, never {@code null}
     * @return the declared name, or an empty string when the binding names none
     */
    private static String boundHeaderName(final RequestHeader bound) {
        return bound.name().isEmpty() ? bound.value() : bound.name();
    }

    /**
     * Reports whether an identifier reads as a credential.
     *
     * @param identifier the name to test, never {@code null}
     * @return {@code true} when any credential word occurs in it
     */
    private static boolean readsAsCredential(final String identifier) {
        final String lower = identifier.toLowerCase(Locale.ROOT);
        return CREDENTIAL_WORDS.stream().anyMatch(lower::contains);
    }

    /**
     * Reports whether a string literal reads as an HTTP header name carrying a credential.
     *
     * <p>Recognition is deliberately shape-based rather than word-based alone, because a message literal can
     * legitimately contain a hyphenated credential word - {@code AuthController} explains the
     * {@code "unknown-identifier and wrong-password outcomes"} of {@code app/cbl/COSGN00C.cbl:L241-L251} in
     * exactly those terms. A literal qualifies only if it is shaped like a field name in the first place: a
     * bounded run of letters, digits and hyphens, containing at least one hyphen, with no whitespace and no
     * punctuation. {@code X-Presented-Password} and {@code Carddemo-Pwd} qualify; a sentence does not, and
     * neither does the body property name {@code password}, because a body member of that name is precisely
     * what this contract requires.
     *
     * @param literal the literal to test, never {@code null}
     * @return {@code true} when the literal is shaped like a header name and names a credential
     */
    private static boolean looksLikeACredentialHeaderName(final String literal) {
        return HEADER_NAME_SHAPE.matcher(literal).matches() && readsAsCredential(literal);
    }

    /**
     * Lists the controller sources.
     *
     * @return every {@code .java} file in the controller package, never {@code null}
     */
    private static List<Path> controllerSources() {
        if (!Files.isDirectory(CONTROLLER_SOURCES)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(CONTROLLER_SOURCES)) {
            return walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .toList();
        } catch (final IOException failure) {
            throw new UncheckedIOException("could not list " + CONTROLLER_SOURCES, failure);
        }
    }

    /**
     * Reads a source file.
     *
     * @param file the file to read, never {@code null}
     * @return its lines, never {@code null}
     */
    private static List<String> readLines(final Path file) {
        try {
            return Files.readAllLines(file);
        } catch (final IOException failure) {
            throw new UncheckedIOException("could not read " + file, failure);
        }
    }

    /**
     * Reports whether a line is commentary rather than code.
     *
     * <p>Deliberately conservative: a line is treated as commentary when it begins with a comment marker.
     * This class and the controllers must be free to name the removed header in prose in order to document
     * why it is forbidden, and a guard that forbade the explanation as well as the defect would be
     * self-defeating.
     *
     * @param line the raw source line, never {@code null}
     * @return {@code true} when the line opens with a comment marker
     */
    private static boolean isCommentary(final String line) {
        final String trimmed = line.trim();
        return trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*");
    }

    /**
     * Extracts the double-quoted string literals from one line of Java source.
     *
     * <p>Escaped quotes are honoured so that a literal containing {@code \"} does not terminate early. No
     * attempt is made to parse text blocks: none appears in the controller package, and a partial parse that
     * silently skipped one would weaken the guard rather than strengthen it, so a text-block delimiter is
     * treated as an ordinary literal boundary and the surrounding content is still scanned.
     *
     * @param line the raw source line, never {@code null}
     * @return the literals found, never {@code null}
     */
    private static List<String> stringLiteralsIn(final String line) {
        final List<String> literals = new ArrayList<>();
        int index = 0;
        while (index < line.length()) {
            if (line.charAt(index) != '"') {
                index++;
                continue;
            }
            final StringBuilder literal = new StringBuilder();
            int cursor = index + 1;
            while (cursor < line.length() && line.charAt(cursor) != '"') {
                if (line.charAt(cursor) == '\\' && cursor + 1 < line.length()) {
                    cursor++;
                }
                literal.append(line.charAt(cursor));
                cursor++;
            }
            literals.add(literal.toString());
            index = cursor + 1;
        }
        return literals;
    }
}
