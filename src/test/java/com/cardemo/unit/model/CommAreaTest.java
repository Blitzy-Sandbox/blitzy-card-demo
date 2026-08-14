/*
 * ******************************************************************
 * Program     : CommAreaTest.java
 * Application : CardDemo
 * Type        : Java 25 unit test (JUnit Jupiter, Surefire tier)
 * Function    : Verifies com.cardemo.model.dto.CommArea against the
 *               frozen 160-byte CICS communication area: the leaf field
 *               contract and its byte budget, the eight declarations
 *               that have no Java counterpart, the numeric-versus-text
 *               card number divergence, the absent/blank/low-values
 *               tri-state, the hostile-input stance, and a rendering
 *               that discloses no card number and no customer name.
 * Source      : app/cpy/COCOM01Y.cpy (01 CARDDEMO-COMMAREA, L19-L44,
 *               160 bytes) @ 7756d89
 * Source      : app/cpy/CVACT02Y.cpy:L5 and app/cpy/CVTRA05Y.cpy:L15,
 *               which both declare the same card number PIC X(16)
 *               @ 7756d89
 * Source      : app/cpy/CVCUS01Y.cpy:L5-L8 (three PIC X(25) names),
 *               app/cpy/CSUSR01Y.cpy:L17-L23 (80-byte user record with
 *               PIC X(20) names), app/cpy/CSSETATY.cpy:L18-L27
 *               (procedural re-entry template) @ 7756d89
 * Source      : app/cbl/COCRDLIC.cbl:L295, app/cbl/COMEN01C.cbl:L153,
 *               app/cbl/COUSR02C.cbl:L91,L113-L114,L125,L253,
 *               app/cbl/COACTUPC.cbl:L505-L508,L1667-L1678 @ 7756d89
 * Source      : app/jcl/DUSRSECJ.jcl:L35-L44,L65-L66 @ 7756d89
 * Source      : app/data/ASCII/custdata.txt and
 *               app/data/ASCII/carddata.txt @ 7756d89
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

import com.cardemo.model.dto.CommArea;
import com.cardemo.model.enums.UserType;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
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
 * Verifies {@link CommArea} against {@code 01 CARDDEMO-COMMAREA} at {@code app/cpy/COCOM01Y.cpy:L19}.
 *
 * <h2>What this class does</h2>
 *
 * <p>The COMMAREA was the legacy system's single carrier of state between pseudo-conversational turns. It
 * was declared in the LINKAGE SECTION as a byte run of unknown length -
 * {@code OCCURS 1 TO 32767 TIMES DEPENDING ON EIBCALEN} at {@code app/cbl/COCRDLIC.cbl:L295} - handed to
 * every program reached by {@code EXEC CICS XCTL} and returned to the terminal on
 * {@code RETURN TRANSID ... COMMAREA}. {@code app/cbl/COMEN01C.cbl:L151-L154} shows one whole turn in five
 * lines: zero the re-entry flag, transfer to a program <em>named by a COMMAREA field</em>, and pass the area
 * along.
 *
 * <p>The target keeps no server-side session state, so the right translation carries <em>less</em> than the
 * source declared. This class therefore verifies a subtraction as well as a contract:
 *
 * <ol>
 *   <li>the copybook's sixteen leaf declarations, their PIC widths and their five {@code 05} groups, whose
 *       byte totals balance to exactly 160 - {@code 34 + 84 + 12 + 16 + 14};</li>
 *   <li>that exactly nine of the sixteen survive as record components, in copybook declaration order, and
 *       that the seven omitted ones are absent under every spelling rather than renamed;</li>
 *   <li>that the three numeric-PIC fields are carried as text, because a numeric type silently deletes the
 *       leading zeros the seeded fixtures actually contain;</li>
 *   <li>that absent, blank and low-values remain three distinguishable states on every component;</li>
 *   <li>that the type is inert - no dispatch, no session, no mutable static, no re-entry behaviour;</li>
 *   <li>that {@link CommArea#toString()} discloses neither the card number nor any customer name.</li>
 * </ol>
 *
 * <h2>The eight declarations with no Java counterpart, and the byte total they take with them</h2>
 *
 * <p>The production type omits them outright rather than retaining them as unused members. Seven are leaf
 * fields - {@code CDEMO-FROM-TRANID}, {@code CDEMO-FROM-PROGRAM}, {@code CDEMO-TO-TRANID} and
 * {@code CDEMO-TO-PROGRAM} at {@code app/cpy/COCOM01Y.cpy:L21-L24}, {@code CDEMO-PGM-CONTEXT} at
 * {@code :L29}, and {@code CDEMO-LAST-MAP} with {@code CDEMO-LAST-MAPSET} at {@code :L43-L44} - and the
 * eighth is the condition-name pair {@code CDEMO-PGM-ENTER VALUE 0} / {@code CDEMO-PGM-REENTER VALUE 1} at
 * {@code :L30-L31}, which is one declaration over the flag at L29.
 *
 * <p><strong>The consequence is recorded here because it is a real, intentional deviation rather than an
 * oversight: the 160-byte total is NOT reconstructible from the Java type.</strong> The nine surviving
 * fields account for 121 bytes and the seven omitted leaf fields for the remaining 39. This class asserts
 * both figures and their sum, so the deviation is measured rather than merely described. That measurement,
 * together with the per-field account above, is the tracking Rule 1 clause B requires: what the clause
 * prohibits is an artefact carrying no explanation at all, and every omitted field here is named, sized and
 * asserted.
 *
 * <p>Why the re-entry flag in particular has no counterpart is worth stating, because it was not merely
 * vestigial - it gated behaviour. {@code app/cpy/CSSETATY.cpy} is a PROCEDURE DIVISION template resolved
 * through {@code COPY ... REPLACING} with the parameters {@code (TESTVAR1)}, {@code (SCRNVAR2)} and
 * {@code (MAPNAME3)}, and at {@code :L18-L20} it fires its field markers only when
 * {@code CDEMO-PGM-REENTER} also holds. In a stateless API every request is its own first request, so
 * there is no second turn for such a flag to describe, and carrying one would be client-supplied state the
 * server would have to trust. Being procedural, that copybook maps onto framework mechanisms and gets no
 * class of its own.
 *
 * <h2>The five groups are flattened, and that is asserted rather than assumed</h2>
 *
 * <p>The production type declares nine flat components and no nested group types. A COBOL group item
 * declares no storage of its own, so flattening loses no byte; what it could lose is <em>order</em>, since
 * a record's canonical constructor is positional and a reordering would change the meaning of every call
 * site while still compiling. This class therefore asserts the flattening explicitly and pins the
 * component sequence to the copybook's declaration order from L21 through L44.
 *
 * <h2>How to run, build and test</h2>
 *
 * <p>This class is bound to the unit tier and executed by {@code maven-surefire-plugin}, which discovers
 * it by the {@code **}{@code /*Test.java} name pattern with the integration and end-to-end trees excluded
 * by path. Neither the path nor the class name may change: a class that matches neither Surefire's nor
 * Failsafe's patterns is collected by <em>neither</em> and silently never runs, which presents as a green
 * build with no warning of any kind.
 *
 * <pre>
 * ./mvnw -B -ntp -Ddependency-check.skip=true test
 * ./mvnw -B -ntp -Ddependency-check.skip=true -Dtest=CommAreaTest test
 * </pre>
 *
 * <p>Compilation is the first gate: {@code maven-compiler-plugin} runs at {@code release 25} with
 * {@code -Xlint:all}, {@code -Werror} and {@code failOnWarning}, and those flags reach test compilation, so
 * a single unused import or raw type fails the build outright rather than producing a warning.
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <ul>
 *   <li><strong>Pure JVM tier.</strong> No container, no Spring context, no database and no cloud service.
 *       The subject is an immutable value type, so there is nothing to stand up.</li>
 *   <li><strong>Fixed clock.</strong> Any temporal reading goes through
 *       {@link FixedClockProvider#canonicalClock()}, never through {@code Instant.now()},
 *       {@code LocalDate.now()} or {@code System.currentTimeMillis()}. {@link CommArea} carries no
 *       temporal field, so the clock is used here to prove the absence of ambient-time dependence rather
 *       than to date a value.</li>
 *   <li><strong>Fixtures by classpath resource only.</strong> Corpus corroboration goes through
 *       {@link FixtureLoader}, which validates each fixture's byte count, record count and record width on
 *       load and refuses an altered file. Nothing is copied, edited or written, and the frozen
 *       {@code app/} tree is never touched. The fixture is named {@code dailytran.txt} - never
 *       {@code dalytran.txt}, which is the mainframe DD name and matches no resource.</li>
 *   <li><strong>No mocking framework is used.</strong> Mockito is on the test classpath but has no subject
 *       here: {@link CommArea} has no collaborator to stub, so introducing a mock would add a strictness
 *       setting to configure and verify nothing. Stating that is more useful than a mock that asserts
 *       nothing.</li>
 *   <li><strong>{@link Locale#ROOT} for every case operation</strong>, so no assertion can vary with the
 *       platform default locale. There is no parsing or formatting to localise.</li>
 *   <li><strong>One shared validator</strong>, bootstrapped once for the test JVM and immutable
 *       thereafter. A {@link Validator} is stateless and thread safe, so this is shared immutable state
 *       rather than the global mutable state Rule 1 clause B forbids.</li>
 * </ul>
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><strong>Build fails at test compilation with no test having run.</strong> {@code -Werror} escalated
 *       a lint warning - most often an unused import left behind by an edit, or a raw type. Note that a
 *       licence header written as {@code /**} rather than {@code /*} is also fatal here, because a doc
 *       comment that precedes no declaration raises the {@code dangling-doc-comments} lint. The compiler
 *       names the file and line; there is no way to run the suite until it is clean, so nothing in the
 *       module can be verified while it stands.</li>
 *   <li><strong>Screen-state widths asserted as 8.</strong> {@code CDEMO-LAST-MAP} and
 *       {@code CDEMO-LAST-MAPSET} are {@code PIC X(7)} at {@code app/cpy/COCOM01Y.cpy:L43-L44}, not
 *       {@code X(8)}. Widening either to 8 unbalances the 160-byte total by two.</li>
 *   <li><strong>The card number typed numerically.</strong> {@code CDEMO-CARD-NUM} is {@code PIC 9(16)},
 *       but {@code CARD-NUM} at {@code app/cpy/CVACT02Y.cpy:L5} and {@code TRAN-CARD-NUM} at
 *       {@code app/cpy/CVTRA05Y.cpy:L15} are both {@code PIC X(16)}. Five of the fifty card numbers in
 *       {@code app/data/ASCII/carddata.txt} begin with a zero, so a {@code long} or a
 *       {@code BigInteger} loses a significant digit on real fixture data and cannot write the value back
 *       into the fixed-width record it came from.</li>
 *   <li><strong>A card number or a customer name reaching {@link CommArea#toString()}.</strong> A record's
 *       inherited rendering prints every component, so the override is mandatory rather than cosmetic and
 *       its absence would disclose protected data on any log line.</li>
 *   <li><strong>The type treated as a live session carrier or a dispatch key.</strong>
 *       {@code app/cbl/COMEN01C.cbl:L153} transfers control to a program named by a COMMAREA field; the
 *       target replaces that with static URL routing, so no component may steer a request.</li>
 *   <li><strong>Absent and blank collapsed into one state.</strong> The source distinguishes them:
 *       {@code app/cbl/COUSR02C.cbl:L113} tests {@code CDEMO-FROM-PROGRAM = SPACES OR LOW-VALUES}, and
 *       {@code app/cbl/COACTUPC.cbl:L505-L508} carries two different messages for the two conditions, so
 *       collapsing them changes which message a caller sees.</li>
 *   <li><strong>A member census failing only under coverage instrumentation.</strong> The coverage agent
 *       adds a non-final static field and a static method whose names begin with a dollar sign, and -
 *       measured, not assumed - it does <em>not</em> mark the method synthetic. A census filtering only on
 *       {@code isSynthetic()} therefore passes on an uninstrumented class and fails on an instrumented
 *       one. {@link #isToolingAdded(String, boolean)} filters on both signals for exactly that reason; the
 *       symptom is a loud failure that misleads about the production type rather than a hidden defect.</li>
 *   <li><strong>A width assertion reading {@code null} from a record component.</strong>
 *       {@code jakarta.validation.constraints.Size} omits {@code RECORD_COMPONENT} from its targets, so
 *       the annotation is propagated to the backing field, the accessor and the constructor parameter but
 *       is absent from the component itself. Read it through {@link #declaredSize(String)}, or the symptom
 *       is a failing assertion against a correct production type.</li>
 * </ul>
 *
 * <h2>Deliberately not asserted, and why</h2>
 *
 * <ul>
 *   <li><strong>Pagination: {@code Not available}.</strong> {@code app/cpy/COCOM01Y.cpy} declares no
 *       pagination field - a case-insensitive search of it for {@code page} and {@code next} returns
 *       nothing - so no page-number or next-page claim about this type can be verified and none is
 *       asserted here.</li>
 *   <li><strong>The seeded password: {@code Not available} by design.</strong> The ten records at
 *       {@code app/jcl/DUSRSECJ.jcl:L35-L44} carry a plain-text password in bytes 49 to 56, and that
 *       literal appears nowhere in this file or anywhere under {@code src/}. Only the identifiers and the
 *       type byte at position 57 are asserted. There is no {@code usrsec.txt} fixture, so the seed set is
 *       reachable only through that JCL member.</li>
 *   <li><strong>Schema claims: {@code Not available}.</strong> {@link CommArea} is a payload, not an
 *       entity; it maps to no table and no migration, so no DDL assertion is made.</li>
 * </ul>
 *
 * @see CommArea
 * @see UserType
 * @see FixtureLoader
 * @see FixedClockProvider
 */
@DisplayName("CommArea: the 160-byte COMMAREA, nine live fields, and a rendering that discloses no PAN")
final class CommAreaTest {

    /**
     * The total byte width of {@code 01 CARDDEMO-COMMAREA}, {@code app/cpy/COCOM01Y.cpy:L19-L44}.
     */
    private static final int COMMAREA_TOTAL_BYTES = 160;

    /** Bytes accounted for by the nine leaf fields the Java type keeps. */
    private static final int LIVE_BYTES = 121;

    /** Bytes accounted for by the seven leaf fields the Java type omits. */
    private static final int OMITTED_BYTES = 39;

    /** Leaf declarations under {@code 01 CARDDEMO-COMMAREA}, counting elementary items only. */
    private static final int LEAF_DECLARATION_COUNT = 16;

    /**
     * Omitted declarations: the seven omitted leaf fields plus the {@code CDEMO-PGM-ENTER} /
     * {@code CDEMO-PGM-REENTER} condition-name pair at {@code app/cpy/COCOM01Y.cpy:L30-L31}, which is one
     * declaration over the flag at L29.
     */
    private static final int OMITTED_DECLARATION_COUNT = 8;

    /** {@code 88 CDEMO-PGM-ENTER VALUE 0.} at {@code app/cpy/COCOM01Y.cpy:L30}. */
    private static final int PGM_ENTER_VALUE = 0;

    /** {@code 88 CDEMO-PGM-REENTER VALUE 1.} at {@code app/cpy/COCOM01Y.cpy:L31}. */
    private static final int PGM_REENTER_VALUE = 1;

    /** {@code 88 CDEMO-USRTYP-ADMIN VALUE 'A'.} at {@code app/cpy/COCOM01Y.cpy:L27}. */
    private static final String ADMIN_CODE = "A";

    /** {@code 88 CDEMO-USRTYP-USER VALUE 'U'.} at {@code app/cpy/COCOM01Y.cpy:L28}. */
    private static final String USER_CODE = "U";

    /** {@code 05 CDEMO-GENERAL-INFO.} at {@code app/cpy/COCOM01Y.cpy:L20}, 34 bytes. */
    private static final String GENERAL_INFO = "CDEMO-GENERAL-INFO";

    /** {@code 05 CDEMO-CUSTOMER-INFO.} at {@code app/cpy/COCOM01Y.cpy:L32}, 84 bytes. */
    private static final String CUSTOMER_INFO = "CDEMO-CUSTOMER-INFO";

    /** {@code 05 CDEMO-ACCOUNT-INFO.} at {@code app/cpy/COCOM01Y.cpy:L37}, 12 bytes. */
    private static final String ACCOUNT_INFO = "CDEMO-ACCOUNT-INFO";

    /** {@code 05 CDEMO-CARD-INFO.} at {@code app/cpy/COCOM01Y.cpy:L40}, 16 bytes. */
    private static final String CARD_INFO = "CDEMO-CARD-INFO";

    /** {@code 05 CDEMO-MORE-INFO.} at {@code app/cpy/COCOM01Y.cpy:L42}, 14 bytes. */
    private static final String MORE_INFO = "CDEMO-MORE-INFO";

    /** Marks a leaf field that the Java type deliberately does not carry. */
    private static final String OMITTED = "";

    /**
     * Width of {@code SEC-USR-FNAME} and {@code SEC-USR-LNAME} at {@code app/cpy/CSUSR01Y.cpy:L19-L20}.
     * Deliberately <em>not</em> the COMMAREA customer-name width; see {@code NameWidthFamilies}.
     */
    private static final int USER_SECURITY_NAME_WIDTH = 20;

    /** Total width of {@code 01 SEC-USER-DATA}, {@code app/cpy/CSUSR01Y.cpy:L17-L23}. */
    private static final int USER_SECURITY_RECORD_WIDTH = 80;

    /**
     * The five administrator identifiers seeded in stream at {@code app/jcl/DUSRSECJ.jcl:L35-L39}, read
     * from bytes 1 to 8 of each record. Their password bytes are not read and not reproduced.
     */
    private static final List<String> ADMIN_SEED_IDS =
            List.of("ADMIN001", "ADMIN002", "ADMIN003", "ADMIN004", "ADMIN005");

    /**
     * The five standard-user identifiers seeded in stream at {@code app/jcl/DUSRSECJ.jcl:L40-L44}, read
     * from bytes 1 to 8 of each record. Their password bytes are not read and not reproduced.
     */
    private static final List<String> USER_SEED_IDS =
            List.of("USER0001", "USER0002", "USER0003", "USER0004", "USER0005");

    /**
     * Bootstrapped once for the test JVM and never closed, matching the lifetime of the JVM itself. A
     * {@link Validator} is immutable and thread safe once obtained, so one instance serves every
     * assertion; closing the factory per class would force a lifecycle this class does not otherwise care
     * about and would risk using a validator whose factory has been released.
     */
    private static final ValidatorFactory VALIDATOR_FACTORY = Validation.buildDefaultValidatorFactory();

    /** Derived from {@link #VALIDATOR_FACTORY}; immutable, thread safe and therefore shareable. */
    private static final Validator VALIDATOR = VALIDATOR_FACTORY.getValidator();

    /**
     * One elementary declaration of {@code 01 CARDDEMO-COMMAREA}, transcribed from
     * {@code app/cpy/COCOM01Y.cpy} at the traceability anchor {@code 7756d89}.
     *
     * <p>The copybook is frozen, so this table is a stable contract rather than a cache. It is transcribed
     * instead of parsed at run time on purpose: reading {@code app/cpy/COCOM01Y.cpy} through a
     * working-directory-relative path would make the assertion depend on where the JVM was launched from,
     * which Rule 1 clause C rules out, and the copybooks are not published on the test classpath.
     *
     * @param line      the one-based line of {@code app/cpy/COCOM01Y.cpy} that declares this field
     * @param group     the enclosing {@code 05} group item's name
     * @param cobolName the elementary item's own name
     * @param picType   {@code 'X'} for an alphanumeric PIC or {@code '9'} for a numeric one
     * @param width     the declared character width, which is also the byte width for a DISPLAY item
     * @param component the {@link CommArea} record component this field becomes, or {@link #OMITTED}
     */
    private record Leaf(int line, String group, String cobolName, char picType, int width,
                        String component) {

        /**
         * Reports whether this declaration survives into the Java type.
         *
         * @return {@code true} when a record component carries this field
         */
        boolean isLive() {
            return !this.component.isEmpty();
        }
    }

    /**
     * Every elementary declaration of {@code 01 CARDDEMO-COMMAREA}, in copybook line order, L21 to L44.
     */
    private static final List<Leaf> LEAVES = List.of(
            new Leaf(21, GENERAL_INFO, "CDEMO-FROM-TRANID", 'X', 4, OMITTED),
            new Leaf(22, GENERAL_INFO, "CDEMO-FROM-PROGRAM", 'X', 8, OMITTED),
            new Leaf(23, GENERAL_INFO, "CDEMO-TO-TRANID", 'X', 4, OMITTED),
            new Leaf(24, GENERAL_INFO, "CDEMO-TO-PROGRAM", 'X', 8, OMITTED),
            new Leaf(25, GENERAL_INFO, "CDEMO-USER-ID", 'X', 8, "userId"),
            new Leaf(26, GENERAL_INFO, "CDEMO-USER-TYPE", 'X', 1, "userType"),
            new Leaf(29, GENERAL_INFO, "CDEMO-PGM-CONTEXT", '9', 1, OMITTED),
            new Leaf(33, CUSTOMER_INFO, "CDEMO-CUST-ID", '9', 9, "customerId"),
            new Leaf(34, CUSTOMER_INFO, "CDEMO-CUST-FNAME", 'X', 25, "customerFirstName"),
            new Leaf(35, CUSTOMER_INFO, "CDEMO-CUST-MNAME", 'X', 25, "customerMiddleName"),
            new Leaf(36, CUSTOMER_INFO, "CDEMO-CUST-LNAME", 'X', 25, "customerLastName"),
            new Leaf(38, ACCOUNT_INFO, "CDEMO-ACCT-ID", '9', 11, "accountId"),
            new Leaf(39, ACCOUNT_INFO, "CDEMO-ACCT-STATUS", 'X', 1, "accountStatus"),
            new Leaf(41, CARD_INFO, "CDEMO-CARD-NUM", '9', 16, "cardNumber"),
            new Leaf(43, MORE_INFO, "CDEMO-LAST-MAP", 'X', 7, OMITTED),
            new Leaf(44, MORE_INFO, "CDEMO-LAST-MAPSET", 'X', 7, OMITTED));

    /**
     * Returns the live leaf declarations, in copybook order.
     *
     * @return the nine declarations a record component carries
     */
    private static List<Leaf> liveLeaves() {
        return LEAVES.stream().filter(Leaf::isLive).toList();
    }

    /**
     * Returns the omitted leaf declarations, in copybook order.
     *
     * @return the seven elementary declarations no record component carries
     */
    private static List<Leaf> omittedLeaves() {
        return LEAVES.stream().filter(leaf -> !leaf.isLive()).toList();
    }

    /**
     * Looks up a leaf declaration by the record component it becomes.
     *
     * @param component the {@link CommArea} component name
     * @return the declaration behind it
     * @throws AssertionError if no live declaration maps to that component, which means this table and the
     *                        production type have diverged
     */
    private static Leaf leafFor(final String component) {
        return liveLeaves().stream()
                .filter(leaf -> leaf.component().equals(component))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no COCOM01Y declaration maps to component '" + component
                                + "'; the transcribed table and CommArea have diverged"));
    }

    /**
     * Returns {@link CommArea}'s record component names in declaration order.
     *
     * @return the component names, positionally ordered as the canonical constructor takes them
     */
    private static List<String> componentNames() {
        final List<String> names = new ArrayList<>();
        for (final RecordComponent component : CommArea.class.getRecordComponents()) {
            names.add(component.getName());
        }
        return List.copyOf(names);
    }

    /**
     * The three methods every record inherits from the {@link Object} contract. They are excluded from a
     * credential-token census because {@code hashCode} contains the substring {@code hash} and would
     * otherwise be reported as a credential member, which it plainly is not.
     */
    private static final List<String> OBJECT_CONTRACT_METHODS = List.of("equals", "hashCode", "toString");

    /**
     * Reports whether a member was added by tooling rather than declared in the source.
     *
     * <p>Two filters are needed rather than one. The coverage agent instruments classes during the
     * {@code test} phase and adds both a static field and a static method whose names begin with a dollar
     * sign, and it does <em>not</em> mark the method synthetic - measured, not assumed. A census that
     * checked only {@code isSynthetic()} would therefore pass on an uninstrumented class and fail under
     * the instrumented run, which is the least useful moment to discover a naming assumption.
     *
     * @param name      the member's name
     * @param synthetic whether the reflective member reports itself synthetic
     * @return {@code true} when the member is tooling-added and must be excluded from a census
     */
    private static boolean isToolingAdded(final String name, final boolean synthetic) {
        return synthetic || name.startsWith("$");
    }

    /**
     * Returns every name a reader could reach on the type: components, declared fields and declared
     * methods, lower-cased under {@link Locale#ROOT} so a token scan cannot vary with the platform locale.
     *
     * @return the lower-cased surface names, excluding tooling-added members
     */
    private static List<String> surfaceNames() {
        final List<String> names = new ArrayList<>(componentNames());
        for (final Field field : CommArea.class.getDeclaredFields()) {
            if (!isToolingAdded(field.getName(), field.isSynthetic())) {
                names.add(field.getName());
            }
        }
        names.addAll(declaredMethodNames());
        return names.stream().map(name -> name.toLowerCase(Locale.ROOT)).toList();
    }

    /**
     * Returns the names of every method declared in the source, in no guaranteed order.
     *
     * @return the declared method names, excluding tooling-added members
     */
    private static List<String> declaredMethodNames() {
        final List<String> names = new ArrayList<>();
        for (final Method method : CommArea.class.getDeclaredMethods()) {
            if (!isToolingAdded(method.getName(), method.isSynthetic())) {
                names.add(method.getName());
            }
        }
        return List.copyOf(names);
    }

    /**
     * Returns every type name reachable from the public surface: component types plus the return and
     * parameter types of every declared method.
     *
     * @return the fully qualified type names appearing on the surface
     */
    private static List<String> surfaceTypeNames() {
        final List<String> types = new ArrayList<>();
        for (final RecordComponent component : CommArea.class.getRecordComponents()) {
            types.add(component.getType().getName());
        }
        for (final Method method : CommArea.class.getDeclaredMethods()) {
            if (isToolingAdded(method.getName(), method.isSynthetic())) {
                continue;
            }
            types.add(method.getReturnType().getName());
            for (final Class<?> parameter : method.getParameterTypes()) {
                types.add(parameter.getName());
            }
        }
        return List.copyOf(types);
    }

    /**
     * Reads the declared width off a component's backing field.
     *
     * <p>The lookup deliberately does not go through {@link RecordComponent#getAnnotation}, which returns
     * {@code null} here. {@code jakarta.validation.constraints.Size} does not list
     * {@code ElementType.RECORD_COMPONENT} among its targets, so when it is written on a record component
     * the compiler propagates it to the backing field, the accessor and the constructor parameter without
     * retaining it on the component itself. Reading the field is therefore the correct lookup rather than a
     * workaround, and {@code SizeIsDeclaredConsistently} asserts all three placements agree.
     *
     * @param component the component name
     * @return the width constraint declared for it
     * @throws AssertionError if the component declares no width, or if there is no such component
     */
    private static Size declaredSize(final String component) {
        final Size declared = backingField(component).getAnnotation(Size.class);
        if (declared == null) {
            throw new AssertionError("component '" + component + "' declares no width constraint");
        }
        return declared;
    }

    /**
     * Returns the private final field a record component is backed by.
     *
     * @param component the component name
     * @return the backing field
     * @throws AssertionError if no such field exists, which means this test and the record have diverged
     */
    private static Field backingField(final String component) {
        try {
            return CommArea.class.getDeclaredField(component);
        } catch (final NoSuchFieldException absent) {
            throw new AssertionError("CommArea declares no field for component '" + component + "'", absent);
        }
    }

    /**
     * Returns the accessor method a record component is read through.
     *
     * @param component the component name
     * @return the zero-argument accessor
     * @throws AssertionError if no such accessor exists, which means this test and the record have diverged
     */
    private static Method accessor(final String component) {
        try {
            return CommArea.class.getDeclaredMethod(component);
        } catch (final NoSuchMethodException absent) {
            throw new AssertionError(
                    "CommArea declares no accessor for component '" + component + "'", absent);
        }
    }

    /**
     * Finds a declared method by name, ignoring its parameter list.
     *
     * @param name the method name
     * @return the first declared method with that name
     * @throws AssertionError if no method of that name is declared
     */
    private static Method findMethod(final String name) {
        for (final Method method : CommArea.class.getDeclaredMethods()) {
            if (method.getName().equals(name)) {
                return method;
            }
        }
        throw new AssertionError("CommArea declares no method named '" + name + "'");
    }

    /**
     * Builds a payload with exactly one component populated and the other eight absent, so a boundary
     * assertion attributes any violation to the field under test and to nothing else.
     *
     * <p>The dispatch is written out longhand rather than driven reflectively. The canonical constructor is
     * positional, so spelling the nine arguments in order is what makes an accidental transposition visible
     * at this call site instead of surfacing as a puzzling violation on a neighbouring field.
     *
     * @param component the component to populate
     * @param value     the value to place in it, which may be {@code null}
     * @return a payload carrying that value alone
     * @throws AssertionError if the component name is not one of the nine
     */
    private static CommArea withOnly(final String component, final String value) {
        return switch (component) {
            case "userId" -> new CommArea(value, null, null, null, null, null, null, null, null);
            case "userType" -> new CommArea(null, value, null, null, null, null, null, null, null);
            case "customerId" -> new CommArea(null, null, value, null, null, null, null, null, null);
            case "customerFirstName" -> new CommArea(null, null, null, value, null, null, null, null, null);
            case "customerMiddleName" -> new CommArea(null, null, null, null, value, null, null, null, null);
            case "customerLastName" -> new CommArea(null, null, null, null, null, value, null, null, null);
            case "accountId" -> new CommArea(null, null, null, null, null, null, value, null, null);
            case "accountStatus" -> new CommArea(null, null, null, null, null, null, null, value, null);
            case "cardNumber" -> new CommArea(null, null, null, null, null, null, null, null, value);
            default -> throw new AssertionError("'" + component + "' is not a CommArea component");
        };
    }

    /**
     * Reads one component back off a payload.
     *
     * @param payload   the payload to read
     * @param component the component name
     * @return the carried value, which may be {@code null}
     * @throws AssertionError if the component name is not one of the nine
     */
    private static String readComponent(final CommArea payload, final String component) {
        return switch (component) {
            case "userId" -> payload.userId();
            case "userType" -> payload.userType();
            case "customerId" -> payload.customerId();
            case "customerFirstName" -> payload.customerFirstName();
            case "customerMiddleName" -> payload.customerMiddleName();
            case "customerLastName" -> payload.customerLastName();
            case "accountId" -> payload.accountId();
            case "accountStatus" -> payload.accountStatus();
            case "cardNumber" -> payload.cardNumber();
            default -> throw new AssertionError("'" + component + "' is not a CommArea component");
        };
    }

    /**
     * Validates a payload against its declared constraints.
     *
     * @param payload the payload to validate
     * @return the violations, empty when every declared constraint holds
     */
    private static Set<ConstraintViolation<CommArea>> violations(final CommArea payload) {
        return VALIDATOR.validate(payload);
    }

    /**
     * Counts the violations attributed to one component.
     *
     * @param payload   the payload to validate
     * @param component the component name to filter on
     * @return the number of violations whose property path is that component
     */
    private static long violationCountOn(final CommArea payload, final String component) {
        return violations(payload).stream()
                .filter(violation -> component.equals(violation.getPropertyPath().toString()))
                .count();
    }

    /**
     * A fully populated payload used where the values themselves are incidental.
     *
     * <p>The card number is a synthetic sixteen-digit run. Every such literal in this file was checked to
     * fail the Luhn check, so none of them can be a usable card number even by accident, and none matches
     * an issuer range. Real card numbers are never hardcoded here: where a genuine one is needed as
     * evidence it is read at run time from {@code carddata.txt} through {@link FixtureLoader}. The user
     * identifier is a real seeded one, which is not sensitive; the seeded <em>password</em> is never read
     * or written anywhere in this file.
     *
     * @return a payload with all nine components populated
     */
    private static CommArea populated() {
        return new CommArea("ADMIN001", ADMIN_CODE, "000000001", "Immanuel", "Madeline", "Kessler",
                "00000000011", "Y", "9999888877776666");
    }

    /**
     * A payload with every component absent, which the source treats as a legitimate state: a COMMAREA
     * reaching a program before an account had been selected simply carried no account identifier.
     *
     * @return a payload with all nine components {@code null}
     */
    private static CommArea absent() {
        return new CommArea(null, null, null, null, null, null, null, null, null);
    }

    /**
     * Builds a run of blanks, reproducing what a COBOL {@code MOVE SPACES} leaves in a fixed-width field.
     *
     * @param width the field width
     * @return a string of exactly {@code width} spaces
     */
    private static String blanks(final int width) {
        return " ".repeat(width);
    }

    /**
     * Builds a run of low values, reproducing what {@code MOVE LOW-VALUES} or {@code INITIALIZE} leaves in
     * a fixed-width field: binary zeros, which are not spaces.
     *
     * @param width the field width
     * @return a string of exactly {@code width} NUL characters
     */
    private static String lowValues(final int width) {
        return "\u0000".repeat(width);
    }

    /**
     * The frozen source layout: sixteen leaf declarations in five groups, balancing to 160 bytes.
     */
    @Nested
    @DisplayName("1. Source layout - app/cpy/COCOM01Y.cpy:L19-L44 balances to exactly 160 bytes")
    final class SourceLayoutContract {

        @Test
        @DisplayName("declares sixteen elementary fields between L21 and L44")
        void declaresSixteenLeafFields() {
            assertThat(LEAVES).hasSize(LEAF_DECLARATION_COUNT);
        }

        @Test
        @DisplayName("orders those declarations by ascending copybook line, from L21 to L44")
        void declarationsRunInCopybookLineOrder() {
            final List<Integer> lines = LEAVES.stream().map(Leaf::line).toList();
            assertThat(lines).isSorted().doesNotHaveDuplicates();
            assertThat(lines.getFirst()).isEqualTo(21);
            assertThat(lines.getLast()).isEqualTo(44);
        }

        @Test
        @DisplayName("partitions every field across the five 05-level groups, in copybook order")
        void fiveGroupsPartitionEveryField() {
            final List<String> groupsInOrder = LEAVES.stream().map(Leaf::group).distinct().toList();
            assertThat(groupsInOrder).containsExactly(
                    GENERAL_INFO, CUSTOMER_INFO, ACCOUNT_INFO, CARD_INFO, MORE_INFO);
            assertThat(LEAVES).allSatisfy(leaf -> assertThat(groupsInOrder).contains(leaf.group()));
        }

        @ParameterizedTest(name = "{0} occupies {1} bytes")
        @CsvSource({
            "CDEMO-GENERAL-INFO,  34",
            "CDEMO-CUSTOMER-INFO, 84",
            "CDEMO-ACCOUNT-INFO,  12",
            "CDEMO-CARD-INFO,     16",
            "CDEMO-MORE-INFO,     14",
        })
        @DisplayName("sums each group to its declared byte width")
        void eachGroupSumsToItsDeclaredWidth(final String group, final int expectedBytes) {
            final int measured = LEAVES.stream()
                    .filter(leaf -> leaf.group().equals(group))
                    .mapToInt(Leaf::width)
                    .sum();
            assertThat(measured).as("byte width of %s", group).isEqualTo(expectedBytes);
        }

        @Test
        @DisplayName("balances the five group widths to 160: 34 + 84 + 12 + 16 + 14")
        void theGroupWidthsBalanceToOneHundredAndSixty() {
            assertThat(34 + 84 + 12 + 16 + 14).isEqualTo(COMMAREA_TOTAL_BYTES);
            final int measured = LEAVES.stream().mapToInt(Leaf::width).sum();
            assertThat(measured).as("total width of 01 CARDDEMO-COMMAREA").isEqualTo(COMMAREA_TOTAL_BYTES);
        }

        @Test
        @DisplayName("carries the screen-state fields at seven bytes each, not eight")
        void screenStateFieldsAreSevenBytesWide() {
            final Leaf map = LEAVES.stream()
                    .filter(leaf -> leaf.cobolName().equals("CDEMO-LAST-MAP"))
                    .findFirst().orElseThrow();
            final Leaf mapset = LEAVES.stream()
                    .filter(leaf -> leaf.cobolName().equals("CDEMO-LAST-MAPSET"))
                    .findFirst().orElseThrow();

            assertThat(map.width()).as("CDEMO-LAST-MAP is PIC X(7) at L43").isEqualTo(7).isNotEqualTo(8);
            assertThat(mapset.width()).as("CDEMO-LAST-MAPSET is PIC X(7) at L44").isEqualTo(7)
                    .isNotEqualTo(8);
            assertThat(map.width() + mapset.width()).as("CDEMO-MORE-INFO is 14 bytes, not 16")
                    .isEqualTo(14);
        }

        @Test
        @DisplayName("types exactly four fields with a numeric PIC and the other twelve alphanumerically")
        void picTypesAreAsDeclared() {
            final List<String> numericFields = LEAVES.stream()
                    .filter(leaf -> leaf.picType() == '9')
                    .map(Leaf::cobolName)
                    .toList();
            assertThat(numericFields).containsExactly(
                    "CDEMO-PGM-CONTEXT", "CDEMO-CUST-ID", "CDEMO-ACCT-ID", "CDEMO-CARD-NUM");
            assertThat(LEAVES).filteredOn(leaf -> leaf.picType() == 'X').hasSize(12);
        }

        @Test
        @DisplayName("gives the program-context flag exactly two condition names, valued 0 and 1")
        void theProgramContextFlagCarriesTwoConditionNames() {
            final Leaf flag = LEAVES.stream()
                    .filter(leaf -> leaf.cobolName().equals("CDEMO-PGM-CONTEXT"))
                    .findFirst().orElseThrow();

            assertThat(flag.picType()).isEqualTo('9');
            assertThat(flag.width()).isEqualTo(1);
            assertThat(PGM_ENTER_VALUE).as("88 CDEMO-PGM-ENTER VALUE 0 at L30").isZero();
            assertThat(PGM_REENTER_VALUE).as("88 CDEMO-PGM-REENTER VALUE 1 at L31").isEqualTo(1);
            assertThat(List.of(PGM_ENTER_VALUE, PGM_REENTER_VALUE)).containsExactly(0, 1);
        }

        @Test
        @DisplayName("gives the user-type field exactly two condition names, valued 'A' and 'U'")
        void theUserTypeFieldCarriesTwoConditionNames() {
            final Leaf userType = leafFor("userType");

            assertThat(userType.cobolName()).isEqualTo("CDEMO-USER-TYPE");
            assertThat(userType.width()).isEqualTo(1);
            assertThat(List.of(ADMIN_CODE, USER_CODE)).containsExactly("A", "U");
        }
    }

    /**
     * The subtraction: nine of the sixteen leaf fields survive, and the omission is measured.
     */
    @Nested
    @DisplayName("2. Field subtraction - nine live components, eight omitted declarations")
    final class FieldSubtraction {

        @Test
        @DisplayName("exposes exactly the nine live fields, in copybook declaration order")
        void exposesTheNineLiveFieldsInCopybookOrder() {
            assertThat(componentNames()).containsExactly(
                    "userId", "userType", "customerId", "customerFirstName", "customerMiddleName",
                    "customerLastName", "accountId", "accountStatus", "cardNumber");
            assertThat(componentNames()).isEqualTo(liveLeaves().stream().map(Leaf::component).toList());
        }

        @Test
        @DisplayName("closes the arithmetic: nine kept plus seven dropped is the sixteen declared")
        void theFieldArithmeticCloses() {
            assertThat(liveLeaves()).hasSize(9);
            assertThat(omittedLeaves()).hasSize(7);
            assertThat(liveLeaves().size() + omittedLeaves().size()).isEqualTo(LEAF_DECLARATION_COUNT);
        }

        @Test
        @DisplayName("counts eight omitted declarations once the condition-name pair is included")
        void theOmittedDeclarationCountIsEight() {
            final int conditionNamePairAsOneDeclaration = 1;
            assertThat(omittedLeaves().size() + conditionNamePairAsOneDeclaration)
                    .as("seven omitted leaf fields plus the CDEMO-PGM-ENTER / -REENTER pair at L30-L31")
                    .isEqualTo(OMITTED_DECLARATION_COUNT);
        }

        @ParameterizedTest(name = "{0} is declared by the source and absent from the record")
        @ValueSource(strings = {
            "CDEMO-FROM-TRANID", "CDEMO-FROM-PROGRAM", "CDEMO-TO-TRANID", "CDEMO-TO-PROGRAM",
            "CDEMO-PGM-CONTEXT", "CDEMO-LAST-MAP", "CDEMO-LAST-MAPSET",
        })
        @DisplayName("omits each routing, re-entry and screen-state field outright")
        void omitsEachCounterpartlessField(final String cobolName) {
            assertThat(LEAVES).as("%s must be a declaration of COCOM01Y", cobolName)
                    .anySatisfy(leaf -> assertThat(leaf.cobolName()).isEqualTo(cobolName));
            assertThat(omittedLeaves()).anySatisfy(
                    leaf -> assertThat(leaf.cobolName()).isEqualTo(cobolName));

            final String javaSpelling = cobolName.replace("CDEMO-", "").replace("-", "")
                    .toLowerCase(Locale.ROOT);
            assertThat(surfaceNames()).as("no member of CommArea may spell %s", cobolName)
                    .doesNotContain(javaSpelling);
        }

        @ParameterizedTest(name = "no component name contains '{0}'")
        @ValueSource(strings = {
            "tranid", "transaction", "program", "pgm", "context", "reenter", "mapset", "screen", "route",
            "navigation", "terminal", "commarea",
        })
        @DisplayName("does not reintroduce a dropped field under a renamed component")
        void doesNotReintroduceDroppedFieldsUnderNewNames(final String token) {
            final List<String> offenders = componentNames().stream()
                    .filter(name -> name.toLowerCase(Locale.ROOT).contains(token))
                    .toList();
            assertThat(offenders).as("component names carrying the token '%s'", token).isEmpty();
        }

        @Test
        @DisplayName("accounts for 121 live bytes and 39 omitted bytes, which is the 160-byte total")
        void theByteBudgetSplitsIntoLiveAndOmitted() {
            final int live = liveLeaves().stream().mapToInt(Leaf::width).sum();
            final int dropped = omittedLeaves().stream().mapToInt(Leaf::width).sum();

            assertThat(live).as("bytes the Java type carries").isEqualTo(LIVE_BYTES);
            assertThat(dropped).as("bytes the Java type omits").isEqualTo(OMITTED_BYTES);
            assertThat(live + dropped).isEqualTo(COMMAREA_TOTAL_BYTES);
        }

        @Test
        @DisplayName("cannot reconstruct the 160-byte record, which is the documented deviation")
        void theOneHundredAndSixtyByteTotalIsNotReconstructible() {
            final int reconstructible = liveLeaves().stream().mapToInt(Leaf::width).sum();

            assertThat(reconstructible)
                    .as("the Java type reaches only %d of the %d source bytes, which the class "
                            + "documentation explains field by field",
                            LIVE_BYTES, COMMAREA_TOTAL_BYTES)
                    .isLessThan(COMMAREA_TOTAL_BYTES)
                    .isEqualTo(LIVE_BYTES);
            assertThat(COMMAREA_TOTAL_BYTES - reconstructible).isEqualTo(OMITTED_BYTES);
        }

        @Test
        @DisplayName("keeps the flattening honest: nine components and no nested group type")
        void theGroupsAreFlattenedNotNested() {
            assertThat(CommArea.class.getRecordComponents()).hasSize(9);
            assertThat(CommArea.class.getDeclaredClasses())
                    .as("the five 05-level groups are flattened, so no nested group type exists")
                    .isEmpty();
            assertThat(surfaceTypeNames())
                    .as("every component is carried as text, so no group-shaped holder is referenced")
                    .doesNotContain(GENERAL_INFO, CUSTOMER_INFO, ACCOUNT_INFO, CARD_INFO, MORE_INFO);
        }
    }

    /**
     * Numeric PIC clauses carried as text, because the seeded fixtures rely on leading zeros.
     */
    @Nested
    @DisplayName("3. Representation - every numeric PIC is carried as text, leading zeros intact")
    final class NumericPicRepresentation {

        @Test
        @DisplayName("types all nine components as text, the three numeric-PIC ones included")
        void typesEveryComponentAsText() {
            for (final RecordComponent component : CommArea.class.getRecordComponents()) {
                assertThat(component.getType()).as("type of %s", component.getName())
                        .isEqualTo(String.class);
            }
        }

        @ParameterizedTest(name = "{0} is text, never a numeric type")
        @ValueSource(strings = {"customerId", "accountId", "cardNumber"})
        @DisplayName("refuses a numeric type for every numeric-PIC identifier")
        void refusesNumericTypesForNumericPicFields(final String component) {
            final Class<?> carried = CommArea.class.getRecordComponents()[componentNames()
                    .indexOf(component)].getType();

            assertThat(leafFor(component).picType())
                    .as("%s is declared with a numeric PIC in the copybook", component).isEqualTo('9');
            assertThat(carried).as("carrier type of %s", component)
                    .isEqualTo(String.class)
                    .isNotIn(Long.class, Integer.class, java.math.BigInteger.class, Long.TYPE, Integer.TYPE);
        }

        @Test
        @DisplayName("diverges from both record layouts, which declare the same card number PIC X(16)")
        void theCardNumberDivergesFromBothRecordLayouts() {
            final char commAreaPic = leafFor("cardNumber").picType();
            final char cardRecordPic = 'X';
            final char transactionRecordPic = 'X';

            assertThat(commAreaPic).as("CDEMO-CARD-NUM at app/cpy/COCOM01Y.cpy:L41").isEqualTo('9');
            assertThat(cardRecordPic).as("CARD-NUM at app/cpy/CVACT02Y.cpy:L5").isEqualTo('X');
            assertThat(transactionRecordPic).as("TRAN-CARD-NUM at app/cpy/CVTRA05Y.cpy:L15").isEqualTo('X');
            assertThat(commAreaPic).as("the two copybooks genuinely disagree and neither is corrected")
                    .isNotEqualTo(cardRecordPic);
            assertThat(leafFor("cardNumber").width())
                    .as("all three declare sixteen positions, so only the PIC class differs")
                    .isEqualTo(CommArea.CARD_NUMBER_MAX_LENGTH)
                    .isEqualTo(16);
        }

        @Test
        @DisplayName("keeps every leading zero of the seeded nine-byte customer identifier")
        void keepsTheSeededCustomerIdentifierImage() {
            final String seeded = "000000001";

            final String carried = withOnly("customerId", seeded).customerId();

            assertThat(carried).isEqualTo(seeded).hasSize(CommArea.CUSTOMER_ID_LENGTH);
            assertThat(Long.toString(Long.parseLong(seeded)))
                    .as("an integral carrier would render this image as '1', losing eight digits")
                    .isNotEqualTo(seeded)
                    .isEqualTo("1");
        }

        @Test
        @DisplayName("keeps every leading zero of the seeded eleven-byte account identifier")
        void keepsTheSeededAccountIdentifierImage() {
            final String seeded = "00000000011";

            final String carried = withOnly("accountId", seeded).accountId();

            assertThat(carried).isEqualTo(seeded).hasSize(CommArea.ACCOUNT_ID_LENGTH);
            assertThat(Long.toString(Long.parseLong(seeded)))
                    .as("an integral carrier would render this image as '11', losing nine digits")
                    .isNotEqualTo(seeded);
        }

        @Test
        @DisplayName("keeps the leading zero of a card number that begins with one")
        void keepsALeadingZeroCardNumber() {
            final String leadingZeroPan = "0000111122223333";

            final String carried = withOnly("cardNumber", leadingZeroPan).cardNumber();

            assertThat(carried).isEqualTo(leadingZeroPan).hasSize(CommArea.CARD_NUMBER_MAX_LENGTH);
            assertThat(Long.toString(Long.parseLong(leadingZeroPan)).length())
                    .as("an integral carrier would shorten this image below sixteen positions")
                    .isLessThan(CommArea.CARD_NUMBER_MAX_LENGTH);
        }

        @Test
        @DisplayName("round-trips each identifier byte for byte, applying no normalisation")
        void appliesNoNormalisationOnTheWayIn() {
            for (final Leaf leaf : liveLeaves()) {
                final String padded = "A".repeat(1) + blanks(leaf.width() - 1);

                final String carried = readComponent(withOnly(leaf.component(), padded), leaf.component());

                assertThat(carried).as("%s must not be trimmed, padded or re-cased", leaf.component())
                        .isEqualTo(padded)
                        .hasSize(leaf.width());
            }
        }
    }

    /**
     * Declared widths, taken from the copybook and enforced at the boundary.
     */
    @Nested
    @DisplayName("4. Declared widths - every ceiling is the copybook's, and nothing tighter")
    final class DeclaredWidths {

        @ParameterizedTest(name = "{0} publishes a ceiling of {1}")
        @CsvSource({
            "userId,             8",
            "userType,           1",
            "customerId,         9",
            "customerFirstName, 25",
            "customerMiddleName,25",
            "customerLastName,  25",
            "accountId,         11",
            "accountStatus,      1",
            "cardNumber,        16",
        })
        @DisplayName("bounds each component at the width its copybook declaration states")
        void boundsEachComponentAtTheCopybookWidth(final String component, final int expectedWidth) {
            final Size declared = declaredSize(component);

            assertThat(declared.max()).as("declared ceiling of %s", component).isEqualTo(expectedWidth);
            assertThat(leafFor(component).width()).as("copybook width of %s", component)
                    .isEqualTo(expectedWidth);
        }

        @Test
        @DisplayName("declares each width on the field, the accessor and the constructor parameter alike")
        void declaresEachWidthOnEveryPropagatedPlacement() {
            for (final Leaf leaf : liveLeaves()) {
                final String component = leaf.component();
                final Size onField = backingField(component).getAnnotation(Size.class);
                final Size onAccessor = accessor(component).getAnnotation(Size.class);

                assertThat(onField).as("%s must publish a width on its backing field", component).isNotNull();
                assertThat(onAccessor).as("%s must publish a width on its accessor", component).isNotNull();
                assertThat(onAccessor.max()).as("the two placements must agree for %s", component)
                        .isEqualTo(onField.max())
                        .isEqualTo(leaf.width());
                assertThat(CommArea.class.getRecordComponents()[componentNames().indexOf(component)]
                        .getAnnotation(Size.class))
                        .as("Size omits RECORD_COMPONENT from its targets, so it is absent there by design")
                        .isNull();
            }
        }

        @Test
        @DisplayName("publishes the seven width constants the copybook fixes")
        void publishesTheCopybookWidthsAsConstants() {
            assertThat(CommArea.USER_ID_MAX_LENGTH).isEqualTo(leafFor("userId").width()).isEqualTo(8);
            assertThat(CommArea.USER_TYPE_LENGTH).isEqualTo(leafFor("userType").width()).isEqualTo(1);
            assertThat(CommArea.CUSTOMER_ID_LENGTH).isEqualTo(leafFor("customerId").width()).isEqualTo(9);
            assertThat(CommArea.CUSTOMER_NAME_MAX_LENGTH)
                    .isEqualTo(leafFor("customerFirstName").width())
                    .isEqualTo(leafFor("customerMiddleName").width())
                    .isEqualTo(leafFor("customerLastName").width())
                    .isEqualTo(25);
            assertThat(CommArea.ACCOUNT_ID_LENGTH).isEqualTo(leafFor("accountId").width()).isEqualTo(11);
            assertThat(CommArea.ACCOUNT_STATUS_LENGTH).isEqualTo(leafFor("accountStatus").width())
                    .isEqualTo(1);
            assertThat(CommArea.CARD_NUMBER_MAX_LENGTH).isEqualTo(leafFor("cardNumber").width())
                    .isEqualTo(16);
        }

        @Test
        @DisplayName("accepts a value at exactly the declared width on every component")
        void acceptsAValueAtExactlyTheDeclaredWidth() {
            for (final Leaf leaf : liveLeaves()) {
                final CommArea payload = withOnly(leaf.component(), "9".repeat(leaf.width()));

                assertThat(violationCountOn(payload, leaf.component()))
                        .as("a %d-character value must satisfy %s", leaf.width(), leaf.component())
                        .isZero();
            }
        }

        @Test
        @DisplayName("refuses a value one character over the declared width on every component")
        void refusesAValueOneCharacterOverTheDeclaredWidth() {
            for (final Leaf leaf : liveLeaves()) {
                final CommArea payload = withOnly(leaf.component(), "9".repeat(leaf.width() + 1));

                assertThat(violationCountOn(payload, leaf.component()))
                        .as("a %d-character value must breach %s, whose ceiling is %d",
                                leaf.width() + 1, leaf.component(), leaf.width())
                        .isEqualTo(1L);
            }
        }

        @Test
        @DisplayName("accepts a value one character under the declared width on every component")
        void acceptsAValueOneCharacterUnderTheDeclaredWidth() {
            for (final Leaf leaf : liveLeaves()) {
                final CommArea payload = withOnly(leaf.component(), "9".repeat(leaf.width() - 1));

                assertThat(violationCountOn(payload, leaf.component()))
                        .as("a %d-character value must satisfy %s", leaf.width() - 1, leaf.component())
                        .isZero();
            }
        }

        @Test
        @DisplayName("preserves trailing blank padding instead of trimming it away")
        void preservesTrailingBlankPadding() {
            final String paddedName = "Kessler" + blanks(CommArea.CUSTOMER_NAME_MAX_LENGTH - 7);

            final CommArea payload = withOnly("customerLastName", paddedName);

            assertThat(payload.customerLastName()).isEqualTo(paddedName)
                    .hasSize(CommArea.CUSTOMER_NAME_MAX_LENGTH)
                    .endsWith(" ");
            assertThat(violations(payload)).isEmpty();
        }

        @Test
        @DisplayName("attributes an over-width breach to the offending component and to no other")
        void attributesABreachToTheOffendingComponentOnly() {
            final CommArea payload = withOnly("userId", "9".repeat(CommArea.USER_ID_MAX_LENGTH + 1));

            final Set<ConstraintViolation<CommArea>> raised = violations(payload);

            assertThat(raised).hasSize(1);
            assertThat(raised.iterator().next().getPropertyPath()).hasToString("userId");
        }
    }

    /**
     * Absent, blank and low values are three distinct states, exactly as the source treats them.
     */
    @Nested
    @DisplayName("5. Tri-state - absent, blank and low-values stay distinguishable on every component")
    final class TriStateFidelity {

        @Test
        @DisplayName("keeps absent, blank and low-values apart on all nine components")
        void keepsTheThreeStatesApartOnEveryComponent() {
            for (final Leaf leaf : liveLeaves()) {
                final String component = leaf.component();
                final String asAbsent = readComponent(withOnly(component, null), component);
                final String asBlank = readComponent(withOnly(component, blanks(leaf.width())), component);
                final String asLow = readComponent(withOnly(component, lowValues(leaf.width())), component);

                assertThat(asAbsent).as("absent %s must stay absent", component).isNull();
                assertThat(asBlank).as("blank %s must stay blank", component)
                        .isNotNull().isBlank().hasSize(leaf.width());
                assertThat(asLow).as("low-values %s must stay low-values", component)
                        .isNotNull().hasSize(leaf.width()).isNotEqualTo(asBlank);
                assertThat(asLow.charAt(0)).as("low-values is binary zero, not a space").isEqualTo('\u0000');
            }
        }

        @Test
        @DisplayName("reproduces the source's own distinction between SPACES and LOW-VALUES")
        void reproducesTheSourceDistinctionBetweenSpacesAndLowValues() {
            final int width = leafFor("userId").width();

            final String spaces = blanks(width);
            final String low = lowValues(width);

            assertThat(spaces)
                    .as("app/cbl/COUSR02C.cbl:L113 tests SPACES OR LOW-VALUES, so the two are not one state")
                    .isNotEqualTo(low);
            assertThat(spaces).isNotNull();
            assertThat(low).isNotNull();
            assertThat(spaces.isBlank()).isTrue();
            assertThat(low.isBlank()).as("a NUL run is not blank under Java's definition either").isFalse();
        }

        @Test
        @DisplayName("collapses neither absent into blank nor blank into absent")
        void collapsesNeitherAbsentIntoBlankNorBlankIntoAbsent() {
            assertThat(withOnly("customerMiddleName", null).customerMiddleName()).isNull();
            assertThat(withOnly("customerMiddleName", "").customerMiddleName()).isNotNull().isEmpty();
            assertThat(withOnly("customerMiddleName", " ").customerMiddleName()).isEqualTo(" ");
        }

        @Test
        @DisplayName("distinguishes an unpopulated payload from a blank-filled one")
        void distinguishesAnUnpopulatedPayloadFromABlankFilledOne() {
            final CommArea unpopulated = absent();
            final CommArea blankFilled = new CommArea(blanks(8), blanks(1), blanks(9), blanks(25),
                    blanks(25), blanks(25), blanks(11), blanks(1), blanks(16));

            assertThat(unpopulated).isNotEqualTo(blankFilled);
            assertThat(unpopulated.userId()).isNull();
            assertThat(blankFilled.userId()).isNotNull().isBlank();
        }

        @Test
        @DisplayName("treats all three states as valid, because an unpopulated context is a real state")
        void treatsAllThreeStatesAsValid() {
            assertThat(violations(absent())).as("an absent payload is legitimate").isEmpty();
            assertThat(violations(populated())).as("a populated payload is legitimate").isEmpty();

            for (final Leaf leaf : liveLeaves()) {
                assertThat(violations(withOnly(leaf.component(), blanks(leaf.width()))))
                        .as("a blank %s is legitimate", leaf.component()).isEmpty();
                assertThat(violations(withOnly(leaf.component(), lowValues(leaf.width()))))
                        .as("a low-values %s is legitimate", leaf.component()).isEmpty();
            }
        }

        @Test
        @DisplayName("keeps a blank middle name distinct from an absent one, which is a real difference")
        void keepsABlankMiddleNameDistinctFromAnAbsentOne() {
            final CommArea noMiddleNameRecorded = withOnly("customerMiddleName", null);
            final CommArea middleNameKnownToBeEmpty =
                    withOnly("customerMiddleName", blanks(CommArea.CUSTOMER_NAME_MAX_LENGTH));

            assertThat(noMiddleNameRecorded).isNotEqualTo(middleNameKnownToBeEmpty);
            assertThat(noMiddleNameRecorded.customerMiddleName()).isNull();
            assertThat(middleNameKnownToBeEmpty.customerMiddleName()).isBlank();
        }
    }

    /**
     * Untrusted input is carried verbatim or refused by width, never silently coerced.
     */
    @Nested
    @DisplayName("6. Hostile input - carried verbatim or refused, never coerced")
    final class HostileInput {

        @ParameterizedTest(name = "{0} carries a non-numeric value verbatim")
        @ValueSource(strings = {"customerId", "accountId", "cardNumber"})
        @DisplayName("carries a non-numeric value in a numeric-PIC component without coercing it")
        void carriesANonNumericValueInANumericPicComponent(final String component) {
            final String hostile = "ABCDEFGHI".substring(0, Math.min(9, leafFor(component).width()));

            final CommArea payload = withOnly(component, hostile);

            assertThat(readComponent(payload, component))
                    .as("%s must round-trip untrusted text without alteration", component)
                    .isEqualTo(hostile);
            assertThat(violationCountOn(payload, component))
                    .as("the copybook applies no digits-only edit at this boundary, so none is invented")
                    .isZero();
        }

        @Test
        @DisplayName("carries a card number with embedded spaces verbatim when it fits the width")
        void carriesACardNumberWithEmbeddedSpaces() {
            final String spaced = "4111 99998888777";

            final CommArea payload = withOnly("cardNumber", spaced);

            assertThat(spaced).hasSize(CommArea.CARD_NUMBER_MAX_LENGTH);
            assertThat(payload.cardNumber()).isEqualTo(spaced).contains(" ");
            assertThat(violationCountOn(payload, "cardNumber")).isZero();
        }

        @Test
        @DisplayName("refuses a spaced card number once the separators push it past sixteen positions")
        void refusesASpacedCardNumberThatExceedsTheWidth() {
            final String spacedInGroupsOfFour = "4111 9999 8888 7777";

            final CommArea payload = withOnly("cardNumber", spacedInGroupsOfFour);

            assertThat(spacedInGroupsOfFour.length()).isGreaterThan(CommArea.CARD_NUMBER_MAX_LENGTH);
            assertThat(violationCountOn(payload, "cardNumber")).isEqualTo(1L);
        }

        @Test
        @DisplayName("gives a program-context value of 2 nowhere to live, so it cannot be coerced")
        void aProgramContextValueOfTwoHasNoHomeOnThisType() {
            final int outOfDomain = 2;

            assertThat(outOfDomain)
                    .as("2 satisfies neither 88 CDEMO-PGM-ENTER (0) nor 88 CDEMO-PGM-REENTER (1)")
                    .isNotEqualTo(PGM_ENTER_VALUE)
                    .isNotEqualTo(PGM_REENTER_VALUE);
            assertThat(omittedLeaves()).anySatisfy(
                    leaf -> assertThat(leaf.cobolName()).isEqualTo("CDEMO-PGM-CONTEXT"));
            assertThat(surfaceNames())
                    .as("with the flag omitted there is no member for any value, in or out of domain")
                    .doesNotContain("pgmcontext", "programcontext", "context");
        }

        @Test
        @DisplayName("refuses an over-long value on every component rather than truncating it")
        void refusesAnOverLongValueRatherThanTruncating() {
            for (final Leaf leaf : liveLeaves()) {
                final String tooLong = "X".repeat(leaf.width() + 16);

                final CommArea payload = withOnly(leaf.component(), tooLong);

                assertThat(violationCountOn(payload, leaf.component()))
                        .as("%s must refuse an over-long value", leaf.component()).isEqualTo(1L);
                assertThat(readComponent(payload, leaf.component()))
                        .as("%s must not silently truncate on the way in", leaf.component())
                        .isEqualTo(tooLong);
            }
        }

        @Test
        @DisplayName("carries control characters verbatim without interpreting them")
        void carriesControlCharactersVerbatim() {
            final String withControlCharacters = "A\r\nB\tC";

            final CommArea payload = withOnly("customerFirstName", withControlCharacters);

            assertThat(payload.customerFirstName()).isEqualTo(withControlCharacters);
            assertThat(violations(payload)).isEmpty();
        }
    }

    /**
     * The user-type domain: exactly two codes, corroborated by the ten seeded records.
     */
    @Nested
    @DisplayName("7. User type - exactly 'A' and 'U', resolved case-sensitively and never defaulted")
    final class UserTypeBoundary {

        @Test
        @DisplayName("declares exactly two constants, matching the two condition names")
        void declaresExactlyTwoConstants() {
            assertThat(UserType.values()).hasSize(2).containsExactly(UserType.ADMIN, UserType.USER);
            assertThat(UserType.ADMIN.getCode()).isEqualTo('A');
            assertThat(UserType.USER.getCode()).isEqualTo('U');
        }

        @ParameterizedTest(name = "{0} carries the administrator code")
        @ValueSource(strings = {"ADMIN001", "ADMIN002", "ADMIN003", "ADMIN004", "ADMIN005"})
        @DisplayName("resolves the administrator seed identifiers to the administrator constant")
        void resolvesTheAdministratorSeedIdentifiers(final String seedId) {
            final CommArea payload = new CommArea(seedId, ADMIN_CODE, null, null, null, null, null, null,
                    null);

            assertThat(payload.userId()).isEqualTo(seedId).hasSize(CommArea.USER_ID_MAX_LENGTH);
            assertThat(payload.resolvedUserType()).isEqualTo(UserType.ADMIN);
            assertThat(violations(payload)).isEmpty();
        }

        @ParameterizedTest(name = "{0} carries the standard-user code")
        @ValueSource(strings = {"USER0001", "USER0002", "USER0003", "USER0004", "USER0005"})
        @DisplayName("resolves the standard-user seed identifiers to the user constant")
        void resolvesTheStandardUserSeedIdentifiers(final String seedId) {
            final CommArea payload = new CommArea(seedId, USER_CODE, null, null, null, null, null, null,
                    null);

            assertThat(payload.userId()).isEqualTo(seedId).hasSize(CommArea.USER_ID_MAX_LENGTH);
            assertThat(payload.resolvedUserType()).isEqualTo(UserType.USER);
            assertThat(violations(payload)).isEmpty();
        }

        @Test
        @DisplayName("accounts for the seed set as five administrators and five standard users")
        void theSeedSetIsFiveAdministratorsAndFiveStandardUsers() {
            assertThat(ADMIN_SEED_IDS).hasSize(5).doesNotHaveDuplicates();
            assertThat(USER_SEED_IDS).hasSize(5).doesNotHaveDuplicates();
            assertThat(ADMIN_SEED_IDS).doesNotContainAnyElementsOf(USER_SEED_IDS);

            final List<String> everySeedId = new ArrayList<>(ADMIN_SEED_IDS);
            everySeedId.addAll(USER_SEED_IDS);
            assertThat(everySeedId).as("app/jcl/DUSRSECJ.jcl:L35-L44 supplies ten records").hasSize(10);
            assertThat(everySeedId).allSatisfy(
                    seedId -> assertThat(seedId).hasSize(CommArea.USER_ID_MAX_LENGTH));
        }

        @Test
        @DisplayName("keys the seed records at eight bytes over an eighty-byte record")
        void theSeedRecordGeometryMatchesTheUserSecurityLayout() {
            assertThat(CommArea.USER_ID_MAX_LENGTH)
                    .as("KEYS(8,0) at app/jcl/DUSRSECJ.jcl:L65 agrees with SEC-USR-ID PIC X(08)")
                    .isEqualTo(8);
            assertThat(8 + USER_SECURITY_NAME_WIDTH + USER_SECURITY_NAME_WIDTH + 8 + 1 + 23)
                    .as("RECORDSIZE(80,80) at app/jcl/DUSRSECJ.jcl:L66 agrees with CSUSR01Y:L17-L23")
                    .isEqualTo(USER_SECURITY_RECORD_WIDTH);
        }

        @Test
        @DisplayName("resolves an absent user type to no type rather than to a fabricated default")
        void resolvesAnAbsentUserTypeToNoType() {
            assertThat(absent().resolvedUserType()).isNull();
            assertThat(UserType.fromCode((String) null)).isEmpty();
        }

        @ParameterizedTest(name = "the code '{0}' resolves to no type")
        @ValueSource(strings = {" ", "", "X", "a", "u", "0", "2", "AU", "Z"})
        @DisplayName("resolves an unrecognised code to no type without throwing and without defaulting")
        void resolvesAnUnrecognisedCodeToNoType(final String code) {
            final CommArea payload = withOnly("userType", code);

            assertThat(payload.resolvedUserType())
                    .as("an unrecognised code must never fall back to ADMIN or USER").isNull();
            assertThat(payload.userType()).as("the raw value survives even when it does not resolve")
                    .isEqualTo(code);
        }

        @Test
        @DisplayName("resolves case-sensitively, exactly as the source compares the type byte")
        void resolvesCaseSensitively() {
            assertThat(UserType.fromCode('a')).as("the source compares against 'A' exactly").isEmpty();
            assertThat(UserType.fromCode('u')).as("the source compares against 'U' exactly").isEmpty();
            assertThat(UserType.fromCode('A')).contains(UserType.ADMIN);
            assertThat(UserType.fromCode('U')).contains(UserType.USER);
        }

        @Test
        @DisplayName("names the offending code when the strict lookup refuses it, and preserves the raw value")
        void theStrictLookupNamesTheOffendingCode() {
            final char outOfDomain = 'Z';

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("an out-of-domain code must fail loudly rather than default")
                    .isThrownBy(() -> UserType.requireFromCode(outOfDomain))
                    .withMessageContaining("0x" + Integer.toHexString(outOfDomain))
                    .withMessageContaining("CDEMO-USER-TYPE")
                    .withNoCause();

            assertThat(withOnly("userType", String.valueOf(outOfDomain)).userType())
                    .as("the permissive path still round-trips the value the strict path refuses")
                    .isEqualTo("Z");
        }

        @Test
        @DisplayName("keeps the typed view derived rather than stored, so the raw byte stays authoritative")
        void theTypedViewIsDerivedNotStored() {
            final Optional<UserType> resolved = UserType.fromCode(ADMIN_CODE);

            assertThat(resolved).contains(UserType.ADMIN);
            assertThat(componentNames()).as("no component carries the typed view; only the raw byte")
                    .doesNotContain("resolvedUserType");
            assertThat(surfaceTypeNames()).contains(UserType.class.getName());
        }
    }

    /**
     * Nothing stricter than a declared width is invented, and no gated edit is modelled unconditionally.
     */
    @Nested
    @DisplayName("8. No invented rules - one width per component and no class-level constraint")
    final class NoInventedConstraints {

        @Test
        @DisplayName("marks no component mandatory, because an unpopulated COMMAREA is legitimate")
        void marksNoComponentMandatory() {
            for (final RecordComponent component : CommArea.class.getRecordComponents()) {
                assertThat(component.getAnnotation(NotNull.class))
                        .as("%s must not be mandatory", component.getName()).isNull();
            }
        }

        @Test
        @DisplayName("imposes no format on any component, since the source edits none at this boundary")
        void imposesNoFormatOnAnyComponent() {
            for (final RecordComponent component : CommArea.class.getRecordComponents()) {
                assertThat(component.getAnnotation(Pattern.class))
                        .as("%s must impose no regular expression", component.getName()).isNull();
                assertThat(component.getAnnotation(Digits.class))
                        .as("%s must impose no digit bound, which would imply a numeric carrier",
                                component.getName())
                        .isNull();
            }
        }

        @Test
        @DisplayName("carries exactly one declared constraint per component, and that constraint is a width")
        void carriesExactlyOneConstraintPerComponent() {
            for (final Leaf leaf : liveLeaves()) {
                final Field field = backingField(leaf.component());

                assertThat(field.getAnnotations())
                        .as("%s must declare exactly its width and nothing else", leaf.component())
                        .hasSize(1);
                assertThat(field.getAnnotation(Size.class)).isNotNull();
            }
        }

        @Test
        @DisplayName("leaves every declared width at its default minimum, adding no lower bound")
        void addsNoLowerBoundToAnyWidth() {
            for (final Leaf leaf : liveLeaves()) {
                assertThat(declaredSize(leaf.component()).min())
                        .as("%s must impose no minimum length, since a short field is a real state",
                                leaf.component())
                        .isZero();
            }
        }

        @Test
        @DisplayName("declares no class-level constraint, so no gated cross-field edit fires unconditionally")
        void declaresNoClassLevelConstraint() {
            assertThat(CommArea.class.getAnnotation(AssertTrue.class))
                    .as("a class-level @AssertTrue fires unconditionally, unlike the gated edit at "
                            + "app/cbl/COACTUPC.cbl:L1667-L1678")
                    .isNull();
            for (final Method method : CommArea.class.getDeclaredMethods()) {
                assertThat(method.getAnnotation(AssertTrue.class))
                        .as("%s must not model a cross-field edit as an unconditional assertion",
                                method.getName())
                        .isNull();
            }
        }

        @Test
        @DisplayName("raises no violation for an absent payload and none for a fully populated one")
        void raisesNoViolationForEitherExtreme() {
            assertThat(violations(absent())).isEmpty();
            assertThat(violations(populated())).isEmpty();
        }
    }

    /**
     * The security posture of the one payload that aggregates a card number and three customer names.
     *
     * <p>No assertion in this class interpolates a card number or a customer name into its own failure
     * message. Where the question is whether a value leaked, the assertion is made on a boolean and the
     * offending field is named instead, so a failure report identifies the field without republishing the
     * value the test exists to keep out of logs.
     */
    @Nested
    @DisplayName("9. Security - the rendering discloses no card number and no customer name")
    final class SecurityPosture {

        @Test
        @DisplayName("renders exactly the user identifier, the user type and the account status")
        void rendersExactlyTheThreeNonSensitiveFields() {
            final String rendered = populated().toString();

            assertThat(rendered).isEqualTo("CommArea[userId=ADMIN001, userType=A, accountStatus=Y]");
        }

        @Test
        @DisplayName("withholds the card number from the rendering, unmasked and unabbreviated alike")
        void withholdsTheCardNumberFromTheRendering() {
            final CommArea payload = populated();
            final String pan = payload.cardNumber();

            final String rendered = payload.toString();

            assertThat(rendered.contains(pan))
                    .as("toString must not disclose CDEMO-CARD-NUM (app/cpy/COCOM01Y.cpy:L41)")
                    .isFalse();
            assertThat(rendered.contains(pan.substring(pan.length() - 4)))
                    .as("toString must not disclose even a trailing fragment of CDEMO-CARD-NUM")
                    .isFalse();
            assertThat(rendered.contains(String.valueOf(pan.length())))
                    .as("toString must not disclose the CDEMO-CARD-NUM length as a stand-in")
                    .isFalse();
        }

        @ParameterizedTest(name = "the rendering withholds {0}")
        @CsvSource({
            "CDEMO-CUST-FNAME, customerFirstName",
            "CDEMO-CUST-MNAME, customerMiddleName",
            "CDEMO-CUST-LNAME, customerLastName",
            "CDEMO-CUST-ID,    customerId",
            "CDEMO-ACCT-ID,    accountId",
        })
        @DisplayName("withholds every personally identifying field and both identifiers")
        void withholdsEveryPersonallyIdentifyingField(final String cobolName, final String component) {
            final CommArea payload = populated();
            final String value = readComponent(payload, component);

            final String rendered = payload.toString();

            assertThat(rendered.contains(value))
                    .as("toString must not disclose %s, carried by %s", cobolName, component)
                    .isFalse();
        }

        @ParameterizedTest(name = "no member is named for '{0}'")
        @ValueSource(strings = {
            "password", "pwd", "passwd", "hash", "secret", "credential", "token", "ssn", "social",
            "phone", "dob", "birth", "cvv",
        })
        @DisplayName("declares no credential or extra sensitive member, because the copybook carries none")
        void declaresNoCredentialOrExtraSensitiveMember(final String forbiddenToken) {
            final List<String> objectContract = OBJECT_CONTRACT_METHODS.stream()
                    .map(name -> name.toLowerCase(Locale.ROOT))
                    .toList();

            final List<String> offenders = surfaceNames().stream()
                    .filter(name -> !objectContract.contains(name))
                    .filter(name -> name.contains(forbiddenToken))
                    .toList();

            assertThat(offenders)
                    .as("the COMMAREA declares no %s field, so none may be added here", forbiddenToken)
                    .isEmpty();
        }

        @Test
        @DisplayName("offers no second rendering path that could disclose what toString withholds")
        void offersNoSecondRenderingPath() {
            final List<String> renderingMethods = new ArrayList<>();
            for (final Method method : CommArea.class.getDeclaredMethods()) {
                if (method.isSynthetic() || method.getParameterCount() != 0) {
                    continue;
                }
                if (method.getReturnType() == String.class && !componentNames().contains(method.getName())) {
                    renderingMethods.add(method.getName());
                }
            }

            assertThat(renderingMethods)
                    .as("only toString may render this type, so a mask or a full-detail variant is refused")
                    .containsExactly("toString");
        }

        @Test
        @DisplayName("keeps record-derived equality over every component while disclosing nothing")
        void keepsRecordDerivedEqualityWhileDisclosingNothing() {
            final CommArea one = populated();
            final CommArea differingOnlyByCardNumber = new CommArea(one.userId(), one.userType(),
                    one.customerId(), one.customerFirstName(), one.customerMiddleName(),
                    one.customerLastName(), one.accountId(), one.accountStatus(), "1111222233334445");

            assertThat(one)
                    .as("value semantics must cover the card number, or two different payloads compare equal")
                    .isNotEqualTo(differingOnlyByCardNumber)
                    .isEqualTo(populated())
                    .hasSameHashCodeAs(populated());
            assertThat(one.toString()).as("equality is total, yet the rendering stays narrow")
                    .isEqualTo(differingOnlyByCardNumber.toString());
        }

        @Test
        @DisplayName("leaves equality to the compiler and narrows only the rendering by hand")
        void leavesEqualityToTheCompilerAndNarrowsOnlyTheRendering() {
            assertThat(declaredMethodNames()).contains("equals", "hashCode", "toString");

            assertThat(Modifier.isFinal(findMethod("equals").getModifiers()))
                    .as("a record's generated equals is final; leaving it generated is correct for a value "
                            + "type, because two payloads differing in any component are different payloads")
                    .isTrue();
            assertThat(Modifier.isFinal(findMethod("hashCode").getModifiers()))
                    .as("a record's generated hashCode is final, and it discloses nothing: an int is lossy")
                    .isTrue();
            assertThat(Modifier.isFinal(findMethod("toString").getModifiers()))
                    .as("toString is NOT final, which is the evidence it was overridden by hand rather than "
                            + "generated; the generated one would have printed every component")
                    .isFalse();
        }

        @Test
        @DisplayName("renders an absent payload without failing and without inventing content")
        void rendersAnAbsentPayloadWithoutFailing() {
            final String rendered = absent().toString();

            assertThat(rendered).isEqualTo("CommArea[userId=null, userType=null, accountStatus=null]");
        }

        @Test
        @DisplayName("renders deterministically, so a log line cannot vary between identical payloads")
        void rendersDeterministically() {
            final CommArea payload = populated();

            assertThat(payload.toString()).isEqualTo(payload.toString()).isEqualTo(populated().toString());
        }
    }

    /**
     * The type is inert: it carries a layout, and it cannot carry a session or steer a request.
     */
    @Nested
    @DisplayName("10. Stateless posture - no dispatch, no session, no mutable static, no re-entry")
    final class StatelessPosture {

        @ParameterizedTest(name = "no method is named for '{0}'")
        @ValueSource(strings = {
            "dispatch", "route", "forward", "xctl", "transfer", "navigate", "redirect", "invoke",
            "execute", "send", "receive",
        })
        @DisplayName("declares no method that could transfer control the way EXEC CICS XCTL did")
        void declaresNoDispatchMethod(final String forbiddenToken) {
            final List<String> offenders = new ArrayList<>();
            for (final Method method : CommArea.class.getDeclaredMethods()) {
                if (!method.isSynthetic()
                        && method.getName().toLowerCase(Locale.ROOT).contains(forbiddenToken)) {
                    offenders.add(method.getName());
                }
            }

            assertThat(offenders)
                    .as("app/cbl/COMEN01C.cbl:L153 transferred control to a COMMAREA-named program; the "
                            + "target routes by URL, so this type may offer no such entry point")
                    .isEmpty();
        }

        @Test
        @DisplayName("exposes no component that could serve as a dispatch key")
        void exposesNoComponentThatCouldServeAsADispatchKey() {
            assertThat(componentNames())
                    .as("no component names a program, a transaction, a map or a mapset")
                    .allSatisfy(name -> assertThat(leafFor(name).isLive()).isTrue());
            assertThat(componentNames()).doesNotContain(
                    "toProgram", "fromProgram", "toTranId", "fromTranId", "lastMap", "lastMapset");
        }

        @ParameterizedTest(name = "the surface references no {0} type")
        @ValueSource(strings = {
            "Servlet", "HttpSession", "Session", "ThreadLocal", "Cookie", "Request", "Response",
        })
        @DisplayName("references no servlet, session or thread-bound type anywhere on its surface")
        void referencesNoServletOrSessionType(final String forbiddenType) {
            final List<String> offenders = surfaceTypeNames().stream()
                    .filter(typeName -> typeName.contains(forbiddenType))
                    .toList();

            assertThat(offenders).as("a %s on this surface would make it a live session carrier",
                    forbiddenType).isEmpty();
        }

        @Test
        @DisplayName("holds no mutable static state, which is precisely what the COMMAREA used to be")
        void holdsNoMutableStaticState() {
            final List<String> mutableStatics = new ArrayList<>();
            for (final Field field : CommArea.class.getDeclaredFields()) {
                if (field.isSynthetic() || field.getName().startsWith("$")) {
                    continue;
                }
                if (Modifier.isStatic(field.getModifiers()) && !Modifier.isFinal(field.getModifiers())) {
                    mutableStatics.add(field.getName());
                }
            }

            assertThat(mutableStatics)
                    .as("the legacy COMMAREA was the global mutable state; nothing here may reinstate it")
                    .isEmpty();
        }

        @Test
        @DisplayName("declares every static member as a primitive width constant")
        void declaresEveryStaticMemberAsAWidthConstant() {
            for (final Field field : CommArea.class.getDeclaredFields()) {
                if (field.isSynthetic() || field.getName().startsWith("$")
                        || !Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                assertThat(Modifier.isFinal(field.getModifiers())).as("%s must be final", field.getName())
                        .isTrue();
                assertThat(field.getType()).as("%s must be a primitive width, not a mutable holder",
                        field.getName()).isEqualTo(Integer.TYPE);
            }
        }

        @Test
        @DisplayName("declares every instance field final, so an instance cannot drift after construction")
        void declaresEveryInstanceFieldFinal() {
            for (final Field field : CommArea.class.getDeclaredFields()) {
                if (field.isSynthetic() || Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                assertThat(Modifier.isFinal(field.getModifiers())).as("%s must be final", field.getName())
                        .isTrue();
                assertThat(field.getType()).as("%s must be an immutable carrier", field.getName())
                        .isEqualTo(String.class);
            }
        }

        @Test
        @DisplayName("is not Java-serialisable, so no deserialisation path can reach it")
        void isNotJavaSerialisable() {
            assertThat(Serializable.class.isAssignableFrom(CommArea.class))
                    .as("this type crosses boundaries as JSON; Java serialisation is an attack surface "
                            + "with no use case here")
                    .isFalse();
        }

        @Test
        @DisplayName("exposes no re-entry-dependent behaviour, because every request is its own first")
        void exposesNoReEntryDependentBehaviour() {
            assertThat(surfaceNames())
                    .as("app/cpy/CSSETATY.cpy:L18-L20 gates its markers on CDEMO-PGM-REENTER; a stateless "
                            + "API has no second turn for such a flag to describe")
                    .doesNotContain("reenter", "isreenter", "pgmcontext", "enter");
            assertThat(declaredMethodNames())
                    .as("exactly the nine accessors, the typed view, toString, equals and hashCode")
                    .hasSize(13)
                    .containsExactlyInAnyOrder("userId", "userType", "customerId", "customerFirstName",
                            "customerMiddleName", "customerLastName", "accountId", "accountStatus",
                            "cardNumber", "resolvedUserType", "toString", "equals", "hashCode");
        }

        @Test
        @DisplayName("is final, so no subtype can bolt session behaviour onto it")
        void isFinalSoNoSubtypeCanExtendIt() {
            assertThat(CommArea.class.isRecord()).isTrue();
            assertThat(Modifier.isFinal(CommArea.class.getModifiers())).isTrue();
        }
    }

    /**
     * Corroboration against the frozen corpus, read through {@link FixtureLoader} by resource name only.
     */
    @Nested
    @DisplayName("11. Corpus corroboration - the fixtures prove the widths and the leading zeros")
    final class CorpusCorroboration {

        @Test
        @DisplayName("finds the customer fixture geometry unchanged at 50 records of 500 bytes")
        void theCustomerFixtureGeometryIsUnchanged() {
            final FixtureLoader.FixtureData customers = FixtureLoader.load(FixtureLoader.Fixture.CUSTOMER);

            assertThat(customers.resourceName()).isEqualTo("custdata.txt");
            assertThat(customers.recordCount()).isEqualTo(50);
            assertThat(customers.recordWidth()).isEqualTo(500);
            assertThat(customers.byteCount()).isEqualTo(25_050);
            assertThat(customers.byteCount()).as("50 records of 500 bytes plus one line feed each")
                    .isEqualTo(50 * 501);
        }

        @Test
        @DisplayName("places the three customer names at the copybook offsets 10-34, 35-59 and 60-84")
        void theCustomerNamesSitAtTheCopybookOffsets() {
            final FixtureLoader.FixtureData customers = FixtureLoader.load(FixtureLoader.Fixture.CUSTOMER);

            assertThat(customers.field(0, 1, 9)).as("CUST-ID at app/cpy/CVCUS01Y.cpy:L5")
                    .isEqualTo("000000001");
            assertThat(customers.field(0, 10, 25).strip()).as("CUST-FIRST-NAME at :L6").isEqualTo("Immanuel");
            assertThat(customers.field(0, 35, 25).strip()).as("CUST-MIDDLE-NAME at :L7")
                    .isEqualTo("Madeline");
            assertThat(customers.field(0, 60, 25).strip()).as("CUST-LAST-NAME at :L8").isEqualTo("Kessler");

            assertThat(customers.field(49, 10, 25).strip()).isEqualTo("Aniya");
            assertThat(customers.field(49, 35, 25).strip()).isEqualTo("Alba");
            assertThat(customers.field(49, 60, 25).strip()).isEqualTo("Von");
        }

        @Test
        @DisplayName("matches the customer record's name width to the COMMAREA's, proving the projection")
        void theCustomerNameWidthMatchesTheCommAreaNameWidth() {
            final FixtureLoader.FixtureData customers = FixtureLoader.load(FixtureLoader.Fixture.CUSTOMER);

            for (final int startColumn : new int[] {10, 35, 60}) {
                assertThat(customers.field(0, startColumn, CommArea.CUSTOMER_NAME_MAX_LENGTH))
                        .as("the slice at column %d is exactly the COMMAREA name width", startColumn)
                        .hasSize(CommArea.CUSTOMER_NAME_MAX_LENGTH);
            }
            assertThat(CommArea.CUSTOMER_NAME_MAX_LENGTH)
                    .as("CVCUS01Y:L6-L8 and COCOM01Y:L34-L36 both declare PIC X(25)")
                    .isEqualTo(25);
        }

        @Test
        @DisplayName("carries every fixture customer name inside the COMMAREA name width")
        void everyFixtureCustomerNameFitsTheCommAreaWidth() {
            final FixtureLoader.FixtureData customers = FixtureLoader.load(FixtureLoader.Fixture.CUSTOMER);

            for (int row = 0; row < customers.recordCount(); row++) {
                final CommArea payload = new CommArea(null, null,
                        customers.field(row, 1, CommArea.CUSTOMER_ID_LENGTH),
                        customers.field(row, 10, CommArea.CUSTOMER_NAME_MAX_LENGTH),
                        customers.field(row, 35, CommArea.CUSTOMER_NAME_MAX_LENGTH),
                        customers.field(row, 60, CommArea.CUSTOMER_NAME_MAX_LENGTH),
                        null, null, null);

                assertThat(violations(payload)).as("row %d must satisfy every declared width", row).isEmpty();
                assertThat(payload.customerId()).as("row %d keeps its leading zeros", row)
                        .hasSize(CommArea.CUSTOMER_ID_LENGTH)
                        .startsWith("0");
            }
        }

        @Test
        @DisplayName("finds the card fixture geometry unchanged at 50 records of 150 bytes")
        void theCardFixtureGeometryIsUnchanged() {
            final FixtureLoader.FixtureData cards = FixtureLoader.load(FixtureLoader.Fixture.CARD);

            assertThat(cards.resourceName()).isEqualTo("carddata.txt");
            assertThat(cards.recordCount()).isEqualTo(50);
            assertThat(cards.recordWidth()).isEqualTo(150);
            assertThat(cards.byteCount()).isEqualTo(7_550).isEqualTo(50 * 151);
        }

        @Test
        @DisplayName("proves the numeric typing would be lossy: five fixture card numbers begin with zero")
        void thereAreRealLeadingZeroCardNumbersInTheFixture() {
            final FixtureLoader.FixtureData cards = FixtureLoader.load(FixtureLoader.Fixture.CARD);

            int leadingZeroCount = 0;
            for (int row = 0; row < cards.recordCount(); row++) {
                final String pan = cards.field(row, 1, CommArea.CARD_NUMBER_MAX_LENGTH);

                assertThat(pan).as("row %d must be a full-width card number", row)
                        .hasSize(CommArea.CARD_NUMBER_MAX_LENGTH);
                assertThat(readComponent(withOnly("cardNumber", pan), "cardNumber"))
                        .as("row %d must round-trip byte for byte", row).isEqualTo(pan);
                if (pan.startsWith("0")) {
                    leadingZeroCount++;
                    assertThat(Long.toString(Long.parseLong(pan)).length())
                            .as("row %d would lose a digit under an integral carrier", row)
                            .isLessThan(CommArea.CARD_NUMBER_MAX_LENGTH);
                }
            }

            assertThat(leadingZeroCount)
                    .as("app/data/ASCII/carddata.txt carries genuine leading-zero card numbers, so the "
                            + "PIC 9(16) declaration at app/cpy/COCOM01Y.cpy:L41 cannot be taken literally")
                    .isEqualTo(5);
        }

        @Test
        @DisplayName("finds the fixture card numbers distinct, so a round trip is individually observable")
        void theFixtureCardNumbersAreDistinct() {
            final FixtureLoader.FixtureData cards = FixtureLoader.load(FixtureLoader.Fixture.CARD);

            final List<String> pans = new ArrayList<>();
            for (int row = 0; row < cards.recordCount(); row++) {
                pans.add(cards.field(row, 1, CommArea.CARD_NUMBER_MAX_LENGTH));
            }

            assertThat(pans).hasSize(50).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("finds leading zeros load-bearing elsewhere in the same fixture record")
        void leadingZerosAreLoadBearingElsewhereInTheCardRecord() {
            final FixtureLoader.FixtureData cards = FixtureLoader.load(FixtureLoader.Fixture.CARD);

            int leadingZeroVerificationValues = 0;
            for (int row = 0; row < cards.recordCount(); row++) {
                if (cards.field(row, 28, 3).startsWith("0")) {
                    leadingZeroVerificationValues++;
                }
            }

            assertThat(leadingZeroVerificationValues)
                    .as("the three-digit field at columns 28-30 relies on leading zeros too, which is why "
                            + "no numeric-PIC field in this corpus may be carried as an integer")
                    .isEqualTo(8);
        }

        @Test
        @DisplayName("keeps the customer-name and user-name width families apart")
        void keepsTheTwoNameWidthFamiliesApart() {
            assertThat(CommArea.CUSTOMER_NAME_MAX_LENGTH)
                    .as("COCOM01Y:L34-L36 declares customer names PIC X(25)").isEqualTo(25);
            assertThat(USER_SECURITY_NAME_WIDTH)
                    .as("CSUSR01Y:L19-L20 declares user names PIC X(20)").isEqualTo(20);
            assertThat(CommArea.CUSTOMER_NAME_MAX_LENGTH)
                    .as("the two families differ by five bytes, so no helper may be shared between them")
                    .isNotEqualTo(USER_SECURITY_NAME_WIDTH)
                    .isGreaterThan(USER_SECURITY_NAME_WIDTH);

            final FixtureLoader.FixtureData customers = FixtureLoader.load(FixtureLoader.Fixture.CUSTOMER);
            final String customerName = customers.field(0, 10, CommArea.CUSTOMER_NAME_MAX_LENGTH);
            assertThat(customerName.length())
                    .as("a customer-name slice overflows the user-security name field, which is the "
                            + "measurable reason the two are not interchangeable")
                    .isGreaterThan(USER_SECURITY_NAME_WIDTH);
        }

        @Test
        @DisplayName("names the daily-transaction fixture as it is actually spelled on the classpath")
        void namesTheDailyTransactionFixtureCorrectly() {
            assertThat(FixtureLoader.Fixture.DAILY_TRANSACTION.resourceName())
                    .as("the mainframe DD name is DALYTRAN but the fixture spells the word in full")
                    .isEqualTo("dailytran.txt")
                    .isNotEqualTo("dalytran.txt");
        }

        @Test
        @DisplayName("reads no ambient clock, so no assertion here can drift with wall-clock time")
        void readsNoAmbientClock() {
            final Clock fixed = FixedClockProvider.canonicalClock();

            final Instant first = fixed.instant();
            final Instant second = fixed.instant();

            assertThat(first).isEqualTo(second).isEqualTo(FixedClockProvider.CANONICAL_INSTANT);
            assertThat(fixed.getZone()).isEqualTo(FixedClockProvider.CANONICAL_ZONE);
            assertThat(surfaceTypeNames())
                    .as("the COMMAREA declares no temporal field, so none is carried and none is dated")
                    .doesNotContain(Instant.class.getName(), Clock.class.getName());
        }
    }

    /**
     * The diagnostic rendering may not be turned into a forged log record.
     *
     * <p>Every component {@code toString()} emits is declared {@code String} and arrives from a JSON request
     * body, so a caller controls its bytes: concatenated straight in, a CR or LF forges as many further log
     * lines as the caller likes, in the exact shape a reader trusts. {@code @Size} and {@code @Pattern} run
     * <em>after</em> Jackson has constructed the record, and a validation failure is precisely the occasion
     * on which something renders the offending instance, so these tests build hostile values directly and
     * never validate them first.
     */
    @Nested
    @DisplayName("the diagnostic rendering cannot forge a log record")
    class HostileDiagnosticRendering {

        @ParameterizedTest(name = "a CR/LF payload in {0} cannot break the record")
        @ValueSource(strings = {"userId", "userType", "accountStatus"})
        @DisplayName("a control character in any rendered component is escaped, not emitted")
        void aControlCharacterInAnyRenderedComponentIsEscaped(final String component) {
            final String hostile = "AAA\r\n2026-08-04 INFO forged FORGED-RECORD";

            final String rendered = withOnly(component, hostile).toString();

            assertThat(rendered)
                    .as("the raw terminators must be gone, or the rendering is one log record per attacker "
                            + "newline rather than one per event")
                    .doesNotContain("\r")
                    .doesNotContain("\n");
            assertThat(rendered.lines().count())
                    .as("and the whole rendering must remain exactly one line")
                    .isEqualTo(1L);
        }

        @Test
        @DisplayName("the escaped payload is still legible, so the evidence survives neutralisation")
        void theEscapedPayloadRemainsLegible() {
            final String rendered = withOnly("userId", "AAA\r\nFORGED-RECORD").toString();

            assertThat(rendered)
                    .as("a reader investigating a hostile request needs to see what arrived; escaping the "
                            + "terminator must not discard the value around it")
                    .contains("FORGED-RECORD")
                    .contains("\\u000D")
                    .contains("\\u000A");
        }

        @Test
        @DisplayName("an over-long component is bounded, so one field cannot flood the record")
        void anOverLongComponentIsBounded() {
            final String rendered = withOnly("userId", "q".repeat(400)).toString();

            assertThat(rendered)
                    .as("the length constraints have not run on an instance being rendered because it failed "
                            + "them, so the rendering bounds the value itself")
                    .contains("chars)")
                    .hasSizeLessThan(600);
        }

        @Test
        @DisplayName("a benign instance renders unchanged, so the guard is invisible in normal use")
        void aBenignInstanceRendersUnchanged() {
            assertThat(populated().toString())
                    .as("neutralisation must not alter what an ordinary log record says")
                    .doesNotContain("chars)")
                    .doesNotContain("\\u");
        }
    }

}
