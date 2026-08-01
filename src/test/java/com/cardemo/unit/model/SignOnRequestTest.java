/*
 * ******************************************************************
 * Program     : SignOnRequestTest.java
 * Component   : Unit test tier - model and DTO suite, resident at
 *               src/test/java/com/cardemo/unit/model
 * Application : CardDemo
 * Type        : JUnit 5 unit test - pure JVM, no container, no Spring
 *               context, no database
 * Function    : Proves that com.cardemo.model.dto.SignOnRequest
 *               reproduces the eleven field sign-on screen contract
 *               byte exactly, transports the submitted identifier and
 *               credential without folding case, keeps the presented
 *               password out of every rendering and serialisation path,
 *               and preserves the source's absent / blank / low-values
 *               tri-state input model.
 * Source      : app/cpy-bms/COSGN00.CPY (11 input fields, group
 *               COSGN0AI; CURTIMEI PIC X(9) at :54)
 *               + app/cbl/COSGN00C.cbl:L117-L136,L196,L223-L235
 *               + app/cpy/CSUSR01Y.cpy:L18,:L21
 *               + app/cpy/COCOM01Y.cpy:L26-L28
 *               + app/cpy/CSSETATY.cpy:L18-L27
 *               + app/cpy/CSDAT01Y.cpy:L30-L41
 *               + app/jcl/DUSRSECJ.jcl:L35-L44 @ 7756d89
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

import com.cardemo.model.dto.SignOnRequest;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.Size;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
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
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Unit suite for {@link SignOnRequest}, the inbound payload of the CardDemo sign-on transaction.
 *
 * <h2>What it does</h2>
 *
 * <p>This suite treats the BMS symbolic map as the authority and the Java record as the thing under
 * test, and it asserts five separable contracts. Every claim below was verified against the frozen
 * corpus at commit {@code 7756d89}; no figure here is inherited from prose.
 *
 * <ol>
 *   <li><strong>The eleven field contract.</strong> {@code app/cpy-bms/COSGN00.CPY} declares the
 *       input group {@code 01 COSGN0AI} at line 17, running to line 84 where
 *       {@code 01 COSGN0AO REDEFINES COSGN0AI} takes over at line 85. BMS emits a quintuple per
 *       screen field - a {@code COMP PIC S9(4)} length item, an attribute byte, a redefined
 *       attribute alias, four reserved bytes, then the data item - behind a twelve byte terminal
 *       header at line 18. Only the eleven {@code ...I} data items are the transfer budget, and this
 *       suite pins each one's name, ordinal position and declared width.</li>
 *   <li><strong>The nine byte {@code CURTIMEI}.</strong> {@code app/cpy-bms/COSGN00.CPY:54} declares
 *       {@code 02  CURTIMEI  PIC X(9).} All sixteen sibling maps declare {@code PIC X(8)} at their
 *       own line 54. See {@link #signOnDeclaresTheOnlyNineByteHeaderTimeInTheCorpus()}.</li>
 *   <li><strong>Raw transport, no case folding.</strong> {@code app/cbl/COSGN00C.cbl:L132-L134}
 *       applies {@code FUNCTION UPPER-CASE} to {@code USERIDI} and {@code :L135-L136} applies it to
 *       {@code PASSWDI} - <em>both</em> identifiers, which is easy to miss - before the comparison
 *       at {@code :L223}. The fold happens in the program, on the raw map field, so the payload must
 *       arrive unfolded for the service to be able to apply it.</li>
 *   <li><strong>Credential containment.</strong> The presented password must not appear in any
 *       rendering or serialisation of this type. {@code app/jcl/DUSRSECJ.jcl:L35-L44} seeds ten users
 *       - five administrators and five standard users, the type character at byte 57 - that share
 *       one plaintext credential; that literal appears nowhere in this suite, and
 *       {@link #theSyntheticCredentialCannotCollideWithTheSeededPlaintext()} proves the substitute
 *       used here cannot coincide with it without ever naming it.</li>
 *   <li><strong>The tri-state input model.</strong> {@code app/cpy/CSSETATY.cpy:L18-L27} is a
 *       {@code COPY ... REPLACING} template copied into the {@code PROCEDURE DIVISION}, not a data
 *       layout, and models three outcomes per field - OK, NOT-OK and BLANK - with the markers gated
 *       on {@code CDEMO-PGM-REENTER}. {@code app/cbl/COSGN00C.cbl:L118} and {@code :L123}
 *       corroborate it independently by testing {@code SPACES OR LOW-VALUES} as two separate
 *       sentinels. Absent, blank and low-values are therefore distinct states here, never
 *       collapsed.</li>
 * </ol>
 *
 * <p>What this suite deliberately does <em>not</em> assert is the sign-on <em>behaviour</em>: no
 * upper-casing, no credential comparison, no menu routing and no token issuance. Those belong to
 * {@code AuthenticationService}, the Java target of {@code app/cbl/COSGN00C.cbl}. The record
 * transports; the service decides. Nor does it assert anything about {@code SignOnResponse}: that
 * type has no BMS map of its own, being the token bearing reply that replaces the COMMAREA, so its
 * field contract is <strong>Not available</strong> from the presentation layer and belongs to
 * {@code SignOnResponseTest}. None is invented here. Any claim about the relational schema is
 * likewise <strong>Not available</strong> to this tier, which reaches no database.
 *
 * <h2>How to build, run and test</h2>
 *
 * <p>{@code mvn -B clean test} runs this suite. Surefire 3.5.4 collects it because the class name
 * ends in {@code Test} and the path is neither under {@code integration} nor under {@code e2e},
 * which are the plugin's two exclusions; a class moved out of {@code src/test/java/com/cardemo/unit}
 * or renamed away from that suffix is collected by neither Surefire nor Failsafe and then silently
 * never runs, reporting a green build the whole time. {@code mvn -B clean test-compile} is the
 * fastest check that this file still satisfies the compiler settings. Where a local toolchain is
 * unavailable the pinned image reproduces it exactly:
 * {@code docker run --rm -v "$PWD":/w -w /w maven:3.9.11-eclipse-temurin-25 ./mvnw -q test}.
 *
 * <h2>Key configurations and defaults</h2>
 *
 * <ul>
 *   <li><strong>Time is injected, never ambient.</strong> Every temporal value originates from
 *       {@link FixedClockProvider} in this package - {@link FixedClockProvider#CANONICAL_INSTANT}
 *       resolved in {@link FixedClockProvider#CANONICAL_ZONE}. No no-argument {@code now()}
 *       accessor, no default zone and no default locale is consulted anywhere, and every case
 *       operation and formatter here is pinned to {@link Locale#ROOT}, so no assertion can drift
 *       with the host.</li>
 *   <li><strong>Mockito is deliberately unused.</strong> Its strict stubs default would flag an
 *       unnecessary stubbing, but the point is moot: this record is a pure data carrier with no
 *       collaborator to mock, so a test double here would be dead code.</li>
 *   <li><strong>Validation runs against a real engine, not a stub.</strong> Each check builds a
 *       {@link ValidatorFactory} from the default provider and closes it, which is pure JVM work -
 *       no Spring context is started and no container is required.</li>
 *   <li><strong>BCrypt strength 10 is context only.</strong> No hash is computed here and no BCrypt
 *       library is on this tier's classpath; hashing belongs to {@code UserSecurityTest} and the
 *       service layer.</li>
 * </ul>
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><em>The build fails on something that looks cosmetic.</em> Compilation runs with
 *       {@code -Xlint:all}, {@code -Werror} and {@code failOnWarning}, and that reaches test
 *       compilation, so one unused import, raw type or deprecation is an error rather than a
 *       warning. Reproduce with {@code mvn -B clean test-compile}.</li>
 *   <li><em>An annotation assertion reads {@code null} unexpectedly.</em> Neither
 *       {@link Size} nor {@link JsonProperty} declares {@code ElementType.RECORD_COMPONENT}, so
 *       {@link RecordComponent#getAnnotation(Class)} returns {@code null} for both even though the
 *       annotation is plainly written on the component. It propagates to the field and the accessor
 *       instead, which is where this suite reads it.</li>
 *   <li><em>A fixture stream is {@code null} at run time.</em> The daily transaction fixture is
 *       {@code dailytran.txt}, spelled in full. The mainframe data definition name and dataset are
 *       {@code DALYTRAN}, so {@code dalytran.txt} is the natural guess and is wrong: it compiles
 *       cleanly and fails only when the resource is opened. This suite loads no fixture, but the
 *       trap is recorded because it is the tier's most common self-inflicted failure.</li>
 *   <li><em>A header width assertion fails after a refactor.</em> Something extracted the six
 *       recurring header fields into a shared base class, interface or mixin. One inherited
 *       {@code CURTIME} cannot be nine bytes here and eight bytes on the other sixteen maps at once,
 *       so such an abstraction silently corrupts one side or the other. See
 *       {@link #noSharedHeaderSupertypeIsIntroduced()}.</li>
 *   <li><em>A credential appears in a log or a response.</em> The write-only access mode on the
 *       password component or the {@code toString()} override has been removed or overridden by a
 *       custom serialiser. Those two, not the log masking configuration, are the primary defence.
 *       See {@link #serialisedFormOmitsBothTheCredentialKeyAndItsValue()}.</li>
 * </ul>
 */
@DisplayName("SignOnRequest - BMS field contract, credential containment and the tri-state model")
final class SignOnRequestTest {

    // -----------------------------------------------------------------------------------------
    // Verified widths from app/cpy-bms/COSGN00.CPY. Restated here as literals on purpose: a test
    // that asserted SignOnRequest's own constants against themselves would be a tautology, so the
    // expected values are transcribed from the copybook and compared against the production
    // constants. Changing a production width therefore fails a test rather than moving the goalposts.
    // -----------------------------------------------------------------------------------------

    /** {@code TRNNAMEI PIC X(4)} at {@code app/cpy-bms/COSGN00.CPY:24}. */
    private static final int TRANSACTION_NAME_WIDTH = 4;

    /** {@code TITLE01I PIC X(40)} at {@code app/cpy-bms/COSGN00.CPY:30}. */
    private static final int TITLE01_WIDTH = 40;

    /** {@code CURDATEI PIC X(8)} at {@code app/cpy-bms/COSGN00.CPY:36}. */
    private static final int CURRENT_DATE_WIDTH = 8;

    /** {@code PGMNAMEI PIC X(8)} at {@code app/cpy-bms/COSGN00.CPY:42}. */
    private static final int PROGRAM_NAME_WIDTH = 8;

    /** {@code TITLE02I PIC X(40)} at {@code app/cpy-bms/COSGN00.CPY:48}. */
    private static final int TITLE02_WIDTH = 40;

    /**
     * {@code CURTIMEI PIC X(9)} at {@code app/cpy-bms/COSGN00.CPY:54} - nine, not eight, and unique
     * to this map in the whole corpus.
     */
    private static final int CURRENT_TIME_WIDTH = 9;

    /**
     * The width every <em>other</em> symbolic map declares for {@code CURTIMEI} at its own line 54.
     * Held as a constant so the divergence is asserted rather than merely described.
     */
    private static final int SIBLING_MAP_CURRENT_TIME_WIDTH = 8;

    /** {@code APPLIDI PIC X(8)} at {@code app/cpy-bms/COSGN00.CPY:60}. */
    private static final int APPLICATION_ID_WIDTH = 8;

    /** {@code SYSIDI PIC X(8)} at {@code app/cpy-bms/COSGN00.CPY:66}. */
    private static final int SYSTEM_ID_WIDTH = 8;

    /** {@code USERIDI PIC X(8)} at {@code app/cpy-bms/COSGN00.CPY:72}. */
    private static final int USER_ID_WIDTH = 8;

    /** {@code PASSWDI PIC X(8)} at {@code app/cpy-bms/COSGN00.CPY:78}. */
    private static final int CREDENTIAL_WIDTH = 8;

    /** {@code ERRMSGI PIC X(78)} at {@code app/cpy-bms/COSGN00.CPY:84}. */
    private static final int ERROR_MESSAGE_WIDTH = 78;

    /** Number of input data items in {@code 01 COSGN0AI}: eleven, counted item by item. */
    private static final int SIGN_ON_INPUT_FIELD_COUNT = 11;

    /**
     * Number of symbolic maps under {@code app/cpy-bms}: seventeen, one per BMS mapset in
     * {@code app/bms}.
     */
    private static final int SYMBOLIC_MAP_COUNT = 17;

    /**
     * Input data item count per symbolic map, in the directory order of {@code app/cpy-bms}.
     *
     * <p>Counted two independent ways that agree exactly: once by tallying the
     * {@code ...L COMP PIC S9(4)} length items inside each {@code ...AI} input group, and once by
     * tallying the non-{@code FILLER}, non-{@code COMP} data items in the same range. Both give
     * {@value #CORPUS_INPUT_FIELD_COUNT}. The order is COACTUP, COACTVW, COADM01, COBIL00, COCRDLI,
     * COCRDSL, COCRDUP, COMEN01, CORPT00, COSGN00, COTRN00, COTRN01, COTRN02, COUSR00, COUSR01,
     * COUSR02, COUSR03.
     */
    private static final int[] INPUT_FIELDS_PER_MAP = {
        54, 37, 20, 10, 45, 15, 17, 20, 17, 11, 59, 21, 21, 59, 12, 12, 11,
    };

    /**
     * Total input data items across all seventeen symbolic maps: <strong>441</strong>.
     *
     * <p>Recorded as a <strong>Medium</strong> finding because the specification asserts 460 while
     * its own per-map table sums to 440. Neither is right. The table is short by exactly one because
     * {@code app/cpy-bms/COACTVW.CPY:60} declares {@code 02  ACCTSIDI  PIC 99999999999.} in
     * repeated-nine form rather than as {@code PIC 9(11)}, so a scan keyed on the
     * {@code X(n)}/{@code 9(n)} shape passes over it; COACTVW therefore contributes 37, not 36.
     * Remediation: the corpus total is 441 and COSGN00 contributes
     * {@value #SIGN_ON_INPUT_FIELD_COUNT} of them.
     */
    private static final int CORPUS_INPUT_FIELD_COUNT = 441;

    /** Zero based position of COSGN00 within {@link #INPUT_FIELDS_PER_MAP}. */
    private static final int SIGN_ON_MAP_INDEX = 9;

    // -----------------------------------------------------------------------------------------
    // Deterministic fixtures. Every value is immutable and every temporal value is derived from
    // FixedClockProvider, so nothing here can vary between runs or between hosts.
    // -----------------------------------------------------------------------------------------

    /**
     * Synthetic credential used throughout this suite: eight characters, matching
     * {@link #CREDENTIAL_WIDTH}, and obviously fabricated.
     *
     * <p>It is deliberately <em>not</em> the seeded plaintext from
     * {@code app/jcl/DUSRSECJ.jcl:L35-L44}, which appears nowhere under {@code src/}. Because it
     * contains lower case letters, digits and punctuation it cannot match that value's character
     * shape, and {@link #theSyntheticCredentialCannotCollideWithTheSeededPlaintext()} asserts exactly
     * that - a non-collision proof that never has to name the real literal.
     */
    private static final String SYNTHETIC_CREDENTIAL = "n0tR3al!";

    /** A second, different synthetic credential, for the value identity assertions. */
    private static final String OTHER_SYNTHETIC_CREDENTIAL = "f4k3Pwd!";

    /**
     * Character shape of the seeded plaintext credential: eight upper case letters.
     *
     * <p>Derived by inspecting bytes 49 to 56 of {@code app/jcl/DUSRSECJ.jcl:L35-L44}, which hold a
     * single distinct value across all ten seeded users. Only the <em>shape</em> is recorded, never
     * the value, which is what lets this suite prove non-collision while honouring the prohibition on
     * writing the credential itself.
     */
    private static final String SEEDED_CREDENTIAL_SHAPE = "^[A-Z]{8}$";

    /**
     * Synthetic user identifier: eight characters, mixed case so that the no-folding assertions have
     * something to bite on. Deliberately not one of the ten seeded identifiers, so that no real user
     * name can reach an assertion message.
     */
    private static final String SYNTHETIC_USER_ID = "tstUsr01";

    /** CICS transaction identifier of the sign-on transaction, {@code app/cbl/COSGN00C.cbl:L37}. */
    private static final String TRANSACTION_NAME = "CC00";

    /** Owning program name, {@code app/cbl/COSGN00C.cbl:L36}. */
    private static final String PROGRAM_NAME = "COSGN00C";

    /** First header title line, within {@link #TITLE01_WIDTH}. */
    private static final String TITLE01 = "CardDemo";

    /** Second header title line, within {@link #TITLE02_WIDTH}. */
    private static final String TITLE02 = "Sign-on";

    /** CICS application identifier, within {@link #APPLICATION_ID_WIDTH}. */
    private static final String APPLICATION_ID = "APPL0001";

    /** CICS system identifier, within {@link #SYSTEM_ID_WIDTH}. */
    private static final String SYSTEM_ID = "SYS00001";

    /** Diagnostic text, within {@link #ERROR_MESSAGE_WIDTH}. */
    private static final String ERROR_MESSAGE = "Please enter User ID ...";

    /**
     * Header date in the layout {@code app/cpy/CSDAT01Y.cpy:L30-L35} defines - two month digits, a
     * {@code '/'}, two day digits, a {@code '/'}, two year digits - which is exactly eight
     * characters and so fills {@code CURDATEI PIC X(8)} precisely.
     *
     * <p>Formatted from {@link FixedClockProvider#CANONICAL_INSTANT} in
     * {@link FixedClockProvider#CANONICAL_ZONE} with {@link Locale#ROOT}. The pattern uses
     * {@code uu} rather than {@code yy} because {@code y} is year-of-era and would misrender a
     * proleptic year; the source has no era to render.
     */
    private static final String HEADER_DATE = DateTimeFormatter.ofPattern("MM/dd/uu", Locale.ROOT)
            .format(LocalDate.ofInstant(FixedClockProvider.CANONICAL_INSTANT, FixedClockProvider.CANONICAL_ZONE));

    /**
     * Header time in the layout {@code app/cpy/CSDAT01Y.cpy:L36-L41} defines - two hour digits, a
     * {@code ':'}, two minute digits, a {@code ':'}, two second digits - which is
     * <strong>eight</strong> characters.
     */
    private static final String HEADER_TIME = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT)
            .format(LocalTime.ofInstant(FixedClockProvider.CANONICAL_INSTANT, FixedClockProvider.CANONICAL_ZONE));

    /**
     * The header time as the nine byte field actually holds it: eight characters of content followed
     * by one space.
     *
     * <p>This is not decoration. {@code app/cbl/COSGN00C.cbl:L196} moves the eight character
     * {@code WS-CURTIME-HH-MM-SS} into {@code CURTIMEO}, which redefines the nine byte
     * {@code CURTIMEI} of {@code app/cpy-bms/COSGN00.CPY:54}. A COBOL alphanumeric move left
     * justifies and space fills to the receiving width, so byte nine is a space by construction.
     * That is the mechanical reason this one map is nine bytes wide while the other sixteen are
     * eight, and it is why trailing space padding has to survive the round trip verbatim.
     */
    private static final String HEADER_TIME_PADDED = HEADER_TIME + " ";

    /**
     * COBOL {@code LOW-VALUES} for an eight byte alphanumeric field: eight NUL characters.
     *
     * <p>{@code app/cbl/COSGN00C.cbl:L118} and {@code :L123} test {@code SPACES OR LOW-VALUES} as two
     * separate sentinels, so low-values is a state in its own right and not a synonym for blank.
     */
    private static final String LOW_VALUES = "\u0000".repeat(USER_ID_WIDTH);

    /** An all-spaces value of user identifier width: the BLANK state of {@code CSSETATY.cpy}. */
    private static final String SPACES = " ".repeat(USER_ID_WIDTH);

    // -----------------------------------------------------------------------------------------
    // The field contract table and the pure helpers that drive the parameterised assertions. All
    // static members below are immutable or pure functions: there is no settable fixture, no shared
    // builder and no cached mutable state that one test could leave behind for another.
    // -----------------------------------------------------------------------------------------

    /**
     * One row of the sign-on field contract, transcribed from {@code app/cpy-bms/COSGN00.CPY}.
     *
     * @param cobolItem      the {@code ...I} data item name as the copybook spells it
     * @param sourceLine     the line of {@code app/cpy-bms/COSGN00.CPY} that declares it
     * @param pictureClause  the declared PICTURE, verbatim
     * @param width          the declared width in bytes
     * @param componentName  the {@link SignOnRequest} record component that carries it
     * @param widthConstant  the public width constant {@link SignOnRequest} publishes for it
     * @param inboundBounded whether the component carries an inbound {@link Size} constraint
     */
    private record FieldContract(
            String cobolItem,
            int sourceLine,
            String pictureClause,
            int width,
            String componentName,
            int widthConstant,
            boolean inboundBounded) {

        @Override
        public String toString() {
            return cobolItem + " " + pictureClause + " at app/cpy-bms/COSGN00.CPY:" + sourceLine
                    + " -> " + componentName;
        }
    }

    /**
     * The eleven rows of the sign-on field contract, in copybook declaration order.
     *
     * <p>A pure factory rather than a static collection so that no test can mutate what another test
     * sees.
     *
     * @return the eleven field contracts, ordered exactly as {@code 01 COSGN0AI} declares them
     */
    private static Stream<FieldContract> fieldContracts() {
        return Stream.of(
                new FieldContract("TRNNAMEI", 24, "PIC X(4)", TRANSACTION_NAME_WIDTH,
                        "transactionName", SignOnRequest.TRANSACTION_NAME_MAX_LENGTH, true),
                new FieldContract("TITLE01I", 30, "PIC X(40)", TITLE01_WIDTH,
                        "title01", SignOnRequest.TITLE01_MAX_LENGTH, true),
                new FieldContract("CURDATEI", 36, "PIC X(8)", CURRENT_DATE_WIDTH,
                        "currentDate", SignOnRequest.CURRENT_DATE_MAX_LENGTH, true),
                new FieldContract("PGMNAMEI", 42, "PIC X(8)", PROGRAM_NAME_WIDTH,
                        "programName", SignOnRequest.PROGRAM_NAME_MAX_LENGTH, true),
                new FieldContract("TITLE02I", 48, "PIC X(40)", TITLE02_WIDTH,
                        "title02", SignOnRequest.TITLE02_MAX_LENGTH, true),
                new FieldContract("CURTIMEI", 54, "PIC X(9)", CURRENT_TIME_WIDTH,
                        "currentTime", SignOnRequest.CURRENT_TIME_MAX_LENGTH, true),
                new FieldContract("APPLIDI", 60, "PIC X(8)", APPLICATION_ID_WIDTH,
                        "applicationId", SignOnRequest.APPLICATION_ID_MAX_LENGTH, true),
                new FieldContract("SYSIDI", 66, "PIC X(8)", SYSTEM_ID_WIDTH,
                        "systemId", SignOnRequest.SYSTEM_ID_MAX_LENGTH, true),
                new FieldContract("USERIDI", 72, "PIC X(8)", USER_ID_WIDTH,
                        "userId", SignOnRequest.USER_ID_MAX_LENGTH, true),
                new FieldContract("PASSWDI", 78, "PIC X(8)", CREDENTIAL_WIDTH,
                        "password", SignOnRequest.PASSWORD_MAX_LENGTH, true),
                new FieldContract("ERRMSGI", 84, "PIC X(78)", ERROR_MESSAGE_WIDTH,
                        "errorMessage", SignOnRequest.ERROR_MESSAGE_MAX_LENGTH, false));
    }

    /**
     * The subset of the contract that a client populates and that therefore carries an inbound
     * bound.
     *
     * @return the ten client supplied field contracts
     */
    private static Stream<FieldContract> inboundBoundedFieldContracts() {
        return fieldContracts().filter(FieldContract::inboundBounded);
    }

    /**
     * The six header field contracts that recur, by name, across all seventeen symbolic maps.
     *
     * @return the six recurring header field contracts, in declaration order
     */
    private static Stream<FieldContract> recurringHeaderFieldContracts() {
        return fieldContracts().limit(6);
    }

    /**
     * A fully populated, entirely valid request. Every component sits within its declared width.
     *
     * @return a valid sign-on request built only from this class's deterministic fixtures
     */
    private static SignOnRequest baseline() {
        return new SignOnRequest(TRANSACTION_NAME, TITLE01, HEADER_DATE, PROGRAM_NAME, TITLE02,
                HEADER_TIME_PADDED, APPLICATION_ID, SYSTEM_ID, SYNTHETIC_USER_ID,
                SYNTHETIC_CREDENTIAL, ERROR_MESSAGE);
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
     * @param componentName the record component to replace, spelled as {@link SignOnRequest} declares
     *                      it
     * @param value         the replacement value, which may be {@code null}
     * @return a request identical to {@link #baseline()} except for the named component
     * @throws IllegalArgumentException if {@code componentName} is not a component of
     *                                  {@link SignOnRequest}; the message names the component and
     *                                  never a value, because one of the components is a credential
     */
    private static SignOnRequest baselineWith(final String componentName, final String value) {
        return switch (componentName) {
            case "transactionName" -> new SignOnRequest(value, TITLE01, HEADER_DATE, PROGRAM_NAME,
                    TITLE02, HEADER_TIME_PADDED, APPLICATION_ID, SYSTEM_ID, SYNTHETIC_USER_ID,
                    SYNTHETIC_CREDENTIAL, ERROR_MESSAGE);
            case "title01" -> new SignOnRequest(TRANSACTION_NAME, value, HEADER_DATE, PROGRAM_NAME,
                    TITLE02, HEADER_TIME_PADDED, APPLICATION_ID, SYSTEM_ID, SYNTHETIC_USER_ID,
                    SYNTHETIC_CREDENTIAL, ERROR_MESSAGE);
            case "currentDate" -> new SignOnRequest(TRANSACTION_NAME, TITLE01, value, PROGRAM_NAME,
                    TITLE02, HEADER_TIME_PADDED, APPLICATION_ID, SYSTEM_ID, SYNTHETIC_USER_ID,
                    SYNTHETIC_CREDENTIAL, ERROR_MESSAGE);
            case "programName" -> new SignOnRequest(TRANSACTION_NAME, TITLE01, HEADER_DATE, value,
                    TITLE02, HEADER_TIME_PADDED, APPLICATION_ID, SYSTEM_ID, SYNTHETIC_USER_ID,
                    SYNTHETIC_CREDENTIAL, ERROR_MESSAGE);
            case "title02" -> new SignOnRequest(TRANSACTION_NAME, TITLE01, HEADER_DATE, PROGRAM_NAME,
                    value, HEADER_TIME_PADDED, APPLICATION_ID, SYSTEM_ID, SYNTHETIC_USER_ID,
                    SYNTHETIC_CREDENTIAL, ERROR_MESSAGE);
            case "currentTime" -> new SignOnRequest(TRANSACTION_NAME, TITLE01, HEADER_DATE,
                    PROGRAM_NAME, TITLE02, value, APPLICATION_ID, SYSTEM_ID, SYNTHETIC_USER_ID,
                    SYNTHETIC_CREDENTIAL, ERROR_MESSAGE);
            case "applicationId" -> new SignOnRequest(TRANSACTION_NAME, TITLE01, HEADER_DATE,
                    PROGRAM_NAME, TITLE02, HEADER_TIME_PADDED, value, SYSTEM_ID, SYNTHETIC_USER_ID,
                    SYNTHETIC_CREDENTIAL, ERROR_MESSAGE);
            case "systemId" -> new SignOnRequest(TRANSACTION_NAME, TITLE01, HEADER_DATE,
                    PROGRAM_NAME, TITLE02, HEADER_TIME_PADDED, APPLICATION_ID, value,
                    SYNTHETIC_USER_ID, SYNTHETIC_CREDENTIAL, ERROR_MESSAGE);
            case "userId" -> new SignOnRequest(TRANSACTION_NAME, TITLE01, HEADER_DATE, PROGRAM_NAME,
                    TITLE02, HEADER_TIME_PADDED, APPLICATION_ID, SYSTEM_ID, value,
                    SYNTHETIC_CREDENTIAL, ERROR_MESSAGE);
            case "password" -> new SignOnRequest(TRANSACTION_NAME, TITLE01, HEADER_DATE,
                    PROGRAM_NAME, TITLE02, HEADER_TIME_PADDED, APPLICATION_ID, SYSTEM_ID,
                    SYNTHETIC_USER_ID, value, ERROR_MESSAGE);
            case "errorMessage" -> new SignOnRequest(TRANSACTION_NAME, TITLE01, HEADER_DATE,
                    PROGRAM_NAME, TITLE02, HEADER_TIME_PADDED, APPLICATION_ID, SYSTEM_ID,
                    SYNTHETIC_USER_ID, SYNTHETIC_CREDENTIAL, value);
            default -> throw new IllegalArgumentException(
                    "not a component of SignOnRequest: " + componentName);
        };
    }

    /**
     * Reads a component's value back through its canonical accessor.
     *
     * <p>Uses an explicit {@code switch} for the same reason {@link #baselineWith(String, String)}
     * does: no reflective invocation, and a renamed component becomes a compile error.
     *
     * @param request       the request to read
     * @param componentName the record component to read
     * @return the component's value, possibly {@code null}
     * @throws IllegalArgumentException if {@code componentName} is not a component of
     *                                  {@link SignOnRequest}
     */
    private static String componentOf(final SignOnRequest request, final String componentName) {
        return switch (componentName) {
            case "transactionName" -> request.transactionName();
            case "title01" -> request.title01();
            case "currentDate" -> request.currentDate();
            case "programName" -> request.programName();
            case "title02" -> request.title02();
            case "currentTime" -> request.currentTime();
            case "applicationId" -> request.applicationId();
            case "systemId" -> request.systemId();
            case "userId" -> request.userId();
            case "password" -> request.password();
            case "errorMessage" -> request.errorMessage();
            default -> throw new IllegalArgumentException(
                    "not a component of SignOnRequest: " + componentName);
        };
    }

    /**
     * Validates a request against a freshly built default {@link Validator} and closes the factory.
     *
     * <p>Building the factory per call rather than once per class is deliberate: it keeps this suite
     * free of shared mutable state and of an unclosed resource, and the cost - a few tens of
     * milliseconds after the provider's classes are loaded - is not worth trading that away for.
     *
     * @param request the request to validate; may contain {@code null} components
     * @return the constraint violations, empty when the request satisfies every declared bound
     */
    private static Set<ConstraintViolation<SignOnRequest>> violationsOf(final SignOnRequest request) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            final Validator validator = factory.getValidator();
            return validator.validate(request);
        }
    }

    /**
     * Reads an annotation from the <em>field</em> a record component generates.
     *
     * <p>Reading it from the {@link RecordComponent} itself does not work here and the reason is
     * worth stating: neither {@link Size} nor {@link JsonProperty} lists
     * {@code ElementType.RECORD_COMPONENT} among its targets, so although both are written on the
     * component, neither is <em>directly present</em> on it. Java propagates each to the applicable
     * declarations instead - the private field, the accessor and the constructor parameter - which is
     * where they are legible, and which is also where Bean Validation and Jackson read them.
     *
     * @param componentName   the record component whose generated field should be inspected
     * @param annotationType  the annotation to look for
     * @param <A>             the annotation type
     * @return the annotation, or {@code null} when the component does not carry it
     * @throws AssertionError if {@link SignOnRequest} declares no such field, which would mean the
     *                        component was renamed
     */
    private static <A extends java.lang.annotation.Annotation> A fieldAnnotation(
            final String componentName, final Class<A> annotationType) {
        try {
            final Field field = SignOnRequest.class.getDeclaredField(componentName);
            return field.getAnnotation(annotationType);
        } catch (final NoSuchFieldException cause) {
            throw new AssertionError(
                    "SignOnRequest declares no field for component " + componentName, cause);
        }
    }

    /**
     * The record component names of {@link SignOnRequest}, in declaration order.
     *
     * @return the eleven component names
     */
    private static List<String> declaredComponentNames() {
        return Arrays.stream(SignOnRequest.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }

    // =========================================================================================
    // 1. THE FIELD CONTRACT - app/cpy-bms/COSGN00.CPY, input group 01 COSGN0AI
    // =========================================================================================

    @Test
    @DisplayName("1.1 exposes exactly the eleven input data items of group COSGN0AI, no more, no fewer")
    void exposesExactlyElevenComponents() {
        assertThat(SignOnRequest.class.isRecord())
                .as("SignOnRequest must remain a record so that its component list is the field contract")
                .isTrue();
        assertThat(SignOnRequest.class.getRecordComponents())
                .as("app/cpy-bms/COSGN00.CPY declares %d input data items between :17 and :84",
                        SIGN_ON_INPUT_FIELD_COUNT)
                .hasSize(SIGN_ON_INPUT_FIELD_COUNT);
        assertThat(fieldContracts())
                .as("the transcribed contract table must itself carry one row per input data item")
                .hasSize(SIGN_ON_INPUT_FIELD_COUNT);
    }

    @Test
    @DisplayName("1.2 declares its components in copybook order, so ordinal position is preserved")
    void declaresComponentsInSymbolicMapOrder() {
        final List<String> expected = fieldContracts().map(FieldContract::componentName).toList();

        assertThat(declaredComponentNames())
                .as("component order must track the declaration order of 01 COSGN0AI")
                .containsExactlyElementsOf(expected);
    }

    @Test
    @DisplayName("1.3 types every component as String, because every input data item is PIC X")
    void typesEveryComponentAsString() {
        assertThat(SignOnRequest.class.getRecordComponents())
                .allSatisfy(component -> assertThat(component.getType())
                        .as("component %s carries a PIC X item and must stay a String",
                                component.getName())
                        .isEqualTo(String.class));
    }

    @ParameterizedTest(name = "1.4 [{index}] {0}")
    @MethodSource("fieldContracts")
    @DisplayName("1.4 publishes a width constant equal to the declared PICTURE width, byte exactly")
    void publishesTheDeclaredWidthAsAConstant(final FieldContract contract) {
        assertThat(contract.widthConstant())
                .as("%s is declared %s at app/cpy-bms/COSGN00.CPY:%d, so component %s must be bounded"
                                + " at %d - neither truncated nor widened",
                        contract.cobolItem(), contract.pictureClause(), contract.sourceLine(),
                        contract.componentName(), contract.width())
                .isEqualTo(contract.width());
    }

    @ParameterizedTest(name = "1.5 [{index}] {0}")
    @MethodSource("inboundBoundedFieldContracts")
    @DisplayName("1.5 bounds every client supplied component at its declared width and no tighter")
    void boundsEveryClientSuppliedComponentAtItsDeclaredWidth(final FieldContract contract) {
        final Size size = fieldAnnotation(contract.componentName(), Size.class);

        assertThat(size)
                .as("component %s carries client input and must declare a Size bound",
                        contract.componentName())
                .isNotNull();
        assertThat(size.max())
                .as("the Size maximum for %s must equal the %s width of %s",
                        contract.componentName(), contract.pictureClause(), contract.cobolItem())
                .isEqualTo(contract.width());
        assertThat(size.min())
                .as("the source imposes no minimum length on %s, so neither may this type",
                        contract.componentName())
                .isZero();
    }

    @Test
    @DisplayName("1.6 records the errorMessage width but leaves it unbounded inbound, as the program"
            + " writes it")
    void recordsTheErrorMessageWidthWithoutBindingItInbound() {
        assertThat(SignOnRequest.ERROR_MESSAGE_MAX_LENGTH)
                .as("ERRMSGI is declared PIC X(78) at app/cpy-bms/COSGN00.CPY:84")
                .isEqualTo(ERROR_MESSAGE_WIDTH);
        assertThat(fieldAnnotation("errorMessage", Size.class))
                .as("errorMessage is populated by the program, not the terminal, so an inbound bound"
                        + " would reject payloads the source accepts")
                .isNull();
        assertThat(violationsOf(baselineWith("errorMessage", "x".repeat(ERROR_MESSAGE_WIDTH * 2))))
                .as("an over-width errorMessage must not be reported as a client error")
                .isEmpty();
    }

    @Test
    @DisplayName("1.7 MEDIUM: the corpus carries 441 input fields, not 460; COSGN00 contributes 11")
    void signOnContributesElevenOfTheCorpusInputFieldBudget() {
        assertThat(INPUT_FIELDS_PER_MAP)
                .as("app/cpy-bms holds one symbolic map per BMS mapset in app/bms")
                .hasSize(SYMBOLIC_MAP_COUNT);
        assertThat(Arrays.stream(INPUT_FIELDS_PER_MAP).sum())
                .as("counted two agreeing ways - by COMP PIC S9(4) length items and by data items -"
                        + " the corpus total is %d, correcting the specification's 460 and its own"
                        + " table's 440 (COACTVW contributes 37: app/cpy-bms/COACTVW.CPY:60 declares"
                        + " ACCTSIDI PIC 99999999999 in repeated-nine form)",
                        CORPUS_INPUT_FIELD_COUNT)
                .isEqualTo(CORPUS_INPUT_FIELD_COUNT);
        assertThat(INPUT_FIELDS_PER_MAP[SIGN_ON_MAP_INDEX])
                .as("COSGN00's own contribution to the corpus budget")
                .isEqualTo(SIGN_ON_INPUT_FIELD_COUNT);
    }

    // =========================================================================================
    // 2. THE HEADER CONTRACT - the nine byte CURTIMEI, and why no shared header type may exist
    // =========================================================================================

    @Test
    @DisplayName("2.1 MEDIUM: bounds currentTime at nine bytes, the corpus's only nine byte CURTIMEI")
    void signOnDeclaresTheOnlyNineByteHeaderTimeInTheCorpus() {
        assertThat(SignOnRequest.CURRENT_TIME_MAX_LENGTH)
                .as("app/cpy-bms/COSGN00.CPY:54 declares CURTIMEI PIC X(9)")
                .isEqualTo(CURRENT_TIME_WIDTH);
        assertThat(SignOnRequest.CURRENT_TIME_MAX_LENGTH)
                .as("the other sixteen symbolic maps declare CURTIMEI PIC X(8) at their own line 54,"
                        + " so this map must not be normalised to %d; the specification's claim that"
                        + " CURTIME is universally X(9) inverts the outlier - remediation: X(9) on"
                        + " COSGN00 alone", SIBLING_MAP_CURRENT_TIME_WIDTH)
                .isNotEqualTo(SIBLING_MAP_CURRENT_TIME_WIDTH);
        assertThat(SignOnRequest.CURRENT_TIME_MAX_LENGTH - SIBLING_MAP_CURRENT_TIME_WIDTH)
                .as("the divergence is exactly one byte, which is what makes it so easy to lose")
                .isOne();
    }

    @ParameterizedTest(name = "2.2 [{index}] {0}")
    @MethodSource("recurringHeaderFieldContracts")
    @DisplayName("2.2 keeps all six recurring header widths at their declared values")
    void keepsEveryRecurringHeaderWidthAtItsDeclaredValue(final FieldContract contract) {
        assertThat(contract.widthConstant())
                .as("%s recurs on all seventeen maps; on COSGN00 it is declared %s at :%d",
                        contract.cobolItem(), contract.pictureClause(), contract.sourceLine())
                .isEqualTo(contract.width());
    }

    @Test
    @DisplayName("2.3 keeps the five uniform header widths at the values every map shares")
    void keepsTheFiveUniformHeaderWidthsUnchanged() {
        assertThat(SignOnRequest.TRANSACTION_NAME_MAX_LENGTH).isEqualTo(TRANSACTION_NAME_WIDTH);
        assertThat(SignOnRequest.TITLE01_MAX_LENGTH).isEqualTo(TITLE01_WIDTH);
        assertThat(SignOnRequest.CURRENT_DATE_MAX_LENGTH).isEqualTo(CURRENT_DATE_WIDTH);
        assertThat(SignOnRequest.PROGRAM_NAME_MAX_LENGTH).isEqualTo(PROGRAM_NAME_WIDTH);
        assertThat(SignOnRequest.TITLE02_MAX_LENGTH).isEqualTo(TITLE02_WIDTH);
    }

    @Test
    @DisplayName("2.4 HIGH: introduces no shared header supertype, which would force one CURTIME width")
    void noSharedHeaderSupertypeIsIntroduced() {
        assertThat(SignOnRequest.class.getSuperclass())
                .as("a header base class would have to fix CURTIME at a single width and so would"
                        + " corrupt either this map at nine bytes or the other sixteen at eight")
                .isEqualTo(Record.class);
        assertThat(SignOnRequest.class.getInterfaces())
                .as("no shared header interface or mixin may be implemented, for the same reason")
                .isEmpty();
    }

    @ParameterizedTest(name = "2.5 [{index}] {0}")
    @MethodSource("recurringHeaderFieldContracts")
    @DisplayName("2.5 declares each recurring header field on itself rather than inheriting it")
    void declaresEveryRecurringHeaderFieldInline(final FieldContract contract) {
        final List<String> declaredFields = Arrays.stream(SignOnRequest.class.getDeclaredFields())
                .map(Field::getName)
                .toList();

        assertThat(declaredFields)
                .as("header field %s must be declared by SignOnRequest itself, not inherited from a"
                        + " deduplicated header type", contract.componentName())
                .contains(contract.componentName());
    }

    @Test
    @DisplayName("2.6 accommodates the eight character HH:MM:SS rendering plus its one space fill")
    void theNineByteHeaderTimeHoldsEightCharactersAndOneSpaceFill() {
        assertThat(HEADER_TIME)
                .as("app/cpy/CSDAT01Y.cpy:L36-L41 lays WS-CURTIME-HH-MM-SS out as 9(02) ':' 9(02)"
                        + " ':' 9(02), which is eight characters")
                .hasSize(SIBLING_MAP_CURRENT_TIME_WIDTH);
        assertThat(HEADER_TIME_PADDED)
                .as("app/cbl/COSGN00C.cbl:L196 moves those eight characters into the nine byte"
                        + " CURTIMEO, and a COBOL alphanumeric move space fills to the receiving"
                        + " width, so byte nine is a space by construction")
                .hasSize(CURRENT_TIME_WIDTH)
                .endsWith(" ");
        assertThat(violationsOf(baseline()))
                .as("the space filled nine byte value must satisfy the declared bound exactly")
                .isEmpty();
        assertThat(baseline().currentTime())
                .as("the trailing space is part of the fixed width value and must survive verbatim")
                .isEqualTo(HEADER_TIME_PADDED);
    }

    @Test
    @DisplayName("2.7 derives its header date and time from the injected fixed clock, never the wall"
            + " clock")
    void derivesHeaderDateAndTimeFromTheInjectedFixedClock() {
        final Clock clock = FixedClockProvider.canonicalClock();
        final String sanctionedRendering = FixedClockProvider.onlineTimestamp(clock);

        assertThat(sanctionedRendering)
                .as("FixedClockProvider is the only sanctioned time source for this tier")
                .hasSize(FixedClockProvider.TIMESTAMP_LENGTH)
                .isEqualTo(FixedClockProvider.CANONICAL_ONLINE_TIMESTAMP);
        assertThat(sanctionedRendering)
                .as("the header time this suite formats must agree with the sanctioned rendering, so"
                        + " that the two cannot drift apart")
                .contains(HEADER_TIME);
        assertThat(HEADER_DATE)
                .as("app/cpy/CSDAT01Y.cpy:L30-L35 lays WS-CURDATE-MM-DD-YY out as MM/DD/YY, exactly"
                        + " filling CURDATEI PIC X(8)")
                .hasSize(CURRENT_DATE_WIDTH)
                .isEqualTo("06/10/22");
    }

    // =========================================================================================
    // 3. SIGN-ON SEMANTICS - the payload transports; app/cbl/COSGN00C.cbl decides
    // =========================================================================================

    @Test
    @DisplayName("3.1 HIGH: transports the user identifier without folding case, leaving the fold to"
            + " the service")
    void transportsTheUserIdentifierWithoutFoldingCase() {
        final SignOnRequest request = baselineWith("userId", SYNTHETIC_USER_ID);

        assertThat(request.userId())
                .as("app/cbl/COSGN00C.cbl:L132-L134 applies FUNCTION UPPER-CASE to USERIDI inside the"
                        + " program, on the raw map field, so the payload must arrive unfolded")
                .isEqualTo(SYNTHETIC_USER_ID);
        assertThat(request.userId())
                .as("a payload that folded case on ingest would make the service's own fold"
                        + " unobservable and would silently alter what the caller submitted")
                .isNotEqualTo(SYNTHETIC_USER_ID.toUpperCase(Locale.ROOT));
    }

    @Test
    @DisplayName("3.2 HIGH: transports the credential without folding case either - both are folded"
            + " by the program")
    void transportsTheCredentialWithoutFoldingCase() {
        final SignOnRequest request = baseline();

        assertThat(request.password())
                .as("app/cbl/COSGN00C.cbl:L135-L136 applies FUNCTION UPPER-CASE to PASSWDI as well as"
                        + " to USERIDI - the credential is folded too, which is the easy half of this"
                        + " contract to overlook - before the comparison at :L223")
                .isEqualTo(SYNTHETIC_CREDENTIAL);
        assertThat(request.password())
                .as("the payload must not pre-empt the program's fold")
                .isNotEqualTo(SYNTHETIC_CREDENTIAL.toUpperCase(Locale.ROOT));
        assertThat(request.password())
                .as("nor may it fold the other way")
                .isNotEqualTo(SYNTHETIC_CREDENTIAL.toLowerCase(Locale.ROOT));
    }

    @ParameterizedTest(name = "3.3 [{index}] {0}")
    @MethodSource("fieldContracts")
    @DisplayName("3.3 trims nothing on ingest, so surrounding whitespace reaches the service intact")
    void trimsNothingOnIngest(final FieldContract contract) {
        final String surrounded = " a ";
        final SignOnRequest request = baselineWith(contract.componentName(), surrounded);

        assertThat(componentOf(request, contract.componentName()))
                .as("component %s must carry leading and trailing whitespace through untouched;"
                        + " app/cbl/COSGN00C.cbl:L118 and :L123 test the raw field for SPACES, so"
                        + " trimming here would change which payloads the program treats as blank",
                        contract.componentName())
                .isEqualTo(surrounded);
    }

    @Test
    @DisplayName("3.4 agrees with the legacy store widths, which corroborate the screen widths")
    void agreesWithTheLegacySecurityStoreWidths() {
        assertThat(SignOnRequest.USER_ID_MAX_LENGTH)
                .as("SEC-USR-ID is declared PIC X(08) at app/cpy/CSUSR01Y.cpy:L18, and"
                        + " app/cbl/COSGN00C.cbl:L45 declares WS-USER-ID PIC X(08) to receive it")
                .isEqualTo(USER_ID_WIDTH);
        assertThat(SignOnRequest.PASSWORD_MAX_LENGTH)
                .as("SEC-USR-PWD is declared PIC X(08) at app/cpy/CSUSR01Y.cpy:L21, and"
                        + " app/cbl/COSGN00C.cbl:L46 declares WS-USER-PWD PIC X(08) to receive it")
                .isEqualTo(CREDENTIAL_WIDTH);
        assertThat(SignOnRequest.USER_ID_MAX_LENGTH)
                .as("screen and store agree, so no widening or truncation happens at the boundary")
                .isEqualTo(SignOnRequest.PASSWORD_MAX_LENGTH);
    }

    @Test
    @DisplayName("3.5 omits the signed-on user type, which the store supplies and the response carries")
    void omitsTheUserTypeFromTheRequestContract() {
        final List<String> componentNames = declaredComponentNames();

        assertThat(componentNames)
                .as("CDEMO-USER-TYPE PIC X(01) with 88 CDEMO-USRTYP-ADMIN VALUE 'A' and"
                        + " 88 CDEMO-USRTYP-USER VALUE 'U' is declared at"
                        + " app/cpy/COCOM01Y.cpy:L26-L28, but app/cbl/COSGN00C.cbl:L227 moves it from"
                        + " SEC-USR-TYPE in the security record - the client never supplies it, and"
                        + " the routing it drives at :L230 is a response concern")
                .doesNotContain("userType", "cdemoUserType", "securityUserType", "userTypeCode", "role");
        assertThat(componentNames)
                .as("nor may the pseudo-conversational re-entry flag CDEMO-PGM-CONTEXT of"
                        + " app/cpy/COCOM01Y.cpy:L29-L31 appear: stateless request handling has no"
                        + " counterpart for it")
                .doesNotContain("pgmContext", "programContext", "reenter", "lastMap", "lastMapset");
        assertThat(componentNames)
                .as("the eleven components are the whole contract")
                .hasSize(SIGN_ON_INPUT_FIELD_COUNT);
    }

    // =========================================================================================
    // 4. CREDENTIAL CONTAINMENT - the presented password leaves this type through no path at all
    // =========================================================================================

    @Test
    @DisplayName("4.1 BLOCKER: renders no credential value through toString")
    void toStringRendersNoCredentialValue() {
        final String rendered = baseline().toString();

        assertThat(rendered)
                .as("the submitted credential must not appear in any diagnostic rendering; a record's"
                        + " implicit toString prints every component, so the override is the defence")
                .doesNotContain(SYNTHETIC_CREDENTIAL);
    }

    @Test
    @DisplayName("4.2 BLOCKER: names no credential field and emits no mask or length through toString")
    void toStringNamesNoCredentialFieldAndEmitsNoMask() {
        final String rendered = baseline().toString();

        assertThat(rendered)
                .as("not even the field name may appear, under any spelling")
                .doesNotContainIgnoringCase("password")
                .doesNotContainIgnoringCase("passwd")
                .doesNotContainIgnoringCase("secret")
                .doesNotContainIgnoringCase("credential");
        assertThat(rendered)
                .as("a mask still discloses that a credential was presented, and a length discloses"
                        + " more than that, so neither is emitted in its place")
                .doesNotContain("***", "REDACTED", "[hidden]", "<masked>")
                .doesNotContain(String.valueOf(SYNTHETIC_CREDENTIAL.length()));
    }

    @Test
    @DisplayName("4.3 renders exactly the three non-credential fields it documents, and nothing else")
    void toStringRendersExactlyTheThreeDocumentedFields() {
        assertThat(baseline().toString())
                .isEqualTo("SignOnRequest[userId=" + SYNTHETIC_USER_ID
                        + ", transactionName=" + TRANSACTION_NAME
                        + ", programName=" + PROGRAM_NAME + "]");
    }

    @Test
    @DisplayName("4.4 BLOCKER: omits both the credential key and its value when serialised outward")
    void serialisedFormOmitsBothTheCredentialKeyAndItsValue() throws JsonProcessingException {
        final String json = new ObjectMapper().writeValueAsString(baseline());

        assertThat(json)
                .as("the credential must not reach a response body, an error payload or any other"
                        + " serialised rendering of this type")
                .doesNotContain(SYNTHETIC_CREDENTIAL)
                .doesNotContainIgnoringCase("password");
    }

    @Test
    @DisplayName("4.5 emits the other ten components and only those, so nothing else was hidden by"
            + " accident")
    void serialisedFormEmitsTheOtherTenComponentsAndOnlyThose() throws JsonProcessingException {
        final ObjectMapper mapper = new ObjectMapper();
        final Map<String, Object> emitted = mapper.readValue(
                mapper.writeValueAsString(baseline()), new TypeReference<Map<String, Object>>() { });
        final String[] expected = fieldContracts()
                .map(FieldContract::componentName)
                .filter(name -> !"password".equals(name))
                .toArray(String[]::new);

        assertThat(emitted)
                .as("write-only must suppress the credential alone; suppressing a header field too"
                        + " would silently shrink the response contract")
                .containsOnlyKeys(expected)
                .hasSize(SIGN_ON_INPUT_FIELD_COUNT - 1);
    }

    @Test
    @DisplayName("4.6 accepts the credential inbound, because write-only means write, not forbidden")
    void acceptsTheCredentialOnDeserialisation() throws JsonProcessingException {
        final String body = "{\"userId\":\"" + SYNTHETIC_USER_ID
                + "\",\"password\":\"" + SYNTHETIC_CREDENTIAL
                + "\",\"transactionName\":\"" + TRANSACTION_NAME + "\"}";

        final SignOnRequest deserialised = new ObjectMapper().readValue(body, SignOnRequest.class);

        assertThat(deserialised.password())
                .as("the sign-on payload would be useless if the credential were rejected inbound")
                .isEqualTo(SYNTHETIC_CREDENTIAL);
        assertThat(deserialised.userId()).isEqualTo(SYNTHETIC_USER_ID);
        assertThat(deserialised.title01())
                .as("a component absent from the body must deserialise to null, not to an empty"
                        + " string, so that the tri-state survives the JSON boundary")
                .isNull();
    }

    @Test
    @DisplayName("4.7 loses the credential across a serialise-then-deserialise round trip, and keeps"
            + " the rest")
    void losesOnlyTheCredentialAcrossAJsonRoundTrip() throws JsonProcessingException {
        final ObjectMapper mapper = new ObjectMapper();
        final SignOnRequest original = baseline();

        final SignOnRequest roundTripped =
                mapper.readValue(mapper.writeValueAsString(original), SignOnRequest.class);

        assertThat(roundTripped.password())
                .as("the credential was never emitted, so it cannot come back - this is the round"
                        + " trip proof of the write-only posture")
                .isNull();
        assertThat(roundTripped)
                .as("every other component must survive the round trip untouched")
                .isEqualTo(baselineWith("password", null));
    }

    @ParameterizedTest(name = "4.8 [{index}] {0}")
    @MethodSource("fieldContracts")
    @DisplayName("4.8 marks the credential component write-only and marks no other component so")
    void marksOnlyTheCredentialComponentWriteOnly(final FieldContract contract) {
        final JsonProperty jsonProperty = fieldAnnotation(contract.componentName(), JsonProperty.class);
        final boolean isCredential = "password".equals(contract.componentName());

        if (isCredential) {
            assertThat(jsonProperty)
                    .as("the credential component must carry an explicit access mode; without it a"
                            + " record's accessor is serialised like any other")
                    .isNotNull();
            assertThat(jsonProperty.access())
                    .as("write-only is what accepts the credential inbound while never emitting it")
                    .isEqualTo(JsonProperty.Access.WRITE_ONLY);
        } else {
            assertThat(jsonProperty == null || jsonProperty.access() == JsonProperty.Access.AUTO)
                    .as("component %s carries no credential and must not have its access mode"
                            + " narrowed, which would quietly drop it from the response",
                            contract.componentName())
                    .isTrue();
        }
    }

    @Test
    @DisplayName("4.9 is not Java serialisable, so no credential can travel in an opaque byte stream")
    void isNotJavaSerialisable() {
        assertThat(Serializable.class.isAssignableFrom(SignOnRequest.class))
                .as("Java serialisation of a credential bearing type is an insecure deserialisation"
                        + " risk: the credential would travel in a stream no log masking inspects."
                        + " The omission is a security decision and must not be undone")
                .isFalse();
    }

    @Test
    @DisplayName("4.10 BLOCKER: uses a synthetic credential that cannot collide with the seeded"
            + " plaintext")
    void theSyntheticCredentialCannotCollideWithTheSeededPlaintext() {
        assertThat(SYNTHETIC_CREDENTIAL)
                .as("app/jcl/DUSRSECJ.jcl:L35-L44 seeds ten users - five type 'A' and five type 'U',"
                        + " the type character at byte 57 - that share one plaintext credential of"
                        + " eight upper case letters in bytes 49 to 56. Asserting the shape mismatch"
                        + " proves this substitute cannot coincide with it without ever writing it")
                .doesNotMatch(SEEDED_CREDENTIAL_SHAPE)
                .hasSize(CREDENTIAL_WIDTH);
        assertThat(OTHER_SYNTHETIC_CREDENTIAL)
                .as("the second synthetic credential must clear the same bar")
                .doesNotMatch(SEEDED_CREDENTIAL_SHAPE)
                .hasSize(CREDENTIAL_WIDTH)
                .isNotEqualTo(SYNTHETIC_CREDENTIAL);
    }

    @Test
    @DisplayName("4.11 uses no signing key, cloud credential, bearer token or password hash as a"
            + " fixture")
    void usesNoSigningKeyCloudCredentialTokenOrHashAsAFixture() {
        for (final String fixture : List.of(SYNTHETIC_CREDENTIAL, OTHER_SYNTHETIC_CREDENTIAL,
                SYNTHETIC_USER_ID, TRANSACTION_NAME, PROGRAM_NAME, APPLICATION_ID, SYSTEM_ID)) {
            assertThat(fixture)
                    .as("no fixture may be shaped like a cloud access key identifier")
                    .doesNotMatch("^(?:AKIA|ASIA)[0-9A-Z]{16}$");
            assertThat(fixture)
                    .as("no fixture may be shaped like a BCrypt hash; hashing belongs to"
                            + " UserSecurityTest and the service tier, and strength 10 is context"
                            + " here rather than something this tier computes")
                    .doesNotMatch("^\\$2[aby]\\$\\d{2}\\$.{53}$");
            assertThat(fixture)
                    .as("no fixture may be shaped like a JSON Web Token")
                    .doesNotMatch("^[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+$");
        }
    }

    @Test
    @DisplayName("4.12 BLOCKER: reports an over-width credential without echoing its value")
    void constraintViolationsNeverEchoTheCredentialValue() {
        final String overWidth = SYNTHETIC_CREDENTIAL + "x";

        final Set<ConstraintViolation<SignOnRequest>> violations =
                violationsOf(baselineWith("password", overWidth));

        assertThat(violations)
                .as("one bound is breached, so exactly one violation is expected")
                .hasSize(1);
        assertThat(violations)
                .allSatisfy(violation -> {
                    assertThat(violation.getPropertyPath().toString())
                            .as("the violation must identify the field by name, which is how a caller"
                                    + " learns what to fix")
                            .isEqualTo("password");
                    assertThat(violation.getMessage())
                            .as("and it must not echo the offending value, because this field is a"
                                    + " credential; rendering getInvalidValue() into a response or a"
                                    + " log is the mistake this asserts against")
                            .doesNotContain(SYNTHETIC_CREDENTIAL)
                            .doesNotContain(overWidth);
                });
    }

    @Test
    @DisplayName("4.13 discloses no credential through its hash code rendering")
    void disclosesNoCredentialThroughItsHashCodeRendering() {
        assertThat(String.valueOf(baseline().hashCode()))
                .as("the credential participates in value identity, so this records that identity"
                        + " nonetheless discloses nothing: a hash code is an int and renders as digits")
                .doesNotContain(SYNTHETIC_CREDENTIAL);
    }

    // =========================================================================================
    // 5. THE TRI-STATE INPUT MODEL AND BOUNDARY CONDITIONS
    //    app/cpy/CSSETATY.cpy:L18-L27 models OK / NOT-OK / BLANK; app/cbl/COSGN00C.cbl:L118,:L123
    //    tests SPACES OR LOW-VALUES as two separate sentinels.
    // =========================================================================================

    @Test
    @DisplayName("5.1 HIGH: keeps absent, blank, low-values and present as four distinguishable states")
    void keepsAbsentBlankLowValuesAndPresentDistinguishable() {
        final SignOnRequest absent = baselineWith("userId", null);
        final SignOnRequest empty = baselineWith("userId", "");
        final SignOnRequest blank = baselineWith("userId", SPACES);
        final SignOnRequest present = baselineWith("userId", SYNTHETIC_USER_ID);

        assertThat(absent.userId())
                .as("absent must stay absent: app/cpy/CSSETATY.cpy distinguishes BLANK from NOT-OK,"
                        + " and coercing null to an empty string would erase the distinction the"
                        + " template depends on")
                .isNull();
        assertThat(empty.userId())
                .as("an empty string must stay empty and must not become null")
                .isNotNull()
                .isEmpty();
        assertThat(blank.userId())
                .as("app/cbl/COSGN00C.cbl:L118 tests the raw field for SPACES, so an all-spaces value"
                        + " must arrive as all spaces of its original length")
                .isEqualTo(SPACES)
                .hasSize(USER_ID_WIDTH);
        assertThat(List.of(empty, blank, present))
                .as("the three non-null states are mutually distinct")
                .doesNotHaveDuplicates();
        assertThat(absent)
                .as("and the absent state is distinct from every one of them")
                .isNotEqualTo(empty)
                .isNotEqualTo(blank)
                .isNotEqualTo(present);
    }

    @Test
    @DisplayName("5.2 carries COBOL LOW-VALUES through as its own state, distinct from blank and absent")
    void carriesLowValuesThroughAsItsOwnState() {
        final SignOnRequest lowValues = baselineWith("userId", LOW_VALUES);

        assertThat(lowValues.userId())
                .as("app/cbl/COSGN00C.cbl:L118 and :L123 test SPACES OR LOW-VALUES as two separate"
                        + " sentinels, so low-values is a state and not a synonym for blank")
                .isEqualTo(LOW_VALUES)
                .hasSize(USER_ID_WIDTH)
                .isNotEqualTo(SPACES);
        assertThat(lowValues.userId())
                .as("nor may it be normalised away to absent")
                .isNotNull();
        assertThat(violationsOf(lowValues))
                .as("low-values is within the declared width, so it breaches no bound; whether it is"
                        + " acceptable is the service's judgement, not this type's")
                .isEmpty();
    }

    @Test
    @DisplayName("5.3 treats neither absence nor blankness as a constraint violation")
    void treatsNeitherAbsenceNorBlanknessAsAViolation() {
        final SignOnRequest allAbsent =
                new SignOnRequest(null, null, null, null, null, null, null, null, null, null, null);
        final SignOnRequest allEmpty =
                new SignOnRequest("", "", "", "", "", "", "", "", "", "", "");

        assertThat(violationsOf(allAbsent))
                .as("a Size bound treats null as valid, which is the correct reading here: the source"
                        + " reports a missing identifier through its own message at"
                        + " app/cbl/COSGN00C.cbl:L120, not as a format error")
                .isEmpty();
        assertThat(violationsOf(allEmpty))
                .as("blankness is likewise a state the service interprets, not a bound breach")
                .isEmpty();
    }

    @ParameterizedTest(name = "5.4 [{index}] {0}")
    @MethodSource("inboundBoundedFieldContracts")
    @DisplayName("5.4 accepts a component filled to exactly its declared width")
    void acceptsAComponentFilledToExactlyItsDeclaredWidth(final FieldContract contract) {
        final String exact = "x".repeat(contract.width());

        assertThat(violationsOf(baselineWith(contract.componentName(), exact)))
                .as("%s is declared %s, so %d characters is the largest legal value and must be"
                        + " accepted", contract.cobolItem(), contract.pictureClause(), contract.width())
                .isEmpty();
    }

    @ParameterizedTest(name = "5.5 [{index}] {0}")
    @MethodSource("inboundBoundedFieldContracts")
    @DisplayName("5.5 accepts a component one character under its declared width")
    void acceptsAComponentOneCharacterUnderItsDeclaredWidth(final FieldContract contract) {
        final String oneUnder = "x".repeat(contract.width() - 1);

        assertThat(violationsOf(baselineWith(contract.componentName(), oneUnder)))
                .as("the source imposes no minimum length on %s, so a short value is legal",
                        contract.componentName())
                .isEmpty();
    }

    @ParameterizedTest(name = "5.6 [{index}] {0}")
    @MethodSource("inboundBoundedFieldContracts")
    @DisplayName("5.6 rejects a component one character over its declared width, and only that one")
    void rejectsAComponentOneCharacterOverItsDeclaredWidthAndOnlyThatOne(final FieldContract contract) {
        final String oneOver = "x".repeat(contract.width() + 1);

        final Set<ConstraintViolation<SignOnRequest>> violations =
                violationsOf(baselineWith(contract.componentName(), oneOver));

        assertThat(violations)
                .as("only %s exceeds its bound, so exactly one violation is expected - a second would"
                        + " mean the baseline itself is invalid", contract.componentName())
                .hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString())
                .as("the violation must name the offending field so a caller can act on it")
                .isEqualTo(contract.componentName());
    }

    @ParameterizedTest(name = "5.7 [{index}] {0}")
    @MethodSource("inboundBoundedFieldContracts")
    @DisplayName("5.7 preserves fixed width trailing space padding verbatim on every component")
    void preservesTrailingSpacePaddingVerbatim(final FieldContract contract) {
        final String padded = "x" + " ".repeat(contract.width() - 1);
        final SignOnRequest request = baselineWith(contract.componentName(), padded);

        assertThat(componentOf(request, contract.componentName()))
                .as("the map fields are fixed width and space padded, so %s must keep every trailing"
                        + " space: trimming would change the byte image the parity comparison reads",
                        contract.componentName())
                .isEqualTo(padded)
                .hasSize(contract.width());
        assertThat(violationsOf(request))
                .as("a fully padded value sits exactly on the bound and must be accepted")
                .isEmpty();
    }

    @Test
    @DisplayName("5.8 transports control characters rather than sanitising them, since the source"
            + " imposes no character class")
    void transportsControlCharactersRatherThanSanitisingThem() {
        final String withControls = "a\tb\nc\u0000";
        final SignOnRequest request = baselineWith("userId", withControls);

        assertThat(request.userId())
                .as("the source applies no character class rule to USERIDI, so rejecting or rewriting"
                        + " these bytes here would reject payloads the legacy screen accepts")
                .isEqualTo(withControls);
        assertThat(violationsOf(request))
                .as("the value is within the declared width, so it breaches no bound")
                .isEmpty();
    }

    @Test
    @DisplayName("5.9 accepts a mixed case, whitespace surrounded credential without alteration")
    void acceptsAMixedCaseWhitespaceSurroundedCredentialWithoutAlteration() {
        final String awkward = " aB1! ";
        final SignOnRequest request = baselineWith("password", awkward);

        assertThat(request.password())
                .as("the sign-on payload is the least trusted input in the system, and the correct"
                        + " response to an awkward credential is to transport it faithfully so the"
                        + " service can fold and compare it - not to normalise it here")
                .isEqualTo(awkward);
        assertThat(violationsOf(request))
                .as("six characters is within PASSWDI PIC X(8)")
                .isEmpty();
    }

    // =========================================================================================
    // 6. VALUE SEMANTICS
    // =========================================================================================

    @Test
    @DisplayName("6.1 defines equals as reflexive, symmetric and transitive over its components")
    void definesEqualsAsReflexiveSymmetricAndTransitive() {
        final SignOnRequest first = baseline();
        final SignOnRequest second = baseline();
        final SignOnRequest third = baseline();

        assertThat(first).isEqualTo(first);
        assertThat(first).isEqualTo(second);
        assertThat(second).isEqualTo(first);
        assertThat(second).isEqualTo(third);
        assertThat(first).isEqualTo(third);
    }

    @Test
    @DisplayName("6.2 defines equals to be null safe and type safe")
    void definesEqualsToBeNullSafeAndTypeSafe() {
        final SignOnRequest request = baseline();
        final Object notARequest = SYNTHETIC_USER_ID;

        assertThat(request.equals(null))
                .as("equals must answer false for null rather than throwing")
                .isFalse();
        assertThat(request.equals(notARequest))
                .as("equals must answer false for an unrelated type rather than throwing")
                .isFalse();
        assertThat(request.equals(request))
                .as("equals must be reflexive on the same reference")
                .isTrue();
    }

    @Test
    @DisplayName("6.3 returns a stable hash code, and the same hash code for equal instances")
    void returnsAStableHashCodeAndSharesItBetweenEqualInstances() {
        final SignOnRequest request = baseline();
        final int first = request.hashCode();

        assertThat(request.hashCode())
                .as("a hash code must not change between invocations on an unchanged value")
                .isEqualTo(first)
                .isEqualTo(request.hashCode());
        assertThat(baseline().hashCode())
                .as("equal instances must agree on their hash code")
                .isEqualTo(first);
    }

    @Test
    @DisplayName("6.4 supports equals and hashCode when every component is absent")
    void supportsEqualsAndHashCodeWhenEveryComponentIsAbsent() {
        final SignOnRequest first =
                new SignOnRequest(null, null, null, null, null, null, null, null, null, null, null);
        final SignOnRequest second =
                new SignOnRequest(null, null, null, null, null, null, null, null, null, null, null);

        assertThat(first)
                .as("an all-absent payload is reachable through JSON, so value semantics must handle it")
                .isEqualTo(second);
        assertThat(first.hashCode()).isEqualTo(second.hashCode());
        assertThat(first).isNotEqualTo(baseline());
    }

    @Test
    @DisplayName("6.5 LOW: includes the credential in value identity, which is record semantics and"
            + " discloses nothing")
    void includesTheCredentialInValueIdentity() {
        final SignOnRequest first = baseline();
        final SignOnRequest second = baselineWith("password", OTHER_SYNTHETIC_CREDENTIAL);

        assertThat(second)
                .as("two payloads differing only in the presented credential are different values."
                        + " This is the compiler generated, value based equals a record defines over"
                        + " all eleven components, and SignOnRequest documents it deliberately."
                        + " Severity Low rather than Blocker because neither equals nor hashCode"
                        + " RENDERS the credential - one returns a boolean, the other an int - so"
                        + " nothing is disclosed. Remediation: do not use instances as cache keys and"
                        + " do not identify them by hash code in diagnostics")
                .isNotEqualTo(first);
        assertThat(second.hashCode())
                .as("and their hash codes are correspondingly unlikely to coincide")
                .isNotEqualTo(first.hashCode());
        assertThat(second.toString())
                .as("while the rendering stays identical, because the rendering omits the credential -"
                        + " which is the property that actually matters")
                .isEqualTo(first.toString());
    }

    @Test
    @DisplayName("6.6 rejects an unknown component name with a message that names the field, not a value")
    void rejectsAnUnknownComponentNameWithAFieldNamingMessage() {
        final String unknown = "userTypeThatDoesNotExist";

        assertThatIllegalArgumentException()
                .as("a renamed component must fail loudly rather than silently build a wrong payload")
                .isThrownBy(() -> baselineWith(unknown, SYNTHETIC_CREDENTIAL))
                .withMessage("not a component of SignOnRequest: " + unknown)
                .withNoCause();
        assertThatIllegalArgumentException()
                .as("the reader helper must fail the same way, for the same reason")
                .isThrownBy(() -> componentOf(baseline(), unknown))
                .withMessage("not a component of SignOnRequest: " + unknown)
                .withNoCause();
    }

    @Test
    @DisplayName("6.7 raises an AssertionError that preserves its cause when a component field is"
            + " renamed")
    void raisesAnAssertionErrorPreservingItsCauseWhenAComponentFieldIsRenamed() {
        assertThatExceptionOfType(AssertionError.class)
                .as("the annotation reader must not swallow the reflective failure: a renamed field is"
                        + " the exact condition it exists to surface")
                .isThrownBy(() -> fieldAnnotation("noSuchComponent", Size.class))
                .withMessage("SignOnRequest declares no field for component noSuchComponent")
                .withCauseInstanceOf(NoSuchFieldException.class);
    }
}
