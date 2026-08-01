/*
 * ******************************************************************
 * Program     : UserSecurityTest.java
 * Component   : Unit test tier, resident at
 *               src/test/java/com/cardemo/unit/model
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

import com.cardemo.model.entity.UserSecurity;
import com.cardemo.model.entity.UserSecurity.UserTypeConverter;
import com.cardemo.model.enums.UserType;

import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
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
 * </ol>
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
 *   <li>{@code source /etc/profile.d/10-carddemo-toolchain.sh} - OpenJDK 25.0.3 and Maven 3.9.11, the
 *       exact pair {@code maven-enforcer-plugin} asserts;</li>
 *   <li>{@code mvn -B clean test} - compiles and runs this tier;</li>
 *   <li>{@code mvn -B test -Dtest=UserSecurityTest} - this class alone;</li>
 *   <li>{@code mvn -B clean verify} - adds the 80 percent line coverage floor and the vulnerability
 *       scan.</li>
 * </ul>
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
 * consequence of the reporting artefact recorded as the Low finding below: with the current include
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
 * </ul>
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
 * </ul>
 *
 * <h2>5. Findings, classified by severity</h2>
 *
 * <p><b>Blocker - a plaintext credential literal must never appear under {@code src/}.</b> Rule 1 clause
 * D names tests explicitly: no secrets in code, logs, tests or config. All ten seeded rows at
 * {@code app/jcl/DUSRSECJ.jcl:L35-L44} share one 8 character plaintext value in the
 * {@code SEC-USR-PWD PIC X(08)} field. That value is referred to here by citation only and is
 * transcribed nowhere - not in a constant, not in an assertion message, not in a comment and not in this
 * documentation. Movement 3 asserts the property from the other side instead: every credential this test
 * constructs is a synthetic 60 character BCrypt shaped value, no value of the source's 8 byte width can
 * satisfy that shape, and the compiled entity's own string constant pool contains no candidate.
 * Remediation if ever violated: delete the literal, rotate nothing (the value is public legacy sample
 * data, not a live secret) and re-derive the assertion from the shape.
 *
 * <p><b>Blocker - the credential column is {@code VARCHAR(60)}, never {@code CHAR(8)}.</b> The source
 * field is 8 bytes because it held plaintext; the column is 60 because that is the exact length of a
 * BCrypt hash, and {@code VARCHAR} because a {@code CHAR} column would blank pad one. A {@code CHAR(8)}
 * column truncates every hash to garbage and no test that only checks a name would notice. Remediation:
 * the migration must declare {@code sec_usr_pwd VARCHAR(60) NOT NULL}.
 *
 * <p><b>Blocker - the user class needs the explicit converter.</b> See the fourth failure mode above.
 * Remediation: keep {@link UserTypeConverter}, and keep {@code @Enumerated} out of the entity entirely.
 *
 * <p><b>High - a multi character code was silently truncated to its first character.</b> The converter
 * strips padding with {@code trim()} and then read only the first character of what remained, so a
 * malformed non blank value such as {@code "AA"} or {@code "AU"} resolved to
 * {@link UserType#ADMIN} instead of being rejected. Because the padding is already stripped before the
 * length is measured, the leniency could never help the case it was written for - a driver padding a
 * {@code CHAR(1)} - and admitted only values a {@code CHAR(1)} column cannot hold. Silently accepting a
 * malformed privilege code is exactly the unsafe default Rule 1 clause A forbids and the boundary
 * condition clause B requires validating. Remediation, applied: the resolution was switched from
 * {@code UserType.fromCode(char)} to {@link UserType#fromCode(String)}, which already reports an empty
 * result for any length but one, so the domain check is delegated to the type that owns the domain rather
 * than restated; the rejection message now names the whole offending value with its length and code
 * units, and the converter's documentation was corrected to match. That string overload had no other
 * caller anywhere in the main sources, which is itself evidence that it was declared for precisely this
 * call site and that the wrong overload had simply been reached for. Movement 2 pins the tightened
 * contract, and no import was added to the entity by the fix.
 *
 * <p><b>High - the entity must not become a security principal.</b> It does not implement Spring
 * Security's user details contract, extends no framework type and references nothing from
 * {@code org.springframework}; the adaptation belongs to
 * {@code com.cardemo.security.CardDemoUserDetailsService}, one layer out. Importing the interface would
 * drag a security framework into the model layer and invert the dependency direction. Remediation:
 * adapt in the security package, never here. Movement 5 asserts it against the compiled class file, so
 * the assertion cannot be satisfied by an import that merely looks absent.
 *
 * <p><b>High - no version column belongs on this entity.</b> Exactly four entities carry
 * {@code @Version}: the account, card, customer and transaction entities. Adding a fifth here would
 * introduce a column the 80 byte record has no room for and an optimistic locking failure mode the four
 * user administration screens never had. Movement 1 asserts the absence, and cross checks that the four
 * versioned entities are exactly those four.
 *
 * <p><b>Medium - {@code USRSEC} is catalogued, contrary to the plan.</b> The technical specification
 * states that this cluster is defined in JCL rather than catalogued. It is catalogued:
 * {@code app/catlg/LISTCAT.txt:L3881} names {@code AWS.M2.CARDDEMO.USRSEC.VSAM.KSDS} and {@code :L3883}
 * reports {@code KEYLEN 8} with {@code AVGLRECL 80}. The IDCAMS declaration at
 * {@code app/jcl/DUSRSECJ.jcl:L65-L66} corroborates the same geometry from a second, independent source
 * rather than being the only one. Remediation: cite {@code app/catlg/LISTCAT.txt:L3883} as the primary
 * authority for this cluster's geometry, exactly as the sibling ten clusters are cited. Both citations
 * are asserted in movement 1, so the correction is machine checked and not merely written down.
 *
 * <p><b>Low - a nested only test class reports zero tests.</b> With Surefire's include patterns matching
 * on {@code *Test.java}, the tests of a {@code @Nested} group execute and count towards the aggregate but
 * are attributed to their display name, leaving the outer class's own report file reading
 * {@code Tests run: 0}. Five sibling classes in this package are in that position today, and one that
 * mixes both forms reports only its top level methods. It is a reporting artefact and not a
 * non execution: the aggregate is correct. Remediation, for the build owner and deliberately not
 * attempted from a test source: add {@code **}{@code /*Test$*.java} to the Surefire includes. This file
 * side steps the artefact entirely by declaring no nested class.
 *
 * <p><b>Low - {@code USRSEC} has no alternate index and needs none.</b> The catalogue's totals at
 * {@code app/catlg/LISTCAT.txt:L3938} and {@code :L3946} are {@code AIX 3} and {@code PATH 3}, and all
 * three belong to the card, cross reference and transaction clusters. The user list screen pages in
 * primary key order at ten rows per page, which the primary key index already serves, so a second index
 * would be write amplification for no read benefit.
 *
 * <h2>6. What is Not available</h2>
 *
 * <ul>
 *   <li><b>Not available: the schema text.</b> {@code src/main/resources/db/migration} has no planned
 *       children, so {@code V1__create_schema.sql} could not be read and the check constraint that
 *       restricts {@code sec_usr_type} to {@code 'A'} and {@code 'U'} - one of the migration's five -
 *       cannot be asserted as SQL. No DDL is invented here. What is asserted instead is the enforceable
 *       Java side counterpart: {@link UserTypeConverter} accepts those two codes and rejects every
 *       other, which is the same domain expressed where this test can reach it. To assert the constraint
 *       itself, the migration would have to be supplied and read.</li>
 *   <li><b>Not available: a {@code usrsec.txt} fixture.</b> The user records exist only as the in stream
 *       {@code SYSUT1 DD *} data at {@code app/jcl/DUSRSECJ.jcl:L34-L45}. Nothing needs to be supplied,
 *       because the in stream data is the authority, but no fixture derived assertion is possible and
 *       none is faked. Movement 8 asserts the absence explicitly, so a future fixture cannot appear
 *       unnoticed.</li>
 *   <li><b>Not available: the ten stored hashes.</b> {@code V3__seed_data.sql} will hold precomputed
 *       BCrypt values and does not exist yet, so no real hash can be read or asserted. Movement 8 uses
 *       synthetic shaped values, which is sufficient because the property under test is the shape and
 *       the column width, never a particular digest.</li>
 * </ul>
 *
 * @see UserSecurity
 * @see UserTypeConverter
 * @see UserType
 */
final class UserSecurityTest {

    // ==================================================================
    // Field contract transcribed from app/cpy/CSUSR01Y.cpy:L17-L23.
    // Every width is a literal taken from a PIC clause, so an assertion
    // is against the copybook and never against the production constant
    // it is checking.
    // ==================================================================

    /** {@code SEC-USR-ID PIC X(08)} at {@code app/cpy/CSUSR01Y.cpy:L18}: bytes 1 to 8, and the key. */
    private static final int USER_ID_PIC_WIDTH = 8;

    /** {@code SEC-USR-FNAME PIC X(20)} at {@code app/cpy/CSUSR01Y.cpy:L19}: bytes 9 to 28. */
    private static final int FIRST_NAME_PIC_WIDTH = 20;

    /** {@code SEC-USR-LNAME PIC X(20)} at {@code app/cpy/CSUSR01Y.cpy:L20}: bytes 29 to 48. */
    private static final int LAST_NAME_PIC_WIDTH = 20;

    /**
     * {@code SEC-USR-PWD PIC X(08)} at {@code app/cpy/CSUSR01Y.cpy:L21}: bytes 49 to 56.
     *
     * <p>This width belongs to the <em>source record</em> and never to the column. It is kept because it
     * is what makes the 80 byte arithmetic close, and because asserting that the column width differs
     * from it is how the {@code CHAR(8)} Blocker is caught.
     *
     * <p>Named for the <em>credential</em> rather than for the source field's own noun, deliberately.
     * The single shared plaintext value at {@code app/jcl/DUSRSECJ.jcl:L35-L44} happens to be the
     * ordinary English word for the thing it protects, so a constant named after that noun in upper case
     * would contain the secret as a substring and would be reported by any mechanical secret scan -
     * a false positive, but one indistinguishable from a real leak at the point of triage. Renaming
     * costs nothing and keeps a grep for the literal at exactly zero hits across this file, which is
     * what makes Rule 1 clause D provable rather than merely asserted. Do not rename it back.
     */
    private static final int SOURCE_CREDENTIAL_PIC_WIDTH = 8;

    /** {@code SEC-USR-TYPE PIC X(01)} at {@code app/cpy/CSUSR01Y.cpy:L22}: byte 57. */
    private static final int USER_TYPE_PIC_WIDTH = 1;

    /**
     * {@code SEC-USR-FILLER PIC X(23)} at {@code app/cpy/CSUSR01Y.cpy:L23}: bytes 58 to 80.
     *
     * <p>Unusually for this corpus the filler is <em>named</em>; every other record layout pads with an
     * anonymous {@code FILLER}. A name does not make it data, and it is still not modelled: it carries
     * nothing in any of the ten seeded rows and no program in the corpus references it. Its only
     * function is to pad the record to the catalogued 80 bytes, which is why the width lives here in a
     * test constant and nowhere in the schema.
     */
    private static final int FILLER_PIC_WIDTH = 23;

    /**
     * The catalogued record length: {@code AVGLRECL 80} at {@code app/catlg/LISTCAT.txt:L3883},
     * {@code MAXLRECL 80} at {@code :L3884}, and {@code RECORDSIZE(80,80)} at
     * {@code app/jcl/DUSRSECJ.jcl:L66}.
     */
    private static final int CATALOGUED_RECORD_LENGTH = 80;

    /**
     * The catalogued key length: {@code KEYLEN 8} at {@code app/catlg/LISTCAT.txt:L3883} and
     * {@code KEYS(8,0)} at {@code app/jcl/DUSRSECJ.jcl:L65}. The second element of that {@code KEYS}
     * pair is the relative key position, which {@code app/catlg/LISTCAT.txt:L3884} reports as
     * {@code RKP 0} - so the key is the leading field and key order coincides with physical order.
     */
    private static final int CATALOGUED_KEY_LENGTH = 8;

    /** {@code REC-TOTAL 10} at {@code app/catlg/LISTCAT.txt:L3888}, matching the ten in stream rows. */
    private static final int CATALOGUED_RECORD_COUNT = 10;

    /** The number of modelled members: the five elementary items, the named filler excluded. */
    private static final int MODELLED_MEMBER_COUNT = 5;

    /** Width of the credential column - the exact length of a BCrypt hash, and never the PIC width. */
    private static final int BCRYPT_HASH_WIDTH = 60;

    /**
     * The code that {@code 88 CDEMO-USRTYP-ADMIN VALUE 'A'.} binds at {@code app/cpy/COCOM01Y.cpy:L27},
     * transcribed as a one character string because that is the form the converter reads and writes.
     */
    private static final String ADMIN_CODE = "A";

    /** The code that {@code 88 CDEMO-USRTYP-USER VALUE 'U'.} binds at {@code app/cpy/COCOM01Y.cpy:L28}. */
    private static final String USER_CODE = "U";

    /**
     * The ten {@code SEC-USR-TYPE} bytes of the seeded records, in record order, read from position 57 of
     * {@code app/jcl/DUSRSECJ.jcl:L35-L44}: five administrators on {@code :L35-L39} then five standard
     * users on {@code :L40-L44}.
     *
     * <p>A {@link String} rather than a {@code char[]}, because a {@code static final} array is mutable
     * content behind an immutable reference - the global mutable state Rule 1 clause B forbids - whereas
     * a string constant cannot be altered by one test and observed by another.
     *
     * <p><b>Only the type bytes are encoded.</b> Position 57 is derived rather than sliced from a record:
     * {@code SEC-USR-ID PIC X(08)} plus {@code SEC-USR-FNAME PIC X(20)} plus
     * {@code SEC-USR-LNAME PIC X(20)} plus {@code SEC-USR-PWD PIC X(08)} occupy the 56 preceding bytes.
     * Reproducing a whole record here would place a credential in a test file.
     */
    private static final String SEEDED_TYPE_BYTES = "AAAAAUUUUU";

    /** Seeded records per user class - five on {@code :L35-L39} and five on {@code :L40-L44}. */
    private static final int SEEDED_RECORDS_PER_TYPE = 5;

    /**
     * The eight CICS file names that {@code app/csd/CARDDEMO.CSD} defines, in the order the member
     * declares them: {@code ACCTDAT} at {@code :L1}, {@code CARDAIX} at {@code :L13}, {@code CARDDAT} at
     * {@code :L25}, {@code CCXREF} at {@code :L37}, {@code CUSTDAT} at {@code :L50}, {@code CXACAIX} at
     * {@code :L63}, {@code TRANSACT} at {@code :L76} and {@code USRSEC} at {@code :L88}.
     *
     * <p>The membership of {@code USRSEC} in this set is the evidence that this cluster is on the online
     * request path rather than batch only: {@code TCATBALF}, {@code DISCGRP}, {@code TRANCATG} and
     * {@code TRANTYPE} appear nowhere in the CSD. That is why four user administration screens exist and
     * why the sign on program reads this table on every authentication.
     */
    private static final List<String> CSD_FILE_NAMES = List.of(
            "ACCTDAT", "CARDAIX", "CARDDAT", "CCXREF", "CUSTDAT", "CXACAIX", "TRANSACT", "USRSEC");

    /**
     * The catalogue's alternate index and path totals at {@code app/catlg/LISTCAT.txt:L3938} and
     * {@code :L3946}: {@code AIX 3} and {@code PATH 3}, all three belonging to the card, cross reference
     * and transaction clusters, so none belongs to {@code USRSEC}.
     */
    private static final int CATALOGUED_ALTERNATE_INDEX_COUNT = 3;

    /**
     * The page size of the user list screen: {@code 02 USER-REC OCCURS 10 TIMES.} at
     * {@code app/cbl/COUSR00C.cbl:L57}. Context for {@code UserSecurityDto} and for the list service;
     * asserted here only to prove that the entity itself carries no such member.
     */
    private static final int USER_LIST_PAGE_SIZE = 10;

    // ==================================================================
    // Synthetic credential material. Nothing here is, or resembles, a
    // real credential: the values are shaped so that the assertions can
    // be about the shape, and are visibly synthetic so that no reader
    // can mistake one for a secret.
    // ==================================================================

    /**
     * The BCrypt version and cost prefix, seven characters: a dollar, the version tag {@code 2a}, a
     * dollar, the two digit cost field {@code 10} and a dollar.
     *
     * <p>The cost field is the pinned BCrypt strength. It is a literal rather than a formatted number so
     * that a change of strength has to be made deliberately here and in the shape pattern together.
     */
    private static final String BCRYPT_PREFIX = "$2a$10$";

    /**
     * A synthetic 22 character salt, every character drawn from BCrypt's own {@code ./A-Za-z0-9}
     * alphabet, and named so that it reads as synthetic wherever it surfaces.
     */
    private static final String SYNTHETIC_SALT = "SyntheticSaltForTests.";

    /**
     * The first 29 characters of a synthetic 31 character digest. Two more characters are appended per
     * user so that ten distinct, equally well formed values can be produced without a random source.
     */
    private static final String SYNTHETIC_DIGEST_STEM = "SyntheticDigestNotARealDigest";

    /**
     * The shape of a BCrypt hash at the pinned strength: the version and cost prefix, then 53 characters
     * of salt and digest drawn from BCrypt's alphabet, for exactly 60 characters in total.
     *
     * <p>The cost field is pinned to {@code 10} inside the pattern, so a hash produced at any other work
     * factor fails it. This is the only thing about a stored credential that this tier can and should
     * assert: the shape and the width. Verifying a hash needs an encoder, and computing one needs a work
     * factor - both belong to the security configuration.
     */
    private static final Pattern BCRYPT_SHAPE = Pattern.compile("^\\$2[abxy]\\$10\\$[./A-Za-z0-9]{53}$");

    // ==================================================================
    // Reflection and class file scanning support.
    // ==================================================================

    /** The simple name of the entity's table, from {@code @Table} - asserted, not assumed. */
    private static final String EXPECTED_TABLE_NAME = "user_security";

    /**
     * The five column names, in the declaration order of {@code app/cpy/CSUSR01Y.cpy:L18-L22}.
     *
     * <p>{@code sec_usr_pwd} keeps the COBOL provenance of the field it replaces even though the Java
     * member is named for what the value actually is; the mapping has to stay traceable in the schema,
     * which is where a migration author looks.
     */
    private static final List<String> EXPECTED_COLUMN_NAMES = List.of(
            "sec_usr_id", "sec_usr_fname", "sec_usr_lname", "sec_usr_pwd", "sec_usr_type");

    /**
     * The five member names, in the same declaration order.
     *
     * <p>Four keep their COBOL derived names so the mapping back to the copybook is mechanical. The
     * credential member breaks the pattern on purpose: it is {@code passwordHash} and not
     * {@code secUsrPwd}, because the field it replaces held plaintext and this member never can, and a
     * name that suggested otherwise would invite a caller to compare it against a presented password.
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
            "com/fasterxml/jackson",
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
     */
    private static final Set<String> FORBIDDEN_ANNOTATION_SIMPLE_NAMES = Set.of(
            "OneToMany", "ManyToOne", "OneToOne", "ManyToMany", "JoinColumn", "JoinColumns", "JoinTable",
            "ElementCollection", "CollectionTable", "MapsId", "SecondaryTable", "Embedded", "EmbeddedId",
            "MappedSuperclass", "Inheritance", "DiscriminatorColumn", "DiscriminatorValue",
            "PrePersist", "PostPersist", "PreUpdate", "PostUpdate", "PreRemove", "PostRemove", "PostLoad",
            "EntityListeners", "NamedQuery", "NamedQueries", "SqlResultSetMapping",
            "NotNull", "NotBlank", "NotEmpty", "Size", "Pattern", "Email", "Valid",
            "JsonProperty", "JsonIgnore", "JsonInclude", "Transient");

    /**
     * Lower case fragments that must not occur in any member name of the entity, covering the personal
     * data the customer and user layouts carry and the authorisation state a security principal would
     * add.
     *
     * <p>{@code hash} is deliberately absent from this list: {@code passwordHash} is the required name of
     * the credential member, and the point of that name is that it says what the value is. Matching is
     * performed after folding with {@link Locale#ROOT} so the check cannot change behaviour on a host
     * whose case rules differ, such as a Turkish locale where {@code I} does not fold to {@code i}.
     */
    private static final List<String> FORBIDDEN_MEMBER_NAME_FRAGMENTS = List.of(
            "plaintext", "cleartext", "secret", "credential", "token", "salt", "cipher",
            "ssn", "socialsecurity", "birth", "dob", "phone", "email", "address",
            "authority", "granted", "enabled", "locked", "expired", "role",
            "filler", "reserved", "version", "page", "logger", "log");

    /**
     * The four entities that carry {@code @Version}, cross checked so that "this entity is not one of the
     * four versioned entities" is asserted against the package rather than against a comment.
     */
    private static final List<String> VERSIONED_ENTITY_NAMES = List.of(
            "Account", "Card", "Customer", "Transaction");

    /**
     * The other ten entities of {@code com.cardemo.model.entity}, one per remaining VSAM cluster.
     *
     * <p>Named rather than discovered by classpath scanning: a scan would silently pass on an empty
     * result, whereas a missing name here fails with the name in the message.
     */
    private static final List<String> SIBLING_ENTITY_NAMES = List.of(
            "Account", "Card", "CardCrossReference", "Customer", "DailyTransaction", "DisclosureGroup",
            "Transaction", "TransactionCategory", "TransactionCategoryBalance", "TransactionType");

    /**
     * The ten seeded user identifiers, in the order IEBGENER reads them from the inline stream at
     * {@code app/jcl/DUSRSECJ.jcl:L35-L44}: five administrators then five standard users.
     *
     * <p>Identifiers, given names and family names are structural record content and are reproduced.
     * The credential column of those same ten rows is <em>not</em>: see {@link #syntheticHash(int)}.
     */
    private static final List<String> SEEDED_USER_IDS = List.of(
            "ADMIN001", "ADMIN002", "ADMIN003", "ADMIN004", "ADMIN005",
            "USER0001", "USER0002", "USER0003", "USER0004", "USER0005");

    /** Given names of the ten seeded rows, bytes 9 to 28 of each 80 byte record. */
    private static final List<String> SEEDED_FIRST_NAMES = List.of(
            "MARGARET", "RUSSELL", "RAYMOND", "EMMANUEL", "GRANVILLE",
            "LAWRENCE", "AJITH", "LAURITZ", "AVERARDO", "LEE");

    /** Family names of the ten seeded rows, bytes 29 to 48 of each 80 byte record. */
    private static final List<String> SEEDED_LAST_NAMES = List.of(
            "GOLD", "RUSSELL", "WHITMORE", "CASGRAIN", "LACHAPELLE",
            "THOMAS", "KUMAR", "ALME", "MAZZI", "TING");

    // ------------------------------------------------------------------
    // Class file constant pool tags, from the JVM specification table
    // 4.4-B. Named rather than written as bare numbers in the switch of
    // stringConstantsOf, so that the entry widths can be read against
    // the specification instead of trusted.
    // ------------------------------------------------------------------

    /** {@code CONSTANT_Utf8}: two length bytes then that many bytes of modified UTF-8. */
    private static final int CONSTANT_UTF8 = 1;

    /** {@code CONSTANT_Integer}: four bytes. */
    private static final int CONSTANT_INTEGER = 3;

    /** {@code CONSTANT_Float}: four bytes. */
    private static final int CONSTANT_FLOAT = 4;

    /** {@code CONSTANT_Long}: eight bytes, and occupies two pool slots. */
    private static final int CONSTANT_LONG = 5;

    /** {@code CONSTANT_Double}: eight bytes, and occupies two pool slots. */
    private static final int CONSTANT_DOUBLE = 6;

    /** {@code CONSTANT_Class}: a two byte name index. */
    private static final int CONSTANT_CLASS = 7;

    /** {@code CONSTANT_String}: a two byte index of the UTF-8 entry holding the literal. */
    private static final int CONSTANT_STRING = 8;

    /** {@code CONSTANT_Fieldref}: four bytes. */
    private static final int CONSTANT_FIELDREF = 9;

    /** {@code CONSTANT_Methodref}: four bytes. */
    private static final int CONSTANT_METHODREF = 10;

    /** {@code CONSTANT_InterfaceMethodref}: four bytes. */
    private static final int CONSTANT_INTERFACE_METHODREF = 11;

    /** {@code CONSTANT_NameAndType}: four bytes. */
    private static final int CONSTANT_NAME_AND_TYPE = 12;

    /** {@code CONSTANT_MethodHandle}: one reference kind byte then a two byte index. */
    private static final int CONSTANT_METHOD_HANDLE = 15;

    /** {@code CONSTANT_MethodType}: a two byte descriptor index. */
    private static final int CONSTANT_METHOD_TYPE = 16;

    /** {@code CONSTANT_Dynamic}: four bytes. Emitted for a condy-backed constant. */
    private static final int CONSTANT_DYNAMIC = 17;

    /** {@code CONSTANT_InvokeDynamic}: four bytes. Emitted for indified string concatenation. */
    private static final int CONSTANT_INVOKE_DYNAMIC = 18;

    /** {@code CONSTANT_Module}: a two byte name index. */
    private static final int CONSTANT_MODULE = 19;

    /** {@code CONSTANT_Package}: a two byte name index. */
    private static final int CONSTANT_PACKAGE = 20;

    // ==================================================================
    // Movement 1 - record geometry. An 80 byte record on an 8 byte
    // leading key, five modelled members and a named filler that stays
    // out of the schema.
    // Source: app/cpy/CSUSR01Y.cpy:L17-L23,
    //         app/catlg/LISTCAT.txt:L3881-L3888,
    //         app/jcl/DUSRSECJ.jcl:L65-L66 @ 7756d89
    // ==================================================================

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

    // ==================================================================
    // Movement 2 - the user type converter. The database stores one
    // character; the model holds a named constant; neither @Enumerated
    // mode bridges the two, and nothing may be guessed.
    // Source: app/cpy/CSUSR01Y.cpy:L22, app/cpy/COCOM01Y.cpy:L26-L28,
    //         app/cbl/COSGN00C.cbl:L227 @ 7756d89
    // ==================================================================

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
    @DisplayName("rejects every other code by name, and never defaults to a privilege class")
    void rejectsEveryOtherCodeByName(final String stored) {
        final UserTypeConverter converter = new UserTypeConverter();

        assertThatExceptionOfType(IllegalArgumentException.class)
                .as("guessing a privilege class is the one mistake a role store must never make, so an "
                        + "unrecognised code is raised rather than absorbed")
                .isThrownBy(() -> converter.convertToEntityAttribute(stored))
                .withMessageContaining("'" + stored.trim() + "'")
                .withMessageContaining("sec_usr_type")
                .withMessageContaining("COCOM01Y")
                .withNoCause();
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

    // ==================================================================
    // Movement 3 - the credential. A BCrypt hash over a 60 character
    // VARCHAR, never the 8 byte plaintext the source held, and never a
    // literal anywhere.
    // Source: app/cpy/CSUSR01Y.cpy:L21, app/cbl/COSGN00C.cbl:L223,
    //         app/jcl/DUSRSECJ.jcl:L35-L44 @ 7756d89
    // ==================================================================

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
                        + "second place for the work factor to be chosen")
                .doesNotContain("PasswordEncoder")
                .doesNotContain("BCrypt")
                .doesNotContain("MessageDigest")
                .doesNotContain("javax/crypto")
                .doesNotContain("java/security");

        assertThat(UserSecurity.class.getDeclaredMethods())
                .as("and no method may look like a comparison helper that takes a presented password")
                .noneSatisfy(method -> assertThat(method.getName().toLowerCase(Locale.ROOT))
                        .containsAnyOf("matches", "verify", "encode", "check", "authenticate"));
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

    // ==================================================================
    // Movement 4 - value semantics. A rendering narrow enough to be safe
    // in a log line, and identity over the natural key alone.
    // Source: app/cpy/CSUSR01Y.cpy:L18-L22 @ 7756d89
    // ==================================================================

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
        final UserSecurity user = new UserSecurity("USER0003", "LAURITZ", "ALME", hash, UserType.USER);

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
                "ADMIN004", "EMMANUEL", "CASGRAIN", syntheticHash(4), UserType.ADMIN);

        assertThat(user.toString())
                .as("the names are omitted under least privilege: they add nothing the primary key does "
                        + "not already identify. This is why the rendering is narrower than the "
                        + "reference data entities', which render every column they hold. User: %s",
                        user.getSecUsrId())
                .doesNotContain("EMMANUEL")
                .doesNotContain("CASGRAIN")
                .doesNotContain("secUsrFname")
                .doesNotContain("secUsrLname");
    }

    @Test
    @DisplayName("renders a transient instance without throwing, showing nulls rather than substitutes")
    void renderingIsNullSafe() {
        final UserSecurity blank = new UserSecurity(null, null, null, null, null);

        assertThatCode(blank::toString)
                .as("an instance the provider has not yet populated must still be loggable")
                .doesNotThrowAnyException();
        assertThat(blank.toString())
                .as("nulls are shown rather than replaced by a default, which would misreport the state")
                .isEqualTo("UserSecurity{secUsrId=null, secUsrType=null}");
    }

    @Test
    @DisplayName("renders the identifier exactly as held, so fixed width padding stays visible")
    void renderingDoesNotTrimTheIdentifier() {
        final UserSecurity padded = new UserSecurity(
                "USER1   ", "LEE", "TING", syntheticHash(5), UserType.USER);

        assertThat(padded.toString())
                .as("a rendering that trimmed would hide the very padding a fixed width defect shows up "
                        + "as. User: [%s]", padded.getSecUsrId())
                .contains("secUsrId=USER1   ,");
    }

    @Test
    @DisplayName("is equal to itself, unequal to null and unequal to an unrelated type")
    void equalityIsReflexiveAndNullSafe() {
        final UserSecurity user = seededUser(0);

        assertThat(user.equals(user)).as("reflexive").isTrue();
        assertThat(user.equals(null)).as("null safe, never a NullPointerException").isFalse();
        assertThat(user.equals("ADMIN001")).as("unequal to an unrelated type").isFalse();
    }

    @Test
    @DisplayName("compares on secUsrId alone, ignoring the names, the credential and the user class")
    void equalityIsComputedOverTheIdentifierAlone() {
        final UserSecurity first = new UserSecurity(
                "ADMIN001", "MARGARET", "GOLD", syntheticHash(1), UserType.ADMIN);
        final UserSecurity rotated = new UserSecurity(
                "ADMIN001", "MARGARET", "PLATINUM", syntheticHash(9), UserType.USER);

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
                first.getSecUsrId(), "AJITH", "KUMAR", syntheticHash(2), UserType.USER);
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
        final UserSecurity admin = new UserSecurity("ADMIN001", "SAME", "SAME", hash, UserType.ADMIN);
        final UserSecurity other = new UserSecurity("ADMIN002", "SAME", "SAME", hash, UserType.ADMIN);

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

        final UserSecurity unkeyed = new UserSecurity(null, null, null, null, null);
        assertThatCode(unkeyed::hashCode)
                .as("a transient instance whose key is still null must hash rather than throw")
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

    // ==================================================================
    // Movement 5 - layering. A data holder, not a security principal:
    // the adaptation to Spring Security lives one package out, and the
    // dependency runs that way and not this way.
    // Source: app/csd/CARDDEMO.CSD:L88, app/cbl/COSGN00C.cbl @ 7756d89
    // ==================================================================

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
                        + "lifecycle callback exists that could hash, mutate or normalise the credential "
                        + "on persist or load; and no bean validation constraint exists that would "
                        + "reject the blank but non null values a fixed width source legitimately holds")
                .isNotEmpty()
                .doesNotContainAnyElementsOf(FORBIDDEN_ANNOTATION_SIMPLE_NAMES);
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

        assertThat(Arrays.stream(UserSecurity.class.getDeclaredFields())
                .filter(field -> Modifier.isStatic(field.getModifiers()))
                .filter(field -> !field.isSynthetic())
                .map(Field::getType)
                .toList())
                .as("every static member of the entity is a primitive width constant, so no static "
                        + "logger, cache or shared buffer exists - the global mutable state Rule 1 "
                        + "clause B forbids")
                .allSatisfy(type -> assertThat(type.isPrimitive()).isTrue());
    }

    // ==================================================================
    // Movement 6 - hostile input and boundary conditions. Every member
    // accepts what the fixed width source can legitimately contain,
    // verbatim: nothing validates, trims, folds or truncates.
    // Source: app/cpy/CSUSR01Y.cpy:L18-L22, app/cbl/COSGN00C.cbl:L132,
    //         :L135 @ 7756d89
    // ==================================================================

    @ParameterizedTest(name = "an identifier of [{0}] is carried verbatim")
    @ValueSource(strings = {"", "A", "USER1", "USER001", "USER0001", "USER00012", "user0001", "  A  ",
        "USER0001OVERFLOW", "\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000"})
    @DisplayName("carries any identifier verbatim: short, over long, blank, low value and mixed case")
    void carriesAnyIdentifierVerbatim(final String identifier) {
        final UserSecurity user = new UserSecurity(
                identifier, "FIRST", "LAST", syntheticHash(1), UserType.USER);

        assertThat(user.getSecUsrId())
                .as("no validation, trimming or case folding happens here. A value longer than the "
                        + "CHAR(8) column is rejected on flush by the column width, with full context "
                        + "from the provider, and one shorter is blank padded by the column - neither "
                        + "is the entity's business. The sign on program's own upper casing at "
                        + "app/cbl/COSGN00C.cbl:L132 is service tier behaviour applied to input")
                .isEqualTo(identifier);
    }

    @Test
    @DisplayName("accepts a 7 character and a 9 character identifier without altering either")
    void acceptsIdentifiersOnEitherSideOfTheKeyWidth() {
        final String tooShort = "USER000";
        final String tooLong = "USER00001";

        assertThat(tooShort).hasSize(CATALOGUED_KEY_LENGTH - 1);
        assertThat(tooLong).hasSize(CATALOGUED_KEY_LENGTH + 1);

        final UserSecurity shortId = new UserSecurity(
                tooShort, "FIRST", "LAST", syntheticHash(1), UserType.USER);
        final UserSecurity longId = new UserSecurity(
                tooLong, "FIRST", "LAST", syntheticHash(1), UserType.USER);

        assertThat(shortId.getSecUsrId()).isEqualTo(tooShort);
        assertThat(longId.getSecUsrId())
                .as("truncating to the key width here would silently create a different row; the column "
                        + "width is the right place for that rejection")
                .isEqualTo(tooLong)
                .hasSize(CATALOGUED_KEY_LENGTH + 1);
    }

    @ParameterizedTest(name = "a name of [{0}] is carried verbatim")
    @ValueSource(strings = {"", " ", "                    ", "GOLD", "gold",
        "LACHAPELLE          ", "A NAME WITH SPACES", "O'BRIEN", "\u0000"})
    @DisplayName("carries any name verbatim, because a name of only spaces is legitimate legacy data")
    void carriesAnyNameVerbatim(final String name) {
        final UserSecurity user = new UserSecurity("USER0001", name, name, syntheticHash(1), UserType.USER);

        assertThat(user.getSecUsrFname())
                .as("a loading rule that rejected blankness would reject rows the source accepts, and a "
                        + "getter that trimmed would hide the very padding a fixed width defect shows up "
                        + "as. User: %s", user.getSecUsrId())
                .isEqualTo(name);
        assertThat(user.getSecUsrLname()).isEqualTo(name);
    }

    @Test
    @DisplayName("accepts a null in every member, because the NOT NULL column is where that is caught")
    void acceptsANullInEveryMember() {
        final UserSecurity transientUser = new UserSecurity(null, null, null, null, null);

        assertThat(transientUser.getSecUsrId()).isNull();
        assertThat(transientUser.getSecUsrFname()).isNull();
        assertThat(transientUser.getSecUsrLname()).isNull();
        assertThat(transientUser.getPasswordHash())
                .as("all five columns are NOT NULL in the schema and the provider reports a null on "
                        + "flush with full context, so a constructor level check would only duplicate "
                        + "that at the cost of rejecting a legitimate staged construction")
                .isNull();
        assertThat(transientUser.getSecUsrType()).isNull();
    }

    @ParameterizedTest(name = "a credential of {1} characters is carried verbatim")
    @MethodSource("credentialBoundaryValues")
    @DisplayName("carries a malformed credential verbatim rather than validating or padding it")
    void carriesAnyCredentialVerbatim(final String credential, final int expectedLength) {
        final UserSecurity user = new UserSecurity(
                "USER0001", "FIRST", "LAST", credential, UserType.USER);

        assertThat(user.getPasswordHash())
                .as("the setter performs no hashing, encoding, validation or normalisation: a caller "
                        + "that passed a plaintext password would store a plaintext password, which is a "
                        + "defect in the caller and one this class cannot detect without taking on the "
                        + "hashing responsibility that belongs to the authentication service. User: %s",
                        user.getSecUsrId())
                .isEqualTo(credential)
                .hasSize(expectedLength);
        assertThat(user.getPasswordHash())
                .as("and it must never be blank padded to the column width, because a padded hash fails "
                        + "verification - which is exactly why the column is VARCHAR and not CHAR")
                .isNotEqualTo(credential + " ");
    }

    @Test
    @DisplayName("accepts a 59 and a 61 character credential, and neither satisfies the hash shape")
    void acceptsCredentialsOnEitherSideOfTheHashWidth() {
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
        assertThat(new UserSecurity("USER0001", "FIRST", "LAST", oneLong, UserType.USER).getPasswordHash())
                .as("the entity still carries it verbatim: detecting a malformed hash belongs to the "
                        + "column width and to the encoder, not to a data holder")
                .isEqualTo(oneLong);
    }

    @ParameterizedTest(name = "the accessor pair for {0} round trips without normalising")
    @ValueSource(strings = {"secUsrId", "secUsrFname", "secUsrLname", "passwordHash"})
    @DisplayName("round trips every character member through its setter without altering the value")
    void everyCharacterMemberRoundTripsUnaltered(final String member) throws Exception {
        final String awkward = "  MiXeD  ";
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

        standard.setSecUsrType(null);
        assertThat(standard.getSecUsrType())
                .as("a null is carried and refused by the NOT NULL column on flush, never substituted")
                .isNull();
    }

    // ==================================================================
    // Movement 7 - schema and ordering context. What the entity must
    // NOT take on: the check constraint whose SQL is Not available, the
    // page size that belongs to the DTO, and the self delete guard the
    // source deliberately lacks.
    // Source: app/csd/CARDDEMO.CSD:L1-L88, app/cbl/COUSR00C.cbl:L57,
    //         app/cbl/COUSR03C.cbl, app/cbl/COSGN00C.cbl:L132 @ 7756d89
    // ==================================================================

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
                .as("V1__create_schema.sql is planned to carry a CHECK constraint restricting "
                        + "sec_usr_type to 'A' or 'U' - one of five check constraints in that migration. "
                        + "The migration has no planned children in this run, so the SQL text itself is "
                        + "Not available and none is invented here. What IS assertable is the converter's "
                        + "own domain, which admits exactly the same two codes and therefore enforces the "
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
    @DisplayName("declares exactly the ten accessors and the three Object overrides, and nothing else")
    void declaresOnlyAccessorsAndTheObjectOverrides() {
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
                        + "the named filler")
                .containsExactlyInAnyOrder(
                        "getSecUsrId", "setSecUsrId",
                        "getSecUsrFname", "setSecUsrFname",
                        "getSecUsrLname", "setSecUsrLname",
                        "getPasswordHash", "setPasswordHash",
                        "getSecUsrType", "setSecUsrType",
                        "equals", "hashCode", "toString");
        assertThat(methodNames)
                .as("two accessors per modelled member, plus the three Object overrides")
                .hasSize(MODELLED_MEMBER_COUNT * 2 + 3);
    }

    @Test
    @DisplayName("performs no case folding of its own, unlike the sign on program it feeds")
    void performsNoCaseFoldingOfItsOwn() {
        final String mixedCaseId = "uSeR0001";
        final UserSecurity user = new UserSecurity(
                mixedCaseId, "margaret", "gold", syntheticHash(1), UserType.ADMIN);

        assertThat(user.getSecUsrId())
                .as("app/cbl/COSGN00C.cbl:L132 upper cases the identifier and :L135 upper cases the "
                        + "password before the plaintext comparison at :L223 - it folds BOTH, not just "
                        + "the identifier. That is input handling in the sign on path and it is asserted "
                        + "against AuthenticationService, not here. Folding on the entity would rewrite "
                        + "stored data on every load and would make the primary key ambiguous")
                .isEqualTo(mixedCaseId)
                .isNotEqualTo(mixedCaseId.toUpperCase(Locale.ROOT));
        assertThat(user.getSecUsrFname()).isEqualTo("margaret");
        assertThat(user.getSecUsrLname()).isEqualTo("gold");
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
    // exist. Names and types are public record; the shared plaintext is
    // not, and never appears here in any form.
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
                .as("and app/data/EBCDIC/USRSEC.PS is explicitly out of scope: no codepage conversion "
                        + "is performed anywhere in this migration")
                .isNull();
    }

    // ==================================================================
    // Argument providers. Pure functions over the constants above: no
    // wall clock, no locale, no random source and no shared mutable
    // state, so every run enumerates the same cases in the same order.
    // ==================================================================

    /**
     * Every internal name that must not appear in the compiled entity or its nested converter.
     *
     * @return one argument per forbidden internal name, in declaration order
     */
    private static Stream<String> forbiddenInternalNames() {
        return FORBIDDEN_INTERNAL_NAMES.stream();
    }

    /**
     * The printable ASCII range with the two copybook codes removed.
     *
     * <p>Runs from {@code '!'} (0x21) to {@code '~'} (0x7E) inclusive, which is 94 characters, less
     * {@code 'A'} and {@code 'U'} - so 92 rejection cases. Space and the control characters are excluded
     * because {@link UserSecurity.UserTypeConverter#convertToEntityAttribute(String)} maps a
     * whitespace-only value to {@code null} by design and that branch is asserted separately.
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
     * <p>Built by deforming a synthetic well-formed hash rather than by writing candidate hashes out,
     * so that no literal in this provider could ever be mistaken for real credential material. The
     * reason string is carried into the assertion description, so a failure names the defect rather
     * than printing the offending value on its own.
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

    /**
     * Credential values of assorted widths that the entity must carry verbatim.
     *
     * <p>Deliberately includes values the credential column would reject on flush: the point of the
     * consuming test is that rejection happens at the column and at the encoder, not in a setter.
     *
     * @return {@code (candidate, expectedLength)} pairs spanning empty, short, exact and over-long
     */
    private static Stream<Arguments> credentialBoundaryValues() {
        final String wellFormed = syntheticHash(1);
        return Stream.of(
                Arguments.of("", 0),
                Arguments.of(" ", 1),
                Arguments.of(BCRYPT_PREFIX, BCRYPT_PREFIX.length()),
                Arguments.of(wellFormed.substring(0, BCRYPT_HASH_WIDTH - 1), BCRYPT_HASH_WIDTH - 1),
                Arguments.of(wellFormed, BCRYPT_HASH_WIDTH),
                Arguments.of(wellFormed + "0", BCRYPT_HASH_WIDTH + 1));
    }

    // ==================================================================
    // Helpers. Reflection and class-file inspection, kept in one place
    // so that every structural assertion above reads as a statement
    // about the entity rather than as plumbing.
    // ==================================================================

    /**
     * The entity's persistent members: every declared field that is neither static nor synthetic.
     *
     * <p>Static fields are the PIC width constants and synthetic fields are compiler artefacts such as
     * the {@code $assertionsDisabled} flag; neither is mapped, and neither belongs in a count of
     * modelled members.
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
     * <p>Resolves through {@link #persistentFields()} rather than {@code getDeclaredField}, so that a
     * renamed or removed member fails as a missing-member assertion naming the member, instead of
     * forcing every caller to declare {@code throws NoSuchFieldException}.
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
     * <p>Uses {@code getDeclaredAnnotations}, so an annotation inherited from a superclass is not
     * reported - which is what makes "this entity declares exactly these annotations" a statement
     * about this entity.
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
     * Every annotation declared anywhere on a type: on the type itself, and on each of its declared
     * fields, methods and constructors.
     *
     * <p>The whole-surface sweep is what lets a single assertion rule out a lifecycle callback, an
     * association, an inheritance strategy and a bean validation constraint at once, wherever the
     * annotation might have been placed.
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
     * <p>Decoded as ISO-8859-1 because that charset maps each of the 256 byte values to a distinct
     * character with no replacement and no failure, which makes an ASCII substring search over the
     * result exact. The class file is the authority the source text cannot be: an import may be absent
     * while a fully qualified reference in a method body is not, and comments do not survive
     * compilation at all - so a Javadoc mention of a forbidden package can never trip the scan.
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
                            + "mvn -B test-compile first", simpleName)
                    .isNotNull();
            return new String(compiled.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }

    /**
     * The string literals embedded in one compiled class file.
     *
     * <p>Walks the constant pool of the class file format, collecting each {@code CONSTANT_String}
     * entry resolved through the {@code CONSTANT_Utf8} entry it points at. Only genuine string
     * literals are returned - not member names, type descriptors or signatures - which is what makes
     * "no literal in this class could be a credential" a precise claim rather than a coincidence of
     * substring matching.
     *
     * <p>An unrecognised pool tag fails loudly with the tag and slot, so a future class file format
     * change surfaces as an explicit failure rather than as a silently short list that would let a
     * committed credential through.
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
     * <p>Letters, digits and the underscore only. {@code $} is excluded deliberately, so a reference to
     * a nested type reads back as the outer simple name rather than as the two joined together, and
     * {@code ;} terminates a descriptor as it should.
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
     * <p>The predicate is width and alphabet only - exactly {@value #SOURCE_CREDENTIAL_PIC_WIDTH}
     * characters, every one an upper-case ASCII letter - which is how the compiled entity can be
     * scanned for a committed credential without the literal itself ever appearing in this file. Rule 1
     * clause D names tests explicitly, so the mechanical check has to be shape-based.
     *
     * <p>Scoped to the entity's own constant pool. Applied to arbitrary text it would also match an
     * eight-letter upper-case name, which is precisely why it is never applied to anything else.
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
     * A synthetic value with the shape of a BCrypt hash at the pinned strength, and the substance of
     * none.
     *
     * <p>Sixty characters: the {@code $2a$10$} prefix, then a salt and digest region built from
     * self-describing text and the supplied ordinal. It satisfies {@link #BCRYPT_SHAPE} and it is not
     * the digest of anything, so no test in this class ever needs a real hash - which is what keeps
     * both the plaintext and any derived credential out of the repository. No BCrypt library is used:
     * this tier pins its dependencies and computing a hash here would add one.
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
     * <p>Rows 0 to 4 are the administrators and rows 5 to 9 the standard users, in the order IEBGENER
     * reads them from {@code app/jcl/DUSRSECJ.jcl:L35-L44}. The user class is resolved through
     * {@link UserType#requireFromCode(char)} from {@link #SEEDED_TYPE_BYTES}, so the mapping under test
     * is exercised rather than restated. The credential is synthetic in every case.
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
}
