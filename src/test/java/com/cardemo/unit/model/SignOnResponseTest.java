/*
 * ******************************************************************
 * Program     : SignOnResponseTest.java
 * Component   : Unit test tier - model and DTO suite, resident at
 *               src/test/java/com/cardemo/unit/model
 * Application : CardDemo
 * Type        : JUnit 5 unit test - pure JVM, no container, no Spring
 *               context, no database
 * Function    : Proves that com.cardemo.model.dto.SignOnResponse
 *               carries exactly the two COMMAREA identity fields plus
 *               the issued token, that not one of the fourteen
 *               remaining COMMAREA data items reappears as server side
 *               session state, that the token leaves the type through
 *               no diagnostic rendering, that no value on it is
 *               derived from the wall clock, and that absent, blank,
 *               low-values and present remain four distinguishable
 *               states.
 * Source      : app/cpy/COCOM01Y.cpy:19-44 identity fields
 *               (no BMS map exists for this response - see the class
 *               documentation for the "Not available" disclosure)
 *               + app/cbl/COSGN00C.cbl:98-101,118,123,132-136,
 *                 145-157,223-240
 *               + app/cpy-bms/COSGN00.CPY:17,24-84,85,92-152
 *               + app/cpy/CSSETATY.cpy:18-27
 *               + app/cbl/COBIL00C.cbl:249-267
 *               + app/cbl/CBACT04C.cbl:613-626
 *               + app/cbl/CBTRN02C.cbl:692-706
 *               + app/cbl/COTRN02C.cbl:464-465
 *               + app/jcl/DUSRSECJ.jcl:35-44 @ 7756d89
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

import com.cardemo.model.dto.SignOnResponse;
import com.cardemo.model.enums.UserType;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.Size;
import java.io.Serializable;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Unit suite for {@link SignOnResponse}, the token bearing reply that replaces the COMMAREA
 * handshake of the legacy sign-on program.
 *
 * <h2>1. What this suite does, and the evidence it asserts against</h2>
 *
 * <p>It holds {@link SignOnResponse} to four contracts, each of which has a named failure that this
 * file exists to catch:</p>
 *
 * <ul>
 *   <li><strong>The identity split.</strong> Exactly two COMMAREA data items survive into the REST
 *       surface - {@code CDEMO-USER-ID PIC X(08)} at {@code app/cpy/COCOM01Y.cpy:25} and
 *       {@code CDEMO-USER-TYPE PIC X(01)} at {@code :26}, whose two condition names
 *       {@code 88 CDEMO-USRTYP-ADMIN VALUE 'A'} at {@code :27} and
 *       {@code 88 CDEMO-USRTYP-USER VALUE 'U'} at {@code :28} fix the only two admissible codes.
 *       Everything else in the 160 byte area is dropped, and section 2 asserts each omission
 *       individually rather than in aggregate.</li>
 *   <li><strong>Token containment.</strong> {@code SignOnResponse.toString()} must never render the
 *       token. Section 3 asserts that, and asserts it for every substring of the fixture rather than
 *       for the whole value, because a truncated or masked prefix would disclose just as much.</li>
 *   <li><strong>Determinism.</strong> Nothing on this type is derived from the ambient clock.
 *       Section 4 asserts that no temporal component exists at all, and proves the renderings are
 *       clock invariant by evaluating them under two different fixed clocks obtained from
 *       {@link FixedClockProvider}.</li>
 *   <li><strong>The tri-state model.</strong> {@code app/cpy/CSSETATY.cpy:18-27} models OK, NOT-OK
 *       and BLANK as three separate states, and {@code app/cbl/COSGN00C.cbl:118} and {@code :123}
 *       test {@code SPACES OR LOW-VALUES} as two separate sentinels. Section 5 asserts that absent,
 *       blank, low-values and present stay four distinguishable states and that nothing is
 *       coerced.</li>
 * </ul>
 *
 * <h2>2. The BMS field contract for this type is "Not available"</h2>
 *
 * <p><strong>Not available.</strong> There is no symbolic map for a sign-on <em>response</em>, so no
 * field contract for this type can be quoted, and none is invented here. The evidence is positive
 * rather than an absence of evidence:</p>
 *
 * <ul>
 *   <li>{@code app/cpy-bms/COSGN00.CPY} declares its input group {@code 01 COSGN0AI} at line 17 with
 *       eleven data items running from line 24 to line 84. Those eleven are the field contract of
 *       {@code com.cardemo.model.dto.SignOnRequest} and are deliberately <strong>not</strong>
 *       borrowed here; test 1.2 asserts that none of their names appears on this type and test 1.3
 *       asserts that none of their widths does either.</li>
 *   <li>The same copybook declares {@code 01 COSGN0AO REDEFINES COSGN0AI} at line 85 with eleven
 *       {@code ...O} items from line 92 to line 152. That group is the sign-on <em>screen</em> sent
 *       back to the same terminal, not a response payload: it is transmitted only by
 *       {@code SEND-SIGNON-SCREEN} at {@code app/cbl/COSGN00C.cbl:145-157}, which is performed on
 *       first display at {@code :83} and on the failure paths at {@code :94}, {@code :122},
 *       {@code :127}, {@code :245} and {@code :251}.</li>
 *   <li>On the <strong>success</strong> path at {@code app/cbl/COSGN00C.cbl:223-240} no map is sent
 *       at all: the program moves its results into the COMMAREA and issues
 *       {@code EXEC CICS XCTL}. There was never a success payload to lay out, which is exactly why
 *       no map for one exists.</li>
 * </ul>
 *
 * <p><strong>What would be needed</strong> to source this type from a map is a mapset describing a
 * successful sign-on reply. No such mapset exists anywhere in the corpus, so the shape is instead
 * derived from {@code app/cpy/COCOM01Y.cpy} plus the requirement that the response carry the issued
 * token in place of the COMMAREA. Fabricating BMS names or widths for it would be a <strong>High
 * severity</strong> defect: it would assert a contract the source does not contain.</p>
 *
 * <h2>3. How to build, run and test</h2>
 *
 * <p>{@code mvn -B clean test} compiles this tree and runs the unit tier;
 * {@code mvn -B test -Dtest=SignOnResponseTest} runs this class alone. Surefire 3.5.4 selects
 * {@code **}{@code /*Test.java} and {@code **}{@code /*Tests.java} while excluding
 * {@code **}{@code /integration/**} and {@code **}{@code /e2e/**}, so <strong>this class is
 * collected by Surefire and not by Failsafe</strong>. That is why the path
 * {@code src/test/java/com/cardemo/unit/model/SignOnResponseTest.java} and the {@code Test} suffix
 * are both load bearing: a class moved out of this tree, or renamed, is collected by neither plugin
 * and silently never runs - a green build in which JaCoCo simply records the type as uncovered, with
 * no error and no warning to notice. Where a local toolchain is unavailable the pinned image
 * reproduces the build exactly:
 * {@code docker run --rm -v "$PWD":/w -w /w maven:3.9.11-eclipse-temurin-25 ./mvnw -q test}.</p>
 *
 * <h2>4. Key configuration and defaults</h2>
 *
 * <p>There is none to set: this is a pure JVM tier. No container is started, no Spring context is
 * refreshed, no database is reached and no environment variable is read, so no privilege is
 * required to run it.</p>
 *
 * <ul>
 *   <li><strong>Time comes from {@link FixedClockProvider} and from nowhere else.</strong>
 *       {@code Instant.now()}, {@code LocalDate.now()}, {@code System.currentTimeMillis()}, the
 *       default zone and the default locale are never consulted. Every formatting and case
 *       operation states {@link Locale#ROOT} explicitly.</li>
 *   <li><strong>No test double is created.</strong> {@link SignOnResponse} has no collaborator to
 *       stub, so Mockito is not used here at all and its strictness setting is irrelevant to this
 *       class; the fixtures are plain immutable values.</li>
 *   <li><strong>The JWT signing key is resolved from an environment variable</strong> by
 *       {@code com.cardemo.security.JwtTokenProvider} and is therefore <strong>absent from this
 *       file entirely</strong>. The token is treated as an opaque string: nothing here mints,
 *       parses, signs or verifies one, no JWT library is imported, and no key material, no
 *       {@code HS256}-style algorithm constant and no committed default appears. A signing key
 *       literal under {@code src/} is a <strong>Blocker</strong>.</li>
 *   <li><strong>No fixture is a real credential.</strong> The token fixtures are synthetic and
 *       obviously so. The ten seeded users of {@code app/jcl/DUSRSECJ.jcl:35-44} share one
 *       plaintext password in bytes 49 to 56 of the 80 byte record, with the type character at byte
 *       57; that literal never appears anywhere in this file, and test 3.8 proves the fixtures
 *       cannot collide with it by asserting a shape mismatch instead of writing it.</li>
 * </ul>
 *
 * <h2>5. Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><em>The build fails on something that looks trivial.</em> Compilation runs with
 *       {@code -Xlint:all}, {@code -Werror} and {@code failOnWarning}, and that reaches test
 *       compilation, so one unused import, one raw type or one deprecation is a build failure rather
 *       than a warning. Reproduce with {@code mvn -B -q test-compile}.</li>
 *   <li><em>Test 3.1 or 3.2 fails.</em> The token has leaked into a diagnostic rendering -
 *       {@code SignOnResponse.toString()} has been removed, weakened, or bypassed by a custom
 *       serialiser. Restore the override; the log masking in {@code logback-spring.xml} is a second
 *       line of defence, not the primary one. <strong>Blocker.</strong></li>
 *   <li><em>A test in section 2 fails.</em> A COMMAREA field has crept back onto the type, which
 *       reintroduces server side session state that the stateless REST surface must not carry.
 *       <strong>High.</strong></li>
 *   <li><em>A temporal assertion is flaky.</em> Something on the path under test is reading the
 *       ambient clock; failures cluster on second, day and month boundaries. Take a
 *       {@link Clock} as a collaborator and obtain it from {@link FixedClockProvider}.
 *       <strong>High.</strong></li>
 *   <li><em>A fixture stream is null at run time.</em> The daily transaction fixture is
 *       {@code dailytran.txt}, spelled in full. The mainframe data definition name and dataset are
 *       {@code DALYTRAN}, so {@code dalytran.txt} is the natural guess and is wrong: it compiles
 *       cleanly and fails only when the resource is opened. This suite reads no fixture file, which
 *       is why it cannot hit that trap.</li>
 *   <li><em>A reflective lookup fails.</em> A record component was renamed. The helpers report that
 *       as an {@code AssertionError} whose cause is the original {@code NoSuchFieldException}, so
 *       the root cause survives; test 6.8 asserts exactly that.</li>
 * </ul>
 *
 * <h2>6. Findings this suite records, by severity</h2>
 *
 * <ul>
 *   <li><strong>Medium.</strong> The corpus carries 441 input fields across its seventeen symbolic
 *       maps, not the 460 quoted in the plan, whose own table sums to 440. The census counts
 *       <em>request</em> fields; a response with no map contributes none of them. Test 1.5.</li>
 *   <li><strong>Low.</strong> The plan marks seven COMMAREA data items as having no Java
 *       counterpart - {@code CDEMO-FROM-TRANID}, {@code CDEMO-FROM-PROGRAM},
 *       {@code CDEMO-TO-TRANID}, {@code CDEMO-TO-PROGRAM}, {@code CDEMO-PGM-CONTEXT},
 *       {@code CDEMO-LAST-MAP} and {@code CDEMO-LAST-MAPSET} - while the accompanying prose says
 *       eight. Rather than adopt either figure, tests 2.2 and 2.3 assert the absence of all
 *       <strong>fourteen</strong> non-identity data items the copybook declares, which is stronger
 *       than both counts and cannot drift with the arithmetic.</li>
 *   <li><strong>Low.</strong> The COMMAREA declares no page number and no next-page flag, so the
 *       pagination row of the plan's COMMAREA split table has no counterpart in
 *       {@code app/cpy/COCOM01Y.cpy}; those fields live in the browsing programs' working storage.
 *       Nothing is asserted about them here beyond their absence from this type.</li>
 *   <li><strong>Low.</strong> {@link SignOnResponse} is a record, so its {@code equals} and
 *       {@code hashCode} are value based over all three components and therefore include the token.
 *       They cannot be made to exclude it without hand writing both, which the type deliberately
 *       does not do. Test 6.5 records that this discloses nothing: producing an equal instance
 *       requires already holding the token, and a hash code renders as digits.</li>
 *   <li><strong>Low.</strong> {@code CDEMO-CARD-NUM} at {@code app/cpy/COCOM01Y.cpy:41} is
 *       {@code PIC 9(16)}, numeric, while the card entity's {@code CARD-NUM} at
 *       {@code app/cpy/CVACT02Y.cpy:5} is {@code PIC X(16)}, alphanumeric. Neither shape is
 *       propagated here, because a card number has no place in a sign-on reply at all. Test
 *       2.8.</li>
 * </ul>
 *
 * <h2>7. Thread safety, side effects and shared state</h2>
 *
 * <p>Every fixture is an immutable {@code static final} value or is produced by a pure factory, so
 * there is no mutable static state that one test could leave behind for another and no ordering
 * dependency between tests. No method here performs I/O, writes a log line or mutates its argument.
 * No test is disabled, no failure is ignored and no exception is swallowed.</p>
 */
@DisplayName("SignOnResponse - COMMAREA identity split, token containment and the tri-state model")
final class SignOnResponseTest {

    // -----------------------------------------------------------------------------------------
    // Widths, codes and sizes transcribed from app/cpy/COCOM01Y.cpy. Restated as literals on
    // purpose: a test that compared SignOnResponse's constants against themselves would be a
    // tautology, so the expected values are transcribed from the copybook and compared against the
    // production constants. Changing a production width therefore fails a test rather than moving
    // the goalposts.
    // -----------------------------------------------------------------------------------------

    /** {@code CDEMO-USER-ID PIC X(08)} at {@code app/cpy/COCOM01Y.cpy:25}. */
    private static final int USER_ID_WIDTH = 8;

    /** {@code CDEMO-USER-TYPE PIC X(01)} at {@code app/cpy/COCOM01Y.cpy:26}. */
    private static final int USER_TYPE_WIDTH = 1;

    /** {@code 88 CDEMO-USRTYP-ADMIN VALUE 'A'} at {@code app/cpy/COCOM01Y.cpy:27}. */
    private static final String ADMIN_CODE = "A";

    /** {@code 88 CDEMO-USRTYP-USER VALUE 'U'} at {@code app/cpy/COCOM01Y.cpy:28}. */
    private static final String USER_CODE = "U";

    /**
     * Byte length of {@code 01 CARDDEMO-COMMAREA}, {@code app/cpy/COCOM01Y.cpy:19-44}.
     *
     * <p>The sum of the declared pictures: {@code 4 + 8 + 4 + 8 + 8 + 1 + 1} for the general
     * information group, {@code 9 + 25 + 25 + 25} for the customer group, {@code 11 + 1} for the
     * account group, {@code 16} for the card group and {@code 7 + 7} for the trailing group. The
     * figure is quoted so that the count of fields this type drops can be tied to a whole area
     * rather than to a list someone might trim.
     */
    private static final int COMMAREA_LENGTH = 160;

    /** Data items {@code app/cpy/COCOM01Y.cpy} declares, excluding group and condition-name lines. */
    private static final int COMMAREA_DATA_ITEMS = 16;

    /**
     * Input fields the seventeen symbolic maps of {@code app/cpy-bms/**} declare between them.
     *
     * <p>441, not the 460 the plan quotes; the plan's own table sums to 440. Classified
     * <strong>Medium</strong>: the discrepancy is a census of <em>request</em> fields and cannot
     * reach a response with no map, but a figure that disagrees with its own table must be recorded
     * rather than repeated.
     */
    private static final int CORPUS_INPUT_FIELDS = 441;

    /** Input fields {@code 01 COSGN0AI} declares, {@code app/cpy-bms/COSGN00.CPY:24-84}. */
    private static final int SIGN_ON_MAP_INPUT_FIELDS = 11;

    /**
     * A second fixed instant, distinct from {@link FixedClockProvider#CANONICAL_INSTANT}.
     *
     * <p>Its only purpose is to give test 4.2 two different clocks to render under. Stated as a
     * literal instant rather than derived by adding to the canonical one, so that the two cannot
     * accidentally coincide if the canonical value is ever revised.
     */
    private static final Instant ALTERNATE_INSTANT = Instant.parse("2023-11-27T08:15:42Z");

    /**
     * The "present" state used wherever a test needs a value that is neither absent, empty, blank nor
     * low-values.
     *
     * <p>One character, so that it is a legitimate value for the one byte type code as well as for the
     * eight byte identifier and the unbounded token. It is deliberately not one of the two condition
     * name codes: a state test must not double as a domain test.
     */
    private static final String PRESENT_VALUE = "X";

    // -----------------------------------------------------------------------------------------
    // Deterministic fixtures. Every one is an immutable value; none is a real credential.
    // -----------------------------------------------------------------------------------------

    /**
     * The synthetic token this suite transports.
     *
     * <p>Obviously fake by construction, and deliberately <em>not</em> shaped like a JSON Web Token:
     * it carries no dot separated segments, so it cannot be mistaken for, or accidentally parsed as,
     * a real credential. Nothing here mints or verifies a token - the type under test treats it as
     * an opaque string - so a well formed token would buy no coverage and would only put a
     * credential shaped literal under {@code src/}.
     */
    private static final String SYNTHETIC_TOKEN = "SYNTHETIC-UNIT-TEST-TOKEN-NOT-A-CREDENTIAL";

    /**
     * A second synthetic token, needed wherever a test must distinguish two responses that differ
     * only in the token.
     */
    private static final String OTHER_SYNTHETIC_TOKEN = "ANOTHER-SYNTHETIC-UNIT-TEST-TOKEN-VALUE";

    /**
     * A synthetic eight character user identifier, filling {@code CDEMO-USER-ID PIC X(08)} exactly.
     *
     * <p>Chosen so that it is none of the ten identifiers seeded by
     * {@code app/jcl/DUSRSECJ.jcl:35-44} - which are {@code ADMIN001} to {@code ADMIN005} and
     * {@code USER0001} to {@code USER0005} - and so that it shares no four character run with either
     * token fixture, which is what lets test 3.1 assert substring containment meaningfully.
     */
    private static final String SYNTHETIC_USER_ID = "TSTUSR01";

    /**
     * Shape of the plaintext password shared by the ten seeded users, expressed as a pattern so that
     * the literal itself never appears under {@code src/}.
     *
     * <p>{@code app/jcl/DUSRSECJ.jcl:35-44} carries it inline in bytes 49 to 56 of each 80 byte
     * record, with the type character at byte 57: eight upper case letters and nothing else. Writing
     * that literal into a test would be a <strong>Blocker</strong> under the no-secrets-in-tests
     * standard, so the fixtures are proved distinct from it by shape instead.
     */
    private static final String SEEDED_PLAINTEXT_SHAPE = "^[A-Z]{8}$";

    /** Shape of an AWS access key identifier; no fixture may look like one. */
    private static final String CLOUD_ACCESS_KEY_SHAPE = "^(?:AKIA|ASIA)[0-9A-Z]{16}$";

    /** Shape of a BCrypt hash; hashing belongs to the security tier, not to this one. */
    private static final String BCRYPT_HASH_SHAPE = "^\\$2[aby]\\$\\d{2}\\$.{53}$";

    /** Shape of a three segment JSON Web Token; no fixture may look like one. */
    private static final String JWT_SHAPE = "^[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+$";

    /** COBOL {@code LOW-VALUES} for a single byte field: {@code X'00'}, not a space and not absent. */
    private static final String LOW_VALUES = "\u0000";

    /** The blank state: a value that is present but carries only spaces. */
    private static final String BLANK = " ";

    /** The shortest present value: the empty string, which is present but zero length. */
    private static final String EMPTY = "";

    /**
     * The three record component names {@link SignOnResponse} declares, in declaration order.
     *
     * <p>Transcribed rather than derived, so that a reordering or a rename is a test failure instead
     * of something the suite silently follows.
     */
    private static final List<String> EXPECTED_COMPONENTS = List.of("token", "userId", "userType");

    /**
     * The eleven data item names of {@code 01 COSGN0AI}, {@code app/cpy-bms/COSGN00.CPY:24-84}.
     *
     * <p>Held only so that test 1.2 can assert that none of them was borrowed into this type. The
     * eleven are the field contract of the sign-on <em>request</em>.
     */
    private static final List<String> SIGN_ON_MAP_ITEMS = List.of(
            "TRNNAMEI", "TITLE01I", "CURDATEI", "PGMNAMEI", "TITLE02I", "CURTIMEI",
            "APPLIDI", "SYSIDI", "USERIDI", "PASSWDI", "ERRMSGI");

    /**
     * The Java component names the eleven map items would have carried, had they been borrowed.
     *
     * <p>{@code userId} is deliberately excluded: {@code USERIDI} and {@code CDEMO-USER-ID} agree on
     * both name and width, so its presence here proves nothing either way. What the list does prove
     * is that no screen-only field - the transaction name, the two titles, the header date and time,
     * the application and system identifiers, the presented password or the error message - reached
     * this type.
     */
    private static final List<String> SCREEN_ONLY_COMPONENTS = List.of(
            "transactionName", "title01", "currentDate", "programName", "title02", "currentTime",
            "applicationId", "systemId", "password", "errorMessage");

    /**
     * Property and accessor names that must not exist on this type under any spelling.
     *
     * <p>Three groups, each excluded for its own reason: credential material, because a sign-on reply
     * returns no secret; personally identifiable information, because
     * {@code app/cbl/COSGN00C.cbl:223-228} never populates the customer name group on the success
     * path and returning it would both invent a result and disclose gratuitously; and token metadata,
     * because the COMMAREA declares no lifetime, no issue instant and no scope, so any such field
     * would be invented rather than translated.
     */
    private static final List<String> FORBIDDEN_MEMBERS = List.of(
            "password", "passwordHash", "pwd", "secret", "credential", "signingKey", "key",
            "salt", "algorithm", "cardNumber", "cardNum", "pan", "ssn", "socialSecurityNumber",
            "firstName", "middleName", "lastName", "dateOfBirth",
            "expiresAt", "expiresIn", "expiry", "issuedAt", "notBefore", "tokenType", "scope",
            "refreshToken", "authorization", "bearer");

    /**
     * Names a routing hint would plausibly be spelled with, asserted absent by test 2.7.
     *
     * <p>The destination that {@code app/cbl/COSGN00C.cbl:230-240} selects is already fully
     * determined by the user type, so a second field restating it would create two sources of truth
     * for one decision and would reintroduce {@code CDEMO-TO-PROGRAM} by another name.
     */
    private static final List<String> FORBIDDEN_ROUTING_HINTS = List.of(
            "landingProgram", "destination", "destinationProgram", "nextProgram", "redirect",
            "redirectUrl", "menu", "menuProgram", "target", "targetProgram");

    /**
     * Names a temporal member would plausibly be spelled with, asserted absent by test 4.1.
     */
    private static final List<String> FORBIDDEN_TEMPORAL_MEMBERS = List.of(
            "timestamp", "createdAt", "issuedTimestamp", "validUntil", "instant", "clock",
            "processingTimestamp", "originatingTimestamp");

    // -----------------------------------------------------------------------------------------
    // Contract rows and pure factories. Every provider below is a factory rather than a static
    // collection, so no test can mutate what another test sees.
    // -----------------------------------------------------------------------------------------

    /**
     * One bounded component of this type, transcribed from {@code app/cpy/COCOM01Y.cpy}.
     *
     * @param cobolItem      the data item name as the copybook spells it
     * @param sourceLine     the line of {@code app/cpy/COCOM01Y.cpy} that declares it
     * @param pictureClause  the declared PICTURE, verbatim
     * @param width          the declared width in bytes
     * @param componentName  the {@link SignOnResponse} component that carries it
     * @param widthConstant  the public width constant {@link SignOnResponse} publishes for it
     */
    private record FieldContract(
            String cobolItem,
            int sourceLine,
            String pictureClause,
            int width,
            String componentName,
            int widthConstant) {

        @Override
        public String toString() {
            return cobolItem + " " + pictureClause + " at app/cpy/COCOM01Y.cpy:" + sourceLine
                    + " -> " + componentName;
        }
    }

    /**
     * The two identity components that survive the COMMAREA to token split, in copybook order.
     *
     * @return the two bounded field contracts
     */
    private static Stream<FieldContract> boundedFieldContracts() {
        return Stream.of(
                new FieldContract("CDEMO-USER-ID", 25, "PIC X(08)", USER_ID_WIDTH,
                        "userId", SignOnResponse.USER_ID_MAX_LENGTH),
                new FieldContract("CDEMO-USER-TYPE", 26, "PIC X(01)", USER_TYPE_WIDTH,
                        "userType", SignOnResponse.USER_TYPE_LENGTH));
    }

    /**
     * One COMMAREA data item that this type deliberately does not carry.
     *
     * @param cobolItem     the data item name as {@code app/cpy/COCOM01Y.cpy} spells it
     * @param sourceLine    the line that declares it
     * @param pictureClause the declared PICTURE, verbatim
     * @param componentName the component name it would have carried, which must not exist
     * @param reason        why it has no counterpart
     */
    private record OmittedField(
            String cobolItem,
            int sourceLine,
            String pictureClause,
            String componentName,
            String reason) {

        @Override
        public String toString() {
            return cobolItem + " " + pictureClause + " at app/cpy/COCOM01Y.cpy:" + sourceLine
                    + " (" + reason + ")";
        }
    }

    /**
     * The seven routing and screen-state data items that carry no counterpart at all.
     *
     * <p>These are the items the plan marks "No equivalent". They are the ones whose reappearance
     * would be <strong>High severity</strong>, because each is server side session state and the
     * REST surface is required to hold none.
     *
     * @return the seven omitted routing and screen-state field rows, in copybook order
     */
    private static Stream<OmittedField> omittedSessionFields() {
        return Stream.of(
                new OmittedField("CDEMO-FROM-TRANID", 21, "PIC X(04)", "fromTranId",
                        "navigation is expressed by URL, so no transaction identifier travels"),
                new OmittedField("CDEMO-FROM-PROGRAM", 22, "PIC X(08)", "fromProgram",
                        "its only consumer was the program to program transfer at :231 and :236"),
                new OmittedField("CDEMO-TO-TRANID", 23, "PIC X(04)", "toTranId",
                        "never written on the success path at :224-:228 in the first place"),
                new OmittedField("CDEMO-TO-PROGRAM", 24, "PIC X(08)", "toProgram",
                        "the destination is already determined by the user type"),
                new OmittedField("CDEMO-PGM-CONTEXT", 29, "PIC 9(01)", "pgmContext",
                        "the enter versus re-enter flag has no half to select in a stateless request"),
                new OmittedField("CDEMO-LAST-MAP", 43, "PIC X(7)", "lastMap",
                        "no screen state is retained because no screen is rendered"),
                new OmittedField("CDEMO-LAST-MAPSET", 44, "PIC X(7)", "lastMapset",
                        "no screen state is retained because no screen is rendered"));
    }

    /**
     * The seven customer, account and card data items that this reply also drops.
     *
     * <p>{@code app/cbl/COSGN00C.cbl:223-228} writes none of them on the success path, so returning
     * any would invent a result the source does not produce; the three name fields and the card
     * number would in addition disclose personally identifiable information.
     *
     * @return the seven omitted customer, account and card field rows, in copybook order
     */
    private static Stream<OmittedField> omittedSubjectFields() {
        return Stream.of(
                new OmittedField("CDEMO-CUST-ID", 33, "PIC 9(09)", "custId",
                        "not written by the sign-on success path"),
                new OmittedField("CDEMO-CUST-FNAME", 34, "PIC X(25)", "custFname",
                        "personally identifiable and not a sign-on result"),
                new OmittedField("CDEMO-CUST-MNAME", 35, "PIC X(25)", "custMname",
                        "personally identifiable and not a sign-on result"),
                new OmittedField("CDEMO-CUST-LNAME", 36, "PIC X(25)", "custLname",
                        "personally identifiable and not a sign-on result"),
                new OmittedField("CDEMO-ACCT-ID", 38, "PIC 9(11)", "acctId",
                        "an account is selected after sign-on, never by it"),
                new OmittedField("CDEMO-ACCT-STATUS", 39, "PIC X(01)", "acctStatus",
                        "an account is selected after sign-on, never by it"),
                new OmittedField("CDEMO-CARD-NUM", 41, "PIC 9(16)", "cardNum",
                        "a card number has no place in a sign-on reply"));
    }

    /**
     * Every COMMAREA data item with no counterpart on this type: the fourteen of
     * {@link #omittedSessionFields()} and {@link #omittedSubjectFields()} together.
     *
     * @return the fourteen omitted field rows
     */
    private static Stream<OmittedField> allOmittedFields() {
        return Stream.concat(omittedSessionFields(), omittedSubjectFields());
    }

    /**
     * Values that must not resolve to a user type, each with the reason it must not.
     *
     * @return the out-of-domain user type codes and their reasons
     */
    private static Stream<Object[]> outOfDomainUserTypeCodes() {
        return Stream.of(
                new Object[] {null, "absent is not a code"},
                new Object[] {EMPTY, "the empty string is zero characters, not one"},
                new Object[] {BLANK, "a blank byte is the BLANK state, not a code"},
                new Object[] {LOW_VALUES, "COBOL LOW-VALUES is its own sentinel, not a code"},
                new Object[] {"a", "resolution is case sensitive: :227 moves the byte unfolded"},
                new Object[] {"u", "resolution is case sensitive: :230 compares against 'A' exactly"},
                new Object[] {"B", "a single character outside the two condition names"},
                new Object[] {"0", "a digit is not one of the two condition names"},
                new Object[] {"A ", "a blank padded code is rejected, not repaired"},
                new Object[] {" A", "a leading blank is rejected, not trimmed"},
                new Object[] {"AU", "two characters cannot fill a one byte field"});
    }

    /**
     * The record component names of this type, as a parameter source.
     *
     * @return the three component names, in declaration order
     */
    private static Stream<String> componentNames() {
        return EXPECTED_COMPONENTS.stream();
    }

    /**
     * The Java component names the screen-only data items of the symbolic map would have carried.
     *
     * @return the ten screen-only component names
     */
    private static Stream<String> screenOnlyComponentNames() {
        return SCREEN_ONLY_COMPONENTS.stream();
    }

    /**
     * The eleven COBOL data item names of {@code 01 COSGN0AI}.
     *
     * @return the eleven symbolic map item names, in copybook order
     */
    private static Stream<String> signOnMapItemNames() {
        return SIGN_ON_MAP_ITEMS.stream();
    }

    /**
     * Member names that must not exist on this type under any spelling.
     *
     * @return the forbidden credential, personal data and token metadata member names
     */
    private static Stream<String> forbiddenMemberNames() {
        return FORBIDDEN_MEMBERS.stream();
    }


    /**
     * A fully populated, entirely valid response: an eight character identifier, the administrator
     * code and the synthetic token.
     *
     * @return a valid response built only from this class's deterministic fixtures
     */
    private static SignOnResponse baseline() {
        return new SignOnResponse(SYNTHETIC_TOKEN, SYNTHETIC_USER_ID, ADMIN_CODE);
    }

    /**
     * {@link #baseline()} with exactly one component replaced, so that a violation can be attributed
     * to a single field rather than inferred from a set.
     *
     * <p>The substitution is an explicit {@code switch} rather than a reflective write. That is a
     * security choice as much as a style one: reflective invocation driven by a string is precisely
     * the pattern the project's standards ask to be flagged, and it would also defeat the compiler's
     * ability to notice a renamed component.
     *
     * @param componentName the record component to replace, spelled as {@link SignOnResponse}
     *                      declares it
     * @param value         the replacement value, which may be {@code null}
     * @return a response identical to {@link #baseline()} except for the named component
     * @throws IllegalArgumentException if {@code componentName} is not a component of
     *                                  {@link SignOnResponse}; the message names the component and
     *                                  never a value, because one component is a credential
     */
    private static SignOnResponse baselineWith(final String componentName, final String value) {
        return switch (componentName) {
            case "token" -> new SignOnResponse(value, SYNTHETIC_USER_ID, ADMIN_CODE);
            case "userId" -> new SignOnResponse(SYNTHETIC_TOKEN, value, ADMIN_CODE);
            case "userType" -> new SignOnResponse(SYNTHETIC_TOKEN, SYNTHETIC_USER_ID, value);
            default -> throw new IllegalArgumentException(
                    "not a component of SignOnResponse: " + componentName);
        };
    }

    /**
     * Reads a component's value back through its canonical accessor.
     *
     * <p>An explicit {@code switch} for the same reasons {@link #baselineWith(String, String)} uses
     * one: no reflective invocation, and a renamed component becomes a compile error rather than a
     * run time surprise.
     *
     * @param response      the response to read
     * @param componentName the record component to read
     * @return the component's value, possibly {@code null}
     * @throws IllegalArgumentException if {@code componentName} is not a component of
     *                                  {@link SignOnResponse}
     */
    private static String componentOf(final SignOnResponse response, final String componentName) {
        return switch (componentName) {
            case "token" -> response.token();
            case "userId" -> response.userId();
            case "userType" -> response.userType();
            default -> throw new IllegalArgumentException(
                    "not a component of SignOnResponse: " + componentName);
        };
    }

    /**
     * Validates a response with a freshly built validator factory.
     *
     * <p>The factory is created and closed per call rather than cached in a static field, so this
     * suite holds no mutable shared state and no test can be affected by the order it runs in. This
     * is plain Jakarta Bean Validation: no Spring context is refreshed and no container is started.
     *
     * @param response the response to validate
     * @return the constraint violations, empty when the response satisfies every declared bound
     */
    private static Set<ConstraintViolation<SignOnResponse>> violationsOf(
            final SignOnResponse response) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            final Validator validator = factory.getValidator();
            return validator.validate(response);
        }
    }

    /**
     * Reads an annotation from the <em>field</em> a record component generates.
     *
     * <p>Reading it from the {@link RecordComponent} itself does not work, and the reason is worth
     * stating: {@link Size} does not list {@code ElementType.RECORD_COMPONENT} among its targets, so
     * although it is written on the component it is not directly present on it. Java propagates it to
     * the applicable declarations instead - the private field, the accessor and the constructor
     * parameter - which is where Bean Validation reads it.
     *
     * @param componentName  the record component whose generated field should be inspected
     * @param annotationType the annotation to look for
     * @param <A>            the annotation type
     * @return the annotation, or {@code null} when the component does not carry it
     * @throws AssertionError if {@link SignOnResponse} declares no such field, which would mean the
     *                        component was renamed; the original reflective failure is preserved as
     *                        the cause rather than swallowed
     */
    private static <A extends Annotation> A fieldAnnotation(
            final String componentName, final Class<A> annotationType) {
        try {
            final Field field = SignOnResponse.class.getDeclaredField(componentName);
            return field.getAnnotation(annotationType);
        } catch (final NoSuchFieldException cause) {
            throw new AssertionError(
                    "SignOnResponse declares no field for component " + componentName, cause);
        }
    }

    /**
     * Looks up a no-argument accessor that {@link SignOnResponse} declares.
     *
     * <p>Reflective <em>lookup</em> only: nothing here invokes a method chosen by a string, because
     * reflection driven invocation is one of the risky patterns the project's standards ask to be
     * flagged. The result is inspected for its annotations and its return type, never called.
     *
     * @param accessorName the accessor to look up
     * @return the accessor
     * @throws AssertionError if no such accessor is declared, which would mean it was renamed; the
     *                        original reflective failure is preserved as the cause
     */
    private static Method accessor(final String accessorName) {
        try {
            return SignOnResponse.class.getDeclaredMethod(accessorName);
        } catch (final NoSuchMethodException cause) {
            throw new AssertionError(
                    "SignOnResponse declares no accessor named " + accessorName, cause);
        }
    }

    /**
     * The record component names of {@link SignOnResponse}, in declaration order.
     *
     * @return the declared component names
     */
    private static List<String> declaredComponentNames() {
        return Arrays.stream(SignOnResponse.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }

    /**
     * The declared component types of {@link SignOnResponse}, in declaration order.
     *
     * @return the declared component types
     */
    private static List<Class<?>> declaredComponentTypes() {
        return Arrays.stream(SignOnResponse.class.getRecordComponents())
                .map(RecordComponent::getType)
                .toList();
    }

    /**
     * Every declared field and method name of {@link SignOnResponse}, lower cased with
     * {@link Locale#ROOT} so that a comparison cannot vary with the host's default locale.
     *
     * <p>Both are inspected together because a value can escape a type through either: a field is
     * what a serialiser reflects over, and a method is what a template or an expression language
     * resolves. Constants and generated members are included deliberately - the question these
     * assertions answer is whether a name exists anywhere on the type, not whether it is public.
     *
     * @return the lower cased declared field and method names
     */
    private static List<String> declaredMemberNames() {
        return Stream.concat(
                        Arrays.stream(SignOnResponse.class.getDeclaredFields()).map(Field::getName),
                        Arrays.stream(SignOnResponse.class.getDeclaredMethods()).map(Method::getName))
                .map(name -> name.toLowerCase(Locale.ROOT))
                .toList();
    }

    /**
     * The names of the instance fields {@link SignOnResponse} declares, excluding its constants.
     *
     * @return the instance field names, which for a record are exactly its components
     */
    private static List<String> instanceFieldNames() {
        return Arrays.stream(SignOnResponse.class.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .map(Field::getName)
                .toList();
    }

    /**
     * Serialises a response to JSON with a plain, unconfigured mapper.
     *
     * <p>Unconfigured on purpose: no polymorphic default typing is activated, because a response type
     * that carried a type identifier would be a deserialisation gadget surface, and no inclusion or
     * naming strategy is set, so what these tests observe is the shape the type itself dictates
     * rather than the shape a configuration produced.
     *
     * @param response the response to serialise
     * @return the JSON rendering
     * @throws JsonProcessingException if serialisation fails, which is a genuine failure and is
     *                                 therefore allowed to propagate rather than being caught
     */
    private static String toJson(final SignOnResponse response) throws JsonProcessingException {
        return new ObjectMapper().writeValueAsString(response);
    }

    /**
     * Serialises a response and reads the result back as a property map.
     *
     * @param response the response to serialise
     * @return the serialised properties, keyed exactly as they appear on the wire
     * @throws JsonProcessingException if serialisation or parsing fails
     */
    private static Map<String, Object> serialisedProperties(final SignOnResponse response)
            throws JsonProcessingException {
        final ObjectMapper mapper = new ObjectMapper();
        return mapper.readValue(mapper.writeValueAsString(response),
                new TypeReference<Map<String, Object>>() { });
    }

    /**
     * Reads a response back from JSON with a plain, unconfigured mapper.
     *
     * @param json the JSON rendering
     * @return the deserialised response
     * @throws JsonProcessingException if the JSON cannot be bound to the type
     */
    private static SignOnResponse fromJson(final String json) throws JsonProcessingException {
        return new ObjectMapper().readValue(json, SignOnResponse.class);
    }

    /**
     * Every contiguous substring of a value at or above a minimum length, longest first.
     *
     * <p>Used by test 3.1 so that the token's absence from a rendering is asserted for every fragment
     * of it rather than only for the whole value. A masked prefix, a truncation or a first-and-last
     * four rendering would each satisfy a whole value comparison while still disclosing enough of a
     * bearer credential to matter.
     *
     * @param value     the value to fragment
     * @param minLength the shortest fragment to produce
     * @return the fragments, in descending length order
     */
    private static List<String> substringsOf(final String value, final int minLength) {
        return Stream.iterate(value.length(), length -> length >= minLength, length -> length - 1)
                .flatMap(length -> Stream.iterate(0, start -> start + length <= value.length(),
                                start -> start + 1)
                        .map(start -> value.substring(start, start + length)))
                .toList();
    }


    // =========================================================================================
    // 1. PROVENANCE - this response has no symbolic map, so nothing may be borrowed from one
    //    app/cpy-bms/COSGN00.CPY:17,24-84 is the request contract; :85,92-152 is the re-displayed
    //    screen; app/cbl/COSGN00C.cbl:223-240 sends no map on the success path at all.
    // =========================================================================================

    @Test
    @DisplayName("1.1 exposes exactly the token and the two COMMAREA identity fields, in that order")
    void exposesExactlyTheTokenAndTheTwoIdentityFields() {
        assertThat(declaredComponentNames())
                .as("the response replaces the COMMAREA with a token plus the identity that"
                        + " app/cbl/COSGN00C.cbl:226-227 establishes; a fourth component would be"
                        + " either invented surface or reinstated session state")
                .containsExactlyElementsOf(EXPECTED_COMPONENTS);
        assertThat(instanceFieldNames())
                .as("the instance fields of a record are exactly its components, so a field without a"
                        + " component would be state smuggled past the wire contract")
                .containsExactlyElementsOf(EXPECTED_COMPONENTS);
        assertThat(declaredComponentTypes())
                .as("CDEMO-USER-ID and CDEMO-USER-TYPE are both PIC X, and a token is opaque text, so"
                        + " every component is a String")
                .containsExactly(String.class, String.class, String.class);
    }

    @ParameterizedTest
    @MethodSource("screenOnlyComponentNames")
    @DisplayName("1.2 HIGH: borrows no data item from the sign-on symbolic map, which is the request"
            + " contract")
    void borrowsNoDataItemFromTheSignOnSymbolicMap(final String screenOnlyComponent) {
        assertThat(declaredComponentNames())
                .as("app/cpy-bms/COSGN00.CPY:24-84 is the field contract of SignOnRequest; borrowing"
                        + " %s into the response would fabricate a BMS contract for a type that has"
                        + " none", screenOnlyComponent)
                .doesNotContain(screenOnlyComponent);
        assertThat(declaredMemberNames())
                .as("nor may %s appear as a field or an accessor under any casing",
                        screenOnlyComponent)
                .doesNotContain(screenOnlyComponent.toLowerCase(Locale.ROOT));
    }

    @ParameterizedTest
    @MethodSource("signOnMapItemNames")
    @DisplayName("1.3 spells no member after a COBOL data item of the symbolic map")
    void spellsNoMemberAfterASymbolicMapDataItem(final String cobolItem) {
        assertThat(declaredMemberNames())
                .as("%s is a screen data item of group 01 COSGN0AI; a member named after it would"
                        + " assert a screen provenance this type does not have", cobolItem)
                .doesNotContain(cobolItem.toLowerCase(Locale.ROOT));
    }

    @Test
    @DisplayName("1.4 bounds exactly the two identity components, and at no symbolic map width")
    void boundsExactlyTheTwoIdentityComponentsAtNoScreenWidth() {
        final List<Integer> declaredBounds = declaredComponentNames().stream()
                .map(component -> fieldAnnotation(component, Size.class))
                .filter(size -> size != null)
                .map(Size::max)
                .toList();

        assertThat(declaredBounds)
                .as("only the two components with a COBOL PICTURE carry a bound: CDEMO-USER-ID"
                        + " PIC X(08) at app/cpy/COCOM01Y.cpy:25 and CDEMO-USER-TYPE PIC X(01) at :26")
                .containsExactly(USER_ID_WIDTH, USER_TYPE_WIDTH);
        assertThat(declaredBounds)
                .as("none of the distinctive symbolic map widths - PIC X(4) at"
                        + " app/cpy-bms/COSGN00.CPY:24, X(9) at :54, X(40) at :30 and :48, X(78) at"
                        + " :84 - may appear as a bound, because no screen field was borrowed")
                .doesNotContain(4, 9, 40, 78);
    }

    @Test
    @DisplayName("1.5 carries no error message field, because the output group is a re-displayed"
            + " screen rather than a reply")
    void carriesNoErrorMessageField() {
        assertThat(declaredMemberNames())
                .as("ERRMSGO PIC X(78) at app/cpy-bms/COSGN00.CPY:152 is written by"
                        + " SEND-SIGNON-SCREEN at app/cbl/COSGN00C.cbl:149 and travels with the"
                        + " screen, not with a successful reply; the success path at :223-:240 sends"
                        + " no map at all, so a failure message has nothing to ride on here")
                .doesNotContain("errormessage", "errmsg", "message");
        assertThat(declaredComponentNames())
                .as("REST reports failure with a status code and a problem body produced by the web"
                        + " layer, so a success payload carries no message component")
                .hasSize(EXPECTED_COMPONENTS.size());
    }

    @Test
    @DisplayName("1.6 MEDIUM: the corpus's 441 input fields are a request census; this response"
            + " contributes none")
    void theCorpusInputFieldCensusCoversRequestsOnly() {
        assertThat(CORPUS_INPUT_FIELDS)
                .as("441 input fields across the seventeen symbolic maps, not the 460 the plan"
                        + " quotes; the plan's own table sums to 440, and a figure that disagrees with"
                        + " its own table is recorded rather than repeated")
                .isNotEqualTo(460)
                .isNotEqualTo(440);
        assertThat(SIGN_ON_MAP_INPUT_FIELDS)
                .as("group 01 COSGN0AI declares eleven data items, app/cpy-bms/COSGN00.CPY:24-84")
                .isEqualTo(SIGN_ON_MAP_ITEMS.size());
        assertThat(declaredComponentNames())
                .as("a census of input fields cannot reach a response with no map: this type"
                        + " contributes none of the 441 and takes none of COSGN00's eleven")
                .hasSizeLessThan(SIGN_ON_MAP_INPUT_FIELDS);
    }

    // =========================================================================================
    // 2. THE COMMAREA IDENTITY SPLIT - app/cpy/COCOM01Y.cpy:19-44 is 160 bytes and sixteen data
    //    items; exactly two survive. app/cbl/COSGN00C.cbl:98-101 returned the whole area to CICS,
    //    :224-:228 populated it and :230-:240 branched on one byte of it.
    // =========================================================================================

    @Test
    @DisplayName("2.1 carries the identifier and the type verbatim, transporting rather than"
            + " transforming")
    void carriesTheIdentifierAndTheTypeVerbatim() {
        final SignOnResponse response = baseline();

        assertThat(response.userId())
                .as("app/cbl/COSGN00C.cbl:226 moves WS-USER-ID into CDEMO-USER-ID unchanged, so the"
                        + " value this type reports must be the value it was given")
                .isEqualTo(SYNTHETIC_USER_ID)
                .hasSize(USER_ID_WIDTH);
        assertThat(response.userType())
                .as("app/cbl/COSGN00C.cbl:227 moves SEC-USR-TYPE into CDEMO-USER-TYPE unfolded")
                .isEqualTo(ADMIN_CODE)
                .hasSize(USER_TYPE_WIDTH);
        assertThat(response.token())
                .as("the token is transported opaquely: nothing here parses, signs or verifies it")
                .isEqualTo(SYNTHETIC_TOKEN);
    }

    @ParameterizedTest
    @MethodSource("omittedSessionFields")
    @DisplayName("2.2 HIGH: reinstates no routing or screen-state field, so no server side session"
            + " state survives")
    void reinstatesNoRoutingOrScreenStateField(final OmittedField omitted) {
        assertThat(declaredComponentNames())
                .as("%s has no counterpart: %s", omitted.cobolItem(), omitted.reason())
                .doesNotContain(omitted.componentName());
        assertThat(declaredMemberNames())
                .as("nor may %s reappear as a field or an accessor under any casing",
                        omitted.cobolItem())
                .doesNotContain(omitted.componentName().toLowerCase(Locale.ROOT));
    }

    @ParameterizedTest
    @MethodSource("omittedSubjectFields")
    @DisplayName("2.3 HIGH: returns no customer, account or card field, none of which sign-on"
            + " produces")
    void returnsNoCustomerAccountOrCardField(final OmittedField omitted) {
        assertThat(declaredComponentNames())
                .as("%s has no counterpart: %s", omitted.cobolItem(), omitted.reason())
                .doesNotContain(omitted.componentName());
        assertThat(declaredMemberNames())
                .as("app/cbl/COSGN00C.cbl:224-228 writes only the two FROM fields, the user"
                        + " identifier, the user type and the program context, so %s holds whatever"
                        + " the caller passed in and is not a sign-on result at all",
                        omitted.cobolItem())
                .doesNotContain(omitted.componentName().toLowerCase(Locale.ROOT));
    }

    @Test
    @DisplayName("2.4 drops fourteen of the sixteen COMMAREA data items, keeping only the identity"
            + " pair")
    void dropsFourteenOfTheSixteenCommareaDataItems() {
        assertThat(allOmittedFields().count() + 2)
                .as("app/cpy/COCOM01Y.cpy:19-44 declares sixteen data items across five groups;"
                        + " fourteen are dropped and CDEMO-USER-ID with CDEMO-USER-TYPE remain. The"
                        + " plan marks seven items 'No equivalent' while its prose says eight, so"
                        + " this asserts all fourteen rather than adopting either count")
                .isEqualTo(COMMAREA_DATA_ITEMS);
        assertThat(COMMAREA_LENGTH)
                .as("the dropped items are most of a 160 byte area: 4 + 8 + 4 + 8 + 8 + 1 + 1 for the"
                        + " general information group, 9 + 25 + 25 + 25 for the customer group,"
                        + " 11 + 1 for the account group, 16 for the card group and 7 + 7 for the"
                        + " trailing group, the last two being PIC X(7) at :43-:44 and not X(8)")
                .isEqualTo(4 + 8 + 4 + 8 + 8 + 1 + 1
                        + 9 + 25 + 25 + 25
                        + 11 + 1
                        + 16
                        + 7 + 7);
        assertThat(allOmittedFields().map(OmittedField::componentName).toList())
                .as("the fourteen omissions must be distinct fields, not the same one counted twice")
                .doesNotHaveDuplicates()
                .hasSize(COMMAREA_DATA_ITEMS - 2);
    }

    @ParameterizedTest
    @MethodSource("boundedFieldContracts")
    @DisplayName("2.5 publishes and enforces each identity width byte exactly, and no tighter")
    void publishesAndEnforcesEachIdentityWidthByteExactly(final FieldContract contract) {
        assertThat(contract.widthConstant())
                .as("the constant SignOnResponse publishes for %s must equal the %s width of %s",
                        contract.componentName(), contract.pictureClause(), contract.cobolItem())
                .isEqualTo(contract.width());

        final Size size = fieldAnnotation(contract.componentName(), Size.class);

        assertThat(size)
                .as("%s records a declared COBOL width and must therefore carry a bound",
                        contract.componentName())
                .isNotNull();
        assertThat(size.max())
                .as("the bound on %s must be the declared width, so neither a silent widening nor a"
                        + " truncation can pass unnoticed", contract.componentName())
                .isEqualTo(contract.width());
        assertThat(size.min())
                .as("the source imposes no minimum length on %s, so neither may this type: a blank"
                        + " fixed width field is a legitimate value", contract.componentName())
                .isZero();
    }

    @Test
    @DisplayName("2.6 resolves exactly the two condition names of the copybook, and models them as"
            + " UserType")
    void resolvesExactlyTheTwoConditionNamesOfTheCopybook() {
        assertThat(UserType.values())
                .as("88 CDEMO-USRTYP-ADMIN VALUE 'A' at app/cpy/COCOM01Y.cpy:27 and"
                        + " 88 CDEMO-USRTYP-USER VALUE 'U' at :28 are exactly a two constant"
                        + " enumeration; a third constant would widen a one byte domain")
                .containsExactly(UserType.ADMIN, UserType.USER);
        assertThat(baselineWith("userType", ADMIN_CODE).resolvedUserType())
                .as("the administrator code must resolve to the administrator constant")
                .isEqualTo(UserType.ADMIN);
        assertThat(baselineWith("userType", USER_CODE).resolvedUserType())
                .as("the standard user code must resolve to the standard user constant")
                .isEqualTo(UserType.USER);
        assertThat(String.valueOf(UserType.ADMIN.getCode()))
                .as("the constant's own code must be the copybook's literal, so the label and the"
                        + " byte cannot drift apart")
                .isEqualTo(ADMIN_CODE);
        assertThat(String.valueOf(UserType.USER.getCode()))
                .as("likewise for the standard user code")
                .isEqualTo(USER_CODE);
    }

    @ParameterizedTest
    @MethodSource("outOfDomainUserTypeCodes")
    @DisplayName("2.7 HIGH: resolves an out-of-domain type to null and never defaults it to a"
            + " constant")
    void resolvesAnOutOfDomainTypeToNullAndNeverDefaults(final String code, final String reason) {
        final SignOnResponse response = baselineWith("userType", code);

        assertThat(response.resolvedUserType())
                .as("%s, so the typed view must be absent rather than guessed", reason)
                .isNull();
        assertThat(response.userType())
                .as("and the raw value must round trip unchanged, so that an out-of-domain stored"
                        + " byte can still be read back rather than making the payload unreadable")
                .isEqualTo(code);
    }

    @Test
    @DisplayName("2.8 carries no routing hint, because the user type alone selects the destination")
    void carriesNoRoutingHint() {
        for (final String hint : FORBIDDEN_ROUTING_HINTS) {
            assertThat(declaredMemberNames())
                    .as("app/cbl/COSGN00C.cbl:230-240 selects COADM01C for the administrator code and"
                            + " COMEN01C otherwise, so %s would restate a decision the user type"
                            + " already determines and would reinstate CDEMO-TO-PROGRAM by another"
                            + " name", hint)
                    .doesNotContain(hint.toLowerCase(Locale.ROOT));
        }
        assertThat(baselineWith("userType", ADMIN_CODE).resolvedUserType())
                .as("the administrator code is the whole input to the administrative branch at :231")
                .isEqualTo(UserType.ADMIN);
        assertThat(baselineWith("userType", USER_CODE).resolvedUserType())
                .as("and any non administrator code takes the main menu branch at :236, which the"
                        + " service tier decides - not this payload")
                .isEqualTo(UserType.USER);
    }

    @Test
    @DisplayName("2.9 LOW: propagates neither card number shape, numeric nor alphanumeric")
    void propagatesNeitherCardNumberShape() {
        assertThat(declaredComponentTypes())
                .as("CDEMO-CARD-NUM at app/cpy/COCOM01Y.cpy:41 is PIC 9(16), numeric, while the card"
                        + " entity's CARD-NUM at app/cpy/CVACT02Y.cpy:5 is PIC X(16), alphanumeric."
                        + " Recorded as a divergence; neither shape is propagated, because a card"
                        + " number has no place in a sign-on reply and no numeric component exists"
                        + " here to receive one")
                .containsOnly(String.class);
        assertThat(declaredMemberNames())
                .as("nor may a card number appear under any spelling")
                .doesNotContain("cardnum", "cardnumber", "card", "pan");
    }


    // =========================================================================================
    // 3. TOKEN CONTAINMENT - the credential leaves this type through no diagnostic path
    //    Rule 1 Clause D names tests explicitly: "No secrets in code, logs, tests, or config."
    // =========================================================================================

    @Test
    @DisplayName("3.1 BLOCKER: renders no fragment of the token through toString, not even a"
            + " four character run")
    void toStringRendersNoFragmentOfTheToken() {
        final String rendered = baseline().toString();
        final List<String> fragments = substringsOf(SYNTHETIC_TOKEN, 4);

        assertThat(fragments)
                .as("the fragment set must be non trivial for this assertion to have teeth")
                .isNotEmpty()
                .contains(SYNTHETIC_TOKEN);
        assertThat(rendered)
                .as("a bearer token in a log line is a replayable credential, so the rendering must"
                        + " disclose no part of it: a masked prefix or a first-and-last-four rendering"
                        + " would satisfy a whole value comparison while still leaking")
                .doesNotContain(fragments.toArray(String[]::new));
    }

    @Test
    @DisplayName("3.2 BLOCKER: names no token field through toString and emits no mask, length or"
            + " presence hint")
    void toStringNamesNoTokenFieldAndEmitsNoMaskOrLength() {
        final String rendered = baseline().toString();

        assertThat(rendered.toLowerCase(Locale.ROOT))
                .as("naming the field would disclose that a credential is present and where to look"
                        + " for it")
                .doesNotContain("token", "bearer", "authorization", "credential", "secret");
        assertThat(rendered)
                .as("no mask, ellipsis or redaction marker may stand in for the token either: the"
                        + " documented contract is that nothing is emitted in its place")
                .doesNotContain("*", "...", "\u2026", "MASKED", "REDACTED", "<", ">");
        assertThat(rendered)
                .as("nor may the token's length be disclosed, since length narrows a credential"
                        + " search space")
                .doesNotContain(String.valueOf(SYNTHETIC_TOKEN.length()));
        assertThat(baselineWith("token", null).toString())
                .as("and the rendering must be identical whether a token is present or absent, so"
                        + " that it discloses not even whether one was issued")
                .isEqualTo(rendered);
    }

    @Test
    @DisplayName("3.3 renders exactly the two non credential fields it documents, and nothing else")
    void toStringRendersExactlyTheTwoDocumentedFields() {
        assertThat(baseline().toString())
                .as("the rendering is the identifier and the one character type code, both of which"
                        + " are opaque and neither of which is personally identifiable")
                .isEqualTo("SignOnResponse[userId=" + SYNTHETIC_USER_ID
                        + ", userType=" + ADMIN_CODE + "]");
    }

    @Test
    @DisplayName("3.4 serialises exactly the three components, so nothing was hidden by accident")
    void serialisesExactlyTheThreeComponents() throws JsonProcessingException {
        final Map<String, Object> properties = serialisedProperties(baseline());

        assertThat(properties)
                .as("the wire contract is the three components and nothing more; a fourth property"
                        + " would be surface the type does not document")
                .containsOnlyKeys(EXPECTED_COMPONENTS.toArray(String[]::new));
        assertThat(properties)
                .as("the token must be serialised outbound - it is the payload, and withholding it"
                        + " would make a successful sign-on useless. Containment is a toString"
                        + " guarantee, not a serialisation one")
                .containsEntry("token", SYNTHETIC_TOKEN)
                .containsEntry("userId", SYNTHETIC_USER_ID)
                .containsEntry("userType", ADMIN_CODE);
    }

    @ParameterizedTest
    @MethodSource("forbiddenMemberNames")
    @DisplayName("3.5 BLOCKER: carries no credential, no personally identifiable field and no invented"
            + " token metadata")
    void carriesNoCredentialPersonalDataOrInventedTokenMetadata(final String forbidden)
            throws JsonProcessingException {
        final String lowerCased = forbidden.toLowerCase(Locale.ROOT);

        assertThat(declaredMemberNames())
                .as("%s must exist on this type neither as a field nor as an accessor: a sign-on reply"
                        + " returns no secret, discloses no personal data, and invents no lifetime the"
                        + " COMMAREA never declared", forbidden)
                .doesNotContain(lowerCased);
        assertThat(serialisedProperties(baseline()).keySet().stream()
                        .map(key -> key.toLowerCase(Locale.ROOT))
                        .toList())
                .as("nor may %s appear on the wire", forbidden)
                .doesNotContain(lowerCased);
        assertThat(baseline().toString().toLowerCase(Locale.ROOT))
                .as("nor in a diagnostic rendering")
                .doesNotContain(lowerCased);
    }

    @Test
    @DisplayName("3.6 survives a JSON round trip with all three components intact")
    void survivesAJsonRoundTripWithAllThreeComponentsIntact() throws JsonProcessingException {
        final SignOnResponse original = baseline();

        final SignOnResponse roundTripped = fromJson(toJson(original));

        assertThat(roundTripped)
                .as("nothing on a response is write-only: the client receives the token, the"
                        + " identifier and the type, so the round trip must be lossless")
                .isEqualTo(original);
        assertThat(roundTripped.resolvedUserType())
                .as("and the typed view must still resolve after the round trip, since it is derived"
                        + " from the raw code rather than serialised alongside it")
                .isEqualTo(UserType.ADMIN);
    }

    @Test
    @DisplayName("3.7 keeps the typed view out of the wire contract, so the serialised shape stays"
            + " exactly three properties")
    void keepsTheTypedViewOutOfTheWireContract() throws JsonProcessingException {
        final Method typedViewAccessor = accessor("resolvedUserType");

        assertThat(typedViewAccessor.getAnnotation(JsonIgnore.class))
                .as("resolvedUserType is a convenience over the raw code; serialising it would add a"
                        + " fourth property and give the same fact two representations on the wire")
                .isNotNull();
        assertThat(serialisedProperties(baseline()))
                .as("so the serialised shape stays exactly the three components")
                .hasSize(EXPECTED_COMPONENTS.size())
                .doesNotContainKey("resolvedUserType");
    }

    @Test
    @DisplayName("3.8 is not Java serialisable, so no token can travel in an opaque byte stream")
    void isNotJavaSerialisable() {
        assertThat(Serializable.class.isAssignableFrom(SignOnResponse.class))
                .as("Java serialisation of a token bearing type is an insecure deserialisation risk:"
                        + " the credential would travel in a stream no log masking inspects and that a"
                        + " gadget chain on the receiving side could exploit. The omission is a"
                        + " security decision and must not be undone")
                .isFalse();
    }

    @Test
    @DisplayName("3.9 carries no polymorphic type identifier, which would be a deserialisation gadget"
            + " surface")
    void carriesNoPolymorphicTypeIdentifier() throws JsonProcessingException {
        assertThat(SignOnResponse.class.getAnnotation(JsonTypeInfo.class))
                .as("a response type that named its own implementation class would let a caller"
                        + " choose what gets instantiated, which is the insecure deserialisation"
                        + " pattern the standards ask to be flagged")
                .isNull();
        assertThat(serialisedProperties(baseline()).keySet())
                .as("and no type identifier may appear on the wire under any of its usual spellings")
                .doesNotContain("@class", "@type", "type", "javaClass", "class");
    }

    @Test
    @DisplayName("3.10 BLOCKER: uses fixtures that cannot collide with the seeded plaintext password")
    void usesFixturesThatCannotCollideWithTheSeededPlaintext() {
        for (final String fixture : List.of(SYNTHETIC_TOKEN, OTHER_SYNTHETIC_TOKEN,
                SYNTHETIC_USER_ID, ADMIN_CODE, USER_CODE)) {
            assertThat(fixture)
                    .as("app/jcl/DUSRSECJ.jcl:35-44 seeds ten users - five type 'A' and five type 'U',"
                            + " the type character at byte 57 - that share one plaintext password of"
                            + " eight upper case letters in bytes 49 to 56. Asserting the shape"
                            + " mismatch proves no fixture can coincide with it without ever writing"
                            + " that literal under src/")
                    .doesNotMatch(SEEDED_PLAINTEXT_SHAPE);
        }
        assertThat(SYNTHETIC_USER_ID)
                .as("and the identifier fixture must be none of the ten seeded identifiers")
                .isNotIn("ADMIN001", "ADMIN002", "ADMIN003", "ADMIN004", "ADMIN005",
                        "USER0001", "USER0002", "USER0003", "USER0004", "USER0005");
    }

    @Test
    @DisplayName("3.11 BLOCKER: uses no signing key, cloud access key, password hash or real token as"
            + " a fixture")
    void usesNoSigningKeyCloudKeyHashOrRealTokenAsAFixture() {
        for (final String fixture : List.of(SYNTHETIC_TOKEN, OTHER_SYNTHETIC_TOKEN,
                SYNTHETIC_USER_ID)) {
            assertThat(fixture)
                    .as("no fixture may be shaped like a cloud access key identifier")
                    .doesNotMatch(CLOUD_ACCESS_KEY_SHAPE);
            assertThat(fixture)
                    .as("no fixture may be shaped like a BCrypt hash; hashing belongs to the security"
                            + " tier and is not computed here")
                    .doesNotMatch(BCRYPT_HASH_SHAPE);
            assertThat(fixture)
                    .as("no fixture may be shaped like a three segment JSON Web Token: this tier"
                            + " treats the token as an opaque string, imports no JWT library and mints"
                            + " nothing, so a well formed token would buy no coverage and would only"
                            + " place a credential shaped literal under src/")
                    .doesNotMatch(JWT_SHAPE);
        }
        assertThat(SYNTHETIC_TOKEN)
                .as("the signing key is resolved from an environment variable by the security layer"
                        + " and never traverses a data transfer object, so no key material and no"
                        + " algorithm constant appears in this suite")
                .doesNotContainIgnoringCase("secret")
                .doesNotContainIgnoringCase("key")
                .doesNotContainIgnoringCase("HS256");
    }

    @Test
    @DisplayName("3.12 BLOCKER: reports an over-width identifier without echoing its value")
    void constraintViolationsNeverEchoTheOffendingValue() {
        final String overWidth = SYNTHETIC_USER_ID + "x";

        final Set<ConstraintViolation<SignOnResponse>> violations =
                violationsOf(baselineWith("userId", overWidth));

        assertThat(violations)
                .as("one bound is breached, so exactly one violation is expected")
                .hasSize(1);
        assertThat(violations)
                .allSatisfy(violation -> {
                    assertThat(violation.getPropertyPath().toString())
                            .as("the violation must identify the field by name, which is how a caller"
                                    + " learns what to fix")
                            .isEqualTo("userId");
                    assertThat(violation.getMessage())
                            .as("and it must not echo the offending value: rendering"
                                    + " getInvalidValue() into a response or a log is the mistake this"
                                    + " asserts against, and on this type one component is a"
                                    + " credential")
                            .doesNotContain(overWidth)
                            .doesNotContain(SYNTHETIC_USER_ID)
                            .doesNotContain(SYNTHETIC_TOKEN);
                });
    }


    // =========================================================================================
    // 4. DETERMINISM - nothing on this type is derived from the ambient clock
    //    The three legacy renderings: app/cbl/COBIL00C.cbl:249-267 (online),
    //    app/cbl/CBACT04C.cbl:613-626 and app/cbl/CBTRN02C.cbl:692-706 (batch),
    //    app/cbl/COTRN02C.cbl:464-465 (pass through). All three are 26 byte character fields.
    // =========================================================================================

    @Test
    @DisplayName("4.1 HIGH: declares no temporal component, so no value on it can be wall clock"
            + " derived")
    void declaresNoTemporalComponent() {
        assertThat(declaredComponentTypes())
                .as("app/cpy/COCOM01Y.cpy declares no lifetime and no issue instant, so an expiry or"
                        + " an issued-at would be invented rather than translated. A client that needs"
                        + " an expiry reads the claim from the token it already holds")
                .containsOnly(String.class);
        for (final String temporal : FORBIDDEN_TEMPORAL_MEMBERS) {
            assertThat(declaredMemberNames())
                    .as("%s must not exist on this type: asserting its absence is the honest outcome"
                            + " when the source has no analogue, rather than inventing a field to"
                            + " assert about", temporal)
                    .doesNotContain(temporal.toLowerCase(Locale.ROOT));
        }
        assertThat(Arrays.stream(SignOnResponse.class.getDeclaredMethods())
                        .map(Method::getReturnType)
                        .map(Class::getName)
                        .toList())
                .as("no accessor may return a temporal type either; every timestamp in this system is"
                        + " CHAR(26) text, never a date time object, because the separator geometry is"
                        + " part of the field contract and no temporal type carries it")
                .noneMatch(name -> name.startsWith("java.time."))
                .doesNotContain("java.util.Date", "java.sql.Timestamp");
    }

    @Test
    @DisplayName("4.2 renders identically under two different fixed clocks, proving no time derived"
            + " content")
    void rendersIdenticallyUnderTwoDifferentFixedClocks() throws JsonProcessingException {
        final Clock canonical = FixedClockProvider.canonicalClock();
        final Clock alternate = FixedClockProvider.fixedClock(ALTERNATE_INSTANT);
        final String canonicalRendering = FixedClockProvider.onlineTimestamp(canonical);
        final String alternateRendering = FixedClockProvider.onlineTimestamp(alternate);

        assertThat(canonicalRendering)
                .as("the fixed clock is deterministic: FixedClockProvider.CANONICAL_INSTANT renders to"
                        + " the one distinct originating timestamp present in every row of"
                        + " app/data/ASCII/dailytran.txt - the fixture is dailytran.txt spelled in"
                        + " full, never dalytran.txt")
                .isEqualTo(FixedClockProvider.CANONICAL_ONLINE_TIMESTAMP);
        assertThat(alternateRendering)
                .as("and the renderer is genuinely clock sensitive, which is what gives the next"
                        + " assertion teeth: were it not, containing neither rendering would prove"
                        + " nothing")
                .isNotEqualTo(canonicalRendering);
        assertThat(baseline().toString())
                .as("the payload carries no time derived content, so neither rendering can appear in"
                        + " it and its diagnostic form cannot drift with the clock")
                .doesNotContain(canonicalRendering, alternateRendering);
        assertThat(toJson(baseline()))
                .as("nor may either rendering reach the wire")
                .doesNotContain(canonicalRendering, alternateRendering);
    }

    @Test
    @DisplayName("4.3 agrees with all three legacy 26 byte renderings and carries none of them")
    void agreesWithAllThreeLegacyRenderingsAndCarriesNone() throws JsonProcessingException {
        final String online = FixedClockProvider.onlineTimestamp(FixedClockProvider.canonicalClock());
        final String batch = FixedClockProvider.batchTimestamp(FixedClockProvider.canonicalClock());
        final String passedThrough = FixedClockProvider.passThroughTimestamp("2022-06-10");

        assertThat(online)
                .as("app/cbl/COBIL00C.cbl:263-265 initialises the field then moves the date into bytes"
                        + " 1 to 10 and the time into bytes 12 to 19, leaving byte 11 as the SPACE its"
                        + " FILLER supplies, and :266 moves zeros into the six microsecond digits")
                .hasSize(FixedClockProvider.TIMESTAMP_LENGTH)
                .isEqualTo(FixedClockProvider.CANONICAL_ONLINE_TIMESTAMP)
                .endsWith(".000000");
        assertThat(online.charAt(10))
                .as("byte 11 of the online rendering is a space")
                .isEqualTo(' ');
        assertThat(batch)
                .as("app/cbl/CBACT04C.cbl:623 moves a dash into THREE separator bytes, so the batch"
                        + " rendering carries a dash between the day and the hour, and :621-:622 fill"
                        + " hundredths then four literal zeros - millisecond precision, never"
                        + " nanoseconds, which would give 29 characters")
                .hasSize(FixedClockProvider.TIMESTAMP_LENGTH)
                .isEqualTo(FixedClockProvider.CANONICAL_BATCH_TIMESTAMP)
                .endsWith("0000");
        assertThat(batch.charAt(10))
                .as("byte 11 of the batch rendering is a dash, which is what distinguishes the two"
                        + " renderings that coexist in one 350 byte transaction record")
                .isEqualTo('-');
        assertThat(passedThrough)
                .as("app/cbl/COTRN02C.cbl:464-465 moves a PIC X(10) screen field into a PIC X(26)"
                        + " receiving field, and a COBOL alphanumeric move left justifies and space"
                        + " fills, so ten bytes of value are followed by sixteen spaces")
                .hasSize(FixedClockProvider.TIMESTAMP_LENGTH)
                .isEqualTo("2022-06-10" + " ".repeat(16));
        assertThat(baseline().toString() + toJson(baseline()))
                .as("none of the three renderings appears anywhere in this payload, because it carries"
                        + " no timestamp at all")
                .doesNotContain(online, batch, passedThrough);
    }

    @Test
    @DisplayName("4.4 folds no case on ingest, and resolves the type code case sensitively under"
            + " Locale.ROOT")
    void foldsNoCaseOnIngestAndResolvesCaseSensitively() {
        final String lowerCasedIdentifier = SYNTHETIC_USER_ID.toLowerCase(Locale.ROOT);

        assertThat(baselineWith("userId", lowerCasedIdentifier).userId())
                .as("app/cbl/COSGN00C.cbl:132-136 folds the identifier and the password to upper case"
                        + " in the program, not in the payload, so this type transports what it was"
                        + " given and normalises nothing")
                .isEqualTo(lowerCasedIdentifier)
                .isNotEqualTo(SYNTHETIC_USER_ID);
        assertThat(baselineWith("userType", ADMIN_CODE.toLowerCase(Locale.ROOT)).resolvedUserType())
                .as("resolution is case sensitive parity rather than strictness: :227 moves"
                        + " SEC-USR-TYPE unfolded and :230 compares that byte against 'A' exactly, so"
                        + " a lower case byte satisfies neither condition name")
                .isNull();
        assertThat(baselineWith("userType", "a".toUpperCase(Locale.ROOT)).resolvedUserType())
                .as("and the upper case byte resolves, so the asymmetry is the case and not the"
                        + " letter. Every case operation in this suite states Locale.ROOT, so no"
                        + " assertion can vary with the host's default locale")
                .isEqualTo(UserType.ADMIN);
    }

    // =========================================================================================
    // 5. THE TRI-STATE MODEL AND BOUNDARY CONDITIONS
    //    app/cpy/CSSETATY.cpy:18-27 models OK / NOT-OK / BLANK and fires its markers only on
    //    re-entry (:20); app/cbl/COSGN00C.cbl:118 and :123 test SPACES OR LOW-VALUES as two
    //    separate sentinels.
    // =========================================================================================

    @ParameterizedTest
    @MethodSource("componentNames")
    @DisplayName("5.1 HIGH: keeps absent, empty, blank, low-values and present as five"
            + " distinguishable states")
    void keepsAbsentEmptyBlankLowValuesAndPresentDistinguishable(final String componentName) {
        final List<String> states = Arrays.asList(null, EMPTY, BLANK, LOW_VALUES, PRESENT_VALUE);

        final List<SignOnResponse> responses = states.stream()
                .map(state -> baselineWith(componentName, state))
                .toList();

        for (int index = 0; index < states.size(); index++) {
            assertThat(componentOf(responses.get(index), componentName))
                    .as("component %s must report state %d exactly as supplied", componentName, index)
                    .isEqualTo(states.get(index));
        }
        assertThat(responses)
                .as("app/cbl/COSGN00C.cbl:118 tests SPACES OR LOW-VALUES as two sentinels and"
                        + " app/cpy/CSSETATY.cpy:18-19 treats NOT-OK and BLANK as two outcomes, so"
                        + " collapsing any of these states into another would lose a distinction the"
                        + " source makes on component %s", componentName)
                .doesNotHaveDuplicates();
    }

    @ParameterizedTest
    @MethodSource("componentNames")
    @DisplayName("5.2 coerces neither absent to blank nor blank to absent")
    void coercesNeitherAbsentToBlankNorBlankToAbsent(final String componentName) {
        assertThat(componentOf(baselineWith(componentName, null), componentName))
                .as("absent means absent: component %s must stay null rather than becoming an empty"
                        + " string, because a caller that sent nothing is distinguishable from one"
                        + " that sent a blank fixed width field", componentName)
                .isNull();
        assertThat(componentOf(baselineWith(componentName, EMPTY), componentName))
                .as("and an empty value must stay empty rather than becoming null", componentName)
                .isNotNull()
                .isEmpty();
        assertThat(componentOf(baselineWith(componentName, BLANK), componentName))
                .as("a blank is present and one byte long, which is neither absent nor empty")
                .isEqualTo(BLANK)
                .hasSize(1);
    }

    @ParameterizedTest
    @MethodSource("boundedFieldContracts")
    @DisplayName("5.3 accepts a component filled to exactly its declared width")
    void acceptsAComponentFilledToExactlyItsDeclaredWidth(final FieldContract contract) {
        final String exact = "X".repeat(contract.width());

        assertThat(violationsOf(baselineWith(contract.componentName(), exact)))
                .as("%s is %s, so a value of exactly %d characters is the largest legitimate one and"
                        + " must not be reported as an error", contract.cobolItem(),
                        contract.pictureClause(), contract.width())
                .isEmpty();
    }

    @ParameterizedTest
    @MethodSource("boundedFieldContracts")
    @DisplayName("5.4 accepts a component one character under its declared width")
    void acceptsAComponentOneCharacterUnderItsDeclaredWidth(final FieldContract contract) {
        final String under = "X".repeat(contract.width() - 1);

        assertThat(violationsOf(baselineWith(contract.componentName(), under)))
                .as("the source declares no minimum length for %s, so a shorter value is legitimate -"
                        + " and for the one byte type code that shorter value is the empty string",
                        contract.cobolItem())
                .isEmpty();
    }

    @ParameterizedTest
    @MethodSource("boundedFieldContracts")
    @DisplayName("5.5 rejects a component one character over its declared width, and only that one")
    void rejectsAComponentOneCharacterOverItsDeclaredWidth(final FieldContract contract) {
        final String over = "X".repeat(contract.width() + 1);

        final Set<ConstraintViolation<SignOnResponse>> violations =
                violationsOf(baselineWith(contract.componentName(), over));

        assertThat(violations)
                .as("one character over the %s width of %s is one violation, no more",
                        contract.pictureClause(), contract.cobolItem())
                .hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString())
                .as("and the violation must name %s, so that a caller learns which field to fix",
                        contract.componentName())
                .isEqualTo(contract.componentName());
    }

    @Test
    @DisplayName("5.6 leaves the token unbounded, because no PIC clause for it is available")
    void leavesTheTokenUnbounded() {
        assertThat(fieldAnnotation("token", Size.class))
                .as("the token's declared width is Not available: it is a new artefact with no COBOL"
                        + " PICTURE, so there is nothing to transcribe. A width invented here would"
                        + " reject valid tokens as the claim set grows")
                .isNull();
        assertThat(violationsOf(baselineWith("token", "X".repeat(4096))))
                .as("so a long token must not be reported as a client error")
                .isEmpty();
        assertThat(violationsOf(baselineWith("token", null)))
                .as("nor may an absent token be, since absence is not a constraint violation and this"
                        + " type validates nothing on construction")
                .isEmpty();
    }

    @Test
    @DisplayName("5.7 HIGH: rejects a blank padded type code rather than repairing it")
    void rejectsABlankPaddedTypeCodeRatherThanRepairingIt() {
        final SignOnResponse padded = baselineWith("userType", ADMIN_CODE + BLANK);

        assertThat(padded.userType())
                .as("the padded value must round trip verbatim: nothing is trimmed on ingest")
                .isEqualTo("A ")
                .hasSize(USER_TYPE_WIDTH + 1);
        assertThat(padded.resolvedUserType())
                .as("a fixed width record carries blank padding, and repairing it here would accept"
                        + " input the legacy comparison at app/cbl/COSGN00C.cbl:230 refuses")
                .isNull();
        assertThat(violationsOf(padded))
                .as("and two characters cannot fill a one byte field, so the bound reports it")
                .hasSize(1);
    }

    @Test
    @DisplayName("5.8 constructs, renders and validates with every component absent")
    void constructsRendersAndValidatesWithEveryComponentAbsent() throws JsonProcessingException {
        final SignOnResponse absent = new SignOnResponse(null, null, null);

        assertThat(absent.token()).as("token must stay absent").isNull();
        assertThat(absent.userId()).as("userId must stay absent").isNull();
        assertThat(absent.userType()).as("userType must stay absent").isNull();
        assertThat(absent.resolvedUserType())
                .as("and the typed view must be absent too, never defaulted to the standard user"
                        + " constant, because a silent default would invent an authorisation decision")
                .isNull();
        assertThat(violationsOf(absent))
                .as("absence is not a constraint violation: the canonical constructor validates"
                        + " nothing, normalises nothing and rejects nothing")
                .isEmpty();
        assertThat(absent.toString())
                .as("the rendering must remain well formed and must still disclose no token")
                .isEqualTo("SignOnResponse[userId=null, userType=null]");
        assertThat(serialisedProperties(absent))
                .as("and the wire shape must remain the three components rather than collapsing to an"
                        + " empty object, so that a client can tell an absent token from a missing one")
                .containsOnlyKeys(EXPECTED_COMPONENTS.toArray(String[]::new))
                .containsValue(null);
    }


    // =========================================================================================
    // 6. VALUE SEMANTICS AND ERROR MODES
    // =========================================================================================

    @Test
    @DisplayName("6.1 defines equals as reflexive, symmetric and transitive over its components")
    void definesEqualsAsReflexiveSymmetricAndTransitive() {
        final SignOnResponse first = baseline();
        final SignOnResponse second = baseline();
        final SignOnResponse third = new SignOnResponse(SYNTHETIC_TOKEN, SYNTHETIC_USER_ID,
                ADMIN_CODE);

        assertThat(first).as("equality must be reflexive").isEqualTo(first);
        assertThat(first).as("and symmetric").isEqualTo(second);
        assertThat(second).as("in both directions").isEqualTo(first);
        assertThat(second).as("and transitive").isEqualTo(third);
        assertThat(first).as("so the first and third agree too").isEqualTo(third);
        assertThat(baselineWith("userType", USER_CODE))
                .as("while a response differing in one component is not equal, which is what makes the"
                        + " identity useful at all")
                .isNotEqualTo(first);
    }

    @Test
    @DisplayName("6.2 defines equals to be null safe and type safe")
    void definesEqualsToBeNullSafeAndTypeSafe() {
        final SignOnResponse response = baseline();

        assertThat(response.equals(null))
                .as("comparing against null must answer false rather than throwing")
                .isFalse();
        assertThat(response.equals(SYNTHETIC_TOKEN))
                .as("and comparing against an unrelated type must answer false rather than throwing,"
                        + " even when that value is one of this response's own components")
                .isFalse();
    }

    @Test
    @DisplayName("6.3 returns a stable hash code, and the same hash code for equal instances")
    void returnsAStableHashCodeForEqualInstances() {
        final SignOnResponse first = baseline();
        final SignOnResponse second = baseline();

        assertThat(first.hashCode())
                .as("the hash code must not change between calls on an immutable value")
                .isEqualTo(first.hashCode());
        assertThat(first.hashCode())
                .as("and equal instances must agree, or the type breaks every hash based collection")
                .isEqualTo(second.hashCode());
    }

    @Test
    @DisplayName("6.4 supports equals and hashCode when every component is absent")
    void supportsEqualsAndHashCodeWhenEveryComponentIsAbsent() {
        final SignOnResponse first = new SignOnResponse(null, null, null);
        final SignOnResponse second = new SignOnResponse(null, null, null);

        assertThat(first)
                .as("two fully absent responses are equal, so the generated equality handles null"
                        + " components without special casing by the caller")
                .isEqualTo(second);
        assertThat(first.hashCode())
                .as("and their hash codes agree")
                .isEqualTo(second.hashCode());
        assertThat(first)
                .as("while an absent response is not equal to a populated one")
                .isNotEqualTo(baseline());
    }

    @Test
    @DisplayName("6.5 LOW: includes the token in value identity, which is record semantics and"
            + " discloses nothing")
    void includesTheTokenInValueIdentityWithoutDisclosingIt() {
        final SignOnResponse withToken = baseline();
        final SignOnResponse withOtherToken = baselineWith("token", OTHER_SYNTHETIC_TOKEN);

        assertThat(withToken)
                .as("this is a record, so equals and hashCode are value based across all three"
                        + " components and cannot be made to exclude the token without hand writing"
                        + " both. Recorded as Low rather than left implicit: producing an equal"
                        + " instance requires already holding the token, so equality discloses"
                        + " nothing a caller did not already have")
                .isNotEqualTo(withOtherToken);
        assertThat(String.valueOf(withToken.hashCode()))
                .as("and a hash code renders as digits, so it cannot carry credential text")
                .doesNotContain(SYNTHETIC_TOKEN)
                .matches("^-?\\d+$");
        assertThat(withToken.toString())
                .as("the consequence worth stating is operational, not cryptographic: instances must"
                        + " not be used as cache keys nor identified by hash code in diagnostics."
                        + " toString remains the primary defence and still omits the token")
                .isEqualTo(withOtherToken.toString());
    }

    @Test
    @DisplayName("6.6 throws from no construction path and from no accessor, for any state")
    void throwsFromNoConstructionPathAndFromNoAccessor() {
        final List<String> states = Arrays.asList(null, EMPTY, BLANK, LOW_VALUES, PRESENT_VALUE,
                "X".repeat(64));

        for (final String state : states) {
            assertThatCode(() -> {
                final SignOnResponse response = new SignOnResponse(state, state, state);
                response.token();
                response.userId();
                response.userType();
                response.resolvedUserType();
                response.toString();
                response.hashCode();
            })
                    .as("this type is a pure data holder: it validates nothing on construction and"
                            + " resolves an out-of-domain code to null rather than failing, so no"
                            + " component value can make it throw. An over-width value is reported as"
                            + " a constraint violation where the type is explicitly validated, never"
                            + " as an exception")
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("6.7 rejects an unknown component name with a message that names the field, not a"
            + " value")
    void rejectsAnUnknownComponentNameWithAFieldNamingMessage() {
        final String unknown = "tokenThatDoesNotExist";

        assertThatIllegalArgumentException()
                .as("a renamed component must fail loudly rather than silently build a wrong payload")
                .isThrownBy(() -> baselineWith(unknown, PRESENT_VALUE))
                .withMessage("not a component of SignOnResponse: " + unknown)
                .withNoCause();
        assertThatIllegalArgumentException()
                .as("the reader helper must fail the same way, for the same reason, and neither"
                        + " message may echo a value because one component is a credential")
                .isThrownBy(() -> componentOf(baseline(), unknown))
                .withMessage("not a component of SignOnResponse: " + unknown)
                .withNoCause();
    }

    @Test
    @DisplayName("6.8 raises an AssertionError that preserves its cause when a component field is"
            + " renamed")
    void raisesAnAssertionErrorPreservingItsCauseWhenAComponentFieldIsRenamed() {
        assertThatExceptionOfType(AssertionError.class)
                .as("the annotation reader must not swallow the reflective failure: a renamed field is"
                        + " the exact condition it exists to surface, and the original"
                        + " NoSuchFieldException is the root cause a reader needs")
                .isThrownBy(() -> fieldAnnotation("noSuchComponent", Size.class))
                .withMessage("SignOnResponse declares no field for component noSuchComponent")
                .withCauseInstanceOf(NoSuchFieldException.class);
        assertThatExceptionOfType(AssertionError.class)
                .as("the accessor lookup must behave identically")
                .isThrownBy(() -> accessor("noSuchAccessor"))
                .withMessage("SignOnResponse declares no accessor named noSuchAccessor")
                .withCauseInstanceOf(NoSuchMethodException.class);
    }
}

