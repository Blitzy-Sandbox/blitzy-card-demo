/*
 * ******************************************************************
 * Program     : ReportRequestTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - pure JVM tier, no container
 * Function    : Locks the 17 field contract of the report submission
 *               request and the observable outcomes the CICS report
 *               screen produces, so that a stateless Java replacement
 *               cannot silently diverge from the frozen corpus.
 * Source      : app/cpy-bms/CORPT00.CPY (17 input fields, group
 *               CORPT0AI) + app/cbl/CORPT00C.cbl @ 7756d89
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cardemo.model.dto.ReportRequest;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
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
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.Temporal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for {@link ReportRequest}, the stateless replacement for the BMS conversation of CICS
 * transaction {@code CR00} - program {@code app/cbl/CORPT00C.cbl}, 649 lines - whose screen is
 * described by the symbolic map {@code app/cpy-bms/CORPT00.CPY}, input group {@code CORPT0AI}.
 *
 * <h2>1. What this class does</h2>
 *
 * <p>{@link ReportRequest} is a 17 component immutable {@code record} that transports raw screen
 * values and performs no arithmetic, no assembly, no validation and no publication. A test of a type
 * that computes nothing could only assert accessors, which would be worthless padding. This class
 * therefore does two distinct things, and keeps them visibly apart:</p>
 *
 * <ol>
 *   <li><strong>It locks the field contract.</strong> Component count, order, type and byte exact
 *       declared width are asserted by reflection against {@code app/cpy-bms/CORPT00.CPY}, so a
 *       widened, narrowed, merged, reordered, retyped or added component fails immediately.</li>
 *   <li><strong>It locks the observable outcomes the 17 components must be able to carry</strong>, by
 *       transliterating the source paragraphs into <em>reference oracles</em> - small private static
 *       functions, one per paragraph, each carrying its {@code app/cbl/CORPT00C.cbl} locator. Every
 *       oracle consumes nothing but this record's own components and a fixed clock. Together they are
 *       the executable specification the service tier must reproduce, and the proof that the 17
 *       components are <em>sufficient</em> to express every outcome the screen produces.</li>
 * </ol>
 *
 * <p>The oracles are deliberately <strong>not</strong> a service. They are declared private, they
 * hold no state, they touch no collaborator and they are never exported. {@code ReportSubmissionService}
 * owns the behaviour; this file owns the contract that behaviour must satisfy.</p>
 *
 * <h2>2. Evidence: every locator this class asserts against</h2>
 *
 * <p><strong>Field contract.</strong> {@code app/cpy-bms/CORPT00.CPY} lines 24, 30, 36, 42, 48, 54,
 * 60, 66, 72, 78, 84, 90, 96, 102, 108, 114 and 120 - the 17 data fields of {@code CORPT0AI}, whose
 * group opens at line 17 behind a twelve byte terminal I/O area {@code FILLER PIC X(12)} at line 18
 * and closes where {@code CORPT0AO REDEFINES CORPT0AI} begins at line 121. Each screen field is
 * generated as a quintuple - a {@code COMP PIC S9(4)} length halfword, an attribute byte, a redefined
 * attribute alias, four reserved bytes, then the data field - and only the trailing data field is a
 * DTO component. Every data field is spelled {@code PIC}; the {@code PICTURE} spelling occurs only on
 * the quintuple members that are not modelled. {@code app/cpy-bms/COSGN00.CPY:54} is the one map that
 * declares {@code CURTIMEI PIC X(9)} where this one declares {@code PIC X(8)}.</p>
 *
 * <p><strong>Working storage.</strong> {@code app/cbl/CORPT00C.cbl:L58} declares
 * {@code WS-REPORT-NAME PIC X(10)}; {@code :L60-L71} declare the two assembled dates as groups with
 * literal dash fillers; {@code :L72} declares {@code WS-DATE-FORMAT PIC X(10) VALUE 'YYYY-MM-DD'};
 * {@code :L39} declares {@code WS-MESSAGE PIC X(80)}, two bytes wider than the {@code X(78)} screen
 * field it is moved into.</p>
 *
 * <p><strong>The job deck.</strong> {@code :L82} opens {@code 02 JOB-DATA-1}; {@code :L126-L127}
 * redefine it as {@code JOB-LINES OCCURS 1000 TIMES PIC X(80)}; {@code :L103-L107},
 * {@code :L108-L112} and {@code :L117-L121} are the three named multi part cards that carry the dates,
 * summing to 18+10+52, 16+10+54 and 10+1+10+59 - eighty bytes each.</p>
 *
 * <p><strong>The date validation linkage.</strong> {@code :L129-L136} declares
 * {@code CSUTLDTC-PARM}; {@code :L392-L394} and {@code :L412-L414} issue {@code CALL 'CSUTLDTC'};
 * {@code :L396-L406} and {@code :L416-L426} interpret the result.</p>
 *
 * <p><strong>Behaviour.</strong> {@code :L212-L442} is the period cascade - monthly {@code :L213-L238},
 * yearly {@code :L239-L255}, custom {@code :L256-L436}, none {@code :L437-L442}. Custom range editing
 * runs in three tiers: emptiness {@code :L258-L303}, numeric normalisation {@code :L305-L327}, per
 * component validity {@code :L329-L379}, then assembly {@code :L381-L386} and whole date validity
 * {@code :L388-L426}. {@code :L429-L433} injects both dates and names the period.
 * {@code :L445-L454} builds the success message. {@code :L462-L510} is the confirmation handshake and
 * submission loop, {@code :L498-L508} the loop itself. {@code :L515-L537} is the queue write
 * paragraph, whose name {@code WIRTE-JOBSUB-TDQ} is misspelled in the source and is cited verbatim.
 * {@code :L633-L646} is {@code INITIALIZE-ALL-FIELDS}.</p>
 *
 * <p><strong>The mechanism that makes the cascade first match wins.</strong>
 * {@code SEND-TRNRPT-SCREEN} at {@code :L556-L578} ends with {@code GO TO RETURN-TO-CICS.} at
 * {@code :L580}, and {@code RETURN-TO-CICS} at {@code :L585-L591} issues {@code EXEC CICS RETURN}.
 * A {@code PERFORM SEND-TRNRPT-SCREEN} therefore <strong>never returns</strong> - the task ends. That
 * is why the first failing edit is the only one ever reported, across all three tiers, and why
 * nothing is aggregated. Without this single fact the ordering assertions below would be guesses.</p>
 *
 * <p><strong>Corroborating members.</strong> {@code app/cpy/CSDAT01Y.cpy:L19-L23} proves the monthly
 * arithmetic operates in place. {@code app/csd/CARDDEMO.CSD:L499-L503} fixes the queue contract:
 * {@code DEFINE TDQUEUE(JOBS) TYPE(EXTRA) DDNAME(INREADER) TYPEFILE(OUTPUT) RECORDSIZE(80)
 * RECORDFORMAT(FIXED) DISPOSITION(MOD)}. {@code app/proc/TRANREPT.prc:L38-L46} declares the sort
 * symbols {@code TRAN-CARD-NUM,263,16,ZD} and {@code TRAN-PROC-DT,305,10,CH} and an inclusive
 * {@code GE}/{@code LE} character range filter; {@code :L76} sets the report to {@code LRECL=133},
 * whose line layouts are {@code app/cpy/CVTRA07Y.cpy:L30} ({@code TRAN-REPORT-AMT PIC
 * -ZZZ,ZZZ,ZZZ.ZZ}) and {@code :L48} ({@code TRANSACTION-HEADER-2 PIC X(133) VALUE ALL '-'}).
 * {@code app/cbl/CBTRN03C.cbl:L131-L132} declares the report's {@code WS-PAGE-SIZE VALUE 20}, which
 * is a batch concern only. {@code app/cpy/COCOM01Y.cpy:L21-L24}, {@code :L29-L31} and {@code :L43-L44}
 * enumerate the session and navigation fields that must not appear, the last pair being
 * {@code PIC X(7)} rather than {@code X(8)}. {@code app/cpy/CSSETATY.cpy:L18-L27} is the
 * {@code COPY ... REPLACING} procedure division template - procedural, so it has no class - that
 * models OK / NOT-OK / BLANK with markers firing only on re-entry, corroborated at message level by
 * {@code app/cbl/COACTUPC.cbl:L505-L508}, and whose gated cross field pattern is
 * {@code app/cbl/COACTUPC.cbl:L1665-L1669} followed by {@code :L1671-L1675}.
 * {@code app/cbl/COBIL00C.cbl:L173-L191} is the comparable four way confirmation gate.</p>
 *
 * <h2>3. How to build, run and test</h2>
 *
 * <p>{@code ./mvnw -q test} runs this class; {@code ./mvnw -q -Dtest=ReportRequestTest test} runs it
 * alone; {@code ./mvnw -q test-compile} is the fastest check that it still satisfies the compiler
 * settings. Where no local toolchain is present the pinned image reproduces it exactly:
 * {@code docker run --rm -v "$PWD":/w -w /w maven:3.9.11-eclipse-temurin-25 ./mvnw -q test}.</p>
 *
 * <p><strong>Surefire, not Failsafe, collects this class, and only because of where it sits.</strong>
 * The build binds Surefire 3.5.4 to {@code **}{@code /*Test.java} while excluding
 * {@code **}{@code /integration/**} and {@code **}{@code /e2e/**}. A class moved out of
 * {@code src/test/java/com/cardemo/unit} - or renamed so it no longer ends in {@code Test} - is
 * collected by neither plugin and silently never runs: the build stays green, both plugins report
 * success, coverage records it as uncovered and nothing warns. Do not rename or relocate this file.
 * Confirm it ran by checking for
 * {@code target/surefire-reports/TEST-com.cardemo.unit.model.ReportRequestTest.xml}.</p>
 *
 * <h2>4. Key configuration and defaults</h2>
 *
 * <ul>
 *   <li><strong>Time is injected, never ambient.</strong> Every clock read goes through
 *       {@link FixedClockProvider}, the tier's single sanctioned time source.
 *       {@code FUNCTION CURRENT-DATE} at {@code app/cbl/CORPT00C.cbl:L215} and {@code :L241} is the
 *       only clock read in the period cascade, and the monthly boundary is an observable output, so
 *       reading a wall clock here would make the December rollover and the leap year February cases
 *       pass or fail depending on the day the suite runs. No {@code now()} overload, no
 *       {@code System.currentTimeMillis()}, no default zone and no default locale appears anywhere in
 *       this file; every zone is stated and every format uses {@link Locale#ROOT}.</li>
 *   <li><strong>Bean validation runs for real, through one shared validator.</strong> Every violation
 *       set in this class comes from {@link ValidationSupport}, which owns a single immutable,
 *       thread-safe validator for the whole test JVM. This class therefore declares no validator field
 *       and no validation lifecycle hook of its own, and holds no static mutable field, no shared cache
 *       and nothing a test could perturb for another. Hibernate Validator 8.0.3 and an expression
 *       language implementation arrive on the test classpath through the project's validation starter;
 *       nothing is added for this file.</li>
 *   <li><strong>Mockito is deliberately unused.</strong> {@code mockito-core} 5.17.0 is available and
 *       its strict stubs policy would apply, but {@link ReportRequest} has no collaborator to double
 *       and the oracles are pure functions of their arguments. Introducing a mock here would fabricate
 *       an interaction the type does not have. The one collaborator the source does consult -
 *       {@code CALL 'CSUTLDTC'} - is represented by two boolean parameters rather than a stub; see
 *       section 5.</li>
 *   <li><strong>No container, no Spring context, no database, no queue and no cloud client.</strong>
 *       This is the pure JVM tier. Nothing here opens a socket, so no endpoint, credential or
 *       privilege is involved.</li>
 * </ul>
 *
 * <h2>5. The date validation boundary</h2>
 *
 * <p>{@code app/cbl/CORPT00C.cbl:L392-L394} calls {@code 'CSUTLDTC'} statically, passing the
 * hundred byte block declared at {@code :L129-L136}: a ten character date, a ten character format,
 * then an eighty byte result of a four character severity code, an eleven byte gap, a four character
 * message number and a sixty one character message. Its Java counterpart is a single injected
 * {@code DateValidationService} subsuming that utility together with both work area copybooks,
 * {@code app/cpy/CSUTLDPY.cpy} and {@code app/cpy/CSUTLDWY.cpy}.</p>
 *
 * <p><strong>This class asserts the shape of that block and nothing more.</strong> It never
 * instantiates, invokes or mocks the service: exercising it belongs to the validation tier. Where an
 * oracle needs the utility's verdict - the third editing tier - the verdict arrives as an explicit
 * boolean parameter standing in for the {@code SEV-CD = '0000'} test at {@code :L396} and the
 * tolerated {@code MSG-NUM = '2513'} escape at {@code :L399}. That keeps the tier boundary visible in
 * the signature instead of hiding it behind a stub.</p>
 *
 * <h2>6. Findings, classified</h2>
 *
 * <p><strong>Medium, closed - prior-generation plan prose described the monthly period as month to date.
 * The source computes a full calendar month.</strong> {@code app/cbl/CORPT00C.cbl:L217-L219} sets the
 * start to the current
 * year and month with day {@code '01'}. {@code :L223-L230} then sets the day to 1, adds one to the
 * month, rolls the year when the month passes 12, and subtracts one day from the packed year month
 * day value. Because {@code app/cpy/CSDAT01Y.cpy:L23} declares {@code WS-CURDATE-N REDEFINES
 * WS-CURDATE PIC 9(08)} over the very year, month and day subfields that {@code :L223-L227} has just
 * overwritten, the moves at {@code :L232-L234} emit the <strong>last day of the current month</strong>
 * - not the clock's day. The December rollover settles it: month 13 becomes January of the following
 * year, and one day less is the 31st of December of the original year. That prose's own yearly period
 * being a full calendar year is the consistent reading, and the current
 * {@code docs/technical-specifications.md} now describes a full calendar month.
 * <em>Remediation still owed by the service layer</em>: {@code ReportSubmissionService} must implement
 * {@code :L223-L234}, not the prose; that bean is <strong>not available</strong>, so the obligation is
 * recorded here rather than asserted as met.</p>
 *
 * <p><strong>Medium, closed - prior-generation plan prose totalled 460 symbolic map input fields.</strong>
 * This map's own figure of 17 was correct in that prose and on disk, so nothing here is affected; the
 * corpus total now reads <strong>441</strong> in {@code docs/technical-specifications.md}, recounted from
 * the input groups and verified on 1 August 2026.</p>
 *
 * <p><strong>Medium, closed - prior-generation plan prose described eighteen job deck card images.</strong>
 * {@code app/cbl/CORPT00C.cbl:L83-L125} declares <strong>seventeen</strong>: fourteen plain
 * {@code PIC X(80)} literals plus the three named multi part groups. The specification no longer states a
 * card count at all - it describes the deck as a run of eighty-byte literal constants - so nothing there
 * now contradicts the seventeen counted here. No impact on this type, which carries no card array.</p>
 *
 * <p><strong>Low - the confirmation gate is not the billing screen's gate.</strong> Two differences,
 * both verified. {@code app/cbl/CORPT00C.cbl:L464-L474} handles blank <em>outside</em> the
 * {@code EVALUATE} and treats it as an <strong>error</strong>, setting {@code WS-ERR-FLG} at
 * {@code :L471}, whereas {@code app/cbl/COBIL00C.cbl:L182-L184} makes {@code WHEN SPACES} /
 * {@code WHEN LOW-VALUES} a <strong>non error</strong> branch that proceeds. And this program's
 * invalid branch at {@code :L485-L490} quotes the offending character back to the caller, whereas
 * {@code app/cbl/COBIL00C.cbl:L187} emits the fixed literal
 * {@code 'Invalid value. Valid values are (Y/N)...'} without quoting. Assuming the billing wording
 * here would be wrong twice over.</p>
 *
 * <p><strong>Low - December 9999 is undefined in the source.</strong> {@code :L226} performs
 * {@code ADD 1 TO WS-CURDATE-YEAR} against a {@code PIC 9(04)} field
 * ({@code app/cpy/CSDAT01Y.cpy:L20}) with <strong>no {@code ON SIZE ERROR} clause</strong>, so 9999
 * plus one truncates high order to 0000 and the subsequent {@code FUNCTION INTEGER-OF-DATE} argument
 * is not a usable standard date. The oracle here reports the condition with context instead of
 * truncating silently; that is a labelled deviation, not parity. <em>Remediation</em>: none - the
 * condition is unreachable in practice and correcting the legacy field would be a behaviour
 * change.</p>
 *
 * <p><strong>Low - the width guard is not reachable from the record component.</strong> The build pins
 * {@code jakarta.validation-api} 3.0.2, whose {@code @Size} declares
 * {@code @Target({METHOD, FIELD, ANNOTATION_TYPE, CONSTRUCTOR, PARAMETER, TYPE_USE})}. That artefact
 * predates {@code ElementType.RECORD_COMPONENT}, so the annotation is not applicable to a record
 * component and {@code RecordComponent.getAnnotation(Size.class)} returns {@code null} - verified
 * against the compiled type, not assumed. Under JLS 8.10.1 it survives on the backing field, the
 * accessor and the canonical constructor parameter, which is how Hibernate Validator finds it. This
 * class therefore reads the accessor and the field and requires them to agree, and it asserts the
 * enforcement end to end through a real validation run rather than trusting reflection alone.
 * <em>Remediation</em>: none required; the constraint is enforced. Recorded because a width assertion
 * written against the record component alone reports a false contract drift.</p>
 *
 * <p><strong>Not available.</strong> Three things this repository does not state, listed rather than
 * invented. (a) The behaviour of {@code FUNCTION NUMVAL-C} on a non numeric argument at
 * {@code :L305-L327}: the source's own guard is the {@code IS NOT NUMERIC} test that follows at
 * {@code :L329}, so the oracle passes such a value through unchanged and lets that test reject it.
 * (b) The lower bound of the {@code FUNCTION INTEGER-OF-DATE} domain: the oracle bounds the year by
 * the {@code PIC 9(04)} field width instead, which is repository evidence. (c) Any relational schema
 * claim - no migration file is a planned child of this test, so nothing about DDL is asserted
 * here.</p>
 *
 * <p><strong>Severity taxonomy applied to changes to this contract.</strong> <em>Blocker</em>:
 * altering any of the fourteen message literals, including normalising the lower case {@code date} of
 * the two whole date literals to {@code Date}; modelling the confirmation as a {@code boolean}; or
 * collapsing absent, blank and low values into one state. <em>High</em>: implementing month to date
 * instead of the full calendar month; reading a wall clock; inventing a mutual exclusivity constraint;
 * requiring a specific tick character; using a temporal type for an assembled date; normalising the
 * blank date rendering; drifting the {@code CSUTLDTC-PARM} widths; carrying two copies of a date;
 * leaking the batch report's page size; factoring the header fields into a shared abstraction;
 * declaring a class level gated constraint; or adding a session or navigation field. <em>Medium</em>:
 * the three plan defects above. <em>Low</em>: the three observations above - the confirmation gate
 * divergence, the undefined December 9999 arithmetic and the annotation propagation.</p>
 *
 * <h2>7. Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><em>The build fails on something trivial.</em> Compilation runs with {@code -Xlint:all},
 *       {@code -Werror} and {@code failOnWarning}, and that reaches test compilation, so one raw type or
 *       one deprecation is a hard error. An unused import is not - {@code javac} 25.0.3 publishes no lint
 *       key for one - so that is a review matter. Reproduce with {@code ./mvnw -q test-compile}.</li>
 *   <li><em>A monthly assertion fails only in some months.</em> Month to date was implemented instead
 *       of the full calendar month, or a wall clock leaked in. The December and February cases fail
 *       first.</li>
 *   <li><em>A custom range assertion reports the wrong literal.</em> The three tiers were allowed to
 *       aggregate. They cannot: {@code :L580} ends the task at the first failure.</li>
 *   <li><em>A period assertion fails for a tick character other than {@code 'Y'}.</em> The selector
 *       test is the abbreviated combined relation {@code NOT = SPACES AND LOW-VALUES}, so any non
 *       blank character selects.</li>
 *   <li><em>A validation assertion fails after adding a constraint.</em> The record carries maximum
 *       length constraints only, by design; anything stricter rejects input the source accepts.</li>
 *   <li><em>A page size, queue URL or job deck field appears on the type.</em> All three belong to
 *       other tiers and are asserted absent here.</li>
 *   <li><em>A width assertion reports that a component declares no {@code @Size}.</em> It was read
 *       from the {@link RecordComponent} rather than from the accessor or the backing field; see the
 *       Low finding above. Read {@code component.getAccessor().getAnnotation(Size.class)}.</li>
 *   <li><em>A locale sensitive assertion fails on another host.</em> Every format and parse in this
 *       class passes {@link Locale#ROOT}; a default locale can render digits outside ASCII, which is
 *       asserted explicitly.</li>
 * </ul>
 *
 * @see ReportRequest
 * @see FixedClockProvider
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("ReportRequest - app/cpy-bms/CORPT00.CPY + app/cbl/CORPT00C.cbl @ 7756d89")
class ReportRequestTest {

    /**
     * The 17 component names in symbolic map declaration order, {@code app/cpy-bms/CORPT00.CPY:24-120}.
     */
    private static final List<String> COMPONENT_NAMES = List.of(
            "transactionName",  // 1  TRNNAMEI  PIC X(4)   :24  header
            "title01",          // 2  TITLE01I  PIC X(40)  :30  header
            "currentDate",      // 3  CURDATEI  PIC X(8)   :36  header
            "programName",      // 4  PGMNAMEI  PIC X(8)   :42  header
            "title02",          // 5  TITLE02I  PIC X(40)  :48  header
            "currentTime",      // 6  CURTIMEI  PIC X(8)   :54  header - X(9) in COSGN00.CPY:54
            "monthlySelected",  // 7  MONTHLYI  PIC X(1)   :60  period selector, evaluated first
            "yearlySelected",   // 8  YEARLYI   PIC X(1)   :66  period selector, evaluated second
            "customSelected",   // 9  CUSTOMI   PIC X(1)   :72  period selector, evaluated third
            "startDateMonth",   // 10 SDTMMI    PIC X(2)   :78  custom component 1 of 6
            "startDateDay",     // 11 SDTDDI    PIC X(2)   :84  custom component 2 of 6
            "startDateYear",    // 12 SDTYYYYI  PIC X(4)   :90  custom component 3 of 6
            "endDateMonth",     // 13 EDTMMI    PIC X(2)   :96  custom component 4 of 6
            "endDateDay",       // 14 EDTDDI    PIC X(2)  :102  custom component 5 of 6
            "endDateYear",      // 15 EDTYYYYI  PIC X(4)  :108  custom component 6 of 6
            "confirmation",     // 16 CONFIRMI  PIC X(1)  :114  four state handshake
            "errorMessage");    // 17 ERRMSGI   PIC X(78) :120  screen message line

    /**
     * The declared width of each component above, in the same order.
     */
    private static final List<Integer> COMPONENT_WIDTHS =
            List.of(4, 40, 8, 8, 40, 8, 1, 1, 1, 2, 2, 4, 2, 2, 4, 1, 78);

    /**
     * The six recurring header fields, declared inline because {@code CURTIMEI} widths diverge.
     */
    private static final List<String> HEADER_COMPONENT_NAMES =
            List.of("transactionName", "title01", "currentDate", "programName", "title02", "currentTime");

    /**
     * The three independent one character period selectors, {@code app/cpy-bms/CORPT00.CPY:60,66,72}.
     */
    private static final List<String> SELECTOR_COMPONENT_NAMES =
            List.of("monthlySelected", "yearlySelected", "customSelected");

    /**
     * The six custom range components in <strong>month, day, year</strong> declaration order - which is also
     * the emptiness cascade order at {@code app/cbl/CORPT00C.cbl:L259-L300}, not year month day.
     */
    private static final List<String> CUSTOM_RANGE_COMPONENT_NAMES = List.of(
            "startDateMonth", "startDateDay", "startDateYear",
            "endDateMonth", "endDateDay", "endDateYear");

    /**
     * {@code app/cbl/CORPT00C.cbl:L261}.
     */
    private static final String START_DATE_MONTH_EMPTY = "Start Date - Month can NOT be empty...";
    /**
     * {@code app/cbl/CORPT00C.cbl:L268}.
     */
    private static final String START_DATE_DAY_EMPTY = "Start Date - Day can NOT be empty...";
    /**
     * {@code app/cbl/CORPT00C.cbl:L275}.
     */
    private static final String START_DATE_YEAR_EMPTY = "Start Date - Year can NOT be empty...";
    /**
     * {@code app/cbl/CORPT00C.cbl:L282}.
     */
    private static final String END_DATE_MONTH_EMPTY = "End Date - Month can NOT be empty...";
    /**
     * {@code app/cbl/CORPT00C.cbl:L289}.
     */
    private static final String END_DATE_DAY_EMPTY = "End Date - Day can NOT be empty...";
    /**
     * {@code app/cbl/CORPT00C.cbl:L296}.
     */
    private static final String END_DATE_YEAR_EMPTY = "End Date - Year can NOT be empty...";

    /**
     * {@code app/cbl/CORPT00C.cbl:L331}, guarded by {@code :L329-L330}.
     */
    private static final String START_DATE_MONTH_INVALID = "Start Date - Not a valid Month...";
    /**
     * {@code app/cbl/CORPT00C.cbl:L340}, guarded by {@code :L338-L339}.
     */
    private static final String START_DATE_DAY_INVALID = "Start Date - Not a valid Day...";
    /**
     * {@code app/cbl/CORPT00C.cbl:L348}, guarded by {@code :L347} - no range test on the year.
     */
    private static final String START_DATE_YEAR_INVALID = "Start Date - Not a valid Year...";
    /**
     * {@code app/cbl/CORPT00C.cbl:L357}, guarded by {@code :L355-L356}.
     */
    private static final String END_DATE_MONTH_INVALID = "End Date - Not a valid Month...";
    /**
     * {@code app/cbl/CORPT00C.cbl:L366}, guarded by {@code :L364-L365}.
     */
    private static final String END_DATE_DAY_INVALID = "End Date - Not a valid Day...";
    /**
     * {@code app/cbl/CORPT00C.cbl:L374}, guarded by {@code :L373}.
     */
    private static final String END_DATE_YEAR_INVALID = "End Date - Not a valid Year...";

    /**
     * {@code app/cbl/CORPT00C.cbl:L400} - lower case {@code date}, deliberately unlike the tier above.
     */
    private static final String START_DATE_WHOLE_INVALID = "Start Date - Not a valid date...";
    /**
     * {@code app/cbl/CORPT00C.cbl:L420} - lower case {@code date}.
     */
    private static final String END_DATE_WHOLE_INVALID = "End Date - Not a valid date...";

    /**
     * All fourteen custom range literals, in source order.
     */
    private static final List<String> CUSTOM_RANGE_MESSAGES = List.of(
            START_DATE_MONTH_EMPTY, START_DATE_DAY_EMPTY, START_DATE_YEAR_EMPTY,
            END_DATE_MONTH_EMPTY, END_DATE_DAY_EMPTY, END_DATE_YEAR_EMPTY,
            START_DATE_MONTH_INVALID, START_DATE_DAY_INVALID, START_DATE_YEAR_INVALID,
            END_DATE_MONTH_INVALID, END_DATE_DAY_INVALID, END_DATE_YEAR_INVALID,
            START_DATE_WHOLE_INVALID, END_DATE_WHOLE_INVALID);

    /**
     * {@code app/cbl/CORPT00C.cbl:L438-L439} - the {@code WHEN OTHER} branch, no selector ticked.
     */
    private static final String NO_REPORT_TYPE_SELECTED = "Select a report type to print report...";

    /**
     * {@code app/cbl/CORPT00C.cbl:L531-L532}, inside the misspelled {@code WIRTE-JOBSUB-TDQ}.
     */
    private static final String QUEUE_WRITE_FAILED = "Unable to Write TDQ (JOBS)...";

    /**
     * {@code app/cbl/COBIL00C.cbl:L187} - the billing screen's wording, cited only for contrast.
     */
    private static final String BILLING_SCREEN_INVALID_CONFIRMATION =
            "Invalid value. Valid values are (Y/N)...";

    /**
     * {@code app/cbl/CORPT00C.cbl:L72} - {@code WS-DATE-FORMAT PIC X(10) VALUE 'YYYY-MM-DD'}.
     */
    private static final String DATE_FORMAT_LITERAL = "YYYY-MM-DD";

    /**
     * {@code app/cbl/CORPT00C.cbl:L214} - the monthly report name, before padding.
     */
    private static final String MONTHLY_LITERAL = "Monthly";
    /**
     * {@code app/cbl/CORPT00C.cbl:L240} - the yearly report name, before padding.
     */
    private static final String YEARLY_LITERAL = "Yearly";
    /**
     * {@code app/cbl/CORPT00C.cbl:L433} - the custom report name, before padding. It IS set.
     */
    private static final String CUSTOM_LITERAL = "Custom";

    /**
     * {@code app/cbl/CORPT00C.cbl:L58} - {@code WS-REPORT-NAME PIC X(10)}.
     */
    private static final int REPORT_NAME_WIDTH = 10;
    /**
     * {@code app/cbl/CORPT00C.cbl:L39} - {@code WS-MESSAGE PIC X(80)}, two bytes wider than X(78).
     */
    private static final int MESSAGE_WORK_AREA_WIDTH = 80;
    /**
     * {@code app/cpy-bms/CORPT00.CPY:120} - {@code ERRMSGI PIC X(78)}, the width on the wire.
     */
    private static final int SCREEN_MESSAGE_WIDTH = 78;

    /**
     * {@code app/cbl/CORPT00C.cbl:L61,L67} - the year subfield of each assembled date.
     */
    private static final int ASSEMBLED_YEAR_WIDTH = 4;
    /**
     * {@code app/cbl/CORPT00C.cbl:L63,L65,L69,L71} - the month and day subfields.
     */
    private static final int ASSEMBLED_MONTH_DAY_WIDTH = 2;
    /**
     * {@code app/cbl/CORPT00C.cbl:L62,L64,L68,L70} - {@code FILLER PIC X(01) VALUE '-'}.
     */
    private static final String ASSEMBLED_DATE_SEPARATOR = "-";
    /**
     * 4 + 1 + 2 + 1 + 2, the total width of {@code WS-START-DATE} and {@code WS-END-DATE}.
     */
    private static final int ASSEMBLED_DATE_WIDTH = 10;
    /**
     * The rendering of an assembled date whose components are still at their {@code VALUE SPACES}.
     */
    private static final String UNPOPULATED_ASSEMBLED_DATE = "    -  -  ";

    /**
     * {@code app/cbl/CORPT00C.cbl:L130} - {@code CSUTLDTC-DATE PIC X(10)}.
     */
    private static final int CSUTLDTC_DATE_WIDTH = 10;
    /**
     * {@code app/cbl/CORPT00C.cbl:L131} - {@code CSUTLDTC-DATE-FORMAT PIC X(10)}.
     */
    private static final int CSUTLDTC_FORMAT_WIDTH = 10;
    /**
     * {@code app/cbl/CORPT00C.cbl:L133} - {@code CSUTLDTC-RESULT-SEV-CD PIC X(04)}.
     */
    private static final int CSUTLDTC_SEVERITY_WIDTH = 4;
    /**
     * {@code app/cbl/CORPT00C.cbl:L134} - the unnamed {@code FILLER PIC X(11)} gap.
     */
    private static final int CSUTLDTC_GAP_WIDTH = 11;
    /**
     * {@code app/cbl/CORPT00C.cbl:L135} - {@code CSUTLDTC-RESULT-MSG-NUM PIC X(04)}.
     */
    private static final int CSUTLDTC_MESSAGE_NUMBER_WIDTH = 4;
    /**
     * {@code app/cbl/CORPT00C.cbl:L136} - {@code CSUTLDTC-RESULT-MSG PIC X(61)}.
     */
    private static final int CSUTLDTC_MESSAGE_WIDTH = 61;
    private static final String CSUTLDTC_ACCEPTED_SEVERITY = "0000";
    private static final String CSUTLDTC_TOLERATED_MESSAGE_NUMBER = "2513";

    /**
     * {@code app/csd/CARDDEMO.CSD:L502} - {@code RECORDSIZE(80)} on {@code DEFINE TDQUEUE(JOBS)}.
     */
    private static final int QUEUE_RECORD_SIZE = 80;
    /**
     * {@code app/cbl/CORPT00C.cbl:L119} - the single {@code FILLER PIC X VALUE SPACE} separator.
     */
    private static final int DATEPARM_SEPARATOR_WIDTH = 1;
    /**
     * {@code app/cbl/CORPT00C.cbl:L121} - the trailing {@code FILLER PIC X(59)} of the DATEPARM card.
     */
    private static final int DATEPARM_TRAILING_FILLER_WIDTH = 59;

    /**
     * {@code app/cbl/CORPT00C.cbl:L330,L356} - the month ceiling, compared as characters.
     */
    private static final String MONTH_CEILING = "12";
    /**
     * {@code app/cbl/CORPT00C.cbl:L339,L365} - the day ceiling, compared as characters.
     */
    private static final String DAY_CEILING = "31";
    /**
     * {@code app/cbl/CORPT00C.cbl:L219,L245-L246} - the literal {@code '01'} moves.
     */
    private static final String FIRST_DAY_LITERAL = "01";
    /**
     * {@code app/cbl/CORPT00C.cbl:L250} - the literal {@code '12'} move.
     */
    private static final String LAST_MONTH_LITERAL = "12";
    /**
     * {@code app/cbl/CORPT00C.cbl:L251} - the literal {@code '31'} move.
     */
    private static final String LAST_DAY_LITERAL = "31";

    /**
     * {@code app/cbl/CORPT00C.cbl:L478} - both letter cases are separate {@code WHEN} operands.
     */
    private static final List<String> AFFIRMATIVE_CONFIRMATIONS = List.of("Y", "y");
    /**
     * {@code app/cbl/CORPT00C.cbl:L480} - both letter cases are separate {@code WHEN} operands.
     */
    private static final List<String> NEGATIVE_CONFIRMATIONS = List.of("N", "n");

    /**
     * The COBOL figurative constant {@code LOW-VALUES} for a character field.
     */
    private static final char LOW_VALUE = '\u0000';
    /**
     * The COBOL figurative constant {@code SPACES} for a character field.
     */
    private static final char SPACE = ' ';

    /**
     * The largest year a {@code PIC 9(04)} field can hold, {@code app/cpy/CSDAT01Y.cpy:L20}.
     */
    private static final int MAX_FOUR_DIGIT_YEAR = 9999;

    /**
     * {@code app/cbl/CORPT00C.cbl:L223} - {@code MOVE 1 TO WS-CURDATE-DAY}.
     */
    private static final int FIRST_DAY_OF_MONTH = 1;
    /**
     * {@code app/cbl/CORPT00C.cbl:L225} - the month ceiling of the rollover guard.
     */
    private static final int LAST_MONTH_OF_YEAR = 12;

    /**
     * Component name fragments that would betray a secret, a credential or personal data.
     */
    private static final List<String> FORBIDDEN_NAME_FRAGMENTS = List.of(
            "password", "passwd", "secret", "credential", "signingkey", "apikey", "accesskey",
            "privatekey", "bearer", "authorization", "sessionid", "ssn", "socialsecurity",
            "cardnumber", "dateofbirth", "governmentid", "queueurl", "endpoint", "connectionstring");

    /**
     * Component name fragments that would betray leaked session, navigation or batch state.
     */
    private static final List<String> FORBIDDEN_STATE_FRAGMENTS = List.of(
            "fromtranid", "totranid", "fromprogram", "toprogram", "pgmcontext", "reenter",
            "lastmap", "lastmapset", "commarea", "cursor", "attribute", "page", "lines",
            "jobdeck", "joblines", "jclrecord", "cardimage", "queue", "topic");

    /**
     * Validates a request with the real Jakarta Validation engine.
     *
     * @param request the request to validate; never {@code null}
     * @return the raised violations, empty when the request satisfies every declared constraint
     */
    private Set<ConstraintViolation<ReportRequest>> violationsOf(final ReportRequest request) {
        return ValidationSupport.violationsOf(request, "request");
    }

    /**
     * The outcome of the period cascade, {@code app/cbl/CORPT00C.cbl:L212-L442}.
     */
    private enum PeriodSelection {
        /**
         * {@code app/cbl/CORPT00C.cbl:L213} - evaluated first, so it wins over the other two.
         */
        MONTHLY,
        /**
         * {@code app/cbl/CORPT00C.cbl:L239} - evaluated second.
         */
        YEARLY,
        /**
         * {@code app/cbl/CORPT00C.cbl:L256} - evaluated third.
         */
        CUSTOM,
        /**
         * {@code app/cbl/CORPT00C.cbl:L437} - the {@code WHEN OTHER} branch; there is no default.
         */
        NONE
    }

    /**
     * The four outcomes of the confirmation handshake, {@code app/cbl/CORPT00C.cbl:L464-L494}.
     */
    private enum ConfirmationOutcome {
        /**
         * {@code :L464} - spaces or low values. Unlike {@code COBIL00C}, this branch IS an error.
         */
        BLANK,
        /**
         * {@code :L478} - {@code 'Y'} or {@code 'y'}; submission proceeds.
         */
        AFFIRMATIVE,
        /**
         * {@code :L480} - {@code 'N'} or {@code 'n'}; the fields and the message are cleared.
         */
        NEGATIVE,
        /**
         * {@code :L484} - any other character; the value is quoted back to the caller.
         */
        INVALID
    }

    /**
     * A resolved reporting period: the padded report name and the two assembled ten character dates.
     *
     * @param reportName the {@code WS-REPORT-NAME PIC X(10)} value, {@code app/cbl/CORPT00C.cbl:L58}
     * @param startDate the assembled {@code WS-START-DATE}, {@code app/cbl/CORPT00C.cbl:L60-L65}
     * @param endDate the assembled {@code WS-END-DATE}, {@code app/cbl/CORPT00C.cbl:L66-L71}
     */
    private record ReportPeriod(String reportName, String startDate, String endDate) {
    }

    /**
     * Reproduces a COBOL alphanumeric {@code MOVE} into a {@code PIC X(n)} receiving field: the sending value
     * is left justified, space filled to the receiving width and truncated on the right.
     *
     * <p>A {@code null} sending value models a field still holding its {@code VALUE SPACES} initial state,
     * {@code app/cbl/CORPT00C.cbl:L61-L71}. It is never conflated with a supplied blank in the record itself.
     *
     * @param sendingField the sending value, or {@code null} for an uninitialised field
     * @param receivingWidth the {@code PIC X(n)} width of the receiving field.
     * @return exactly {@code receivingWidth} characters
     */
    private static String alphanumericMove(final String sendingField, final int receivingWidth) {
        final String sending = sendingField == null ? "" : sendingField;
        if (sending.length() >= receivingWidth) {
            return sending.substring(0, receivingWidth);
        }
        return sending + String.valueOf(SPACE).repeat(receivingWidth - sending.length());
    }

    /**
     * Reproduces a {@code MOVE} from a {@code PIC 9(n)} display numeric sender into a {@code PIC X(n)}
     * receiver: the value is rendered zero filled to the sender's own width.
     *
     * <p>{@code app/cbl/CORPT00C.cbl:L217-L218} and {@code :L232-L234} move {@code WS-CURDATE-YEAR PIC 9(04)},
     * {@code WS-CURDATE-MONTH PIC 9(02)} and {@code WS-CURDATE-DAY PIC 9(02)} - declared at
     * {@code app/cpy/CSDAT01Y.cpy:L20-L22} - into the {@code PIC X(04)} and {@code PIC X(02)} subfields of the
     * assembled dates.
     *
     * @param value the numeric value; must be representable in {@code width} digits
     * @param width the sender's declared digit count
     * @return exactly {@code width} digit characters
     * @throws IllegalArgumentException when the value cannot be held by a {@code PIC 9(width)} field, which no
     * clock the source could ever have read would produce
     */
    private static String zonedDecimalMove(final int value, final int width) {
        if (value < 0) {
            throw new IllegalArgumentException("app/cpy/CSDAT01Y.cpy:L20-L22 declares the date subfields "
                    + "as unsigned PIC 9(n), so a negative value cannot arise: " + value);
        }
        final String rendered = String.format(Locale.ROOT, "%0" + width + "d", value);
        if (rendered.length() > width) {
            throw new IllegalArgumentException("app/cpy/CSDAT01Y.cpy:L20-L22 declares this subfield as "
                    + "PIC 9(0" + width + "), which cannot hold " + value + "; the injected clock is "
                    + "outside the calendar the legacy fields can represent");
        }
        return rendered;
    }

    /**
     * Assembles {@code WS-START-DATE} or {@code WS-END-DATE}, {@code app/cbl/CORPT00C.cbl:L60-L71}.
     *
     * @param year the four character year subfield, or {@code null} for its initial spaces
     * @param month the two character month subfield, or {@code null} for its initial spaces
     * @param day the two character day subfield, or {@code null} for its initial spaces
     * @return exactly ten characters in {@code YYYY-MM-DD} shape
     */
    private static String assembleDate(final String year, final String month, final String day) {
        return alphanumericMove(year, ASSEMBLED_YEAR_WIDTH)
                + ASSEMBLED_DATE_SEPARATOR
                + alphanumericMove(month, ASSEMBLED_MONTH_DAY_WIDTH)
                + ASSEMBLED_DATE_SEPARATOR
                + alphanumericMove(day, ASSEMBLED_MONTH_DAY_WIDTH);
    }

    /**
     * Pads a report name literal into {@code WS-REPORT-NAME PIC X(10)}, {@code app/cbl/CORPT00C.cbl:L58}, as
     * {@code :L214}, {@code :L240} and {@code :L433} do.
     *
     * @param literal the unpadded literal
     * @return exactly ten characters
     */
    private static String reportName(final String literal) {
        return alphanumericMove(literal, REPORT_NAME_WIDTH);
    }

    /**
     * Reproduces {@code STRING ... DELIMITED BY SPACE}: the sending value contributes only the characters
     * before its first space. Used at {@code app/cbl/CORPT00C.cbl:L449}, {@code :L468} and {@code :L487}, which
     * is how a padded ten character report name renders as a bare word.
     *
     * @param sendingField the sending value, or {@code null}
     * @return the prefix before the first space, or the empty string when the value is {@code null}
     */
    private static String delimitedBySpace(final String sendingField) {
        if (sendingField == null) {
            return "";
        }
        final int firstSpace = sendingField.indexOf(SPACE);
        return firstSpace < 0 ? sendingField : sendingField.substring(0, firstSpace);
    }

    /**
     * Reproduces the COBOL relation {@code = SPACES OR LOW-VALUES}, used at
     * {@code app/cbl/CORPT00C.cbl:L259-L295} and, negated as the abbreviated combined relation
     * {@code NOT = SPACES AND LOW-VALUES}, at {@code :L213}, {@code :L239} and {@code :L256}.
     *
     * @param screenField the raw screen value
     * @return {@code true} when the field satisfies {@code = SPACES OR LOW-VALUES}
     */
    private static boolean isSpacesOrLowValues(final String screenField) {
        if (screenField == null || screenField.isEmpty()) {
            return true;
        }
        return isEntirely(screenField, SPACE) || isEntirely(screenField, LOW_VALUE);
    }

    private static boolean isEntirely(final String screenField, final char character) {
        for (int index = 0; index < screenField.length(); index++) {
            if (screenField.charAt(index) != character) {
                return false;
            }
        }
        return true;
    }

    /**
     * Reproduces the COBOL {@code NUMERIC} class test on an alphanumeric item, as used at
     * {@code app/cbl/CORPT00C.cbl:L329}, {@code :L338}, {@code :L347}, {@code :L355}, {@code :L364} and
     * {@code :L373}. Every character must be a digit: no sign, no space and no separator qualifies, and an
     * empty field is not numeric.
     *
     * @param screenField the raw screen value
     * @return {@code true} when every character is a decimal digit and the field is not empty
     */
    private static boolean isNumericClass(final String screenField) {
        if (screenField == null || screenField.isEmpty()) {
            return false;
        }
        for (int index = 0; index < screenField.length(); index++) {
            final char character = screenField.charAt(index);
            if (character < '0' || character > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * Reproduces the numeric normalisation of {@code app/cbl/CORPT00C.cbl:L305-L327}: each component is
     * converted with {@code FUNCTION NUMVAL-C} into {@code WS-NUM-99} or {@code WS-NUM-9999} ({@code :L74-L75})
     * and then moved straight back into the screen field, which re-renders it zero filled to the field's width.
     *
     * <p>Surrounding space characters (0x20, the only pad a BMS field emits) are removed before the class
     * test, because {@code NUMVAL-C} tolerates them and a
     * 3270 field blank pads whatever the operator typed. Without that tolerance a single digit month could
     * never be entered at all: the padded value would fail the numeric class test, be returned unchanged, and
     * assemble into a malformed date. A genuinely non-numeric value is still returned unchanged, so nothing
     * the source rejects becomes accepted here.
     *
     * @param screenField the raw screen value
     * @param digits the digit count of the intermediate {@code PIC 9(n)} item
     * @return the re-rendered value, or the original characters when they are not numeric
     */
    private static String numericRedisplay(final String screenField, final int digits) {
        if (screenField == null) {
            return "";
        }
        int begin = 0;
        int end = screenField.length();
        while (begin < end && screenField.charAt(begin) == SPACE) {
            begin++;
        }
        while (end > begin && screenField.charAt(end - 1) == SPACE) {
            end--;
        }
        final String withoutSurroundingSpaces = screenField.substring(begin, end);
        if (!isNumericClass(withoutSurroundingSpaces) || withoutSurroundingSpaces.isEmpty()) {
            return screenField;
        }
        long ceiling = 1L;
        for (int power = 0; power < digits; power++) {
            ceiling = ceiling * 10L;
        }
        final long truncated = Long.parseLong(withoutSurroundingSpaces) % ceiling;
        return String.format(Locale.ROOT, "%0" + digits + "d", truncated);
    }

    /**
     * Reads the injected clock, standing in for {@code MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA} at
     * {@code app/cbl/CORPT00C.cbl:L215} and {@code :L241}.
     *
     * @param clock an injected fixed clock, obtained from {@link FixedClockProvider}
     * @return the date the clock reports in its own zone
     */
    private static LocalDate currentDate(final Clock clock) {
        Objects.requireNonNull(clock, "clock must not be null: FUNCTION CURRENT-DATE at "
                + "app/cbl/CORPT00C.cbl:L215 is the only clock read in this paragraph, and this tier "
                + "replaces it with an injected fixed clock rather than the ambient one");
        return LocalDate.ofInstant(clock.instant(), clock.getZone());
    }

    /**
     * Bounds the {@code FUNCTION INTEGER-OF-DATE} argument of {@code app/cbl/CORPT00C.cbl:L229-L230} by the
     * width of the field that supplies it.
     *
     * @param year the four digit year subfield after the rollover of {@code :L225-L228}
     * @param month the month subfield after the rollover
     * @param day the day subfield, always 1 at this point ({@code :L223})
     * @return the composed date
     * @throws DateTimeException when the composition is not a date a {@code PIC 9(04)} year could hold
     */
    private static LocalDate integerOfDateArgument(final int year, final int month, final int day) {
        if (year > MAX_FOUR_DIGIT_YEAR) {
            throw new DateTimeException("FUNCTION INTEGER-OF-DATE at app/cbl/CORPT00C.cbl:L229-L230 is "
                    + "supplied from WS-CURDATE-N, a redefinition of a PIC 9(04) year "
                    + "(app/cpy/CSDAT01Y.cpy:L20,L23); year " + year + " exceeds " + MAX_FOUR_DIGIT_YEAR
                    + " and is therefore not a representable standard date");
        }
        return LocalDate.of(year, month, day);
    }

    /**
     * Transliterates the monthly branch, {@code app/cbl/CORPT00C.cbl:L213-L238}.
     *
     * @param clock an injected fixed clock
     * @return the padded report name and both assembled dates
     * @throws IllegalStateException when the rolled forward date is unusable, wrapping the underlying
     * {@link DateTimeException} so the root cause survives
     */
    private static ReportPeriod monthlyPeriod(final Clock clock) {
        final LocalDate today = currentDate(clock);

        final String startDate = assembleDate(
                zonedDecimalMove(today.getYear(), ASSEMBLED_YEAR_WIDTH),
                zonedDecimalMove(today.getMonthValue(), ASSEMBLED_MONTH_DAY_WIDTH),
                FIRST_DAY_LITERAL);

        int rolledYear = today.getYear();
        int rolledMonth = today.getMonthValue() + 1;
        if (rolledMonth > LAST_MONTH_OF_YEAR) {
            rolledYear = rolledYear + 1;
            rolledMonth = 1;
        }

        final LocalDate lastDayOfMonth;
        try {
            lastDayOfMonth = integerOfDateArgument(rolledYear, rolledMonth, FIRST_DAY_OF_MONTH)
                    .minusDays(1);
        } catch (final DateTimeException cause) {
            throw new IllegalStateException("app/cbl/CORPT00C.cbl:L223-L234 cannot compute the last day "
                    + "of the month for a clock reading " + today + ": the rolled forward date "
                    + rolledYear + "-" + rolledMonth + "-" + FIRST_DAY_OF_MONTH + " is unusable. "
                    + ":L226 performs ADD 1 TO WS-CURDATE-YEAR with no ON SIZE ERROR clause, so the "
                    + "legacy PIC 9(04) field truncates silently here; this oracle reports instead, "
                    + "which is a labelled deviation rather than parity", cause);
        }

        final String endDate = assembleDate(
                zonedDecimalMove(lastDayOfMonth.getYear(), ASSEMBLED_YEAR_WIDTH),
                zonedDecimalMove(lastDayOfMonth.getMonthValue(), ASSEMBLED_MONTH_DAY_WIDTH),
                zonedDecimalMove(lastDayOfMonth.getDayOfMonth(), ASSEMBLED_MONTH_DAY_WIDTH));

        return new ReportPeriod(reportName(MONTHLY_LITERAL), startDate, endDate);
    }

    /**
     * Transliterates the yearly branch, {@code app/cbl/CORPT00C.cbl:L239-L255}.
     *
     * @param clock an injected fixed clock
     * @return the padded report name and both assembled dates
     */
    private static ReportPeriod yearlyPeriod(final Clock clock) {
        final String year = zonedDecimalMove(currentDate(clock).getYear(), ASSEMBLED_YEAR_WIDTH);
        return new ReportPeriod(
                reportName(YEARLY_LITERAL),
                assembleDate(year, FIRST_DAY_LITERAL, FIRST_DAY_LITERAL),
                assembleDate(year, LAST_MONTH_LITERAL, LAST_DAY_LITERAL));
    }

    /**
     * Transliterates the period cascade, {@code app/cbl/CORPT00C.cbl:L212-L442}.
     *
     * @param request the request carrying the three raw selector characters
     * @return the selected period, or {@link PeriodSelection#NONE}
     */
    private static PeriodSelection selectedPeriod(final ReportRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        if (!isSpacesOrLowValues(request.monthlySelected())) {
            return PeriodSelection.MONTHLY;
        }
        if (!isSpacesOrLowValues(request.yearlySelected())) {
            return PeriodSelection.YEARLY;
        }
        if (!isSpacesOrLowValues(request.customSelected())) {
            return PeriodSelection.CUSTOM;
        }
        return PeriodSelection.NONE;
    }

    /**
     * Transliterates the three tier custom range edit, {@code app/cbl/CORPT00C.cbl:L258-L426}.
     *
     * @param request the request carrying the six raw custom range components
     * @param startDateAcceptedByUtility the date utility's verdict on the assembled start date
     * @param endDateAcceptedByUtility the date utility's verdict on the assembled end date
     * @return the single reported message, or empty when every tier passes
     */
    private static Optional<String> firstCustomRangeFailure(final ReportRequest request,
            final boolean startDateAcceptedByUtility, final boolean endDateAcceptedByUtility) {
        Objects.requireNonNull(request, "request must not be null");

        // Tier 1 - the emptiness EVALUATE, :L258-L303, in map declaration order.
        if (isSpacesOrLowValues(request.startDateMonth())) {
            return Optional.of(START_DATE_MONTH_EMPTY);
        }
        if (isSpacesOrLowValues(request.startDateDay())) {
            return Optional.of(START_DATE_DAY_EMPTY);
        }
        if (isSpacesOrLowValues(request.startDateYear())) {
            return Optional.of(START_DATE_YEAR_EMPTY);
        }
        if (isSpacesOrLowValues(request.endDateMonth())) {
            return Optional.of(END_DATE_MONTH_EMPTY);
        }
        if (isSpacesOrLowValues(request.endDateDay())) {
            return Optional.of(END_DATE_DAY_EMPTY);
        }
        if (isSpacesOrLowValues(request.endDateYear())) {
            return Optional.of(END_DATE_YEAR_EMPTY);
        }

        // The NUMVAL-C round trip, :L305-L327.
        final String startMonth = numericRedisplay(request.startDateMonth(), ASSEMBLED_MONTH_DAY_WIDTH);
        final String startDay = numericRedisplay(request.startDateDay(), ASSEMBLED_MONTH_DAY_WIDTH);
        final String startYear = numericRedisplay(request.startDateYear(), ASSEMBLED_YEAR_WIDTH);
        final String endMonth = numericRedisplay(request.endDateMonth(), ASSEMBLED_MONTH_DAY_WIDTH);
        final String endDay = numericRedisplay(request.endDateDay(), ASSEMBLED_MONTH_DAY_WIDTH);
        final String endYear = numericRedisplay(request.endDateYear(), ASSEMBLED_YEAR_WIDTH);

        // Tier 2 - six independent per component IFs, :L329-L379. The month and day carry a character
        // ceiling; the year carries none, so a year is only tested for the numeric class.
        if (!isNumericClass(startMonth) || startMonth.compareTo(MONTH_CEILING) > 0) {
            return Optional.of(START_DATE_MONTH_INVALID);
        }
        if (!isNumericClass(startDay) || startDay.compareTo(DAY_CEILING) > 0) {
            return Optional.of(START_DATE_DAY_INVALID);
        }
        if (!isNumericClass(startYear)) {
            return Optional.of(START_DATE_YEAR_INVALID);
        }
        if (!isNumericClass(endMonth) || endMonth.compareTo(MONTH_CEILING) > 0) {
            return Optional.of(END_DATE_MONTH_INVALID);
        }
        if (!isNumericClass(endDay) || endDay.compareTo(DAY_CEILING) > 0) {
            return Optional.of(END_DATE_DAY_INVALID);
        }
        if (!isNumericClass(endYear)) {
            return Optional.of(END_DATE_YEAR_INVALID);
        }

        // Tier 3 - the whole date verdicts, :L388-L426, reachable only once every component passed.
        if (!startDateAcceptedByUtility) {
            return Optional.of(START_DATE_WHOLE_INVALID);
        }
        if (!endDateAcceptedByUtility) {
            return Optional.of(END_DATE_WHOLE_INVALID);
        }
        return Optional.empty();
    }

    /**
     * Assembles the custom period from the six raw components, {@code app/cbl/CORPT00C.cbl:L381-L386} and
     * {@code :L433}.
     *
     * @param request the request carrying the six raw custom range components
     * @return the padded report name and both assembled dates
     */
    private static ReportPeriod customPeriod(final ReportRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        return new ReportPeriod(
                reportName(CUSTOM_LITERAL),
                assembleDate(request.startDateYear(), request.startDateMonth(), request.startDateDay()),
                assembleDate(request.endDateYear(), request.endDateMonth(), request.endDateDay()));
    }

    /**
     * Transliterates the confirmation handshake, {@code app/cbl/CORPT00C.cbl:L464-L494}.
     *
     * @param confirmation the raw one character screen value
     * @return the outcome the source would take
     */
    private static ConfirmationOutcome confirmationOutcome(final String confirmation) {
        if (isSpacesOrLowValues(confirmation)) {
            return ConfirmationOutcome.BLANK;
        }
        if (AFFIRMATIVE_CONFIRMATIONS.contains(confirmation)) {
            return ConfirmationOutcome.AFFIRMATIVE;
        }
        if (NEGATIVE_CONFIRMATIONS.contains(confirmation)) {
            return ConfirmationOutcome.NEGATIVE;
        }
        return ConfirmationOutcome.INVALID;
    }

    /**
     * Builds the message each confirmation outcome reports.
     *
     * @param outcome the outcome from {@link #confirmationOutcome(String)}
     * @param paddedName the {@code WS-REPORT-NAME PIC X(10)} value
     * @param confirmation the raw one character screen value
     * @return the reported message, empty where the source leaves {@code WS-MESSAGE} blank
     */
    private static String confirmationMessage(final ConfirmationOutcome outcome, final String paddedName,
            final String confirmation) {
        return switch (outcome) {
            case BLANK -> "Please confirm to print the " + delimitedBySpace(paddedName) + " report...";
            case INVALID -> "\"" + delimitedBySpace(confirmation) + "\" is not a valid value to confirm...";
            case AFFIRMATIVE, NEGATIVE -> "";
        };
    }

    /**
     * Builds the success message of {@code app/cbl/CORPT00C.cbl:L449-L452}, reached only when the error flag is
     * still off after the cascade and the submission loop.
     *
     * @param paddedName the {@code WS-REPORT-NAME PIC X(10)} value
     * @return the message the screen displays in green ({@code :L448})
     */
    private static String submissionSuccessMessage(final String paddedName) {
        return delimitedBySpace(paddedName) + " report submitted for printing ...";
    }

    /**
     * Builds a request carrying only the six custom range components and the custom selector, leaving every
     * other component {@code null} so that an absent value is never confused with a supplied one.
     *
     * @param startMonth the raw {@code SDTMMI} value
     * @param startDay the raw {@code SDTDDI} value
     * @param startYear the raw {@code SDTYYYYI} value
     * @param endMonth the raw {@code EDTMMI} value
     * @param endDay the raw {@code EDTDDI} value
     * @param endYear the raw {@code EDTYYYYI} value
     * @return a request suitable for driving the custom range oracles
     */
    private static ReportRequest customRangeRequest(final String startMonth, final String startDay,
            final String startYear, final String endMonth, final String endDay, final String endYear) {
        return new ReportRequest(null, null, null, null, null, null,
                null, null, "S",
                startMonth, startDay, startYear, endMonth, endDay, endYear,
                null, null);
    }

    /**
     * Builds a request carrying only the three period selectors.
     *
     * @param monthly the raw {@code MONTHLYI} value
     * @param yearly the raw {@code YEARLYI} value
     * @param custom the raw {@code CUSTOMI} value
     * @return a request suitable for driving {@link #selectedPeriod(ReportRequest)}
     */
    private static ReportRequest selectorRequest(final String monthly, final String yearly,
            final String custom) {
        return new ReportRequest(null, null, null, null, null, null,
                monthly, yearly, custom,
                null, null, null, null, null, null,
                null, null);
    }

    /**
     * Builds a request whose every component is {@code null}, the shape a payload carrying no field at all
     * deserialises into.
     *
     * @return an entirely empty request
     */
    private static ReportRequest emptyRequest() {
        return new ReportRequest(null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);
    }

    /**
     * Resolves a record component by name, failing the test rather than returning {@code null} when the
     * contract has drifted.
     *
     * @param componentName the record component name
     * @return the component
     */
    private static RecordComponent componentNamed(final String componentName) {
        for (final RecordComponent component : ReportRequest.class.getRecordComponents()) {
            if (component.getName().equals(componentName)) {
                return component;
            }
        }
        throw new AssertionError("ReportRequest declares no component named '" + componentName
                + "'; the field contract of app/cpy-bms/CORPT00.CPY:24-120 has drifted");
    }

    /**
     * Resolves the private final field a record component is compiled into, failing the test rather than
     * propagating a checked reflection failure.
     *
     * @param componentName the record component name
     * @return the backing field
     */
    private static Field backingFieldOf(final String componentName) {
        try {
            return ReportRequest.class.getDeclaredField(componentName);
        } catch (final NoSuchFieldException cause) {
            throw new AssertionError("ReportRequest declares no field backing component '"
                    + componentName + "'", cause);
        }
    }

    /**
     * Reads the {@link Size#max()} a component declares, which must equal its {@code PIC X(n)} width.
     *
     * @param componentName the record component name
     * @return the declared maximum length
     */
    private static int declaredWidthOf(final String componentName) {
        final Method accessor = componentNamed(componentName).getAccessor();
        final Size onAccessor = accessor.getAnnotation(Size.class);
        if (onAccessor == null) {
            throw new AssertionError("ReportRequest accessor '" + componentName + "()' declares no "
                    + "@Size, so its app/cpy-bms/CORPT00.CPY width is no longer asserted at the "
                    + "boundary");
        }
        final Size onField = backingFieldOf(componentName).getAnnotation(Size.class);
        if (onField == null) {
            throw new AssertionError("ReportRequest field '" + componentName + "' declares no @Size, so "
                    + "Hibernate Validator would not enforce its app/cpy-bms/CORPT00.CPY width");
        }
        if (onField.max() != onAccessor.max()) {
            throw new AssertionError("ReportRequest component '" + componentName + "' declares "
                    + onField.max() + " on its field but " + onAccessor.max() + " on its accessor");
        }
        return onAccessor.max();
    }

    /**
     * @return the 17 component names, lower cased, for substring screening.
     */
    private static List<String> lowerCasedComponentNames() {
        return List.of(ReportRequest.class.getRecordComponents()).stream()
                .map(component -> component.getName().toLowerCase(Locale.ROOT))
                .toList();
    }

    @Nested
    @DisplayName("1. Field contract - app/cpy-bms/CORPT00.CPY group CORPT0AI")
    class FieldContract {

        @Test
        @DisplayName("declares exactly 17 components, the input field count of CORPT0AI")
        void declaresExactlySeventeenComponents() {
            assertThat(ReportRequest.class.getRecordComponents())
                    .as("app/cpy-bms/CORPT00.CPY declares 17 data fields between the CORPT0AI header "
                            + "FILLER PIC X(12) at line 18 and the CORPT0AO REDEFINES at line 121")
                    .hasSize(17);
        }

        @Test
        @DisplayName("names its components in symbolic map declaration order")
        void namesComponentsInSymbolicMapDeclarationOrder() {
            assertThat(List.of(ReportRequest.class.getRecordComponents()).stream()
                    .map(RecordComponent::getName)
                    .toList())
                    .as("declaration order is load bearing: it is the order the emptiness cascade at "
                            + "app/cbl/CORPT00C.cbl:L259-L300 reports failures in")
                    .containsExactlyElementsOf(COMPONENT_NAMES);
        }

        @Test
        @DisplayName("types every component as String, never a temporal or numeric type")
        void typesEveryComponentAsString() {
            for (final RecordComponent component : ReportRequest.class.getRecordComponents()) {
                assertThat(component.getType())
                        .as("component '%s' derives from a PIC X(n) alphanumeric screen field, and the "
                                + "downstream sort filter at app/proc/TRANREPT.prc:L45-L46 compares its "
                                + "value as characters, not as an instant", component.getName())
                        .isEqualTo(String.class);
            }
        }

        @Test
        @DisplayName("carries a @Size max equal to each PIC X(n) width, byte exactly")
        void carriesTheDeclaredWidthOfEveryPicClause() {
            for (int index = 0; index < COMPONENT_NAMES.size(); index++) {
                final String componentName = COMPONENT_NAMES.get(index);
                assertThat(declaredWidthOf(componentName))
                        .as("app/cpy-bms/CORPT00.CPY declares '%s' as PIC X(%d)", componentName,
                                COMPONENT_WIDTHS.get(index))
                        .isEqualTo(COMPONENT_WIDTHS.get(index));
            }
        }

        @Test
        @DisplayName("caps errorMessage at 78, not the program's 80 byte work area")
        void capsErrorMessageAtSeventyEightNotEighty() {
            assertThat(declaredWidthOf("errorMessage"))
                    .as("app/cpy-bms/CORPT00.CPY:120 declares ERRMSGI PIC X(78) while "
                            + "app/cbl/CORPT00C.cbl:L39 declares WS-MESSAGE PIC X(80); the move at "
                            + ":L560 truncates two bytes onto the wire and that truncation is the "
                            + "contract, so the field must not be widened")
                    .isEqualTo(SCREEN_MESSAGE_WIDTH)
                    .isNotEqualTo(MESSAGE_WORK_AREA_WIDTH);
            assertThat(MESSAGE_WORK_AREA_WIDTH - SCREEN_MESSAGE_WIDTH)
                    .as("the silent truncation is exactly two bytes wide")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("carries the three period selectors as three independent X(1) components")
        void carriesThreeIndependentSingleCharacterSelectors() {
            assertThat(SELECTOR_COMPONENT_NAMES).hasSize(3);
            for (final String selectorName : SELECTOR_COMPONENT_NAMES) {
                assertThat(componentNamed(selectorName).getType()).isEqualTo(String.class);
                assertThat(declaredWidthOf(selectorName))
                        .as("app/cpy-bms/CORPT00.CPY declares '%s' as PIC X(1)", selectorName)
                        .isEqualTo(1);
            }
            final ReportRequest allThreeTicked = selectorRequest("Y", "Y", "Y");
            assertThat(allThreeTicked.monthlySelected()).isEqualTo("Y");
            assertThat(allThreeTicked.yearlySelected()).isEqualTo("Y");
            assertThat(allThreeTicked.customSelected()).isEqualTo("Y");
            assertThat(lowerCasedComponentNames())
                    .as("a single enum valued selector could not represent two selectors set at once, "
                            + "which app/cbl/CORPT00C.cbl:L212-L256 accepts without complaint")
                    .doesNotContain("reporttype", "reportperiod", "period", "reportkind");
        }

        @Test
        @DisplayName("collects the six custom range components in month, day, year order")
        void collectsTheSixCustomRangeComponentsInMonthDayYearOrder() {
            final List<String> declared = List.of(ReportRequest.class.getRecordComponents()).stream()
                    .map(RecordComponent::getName)
                    .filter(CUSTOM_RANGE_COMPONENT_NAMES::contains)
                    .toList();
            assertThat(declared)
                    .as("app/cpy-bms/CORPT00.CPY collects SDTMMI:78, SDTDDI:84, SDTYYYYI:90 then "
                            + "EDTMMI:96, EDTDDI:102, EDTYYYYI:108 - month, day, year, not ISO order")
                    .containsExactlyElementsOf(CUSTOM_RANGE_COMPONENT_NAMES);
            assertThat(declaredWidthOf("startDateMonth")).isEqualTo(ASSEMBLED_MONTH_DAY_WIDTH);
            assertThat(declaredWidthOf("startDateDay")).isEqualTo(ASSEMBLED_MONTH_DAY_WIDTH);
            assertThat(declaredWidthOf("startDateYear")).isEqualTo(ASSEMBLED_YEAR_WIDTH);
            assertThat(declaredWidthOf("endDateMonth")).isEqualTo(ASSEMBLED_MONTH_DAY_WIDTH);
            assertThat(declaredWidthOf("endDateDay")).isEqualTo(ASSEMBLED_MONTH_DAY_WIDTH);
            assertThat(declaredWidthOf("endDateYear")).isEqualTo(ASSEMBLED_YEAR_WIDTH);
            assertThat(lowerCasedComponentNames())
                    .as("the components must not be merged into two dates: each is edited and marked "
                            + "individually at app/cbl/CORPT00C.cbl:L259-L379")
                    .doesNotContain("startdate", "enddate", "daterange");
        }

        @Test
        @DisplayName("declares the six header fields inline, with no shared supertype or mixin")
        void declaresTheSixHeaderFieldsInline() {
            assertThat(HEADER_COMPONENT_NAMES).hasSize(6);
            assertThat(ReportRequest.class.getSuperclass())
                    .as("a shared header base class would have to assert a CURTIMEI width that "
                            + "app/cpy-bms/COSGN00.CPY:54 contradicts")
                    .isEqualTo(Record.class);
            assertThat(ReportRequest.class.getInterfaces())
                    .as("no header mixin interface may be implemented, for the same reason")
                    .isEmpty();
            assertThat(declaredWidthOf("currentTime"))
                    .as("app/cpy-bms/CORPT00.CPY:54 declares CURTIMEI PIC X(8); "
                            + "app/cpy-bms/COSGN00.CPY:54 alone declares it PIC X(9), which is exactly "
                            + "why the header cannot be factored out")
                    .isEqualTo(8)
                    .isNotEqualTo(9);
            for (final String headerName : HEADER_COMPONENT_NAMES) {
                assertThat(componentNamed(headerName).getDeclaringRecord())
                        .as("header component '%s' is declared by ReportRequest itself", headerName)
                        .isEqualTo(ReportRequest.class);
            }
            final ReportRequest header = new ReportRequest("CR00", "Report Submission",
                    alphanumericMove("06/10/22", 8), "CORPT00C", "CardDemo",
                    alphanumericMove("19:27:53", 8), null, null, "S", null, null, null, null, null,
                    null, null, null);
            assertThat(header.transactionName())
                    .as("app/cbl/CORPT00C.cbl:L38 declares WS-TRANID VALUE 'CR00' and moves it to "
                            + "TRNNAMEI, so the four character transaction name round trips verbatim")
                    .isEqualTo("CR00");
            assertThat(header.title01()).isEqualTo("Report Submission");
            assertThat(header.currentDate()).isEqualTo("06/10/22").hasSize(8);
            assertThat(header.programName())
                    .as("app/cbl/CORPT00C.cbl:L37 declares WS-PGMNAME VALUE 'CORPT00C'")
                    .isEqualTo("CORPT00C")
                    .hasSize(8);
            assertThat(header.title02()).isEqualTo("CardDemo");
            assertThat(header.currentTime())
                    .as("eight characters here, where app/cpy-bms/COSGN00.CPY:54 would allow nine")
                    .isEqualTo("19:27:53")
                    .hasSize(8);
            assertThat(violationsOf(header))
                    .as("a realistically populated header is valid input")
                    .isEmpty();
        }

        @Test
        @DisplayName("carries the width guard on the field and accessor, not on the record component")
        void carriesTheWidthGuardWhereHibernateValidatorLooksForIt() {
            final RecordComponent component = componentNamed("errorMessage");
            assertThat(component.getAnnotations())
                    .as("jakarta.validation-api 3.0.2 targets METHOD, FIELD, ANNOTATION_TYPE, "
                            + "CONSTRUCTOR, PARAMETER and TYPE_USE only - it predates "
                            + "ElementType.RECORD_COMPONENT - so @Size is never applicable to a record "
                            + "component and reflecting on the component alone finds nothing")
                    .isEmpty();
            assertThat(component.getAccessor().getAnnotation(Size.class))
                    .as("JLS 8.10.1 propagates it onto the accessor, which is one of the two places "
                            + "Hibernate Validator reads")
                    .isNotNull();
            assertThat(backingFieldOf("errorMessage").getAnnotation(Size.class))
                    .as("and onto the backing field, which is the other")
                    .isNotNull();
            assertThat(declaredWidthOf("errorMessage"))
                    .as("both surviving copies must agree, or the enforced width would depend on which "
                            + "one the provider happened to read")
                    .isEqualTo(SCREEN_MESSAGE_WIDTH);
        }

        @Test
        @DisplayName("actually enforces every declared width through a real Bean Validation run")
        void actuallyEnforcesEveryDeclaredWidth() {
            for (int index = 0; index < COMPONENT_NAMES.size(); index++) {
                final int width = COMPONENT_WIDTHS.get(index);
                final String[] values = new String[COMPONENT_NAMES.size()];
                values[index] = "x".repeat(width + 1);
                final ReportRequest oversized = new ReportRequest(values[0], values[1], values[2],
                        values[3], values[4], values[5], values[6], values[7], values[8], values[9],
                        values[10], values[11], values[12], values[13], values[14], values[15],
                        values[16]);
                assertThat(violationsOf(oversized))
                        .as("one character over PIC X(%d) on '%s' must be rejected, so the copybook "
                                + "width is enforced rather than merely documented", width,
                                COMPONENT_NAMES.get(index))
                        .hasSize(1);
                values[index] = "x".repeat(width);
                final ReportRequest exact = new ReportRequest(values[0], values[1], values[2], values[3],
                        values[4], values[5], values[6], values[7], values[8], values[9], values[10],
                        values[11], values[12], values[13], values[14], values[15], values[16]);
                assertThat(violationsOf(exact))
                        .as("exactly PIC X(%d) characters on '%s' must be accepted", width,
                                COMPONENT_NAMES.get(index))
                        .isEmpty();
                values[index] = width == 1 ? "" : "x".repeat(width - 1);
                final ReportRequest oneUnder = new ReportRequest(values[0], values[1], values[2],
                        values[3], values[4], values[5], values[6], values[7], values[8], values[9],
                        values[10], values[11], values[12], values[13], values[14], values[15],
                        values[16]);
                assertThat(violationsOf(oneUnder))
                        .as("one character under PIC X(%d) on '%s' must also be accepted: a COBOL "
                                + "alphanumeric field is space filled, never minimum length", width,
                                COMPONENT_NAMES.get(index))
                        .isEmpty();
            }
        }

        @Test
        @DisplayName("preserves trailing space padding verbatim, exactly as the terminal sends it")
        void preservesTrailingSpacePaddingVerbatim() {
            final String paddedTransactionName = alphanumericMove("CR", 4);
            final String paddedProgramName = alphanumericMove("CORPT00C", 8);
            final String paddedConfirmation = alphanumericMove("", 1);
            final ReportRequest padded = new ReportRequest(paddedTransactionName, null, null,
                    paddedProgramName, null, null, null, null, alphanumericMove("S", 1),
                    alphanumericMove("6", 2), alphanumericMove("1", 2), alphanumericMove("22", 4),
                    null, null, null, paddedConfirmation, null);
            assertThat(violationsOf(padded))
                    .as("a field padded to exactly its PIC X(n) width is valid input")
                    .isEmpty();
            assertThat(padded.transactionName())
                    .as("the trailing spaces a 3270 field carries are part of the value and must survive "
                            + "transport untouched; trimming them would change what the emptiness "
                            + "relation of app/cbl/CORPT00C.cbl:L259 sees")
                    .isEqualTo("CR  ")
                    .hasSize(4);
            assertThat(padded.programName()).isEqualTo("CORPT00C").hasSize(8);
            assertThat(padded.startDateMonth()).isEqualTo("6 ");
            assertThat(padded.startDateDay()).isEqualTo("1 ");
            assertThat(padded.startDateYear()).isEqualTo("22  ");
            assertThat(padded.confirmation())
                    .as("a one character field padded from an empty value is a single space, which is "
                            + "the BLANK state rather than the absent one")
                    .isEqualTo(" ");
            assertThat(customPeriod(padded).startDate())
                    .as("the padded components assemble into a ten character group whose spaces are "
                            + "exactly where the terminal left them")
                    .isEqualTo("22  -6 -1 ");
        }

        @Test
        @DisplayName("models the data field of each quintuple and none of its four companions")
        void modelsOnlyTheDataFieldOfEachQuintuple() {
            assertThat(lowerCasedComponentNames())
                    .as("app/cpy-bms/CORPT00.CPY:19-23 generates a COMP PIC S9(4) length halfword, an "
                            + "attribute byte, a redefined attribute alias and four reserved bytes ahead "
                            + "of every data field; none of them is a DTO component")
                    .noneMatch(name -> name.endsWith("length"))
                    .noneMatch(name -> name.endsWith("attribute"))
                    .noneMatch(name -> name.endsWith("flag"))
                    .noneMatch(name -> name.contains("filler"))
                    .noneMatch(name -> name.contains("reserved"));
        }
    }

    @Nested
    @DisplayName("2. Monthly period - app/cbl/CORPT00C.cbl:L213-L238, a full calendar month")
    class MonthlyPeriod {

        @Test
        @DisplayName("starts on the first day of the clock's month")
        void startsOnTheFirstDayOfTheClocksMonth() {
            final ReportPeriod period = monthlyPeriod(
                    FixedClockProvider.fixedClock(Instant.parse("2022-06-10T19:27:53Z")));
            assertThat(period.startDate())
                    .as("app/cbl/CORPT00C.cbl:L217-L219 moves the clock year and month, then the "
                            + "literal '01' into the day subfield")
                    .isEqualTo("2022-06-01");
        }

        @Test
        @DisplayName("ends on the LAST day of the clock's month, not the clock's day")
        void endsOnTheLastDayOfTheMonthNotTheClockDay() {
            final Instant midMonth = Instant.parse("2022-06-10T19:27:53Z");
            final ReportPeriod period = monthlyPeriod(FixedClockProvider.fixedClock(midMonth));
            assertThat(period.endDate())
                    .as("app/cbl/CORPT00C.cbl:L223-L230 sets the day to 1, advances the month and "
                            + "subtracts one day in place over the redefinition at "
                            + "app/cpy/CSDAT01Y.cpy:L23, so the end is 30 June - NOT the clock's 10th")
                    .isEqualTo("2022-06-30");
            assertThat(period.endDate())
                    .as("month-to-date would have produced the clock's own day")
                    .isNotEqualTo("2022-06-10");
        }

        @ParameterizedTest(name = "{0} -> {1} .. {2}")
        @DisplayName("spans the whole calendar month for every month length, including both Februaries")
        @CsvSource({
            "2023-01-17T00:00:00Z, 2023-01-01, 2023-01-31",
            "2023-02-14T23:59:59Z, 2023-02-01, 2023-02-28",
            "2024-02-14T23:59:59Z, 2024-02-01, 2024-02-29",
            "2023-04-30T12:00:00Z, 2023-04-01, 2023-04-30",
            "2023-06-01T00:00:00Z, 2023-06-01, 2023-06-30",
            "2023-07-31T23:00:00Z, 2023-07-01, 2023-07-31",
            "2023-09-15T06:30:00Z, 2023-09-01, 2023-09-30",
            "2023-12-25T18:00:00Z, 2023-12-01, 2023-12-31",
            "2100-02-05T00:00:00Z, 2100-02-01, 2100-02-28",
            "2000-02-05T00:00:00Z, 2000-02-01, 2000-02-29"})
        void spansTheWholeCalendarMonthForEveryMonthLength(final String instant,
                final String expectedStart, final String expectedEnd) {
            final ReportPeriod period =
                    monthlyPeriod(FixedClockProvider.fixedClock(Instant.parse(instant)));
            assertThat(period.startDate()).isEqualTo(expectedStart);
            assertThat(period.endDate())
                    .as("2100 is a non leap century year and 2000 is a leap century year, so both "
                            + "Gregorian exceptions are covered")
                    .isEqualTo(expectedEnd);
        }

        @Test
        @DisplayName("rolls the year in December and lands back on 31 December of the original year")
        void rollsTheYearInDecemberAndLandsOnThirtyFirstDecember() {
            final ReportPeriod period = monthlyPeriod(
                    FixedClockProvider.fixedClock(Instant.parse("2022-12-01T00:00:00Z")));
            assertThat(period.startDate()).isEqualTo("2022-12-01");
            assertThat(period.endDate())
                    .as("app/cbl/CORPT00C.cbl:L224 makes the month 13, :L225-L228 increments the year "
                            + "to 2023 and resets the month to 1, and :L229-L230 subtracts one day from "
                            + "2023-01-01 - which is 31 December 2022, the ORIGINAL year")
                    .isEqualTo("2022-12-31");
        }

        @Test
        @DisplayName("keeps the January boundary inside the same year")
        void keepsTheJanuaryBoundaryInsideTheSameYear() {
            final ReportPeriod period = monthlyPeriod(
                    FixedClockProvider.fixedClock(Instant.parse("2022-01-31T23:59:59Z")));
            assertThat(period.startDate()).isEqualTo("2022-01-01");
            assertThat(period.endDate())
                    .as("app/cbl/CORPT00C.cbl:L225 does not fire for January, so no year rollover "
                            + "occurs")
                    .isEqualTo("2022-01-31");
        }

        @Test
        @DisplayName("names the period 'Monthly' padded into WS-REPORT-NAME PIC X(10)")
        void namesThePeriodMonthlyPaddedIntoTheTenCharacterWorkArea() {
            final ReportPeriod period = monthlyPeriod(FixedClockProvider.canonicalClock());
            assertThat(period.reportName())
                    .as("app/cbl/CORPT00C.cbl:L214 moves the literal 'Monthly' into the PIC X(10) field "
                            + "declared at :L58")
                    .isEqualTo("Monthly   ")
                    .hasSize(REPORT_NAME_WIDTH);
            assertThat(submissionSuccessMessage(period.reportName()))
                    .as("app/cbl/CORPT00C.cbl:L449-L452 strings the name DELIMITED BY SPACE, so the "
                            + "padding never reaches the message")
                    .isEqualTo("Monthly report submitted for printing ...");
        }

        @Test
        @DisplayName("is a pure function of the injected clock, never of the ambient one")
        void isAPureFunctionOfTheInjectedClock() {
            final Clock clock = FixedClockProvider.fixedClock(Instant.parse("2024-02-29T12:00:00Z"));
            final ReportPeriod first = monthlyPeriod(clock);
            final ReportPeriod second = monthlyPeriod(clock);
            assertThat(second)
                    .as("two reads of the same fixed clock must agree, which is what makes the leap "
                            + "year and December cases reproducible rather than calendar dependent")
                    .isEqualTo(first);
            final ReportPeriod elsewhere = monthlyPeriod(
                    FixedClockProvider.fixedClock(Instant.parse("2024-03-01T12:00:00Z")));
            assertThat(elsewhere)
                    .as("a different clock must produce a different period, proving the clock is "
                            + "actually read")
                    .isNotEqualTo(first);
            assertThat(first.endDate()).isEqualTo("2024-02-29");
            assertThat(elsewhere.endDate()).isEqualTo("2024-03-31");
        }

        @Test
        @DisplayName("resolves the date in the clock's own zone, not the host default")
        void resolvesTheDateInTheClocksOwnZone() {
            final Instant lateUtc = Instant.parse("2022-06-30T23:30:00Z");
            final ReportPeriod inUtc = monthlyPeriod(FixedClockProvider.fixedClock(lateUtc));
            final ReportPeriod inPlusTwo = monthlyPeriod(
                    FixedClockProvider.fixedClock(lateUtc, ZoneOffset.ofHours(2)));
            assertThat(inUtc.endDate())
                    .as("the same instant is 30 June in UTC")
                    .isEqualTo("2022-06-30");
            assertThat(inPlusTwo.endDate())
                    .as("and 1 July two hours east, which is precisely why the zone is stated on the "
                            + "clock rather than inherited from the host")
                    .isEqualTo("2022-07-31");
        }

        @Test
        @DisplayName("reports the December 9999 overflow with context and preserves the root cause")
        void reportsTheDecemberOverflowWithContextAndPreservesTheCause() {
            final Clock endOfCalendar =
                    FixedClockProvider.fixedClock(Instant.parse("9999-12-31T00:00:00Z"));
            assertThatThrownBy(() -> monthlyPeriod(endOfCalendar))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("app/cbl/CORPT00C.cbl:L223-L234")
                    .hasMessageContaining("ADD 1 TO WS-CURDATE-YEAR with no ON SIZE ERROR")
                    .hasMessageContaining("10000-1-1")
                    .cause()
                    .as("the low level fault is reported by the intrinsic domain check and the caller "
                            + "adds the locator context, so the root cause survives rather than being "
                            + "swallowed")
                    .isInstanceOf(DateTimeException.class)
                    .hasMessageContaining("FUNCTION INTEGER-OF-DATE")
                    .hasMessageContaining("PIC 9(04)");
        }

        @Test
        @DisplayName("rejects a clock outside the calendar a PIC 9(04) year can represent")
        void rejectsAClockOutsideTheFourDigitYearCalendar() {
            final Clock beyondFourDigits = FixedClockProvider.fixedClock(
                    LocalDate.of(10_000, 6, 15).atStartOfDay(ZoneOffset.UTC).toInstant());
            assertThatThrownBy(() -> monthlyPeriod(beyondFourDigits))
                    .as("app/cpy/CSDAT01Y.cpy:L20 declares WS-CURDATE-YEAR PIC 9(04), so a five digit "
                            + "year is reported rather than silently truncated to 0000")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("app/cpy/CSDAT01Y.cpy:L20-L22")
                    .hasMessageContaining("PIC 9(04)")
                    .hasMessageContaining("10000");
            assertThatThrownBy(() -> monthlyPeriod(FixedClockProvider.fixedClock(Instant.MAX)))
                    .as("and a clock beyond the ISO calendar itself surfaces the underlying domain "
                            + "fault rather than being coerced into a plausible looking date")
                    .isInstanceOf(DateTimeException.class);
        }

        @Test
        @DisplayName("refuses a null clock rather than falling back to the ambient one")
        void refusesANullClock() {
            assertThatThrownBy(() -> monthlyPeriod(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("FUNCTION CURRENT-DATE")
                    .hasMessageContaining("injected fixed clock");
        }

        @Test
        @DisplayName("produces two ten character dates the record's components can transport")
        void producesTwoTenCharacterDatesTheRecordCanTransport() {
            final ReportPeriod period = monthlyPeriod(FixedClockProvider.canonicalClock());
            assertThat(period.startDate()).hasSize(ASSEMBLED_DATE_WIDTH);
            assertThat(period.endDate()).hasSize(ASSEMBLED_DATE_WIDTH);
            final ReportRequest echoed = new ReportRequest(null, null, null, null, null, null,
                    "S", null, null,
                    period.startDate().substring(5, 7), period.startDate().substring(8, 10),
                    period.startDate().substring(0, 4),
                    period.endDate().substring(5, 7), period.endDate().substring(8, 10),
                    period.endDate().substring(0, 4),
                    null, null);
            assertThat(customPeriod(echoed).startDate())
                    .as("the six X(2)/X(2)/X(4) components round trip a derived period without loss")
                    .isEqualTo(period.startDate());
            assertThat(customPeriod(echoed).endDate()).isEqualTo(period.endDate());
        }
    }

    @Nested
    @DisplayName("3. Yearly period and the period cascade - app/cbl/CORPT00C.cbl:L212-L442")
    class YearlyPeriodAndCascade {

        @Test
        @DisplayName("spans 1 January to 31 December of the clock's year")
        void spansTheWholeClockYear() {
            final ReportPeriod period = yearlyPeriod(
                    FixedClockProvider.fixedClock(Instant.parse("2022-06-10T19:27:53Z")));
            assertThat(period.startDate())
                    .as("app/cbl/CORPT00C.cbl:L243-L246 moves the clock year and the literal '01' into "
                            + "both the month and the day subfields")
                    .isEqualTo("2022-01-01");
            assertThat(period.endDate())
                    .as("app/cbl/CORPT00C.cbl:L250-L251 moves the literals '12' and '31'")
                    .isEqualTo("2022-12-31");
        }

        @Test
        @DisplayName("uses literal two character month and day moves, never a computed month end")
        void usesLiteralMonthAndDayMoves() {
            assertThat(FIRST_DAY_LITERAL).isEqualTo("01").hasSize(ASSEMBLED_MONTH_DAY_WIDTH);
            assertThat(LAST_MONTH_LITERAL).isEqualTo("12").hasSize(ASSEMBLED_MONTH_DAY_WIDTH);
            assertThat(LAST_DAY_LITERAL).isEqualTo("31").hasSize(ASSEMBLED_MONTH_DAY_WIDTH);
            final ReportPeriod leapYear = yearlyPeriod(
                    FixedClockProvider.fixedClock(Instant.parse("2024-02-29T00:00:00Z")));
            assertThat(leapYear.endDate())
                    .as("the literals at app/cbl/CORPT00C.cbl:L250-L251 are moved unconditionally, so no "
                            + "month length and no leap rule can influence the yearly end date")
                    .isEqualTo("2024-12-31");
        }

        @ParameterizedTest(name = "{0} -> {1}-01-01 .. {1}-12-31")
        @DisplayName("reads only the year from the injected clock")
        @CsvSource({
            "2022-01-01T00:00:00Z, 2022",
            "2022-12-31T23:59:59Z, 2022",
            "2024-02-29T12:00:00Z, 2024",
            "1999-07-04T06:00:00Z, 1999"})
        void readsOnlyTheYearFromTheClock(final String instant, final String expectedYear) {
            final ReportPeriod period =
                    yearlyPeriod(FixedClockProvider.fixedClock(Instant.parse(instant)));
            assertThat(period.startDate()).isEqualTo(expectedYear + "-01-01");
            assertThat(period.endDate()).isEqualTo(expectedYear + "-12-31");
        }

        @Test
        @DisplayName("names the period 'Yearly' padded into WS-REPORT-NAME PIC X(10)")
        void namesThePeriodYearlyPaddedIntoTheTenCharacterWorkArea() {
            assertThat(yearlyPeriod(FixedClockProvider.canonicalClock()).reportName())
                    .as("app/cbl/CORPT00C.cbl:L240 moves 'Yearly' into the PIC X(10) field of :L58")
                    .isEqualTo("Yearly    ")
                    .hasSize(REPORT_NAME_WIDTH);
        }

        @Test
        @DisplayName("names the custom period 'Custom' - the literal IS set, at :L433")
        void namesTheCustomPeriodCustom() {
            assertThat(customPeriod(customRangeRequest("01", "01", "2022", "12", "31", "2022"))
                    .reportName())
                    .as("app/cbl/CORPT00C.cbl:L433 moves 'Custom' to WS-REPORT-NAME after the edits "
                            + "pass, so the custom report name is NOT 'Not available'")
                    .isEqualTo("Custom    ")
                    .hasSize(REPORT_NAME_WIDTH);
            assertThat(submissionSuccessMessage(reportName(CUSTOM_LITERAL)))
                    .isEqualTo("Custom report submitted for printing ...");
        }

        @Test
        @DisplayName("pads all three report names to exactly ten characters")
        void padsAllThreeReportNamesToTenCharacters() {
            for (final String literal : List.of(MONTHLY_LITERAL, YEARLY_LITERAL, CUSTOM_LITERAL)) {
                assertThat(reportName(literal))
                        .as("WS-REPORT-NAME is PIC X(10) at app/cbl/CORPT00C.cbl:L58")
                        .hasSize(REPORT_NAME_WIDTH)
                        .startsWith(literal);
                assertThat(delimitedBySpace(reportName(literal)))
                        .as("STRING ... DELIMITED BY SPACE at :L449 strips the padding again")
                        .isEqualTo(literal);
            }
        }

        @Test
        @DisplayName("lets monthly win silently when every selector is ticked - no exclusivity error")
        void letsMonthlyWinWhenEverySelectorIsTicked() {
            assertThat(selectedPeriod(selectorRequest("Y", "Y", "Y")))
                    .as("app/cbl/CORPT00C.cbl:L212 is EVALUATE TRUE, so :L213 matches first and :L239 "
                            + "and :L256 are never reached; the source raises no mutual exclusivity "
                            + "error and none may be invented")
                    .isEqualTo(PeriodSelection.MONTHLY);
            assertThat(selectedPeriod(selectorRequest(" ", "Y", "Y")))
                    .as("with monthly blank, yearly at :L239 wins over custom at :L256")
                    .isEqualTo(PeriodSelection.YEARLY);
            assertThat(selectedPeriod(selectorRequest(" ", " ", "Y")))
                    .isEqualTo(PeriodSelection.CUSTOM);
        }

        @Test
        @DisplayName("carries all three ticks on the record even though only one is honoured")
        void carriesAllThreeTicksOnTheRecord() {
            final ReportRequest allTicked = selectorRequest("Y", "Y", "Y");
            assertThat(selectedPeriod(allTicked)).isEqualTo(PeriodSelection.MONTHLY);
            assertThat(allTicked.yearlySelected())
                    .as("the losing selectors are still transported verbatim; the record does not "
                            + "normalise them away, because :L639-L641 clears them only on re-display")
                    .isEqualTo("Y");
            assertThat(allTicked.customSelected()).isEqualTo("Y");
            assertThat(violationsOf(allTicked))
                    .as("three simultaneous single character ticks are structurally valid input")
                    .isEmpty();
        }

        @ParameterizedTest(name = "MONTHLYI = [{0}]")
        @DisplayName("accepts ANY non blank character as a tick, not only 'Y'")
        @ValueSource(strings = {"Y", "y", "S", "s", "X", "x", "1", "0", "*", "-", "/", "?", "#", "."})
        void acceptsAnyNonBlankCharacterAsATick(final String tick) {
            assertThat(selectedPeriod(selectorRequest(tick, null, null)))
                    .as("app/cbl/CORPT00C.cbl:L213 tests NOT = SPACES AND LOW-VALUES, an abbreviated "
                            + "combined relation expanding to NOT = SPACES AND NOT = LOW-VALUES; any "
                            + "other character therefore selects the period")
                    .isEqualTo(PeriodSelection.MONTHLY);
        }

        @ParameterizedTest(name = "MONTHLYI = [{0}] does not tick")
        @DisplayName("treats spaces and low values as untouched, and only those")
        @ValueSource(strings = {"", " ", "\u0000"})
        void treatsSpacesAndLowValuesAsUntouched(final String untouched) {
            assertThat(selectedPeriod(selectorRequest(untouched, null, null)))
                    .as("app/cbl/CORPT00C.cbl:L213 excludes exactly the two figurative constants")
                    .isEqualTo(PeriodSelection.NONE);
        }

        @Test
        @DisplayName("reaches WHEN OTHER with no selector at all, and applies no default period")
        void reachesWhenOtherWithNoSelectorAtAll() {
            assertThat(selectedPeriod(emptyRequest()))
                    .as("app/cbl/CORPT00C.cbl:L437 is the WHEN OTHER branch; :L438 sets the message and "
                            + ":L439 raises the error flag, so no period is defaulted")
                    .isEqualTo(PeriodSelection.NONE);
            assertThat(selectedPeriod(selectorRequest(String.valueOf(LOW_VALUE), " ", "")))
                    .as("a mix of low values, a space and an empty field still selects nothing")
                    .isEqualTo(PeriodSelection.NONE);
            assertThat(NO_REPORT_TYPE_SELECTED)
                    .as("app/cbl/CORPT00C.cbl:L438 sets this literal verbatim, ellipsis included")
                    .isEqualTo("Select a report type to print report...")
                    .endsWith("...")
                    .hasSizeLessThanOrEqualTo(SCREEN_MESSAGE_WIDTH);
        }

        @Test
        @DisplayName("keeps the cascade a pure function of the three selector characters")
        void keepsTheCascadeAPureFunctionOfTheSelectors() {
            final ReportRequest request = selectorRequest(null, "Y", null);
            assertThat(selectedPeriod(request)).isEqualTo(selectedPeriod(request));
            assertThatThrownBy(() -> selectedPeriod(null))
                    .as("no ambient state may substitute for the absent request")
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("request must not be null");
        }
    }

    @Nested
    @DisplayName("4. Custom range message literals - app/cbl/CORPT00C.cbl:L258-L426")
    class CustomRangeMessageLiterals {

        @Test
        @DisplayName("declares exactly fourteen distinct literals, six plus six plus two")
        void declaresExactlyFourteenDistinctLiterals() {
            assertThat(CUSTOM_RANGE_MESSAGES)
                    .as("six emptiness messages at :L261,:L268,:L275,:L282,:L289,:L296, six component "
                            + "messages at :L331,:L340,:L348,:L357,:L366,:L374, and two whole date "
                            + "messages at :L400 and :L420")
                    .hasSize(14)
                    .doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("ends every literal in three dots and fits the X(78) screen field")
        void endsEveryLiteralInThreeDotsAndFitsTheScreenField() {
            for (final String message : CUSTOM_RANGE_MESSAGES) {
                assertThat(message)
                        .as("the ellipsis is part of the literal in the source")
                        .endsWith("...")
                        .doesNotEndWith("....")
                        .doesNotContain("\u2026");
                assertThat(message.length())
                        .as("message '%s' must survive the MOVE into ERRMSGI PIC X(78)", message)
                        .isLessThanOrEqualTo(SCREEN_MESSAGE_WIDTH);
            }
        }

        @Test
        @DisplayName("keeps the deliberate upper case NOT in the six emptiness literals")
        void keepsTheUpperCaseNotInTheEmptinessLiterals() {
            for (final String message : List.of(START_DATE_MONTH_EMPTY, START_DATE_DAY_EMPTY,
                    START_DATE_YEAR_EMPTY, END_DATE_MONTH_EMPTY, END_DATE_DAY_EMPTY,
                    END_DATE_YEAR_EMPTY)) {
                assertThat(message)
                        .as("the source writes 'can NOT be empty...' with NOT capitalised; normalising "
                                + "it to 'cannot' or 'can not' breaks the parity comparison")
                        .contains("can NOT be empty...")
                        .doesNotContain("cannot")
                        .doesNotContain("can not");
            }
        }

        @Test
        @DisplayName("capitalises Month, Day and Year in the component literals")
        void capitalisesTheComponentNamesInTheComponentLiterals() {
            assertThat(START_DATE_MONTH_INVALID).isEqualTo("Start Date - Not a valid Month...");
            assertThat(START_DATE_DAY_INVALID).isEqualTo("Start Date - Not a valid Day...");
            assertThat(START_DATE_YEAR_INVALID).isEqualTo("Start Date - Not a valid Year...");
            assertThat(END_DATE_MONTH_INVALID).isEqualTo("End Date - Not a valid Month...");
            assertThat(END_DATE_DAY_INVALID).isEqualTo("End Date - Not a valid Day...");
            assertThat(END_DATE_YEAR_INVALID).isEqualTo("End Date - Not a valid Year...");
        }

        @Test
        @DisplayName("keeps the lower case 'date' of the two whole date literals - sic, do not normalise")
        void keepsTheLowerCaseDateOfTheWholeDateLiterals() {
            assertThat(START_DATE_WHOLE_INVALID)
                    .as("app/cbl/CORPT00C.cbl:L400 writes 'date' in lower case even though the "
                            + "component messages capitalise Month, Day and Year")
                    .isEqualTo("Start Date - Not a valid date...")
                    .endsWith("valid date...")
                    .doesNotContain("valid Date");
            assertThat(END_DATE_WHOLE_INVALID)
                    .as("app/cbl/CORPT00C.cbl:L420 writes it the same way")
                    .isEqualTo("End Date - Not a valid date...")
                    .endsWith("valid date...")
                    .doesNotContain("valid Date");
        }

        @Test
        @DisplayName("prefixes start messages with 'Start Date - ' and end messages with 'End Date - '")
        void prefixesEachFamilyConsistently() {
            for (final String message : List.of(START_DATE_MONTH_EMPTY, START_DATE_DAY_EMPTY,
                    START_DATE_YEAR_EMPTY, START_DATE_MONTH_INVALID, START_DATE_DAY_INVALID,
                    START_DATE_YEAR_INVALID, START_DATE_WHOLE_INVALID)) {
                assertThat(message).startsWith("Start Date - ");
            }
            for (final String message : List.of(END_DATE_MONTH_EMPTY, END_DATE_DAY_EMPTY,
                    END_DATE_YEAR_EMPTY, END_DATE_MONTH_INVALID, END_DATE_DAY_INVALID,
                    END_DATE_YEAR_INVALID, END_DATE_WHOLE_INVALID)) {
                assertThat(message).startsWith("End Date - ");
            }
        }

        @Test
        @DisplayName("reports only Start Date - Month when every component is blank")
        void reportsOnlyStartDateMonthWhenEveryComponentIsBlank() {
            assertThat(firstCustomRangeFailure(customRangeRequest("", "", "", "", "", ""), true, true))
                    .as("app/cbl/CORPT00C.cbl:L259 matches first and its branch performs "
                            + "SEND-TRNRPT-SCREEN, which ends in GO TO RETURN-TO-CICS at :L580 and "
                            + "therefore never returns; the six failures are never aggregated")
                    .contains(START_DATE_MONTH_EMPTY);
            assertThat(firstCustomRangeFailure(emptyRequest(), true, true))
                    .as("an absent component behaves as an unpopulated one for this relation")
                    .contains(START_DATE_MONTH_EMPTY);
        }

        @ParameterizedTest(name = "blank {0} -> {6}")
        @DisplayName("walks the emptiness cascade in start month, day, year then end month, day, year")
        @CsvSource({
            "'', 01, 2022, 12, 31, 2022, Start Date - Month can NOT be empty...",
            "01, '', 2022, 12, 31, 2022, Start Date - Day can NOT be empty...",
            "01, 01, '', 12, 31, 2022, Start Date - Year can NOT be empty...",
            "01, 01, 2022, '', 31, 2022, End Date - Month can NOT be empty...",
            "01, 01, 2022, 12, '', 2022, End Date - Day can NOT be empty...",
            "01, 01, 2022, 12, 31, '', End Date - Year can NOT be empty..."})
        void walksTheEmptinessCascadeInDeclarationOrder(final String startMonth, final String startDay,
                final String startYear, final String endMonth, final String endDay,
                final String endYear, final String expected) {
            assertThat(firstCustomRangeFailure(customRangeRequest(startMonth, startDay, startYear,
                    endMonth, endDay, endYear), true, true))
                    .as("app/cbl/CORPT00C.cbl:L259-L300 tests the six components in exactly this order")
                    .contains(expected);
        }

        @ParameterizedTest(name = "blank {0} still reports the emptiness message")
        @DisplayName("treats a space and a low value exactly as an empty component")
        @ValueSource(strings = {"", " ", "  ", "\u0000", "\u0000\u0000"})
        void treatsSpacesAndLowValuesAsEmpty(final String blank) {
            assertThat(firstCustomRangeFailure(
                    customRangeRequest(blank, "01", "2022", "12", "31", "2022"), true, true))
                    .as("app/cbl/CORPT00C.cbl:L259 compares against the figurative constants SPACES and "
                            + "LOW-VALUES, so a whole field of either satisfies the relation")
                    .contains(START_DATE_MONTH_EMPTY);
        }

        @Test
        @DisplayName("runs emptiness before component validity before whole date validity")
        void runsTheThreeTiersInOrder() {
            assertThat(firstCustomRangeFailure(
                    customRangeRequest("", "99", "abcd", "13", "32", "xxxx"), false, false))
                    .as("with a blank start month the reported failure is the emptiness message; the "
                            + "component tier at :L329 and the whole date tier at :L396 are unreachable")
                    .contains(START_DATE_MONTH_EMPTY);
            assertThat(firstCustomRangeFailure(
                    customRangeRequest("13", "01", "2022", "12", "31", "2022"), false, false))
                    .as("with every component present the component tier reports first, ahead of the "
                            + "whole date tier")
                    .contains(START_DATE_MONTH_INVALID);
            assertThat(firstCustomRangeFailure(
                    customRangeRequest("02", "31", "2022", "12", "31", "2022"), false, true))
                    .as("only once all six components pass does the whole date verdict of :L400 surface")
                    .contains(START_DATE_WHOLE_INVALID);
            assertThat(firstCustomRangeFailure(
                    customRangeRequest("01", "31", "2022", "02", "31", "2022"), true, false))
                    .as("and the end date verdict of :L420 is reported only after the start date passed")
                    .contains(END_DATE_WHOLE_INVALID);
        }

        @ParameterizedTest(name = "start month [{0}] -> component message")
        @DisplayName("rejects a month above the character ceiling '12' and a non numeric month")
        @ValueSource(strings = {"13", "99", "ab", "1a", "a1", "-1", "+1", "1."})
        void rejectsAnInvalidMonth(final String month) {
            assertThat(firstCustomRangeFailure(
                    customRangeRequest(month, "01", "2022", "12", "31", "2022"), true, true))
                    .as("app/cbl/CORPT00C.cbl:L329-L330 tests IS NOT NUMERIC OR > '12' as characters")
                    .contains(START_DATE_MONTH_INVALID);
        }

        @ParameterizedTest(name = "start month [{0}] survives the NUMVAL-C round trip")
        @DisplayName("accepts a space padded digit run, the only single digit form a 2 byte field delivers")
        @ValueSource(strings = {" 1", "1 ", " 2", "9 "})
        void acceptsASpacePaddedMonth(final String month) {
            assertThat(firstCustomRangeFailure(
                    customRangeRequest(month, "01", "2022", "12", "31", "2022"), true, true))
                    .as("app/cbl/CORPT00C.cbl:L305-L307 converts the field with FUNCTION NUMVAL-C, which "
                            + "tolerates surrounding spaces, and moves the resulting WS-NUM-99 (:L74) back "
                            + "into it zero filled, so the class test at :L329 never sees a space. Were the "
                            + "spaces rejected instead, no single digit month could ever be entered")
                    .isEmpty();
            assertThat(numericRedisplay(month, ASSEMBLED_MONTH_DAY_WIDTH))
                    .as("and the field the operator sees back is the zero filled form the MOVE renders")
                    .isEqualTo("0" + month.trim());
        }

        @ParameterizedTest(name = "start day [{0}] -> component message")
        @DisplayName("rejects a day above the character ceiling '31' and a non numeric day")
        @ValueSource(strings = {"32", "99", "cd", "3x", "-1", "1,"})
        void rejectsAnInvalidDay(final String day) {
            assertThat(firstCustomRangeFailure(
                    customRangeRequest("01", day, "2022", "12", "31", "2022"), true, true))
                    .as("app/cbl/CORPT00C.cbl:L338-L339 tests IS NOT NUMERIC OR > '31' as characters")
                    .contains(START_DATE_DAY_INVALID);
        }

        @Test
        @DisplayName("tests the year for the numeric class only - the source declares NO year range")
        void testsTheYearForTheNumericClassOnly() {
            assertThat(firstCustomRangeFailure(
                    customRangeRequest("01", "01", "abcd", "12", "31", "2022"), true, true))
                    .as("app/cbl/CORPT00C.cbl:L347 is IS NOT NUMERIC with no relational operand")
                    .contains(START_DATE_YEAR_INVALID);
            assertThat(firstCustomRangeFailure(
                    customRangeRequest("01", "01", "0000", "12", "31", "9999"), true, true))
                    .as("years 0000 and 9999 pass the component tier untouched, because :L347 and :L373 "
                            + "carry no range test; only the whole date verdict can reject them")
                    .isEmpty();
        }

        @Test
        @DisplayName("accepts '00' month and day at the component tier, deferring to the whole date tier")
        void acceptsZeroMonthAndDayAtTheComponentTier() {
            assertThat(firstCustomRangeFailure(
                    customRangeRequest("00", "00", "2022", "12", "31", "2022"), true, true))
                    .as("'00' is numeric and is not greater than '12' or '31', so app/cbl/CORPT00C.cbl "
                            + ":L329-L346 lets it through; the source relies wholly on CALL 'CSUTLDTC' "
                            + "at :L392 to reject it, and that ordering must not be tightened")
                    .isEmpty();
            assertThat(firstCustomRangeFailure(
                    customRangeRequest("00", "00", "2022", "12", "31", "2022"), false, true))
                    .as("with the utility refusing, the whole date message is what the screen shows")
                    .contains(START_DATE_WHOLE_INVALID);
        }

        @ParameterizedTest(name = "{0}-{1} passes the component tier and defers to the whole date tier")
        @DisplayName("lets a day legal in some month through, leaving the calendar to the utility")
        @CsvSource({
            "04, 31, 2022",
            "06, 31, 2022",
            "09, 31, 2022",
            "11, 31, 2022",
            "02, 30, 2022",
            "02, 29, 2022",
            "02, 31, 2024"})
        void letsACalendarImpossibleDayThroughTheComponentTier(final String month, final String day,
                final String year) {
            final ReportRequest request = customRangeRequest(month, day, year, "12", "31", "2022");
            assertThat(firstCustomRangeFailure(request, true, true))
                    .as("app/cbl/CORPT00C.cbl:L338-L339 compares the day against the character literal "
                            + "'31' only, with no knowledge of the month or of leap years, so %s-%s-%s "
                            + "reaches the utility untouched", year, month, day)
                    .isEmpty();
            assertThat(firstCustomRangeFailure(request, false, true))
                    .as("only CALL 'CSUTLDTC' at :L392 can reject it, and its verdict surfaces as the "
                            + "whole date message of :L400")
                    .contains(START_DATE_WHOLE_INVALID);
            assertThat(customPeriod(request).startDate())
                    .as("the group is still assembled to ten characters whatever the calendar says")
                    .isEqualTo(year + "-" + month + "-" + day);
        }

        @Test
        @DisplayName("zero fills a short component through the NUMVAL-C round trip of :L305-L327")
        void zeroFillsAShortComponentThroughTheNumvalRoundTrip() {
            assertThat(firstCustomRangeFailure(
                    customRangeRequest("1", "1", "202", "12", "31", "2022"), true, true))
                    .as("app/cbl/CORPT00C.cbl:L306-L308 converts with FUNCTION NUMVAL-C into WS-NUM-99 "
                            + "and moves the result back, so '1' re-renders as '01' and '202' as '0202' "
                            + "- both then pass the component tier")
                    .isEmpty();
            assertThat(numericRedisplay("1", ASSEMBLED_MONTH_DAY_WIDTH)).isEqualTo("01");
            assertThat(numericRedisplay("202", ASSEMBLED_YEAR_WIDTH)).isEqualTo("0202");
            assertThat(numericRedisplay("20255", ASSEMBLED_YEAR_WIDTH))
                    .as("a five digit year cannot be held by WS-NUM-9999 (:L75), so the high order digit "
                            + "is truncated exactly as a COBOL MOVE into PIC 9(4) truncates")
                    .isEqualTo("0255");
        }

        @Test
        @DisplayName("declares the WS-DATE-FORMAT literal 'YYYY-MM-DD' verbatim")
        void declaresTheDateFormatLiteralVerbatim() {
            assertThat(DATE_FORMAT_LITERAL)
                    .as("app/cbl/CORPT00C.cbl:L72 declares 05 WS-DATE-FORMAT PIC X(10) VALUE "
                            + "'YYYY-MM-DD', and it is passed to the utility unchanged at :L391")
                    .isEqualTo("YYYY-MM-DD")
                    .hasSize(CSUTLDTC_FORMAT_WIDTH);
            assertThat(assembleDate("2022", "06", "10"))
                    .as("the assembled date must match the format the utility is told to expect")
                    .isEqualTo("2022-06-10")
                    .matches("\\d{4}-\\d{2}-\\d{2}");
        }

        @Test
        @DisplayName("raises no ordering error when the start date is later than the end date")
        void raisesNoOrderingErrorWhenStartIsLaterThanEnd() {
            assertThat(firstCustomRangeFailure(
                    customRangeRequest("12", "31", "2022", "01", "01", "2022"), true, true))
                    .as("app/cbl/CORPT00C.cbl:L258-L426 contains no start-before-end comparison; the "
                            + "inverted range simply yields an INCLUDE COND at "
                            + "app/proc/TRANREPT.prc:L45-L46 that selects nothing. Inventing an ordering "
                            + "constraint here would be a behaviour change")
                    .isEmpty();
        }

        @Test
        @DisplayName("declares no Bean Validation message on the record, deliberately")
        void declaresNoBeanValidationMessageOnTheRecord() {
            for (final RecordComponent component : ReportRequest.class.getRecordComponents()) {
                for (final Annotation annotation : component.getAccessor().getAnnotations()) {
                    assertThat(annotation.annotationType())
                            .as("component '%s' may carry only the width guard; the fourteen literals "
                                    + "are ordered outcomes of a first-match-wins cascade and cannot be "
                                    + "expressed as unordered constraint violations", component.getName())
                            .isEqualTo(Size.class);
                }
                assertThat(component.getAccessor().getAnnotation(Size.class).message())
                        .as("component '%s' must not override the default @Size message with one of the "
                                + "fourteen source literals, which belong to the ordered cascade",
                                component.getName())
                        .isEqualTo("{jakarta.validation.constraints.Size.message}");
            }
            assertThat(ReportRequest.class.getAnnotations())
                    .as("a class level constraint would fire unconditionally and would report the whole "
                            + "date failure even when a component was blank, which "
                            + "app/cbl/COACTUPC.cbl:L1665-L1669 shows is a gated edit")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("5. Assembled date geometry - app/cbl/CORPT00C.cbl:L60-L71")
    class AssembledDateGeometry {

        @Test
        @DisplayName("is exactly ten characters, 4 + 1 + 2 + 1 + 2, with dashes as group fillers")
        void isExactlyTenCharactersWithDashFillers() {
            assertThat(ASSEMBLED_YEAR_WIDTH + 1 + ASSEMBLED_MONTH_DAY_WIDTH + 1
                    + ASSEMBLED_MONTH_DAY_WIDTH)
                    .as("app/cbl/CORPT00C.cbl:L61-L65 is PIC X(04), FILLER X(01) VALUE '-', PIC X(02), "
                            + "FILLER X(01) VALUE '-', PIC X(02)")
                    .isEqualTo(ASSEMBLED_DATE_WIDTH)
                    .isEqualTo(10);
            final String assembled = assembleDate("2022", "06", "10");
            assertThat(assembled).hasSize(ASSEMBLED_DATE_WIDTH).isEqualTo("2022-06-10");
            assertThat(assembled.charAt(4))
                    .as("the first FILLER PIC X(01) VALUE '-' sits at index 4")
                    .isEqualTo('-');
            assertThat(assembled.charAt(7))
                    .as("the second sits at index 7")
                    .isEqualTo('-');
            assertThat(ASSEMBLED_DATE_SEPARATOR).isEqualTo("-").hasSize(1);
        }

        @Test
        @DisplayName("renders an unpopulated group as the four space, dash, two space, dash, two space")
        void rendersAnUnpopulatedGroupWithItsInitialSpaces() {
            assertThat(assembleDate(null, null, null))
                    .as("app/cbl/CORPT00C.cbl:L61-L65 initialises every subfield VALUE SPACES while the "
                            + "two fillers hold their dashes, so an untouched group renders as ten "
                            + "characters carrying only the separators - this is the exact shape the "
                            + "downstream job parameter receives and it must not be normalised")
                    .isEqualTo(UNPOPULATED_ASSEMBLED_DATE)
                    .isEqualTo("    -  -  ")
                    .hasSize(ASSEMBLED_DATE_WIDTH);
            assertThat(assembleDate("", "", ""))
                    .as("an empty component is indistinguishable from an uninitialised one at the group "
                            + "level, exactly as SPACES is")
                    .isEqualTo(UNPOPULATED_ASSEMBLED_DATE);
            assertThat(UNPOPULATED_ASSEMBLED_DATE.charAt(4)).isEqualTo('-');
            assertThat(UNPOPULATED_ASSEMBLED_DATE.charAt(7)).isEqualTo('-');
            assertThat(UNPOPULATED_ASSEMBLED_DATE.replace(ASSEMBLED_DATE_SEPARATOR, "").trim())
                    .as("nothing but the separators survives")
                    .isEmpty();
        }

        @Test
        @DisplayName("renders a partially populated group without collapsing the missing components")
        void rendersAPartiallyPopulatedGroup() {
            assertThat(assembleDate("2022", null, null))
                    .as("only the year is known, and the shape is still ten characters")
                    .isEqualTo("2022-  -  ");
            assertThat(assembleDate(null, "06", null)).isEqualTo("    -06-  ");
            assertThat(assembleDate(null, null, "10")).isEqualTo("    -  -10");
            assertThat(assembleDate("2022", "06", null)).isEqualTo("2022-06-  ");
        }

        @ParameterizedTest(name = "year [{0}] -> [{1}]")
        @DisplayName("space fills a short year and truncates a long one, as MOVE into PIC X(04) does")
        @CsvSource({
            "202,   '202 -06-10'",
            "20,    '20  -06-10'",
            "2,     '2   -06-10'",
            "2022,  '2022-06-10'",
            "20255, '2025-06-10'",
            "999999,'9999-06-10'"})
        void spaceFillsOrTruncatesTheYearSubfield(final String year, final String expected) {
            assertThat(assembleDate(year, "06", "10"))
                    .as("a COBOL alphanumeric MOVE left justifies, space fills and truncates on the "
                            + "right; the group stays ten characters whatever arrives")
                    .isEqualTo(expected)
                    .hasSize(ASSEMBLED_DATE_WIDTH);
        }

        @ParameterizedTest(name = "month [{0}] -> [{1}]")
        @DisplayName("space fills a one digit month and truncates a three digit one")
        @CsvSource({
            "6,   '2022-6 -10'",
            "06,  '2022-06-10'",
            "066, '2022-06-10'",
            "123, '2022-12-10'"})
        void spaceFillsOrTruncatesTheMonthSubfield(final String month, final String expected) {
            assertThat(assembleDate("2022", month, "10"))
                    .isEqualTo(expected)
                    .hasSize(ASSEMBLED_DATE_WIDTH);
        }

        @Test
        @DisplayName("keeps the assembled dates as ten character Strings, never a temporal type")
        void keepsTheAssembledDatesAsStrings() {
            for (final RecordComponent component : ReportRequest.class.getRecordComponents()) {
                assertThat(Temporal.class.isAssignableFrom(component.getType()))
                        .as("component '%s' must not be a java.time type: the group at "
                                + "app/cbl/CORPT00C.cbl:L60-L71 can legally hold '    -  -  ', which no "
                                + "temporal type can represent, and the downstream INCLUDE COND at "
                                + "app/proc/TRANREPT.prc:L45-L46 compares characters",
                                component.getName())
                        .isFalse();
                assertThat(component.getType().getName())
                        .as("component '%s' must not be a legacy date or calendar type either",
                                component.getName())
                        .doesNotStartWith("java.time")
                        .doesNotStartWith("java.sql")
                        .isNotEqualTo("java.util.Date")
                        .isNotEqualTo("java.util.Calendar");
            }
            assertThat(assembleDate(null, null, null).trim())
                    .as("trimming the unpopulated group would destroy its ten character geometry, which "
                            + "is exactly why the value is transported as an untouched String")
                    .isEqualTo("-  -")
                    .hasSize(4);
        }

        @Test
        @DisplayName("orders the assembled group year, month, day from month, day, year screen input")
        void reordersTheScreenComponentsIntoTheAssembledGroup() {
            final ReportRequest request = customRangeRequest("06", "10", "2022", "07", "04", "2023");
            final ReportPeriod period = customPeriod(request);
            assertThat(period.startDate())
                    .as("app/cbl/CORPT00C.cbl:L381-L383 moves SDTYYYYI, SDTMMI then SDTDDI into the "
                            + "year, month and day subfields - the screen order is reversed")
                    .isEqualTo("2022-06-10");
            assertThat(period.endDate()).isEqualTo("2023-07-04");
        }

        @Test
        @DisplayName("survives a component one over its declared width without losing the group shape")
        void survivesAnOversizedComponentWithoutLosingTheGroupShape() {
            final ReportRequest oversized =
                    customRangeRequest("061", "101", "20222", "071", "041", "20233");
            assertThat(violationsOf(oversized))
                    .as("all six components exceed their PIC X(n) width, so the boundary guard reports "
                            + "six violations before anything reaches the group")
                    .hasSize(6);
            assertThat(customPeriod(oversized).startDate())
                    .as("were it to reach the group anyway, the MOVE would truncate rather than corrupt "
                            + "the geometry")
                    .hasSize(ASSEMBLED_DATE_WIDTH)
                    .isEqualTo("2022-06-10");
        }
    }

    @Nested
    @DisplayName("6. Date validation parameter block - app/cbl/CORPT00C.cbl:L129-L136")
    class DateValidationParameterBlock {

        @Test
        @DisplayName("declares a ten character date and a ten character format")
        void declaresATenCharacterDateAndFormat() {
            assertThat(CSUTLDTC_DATE_WIDTH)
                    .as("app/cbl/CORPT00C.cbl:L130 declares CSUTLDTC-DATE PIC X(10)")
                    .isEqualTo(10)
                    .isEqualTo(ASSEMBLED_DATE_WIDTH);
            assertThat(CSUTLDTC_FORMAT_WIDTH)
                    .as("app/cbl/CORPT00C.cbl:L131 declares CSUTLDTC-DATE-FORMAT PIC X(10)")
                    .isEqualTo(10);
            assertThat(assembleDate("2022", "06", "10")).hasSize(CSUTLDTC_DATE_WIDTH);
            assertThat(assembleDate(null, null, null))
                    .as("even the unpopulated group is exactly ten characters, so the argument the "
                            + "utility receives is always the declared width")
                    .hasSize(CSUTLDTC_DATE_WIDTH);
            assertThat(DATE_FORMAT_LITERAL).hasSize(CSUTLDTC_FORMAT_WIDTH);
        }

        @Test
        @DisplayName("declares an eighty byte result group of 4 + 11 + 4 + 61")
        void declaresAnEightyByteResultGroup() {
            assertThat(CSUTLDTC_SEVERITY_WIDTH)
                    .as("app/cbl/CORPT00C.cbl:L133 declares CSUTLDTC-RESULT-SEV-CD PIC X(04)")
                    .isEqualTo(4);
            assertThat(CSUTLDTC_GAP_WIDTH)
                    .as("app/cbl/CORPT00C.cbl:L134 declares FILLER PIC X(11) - an eleven byte gap the "
                            + "program never names and never reads")
                    .isEqualTo(11);
            assertThat(CSUTLDTC_MESSAGE_NUMBER_WIDTH)
                    .as("app/cbl/CORPT00C.cbl:L135 declares CSUTLDTC-RESULT-MSG-NUM PIC X(04)")
                    .isEqualTo(4);
            assertThat(CSUTLDTC_MESSAGE_WIDTH)
                    .as("app/cbl/CORPT00C.cbl:L136 declares CSUTLDTC-RESULT-MSG PIC X(61); the plan "
                            + "omits this width entirely, so the copybook governs")
                    .isEqualTo(61);
            assertThat(CSUTLDTC_SEVERITY_WIDTH + CSUTLDTC_GAP_WIDTH + CSUTLDTC_MESSAGE_NUMBER_WIDTH
                    + CSUTLDTC_MESSAGE_WIDTH)
                    .as("the result group is exactly eighty bytes")
                    .isEqualTo(80);
        }

        @Test
        @DisplayName("declares a one hundred byte parameter block in total")
        void declaresAOneHundredByteParameterBlock() {
            assertThat(CSUTLDTC_DATE_WIDTH + CSUTLDTC_FORMAT_WIDTH + CSUTLDTC_SEVERITY_WIDTH
                    + CSUTLDTC_GAP_WIDTH + CSUTLDTC_MESSAGE_NUMBER_WIDTH + CSUTLDTC_MESSAGE_WIDTH)
                    .as("app/cbl/CORPT00C.cbl:L129-L136 totals 10 + 10 + 80 = 100 bytes, and that is the "
                            + "shape the injected date validation service must return")
                    .isEqualTo(100);
        }

        @Test
        @DisplayName("accepts severity '0000' and tolerates message number '2513', both four characters")
        void acceptsSeverityZeroAndToleratesMessageNumber() {
            assertThat(CSUTLDTC_ACCEPTED_SEVERITY)
                    .as("app/cbl/CORPT00C.cbl:L396 and :L416 accept the date when the severity code is "
                            + "'0000'")
                    .isEqualTo("0000")
                    .hasSize(CSUTLDTC_SEVERITY_WIDTH);
            assertThat(CSUTLDTC_TOLERATED_MESSAGE_NUMBER)
                    .as("app/cbl/CORPT00C.cbl:L399 and :L419 still accept a non zero severity when the "
                            + "message number is '2513', so the verdict is a two part test")
                    .isEqualTo("2513")
                    .hasSize(CSUTLDTC_MESSAGE_NUMBER_WIDTH);
        }

        @Test
        @DisplayName("models none of the parameter block on the record itself")
        void modelsNoneOfTheParameterBlockOnTheRecord() {
            assertThat(lowerCasedComponentNames())
                    .as("the parameter block is a call linkage area, not screen input; the record "
                            + "carries the six components the map collects and nothing else")
                    .doesNotContain("severity", "severitycode", "messagenumber", "resultmessage",
                            "dateformat", "csutldtc", "validationresult");
            assertThat(ReportRequest.class.getRecordComponents()).hasSize(17);
        }

        @Test
        @DisplayName("keeps the utility verdict a parameter, so this tier never touches the service")
        void keepsTheUtilityVerdictAParameter() throws NoSuchMethodException {
            final Method oracle = ReportRequestTest.class.getDeclaredMethod("firstCustomRangeFailure",
                    ReportRequest.class, boolean.class, boolean.class);
            assertThat(oracle.getParameterTypes())
                    .as("CALL 'CSUTLDTC' at app/cbl/CORPT00C.cbl:L392 and :L412 becomes a single "
                            + "injected DateValidationService in the target, and that service belongs to "
                            + "the validation tier. Its verdict arrives here as two booleans rather than "
                            + "a stub, so the tier boundary is visible in the signature and no "
                            + "collaborator is ever instantiated, invoked or doubled")
                    .containsExactly(ReportRequest.class, boolean.class, boolean.class);
            assertThat(oracle.getReturnType())
                    .as("the oracle returns the single reported message, never a violation set")
                    .isEqualTo(Optional.class);
        }

        @Test
        @DisplayName("routes both utility verdicts independently, start before end")
        void routesBothUtilityVerdictsIndependently() {
            final ReportRequest valid = customRangeRequest("06", "10", "2022", "07", "04", "2023");
            assertThat(firstCustomRangeFailure(valid, true, true))
                    .as("both accepted, so the cascade completes and :L433 names the period")
                    .isEmpty();
            assertThat(firstCustomRangeFailure(valid, false, true))
                    .contains(START_DATE_WHOLE_INVALID);
            assertThat(firstCustomRangeFailure(valid, true, false))
                    .contains(END_DATE_WHOLE_INVALID);
            assertThat(firstCustomRangeFailure(valid, false, false))
                    .as("app/cbl/CORPT00C.cbl:L400 reports and returns, so the end date verdict at "
                            + ":L420 is never reached when the start date already failed")
                    .contains(START_DATE_WHOLE_INVALID);
        }
    }

    @Nested
    @DisplayName("7. Submission boundary - app/cbl/CORPT00C.cbl:L82-L127, :L498-L535")
    class SubmissionBoundary {

        @Test
        @DisplayName("carries one start date and one end date, though the deck injects each one twice")
        void carriesEachDateOnceEvenThoughTheDeckInjectsItTwice() {
            final List<String> startComponents = List.of(ReportRequest.class.getRecordComponents())
                    .stream()
                    .map(RecordComponent::getName)
                    .filter(name -> name.startsWith("startDate"))
                    .toList();
            final List<String> endComponents = List.of(ReportRequest.class.getRecordComponents())
                    .stream()
                    .map(RecordComponent::getName)
                    .filter(name -> name.startsWith("endDate"))
                    .toList();
            assertThat(startComponents)
                    .as("app/cbl/CORPT00C.cbl:L106 declares PARM-START-DATE-1 inside the DFSORT "
                            + "SYMNAMES card and :L118 declares PARM-START-DATE-2 inside the DATEPARM "
                            + "card, and :L220-L221 moves the same value into both. Duplicating a value "
                            + "is a serialisation concern, so the record models the start date exactly "
                            + "once - as its three screen components")
                    .containsExactly("startDateMonth", "startDateDay", "startDateYear");
            assertThat(endComponents)
                    .as("likewise :L111 and :L120, moved together at :L235-L236")
                    .containsExactly("endDateMonth", "endDateDay", "endDateYear");
            assertThat(lowerCasedComponentNames())
                    .as("no second copy of either date may appear under a sort or parameter name")
                    .doesNotContain("parmstartdate", "parmenddate", "startdate1", "startdate2",
                            "enddate1", "enddate2", "sortsymbol", "symnames");
        }

        @Test
        @DisplayName("moves both copies of each date together, so one value can never diverge")
        void movesBothCopiesOfEachDateTogether() {
            final ReportPeriod monthly = monthlyPeriod(FixedClockProvider.canonicalClock());
            final ReportPeriod yearly = yearlyPeriod(FixedClockProvider.canonicalClock());
            final ReportPeriod custom =
                    customPeriod(customRangeRequest("06", "10", "2022", "07", "04", "2023"));
            for (final ReportPeriod period : List.of(monthly, yearly, custom)) {
                assertThat(period.startDate())
                        .as("app/cbl/CORPT00C.cbl:L220-L221, :L247-L248 and :L429-L430 each move the "
                                + "assembled start date into PARM-START-DATE-1 AND PARM-START-DATE-2 in "
                                + "a single statement, so a single modelled value is faithful")
                        .hasSize(ASSEMBLED_DATE_WIDTH);
                assertThat(period.endDate())
                        .as("and :L235-L236, :L252-L253 and :L431-L432 do the same for the end date")
                        .hasSize(ASSEMBLED_DATE_WIDTH);
            }
        }

        @Test
        @DisplayName("carries enough to reconstruct the fixed eighty byte parameter record")
        void carriesEnoughToReconstructTheEightyByteParameterRecord() {
            assertThat(QUEUE_RECORD_SIZE)
                    .as("app/csd/CARDDEMO.CSD defines TDQUEUE(JOBS) with RECORDSIZE(80) and "
                            + "RECORDFORMAT(FIXED), and app/cbl/CORPT00C.cbl:L79 declares JCL-RECORD "
                            + "PIC X(80) to match")
                    .isEqualTo(80);
            assertThat(ASSEMBLED_DATE_WIDTH + DATEPARM_SEPARATOR_WIDTH + ASSEMBLED_DATE_WIDTH
                    + DATEPARM_TRAILING_FILLER_WIDTH)
                    .as("app/cbl/CORPT00C.cbl:L118-L121 lays the parameter card out as PARM-START-DATE-2 "
                            + "PIC X(10), FILLER PIC X(01), PARM-END-DATE-2 PIC X(10) and FILLER "
                            + "PIC X(59) - exactly the fixed record size")
                    .isEqualTo(QUEUE_RECORD_SIZE);
            final ReportPeriod period =
                    customPeriod(customRangeRequest("06", "10", "2022", "07", "04", "2023"));
            assertThat(period.startDate().length() + DATEPARM_SEPARATOR_WIDTH
                    + period.endDate().length() + DATEPARM_TRAILING_FILLER_WIDTH)
                    .as("the six components the record transports are sufficient to fill that record "
                            + "with no residue and no shortfall; emitting the card itself belongs to the "
                            + "publishing tier, not here")
                    .isEqualTo(QUEUE_RECORD_SIZE);
        }

        @Test
        @DisplayName("carries no job control text, no card image and no queue coordinate")
        void carriesNoJobControlTextOrQueueCoordinate() {
            for (final String fragment : FORBIDDEN_STATE_FRAGMENTS) {
                assertThat(lowerCasedComponentNames())
                        .as("app/cbl/CORPT00C.cbl:L82-L127 embeds an entire job deck as eighty byte "
                                + "literals and :L517-L523 writes it card by card to a queue - the "
                                + "mainframe analogue of remote execution. The target replaces the whole "
                                + "deck with a typed message carrying the report name and two dates, so "
                                + "no component may hint at '%s'", fragment)
                        .noneMatch(name -> name.contains(fragment));
            }
            assertThat(ReportRequest.class.getDeclaredFields())
                    .as("a record declares exactly one private final field per component and nothing "
                            + "else; a static field could smuggle a queue name, an endpoint or a "
                            + "committed default into the type")
                    .hasSize(17);
            for (final Field field : ReportRequest.class.getDeclaredFields()) {
                assertThat(Modifier.isStatic(field.getModifiers()))
                        .as("field '%s' must be an instance component, never static state",
                                field.getName())
                        .isFalse();
            }
        }

        @Test
        @DisplayName("leaks no batch concern: no page size, no record length, no report line layout")
        void leaksNoBatchConcern() {
            assertThat(lowerCasedComponentNames())
                    .as("app/cbl/CBTRN03C.cbl:L131 declares WS-PAGE-SIZE PIC 9(03) COMP-3 VALUE 20 and "
                            + "app/proc/TRANREPT.prc:L76 sets LRECL=133 for the report; both belong to "
                            + "the batch job that consumes the message, never to the request that "
                            + "triggers it")
                    .doesNotContain("pagesize", "linesperpage", "pagecount", "lrecl", "recordlength",
                            "recordsize", "reportline", "header1", "header2", "reportamt");
            assertThat(COMPONENT_WIDTHS)
                    .as("no component width may be the report line length of 133 or the page size of 20")
                    .doesNotContain(133, 20);
        }

        @Test
        @DisplayName("keeps both dates lexicographically comparable, as the INCLUDE COND requires")
        void keepsBothDatesLexicographicallyComparable() {
            final String start = assembleDate("2022", "01", "01");
            final String end = assembleDate("2022", "07", "06");
            assertThat(start.compareTo(end))
                    .as("app/proc/TRANREPT.prc:L38-L40 declares TRAN-PROC-DT,305,10,CH - a CHARACTER "
                            + "field - and :L45-L46 filters GE the start and LE the end, so the "
                            + "comparison is on characters and the zero padded YYYY-MM-DD shape is what "
                            + "makes it order correctly")
                    .isNegative();
            assertThat(assembleDate("2022", "01", "01").compareTo(start))
                    .as("the lower bound is inclusive")
                    .isZero();
            assertThat(end.compareTo(assembleDate("2022", "07", "06")))
                    .as("and so is the upper bound")
                    .isZero();
            assertThat(assembleDate("2022", "07", "07").compareTo(end))
                    .as("one day past the upper bound falls outside")
                    .isPositive();
            assertThat(assembleDate("2021", "12", "31").compareTo(start))
                    .as("and one day before the lower bound falls outside, across a year boundary")
                    .isNegative();
            assertThat(assembleDate("2022", "09", "01").compareTo(assembleDate("2022", "10", "01")))
                    .as("zero padding is what keeps September before October under a character compare")
                    .isNegative();
        }

        @Test
        @DisplayName("reports the queue write failure with the source's exact literal")
        void reportsTheQueueWriteFailureWithTheSourceLiteral() {
            assertThat(QUEUE_WRITE_FAILED)
                    .as("app/cbl/CORPT00C.cbl:L531-L532 - inside the paragraph the source misspells as "
                            + "WIRTE-JOBSUB-TDQ at :L515 - moves this literal verbatim, ellipsis and "
                            + "parenthesised queue name included")
                    .isEqualTo("Unable to Write TDQ (JOBS)...")
                    .endsWith("...")
                    .contains("(JOBS)")
                    .doesNotContain("WIRTE");
            assertThat(QUEUE_WRITE_FAILED.length())
                    .as("it must survive the MOVE into ERRMSGI PIC X(78)")
                    .isLessThanOrEqualTo(SCREEN_MESSAGE_WIDTH);
            final ReportRequest echoed = new ReportRequest(null, null, null, null, null, null,
                    "S", null, null, null, null, null, null, null, null, null, QUEUE_WRITE_FAILED);
            assertThat(violationsOf(echoed))
                    .as("the failure text fits the error message component, so a re-displayed screen is "
                            + "representable without widening the field")
                    .isEmpty();
            assertThat(echoed.errorMessage()).isEqualTo(QUEUE_WRITE_FAILED);
        }

        @Test
        @DisplayName("is not Java serialisable, so no deserialisation surface exists")
        void isNotJavaSerialisable() {
            assertThat(Serializable.class.isAssignableFrom(ReportRequest.class))
                    .as("the record is transported as JSON over a queue; implementing Serializable would "
                            + "add a deserialisation gadget surface for no benefit")
                    .isFalse();
            assertThat(ReportRequest.class.getInterfaces()).isEmpty();
        }
    }

    @Nested
    @DisplayName("8. Confirmation, tri-state and boundaries - app/cbl/CORPT00C.cbl:L464-L494")
    class ConfirmationAndTriState {

        @Test
        @DisplayName("distinguishes four confirmation states, which no boolean could carry")
        void distinguishesFourConfirmationStates() {
            assertThat(ConfirmationOutcome.values())
                    .as("app/cbl/CORPT00C.cbl:L464 handles blank, :L478 affirmative, :L480 negative and "
                            + ":L484 anything else - four outcomes, each with its own message and cursor "
                            + "behaviour, so a boolean is structurally incapable of modelling the field")
                    .containsExactly(ConfirmationOutcome.BLANK, ConfirmationOutcome.AFFIRMATIVE,
                            ConfirmationOutcome.NEGATIVE, ConfirmationOutcome.INVALID);
            assertThat(componentNamed("confirmation").getType())
                    .as("app/cpy-bms/CORPT00.CPY:114 declares CONFIRMI PIC X(1)")
                    .isEqualTo(String.class)
                    .isNotEqualTo(boolean.class)
                    .isNotEqualTo(Boolean.class);
            assertThat(declaredWidthOf("confirmation")).isEqualTo(1);
        }

        @ParameterizedTest(name = "CONFIRMI = [{0}] -> AFFIRMATIVE")
        @DisplayName("accepts 'Y' and 'y' as separate WHEN operands, and nothing else as affirmative")
        @ValueSource(strings = {"Y", "y"})
        void acceptsBothLetterCasesAsAffirmative(final String confirmation) {
            assertThat(confirmationOutcome(confirmation))
                    .as("app/cbl/CORPT00C.cbl:L478 lists 'Y' OR 'y' explicitly rather than folding case")
                    .isEqualTo(ConfirmationOutcome.AFFIRMATIVE);
            assertThat(AFFIRMATIVE_CONFIRMATIONS).containsExactly("Y", "y");
            assertThat(confirmationMessage(ConfirmationOutcome.AFFIRMATIVE, reportName(MONTHLY_LITERAL),
                    confirmation))
                    .as(":L479 is CONTINUE, so no message is set and submission proceeds")
                    .isEmpty();
        }

        @ParameterizedTest(name = "CONFIRMI = [{0}] -> NEGATIVE")
        @DisplayName("accepts 'N' and 'n' as separate WHEN operands and clears the screen")
        @ValueSource(strings = {"N", "n"})
        void acceptsBothLetterCasesAsNegative(final String confirmation) {
            assertThat(confirmationOutcome(confirmation)).isEqualTo(ConfirmationOutcome.NEGATIVE);
            assertThat(NEGATIVE_CONFIRMATIONS).containsExactly("N", "n");
            assertThat(confirmationMessage(ConfirmationOutcome.NEGATIVE, reportName(MONTHLY_LITERAL),
                    confirmation))
                    .as("app/cbl/CORPT00C.cbl:L481 performs INITIALIZE-ALL-FIELDS, which clears "
                            + "WS-MESSAGE at :L646, so the screen returns carrying no message at all")
                    .isEmpty();
        }

        @ParameterizedTest(name = "CONFIRMI = [{0}] -> BLANK")
        @DisplayName("treats blank as an ERROR here, unlike the billing screen's third non error branch")
        @ValueSource(strings = {"", " ", "\u0000"})
        void treatsBlankAsAnError(final String blank) {
            assertThat(confirmationOutcome(blank))
                    .as("app/cbl/CORPT00C.cbl:L464 handles the figurative constants BEFORE the "
                            + "EVALUATE, sets the error flag at :L471 and re-displays. This DIFFERS from "
                            + "app/cbl/COBIL00C.cbl:L182-L184, where WHEN SPACES and WHEN LOW-VALUES "
                            + "fall through to READ-ACCTDAT-FILE as a non error third branch. The two "
                            + "screens are reproduced as written rather than harmonised")
                    .isEqualTo(ConfirmationOutcome.BLANK);
            assertThat(confirmationMessage(ConfirmationOutcome.BLANK, reportName(MONTHLY_LITERAL), blank))
                    .as("app/cbl/CORPT00C.cbl:L465-L470 strings the prompt around the report name, "
                            + "which arrives DELIMITED BY SPACE so its PIC X(10) padding is stripped")
                    .isEqualTo("Please confirm to print the Monthly report...")
                    .endsWith("...");
            assertThat(confirmationMessage(ConfirmationOutcome.BLANK, reportName(YEARLY_LITERAL), blank))
                    .isEqualTo("Please confirm to print the Yearly report...");
            assertThat(confirmationMessage(ConfirmationOutcome.BLANK, reportName(CUSTOM_LITERAL), blank))
                    .isEqualTo("Please confirm to print the Custom report...");
        }

        @ParameterizedTest(name = "CONFIRMI = [{0}] -> INVALID")
        @DisplayName("quotes the offending character back, which the billing screen does not")
        @ValueSource(strings = {"Z", "z", "1", "0", "*", "?", "-", "q"})
        void quotesTheOffendingCharacterBack(final String confirmation) {
            assertThat(confirmationOutcome(confirmation)).isEqualTo(ConfirmationOutcome.INVALID);
            assertThat(confirmationMessage(ConfirmationOutcome.INVALID, reportName(MONTHLY_LITERAL),
                    confirmation))
                    .as("app/cbl/CORPT00C.cbl:L485-L490 strings a double quote, then CONFIRMI DELIMITED "
                            + "BY SPACE, then the tail literal")
                    .isEqualTo("\"" + confirmation + "\" is not a valid value to confirm...")
                    .startsWith("\"")
                    .endsWith("...");
        }

        @Test
        @DisplayName("does not reuse the billing screen's fixed invalid confirmation literal")
        void doesNotReuseTheBillingScreenLiteral() {
            assertThat(BILLING_SCREEN_INVALID_CONFIRMATION)
                    .as("app/cbl/COBIL00C.cbl:L187 uses a fixed literal that names the valid values and "
                            + "quotes nothing")
                    .isEqualTo("Invalid value. Valid values are (Y/N)...")
                    .doesNotContain("\"");
            assertThat(confirmationMessage(ConfirmationOutcome.INVALID, reportName(MONTHLY_LITERAL), "Z"))
                    .as("the report screen's own literal quotes the value instead, and the two must not "
                            + "be harmonised")
                    .isNotEqualTo(BILLING_SCREEN_INVALID_CONFIRMATION)
                    .contains("\"Z\"");
        }

        @Test
        @DisplayName("keeps absent, empty, blank and low values as four distinguishable record states")
        void keepsAbsentEmptyBlankAndLowValuesDistinguishable() {
            final String lowValues = String.valueOf(LOW_VALUE);
            assertThat(selectorRequest(null, null, null).monthlySelected())
                    .as("an absent field arrives as null and must stay null")
                    .isNull();
            assertThat(selectorRequest("", null, null).monthlySelected())
                    .as("an empty field is not the same value as an absent one")
                    .isEqualTo("")
                    .isNotNull();
            assertThat(selectorRequest(" ", null, null).monthlySelected())
                    .as("app/cpy/CSSETATY.cpy models OK, NOT-OK and BLANK as three distinct flags, and "
                            + "app/cbl/COACTUPC.cbl:L505-L508 gives blank and invalid different message "
                            + "literals, so blank may never collapse into either neighbour")
                    .isEqualTo(" ");
            assertThat(selectorRequest(lowValues, null, null).monthlySelected())
                    .as("and low values is a fourth, distinct wire state")
                    .isEqualTo(lowValues)
                    .isNotEqualTo(" ")
                    .isNotEqualTo("");
            assertThat(List.of(isSpacesOrLowValues(null), isSpacesOrLowValues(""),
                    isSpacesOrLowValues(" "), isSpacesOrLowValues(lowValues)))
                    .as("all four nevertheless satisfy the COBOL relation of "
                            + "app/cbl/CORPT00C.cbl:L259, which is a property of the comparison and not "
                            + "of the transported value")
                    .containsExactly(true, true, true, true);
            assertThat(isSpacesOrLowValues("Y" + LOW_VALUE))
                    .as("a field mixing a character with low values equals neither figurative constant")
                    .isFalse();
        }

        @Test
        @DisplayName("accepts an entirely absent payload without throwing")
        void acceptsAnEntirelyAbsentPayload() {
            assertThatCode(ReportRequestTest::emptyRequest)
                    .as("the record is a transport. An untouched screen is rejected by "
                            + "app/cbl/CORPT00C.cbl:L437-L441, not by the constructor, so construction "
                            + "must not pre-empt that message")
                    .doesNotThrowAnyException();
            assertThat(violationsOf(emptyRequest()))
                    .as("@Size ignores null by specification, so an absent payload raises no width "
                            + "violation and the cascade remains the sole authority")
                    .isEmpty();
            for (final RecordComponent component : ReportRequest.class.getRecordComponents()) {
                assertThat(component.getAccessor().getAnnotation(Size.class).min())
                        .as("component '%s' must not carry a minimum length: no component of "
                                + "app/cpy-bms/CORPT00.CPY is mandatory at the boundary, because "
                                + "emptiness is reported by the ordered cascade at :L259-L300",
                                component.getName())
                        .isZero();
            }
        }

        @ParameterizedTest(name = "every component = [{0}]")
        @DisplayName("accepts null, empty and blank on every one of the seventeen components")
        @ValueSource(strings = {"", " ", "\u0000"})
        void acceptsEmptyAndBlankOnEveryComponent(final String value) {
            final ReportRequest uniform = new ReportRequest(value, value, value, value, value, value,
                    value, value, value, value, value, value, value, value, value, value, value);
            assertThat(violationsOf(uniform))
                    .as("a one character value fits every PIC X(n) width in the map, so nothing is "
                            + "rejected at the boundary")
                    .isEmpty();
            assertThat(uniform.confirmation()).isEqualTo(value);
            assertThat(uniform.errorMessage()).isEqualTo(value);
        }

        @Test
        @DisplayName("rejects one character over the width on the narrowest and widest components")
        void rejectsOneCharacterOverOnTheNarrowestAndWidestComponents() {
            final ReportRequest overOnSelector = selectorRequest("YY", null, null);
            assertThat(violationsOf(overOnSelector))
                    .as("two characters cannot reach a PIC X(1) screen field")
                    .hasSize(1);
            final ReportRequest overOnMessage = new ReportRequest(null, null, null, null, null, null,
                    "S", null, null, null, null, null, null, null, null, null,
                    "x".repeat(SCREEN_MESSAGE_WIDTH + 1));
            assertThat(violationsOf(overOnMessage))
                    .as("a seventy nine character message cannot reach ERRMSGI PIC X(78), which is the "
                            + "wire width even though WS-MESSAGE at app/cbl/CORPT00C.cbl:L39 is "
                            + "PIC X(80)")
                    .hasSize(1);
            final ReportRequest atTheWorkAreaWidth = new ReportRequest(null, null, null, null, null,
                    null, "S", null, null, null, null, null, null, null, null, null,
                    "x".repeat(MESSAGE_WORK_AREA_WIDTH));
            assertThat(violationsOf(atTheWorkAreaWidth))
                    .as("the program's own eighty byte work area is two bytes wider than the map field, "
                            + "and the map field governs")
                    .hasSize(1);
        }

        @Test
        @DisplayName("carries no session, navigation or pseudo-conversational state")
        void carriesNoSessionOrNavigationState() {
            assertThat(lowerCasedComponentNames())
                    .as("app/cpy/COCOM01Y.cpy:L21-L24 declares CDEMO-FROM-TRANID X(04), "
                            + "CDEMO-FROM-PROGRAM X(08), CDEMO-TO-TRANID X(04) and CDEMO-TO-PROGRAM "
                            + "X(08); :L29-L31 declares CDEMO-PGM-CONTEXT PIC 9(01) with 88 levels for "
                            + "ENTER and REENTER; :L43-L44 declares CDEMO-LAST-MAP and "
                            + "CDEMO-LAST-MAPSET as X(7) - seven, not eight. None has a stateless REST "
                            + "counterpart, and the confirmation is a request field rather than "
                            + "server held state")
                    .doesNotContain("fromtranid", "totranid", "fromprogram", "toprogram", "pgmcontext",
                            "reenter", "lastmap", "lastmapset", "commarea", "programcontext");
            assertThat(lowerCasedComponentNames())
                    .as("nor may any terminal artefact survive: the 3270 layer is a field contract only")
                    .noneMatch(name -> name.contains("cursor"))
                    .noneMatch(name -> name.contains("attribute"))
                    .noneMatch(name -> name.contains("mapset"))
                    .noneMatch(name -> name.contains("aid"));
            assertThat(componentNamed("confirmation").getDeclaringRecord())
                    .as("the confirmation travels on the request, so no handshake state is retained "
                            + "between calls")
                    .isEqualTo(ReportRequest.class);
        }

        @Test
        @DisplayName("carries no secret, no credential, no endpoint and no personal data")
        void carriesNoSecretCredentialEndpointOrPersonalData() {
            for (final String fragment : FORBIDDEN_NAME_FRAGMENTS) {
                assertThat(lowerCasedComponentNames())
                        .as("no component may hint at '%s': this type is a report parameter carrier and "
                                + "nothing about it is privileged", fragment)
                        .noneMatch(name -> name.contains(fragment));
            }
            assertThat(ReportRequest.class.getDeclaredFields())
                    .as("no static field can hold a committed default, a signing key or a queue "
                            + "coordinate")
                    .hasSize(COMPONENT_NAMES.size());
            final ReportRequest populated = new ReportRequest("CR00", "Report Submission", "06/10/22",
                    "CORPT00C", "CardDemo", "19:27:53", " ", " ", "S", "06", "10", "2022", "07", "04",
                    "2023", "Y", "");
            assertThat(populated.toString())
                    .as("a fully populated instance renders only screen values; nothing resembling a "
                            + "host, a port, a data source URL or a provider domain may appear")
                    .doesNotContain("localhost")
                    .doesNotContain("127.0.0.1")
                    .doesNotContain("0.0.0.0")
                    .doesNotContain("jdbc:")
                    .doesNotContain("amazonaws")
                    .doesNotContain("5432")
                    .doesNotContain("4566");
            assertThat(violationsOf(populated))
                    .as("and that realistic payload is valid, so the screen is representable end to end")
                    .isEmpty();
        }

        @Test
        @DisplayName("the rendering discloses no submitted value on any of the seventeen components")
        void theRenderingDisclosesNoSubmittedValue() {
            final String[] markers = new String[COMPONENT_NAMES.size()];
            for (int index = 0; index < markers.length; index++) {
                // Each marker is unique and none is a substring of another, so a single leaked component
                // is attributable rather than merely detectable.
                markers[index] = "MARKER" + (char) ('A' + index) + "VALUE";
            }
            final ReportRequest populated = new ReportRequest(markers[0], markers[1], markers[2],
                    markers[3], markers[4], markers[5], markers[6], markers[7], markers[8], markers[9],
                    markers[10], markers[11], markers[12], markers[13], markers[14], markers[15],
                    markers[16]);

            final String rendered = populated.toString();

            for (int index = 0; index < markers.length; index++) {
                assertThat(rendered)
                        .as("the compiler generated rendering emitted every component verbatim, which is "
                                + "how all seventeen untrusted members reached a log through ordinary "
                                + "parameter interpolation. The override must emit none of them. "
                                + "Component %s carried marker index %d",
                                COMPONENT_NAMES.get(index), index)
                        .doesNotContain(markers[index]);
            }
            assertThat(rendered)
                    .as("nor may any fragment of a marker survive")
                    .doesNotContain("MARKER")
                    .doesNotContain("VALUE");
        }

        @Test
        @DisplayName("the rendering cannot forge a log line, whatever control bytes are submitted")
        void theRenderingCannotForgeALogLine() {
            final String forgery = "\r\nWARN attacker composed this line\u0000\u001b[31m\t\u007f";
            final ReportRequest hostile = new ReportRequest(forgery, forgery, forgery, forgery, forgery,
                    forgery, forgery, forgery, forgery, forgery, forgery, forgery, forgery, forgery,
                    forgery, forgery, forgery);

            final String rendered = hostile.toString();

            assertThat(rendered.chars().filter(codePoint -> codePoint < 0x20 || codePoint == 0x7f).count())
                    .as("not one control character may reach the output. A carriage return and line feed "
                            + "inside any component would terminate the current log line and let the "
                            + "caller compose the next one in a sink that stores one event per line, and "
                            + "the escape sequence would reach whatever terminal rendered the file. The "
                            + "guarantee here is structural rather than achieved by escaping: the output "
                            + "is built only from fixed literals, decimal lengths and hexadecimal digits, "
                            + "so it cannot carry a control character at all. Rendered: [%s]", rendered)
                    .isZero();
            assertThat(rendered.lines().count())
                    .as("and the whole rendering is exactly one line")
                    .isEqualTo(1L);
            assertThat(rendered)
                    .as("no fragment of the forged text survives either")
                    .doesNotContain("attacker")
                    .doesNotContain("WARN");
        }

        @Test
        @DisplayName("the rendering still diagnoses a fault: selector code points and component widths")
        void theRenderingStillDiagnosesAFault() {
            final ReportRequest custom = new ReportRequest("CR00", "Report Submission", "06/10/22",
                    "CORPT00C", "CardDemo", "19:27:53", " ", " ", "S", "06", "10", "2022", "07", "04",
                    "2023", "Y", "");

            final String rendered = custom.toString();

            assertThat(rendered)
                    .as("the three selectors decide which period branch the service takes at "
                            + "app/cbl/CORPT00C.cbl:L213, :L239 and :L256, so their code points are the "
                            + "whole of the useful signal - and a code point tells a blank (0x20) from a "
                            + "low value byte where printing the character could not. Rendered: [%s]",
                            rendered)
                    .contains("monthly=0x20")
                    .contains("yearly=0x20")
                    .contains("custom=0x53")
                    .contains("confirmation=0x59");
            assertThat(rendered)
                    .as("the six custom range components are reported by width, which is what catches "
                            + "the fixed width error that actually happens - a two character month "
                            + "arriving with one, or a year with three - without disclosing the range "
                            + "the caller asked for")
                    .contains("startDate=2 chars/2 chars/4 chars")
                    .contains("endDate=2 chars/2 chars/4 chars");
            assertThat(rendered)
                    .as("an empty message is reported as empty rather than as a zero width, keeping the "
                            + "three states the custom range cascade at :L259-L300 depends on apart")
                    .contains("errorMessage=empty");
            assertThat(rendered)
                    .as("and the six presentation members are omitted outright rather than shaped, "
                            + "because a screen title carries no diagnostic signal")
                    .contains("header=<6 presentation members omitted>")
                    .doesNotContain("CR00")
                    .doesNotContain("CORPT00C")
                    .doesNotContain("CardDemo");
        }

        @Test
        @DisplayName("the rendering is null safe and keeps absent distinct from empty")
        void theRenderingIsNullSafeAndKeepsAbsentDistinctFromEmpty() {
            assertThatCode(() -> emptyRequest().toString())
                    .as("an untouched screen must still be loggable")
                    .doesNotThrowAnyException();

            assertThat(emptyRequest().toString())
                    .as("every component of an untouched payload is absent, and absent is reported as "
                            + "such rather than as an empty string or a zero width")
                    .contains("monthly=absent")
                    .contains("confirmation=absent")
                    .contains("errorMessage=absent")
                    .contains("startDate=absent/absent/absent");

            final ReportRequest empties = new ReportRequest("", "", "", "", "", "", "", "", "", "", "",
                    "", "", "", "", "", "");
            assertThat(empties.toString())
                    .as("an empty component is a different state from an absent one throughout this "
                            + "corpus, so the rendering must not conflate them")
                    .contains("monthly=empty")
                    .contains("confirmation=empty")
                    .doesNotContain("absent");
        }

        @Test
        @DisplayName("the rendering is bounded by its own shape rather than by the submitted size")
        void theRenderingIsBoundedByItsOwnShape() {
            final String oversized = "x".repeat(100_000);
            final ReportRequest huge = new ReportRequest(oversized, oversized, oversized, oversized,
                    oversized, oversized, oversized, oversized, oversized, oversized, oversized,
                    oversized, oversized, oversized, oversized, oversized, oversized);

            final String rendered = huge.toString();

            assertThat(rendered.length())
                    .as("a rendering whose size tracked its input would itself be a denial of service "
                            + "against every log sink downstream. This one reports a decimal length, so "
                            + "it grows only with the number of digits in that length. Rendered %d "
                            + "characters for a payload of %d", rendered.length(), oversized.length() * 17)
                    .isLessThan(400);
            assertThat(rendered)
                    .as("an over width single character code falls back to a shape rather than rendering "
                            + "a hundred thousand code points, which bounds the selector rendering too")
                    .contains("monthly=100000 chars")
                    .doesNotContain("0x78");
        }

        @Test
        @DisplayName("formats and parses locale independently, never under the ambient locale")
        void formatsAndParsesLocaleIndependently() {
            assertThat(String.format(Locale.forLanguageTag("ar-EG-u-nu-arab"), "%04d", 2022))
                    .as("a locale sensitive numeric format can emit non ASCII digits, which would "
                            + "corrupt a PIC X(04) year subfield beyond recognition")
                    .isNotEqualTo("2022");
            assertThat(zonedDecimalMove(2022, ASSEMBLED_YEAR_WIDTH))
                    .as("every oracle formats with Locale.ROOT, so the rendering is a property of the "
                            + "contract rather than of the host")
                    .isEqualTo("2022")
                    .matches("[0-9]{4}");
            assertThat(numericRedisplay("7", ASSEMBLED_MONTH_DAY_WIDTH))
                    .as("and the NUMVAL-C round trip of app/cbl/CORPT00C.cbl:L305-L327 likewise")
                    .isEqualTo("07")
                    .matches("[0-9]{2}");
            for (final String componentName : COMPONENT_NAMES) {
                assertThat(componentName)
                        .as("component '%s' must be pure ASCII so that case folding cannot depend on "
                                + "the ambient locale", componentName)
                        .matches("[A-Za-z0-9]+");
            }
        }

        @Test
        @DisplayName("rejects a negative subfield, which an unsigned PIC 9(n) cannot represent")
        void rejectsANegativeSubfield() {
            assertThatThrownBy(() -> zonedDecimalMove(-1, ASSEMBLED_MONTH_DAY_WIDTH))
                    .as("app/cpy/CSDAT01Y.cpy:L20-L22 declares the date subfields unsigned, so a "
                            + "negative value is reported rather than rendered with a sign overpunch")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("unsigned PIC 9(n)")
                    .hasMessageContaining("-1");
        }

        @Test
        @DisplayName("refuses a null request in every oracle rather than substituting ambient state")
        void refusesANullRequestInEveryOracle() {
            assertThatThrownBy(() -> customPeriod(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("request must not be null");
            assertThatThrownBy(() -> firstCustomRangeFailure(null, true, true))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("request must not be null");
            assertThatThrownBy(() -> violationsOf(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("request must not be null");
        }
    }

    @Nested
    @DisplayName("9. Containment - redacted rendering and refusal of an undeclared property")
    class Containment {

        @Test
        @DisplayName("overrides toString rather than inheriting the generated one")
        void overridesToStringRatherThanInheritingIt() {
            assertThat(List.of(ReportRequest.class.getDeclaredMethods()).stream()
                    .map(Method::getName)
                    .filter("toString"::equals)
                    .toList())
                    .as("a record's generated toString emits every component. On this payload that means "
                            + "the six custom-range date subfields, which together describe exactly which "
                            + "window of a customer's activity someone asked to see")
                    .containsExactly("toString");
        }

        @Test
        @DisplayName("emits a fixed structural form that names members but never their content")
        void emitsAFixedStructuralFormAndNoContent() {
            // The transaction name and the program name are withheld along with the rest of the
            // presentation header. They are constructor arguments on this record, so they are
            // caller supplied: rendering either verbatim let a caller embed a line break and forge a
            // log entry, and no masking configuration exists to catch it downstream. The rendering
            // therefore describes the SHAPE of every member - absent, empty, a code point for a single
            // byte, or a character count - and discloses no byte of any submitted value.
            assertThat(populatedCustomRangeRequest().toString())
                    .as("the whole rendering is pinned, so any future widening that reintroduced a "
                            + "value would fail here rather than silently reach a log")
                    .isEqualTo("ReportRequest[monthly=0x20, yearly=0x20, custom=0x53, "
                            + "startDate=2 chars/2 chars/4 chars, endDate=2 chars/2 chars/4 chars, "
                            + "confirmation=0x59, errorMessage=17 chars, "
                            + "header=<6 presentation members omitted>]");
        }

        @Test
        @DisplayName("emits no date subfield, no selector and no confirmation")
        void emitsNoDateSubfieldNoSelectorAndNoConfirmation() {
            final String rendered = populatedCustomRangeRequest().toString();

            assertThat(rendered)
                    .as("the assembled range at app/cbl/CORPT00C.cbl:60-71 is built from these six "
                            + "subfields, so emitting them discloses the requested window")
                    .doesNotContain("2022")
                    .doesNotContain("2023")
                    .doesNotContain("06")
                    .doesNotContain("07");
            assertThat(rendered)
                    .as("the confirmation and the error message are caller controlled, and copying "
                            + "either into a log record would let a caller forge log content")
                    .doesNotContain("Y")
                    .doesNotContain("SYNTHETIC");
        }

        @Test
        @DisplayName("discloses nothing when interpolated into a message")
        void disclosesNothingWhenInterpolated() {
            assertThat("report submission failed: " + populatedCustomRangeRequest())
                    .as("implicit toString through concatenation is the path by which a generated "
                            + "rendering reaches a log without anyone deciding that it should")
                    .doesNotContain("2022")
                    .doesNotContain("SYNTHETIC");
        }

        @Test
        @DisplayName("keeps every value readable through its accessor, so nothing was lost")
        void keepsEveryValueReadableThroughItsAccessor() {
            final ReportRequest request = populatedCustomRangeRequest();

            assertThat(request.startDateYear()).isEqualTo("2022");
            assertThat(request.endDateYear()).isEqualTo("2023");
            assertThat(request.confirmation())
                    .as("redacting the rendering must not redact the data the service acts on")
                    .isEqualTo("Y");
        }

        @Test
        @DisplayName("refuses an unrecognised property under a lenient mapper as well as a strict one")
        void refusesAnUnrecognisedPropertyUnderEitherMapperPosture() {
            final String body = "{\"customSelected\":\"S\",\"reportName\":\"DALYREPT\"}";

            final ObjectMapper lenient = new ObjectMapper()
                    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
            final ObjectMapper strict = new ObjectMapper()
                    .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

            assertThat(refusalOf(() -> lenient.readValue(body, ReportRequest.class)))
                    .as("this repository publishes no application*.yml, so the framework default of "
                            + "ignoring unknown properties is the posture that actually ships. The guard "
                            + "is declared on the type so it holds regardless of mapper configuration")
                    .isNotNull();
            assertThat(refusalOf(() -> strict.readValue(body, ReportRequest.class)))
                    .isNotNull();
        }

        @Test
        @DisplayName("states the field count and the copybook, and echoes neither name nor value")
        void statesTheContractAndEchoesNeitherNameNorValue() {
            final String offendingName = "accountId";
            final String offendingValue = "00000000001";
            final String body = "{\"" + offendingName + "\":\"" + offendingValue + "\"}";

            final IllegalArgumentException refusal =
                    refusalOf(() -> new ObjectMapper().readValue(body, ReportRequest.class));

            assertThat(refusal).isNotNull();
            assertThat(refusal.getMessage())
                    .as("the message must state the declared field count and cite the map, which is what "
                            + "a caller needs in order to correct the payload")
                    .contains("17")
                    .contains("app/cpy-bms/CORPT00.CPY");
            assertThat(refusal.getMessage())
                    .as("the map declares no account or card field, so a caller trying to scope a report "
                            + "to one account is asking for behaviour the source cannot express - and "
                            + "echoing their input back would place chosen text into the logs")
                    .doesNotContain(offendingName)
                    .doesNotContain(offendingValue);
        }

        @ParameterizedTest(name = "a misspelling of {0} is refused rather than dropped")
        @CsvSource({"customSelected, customSelcted", "confirmation, confirmatoin",
            "startDateYear, startYear"})
        @DisplayName("a misspelled property is refused, so a typo cannot read as an absent field")
        void aMisspelledPropertyIsRefused(final String declared, final String misspelling) {
            final String body = "{\"" + misspelling + "\":\"Y\"}";

            assertThat(refusalOf(() -> new ObjectMapper().readValue(body, ReportRequest.class)))
                    .as("silently dropping '%s' would leave '%s' absent. On this payload that is "
                            + "particularly damaging: the three period selectors are evaluated in order at "
                            + "app/cbl/CORPT00C.cbl:213, :239 and :256, so a dropped selector silently "
                            + "selects a different reporting period from the one requested",
                            misspelling, declared)
                    .isNotNull();
        }

        @Test
        @DisplayName("accepts a body naming only declared properties, so it refuses nothing it should "
                + "admit")
        void acceptsABodyNamingOnlyDeclaredProperties() throws Exception {
            final ObjectMapper mapper = new ObjectMapper();
            final ReportRequest original = populatedCustomRangeRequest();

            assertThat(mapper.readValue(mapper.writeValueAsString(original), ReportRequest.class))
                    .as("a round trip of this type's own output names only declared properties and must "
                            + "pass the guard untouched")
                    .isEqualTo(original);
        }

        @Test
        @DisplayName("emits exactly the seventeen map fields and nothing invented")
        void emitsExactlyTheSeventeenMapFields() throws Exception {
            final ObjectMapper mapper = new ObjectMapper();

            final Map<String, Object> emitted = mapper.readValue(
                    mapper.writeValueAsString(populatedCustomRangeRequest()),
                    new TypeReference<LinkedHashMap<String, Object>>() { });

            assertThat(emitted.keySet())
                    .as("the wire contract is the map's seventeen fields; a derived period or an "
                            + "assembled date would publish a field the screen never had")
                    .containsExactlyInAnyOrderElementsOf(COMPONENT_NAMES);
        }
    }

    /**
     * Builds a fully populated custom-range request whose values are distinctive enough that a leak
     * through the diagnostic rendering is unambiguous.
     *
     * @return a valid, fully populated custom-range request
     */
    private static ReportRequest populatedCustomRangeRequest() {
        return new ReportRequest("CR00", "SYNTHETIC TITLE ONE", "06/10/22", "CORPT00C",
                "SYNTHETIC TITLE TWO", "19:27:53", " ", " ", "S", "06", "10", "2022", "07", "04", "2023",
                "Y", "SYNTHETIC MESSAGE");
    }

    /**
     * Runs an action expected to be refused and returns the {@link IllegalArgumentException} behind the
     * refusal, or {@code null} if none appears in the cause chain.
     *
     * <p>The chain is walked rather than asserted on directly because Jackson wraps an exception thrown
     * from an any-setter, and how deeply it nests it is an implementation detail of the databind version.
     *
     * @param action the action expected to be refused
     * @return the refusal, or {@code null} if the action was not refused for that reason
     */
    private static IllegalArgumentException refusalOf(final ThrowingAction action) {
        try {
            action.run();
            return null;
        } catch (final Throwable thrown) {
            Throwable cursor = thrown;
            for (int depth = 0; cursor != null && depth < 16; depth++) {
                if (cursor instanceof IllegalArgumentException refusal) {
                    return refusal;
                }
                cursor = cursor.getCause();
            }
            return null;
        }
    }

    /** An action that may throw any exception, so that {@link #refusalOf} can invoke it. */
    @FunctionalInterface
    private interface ThrowingAction {

        /**
         * Runs the action.
         *
         * @throws Exception if the action fails, which is the case under test
         */
        void run() throws Exception;
    }
}
