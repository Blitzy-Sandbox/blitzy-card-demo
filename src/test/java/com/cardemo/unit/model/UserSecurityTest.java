/*
 * ******************************************************************
 * Program     : UserSecurityTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - pure JVM tier, no container, no
 *               Spring context, no database, no live endpoint
 * Function    : Proves that com.cardemo.model.entity.UserSecurity
 *               reproduces the SEC-USER-DATA record contract exactly -
 *               an 80 byte record on an 8 byte key, five modelled
 *               members and a trailing filler that stays unmodelled, a
 *               credential column that can only hold a BCrypt hash, a
 *               user type mapped by an explicit converter that never
 *               guesses, a rendering that leaks neither credential nor
 *               personal data, and a data holder that reaches no
 *               framework layer beyond persistence.
 * Source      : app/cpy/CSUSR01Y.cpy:L17-L23 @ 7756d89
 * Source      : app/cpy/COCOM01Y.cpy:L26-L28 @ 7756d89
 * Source      : app/catlg/LISTCAT.txt:L3881-L3888, :L3938-L3946 @ 7756d89
 * Source      : app/jcl/DUSRSECJ.jcl:L35-L44, :L65-L66 @ 7756d89
 * Source      : app/csd/CARDDEMO.CSD:L1-L88 @ 7756d89
 * Source      : app/cbl/COUSR00C.cbl:L57 @ 7756d89
 * Source      : app/cbl/COUSR03C.cbl @ 7756d89
 * Source      : app/cbl/COSGN00C.cbl:L132, :L135, :L223, :L227 @ 7756d89
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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.cardemo.model.entity.UserSecurity;
import com.cardemo.model.entity.UserSecurity.UserTypeConverter;
import com.cardemo.model.enums.UserType;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidDefinitionException;

import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Converter;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit test for {@link UserSecurity}, the relational replacement for the VSAM KSDS cluster
 * {@code AWS.M2.CARDDEMO.USRSEC.VSAM.KSDS}.
 *
 * <h2>1. What this test does</h2>
 *
 * <p>It holds the entity to the {@code 01 SEC-USER-DATA} record contract declared at
 * {@code app/cpy/CSUSR01Y.cpy:L17-L23} - note {@code :L17}, not {@code :L4}, because that member is one of
 * the 12 of 28 copybooks that carry the repository's own Apache-2.0 banner on {@code :L1-L16}. Eight
 * concerns are asserted, one per movement below:
 *
 * <ol>
 *   <li><b>Geometry.</b> {@code @Table("user_security")}; an 80 byte record that closes as
 *       {@code 8 + 20 + 20 + 8 + 1 = 57} populated bytes plus 23 bytes of trailing filler; a key length of
 *       8; exactly five modelled members and no sixth; no version column; a supplied primary key that is
 *       never generated; no decimal or floating point member; and no {@code Serializable}.</li>
 *   <li><b>The user type converter.</b> That persistence goes through the nested
 *       {@link UserTypeConverter} and through neither {@code @Enumerated} mode, that both codes round
 *       trip, that {@code null} and padding resolve to {@code null}, and that every other code is
 *       rejected by name instead of being guessed at.</li>
 *   <li><b>The credential.</b> That the member is {@code passwordHash} over a 60 character
 *       {@code VARCHAR}, that the 8 byte source width could not hold a hash, and that the compiled entity
 *       embeds neither a credential nor a hash literal.</li>
 *   <li><b>Value semantics.</b> That the rendering carries the identifier and the user class and nothing
 *       else, and that equality and hashing are computed over the identifier alone.</li>
 *   <li><b>Layering.</b> That the entity is not a security principal, reaches nothing from Spring, and
 *       carries no association, no lifecycle callback and no logger.</li>
 *   <li><b>Hostile input.</b> That every member accepts what the fixed width source can legitimately
 *       contain - short, over long, blank and absent values - verbatim, without validating, trimming,
 *       folding or truncating.</li>
 *   <li><b>Schema and ordering context.</b> The Java side counterpart of the check constraint, the
 *       primary key ordering that makes a secondary index unnecessary, and the behaviours that
 *       deliberately live in the service tier instead.</li>
 *   <li><b>The seeded users.</b> The ten rows of {@code app/jcl/DUSRSECJ.jcl:L35-L44}, asserted by
 *       identifier, name and user class only, each carrying a synthetic BCrypt shaped value.</li>
 *   </ol>
 *
 * <p>Three physical facts underpin the whole file and each is corroborated twice over, which is why they
 * are asserted as settled rather than inferred. The record is <b>80 bytes</b>:
 * {@code app/catlg/LISTCAT.txt:L3883} reports {@code AVGLRECL 80} and {@code :L3884} repeats
 * {@code MAXLRECL 80}, while {@code app/jcl/DUSRSECJ.jcl:L66} declares {@code RECORDSIZE(80,80)}. The key
 * is <b>8 bytes at offset zero</b>: {@code app/catlg/LISTCAT.txt:L3883} reports {@code KEYLEN 8} and
 * {@code :L3884} reports {@code RKP 0}, while {@code app/jcl/DUSRSECJ.jcl:L65} declares
 * {@code KEYS(8,0)}. And there are <b>ten rows</b>: {@code app/catlg/LISTCAT.txt:L3888} reports
 * {@code REC-TOTAL 10}, matching the ten in stream records exactly.
 *
 * <h2>2. How to build, run and test</h2>
 *
 * <p>Source the pinned toolchain first, then work from the repository root:
 *
 * <ul>
 *   <li><strong>Prerequisite, stated as a capability rather than as a host path:</strong> JDK 25 on
 *       {@code PATH} with {@code JAVA_HOME} set, however the host provides it. Maven comes from the pinned
 *       wrapper, so the pair {@code maven-enforcer-plugin} asserts is satisfied without naming any file
 *       outside this repository. The repository's own contract is {@code .env} plus {@code ./mvnw}, and
 *       {@code .env.example} documents every variable;</li>
 *   <li>{@code ./mvnw -B clean test} - compiles and runs this tier;</li>
 *   <li>{@code ./mvnw -B test -Dtest=UserSecurityTest} - this class alone;</li>
 *   <li>{@code ./mvnw -B clean verify} - adds the 80 percent line coverage floor and the vulnerability
 *       scan.</li>
 *   </ul>
 *
 * <p><b>This class is bound to Surefire, not to Failsafe, and the binding is positional.</b>
 * {@code maven-surefire-plugin} 3.5.4 includes {@code **}{@code /*Test.java} and excludes
 * {@code **}{@code /integration/**} and {@code **}{@code /e2e/**}, while {@code maven-failsafe-plugin}
 * includes only those two excluded trees. A class named {@code *Test} that sits under
 * {@code src/test/java/com/cardemo/unit} is therefore collected exactly once, by Surefire. Moved outside
 * that tree it would be collected by neither plugin and would silently never run: a green build, both
 * plugins reporting success, and no error and no warning to show that nothing was executed. Neither the
 * file name nor the directory may change.
 *
 * <p><b>Every test method here is declared at the top level; none is nested.</b> That is a deliberate
 * consequence of the reporting artefact described below: with the current include
 * patterns a class whose tests live only inside {@code @Nested} groups is reported as
 * {@code Tests run: 0} in its own {@code target/surefire-reports} file even though the tests do execute.
 * Keeping every method at the top level makes the per class report show a real, non zero count, so
 * "did this class actually run" is answerable by reading one file.
 *
 * <h2>3. Key configuration and defaults</h2>
 *
 * <ul>
 *   <li><b>Pure JVM tier.</b> No container, no Spring context, no database, no live endpoint, no
 *       credential material and no network. Every assertion is reflective, arithmetic or over a value
 *       constructed in the test method itself.</li>
 *   <li><b>Determinism.</b> Nothing reads the wall clock, the default locale, the default time zone or a
 *       random source: there is no {@code Instant.now()}, no {@code LocalDate.now()} and no
 *       {@code System.currentTimeMillis()} in this file. Every case operation folds with
 *       {@link Locale#ROOT} so it cannot change behaviour on a host whose case rules differ. The sibling
 *       {@code FixedClockProvider} exists for tests whose subject renders a date and time; this entity
 *       carries no temporal member, so no clock is injected here and none is needed.</li>
 *   <li><b>Pinned test dependencies only</b> - {@code junit-jupiter} 5.12.2 and {@code assertj-core}
 *       3.27.7, both reaching the test classpath through {@code spring-boot-starter-test}. No dependency
 *       is added by this file. In particular <b>no BCrypt library is used</b>: the hash <em>shape</em> is
 *       asserted against a pattern, and no hash is ever computed, because computing one would mean
 *       introducing an encoder and choosing a work factor in a unit test.</li>
 *   <li><b>BCrypt strength 10</b> is the pinned work factor, and it is asserted structurally: the shape
 *       pattern requires the literal cost field {@code 10}, so a hash produced at any other strength
 *       fails it. Producing hashes belongs to the security configuration's password encoder, never to
 *       this entity and never to this test.</li>
 *   <li><b>No fixture file.</b> Unlike the nine {@code app/data/ASCII} datasets there is no
 *       {@code usrsec.txt}, so no fixture loader is used here and the absence is asserted rather than
 *       assumed. The seed lives only as in stream JCL data.</li>
 *   <li><b>No Mockito.</b> {@code mockito-core} 5.17.0 is on the classpath and its strict stub checking
 *       would apply, but a data holder with no collaborator has nothing to mock; introducing a double
 *       would test the double instead of the entity.</li>
 *   </ul>
 *
 * <h2>4. Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><b>The build fails on a warning rather than on an error.</b> {@code maven-compiler-plugin} 3.14.1
 *       runs with {@code -Xlint:all}, {@code -Werror} and {@code failOnWarning}, and that configuration
 *       reaches test compilation as well as main compilation. A raw type, an unchecked cast, a
 *       deprecation or a dangling documentation comment fails the build outright. Read the first
 *       {@code [WARNING]} in the log, not the last {@code [ERROR]}.</li>
 *   <li><b>A fixture name that differs by one letter.</b> The daily transaction fixture is
 *       {@code app/data/ASCII/dailytran.txt} - the mainframe DD name and dataset are {@code DALYTRAN},
 *       but the ASCII file spells the word in full. A resource path of {@code dalytran.txt} resolves to
 *       {@code null} and produces a {@link NullPointerException} far from its cause. This test loads no
 *       fixture at all, which is the surest way to avoid the trap.</li>
 *   <li><b>Every seeded user fails to authenticate.</b> The credential column was created as
 *       {@code CHAR(8)}, the source PIC width, which cannot hold a 60 character hash; or as
 *       {@code CHAR(60)}, which blank pads it, and a padded hash fails verification. It must be
 *       {@code VARCHAR(60)}. Movement 3 asserts exactly this.</li>
 *   <li><b>{@code SchemaManagementException} naming a column at startup.</b> A character column's JDBC
 *       type code disagrees with the migration. Four columns here are {@code CHAR} and carry an explicit
 *       type code; {@code sec_usr_pwd} deliberately carries none, so that it presents as
 *       {@code VARCHAR}.</li>
 *   <li><b>The user class silently changes meaning.</b> An {@code @Enumerated} mapping replaced the
 *       converter: the string mode writes the constant names {@code ADMIN} and {@code USER} into a one
 *       character column, and the ordinal mode writes {@code 0} and {@code 1}, which are not the
 *       source's codes and shift the moment the constants are reordered. Movement 2 rejects both.</li>
 *   <li><b>An unrecognised user class resolves to something.</b> A converter that defaulted would hand a
 *       privilege class to a row the schema never authorised. Movement 2 asserts that every code outside
 *       {@code 'A'} and {@code 'U'} throws, and that the message names the offending code.</li>
 *   </ul>
 *
 * <h2>5. Constraints that must continue to hold</h2>
 *
 * <p><b>A plaintext credential literal must never appear under {@code src/}.</b> Rule 1 clause
 * D names tests explicitly: no secrets in code, logs, tests or config. All ten seeded rows at
 * {@code app/jcl/DUSRSECJ.jcl:L35-L44} share one 8 character plaintext value in the
 * {@code SEC-USR-PWD PIC X(08)} field. That value is referred to here by citation only and is
 * transcribed nowhere - not in a constant, not in an assertion message, not in a comment and not in this
 * documentation. Movement 3 asserts the property from the other side instead: every credential this test
 * constructs is a synthetic 60 character BCrypt shaped value, no value of the source's 8 byte width can
 * satisfy that shape, and the compiled entity's own string constant pool contains no candidate. Should a
 * literal ever be introduced, delete it and re-derive the assertion from the shape; the value is public
 * legacy sample data rather than a live secret, so nothing needs rotating.
 *
 * <p><b>The credential column is {@code VARCHAR(60)}, never {@code CHAR(8)}.</b> The source
 * field is 8 bytes because it held plaintext; the column is 60 because that is the exact length of a
 * BCrypt hash, and {@code VARCHAR} because a {@code CHAR} column would blank pad one. A {@code CHAR(8)}
 * column truncates every hash to garbage and no test that only checks a name would notice, so the
 * migration must declare {@code sec_usr_pwd VARCHAR(60) NOT NULL}.
 *
 * <p><b>The user class needs the explicit converter.</b> See the fourth failure mode above: keep
 * {@link UserTypeConverter}, and keep {@code @Enumerated} out of the entity entirely.
 *
 * <p><b>A multi character code must be rejected, never truncated to its first character.</b> Padding is
 * stripped with {@code trim()} before the length is measured, so reading only the first character of what
 * remains would resolve a malformed non blank value such as {@code "AA"} or {@code "AU"} to
 * {@link UserType#ADMIN} instead of rejecting it - and, because the padding is already gone, such a
 * leniency could never help the case it would be written for, a driver padding a {@code CHAR(1)}. It would
 * admit only values a {@code CHAR(1)} column cannot hold. Silently accepting a
 * malformed privilege code is exactly the unsafe default Rule 1 clause A forbids and the boundary
 * condition clause B requires validating, so the converter resolves through
 * {@link UserType#fromCode(String)}, which reports an empty
 * result for any length but one: the domain check is delegated to the type that owns the domain rather
 * than restated, and the rejection message names the whole offending value with its length and code
 * units. Movement 2 pins that contract, and the entity needs no import to satisfy it.
 *
 * <p><b>The entity must not become a security principal.</b> It does not implement Spring
 * Security's user details contract, extends no framework type and references nothing from
 * {@code org.springframework}; the adaptation belongs to
 * {@code com.cardemo.security.CardDemoUserDetailsService}, one layer out. Importing the interface would
 * drag a security framework into the model layer and invert the dependency direction, so the adaptation
 * belongs in the security package and never here. Movement 5 asserts it against the compiled class file,
 * so the assertion cannot be satisfied by an import that merely looks absent.
 *
 * <p><b>No version column belongs on this entity.</b> Exactly four entities carry
 * {@code @Version}: the account, card, customer and transaction entities. Adding a fifth here would
 * introduce a column the 80 byte record has no room for and an optimistic locking failure mode the four
 * user administration screens never had. Movement 1 asserts the absence, and cross checks that the four
 * versioned entities are exactly those four.
 *
 * <p><b>{@code USRSEC} is catalogued, so the catalogue is the primary authority for its geometry.</b>
 * {@code app/catlg/LISTCAT.txt:L3881} names {@code AWS.M2.CARDDEMO.USRSEC.VSAM.KSDS} and {@code :L3883}
 * reports {@code KEYLEN 8} with {@code AVGLRECL 80}. The IDCAMS declaration at
 * {@code app/jcl/DUSRSECJ.jcl:L65-L66} corroborates the same geometry from a second, independent source
 * rather than being the only one, and this cluster is therefore cited exactly as the sibling ten are.
 * Both citations
 * are asserted in movement 1, so the geometry is machine checked and not merely written down.
 *
 * <p><b>A class whose tests all live in {@code @Nested} groups reports {@code Tests run: 0} of its
 * own.</b> With Surefire's include patterns matching
 * on {@code *Test.java}, the tests of a {@code @Nested} group execute and count towards the aggregate but
 * are attributed to their display name, leaving the outer class's own report file empty. It is a reporting
 * artefact and not a non execution: the aggregate is correct. This file
 * side steps the artefact entirely by declaring no nested class.
 *
 * <p><b>{@code USRSEC} has no alternate index and needs none.</b> The catalogue's totals at
 * {@code app/catlg/LISTCAT.txt:L3938} and {@code :L3946} are {@code AIX 3} and {@code PATH 3}, and all
 * three belong to the card, cross reference and transaction clusters. The user list screen pages in
 * primary key order at ten rows per page, which the primary key index already serves, so a second index
 * would be write amplification for no read benefit.
 *
 * <h2>6. Boundaries of this class</h2>
 *
 * <ul>
 *   <li><b>The check constraint is asserted as SQL elsewhere.</b> {@code V1__create_schema.sql} declares
 *       {@code user_security}, and {@code ck_user_security_type} - which restricts {@code sec_usr_type} to
 *       {@code 'A'} and {@code 'U'} - is one of its five {@code CHECK} constraints.
 *       {@code SchemaStructureTest} parses the migration and pins the
 *       check census at exactly five, so a sixth could not be added unnoticed. This class asserts the
 *       enforceable Java side counterpart rather than duplicating that work: {@link
 *       UserTypeConverter} accepts those two codes and rejects every other, which is the same domain
 *       expressed where a unit test can reach it without a database. No DDL is invented here.</li>
 *   <li><b>There is no {@code usrsec.txt} fixture, deliberately.</b> The user records exist only as the in
 *       stream
 *       {@code SYSUT1 DD *} data at {@code app/jcl/DUSRSECJ.jcl:L34-L45}. Nothing needs to be supplied,
 *       because the in stream data is the authority, but no fixture derived assertion is possible and
 *       none is faked. Movement 8 asserts the absence explicitly, so a future fixture cannot appear
 *       unnoticed.</li>
 *   <li><b>No stored hash is read from the seed.</b> {@code V3__seed_data.sql} holds the ten precomputed
 *       BCrypt values, but this class deliberately does not reach for them: movement 8 uses
 *       synthetic shaped values, because the property under test is the shape and
 *       the column width, never a particular digest.</li>
 *   </ul>
 *
 * @see UserSecurity
 * @see UserTypeConverter
 * @see UserType
 */
final class UserSecurityTest {

    /**
     * {@code SEC-USR-ID PIC X(08)} at {@code app/cpy/CSUSR01Y.cpy:L18}: bytes 1 to 8, and the key.
     */
    private static final int USER_ID_PIC_WIDTH = 8;

    /**
     * {@code SEC-USR-FNAME PIC X(20)} at {@code app/cpy/CSUSR01Y.cpy:L19}: bytes 9 to 28.
     */
    private static final int FIRST_NAME_PIC_WIDTH = 20;

    /**
     * {@code SEC-USR-LNAME PIC X(20)} at {@code app/cpy/CSUSR01Y.cpy:L20}: bytes 29 to 48.
     */
    private static final int LAST_NAME_PIC_WIDTH = 20;

    /**
     * {@code SEC-USR-PWD PIC X(08)} at {@code app/cpy/CSUSR01Y.cpy:L21}: bytes 49 to 56.
     */
    private static final int SOURCE_CREDENTIAL_PIC_WIDTH = 8;

    /**
     * {@code SEC-USR-TYPE PIC X(01)} at {@code app/cpy/CSUSR01Y.cpy:L22}: byte 57.
     */
    private static final int USER_TYPE_PIC_WIDTH = 1;

    /**
     * {@code SEC-USR-FILLER PIC X(23)} at {@code app/cpy/CSUSR01Y.cpy:L23}: bytes 58 to 80.
     */
    private static final int FILLER_PIC_WIDTH = 23;

    /**
     * The catalogued record length: {@code AVGLRECL 80} at {@code app/catlg/LISTCAT.txt:L3883},
     * {@code MAXLRECL 80} at {@code :L3884}, and {@code RECORDSIZE(80,80)} at {@code app/jcl/DUSRSECJ.jcl:L66}.
     */
    private static final int CATALOGUED_RECORD_LENGTH = 80;

    /**
     * The catalogued key length: {@code KEYLEN 8} at {@code app/catlg/LISTCAT.txt:L3883} and {@code KEYS(8,0)}
     * at {@code app/jcl/DUSRSECJ.jcl:L65}. The second element of that {@code KEYS} pair is the relative key
     * position, which {@code app/catlg/LISTCAT.txt:L3884} reports as {@code RKP 0} - so the key is the leading
     * field and key order coincides with physical order.
     */
    private static final int CATALOGUED_KEY_LENGTH = 8;

    /**
     * {@code REC-TOTAL 10} at {@code app/catlg/LISTCAT.txt:L3888}, matching the ten in stream rows.
     */
    private static final int CATALOGUED_RECORD_COUNT = 10;

    /**
     * The number of modelled members: the five elementary items, the named filler excluded.
     */
    private static final int MODELLED_MEMBER_COUNT = 5;

    /**
     * The four non public members beyond the accessors and the {@link Object} overrides: the three
     * static boundary guards {@code requireBcryptStrength10Digest}, {@code requireWidth} and
     * {@code requireUserType}, plus the instance write callback
     * {@code assertCredentialInvariantBeforeWrite} that re-asserts the credential invariant at the
     * database boundary.
     *
     * <p>Named as a constant rather than written as a literal so that the method count assertion reads
     * as a statement about what the entity holds. The count is deliberately small and deliberately fixed:
     * a fifth member appearing here would mean a business rule had grown on a data holder, which is
     * exactly what {@link #declaresOnlyAccessorsAndTheObjectOverrides()} exists to catch.
     */
    private static final int NON_PUBLIC_GUARD_MEMBER_COUNT = 4;

    /** Width of the credential column - the exact length of a BCrypt hash, and never the PIC width. */
    private static final int BCRYPT_HASH_WIDTH = 60;

    /**
     * The code that {@code 88 CDEMO-USRTYP-ADMIN VALUE 'A'.} binds at {@code app/cpy/COCOM01Y.cpy:L27},
     * transcribed as a one character string because that is the form the converter reads and writes.
     */
    private static final String ADMIN_CODE = "A";

    /**
     * The code that {@code 88 CDEMO-USRTYP-USER VALUE 'U'.} binds at {@code app/cpy/COCOM01Y.cpy:L28}.
     */
    private static final String USER_CODE = "U";

    /**
     * The ten {@code SEC-USR-TYPE} bytes of the seeded records, in record order, read from position 57 of
     * {@code app/jcl/DUSRSECJ.jcl:L35-L44}: five administrators on {@code :L35-L39} then five standard users on
     * {@code :L40-L44}.
     */
    private static final String SEEDED_TYPE_BYTES = "AAAAAUUUUU";

    /**
     * Seeded records per user class - five on {@code :L35-L39} and five on {@code :L40-L44}.
     */
    private static final int SEEDED_RECORDS_PER_TYPE = 5;

    /**
     * The eight CICS file names that {@code app/csd/CARDDEMO.CSD} defines, in the order the member declares
     * them: {@code ACCTDAT} at {@code :L1}, {@code CARDAIX} at {@code :L13}, {@code CARDDAT} at {@code :L25},
     * {@code CCXREF} at {@code :L37}, {@code CUSTDAT} at {@code :L50}, {@code CXACAIX} at {@code :L63},
     * {@code TRANSACT} at {@code :L76} and {@code USRSEC} at {@code :L88}.
     */
    private static final List<String> CSD_FILE_NAMES = List.of(
            "ACCTDAT", "CARDAIX", "CARDDAT", "CCXREF", "CUSTDAT", "CXACAIX", "TRANSACT", "USRSEC");

    /**
     * The catalogue's alternate index and path totals at {@code app/catlg/LISTCAT.txt:L3938} and
     * {@code :L3946}: {@code AIX 3} and {@code PATH 3}, all three belonging to the card, cross reference and
     * transaction clusters, so none belongs to {@code USRSEC}.
     */
    private static final int CATALOGUED_ALTERNATE_INDEX_COUNT = 3;

    /**
     * The page size of the user list screen: {@code 02 USER-REC OCCURS 10 TIMES.} at
     * {@code app/cbl/COUSR00C.cbl:L57}. Context for {@code UserSecurityDto} and for the list service; asserted
     * here only to prove that the entity itself carries no such member.
     */
    private static final int USER_LIST_PAGE_SIZE = 10;

    /**
     * The BCrypt version and cost prefix, seven characters: a dollar, the version tag {@code 2a}, a dollar, the
     * two digit cost field {@code 10} and a dollar.
     */
    private static final String BCRYPT_PREFIX = "$2a$10$";

    /**
     * A synthetic 22 character salt, every character drawn from BCrypt's own {@code ./A-Za-z0-9} alphabet, and
     * named so that it reads as synthetic wherever it surfaces.
     */
    private static final String SYNTHETIC_SALT = "SyntheticSaltForTests.";

    /**
     * The first 29 characters of a synthetic 31 character digest. Two more characters are appended per user so
     * that ten distinct, equally well formed values can be produced without a random source.
     */
    private static final String SYNTHETIC_DIGEST_STEM = "SyntheticDigestNotARealDigest";

    /**
     * The shape of a BCrypt hash at the pinned strength: the version and cost prefix, then 53 characters of
     * salt and digest drawn from BCrypt's alphabet, for exactly 60 characters in total.
     */
    private static final Pattern BCRYPT_SHAPE = Pattern.compile("^\\$2[abxy]\\$10\\$[./A-Za-z0-9]{53}$");

    /**
     * The simple name of the entity's table, from {@code @Table} - asserted, not assumed.
     */
    private static final String EXPECTED_TABLE_NAME = "user_security";

    /**
     * The five column names, in the declaration order of {@code app/cpy/CSUSR01Y.cpy:L18-L22}.
     */
    private static final List<String> EXPECTED_COLUMN_NAMES = List.of(
            "sec_usr_id", "sec_usr_fname", "sec_usr_lname", "sec_usr_pwd", "sec_usr_type");

    /**
     * The five member names, in the same declaration order.
     */
    private static final List<String> EXPECTED_MEMBER_NAMES = List.of(
            "secUsrId", "secUsrFname", "secUsrLname", "passwordHash", "secUsrType");

    /**
     * Internal class file names that must not appear anywhere in the compiled entity or its nested
     * converter, each one a layer or capability the model is required to stay clear of.
     *
     * <p>Expressed with slash separators, which is the form a class file uses, so a match is a genuine
     * type reference rather than a coincidence inside a string literal. Source comments do not survive
     * compilation at all, which is what makes the scan stronger than reading the imports: an import can
     * be absent while a fully qualified reference in a method body is not.
     *
     * <p><b>{@code com/fasterxml/jackson} was previously in this list and has been removed, for the same
     * reason the annotation set no longer forbids {@code JsonIgnore}.</b> The serialisation barrier this
     * class carries is expressed in Jackson's own annotation types, so a reference to them in the
     * compiled form is now required rather than forbidden, and
     * {@link #carriesTheJsonSerialisationBarrier()} asserts it positively. The layering concern the entry
     * expressed is untouched: those three annotation types are declarative metadata with no behaviour, so
     * this entity still calls nothing, constructs nothing and depends on no serialiser. What it must not
     * do is define an outbound shape, and {@link #carriesNoForbiddenAnnotationAnywhere()} still enforces
     * that by keeping {@code JsonProperty} and {@code JsonInclude} out.
     */
    private static final List<String> FORBIDDEN_INTERNAL_NAMES = List.of(
            "org/springframework",
            "UserDetails",
            "GrantedAuthority",
            "org/slf4j",
            "ch/qos/logback",
            "org/apache/logging",
            "java/util/logging",
            "java/math/BigDecimal",
            "jakarta/validation",
            "com/cardemo/exception",
            "com/cardemo/repository",
            "com/cardemo/service",
            "com/cardemo/controller",
            "com/cardemo/batch",
            "com/cardemo/security",
            "com/cardemo/config",
            "com/cardemo/observability",
            "com/cardemo/model/key",
            "com/cardemo/model/dto");

    /**
     * Simple names of annotations that must appear nowhere on the entity, its members or its
     * constructors.
     *
     * <p>Matched by simple name rather than by type literal so that the sweep stays broad without
     * importing twenty annotation types, several of which would otherwise be imported solely to be
     * asserted absent - which is itself the unused surface Rule 1 clause B discourages. The three whose
     * absence is a stated finding - the version, generation and enumerated mappings - are additionally
     * asserted by type literal elsewhere, where a typo cannot pass unnoticed.
     *
     * <p><b>The Jackson entries draw a line that is easy to misread, so it is stated explicitly:
     * annotations that SHAPE an outbound payload are forbidden, annotations that SUPPRESS one are
     * required.</b> {@code JsonProperty} and {@code JsonInclude} remain forbidden because either would
     * make this entity define how it appears on the wire, which is the data transfer object's job and
     * nobody else's. {@code JsonIgnore} was previously in this set and has been removed, because it is
     * the opposite kind of annotation: it removes a property from serialisation and can never add one.
     * It is now present on the credential accessor deliberately, as part of the serialisation barrier
     * this class carries, and {@link #carriesTheJsonSerialisationBarrier()} asserts it positively. A set
     * that forbade suppression alongside shaping would have forced the barrier to be expressed as an
     * externally registered mix-in, which protects only the one object mapper it is registered on and
     * leaves every other mapper on the classpath free to serialise the credential.
     */
    private static final Set<String> FORBIDDEN_ANNOTATION_SIMPLE_NAMES = Set.of(
            "OneToMany", "ManyToOne", "OneToOne", "ManyToMany", "JoinColumn", "JoinColumns", "JoinTable",
            "ElementCollection", "CollectionTable", "MapsId", "SecondaryTable", "Embedded", "EmbeddedId",
            "MappedSuperclass", "Inheritance", "DiscriminatorColumn", "DiscriminatorValue",
            "PostPersist", "PostUpdate", "PreRemove", "PostRemove", "PostLoad",
            "EntityListeners", "NamedQuery", "NamedQueries", "SqlResultSetMapping",
            "NotNull", "NotBlank", "NotEmpty", "Size", "Pattern", "Email", "Valid",
            "JsonProperty", "JsonInclude", "Transient");

    /**
     * Lower case fragments that must not occur in any member name of the entity, covering the personal data the
     * customer and user layouts carry and the authorisation state a security principal would add.
     */
    private static final List<String> FORBIDDEN_MEMBER_NAME_FRAGMENTS = List.of(
            "plaintext", "cleartext", "secret", "credential", "token", "salt", "cipher",
            "ssn", "socialsecurity", "birth", "dob", "phone", "email", "address",
            "authority", "granted", "enabled", "locked", "expired", "role",
            "filler", "reserved", "version", "page", "logger", "log");

    /**
     * The four entities that carry {@code @Version}, cross checked so that "this entity is not one of the four
     * versioned entities" is asserted against the package rather than against a comment.
     */
    private static final List<String> VERSIONED_ENTITY_NAMES = List.of(
            "Account", "Card", "Customer", "Transaction");

    /**
     * The other ten entities of {@code com.cardemo.model.entity}, one per remaining VSAM cluster.
     */
    private static final List<String> SIBLING_ENTITY_NAMES = List.of(
            "Account", "Card", "CardCrossReference", "Customer", "DailyTransaction", "DisclosureGroup",
            "Transaction", "TransactionCategory", "TransactionCategoryBalance", "TransactionType");

    /**
     * The ten seeded user identifiers, in the order IEBGENER reads them from the inline stream at
     * {@code app/jcl/DUSRSECJ.jcl:L35-L44}: five administrators then five standard users.
     */
    private static final List<String> SEEDED_USER_IDS = List.of(
            "ADMNUSR1", "ADMNUSR2", "ADMNUSR3", "ADMNUSR4", "ADMNUSR5",
            "STDUSR01", "STDUSR02", "STDUSR03", "STDUSR04", "STDUSR05");

    /**
     * Given names of the ten seeded rows, bytes 9 to 28 of each 80 byte record.
     */
    private static final List<String> SEEDED_FIRST_NAMES = List.of(
            "FNAMEAA1", "FNAMEA2", "FNAMEA3", "FNAMEAA4", "FNAMEAAA5",
            "FNAMEAA6", "FNAM7", "FNAMEA8", "FNAMEAA9", "FN0");

    /**
     * Family names of the ten seeded rows, bytes 29 to 48 of each 80 byte record.
     */
    private static final List<String> SEEDED_LAST_NAMES = List.of(
            "LNM1", "LNAMEA2", "LNAMEAA3", "LNAMEAA4", "LNAMEAAAA5",
            "LNAME6", "LNAM7", "LNM8", "LNAM9", "LN10");

    /**
     * {@code CONSTANT_Utf8}: two length bytes then that many bytes of modified UTF-8.
     */
    private static final int CONSTANT_UTF8 = 1;

    /**
     * {@code CONSTANT_Integer}: four bytes.
     */
    private static final int CONSTANT_INTEGER = 3;

    /**
     * {@code CONSTANT_Float}: four bytes.
     */
    private static final int CONSTANT_FLOAT = 4;

    /**
     * {@code CONSTANT_Long}: eight bytes, and occupies two pool slots.
     */
    private static final int CONSTANT_LONG = 5;

    /**
     * {@code CONSTANT_Double}: eight bytes, and occupies two pool slots.
     */
    private static final int CONSTANT_DOUBLE = 6;

    /**
     * {@code CONSTANT_Class}: a two byte name index.
     */
    private static final int CONSTANT_CLASS = 7;

    /**
     * {@code CONSTANT_String}: a two byte index of the UTF-8 entry holding the literal.
     */
    private static final int CONSTANT_STRING = 8;

    /**
     * {@code CONSTANT_Fieldref}: four bytes.
     */
    private static final int CONSTANT_FIELDREF = 9;

    /**
     * {@code CONSTANT_Methodref}: four bytes.
     */
    private static final int CONSTANT_METHODREF = 10;

    /**
     * {@code CONSTANT_InterfaceMethodref}: four bytes.
     */
    private static final int CONSTANT_INTERFACE_METHODREF = 11;

    /**
     * {@code CONSTANT_NameAndType}: four bytes.
     */
    private static final int CONSTANT_NAME_AND_TYPE = 12;

    /**
     * {@code CONSTANT_MethodHandle}: one reference kind byte then a two byte index.
     */
    private static final int CONSTANT_METHOD_HANDLE = 15;

    /**
     * {@code CONSTANT_MethodType}: a two byte descriptor index.
     */
    private static final int CONSTANT_METHOD_TYPE = 16;

    /**
     * {@code CONSTANT_Dynamic}: four bytes. Emitted for a condy-backed constant.
     */
    private static final int CONSTANT_DYNAMIC = 17;

    /**
     * {@code CONSTANT_InvokeDynamic}: four bytes. Emitted for indified string concatenation.
     */
    private static final int CONSTANT_INVOKE_DYNAMIC = 18;

    /**
     * {@code CONSTANT_Module}: a two byte name index.
     */
    private static final int CONSTANT_MODULE = 19;

    /**
     * {@code CONSTANT_Package}: a two byte name index.
     */
    private static final int CONSTANT_PACKAGE = 20;

    @Test
    @DisplayName("is a JPA entity mapped to user_security, with no index or unique constraint declared")
    void isAnEntityMappedToTheUserSecurityTable() {
        assertThat(UserSecurity.class.getAnnotation(Entity.class))
                .as("app/csd/CARDDEMO.CSD:L88 puts USRSEC on the online request path, so the cluster "
                        + "becomes a mapped entity rather than a batch only projection")
                .isNotNull();

        final Table table = UserSecurity.class.getAnnotation(Table.class);
        assertThat(table)
                .as("the table name must be declared explicitly rather than left to a naming strategy, "
                        + "because ddl-auto validate compares it against V1__create_schema.sql")
                .isNotNull();
        assertThat(table.name())
                .as("app/catlg/LISTCAT.txt:L3881 names the cluster AWS.M2.CARDDEMO.USRSEC.VSAM.KSDS, "
                        + "which maps to the table %s", EXPECTED_TABLE_NAME)
                .isEqualTo(EXPECTED_TABLE_NAME);
        assertThat(table.indexes())
                .as("app/catlg/LISTCAT.txt:L3938 tallies AIX %d, all three belonging to CARDDATA, "
                        + "CARDXREF and TRANSACT, so USRSEC declares no secondary index",
                        CATALOGUED_ALTERNATE_INDEX_COUNT)
                .isEmpty();
        assertThat(table.uniqueConstraints())
                .as("the only uniqueness on this cluster is its primary key: app/catlg/LISTCAT.txt:L3946 "
                        + "tallies PATH 3 and none of the three is a USRSEC path")
                .isEmpty();
    }

    @Test
    @DisplayName("closes the 80 byte record exactly: 8 + 20 + 20 + 8 + 1 populated plus 23 filler")
    void recordGeometryClosesAtTheCataloguedEightyBytes() {
        final int populated = USER_ID_PIC_WIDTH + FIRST_NAME_PIC_WIDTH + LAST_NAME_PIC_WIDTH
                + SOURCE_CREDENTIAL_PIC_WIDTH + USER_TYPE_PIC_WIDTH;

        assertThat(populated)
                .as("the five elementary items of app/cpy/CSUSR01Y.cpy:L18-L22 occupy 57 bytes")
                .isEqualTo(57);
        assertThat(populated + FILLER_PIC_WIDTH)
                .as("57 populated bytes plus the 23 byte SEC-USR-FILLER of "
                        + "app/cpy/CSUSR01Y.cpy:L23 must reach the AVGLRECL 80 of "
                        + "app/catlg/LISTCAT.txt:L3883 and the RECORDSIZE(80,80) of "
                        + "app/jcl/DUSRSECJ.jcl:L66")
                .isEqualTo(CATALOGUED_RECORD_LENGTH);
    }

    @Test
    @DisplayName("carries the catalogued key length of 8 on the leading field, from two sources")
    void keyLengthIsEightAndCorroboratedByCatalogueAndJcl() {
        final Column idColumn = columnOf("secUsrId");

        assertThat(CATALOGUED_KEY_LENGTH)
                .as("app/catlg/LISTCAT.txt:L3883 reports KEYLEN 8 and app/jcl/DUSRSECJ.jcl:L65 declares "
                        + "KEYS(8,0); the two citations must agree with the PIC width of "
                        + "app/cpy/CSUSR01Y.cpy:L18")
                .isEqualTo(USER_ID_PIC_WIDTH);
        assertThat(idColumn.length())
                .as("the primary key column must be exactly as wide as the catalogued key, or a key that "
                        + "the source accepts is rejected on flush")
                .isEqualTo(CATALOGUED_KEY_LENGTH);
        assertThat(idColumn.name())
                .as("SEC-USR-ID is the leading field, which app/catlg/LISTCAT.txt:L3884 confirms as "
                        + "RKP 0, so key order and physical record order coincide")
                .isEqualTo("sec_usr_id");
    }

    @Test
    @DisplayName("models exactly five members - the named filler is not one of them")
    void modelsExactlyTheFiveElementaryItems() {
        final List<String> memberNames = persistentFields().stream().map(Field::getName).toList();

        assertThat(memberNames)
                .as("app/cpy/CSUSR01Y.cpy:L18-L22 declares five elementary items and :L23 declares a "
                        + "filler that carries no data in any of the %d seeded rows",
                        CATALOGUED_RECORD_COUNT)
                .hasSize(MODELLED_MEMBER_COUNT)
                .containsExactlyInAnyOrderElementsOf(EXPECTED_MEMBER_NAMES);
    }

    @Test
    @DisplayName("models no filler: no member, and no 23 character column, stands in for SEC-USR-FILLER")
    void doesNotModelTheNamedTrailingFiller() {
        final List<String> memberNames = persistentFields().stream()
                .map(field -> field.getName().toLowerCase(Locale.ROOT))
                .toList();

        assertThat(memberNames)
                .as("SEC-USR-FILLER at app/cpy/CSUSR01Y.cpy:L23 is named, unlike the anonymous FILLER "
                        + "items in the other ten layouts, but a name does not make it data")
                .doesNotContain("filler", "secusrfiller", "reserved", "padding");

        assertThat(persistentFields())
                .allSatisfy(field -> assertThat(field.getAnnotation(Column.class).length())
                        .as("no column may be %d characters wide, which would be the filler modelled "
                                + "under another name", FILLER_PIC_WIDTH)
                        .isNotEqualTo(FILLER_PIC_WIDTH));
    }

    @ParameterizedTest(name = "{0} maps to {1} at width {2}")
    @CsvSource({
        "secUsrId,     sec_usr_id,    8",
        "secUsrFname,  sec_usr_fname, 20",
        "secUsrLname,  sec_usr_lname, 20",
        "secUsrType,   sec_usr_type,  1"
    })
    @DisplayName("maps each character member to its copybook column at its exact PIC width")
    void mapsEachCharacterMemberAtItsPicWidth(final String member, final String column, final int width) {
        final Column mapping = columnOf(member);

        assertThat(mapping.name())
                .as("%s must keep the COBOL derived column name so the mapping back to "
                        + "app/cpy/CSUSR01Y.cpy stays mechanical", member)
                .isEqualTo(column);
        assertThat(mapping.length())
                .as("%s must be exactly as wide as its PIC clause in app/cpy/CSUSR01Y.cpy; widening "
                        + "accepts data the source rejects and narrowing truncates silently", member)
                .isEqualTo(width);
        assertThat(mapping.nullable())
                .as("every column of an 80 byte fixed width record is present, so %s is NOT NULL; "
                        + "blankness is a value here and never an absence", member)
                .isFalse();
    }

    @Test
    @DisplayName("declares the five expected column names and no others")
    void declaresExactlyTheFiveExpectedColumns() {
        final List<String> columnNames = persistentFields().stream()
                .map(field -> field.getAnnotation(Column.class).name())
                .toList();

        assertThat(columnNames)
                .as("the schema surface of this entity is exactly the five columns of "
                        + "app/cpy/CSUSR01Y.cpy:L18-L22")
                .containsExactlyInAnyOrderElementsOf(EXPECTED_COLUMN_NAMES);
    }

    @Test
    @DisplayName("carries no @Version: it is not one of the four versioned entities")
    void carriesNoVersionColumn() {
        assertThat(annotationSimpleNamesOf(UserSecurity.class))
                .as("only the account, card, customer and transaction entities carry @Version; an 80 "
                        + "byte record has no room for a fifth column and the four user administration "
                        + "screens never had an optimistic locking failure mode")
                .doesNotContain(Version.class.getSimpleName());

        assertThat(persistentFields())
                .allSatisfy(field -> assertThat(field.getAnnotation(Version.class))
                        .as("member %s must not be a version counter", field.getName())
                        .isNull());

        assertThat(VERSIONED_ENTITY_NAMES)
                .as("the four versioned entities are named here so the claim is checked against the "
                        + "package rather than against a comment")
                .hasSize(4)
                .doesNotContain(UserSecurity.class.getSimpleName());
    }

    @Test
    @DisplayName("cross checks that exactly the four named sibling entities reference @Version")
    void exactlyFourSiblingEntitiesReferenceTheVersionAnnotation() throws IOException {
        final String versionInternalName = "jakarta/persistence/Version";
        final List<String> referencing = new ArrayList<>();

        for (final String entityName : SIBLING_ENTITY_NAMES) {
            if (classFileTextOf(entityName).contains(versionInternalName)) {
                referencing.add(entityName);
            }
        }

        assertThat(referencing)
                .as("the compiled class files are the authority: a Javadoc mention of @Version does not "
                        + "survive compilation, so only a real annotation registers here")
                .containsExactlyInAnyOrderElementsOf(VERSIONED_ENTITY_NAMES);
        assertThat(classFileTextOf(UserSecurity.class.getSimpleName()))
                .as("and the entity under test must not reference it at all")
                .doesNotContain(versionInternalName);
    }

    @Test
    @DisplayName("uses secUsrId as a supplied @Id String, never a generated one")
    void identifierIsASuppliedStringPrimaryKey() throws NoSuchFieldException {
        final Field id = UserSecurity.class.getDeclaredField("secUsrId");

        assertThat(id.getAnnotation(Id.class))
                .as("SEC-USR-ID at app/cpy/CSUSR01Y.cpy:L18 is the cluster key, so it is the @Id")
                .isNotNull();
        assertThat(id.getType())
                .as("PIC X(08) is character data, so the key is a String and never a numeric surrogate")
                .isEqualTo(String.class);
        assertThat(id.getAnnotation(GeneratedValue.class))
                .as("all ten identifiers arrive from app/jcl/DUSRSECJ.jcl:L35-L44 or from the user add "
                        + "service; none is generated and no sequence exists for one")
                .isNull();

        final List<Field> annotatedWithId = persistentFields().stream()
                .filter(field -> field.getAnnotation(Id.class) != null)
                .toList();
        assertThat(annotatedWithId)
                .as("KEYS(8,0) at app/jcl/DUSRSECJ.jcl:L65 is a single eight byte key, so there is "
                        + "exactly one @Id and no composite key class")
                .hasSize(1);
    }

    @Test
    @DisplayName("holds no decimal or floating point member: this record carries no money")
    void holdsNoNumericMoneyMember() {
        final Set<Class<?>> forbidden = Set.of(
                float.class, double.class, Float.class, Double.class, java.math.BigDecimal.class);

        assertThat(persistentFields())
                .allSatisfy(field -> assertThat(forbidden)
                        .as("member %s must not be a floating point or decimal type; the 80 byte user "
                                + "record has no monetary field at all", field.getName())
                        .doesNotContain(field.getType()));

        assertThat(Arrays.stream(UserSecurity.class.getDeclaredMethods())
                .map(Method::getReturnType)
                .toList())
                .as("and no accessor may hand one back either")
                .doesNotContainAnyElementsOf(forbidden);
    }

    @Test
    @DisplayName("does not implement Serializable, so a credential bearing type cannot be deserialised")
    void isNotSerializable() {
        assertThat(java.io.Serializable.class.isAssignableFrom(UserSecurity.class))
                .as("a credential bearing type is precisely the wrong thing to make serialisable, and "
                        + "the missing serialVersionUID lint would fail the build under -Werror anyway")
                .isFalse();

        assertThat(Arrays.stream(UserSecurity.class.getDeclaredFields())
                .map(Field::getName)
                .toList())
                .as("and no serialVersionUID may be left behind by a reverted attempt")
                .doesNotContain("serialVersionUID");
    }

    @Test
    @DisplayName("declares the converter as a nested public static class beside the field it serves")
    void converterIsANestedPublicStaticClass() {
        assertThat(UserTypeConverter.class.getEnclosingClass())
                .as("the mapping is meaningful only for this entity's one attribute, so it is declared "
                        + "beside that field rather than as a twelfth source file in a package whose "
                        + "eleven entities correspond one to one with the eleven VSAM clusters")
                .isEqualTo(UserSecurity.class);

        final int modifiers = UserTypeConverter.class.getModifiers();
        assertThat(Modifier.isPublic(modifiers))
                .as("the persistence provider instantiates the converter reflectively, so it must be "
                        + "reachable")
                .isTrue();
        assertThat(Modifier.isStatic(modifiers))
                .as("a non static inner class would need an enclosing instance the provider has no way "
                        + "to supply")
                .isTrue();
        assertThat(UserTypeConverter.class.getSimpleName()).isEqualTo("UserTypeConverter");
    }

    @Test
    @DisplayName("implements AttributeConverter<UserType, String>, mapping to a character and not a number")
    void converterImplementsTheCharacterValuedContract() {
        assertThat(AttributeConverter.class.isAssignableFrom(UserTypeConverter.class))
                .as("an explicit converter is the only mechanism that can map a named constant onto the "
                        + "single character CDEMO-USER-TYPE carries at app/cpy/COCOM01Y.cpy:L26")
                .isTrue();

        final List<Type> typeArguments = new ArrayList<>();
        for (final Type implemented : UserTypeConverter.class.getGenericInterfaces()) {
            if (implemented instanceof ParameterizedType parameterized
                    && parameterized.getRawType().equals(AttributeConverter.class)) {
                typeArguments.addAll(Arrays.asList(parameterized.getActualTypeArguments()));
            }
        }

        assertThat(typeArguments)
                .as("the attribute type must be UserType and the column type must be String; a "
                        + "converter to Character or Integer would change the stored representation")
                .containsExactly(UserType.class, String.class);
    }

    @Test
    @DisplayName("switches automatic application off, so it can never attach itself elsewhere")
    void converterIsNotAutoApplied() {
        final Converter converter = UserTypeConverter.class.getAnnotation(Converter.class);

        assertThat(converter)
                .as("without @Converter the provider does not recognise the class as a converter at all")
                .isNotNull();
        assertThat(converter.autoApply())
                .as("automatic application would attach this converter to every UserType attribute in "
                        + "the model as a side effect of being on the classpath; binding it to one "
                        + "attribute explicitly is the least privilege reading")
                .isFalse();
    }

    @Test
    @DisplayName("holds no state at all, so one instance is safe for concurrent provider use")
    void converterIsStateless() {
        assertThat(UserTypeConverter.class.getDeclaredFields())
                .as("both directions are pure functions of their argument: no cache, no counter and "
                        + "above all no static mutable state, which Rule 1 clause B forbids")
                .isEmpty();

        final Constructor<?>[] constructors = UserTypeConverter.class.getDeclaredConstructors();
        assertThat(constructors)
                .as("the provider needs exactly one no argument constructor and nothing else")
                .hasSize(1);
        assertThat(constructors[0].getParameterCount()).isZero();
        assertThat(Modifier.isPublic(constructors[0].getModifiers()))
                .as("declared explicitly and public, so the provider's requirement is stated in code "
                        + "rather than inferred from an implicit default")
                .isTrue();
    }

    @Test
    @DisplayName("binds the converter to secUsrType with exactly @Convert, @JdbcTypeCode and @Column")
    void userTypeFieldCarriesExactlyTheExpectedMappingAnnotations() throws NoSuchFieldException {
        final Field field = UserSecurity.class.getDeclaredField("secUsrType");

        assertThat(annotationSimpleNamesOf(field))
                .as("the field needs the conversion binding, the JDBC type code that makes the "
                        + "converter's String present itself as a CHAR, and the column mapping - and "
                        + "nothing more")
                .containsExactlyInAnyOrder("Convert", "JdbcTypeCode", "Column");

        final Convert convert = field.getAnnotation(Convert.class);
        assertThat(convert).isNotNull();
        assertThat(convert.converter())
                .as("the binding must name the nested converter, not some other implementation")
                .isEqualTo(UserTypeConverter.class);

        final Column column = field.getAnnotation(Column.class);
        assertThat(column.name()).isEqualTo("sec_usr_type");
        assertThat(column.nullable()).isFalse();
        assertThat(column.length())
                .as("SEC-USR-TYPE PIC X(01) at app/cpy/CSUSR01Y.cpy:L22 is one byte, byte 57 of the "
                        + "record")
                .isEqualTo(USER_TYPE_PIC_WIDTH);
        assertThat(field.getType()).isEqualTo(UserType.class);
    }

    @Test
    @DisplayName("uses no @Enumerated mode anywhere: neither the names nor the ordinals are the codes")
    void usesNoEnumeratedMappingAnywhere() {
        assertThat(annotationSimpleNamesOf(UserSecurity.class))
                .doesNotContain(Enumerated.class.getSimpleName());

        assertThat(persistentFields())
                .allSatisfy(field -> assertThat(field.getAnnotation(Enumerated.class))
                        .as("EnumType.STRING would write the constant names ADMIN and USER into a one "
                                + "character column, and EnumType.ORDINAL would write 0 and 1, which "
                                + "are not the codes app/cpy/COCOM01Y.cpy:L27-L28 declares and would "
                                + "shift the moment the constants were reordered. Member: %s",
                                field.getName())
                        .isNull());
    }

    @ParameterizedTest(name = "{0} is written as \"{1}\"")
    @CsvSource({"ADMIN, A", "USER, U"})
    @DisplayName("writes each constant as the one character code the copybook declares")
    void writesEachConstantAsItsCopybookCode(final UserType attribute, final String expectedCode) {
        assertThat(new UserTypeConverter().convertToDatabaseColumn(attribute))
                .as("app/cpy/COCOM01Y.cpy:L27-L28 binds 'A' to the administrator condition name and "
                        + "'U' to the standard user condition name; app/cbl/COSGN00C.cbl:L227 moves that "
                        + "very byte into CDEMO-USER-TYPE to choose the menu")
                .isEqualTo(expectedCode);
    }

    @Test
    @DisplayName("writes null for a null attribute, so the NOT NULL column rejects it rather than a stand-in")
    void writesNullForANullAttribute() {
        assertThat(new UserTypeConverter().convertToDatabaseColumn(null))
                .as("a null attribute must become a SQL null and be refused by the NOT NULL column; "
                        + "substituting a character would silently invent a privilege class")
                .isNull();
    }

    @ParameterizedTest(name = "\"{0}\" reads back as {1}")
    @CsvSource({
        "A,          ADMIN",
        "U,          USER",
        "'A       ', ADMIN",
        "'U       ', USER"
    })
    @DisplayName("reads each code back, tolerating the blank padding a fixed width column carries")
    void readsEachCodeBackToleratingPadding(final String stored, final UserType expected) {
        assertThat(new UserTypeConverter().convertToEntityAttribute(stored))
                .as("a CHAR(1) value may arrive blank padded, and padding is not part of the code")
                .isEqualTo(expected);
    }

    @Test
    @DisplayName("round trips both constants without loss, in both directions")
    void roundTripsEveryConstant() {
        final UserTypeConverter converter = new UserTypeConverter();

        for (final UserType constant : UserType.values()) {
            final String written = converter.convertToDatabaseColumn(constant);

            assertThat(written)
                    .as("every code is exactly one character wide, matching PIC X(01)")
                    .hasSize(USER_TYPE_PIC_WIDTH);
            assertThat(converter.convertToEntityAttribute(written))
                    .as("the round trip must be lossless for %s", constant.name())
                    .isEqualTo(constant);
        }
    }

    @ParameterizedTest(name = "a stored value of [{0}] reads back as null")
    @ValueSource(strings = {"", " ", "  ", "        ", "\t", "\n", "\r", "\u0000", "\u0000\u0000", " \t "})
    @DisplayName("reads an absent value back as null: padding is an absence, not an invalid code")
    void readsPaddingOnlyValuesBackAsNull(final String stored) {
        assertThat(new UserTypeConverter().convertToEntityAttribute(stored))
                .as("the column is fixed width, so a row that never had a user class set arrives as "
                        + "padding; trim() strips every character at or below the space, which covers "
                        + "the low value bytes a legacy record pads with as well as the blanks")
                .isNull();
    }

    @Test
    @DisplayName("reads a null column value back as null rather than throwing")
    void readsNullBackAsNull() {
        assertThat(new UserTypeConverter().convertToEntityAttribute(null))
                .as("a null column value is an absence and is handled explicitly, per Rule 1 clause B")
                .isNull();
    }

    @ParameterizedTest(name = "a stored value of \"{0}\" is rejected by name")
    @ValueSource(strings = {
        "X", "a", "u", "Z", "1", "0", "9", "$", "-", "*", "@", "?", "b",
        "AA", "AU", "UA", "UU", "Ax", "A U", "A1", "ADMIN", "USER", "admin", "user"
    })
    @DisplayName("rejects every other code by code point, without echoing the rejected value")
    void rejectsEveryOtherCodeByName(final String stored) {
        final UserTypeConverter converter = new UserTypeConverter();
        final String trimmed = stored.trim();
        final StringBuilder expectedCodePoints = new StringBuilder();
        for (int index = 0; index < trimmed.length(); index++) {
            if (index > 0) {
                expectedCodePoints.append(' ');
            }
            expectedCodePoints.append("0x").append(Integer.toHexString(trimmed.charAt(index)));
        }

        assertThatExceptionOfType(IllegalArgumentException.class)
                .as("guessing a privilege class is the one mistake a role store must never make, so an "
                        + "unrecognised code is raised rather than absorbed")
                .isThrownBy(() -> converter.convertToEntityAttribute(stored))
                .withMessageContaining(expectedCodePoints.toString())
                .withMessageContaining(trimmed.length() + " UTF-16 code unit(s)")
                .withMessageContaining("sec_usr_type")
                .withMessageContaining("COCOM01Y")
                .withNoCause();
    }

    @ParameterizedTest(name = "the rejection of \"{0}\" quotes no part of the rejected value")
    @ValueSource(strings = {
        "X", "a", "u", "Z", "1", "0", "9", "$", "-", "*", "@", "?", "b",
        "AA", "AU", "UA", "UU", "Ax", "A U", "A1", "ADMIN", "USER", "admin", "user",
        "A\r\nsec_usr_type=U", "U\nADMIN", "A\u0000U", "\u001b[31m", "\u00a0"
    })
    @DisplayName("never reproduces the rejected value in the message, so corrupt data cannot forge a log")
    void neverEchoesTheRejectedCodeIntoTheMessage(final String stored) {
        final UserTypeConverter converter = new UserTypeConverter();
        final String trimmed = stored.trim();

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> converter.convertToEntityAttribute(stored))
                .satisfies(thrown -> {
                    final String message = thrown.getMessage();
                    assertThat(message)
                            .as("the previous implementation emitted the rejected value in single quotes "
                                    + "and that exact rendering is what must be gone. Asserting the "
                                    + "quoted form rather than the bare value is deliberate: the message "
                                    + "is a sentence of English that legitimately contains most single "
                                    + "characters and the words ADMIN and USER, so a bare containment "
                                    + "check would report a leak that is not one and would prove nothing "
                                    + "about the values that matter. Rejected value: %s", trimmed)
                            .doesNotContain("'" + trimmed + "'");
                    assertThat(message)
                            .as("and no control character may reach the message by any route at all - "
                                    + "this is the assertion that actually closes the log forging path, "
                                    + "because a carriage return or line feed would terminate the line "
                                    + "in any sink that stores one event per line and an escape sequence "
                                    + "would reach a terminal that renders it")
                            .doesNotContain("\r")
                            .doesNotContain("\n")
                            .doesNotContain("\t")
                            .doesNotContain("\u001b")
                            .doesNotContain("\u0000")
                            .doesNotContain("\u00a0");
                    assertThat(message)
                            .as("what remains must still be diagnosable: the code point rendering "
                                    + "identifies the value completely and distinguishes a blank, a low "
                                    + "value byte and a non breaking space, which all print as nothing")
                            .contains("0x" + Integer.toHexString(trimmed.charAt(0)));
                });
    }

    @ParameterizedTest(name = "the printable code {0} outside 'A' and 'U' is rejected")
    @MethodSource("printableCodesOutsideTheCopybookDomain")
    @DisplayName("rejects the whole printable ASCII range apart from the two copybook codes")
    void rejectsTheWholePrintableRangeApartFromTheTwoCodes(final String stored) {
        final UserTypeConverter converter = new UserTypeConverter();

        assertThatExceptionOfType(IllegalArgumentException.class)
                .as("this is the Java side counterpart of the CHECK constraint that restricts "
                        + "sec_usr_type to 'A' and 'U'; the constraint's own SQL text is Not available, "
                        + "so the domain is asserted where it can be reached")
                .isThrownBy(() -> converter.convertToEntityAttribute(stored));
    }

    @Test
    @DisplayName("is deliberately case sensitive: a lower case code is invalid, not a synonym")
    void isCaseSensitiveByDesign() {
        final UserTypeConverter converter = new UserTypeConverter();

        for (final UserType constant : UserType.values()) {
            final String upper = converter.convertToDatabaseColumn(constant);
            final String lower = upper.toLowerCase(Locale.ROOT);

            assertThat(lower)
                    .as("the fold must actually change the character, or the case assertion is vacuous")
                    .isNotEqualTo(upper);
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("the source stores upper case codes only and compares them exactly; the sign on "
                            + "program's own upper casing at app/cbl/COSGN00C.cbl:L132 and :L135 is "
                            + "service tier behaviour applied to input, never to a stored code")
                    .isThrownBy(() -> converter.convertToEntityAttribute(lower));
        }
    }

    @Test
    @DisplayName("never resolves an unrecognised code to a constant, to null or to the first constant")
    void neverResolvesAnUnrecognisedCodeToAnything() {
        final UserTypeConverter converter = new UserTypeConverter();

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> converter.convertToEntityAttribute("X"));
        assertThat(UserType.fromCode('X'))
                .as("the enum's own lookup is empty for an unknown code, so the converter has nothing "
                        + "to fall back on and no fallback constant exists to be reached")
                .isEmpty();
        assertThat(UserType.values())
                .as("and the constant set is closed at the two condition names of "
                        + "app/cpy/COCOM01Y.cpy:L27-L28, with no widening UNKNOWN member")
                .containsExactly(UserType.ADMIN, UserType.USER);
    }

    @Test
    @DisplayName("is the only reference from this entity into com.cardemo.model.enums")
    void userTypeIsTheOnlyEnumTheEntityReferences() throws IOException {
        final String enumPackage = "com/cardemo/model/enums/";
        final String combined = classFileTextOf(UserSecurity.class.getSimpleName())
                + classFileTextOf("UserSecurity$UserTypeConverter");
        final Set<String> referenced = new HashSet<>();

        int cursor = combined.indexOf(enumPackage);
        while (cursor >= 0) {
            int end = cursor + enumPackage.length();
            while (end < combined.length() && isInternalNameCharacter(combined.charAt(end))) {
                end++;
            }
            referenced.add(combined.substring(cursor + enumPackage.length(), end));
            cursor = combined.indexOf(enumPackage, end);
        }

        assertThat(referenced)
                .as("UserType is the one enumeration this entity needs; FileStatus, RejectCode and "
                        + "TransactionSource belong to the batch and I/O tiers and have no place in a "
                        + "record layout")
                .containsExactly(UserType.class.getSimpleName());
    }

    @Test
    @DisplayName("is the only entity in the package that references com.cardemo.model.enums at all")
    void noSiblingEntityReferencesTheEnumPackage() throws IOException {
        final String enumPackage = "com/cardemo/model/enums";

        for (final String entityName : SIBLING_ENTITY_NAMES) {
            assertThat(classFileTextOf(entityName))
                    .as("%s must not reference the enum package: the other ten record layouts are "
                            + "character and decimal data with no closed domain to type, and a Javadoc "
                            + "mention does not survive compilation", entityName)
                    .doesNotContain(enumPackage);
        }
    }

    @Test
    @DisplayName("names the credential member passwordHash, so no caller can mistake it for a password")
    void credentialMemberIsNamedForWhatItHolds() {
        final List<String> memberNames = persistentFields().stream().map(Field::getName).toList();

        assertThat(memberNames)
                .as("the source field SEC-USR-PWD at app/cpy/CSUSR01Y.cpy:L21 held plaintext, which "
                        + "app/cbl/COSGN00C.cbl:L223 compared directly; a Java member called secUsrPwd "
                        + "would invite exactly that comparison")
                .contains("passwordHash")
                .doesNotContain("secUsrPwd", "password", "pwd", "passwd", "plaintextPassword");

        assertThat(UserSecurity.class.getDeclaredMethods())
                .as("and the accessor pair must be named for the hash as well")
                .anySatisfy(method -> assertThat(method.getName()).isEqualTo("getPasswordHash"))
                .anySatisfy(method -> assertThat(method.getName()).isEqualTo("setPasswordHash"));
    }

    @Test
    @DisplayName("maps the credential to sec_usr_pwd as a 60 character VARCHAR, never a CHAR(8)")
    void credentialColumnIsASixtyCharacterVarchar() throws NoSuchFieldException {
        final Field field = UserSecurity.class.getDeclaredField("passwordHash");
        final Column column = field.getAnnotation(Column.class);

        assertThat(column.name())
                .as("the column keeps the COBOL provenance of SEC-USR-PWD so a migration author can "
                        + "trace the mapping, while the Java member says what the value is")
                .isEqualTo("sec_usr_pwd");
        assertThat(column.length())
                .as("a BCrypt hash is exactly %d characters; a CHAR(8) column - the source PIC width - "
                        + "would truncate every hash to garbage", BCRYPT_HASH_WIDTH)
                .isEqualTo(BCRYPT_HASH_WIDTH)
                .isNotEqualTo(SOURCE_CREDENTIAL_PIC_WIDTH);
        assertThat(column.nullable()).isFalse();
        assertThat(field.getType()).isEqualTo(String.class);

        assertThat(annotationSimpleNamesOf(field))
                .as("this field alone carries no JDBC type code override, and that omission is load "
                        + "bearing: a String maps to VARCHAR by default, which is exactly the "
                        + "expectation a VARCHAR(60) column needs under ddl-auto validate, whereas the "
                        + "four CHAR columns each need an explicit code")
                .containsExactly("Column");
    }

    @ParameterizedTest(name = "{0} is a CHAR column and carries an explicit JDBC type code")
    @ValueSource(strings = {"secUsrId", "secUsrFname", "secUsrLname", "secUsrType"})
    @DisplayName("gives every fixed width CHAR member an explicit JDBC type code, unlike the credential")
    void everyCharacterMemberCarriesAnExplicitJdbcTypeCode(final String member) throws NoSuchFieldException {
        assertThat(annotationSimpleNamesOf(UserSecurity.class.getDeclaredField(member)))
                .as("schema validation compares type codes, so a CHAR column mapped without an explicit "
                        + "code fails startup with a SchemaManagementException naming it. Member: %s",
                        member)
                .contains("JdbcTypeCode");
    }

    @Test
    @DisplayName("accepts a well formed BCrypt hash at the pinned strength, and only at that strength")
    void recognisesAWellFormedHashAtThePinnedStrength() {
        final String hash = syntheticHash(1);

        assertThat(hash)
                .as("the shape is the only property of a stored credential this tier can assert: seven "
                        + "characters of version and cost, then 53 of salt and digest")
                .hasSize(BCRYPT_HASH_WIDTH)
                .startsWith(BCRYPT_PREFIX)
                .matches(BCRYPT_SHAPE);
        assertThat(BCRYPT_PREFIX)
                .as("the cost field pins BCrypt strength 10, so a hash produced at any other work "
                        + "factor fails the shape")
                .isEqualTo("$2a$10$");
    }

    @ParameterizedTest(name = "the malformed value \"{0}\" is not a BCrypt hash")
    @MethodSource("malformedCredentialValues")
    @DisplayName("rejects every value that is not a 60 character BCrypt hash at strength 10")
    void rejectsEveryMalformedCredentialShape(final String candidate, final String why) {
        assertThat(BCRYPT_SHAPE.matcher(candidate).matches())
                .as("%s - a value the credential column must never be trusted to hold", why)
                .isFalse();
    }

    @Test
    @DisplayName("cannot be satisfied by any value of the source's 8 byte plaintext width")
    void noValueOfTheSourcePasswordWidthCanBeAHash() {
        final String eightCharacters = BCRYPT_PREFIX + "X";

        assertThat(eightCharacters)
                .as("built from the shape's own prefix so the counterexample is as favourable as "
                        + "possible: even then it is far too short")
                .hasSize(SOURCE_CREDENTIAL_PIC_WIDTH);
        assertThat(BCRYPT_SHAPE.matcher(eightCharacters).matches()).isFalse();
        assertThat(BCRYPT_SHAPE.matcher("12345678").matches()).isFalse();
        assertThat(SOURCE_CREDENTIAL_PIC_WIDTH)
                .as("the shape requires %d characters, so no value of the source's %d byte plaintext "
                        + "width can ever satisfy it - which is why the seeded plaintext is never "
                        + "needed, and never written, to prove the point",
                        BCRYPT_HASH_WIDTH, SOURCE_CREDENTIAL_PIC_WIDTH)
                .isLessThan(BCRYPT_HASH_WIDTH);
    }

    @Test
    @DisplayName("embeds no credential candidate in its compiled string constants")
    void compiledEntityEmbedsNoCredentialCandidate() throws IOException {
        final List<String> constants = new ArrayList<>();
        constants.addAll(stringConstantsOf(UserSecurity.class.getSimpleName()));
        constants.addAll(stringConstantsOf("UserSecurity$UserTypeConverter"));

        assertThat(constants)
                .as("the compiled constants must be non empty, or the scan proves nothing")
                .isNotEmpty();
        assertThat(constants)
                .as("no string constant may have the shape of the seeded plaintext - exactly %d "
                        + "characters, all upper case ASCII letters. Asserting the shape rather than the "
                        + "value is what lets the check be mechanical while the literal itself stays out "
                        + "of this file entirely, as Rule 1 clause D requires of tests",
                        SOURCE_CREDENTIAL_PIC_WIDTH)
                .noneMatch(UserSecurityTest::looksLikeTheSeededPlaintext);
        assertThat(constants)
                .as("and no BCrypt hash may be embedded either, in any version prefix: a committed hash "
                        + "is a committed credential, and V3__seed_data.sql owns the seeded values")
                .noneMatch(constant -> constant.contains("$2a$") || constant.contains("$2b$")
                        || constant.contains("$2y$") || constant.contains("$2x$"));
    }

    @Test
    @DisplayName("performs no hashing, verification or encoding of its own")
    void performsNoCredentialProcessing() throws IOException {
        final String combined = classFileTextOf(UserSecurity.class.getSimpleName())
                + classFileTextOf("UserSecurity$UserTypeConverter");

        assertThat(combined)
                .as("producing a hash belongs to the authentication service and verifying one to the "
                        + "security configuration; a data holder that could do either would be a "
                        + "second place for the work factor to be chosen. The assertion is on the "
                        + "referenced types rather than on the word BCrypt, because the entity now names "
                        + "the algorithm in its shape rule and in the diagnostics that rule throws - and "
                        + "naming an encoding is not implementing one. What must stay absent is any type "
                        + "that could compute or compare a digest")
                .doesNotContain("PasswordEncoder")
                .doesNotContain("crypto/bcrypt")
                .doesNotContain("BCrypt.")
                .doesNotContain("MessageDigest")
                .doesNotContain("javax/crypto")
                .doesNotContain("java/security")
                .doesNotContain("springframework");

        assertThat(UserSecurity.class.getDeclaredMethods())
                .as("and no method may look like a comparison helper that takes a presented password")
                .noneSatisfy(method -> assertThat(method.getName().toLowerCase(Locale.ROOT))
                        .containsAnyOf("matches", "verify", "encode", "check", "authenticate"));

        assertThat(UserSecurity.class.getDeclaredMethods())
                .as("the shape rule must be a structural check over the encoding, so it takes and returns "
                        + "one value and cannot be handed a presented password to compare against")
                .filteredOn(method -> "requireBcryptStrength10Digest".equals(method.getName()))
                .singleElement()
                .satisfies(method -> {
                    assertThat(method.getParameterCount())
                            .as("a verifier would need two arguments: a presented password and a digest")
                            .isEqualTo(1);
                    assertThat(method.getReturnType())
                            .as("it returns the value it validated, not a boolean verdict")
                            .isEqualTo(String.class);
                });
    }

    @Test
    @DisplayName("offers no constructor or setter that accepts a password in plaintext")
    void offersNoPlaintextAcceptingEntryPoint() {
        final List<Constructor<?>> constructors =
                Arrays.asList(UserSecurity.class.getDeclaredConstructors());

        assertThat(constructors)
                .as("a no argument constructor for the provider and one all columns constructor for "
                        + "application, migration and test code - and no convenience overload that "
                        + "appeared to accept a plaintext password, which would be a lie about where "
                        + "the hashing responsibility sits")
                .hasSize(2);
        assertThat(constructors)
                .allSatisfy(constructor -> assertThat(constructor.getParameterCount())
                        .isIn(0, MODELLED_MEMBER_COUNT));

        assertThat(UserSecurity.class.getDeclaredMethods())
                .as("and exactly one accessor pair reaches the credential - one getter and one setter, "
                        + "which is why the expected count is two - with the setter taking the hash the "
                        + "caller has already computed rather than a password it would have to hash")
                .filteredOn(method -> method.getName().toLowerCase(Locale.ROOT).contains("password"))
                .hasSize(2);
    }

    @Test
    @DisplayName("renders the identifier and the user class, and nothing else")
    void renderingCarriesOnlyTheIdentifierAndUserClass() {
        final UserSecurity user = seededUser(0);
        final String rendered = user.toString();

        assertThat(rendered)
                .as("what remains is the minimum that makes a log line useful - which row, and which "
                        + "privilege class. User: %s", user.getSecUsrId())
                .isEqualTo("UserSecurity{secUsrId=" + user.getSecUsrId()
                        + ", secUsrType=" + user.getSecUsrType().name() + "}");
        assertThat(rendered)
                .as("the user class renders as its constant name rather than its stored code, because a "
                        + "diagnostic reader is better served by ADMIN than by A. The closing brace is "
                        + "part of the needle deliberately: 'secUsrType=A' on its own is a prefix of "
                        + "'secUsrType=ADMIN' and would match either rendering")
                .contains("secUsrType=" + UserType.ADMIN.name() + "}")
                .doesNotContain("secUsrType=" + UserType.ADMIN.getCode() + "}");
    }

    @Test
    @DisplayName("never renders the credential: no hash, no version prefix, no accessor name")
    void renderingNeverCarriesTheCredential() {
        final String hash = syntheticHash(3);
        final UserSecurity user = new UserSecurity("STDUSR03", "FNAMEA8", "LNM8", hash, UserType.USER);

        assertThat(user.toString())
                .as("a rendering is exactly how credential material escapes into a log aggregator, an "
                        + "exception message, a metric tag or a trace. There is no flag, profile or "
                        + "debug mode that adds it. User: %s", user.getSecUsrId())
                .doesNotContain(hash)
                .doesNotContain(BCRYPT_PREFIX)
                .doesNotContain("$2")
                .doesNotContain("passwordHash");
    }

    @Test
    @DisplayName("never renders the two name fields, which are personal data a log line does not need")
    void renderingNeverCarriesPersonalData() {
        final UserSecurity user = new UserSecurity(
                "ADMNUSR4", "FNAMEAA4", "LNAMEAA4", syntheticHash(4), UserType.ADMIN);

        assertThat(user.toString())
                .as("the names are omitted under least privilege: they add nothing the primary key does "
                        + "not already identify. This is why the rendering is narrower than the "
                        + "reference data entities', which render every column they hold. User: %s",
                        user.getSecUsrId())
                .doesNotContain("FNAMEAA4")
                .doesNotContain("LNAMEAA4")
                .doesNotContain("secUsrFname")
                .doesNotContain("secUsrLname");
    }

    @Test
    @DisplayName("renders a transient instance without throwing, showing nulls rather than substitutes")
    void renderingIsNullSafe() {
        final UserSecurity blank = providerMaterialisedUser();

        assertThatCode(blank::toString)
                .as("an instance the provider has not yet populated must still be loggable. It is built "
                        + "through the provider's own no argument route, because the all columns "
                        + "constructor now rejects a null credential by design")
                .doesNotThrowAnyException();
        assertThat(blank.toString())
                .as("nulls are shown rather than replaced by a default, which would misreport the state")
                .isEqualTo("UserSecurity{secUsrId=null, secUsrType=null}");
    }

    @Test
    @DisplayName("renders the identifier exactly as held, so fixed width padding stays visible")
    void renderingDoesNotTrimTheIdentifier() {
        final UserSecurity padded = new UserSecurity(
                "STDU1   ", "FN0", "LN10", syntheticHash(5), UserType.USER);

        assertThat(padded.toString())
                .as("a rendering that trimmed would hide the very padding a fixed width defect shows up "
                        + "as. User: [%s]", padded.getSecUsrId())
                .contains("secUsrId=STDU1   ,");
    }

    @Test
    @DisplayName("is equal to itself, unequal to null and unequal to an unrelated type")
    void equalityIsReflexiveAndNullSafe() {
        final UserSecurity user = seededUser(0);

        assertThat(user.equals(user)).as("reflexive").isTrue();
        assertThat(user.equals(null)).as("null safe, never a NullPointerException").isFalse();
        assertThat(user.equals("ADMNUSR1")).as("unequal to an unrelated type").isFalse();
    }

    @Test
    @DisplayName("compares on secUsrId alone, ignoring the names, the credential and the user class")
    void equalityIsComputedOverTheIdentifierAlone() {
        final UserSecurity first = new UserSecurity(
                "ADMNUSR1", "FNAMEAA1", "LNM1", syntheticHash(1), UserType.ADMIN);
        final UserSecurity rotated = new UserSecurity(
                "ADMNUSR1", "FNAMEAA1", "PLATINUM", syntheticHash(9), UserType.USER);

        assertThat(first)
                .as("the identifier is a natural key that arrives from the seed rather than a generated "
                        + "surrogate, so it is populated from the moment an instance is meaningful and "
                        + "stays stable while a name, a class or a credential can all legitimately "
                        + "change. Two instances denoting user %s must not start comparing unequal "
                        + "because one has had its password rotated", first.getSecUsrId())
                .isEqualTo(rotated);
        assertThat(rotated).as("symmetric").isEqualTo(first);
        assertThat(first.hashCode())
                .as("and the hash must agree, or a hash based collection loses the row")
                .isEqualTo(rotated.hashCode());
    }

    @Test
    @DisplayName("is symmetric and transitive across three instances of the same identifier")
    void equalityIsSymmetricAndTransitive() {
        final UserSecurity first = seededUser(6);
        final UserSecurity second = new UserSecurity(
                first.getSecUsrId(), "FNAM7", "LNAM7", syntheticHash(2), UserType.USER);
        final UserSecurity third = new UserSecurity(
                first.getSecUsrId(), "OTHER", "NAME", syntheticHash(8), UserType.ADMIN);

        assertThat(first.equals(second)).as("first equals second").isTrue();
        assertThat(second.equals(third)).as("second equals third").isTrue();
        assertThat(first.equals(third)).as("so first must equal third").isTrue();
        assertThat(second.equals(first)).as("and each direction must agree").isTrue();
        assertThat(third.equals(second)).isTrue();
    }

    @Test
    @DisplayName("distinguishes two different identifiers even when every other member matches")
    void equalityDistinguishesDifferentIdentifiers() {
        final String hash = syntheticHash(1);
        final UserSecurity admin = new UserSecurity("ADMNUSR1", "SAME", "SAME", hash, UserType.ADMIN);
        final UserSecurity other = new UserSecurity("ADMNUSR2", "SAME", "SAME", hash, UserType.ADMIN);

        assertThat(admin)
                .as("users %s and %s are distinct rows", admin.getSecUsrId(), other.getSecUsrId())
                .isNotEqualTo(other);
    }

    @Test
    @DisplayName("hashes on secUsrId alone, stably, and does not throw when the key is absent")
    void hashCodeIsStableAndDerivedFromTheIdentifier() {
        final UserSecurity user = seededUser(2);
        final int first = user.hashCode();

        assertThat(user.hashCode())
                .as("the value must be stable for the lifetime of an instance whose key does not "
                        + "change, which is the property a hash based collection requires. User: %s",
                        user.getSecUsrId())
                .isEqualTo(first)
                .isEqualTo(user.getSecUsrId().hashCode());

        final UserSecurity unkeyed = providerMaterialisedUser();
        assertThatCode(unkeyed::hashCode)
                .as("a transient instance whose key is still null must hash rather than throw. The "
                        + "credential is supplied because it is guarded on the way in, and it takes no "
                        + "part in the hash, which is what the assertion below proves")
                .doesNotThrowAnyException();
        assertThat(unkeyed.hashCode()).isZero();
    }

    @Test
    @DisplayName("excludes the credential from equality and hashing, so no hash reaches a collection call")
    void credentialTakesNoPartInIdentity() {
        final UserSecurity user = seededUser(0);
        final String originalHash = user.getPasswordHash();

        user.setPasswordHash(syntheticHash(10));

        assertThat(user.getPasswordHash())
                .as("the rotation must actually have happened, or the assertion is vacuous")
                .isNotEqualTo(originalHash);
        assertThat(user)
                .as("reading the hash inside equals would put credential material on a code path that "
                        + "collection membership calls implicitly and frequently, and a hash comparison "
                        + "is not a meaningful contributor to identity in the first place. User: %s",
                        user.getSecUsrId())
                .isEqualTo(seededUser(0));
        assertThat(user.hashCode()).isEqualTo(seededUser(0).hashCode());
    }

    @Test
    @DisplayName("behaves correctly as a hash based collection member, keyed by identifier")
    void behavesAsAHashBasedCollectionMember() {
        final Set<UserSecurity> users = new HashSet<>();
        for (int index = 0; index < CATALOGUED_RECORD_COUNT; index++) {
            users.add(seededUser(index));
        }

        assertThat(users)
                .as("app/catlg/LISTCAT.txt:L3888 reports REC-TOTAL %d, and the ten identifiers are "
                        + "distinct, so the set must hold ten members", CATALOGUED_RECORD_COUNT)
                .hasSize(CATALOGUED_RECORD_COUNT);
        assertThat(users.add(seededUser(0)))
                .as("re-adding an equal instance must be rejected by the set")
                .isFalse();
        assertThat(users)
                .as("and lookup must succeed on an instance that differs in every member but the key")
                .contains(new UserSecurity(
                        seededUser(0).getSecUsrId(), "DIFFERENT", "DIFFERENT",
                        syntheticHash(7), UserType.USER));
    }

    @Test
    @DisplayName("implements no interface at all, so it cannot be a Spring Security principal")
    void implementsNoInterfaceAndExtendsNoFrameworkType() {
        assertThat(UserSecurity.class.getInterfaces())
                .as("it does not implement Spring Security's user details contract and declares none of "
                        + "that contract's authorisation state - no activation flag, no expiry or lock "
                        + "flags, no granted authority collection. None of those exists in the 80 byte "
                        + "record of app/cpy/CSUSR01Y.cpy:L17-L23, so adding one would break the record "
                        + "contract. The adaptation belongs to CardDemoUserDetailsService")
                .isEmpty();
        assertThat(UserSecurity.class.getSuperclass())
                .as("and it extends no framework or mapped superclass")
                .isEqualTo(Object.class);
        assertThat(UserSecurity.class.getName())
                .as("it lives in the model package, one layer below security")
                .isEqualTo("com.cardemo.model.entity.UserSecurity");
    }

    @ParameterizedTest(name = "the compiled entity references nothing from {0}")
    @MethodSource("forbiddenInternalNames")
    @DisplayName("references no forbidden layer, framework or capability in its compiled form")
    void referencesNoForbiddenLayerInItsCompiledForm(final String internalName) throws IOException {
        final String combined = classFileTextOf(UserSecurity.class.getSimpleName())
                + classFileTextOf("UserSecurity$UserTypeConverter");

        assertThat(combined)
                .as("the compiled class file is the authority rather than the import list: an import can "
                        + "be absent while a fully qualified reference in a method body is not, and "
                        + "source comments do not survive compilation at all. Forbidden: %s",
                        internalName)
                .doesNotContain(internalName);
    }

    @Test
    @DisplayName("carries no association, lifecycle callback, inheritance or validation annotation")
    void carriesNoForbiddenAnnotationAnywhere() {
        final List<String> present = new ArrayList<>();
        for (final Annotation annotation : allDeclaredAnnotations(UserSecurity.class)) {
            present.add(annotation.annotationType().getSimpleName());
        }
        for (final Annotation annotation : allDeclaredAnnotations(UserTypeConverter.class)) {
            present.add(annotation.annotationType().getSimpleName());
        }

        assertThat(present)
                .as("the source record has no relationship to any other cluster, so there is no "
                        + "association and therefore no cascade, no lazy loading and no N+1 pattern; no "
                        + "callback exists on the read or delete path that could hash, mutate or "
                        + "normalise the credential; and no bean validation constraint exists that would "
                        + "reject the blank but non null values a fixed width source legitimately holds")
                .isNotEmpty()
                .doesNotContainAnyElementsOf(FORBIDDEN_ANNOTATION_SIMPLE_NAMES);
    }

    /**
     * A bean declaring the entity as a property, used to prove the type level barrier removes it.
     *
     * <p>Declared here rather than reached for in production code deliberately: the property this holder
     * exists to demonstrate is that <em>any</em> object anywhere that happens to declare a persistence
     * entity as a field loses that field on serialisation, and a holder written for the test is the
     * clearest way to show that without depending on some particular response object continuing to have
     * that shape.
     */
    private static final class EntityHolder {

        /** The entity under test, held as a declared property so that Jackson can discover it. */
        private final UserSecurity user;

        /**
         * Wraps the supplied entity.
         *
         * @param user the entity to hold, never {@code null} in this test
         */
        EntityHolder(final UserSecurity user) {
            this.user = user;
        }

        /**
         * A conventional bean accessor, which is what makes the property discoverable.
         *
         * @return the held entity
         */
        public UserSecurity getUser() {
            return user;
        }
    }

    @Test
    @DisplayName("carries the JSON serialisation barrier, so no route through Jackson discloses a column")
    void carriesTheJsonSerialisationBarrier() throws Exception {
        assertThat(UserSecurity.class.getDeclaredAnnotation(JsonIgnoreType.class))
                .as("the type level ignore is what removes this entity wherever it appears as a property "
                        + "of something else, which is the disclosure route that does not require anybody "
                        + "to have written a controller returning the entity directly")
                .isNotNull();

        final JsonAutoDetect autoDetect = UserSecurity.class.getDeclaredAnnotation(JsonAutoDetect.class);
        assertThat(autoDetect)
                .as("and the visibility override is what removes every property when the entity is the "
                        + "root value, where the type level ignore does not reach")
                .isNotNull();
        assertThat(autoDetect.getterVisibility()).as("getter visibility").isEqualTo(Visibility.NONE);
        assertThat(autoDetect.isGetterVisibility()).as("is-getter visibility").isEqualTo(Visibility.NONE);
        assertThat(autoDetect.setterVisibility()).as("setter visibility").isEqualTo(Visibility.NONE);
        assertThat(autoDetect.creatorVisibility()).as("creator visibility").isEqualTo(Visibility.NONE);
        assertThat(autoDetect.fieldVisibility())
                .as("field visibility must be NONE as well, because the provider uses field access on "
                        + "this class and a serialiser configured to read fields would otherwise reach "
                        + "every column the getters no longer expose")
                .isEqualTo(Visibility.NONE);

        assertThat(UserSecurity.class.getDeclaredMethod("getPasswordHash")
                .getDeclaredAnnotation(JsonIgnore.class))
                .as("the credential accessor carries its own ignore in addition to the two type level "
                        + "annotations, so that the barrier survives a future subclass, mix-in or "
                        + "visibility override that re-enables getters for this type")
                .isNotNull();
    }

    @Test
    @DisplayName("discloses no column through a real object mapper, at the root, in a list or as a field")
    void disclosesNoColumnThroughARealObjectMapper() throws Exception {
        final UserSecurity user = seededUser(3);
        final ObjectMapper mapper = new ObjectMapper();

        assertThat(mapper.writeValueAsString(user))
                .as("serialised as the root value the entity yields an empty object: every property is "
                        + "invisible, so there is nothing to write. This is the accidental disclosure the "
                        + "finding described - a repository result handed straight back from a controller "
                        + "or logged as JSON - and it now yields nothing at all. User: %s",
                        user.getSecUsrId())
                .isEqualTo("{}")
                .doesNotContain(user.getSecUsrId())
                .doesNotContain(user.getSecUsrFname())
                .doesNotContain(user.getSecUsrLname())
                .doesNotContain(user.getPasswordHash())
                .doesNotContain("sec_usr");

        assertThat(mapper.writeValueAsString(List.of(user)))
                .as("and a collection of them, which is what a page of results actually is, yields a list "
                        + "of empty objects rather than a list of credentials")
                .isEqualTo("[{}]");

        assertThatExceptionOfType(InvalidDefinitionException.class)
                .as("held as a declared property the entity is removed outright by the type level ignore, "
                        + "which leaves a holder whose only property was the entity with no properties at "
                        + "all. Jackson reports that rather than writing an empty object, so a response "
                        + "object that tried to embed a persistence entity fails loudly at the boundary "
                        + "instead of quietly shipping the columns")
                .isThrownBy(() -> mapper.writeValueAsString(new EntityHolder(user)))
                .withMessageContaining("no properties discovered");
    }

    @ParameterizedTest(name = "{0} carries the barrier and serialises to an empty object")
    @MethodSource("siblingEntityNames")
    @DisplayName("every sibling entity in the package carries the same barrier, uniformly")
    void everySiblingEntityCarriesTheSameBarrier(final String entityName) throws Exception {
        final Class<?> entity = Class.forName("com.cardemo.model.entity." + entityName);

        assertThat(entity.getDeclaredAnnotation(JsonIgnoreType.class))
                .as("%s must carry the type level ignore. Applying the barrier to only the entities "
                        + "whose columns are obviously sensitive would leave the control unverifiable by "
                        + "inspection, and would leave the next entity added to the package silently "
                        + "outside it; uniform application is what makes it checkable", entityName)
                .isNotNull();

        final JsonAutoDetect autoDetect = entity.getDeclaredAnnotation(JsonAutoDetect.class);
        assertThat(autoDetect).as("%s must carry the visibility override", entityName).isNotNull();
        assertThat(List.of(autoDetect.getterVisibility(), autoDetect.isGetterVisibility(),
                        autoDetect.setterVisibility(), autoDetect.creatorVisibility(),
                        autoDetect.fieldVisibility()))
                .as("all five visibilities on %s must be NONE; leaving field visibility at its default "
                        + "would readmit every column, because the provider maps this class by field",
                        entityName)
                .containsOnly(Visibility.NONE);

        final Constructor<?> providerConstructor = entity.getDeclaredConstructor();
        providerConstructor.setAccessible(true);
        final Object instance = providerConstructor.newInstance();

        assertThat(new ObjectMapper().writeValueAsString(instance))
                .as("an instance of %s discloses nothing through a default object mapper. The instance "
                        + "here is blank, and that is what makes the assertion meaningful rather than "
                        + "vacuous: without the barrier every column would appear as an explicit null, "
                        + "so an empty object proves the properties are invisible rather than merely "
                        + "unset", entityName)
                .isEqualTo("{}");
    }

    @Test
    @DisplayName("carries exactly one lifecycle callback, on the write path only, and it mutates nothing")
    void carriesOnlyANonMutatingWriteCallback() throws Exception {
        final List<Method> callbacks = Arrays.stream(UserSecurity.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(PrePersist.class)
                        || method.isAnnotationPresent(PreUpdate.class))
                .toList();

        assertThat(callbacks)
                .as("one method serves both write events, which the specification permits and which is "
                        + "what keeps insert and update asserting the identical invariant. Two methods "
                        + "would be two places for the rule to drift")
                .singleElement()
                .satisfies(method -> {
                    assertThat(method.isAnnotationPresent(PrePersist.class))
                            .as("the invariant must hold on insert").isTrue();
                    assertThat(method.isAnnotationPresent(PreUpdate.class))
                            .as("and on update, which is the path a credential rotation takes").isTrue();
                    assertThat(method.getReturnType())
                            .as("a lifecycle callback returns void")
                            .isEqualTo(void.class);
                    assertThat(method.getParameterCount())
                            .as("and takes no argument, so it can only read the state it is called on")
                            .isZero();
                    assertThat(Modifier.isPrivate(method.getModifiers()))
                            .as("private, because the provider reaches it reflectively and nothing "
                                    + "outside the entity has any business invoking it")
                            .isTrue();
                });

        final UserSecurity user = seededUser(0);
        final Map<String, Object> before = new LinkedHashMap<>();
        for (final Field field : persistentFields()) {
            field.setAccessible(true);
            before.put(field.getName(), field.get(user));
        }

        final Method callback = callbacks.get(0);
        callback.setAccessible(true);
        callback.invoke(user);

        for (final Field field : persistentFields()) {
            field.setAccessible(true);
            assertThat(field.get(user))
                    .as("the callback asserts and returns: member %s must be byte for byte what it was "
                            + "before the call. A callback that repaired, trimmed, re-encoded or "
                            + "re-hashed anything would change stored data on every write, which is the "
                            + "hazard the forbidden-annotation sweep exists to catch", field.getName())
                    .isEqualTo(before.get(field.getName()));
        }
    }

    @Test
    @DisplayName("rejects an unpopulated instance at the write callback rather than at the NOT NULL column")
    void rejectsAnUnpopulatedInstanceAtTheWriteCallback() throws Exception {
        final UserSecurity unpopulated = providerMaterialisedUser();
        final Method callback = UserSecurity.class.getDeclaredMethod(
                "assertCredentialInvariantBeforeWrite");
        callback.setAccessible(true);

        assertThat(catchThrowable(() -> callback.invoke(unpopulated)))
                .as("an instance the provider materialised and nothing populated must fail before the "
                        + "insert statement exists, naming the invariant, rather than inside the driver "
                        + "as a not-null violation naming a column")
                .isInstanceOf(InvocationTargetException.class)
                .cause()
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sec_usr_pwd")
                .hasMessageContaining("was null");
    }

    @Test
    @DisplayName("names no member after personal data, authorisation state, a filler or a logger")
    void namesNoMemberAfterAForbiddenConcern() {
        final List<String> lowerCaseNames = new ArrayList<>();
        for (final Field field : persistentFields()) {
            lowerCaseNames.add(field.getName().toLowerCase(Locale.ROOT));
        }
        for (final Field field : UserTypeConverter.class.getDeclaredFields()) {
            lowerCaseNames.add(field.getName().toLowerCase(Locale.ROOT));
        }

        assertThat(lowerCaseNames)
                .as("folded with Locale.ROOT so the check cannot change behaviour on a host whose case "
                        + "rules differ, such as a Turkish locale where I does not fold to i")
                .isNotEmpty();
        assertThat(lowerCaseNames).allSatisfy(name -> assertThat(name)
                .as("member %s must not name a concern outside the five item record layout; note that "
                        + "hash is deliberately absent from the forbidden list, because passwordHash is "
                        + "the required name and the whole point of it is that it says what the value "
                        + "is", name)
                .doesNotContainAnyWhitespaces()
                .satisfies(candidate -> assertThat(FORBIDDEN_MEMBER_NAME_FRAGMENTS)
                        .noneMatch(candidate::contains)));
    }

    @Test
    @DisplayName("emits no log line of its own, from either the entity or the converter")
    void emitsNoLogLine() {
        for (final Field field : persistentFields()) {
            assertThat(field.getType().getName())
                    .as("member %s must not be a logger: the masking rules in logback-spring.xml are a "
                            + "backstop for accidents, and never emitting the value is the primary "
                            + "defence", field.getName())
                    .doesNotContain("Logger");
        }

        final List<Field> staticFields = Arrays.stream(UserSecurity.class.getDeclaredFields())
                .filter(field -> Modifier.isStatic(field.getModifiers()))
                .filter(field -> !field.isSynthetic())
                .toList();

        assertThat(staticFields)
                .as("no static member may be writable, whatever its type: a non final static field is "
                        + "precisely the global mutable state Rule 1 clause B forbids")
                .isNotEmpty()
                .allSatisfy(field -> assertThat(Modifier.isFinal(field.getModifiers()))
                        .as("static member %s must be final", field.getName())
                        .isTrue());

        assertThat(staticFields)
                .as("every static member is either a primitive width constant or the compiled credential "
                        + "shape. A compiled Pattern is immutable and thread safe by specification, so it "
                        + "is a constant in the same sense the widths are - which is exactly why it is "
                        + "compiled once here instead of per call. No static logger, cache, buffer, "
                        + "collection or counter exists")
                .allSatisfy(field -> assertThat(field.getType())
                        .as("static member %s has type %s", field.getName(), field.getType().getName())
                        .satisfiesAnyOf(
                                type -> assertThat(type.isPrimitive()).isTrue(),
                                type -> assertThat(type).isEqualTo(Pattern.class)));

        assertThat(staticFields)
                .as("and none may be a logger, a mutable container or an accumulator by type")
                .allSatisfy(field -> assertThat(field.getType().getName())
                        .as("static member %s", field.getName())
                        .doesNotContain("Logger")
                        .doesNotContain("Collection")
                        .doesNotContain("List")
                        .doesNotContain("Map")
                        .doesNotContain("Set")
                        .doesNotContain("Buffer")
                        .doesNotContain("Atomic")
                        .doesNotContain("StringBuilder"));
    }

    @ParameterizedTest(name = "an identifier of [{0}] is carried verbatim")
    @ValueSource(strings = {"", "A", "STDU1", "STDUSR1", "STDUSR01", "stdusr01", "  A  ",
        "\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000"})
    @DisplayName("carries every identifier the picture clause admits verbatim: short, blank, low value "
            + "and mixed case")
    void carriesAnyIdentifierVerbatim(final String identifier) {
        assertThat(identifier.length())
                .as("every value in this set is within PIC X(08), which is what makes it a value the "
                        + "80 byte record can carry")
                .isLessThanOrEqualTo(CATALOGUED_KEY_LENGTH);

        final UserSecurity user = new UserSecurity(
                identifier, "FIRST", "LAST", syntheticHash(1), UserType.USER);

        assertThat(user.getSecUsrId())
                .as("no trimming, padding or case folding happens here: a shorter value is blank padded "
                        + "by the CHAR(8) column on write, not by the entity, and a value of only low "
                        + "values or only spaces is legitimate legacy data. The sign on program's own "
                        + "upper casing at app/cbl/COSGN00C.cbl:L132 is service tier behaviour applied "
                        + "to input")
                .isEqualTo(identifier);
    }

    @ParameterizedTest(name = "an identifier of [{0}] is refused because PIC X(08) cannot hold it")
    @ValueSource(strings = {"STDUSR012", "STDUSR01OVERFLOW",
        "                                        "})
    @DisplayName("refuses an identifier wider than PIC X(08) instead of deferring to the column width")
    void refusesAnIdentifierWiderThanTheKeyWidth(final String identifier) {
        assertThat(identifier.length())
                .as("each value in this set exceeds PIC X(08) at app/cpy/CSUSR01Y.cpy:L18, so no "
                        + "80 byte source record could have produced it")
                .isGreaterThan(CATALOGUED_KEY_LENGTH);

        assertThatExceptionOfType(IllegalArgumentException.class)
                .as("deferring this to the CHAR(8) column would report a column name from inside the "
                        + "driver at flush time rather than naming the property at the call site that "
                        + "supplied it, and on a CHAR column the failure mode is a truncation error "
                        + "whose text does not distinguish a width overrun from a type mismatch")
                .isThrownBy(() -> new UserSecurity(
                        identifier, "FIRST", "LAST", syntheticHash(1), UserType.USER))
                .withMessageContaining("secUsrId")
                .withMessageContaining("SEC-USR-ID PIC X(08)")
                .withMessageContaining(String.valueOf(identifier.length()));

        assertThatExceptionOfType(IllegalArgumentException.class)
                .as("and the message never quotes the value itself, holding the same line the "
                        + "credential guard holds")
                .isThrownBy(() -> new UserSecurity(
                        identifier, "FIRST", "LAST", syntheticHash(1), UserType.USER))
                .withMessageNotContaining(identifier.trim().isEmpty() ? "\u0000" : identifier);
    }

    @Test
    @DisplayName("carries a 7 character identifier unpadded and refuses a 9 character one outright")
    void treatsTheTwoSidesOfTheKeyWidthDifferently() {
        final String shorterThanTheKey = "STDUSR0";
        final String widerThanTheKey = "STDUSR001";

        assertThat(shorterThanTheKey).hasSize(CATALOGUED_KEY_LENGTH - 1);
        assertThat(widerThanTheKey).hasSize(CATALOGUED_KEY_LENGTH + 1);

        final UserSecurity shortId = new UserSecurity(
                shorterThanTheKey, "FIRST", "LAST", syntheticHash(1), UserType.USER);

        assertThat(shortId.getSecUsrId())
                .as("the two sides are not symmetric and must not be treated as though they were. A "
                        + "value shorter than the key is inside PIC X(08) - the source pads it on write "
                        + "and the CHAR(8) column pads it on read - so padding it here would put the "
                        + "column's job in the entity and would make a round trip through the setter "
                        + "return something other than what was set")
                .isEqualTo(shorterThanTheKey)
                .hasSize(CATALOGUED_KEY_LENGTH - 1);

        assertThatExceptionOfType(IllegalArgumentException.class)
                .as("a value wider than the key is outside PIC X(08) altogether, so no source record "
                        + "produced it and there is nothing to preserve. Truncating it would silently "
                        + "address a different row, which is why the guard refuses rather than repairs")
                .isThrownBy(() -> new UserSecurity(
                        widerThanTheKey, "FIRST", "LAST", syntheticHash(1), UserType.USER))
                .withMessageContaining("must be at most " + CATALOGUED_KEY_LENGTH + " characters");
    }

    @ParameterizedTest(name = "a name of [{0}] is carried verbatim")
    @ValueSource(strings = {"", " ", "                    ", "LNM1", "lnm1",
        "LNAMEAAAA5          ", "A NAME WITH SPACES", "O'BRIEN", "\u0000"})
    @DisplayName("carries any name verbatim, because a name of only spaces is legitimate legacy data")
    void carriesAnyNameVerbatim(final String name) {
        final UserSecurity user = new UserSecurity("STDUSR01", name, name, syntheticHash(1), UserType.USER);

        assertThat(user.getSecUsrFname())
                .as("a loading rule that rejected blankness would reject rows the source accepts, and a "
                        + "getter that trimmed would hide the very padding a fixed width defect shows up "
                        + "as. User: %s", user.getSecUsrId())
                .isEqualTo(name);
        assertThat(user.getSecUsrLname()).isEqualTo(name);
    }

    @Test
    @DisplayName("refuses a null in every one of the five members, each naming its own property")
    void refusesANullInEveryMember() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .as("COBOL has no null: PIC X(08) always holds eight characters, so a null identifier is "
                        + "not a value the source could have produced. All five columns are NOT NULL, and "
                        + "refusing here names the property at the call site instead of surfacing a "
                        + "column name from inside the driver at flush time")
                .isThrownBy(() -> new UserSecurity(
                        null, "FIRST", "LAST", syntheticHash(1), UserType.USER))
                .withMessageContaining("secUsrId")
                .withMessageContaining("SEC-USR-ID PIC X(08)");

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new UserSecurity(
                        "STDUSR01", null, "LAST", syntheticHash(1), UserType.USER))
                .withMessageContaining("secUsrFname")
                .withMessageContaining("SEC-USR-FNAME PIC X(20)");

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new UserSecurity(
                        "STDUSR01", "FIRST", null, syntheticHash(1), UserType.USER))
                .withMessageContaining("secUsrLname")
                .withMessageContaining("SEC-USR-LNAME PIC X(20)");

        assertThatExceptionOfType(IllegalArgumentException.class)
                .as("a null credential is not a staging state, it is an unauthenticatable row")
                .isThrownBy(() -> new UserSecurity("STDUSR01", "FIRST", "LAST", null, UserType.USER))
                .withMessageContaining("sec_usr_pwd")
                .withMessageContaining("was null");

        assertThatExceptionOfType(IllegalArgumentException.class)
                .as("and the enumeration closes the domain to two codes, so null is the only value "
                        + "outside it that can reach the constructor at all")
                .isThrownBy(() -> new UserSecurity("STDUSR01", "FIRST", "LAST", syntheticHash(1), null))
                .withMessageContaining("secUsrType")
                .withMessageContaining("SEC-USR-TYPE PIC X(01)");
    }

    @Test
    @DisplayName("staged construction remains available, through the provider path rather than by "
            + "weakening the all columns constructor")
    void stagedConstructionRemainsAvailableThroughTheProviderPath() {
        final UserSecurity staged = providerMaterialisedUser();

        assertThat(staged.getSecUsrId())
                .as("the no argument constructor the persistence provider requires is the staging path, "
                        + "and it leaves every member null because populating them is the provider's job. "
                        + "That is why refusing a null in the all columns constructor costs nothing: a "
                        + "constructor that takes all five columns is by definition not a staged one")
                .isNull();
        assertThat(staged.getSecUsrFname()).isNull();
        assertThat(staged.getSecUsrLname()).isNull();
        assertThat(staged.getPasswordHash()).isNull();
        assertThat(staged.getSecUsrType()).isNull();
    }

    @ParameterizedTest(name = "a credential that is {1} is rejected")
    @MethodSource("malformedCredentialValues")
    @DisplayName("rejects every malformed credential at construction rather than carrying it verbatim")
    void rejectsEveryMalformedCredentialAtConstruction(final String credential, final String why) {
        final Throwable thrown = catchThrowable(
                () -> new UserSecurity("STDUSR01", "FIRST", "LAST", credential, UserType.USER));

        assertThat(thrown)
                .as("the entity must refuse to hold a value the verifier could never accept. Rejected "
                        + "because %s", why)
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(thrown.getMessage())
                .as("the message must name the column and the requirement that was not met, so that a "
                        + "caller can fix the defect from the message alone. Case: %s", why)
                .contains("sec_usr_pwd")
                .contains("BCrypt strength 10 digest");
        assertThat(credential.isEmpty() || !thrown.getMessage().contains(credential))
                .as("and it must never reproduce the offending value - an exception message reaches the "
                        + "structured logger, the trace and any rendered error response, so "
                        + "interpolating credential shaped material into one is the very leak Rule 1 "
                        + "clause D forbids. This is also why the value is rejected here rather than "
                        + "downstream: BCryptPasswordEncoder.upgradeEncoding interpolates the offending "
                        + "value into its own message. The empty candidate is excluded from the "
                        + "containment question because every string contains the empty string, which "
                        + "makes the question vacuous rather than interesting. Case: %s", why)
                .isTrue();
    }

    @ParameterizedTest(name = "a credential that is {1} is rejected by the setter too")
    @MethodSource("malformedCredentialValues")
    @DisplayName("rejects every malformed credential at the setter, leaving the held value untouched")
    void rejectsEveryMalformedCredentialAtTheSetter(final String credential, final String why) {
        final UserSecurity user = seededUser(0);
        final String held = user.getPasswordHash();

        assertThatExceptionOfType(IllegalArgumentException.class)
                .as("the setter is the credential rotation path, so it must enforce the same rule as "
                        + "construction. Rejected because %s", why)
                .isThrownBy(() -> user.setPasswordHash(credential))
                .withMessageContaining("sec_usr_pwd");
        assertThat(user.getPasswordHash())
                .as("and a rejected rotation must leave the previous digest in place: a half applied "
                        + "credential change would lock the user out. User: %s", user.getSecUsrId())
                .isEqualTo(held);
    }

    @ParameterizedTest(name = "a digest with the {0} version tag is accepted")
    @ValueSource(strings = {"a", "b", "y"})
    @DisplayName("accepts all three version tags the verifier accepts, and no others")
    void acceptsExactlyTheVersionTagsTheVerifierAccepts(final String versionTag) {
        final String digest = syntheticHash(1).replaceFirst("^\\$2.", "\\$2" + versionTag);

        assertThat(digest).hasSize(BCRYPT_HASH_WIDTH).matches(BCRYPT_SHAPE);
        assertThatCode(() -> new UserSecurity("STDUSR01", "FIRST", "LAST", digest, UserType.USER))
                .as("BCryptPasswordEncoder.BCryptVersion of spring-security-crypto 6.5.8 declares "
                        + "exactly three constants, for the 2a, 2y and 2b tags, and "
                        + "BCrypt.gensalt(String, int, SecureRandom) rejects any other third character "
                        + "with IllegalArgumentException(\"Invalid prefix\"). Accepting exactly that set "
                        + "is what makes the invariant correct rather than merely conventional")
                .doesNotThrowAnyException();
        assertThat(new UserSecurity("STDUSR01", "FIRST", "LAST", digest, UserType.USER)
                .getPasswordHash())
                .as("and the accepted digest is stored byte for byte, never padded to the column width, "
                        + "because a padded digest fails verification - which is exactly why the column "
                        + "is VARCHAR and not CHAR")
                .isEqualTo(digest)
                .hasSize(BCRYPT_HASH_WIDTH);
    }

    @Test
    @DisplayName("rejects the historical 2x version tag, which the verifier can neither make nor check")
    void rejectsTheHistoricalVersionTagTheVerifierCannotUse() {
        final String tagged2x = syntheticHash(1).replaceFirst("^\\$2.", "\\$2x");

        assertThat(tagged2x)
                .as("it is the right length and the right alphabet, so only the version tag can be the "
                        + "reason it is refused")
                .hasSize(BCRYPT_HASH_WIDTH);
        assertThatExceptionOfType(IllegalArgumentException.class)
                .as("BCryptPasswordEncoder.BCRYPT_PATTERN admits only 2a, 2y and 2b, so a 2x digest "
                        + "could never authenticate anybody. Storing one would store an unusable "
                        + "credential and would look like valid data to every reader of the table")
                .isThrownBy(() -> new UserSecurity("STDUSR01", "FIRST", "LAST", tagged2x, UserType.USER))
                .withMessageContaining("2a, 2b or 2y");
    }

    @Test
    @DisplayName("rejects a 59 and a 61 character credential, on both the constructor and the setter")
    void rejectsCredentialsOnEitherSideOfTheHashWidth() {
        final String wellFormed = syntheticHash(1);
        final String oneShort = wellFormed.substring(0, BCRYPT_HASH_WIDTH - 1);
        final String oneLong = wellFormed + "0";

        assertThat(oneShort).hasSize(BCRYPT_HASH_WIDTH - 1);
        assertThat(oneLong).hasSize(BCRYPT_HASH_WIDTH + 1);
        assertThat(BCRYPT_SHAPE.matcher(oneShort).matches())
                .as("59 characters is one short of a BCrypt hash")
                .isFalse();
        assertThat(BCRYPT_SHAPE.matcher(oneLong).matches())
                .as("61 characters is one too many, and would be rejected by a VARCHAR(60) column")
                .isFalse();

        assertThatExceptionOfType(IllegalArgumentException.class)
                .as("the short case is the one a column width can never catch: VARCHAR(60) accepts 59 "
                        + "characters happily, so leaving this to the schema would store a digest that "
                        + "silently fails every verification attempt")
                .isThrownBy(() -> new UserSecurity("STDUSR01", "FIRST", "LAST", oneShort, UserType.USER))
                .withMessageContaining("exactly " + BCRYPT_HASH_WIDTH + " characters")
                .withMessageContaining(String.valueOf(BCRYPT_HASH_WIDTH - 1));
        assertThatExceptionOfType(IllegalArgumentException.class)
                .as("and the long case is caught here rather than at the column, so it fails before a "
                        + "statement is built rather than as a driver level truncation error")
                .isThrownBy(() -> new UserSecurity("STDUSR01", "FIRST", "LAST", oneLong, UserType.USER))
                .withMessageContaining(String.valueOf(BCRYPT_HASH_WIDTH + 1));
    }

    @ParameterizedTest(name = "the accessor pair for {0} round trips without normalising")
    @ValueSource(strings = {"secUsrId", "secUsrFname", "secUsrLname"})
    @DisplayName("round trips every fixed width member through its setter without altering the value")
    void everyFixedWidthMemberRoundTripsUnaltered(final String member) throws Exception {
        // The value must be awkward - leading and trailing spaces, mixed case - and must also fit the
        // member's own picture clause, because a value the record cannot hold is refused rather than
        // round tripped. PIC X(08) admits eight characters and PIC X(20) admits twenty.
        final String awkward = "secUsrId".equals(member) ? " MiXeD  " : "  MiXeD  ";
        final UserSecurity user = seededUser(0);
        final String setterName = "set" + Character.toUpperCase(member.charAt(0)) + member.substring(1);
        final String getterName = "get" + Character.toUpperCase(member.charAt(0)) + member.substring(1);

        UserSecurity.class.getDeclaredMethod(setterName, String.class).invoke(user, awkward);
        final Object readBack = UserSecurity.class.getDeclaredMethod(getterName).invoke(user);

        assertThat(readBack)
                .as("no accessor trims, folds case or normalises: trimming is a presentation concern "
                        + "belonging to the DTO layer. Member: %s", member)
                .isEqualTo(awkward);
    }

    /**
     * The credential is excluded from the source above and asserted here instead.
     *
     * <p>An awkwardly padded, mixed case value is exactly what the credential guard now refuses, so
     * feeding it through the shared round trip would have asserted that the guard does not exist. The
     * property under test is unchanged - a value that passes the guard is stored byte for byte, with no
     * trimming and no case folding - so it is asserted with a value the guard admits.
     */
    @Test
    @DisplayName("round trips the credential through its setter without trimming or folding its case")
    void theCredentialRoundTripsUnalteredWhenItIsWellFormed() {
        final UserSecurity user = seededUser(0);
        final String replacement = syntheticHash(7);

        user.setPasswordHash(replacement);

        assertThat(user.getPasswordHash())
                .as("normalising a hash would invalidate it: the radix 64 alphabet is case significant "
                        + "and the trailing characters of a digest are as meaningful as the leading ones, "
                        + "so trimming or folding would silently produce a credential that verifies "
                        + "against nothing")
                .isEqualTo(replacement)
                .hasSize(BCRYPT_HASH_WIDTH);
    }

    @Test
    @DisplayName("round trips a well formed credential through its setter without altering the value")
    void theCredentialRoundTripsUnalteredWhenItSatisfiesTheInvariant() {
        final UserSecurity user = seededUser(0);
        final String rotated = syntheticHash(42);

        assertThat(rotated)
                .as("the rotation target must itself satisfy the invariant, or the assertion would be "
                        + "about the guard rather than about normalisation")
                .isNotEqualTo(user.getPasswordHash())
                .matches(BCRYPT_SHAPE);

        user.setPasswordHash(rotated);

        assertThat(user.getPasswordHash())
                .as("the guard rejects or admits; it never repairs. An admitted digest is stored byte "
                        + "for byte, untrimmed, unpadded and unfolded, because every one of those "
                        + "operations produces a value the verifier would refuse. User: %s",
                        user.getSecUsrId())
                .isEqualTo(rotated);
    }

    @Test
    @DisplayName("carries the user type as the typed enumeration, closing the domain at the type system")
    void carriesTheUserTypeAsTheEnumeration() {
        final UserSecurity admin = seededUser(0);
        final UserSecurity standard = seededUser(5);

        assertThat(admin.getSecUsrType())
                .as("because the property is the enumeration rather than a character, the closed domain "
                        + "of two codes is enforced by the type system on this path and the only value "
                        + "outside it that can reach a setter is null. User: %s", admin.getSecUsrId())
                .isEqualTo(UserType.ADMIN);
        assertThat(standard.getSecUsrType()).isEqualTo(UserType.USER);

        assertThatExceptionOfType(IllegalArgumentException.class)
                .as("and that one remaining value outside the domain is refused rather than carried to "
                        + "flush, so the failure names the property at the call site that set it")
                .isThrownBy(() -> standard.setSecUsrType(null))
                .withMessageContaining("secUsrType");
        assertThat(standard.getSecUsrType())
                .as("a refused assignment must leave the previous value in place rather than clearing it")
                .isEqualTo(UserType.USER);
    }

    @Test
    @DisplayName("the converter's closed domain is the enforceable Java side of the check constraint")
    void converterDomainIsTheJavaSideEquivalentOfTheCheckConstraint() {
        final UserTypeConverter converter = new UserTypeConverter();
        final List<String> accepted = new ArrayList<>();

        for (char candidate = '\u0000'; candidate < '\u0080'; candidate++) {
            final String code = String.valueOf(candidate);
            if (code.trim().isEmpty()) {
                // Every character at or below the space is stripped by trim(), so the converter maps it
                // to null by design. That branch belongs to readsPaddingOnlyValuesBackAsNull, not here:
                // note that Character.isWhitespace would NOT be equivalent, because it reports false for
                // the low value control bytes a legacy fixed width record legitimately pads with.
                continue;
            }
            try {
                accepted.add(String.valueOf(converter.convertToEntityAttribute(code).getCode()));
            } catch (final IllegalArgumentException rejected) {
                assertThat(rejected)
                        .as("every rejection must carry context rather than a bare throw")
                        .hasMessageContaining("sec_usr_type");
            }
        }

        assertThat(accepted)
                .as("ck_user_security_type restricts sec_usr_type to 'A' or 'U' in the schema. This is "
                        + "a pure JVM test that loads no migration, so what it asserts is the converter's "
                        + "own domain: it admits exactly the same two codes and therefore enforces the "
                        + "same rule on the read path even where the constraint is absent or was written "
                        + "around")
                .containsExactlyInAnyOrder(ADMIN_CODE, USER_CODE);
    }

    @Test
    @DisplayName("declares no pagination member, because page size is not an entity concern")
    void declaresNoPaginationMember() {
        final List<String> names = new ArrayList<>();
        for (final Field field : UserSecurity.class.getDeclaredFields()) {
            names.add(field.getName().toLowerCase(Locale.ROOT));
        }
        for (final Method method : UserSecurity.class.getDeclaredMethods()) {
            names.add(method.getName().toLowerCase(Locale.ROOT));
        }

        assertThat(USER_LIST_PAGE_SIZE)
                .as("app/cbl/COUSR00C.cbl:L57 declares 02 USER-REC OCCURS 10 TIMES, so the user list "
                        + "screen shows ten rows at a time. That figure is context for UserSecurityDto "
                        + "and for the list service; it has no place on the row itself, and asserting it "
                        + "here would bind a presentation decision to a persistence type")
                .isEqualTo(10);
        assertThat(names)
                .as("no member names a paging, windowing or cursor concern")
                .isNotEmpty();
        assertThat(names).allSatisfy(name -> assertThat(name)
                .doesNotContain("page")
                .doesNotContain("pagesize")
                .doesNotContain("offset")
                .doesNotContain("limit")
                .doesNotContain("cursor")
                .doesNotContain("browse"));
    }

    @Test
    @DisplayName("presumes no ordering beyond the primary key and declares no secondary index")
    void presumesNoOrderingBeyondThePrimaryKey() {
        final Table table = UserSecurity.class.getAnnotation(Table.class);

        assertThat(table.indexes())
                .as("app/catlg/LISTCAT.txt:L3938 and :L3946 tally exactly AIX 3 and PATH 3 across the "
                        + "whole catalogue, and all six belong to CARDDATA, CARDXREF and TRANSACT. "
                        + "AWS.M2.CARDDEMO.USRSEC.VSAM.KSDS has none, so no secondary index is declared "
                        + "here and none is needed: every access path in the four user administration "
                        + "programs is either a keyed read on the 8 character id or a full browse in key "
                        + "order")
                .isEmpty();
        assertThat(table.uniqueConstraints())
                .as("and the primary key is the only uniqueness the source asserts")
                .isEmpty();
        assertThat(CATALOGUED_ALTERNATE_INDEX_COUNT)
                .as("the catalogue wide alternate index tally, recorded so the absence above is a "
                        + "positive finding rather than an omission")
                .isEqualTo(3);
        assertThat(annotationSimpleNamesOf(UserSecurity.class))
                .as("and no ordering annotation appears at type level either")
                .doesNotContain("OrderBy", "OrderColumn", "SecondaryTable");
    }

    @Test
    @DisplayName("declares the ten accessors, the three Object overrides and the credential guard")
    void declaresOnlyAccessorsAndTheObjectOverrides() throws NoSuchMethodException {
        final List<String> methodNames = Arrays.stream(UserSecurity.class.getDeclaredMethods())
                .filter(method -> !method.isSynthetic())
                .map(Method::getName)
                .toList();

        assertThat(methodNames)
                .as("this single assertion carries several guarantees at once. There is no behavioural "
                        + "method, so no business rule can hide on the row. There is no self delete "
                        + "guard: app/cbl/COUSR03C.cbl never mentions CDEMO-USER-ID and so never "
                        + "compares the target id against the signed on id, and that absence is "
                        + "preserved deliberately rather than repaired - the decision belongs to the "
                        + "service tier and is asserted there, not invented here. There is no "
                        + "authenticate, matches, verify or encode method. And there is no accessor for "
                        + "the named filler. The only four members beyond the accessors and the Object "
                        + "overrides are the three boundary guards - the credential invariant, the fixed "
                        + "width check and the user type check - and the write callback that asserts the "
                        + "first of them. All four are non public, all four are static except the "
                        + "callback, and none carries a business rule: each refuses only a value the "
                        + "80 byte record layout at app/cpy/CSUSR01Y.cpy:L18-L22 cannot represent")
                .containsExactlyInAnyOrder(
                        "getSecUsrId", "setSecUsrId",
                        "getSecUsrFname", "setSecUsrFname",
                        "getSecUsrLname", "setSecUsrLname",
                        "getPasswordHash", "setPasswordHash",
                        "getSecUsrType", "setSecUsrType",
                        "equals", "hashCode", "toString",
                        "requireBcryptStrength10Digest", "requireWidth", "requireUserType",
                        "assertCredentialInvariantBeforeWrite");
        assertThat(methodNames)
                .as("two accessors per modelled member, the three Object overrides, and the four non "
                        + "public boundary guard members")
                .hasSize(MODELLED_MEMBER_COUNT * 2 + 3 + NON_PUBLIC_GUARD_MEMBER_COUNT);

        assertThat(Arrays.stream(UserSecurity.class.getDeclaredMethods())
                .filter(method -> !method.isSynthetic())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Method::getName)
                .toList())
                .as("and the public surface is unchanged by the invariants: all four guard members are "
                        + "private, so nothing outside the entity gained a way to call them")
                .containsExactlyInAnyOrder(
                        "getSecUsrId", "setSecUsrId",
                        "getSecUsrFname", "setSecUsrFname",
                        "getSecUsrLname", "setSecUsrLname",
                        "getPasswordHash", "setPasswordHash",
                        "getSecUsrType", "setSecUsrType",
                        "equals", "hashCode", "toString");
    }

    @Test
    @DisplayName("performs no case folding of its own, unlike the sign on program it feeds")
    void performsNoCaseFoldingOfItsOwn() {
        final String mixedCaseId = "sTdUsR01";
        final UserSecurity user = new UserSecurity(
                mixedCaseId, "fNameaa1", "lNm1", syntheticHash(1), UserType.ADMIN);

        assertThat(user.getSecUsrId())
                .as("app/cbl/COSGN00C.cbl:L132 upper cases the identifier and :L135 upper cases the "
                        + "password before the plaintext comparison at :L223 - it folds BOTH, not just "
                        + "the identifier. That is input handling in the sign on path and it is asserted "
                        + "against AuthenticationService, not here. Folding on the entity would rewrite "
                        + "stored data on every load and would make the primary key ambiguous")
                .isEqualTo(mixedCaseId)
                .isNotEqualTo(mixedCaseId.toUpperCase(Locale.ROOT));
        assertThat(user.getSecUsrFname()).isEqualTo("fNameaa1");
        assertThat(user.getSecUsrLname()).isEqualTo("lNm1");
    }

    @Test
    @DisplayName("maps a cluster that is online visible, which is why the four admin screens exist")
    void mapsAnOnlineVisibleCluster() {
        assertThat(CSD_FILE_NAMES)
                .as("app/csd/CARDDEMO.CSD declares exactly eight DEFINE FILE entries, and USRSEC at "
                        + ":L88 is one of them. TCATBALF, DISCGRP, TRANCATG and TRANTYPE have no CICS "
                        + "definition at all and are batch only - USRSEC is not, which is precisely why "
                        + "COUSR00C, COUSR01C, COUSR02C and COUSR03C exist as online screens")
                .hasSize(8)
                .contains("USRSEC")
                .doesNotContain("TCATBALF", "DISCGRP", "TRANCATG", "TRANTYPE")
                .doesNotHaveDuplicates();
        assertThat(CATALOGUED_RECORD_COUNT)
                .as("app/catlg/LISTCAT.txt:L3888 records REC-TOTAL 10 on the cluster, matching the ten "
                        + "rows IEBGENER loads at app/jcl/DUSRSECJ.jcl:L35-L44 exactly")
                .isEqualTo(SEEDED_USER_IDS.size());
    }

    // ==================================================================
    // Movement 8 - the ten seeded users, and the fixture that does not
    // exist. Identifiers and names are seeded identities and are NOT
    // reproduced: the fixtures below are synthetic stand-ins carrying the
    // structure only. The type bytes and the aggregate five-A/five-U split
    // are structural and are asserted. The shared plaintext never appears
    // here in any form.
    // Source: app/jcl/DUSRSECJ.jcl:L32-L48 (IEBGENER inline SYSUT1),
    //         app/catlg/LISTCAT.txt:L3888 @ 7756d89
    // ==================================================================

    @ParameterizedTest(name = "seeded row {0} is carried faithfully")
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9})
    @DisplayName("carries each of the ten seeded users with its catalogued identity and class")
    void carriesEachOfTheTenSeededUsers(final int row) {
        final UserSecurity user = seededUser(row);
        final char expectedTypeByte = SEEDED_TYPE_BYTES.charAt(row);

        assertThat(user.getSecUsrId())
                .as("row %d of app/jcl/DUSRSECJ.jcl:L35-L44", row + 35)
                .isEqualTo(SEEDED_USER_IDS.get(row))
                .hasSize(USER_ID_PIC_WIDTH);
        assertThat(user.getSecUsrFname())
                .as("the given name occupies bytes 9 to 28 of the 80 byte record")
                .isEqualTo(SEEDED_FIRST_NAMES.get(row))
                .hasSizeLessThanOrEqualTo(FIRST_NAME_PIC_WIDTH);
        assertThat(user.getSecUsrLname())
                .as("the family name occupies bytes 29 to 48")
                .isEqualTo(SEEDED_LAST_NAMES.get(row))
                .hasSizeLessThanOrEqualTo(LAST_NAME_PIC_WIDTH);
        assertThat(user.getSecUsrType().getCode())
                .as("the class byte sits at position %d, immediately after the 8 byte credential field, "
                        + "and is encoded here only as the character 'A' or 'U' from "
                        + "app/cpy/COCOM01Y.cpy:L27-L28", USER_ID_PIC_WIDTH + FIRST_NAME_PIC_WIDTH
                        + LAST_NAME_PIC_WIDTH + SOURCE_CREDENTIAL_PIC_WIDTH + 1)
                .isEqualTo(expectedTypeByte);
        assertThat(user.getPasswordHash())
                .as("and the stored credential is asserted only as a well formed BCrypt hash at the "
                        + "pinned strength - never as the value the source actually holds")
                .matches(BCRYPT_SHAPE)
                .hasSize(BCRYPT_HASH_WIDTH);
    }

    @Test
    @DisplayName("splits five administrators and five standard users, in that catalogued order")
    void theSeedSplitsFiveAdministratorsAndFiveStandardUsers() {
        final List<UserType> types = new ArrayList<>();
        for (int row = 0; row < SEEDED_USER_IDS.size(); row++) {
            types.add(seededUser(row).getSecUsrType());
        }

        assertThat(types)
                .as("five type 'A' rows then five type 'U' rows, exactly as IEBGENER reads them from "
                        + "the inline stream at app/jcl/DUSRSECJ.jcl:L35-L44. The encoding %s is how "
                        + "this file records the class bytes without touching the credential column",
                        SEEDED_TYPE_BYTES)
                .hasSize(SEEDED_TYPE_BYTES.length())
                .filteredOn(UserType.ADMIN::equals)
                .hasSize(SEEDED_RECORDS_PER_TYPE);
        assertThat(types)
                .filteredOn(UserType.USER::equals)
                .hasSize(SEEDED_RECORDS_PER_TYPE);
        assertThat(types.subList(0, SEEDED_RECORDS_PER_TYPE))
                .as("the administrators lead")
                .containsOnly(UserType.ADMIN);
        assertThat(types.subList(SEEDED_RECORDS_PER_TYPE, types.size()))
                .as("and the standard users follow")
                .containsOnly(UserType.USER);
    }

    @Test
    @DisplayName("every seeded identifier is eight characters, distinct, and its own hash key")
    void everySeededIdentifierIsEightCharactersAndDistinct() {
        final Set<String> identifiers = new HashSet<>();
        final Set<Integer> hashes = new HashSet<>();
        for (int row = 0; row < SEEDED_USER_IDS.size(); row++) {
            final UserSecurity user = seededUser(row);
            identifiers.add(user.getSecUsrId());
            hashes.add(user.hashCode());
        }

        assertThat(identifiers)
                .as("ten distinct keys at exactly the catalogued key length of "
                        + "app/catlg/LISTCAT.txt:L3883 and app/jcl/DUSRSECJ.jcl:L65")
                .hasSize(SEEDED_USER_IDS.size())
                .allSatisfy(identifier -> assertThat(identifier).hasSize(CATALOGUED_KEY_LENGTH));
        assertThat(hashes)
                .as("and ten distinct hash codes, so a HashMap keyed on the entity separates every "
                        + "seeded row without collision handling")
                .hasSize(SEEDED_USER_IDS.size());
    }

    @Test
    @DisplayName("no seeded credential is a plaintext of the source PIC width")
    void noSeededCredentialIsAPlaintextOfTheSourceWidth() {
        for (int row = 0; row < SEEDED_USER_IDS.size(); row++) {
            final UserSecurity user = seededUser(row);

            assertThat(user.getPasswordHash().length())
                    .as("app/cpy/CSUSR01Y.cpy:L21 declares SEC-USR-PWD as PIC X(08) plaintext and every "
                            + "one of the ten rows carries the same literal value. That literal is a "
                            + "secret and Rule 1 clause D names tests explicitly, so it appears nowhere "
                            + "under src/. What is asserted instead is that no stored value could be it: "
                            + "%d characters cannot be %d. User: %s", BCRYPT_HASH_WIDTH,
                            SOURCE_CREDENTIAL_PIC_WIDTH, user.getSecUsrId())
                    .isNotEqualTo(SOURCE_CREDENTIAL_PIC_WIDTH);
            assertThat(looksLikeTheSeededPlaintext(user.getPasswordHash()))
                    .as("and it does not even have the shape of an 8 character upper case plaintext. "
                            + "User: %s", user.getSecUsrId())
                    .isFalse();
        }
    }

    @Test
    @DisplayName("no user security fixture exists on the classpath, and none is invented")
    void noUserSecurityFixtureExistsOnTheClasspath() {
        assertThat(UserSecurityTest.class.getResource("/usrsec.txt"))
                .as("the other nine seed sets are ASCII fixtures under app/data/ASCII, but the ten user "
                        + "rows exist only as inline SYSUT1 DD * data inside app/jcl/DUSRSECJ.jcl:L34-L45 "
                        + "fed through IEBGENER at :L32. There is therefore no usrsec.txt and this test "
                        + "uses no fixture loader - the absence is asserted rather than papered over "
                        + "with an invented file. Note the neighbouring trap: the posting fixture is "
                        + "spelled dailytran.txt in full, never dalytran.txt, even though the DD name "
                        + "and dataset are DALYTRAN")
                .isNull();
        assertThat(UserSecurityTest.class.getResource("/usersec.txt"))
                .as("nor under the alternative spelling")
                .isNull();
        assertThat(UserSecurityTest.class.getResource("/USRSEC.PS"))
                .as("and app/data/EBCDIC/AWS.M2.CARDDEMO.USRSEC.PS is explicitly out of scope: no "
                        + "codepage conversion is performed anywhere in this migration. Note the member "
                        + "name is fully qualified with the AWS.M2.CARDDEMO high level qualifiers - "
                        + "there is no bare USRSEC.PS in that directory")
                .isNull();
    }

    /**
     * Every internal name that must not appear in the compiled entity or its nested converter.
     *
     * @return one argument per forbidden internal name, in declaration order
     */
    private static Stream<String> forbiddenInternalNames() {
        return FORBIDDEN_INTERNAL_NAMES.stream();
    }

    /**
     * The other ten persistence entities in this package, as simple names.
     *
     * <p>Supplied as names rather than as class literals so that the consuming test resolves each one by
     * reflection and therefore also proves the class is present under the expected fully qualified name.
     *
     * @return one argument per sibling entity, in declaration order
     */
    private static Stream<String> siblingEntityNames() {
        return SIBLING_ENTITY_NAMES.stream();
    }

    /**
     * The printable ASCII range with the two copybook codes removed.
     *
     * @return one argument per printable code outside the copybook's two-value domain
     */
    private static Stream<Arguments> printableCodesOutsideTheCopybookDomain() {
        final List<Arguments> codes = new ArrayList<>();
        for (char candidate = '!'; candidate <= '~'; candidate++) {
            final String code = String.valueOf(candidate);
            if (!ADMIN_CODE.equals(code) && !USER_CODE.equals(code)) {
                codes.add(Arguments.of(code));
            }
        }
        return codes.stream();
    }

    /**
     * Values that must never satisfy {@link #BCRYPT_SHAPE}, each paired with the reason it fails.
     *
     * @return {@code (candidate, why)} pairs covering width, version, cost and alphabet defects
     */
    private static Stream<Arguments> malformedCredentialValues() {
        final String wellFormed = syntheticHash(1);
        return Stream.of(
                Arguments.of("", "an empty value carries no hash at all"),
                Arguments.of(" ".repeat(BCRYPT_HASH_WIDTH),
                        "a column-width run of spaces is padding, not a hash"),
                Arguments.of(wellFormed.substring(0, BCRYPT_HASH_WIDTH - 1),
                        "59 characters is one short of a BCrypt hash"),
                Arguments.of(wellFormed + "0",
                        "61 characters is one too many, and a VARCHAR(60) column would reject it"),
                Arguments.of(wellFormed.replace("$2a$10$", "$2a$04$"),
                        "cost 04 is below the pinned strength of 10"),
                Arguments.of(wellFormed.replace("$2a$10$", "$2a$12$"),
                        "cost 12 is above the pinned strength of 10"),
                Arguments.of(wellFormed.replace("$2a$", "$1a$"),
                        "version tag 1a is not a BCrypt version"),
                Arguments.of(wellFormed.substring(BCRYPT_PREFIX.length()),
                        "the version and cost prefix is missing entirely"),
                Arguments.of(BCRYPT_PREFIX + "!".repeat(BCRYPT_HASH_WIDTH - BCRYPT_PREFIX.length()),
                        "the salt and digest use characters outside BCrypt's radix-64 alphabet"),
                Arguments.of(BCRYPT_PREFIX + "X",
                        "eight characters is the source PIC width, and a CHAR(8) column would truncate "
                                + "every real hash to exactly this kind of garbage"));
    }

    // ==================================================================
    // Helpers. Reflection and class-file inspection, kept in one place
    // so that every structural assertion above reads as a statement
    // about the entity rather than as plumbing.
    // ==================================================================

    /**
     * The entity's persistent members: every declared field that is neither static nor synthetic.
     *
     * @return the persistent fields of {@link UserSecurity} in declaration order
     */
    private static List<Field> persistentFields() {
        return Arrays.stream(UserSecurity.class.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .filter(field -> !field.isSynthetic())
                .toList();
    }

    /**
     * The {@code @Column} mapping of one named persistent member.
     *
     * @param member the Java property name, for example {@code secUsrId}
     * @return the column mapping declared on that member
     */
    private static Column columnOf(final String member) {
        final List<Field> matches = persistentFields().stream()
                .filter(field -> field.getName().equals(member))
                .toList();

        assertThat(matches)
                .as("the entity must declare exactly one persistent member named %s; the five expected "
                        + "members are %s, derived from app/cpy/CSUSR01Y.cpy:L18-L22",
                        member, EXPECTED_MEMBER_NAMES)
                .hasSize(1);

        final Column column = matches.get(0).getAnnotation(Column.class);
        assertThat(column)
                .as("member %s must carry an explicit @Column: relying on the implicit naming strategy "
                        + "would let a rename of the Java property silently rename the database column, "
                        + "breaking the copybook contract without any code change", member)
                .isNotNull();
        return column;
    }

    /**
     * The simple names of the annotations declared directly on one program element.
     *
     * @param element a class, field, method or constructor
     * @return the declared annotation simple names, in reflection order
     */
    private static List<String> annotationSimpleNamesOf(final AnnotatedElement element) {
        return Arrays.stream(element.getDeclaredAnnotations())
                .map(annotation -> annotation.annotationType().getSimpleName())
                .toList();
    }

    /**
     * Every annotation declared anywhere on a type: on the type itself, and on each of its declared fields,
     * methods and constructors.
     *
     * @param type the type to sweep
     * @return every declared annotation found on that type's own surface
     */
    private static List<Annotation> allDeclaredAnnotations(final Class<?> type) {
        final List<Annotation> annotations = new ArrayList<>(Arrays.asList(type.getDeclaredAnnotations()));
        for (final Field field : type.getDeclaredFields()) {
            annotations.addAll(Arrays.asList(field.getDeclaredAnnotations()));
        }
        for (final Method method : type.getDeclaredMethods()) {
            annotations.addAll(Arrays.asList(method.getDeclaredAnnotations()));
        }
        for (final Constructor<?> constructor : type.getDeclaredConstructors()) {
            annotations.addAll(Arrays.asList(constructor.getDeclaredAnnotations()));
        }
        return annotations;
    }

    /**
     * One compiled class file, read from the test classpath as text.
     *
     * @param simpleName the class file's simple name, nested classes written as {@code Outer$Nested}
     * @return the class file's bytes, one character per byte
     * @throws IOException if the class file cannot be read
     */
    private static String classFileTextOf(final String simpleName) throws IOException {
        try (InputStream compiled = UserSecurity.class.getResourceAsStream(simpleName + ".class")) {
            assertThat(compiled)
                    .as("no compiled class file %s.class sits beside "
                            + "com.cardemo.model.entity.UserSecurity on the test classpath; run "
                            + "./mvnw -B test-compile first", simpleName)
                    .isNotNull();
            return new String(compiled.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }

    /**
     * The string literals embedded in one compiled class file.
     *
     * @param simpleName the class file's simple name, nested classes written as {@code Outer$Nested}
     * @return every string literal in that class file, in constant pool order
     * @throws IOException if the class file cannot be read
     */
    private static List<String> stringConstantsOf(final String simpleName) throws IOException {
        final byte[] bytes;
        try (InputStream compiled = UserSecurity.class.getResourceAsStream(simpleName + ".class")) {
            assertThat(compiled)
                    .as("no compiled class file %s.class on the test classpath", simpleName)
                    .isNotNull();
            bytes = compiled.readAllBytes();
        }

        final ByteBuffer buffer = ByteBuffer.wrap(bytes);
        buffer.getInt();
        buffer.getShort();
        buffer.getShort();

        final int poolCount = Short.toUnsignedInt(buffer.getShort());
        final Map<Integer, String> utf8Entries = new HashMap<>();
        final List<Integer> literalIndexes = new ArrayList<>();

        int slot = 1;
        while (slot < poolCount) {
            final int tag = Byte.toUnsignedInt(buffer.get());
            switch (tag) {
                case CONSTANT_UTF8 -> {
                    final byte[] text = new byte[Short.toUnsignedInt(buffer.getShort())];
                    buffer.get(text);
                    utf8Entries.put(slot, new String(text, StandardCharsets.UTF_8));
                }
                case CONSTANT_STRING -> literalIndexes.add(Short.toUnsignedInt(buffer.getShort()));
                case CONSTANT_LONG, CONSTANT_DOUBLE -> {
                    buffer.position(buffer.position() + Long.BYTES);
                    slot++;
                }
                case CONSTANT_METHOD_HANDLE -> buffer.position(buffer.position() + 3);
                case CONSTANT_CLASS, CONSTANT_METHOD_TYPE, CONSTANT_MODULE, CONSTANT_PACKAGE ->
                        buffer.position(buffer.position() + Short.BYTES);
                case CONSTANT_INTEGER, CONSTANT_FLOAT, CONSTANT_FIELDREF, CONSTANT_METHODREF,
                        CONSTANT_INTERFACE_METHODREF, CONSTANT_NAME_AND_TYPE, CONSTANT_DYNAMIC,
                        CONSTANT_INVOKE_DYNAMIC -> buffer.position(buffer.position() + Integer.BYTES);
                default -> throw new AssertionError("Unrecognised constant pool tag " + tag + " at slot "
                        + slot + " of " + poolCount + " while scanning " + simpleName + ".class; the scan "
                        + "for embedded credentials cannot be trusted until this tag is handled");
            }
            slot++;
        }

        final List<String> literals = new ArrayList<>();
        for (final Integer index : literalIndexes) {
            final String literal = utf8Entries.get(index);
            if (literal != null) {
                literals.add(literal);
            }
        }
        return literals;
    }

    /**
     * Whether a character may appear inside a JVM internal type name.
     *
     * @param character the character under test
     * @return {@code true} if the character continues an internal name
     */
    private static boolean isInternalNameCharacter(final char character) {
        return Character.isLetterOrDigit(character) || character == '_';
    }

    /**
     * Whether a value has the shape of the shared plaintext credential the ten seeded rows carry.
     *
     * @param candidate the string literal under test, never {@code null}
     * @return {@code true} if the candidate could be an eight-character upper-case plaintext
     */
    private static boolean looksLikeTheSeededPlaintext(final String candidate) {
        if (candidate.length() != SOURCE_CREDENTIAL_PIC_WIDTH) {
            return false;
        }
        return candidate.chars().allMatch(character -> character >= 'A' && character <= 'Z');
    }

    /**
     * A synthetic value with the shape of a BCrypt hash at the pinned strength, and the substance of none.
     *
     * @param ordinal a discriminator in the range 0 to 99, so that distinct rows get distinct values
     * @return a 60-character value matching the BCrypt shape at strength 10
     */
    private static String syntheticHash(final int ordinal) {
        if (ordinal < 0 || ordinal > 99) {
            throw new AssertionError("syntheticHash needs a two digit ordinal to keep the result at "
                    + BCRYPT_HASH_WIDTH + " characters, but was given " + ordinal);
        }
        final String discriminator = ordinal < 10 ? "0" + ordinal : Integer.toString(ordinal);
        final String hash = BCRYPT_PREFIX + SYNTHETIC_SALT + SYNTHETIC_DIGEST_STEM + discriminator;
        if (hash.length() != BCRYPT_HASH_WIDTH) {
            throw new AssertionError("the synthetic hash must be exactly " + BCRYPT_HASH_WIDTH
                    + " characters to fit the credential column, but was " + hash.length());
        }
        return hash;
    }

    /**
     * One of the ten seeded users, carrying its catalogued identity and a synthetic credential.
     *
     * <p>Rows 0 to 4 are the administrators and rows 5 to 9 the standard users, in the order IEBGENER reads
     * them from {@code app/jcl/DUSRSECJ.jcl:L35-L44}. The user class resolves through
     * {@link UserType#requireFromCode(char)} so the mapping under test is exercised rather than restated.
     *
     * @param row the zero-based seed row, 0 to 9
     * @return a transient entity mirroring that seeded row
     */
    private static UserSecurity seededUser(final int row) {
        if (row < 0 || row >= SEEDED_USER_IDS.size()) {
            throw new AssertionError("app/jcl/DUSRSECJ.jcl:L35-L44 seeds exactly "
                    + SEEDED_USER_IDS.size() + " rows, so row " + row + " does not exist");
        }
        return new UserSecurity(
                SEEDED_USER_IDS.get(row),
                SEEDED_FIRST_NAMES.get(row),
                SEEDED_LAST_NAMES.get(row),
                syntheticHash(row + 1),
                UserType.requireFromCode(SEEDED_TYPE_BYTES.charAt(row)));
    }

    /**
     * An instance in the state the persistence provider materialises: every member still null.
     *
     * <p>Built through the no argument constructor, which is the only route to that state and is exactly
     * how the provider reaches it. The constructor is {@code protected} and this test lives in a
     * different package, so reflection is required - and that requirement is itself part of the contract
     * being relied on, because it is what stops application code creating a user with five null members
     * by accident.
     *
     * <p>This helper exists because the all columns constructor can no longer produce this state. It
     * enforces the credential invariant, so {@code new UserSecurity(null, null, null, null, null)} now
     * throws by design - which is the point of the invariant. The tests that genuinely need an
     * unpopulated instance are the ones asserting that rendering and hashing survive it, and they need
     * the provider's route rather than the application's.
     *
     * @return a transient instance with all five members null
     * @throws AssertionError if the no argument constructor is missing or is not reachable reflectively,
     *                        either of which would break provider materialisation itself
     */
    private static UserSecurity providerMaterialisedUser() {
        try {
            final Constructor<UserSecurity> noArg = UserSecurity.class.getDeclaredConstructor();
            noArg.setAccessible(true);
            return noArg.newInstance();
        } catch (ReflectiveOperationException cause) {
            throw new AssertionError("the JPA specification requires a reachable no argument "
                    + "constructor, and the provider materialises every row through it, so its absence "
                    + "would break persistence outright", cause);
        }
    }
}
