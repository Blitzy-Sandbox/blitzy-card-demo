/*
 * ****************************************************************************
 * Test        : ControllerRoutingContractTest
 * Application : CardDemo
 * Type        : Java unit test - REST presentation tier
 * Function    : Assert the routing surface all eight controllers publish: the
 *               base paths, the HTTP verb per route, the total endpoint count,
 *               the constructor contracts, and that a controller delegates
 *               rather than deciding.
 * Source      : app/csd/CARDDEMO.CSD - the transaction-to-program map that
 *               fixes which endpoint replaces which screen program:
 *                 CC00      -> AuthController
 *                 CAVW/CAUP -> AccountController
 *                 CB00      -> BillingController
 *                 CCLI/CCDL/CCUP -> CardController
 *                 CM00/CA00 -> MenuController
 *                 CR00      -> ReportController
 *                 CT00/CT01/CT02 -> TransactionController
 *                 CU00/CU01/CU02/CU03 -> AdminController
 * ****************************************************************************
 *
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
 */

package com.cardemo.unit.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.Mockito.mock;

import com.cardemo.controller.AccountController;
import com.cardemo.controller.AdminController;
import com.cardemo.controller.AuthController;
import com.cardemo.controller.BillingController;
import com.cardemo.controller.CardController;
import com.cardemo.controller.MenuController;
import com.cardemo.controller.ReportController;
import com.cardemo.controller.TransactionController;
import com.cardemo.security.SnapshotTokenService;
import com.cardemo.service.account.AccountUpdateService;
import com.cardemo.service.account.AccountViewService;
import com.cardemo.service.admin.UserAddService;
import com.cardemo.service.admin.UserDeleteService;
import com.cardemo.service.admin.UserListService;
import com.cardemo.service.admin.UserUpdateService;
import com.cardemo.service.auth.AuthenticationService;
import com.cardemo.service.billing.BillPaymentService;
import com.cardemo.service.card.CardDetailService;
import com.cardemo.service.card.CardListService;
import com.cardemo.service.card.CardUpdateService;
import com.cardemo.service.menu.AdminMenuService;
import com.cardemo.service.menu.MainMenuService;
import com.cardemo.service.report.ReportSubmissionService;
import com.cardemo.service.transaction.TransactionAddService;
import com.cardemo.service.transaction.TransactionDetailService;
import com.cardemo.service.transaction.TransactionListService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contract tests for the eight controllers that replace the seventeen CICS screen programs.
 *
 * <p>A review recorded no dedicated controller tests at all. What was missing is specifically the
 * <em>routing</em> contract: which path each resource group publishes, which HTTP verb each operation answers
 * on, and how many endpoints exist in total. None of that is reachable from a service test, and all of it is
 * silently breakable - moving a base path or changing a verb compiles cleanly, passes every service test, and
 * breaks every caller.
 *
 * <p><strong>Why the assertions are made by reflection over annotations.</strong> The property under test is
 * what the framework will read at startup, which is the annotation, not the method body. Reading the
 * annotations directly asserts the same thing the {@code DispatcherServlet} will, without needing a servlet
 * container, and it fails on a renamed path rather than on a 404 in an integration test far away.
 *
 * <p><strong>Base paths are a published contract.</strong> They are what the CICS transaction identifiers
 * became: {@code CAVW} and {@code CAUP} are now two operations on one account resource, {@code CT00},
 * {@code CT01} and {@code CT02} three on one transaction resource. Verifying the paths is therefore verifying
 * the mapping recorded in the CSD, not merely a string constant.
 */
@DisplayName("Controller routing - the eight controllers and the seventeen endpoints they publish")
final class ControllerRoutingContractTest {

    /**
     * The endpoint count the eight controllers publish between them.
     *
     * <p>One route per sourced CICS transaction. The CSD defines eighteen transactions, but {@code CDV1}
     * fronts {@code COCRDSEC}, whose source is absent from the repository, so no endpoint exists for it and
     * the translated total is seventeen rather than eighteen.
     *
     * <p><strong>This was 12 while the surface was six controllers, and a review found it still saying 12
     * after two more controllers had been published.</strong> That is why
     * {@link RoutingSurface#everyControllerOnDiskIsUnderTest()} now reads the controller package from disk
     * and compares it with {@link #controllersByBasePath()}: an unregistered controller fails that test by
     * name instead of quietly leaving this constant correct for a subset.
     */
    private static final int EXPECTED_ENDPOINT_COUNT = 17;

    /** The number of controllers the presentation tier publishes. */
    private static final int EXPECTED_CONTROLLER_COUNT = 8;

    /**
     * The shape of a source citation inside a constructor-guard message.
     *
     * <p>Both letter cases are accepted for the extension because the frozen corpus uses both: twenty-six
     * members are lower-case and {@code CBSTM03A.CBL} and {@code CBSTM03B.CBL} are upper-case. Neither of
     * those two is a screen program, so no controller cites them, but a pattern that assumed one case would
     * be a trap for the next citation added.
     */
    private static final Pattern SOURCE_CITATION = Pattern.compile("app/cbl/[A-Za-z0-9]+\\.(?:cbl|CBL)");

    /**
     * The repository root, located by walking upward rather than trusting the working directory.
     *
     * <p>The build pins the forked working directory in both Surefire and Failsafe, so a bare relative path
     * would in fact resolve today. Climbing anyway costs one walk and removes the dependence entirely, which
     * is the same discipline applied to every other suite that reads the frozen corpus.
     */
    private static final Path REPOSITORY_ROOT = locateRepositoryRoot();

    /**
     * Walks upward from the working directory until the repository root is unambiguous.
     *
     * <p>All three markers are required together. {@code pom.xml} alone would match a nested module,
     * {@code src} alone any source tree; only the frozen {@code app} tree beside both identifies this
     * repository.
     *
     * @return the repository root
     */
    private static Path locateRepositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("pom.xml"))
                    && Files.isDirectory(candidate.resolve("src"))
                    && Files.isDirectory(candidate.resolve("app"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("could not locate the repository root above "
                + Path.of("").toAbsolutePath());
    }

    /**
     * Builds every controller with fully stubbed services.
     *
     * <p>Instantiating rather than loading a context is deliberate: routing is declared on the class, so it
     * can be read without a running application, and a controller that needed a context to reveal its own
     * routes would be carrying state it should not have.
     *
     * @return each controller instance, keyed by its expected base path
     */
    private static Map<String, Object> controllersByBasePath() {
        final Map<String, Object> controllers = new LinkedHashMap<>();
        controllers.put("/api/accounts", new AccountController(
                mock(AccountViewService.class), mock(AccountUpdateService.class)));
        controllers.put("/api/admin/users", new AdminController(
                mock(UserListService.class), mock(UserAddService.class),
                mock(UserUpdateService.class), mock(UserDeleteService.class)));
        controllers.put("/api/auth", new AuthController(mock(AuthenticationService.class)));
        controllers.put("/api/billing", new BillingController(mock(BillPaymentService.class)));
        controllers.put("/api/cards", new CardController(
                mock(CardListService.class), mock(CardDetailService.class), mock(CardUpdateService.class),
                mock(SnapshotTokenService.class)));
        controllers.put("/api/menu", new MenuController(
                mock(MainMenuService.class), mock(AdminMenuService.class)));
        controllers.put("/api/reports", new ReportController(mock(ReportSubmissionService.class)));
        controllers.put("/api/transactions", new TransactionController(
                mock(TransactionListService.class), mock(TransactionDetailService.class),
                mock(TransactionAddService.class)));
        return controllers;
    }

    /**
     * Lists the controller classes the package directory holds, by simple name, sorted.
     *
     * <p>Read from disk deliberately. {@link #controllersByBasePath()} is hand-maintained because each
     * controller needs its own stubbed collaborators, and a hand-maintained list can only go stale silently -
     * which it did, at six controllers. Comparing it against the directory turns the omission into a named
     * failure. {@code package-info.java} declares no type and is excluded.
     *
     * @return the controller simple names, sorted so the comparison is order-independent
     */
    private static List<String> controllerSimpleNamesOnDisk() {
        final Path directory = REPOSITORY_ROOT.resolve("src/main/java/com/cardemo/controller");
        try (java.util.stream.Stream<Path> sources = Files.list(directory)) {
            return sources
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".java"))
                    .filter(name -> !"package-info.java".equals(name))
                    .map(name -> name.substring(0, name.length() - ".java".length()))
                    .sorted()
                    .toList();
        } catch (final java.io.IOException cause) {
            throw new IllegalStateException("could not list " + directory, cause);
        }
    }

    /**
     * Supplies each controller class with the base path it must publish.
     *
     * @return one argument pair per controller
     */
    private static List<org.junit.jupiter.params.provider.Arguments> controllerBasePaths() {
        final List<org.junit.jupiter.params.provider.Arguments> cases = new ArrayList<>();
        controllersByBasePath().forEach((path, controller) ->
                cases.add(org.junit.jupiter.params.provider.Arguments.of(controller.getClass(), path)));
        return cases;
    }

    /**
     * Collects the request-handling methods of a controller: those carrying an HTTP verb mapping.
     *
     * @param type the controller class
     * @return its route methods
     */
    private static List<Method> routeMethods(final Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(GetMapping.class)
                        || method.isAnnotationPresent(PostMapping.class)
                        || method.isAnnotationPresent(PutMapping.class)
                        || method.isAnnotationPresent(DeleteMapping.class)
                        || method.isAnnotationPresent(PatchMapping.class))
                .toList();
    }

    /** The published routing surface. */
    @Nested
    @DisplayName("the published routing surface")
    final class RoutingSurface {

        @ParameterizedTest(name = "{0} publishes {1}")
        @MethodSource("com.cardemo.unit.controller.ControllerRoutingContractTest#controllerBasePaths")
        @DisplayName("each controller is a REST controller mapped at its documented base path")
        void basePathIsPublished(final Class<?> type, final String expectedBasePath) {
            assertThat(type.isAnnotationPresent(RestController.class))
                    .as("%s must be a @RestController: the target exposes JSON, not rendered views, because "
                            + "the BMS layer became a field contract rather than a user interface", type)
                    .isTrue();

            final RequestMapping mapping = type.getAnnotation(RequestMapping.class);
            assertThat(mapping)
                    .as("%s must declare a class-level @RequestMapping, or its routes are published at the "
                            + "application root and collide with every other controller", type)
                    .isNotNull();
            assertThat(mapping.value())
                    .as("the base path is the CICS transaction group's new address; changing it breaks every "
                            + "caller while compiling cleanly and passing every service test")
                    .containsExactly(expectedBasePath);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.cardemo.unit.controller.ControllerRoutingContractTest#controllerBasePaths")
        @DisplayName("every route returns ResponseEntity, so status is always explicit")
        void everyRouteReturnsResponseEntity(final Class<?> type, final String ignoredBasePath) {
            assertThat(routeMethods(type))
                    .as("%s must publish at least one route", type)
                    .isNotEmpty()
                    .allSatisfy(method -> assertThat(method.getReturnType())
                            .as("%s.%s must return ResponseEntity so the status code is chosen deliberately "
                                    + "rather than defaulted to 200 by the framework", type.getSimpleName(),
                                    method.getName())
                            .isEqualTo(ResponseEntity.class));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.cardemo.unit.controller.ControllerRoutingContractTest#controllerBasePaths")
        @DisplayName("every route is public, since a private handler is silently never mapped")
        void everyRouteIsPublic(final Class<?> type, final String ignoredBasePath) {
            assertThat(routeMethods(type))
                    .allSatisfy(method -> assertThat(java.lang.reflect.Modifier.isPublic(
                            method.getModifiers()))
                            .as("%s.%s carries a mapping annotation but is not public, so Spring would not "
                                    + "register it and the endpoint would simply not exist",
                                    type.getSimpleName(), method.getName())
                            .isTrue());
        }

        @Test
        @DisplayName("the eight controllers publish seventeen endpoints between them")
        void endpointCountIsSeventeen() {
            final Map<String, Integer> perController = new LinkedHashMap<>();
            controllersByBasePath().forEach((path, controller) ->
                    perController.put(controller.getClass().getSimpleName(),
                            routeMethods(controller.getClass()).size()));

            assertThat(perController.values().stream().mapToInt(Integer::intValue).sum())
                    .as("the endpoint inventory is evidence, so it is counted rather than asserted in prose. "
                            + "Per controller: %s", perController)
                    .isEqualTo(EXPECTED_ENDPOINT_COUNT);
        }

        @Test
        @DisplayName("the verb per operation follows the resource semantics, not habit")
        void verbsFollowResourceSemantics() {
            final Map<String, List<String>> verbs = new LinkedHashMap<>();
            controllersByBasePath().forEach((path, controller) -> {
                final List<String> methodVerbs = new ArrayList<>();
                for (final Method route : routeMethods(controller.getClass())) {
                    // Each verb is read from its own annotation. An else-branch that assumed PUT for
                    // everything not GET or POST would report the user-delete route as a PUT and the
                    // assertion below would then pass on a wrong verb.
                    if (route.isAnnotationPresent(GetMapping.class)) {
                        methodVerbs.add("GET");
                    } else if (route.isAnnotationPresent(PostMapping.class)) {
                        methodVerbs.add("POST");
                    } else if (route.isAnnotationPresent(PutMapping.class)) {
                        methodVerbs.add("PUT");
                    } else if (route.isAnnotationPresent(DeleteMapping.class)) {
                        methodVerbs.add("DELETE");
                    } else {
                        methodVerbs.add("PATCH");
                    }
                }
                verbs.put(controller.getClass().getSimpleName(), methodVerbs.stream().sorted().toList());
            });

            assertThat(verbs.get("AccountController"))
                    .as("a view is a GET and an update is a PUT: the update carries the whole record plus "
                            + "its snapshot, which is a replacement rather than a partial change")
                    .containsExactly("GET", "PUT");
            assertThat(verbs.get("AdminController"))
                    .as("the four user-administration operations of CU00, CU01, CU02 and CU03 are a list "
                            + "read, a create, a whole-record replacement and a destroy, so GET, POST, PUT "
                            + "and DELETE - one verb each, and the DELETE is why the verb reader tests its "
                            + "own annotation rather than defaulting")
                    .containsExactly("DELETE", "GET", "POST", "PUT");
            assertThat(verbs.get("AuthController"))
                    .as("signing on establishes an identity that did not exist, so it is a POST; it is also "
                            + "the one route the security chain grants anonymously, and a GET carrying a "
                            + "credential would put that credential in a query string")
                    .containsExactly("POST");
            assertThat(verbs.get("BillingController"))
                    .as("a bill payment creates a transaction, so it is a POST and not idempotent - the "
                            + "source generates a new identifier on every submission")
                    .containsExactly("POST");
            assertThat(verbs.get("CardController")).containsExactly("GET", "GET", "PUT");
            assertThat(verbs.get("MenuController"))
                    .as("both menus are pure reads")
                    .containsExactly("GET", "GET");
            assertThat(verbs.get("ReportController"))
                    .as("submitting a report enqueues work, which is a POST")
                    .containsExactly("POST");
            assertThat(verbs.get("TransactionController"))
                    .as("list and detail are GETs; adding a transaction is a POST")
                    .containsExactly("GET", "GET", "POST");
        }

        @Test
        @DisplayName("every controller the package holds is registered here, so none escapes this suite")
        void everyControllerOnDiskIsUnderTest() {
            final List<String> registered = controllersByBasePath().values().stream()
                    .map(controller -> controller.getClass().getSimpleName())
                    .sorted()
                    .toList();

            assertThat(registered)
                    .as("a controller present in src/main/java/com/cardemo/controller but absent from "
                            + "controllersByBasePath() is exercised by nothing in this class, and every "
                            + "count here stays correct for the subset - which is exactly how this suite "
                            + "went stale at six controllers and twelve endpoints")
                    .containsExactlyElementsOf(controllerSimpleNamesOnDisk())
                    .hasSize(EXPECTED_CONTROLLER_COUNT);
        }

        @Test
        @DisplayName("no two controllers share a base path")
        void basePathsAreDistinct()  {
            final List<String> paths = new ArrayList<>(controllersByBasePath().keySet());

            assertThat(paths)
                    .as("a shared base path would make route resolution depend on registration order")
                    .doesNotHaveDuplicates()
                    .hasSize(EXPECTED_CONTROLLER_COUNT);
        }

        @Test
        @DisplayName("every base path sits under the /api prefix the security rules match on")
        void everyBasePathIsUnderApi() {
            assertThat(controllersByBasePath().keySet())
                    .as("the security chain authorises by path prefix, so a controller published outside "
                            + "/api would fall outside every rule and be reachable unauthenticated")
                    .allSatisfy(path -> assertThat(path).startsWith("/api/"));
        }
    }

    /** Construction contracts. */
    @Nested
    @DisplayName("a controller cannot be built without its services")
    final class ConstructionContracts {

        // The controllers refuse a null collaborator with IllegalArgumentException rather than
        // NullPointerException, and each message names the COBOL program the service replaces. That is a
        // deliberate choice: the argument is wrong rather than merely absent, and the message tells a reader
        // which screen program stops working if the wiring is incomplete.

        @Test
        @DisplayName("AccountController requires both of its services")
        void accountControllerRequiresBoth() {
            assertThatIllegalArgumentException().isThrownBy(() ->
                    new AccountController(null, mock(AccountUpdateService.class)));
            assertThatIllegalArgumentException().isThrownBy(() ->
                    new AccountController(mock(AccountViewService.class), null));
        }

        @Test
        @DisplayName("CardController requires all four of its collaborators")
        void cardControllerRequiresAllThree() {
            // The fourth is SnapshotTokenService: the card list's cursor and the card update's
            // as-displayed snapshot are both minted and verified by it, so a controller built without one
            // would put a card number in a page cursor and a snapshot in a caller's hands. Each arm below
            // therefore withholds exactly one collaborator and supplies the other three.
            assertThatIllegalArgumentException().isThrownBy(() -> new CardController(
                    null, mock(CardDetailService.class), mock(CardUpdateService.class),
                    mock(SnapshotTokenService.class)));
            assertThatIllegalArgumentException().isThrownBy(() -> new CardController(
                    mock(CardListService.class), null, mock(CardUpdateService.class),
                    mock(SnapshotTokenService.class)));
            assertThatIllegalArgumentException().isThrownBy(() -> new CardController(
                    mock(CardListService.class), mock(CardDetailService.class), null,
                    mock(SnapshotTokenService.class)));
            assertThatIllegalArgumentException().isThrownBy(() -> new CardController(
                    mock(CardListService.class), mock(CardDetailService.class),
                    mock(CardUpdateService.class), null));
        }

        @Test
        @DisplayName("TransactionController requires all three of its services")
        void transactionControllerRequiresAllThree() {
            assertThatIllegalArgumentException().isThrownBy(() -> new TransactionController(
                    null, mock(TransactionDetailService.class), mock(TransactionAddService.class)));
            assertThatIllegalArgumentException().isThrownBy(() -> new TransactionController(
                    mock(TransactionListService.class), null, mock(TransactionAddService.class)));
            assertThatIllegalArgumentException().isThrownBy(() -> new TransactionController(
                    mock(TransactionListService.class), mock(TransactionDetailService.class), null));
        }

        @Test
        @DisplayName("MenuController requires both menu services")
        void menuControllerRequiresBoth() {
            assertThatIllegalArgumentException().isThrownBy(() ->
                    new MenuController(null, mock(AdminMenuService.class)));
            assertThatIllegalArgumentException().isThrownBy(() ->
                    new MenuController(mock(MainMenuService.class), null));
        }

        @Test
        @DisplayName("the single-service controllers require their one service")
        void singleServiceControllersRequireIt() {
            assertThatIllegalArgumentException().isThrownBy(() -> new BillingController(null));
            assertThatIllegalArgumentException().isThrownBy(() -> new ReportController(null));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.cardemo.unit.controller.ControllerRoutingContractTest#controllerBasePaths")
        @DisplayName("every constructor argument is guarded, and each refusal cites the program it replaces")
        void everyArgumentIsGuardedWithAResolvableCitation(final Class<?> type, final String ignoredBasePath) {
            final Constructor<?> constructor = soleConstructorOf(type);
            final Class<?>[] parameterTypes = constructor.getParameterTypes();
            assertThat(parameterTypes)
                    .as("%s must be constructor-injected; a no-argument controller would have to reach for "
                            + "its collaborators some other way", type.getSimpleName())
                    .isNotEmpty();

            for (int nulled = 0; nulled < parameterTypes.length; nulled++) {
                final Object[] arguments = new Object[parameterTypes.length];
                for (int slot = 0; slot < parameterTypes.length; slot++) {
                    arguments[slot] = slot == nulled ? null : mock(parameterTypes[slot]);
                }

                final Throwable refusal = refusalFrom(constructor, arguments, type, nulled);
                assertThat(refusal)
                        .as("%s accepted null for constructor argument %d (%s). An unguarded collaborator "
                                + "surfaces later as a NullPointerException from inside a request, which "
                                + "names neither the missing bean nor the screen it serves",
                                type.getSimpleName(), nulled, parameterTypes[nulled].getSimpleName())
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("must not be null");

                final Matcher citation = SOURCE_CITATION.matcher(String.valueOf(refusal.getMessage()));
                assertThat(citation.find())
                        .as("%s argument %d (%s) is guarded, but its message does not name the COBOL program "
                                + "the collaborator replaces. The message was: %s",
                                type.getSimpleName(), nulled, parameterTypes[nulled].getSimpleName(),
                                refusal.getMessage())
                        .isTrue();

                final String cited = citation.group();
                assertThat(REPOSITORY_ROOT.resolve(cited))
                        .as("%s argument %d cites %s, which does not resolve in the frozen corpus. A citation "
                                + "that does not resolve is worse than none: it reads as evidence and is not",
                                type.getSimpleName(), nulled, cited)
                        .exists();
            }
        }

        @Test
        @DisplayName("the guards together name all seventeen screen programs the controllers replace")
        void theGuardsNameEveryReplacedScreenProgram() {
            // Scoped to the SERVICE collaborators, which is what makes the claim "once each" true and
            // meaningful. Each of the seventeen sourced screen programs is replaced by exactly one service
            // bean, so the guard for that bean is the one place its program should be named. A controller may
            // also depend on a cross-cutting collaborator that replaces a PART of one of those programs rather
            // than the whole of it - SnapshotTokenService stands in for the CCUP-OLD-DETAILS snapshot
            // comparison and the WS-CA-SCREEN-NUM browse position - and its guard cites a program that is
            // already in the seventeen. Counting it here would report a duplicate for a citation that is
            // correct, and dropping its citation to satisfy an inventory would remove evidence the sibling
            // test requires.
            //
            // COCRDSEC is deliberately absent: app/csd/CARDDEMO.CSD defines transaction CDV1 against it, but
            // no source for it exists anywhere in the repository, so no controller replaces it and no guard
            // may cite it.
            final List<String> cited = new ArrayList<>();
            final List<String> otherCitations = new ArrayList<>();
            for (final Object controller : controllersByBasePath().values()) {
                final Class<?> type = controller.getClass();
                final Constructor<?> constructor = soleConstructorOf(type);
                final Class<?>[] parameterTypes = constructor.getParameterTypes();
                for (int nulled = 0; nulled < parameterTypes.length; nulled++) {
                    final Object[] arguments = new Object[parameterTypes.length];
                    for (int slot = 0; slot < parameterTypes.length; slot++) {
                        arguments[slot] = slot == nulled ? null : mock(parameterTypes[slot]);
                    }
                    final Matcher citation = SOURCE_CITATION.matcher(
                            String.valueOf(refusalFrom(constructor, arguments, type, nulled).getMessage()));
                    if (citation.find()) {
                        if (parameterTypes[nulled].getPackageName().startsWith("com.cardemo.service")) {
                            cited.add(citation.group());
                        } else {
                            otherCitations.add(citation.group());
                        }
                    }
                }
            }

            assertThat(cited)
                    .as("the guards should name the seventeen sourced screen programs of the CSD "
                            + "transaction-to-program map, once each")
                    .containsExactlyInAnyOrder(
                            "app/cbl/COSGN00C.cbl",
                            "app/cbl/COACTVWC.cbl", "app/cbl/COACTUPC.cbl",
                            "app/cbl/COBIL00C.cbl",
                            "app/cbl/COCRDLIC.cbl", "app/cbl/COCRDSLC.cbl", "app/cbl/COCRDUPC.cbl",
                            "app/cbl/COMEN01C.cbl", "app/cbl/COADM01C.cbl",
                            "app/cbl/CORPT00C.cbl",
                            "app/cbl/COTRN00C.cbl", "app/cbl/COTRN01C.cbl", "app/cbl/COTRN02C.cbl",
                            "app/cbl/COUSR00C.cbl", "app/cbl/COUSR01C.cbl", "app/cbl/COUSR02C.cbl",
                            "app/cbl/COUSR03C.cbl");

            // Every other guarded collaborator must still cite a program from within the seventeen. A citation
            // naming something outside that set would mean a controller depends on a collaborator standing in
            // for a program no controller replaces, which is a routing defect rather than a documentation one.
            assertThat(otherCitations)
                    .as("a non-service collaborator replaces part of a screen program, never a new one")
                    .isSubsetOf(cited);
        }

        private Constructor<?> soleConstructorOf(final Class<?> type) {
            final Constructor<?>[] declared = type.getDeclaredConstructors();
            assertThat(declared)
                    .as("%s should publish exactly one constructor, so Spring needs no @Autowired hint to "
                            + "choose between them", type.getSimpleName())
                    .hasSize(1);
            return declared[0];
        }

        private Throwable refusalFrom(final Constructor<?> constructor, final Object[] arguments,
                final Class<?> type, final int nulled) {
            try {
                constructor.newInstance(arguments);
                return null;
            } catch (final InvocationTargetException rejected) {
                return rejected.getCause();
            } catch (final ReflectiveOperationException unusable) {
                throw new AssertionError("could not construct " + type.getSimpleName()
                        + " with argument " + nulled + " nulled", unusable);
            }
        }
    }

    /** Statelessness. */
    @Nested
    @DisplayName("a controller holds no request state")
    final class Statelessness {

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.cardemo.unit.controller.ControllerRoutingContractTest#controllerBasePaths")
        @DisplayName("every instance field is final, so no request can leave state behind for the next")
        void everyFieldIsFinal(final Class<?> type, final String ignoredBasePath) {
            assertThat(type.getDeclaredFields())
                    .allSatisfy(field -> {
                        if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                            return;
                        }
                        assertThat(java.lang.reflect.Modifier.isFinal(field.getModifiers()))
                                .as("%s.%s is a mutable instance field on a singleton bean that serves every "
                                        + "concurrent request. The COMMAREA was per-task state; a field here "
                                        + "is shared state, which is a different and much worse thing",
                                        type.getSimpleName(), field.getName())
                                .isTrue();
                    });
        }
    }
}
