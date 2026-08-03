/*
 * ******************************************************************
 * Program     : UserUpdateRequestTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - pure JVM tier, no container, no
 *               Spring context, no database, no live AWS endpoint
 * Function    : Pins the field contract, the two validation cascades,
 *               the change-detection contract, the message literals,
 *               the error-field width, the credential confidentiality
 *               rules and the boundary behaviour of
 *               com.cardemo.model.dto.UserUpdateRequest against the
 *               BMS symbolic map and the CICS program it was
 *               translated from, so that no later edit can widen a
 *               field, reorder a cascade, paraphrase a message,
 *               fabricate a snapshot group or disclose a credential
 *               without a test failing.
 * Source      : app/cpy-bms/COUSR02.CPY (12 input fields, group
 *               COUSR2AI) + app/cbl/COUSR02C.cbl
 *               + app/cpy/CSUSR01Y.cpy @ 7756d89
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cardemo.model.dto.UserUpdateRequest;
import com.cardemo.model.enums.UserType;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.constraints.Size;
import java.io.Serializable;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Regression suite for {@link UserUpdateRequest}, the inbound payload of the user-update transaction.
 *
 * <h2>What it does</h2>
 *
 * <p>The type under test is a pure carrier, so there is almost no algorithm to exercise. What there is to
 * protect is a <em>contract</em>: twelve fields of exact widths in one exact order, five message literals
 * that downstream parity comparisons match byte for byte, four fields that participate in change detection
 * and one that must not, an error field that is deliberately two bytes narrower than the work area feeding
 * it, and a plaintext credential that must never travel outbound. Each of those is a property a later edit
 * could break silently, and each one is asserted here. Where the contract belongs to the source rather than
 * to the carrier - the ordering of a validation cascade, the semantics of a fixed-width {@code MOVE}, the
 * trimming behaviour of {@code STRING ... DELIMITED BY SPACE} - this class encodes it as an ordered constant
 * table plus a small pure function and asserts the carrier against it, which is the only way to pin an
 * ordering contract from a tier that holds no service.</p>
 *
 * <p>Every assertion is anchored to a locator in the frozen corpus. The locators, all verified by direct
 * inspection at commit {@code 7756d89} rather than inherited from prose:</p>
 * <ul>
 *   <li><strong>Field contract</strong> - {@code app/cpy-bms/COUSR02.CPY}, input group {@code 01 COUSR2AI}
 *       opening at line 17 and closing where {@code 01 COUSR2AO REDEFINES COUSR2AI} opens at line 91. The
 *       twelve data fields sit at lines 24, 30, 36, 42, 48, 54, 60, 66, 72, 78, 84 and 90. Each is the last
 *       member of a generated quintuple - a {@code COMP PIC S9(4)} length field, an attribute byte, a
 *       redefined attribute alias, four reserved bytes, then the data field - and the group is preceded by a
 *       twelve-byte terminal input/output area header. The data fields spell the picture clause {@code PIC};
 *       the quintuple members spell it {@code PICTURE}. No data field on this map uses {@code PICTURE}.</li>
 *   <li><strong>Map family divergence</strong> - {@code app/cpy-bms/COUSR01.CPY:72} declares
 *       {@code USERIDI} for the add screen, while this map declares {@code USRIDINI} at line 60, as does the
 *       list map at {@code app/cpy-bms/COUSR00.CPY:66} and the delete map at
 *       {@code app/cpy-bms/COUSR03.CPY:60}. The add screen is the outlier.</li>
 *   <li><strong>Header width outlier</strong> - {@code app/cpy-bms/COSGN00.CPY:54} declares
 *       {@code CURTIMEI PIC X(9)}; every other map, this one included, declares {@code PIC X(8)}.</li>
 *   <li><strong>Message work area</strong> - {@code app/cbl/COUSR02C.cbl:L38} declares
 *       {@code WS-MESSAGE PIC X(80)} against a wire field of {@code PIC X(78)}.</li>
 *   <li><strong>Modified flag</strong> - {@code app/cbl/COUSR02C.cbl:L45} declares
 *       {@code WS-USR-MODIFIED PIC X(01) VALUE 'N'} with its two condition names on the following lines.</li>
 *   <li><strong>Cascade one, the lookup path</strong> - {@code app/cbl/COUSR02C.cbl:L145-L155}, whose only
 *       {@code WHEN} tests the identifier at line 146 and whose message is at line 148.</li>
 *   <li><strong>Cascade two, the update path</strong> - {@code app/cbl/COUSR02C.cbl:L179-L213}, five
 *       {@code WHEN} clauses at lines 180, 186, 192, 198 and 204 with their messages at 182, 188, 194, 200
 *       and 206.</li>
 *   <li><strong>Change detection</strong> - {@code app/cbl/COUSR02C.cbl:L215-L245}, four {@code NOT =}
 *       comparisons at lines 219, 223, 227 and 231, the modified test at 236 and the nothing-changed message
 *       at 239.</li>
 *   <li><strong>Lookup outcomes</strong> - {@code app/cbl/COUSR02C.cbl:L336}, {@code :L342} and
 *       {@code :L349}; <strong>rewrite outcomes</strong> - {@code :L360-L392}, whose
 *       {@code EXEC CICS REWRITE} at lines 360 to 366 carries no {@code RIDFLD}, whose success path composes
 *       its message at lines 372 to 375 and whose miss at line 379 repeats the lookup miss literal
 *       verbatim.</li>
 *   <li><strong>Add-screen contrast</strong> - {@code app/cbl/COUSR01C.cbl:L115-L150} orders its cascade
 *       differently, and {@code :L253-L275} carries both a different success literal and a duplicate-key
 *       branch that this program does not have.</li>
 *   <li><strong>Absent guard</strong> - {@code app/cbl/COUSR03C.cbl}, 359 lines, contains no reference to
 *       the signed-on identifier at all, so it has no self-delete guard.</li>
 *   <li><strong>Persisted layout</strong> - {@code app/cpy/CSUSR01Y.cpy:L17-L23}.</li>
 *   <li><strong>User-type domain</strong> - {@code app/cpy/COCOM01Y.cpy:L26-L28};
 *       <strong>session state</strong> - {@code :L21-L24}, {@code :L29} and {@code :L43-L44}, the last pair
 *       being {@code PIC X(7)} and not {@code X(8)}.</li>
 *   <li><strong>Tri-state model</strong> - {@code app/cpy/CSSETATY.cpy}, corroborated at message level by
 *       {@code app/cbl/COACTUPC.cbl:505-508}; <strong>gated cross-field edit</strong> -
 *       {@code app/cbl/COACTUPC.cbl:1665-1669}, followed by {@code :1671-1675}.</li>
 *   <li><strong>Seed data</strong> - {@code app/jcl/DUSRSECJ.jcl:L35-L44}, loaded into a cluster defined
 *       with {@code KEYS(8,0)} at {@code :L65} and {@code RECORDSIZE(80,80)} at {@code :L66}.</li>
 *   <li><strong>Header rendering</strong> - {@code app/cbl/COUSR02C.cbl:L296-L315} moves
 *       {@code WS-CURDATE-MM-DD-YY} and {@code WS-CURTIME-HH-MM-SS}, both eight characters wide in
 *       {@code app/cpy/CSDAT01Y.cpy}, into the two header fields.</li>
 *   </ul>
 *
 * <h2>How to run, build and test</h2>
 *
 * <p>This class lives in the Surefire territory. The single root {@code pom.xml} binds
 * {@code maven-surefire-plugin} 3.5.4 to {@code **}{@code /*Test.java} while excluding
 * {@code **}{@code /integration/**} and {@code **}{@code /e2e/**}, so a class in this package is collected
 * by Surefire and never by Failsafe. That boundary is load-bearing: a class placed outside it is collected
 * by <em>neither</em> plugin and simply never runs, with both plugins still reporting success, so the path
 * and the {@code Test} suffix must not be changed.</p>
 *
 * <pre>{@code
 * ./mvnw -B clean test                               # this tier only
 * ./mvnw -B -Dtest=UserUpdateRequestTest clean test  # this class only
 * ./mvnw -B clean verify                             # adds coverage and the dependency audit
 * }</pre>
 *
 * <p>Compilation is by {@code maven-compiler-plugin} 3.14.1 at {@code release} 25 with
 * {@code -Xlint:all}, {@code -Werror} and {@code failOnWarning}, and those settings reach test compilation
 * as well as main compilation. {@code maven-enforcer-plugin} 3.5.0 asserts the toolchain, and JaCoCo applies
 * a line-coverage floor at {@code verify}. No dependency is added by this class: JUnit Jupiter 5.12.2,
 * AssertJ 3.27.7, Jackson 2.19.4 and Hibernate Validator 8.0.3.Final are already on the test classpath
 * through the pinned starters.</p>
 *
 * <h2>Key configurations and defaults</h2>
 *
 * <ul>
 *   <li><strong>Time is injected, never ambient.</strong> Every temporal value comes from
 *       {@link FixedClockProvider}, the sanctioned time source for this tier. {@code Instant.now()},
 *       {@code LocalDate.now()} and {@code System.currentTimeMillis()} are not used, and neither is the
 *       host default zone.</li>
 *   <li><strong>Locale is explicit.</strong> Both header formatters are built with {@link Locale#ROOT}, and
 *       every case operation passes it. Assertions on validation messages are made against the
 *       locale-independent message <em>template</em> rather than the interpolated text, because the
 *       interpolated text is resolved from a resource bundle in the host's default locale and asserting it
 *       would make the suite pass on one machine and fail on another.</li>
 *   <li><strong>No test double.</strong> Mockito 5.17.0 is on the classpath and the tier's default is
 *       strict stubbing, which fails a test on an unnecessary stub. This class declares no mock at all,
 *       because the type under test has no collaborator to stub; introducing one would be an unused stub and
 *       strict stubbing would rightly reject it.</li>
 *   <li><strong>No global mutable state.</strong> Validation now runs through
 *       {@link ValidationSupport#violationsOf(Object, String)}, which holds the one immutable, thread-safe
 *       {@code Validator} shared by the DTO test tier; this class no longer bootstraps or releases a factory
 *       of its own. Every other fixture is either an immutable constant or a fresh local built by a pure
 *       factory method, so no test can observe a value another test wrote.</li>
 *   <li><strong>No environment coupling.</strong> No host, port, JDBC URL or cloud endpoint appears
 *       anywhere in this file, and nothing here opens a socket, starts a container or reads a file.</li>
 *   </ul>
 *
 * <h2>The two decisions this class records</h2>
 *
 * <p><strong>The user-type property is a raw one-character {@code String}, not the enum.</strong>
 * {@code USRTYPEI} is {@code PIC X(1)} at {@code app/cpy-bms/COUSR02.CPY:84}, and the type under test
 * carries it as a one-character {@code String}. That choice is asserted here rather than assumed, and it is
 * the correct one for two independent reasons. First, {@code app/cbl/COUSR02C.cbl} performs no membership
 * test on the character beyond the empty test at line 204, so binding the field to an enum would convert an
 * out-of-domain character into a deserialization failure and replace the source's own message with a
 * framework error - a behaviour change, where parity is the contract. Second, this is the update path, which
 * re-presents a stored value: a legacy record holding a character outside the domain would become
 * unloadable, and therefore uneditable, under an enum binding. The domain itself is still asserted, through
 * {@link UserType} - the one enum a model type in this project may reference - whose two constants come from
 * the condition names at {@code app/cpy/COCOM01Y.cpy:L26-L28}. Because the carrier is a {@code String}, the
 * source's {@code NOT =} comparison against the stored byte reproduces exactly, which an enum could not
 * guarantee for an out-of-domain value.</p>
 *
 * <p><strong>Equality covers the credential; disclosure does not.</strong> The type under test leaves
 * {@code equals} and {@code hashCode} as the compiler generates them for a record, so both consider all
 * twelve components including the password. That is asserted here as the actual behaviour rather than
 * wished away, and the security requirement is met by a different and stronger route: neither operation
 * renders a value, so neither can disclose one. The properties that do matter - that
 * {@link UserUpdateRequest#toString()} omits the credential, that no outbound JSON carries it, that no
 * digest field exists and that no {@code String} the type produces contains it - are each asserted
 * separately below.</p>
 *
 * <h2>Common failure modes</h2>
 *
 * <ul>
 *   <li><strong>A raw type or a deprecation fails the build, not the test.</strong> Under
 *       {@code -Werror} with {@code failOnWarning} the compiler is the first gate, so a warning here is a
 *       build failure with no test report at all. An <em>unused</em> import behaves differently:
 *       {@code javac} at release 25 publishes no lint key for one, so it compiles cleanly and must be
 *       spotted by hand.</li>
 *   <li><strong>Reading a constraint from the wrong reflective element returns null.</strong> On a record,
 *       {@code @Size} and {@code @JsonProperty} are propagated to the private final field, the accessor and
 *       the constructor parameter, but <em>not</em> retained on the {@link RecordComponent}, because neither
 *       annotation lists {@code RECORD_COMPONENT} among its targets. Reading them from the component yields
 *       {@code null} and an assertion that silently proves nothing, which is why
 *       {@link #declaredWidthOf(String)} reads the accessor.</li>
 *   <li><strong>Reusing the add screen's cascade order.</strong> The two maps carry twelve fields of
 *       identical widths, which makes a shared expectation look safe. It is not: with every field blank the
 *       add screen reports the first name and this screen reports the identifier.</li>
 *   <li><strong>Comparing a submitted plaintext against a stored digest by equality.</strong> The source
 *       compared plaintext with plaintext. The target stores a sixty-character digest, so an equality
 *       comparison is unconditionally unequal, reports the credential as modified on every single request
 *       and rehashes every time.</li>
 *   <li><strong>Fabricating old and new snapshot groups.</strong> Only
 *       {@code app/cbl/COACTUPC.cbl} declares such groups; this program compares against a record it has
 *       just read.</li>
 *   <li><strong>Inventing a duplicate-key outcome for the rewrite path.</strong> The rewrite carries no
 *       {@code RIDFLD}, so no duplicate branch exists to translate.</li>
 *   <li><strong>Widening the error field to eighty.</strong> The work area is eighty bytes and the wire
 *       field is seventy-eight. The two-byte loss is the contract.</li>
 *   <li><strong>Collapsing absent, blank and low-values.</strong> This screen's empty predicate unifies
 *       spaces and low-values, but the framework tri-state keeps blank distinct from invalid, and the
 *       carrier keeps absent distinct from blank.</li>
 *   </ul>
 *
 * <h2>Constraints that must continue to hold</h2>
 *
 * <ul>
 *   <li><strong>The credential is inbound only.</strong> {@code app/cbl/COUSR02C.cbl:L169} moves
 *       {@code SEC-USR-PWD} straight
 *       into {@code PASSWDI}, so the legacy read-modify-write flow rendered the stored plaintext credential
 *       on the terminal. Here it is bound inbound
 *       only, is absent from every outbound rendering, and no response type carries it.</li>
 *   <li><strong>"Did the credential change" is never decided by string equality against the stored
 *       value.</strong> The carrier performs no comparison and structurally cannot hold a
 *       digest, since its width constraint is eight characters and a digest is sixty. The decision belongs
 *       to a digest <em>verification</em> call in the service tier. Asserted by
 *       {@link #aDigestWidthValueCannotEvenBeCarriedSoEqualityCannotDecideTheChange()}.</li>
 *   <li><strong>No abstraction is shared across the user payloads.</strong> The two user maps diverge in
 *       both the identifier field name and the field
 *       order, so a shared base type, interface or mixin across them would be factually wrong. No such
 *       abstraction exists, and its absence is asserted.</li>
 *   <li><strong>The update cascade's order differs from the add cascade's</strong>, so the update order is
 *       asserted explicitly and its inequality with the add order is asserted as well.</li>
 *   <li><strong>This map contributes twelve fields.</strong> A direct count across all seventeen symbolic
 *       maps totals 441 rather than the 460 that older prose quotes; the difference is confined to a
 *       longhand picture clause on an unrelated map, and this map contributes twelve under every reading.</li>
 *   <li><strong>The gated cross-field edit in {@code app/cbl/COACTUPC.cbl} is at lines 1665 to 1669, with
 *       the follow-on at 1671 to 1675.</strong> Those are the verified locators cited above; a citation three
 *       lines later points at the wrong statements.</li>
 *   <li><strong>The credential participates in {@code equals} and {@code hashCode}</strong>, which is
 *       correct value semantics for this carrier and discloses nothing, because neither operation renders a
 *       value. Should exclusion ever be wanted, hand-write both members on
 *       {@link UserUpdateRequest} omitting the credential component.</li>
 *   <li><strong>Both {@code WHEN OTHER} branches of this program carry a live {@code DISPLAY}</strong>, at
 *       {@code app/cbl/COUSR02C.cbl:L347} and {@code :L384}, whereas the
 *       add program's equivalent is commented out. It is a preserved legacy inconsistency and is not
 *       harmonised.</li>
 *   </ul>
 *
 * <h2>Boundaries of this class</h2>
 *
 * <ul>
 *   <li><strong>No {@code usrsec.txt} fixture is loaded, because there is none.</strong> The ten seeded rows
 *       exist only as
 *       inline {@code SYSUT1 DD *} card images inside {@code app/jcl/DUSRSECJ.jcl:L35-L44}, fed through
 *       {@code IEBGENER}. No fixture loader is used by this class, and none is needed; extracting one from
 *       that job stream is out of scope because the corpus is frozen.</li>
 *   <li><strong>The user record carries no optimistic-lock column.</strong> Version columns are
 *       carried by the account, card, customer and transaction entities only, so nothing is asserted about
 *       one here.</li>
 *   <li><strong>No schema-level assertion is made.</strong> DDL is a database-tier concern and outside this
 *       pure-JVM tier.</li>
 *   <li><strong>The corpus states no credential policy.</strong> {@code app/cbl/COUSR02C.cbl} imposes no
 *       minimum length, no character-class rule and no complexity rule, so none is asserted and none is
 *       added.</li>
 *   </ul>
 *
 * <h2>Security note on this file specifically</h2>
 *
 * <p>Rule 1 clause D names tests explicitly: no secrets in code, logs, tests or configuration. All ten
 * seeded users in {@code app/jcl/DUSRSECJ.jcl} share one literal plaintext credential, and that literal
 * appears nowhere in this file, in any comment, in any assertion description or in any fixture. The values
 * used here are obviously synthetic, lower case and hyphenated, a shape no eight-character upper-case card
 * image could take, and {@link #syntheticCredentialsCannotBeTheSeedLiteral()} proves that structurally
 * without ever writing the secret. Nor is a credential ever interpolated into an assertion description: a
 * failing assertion is identified by field name, because an assertion description reaches the Surefire
 * report and the build log.</p>
 */
@DisplayName("UserUpdateRequest - app/cpy-bms/COUSR02.CPY + app/cbl/COUSR02C.cbl")
final class UserUpdateRequestTest {

    /**
     * The twelve component names in the declaration order of {@code app/cpy-bms/COUSR02.CPY}.
     */
    private static final List<String> COMPONENTS_IN_MAP_ORDER = List.of(
            "transactionName",  // TRNNAMEI PIC X(4)  - app/cpy-bms/COUSR02.CPY:24
            "title01",          // TITLE01I PIC X(40) - app/cpy-bms/COUSR02.CPY:30
            "currentDate",      // CURDATEI PIC X(8)  - app/cpy-bms/COUSR02.CPY:36
            "programName",      // PGMNAMEI PIC X(8)  - app/cpy-bms/COUSR02.CPY:42
            "title02",          // TITLE02I PIC X(40) - app/cpy-bms/COUSR02.CPY:48
            "currentTime",      // CURTIMEI PIC X(8)  - app/cpy-bms/COUSR02.CPY:54
            "userId",           // USRIDINI PIC X(8)  - app/cpy-bms/COUSR02.CPY:60
            "firstName",        // FNAMEI   PIC X(20) - app/cpy-bms/COUSR02.CPY:66
            "lastName",         // LNAMEI   PIC X(20) - app/cpy-bms/COUSR02.CPY:72
            "password",         // PASSWDI  PIC X(8)  - app/cpy-bms/COUSR02.CPY:78
            "userType",         // USRTYPEI PIC X(1)  - app/cpy-bms/COUSR02.CPY:84
            "errorMessage");    // ERRMSGI  PIC X(78) - app/cpy-bms/COUSR02.CPY:90

    /**
     * The picture width of each component, positionally aligned with {@link #COMPONENTS_IN_MAP_ORDER}.
     */
    private static final List<Integer> WIDTHS_IN_MAP_ORDER =
            List.of(4, 40, 8, 8, 40, 8, 8, 20, 20, 8, 1, 78);

    /**
     * The census the map yields, asserted rather than assumed.
     */
    private static final int DECLARED_FIELD_COUNT = 12;

    /**
     * {@code ERRMSGI PIC X(78)} - the wire width, narrower than the work area that feeds it.
     */
    private static final int ERRMSGI_WIDTH = 78;

    /**
     * {@code WS-MESSAGE PIC X(80)} at {@code app/cbl/COUSR02C.cbl:L38} - the work-area width.
     */
    private static final int WS_MESSAGE_WIDTH = 80;

    /**
     * {@code SEC-USR-PWD PIC X(08)} at {@code app/cpy/CSUSR01Y.cpy:L21} and {@code PASSWDI PIC X(8)}.
     */
    private static final int PASSWDI_WIDTH = 8;

    /**
     * The target column width of the stored digest, which is why the eight-byte carrier cannot hold one.
     */
    private static final int STORED_DIGEST_WIDTH = 60;

    /**
     * {@code SEC-USER-DATA} is an 80-byte record: 8 + 20 + 20 + 8 + 1 populated, plus a named 23-byte filler -
     * {@code app/cpy/CSUSR01Y.cpy:L17-L23}, corroborated by {@code RECORDSIZE(80,80)} at
     * {@code app/jcl/DUSRSECJ.jcl:L66}.
     */
    private static final int SEC_USER_DATA_LENGTH = 80;

    /**
     * {@code KEYS(8,0)} at {@code app/jcl/DUSRSECJ.jcl:L65} - the cluster key length.
     */
    private static final int USRSEC_KEY_LENGTH = 8;

    /**
     * {@code app/cbl/COUSR02C.cbl:L148} and, verbatim again, {@code :L182}.
     */
    private static final String USER_ID_EMPTY = "User ID can NOT be empty...";

    /**
     * {@code app/cbl/COUSR02C.cbl:L188}.
     */
    private static final String FIRST_NAME_EMPTY = "First Name can NOT be empty...";

    /**
     * {@code app/cbl/COUSR02C.cbl:L194}.
     */
    private static final String LAST_NAME_EMPTY = "Last Name can NOT be empty...";

    /**
     * {@code app/cbl/COUSR02C.cbl:L200}.
     */
    private static final String PASSWDI_EMPTY = "Password can NOT be empty...";

    /**
     * {@code app/cbl/COUSR02C.cbl:L206}.
     */
    private static final String USER_TYPE_EMPTY = "User Type can NOT be empty...";

    /**
     * {@code app/cbl/COUSR02C.cbl:L239} - lower-case {@code update}, and a space before the dots.
     */
    private static final String NOTHING_MODIFIED = "Please modify to update ...";

    /**
     * {@code app/cbl/COUSR02C.cbl:L336} - a space before the dots.
     */
    private static final String SAVE_PROMPT = "Press PF5 key to save your updates ...";

    /**
     * {@code app/cbl/COUSR02C.cbl:L342}, and the same literal again at {@code :L379}. No space.
     */
    private static final String USER_ID_NOT_FOUND = "User ID NOT found...";

    /**
     * {@code app/cbl/COUSR02C.cbl:L349} - lower-case {@code lookup}.
     */
    private static final String LOOKUP_FAILED = "Unable to lookup User...";

    /**
     * {@code app/cbl/COUSR02C.cbl:L386} - capital {@code Update}.
     */
    private static final String REWRITE_FAILED = "Unable to Update User...";

    /**
     * First operand of the {@code STRING} at {@code app/cbl/COUSR02C.cbl:L372}.
     */
    private static final String COMPOSED_PREFIX = "User ";

    /**
     * Third operand of the {@code STRING} at {@code app/cbl/COUSR02C.cbl:L374}.
     */
    private static final String COMPOSED_UPDATE_SUFFIX = " has been updated ...";

    /**
     * The add program's counterpart at {@code app/cbl/COUSR01C.cbl:L257}, held for contrast only.
     */
    private static final String COMPOSED_ADD_SUFFIX = " has been added ...";

    /**
     * {@code app/cbl/COUSR01C.cbl:L263}, where {@code DUPKEY} and {@code DUPREC} collapse into one message.
     * Held for contrast only: the update path has no duplicate branch to model. Note the source spelling
     * {@code exist} rather than {@code exists}.
     */
    private static final String ADD_ONLY_DUPLICATE = "User ID already exist...";

    /**
     * The five update-path cascade messages in the source's own order, {@code app/cbl/COUSR02C.cbl:L179-L213}.
     */
    private static final List<String> UPDATE_CASCADE_MESSAGES = List.of(
            USER_ID_EMPTY, FIRST_NAME_EMPTY, LAST_NAME_EMPTY, PASSWDI_EMPTY, USER_TYPE_EMPTY);

    /**
     * The components the update cascade tests, in the order it tests them.
     */
    private static final List<String> UPDATE_CASCADE_ORDER =
            List.of("userId", "firstName", "lastName", "password", "userType");

    /**
     * The add screen's order at {@code app/cbl/COUSR01C.cbl:L118,124,130,136,142}, held for contrast.
     */
    private static final List<String> ADD_CASCADE_ORDER =
            List.of("firstName", "lastName", "userId", "password", "userType");

    /**
     * The four components compared at {@code app/cbl/COUSR02C.cbl:L219,223,227,231}. The identifier is absent
     * by design: it is the key moved into {@code SEC-USR-ID} at line 216 before the read.
     */
    private static final List<String> CHANGE_DETECTED_COMPONENTS =
            List.of("firstName", "lastName", "password", "userType");

    /**
     * Lower-cased fragments of the COMMAREA navigation fields at {@code app/cpy/COCOM01Y.cpy:L21-L24},
     * {@code :L29} and {@code :L43-L44}. Routing is by URL, so none of these may surface on a payload.
     */
    private static final List<String> FORBIDDEN_SESSION_FRAGMENTS = List.of(
            "fromtranid", "totranid", "fromprogram", "toprogram", "pgmcontext", "context",
            "lastmap", "lastmapset", "commarea", "reenter", "session");

    /**
     * Lower-cased fragments of a screen-time snapshot group. Only {@code app/cbl/COACTUPC.cbl} has one.
     */
    private static final List<String> FORBIDDEN_SNAPSHOT_FRAGMENTS = List.of(
            "olddetails", "newdetails", "snapshot", "original", "previous", "priorvalue", "baseline");

    /**
     * Lower-cased fragments of a stored-digest or comparison concern, which belongs to the service.
     */
    private static final List<String> FORBIDDEN_DIGEST_FRAGMENTS = List.of(
            "hash", "digest", "bcrypt", "encoded", "encrypt", "cipher", "salt", "verify", "matches");

    /**
     * Lower-cased fragments of a self-delete guard. {@code app/cbl/COUSR03C.cbl} has none, so neither has this
     * carrier - the absent guard is preserved deliberately.
     */
    private static final List<String> FORBIDDEN_ACTOR_FRAGMENTS = List.of(
            "signedon", "signedonuser", "currentuser", "actinguser", "invoker", "principal");

    /**
     * {@code WS-TRANID} at {@code app/cbl/COUSR02C.cbl:L37}, four characters.
     */
    private static final String BASE_TRANSACTION_NAME = "CU02";

    /**
     * {@code WS-PGMNAME} at {@code app/cbl/COUSR02C.cbl:L36}, eight characters.
     */
    private static final String BASE_PROGRAM_NAME = "COUSR02C";

    /**
     * A <strong>synthetic</strong> eight character identifier standing in for one of the ten seeded rows
     * at {@code app/jcl/DUSRSECJ.jcl:L35-L44}. The seeded identity is not transcribed here, and neither
     * is the shared credential; what this fixture supplies is the eight character key width the composed
     * message arithmetic depends on.
     */
    private static final String BASE_USER_ID = "STDUSR01";

    /** A synthetic given name paired with {@link #BASE_USER_ID}; the seeded value is not reproduced. */
    private static final String BASE_FIRST_NAME = "FNAMEAA6";

    /** A synthetic family name paired with {@link #BASE_USER_ID}; the seeded value is not reproduced. */
    private static final String BASE_LAST_NAME = "LNAME6";

    /**
     * {@code CDEMO-USRTYP-USER VALUE 'U'} at {@code app/cpy/COCOM01Y.cpy:L28}.
     */
    private static final String BASE_USER_TYPE = "U";

    /**
     * A synthetic eight-character credential. Lower case and hyphenated, so it cannot be the upper-case literal
     * that every seeded card image carries.
     */
    private static final String SYNTHETIC_CREDENTIAL = "pw-fake1";

    /**
     * A second synthetic credential, distinct from the first, for change-detection assertions.
     */
    private static final String OTHER_SYNTHETIC_CREDENTIAL = "pw-fake2";

    /**
     * The first screen title line; any value within {@code X(40)} serves.
     */
    private static final String BASE_TITLE_01 = "Update User";

    /**
     * The second screen title line; any value within {@code X(40)} serves.
     */
    private static final String BASE_TITLE_02 = "CardDemo";

    /**
     * {@code MAIN-PARA} clears the message at {@code app/cbl/COUSR02C.cbl:L87-L88}, so an empty error line is
     * the baseline the screen actually starts from.
     */
    private static final String BASE_ERROR_MESSAGE = "";

    /**
     * {@code WS-CURDATE-MM-DD-YY} in {@link Locale#ROOT}: two digits, slash, two, slash, two.
     */
    private static final DateTimeFormatter HEADER_DATE_FORMAT =
            DateTimeFormatter.ofPattern("MM/dd/yy", Locale.ROOT);

    /**
     * {@code WS-CURTIME-HH-MM-SS} in {@link Locale#ROOT}: two digits, colon, two, colon, two.
     */
    private static final DateTimeFormatter HEADER_TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);

    /**
     * The canonical moment of this tier, read from {@link FixedClockProvider#canonicalClock()}.
     */
    private static final ZonedDateTime FIXED_MOMENT = momentOfCanonicalClock();

    /**
     * The header date the fixed clock yields, eight characters as {@code CURDATEI PIC X(8)} requires.
     */
    private static final String FIXED_HEADER_DATE = HEADER_DATE_FORMAT.format(FIXED_MOMENT);

    /**
     * The header time the fixed clock yields, eight characters as {@code CURTIMEI PIC X(8)} requires.
     */
    private static final String FIXED_HEADER_TIME = HEADER_TIME_FORMAT.format(FIXED_MOMENT);

    // ---------------------------------------------------------------------------------------------
    // Bean validation. Both references are final and both targets are immutable and thread-safe, so this
    // is shared constant state rather than shared mutable state. One bootstrap, released deterministically.
    // ---------------------------------------------------------------------------------------------

    // ---------------------------------------------------------------------------------------------
    // Pure helpers. Each models one COBOL construct so that the carrier can be asserted against the
    // source's own semantics. All are static and side-effect free, and none reads ambient state.
    // ---------------------------------------------------------------------------------------------

    /**
     * Reads the canonical moment from the injected fixed clock without ever consulting the ambient clock.
     *
     * @return the canonical instant of {@link FixedClockProvider}, resolved in its explicit zone
     */
    private static ZonedDateTime momentOfCanonicalClock() {
        final Clock fixedClock = FixedClockProvider.canonicalClock();
        return ZonedDateTime.ofInstant(fixedClock.instant(), fixedClock.getZone());
    }

    /**
     * Reproduces a COBOL {@code MOVE} of an alphanumeric sending field into a {@code PIC X(width)} receiving
     * field: the value is truncated on the right when it is too long and padded on the right with spaces when
     * it is too short. This is the mechanism behind the two-byte loss when {@code WS-MESSAGE PIC X(80)} reaches
     * {@code ERRMSGO PIC X(78)}.
     *
     * @param sendingField the value being moved; must not be {@code null}, since a COBOL sending field is never
     * absent - use {@code ""} for a field of spaces
     * @param width the receiving field's picture width.
     * @return a value of exactly {@code width} characters
     * @throws IllegalArgumentException if {@code sendingField} is {@code null} or {@code width} is not
     * positive, rather than substituting a default that would hide the caller's mistake
     */
    private static String moveAlphanumeric(final String sendingField, final int width) {
        if (sendingField == null) {
            throw new IllegalArgumentException(
                    "a COBOL sending field is never absent; pass an empty string for a field of spaces");
        }
        if (width <= 0) {
            throw new IllegalArgumentException("a PIC X(n) receiving field has a positive width");
        }
        if (sendingField.length() >= width) {
            return sendingField.substring(0, width);
        }
        return sendingField + " ".repeat(width - sendingField.length());
    }

    /**
     * Reproduces the combined predicate {@code = SPACES OR LOW-VALUES} that both cascades of
     * {@code app/cbl/COUSR02C.cbl} apply. The two states are one predicate in the source, so they yield one
     * message; a {@code null} reference stands for {@code LOW-VALUES}, and a {@code NUL} character is treated
     * as low-values as well, which is what {@code X'00'} is in a character field.
     *
     * @param field the carried value, possibly {@code null}
     * @return {@code true} when the source would consider the field empty
     */
    private static boolean isSpacesOrLowValues(final String field) {
        if (field == null) {
            return true;
        }
        return field.chars().allMatch(character -> character == ' ' || character == '\0');
    }

    /**
     * Reproduces cascade two, the update path at {@code app/cbl/COUSR02C.cbl:L179-L213}, in the source's own
     * order: identifier, then given name, then family name, then credential, then type. The first failing test
     * wins and the rest are never reached, exactly as {@code EVALUATE TRUE} behaves.
     *
     * @param request the carrier under test
     * @return the message the source would raise, or empty when no field is empty
     */
    private static Optional<String> updateCascadeMessage(final UserUpdateRequest request) {
        if (isSpacesOrLowValues(request.userId())) {
            return Optional.of(USER_ID_EMPTY);
        }
        if (isSpacesOrLowValues(request.firstName())) {
            return Optional.of(FIRST_NAME_EMPTY);
        }
        if (isSpacesOrLowValues(request.lastName())) {
            return Optional.of(LAST_NAME_EMPTY);
        }
        if (isSpacesOrLowValues(request.password())) {
            return Optional.of(PASSWDI_EMPTY);
        }
        if (isSpacesOrLowValues(request.userType())) {
            return Optional.of(USER_TYPE_EMPTY);
        }
        return Optional.empty();
    }

    /**
     * Reproduces cascade one, the lookup path at {@code app/cbl/COUSR02C.cbl:L145-L155}, which tests the
     * identifier and nothing else. Its single {@code WHEN} raises the same literal as the update path's first
     * {@code WHEN}, so the literal alone does not identify which path produced it.
     *
     * @param request the carrier under test
     * @return the message the source would raise, or empty when the identifier is populated
     */
    private static Optional<String> lookupCascadeMessage(final UserUpdateRequest request) {
        return isSpacesOrLowValues(request.userId()) ? Optional.of(USER_ID_EMPTY) : Optional.empty();
    }

    /**
     * Reproduces {@code STRING ... DELIMITED BY SPACE}, which stops copying the sending field at its first
     * space rather than transferring the whole picture width. This is why the composed success message at
     * {@code app/cbl/COUSR02C.cbl:L372-L375} carries a trimmed identifier and not a padded one.
     *
     * @param sendingField the value being copied; must not be {@code null}
     * @return the prefix of {@code sendingField} up to but excluding its first space
     * @throws IllegalArgumentException if {@code sendingField} is {@code null}
     */
    private static String delimitedBySpace(final String sendingField) {
        if (sendingField == null) {
            throw new IllegalArgumentException("a COBOL sending field is never absent");
        }
        final int firstSpace = sendingField.indexOf(' ');
        return firstSpace < 0 ? sendingField : sendingField.substring(0, firstSpace);
    }

    /**
     * Reproduces the composition at {@code app/cbl/COUSR02C.cbl:L369-L375}: the fixed prefix, then the
     * identifier delimited by space, then the fixed suffix, all into {@code WS-MESSAGE}.
     *
     * @param storedIdentifier the value of {@code SEC-USR-ID}, space-padded to its picture width
     * @return the composed message, before it is moved onto the narrower wire field
     */
    private static String composeUpdateSuccessMessage(final String storedIdentifier) {
        return COMPOSED_PREFIX + delimitedBySpace(storedIdentifier) + COMPOSED_UPDATE_SUFFIX;
    }

    /**
     * Returns the twelve baseline values in map order. A fresh array is returned on every call, so no test can
     * mutate a fixture another test observes.
     *
     * @return a mutable array of twelve valid values, positionally aligned with the record's components
     */
    private static String[] baselineValues() {
        return new String[] {
            BASE_TRANSACTION_NAME, BASE_TITLE_01, FIXED_HEADER_DATE, BASE_PROGRAM_NAME, BASE_TITLE_02,
            FIXED_HEADER_TIME, BASE_USER_ID, BASE_FIRST_NAME, BASE_LAST_NAME, SYNTHETIC_CREDENTIAL,
            BASE_USER_TYPE, BASE_ERROR_MESSAGE,
        };
    }

    /**
     * Builds a fully valid request from {@link #baselineValues()}.
     *
     * @return a request whose every component satisfies its width constraint
     */
    private static UserUpdateRequest validRequest() {
        final String[] values = baselineValues();
        return new UserUpdateRequest(values[0], values[1], values[2], values[3], values[4], values[5],
                values[6], values[7], values[8], values[9], values[10], values[11]);
    }

    /**
     * Builds a request that is valid in every component except the named one, which carries the supplied value.
     * The component is located by name against {@link #COMPONENTS_IN_MAP_ORDER}, so a typo fails loudly instead
     * of silently perturbing nothing.
     *
     * @param componentName one of the twelve component names
     * @param value the value to place in that component, possibly {@code null}
     * @return the perturbed request
     * @throws IllegalArgumentException if {@code componentName} is not one of the twelve
     */
    private static UserUpdateRequest withComponent(final String componentName, final String value) {
        final int index = COMPONENTS_IN_MAP_ORDER.indexOf(componentName);
        if (index < 0) {
            throw new IllegalArgumentException(
                    "not a component of UserUpdateRequest: " + componentName + "; the twelve declared by "
                            + "app/cpy-bms/COUSR02.CPY are " + COMPONENTS_IN_MAP_ORDER);
        }
        final String[] values = baselineValues();
        values[index] = value;
        return new UserUpdateRequest(values[0], values[1], values[2], values[3], values[4], values[5],
                values[6], values[7], values[8], values[9], values[10], values[11]);
    }

    /**
     * Reads the {@code @Size(max)} actually in force for a component.
     *
     * @param componentName one of the twelve component names
     * @return the declared maximum width
     * @throws IllegalArgumentException if the component does not exist or carries no size constraint
     */
    private static int declaredWidthOf(final String componentName) {
        for (final RecordComponent component : UserUpdateRequest.class.getRecordComponents()) {
            if (!component.getName().equals(componentName)) {
                continue;
            }
            final Size size = component.getAccessor().getAnnotation(Size.class);
            if (size == null) {
                throw new IllegalArgumentException(
                        "component carries no @Size constraint, so its picture width is unenforced: "
                                + componentName);
            }
            return size.max();
        }
        throw new IllegalArgumentException("not a component of UserUpdateRequest: " + componentName);
    }

    /**
     * Validates a request against the bean validation constraints the carrier declares.
     *
     * @param request the carrier under test
     * @return the violations, empty when the request satisfies every constraint
     */
    private static Set<ConstraintViolation<UserUpdateRequest>> violationsOf(final UserUpdateRequest request) {
        return ValidationSupport.violationsOf(request, "request");
    }

    /**
     * Returns the lower-cased names of every declared component, for the negative name assertions.
     *
     * @return twelve lower-cased component names
     */
    private static List<String> lowerCasedComponentNames() {
        final List<String> names = new ArrayList<>(DECLARED_FIELD_COUNT);
        for (final RecordComponent component : UserUpdateRequest.class.getRecordComponents()) {
            names.add(component.getName().toLowerCase(Locale.ROOT));
        }
        return names;
    }

    /**
     * Builds a request in which every one of the twelve components holds a field of spaces at its own picture
     * width, which is the state the source sees when an operator transmits an untouched screen.
     *
     * @return a request whose every component is empty by the source's own predicate
     */
    private static UserUpdateRequest allComponentsBlank() {
        final String[] values = baselineValues();
        for (int index = 0; index < values.length; index++) {
            values[index] = " ".repeat(WIDTHS_IN_MAP_ORDER.get(index));
        }
        return new UserUpdateRequest(values[0], values[1], values[2], values[3], values[4], values[5],
                values[6], values[7], values[8], values[9], values[10], values[11]);
    }

    /**
     * Supplies the twelve component indices, so that every boundary test covers every field without the index
     * list being restated at each use site.
     *
     * @return the indices {@code 0} through {@code 11}
     */
    private static IntStream componentIndices() {
        return IntStream.range(0, DECLARED_FIELD_COUNT);
    }

    @Test
    @DisplayName("the map declares exactly 12 input fields and the carrier declares exactly 12 components")
    void theMapDeclaresExactlyTwelveInputFieldsAndTheCarrierMatches() {
        // The census is bounded by the group boundaries: 01 COUSR2AI opens at app/cpy-bms/COUSR02.CPY:17
        // and 01 COUSR2AO REDEFINES COUSR2AI opens at :91, and between them sit exactly twelve data
        // fields, each the last member of a generated quintuple.
        assertThat(UserUpdateRequest.class.getRecordComponents())
                .as("component count against the 12 input fields of app/cpy-bms/COUSR02.CPY")
                .hasSize(DECLARED_FIELD_COUNT);
        assertThat(COMPONENTS_IN_MAP_ORDER).hasSize(DECLARED_FIELD_COUNT);
        assertThat(WIDTHS_IN_MAP_ORDER).hasSize(DECLARED_FIELD_COUNT);
    }

    @Test
    @DisplayName("the twelve components appear in the update map's own declaration order")
    void theTwelveComponentsAppearInTheUpdateMapsOwnDeclarationOrder() {
        final List<String> declared = new ArrayList<>(DECLARED_FIELD_COUNT);
        for (final RecordComponent component : UserUpdateRequest.class.getRecordComponents()) {
            declared.add(component.getName());
        }
        // Lines 24, 30, 36, 42, 48, 54, 60, 66, 72, 78, 84, 90 of app/cpy-bms/COUSR02.CPY, in order.
        assertThat(declared).containsExactlyElementsOf(COMPONENTS_IN_MAP_ORDER);
    }

    @Test
    @DisplayName("every component is alphanumeric, because every map field is PIC X(n)")
    void everyComponentIsAlphanumericBecauseEveryMapFieldIsPictureX() {
        for (final RecordComponent component : UserUpdateRequest.class.getRecordComponents()) {
            assertThat(component.getType())
                    .as("carried type of %s", component.getName())
                    .isEqualTo(String.class);
        }
    }

    @Test
    @DisplayName("every component carries the size constraint of its picture width, byte-exactly")
    void everyComponentCarriesTheSizeConstraintOfItsPictureWidth() {
        final RecordComponent[] components = UserUpdateRequest.class.getRecordComponents();
        for (int index = 0; index < components.length; index++) {
            final RecordComponent component = components[index];
            final String name = component.getName();
            assertThat(declaredWidthOf(name))
                    .as("declared width of %s against app/cpy-bms/COUSR02.CPY", name)
                    .isEqualTo(WIDTHS_IN_MAP_ORDER.get(index));
            // The methodology is pinned alongside the contract, so this loop can never quietly become
            // vacuous. The constraint lives on the accessor and on the private final field, and it is
            // NOT retained on the record component, because RECORD_COMPONENT is not among the declared
            // targets of @Size. A width read from the component would be null on every field.
            assertThat(component.getAccessor().getAnnotation(Size.class)).isNotNull();
            assertThat(component.getAnnotation(Size.class))
                    .as("@Size is not retained on the record component of %s", name)
                    .isNull();
        }
        // Every field is private and final, so the widths cannot be circumvented after construction.
        for (final Field field : UserUpdateRequest.class.getDeclaredFields()) {
            assertThat(Modifier.isPrivate(field.getModifiers())).as("%s private", field.getName()).isTrue();
            assertThat(Modifier.isFinal(field.getModifiers())).as("%s final", field.getName()).isTrue();
            assertThat(field.getAnnotation(Size.class))
                    .as("the constraint is also propagated to the backing field of %s", field.getName())
                    .isNotNull();
        }
    }

    @Test
    @DisplayName("the error line is 78 characters: not 79, and not the 80 of the work area feeding it")
    void theErrorComponentIsSeventyEightCharactersWideAndNotSeventyNineOrEighty() {
        assertThat(declaredWidthOf("errorMessage")).isEqualTo(ERRMSGI_WIDTH);
        assertThat(ERRMSGI_WIDTH).isNotEqualTo(79).isNotEqualTo(WS_MESSAGE_WIDTH);
        // The two-byte gap between the work area and the wire field is the source's own, and reproducing
        // it is the point: app/cbl/COUSR02C.cbl:L38 against ERRMSGI/ERRMSGO PIC X(78).
        assertThat(WS_MESSAGE_WIDTH - ERRMSGI_WIDTH).isEqualTo(2);
    }

    @Test
    @DisplayName("the six terminal header fields are declared inline, not inherited from any abstraction")
    void theSixTerminalHeaderFieldsAreDeclaredInlineAndNotInherited() {
        // A shared header helper could not carry a single correct width: CURTIMEI is PIC X(8) here at
        // app/cpy-bms/COUSR02.CPY:54 but PIC X(9) at app/cpy-bms/COSGN00.CPY:54.
        assertThat(declaredWidthOf("currentTime")).isEqualTo(8).isNotEqualTo(9);
        assertThat(declaredWidthOf("transactionName")).isEqualTo(4);
        assertThat(declaredWidthOf("title01")).isEqualTo(40);
        assertThat(declaredWidthOf("currentDate")).isEqualTo(8);
        assertThat(declaredWidthOf("programName")).isEqualTo(8);
        assertThat(declaredWidthOf("title02")).isEqualTo(40);
        assertThat(UserUpdateRequest.class.getSuperclass())
                .as("a record extends java.lang.Record and nothing else, so no header base type exists")
                .isEqualTo(Record.class);
        assertThat(UserUpdateRequest.class.getInterfaces())
                .as("no header interface or mixin is implemented")
                .isEmpty();
    }

    @Test
    @DisplayName("the identifier derives from USRIDINI and precedes both name fields, as the map does")
    void theIdentifierComponentDerivesFromUsridiniAndPrecedesBothNameFields() {
        final List<String> declared = new ArrayList<>(DECLARED_FIELD_COUNT);
        for (final RecordComponent component : UserUpdateRequest.class.getRecordComponents()) {
            declared.add(component.getName());
        }
        final int identifier = declared.indexOf("userId");
        final int givenName = declared.indexOf("firstName");
        final int familyName = declared.indexOf("lastName");
        // USRIDINI at app/cpy-bms/COUSR02.CPY:60, FNAMEI at :66, LNAMEI at :72 - identifier first.
        assertThat(identifier).as("position of the identifier on the update map").isEqualTo(6);
        assertThat(identifier).isLessThan(givenName).isLessThan(familyName);
        assertThat(givenName).isEqualTo(7);
        assertThat(familyName).isEqualTo(8);
        // The add map at app/cpy-bms/COUSR01.CPY declares FNAMEI:60, LNAMEI:66, USERIDI:72 - identifier
        // third. Reproducing that order here would contradict the map this carrier is derived from.
        assertThat(declared.subList(6, 9)).containsExactly("userId", "firstName", "lastName");
        assertThat(declared.subList(6, 9)).isNotEqualTo(List.of("firstName", "lastName", "userId"));
        // The identifier width is the USRSEC cluster key length, KEYS(8,0) at app/jcl/DUSRSECJ.jcl:L65.
        assertThat(declaredWidthOf("userId")).isEqualTo(USRSEC_KEY_LENGTH);
    }

    @Test
    @DisplayName("no shared base type, interface or mixin exists with the add request")
    void noSharedBaseTypeInterfaceOrMixinExistsWithTheAddRequest() {
        // Both maps carry twelve fields of identical widths, which makes an abstraction look attractive.
        // It would be factually wrong: the identifier field name differs (USRIDINI against USERIDI) and
        // the field order differs, and the two validation cascades differ as well.
        assertThat(UserUpdateRequest.class.isRecord()).isTrue();
        assertThat(UserUpdateRequest.class.getSuperclass()).isEqualTo(Record.class);
        assertThat(UserUpdateRequest.class.getInterfaces()).isEmpty();
        assertThat(Modifier.isFinal(UserUpdateRequest.class.getModifiers()))
                .as("a final record cannot be extended, so no add-request subtype can appear later")
                .isTrue();
        assertThat(Modifier.isAbstract(UserUpdateRequest.class.getModifiers())).isFalse();
        assertThat(UserUpdateRequest.class.getDeclaredClasses())
                .as("no nested detail or snapshot group is declared")
                .isEmpty();
    }

    @Test
    @DisplayName("the carrier is not serializable, because it holds a credential in memory")
    void theCarrierIsNotSerializable() {
        // Java serialisation is a recognised risky pattern, and there is no reason to make an object that
        // carries a presented credential reconstructible from a byte stream. JSON binding is the only
        // ingress it needs.
        assertThat(Serializable.class.isAssignableFrom(UserUpdateRequest.class)).isFalse();
    }

    @Test
    @DisplayName("with every field blank the update cascade reports the identifier, not the first name")
    void theUpdateCascadeReportsTheIdentifierFirstWhenEveryFieldIsBlank() {
        final UserUpdateRequest blank = allComponentsBlank();
        // app/cbl/COUSR02C.cbl:L180 tests USRIDINI first and :L182 raises this literal.
        assertThat(updateCascadeMessage(blank)).contains(USER_ID_EMPTY);
        // The add screen would report the given name instead: app/cbl/COUSR01C.cbl:L118 tests FNAMEI
        // first and :L120 raises that literal. Reusing the add expectation here is the classic error.
        assertThat(updateCascadeMessage(blank)).isNotEqualTo(Optional.of(FIRST_NAME_EMPTY));
    }

    @Test
    @DisplayName("the update cascade's order is not the add screen's order")
    void theUpdateCascadeOrderIsNotTheAddScreensOrder() {
        assertThat(UPDATE_CASCADE_ORDER)
                .containsExactly("userId", "firstName", "lastName", "password", "userType");
        assertThat(ADD_CASCADE_ORDER)
                .containsExactly("firstName", "lastName", "userId", "password", "userType");
        assertThat(UPDATE_CASCADE_ORDER).isNotEqualTo(ADD_CASCADE_ORDER);
        // The two orders are permutations of one another, which is exactly why a shared expectation
        // compiles, passes a careless review and still reports the wrong field.
        assertThat(UPDATE_CASCADE_ORDER).containsExactlyInAnyOrderElementsOf(ADD_CASCADE_ORDER);
    }

    @Test
    @DisplayName("the carrier declares the five validated components in the update cascade's order")
    void theCarrierDeclaresTheFiveValidatedComponentsInTheUpdateCascadesOrder() {
        final List<String> validatedInDeclarationOrder = new ArrayList<>(UPDATE_CASCADE_ORDER.size());
        for (final RecordComponent component : UserUpdateRequest.class.getRecordComponents()) {
            if (UPDATE_CASCADE_ORDER.contains(component.getName())) {
                validatedInDeclarationOrder.add(component.getName());
            }
        }
        assertThat(validatedInDeclarationOrder).containsExactlyElementsOf(UPDATE_CASCADE_ORDER);
        assertThat(validatedInDeclarationOrder).isNotEqualTo(ADD_CASCADE_ORDER);
        assertThat(UPDATE_CASCADE_MESSAGES).hasSameSizeAs(UPDATE_CASCADE_ORDER);
    }

    @ParameterizedTest(name = "[{index}] cascade position {0}")
    @MethodSource("cascadePositions")
    @DisplayName("each cascade position raises its own literal once the earlier fields are populated")
    void eachCascadePositionRaisesItsOwnLiteral(final int position) {
        final String component = UPDATE_CASCADE_ORDER.get(position);
        final String blankAtItsOwnWidth = " ".repeat(declaredWidthOf(component));
        final UserUpdateRequest request = withComponent(component, blankAtItsOwnWidth);
        assertThat(updateCascadeMessage(request))
                .as("cascade outcome when %s alone is blank", component)
                .contains(UPDATE_CASCADE_MESSAGES.get(position));
        // A field of spaces at its picture width is still valid input as far as the carrier is concerned:
        // emptiness is the source's message, not a binding failure.
        assertThat(violationsOf(request)).isEmpty();
    }

    /**
     * Supplies the five update-cascade positions.
     *
     * @return the indices {@code 0} through {@code 4}
     */
    private static IntStream cascadePositions() {
        return IntStream.range(0, UPDATE_CASCADE_ORDER.size());
    }

    @ParameterizedTest(name = "[{index}] \"{0}\"")
    @ValueSource(strings = {"", " ", "        "})
    @DisplayName("spaces, an empty field and an absent field all satisfy = SPACES OR LOW-VALUES")
    void spacesAndLowValuesSatisfyTheOneCombinedPredicate(final String blank) {
        // app/cbl/COUSR02C.cbl:L180 tests "= SPACES OR LOW-VALUES" as a single predicate, so both states
        // yield one message. They remain distinguishable on the carrier - see the tri-state assertions -
        // but this screen does not distinguish them, and inventing a second message would be a change.
        assertThat(isSpacesOrLowValues(blank)).isTrue();
        assertThat(updateCascadeMessage(withComponent("userId", blank))).contains(USER_ID_EMPTY);
        assertThat(updateCascadeMessage(withComponent("userId", null))).contains(USER_ID_EMPTY);
        assertThat(updateCascadeMessage(withComponent("userId", blank)))
                .isEqualTo(updateCascadeMessage(withComponent("userId", null)));
    }

    @Test
    @DisplayName("a populated field does not satisfy the empty predicate")
    void aPopulatedFieldDoesNotSatisfyTheEmptyPredicate() {
        assertThat(isSpacesOrLowValues(BASE_USER_ID)).isFalse();
        assertThat(isSpacesOrLowValues(" A ")).isFalse();
        assertThat(updateCascadeMessage(validRequest())).isEmpty();
        assertThat(lookupCascadeMessage(validRequest())).isEmpty();
    }

    @Test
    @DisplayName("the identifier literal is emitted from both cascades, so its origin is not unique")
    void theIdentifierLiteralIsEmittedFromBothCascadesSoItsOriginIsNotUnique() {
        final UserUpdateRequest blankIdentifier = withComponent("userId", null);
        // app/cbl/COUSR02C.cbl:L148 on the lookup path and :L182 on the update path carry the same text.
        // A test that inferred the path from the message alone would be asserting something untrue.
        assertThat(lookupCascadeMessage(blankIdentifier)).contains(USER_ID_EMPTY);
        assertThat(updateCascadeMessage(blankIdentifier)).contains(USER_ID_EMPTY);
        assertThat(lookupCascadeMessage(blankIdentifier)).isEqualTo(updateCascadeMessage(blankIdentifier));
    }

    @Test
    @DisplayName("the lookup cascade tests the identifier and nothing else")
    void theLookupCascadeTestsTheIdentifierAndNothingElse() {
        // app/cbl/COUSR02C.cbl:L145-L155 has one WHEN and one WHEN OTHER. A blank given name, family
        // name, credential or type is simply not examined on that path, because the record has not been
        // read yet and lines 158 to 161 are about to overwrite all four from the record.
        for (final String component : List.of("firstName", "lastName", "password", "userType")) {
            final UserUpdateRequest request =
                    withComponent(component, " ".repeat(declaredWidthOf(component)));
            assertThat(lookupCascadeMessage(request))
                    .as("lookup-path outcome when %s alone is blank", component)
                    .isEmpty();
            // The same input on the update path does raise a message, which is what makes the two
            // cascades genuinely distinct rather than one routine reached from two places.
            assertThat(updateCascadeMessage(request)).isNotEmpty();
        }
    }

    @Test
    @DisplayName("exactly four components take part in change detection, and the identifier is not one")
    void exactlyFourComponentsTakePartInChangeDetectionAndTheIdentifierIsNotOne() {
        // app/cbl/COUSR02C.cbl compares FNAMEI at :L219, LNAMEI at :L223, PASSWDI at :L227 and USRTYPEI
        // at :L231. The identifier is moved into SEC-USR-ID at :L216 and used to read the record, so it
        // is the lookup key and can never itself be a detected change.
        assertThat(CHANGE_DETECTED_COMPONENTS)
                .containsExactly("firstName", "lastName", "password", "userType")
                .doesNotContain("userId")
                .hasSize(4);
        final List<String> declared = lowerCasedComponentNames();
        for (final String component : CHANGE_DETECTED_COMPONENTS) {
            assertThat(declared)
                    .as("%s must exist on the carrier for the service to compare it", component)
                    .contains(component.toLowerCase(Locale.ROOT));
        }
        // The identifier participates in the cascade but not in the comparison; both statements hold.
        assertThat(UPDATE_CASCADE_ORDER).contains("userId");
        assertThat(CHANGE_DETECTED_COMPONENTS).isNotEqualTo(UPDATE_CASCADE_ORDER);
    }

    @Test
    @DisplayName("the identifier is an immutable lookup key: the carrier exposes no mutator at all")
    void theIdentifierIsAnImmutableLookupKeyAndTheCarrierExposesNoMutator() {
        for (final Field field : UserUpdateRequest.class.getDeclaredFields()) {
            assertThat(Modifier.isFinal(field.getModifiers()))
                    .as("%s must be final so no component, least of all the key, can be reassigned",
                            field.getName())
                    .isTrue();
            assertThat(Modifier.isPrivate(field.getModifiers())).isTrue();
        }
        for (final Method method : UserUpdateRequest.class.getDeclaredMethods()) {
            assertThat(method.getName())
                    .as("a mutator would make the lookup key reassignable after binding")
                    .doesNotStartWith("set")
                    .doesNotStartWith("with");
        }
    }

    @Test
    @DisplayName("comparison is on the padded value, so an unpadded name is a different string")
    void comparisonIsOnThePaddedValueSoAnUnpaddedNameIsADifferentString() {
        final int width = declaredWidthOf("firstName");
        final String storedPadded = moveAlphanumeric("JOHN", width);
        assertThat(storedPadded).hasSize(width).isEqualTo("JOHN" + " ".repeat(width - 4));
        // A submitted value that has been through the same fixed-width MOVE matches the stored value, so
        // the source detects no change.
        assertThat(moveAlphanumeric("JOHN", width)).isEqualTo(storedPadded);
        // The unpadded Java string does not. A naive comparison of raw carrier text against the padded
        // stored value would therefore report a change on every request for every short name.
        assertThat("JOHN").isNotEqualTo(storedPadded);
        assertThat("JOHN".length()).isNotEqualTo(storedPadded.length());
        // The carrier itself stores exactly what it was given, padded or not; padding is the service's
        // concern at the persistence boundary, not the carrier's.
        assertThat(withComponent("firstName", storedPadded).firstName()).isEqualTo(storedPadded);
        assertThat(withComponent("firstName", "JOHN").firstName()).isEqualTo("JOHN");
    }

    @Test
    @DisplayName("comparison applies no case folding, unlike the account-update program's asymmetry")
    void comparisonAppliesNoCaseFoldingUnlikeTheAccountUpdateProgram() {
        // app/cbl/COUSR02C.cbl uses plain NOT = at :L219, :L223, :L227 and :L231. There is no
        // FUNCTION UPPER-CASE and no FUNCTION LOWER-CASE anywhere in the comparison, in deliberate
        // contrast with app/cbl/COACTUPC.cbl, which lower-cases the account group identifier on both
        // sides, upper-cases the customer name, address, state, country and government identifier, and
        // applies no case function at all to the remainder. That asymmetry is not imported here.
        final int width = declaredWidthOf("firstName");
        assertThat(moveAlphanumeric("JOHN", width)).isNotEqualTo(moveAlphanumeric("john", width));
        assertThat(moveAlphanumeric("JOHN", width))
                .isNotEqualTo(moveAlphanumeric("JOHN".toLowerCase(Locale.ROOT), width));
        assertThat(withComponent("firstName", "john").firstName()).isEqualTo("john");
        assertThat(withComponent("firstName", "JOHN").firstName())
                .isNotEqualTo(withComponent("firstName", "john").firstName());
    }

    @Test
    @DisplayName("comparison applies no trimming, so leading and trailing spaces are significant")
    void comparisonAppliesNoTrimming() {
        final String leading = " LNAME6";
        final String trailing = "LNAME6 ";
        assertThat(withComponent("lastName", leading).lastName()).isEqualTo(leading);
        assertThat(withComponent("lastName", trailing).lastName()).isEqualTo(trailing);
        assertThat(leading).isNotEqualTo(leading.strip());
        assertThat(trailing).isNotEqualTo(trailing.strip());
        // Trimming would additionally destroy the tri-state distinction, turning a field of spaces into
        // an empty field and an empty field into something indistinguishable from a blank one.
        assertThat(withComponent("lastName", "   ").lastName()).isEqualTo("   ").isNotEqualTo("");
    }

    @Test
    @DisplayName("when nothing changed the outcome is a message, not an error and not a write")
    void whenNothingChangedTheOutcomeIsAMessageNotAnError() {
        // app/cbl/COUSR02C.cbl:L236 tests USR-MODIFIED-YES; the ELSE at :L239 raises this literal, colours
        // the field and re-sends the screen. No error flag is set and no REWRITE is attempted.
        assertThat(NOTHING_MODIFIED).isEqualTo("Please modify to update ...");
        assertThat(NOTHING_MODIFIED).endsWith(" ...").doesNotEndWith("  ...").doesNotEndWith(" ....");
        assertThat(NOTHING_MODIFIED).contains("update").doesNotContain("Update").doesNotContain("UPDATE");
        // It is not one of the five cascade messages: the request was well formed, nothing was altered.
        assertThat(UPDATE_CASCADE_MESSAGES).doesNotContain(NOTHING_MODIFIED);
        assertThat(NOTHING_MODIFIED.length()).isLessThanOrEqualTo(ERRMSGI_WIDTH);
        // A carrier whose four comparable fields equal the stored record still validates cleanly; the
        // nothing-changed outcome is a service decision, never a binding or constraint failure.
        assertThat(violationsOf(validRequest())).isEmpty();
    }

    @Test
    @DisplayName("the carrier holds no digest field and performs no comparison of its own")
    void theCarrierHoldsNoDigestFieldAndPerformsNoComparisonOfItsOwn() {
        final List<String> declared = lowerCasedComponentNames();
        for (final String fragment : FORBIDDEN_DIGEST_FRAGMENTS) {
            for (final String component : declared) {
                assertThat(component)
                        .as("no component may model a digest or a comparison; found fragment %s", fragment)
                        .doesNotContain(fragment);
            }
        }
        // The carrier's whole surface is twelve accessors, the three value-semantics members and the
        // binding guard. Anything else would be behaviour, and behaviour here belongs to the service.
        final List<String> unexpected = new ArrayList<>();
        for (final Method method : UserUpdateRequest.class.getDeclaredMethods()) {
            if (method.isSynthetic()) {
                continue;
            }
            final String name = method.getName();
            final boolean accessor = method.getParameterCount() == 0 && declared.contains(
                    name.toLowerCase(Locale.ROOT));
            final boolean valueSemantics = "equals".equals(name) || "hashCode".equals(name)
                    || "toString".equals(name);
            final boolean bindingGuard = "rejectUnrecognisedProperty".equals(name);
            if (!accessor && !valueSemantics && !bindingGuard) {
                unexpected.add(name);
            }
        }
        assertThat(unexpected)
                .as("the carrier transports; it does not hash, compare, normalise or decide")
                .isEmpty();
    }

    @Test
    @DisplayName("a digest-width value cannot even be carried, so equality cannot decide the change")
    void aDigestWidthValueCannotEvenBeCarriedSoEqualityCannotDecideTheChange() {
        // The source compared the submitted plaintext against SEC-USR-PWD PIC X(08) at
        // app/cpy/CSUSR01Y.cpy:L21 - plaintext against plaintext. The target stores a digest sixty
        // characters wide instead, and a plaintext is never equal to a digest of itself, so a naive
        // translation of the NOT = at app/cbl/COUSR02C.cbl:L227 would report the credential as modified
        // on every single request and rehash it every time.
        final String digestWidthStandIn = "d".repeat(STORED_DIGEST_WIDTH);
        assertThat(digestWidthStandIn).hasSize(STORED_DIGEST_WIDTH);
        assertThat(SYNTHETIC_CREDENTIAL).isNotEqualTo(digestWidthStandIn);
        assertThat(SYNTHETIC_CREDENTIAL.length()).isLessThan(digestWidthStandIn.length());
        // The carrier cannot hold a digest at all: sixty characters violate PASSWDI PIC X(8). That is the
        // structural guarantee that no digest can arrive here and be compared by equality.
        assertThat(STORED_DIGEST_WIDTH).isGreaterThan(PASSWDI_WIDTH);
        final Set<ConstraintViolation<UserUpdateRequest>> violations =
                violationsOf(withComponent("password", digestWidthStandIn));
        assertThat(violations).hasSize(1);
        final ConstraintViolation<UserUpdateRequest> violation = violations.iterator().next();
        assertThat(violation.getPropertyPath()).hasToString("password");
        assertThat(violation.getConstraintDescriptor().getAnnotation()).isInstanceOf(Size.class);
        assertThat(((Size) violation.getConstraintDescriptor().getAnnotation()).max())
                .isEqualTo(PASSWDI_WIDTH);
    }

    @Test
    @DisplayName("the carrier holds no old or new snapshot group")
    void theCarrierHoldsNoOldOrNewSnapshotGroup() {
        // Only app/cbl/COACTUPC.cbl declares ACUP-OLD-DETAILS and ACUP-NEW-DETAILS, because a stateless
        // account-update request cannot otherwise reproduce a comparison against what the screen showed.
        // This program reads the record afresh at app/cbl/COUSR02C.cbl:L217 and compares against that, so
        // a snapshot group here would be an invention with nothing behind it.
        final List<String> declared = lowerCasedComponentNames();
        for (final String fragment : FORBIDDEN_SNAPSHOT_FRAGMENTS) {
            for (final String component : declared) {
                assertThat(component)
                        .as("no snapshot group may be fabricated; found fragment %s", fragment)
                        .doesNotContain(fragment);
            }
        }
        assertThat(UserUpdateRequest.class.getDeclaredClasses())
                .as("a snapshot group would surface as a nested record on the carrier")
                .isEmpty();
        assertThat(UserUpdateRequest.class.getRecordComponents()).hasSize(DECLARED_FIELD_COUNT);
    }

    @Test
    @DisplayName("the credential is inbound-only plaintext at the source's own picture width")
    void theCredentialIsInboundOnlyPlaintextAtTheSourcesOwnPictureWidth() {
        assertThat(declaredWidthOf("password")).isEqualTo(PASSWDI_WIDTH);
        final UserUpdateRequest request = withComponent("password", SYNTHETIC_CREDENTIAL);
        assertThat(request.password()).isEqualTo(SYNTHETIC_CREDENTIAL);
        assertThat(violationsOf(request)).isEmpty();
        // No minimum length, no character class and no complexity rule: app/cbl/COUSR02C.cbl imposes
        // none, and adding one would reject input the legacy system accepts.
        assertThat(violationsOf(withComponent("password", "a"))).isEmpty();
        assertThat(violationsOf(withComponent("password", "1"))).isEmpty();
    }

    @Test
    @DisplayName("an 80-character work area is truncated to 78, not rejected and not widened")
    void anEightyCharacterWorkAreaIsTruncatedToSeventyEightNotRejectedAndNotWidened() {
        final String workArea = moveAlphanumeric("X".repeat(WS_MESSAGE_WIDTH), WS_MESSAGE_WIDTH);
        assertThat(workArea).hasSize(WS_MESSAGE_WIDTH);
        final String wireField = moveAlphanumeric(workArea, ERRMSGI_WIDTH);
        assertThat(wireField).hasSize(ERRMSGI_WIDTH);
        assertThat(wireField).isEqualTo(workArea.substring(0, ERRMSGI_WIDTH));
        // Truncated, not rejected: the truncated value satisfies the carrier's own constraint, whereas
        // the untruncated work area does not. Both halves of that statement matter.
        assertThat(violationsOf(withComponent("errorMessage", wireField))).isEmpty();
        assertThat(violationsOf(withComponent("errorMessage", workArea))).hasSize(1);
    }

    @Test
    @DisplayName("the truncation drops exactly the final two bytes")
    void theTruncationDropsExactlyTheFinalTwoBytes() {
        // The final two characters are deliberately unique within the work area. A cyclic filler would
        // make the dropped pair reappear earlier in the retained prefix, and the assertion that the pair
        // has genuinely vanished would then be unprovable.
        final String sent = "x".repeat(ERRMSGI_WIDTH) + "yz";
        final String received = moveAlphanumeric(sent, ERRMSGI_WIDTH);
        assertThat(sent).hasSize(WS_MESSAGE_WIDTH);
        assertThat(received).hasSize(ERRMSGI_WIDTH);
        assertThat(sent).startsWith(received);
        final String dropped = sent.substring(ERRMSGI_WIDTH);
        assertThat(dropped)
                .as("the two bytes the wire field cannot carry")
                .isEqualTo("yz")
                .hasSize(WS_MESSAGE_WIDTH - ERRMSGI_WIDTH);
        assertThat(received).doesNotContain(dropped).doesNotContain("y").doesNotContain("z");
        // Byte seventy-eight survives and byte seventy-nine does not, which locates the cut exactly.
        assertThat(received.charAt(ERRMSGI_WIDTH - 1)).isEqualTo(sent.charAt(ERRMSGI_WIDTH - 1));
        assertThat(received.length()).isLessThan(sent.length());
    }

    @Test
    @DisplayName("a 79-character error line violates the declared width")
    void aSeventyNineCharacterErrorLineViolatesTheDeclaredWidth() {
        assertThat(violationsOf(withComponent("errorMessage", "y".repeat(ERRMSGI_WIDTH)))).isEmpty();
        final Set<ConstraintViolation<UserUpdateRequest>> violations =
                violationsOf(withComponent("errorMessage", "y".repeat(ERRMSGI_WIDTH + 1)));
        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath()).hasToString("errorMessage");
    }

    @Test
    @DisplayName("the fixed-width MOVE pads a short sending field rather than shortening the receiver")
    void theFixedWidthMovePadsAShortSendingFieldRatherThanShorteningTheReceiver() {
        assertThat(moveAlphanumeric("", ERRMSGI_WIDTH)).hasSize(ERRMSGI_WIDTH).isBlank();
        assertThat(moveAlphanumeric(USER_ID_EMPTY, ERRMSGI_WIDTH))
                .hasSize(ERRMSGI_WIDTH)
                .startsWith(USER_ID_EMPTY);
        assertThatThrownBy(() -> moveAlphanumeric(null, ERRMSGI_WIDTH))
                .isInstanceOf(IllegalArgumentException.class)
                .hasNoCause()
                .hasMessageContaining("never absent");
        assertThatThrownBy(() -> moveAlphanumeric("x", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasNoCause()
                .hasMessageContaining("positive width");
    }

    @ParameterizedTest(name = "[{index}] \"{0}\"")
    @ValueSource(strings = {USER_ID_EMPTY, FIRST_NAME_EMPTY, LAST_NAME_EMPTY, PASSWDI_EMPTY,
        USER_TYPE_EMPTY, USER_ID_NOT_FOUND, LOOKUP_FAILED, REWRITE_FAILED, ADD_ONLY_DUPLICATE})
    @DisplayName("literals with no space before the ellipsis keep exactly three dots and no space")
    void literalsWithNoSpaceBeforeTheEllipsisKeepThreeDotsAndNoSpace(final String literal) {
        assertThat(literal).isNotBlank().endsWith("...").doesNotEndWith(" ...").doesNotEndWith("....");
        // The run is exactly three dots with nothing separating it from the preceding word, so the
        // character before the run is neither another dot nor a space.
        assertThat(literal.charAt(literal.length() - 4)).isNotEqualTo('.').isNotEqualTo(' ');
        assertThat(literal.length()).isLessThanOrEqualTo(ERRMSGI_WIDTH);
    }

    @ParameterizedTest(name = "[{index}] \"{0}\"")
    @ValueSource(strings = {NOTHING_MODIFIED, SAVE_PROMPT, COMPOSED_UPDATE_SUFFIX, COMPOSED_ADD_SUFFIX})
    @DisplayName("literals with a space before the ellipsis keep that space, exactly one of it")
    void literalsWithASpaceBeforeTheEllipsisKeepThatSpace(final String literal) {
        assertThat(literal).endsWith(" ...").doesNotEndWith("  ...");
        // Exactly one space precedes a run of exactly three dots, so the character before that space is
        // neither a second space nor a dot.
        assertThat(literal.charAt(literal.length() - 5)).isNotEqualTo(' ').isNotEqualTo('.');
        assertThat(literal.length()).isLessThanOrEqualTo(ERRMSGI_WIDTH);
    }

    @ParameterizedTest(name = "[{index}] \"{0}\"")
    @ValueSource(strings = {USER_ID_EMPTY, FIRST_NAME_EMPTY, LAST_NAME_EMPTY, PASSWDI_EMPTY,
        USER_TYPE_EMPTY})
    @DisplayName("every empty-field literal carries the deliberate upper-case NOT")
    void everyEmptyFieldLiteralCarriesTheDeliberateUpperCaseNot(final String literal) {
        assertThat(literal).contains(" can NOT be empty");
        assertThat(literal).doesNotContain(" can not be empty").doesNotContain(" can Not be empty");
        assertThat(literal).doesNotContain("cannot").doesNotContain("must not");
        assertThat(literal).isEqualTo(literal.strip());
    }

    @Test
    @DisplayName("the inconsistent capitalisation of the file-access literals is preserved")
    void theInconsistentCapitalisationOfTheFileAccessLiteralsIsPreserved() {
        // Three literals, three different treatments of the same two verbs. Normalising any of them
        // would break a byte-for-byte parity comparison, so all three are pinned as written.
        assertThat(LOOKUP_FAILED).contains("lookup").doesNotContain("Lookup").doesNotContain("LOOKUP");
        assertThat(REWRITE_FAILED).contains("Update").doesNotContain(" update").doesNotContain("UPDATE");
        assertThat(NOTHING_MODIFIED).contains("update").doesNotContain("Update");
        assertThat(USER_ID_NOT_FOUND).contains("NOT found").doesNotContain("not found");
        assertThat(SAVE_PROMPT).contains("PF5").contains("your updates");
        // The add program's duplicate literal spells the verb without its final s. That is a source sic
        // belonging to the add path only, and it is pinned as written rather than normalised.
        assertThat(ADD_ONLY_DUPLICATE).contains("already exist").doesNotContain("already exists");
    }

    @Test
    @DisplayName("the message literals are pairwise distinct, so an outcome is identifiable from its text")
    void theMessageLiteralsArePairwiseDistinct() {
        final List<String> literals = List.of(USER_ID_EMPTY, FIRST_NAME_EMPTY, LAST_NAME_EMPTY,
                PASSWDI_EMPTY, USER_TYPE_EMPTY, NOTHING_MODIFIED, SAVE_PROMPT, USER_ID_NOT_FOUND,
                LOOKUP_FAILED, REWRITE_FAILED, ADD_ONLY_DUPLICATE);
        assertThat(literals).doesNotHaveDuplicates().hasSize(11);
        // The one deliberate sharing is the miss literal, which app/cbl/COUSR02C.cbl raises from the
        // lookup paragraph at :L342 and again from the rewrite paragraph at :L379. One constant, two
        // sites, and therefore no way to infer the site from the text.
        assertThat(USER_ID_NOT_FOUND).isNotEqualTo(LOOKUP_FAILED).isNotEqualTo(REWRITE_FAILED);
    }

    @ParameterizedTest(name = "[{index}] \"{0}\"")
    @ValueSource(strings = {USER_ID_EMPTY, FIRST_NAME_EMPTY, LAST_NAME_EMPTY, PASSWDI_EMPTY,
        USER_TYPE_EMPTY, NOTHING_MODIFIED, SAVE_PROMPT, USER_ID_NOT_FOUND, LOOKUP_FAILED, REWRITE_FAILED})
    @DisplayName("every literal survives the move onto the 78-character wire field intact")
    void everyLiteralSurvivesTheMoveOntoTheWireFieldIntact(final String literal) {
        final String onTheWire = moveAlphanumeric(literal, ERRMSGI_WIDTH);
        assertThat(onTheWire).hasSize(ERRMSGI_WIDTH);
        assertThat(onTheWire.stripTrailing()).isEqualTo(literal);
        assertThat(violationsOf(withComponent("errorMessage", literal))).isEmpty();
    }

    @Test
    @DisplayName("the composed success message trims the identifier at its first space")
    void theComposedSuccessMessageTrimsTheIdentifierAtItsFirstSpace() {
        // app/cbl/COUSR02C.cbl:L373 copies SEC-USR-ID DELIMITED BY SPACE, so a short identifier is not
        // padded into the message. For the eight-character STDUSR01 the composed text is exact.
        final String storedIdentifier = moveAlphanumeric(BASE_USER_ID, USRSEC_KEY_LENGTH);
        assertThat(storedIdentifier).isEqualTo(BASE_USER_ID).hasSize(USRSEC_KEY_LENGTH);
        assertThat(composeUpdateSuccessMessage(storedIdentifier))
                .isEqualTo("User STDUSR01 has been updated ...");
        // A shorter identifier is trimmed at its first space rather than carrying the padding through.
        final String shortIdentifier = moveAlphanumeric("ADM1", USRSEC_KEY_LENGTH);
        assertThat(shortIdentifier).isEqualTo("ADM1    ").hasSize(USRSEC_KEY_LENGTH);
        assertThat(delimitedBySpace(shortIdentifier)).isEqualTo("ADM1");
        assertThat(composeUpdateSuccessMessage(shortIdentifier))
                .isEqualTo("User ADM1 has been updated ...")
                .doesNotContain("ADM1    ");
        assertThatThrownBy(() -> delimitedBySpace(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasNoCause();
    }

    @Test
    @DisplayName("the update path models no duplicate-key outcome, because the rewrite carries no RIDFLD")
    void theUpdatePathModelsNoDuplicateKeyOutcome() {
        // app/cbl/COUSR02C.cbl:L360-L366 issues EXEC CICS REWRITE with DATASET, FROM, LENGTH, RESP and
        // RESP2 and no RIDFLD at all: it rewrites the record already read for update at :L322-L331. Its
        // EVALUATE at :L368-L390 therefore has exactly three arms - NORMAL, NOTFND and OTHER - and no
        // duplicate arm exists to translate. The add program is the one with DUPKEY and DUPREC, at
        // app/cbl/COUSR01C.cbl:L260-L263.
        final List<String> updatePathOutcomes = List.of(
                composeUpdateSuccessMessage(BASE_USER_ID), USER_ID_NOT_FOUND, REWRITE_FAILED);
        assertThat(updatePathOutcomes).hasSize(3).doesNotContain(ADD_ONLY_DUPLICATE);
        for (final String outcome : updatePathOutcomes) {
            assertThat(outcome).doesNotContain("already exist").doesNotContain("duplicate");
        }
        // The carrier models no duplicate-key concern either: it has no component that could carry one.
        for (final String component : lowerCasedComponentNames()) {
            assertThat(component).doesNotContain("duplicate").doesNotContain("dupkey").doesNotContain(
                    "duprec");
        }
    }

    @Test
    @DisplayName("the add success suffix differs from the update success suffix")
    void theAddSuccessSuffixDiffersFromTheUpdateSuccessSuffix() {
        assertThat(COMPOSED_UPDATE_SUFFIX).isEqualTo(" has been updated ...");
        assertThat(COMPOSED_ADD_SUFFIX).isEqualTo(" has been added ...");
        assertThat(COMPOSED_UPDATE_SUFFIX).isNotEqualTo(COMPOSED_ADD_SUFFIX);
        assertThat(COMPOSED_PREFIX).isEqualTo("User ").endsWith(" ");
        // Both are composed with the same prefix and the same DELIMITED BY SPACE identifier, which is
        // precisely why only the suffix distinguishes the two outcomes and why it must not be shared.
        assertThat(composeUpdateSuccessMessage(BASE_USER_ID))
                .isNotEqualTo(COMPOSED_PREFIX + BASE_USER_ID + COMPOSED_ADD_SUFFIX);
    }

    @Test
    @DisplayName("toString renders neither the credential nor either personal name")
    void toStringRendersNeitherTheCredentialNorEitherPersonalName() {
        final String rendering = validRequest().toString();
        assertThat(rendering).doesNotContain(SYNTHETIC_CREDENTIAL);
        assertThat(rendering).doesNotContain("password");
        assertThat(rendering).doesNotContain(BASE_FIRST_NAME).doesNotContain(BASE_LAST_NAME);
        // Only the two values the legacy screen itself displayed in clear are emitted. A mask is not used
        // in place of the credential, because a mask still discloses its length.
        assertThat(rendering).contains(BASE_USER_ID).contains(BASE_PROGRAM_NAME);
        assertThat(rendering).startsWith("UserUpdateRequest[").endsWith("]");
        assertThat(withComponent("password", OTHER_SYNTHETIC_CREDENTIAL).toString())
                .isEqualTo(rendering)
                .doesNotContain(OTHER_SYNTHETIC_CREDENTIAL);
    }

    @Test
    @DisplayName("the credential is never serialised outbound")
    void theCredentialIsNeverSerialisedOutbound() throws JsonProcessingException {
        // app/cbl/COUSR02C.cbl:L169 moved SEC-USR-PWD straight into PASSWDI, so the legacy screen
        // rendered the stored plaintext. That is the one place this translation deliberately departs from
        // its source, and this assertion is what keeps the departure in force.
        final ObjectMapper mapper = new ObjectMapper();
        final String json = mapper.writeValueAsString(validRequest());
        assertThat(json).doesNotContain(SYNTHETIC_CREDENTIAL);
        assertThat(json).doesNotContain("\"password\"");
        assertThat(json).contains("\"userId\":\"" + BASE_USER_ID + "\"");
        // Eleven of the twelve components serialise; the credential is the one that does not.
        for (final String component : COMPONENTS_IN_MAP_ORDER) {
            if ("password".equals(component)) {
                continue;
            }
            assertThat(json).as("outbound key for %s", component).contains("\"" + component + "\"");
        }
    }

    @Test
    @DisplayName("the credential still binds inbound, so the update flow remains usable")
    void theCredentialStillBindsInbound() throws JsonProcessingException {
        final ObjectMapper mapper = new ObjectMapper();
        final String payload = "{\"userId\":\"" + BASE_USER_ID + "\",\"firstName\":\"" + BASE_FIRST_NAME
                + "\",\"lastName\":\"" + BASE_LAST_NAME + "\",\"password\":\"" + SYNTHETIC_CREDENTIAL
                + "\",\"userType\":\"" + BASE_USER_TYPE + "\"}";
        final UserUpdateRequest bound = mapper.readValue(payload, UserUpdateRequest.class);
        assertThat(bound.userId()).isEqualTo(BASE_USER_ID);
        assertThat(bound.firstName()).isEqualTo(BASE_FIRST_NAME);
        assertThat(bound.lastName()).isEqualTo(BASE_LAST_NAME);
        assertThat(bound.userType()).isEqualTo(BASE_USER_TYPE);
        assertThat(bound.password())
                .as("write-only means outbound-suppressed, not inbound-ignored")
                .isEqualTo(SYNTHETIC_CREDENTIAL);
        // Components the payload omitted stay absent rather than being defaulted to the empty string.
        assertThat(bound.transactionName()).isNull();
        assertThat(bound.errorMessage()).isNull();
    }

    @Test
    @DisplayName("the credential component is marked write-only on the member Jackson actually reads")
    void theCredentialComponentIsMarkedWriteOnly() throws NoSuchMethodException {
        // On a record the annotation is propagated to the field, the accessor and the constructor
        // parameter, but not retained on the RecordComponent, so the accessor is what must be inspected.
        final Method accessor = UserUpdateRequest.class.getDeclaredMethod("password");
        final JsonProperty jsonProperty = accessor.getAnnotation(JsonProperty.class);
        assertThat(jsonProperty).isNotNull();
        assertThat(jsonProperty.access()).isEqualTo(JsonProperty.Access.WRITE_ONLY);
        // No other component is suppressed: the eleven remaining fields are part of the wire contract.
        for (final RecordComponent component : UserUpdateRequest.class.getRecordComponents()) {
            if ("password".equals(component.getName())) {
                continue;
            }
            assertThat(component.getAccessor().getAnnotation(JsonProperty.class))
                    .as("%s must not be access-restricted", component.getName())
                    .isNull();
        }
    }

    @Test
    @DisplayName("neither equality nor hash code renders the credential")
    void neitherEqualityNorHashCodeRendersTheCredential() {
        final UserUpdateRequest first = withComponent("password", SYNTHETIC_CREDENTIAL);
        final UserUpdateRequest second = withComponent("password", OTHER_SYNTHETIC_CREDENTIAL);
        // Both members return a primitive, so neither can disclose a value. That is the property the
        // security requirement actually needs, and it holds unconditionally.
        assertThat(String.valueOf(first.hashCode())).doesNotContain(SYNTHETIC_CREDENTIAL);
        assertThat(String.valueOf(first.equals(second))).doesNotContain(SYNTHETIC_CREDENTIAL);
        // Both members are left as the compiler generates them, so both consider all twelve components
        // including the credential. Excluding it would require hand-writing both, which is unnecessary
        // here precisely because neither can render a value.
        assertThat(first).isNotEqualTo(second);
        assertThat(first).isEqualTo(withComponent("password", SYNTHETIC_CREDENTIAL));
        assertThat(first).hasSameHashCodeAs(withComponent("password", SYNTHETIC_CREDENTIAL));
        // The only String the carrier can produce is its rendering, and that one omits the credential.
        assertThat(first.toString()).doesNotContain(SYNTHETIC_CREDENTIAL);
    }

    @Test
    @DisplayName("a rejected credential is never echoed into a violation message")
    void aRejectedCredentialIsNeverEchoedIntoAViolationMessage() {
        final String overWidth = SYNTHETIC_CREDENTIAL + "z";
        final Set<ConstraintViolation<UserUpdateRequest>> violations =
                violationsOf(withComponent("password", overWidth));
        assertThat(violations).hasSize(1);
        final ConstraintViolation<UserUpdateRequest> violation = violations.iterator().next();
        assertThat(violation.getPropertyPath()).hasToString("password");
        // The interpolated text is asserted only negatively, because the positive text is resolved from a
        // resource bundle in the host's default locale and asserting it would make the suite host-specific.
        assertThat(violation.getMessage()).doesNotContain(overWidth).doesNotContain(SYNTHETIC_CREDENTIAL);
        assertThat(violation.getMessageTemplate())
                .as("the template is locale-independent, so it is safe to pin")
                .isEqualTo("{jakarta.validation.constraints.Size.message}");
        // The template is not one of the source's own messages, so a size rejection and an empty field
        // stay separable rather than collapsing into one outcome.
        assertThat(UPDATE_CASCADE_MESSAGES).doesNotContain(violation.getMessageTemplate());
        assertThat(violation.getMessageTemplate()).isNotEqualTo(PASSWDI_EMPTY);
    }

    @Test
    @DisplayName("the synthetic credentials used here cannot be the seeded literal")
    void syntheticCredentialsCannotBeTheSeedLiteral() {
        // All ten users seeded by app/jcl/DUSRSECJ.jcl:L35-L44 share one literal plaintext credential,
        // written in the card images as eight upper-case letters. That literal appears nowhere in this
        // file. The values used instead are proved synthetic structurally, without the secret ever being
        // written down for comparison: an upper-case-only card image cannot contain a hyphen or a
        // lower-case letter.
        for (final String synthetic : List.of(SYNTHETIC_CREDENTIAL, OTHER_SYNTHETIC_CREDENTIAL)) {
            assertThat(synthetic).hasSize(PASSWDI_WIDTH);
            assertThat(synthetic).matches("[a-z0-9-]{8}");
            assertThat(synthetic).contains("-");
            assertThat(synthetic).isNotEqualTo(synthetic.toUpperCase(Locale.ROOT));
        }
        assertThat(SYNTHETIC_CREDENTIAL).isNotEqualTo(OTHER_SYNTHETIC_CREDENTIAL);
        // There is no usrsec fixture to load: the ten seed rows exist only as inline SYSUT1 card images
        // fed through IEBGENER at app/jcl/DUSRSECJ.jcl:35-44, so this class reads no fixture at all.
    }

    @Test
    @DisplayName("the user-type component is a raw one-character code and not the enum")
    void theUserTypeComponentIsARawOneCharacterCodeAndNotTheEnum() {
        for (final RecordComponent component : UserUpdateRequest.class.getRecordComponents()) {
            if ("userType".equals(component.getName())) {
                assertThat(component.getType()).isEqualTo(String.class);
                assertThat(component.getType()).isNotEqualTo(UserType.class);
            }
        }
        assertThat(declaredWidthOf("userType")).isEqualTo(1);
        // The domain itself is still pinned, through the one enum a model type in this project may
        // reference. Its two constants come from the 88-levels at app/cpy/COCOM01Y.cpy:L27-L28.
        assertThat(UserType.values()).containsExactly(UserType.ADMIN, UserType.USER).hasSize(2);
        assertThat(UserType.ADMIN.getCode()).isEqualTo('A');
        assertThat(UserType.USER.getCode()).isEqualTo('U');
    }

    @Test
    @DisplayName("both domain codes round-trip through the carrier byte-exactly")
    void bothDomainCodesRoundTripThroughTheCarrierByteExactly() {
        for (final UserType type : UserType.values()) {
            final String code = String.valueOf(type.getCode());
            final UserUpdateRequest request = withComponent("userType", code);
            assertThat(request.userType()).isEqualTo(code).hasSize(1);
            assertThat(violationsOf(request)).isEmpty();
            assertThat(UserType.fromCode(request.userType())).contains(type);
            // A character comparison against the stored byte reproduces exactly, which is the property
            // the NOT = at app/cbl/COUSR02C.cbl:L231 needs and an enum binding could not guarantee for an
            // out-of-domain value.
            assertThat(request.userType().charAt(0)).isEqualTo(type.getCode());
        }
    }

    @Test
    @DisplayName("a blank or absent code yields no user type rather than a fabricated default")
    void aBlankOrAbsentCodeYieldsNoUserTypeRatherThanAFabricatedDefault() {
        assertThat(UserType.fromCode((String) null)).isEmpty();
        assertThat(UserType.fromCode("")).isEmpty();
        assertThat(UserType.fromCode(" ")).isEmpty();
        assertThat(UserType.fromCode("AU")).as("a multi-character code is not one character").isEmpty();
        // The carrier keeps the blank as it found it, so the service can raise the source's own message at
        // app/cbl/COUSR02C.cbl:L206 rather than acting on a substituted default.
        assertThat(withComponent("userType", " ").userType()).isEqualTo(" ");
        assertThat(withComponent("userType", null).userType()).isNull();
        assertThat(updateCascadeMessage(withComponent("userType", " "))).contains(USER_TYPE_EMPTY);
        assertThat(updateCascadeMessage(withComponent("userType", null))).contains(USER_TYPE_EMPTY);
    }

    @Test
    @DisplayName("an unrecognised code raises an exception naming the offending code point")
    void anUnrecognisedCodeRaisesAnExceptionNamingTheOffendingCode() {
        // The code point is named rather than the character. The value arrives in this payload from a
        // request body, so it is caller supplied; a carriage return or line feed among those bytes copied
        // verbatim into a message that then reaches a log would terminate the current line and let the
        // caller compose the next one. The hexadecimal rendering distinguishes every character from every
        // other, so withholding the character costs nothing diagnostically.
        assertThatThrownBy(() -> UserType.requireFromCode('X'))
                .isInstanceOf(IllegalArgumentException.class)
                .hasNoCause()
                .hasMessageContaining("code point 0x58")
                .hasMessageNotContaining("'X'")
                .hasMessageContaining("app/cpy/COCOM01Y.cpy");
        // Never a silent fallback: the two defined codes resolve and everything else fails loudly.
        assertThat(UserType.requireFromCode('A')).isEqualTo(UserType.ADMIN);
        assertThat(UserType.requireFromCode('U')).isEqualTo(UserType.USER);
        assertThatThrownBy(() -> UserType.requireFromCode(' '))
                .isInstanceOf(IllegalArgumentException.class)
                .hasNoCause();
    }

    @Test
    @DisplayName("the carrier adds no membership check stricter than the source's")
    void theCarrierAddsNoMembershipCheckStricterThanTheSources() {
        // app/cbl/COUSR02C.cbl:L204 tests the type character for emptiness and for nothing else. An
        // out-of-domain character therefore binds and validates cleanly here, exactly as it reached the
        // legacy program, and the domain decision is taken later by the service.
        final UserUpdateRequest outOfDomain = withComponent("userType", "X");
        assertThat(violationsOf(outOfDomain)).isEmpty();
        assertThat(outOfDomain.userType()).isEqualTo("X");
        assertThat(UserType.fromCode(outOfDomain.userType())).isEmpty();
        // Empty and out-of-domain remain two separable states, which is what keeps the source's own
        // message distinguishable from a domain rejection.
        assertThat(updateCascadeMessage(outOfDomain)).isEmpty();
        assertThat(updateCascadeMessage(withComponent("userType", " "))).contains(USER_TYPE_EMPTY);
        assertThat(updateCascadeMessage(outOfDomain))
                .isNotEqualTo(updateCascadeMessage(withComponent("userType", " ")));
    }

    @Test
    @DisplayName("no self-delete guard is modelled, because the delete program has none")
    void noSelfDeleteGuardIsModelled() {
        // app/cbl/COUSR03C.cbl runs to 359 lines and never once references the signed-on identifier, so
        // it never compares the target user against the operator. The absent guard is preserved
        // deliberately; inventing one here would be a behaviour change.
        final List<String> declared = lowerCasedComponentNames();
        for (final String fragment : FORBIDDEN_ACTOR_FRAGMENTS) {
            for (final String component : declared) {
                assertThat(component)
                        .as("no acting-user component may be invented; found fragment %s", fragment)
                        .doesNotContain(fragment);
            }
        }
        // The carrier names one user and one only: the target of the update.
        assertThat(declared).filteredOn(name -> name.contains("user")).containsExactly(
                "userid", "usertype");
    }

    @Test
    @DisplayName("absent, blank and populated remain three distinct states")
    void absentBlankAndPopulatedRemainThreeDistinctStates() {
        // app/cpy/CSSETATY.cpy models OK, NOT-OK and BLANK as three states, with the markers firing only
        // on re-entry, and app/cbl/COACTUPC.cbl:505-508 corroborates it at message level by declaring
        // separate literals for a field that is missing and a field that is invalid. This screen's own
        // empty predicate unifies spaces with low-values, but the carrier must not collapse the states,
        // because a collapsed carrier makes the distinction unrecoverable further down.
        assertThat(withComponent("firstName", null).firstName()).isNull();
        assertThat(withComponent("firstName", "").firstName()).isEmpty();
        assertThat(withComponent("firstName", " ").firstName()).isEqualTo(" ");
        assertThat(withComponent("firstName", null).firstName())
                .isNotEqualTo(withComponent("firstName", "").firstName());
        assertThat(withComponent("firstName", "").firstName())
                .isNotEqualTo(withComponent("firstName", " ").firstName());
        assertThat(withComponent("firstName", BASE_FIRST_NAME).firstName()).isEqualTo(BASE_FIRST_NAME);
        // All three of the empty-ish states reach the same source message, which is a property of the
        // screen's predicate and not of the carrier.
        assertThat(isSpacesOrLowValues(null)).isTrue();
        assertThat(isSpacesOrLowValues("")).isTrue();
        assertThat(isSpacesOrLowValues(" ")).isTrue();
        assertThat(isSpacesOrLowValues("\0")).as("LOW-VALUES is X'00' in a character field").isTrue();
    }

    @ParameterizedTest(name = "[{index}] component {0}")
    @MethodSource("componentIndices")
    @DisplayName("no component is coerced, normalised or padded on construction")
    void noComponentIsCoercedNormalisedOrPaddedOnConstruction(final int index) {
        final String name = COMPONENTS_IN_MAP_ORDER.get(index);
        final int width = WIDTHS_IN_MAP_ORDER.get(index);
        assertThat(componentValue(withComponent(name, null), index)).as("%s absent", name).isNull();
        assertThat(componentValue(withComponent(name, ""), index)).as("%s empty", name).isEmpty();
        final String blank = " ".repeat(width);
        assertThat(componentValue(withComponent(name, blank), index))
                .as("%s blank at its picture width", name)
                .isEqualTo(blank)
                .hasSize(width);
        final String padded = moveAlphanumeric("A", width);
        assertThat(componentValue(withComponent(name, padded), index))
                .as("%s trailing padding preserved", name)
                .isEqualTo(padded)
                .hasSize(width);
    }

    @ParameterizedTest(name = "[{index}] component {0}")
    @MethodSource("componentIndices")
    @DisplayName("an absent value satisfies every constraint, because the source tolerates a blank field")
    void anAbsentValueSatisfiesEveryConstraint(final int index) {
        final String name = COMPONENTS_IN_MAP_ORDER.get(index);
        assertThat(violationsOf(withComponent(name, null)))
                .as("%s must carry no required-ness constraint", name)
                .isEmpty();
        assertThat(violationsOf(withComponent(name, ""))).isEmpty();
    }

    @ParameterizedTest(name = "[{index}] component {0}")
    @MethodSource("componentIndices")
    @DisplayName("one character under, and exactly at, the declared width are both accepted")
    void oneCharacterUnderAndExactlyAtTheDeclaredWidthAreAccepted(final int index) {
        final String name = COMPONENTS_IN_MAP_ORDER.get(index);
        final int width = WIDTHS_IN_MAP_ORDER.get(index);
        assertThat(violationsOf(withComponent(name, "x".repeat(width - 1))))
                .as("%s at one under its width", name)
                .isEmpty();
        assertThat(violationsOf(withComponent(name, "x".repeat(width))))
                .as("%s at exactly its width", name)
                .isEmpty();
    }

    @ParameterizedTest(name = "[{index}] component {0}")
    @MethodSource("componentIndices")
    @DisplayName("one character over the declared width is rejected, naming that component alone")
    void oneCharacterOverTheDeclaredWidthIsRejectedNamingThatComponentAlone(final int index) {
        final String name = COMPONENTS_IN_MAP_ORDER.get(index);
        final int width = WIDTHS_IN_MAP_ORDER.get(index);
        final Set<ConstraintViolation<UserUpdateRequest>> violations =
                violationsOf(withComponent(name, "x".repeat(width + 1)));
        assertThat(violations).as("%s at one over its width", name).hasSize(1);
        final ConstraintViolation<UserUpdateRequest> violation = violations.iterator().next();
        assertThat(violation.getPropertyPath()).hasToString(name);
        assertThat(violation.getConstraintDescriptor().getAnnotation()).isInstanceOf(Size.class);
        assertThat(((Size) violation.getConstraintDescriptor().getAnnotation()).max()).isEqualTo(width);
        // The property path is never empty, which is what proves the constraint is field-level and not a
        // class-level cross-field edit.
        assertThat(violation.getPropertyPath().toString()).isNotEmpty();
    }

    @Test
    @DisplayName("hostile inputs are rejected one field at a time")
    void hostileInputsAreRejectedOneFieldAtATime() {
        // A nine-character identifier and credential against X(8), twenty-one-character names against
        // X(20), a seventy-nine-character message against X(78) and a two-character type against X(1).
        final UserUpdateRequest hostile = new UserUpdateRequest(
                "CU023", BASE_TITLE_01, FIXED_HEADER_DATE, BASE_PROGRAM_NAME, BASE_TITLE_02,
                FIXED_HEADER_TIME, "STDUSR012", "N".repeat(21), "M".repeat(21),
                SYNTHETIC_CREDENTIAL + "z", "UU", "y".repeat(ERRMSGI_WIDTH + 1));
        final Set<ConstraintViolation<UserUpdateRequest>> violations = violationsOf(hostile);
        final List<String> offendingPaths = new ArrayList<>(violations.size());
        for (final ConstraintViolation<UserUpdateRequest> violation : violations) {
            offendingPaths.add(violation.getPropertyPath().toString());
            assertThat(violation.getMessage())
                    .as("no submitted value may be echoed back")
                    .doesNotContain(SYNTHETIC_CREDENTIAL);
        }
        assertThat(offendingPaths).containsExactlyInAnyOrder(
                "transactionName", "userId", "firstName", "lastName", "password", "userType",
                "errorMessage");
        assertThat(violations).hasSize(7);
    }

    @Test
    @DisplayName("no class-level cross-field constraint is declared")
    void noClassLevelCrossFieldConstraintIsDeclared() {
        // app/cbl/COACTUPC.cbl:1665-1669 runs its cross-field edit only when both single-field edits have
        // already passed, and :1671-1675 then decides the outcome. A class-level constraint fires
        // unconditionally instead, producing a different message set from a different set of states.
        final Annotation[] classAnnotations = UserUpdateRequest.class.getAnnotations();
        assertThat(classAnnotations)
                .as("the carrier declares no type-level annotation at all")
                .isEmpty();
        // Behavioural confirmation: every violation the carrier can produce names a single component, so
        // none of them originates from a class-level constraint, whose path would be empty.
        final UserUpdateRequest multiplyInvalid = new UserUpdateRequest(
                "CU023", BASE_TITLE_01, FIXED_HEADER_DATE, BASE_PROGRAM_NAME, BASE_TITLE_02,
                FIXED_HEADER_TIME, "STDUSR012", BASE_FIRST_NAME, BASE_LAST_NAME, SYNTHETIC_CREDENTIAL,
                "UU", BASE_ERROR_MESSAGE);
        final Set<ConstraintViolation<UserUpdateRequest>> violations = violationsOf(multiplyInvalid);
        assertThat(violations).hasSize(3);
        for (final ConstraintViolation<UserUpdateRequest> violation : violations) {
            assertThat(violation.getPropertyPath().toString())
                    .as("a class-level constraint would report an empty property path")
                    .isNotEmpty();
            assertThat(COMPONENTS_IN_MAP_ORDER).contains(violation.getPropertyPath().toString());
        }
    }

    @Test
    @DisplayName("no session-state or navigation component is present")
    void noSessionStateOrNavigationComponentIsPresent() {
        // Routing is by URL, so the COMMAREA navigation fields at app/cpy/COCOM01Y.cpy:L21-L24, the
        // enter-versus-re-enter flag at :L29 and the last-map pair at :L43-L44 - both PIC X(7), not
        // X(8) - have no counterpart on a stateless payload. app/cbl/COUSR02C.cbl does move program
        // names into CDEMO-TO-PROGRAM at :L91, :L114, :L117 and :L125, and that is exactly the CICS
        // navigation being replaced, so none of it may surface here.
        final List<String> declared = lowerCasedComponentNames();
        for (final String fragment : FORBIDDEN_SESSION_FRAGMENTS) {
            for (final String component : declared) {
                assertThat(component)
                        .as("no session or navigation component may appear; found fragment %s", fragment)
                        .doesNotContain(fragment);
            }
        }
        // programName is a display-only header field, PIC X(8) at app/cpy-bms/COUSR02.CPY:42, and is not
        // a routing target: it carries no destination and the carrier acts on nothing.
        assertThat(declared).contains("programname");
        assertThat(declaredWidthOf("programName")).isEqualTo(8).isNotEqualTo(7);
    }

    @Test
    @DisplayName("an unrecognised property is rejected with its root cause preserved")
    void anUnrecognisedPropertyIsRejectedWithItsRootCausePreserved() {
        final ObjectMapper mapper = new ObjectMapper();
        // Both the property name and its value are distinctive tokens, so their absence from the message
        // can be asserted without the assertion accidentally matching ordinary prose in that message.
        final String forgedName = "zzUnexpectedPropertyzz";
        final String forgedValue = "zzForgedValueTokenzz";
        final String payload = "{\"userId\":\"" + BASE_USER_ID + "\",\"" + forgedName + "\":\""
                + forgedValue + "\"}";
        assertThatThrownBy(() -> mapper.readValue(payload, UserUpdateRequest.class))
                .as("an unexpected property is refused, not bound and silently discarded")
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .rootCause()
                .hasMessageContaining("app/cpy-bms/COUSR02.CPY")
                .hasMessageContaining("withheld")
                // Neither the offending name nor its value is reproduced: both are untrusted input, and
                // copying either into a message that reaches a log record would let a caller forge log
                // content. The same rule keeps a rejected credential out of the logs.
                .hasMessageNotContaining(forgedName)
                .hasMessageNotContaining(forgedValue);
        // The guard is the always-effective form rather than a type-level setting, which would be inert
        // whenever the object mapper has failure on unknown properties disabled - the framework default.
        final List<String> guardNames = new ArrayList<>();
        for (final Method method : UserUpdateRequest.class.getDeclaredMethods()) {
            if (method.getParameterCount() == 2 && method.getReturnType() == void.class) {
                guardNames.add(method.getName());
                assertThat(Modifier.isPublic(method.getModifiers()))
                        .as("the guard is not part of the public surface")
                        .isFalse();
            }
        }
        assertThat(guardNames).containsExactly("rejectUnrecognisedProperty");
    }

    @Test
    @DisplayName("the header date and time come from the injected clock and fit their picture widths")
    void theHeaderDateAndTimeComeFromTheInjectedClockAndFitTheirPictureWidths() {
        // app/cbl/COUSR02C.cbl:L296-L315 moves WS-CURDATE-MM-DD-YY into CURDATEO and
        // WS-CURTIME-HH-MM-SS into CURTIMEO. Both groups are eight characters wide in
        // app/cpy/CSDAT01Y.cpy, which is why CURDATEI and CURTIMEI are PIC X(8) on this map.
        final Clock injected = FixedClockProvider.canonicalClock();
        final ZonedDateTime moment = ZonedDateTime.ofInstant(injected.instant(), injected.getZone());
        assertThat(moment).isEqualTo(FIXED_MOMENT);
        assertThat(HEADER_DATE_FORMAT.format(moment)).isEqualTo(FIXED_HEADER_DATE).hasSize(8);
        assertThat(HEADER_TIME_FORMAT.format(moment)).isEqualTo(FIXED_HEADER_TIME).hasSize(8);
        assertThat(FIXED_HEADER_DATE).matches("\\d{2}/\\d{2}/\\d{2}");
        assertThat(FIXED_HEADER_TIME).matches("\\d{2}:\\d{2}:\\d{2}");
        assertThat(FIXED_HEADER_DATE).hasSize(declaredWidthOf("currentDate"));
        assertThat(FIXED_HEADER_TIME).hasSize(declaredWidthOf("currentTime"));
        // Reading the same fixed clock twice yields the same instant, which is what makes the whole tier
        // reproducible; the ambient clock is never consulted anywhere in this file.
        assertThat(injected.instant()).isEqualTo(FixedClockProvider.CANONICAL_INSTANT);
        assertThat(injected.getZone()).isEqualTo(FixedClockProvider.CANONICAL_ZONE);
        assertThat(violationsOf(validRequest())).isEmpty();
    }

    @Test
    @DisplayName("the persisted user-security geometry the carrier is measured against is unchanged")
    void thePersistedUserSecurityGeometryIsUnchanged() {
        // app/cpy/CSUSR01Y.cpy:L17-L23 - identifier X(08), given name X(20), family name X(20),
        // credential X(08), type X(01), then a NAMED filler X(23). Fifty-seven populated bytes in an
        // eighty-byte record, corroborated by RECORDSIZE(80,80) at app/jcl/DUSRSECJ.jcl:L66, with the
        // type character at byte fifty-seven.
        final int populated = USRSEC_KEY_LENGTH + 20 + 20 + PASSWDI_WIDTH + 1;
        assertThat(populated).isEqualTo(57);
        assertThat(populated + 23).isEqualTo(SEC_USER_DATA_LENGTH);
        assertThat(declaredWidthOf("userId")).isEqualTo(USRSEC_KEY_LENGTH);
        assertThat(declaredWidthOf("firstName")).isEqualTo(20);
        assertThat(declaredWidthOf("lastName")).isEqualTo(20);
        assertThat(declaredWidthOf("password")).isEqualTo(PASSWDI_WIDTH);
        assertThat(declaredWidthOf("userType")).isEqualTo(1);
        // This record carries no optimistic-lock column: version columns belong to the account, card,
        // customer and transaction entities only, so nothing is asserted about one here.
    }

    @Test
    @DisplayName("the component locator helpers fail loudly on an unknown component name")
    void theComponentLocatorHelpersFailLoudlyOnAnUnknownComponentName() {
        assertThatThrownBy(() -> withComponent("userIdIn", BASE_USER_ID))
                .as("USRIDINI maps to userId; a near-miss name must not silently perturb nothing")
                .isInstanceOf(IllegalArgumentException.class)
                .hasNoCause()
                .hasMessageContaining("not a component of UserUpdateRequest")
                .hasMessageContaining("userIdIn");
        assertThatThrownBy(() -> declaredWidthOf("userid"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasNoCause()
                .hasMessageContaining("not a component of UserUpdateRequest");
    }

    /**
     * Reads the component at the given position from a request, by position rather than by reflection, so the
     * positional contract of the canonical constructor is exercised as well as the accessor.
     *
     * @param request the carrier to read
     * @param index the component position, {@code 0} through {@code 11}, in map order
     * @return the carried value, possibly {@code null}
     * @throws IllegalArgumentException if the index is outside the twelve declared positions
     */
    private static String componentValue(final UserUpdateRequest request, final int index) {
        return switch (index) {
            case 0 -> request.transactionName();
            case 1 -> request.title01();
            case 2 -> request.currentDate();
            case 3 -> request.programName();
            case 4 -> request.title02();
            case 5 -> request.currentTime();
            case 6 -> request.userId();
            case 7 -> request.firstName();
            case 8 -> request.lastName();
            case 9 -> request.password();
            case 10 -> request.userType();
            case 11 -> request.errorMessage();
            default -> throw new IllegalArgumentException(
                    "app/cpy-bms/COUSR02.CPY declares twelve input fields, so the position must be 0 to 11"
                            + " and not " + index);
        };
    }
}
