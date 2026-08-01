/*
 * ******************************************************************
 * Program     : UserCreateRequestTest.java
 * Component   : Unit test for com.cardemo.model.dto.UserCreateRequest,
 *               resident at src/test/java/com/cardemo/unit/model
 * Application : CardDemo
 * Type        : JUnit 5 unit test - pure JVM tier, no container, no
 *               Spring context, no database, no live endpoint
 * Function    : Pins the field, presence, geometry, outcome and
 *               credential contracts of the user add request payload
 *               against the BMS symbolic map and the CICS screen
 *               program it was translated from. Asserts the twelve
 *               field census and every PIC width byte exactly, the
 *               ordered first match wins presence cascade with its
 *               five message literals, the 80 onto 78 message
 *               truncation, the three write path literals including
 *               the source's own 'exist' spelling, the write only
 *               password, the raw one character user type, and the
 *               distinct absent / blank / low values states. Every
 *               legacy quirk is reproduced here, never repaired.
 * Source      : app/cpy-bms/COUSR01.CPY (12 input fields, group
 *               COUSR1AI) + app/cbl/COUSR01C.cbl +
 *               app/cpy/CSUSR01Y.cpy @ 7756d89
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

import com.cardemo.model.dto.UserCreateRequest;
import com.cardemo.model.enums.UserType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit test for {@link UserCreateRequest}, the inbound payload of the user add transaction {@code CU01}.
 *
 * <h2>What it does</h2>
 *
 * <p>This class pins the parity contract of the user add request against the frozen COBOL corpus. The
 * payload was translated field for field from the BMS symbolic map {@code app/cpy-bms/COUSR01.CPY}, whose
 * input group {@code COUSR1AI} carries exactly <strong>twelve</strong> input fields, declared at lines 24
 * ({@code TRNNAMEI X(4)}), 30 ({@code TITLE01I X(40)}), 36 ({@code CURDATEI X(8)}), 42
 * ({@code PGMNAMEI X(8)}), 48 ({@code TITLE02I X(40)}), 54 ({@code CURTIMEI X(8)}), 60
 * ({@code FNAMEI X(20)}), 66 ({@code LNAMEI X(20)}), 72 ({@code USERIDI X(8)}), 78
 * ({@code PASSWDI X(8)}), 84 ({@code USRTYPEI X(1)}) and 90 ({@code ERRMSGI X(78)}). Each field is the
 * data element of a generated quintuple - a {@code COMP PIC S9(4)} length field, an attribute byte, a
 * redefined attribute alias, four reserved bytes, then the data field - preceded by the twelve byte
 * terminal I/O area {@code FILLER PIC X(12)} at line 18.
 *
 * <p>The eight groups below correspond one to one to the eight contracts under test:
 *
 * <ol>
 *   <li><strong>Field contract.</strong> Twelve fields, every width byte exact, no shared header
 *       abstraction. {@code CURTIMEI} is {@code X(8)} on this map, and
 *       {@code app/cpy-bms/COSGN00.CPY:54} alone declares {@code X(9)}, so a shared header type would
 *       have to misrepresent one of the two.</li>
 *   <li><strong>Add versus update divergence.</strong> {@code app/cpy-bms/COUSR02.CPY} also declares
 *       twelve fields of identical widths, which makes a shared base type look attractive and is why it
 *       is wrong. The add map declares {@code USERIDI} at line 72, after the names; the update map
 *       declares {@code USRIDINI} at {@code COUSR02.CPY:60}, before them, as does the user list map at
 *       {@code app/cpy-bms/COUSR00.CPY:66}. The name and the position both differ.</li>
 *   <li><strong>Presence cascade.</strong> {@code app/cbl/COUSR01C.cbl:L115-L151} is a single
 *       {@code EVALUATE TRUE} whose branches are evaluated in declaration order and whose first match
 *       sends the screen and stops, so with every field blank the reported failure is the first name
 *       alone and never a collected set of five.</li>
 *   <li><strong>Message geometry.</strong> {@code app/cbl/COUSR01C.cbl:L38} declares
 *       {@code WS-MESSAGE PIC X(80)} and {@code :L188} moves it into a {@code PIC X(78)} screen field, a
 *       silent two byte truncation that is reproduced rather than corrected. {@code :L101} moves the
 *       shared {@code CCDA-MSG-INVALID-KEY} constant into the same work area.</li>
 *   <li><strong>Write path outcomes.</strong> {@code app/cbl/COUSR01C.cbl:L238-L274} composes the
 *       success message with {@code STRING}, collapses two CICS response codes into one duplicate
 *       branch, and carries a third message for every other failure.</li>
 *   <li><strong>Credential handling.</strong> {@code PASSWDI PIC X(8)} at
 *       {@code app/cpy-bms/COUSR01.CPY:78} means a plaintext password crosses this screen. The persisted
 *       layout {@code app/cpy/CSUSR01Y.cpy:L17-L23} is an eighty byte record whose password field is
 *       only {@code X(08)}, so the migrated BCrypt digest cannot live there and does not live here
 *       either. The ten seeded operators inline at {@code app/jcl/DUSRSECJ.jcl:L35-L44}, loaded into a
 *       cluster defined {@code KEYS(8,0)} at {@code :L65} and {@code RECORDSIZE(80,80)} at {@code :L66},
 *       share one plaintext literal; that literal appears nowhere in this file or anywhere under
 *       {@code src/}, and every credential used below is synthetic.</li>
 *   <li><strong>User type.</strong> {@code USRTYPEI PIC X(1)} has a domain of exactly two values,
 *       {@code app/cpy/COCOM01Y.cpy:L26-L28} declaring {@code CDEMO-USER-TYPE PIC X(01)} with
 *       {@code 88 CDEMO-USRTYP-ADMIN VALUE 'A'} and {@code 88 CDEMO-USRTYP-USER VALUE 'U'}.</li>
 *   <li><strong>Boundaries and the three state model.</strong> {@code app/cpy/CSSETATY.cpy} is a
 *       procedural {@code COPY ... REPLACING} template, not a data layout, and models exactly
 *       {@code OK}, {@code NOT-OK} and {@code BLANK}, its markers firing only on re-entry. The same
 *       distinction appears at message level in {@code app/cbl/COACTUPC.cbl:505-508}, where
 *       {@code CRED-LIMIT-IS-BLANK} and {@code CRED-LIMIT-IS-NOT-VALID} are two messages for two
 *       states. That file also carries the gated cross field pattern at {@code :1664-1675}, where the
 *       cross field edit runs only once both single field edits have passed - which is why no
 *       unconditional class level constraint may model it.</li>
 * </ol>
 *
 * <h2>How to run, build and test</h2>
 *
 * <p>This class lives in the pure JVM unit tier and is bound by <strong>Surefire</strong>: the root
 * {@code pom.xml} includes {@code **}{@code /*Test.java} and excludes {@code **}{@code /integration/**}
 * and {@code **}{@code /e2e/**}, so a class under {@code src/test/java/com/cardemo/unit} is collected by
 * Surefire and never by Failsafe. Moving or renaming it would silently remove it from both plugins,
 * producing a green build in which it never runs.
 *
 * <ul>
 *   <li>{@code mvn -B test} runs this class with the whole unit tier.</li>
 *   <li>{@code mvn -B test -Dtest=UserCreateRequestTest} runs this class alone.</li>
 *   <li>{@code mvn -B verify} additionally applies the JaCoCo line coverage gate.</li>
 * </ul>
 *
 * <h2>Key configurations and defaults</h2>
 *
 * <ul>
 *   <li><strong>No ambient state of any kind.</strong> No wall clock, no default locale, no default
 *       time zone, no randomness and no dependence on iteration order. Every temporal value is derived
 *       from {@link FixedClockProvider#canonicalClock()} and every format, parse and case operation
 *       passes {@link Locale#ROOT} explicitly.</li>
 *   <li><strong>No global mutable state.</strong> Every constant here is an immutable {@code String},
 *       {@code int} or {@link List#of} value, and every {@link ValidatorFactory} is opened and closed
 *       inside the test that needs it.</li>
 *   <li><strong>No mock objects.</strong> The subject is a payload with no collaborator, so Mockito
 *       would have nothing to stub and is deliberately not referenced.</li>
 *   <li><strong>Reflection is read only.</strong> It is used to introspect declared fields and their
 *       {@link Size} annotations, which is the only way to assert a declared width. Mutation goes
 *       through the real setters via an explicit switch, never through reflective invocation.</li>
 * </ul>
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <p>Each mode is classified by severity, highest first, and names the assertion that catches it.
 *
 * <ul>
 *   <li><strong>Blocker - the credential becomes readable.</strong> Adding a getter, a
 *       {@code toString} override, {@code equals}, {@code hashCode} or {@code Serializable} to the
 *       payload opens a read path to a plaintext credential. Caught by
 *       {@code noReadPathToTheCredentialExists}, {@code toStringIsTheInheritedIdentityForm},
 *       {@code equalsAndHashCodeAreNotOverridden} and {@code theTypeIsNotSerializable}.
 *       <em>Remediation:</em> remove the accessor or override; the service tier obtains the credential
 *       at the binding boundary and hashes it there.</li>
 *   <li><strong>Blocker - the seeded plaintext literal appears under {@code src/}.</strong> The ten
 *       operators at {@code app/jcl/DUSRSECJ.jcl:L35-L44} share one plaintext value, which may not
 *       appear in any source, comment, assertion message or fixture. Caught by
 *       {@code everyCredentialUsedHereIsObviouslySynthetic}. <em>Remediation:</em> substitute an
 *       obviously synthetic value carrying punctuation and mixed case.</li>
 *   <li><strong>Blocker - a parity literal is altered.</strong> Most easily by "correcting"
 *       {@code 'User ID already exist...'} at {@code app/cbl/COUSR01C.cbl:L263} to {@code exists}, or
 *       by re-casing {@code can NOT be empty}, or by replacing three dots with an ellipsis character.
 *       Caught by {@code theDuplicateLiteralKeepsTheSourcesExistSpelling} and
 *       {@code everyPresenceLiteralKeepsItsThreeDotsAndItsUpperCaseNot}. <em>Remediation:</em> restore
 *       the literal byte for byte from the cited line; the parity gates compare these strings
 *       directly.</li>
 *   <li><strong>Blocker - the three states are collapsed.</strong> Coercing absent to blank, or
 *       trimming a padded value, erases the {@code BLANK} versus {@code NOT-OK} distinction that
 *       {@code app/cpy/CSSETATY.cpy} depends on. Caught by
 *       {@code absentBlankAndLowValuesRemainDistinguishable} and
 *       {@code trailingSpacePaddingIsPreserved}. <em>Remediation:</em> store presented values verbatim
 *       and let the predicate, not the accessor, decide emptiness.</li>
 *   <li><strong>High - a shared base type is introduced with the update request.</strong> The two maps
 *       differ in both the identifier field name and the field order, so any parent, interface or mixin
 *       makes one of them factually wrong. Caught by
 *       {@code hierarchyCarriesNoSharedBaseTypeOrMixin}. <em>Remediation:</em> keep each map's fields
 *       on its own type, in its own order.</li>
 *   <li><strong>High - the five presence messages are aggregated.</strong> The source reports the
 *       first failing field only, so collecting all five changes observable behaviour even though
 *       every individual literal is still correct. Caught by
 *       {@code withEveryFieldBlankOnlyTheFirstNameIsReported} and
 *       {@code theCascadeReportsTheFirstFailingFieldInSourceOrder}. <em>Remediation:</em> evaluate the
 *       five checks in declaration order and return on the first failure.</li>
 *   <li><strong>High - the error field is widened from 78 to 80.</strong> The work area is eighty bytes
 *       and the wire field seventy eight; widening hides the truncation instead of reproducing it.
 *       Caught by {@code errorMessageIsSeventyEightAndNotEighty} and
 *       {@code aSeventyNineCharacterValueViolatesTheWidth}. <em>Remediation:</em> keep the constraint
 *       at 78 and truncate on the way in.</li>
 *   <li><strong>High - a membership check stricter than the source is invented.</strong> The cascade
 *       rejects a blank user type and nothing else, so a pattern constraint would refuse input the
 *       legacy system accepts. Caught by
 *       {@code thePayloadAddsNoMembershipCheckStricterThanTheSource}. <em>Remediation:</em> carry the
 *       raw code and report it from the service.</li>
 *   <li><strong>High - a class level constraint models a gated cross field edit,</strong> or a session
 *       or navigation field reappears on the payload. Caught by
 *       {@code noClassLevelConstraintModelsAGatedCrossFieldEdit} and
 *       {@code noSessionOrNavigationPropertyIsPresent}. <em>Remediation:</em> gate the cross field rule
 *       in the service, after the single field edits, and keep routing in the URL.</li>
 *   <li><strong>Medium - the build fails on an unused import or a raw type.</strong> The compiler runs
 *       with {@code -Xlint:all -Werror} at {@code release 25}, so any warning is fatal. In particular an
 *       {@link AutoCloseable} opened in a try with resources block must be referenced inside that
 *       block, or {@code -Xlint:try} fails the build. <em>Remediation:</em> read the first
 *       {@code [ERROR]} line; the compiler names the exact construct.</li>
 * </ul>
 *
 * <h2>Not available</h2>
 *
 * <p>Two inputs a reader might reasonably expect do not exist, and neither is invented here:
 *
 * <ul>
 *   <li><strong>A user security fixture is Not available.</strong> There is no
 *       {@code app/data/ASCII/usrsec.txt}. The ten operator rows exist only as inline
 *       {@code SYSUT1 DD *} data at {@code app/jcl/DUSRSECJ.jcl:L35-L44}, fed through
 *       {@code IEBGENER} into the cluster defined at {@code :L62-L70}. No fixture loader is therefore
 *       used by this test. To supply one, a fixture would have to be extracted from that JCL member,
 *       which is frozen, and its credential column would have to be replaced before it could be
 *       committed.</li>
 *   <li><strong>Child migrations of {@code V1__create_schema.sql} are Not available.</strong> No
 *       further schema artefacts are planned beyond the three migrations named in the plan, so none is
 *       referenced. This test is a pure JVM unit test and reaches no schema in any case.</li>
 * </ul>
 *
 * @see UserCreateRequest
 * @see UserType
 * @see FixedClockProvider
 */
@DisplayName("UserCreateRequest - app/cpy-bms/COUSR01.CPY group COUSR1AI + app/cbl/COUSR01C.cbl")
class UserCreateRequestTest {

    // ----------------------------------------------------------------------------------------------
    // Geometry taken from the frozen corpus. Every value below is a byte count proven by a locator.
    // ----------------------------------------------------------------------------------------------

    /** Input field count of group {@code COUSR1AI}, {@code app/cpy-bms/COUSR01.CPY:17-90}. */
    private static final int ADD_MAP_INPUT_FIELD_COUNT = 12;

    /** Width of {@code WS-MESSAGE PIC X(80)}, {@code app/cbl/COUSR01C.cbl:L38}. */
    private static final int WS_MESSAGE_WIDTH = 80;

    /**
     * Width of {@code ERRMSGI} at {@code app/cpy-bms/COUSR01.CPY:90} and of {@code ERRMSGO} at
     * {@code :164}. Seventy eight, not seventy nine and not eighty.
     */
    private static final int ERRMSG_WIDTH = 78;

    /** Width of {@code SEC-USR-ID PIC X(08)}, {@code app/cpy/CSUSR01Y.cpy:L18}, and the cluster key. */
    private static final int SEC_USR_ID_WIDTH = 8;

    /** Width of {@code SEC-USER-DATA}, {@code app/cpy/CSUSR01Y.cpy:L17-L23}: 8+20+20+8+1+23. */
    private static final int SEC_USER_DATA_WIDTH = 80;

    /** Width of the {@code CCDA-} message constants, {@code app/cpy/CSMSG01Y.cpy:20}. */
    private static final int CCDA_MESSAGE_WIDTH = 50;

    /**
     * COBOL {@code LOW-VALUES}: the lowest character in the collating sequence. Expressed as
     * {@link Character#MIN_VALUE} rather than as a unicode escape, because unicode escapes are resolved
     * before tokenisation and are therefore a hazard in source text.
     */
    private static final char LOW_VALUE = Character.MIN_VALUE;

    // ----------------------------------------------------------------------------------------------
    // Message literals. Reproduced byte for byte from the source, never paraphrased, never re-cased
    // and never re-punctuated: the parity gates compare these strings byte for byte. Note the
    // deliberate upper case NOT in all five presence messages and the three dot suffix on each.
    // ----------------------------------------------------------------------------------------------

    /** {@code app/cbl/COUSR01C.cbl:L120}. */
    private static final String MSG_FIRST_NAME_EMPTY = "First Name can NOT be empty...";

    /** {@code app/cbl/COUSR01C.cbl:L126}. */
    private static final String MSG_LAST_NAME_EMPTY = "Last Name can NOT be empty...";

    /** {@code app/cbl/COUSR01C.cbl:L132}. */
    private static final String MSG_USER_ID_EMPTY = "User ID can NOT be empty...";

    /** {@code app/cbl/COUSR01C.cbl:L138}. */
    private static final String MSG_PASSWD_EMPTY = "Password can NOT be empty...";

    /** {@code app/cbl/COUSR01C.cbl:L144}. */
    private static final String MSG_USER_TYPE_EMPTY = "User Type can NOT be empty...";

    /**
     * {@code app/cbl/COUSR01C.cbl:L263}, reached from both {@code WHEN DFHRESP(DUPKEY)} at {@code :L260}
     * and {@code WHEN DFHRESP(DUPREC)} at {@code :L261}. The source spells the verb {@code exist}, not
     * {@code exists}; the grammar is deliberately not corrected.
     */
    private static final String MSG_DUPLICATE_USER_ID = "User ID already exist...";

    /** {@code app/cbl/COUSR01C.cbl:L270}, the {@code WHEN OTHER} outcome. */
    private static final String MSG_UNABLE_TO_ADD = "Unable to Add User...";

    /**
     * Text of {@code CCDA-MSG-INVALID-KEY}, {@code app/cpy/CSMSG01Y.cpy:20-21}, moved into the same
     * eighty byte work area at {@code app/cbl/COUSR01C.cbl:L101}. The copybook declares
     * {@code PIC X(50)} and the literal as written is space padded; only the significant text is held
     * here and the padding is applied by {@link #cobolMove(String, int)} where it matters.
     */
    private static final String MSG_INVALID_KEY_TEXT = "Invalid key pressed. Please see below...";

    /** First {@code STRING} operand, {@code app/cbl/COUSR01C.cbl:L255}, {@code DELIMITED BY SIZE}. */
    private static final String CONFIRMATION_HEAD = "User ";

    /**
     * Third {@code STRING} operand, {@code app/cbl/COUSR01C.cbl:L257}, {@code DELIMITED BY SIZE}. Note
     * the leading space, the verb {@code added}, and the space that precedes the three dots.
     */
    private static final String CONFIRMATION_TAIL = " has been added ...";

    /**
     * The five presence messages in the exact order the {@code EVALUATE TRUE} at
     * {@code app/cbl/COUSR01C.cbl:L117} declares its branches. The order is the contract: the first
     * matching branch sends the screen and stops.
     */
    private static final List<String> CASCADE_MESSAGES_IN_SOURCE_ORDER = List.of(
            MSG_FIRST_NAME_EMPTY,
            MSG_LAST_NAME_EMPTY,
            MSG_USER_ID_EMPTY,
            MSG_PASSWD_EMPTY,
            MSG_USER_TYPE_EMPTY);

    // ----------------------------------------------------------------------------------------------
    // Synthetic test values. Rule 1 clause D forbids secrets in code, logs, tests and config, and it
    // names tests explicitly. The plaintext literal shared by the ten operators seeded at
    // app/jcl/DUSRSECJ.jcl:L35-L44 therefore appears nowhere here. These values are obviously
    // synthetic, are eight characters to match PASSWDI PIC X(8), and are never echoed into an
    // assertion message: a failing credential assertion identifies the field, never the value.
    // ----------------------------------------------------------------------------------------------

    /** Synthetic eight character credential. Not a real password and not the seeded literal. */
    private static final String SYNTHETIC_CREDENTIAL = "Zq7#Kv2m";

    /** A second, different synthetic credential, used where two payloads must differ. */
    private static final String SYNTHETIC_CREDENTIAL_ALTERNATE = "Wm4$Ny8p";

    /** A seeded operator identifier. Identifiers are not credentials and carry no secret. */
    private static final String SEEDED_USER_ID = "USER0001";

    /**
     * Property names of the COMMAREA navigation and session fields, {@code app/cpy/COCOM01Y.cpy:L21-L24},
     * {@code :L29} and {@code :L43-L44}. Transformation rule 7 of the plan states that the target keeps
     * no server side session state, so none of these may appear on a request payload. Note that
     * {@code CDEMO-LAST-MAP} and {@code CDEMO-LAST-MAPSET} are {@code PIC X(7)}, not {@code X(8)}.
     */
    private static final List<String> COMMAREA_SESSION_PROPERTIES = List.of(
            "fromTranId",
            "toTranId",
            "fromProgram",
            "toProgram",
            "pgmContext",
            "lastMap",
            "lastMapset");

    // ----------------------------------------------------------------------------------------------
    // Parity oracles. These are pure functions reproducing the COBOL semantics that the payload
    // deliberately does not carry, because it is a transport object: presence testing, fixed width
    // movement and string composition all belong to the service tier. They are test oracles, not
    // production code, and they exist so that the ordered cascade, the truncation and the composed
    // literals are asserted against executable behaviour rather than against restated constants.
    // None reads ambient state, none mutates anything and none performs I/O.
    // ----------------------------------------------------------------------------------------------

    /**
     * Reproduces the COBOL predicate {@code = SPACES OR LOW-VALUES} used by all five branches of the
     * cascade at {@code app/cbl/COUSR01C.cbl:L118}, {@code :L124}, {@code :L130}, {@code :L136} and
     * {@code :L142}.
     *
     * <p>The predicate is a disjunction of two whole field comparisons, not a character by character
     * test, so a field holding a mixture of spaces and low values equals neither operand and is
     * therefore <em>not</em> empty. That distinction is preserved here rather than simplified away.
     *
     * <p>A {@code null} reference is reported as empty because an absent JSON property leaves the
     * property unset, and the fixed width map area the source received would have contained spaces or
     * low values in that case. Absent and blank remain distinct <em>values</em> - the accessor returns
     * exactly what was set - while sharing one screen <em>outcome</em>, which is precisely the three
     * state model of {@code app/cpy/CSSETATY.cpy}.
     *
     * @param presented the value as bound from the request, which may be {@code null}
     * @return {@code true} when the source would treat the field as empty
     */
    private static boolean isCobolEmpty(final String presented) {
        if (presented == null) {
            return true;
        }
        return isUniformly(presented, ' ') || isUniformly(presented, LOW_VALUE);
    }

    /**
     * Reports whether every character of a value is one given fill character, which is what a whole
     * field comparison against a COBOL figurative constant amounts to.
     *
     * @param value the value to inspect, never {@code null}
     * @param fill  the fill character to compare against
     * @return {@code true} when the value contains nothing but the fill character, including when the
     *         value is empty
     */
    private static boolean isUniformly(final String value, final char fill) {
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) != fill) {
                return false;
            }
        }
        return true;
    }

    /**
     * Reproduces the ordered, first match wins presence cascade of {@code PROCESS-ENTER-KEY} at
     * {@code app/cbl/COUSR01C.cbl:L115-L151}.
     *
     * <p>The source is a single {@code EVALUATE TRUE} whose branches are tested in declaration order.
     * The first branch that matches sets the error flag, moves its literal into the message work area,
     * positions the cursor and performs {@code SEND-USRADD-SCREEN}, which ends the evaluation. No later
     * branch is reached, so at most one message is ever produced no matter how many fields are blank.
     * The final {@code WHEN OTHER} at {@code :L148} positions the cursor on the first name and
     * continues without producing a message.
     *
     * <p>The credential is passed separately rather than read from the payload because the payload
     * exposes no read path to it, which is the security contract asserted in {@code CredentialHandling}.
     * The source reads {@code PASSWDI} straight out of the map area at {@code :L136}, so passing the
     * presented value explicitly is the faithful equivalent.
     *
     * @param presented          the bound payload supplying the four readable operator fields
     * @param presentedPassword  the presented credential, which the payload cannot surrender
     * @return the single message the source would display, or empty on the success path
     */
    private static Optional<String> firstCascadeFailure(final UserCreateRequest presented,
            final String presentedPassword) {
        if (isCobolEmpty(presented.getFirstName())) {
            return Optional.of(MSG_FIRST_NAME_EMPTY);
        }
        if (isCobolEmpty(presented.getLastName())) {
            return Optional.of(MSG_LAST_NAME_EMPTY);
        }
        if (isCobolEmpty(presented.getUserId())) {
            return Optional.of(MSG_USER_ID_EMPTY);
        }
        if (isCobolEmpty(presentedPassword)) {
            return Optional.of(MSG_PASSWD_EMPTY);
        }
        if (isCobolEmpty(presented.getUserType())) {
            return Optional.of(MSG_USER_TYPE_EMPTY);
        }
        return Optional.empty();
    }

    /**
     * Reproduces a COBOL alphanumeric {@code MOVE} into a fixed width receiving field, the operation
     * that silently loses two bytes at {@code app/cbl/COUSR01C.cbl:L188} when the eighty byte
     * {@code WS-MESSAGE} is moved into the seventy eight byte {@code ERRMSGO}.
     *
     * <p>The value is left justified in the receiving field, truncated on the right when it is too long
     * and space filled on the right when it is too short. Truncation is silent: it is neither an error
     * nor a rejection, which is why the receiving width must never be widened to hide it.
     *
     * @param sending         the sending value, never {@code null}
     * @param receivingWidth  the declared width of the receiving field, which must be positive
     * @return the receiving field contents, always exactly {@code receivingWidth} characters long
     */
    private static String cobolMove(final String sending, final int receivingWidth) {
        if (sending.length() >= receivingWidth) {
            return sending.substring(0, receivingWidth);
        }
        return sending + " ".repeat(receivingWidth - sending.length());
    }

    /**
     * Reproduces {@code DELIMITED BY SPACE} as applied to {@code SEC-USR-ID} at
     * {@code app/cbl/COUSR01C.cbl:L256}.
     *
     * <p>The operand contributes only the characters preceding its first space, so a fixed width
     * identifier shorter than its eight byte field is trimmed rather than carried with its padding.
     * This is the one place in the write path where the record's fixed width geometry is deliberately
     * not preserved into the message.
     *
     * @param sending the sending field contents, never {@code null}
     * @return the characters up to but excluding the first space, or the whole value when it holds none
     */
    private static String delimitedBySpace(final String sending) {
        final int firstSpace = sending.indexOf(' ');
        return firstSpace < 0 ? sending : sending.substring(0, firstSpace);
    }

    /**
     * Reproduces the {@code STRING} statement at {@code app/cbl/COUSR01C.cbl:L255-L258} that composes
     * the success message on the {@code DFHRESP(NORMAL)} branch.
     *
     * <p>The identifier arrives from {@code USERIDI PIC X(8)} through a {@code MOVE} into
     * {@code SEC-USR-ID PIC X(08)} at {@code :L154}, so it is space padded to eight bytes before the
     * {@code STRING} trims it at its first space. Both steps are applied here in that order, because
     * the padding is what the trim acts upon.
     *
     * @param presentedUserId the identifier as presented on the screen, never {@code null}
     * @return the composed confirmation, for example {@code User USER0001 has been added ...}
     */
    private static String additionConfirmation(final String presentedUserId) {
        final String securityUserId = cobolMove(presentedUserId, SEC_USR_ID_WIDTH);
        return CONFIRMATION_HEAD + delimitedBySpace(securityUserId) + CONFIRMATION_TAIL;
    }

    /**
     * Reproduces the {@code EVALUATE WS-RESP-CD} of {@code WRITE-USER-SEC-FILE} at
     * {@code app/cbl/COUSR01C.cbl:L250-L274}, which maps the CICS response of the
     * {@code EXEC CICS WRITE} at {@code :L240} onto exactly one of three messages.
     *
     * <p>The duplicate case is the structural detail that matters: {@code WHEN DFHRESP(DUPKEY)} at
     * {@code :L260} and {@code WHEN DFHRESP(DUPREC)} at {@code :L261} are two consecutive
     * {@code WHEN} clauses with no statements between them, so both fall into the single branch that
     * follows. The two responses are therefore indistinguishable in the output by construction, and
     * giving them separate messages would be a behaviour change.
     *
     * @param cicsResponse    the symbolic CICS response name, one of {@code NORMAL}, {@code DUPKEY},
     *                        {@code DUPREC} or {@code OTHER}
     * @param presentedUserId the identifier the operator supplied, used only on the success branch
     * @return the single message the branch moves into {@code WS-MESSAGE}
     */
    private static String writeOutcomeMessage(final String cicsResponse,
            final String presentedUserId) {
        return switch (cicsResponse) {
            case "NORMAL" -> additionConfirmation(presentedUserId);
            case "DUPKEY", "DUPREC" -> MSG_DUPLICATE_USER_ID;
            case "OTHER" -> MSG_UNABLE_TO_ADD;
            default -> throw new IllegalArgumentException(
                    "app/cbl/COUSR01C.cbl:L250-L274 evaluates only NORMAL, DUPKEY, DUPREC and "
                            + "OTHER, never '" + cicsResponse + "'");
        };
    }

    /**
     * Reads the declared {@link Size#max()} of one payload property, which is the only way to assert a
     * declared width without depending on a locale sensitive interpolated constraint message.
     *
     * @param property the Java property name, which must name a declared field
     * @return the declared maximum length
     * @throws AssertionError if the property is not declared or carries no {@link Size} constraint
     */
    private static int declaredMaxLength(final String property) {
        final Field field = declaredField(property);
        final Size size = field.getAnnotation(Size.class);
        assertThat(size)
                .as("property '%s' must carry a @Size constraint naming its PIC width", property)
                .isNotNull();
        return size.max();
    }

    /**
     * Looks up one declared field of the payload by property name.
     *
     * @param property the Java property name
     * @return the declared field, never {@code null}
     * @throws AssertionError if no such field is declared
     */
    private static Field declaredField(final String property) {
        try {
            return UserCreateRequest.class.getDeclaredField(property);
        } catch (final NoSuchFieldException absent) {
            throw new AssertionError(
                    "UserCreateRequest must declare the field '" + property
                            + "' translated from app/cpy-bms/COUSR01.CPY", absent);
        }
    }

    /**
     * Applies a presented value to one payload property through its real setter.
     *
     * <p>An explicit switch is used rather than reflective invocation so that every mutation is a
     * compile checked call on a known method, in keeping with Rule 1 clause D's prohibition on
     * reflection driven invocation. The switch is exhaustive over the twelve fields of
     * {@code COUSR1AI} and rejects any other name rather than silently ignoring it.
     *
     * @param target   the payload to mutate, never {@code null}
     * @param property the Java property name, which must name one of the twelve fields
     * @param value    the presented value, which may be {@code null}
     */
    private static void applyPresented(final UserCreateRequest target, final String property,
            final String value) {
        switch (property) {
            case "transactionName" -> target.setTransactionName(value);
            case "title01" -> target.setTitle01(value);
            case "currentDate" -> target.setCurrentDate(value);
            case "programName" -> target.setProgramName(value);
            case "title02" -> target.setTitle02(value);
            case "currentTime" -> target.setCurrentTime(value);
            case "firstName" -> target.setFirstName(value);
            case "lastName" -> target.setLastName(value);
            case "userId" -> target.setUserId(value);
            case "password" -> target.setPassword(value);
            case "userType" -> target.setUserType(value);
            case "errorMessage" -> target.setErrorMessage(value);
            default -> throw new IllegalArgumentException(
                    "'" + property + "' is not one of the " + ADD_MAP_INPUT_FIELD_COUNT
                            + " fields of app/cpy-bms/COUSR01.CPY group COUSR1AI");
        }
    }

    /**
     * Reads back one of the eleven readable payload properties through its real getter.
     *
     * <p>The credential is deliberately absent from this switch: the payload declares no
     * {@code getPassword()}, so there is nothing to call. Its retention is proven instead by the
     * binding assertions in {@code CredentialHandling}.
     *
     * @param source   the payload to read, never {@code null}
     * @param property the Java property name, which must name one of the eleven readable fields
     * @return the value exactly as retained, which may be {@code null}
     */
    private static String readPresented(final UserCreateRequest source, final String property) {
        return switch (property) {
            case "transactionName" -> source.getTransactionName();
            case "title01" -> source.getTitle01();
            case "currentDate" -> source.getCurrentDate();
            case "programName" -> source.getProgramName();
            case "title02" -> source.getTitle02();
            case "currentTime" -> source.getCurrentTime();
            case "firstName" -> source.getFirstName();
            case "lastName" -> source.getLastName();
            case "userId" -> source.getUserId();
            case "userType" -> source.getUserType();
            case "errorMessage" -> source.getErrorMessage();
            default -> throw new IllegalArgumentException(
                    "'" + property + "' is not one of the readable fields of group COUSR1AI; the "
                            + "credential is write only and has no getter");
        };
    }

    /**
     * Validates a payload with a freshly built, immediately closed validator.
     *
     * <p>The factory is opened and closed per call so that no validator is ever shared as mutable state
     * between tests. Only the violated property paths and constraint types are asserted by callers,
     * never the interpolated message text, which Hibernate Validator resolves against the ambient
     * locale and which would therefore make an assertion machine dependent.
     *
     * @param payload the payload to validate, never {@code null}
     * @return every violation raised, possibly empty, never {@code null}
     */
    private static Set<ConstraintViolation<UserCreateRequest>> violationsOf(
            final UserCreateRequest payload) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            final Validator validator = factory.getValidator();
            return validator.validate(payload);
        }
    }

    /**
     * Collects the property names that raised a violation, so that assertions can name fields rather
     * than quote values. This keeps a failing credential assertion free of the credential.
     *
     * @param payload the payload to validate, never {@code null}
     * @return the violated property names, possibly empty, never {@code null}
     */
    private static List<String> violatedPropertiesOf(final UserCreateRequest payload) {
        return violationsOf(payload).stream()
                .map(violation -> violation.getPropertyPath().toString())
                .sorted()
                .toList();
    }

    /**
     * Builds a payload whose five operator supplied fields are all populated, which is the success path
     * of the cascade. The header and error fields are left unset, exactly as an inbound request leaves
     * them.
     *
     * @return a fully populated payload, never {@code null}
     */
    private static UserCreateRequest populatedPayload() {
        final UserCreateRequest payload = new UserCreateRequest();
        payload.setFirstName("LAWRENCE");
        payload.setLastName("THOMAS");
        payload.setUserId(SEEDED_USER_ID);
        payload.setPassword(SYNTHETIC_CREDENTIAL);
        payload.setUserType("U");
        return payload;
    }

    // ==============================================================================================
    // 1. The field contract: exactly twelve input fields, every width byte exact.
    // ==============================================================================================

    /**
     * Asserts the census of group {@code COUSR1AI} at {@code app/cpy-bms/COUSR01.CPY:17-90}: twelve
     * input fields, no more and no fewer, each an alphanumeric of exactly its declared PIC width.
     */
    @Nested
    @DisplayName("1. Field contract - app/cpy-bms/COUSR01.CPY group COUSR1AI")
    class FieldContract {

        @Test
        @DisplayName("declares exactly 12 input fields, reproducing COUSR1AI with zero remainder")
        void declaresExactlyTwelveInputFields() {
            assertThat(UserCreateRequest.class.getDeclaredFields())
                    .as("group COUSR1AI at app/cpy-bms/COUSR01.CPY:17-90 carries exactly %d input "
                            + "fields; a 13th field would be an invention and a missing one a loss",
                            ADD_MAP_INPUT_FIELD_COUNT)
                    .hasSize(ADD_MAP_INPUT_FIELD_COUNT);
        }

        @Test
        @DisplayName("every field is alphanumeric, as every PIC clause on the map is X(n)")
        void everyFieldIsAlphanumeric() {
            assertThat(UserCreateRequest.class.getDeclaredFields())
                    .as("every COUSR1AI data field is PIC X(n), so every property is a String; a "
                            + "numeric type would silently drop leading zeros and trailing padding")
                    .allSatisfy(field -> assertThat(field.getType()).isEqualTo(String.class));
        }

        @ParameterizedTest(name = "{1} PIC X({2}) at app/cpy-bms/COUSR01.CPY:{3} -> {0}")
        @CsvSource({
            "transactionName, TRNNAMEI,  4, 24",
            "title01,         TITLE01I, 40, 30",
            "currentDate,     CURDATEI,  8, 36",
            "programName,     PGMNAMEI,  8, 42",
            "title02,         TITLE02I, 40, 48",
            "currentTime,     CURTIMEI,  8, 54",
            "firstName,       FNAMEI,   20, 60",
            "lastName,        LNAMEI,   20, 66",
            "userId,          USERIDI,   8, 72",
            "password,        PASSWDI,   8, 78",
            "userType,        USRTYPEI,  1, 84",
            "errorMessage,    ERRMSGI,  78, 90",
        })
        @DisplayName("every declared width equals its PIC clause byte exactly")
        void everyDeclaredWidthEqualsItsPicClause(final String property, final String cobolField,
                final int picWidth, final int mapLine) {
            assertThat(declaredMaxLength(property))
                    .as("%s is PIC X(%d) at app/cpy-bms/COUSR01.CPY:%d, so property '%s' must be "
                            + "constrained to exactly %d; widening or narrowing it breaks the field "
                            + "contract in a way no round trip test would catch",
                            cobolField, picWidth, mapLine, property, picWidth)
                    .isEqualTo(picWidth);
        }

        @Test
        @DisplayName("ERRMSGI is 78 - not 79 and not the 80 of the WS-MESSAGE work area")
        void errorMessageIsSeventyEightAndNotEighty() {
            assertThat(declaredMaxLength("errorMessage"))
                    .as("ERRMSGI at app/cpy-bms/COUSR01.CPY:90 and ERRMSGO at :164 are both PIC X(78); "
                            + "WS-MESSAGE at app/cbl/COUSR01C.cbl:L38 is PIC X(80) and the difference "
                            + "is the silent truncation asserted by MessageGeometry")
                    .isEqualTo(ERRMSG_WIDTH)
                    .isNotEqualTo(WS_MESSAGE_WIDTH)
                    .isNotEqualTo(ERRMSG_WIDTH + 1);
        }

        @Test
        @DisplayName("CURTIMEI is X(8) here even though COSGN00.CPY:54 alone declares X(9)")
        void currentTimeIsEightBecauseTheSignOnMapsNineIsNotShared() {
            assertThat(declaredMaxLength("currentTime"))
                    .as("CURTIMEI is PIC X(8) at app/cpy-bms/COUSR01.CPY:54; only "
                            + "app/cpy-bms/COSGN00.CPY:54 declares PIC X(9), and adopting 9 here "
                            + "would import another map's geometry")
                    .isEqualTo(8);
        }

        @ParameterizedTest(name = "header field {0} is declared on the class itself")
        @CsvSource({
            "transactionName,  4",
            "title01,         40",
            "currentDate,      8",
            "programName,      8",
            "title02,         40",
            "currentTime,      8",
        })
        @DisplayName("the six recurring header fields are declared inline, not inherited from a mixin")
        void headerFieldsAreDeclaredInlineRatherThanInherited(final String property,
                final int picWidth) {
            assertThat(declaredField(property).getDeclaringClass())
                    .as("the header recurrence is not uniform across the 17 symbolic maps - CURTIMEI "
                            + "is X(8) here and X(9) at app/cpy-bms/COSGN00.CPY:54 - so a shared "
                            + "header type would have to misrepresent one width; '%s' must therefore "
                            + "be declared on UserCreateRequest itself",
                            property)
                    .isEqualTo(UserCreateRequest.class);
            assertThat(declaredMaxLength(property)).isEqualTo(picWidth);
        }
    }

    // ==============================================================================================
    // 2. The add and update maps are not interchangeable.
    // ==============================================================================================

    /**
     * Asserts the two verified divergences between the add map and the update map, either of which is
     * enough to make a shared base type factually wrong: the identifier field is named {@code USERIDI}
     * at {@code app/cpy-bms/COUSR01.CPY:72} here and {@code USRIDINI} at
     * {@code app/cpy-bms/COUSR02.CPY:60} there, and it sits after the operator's names here and before
     * them there.
     *
     * <p>The sibling payload type is deliberately not referenced from this file. It is not a declared
     * dependency of this test, and the absence of a shared parent is provable from this class's own
     * reflected hierarchy without importing anything.
     */
    @Nested
    @DisplayName("2. Add versus update map divergence - COUSR01.CPY:72 USERIDI vs COUSR02.CPY:60 USRIDINI")
    class MapDivergence {

        @Test
        @DisplayName("the identifier property derives from USERIDI, the add map's own name")
        void identifierDerivesFromUserIdiOfTheAddMap() {
            assertThat(declaredMaxLength("userId"))
                    .as("USERIDI is PIC X(8) at app/cpy-bms/COUSR01.CPY:72")
                    .isEqualTo(SEC_USR_ID_WIDTH);
            assertThat(declaredField("userId").getType()).isEqualTo(String.class);
        }

        @Test
        @DisplayName("no property is named after the update or list maps' USRIDINI")
        void noPropertyIsNamedAfterUsrIdIn() {
            final List<String> declared = declaredPropertyNames();
            assertThat(declared)
                    .as("USRIDINI belongs to app/cpy-bms/COUSR02.CPY:60 and "
                            + "app/cpy-bms/COUSR00.CPY:66, never to the add map; a property named "
                            + "after it here would mean this payload was derived from the wrong map")
                    .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("usridin"))
                    .contains("userId");
        }

        @Test
        @DisplayName("the hierarchy carries no shared base type, interface or mixin")
        void hierarchyCarriesNoSharedBaseTypeOrMixin() {
            assertThat(UserCreateRequest.class.getSuperclass())
                    .as("a parent shared with the update payload would have to fix one field order "
                            + "and one identifier name, and would therefore misrepresent whichever "
                            + "map it did not follow")
                    .isEqualTo(Object.class);
            assertThat(UserCreateRequest.class.getInterfaces())
                    .as("no interface or mixin may unify the two maps either, for the same reason")
                    .isEmpty();
        }

        @Test
        @DisplayName("fields are declared in the add map's own order, identifier after the names")
        void fieldsAreDeclaredInTheAddMapsOwnOrder() {
            assertThat(declaredPropertyNames())
                    .as("the add map's order is header, FNAMEI (:60), LNAMEI (:66), USERIDI (:72), "
                            + "PASSWDI (:78), USRTYPEI (:84), ERRMSGI (:90); the update map leads "
                            + "with its identifier instead. Field declaration order is not specified "
                            + "by the JLS, but the enforcer plugin pins this build to one JDK, on "
                            + "which getDeclaredFields reports declaration order, so this guards the "
                            + "source convention deterministically here")
                    .containsExactly(
                            "transactionName",
                            "title01",
                            "currentDate",
                            "programName",
                            "title02",
                            "currentTime",
                            "firstName",
                            "lastName",
                            "userId",
                            "password",
                            "userType",
                            "errorMessage");
        }

        @Test
        @DisplayName("the identifier follows the names, as on the add map, not before them")
        void identifierFollowsTheNamesNotPrecedesThem() {
            final List<String> declared = declaredPropertyNames();
            assertThat(declared.indexOf("userId"))
                    .as("USERIDI is at app/cpy-bms/COUSR01.CPY:72, after FNAMEI at :60 and LNAMEI "
                            + "at :66; the update map's identifier at COUSR02.CPY:60 precedes them")
                    .isGreaterThan(declared.indexOf("firstName"))
                    .isGreaterThan(declared.indexOf("lastName"));
        }

        /**
         * Lists the declared property names of the payload in declaration order.
         *
         * @return the twelve property names, never {@code null}
         */
        private List<String> declaredPropertyNames() {
            return Arrays.stream(UserCreateRequest.class.getDeclaredFields())
                    .map(Field::getName)
                    .toList();
        }
    }

    // ==============================================================================================
    // 3. The presence cascade: ordered, first match wins, with exact literals.
    // ==============================================================================================

    /**
     * Asserts the ordered presence cascade of {@code PROCESS-ENTER-KEY} at
     * {@code app/cbl/COUSR01C.cbl:L115-L151} and the five message literals it moves into the work area.
     *
     * <p>The payload itself carries no presence constraint, which is asserted here rather than assumed:
     * the source tolerates a blank submission and answers it with a business message instead of
     * refusing the input, so presence is the service tier's decision, taken in the source's order.
     * Bean Validation would report the five failures as an unordered set, so the ordered contract is
     * asserted against the executable oracle {@link #firstCascadeFailure(UserCreateRequest, String)}.
     */
    @Nested
    @DisplayName("3. Presence cascade - app/cbl/COUSR01C.cbl:L115-L151, first match wins")
    class PresenceCascade {

        @ParameterizedTest(name = "presented value [{0}] is empty to = SPACES OR LOW-VALUES")
        @DisplayName("absent, empty, all spaces and all low values are every one of them empty")
        @CsvSource(value = {
            "ABSENT",
            "EMPTY",
            "SPACES",
            "LOW_VALUES",
        })
        void absentBlankAndLowValuesAreAllEmptyToTheScreenPredicate(final String shape) {
            assertThat(isCobolEmpty(shapedValue(shape, 20)))
                    .as("the branch predicate at app/cbl/COUSR01C.cbl:L118 is the single combined "
                            + "test = SPACES OR LOW-VALUES, so shape [%s] is empty for this screen",
                            shape)
                    .isTrue();
        }

        @Test
        @DisplayName("a populated value is not empty, keeping empty distinct from invalid")
        void aPopulatedValueIsNotEmpty() {
            assertThat(isCobolEmpty("LAWRENCE"))
                    .as("empty and invalid are different states; only the former is what the "
                            + "cascade at app/cbl/COUSR01C.cbl:L115-L151 tests")
                    .isFalse();
        }

        @Test
        @DisplayName("a field mixing spaces and low values equals neither operand and is not empty")
        void aMixtureOfSpacesAndLowValuesIsNotEmpty() {
            assertThat(isCobolEmpty("  " + LOW_VALUE + LOW_VALUE))
                    .as("= SPACES OR LOW-VALUES is a disjunction of two whole field comparisons, "
                            + "not a character by character test, so a mixture matches neither")
                    .isFalse();
        }

        @ParameterizedTest(name = "app/cbl/COUSR01C.cbl:{1} -> {0}")
        @CsvSource({
            "First Name can NOT be empty..., 120",
            "Last Name can NOT be empty...,  126",
            "User ID can NOT be empty...,    132",
            "Password can NOT be empty...,   138",
            "User Type can NOT be empty...,  142",
        })
        @DisplayName("the five presence literals are reproduced byte exactly at their own locators")
        void theFivePresenceLiteralsAreReproducedByteExactly(final String literal,
                final int sourceLine) {
            assertThat(CASCADE_MESSAGES_IN_SOURCE_ORDER)
                    .as("the literal moved into WS-MESSAGE at app/cbl/COUSR01C.cbl:%d is compared "
                            + "byte for byte by the parity gates; it may never be paraphrased, "
                            + "re-cased or re-punctuated", sourceLine)
                    .contains(literal);
        }

        @Test
        @DisplayName("every presence literal ends in exactly three dots and carries upper case NOT")
        void everyPresenceLiteralKeepsItsThreeDotsAndItsUpperCaseNot() {
            assertThat(CASCADE_MESSAGES_IN_SOURCE_ORDER)
                    .hasSize(5)
                    .allSatisfy(message -> {
                        assertThat(message)
                                .as("each literal ends in exactly three dots, never one ellipsis "
                                        + "character and never two or four dots")
                                .endsWith("...")
                                .doesNotEndWith("....")
                                .doesNotContain("\u2026");
                        assertThat(message)
                                .as("the source spells the negation in upper case: 'can NOT be "
                                        + "empty', never 'can not' and never 'cannot'")
                                .contains("can NOT be empty")
                                .doesNotContain("can not be empty")
                                .doesNotContain("cannot");
                    });
        }

        @Test
        @DisplayName("with every field blank the reported failure is the first name alone")
        void withEveryFieldBlankOnlyTheFirstNameIsReported() {
            final UserCreateRequest blank = new UserCreateRequest();

            assertThat(firstCascadeFailure(blank, null))
                    .as("the EVALUATE TRUE at app/cbl/COUSR01C.cbl:L117 stops at its first matching "
                            + "branch, so five blank fields still yield one message and never a "
                            + "collected set of five")
                    .contains(MSG_FIRST_NAME_EMPTY);
        }

        @ParameterizedTest(name = "{0} fields populated -> {1}")
        @CsvSource({
            "0, First Name can NOT be empty...",
            "1, Last Name can NOT be empty...",
            "2, User ID can NOT be empty...",
            "3, Password can NOT be empty...",
            "4, User Type can NOT be empty...",
        })
        @DisplayName("the cascade reports the first failing field in the source's declaration order")
        void theCascadeReportsTheFirstFailingFieldInSourceOrder(final int populatedCount,
                final String expected) {
            final UserCreateRequest payload = new UserCreateRequest();
            String credential = null;
            if (populatedCount > 0) {
                payload.setFirstName("LAWRENCE");
            }
            if (populatedCount > 1) {
                payload.setLastName("THOMAS");
            }
            if (populatedCount > 2) {
                payload.setUserId(SEEDED_USER_ID);
            }
            if (populatedCount > 3) {
                credential = SYNTHETIC_CREDENTIAL;
            }

            assertThat(firstCascadeFailure(payload, credential))
                    .as("with the first %d fields supplied the next unsatisfied branch in the order "
                            + "First Name, Last Name, User ID, Password, User Type is the one "
                            + "reported", populatedCount)
                    .contains(expected);
        }

        @Test
        @DisplayName("blank and low values reach the identical message, not two different ones")
        void blankAndLowValuesProduceTheIdenticalMessage() {
            final UserCreateRequest spaces = new UserCreateRequest();
            spaces.setFirstName("   ");
            final UserCreateRequest lowValues = new UserCreateRequest();
            lowValues.setFirstName(String.valueOf(LOW_VALUE).repeat(3));

            assertThat(firstCascadeFailure(spaces, SYNTHETIC_CREDENTIAL))
                    .as("both operands of = SPACES OR LOW-VALUES lead to the one branch at "
                            + "app/cbl/COUSR01C.cbl:L118, so both produce the same literal")
                    .contains(MSG_FIRST_NAME_EMPTY)
                    .isEqualTo(firstCascadeFailure(lowValues, SYNTHETIC_CREDENTIAL));
        }

        @Test
        @DisplayName("a populated but out of domain user type produces no empty message")
        void aPopulatedButOutOfDomainValueProducesNoEmptyMessage() {
            final UserCreateRequest payload = populatedPayload();
            payload.setUserType("Z");

            assertThat(firstCascadeFailure(payload, SYNTHETIC_CREDENTIAL))
                    .as("'Z' is outside the 'A'/'U' domain of app/cpy/COCOM01Y.cpy:L27-L28 but it is "
                            + "not blank, and the cascade tests presence only, so no empty message "
                            + "is produced; the source adds no membership check of its own")
                    .isEmpty();
        }

        @Test
        @DisplayName("the success path produces no message, matching WHEN OTHER at :L148")
        void theSuccessPathProducesNoMessage() {
            assertThat(firstCascadeFailure(populatedPayload(), SYNTHETIC_CREDENTIAL))
                    .as("WHEN OTHER at app/cbl/COUSR01C.cbl:L148 positions the cursor on the first "
                            + "name and continues without moving anything into WS-MESSAGE")
                    .isEmpty();
        }

        @Test
        @DisplayName("the payload declares no presence constraint, so the service owns the order")
        void thePayloadDeclaresNoPresenceConstraintSoTheServiceOwnsTheOrder() {
            assertThat(violationsOf(new UserCreateRequest()))
                    .as("an entirely blank submission binds without a single violation, because the "
                            + "source answers it with a business message rather than refusing it. "
                            + "Bean Validation discovery order is unspecified, so the ordered first "
                            + "match wins contract is enforced by the service tier and asserted here "
                            + "against the oracle instead of against constraint iteration")
                    .isEmpty();
        }

        /**
         * Builds one of the four presented shapes an inbound field can take, at a given field width.
         *
         * @param shape one of {@code ABSENT}, {@code EMPTY}, {@code SPACES} or {@code LOW_VALUES}
         * @param width the declared PIC width of the field being shaped
         * @return the shaped value, {@code null} for {@code ABSENT}
         */
        private String shapedValue(final String shape, final int width) {
            return switch (shape) {
                case "ABSENT" -> null;
                case "EMPTY" -> "";
                case "SPACES" -> " ".repeat(width);
                case "LOW_VALUES" -> String.valueOf(LOW_VALUE).repeat(width);
                default -> throw new IllegalArgumentException("unknown presented shape: " + shape);
            };
        }
    }

    // ==============================================================================================
    // 4. The eighty byte work area truncated onto a seventy eight byte screen field.
    // ==============================================================================================

    /**
     * Asserts the silent two byte truncation at {@code app/cbl/COUSR01C.cbl:L188}, where the eighty byte
     * {@code WS-MESSAGE} declared at {@code :L38} is moved into the seventy eight byte {@code ERRMSGO}
     * of group {@code COUSR1AO} at {@code app/cpy-bms/COUSR01.CPY:164}.
     *
     * <p>The truncation is reproduced, not repaired. Widening the field to eighty would hide it, and
     * rejecting the over length value would turn a silent COBOL {@code MOVE} into an error the source
     * never raises.
     */
    @Nested
    @DisplayName("4. Message geometry - WS-MESSAGE X(80) at :L38 moved onto ERRMSGO X(78) at :L188")
    class MessageGeometry {

        @Test
        @DisplayName("the work area is 80 and the wire field is 78, a two byte difference")
        void theWorkAreaIsEightyAndTheWireFieldIsSeventyEight() {
            assertThat(WS_MESSAGE_WIDTH - ERRMSG_WIDTH)
                    .as("WS-MESSAGE PIC X(80) at app/cbl/COUSR01C.cbl:L38 against ERRMSGI/ERRMSGO "
                            + "PIC X(78) at app/cpy-bms/COUSR01.CPY:90 and :164")
                    .isEqualTo(2);
            assertThat(declaredMaxLength("errorMessage")).isEqualTo(ERRMSG_WIDTH);
        }

        @Test
        @DisplayName("an 80 character message is truncated to 78, neither rejected nor widened")
        void anEightyCharacterMessageIsTruncatedNotRejected() {
            final String workArea = cobolMove(MSG_INVALID_KEY_TEXT, WS_MESSAGE_WIDTH);
            final String onTheWire = cobolMove(workArea, ERRMSG_WIDTH);

            assertThat(workArea).hasSize(WS_MESSAGE_WIDTH);
            assertThat(onTheWire)
                    .as("the MOVE at app/cbl/COUSR01C.cbl:L188 is silent: it truncates on the right "
                            + "and raises nothing")
                    .hasSize(ERRMSG_WIDTH)
                    .startsWith(MSG_INVALID_KEY_TEXT);
        }

        @Test
        @DisplayName("the two bytes lost are exactly positions 79 and 80 of the work area")
        void theTwoLostBytesAreExactlyPositionsSeventyNineAndEighty() {
            final String workArea = "x".repeat(ERRMSG_WIDTH) + "YZ";
            assertThat(workArea).hasSize(WS_MESSAGE_WIDTH);

            final String onTheWire = cobolMove(workArea, ERRMSG_WIDTH);

            assertThat(onTheWire)
                    .as("bytes 1 to 78 survive the move")
                    .isEqualTo("x".repeat(ERRMSG_WIDTH));
            assertThat(workArea.substring(ERRMSG_WIDTH))
                    .as("bytes 79 and 80 are dropped, which is the whole of the loss")
                    .isEqualTo("YZ");
        }

        @Test
        @DisplayName("a short message is space padded to the field width, never trimmed")
        void aShortMessageIsSpacePaddedToTheFieldWidth() {
            final String onTheWire = cobolMove(MSG_UNABLE_TO_ADD, ERRMSG_WIDTH);

            assertThat(onTheWire)
                    .as("a COBOL alphanumeric MOVE left justifies and space fills the remainder")
                    .hasSize(ERRMSG_WIDTH)
                    .startsWith(MSG_UNABLE_TO_ADD)
                    .isEqualTo(MSG_UNABLE_TO_ADD + " ".repeat(ERRMSG_WIDTH - MSG_UNABLE_TO_ADD.length()));
        }

        @ParameterizedTest(name = "[{0}] survives the 78 byte field intact")
        @ValueSource(strings = {
            "First Name can NOT be empty...",
            "Last Name can NOT be empty...",
            "User ID can NOT be empty...",
            "Password can NOT be empty...",
            "User Type can NOT be empty...",
            "User ID already exist...",
            "Unable to Add User...",
        })
        @DisplayName("every parity literal fits the 78 byte field without truncation")
        void everyParityLiteralFitsWithoutTruncation(final String literal) {
            assertThat(literal.length())
                    .as("a literal longer than %d would be silently clipped on the wire and would "
                            + "no longer match the parity baseline", ERRMSG_WIDTH)
                    .isLessThanOrEqualTo(ERRMSG_WIDTH);
            assertThat(cobolMove(literal, ERRMSG_WIDTH).strip()).isEqualTo(literal);
        }

        @Test
        @DisplayName("the shared CCDA-MSG-INVALID-KEY constant fits, and the payload does not carry it")
        void theSharedInvalidKeyConstantFitsAndIsNotSurfacedByThePayload() {
            final String constant = cobolMove(MSG_INVALID_KEY_TEXT, CCDA_MESSAGE_WIDTH);

            assertThat(constant)
                    .as("CCDA-MSG-INVALID-KEY is PIC X(50) at app/cpy/CSMSG01Y.cpy:20 and is moved "
                            + "into the same 80 byte work area at app/cbl/COUSR01C.cbl:L101")
                    .hasSize(CCDA_MESSAGE_WIDTH);
            assertThat(cobolMove(constant, ERRMSG_WIDTH).strip())
                    .as("50 is well inside 78, so this constant is never truncated on the wire")
                    .isEqualTo(MSG_INVALID_KEY_TEXT);
            assertThat(declaredPropertyNamesOfPayload())
                    .as("the payload is a transport object: it surfaces no message constant of its "
                            + "own, so the shared common message copybook value is not exposed here "
                            + "and belongs to the service tier that renders the screen")
                    .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("invalidkey"));
        }

        @Test
        @DisplayName("a 79 character value violates the width, proving 78 was not widened to 80")
        void aSeventyNineCharacterValueViolatesTheWidth() {
            final UserCreateRequest payload = populatedPayload();
            payload.setErrorMessage("x".repeat(ERRMSG_WIDTH + 1));

            assertThat(violatedPropertiesOf(payload))
                    .as("if the field had been widened to the work area's 80 bytes, a 79 character "
                            + "value would bind cleanly and the truncation would be invisible")
                    .containsExactly("errorMessage");
        }

        @Test
        @DisplayName("a 78 character value binds cleanly at the field's exact width")
        void aSeventyEightCharacterValueBindsCleanly() {
            final UserCreateRequest payload = populatedPayload();
            payload.setErrorMessage("x".repeat(ERRMSG_WIDTH));

            assertThat(violationsOf(payload))
                    .as("78 is the declared width, so it is accepted rather than clipped")
                    .isEmpty();
        }

        /**
         * Lists the declared property names of the payload.
         *
         * @return the twelve property names, never {@code null}
         */
        private List<String> declaredPropertyNamesOfPayload() {
            return Arrays.stream(UserCreateRequest.class.getDeclaredFields())
                    .map(Field::getName)
                    .toList();
        }
    }

    // ==============================================================================================
    // 5. The write path: duplicate handling and the composed success literal.
    // ==============================================================================================

    /**
     * Asserts the three outcomes of {@code WRITE-USER-SEC-FILE} at
     * {@code app/cbl/COUSR01C.cbl:L238-L274}, whose {@code EXEC CICS WRITE} at {@code :L240} uses
     * {@code RIDFLD(SEC-USR-ID)} and {@code KEYLENGTH(LENGTH OF SEC-USR-ID)} against the eight byte key
     * of the cluster defined {@code KEYS(8,0)} at {@code app/jcl/DUSRSECJ.jcl:L65}.
     *
     * <p>The payload models none of these outcomes, which is asserted rather than assumed: it is a pure
     * inbound transport object, so response mapping belongs to the service tier. The literals are
     * nonetheless pinned here, because they are compared byte for byte by the parity gates and this is
     * the tier that can hold them to that standard.
     */
    @Nested
    @DisplayName("5. Write path outcomes - app/cbl/COUSR01C.cbl:L238-L274")
    class WritePathOutcomes {

        @Test
        @DisplayName("the success message composes byte exactly: User USER0001 has been added ...")
        void theSuccessMessageComposesByteExactly() {
            assertThat(writeOutcomeMessage("NORMAL", SEEDED_USER_ID))
                    .as("the STRING at app/cbl/COUSR01C.cbl:L255-L258 concatenates 'User ' "
                            + "DELIMITED BY SIZE, SEC-USR-ID DELIMITED BY SPACE and "
                            + "' has been added ...' DELIMITED BY SIZE")
                    .isEqualTo("User USER0001 has been added ...");
        }

        @Test
        @DisplayName("the identifier is trimmed at its first space, not carried with its padding")
        void theIdentifierIsTrimmedAtItsFirstSpace() {
            assertThat(writeOutcomeMessage("NORMAL", "USER1"))
                    .as("USERIDI X(8) is moved into SEC-USR-ID X(08) and so arrives space padded, "
                            + "but DELIMITED BY SPACE at app/cbl/COUSR01C.cbl:L256 contributes only "
                            + "the characters before the first space")
                    .isEqualTo("User USER1 has been added ...")
                    .doesNotContain("USER1   ");
            assertThat(delimitedBySpace(cobolMove("USER1", SEC_USR_ID_WIDTH)))
                    .as("the padding is what the trim acts upon, so both steps must be applied in "
                            + "that order")
                    .isEqualTo("USER1");
        }

        @Test
        @DisplayName("the tail literal keeps its leading space and the space before its three dots")
        void theTailLiteralKeepsItsLeadingAndPreDotSpaces() {
            assertThat(CONFIRMATION_TAIL)
                    .as("app/cbl/COUSR01C.cbl:L257 reads ' has been added ...' - a leading space, "
                            + "the verb 'added', then a SPACE and only then the three dots. This is "
                            + "the one parity literal whose dots are not flush against a word")
                    .isEqualTo(" has been added ...")
                    .startsWith(" ")
                    .endsWith(" ...")
                    .doesNotContain("added...");
            assertThat(CONFIRMATION_HEAD)
                    .as("app/cbl/COUSR01C.cbl:L255 reads 'User ' with one trailing space")
                    .isEqualTo("User ");
        }

        @Test
        @DisplayName("the duplicate literal keeps the source's 'exist' spelling, not 'exists'")
        void theDuplicateLiteralKeepsTheSourcesExistSpelling() {
            assertThat(MSG_DUPLICATE_USER_ID)
                    .as("app/cbl/COUSR01C.cbl:L263 reads 'User ID already exist...'. The singular "
                            + "verb is a source sic and correcting the grammar breaks the byte for "
                            + "byte parity comparison")
                    .isEqualTo("User ID already exist...")
                    .endsWith("exist...")
                    .doesNotContain("exists");
        }

        @ParameterizedTest(name = "DFHRESP({0}) -> User ID already exist...")
        @ValueSource(strings = {"DUPKEY", "DUPREC"})
        @DisplayName("both duplicate responses collapse into the one branch, with one message")
        void bothDuplicateResponsesCollapseIntoOneBranch(final String cicsResponse) {
            assertThat(writeOutcomeMessage(cicsResponse, SEEDED_USER_ID))
                    .as("WHEN DFHRESP(DUPKEY) at app/cbl/COUSR01C.cbl:L260 and WHEN DFHRESP(DUPREC) "
                            + "at :L261 are consecutive clauses with no statements between them, so "
                            + "both execute the same branch; the two are not separable in the output")
                    .isEqualTo(MSG_DUPLICATE_USER_ID);
        }

        @Test
        @DisplayName("the duplicate outcome is one distinguishable state shared by both responses")
        void theDuplicateOutcomeIsOneStateSharedByBothResponses() {
            assertThat(writeOutcomeMessage("DUPKEY", SEEDED_USER_ID))
                    .isEqualTo(writeOutcomeMessage("DUPREC", SEEDED_USER_ID))
                    .as("the duplicate state is distinguishable from both the success and the "
                            + "catch all outcomes, while the two response codes inside it are not "
                            + "distinguishable from each other")
                    .isNotEqualTo(writeOutcomeMessage("NORMAL", SEEDED_USER_ID))
                    .isNotEqualTo(writeOutcomeMessage("OTHER", SEEDED_USER_ID));
        }

        @Test
        @DisplayName("the catch all literal is reproduced byte exactly")
        void theCatchAllLiteralIsReproducedByteExactly() {
            assertThat(writeOutcomeMessage("OTHER", SEEDED_USER_ID))
                    .as("app/cbl/COUSR01C.cbl:L270, the WHEN OTHER branch, which also positions the "
                            + "cursor on the first name")
                    .isEqualTo("Unable to Add User...")
                    .endsWith("...");
        }

        @Test
        @DisplayName("an unevaluated response is rejected rather than silently mapped")
        void anUnevaluatedResponseIsRejectedRatherThanSilentlyMapped() {
            assertThatThrownBy(() -> writeOutcomeMessage("NOTFND", SEEDED_USER_ID))
                    .as("the source evaluates four cases only; a fifth must fail loudly rather than "
                            + "fall through to a default message the source never emits")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("NOTFND")
                    .hasMessageContaining("app/cbl/COUSR01C.cbl:L250-L274")
                    .hasNoCause();
        }

        @Test
        @DisplayName("the payload models no outcome, so response mapping is the service tier's work")
        void thePayloadModelsNoOutcome() {
            assertThat(Arrays.stream(UserCreateRequest.class.getDeclaredFields())
                    .map(Field::getName)
                    .toList())
                    .as("no status, outcome, response or result property exists on the payload: it "
                            + "carries the twelve map fields and nothing else, so the duplicate, "
                            + "success and failure states are represented by the service tier")
                    .noneMatch(name -> {
                        final String lower = name.toLowerCase(Locale.ROOT);
                        return lower.contains("outcome") || lower.contains("respcd")
                                || lower.contains("status") || lower.contains("result");
                    });
        }
    }

    // ==============================================================================================
    // 6. The one payload that legitimately carries a plaintext credential.
    // ==============================================================================================

    /**
     * Asserts that the plaintext credential presented by {@code PASSWDI PIC X(8)} at
     * {@code app/cpy-bms/COUSR01.CPY:78} travels inbound and nothing outbound.
     *
     * <p>Rule 1 clause D forbids secrets in code, logs, tests and config, naming tests explicitly. Two
     * consequences are asserted here. First, the payload must offer no read path to the credential: no
     * getter, no {@code toString}, no {@code equals}, no serialization and no Java deserialization
     * surface. Second, this test file itself must carry no real secret, so every credential below is
     * synthetic and no assertion message ever quotes a credential - failures name the field instead.
     *
     * <p>The ten operators seeded inline at {@code app/jcl/DUSRSECJ.jcl:L35-L44} share one plaintext
     * literal. That literal is deliberately absent from this file, and there is no
     * {@code app/data/ASCII/usrsec.txt} fixture to load it from either: <strong>Not available</strong> -
     * the rows exist only inside that JCL member, fed through {@code IEBGENER}, so no fixture loader is
     * used by this test.
     */
    @Nested
    @DisplayName("6. Credential handling - PASSWDI X(8) at app/cpy-bms/COUSR01.CPY:78 is inbound only")
    class CredentialHandling {

        @Test
        @DisplayName("no read path to the credential exists anywhere on the public surface")
        void noReadPathToTheCredentialExists() {
            assertThat(Arrays.stream(UserCreateRequest.class.getMethods())
                    .map(method -> method.getName())
                    .filter(name -> name.toLowerCase(Locale.ROOT).contains("password"))
                    .toList())
                    .as("the credential's only accessor is the setter; a getPassword() would open a "
                            + "read path for every caller, serializer and reflective bean mapper")
                    .containsExactly("setPassword");
        }

        @Test
        @DisplayName("toString is the inherited identity form and cannot render the credential")
        void toStringIsTheInheritedIdentityForm() {
            final UserCreateRequest payload = populatedPayload();

            assertThat(payload.toString())
                    .as("no toString override exists, so Object's class name and identity hash are "
                            + "all that any log line, debugger expression or exception message can "
                            + "render. A masked override would still be a read path")
                    .startsWith(UserCreateRequest.class.getName() + "@")
                    .doesNotContain(SYNTHETIC_CREDENTIAL);
        }

        @Test
        @DisplayName("equals and hashCode are not overridden, so the credential is never bulk compared")
        void equalsAndHashCodeAreNotOverridden() {
            final List<String> overridden = Arrays.stream(UserCreateRequest.class.getDeclaredMethods())
                    .map(method -> method.getName())
                    .filter(name -> "equals".equals(name) || "hashCode".equals(name))
                    .toList();
            assertThat(overridden)
                    .as("value equality over a credential bearing object would compare the "
                            + "credential as bulk state and would expose it to timing comparison")
                    .isEmpty();

            final UserCreateRequest first = populatedPayload();
            final UserCreateRequest second = populatedPayload();
            second.setPassword(SYNTHETIC_CREDENTIAL_ALTERNATE);
            assertThat(first)
                    .as("identity semantics are retained, so two payloads are never equal on their "
                            + "field values regardless of the credentials they hold")
                    .isNotEqualTo(second)
                    .isEqualTo(first);
        }

        @Test
        @DisplayName("the type is not Serializable, keeping it off Java deserialization paths")
        void theTypeIsNotSerializable() {
            assertThat(Serializable.class.isAssignableFrom(UserCreateRequest.class))
                    .as("a credential bearing object that is Serializable becomes reachable from "
                            + "every ObjectInputStream, which Rule 1 clause D flags as a risky "
                            + "deserialization pattern")
                    .isFalse();
        }

        @Test
        @DisplayName("the credential is never serialized outward, by name or by value")
        void theCredentialIsNeverSerializedOutward() throws Exception {
            final String json = new ObjectMapper().writeValueAsString(populatedPayload());

            assertThat(json)
                    .as("the credential is annotated write only for JSON, so the serializer emits "
                            + "neither the property nor its value; the other eleven fields do appear")
                    .doesNotContain("password")
                    .doesNotContain(SYNTHETIC_CREDENTIAL)
                    .contains("\"userId\":\"" + SEEDED_USER_ID + "\"")
                    .contains("\"firstName\":\"LAWRENCE\"");
        }

        @Test
        @DisplayName("the credential does bind inbound, so it is write only rather than absent")
        void theCredentialBindsInbound() throws Exception {
            final String inbound = "{\"userId\":\"" + SEEDED_USER_ID
                    + "\",\"password\":\"" + SYNTHETIC_CREDENTIAL + "\"}";

            final UserCreateRequest bound = new ObjectMapper().readValue(inbound,
                    UserCreateRequest.class);

            assertThat(bound.getUserId())
                    .as("the payload binds unknown properties as failures, so the credential "
                            + "binding cleanly proves the property is recognised and writable "
                            + "rather than merely ignored")
                    .isEqualTo(SEEDED_USER_ID);
            assertThat(new ObjectMapper().writeValueAsString(bound))
                    .as("having bound inbound, it still cannot come back out")
                    .doesNotContain("password");
        }

        @Test
        @DisplayName("no digest, hash or salt property exists - hashing is the service tier's work")
        void noDigestHashOrSaltPropertyExists() {
            assertThat(Arrays.stream(UserCreateRequest.class.getDeclaredFields())
                    .map(Field::getName)
                    .toList())
                    .as("the store column is a 60 character BCrypt digest, but SEC-USR-PWD is only "
                            + "PIC X(08) at app/cpy/CSUSR01Y.cpy:L21, so a digest never fitted the "
                            + "legacy record and never belongs on a transport object either")
                    .noneMatch(name -> {
                        final String lower = name.toLowerCase(Locale.ROOT);
                        return lower.contains("hash") || lower.contains("digest")
                                || lower.contains("salt") || lower.contains("bcrypt")
                                || lower.contains("encoded");
                    });
        }

        @Test
        @DisplayName("the credential field is 8 wide, matching X(08) and refusing to host a digest")
        void theCredentialFieldIsEightWideAndCannotHostADigest() {
            assertThat(declaredMaxLength("password"))
                    .as("PASSWDI is PIC X(8) at app/cpy-bms/COUSR01.CPY:78 and SEC-USR-PWD is "
                            + "PIC X(08) at app/cpy/CSUSR01Y.cpy:L21; widening this to 60 to "
                            + "accommodate a digest would put the digest on the wire")
                    .isEqualTo(SEC_USR_ID_WIDTH);

            final int populated = SEC_USR_ID_WIDTH + 20 + 20 + SEC_USR_ID_WIDTH + 1;
            assertThat(populated + 23)
                    .as("app/cpy/CSUSR01Y.cpy:L17-L23 is 8+20+20+8+1 populated plus the named "
                            + "SEC-USR-FILLER X(23), totalling exactly %d bytes",
                            SEC_USER_DATA_WIDTH)
                    .isEqualTo(SEC_USER_DATA_WIDTH);
            assertThat(populated)
                    .as("the type character therefore sits at byte 57 of the record")
                    .isEqualTo(57);
        }

        @Test
        @DisplayName("every credential used by this test is obviously synthetic")
        void everyCredentialUsedHereIsObviouslySynthetic() {
            assertThat(List.of(SYNTHETIC_CREDENTIAL, SYNTHETIC_CREDENTIAL_ALTERNATE))
                    .as("the ten operators seeded at app/jcl/DUSRSECJ.jcl:L35-L44 share one upper "
                            + "case alphabetic plaintext literal. Every credential here carries "
                            + "punctuation and mixed case, so none of them can be that literal, and "
                            + "it appears nowhere in this file")
                    .allSatisfy(credential -> {
                        assertThat(credential).hasSize(SEC_USR_ID_WIDTH);
                        assertThat(credential.chars().anyMatch(ch -> !Character.isLetterOrDigit(ch)))
                                .isTrue();
                        assertThat(credential).isNotEqualTo(credential.toUpperCase(Locale.ROOT));
                    })
                    .doesNotHaveDuplicates();
        }
    }

    // ==============================================================================================
    // 7. User type: a raw one character code, with the enum's domain asserted separately.
    // ==============================================================================================

    /**
     * Asserts the user type contract of {@code USRTYPEI PIC X(1)} at
     * {@code app/cpy-bms/COUSR01.CPY:84}, whose domain is the two 88-level condition names at
     * {@code app/cpy/COCOM01Y.cpy:L26-L28}: {@code CDEMO-USER-TYPE PIC X(01)} with
     * {@code 88 CDEMO-USRTYP-ADMIN VALUE 'A'} and {@code 88 CDEMO-USRTYP-USER VALUE 'U'}.
     *
     * <p><strong>The decision, asserted explicitly below: the property is a raw one character
     * {@code String}, not {@link UserType}.</strong> The rationale is parity. Binding an inbound
     * one character field straight to the enum would turn any out of domain value into a
     * deserialization failure and would replace the source program's own message with a framework
     * error, whereas the source carries the value through and reports it. The enum remains the typed
     * domain for everything downstream of binding, and its own contract - two constants, a permissive
     * lookup and a strict lookup that names what it rejected - is asserted here alongside.
     *
     * <p>The source performs no membership check on the type character beyond the presence test of the
     * cascade, so none is added here.
     */
    @Nested
    @DisplayName("7. User type - USRTYPEI X(1), domain 'A'/'U' at app/cpy/COCOM01Y.cpy:L26-L28")
    class UserTypeContract {

        @Test
        @DisplayName("the property is a raw one character String, deliberately not the enum")
        void thePropertyIsARawOneCharacterString() {
            assertThat(declaredField("userType").getType())
                    .as("binding X(1) directly to UserType would reject an out of domain value at "
                            + "the JSON boundary with a framework error, where the source carries it "
                            + "through and answers with its own message. The raw code is carried and "
                            + "the service decides")
                    .isEqualTo(String.class)
                    .isNotEqualTo(UserType.class);
            assertThat(declaredMaxLength("userType")).isEqualTo(1);
        }

        @Test
        @DisplayName("no payload property is typed as the enum")
        void noPayloadPropertyIsTypedAsTheEnum() {
            assertThat(Arrays.stream(UserCreateRequest.class.getDeclaredFields())
                    .map(field -> field.getType().getName())
                    .toList())
                    .as("every one of the twelve COUSR1AI fields is PIC X(n) and is carried as a "
                            + "String; UserType is the domain type used downstream of binding")
                    .doesNotContain(UserType.class.getName())
                    .containsOnly(String.class.getName());
        }

        @Test
        @DisplayName("the enum domain is exactly the two codes the copybook declares")
        void theEnumDomainIsExactlyTwoCodes() {
            assertThat(UserType.values())
                    .as("app/cpy/COCOM01Y.cpy:L27-L28 declares exactly two 88-levels, so a third "
                            + "constant would be an invention")
                    .hasSize(2)
                    .containsExactly(UserType.ADMIN, UserType.USER);
            assertThat(UserType.ADMIN.getCode()).isEqualTo('A');
            assertThat(UserType.USER.getCode()).isEqualTo('U');
        }

        @ParameterizedTest(name = "USRTYPEI value [{0}] round trips byte exactly")
        @ValueSource(strings = {"A", "U", "Z", "a", " "})
        @DisplayName("a single character code round trips byte exactly, in or out of domain")
        void aSingleCharacterCodeRoundTripsByteExactly(final String presented) {
            final UserCreateRequest payload = populatedPayload();
            payload.setUserType(presented);

            assertThat(payload.getUserType())
                    .as("the presented character is retained verbatim: not trimmed, not case folded "
                            + "and not coerced, because repairing it would accept or reject input "
                            + "differently from the source")
                    .isEqualTo(presented)
                    .hasSize(1);
        }

        @ParameterizedTest(name = "code [{0}] resolves to empty, never to a fabricated default")
        @ValueSource(strings = {"", " ", "Z", "a", "u", "AU", "A "})
        @DisplayName("a blank or unrecognised code resolves to empty, never to a fabricated default")
        void aBlankOrUnrecognisedCodeResolvesToEmpty(final String candidate) {
            assertThat(UserType.fromCode(candidate))
                    .as("an unknown code never silently becomes USER, ADMIN, the first declared "
                            + "constant or null; a padded or lower case value is rejected rather "
                            + "than repaired")
                    .isEmpty();
        }

        @Test
        @DisplayName("an absent code resolves to empty rather than raising")
        void anAbsentCodeResolvesToEmpty() {
            assertThat(UserType.fromCode((String) null))
                    .as("a blank screen value maps to no user type at all, which is the honest "
                            + "answer; fabricating a default here would grant a privilege the "
                            + "operator never selected")
                    .isEmpty();
        }

        @ParameterizedTest(name = "code [{0}] resolves to its copybook constant")
        @CsvSource({"A, ADMIN", "U, USER"})
        @DisplayName("the two copybook codes resolve to their constants")
        void theTwoCopybookCodesResolve(final String code, final String constant) {
            assertThat(UserType.fromCode(code))
                    .contains(UserType.valueOf(constant));
        }

        @Test
        @DisplayName("the strict lookup names the offending code rather than falling back")
        void theStrictLookupNamesTheOffendingCode() {
            assertThatThrownBy(() -> UserType.requireFromCode('Z'))
                    .as("an unrecognised code must fail loudly and identify what it rejected, "
                            + "because the legacy records are fixed width and blank padded, so a "
                            + "blank, a low value byte and a wrong letter are otherwise "
                            + "indistinguishable in a log")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Z")
                    .hasMessageContaining("app/cpy/COCOM01Y.cpy")
                    .hasNoCause();
        }

        @Test
        @DisplayName("the payload adds no membership check stricter than the source's")
        void thePayloadAddsNoMembershipCheckStricterThanTheSource() {
            final UserCreateRequest payload = populatedPayload();
            payload.setUserType("Z");

            assertThat(violationsOf(payload))
                    .as("the cascade at app/cbl/COUSR01C.cbl:L142 rejects a blank type and nothing "
                            + "else, so an out of domain character binds cleanly and is reported by "
                            + "the service. A pattern constraint here would reject input the legacy "
                            + "system accepts")
                    .isEmpty();
        }

        @Test
        @DisplayName("a multi character type violates the width, a state distinct from emptiness")
        void aMultiCharacterTypeViolatesTheWidth() {
            final UserCreateRequest payload = populatedPayload();
            payload.setUserType("AU");

            assertThat(violatedPropertiesOf(payload))
                    .as("USRTYPEI is X(1), so two characters overflow the field; this width failure "
                            + "is a different state from the cascade's emptiness message, and the "
                            + "two remain separable")
                    .containsExactly("userType");
            assertThat(firstCascadeFailure(payload, SYNTHETIC_CREDENTIAL))
                    .as("an over long value is not blank, so the emptiness branch is not taken")
                    .isEmpty();
        }
    }

    // ==============================================================================================
    // 8. Boundaries, the three state model, and the absence of session state.
    // ==============================================================================================

    /**
     * Asserts the boundary behaviour of all twelve fields and the three state model of
     * {@code app/cpy/CSSETATY.cpy}.
     *
     * <p>That copybook is a procedural {@code COPY ... REPLACING} template - parameters
     * {@code (TESTVAR1)}, {@code (SCRNVAR2)} and {@code (MAPNAME3)} - and therefore has no data layout
     * and no class of its own. It models exactly {@code OK}, {@code NOT-OK} and {@code BLANK}, colouring
     * a field red when it is either not valid or blank and additionally writing a {@code '*'} marker
     * only when it is blank, and only on re-entry. The same two-messages-for-two-states distinction is
     * visible at {@code app/cbl/COACTUPC.cbl:505-508}, where {@code CRED-LIMIT-IS-BLANK} reads
     * {@code 'Credit Limit must be supplied'} and {@code CRED-LIMIT-IS-NOT-VALID} reads
     * {@code 'Credit Limit is not valid'}.
     *
     * <p>The gated cross field pattern in that same program - verified at {@code :1664-1675}, where the
     * state and ZIP code edit runs only once both single field edits have passed - is why no
     * unconditional class level constraint may model a cross field rule: a class level constraint fires
     * on every submission and produces a different message set from the source's.
     */
    @Nested
    @DisplayName("8. Boundaries and the OK / NOT-OK / BLANK model - app/cpy/CSSETATY.cpy")
    class BoundariesAndStateModel {

        @Test
        @DisplayName("absent, empty, blank and low values remain four distinguishable values")
        void absentBlankAndLowValuesRemainDistinguishable() {
            final String lowValues = String.valueOf(LOW_VALUE).repeat(3);
            final UserCreateRequest absent = new UserCreateRequest();
            final UserCreateRequest empty = new UserCreateRequest();
            empty.setFirstName("");
            final UserCreateRequest blank = new UserCreateRequest();
            blank.setFirstName("   ");
            final UserCreateRequest low = new UserCreateRequest();
            low.setFirstName(lowValues);

            assertThat(absent.getFirstName())
                    .as("absent is the state in which the property never arrived")
                    .isNull();
            assertThat(empty.getFirstName()).isEmpty();
            assertThat(blank.getFirstName()).isEqualTo("   ").isNotEqualTo("");
            assertThat(low.getFirstName()).isEqualTo(lowValues).isNotEqualTo("   ");

            assertThat(isCobolEmpty(absent.getFirstName()))
                    .as("all four share one screen outcome, because the branch predicate unifies "
                            + "them, yet they remain distinct values through the accessor. "
                            + "Collapsing them into a single representation would erase the "
                            + "BLANK versus NOT-OK distinction that CSSETATY.cpy depends on")
                    .isTrue();
            assertThat(isCobolEmpty(blank.getFirstName())).isTrue();
            assertThat(isCobolEmpty(low.getFirstName())).isTrue();
        }

        @Test
        @DisplayName("trailing space padding is preserved, never trimmed")
        void trailingSpacePaddingIsPreserved() {
            final String padded = cobolMove("LEE", 20);
            final UserCreateRequest payload = populatedPayload();
            payload.setFirstName(padded);

            assertThat(payload.getFirstName())
                    .as("FNAMEI is a fixed width X(20) field, so its padding is part of the value; "
                            + "trimming it here would change what the service compares and stores")
                    .isEqualTo(padded)
                    .hasSize(20)
                    .endsWith(" ");
            assertThat(violationsOf(payload))
                    .as("a value padded to exactly its declared width is valid")
                    .isEmpty();
        }

        @Test
        @DisplayName("no class level constraint models a gated cross field edit")
        void noClassLevelConstraintModelsAGatedCrossFieldEdit() {
            assertThat(UserCreateRequest.class.getAnnotations())
                    .as("app/cbl/COACTUPC.cbl:1664-1675 runs its cross field edit only once both "
                            + "single field edits have passed. A class level constraint fires "
                            + "unconditionally and would produce a message set the source never emits")
                    .noneMatch(annotation ->
                            annotation.annotationType().isAnnotationPresent(Constraint.class));
            assertThat(Arrays.stream(UserCreateRequest.class.getDeclaredMethods())
                    .filter(method -> method.isAnnotationPresent(AssertTrue.class))
                    .toList())
                    .as("no @AssertTrue predicate method may stand in for the gated edit either")
                    .isEmpty();
        }

        @ParameterizedTest(name = "{1} X({2}): null, empty, blank, {3} and {2} all bind cleanly")
        @CsvSource({
            "transactionName, TRNNAMEI,  4,  3",
            "title01,         TITLE01I, 40, 39",
            "currentDate,     CURDATEI,  8,  7",
            "programName,     PGMNAMEI,  8,  7",
            "title02,         TITLE02I, 40, 39",
            "currentTime,     CURTIMEI,  8,  7",
            "firstName,       FNAMEI,   20, 19",
            "lastName,        LNAMEI,   20, 19",
            "userId,          USERIDI,   8,  7",
            "password,        PASSWDI,   8,  7",
            "userType,        USRTYPEI,  1,  0",
            "errorMessage,    ERRMSGI,  78, 77",
        })
        @DisplayName("null, empty, blank, one under and at limit all bind cleanly on every field")
        void everyFieldAcceptsNullEmptyBlankOneUnderAndAtLimit(final String property,
                final String cobolField, final int width, final int oneUnder) {
            for (final String candidate : Arrays.asList(
                    null,
                    "",
                    " ".repeat(width),
                    "x".repeat(oneUnder),
                    "x".repeat(width))) {
                final UserCreateRequest payload = populatedPayload();
                applyPresented(payload, property, candidate);

                assertThat(violatedPropertiesOf(payload))
                        .as("%s is PIC X(%d), so nothing up to and including %d characters may be "
                                + "refused; the source answers a blank field with a business "
                                + "message rather than refusing the submission", cobolField, width,
                                width)
                        .doesNotContain(property);
            }
        }

        @ParameterizedTest(name = "{1} X({2}): {3} characters overflow the field")
        @CsvSource({
            "transactionName, TRNNAMEI,  4,  5",
            "title01,         TITLE01I, 40, 41",
            "currentDate,     CURDATEI,  8,  9",
            "programName,     PGMNAMEI,  8,  9",
            "title02,         TITLE02I, 40, 41",
            "currentTime,     CURTIMEI,  8,  9",
            "firstName,       FNAMEI,   20, 21",
            "lastName,        LNAMEI,   20, 21",
            "userId,          USERIDI,   8,  9",
            "password,        PASSWDI,   8,  9",
            "userType,        USRTYPEI,  1,  2",
            "errorMessage,    ERRMSGI,  78, 79",
        })
        @DisplayName("one over the declared width is refused on every field")
        void everyFieldRefusesOneOverItsDeclaredWidth(final String property, final String cobolField,
                final int width, final int oneOver) {
            final UserCreateRequest payload = populatedPayload();
            applyPresented(payload, property, "x".repeat(oneOver));

            assertThat(violatedPropertiesOf(payload))
                    .as("%s is PIC X(%d) so %d characters cannot be carried; the field is not "
                            + "silently widened and the value is not silently clipped at the "
                            + "binding boundary", cobolField, width, oneOver)
                    .containsExactly(property);
        }

        @ParameterizedTest(name = "{0} retains its presented value verbatim")
        @ValueSource(strings = {
            "transactionName",
            "title01",
            "currentDate",
            "programName",
            "title02",
            "currentTime",
            "firstName",
            "lastName",
            "userId",
            "userType",
            "errorMessage",
        })
        @DisplayName("every readable field retains its presented value verbatim")
        void everyReadableFieldRetainsItsValueVerbatim(final String property) {
            final int width = declaredMaxLength(property);
            final String presented = cobolMove("V", width);
            final UserCreateRequest payload = populatedPayload();
            applyPresented(payload, property, presented);

            assertThat(readPresented(payload, property))
                    .as("the payload transports; it does not normalise, so '%s' comes back exactly "
                            + "as it went in, padding included", property)
                    .isEqualTo(presented);
        }

        @Test
        @DisplayName("hostile over length input on six fields reports exactly those six")
        void hostileOverLengthInputReportsEveryOffendingField() {
            final UserCreateRequest hostile = new UserCreateRequest();
            hostile.setUserId("x".repeat(SEC_USR_ID_WIDTH + 1));
            hostile.setPassword("x".repeat(SEC_USR_ID_WIDTH + 1));
            hostile.setFirstName("x".repeat(21));
            hostile.setLastName("x".repeat(21));
            hostile.setUserType("AU");
            hostile.setErrorMessage("x".repeat(ERRMSG_WIDTH + 1));

            assertThat(violatedPropertiesOf(hostile))
                    .as("inputs are untrusted: every field that overflows its PIC width is named, "
                            + "and the report identifies fields rather than quoting values, so a "
                            + "credential is never echoed into a failure message")
                    .containsExactly("errorMessage", "firstName", "lastName", "password", "userId",
                            "userType");
        }

        @Test
        @DisplayName("no COMMAREA session or navigation property is present on the payload")
        void noSessionOrNavigationPropertyIsPresent() {
            final List<String> declared = Arrays.stream(UserCreateRequest.class.getDeclaredFields())
                    .map(Field::getName)
                    .toList();

            assertThat(COMMAREA_SESSION_PROPERTIES)
                    .as("transformation rule 7 of the plan keeps no server side session state, so "
                            + "the COMMAREA routing and screen state fields of "
                            + "app/cpy/COCOM01Y.cpy:L21-L24, :L29 and :L43-L44 have no counterpart "
                            + "on a request payload")
                    .hasSize(7)
                    .allSatisfy(sessionProperty -> assertThat(declared)
                            .doesNotContain(sessionProperty));
        }

        @Test
        @DisplayName("a session or navigation property is refused at binding, not silently ignored")
        void aSessionPropertyIsRefusedAtBinding() throws Exception {
            final ObjectMapper mapper = new ObjectMapper();

            assertThat(COMMAREA_SESSION_PROPERTIES).allSatisfy(sessionProperty -> {
                final String inbound = "{\"userId\":\"" + SEEDED_USER_ID + "\",\""
                        + sessionProperty + "\":\"X\"}";
                assertThatThrownBy(() -> mapper.readValue(inbound, UserCreateRequest.class))
                        .as("the payload refuses unknown properties, so smuggling '%s' in fails "
                                + "loudly instead of being dropped without trace", sessionProperty)
                        .isInstanceOf(UnrecognizedPropertyException.class);
            });

            assertThat(mapper.readValue("{\"userId\":\"" + SEEDED_USER_ID + "\"}",
                    UserCreateRequest.class).getUserId())
                    .as("a payload carrying only mapped fields binds cleanly")
                    .isEqualTo(SEEDED_USER_ID);
        }

        @Test
        @DisplayName("the header date and time come from the fixed clock and fit their X(8) fields")
        void theHeaderDateAndTimeComeFromTheFixedClock() {
            final DateTimeFormatter headerDate =
                    DateTimeFormatter.ofPattern("MM/dd/yy", Locale.ROOT)
                            .withZone(FixedClockProvider.CANONICAL_ZONE);
            final DateTimeFormatter headerTime =
                    DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT)
                            .withZone(FixedClockProvider.CANONICAL_ZONE);
            final String presentedDate =
                    headerDate.format(FixedClockProvider.canonicalClock().instant());
            final String presentedTime =
                    headerTime.format(FixedClockProvider.canonicalClock().instant());

            final UserCreateRequest payload = populatedPayload();
            payload.setCurrentDate(presentedDate);
            payload.setCurrentTime(presentedTime);

            assertThat(presentedDate)
                    .as("CURDATEI is X(8) at app/cpy-bms/COUSR01.CPY:36 and the value is derived "
                            + "from the injected fixed clock, never from the ambient wall clock, "
                            + "and formatted with Locale.ROOT and a fixed zone so the assertion is "
                            + "machine independent")
                    .isEqualTo("06/10/22")
                    .hasSize(declaredMaxLength("currentDate"));
            assertThat(presentedTime)
                    .as("CURTIMEI is X(8) at app/cpy-bms/COUSR01.CPY:54, one byte narrower than the "
                            + "X(9) of app/cpy-bms/COSGN00.CPY:54")
                    .isEqualTo("19:27:53")
                    .hasSize(declaredMaxLength("currentTime"));
            assertThat(violationsOf(payload))
                    .as("both header values fit their declared widths exactly")
                    .isEmpty();
            assertThat(payload.getCurrentDate()).isEqualTo(presentedDate);
            assertThat(payload.getCurrentTime()).isEqualTo(presentedTime);
        }
    }
}
