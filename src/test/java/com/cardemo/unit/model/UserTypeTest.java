/*
 * ******************************************************************
 * Program     : UserTypeTest.java
 * Component   : Unit test tier, resident at
 *               src/test/java/com/cardemo/unit/model
 * Application : CardDemo
 * Type        : JUnit 5 unit test - pure JVM tier, no container, no
 *               Spring context, no database, no live endpoint
 * Function    : Proves that com.cardemo.model.enums.UserType reproduces
 *               the CDEMO-USER-TYPE field contract exactly - two
 *               constants and no more, the single byte codes 'A' and
 *               'U', a case sensitive lookup that never silently
 *               defaults, and a type that stays a pure data holder
 *               carrying no framework, persistence, authorisation or
 *               credential concern.
 * Source      : app/cpy/COCOM01Y.cpy:L26-L28 @ 7756d89
 * Source      : app/jcl/DUSRSECJ.jcl:L35-L44 @ 7756d89
 * Source      : app/cpy/CSUSR01Y.cpy:L17-L23 @ 7756d89
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

import com.cardemo.model.enums.UserType;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit test for {@link UserType}, the typed replacement for the two {@code 88}-level condition names
 * declared on {@code CDEMO-USER-TYPE}.
 *
 * <h2>1. What this test does</h2>
 *
 * <p>It holds {@link UserType} to the field contract of the frozen legacy corpus, read first hand at the
 * traceability anchor commit {@code 7756d89}, and it does so in four movements:
 *
 * <ol>
 *   <li><strong>The constant set is closed.</strong> {@code app/cpy/COCOM01Y.cpy:L26-L28} declares, verbatim,
 *       {@code 10 CDEMO-USER-TYPE PIC X(01).} followed by exactly two condition names -
 *       {@code 88 CDEMO-USRTYP-ADMIN VALUE 'A'.} at L27 and {@code 88 CDEMO-USRTYP-USER VALUE 'U'.} at L28.
 *       Two condition names means two constants: no {@code UNKNOWN}, {@code NONE}, {@code NULL_TYPE},
 *       {@code UNSPECIFIED} or {@code SYSTEM} may exist, because none exists in the source.</li>
 *   <li><strong>The lookups are exact, total and never permissive.</strong> The field is one byte wide, so
 *       the codes are asserted as the characters {@code 'A'} and {@code 'U'} rather than as names, ordinals
 *       or paraphrases. Every out of domain input - lower case, blank, low value, digit, over long, blank
 *       padded and non ASCII - is asserted to yield an empty result or a named failure, never a silent
 *       fallback onto either constant.</li>
 *   <li><strong>The type stays a pure data holder.</strong> Reflection and a constant pool scan of the
 *       compiled class prove that {@link UserType} declares no interface of its own, carries no annotation,
 *       exposes no authority naming convention, holds no credential or personally identifiable field, and
 *       references no persistence, framework or cross layer type.</li>
 *   <li><strong>Real seed data exercises both constants.</strong> {@code app/jcl/DUSRSECJ.jcl:L35-L44}
 *       supplies ten user records in stream to {@code IEBGENER}. Under the {@code app/cpy/CSUSR01Y.cpy}
 *       layout - {@code SEC-USR-ID PIC X(08)}, {@code SEC-USR-FNAME PIC X(20)},
 *       {@code SEC-USR-LNAME PIC X(20)}, {@code SEC-USR-PWD PIC X(08)}, {@code SEC-USR-TYPE PIC X(01)},
 *       {@code SEC-USR-FILLER PIC X(23)}, totalling the {@code LRECL=80} that
 *       {@code app/jcl/DUSRSECJ.jcl:L48} declares - the type byte follows 8 + 20 + 20 + 8 = 56 preceding
 *       bytes and therefore sits at record position 57. Across the ten records that byte takes two distinct
 *       values in a five and five split: {@code 'A'} on L35 to L39 and {@code 'U'} on L40 to L44.</li>
 * </ol>
 *
 * <h2>2. How to build, run and test</h2>
 *
 * <pre>
 * mvn -B -o test -Dtest=UserTypeTest     # this class alone
 * mvn -B clean test                      # the whole unit tier
 * mvn -B clean verify                    # unit tier plus coverage and dependency gates
 * </pre>
 *
 * <p><strong>Which plugin collects this class matters, and the failure mode is silent.</strong>
 * {@code maven-surefire-plugin} 3.5.4 is configured with the includes {@code **}{@code /*Test.java} and
 * {@code **}{@code /*Tests.java} and the path excludes {@code **}{@code /integration/**} and
 * {@code **}{@code /e2e/**}, so a class named {@code *Test} anywhere under {@code src/test/java} outside
 * those two package trees runs at the {@code test} phase, and {@code maven-failsafe-plugin} takes only
 * the two excluded trees at {@code verify}. Moving this file into {@code integration} or {@code e2e}
 * would hand it to Failsafe and change when it runs; renaming it so that it ends in neither
 * {@code Test} nor {@code Tests} would leave it collected by neither plugin. That second mistake produces
 * a green build with both plugins reporting success and no error and no warning anywhere, so verify the
 * report exists rather than trusting the exit code:
 *
 * <pre>
 * target/surefire-reports/TEST-com.cardemo.unit.model.UserTypeTest.xml
 * </pre>
 *
 * <h2>3. Key configuration and defaults</h2>
 *
 * <ul>
 *   <li><strong>Determinism.</strong> Nothing here reads the wall clock, the ambient time zone, the default
 *       locale, the default charset or a random source. {@link Locale#ROOT} is passed explicitly to both of
 *       the case folding operations this class performs, and the constant pool scan decodes with an explicit
 *       {@link StandardCharsets#ISO_8859_1} so that the byte to character mapping cannot vary by platform.
 *       Every non ASCII test input is written as a Unicode escape rather than as a literal character, so the
 *       source file itself is pure ASCII and cannot be mis-decoded.</li>
 *   <li><strong>The fixed clock is deliberately not used.</strong> {@code FixedClockProvider} in this same
 *       package is the only sanctioned source of time for the unit tier, and it is not referenced here
 *       because {@link UserType} reads no clock, generates no timestamp and has no time dependent
 *       behaviour to pin. Injecting it would add a dependency with nothing to hold. A {@code FixtureLoader}
 *       is <strong>Not available</strong> in this tree; none is needed, because the ten seed records are
 *       in stream JCL data rather than a fixture file - see section 5.</li>
 *   <li><strong>Mockito is deliberately not used.</strong> {@link UserType} has no collaborator, performs no
 *       I/O and reads no configuration, so there is nothing to stub and no interaction to verify. Mockito
 *       5.17.0 is on the test classpath through {@code spring-boot-starter-test}, and its strict stubs
 *       default would fail this class for unnecessary stubbing if a mock were introduced without cause.</li>
 *   <li><strong>Pinned versions, resolved by the build descriptor.</strong> JUnit Jupiter 5.12.2, AssertJ
 *       3.27.7 and Mockito 5.17.0 arrive through {@code spring-boot-starter-test}; this class adds no
 *       dependency of its own.</li>
 *   <li><strong>Privilege.</strong> This is a pure JVM test. It opens no socket, starts no container, reads
 *       no environment variable, holds no credential and can reach no endpoint, so it needs no privilege to
 *       run and no cleanup after it.</li>
 * </ul>
 *
 * <h2>4. Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><strong>The build fails at {@code testCompile} rather than at a test.</strong>
 *       {@code maven-compiler-plugin} 3.14.1 is configured with {@code failOnWarning}, {@code -Xlint:all}
 *       and {@code -Werror}, and that configuration reaches test compilation. An unused import, a raw type,
 *       a deprecated call or an unchecked conversion is therefore a hard build failure and not a warning.
 *       Read the {@code [ERROR]} line: it names the offending import or expression.</li>
 *   <li><strong>A constant set assertion fails.</strong> A third constant was added to {@link UserType}.
 *       The copybook is the authority, not the enum: confirm against
 *       {@code app/cpy/COCOM01Y.cpy:L26-L28} before changing this test, because {@code app/} is frozen and
 *       cannot have grown a condition name.</li>
 *   <li><strong>A hostile input assertion fails.</strong> A lookup was made permissive - trimming, case
 *       folding or defaulting. That is a behaviour change, not a fix: {@code app/cbl/COSGN00C.cbl} folds
 *       only the entered user id and password, moves {@code SEC-USR-TYPE} through unfolded, and then tests
 *       the byte against {@code 'A'} exactly, so a lower case or padded type byte must match neither
 *       constant.</li>
 *   <li><strong>The constant pool scan fails.</strong> A persistence annotation, a Spring type or a cross
 *       layer import reached {@link UserType}. Persistence of the type byte belongs to the user security
 *       entity through an attribute converter, and role mapping belongs to the security and configuration
 *       packages; neither belongs on the enum.</li>
 *   <li><strong>Fixture name trap, for the tiers that do load fixtures.</strong> The daily transaction
 *       fixture is {@code app/data/ASCII/dailytran.txt} - the word spelled in full. The mainframe DD name
 *       and dataset are {@code DALYTRAN}, so {@code dalytran.txt} is the intuitive spelling and it does not
 *       exist. A test resource path using it fails at run time with a null stream, not at compile time.
 *       This class loads no fixture and is immune, but the trap is recorded here because this tier is where
 *       it bites.</li>
 * </ul>
 *
 * <h2>5. Evidence, severity and what is not available</h2>
 *
 * <ul>
 *   <li><strong>Blocker, avoided by construction.</strong> A class in this tier that is collected by neither
 *       Surefire nor Failsafe reports success while running nothing. Remediation: keep the name ending in
 *       {@code Test} and the package at {@code com.cardemo.unit.model}, and confirm the report file named in
 *       section 2 exists after every run.</li>
 *   <li><strong>High, and the reason one input set is absent.</strong> Each of the ten records at
 *       {@code app/jcl/DUSRSECJ.jcl:L35-L44} carries a plain text password in the eight bytes at positions
 *       49 to 56. That literal appears nowhere in this file and nowhere under {@code src/}, because Rule 1
 *       clause D forbids secrets in tests by name. Consequently this class does <strong>not</strong>
 *       reconstruct seed records: it encodes only the ten type characters, so no credential byte can be
 *       present even by accident. Hashing of that field is exercised where the credential actually lives,
 *       against BCrypt strength 10, and not here.</li>
 *   <li><strong>Medium, and expected: {@code verify} does not go green on this class alone.</strong> The
 *       coverage gate is declared at bundle level with a floor of 0.80, so it measures the whole project
 *       rather than one class. This class takes {@link UserType} to full line coverage, but the remaining
 *       main classes are covered by their own test classes, and until those exist the bundle ratio stays far
 *       below the floor and {@code mvn verify} stops at {@code jacoco:check}. Remediation: run
 *       {@code mvn -B clean test} while working on this class and read the per class figure in
 *       {@code target/site/jacoco/index.html}; expect {@code verify} to go green only once the test tier is
 *       complete. Lowering the floor or adding a coverage exclusion is not a remediation and is not
 *       permitted.</li>
 *   <li><strong>Low, and out of this file's reach: the vulnerability gate is dependency driven.</strong>
 *       {@code dependency-check:check} fails the build at CVSS 7.0 and upwards on transitive artefacts of
 *       the pinned dependency set, so it is governed entirely by the build descriptor and is unaffected by
 *       any test source. Two operational notes for anyone reading a build log: the goal requires online
 *       mode, so an offline run skips it with a warning and a green offline build is therefore not evidence
 *       that the dependency set is clean; and the documented override
 *       {@code -Ddependency-check.skip=true} exists for the jobs that delegate the scan, not as a way to
 *       clear a finding. Remediation belongs with the dependency pins, not here.</li>
 *   <li><strong>Not available: a {@code usrsec.txt} fixture.</strong> The other nine datasets have ASCII
 *       fixtures under {@code app/data/ASCII/}; the user security records do not, existing only as the in
 *       stream {@code SYSUT1 DD *} data cited above. Nothing needs to be supplied - the in stream data is
 *       the authority - but any test that tries to load such a file will fail with a null stream.</li>
 *   <li><strong>Not available: the stored column type beyond its domain.</strong> The schema restricts the
 *       stored type byte to {@code 'A'} or {@code 'U'}, which is asserted here from the copybook because the
 *       copybook is the authority for the domain. Any further SQL detail - declared type, length,
 *       nullability, constraint name - is <strong>Not available</strong> from the sources this test is
 *       entitled to read, and is deliberately not guessed. To assert it, the schema migration would have to
 *       be supplied and read directly.</li>
 * </ul>
 *
 * @see UserType
 */
class UserTypeTest {

    /**
     * The name of the compiled class file this test scans, resolved relative to the package of
     * {@link UserType} by {@link Class#getResourceAsStream(String)}.
     */
    private static final String USER_TYPE_CLASS_FILE = "UserType.class";

    /**
     * The one character code that {@code 88 CDEMO-USRTYP-ADMIN VALUE 'A'.} binds at
     * {@code app/cpy/COCOM01Y.cpy:L27}, transcribed here as a literal so that the assertion is against the
     * copybook rather than against the production constant.
     */
    private static final char COPYBOOK_ADMIN_CODE = 'A';

    /**
     * The one character code that {@code 88 CDEMO-USRTYP-USER VALUE 'U'.} binds at
     * {@code app/cpy/COCOM01Y.cpy:L28}.
     */
    private static final char COPYBOOK_USER_CODE = 'U';

    /**
     * The ten {@code SEC-USR-TYPE} bytes of the seeded user security records, read in record order from
     * position 57 of {@code app/jcl/DUSRSECJ.jcl:L35-L44}.
     *
     * <p>This is a {@link String} rather than a {@code char[]} on purpose: a {@code static final} array is
     * mutable content behind an immutable reference, which is the global mutable state Rule 1 clause B
     * forbids, whereas a string constant cannot be altered by one test and observed by another.
     *
     * <p><strong>Only the type bytes are encoded.</strong> The records also carry a name and a plain text
     * password, and reproducing a record here would place a credential in a test file. Position 57 is
     * derived rather than sliced: {@code SEC-USR-ID PIC X(08)} plus {@code SEC-USR-FNAME PIC X(20)} plus
     * {@code SEC-USR-LNAME PIC X(20)} plus {@code SEC-USR-PWD PIC X(08)} occupy the 56 preceding bytes.
     */
    private static final String SEEDED_TYPE_BYTES = "AAAAAUUUUU";

    /**
     * The number of seeded records that carry each of the two type bytes - five administrators on
     * {@code app/jcl/DUSRSECJ.jcl:L35-L39} and five standard users on {@code :L40-L44}.
     */
    private static final int SEEDED_RECORDS_PER_TYPE = 5;

    /**
     * Internal names that must not appear in the constant pool of the compiled {@link UserType}, each one
     * a layer or framework the enum is required to stay clear of.
     *
     * <p>The list is deliberately expressed as internal names with slash separators, which is the form the
     * class file uses, so that a match means a genuine type reference rather than a coincidence in a string
     * literal or a comment. Comments do not survive compilation at all.
     */
    private static final List<String> FORBIDDEN_INTERNAL_NAMES = List.of(
            "org/springframework",
            "GrantedAuthority",
            "jakarta/persistence",
            "javax/persistence",
            "jakarta/validation",
            "org/hibernate",
            "com/fasterxml/jackson",
            "com/cardemo/exception",
            "com/cardemo/repository",
            "com/cardemo/service",
            "com/cardemo/controller",
            "com/cardemo/batch",
            "com/cardemo/security",
            "com/cardemo/config",
            "com/cardemo/observability");

    /**
     * Lower case fragments that must not occur in the name of any member of {@link UserType}, covering
     * credential material and the personally identifiable fields the customer and user layouts carry.
     *
     * <p>Matching is performed after folding the member name with {@link Locale#ROOT}, so the check cannot
     * change behaviour under a locale whose case rules differ, such as Turkish.
     */
    private static final List<String> FORBIDDEN_MEMBER_NAME_FRAGMENTS = List.of(
            "password",
            "passwd",
            "pwd",
            "secret",
            "credential",
            "token",
            "hash",
            "salt",
            "cipher",
            "ssn",
            "socialsecurity",
            "birth",
            "dob",
            "phone",
            "email",
            "address",
            "authority",
            "granted");

    // ==================================================================
    // Movement 1 - the constant set is closed at exactly two, and the
    // external meaning of a constant is carried by its code and never by
    // its declaration position.
    // Source: app/cpy/COCOM01Y.cpy:L26-L28 @ 7756d89
    // ==================================================================

    @Test
    @DisplayName("declares exactly two constants, one per 88-level condition name and no more")
    void declaresExactlyTheTwoCopybookConditionNames() {
        final UserType[] constants = UserType.values();

        assertThat(constants)
                .as("app/cpy/COCOM01Y.cpy:L26-L28 declares two condition names on CDEMO-USER-TYPE "
                        + "PIC X(01), so the enum must hold two constants and no more")
                .hasSize(2)
                .containsExactlyInAnyOrder(UserType.ADMIN, UserType.USER);
    }

    @Test
    @DisplayName("declares ADMIN first, matching the order of the condition names in the copybook")
    void declaresAdminFirstMatchingTheConditionNameOrder() {
        final List<String> constantNames = Arrays.stream(UserType.values()).map(Enum::name).toList();

        assertThat(constantNames)
                .as("CDEMO-USRTYP-ADMIN is declared at L27 and CDEMO-USRTYP-USER at L28, so ADMIN "
                        + "precedes USER; this is the only assertion in this class that depends on "
                        + "declaration order, because order carries no external meaning")
                .containsExactly("ADMIN", "USER");
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNKNOWN", "NONE", "NULL_TYPE", "UNSPECIFIED", "SYSTEM"})
    @DisplayName("holds no widening constant: UNKNOWN, NONE, NULL_TYPE, UNSPECIFIED and SYSTEM are absent")
    void holdsNoConstantThatWouldWidenTheClosedDomain(final String absentConstantName) {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .as("%s has no condition name at app/cpy/COCOM01Y.cpy:L26-L28; adding it would invent a "
                        + "user class the frozen corpus does not have", absentConstantName)
                .isThrownBy(() -> UserType.valueOf(absentConstantName))
                .withMessageContaining(absentConstantName);

        assertThat(Arrays.stream(UserType.values()).map(Enum::name).toList())
                .as("%s must not appear in the constant set either", absentConstantName)
                .doesNotContain(absentConstantName);
    }

    @Test
    @DisplayName("ADMIN carries the code 'A' transcribed from CDEMO-USRTYP-ADMIN VALUE 'A'")
    void adminCarriesTheCopybookCodeA() {
        assertThat(UserType.ADMIN.getCode())
                .as("app/cpy/COCOM01Y.cpy:L27 binds VALUE 'A' to CDEMO-USRTYP-ADMIN")
                .isEqualTo(COPYBOOK_ADMIN_CODE);
    }

    @Test
    @DisplayName("USER carries the code 'U' transcribed from CDEMO-USRTYP-USER VALUE 'U'")
    void userCarriesTheCopybookCodeU() {
        assertThat(UserType.USER.getCode())
                .as("app/cpy/COCOM01Y.cpy:L28 binds VALUE 'U' to CDEMO-USRTYP-USER")
                .isEqualTo(COPYBOOK_USER_CODE);
    }

    @Test
    @DisplayName("the code accessor, never the ordinal, carries the external meaning of a constant")
    void theCodeAccessorAndNotTheOrdinalCarriesTheExternalMeaning() {
        // Each iteration asserts a property of the constant it is handed, so the assertions hold
        // independently of the order values() returns and independently of how many constants exist.
        for (final UserType userType : UserType.values()) {
            assertThat(UserType.fromCode(userType.getCode()))
                    .as("resolving %s by its own code must return %s; this round trip is what couples "
                            + "the lookup labels to the constants so the two cannot drift apart",
                            userType, userType)
                    .contains(userType);

            assertThat(UserType.fromCode((char) userType.ordinal()))
                    .as("the ordinal of %s, read as a byte, must not resolve to anything - a persisted "
                            + "or transmitted ordinal would silently change if a constant were inserted",
                            userType)
                    .isEmpty();

            assertThat(UserType.fromCode(Character.forDigit(userType.ordinal(), 10)))
                    .as("the printed decimal ordinal of %s must not resolve to anything either", userType)
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("values() hands back a defensive copy, so the constant set is never shared mutable state")
    void valuesHandsBackADefensiveCopyOfTheConstantSet() {
        final UserType[] firstCall = UserType.values();

        firstCall[0] = UserType.USER;
        firstCall[1] = UserType.USER;

        assertThat(UserType.values())
                .as("mutating the array one caller received must not be observable by the next caller, "
                        + "which is what keeps the constant set out of global mutable state")
                .containsExactly(UserType.ADMIN, UserType.USER);
    }

    // ==================================================================
    // Movement 2 - the lookups are exact and total. Every input is
    // treated as untrusted, every boundary condition is handled
    // explicitly, and nothing ever falls back onto a default.
    // Source: app/cpy/COCOM01Y.cpy:L26-L28 @ 7756d89
    // ==================================================================

    @Test
    @DisplayName("fromCode(char) resolves both copybook codes to their constants")
    void fromCharResolvesBothCopybookCodes() {
        assertThat(UserType.fromCode(COPYBOOK_ADMIN_CODE))
                .as("the byte 'A' is the CDEMO-USRTYP-ADMIN value at app/cpy/COCOM01Y.cpy:L27")
                .contains(UserType.ADMIN);

        assertThat(UserType.fromCode(COPYBOOK_USER_CODE))
                .as("the byte 'U' is the CDEMO-USRTYP-USER value at app/cpy/COCOM01Y.cpy:L28")
                .contains(UserType.USER);
    }

    @ParameterizedTest
    @ValueSource(chars = {
        'a', 'u', ' ', '\0', 'X', '0', 'Z', '1', '-', '\t', '\n',
        '\u0391', '\u0410', '\uFF21', '\uFF35', '\u00C0'})
    @DisplayName("fromCode(char) rejects every character outside the two codes, including hostile ones")
    void fromCharRejectsEveryCharacterOutsideTheTwoCodes(final char hostileCode) {
        // The set is deliberately adversarial rather than representative. It covers the lower case
        // twins of both codes, which a case folding lookup would wrongly accept; a blank and a low
        // value byte, which fixed width records pad and initialise with; an unrelated upper case
        // letter and two digits; a separator; two control characters; and four non ASCII look alikes
        // written as Unicode escapes so this source file stays pure ASCII - Greek capital alpha
        // U+0391 and Cyrillic capital A U+0410 are visually indistinguishable from 'A', the
        // fullwidth forms U+FF21 and U+FF35 are the wide variants of 'A' and 'U', and U+00C0 is an
        // accented 'A' that would collide under any accent stripping normalisation.
        assertThat(UserType.fromCode(hostileCode))
                .as("app/cbl/COSGN00C.cbl tests the type byte against 'A' exactly, so nothing but the "
                        + "two copybook bytes may resolve")
                .isEmpty();
    }

    @Test
    @DisplayName("fromCode(String) resolves both copybook codes to their constants")
    void fromStringResolvesBothCopybookCodes() {
        assertThat(UserType.fromCode(String.valueOf(COPYBOOK_ADMIN_CODE)))
                .as("the string overload carries the type across a boundary that serialises it as text")
                .contains(UserType.ADMIN);

        assertThat(UserType.fromCode(String.valueOf(COPYBOOK_USER_CODE)))
                .as("the string overload carries the type across a boundary that serialises it as text")
                .contains(UserType.USER);
    }

    @ParameterizedTest
    @NullSource
    @DisplayName("fromCode(String) returns an empty result for a null reference rather than throwing")
    void fromStringReturnsEmptyForANullReference(final String nullCode) {
        assertThat(UserType.fromCode(nullCode))
                .as("a null must be handled explicitly: an absent value is not an error at this layer, "
                        + "and it must not surface as a NullPointerException from a lookup")
                .isEmpty();
    }

    @ParameterizedTest
    @EmptySource
    @DisplayName("fromCode(String) returns an empty result for the empty string")
    void fromStringReturnsEmptyForTheEmptyString(final String emptyCode) {
        assertThat(UserType.fromCode(emptyCode))
                .as("the empty string is the length zero case of a one byte field and must not resolve")
                .isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"A ", " A", "U ", " U", "AU", "UA", "AA", "ADMIN", "USER", "  ", "A\0",
        "AAAAAUUUUU", "A       "})
    @DisplayName("fromCode(String) rejects any value that is not exactly one character long")
    void fromStringRejectsAnyValueThatIsNotExactlyOneCharacter(final String wrongLengthCode) {
        // The blank padded forms matter most: SEC-USR-TYPE lives inside an 80 byte fixed width record,
        // so a naive substring or a trailing pad is the realistic way a two character value arrives.
        // Trimming it here would accept input the legacy condition name test refuses.
        assertThat(UserType.fromCode(wrongLengthCode))
                .as("a value of length %d is not a CDEMO-USER-TYPE PIC X(01) byte and must not be "
                        + "trimmed, padded or otherwise repaired into one", wrongLengthCode.length())
                .isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "u", "X", "0", " ", "\t", "\u0391", "\u0410", "\uFF21", "\uFF35"})
    @DisplayName("fromCode(String) rejects single characters that are outside the two codes")
    void fromStringRejectsSingleCharactersOutsideTheTwoCodes(final String outOfDomainCode) {
        assertThat(UserType.fromCode(outOfDomainCode))
                .as("the string overload must be exactly as strict as the character overload it delegates "
                        + "to, with no case folding of its own")
                .isEmpty();
    }

    @ParameterizedTest
    @ValueSource(chars = {'a', 'u', ' ', '\0', 'X', '0', '\u0391', '\u0410'})
    @DisplayName("an unrecognised code never silently defaults to USER, to ADMIN or to the first constant")
    void anUnrecognisedCodeNeverSilentlyDefaults(final char unrecognisedCode) {
        final Optional<UserType> resolved = UserType.fromCode(unrecognisedCode);

        assertThat(resolved)
                .as("an unsafe default would let an unknown byte be treated as a real user class")
                .isEmpty()
                .isNotEqualTo(Optional.of(UserType.ADMIN))
                .isNotEqualTo(Optional.of(UserType.USER));

        assertThat(resolved.orElse(null))
                .as("unwrapping the empty result must yield nothing, not the first declared constant")
                .isNull();

        assertThatExceptionOfType(IllegalArgumentException.class)
                .as("the strict overload must fail rather than substitute a default")
                .isThrownBy(() -> UserType.requireFromCode(unrecognisedCode));
    }

    @Test
    @DisplayName("requireFromCode resolves both copybook codes without throwing")
    void requireFromCodeResolvesBothCopybookCodes() {
        assertThat(UserType.requireFromCode(COPYBOOK_ADMIN_CODE))
                .as("a recognised code must be returned, never wrapped and never null")
                .isSameAs(UserType.ADMIN);

        assertThat(UserType.requireFromCode(COPYBOOK_USER_CODE))
                .as("a recognised code must be returned, never wrapped and never null")
                .isSameAs(UserType.USER);
    }

    @ParameterizedTest
    @MethodSource("unrecognisedCodesWithTheirHexadecimalCodePoints")
    @DisplayName("requireFromCode names the offending value, its code point and the two valid codes")
    void requireFromCodeNamesTheOffendingValueAndItsCodePoint(final char unrecognisedCode,
            final String expectedHexadecimalCodePoint) {
        // The hexadecimal code point is not decoration. A blank, a low value byte and a wrong letter
        // are indistinguishable once a message is rendered into a log, and the records this type reads
        // are blank padded, so the numeric form is what makes the failure diagnosable.
        assertThatExceptionOfType(IllegalArgumentException.class)
                .as("an out of domain code must fail with a message an operator can act on without "
                        + "opening the source")
                .isThrownBy(() -> UserType.requireFromCode(unrecognisedCode))
                .withMessageContainingAll(
                        "'" + unrecognisedCode + "'",
                        "code point 0x" + expectedHexadecimalCodePoint,
                        "app/cpy/COCOM01Y.cpy:L27-L28",
                        "'A'",
                        "'U'");
    }

    @Test
    @DisplayName("requireFromCode fails as exactly IllegalArgumentException and wraps no cause")
    void requireFromCodeFailsExactlyAndWrapsNoCause() {
        // Rule 1 clause B requires that nothing is swallowed and that context is preserved. There is
        // no lower level failure to preserve here: the rejection originates in this check, so the
        // correct shape is a cause-free exception whose message carries the whole context. Asserting
        // the absence of a cause is what proves nothing was discarded on the way out.
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> UserType.requireFromCode('X'))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .withNoCause();
    }

    /**
     * Supplies unrecognised codes paired with the hexadecimal rendering of their code point, as
     * {@code Integer.toHexString} produces it: no {@code 0x} prefix, no leading zeroes and lower case
     * digits. Feeds {@link #requireFromCodeNamesTheOffendingValueAndItsCodePoint(char, String)}.
     *
     * <p>The expected renderings are written out as literals rather than computed, so that the test
     * pins the message format independently instead of restating the production expression.
     *
     * @return one argument pair per unrecognised code under test
     */
    static Stream<Arguments> unrecognisedCodesWithTheirHexadecimalCodePoints() {
        return Stream.of(
                Arguments.of('a', "61"),
                Arguments.of('u', "75"),
                Arguments.of(' ', "20"),
                Arguments.of('\0', "0"),
                Arguments.of('X', "58"),
                Arguments.of('0', "30"),
                Arguments.of('\u0391', "391"),
                Arguments.of('\u0410', "410"));
    }

    // ==================================================================
    // Movement 3 - the type stays a pure data holder. Persistence,
    // authorisation, framework coupling and credential material all
    // belong elsewhere, and their absence is asserted rather than
    // assumed.
    // ==================================================================

    @Test
    @DisplayName("declares no interface of its own, deliberately including Serializable")
    void declaresNoInterfaceOfItsOwnDeliberatelyIncludingSerializable() {
        // Comparable and Serializable arrive from java.lang.Enum, which every enum extends and which
        // no enum can decline, so the meaningful assertion is that UserType re-declares neither. The
        // compiler will not make that assertion for us: -Xlint:serial reports a Serializable type with
        // no explicit serialVersionUID, and -Werror escalates the report to a failure, but javac
        // exempts enums from that check - so a re-declaration here would compile silently while
        // announcing a serial form the type deliberately does not define.
        assertThat(UserType.class.getInterfaces())
                .as("a pure data holder implements nothing: no marker, no comparator contract of its "
                        + "own and no framework interface")
                .isEmpty();

        assertThat(UserType.class.getSuperclass())
                .as("the only supertype is java.lang.Enum, so no abstract base class has been "
                        + "introduced to share behaviour across the enums")
                .isEqualTo(Enum.class);
    }

    @Test
    @DisplayName("carries no annotation on the type, its fields, its methods or its constructor")
    void carriesNoAnnotationOnTheTypeOrOnAnyOfItsMembers() {
        // Reflection sees only runtime retained annotations, which is exactly the retention that
        // jakarta.persistence and the Spring stereotypes use, so this catches the cases that matter
        // most. The constant pool scan below is the complement: it sees every retention, including
        // source and class, because it reads the compiled image rather than the reflective view.
        assertThat(UserType.class.getDeclaredAnnotations())
                .as("no @Entity, @Converter, @Enumerated, @Component or validation annotation may sit "
                        + "on the enum; the stored type byte is mapped by the user security entity "
                        + "through an attribute converter, which is where that concern belongs")
                .isEmpty();

        for (final Field field : UserType.class.getDeclaredFields()) {
            assertThat(field.getDeclaredAnnotations())
                    .as("field %s must carry no annotation", field.getName())
                    .isEmpty();
        }

        for (final Method method : UserType.class.getDeclaredMethods()) {
            assertThat(method.getDeclaredAnnotations())
                    .as("method %s must carry no annotation", method.getName())
                    .isEmpty();
        }

        for (final Constructor<?> constructor : UserType.class.getDeclaredConstructors()) {
            assertThat(constructor.getDeclaredAnnotations())
                    .as("the constructor taking %d parameter(s) must carry no annotation",
                            constructor.getParameterCount())
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("declares exactly the two constants plus one private final char, and nothing else")
    void declaresExactlyTheTwoConstantsAndTheSingleCharacterCodeField() throws NoSuchFieldException {
        final List<String> declaredFieldNames = Arrays.stream(UserType.class.getDeclaredFields())
                .filter(field -> !field.isSynthetic())
                .map(Field::getName)
                .toList();

        assertThat(declaredFieldNames)
                .as("the compiler adds a synthetic values array, which is filtered out here; what "
                        + "remains is the entire state of the type, and an extra field is the way "
                        + "credential or personally identifiable data would arrive")
                .containsExactlyInAnyOrder("ADMIN", "USER", "code");

        final Field codeField = UserType.class.getDeclaredField("code");

        assertThat(codeField.getType())
                .as("CDEMO-USER-TYPE is PIC X(01), a single byte, so the representation is one char - "
                        + "not a String, not an int and not an ordinal")
                .isEqualTo(char.class);
        assertThat(Modifier.isPrivate(codeField.getModifiers()))
                .as("the code field must be private, reachable only through the accessor")
                .isTrue();
        assertThat(Modifier.isFinal(codeField.getModifiers()))
                .as("the code field must be final, so a constant cannot be re-coded at run time")
                .isTrue();
        assertThat(Modifier.isStatic(codeField.getModifiers()))
                .as("the code field must be per constant, not shared across the type")
                .isFalse();

        for (final String constantName : List.of("ADMIN", "USER")) {
            final Field constantField = UserType.class.getDeclaredField(constantName);

            assertThat(constantField.getType())
                    .as("%s must be a constant of the enum itself", constantName)
                    .isEqualTo(UserType.class);
            assertThat(Modifier.isPublic(constantField.getModifiers()))
                    .as("%s must be publicly reachable", constantName)
                    .isTrue();
            assertThat(Modifier.isStatic(constantField.getModifiers()))
                    .as("%s must be static", constantName)
                    .isTrue();
            assertThat(Modifier.isFinal(constantField.getModifiers()))
                    .as("%s must be final", constantName)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("holds no mutable state, because every declared field is final")
    void holdsNoMutableStateBecauseEveryDeclaredFieldIsFinal() {
        // The synthetic values array is included on purpose: it is static, it is shared by every
        // caller, and it is the one place a non final field would turn the constant set into global
        // mutable state.
        for (final Field field : UserType.class.getDeclaredFields()) {
            assertThat(Modifier.isFinal(field.getModifiers()))
                    .as("field %s must be final; a mutable field on a type whose instances are shared "
                            + "process wide is global mutable state by definition", field.getName())
                    .isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_ADMIN", "ROLE_USER"})
    @DisplayName("exposes no ROLE_ prefixed constant: authority naming is not this type's concern")
    void exposesNoRolePrefixedConstant(final String authorityName) {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .as("%s is a Spring Security authority name, not a CDEMO-USER-TYPE value; mapping the "
                        + "code onto an authority belongs to the security and configuration packages, "
                        + "and keeping it out of here is what keeps the enum free of Spring",
                        authorityName)
                .isThrownBy(() -> UserType.valueOf(authorityName))
                .withMessageContaining(authorityName);

        assertThat(declaredMemberNames())
                .as("no member of the enum may carry an authority naming convention either")
                .noneMatch(memberName -> memberName.toUpperCase(Locale.ROOT).startsWith("ROLE_"));
    }

    @Test
    @DisplayName("exposes no member shaped like a credential or personally identifiable field")
    void exposesNoCredentialOrPersonallyIdentifiableMember() {
        for (final String memberName : declaredMemberNames()) {
            final String foldedMemberName = memberName.toLowerCase(Locale.ROOT);

            assertThat(FORBIDDEN_MEMBER_NAME_FRAGMENTS)
                    .as("member %s must not be shaped like credential or personally identifiable "
                            + "state: the seeded records at app/jcl/DUSRSECJ.jcl:L35-L44 carry a "
                            + "password field and the customer layout carries a social security "
                            + "number, and neither may reach a one byte user class", memberName)
                    .noneMatch(foldedMemberName::contains);
        }

        final List<Class<?>> instanceFieldTypes = Arrays.stream(UserType.class.getDeclaredFields())
                .filter(field -> !field.isSynthetic())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .map(Field::getType)
                .toList();

        assertThat(instanceFieldTypes)
                .as("the whole instance state is one primitive char, so there is no reference field a "
                        + "secret could be parked in - not a String, not a char array and not a byte "
                        + "array")
                .containsExactly(char.class);
    }

    @Test
    @DisplayName("the compiled class references no persistence, framework or cross layer type")
    void theCompiledClassReferencesNoFrameworkOrCrossLayerType() throws IOException {
        final String compiledImage = compiledImageOfUserType();

        assertThat(compiledImage)
                .as("positive control: the scan must be able to see type references that genuinely are "
                        + "present, otherwise every absence asserted below would pass vacuously on an "
                        + "empty or mis-decoded read")
                .contains("com/cardemo/model/enums/UserType", "java/util/Optional");

        for (final String forbiddenInternalName : FORBIDDEN_INTERNAL_NAMES) {
            assertThat(compiledImage)
                    .as("the compiled UserType must not reference %s; a reference here would couple the "
                            + "data holder to a layer it must stay clear of, and unlike the reflective "
                            + "check this one sees annotations of every retention", forbiddenInternalName)
                    .doesNotContain(forbiddenInternalName);
        }
    }

    // ==================================================================
    // Movement 4 - the ten seeded user records corroborate that the two
    // constants are complete, using their type byte and nothing else.
    // Source: app/jcl/DUSRSECJ.jcl:L35-L44 @ 7756d89
    //         app/cpy/CSUSR01Y.cpy:L17-L23 @ 7756d89
    // ==================================================================

    @Test
    @DisplayName("the ten seeded type bytes resolve in a five administrator and five user split")
    void theTenSeededTypeBytesResolveInAFiveAndFiveSplit() {
        assertThat(SEEDED_TYPE_BYTES)
                .as("app/jcl/DUSRSECJ.jcl:L35-L44 supplies exactly ten records in stream to IEBGENER")
                .hasSize(SEEDED_RECORDS_PER_TYPE * 2);

        final List<UserType> seededTypes = new ArrayList<>();
        for (int recordIndex = 0; recordIndex < SEEDED_TYPE_BYTES.length(); recordIndex++) {
            seededTypes.add(UserType.requireFromCode(SEEDED_TYPE_BYTES.charAt(recordIndex)));
        }

        assertThat(seededTypes)
                .as("every one of the ten type bytes must resolve without a failure, which is what "
                        + "shows the two constants are complete and not merely correct")
                .hasSize(SEEDED_RECORDS_PER_TYPE * 2)
                .containsOnly(UserType.ADMIN, UserType.USER);

        assertThat(seededTypes.subList(0, SEEDED_RECORDS_PER_TYPE))
                .as("app/jcl/DUSRSECJ.jcl:L35-L39 are the five administrator records, in record order")
                .containsOnly(UserType.ADMIN);

        assertThat(seededTypes.subList(SEEDED_RECORDS_PER_TYPE, seededTypes.size()))
                .as("app/jcl/DUSRSECJ.jcl:L40-L44 are the five standard user records, in record order")
                .containsOnly(UserType.USER);
    }

    @Test
    @DisplayName("the seeded type bytes take exactly two distinct values, so no third constant is needed")
    void theSeededTypeBytesTakeExactlyTwoDistinctValues() {
        final List<UserType> distinctSeededTypes = SEEDED_TYPE_BYTES.chars()
                .mapToObj(seededTypeByte -> UserType.requireFromCode((char) seededTypeByte))
                .distinct()
                .toList();

        assertThat(distinctSeededTypes)
                .as("no third value occurs at position 57 of any seeded record, so the closed domain "
                        + "of app/cpy/COCOM01Y.cpy:L26-L28 is corroborated by real data as well as by "
                        + "the copybook")
                .containsExactlyInAnyOrder(UserType.ADMIN, UserType.USER);
    }

    // ==================================================================
    // Shared pure helpers. Neither holds state, so no test can influence
    // another through them.
    // ==================================================================

    /**
     * Collects the names of every member {@link UserType} declares - fields and methods, synthetic ones
     * included - so that a scan for a forbidden naming shape cannot be evaded by a compiler generated or
     * package private member.
     *
     * @return an immutable list of declared member names, in no guaranteed order
     */
    private static List<String> declaredMemberNames() {
        final List<String> memberNames = new ArrayList<>();

        for (final Field field : UserType.class.getDeclaredFields()) {
            memberNames.add(field.getName());
        }
        for (final Method method : UserType.class.getDeclaredMethods()) {
            memberNames.add(method.getName());
        }

        return List.copyOf(memberNames);
    }

    /**
     * Reads the compiled {@code UserType.class} from the test classpath and returns its bytes decoded one
     * for one as characters, which makes the constant pool searchable as text.
     *
     * <p>{@link StandardCharsets#ISO_8859_1} is chosen because it maps every byte to exactly one character
     * and never fails, so a search for an internal name such as {@code org/springframework} is a search
     * over the real bytes rather than over a lossy decoding. The constant pool stores type names in
     * modified UTF-8, in which every ASCII character is a single byte, so the internal names this test
     * looks for survive the mapping unchanged.
     *
     * @return the compiled class file of {@link UserType}, one character per byte
     * @throws IOException if the class file is present but cannot be read to completion
     */
    private static String compiledImageOfUserType() throws IOException {
        try (InputStream compiledClassFile = UserType.class.getResourceAsStream(USER_TYPE_CLASS_FILE)) {
            assertThat(compiledClassFile)
                    .as("%s must be resolvable from the package of UserType on the test classpath, or "
                            + "the scan proves nothing", USER_TYPE_CLASS_FILE)
                    .isNotNull();

            return new String(compiledClassFile.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }
}
