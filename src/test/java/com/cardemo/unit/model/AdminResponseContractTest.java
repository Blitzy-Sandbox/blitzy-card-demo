/*
 * ******************************************************************
 * Program     : AdminResponseContractTest.java
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 unit test
 * Function    : Pins the API-owned wire contract of the three user
 *               administration responses, and the boundary that keeps
 *               the service tier's own screen records off the wire.
 * Source      : app/cbl/COUSR00C.cbl (695 lines), COUSR01C.cbl (299),
 *               COUSR02C.cbl (414) over mapsets COUSR00, COUSR01 and
 *               COUSR02; :L57 (USER-REC OCCURS 10 TIMES),
 *               :L68-L71 (the paging cursor),
 *               COUSR01C.cbl:L255-L257 (' has been added ...'),
 *               COUSR02C.cbl:L219-L236 (WS-USR-MODIFIED and the write
 *               it gates) @ 7756d89
 * Source      : app/cpy-bms/COUSR00.CPY (59 input fields, five per row
 *               group of which SEL000nI is the selector),
 *               COUSR01.CPY and COUSR02.CPY (12 fields each, PASSWDI
 *               PIC X(8) inbound only) @ 7756d89
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
package com.cardemo.unit.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.cardemo.controller.AdminController;
import com.cardemo.model.dto.PageResponse;
import com.cardemo.model.dto.UserCreateResponse;
import com.cardemo.model.dto.UserListResponse;
import com.cardemo.model.dto.UserSecurityDto;
import com.cardemo.model.dto.UserUpdateResponse;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * The wire contract of the user-administration responses, and the boundary that produces them.
 *
 * <p><strong>Finding, severity High - this class pins the remediation.</strong> The list, add and update
 * operations returned the service tier's own screen records directly. Those records are faithful to the 3270
 * turn and therefore carry an advisory navigation target, a cursor field, a message-attribute byte, erase and
 * send counters, a control-transferred flag, a transfer-requested flag, the row selector and a duplicated
 * screen-and-page pair. Serialising them made terminal and implementation state the public REST contract - a
 * CWE-200-style exposure, and an accidental compatibility commitment to internals that would have made every
 * later change to a service record a breaking change to the wire.</p>
 *
 * <p>Three properties are asserted, and the first is the one that cannot be talked around: the handler return
 * types themselves. A response type that withholds the right members is worth nothing if the handler no longer
 * returns it, so the boundary is checked at the handler and at the payload, not only at the payload.</p>
 */
@DisplayName("Admin response contract - API-owned payloads, and no service record on the wire")
class AdminResponseContractTest {

    /** The identifier the fixtures use; one of the ten seeded standard users. */
    private static final String USER_ID = "USER0001";

    /**
     * The caption the source composes on the add success arm.
     *
     * <p>{@code app/cbl/COUSR01C.cbl:L255-L257} builds it with {@code STRING}, and the parity comparison is
     * made on text, so it is asserted byte for byte rather than by shape.
     */
    private static final String ADDED_CAPTION = "User USER0001 has been added ...";

    /** The caption the source emits when nothing differed, at {@code app/cbl/COUSR02C.cbl:L239}. */
    private static final String UNCHANGED_CAPTION = "Please modify to update ...";

    /**
     * Words that must not appear in any published component name.
     *
     * <p>Matched against a lower-cased component name, so a camel-cased member is caught too.
     */
    private static final List<String> CREDENTIAL_WORDS =
            List.of("password", "passwd", "pwd", "hash", "secret", "token", "credential");

    /**
     * The three methods every record generates, which the credential scan skips by name.
     *
     * <p>{@code hashCode} contains the substring {@code hash} for reasons unrelated to a credential. Skipping
     * exactly these three keeps the word list sharp instead of loosening it to accommodate a false positive.
     */
    private static final List<String> OBJECT_METHODS = List.of("hashCode", "equals", "toString");

    /**
     * The component names a record declares.
     *
     * @param type the record type to inspect, never {@code null}
     * @return the component names in declaration order, never {@code null}
     */
    private static List<String> componentNamesOf(final Class<?> type) {
        return Arrays.stream(type.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }

    /**
     * Builds one page of legacy rows, each carrying the selector the wire must not receive.
     *
     * @return a two-row page with both keyset boundaries set, never {@code null}
     */
    private static PageResponse<UserSecurityDto.UserRow> legacyPage() {
        return new PageResponse<>(
                List.of(
                        new UserSecurityDto.UserRow("U", USER_ID, "LAWRENCE", "THOMAS", "U"),
                        new UserSecurityDto.UserRow("D", "USER0002", "AJITH", "KUMAR", "U")),
                1,
                UserSecurityDto.PAGE_SIZE,
                true,
                USER_ID,
                "USER0002");
    }

    /** The handler boundary: what the three operations actually declare they return. */
    @Nested
    @DisplayName("1. the handlers return API-owned payloads, never a service record")
    class HandlerReturnTypes {

        @Test
        @DisplayName("each of the three body-bearing operations declares its own response payload")
        void eachOperationReturnsItsOwnPayload() throws NoSuchMethodException {
            assertThat(AdminController.class
                    .getMethod("listUsers", String.class, String.class, String.class, String.class,
                            String.class, String.class, String.class)
                    .getGenericReturnType().getTypeName())
                    .as("the page projection, not UserListService.UserListScreen")
                    .isEqualTo(ResponseEntity.class.getName() + "<" + UserListResponse.class.getName() + ">");

            assertThat(AdminController.class
                    .getMethod("addUser", com.cardemo.model.dto.UserCreateRequest.class)
                    .getGenericReturnType().getTypeName())
                    .as("the add projection, not UserAddService.UserAddScreen")
                    .isEqualTo(ResponseEntity.class.getName() + "<" + UserCreateResponse.class.getName()
                            + ">");

            assertThat(AdminController.class
                    .getMethod("updateUser", String.class, com.cardemo.model.dto.UserUpdateRequest.class)
                    .getGenericReturnType().getTypeName())
                    .as("the update projection, not UserUpdateService.UserUpdateScreen")
                    .isEqualTo(ResponseEntity.class.getName() + "<" + UserUpdateResponse.class.getName()
                            + ">");
        }

        @Test
        @DisplayName("no public handler returns anything declared in the service tier")
        void noHandlerReturnsAServiceType() {
            final List<String> offenders = Arrays.stream(AdminController.class.getDeclaredMethods())
                    .filter(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers()))
                    .filter(method -> !method.isSynthetic() && !method.isBridge())
                    .filter(method -> method.getGenericReturnType().getTypeName()
                            .contains("com.cardemo.service."))
                    .map(Method::getName)
                    .toList();

            assertThat(offenders)
                    .as("""
                        a service record on a public signature is the defect itself, whatever the record \
                        happens to contain today: it commits the wire to the service's internal shape, so \
                        every later change to a paragraph-faithful record becomes a breaking API change. \
                        Offenders: %s""", offenders)
                    .isEmpty();
        }

        @Test
        @DisplayName("every published response type lives in the data-transfer package")
        void everyResponseTypeIsApiOwned() {
            for (final Class<?> payload : List.of(UserListResponse.class, UserCreateResponse.class,
                    UserUpdateResponse.class)) {
                assertThat(payload.getPackageName())
                        .as("%s must be owned by the API, not by a caller of it", payload.getSimpleName())
                        .isEqualTo("com.cardemo.model.dto");
                assertThat(payload.isRecord())
                        .as("%s follows the sibling response convention", payload.getSimpleName())
                        .isTrue();
            }
        }
    }

    /** The payload boundary: what each response withholds. */
    @Nested
    @DisplayName("2. no terminal, navigation or credential member reaches the wire")
    class WithheldMembers {

        @Test
        @DisplayName("no response declares a component its own withheld list names")
        void noResponseDeclaresAWithheldComponent() {
            assertThat(componentNamesOf(UserListResponse.class))
                    .doesNotContainAnyElementsOf(UserListResponse.WITHHELD_COMPONENTS);
            assertThat(componentNamesOf(UserCreateResponse.class))
                    .doesNotContainAnyElementsOf(UserCreateResponse.WITHHELD_COMPONENTS);
            assertThat(componentNamesOf(UserUpdateResponse.class))
                    .doesNotContainAnyElementsOf(UserUpdateResponse.WITHHELD_COMPONENTS);
        }

        @Test
        @DisplayName("the withheld lists name every member the finding named")
        void theWithheldListsCoverTheFinding() {
            assertThat(UserListResponse.WITHHELD_COMPONENTS)
                    .as("the exact set the High-severity finding enumerated for the list operation")
                    .contains("navigationTarget", "cursorField", "errorFlagOn", "eraseRequested", "sendCount",
                            "controlTransferred", "selectionFlag", "selectedUserId", "screen", "page");
            assertThat(UserCreateResponse.WITHHELD_COMPONENTS)
                    .contains("messageColour", "cursorField", "navigationTarget");
            assertThat(UserUpdateResponse.WITHHELD_COMPONENTS)
                    .contains("messageColour", "cursorField", "navigationTarget", "transferRequested");
        }

        @Test
        @DisplayName("no component name and no accessor reads as a credential channel")
        void noCredentialSurfaceExists() {
            for (final Class<?> payload : List.of(UserListResponse.class,
                    UserListResponse.UserRowResponse.class, UserCreateResponse.class,
                    UserUpdateResponse.class)) {
                for (final String component : componentNamesOf(payload)) {
                    assertThat(CREDENTIAL_WORDS)
                            .as("%s.%s reads as a credential", payload.getSimpleName(), component)
                            .noneMatch(word -> component.toLowerCase(Locale.ROOT).contains(word));
                }
                for (final Method declared : payload.getDeclaredMethods()) {
                    // The three methods every record generates are excluded by name: hashCode carries the
                    // substring "hash" for reasons that have nothing to do with a credential, and excluding
                    // it by name is honest where widening the word list to accommodate it would blunt the
                    // check. Nothing else is exempt.
                    if (OBJECT_METHODS.contains(declared.getName())) {
                        continue;
                    }
                    assertThat(CREDENTIAL_WORDS)
                            .as("%s.%s() could read a credential", payload.getSimpleName(),
                                    declared.getName())
                            .noneMatch(word -> declared.getName().toLowerCase(Locale.ROOT).contains(word));
                }
            }
        }

        @Test
        @DisplayName("the six recurring header fields travel on none of the three")
        void noScreenChromeTravels() {
            for (final Class<?> payload : List.of(UserListResponse.class, UserCreateResponse.class,
                    UserUpdateResponse.class)) {
                assertThat(componentNamesOf(payload))
                        .as("%s must not publish screen chrome, two members of which are the server's own "
                                + "clock reading", payload.getSimpleName())
                        .doesNotContain("transactionName", "title01", "currentDate", "programName", "title02",
                                "currentTime");
            }
        }
    }

    /** The projection itself: what each response carries, and that it is byte-faithful. */
    @Nested
    @DisplayName("3. the projection carries the business fields, the cursor and the source's own message")
    class Projection {

        @Test
        @DisplayName("the list projection drops the row selector and keeps the four business columns")
        void theListProjectionDropsTheSelector() {
            final UserListResponse response = UserListResponse.of(legacyPage(), 1, null);

            assertThat(componentNamesOf(UserListResponse.UserRowResponse.class))
                    .as("four of the row group's five fields; SEL000nI has no counterpart because the "
                            + "operation accepts no selector inbound either")
                    .containsExactly("userId", "firstName", "lastName", "userType");
            assertThat(response.rows())
                    .extracting(UserListResponse.UserRowResponse::userId)
                    .containsExactly(USER_ID, "USER0002");
            assertThat(response.rows().getFirst().firstName()).isEqualTo("LAWRENCE");
            assertThat(response.rows().getFirst().userType()).isEqualTo("U");
        }

        @Test
        @DisplayName("the list projection publishes the cursor a caller echoes, including a zero page")
        void theListProjectionPublishesTheEchoedCursor() {
            assertThat(UserListResponse.of(legacyPage(), 3, null).pageNumber())
                    .as("CDEMO-CU00-PAGE-NUM as the source left it, which is what the next request carries")
                    .isEqualTo(3);
            assertThat(UserListResponse.of(legacyPage(), 0, null).pageNumber())
                    .as("""
                        and zero survives. The increment at app/cbl/COUSR00C.cbl:L320-L321 is guarded by \
                        IF WS-IDX > 1, so a browse that found nothing leaves zero; publishing the floored \
                        value instead would send a caller back to page one for ever.""")
                    .isZero();

            final UserListResponse response = UserListResponse.of(legacyPage(), 1, null);
            assertThat(response.pageSize()).isEqualTo(UserSecurityDto.PAGE_SIZE);
            assertThat(response.nextPageAvailable()).isTrue();
            assertThat(response.firstUserId()).isEqualTo(USER_ID);
            assertThat(response.lastUserId()).isEqualTo("USER0002");
        }

        @Test
        @DisplayName("no total element count and no total page count is invented")
        void noTotalsAreInvented() {
            assertThat(componentNamesOf(UserListResponse.class))
                    .as("the browse looks ahead exactly one record and never counts, so either number would "
                            + "be an invention")
                    .doesNotContain("totalElements", "totalPages", "totalRows", "count");
        }

        @Test
        @DisplayName("the add projection relays the source's caption byte for byte")
        void theAddProjectionRelaysItsCaption() {
            final UserCreateResponse response =
                    UserCreateResponse.of(USER_ID, "", "", "U", ADDED_CAPTION);

            assertThat(response.errorMessage())
                    .as("the STRING result of app/cbl/COUSR01C.cbl:L255-L257, on which the parity comparison "
                            + "is made")
                    .isEqualTo(ADDED_CAPTION);
            assertThat(response.userId()).isEqualTo(USER_ID);
            assertThat(componentNamesOf(UserCreateResponse.class))
                    .containsExactly("userId", "firstName", "lastName", "userType", "errorMessage");
        }

        @Test
        @DisplayName("the update projection publishes whether the rewrite actually happened")
        void theUpdateProjectionPublishesTheWriteDecision() {
            assertThat(UserUpdateResponse.of(USER_ID, "LAWRENCE", "THOMAS", "U",
                    "User USER0001 has been updated ...", true).updateApplied())
                    .as("WS-USR-MODIFIED as it stood at the write decision of app/cbl/COUSR02C.cbl:L236")
                    .isTrue();

            final UserUpdateResponse unchanged =
                    UserUpdateResponse.of(USER_ID, "LAWRENCE", "THOMAS", "U", UNCHANGED_CAPTION, false);
            assertThat(unchanged.updateApplied())
                    .as("""
                        false is the outcome the source reports with 'Please modify to update ...' at \
                        app/cbl/COUSR02C.cbl:L239 - nothing differed, so nothing was written. A caller that \
                        cannot tell it from a save has lost behaviour the screen conveyed, which is why this \
                        one member is published where the colour byte beside it is not.""")
                    .isFalse();
            assertThat(unchanged.errorMessage()).isEqualTo(UNCHANGED_CAPTION);
        }

        @Test
        @DisplayName("absence is preserved rather than collapsed into a blank")
        void absenceIsPreserved() {
            final UserCreateResponse added = UserCreateResponse.of(null, null, null, null, null);
            assertThat(added.userId()).isNull();
            assertThat(added.errorMessage()).isNull();

            final UserListResponse listed = UserListResponse.of(
                    new PageResponse<>(List.of(), 1, UserSecurityDto.PAGE_SIZE, false), 0, null);
            assertThat(listed.rows()).isEmpty();
            assertThat(listed.errorMessage()).isNull();
            assertThat(listed.nextPageAvailable()).isFalse();
        }
    }

    /** Immutability and rendering hygiene, on the same terms as the sibling payloads. */
    @Nested
    @DisplayName("4. the payloads are immutable and disclose nothing when rendered")
    class HygieneAndImmutability {

        @Test
        @DisplayName("the row list is copied in and exposed unmodifiable")
        void theRowListIsImmutable() {
            final UserListResponse response = UserListResponse.of(legacyPage(), 1, null);

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .as("a response cannot be altered after it is built")
                    .isThrownBy(() -> response.rows().clear());
            assertThat(new UserListResponse(null, 1, 10, false, null, null, null).rows())
                    .as("a null list becomes an empty page, because an empty page is a legitimate result")
                    .isEmpty();
        }

        @Test
        @DisplayName("no rendering publishes an identifier or a personal name")
        void renderingDisclosesNothing() {
            final String listed = UserListResponse.of(legacyPage(), 1, ADDED_CAPTION).toString();
            final String row = UserListResponse.UserRowResponse
                    .of(new UserSecurityDto.UserRow("U", USER_ID, "LAWRENCE", "THOMAS", "U")).toString();
            final String added = UserCreateResponse.of(USER_ID, "LAWRENCE", "THOMAS", "U", null).toString();
            final String updated =
                    UserUpdateResponse.of(USER_ID, "LAWRENCE", "THOMAS", "U", null, true).toString();

            for (final String rendered : List.of(listed, row, added, updated)) {
                assertThat(rendered)
                        .as("a generated rendering would put the whole page into any log line that "
                                + "interpolated it")
                        .doesNotContain(USER_ID)
                        .doesNotContain("LAWRENCE")
                        .doesNotContain("THOMAS");
            }
            assertThat(updated)
                    .as("the write decision is the one member worth having in a log line, and it names "
                            + "no person")
                    .contains("updateApplied=true");
        }
    }
}
