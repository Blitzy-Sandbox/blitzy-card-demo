/*
 * ******************************************************************
 * Program     : FixedClockProvider.java
 * Component   : Unit test tier shared time source, resident at
 *               src/test/java/com/cardemo/unit/model
 * Application : CardDemo
 * Type        : JUnit 5 test support - immutable fixed clock factory
 * Function    : Supplies deterministic java.time.Clock instances and
 *               reproduces, without ever reading the ambient clock,
 *               the two 26 byte CHAR(26) timestamp renderings the
 *               legacy corpus emits, plus the fixed width alphanumeric
 *               move that copies a timestamp straight through. All
 *               three legacy producers read the wall clock, so no
 *               deterministic assertion over a generated timestamp is
 *               possible until that read is replaced by an injected
 *               fixed clock. This class is that replacement, and it is
 *               the only sanctioned source of time for the unit tier.
 * Source      : app/cbl/COBIL00C.cbl:L249-L267,
 *               app/cbl/CBACT04C.cbl:L613-L625,
 *               app/cbl/CBTRN02C.cbl:L149,L159-L175,
 *               app/cbl/COTRN02C.cbl:L464-L465,
 *               app/cpy/CSDAT01Y.cpy:L42-L55,
 *               app/cpy/CVTRA05Y.cpy:L16-L17,
 *               app/data/ASCII/dailytran.txt @ 7756d89
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

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;

/**
 * Single sanctioned source of time for the CardDemo unit test tier, and the only place under
 * {@code src/test/java/com/cardemo/unit} permitted to decide what the current instant is.
 *
 * <p><strong>Why this type exists.</strong> Every timestamp the legacy corpus generates is read from
 * the wall clock at the moment of generation, verified first hand in all three producers:
 * {@code app/cbl/COBIL00C.cbl:L251} issues {@code EXEC CICS ASKTIME ABSTIME(WS-ABS-TIME)} inside
 * {@code GET-CURRENT-TIMESTAMP}; {@code app/cbl/CBACT04C.cbl:L614} issues
 * {@code MOVE FUNCTION CURRENT-DATE TO COBOL-TS} inside {@code Z-GET-DB2-FORMAT-TIMESTAMP}; and
 * {@code app/cbl/CBTRN02C.cbl:L693} issues the identical move inside its own copy of that paragraph.
 * A Java translation that inherits the ambient clock inherits the non-determinism with it, and an
 * assertion over its output can only be flaky. Routing every unit test through a fixed clock removes
 * the variable entirely, which is what makes byte for byte comparison against the frozen fixtures a
 * meaningful gate rather than a coincidence.
 *
 * <p><strong>Placement is deliberate and must not be tidied.</strong> This class lives in
 * {@code com.cardemo.unit.model} rather than in a helper package such as {@code unit.support},
 * {@code unit.fixtures} or {@code unit.common} because the folder contract for this tree admits no
 * unlisted helper package, and it is not placed directly in {@code com.cardemo.unit} because that
 * level admits no files at all. The build path consequence is sharper than the style one: the test
 * classpath is populated from {@code src/test/java}, so a class moved outside that tree is not
 * compiled with the tier at all, and a class renamed to end in {@code Test} or {@code Tests} would
 * instead be collected by Surefire and reported as an empty suite. Neither mistake produces an error
 * or a warning, which makes both of them expensive to notice. Do not move, rename or repackage this
 * file.
 *
 * <p><strong>What it does.</strong> It hands out {@link java.time.Clock} instances built by
 * {@code Clock.fixed}, always against an explicit {@link java.time.ZoneId}, and it renders those
 * clocks into the exact 26 character strings the legacy programs write. It performs no I/O, reads no
 * configuration, opens no connection, touches no environment variable and holds no credential, so it
 * reaches no endpoint of any kind and needs no privilege to run.
 *
 * <p><strong>The three legacy timestamp renderings.</strong> All three are 26 byte
 * {@code PIC X(26)} character fields. They are not interchangeable, and two of them occur in the
 * same record.
 *
 * <ol>
 *   <li><strong>Online, {@code yyyy-MM-dd HH:mm:ss.000000}</strong> - produced by
 *       {@code GET-CURRENT-TIMESTAMP} at {@code app/cbl/COBIL00C.cbl:L249-L267}, which formats the
 *       CICS absolute time with {@code DATESEP('-')} and {@code TIMESEP(':')}, initialises
 *       {@code WS-TIMESTAMP}, moves the ten character date into bytes 1 to 10, moves the eight
 *       character time into bytes 12 to 19, and then moves zeros into the six digit microsecond
 *       field. The layout itself is declared in {@code app/cpy/CSDAT01Y.cpy:L42-L55} and sums to
 *       exactly 26: a four digit year, a dash, two month digits, a dash, two day digits, a
 *       {@code FILLER PIC X(01) VALUE ' '}, two hour digits, a colon, two minute digits, a colon,
 *       two second digits, a {@code FILLER PIC X(01) VALUE '.'} and six microsecond digits.
 *       <strong>Byte 11 is therefore a SPACE</strong>, contributed by that filler and left untouched
 *       by all three reference modified moves, byte 20 is a period, and bytes 21 to 26 are six
 *       literal zeros. Sub second precision is discarded on purpose: it is overwritten rather than
 *       rounded. Rendered by {@link #onlineTimestamp(Clock)}.</li>
 *   <li><strong>Batch, {@code yyyy-MM-dd-HH.mm.ss.SS0000}</strong> - produced by
 *       {@code Z-GET-DB2-FORMAT-TIMESTAMP} at {@code app/cbl/CBACT04C.cbl:L613-L625} and, character
 *       for character, at {@code app/cbl/CBTRN02C.cbl:L692-L706}. The redefinition at
 *       {@code app/cbl/CBTRN02C.cbl:L159-L175} also sums to exactly 26, and the paragraph moves a
 *       dash into three separate separator bytes and a period into three more, so
 *       <strong>byte 11 is a DASH, not a space</strong>. The two digit field the paragraph fills
 *       from {@code COB-MIL} holds <strong>hundredths</strong> of a second, because
 *       {@code COBOL-TS} at {@code app/cbl/CBTRN02C.cbl:L150-L158} lays the twenty one character
 *       {@code FUNCTION CURRENT-DATE} result out as year, month, day, hour, minute, second,
 *       hundredths and a five byte remainder. The paragraph then moves the literal {@code '0000'}
 *       into the trailing four bytes. The header comment at {@code app/cbl/CBTRN02C.cbl:L149}
 *       states the shape as {@code EEEE-MM-DD-UU.MM.SS.HH0000}. Format to hundredths and append four
 *       literal zeros; <strong>never</strong> to nanoseconds, which would yield a 29 character
 *       string and break the {@code CHAR(26)} contract. Rendered by
 *       {@link #batchTimestamp(Clock)}.</li>
 *   <li><strong>Pass through - no formatting at all.</strong> Some timestamps are never generated,
 *       only moved. {@code app/cbl/CBTRN02C.cbl:L436} moves {@code DALYTRAN-ORIG-TS} into
 *       {@code TRAN-ORIG-TS}, both {@code PIC X(26)}, and {@code app/cbl/CBTRN02C.cbl:L428} moves
 *       {@code DALYTRAN-SOURCE} into {@code TRAN-SOURCE}, both {@code PIC X(10)}. Crucially the move
 *       is not always width preserving: {@code app/cbl/COTRN02C.cbl:L464-L465} moves the screen
 *       fields {@code TORIGDTI} and {@code TPROCDTI}, declared {@code PIC X(10)} at
 *       {@code app/cpy-bms/COTRN02.CPY:L102} and {@code :L108}, into {@code TRAN-ORIG-TS} and
 *       {@code TRAN-PROC-TS}, declared {@code PIC X(26)} at {@code app/cpy/CVTRA05Y.cpy:L16-L17}.
 *       A COBOL alphanumeric move left justifies and space fills to the receiving width, so those
 *       ten screen bytes occupy positions 1 to 10 and positions 11 to 26 become sixteen spaces. This
 *       is a width changing operation, not an identity, and it is reproduced by
 *       {@link #passThroughTimestamp(String)}.</li>
 * </ol>
 *
 * <p><strong>The layouts above are written with {@code yyyy} by convention, but the formatter
 * patterns deliberately use {@code uuuu}.</strong> {@code y} is year-of-era and would render the
 * proleptic year 0, which is 1 BCE, as {@code 0001} - well formed, plausible and wrong. The source
 * has no era to render: the year is an unsigned four digit absolute field in both layouts,
 * {@code PIC 9(04)} at {@code app/cpy/CSDAT01Y.cpy:L43} and {@code PIC X(004)} at
 * {@code app/cbl/CBTRN02C.cbl:L161}. {@code u} is the proleptic year and is therefore the faithful
 * translation; the accepted year range on this class is bounded accordingly.
 *
 * <p><strong>The two generated renderings coexist in one record, which is the whole point.</strong>
 * {@code app/cbl/CBTRN02C.cbl:L436-L438} passes the originating timestamp through unchanged and then
 * generates the processing timestamp in the batch rendering. The frozen fixture proves it: all 300
 * rows of {@code app/data/ASCII/dailytran.txt} carry the single distinct value
 * {@code 2022-06-10 19:27:53.000000} in columns 279 to 304, the online rendering, while columns 305
 * to 330 are twenty six spaces because the batch job has not yet filled them. Any test that assumes
 * one rendering for both fields is asserting against a shape the source never produces.
 *
 * <p><strong>Timestamps are text, never a temporal type.</strong> Every timestamp in this system is
 * a {@code String} of exactly {@link #TIMESTAMP_LENGTH} characters, mapped to {@code CHAR(26)}.
 * This class supplies the <em>clock</em> and renders <em>strings</em>; it deliberately never returns
 * a date time object as a stand in for a persisted timestamp, because the separator geometry above
 * is part of the field contract and no temporal type carries it.
 *
 * <p><strong>How to build, run and test.</strong> {@code ./mvnw test} compiles this tree and runs
 * the unit suite. Surefire 3.5.4 selects test classes by name, including those ending in
 * {@code Test.java} and {@code Tests.java} and excluding the integration and end to end paths, so
 * this class is intentionally outside that selection: it is compiled onto the test classpath by
 * {@code ./mvnw test-compile} and imported by the sibling {@code unit.validation},
 * {@code unit.batch} and {@code unit.service} tiers rather than executed as a suite of its own.
 * {@code ./mvnw -q test-compile} is the fastest check that this file still satisfies the compiler
 * settings. Where a local toolchain is unavailable, the pinned image reproduces it exactly:
 * {@code docker run --rm -v "$PWD":/w -w /w maven:3.9.11-eclipse-temurin-25 ./mvnw -q test}. This is
 * a pure JVM tier: no container, no Spring context and no database is started here, and none may be.
 *
 * <p><strong>Key configuration and defaults.</strong> There is no external configuration; every
 * default is a compile time constant on this class. {@link #CANONICAL_INSTANT} is
 * {@code 2022-06-10T19:27:53Z}, chosen because it is the one distinct originating timestamp present
 * in {@code app/data/ASCII/dailytran.txt}, so rendering it through {@link #onlineTimestamp(Clock)}
 * reproduces the fixture bytes exactly. {@link #CANONICAL_ZONE} is {@link java.time.ZoneOffset#UTC},
 * stated explicitly on every factory so the rendering cannot drift with the host, and the JVM
 * default zone accessor is never consulted. Both formatters are constructed with
 * {@link java.util.Locale#ROOT} so digit shaping and text are locale independent.
 *
 * <p><strong>Common failure modes and troubleshooting.</strong>
 * <ul>
 *   <li><em>The build fails on something that looks trivial.</em> Compilation runs with
 *       {@code -Xlint:all} and {@code -Werror}, and that reaches test compilation, so a single
 *       unused import, raw type or deprecation turns into an error rather than a warning. Reproduce
 *       and fix with {@code ./mvnw -q test-compile}.</li>
 *   <li><em>A fixture stream is null at run time.</em> The daily transaction fixture is named
 *       {@code dailytran.txt}, spelled in full. The mainframe data definition name and dataset are
 *       {@code DALYTRAN}, so {@code dalytran.txt} is the natural guess and is wrong; it compiles
 *       cleanly and fails only when the resource is opened.</li>
 *   <li><em>A test passes locally and fails intermittently in CI.</em> Something on the path under
 *       test is still reading the ambient clock, and the failure will cluster on second, day and
 *       month boundaries. Take a {@link java.time.Clock} as a collaborator and obtain it here.</li>
 *   <li><em>A rendering is 29 characters instead of 26.</em> The batch rendering was formatted to
 *       nanosecond precision. It is hundredths followed by four literal zeros; see
 *       {@link #batchTimestamp(Clock)}.</li>
 *   <li><em>{@link #onlineTimestamp(Clock)} or {@link #batchTimestamp(Clock)} throws
 *       {@link IllegalArgumentException}.</em> A moving clock was supplied. Supply one of the
 *       factories on this class instead. The guard compares two successive reads, so it is a
 *       diagnostic aid rather than a proof: it cannot in principle distinguish a fixed clock from a
 *       moving clock whose two reads happen to coincide.</li>
 * </ul>
 *
 * <p><strong>Thread safety and side effects.</strong> The class is final, cannot be instantiated,
 * and holds only immutable {@code static final} state, so there is no mutable static field, no
 * settable current clock and no cache a caller could perturb. Every method is a pure function of its
 * arguments: none mutates its input, none writes a log line and none performs I/O. Instances handed
 * out by the factories are independent of one another and safe to share across threads.
 */
public final class FixedClockProvider {

    /**
     * Length in characters of every CardDemo timestamp field, and therefore of every string this
     * class renders.
     *
     * <p>The value 26 is not a convention. It is proven three independent ways at commit
     * {@code 7756d89}: {@code app/cbl/CBTRN02C.cbl:L159} declares
     * {@code DB2-FORMAT-TS PIC X(26)} and its redefinition at {@code :L160-L174} sums to 26;
     * {@code app/cpy/CSDAT01Y.cpy:L42-L55} lays {@code WS-TIMESTAMP} out to 26; and
     * {@code app/cpy/CVTRA05Y.cpy:L16-L17} declares both transaction timestamps
     * {@code PIC X(26)}, which the 350 byte record geometry of
     * {@code app/data/ASCII/dailytran.txt} confirms at columns 279 to 304 and 305 to 330.
     */
    public static final int TIMESTAMP_LENGTH = 26;

    /**
     * The zone every factory on this class uses when none is supplied, fixed to
     * {@link java.time.ZoneOffset#UTC}.
     *
     * <p>Stated explicitly rather than inherited, because resolving it from the JVM default zone
     * would make the rendered text depend on the machine that ran the test.
     *
     * <p><strong>The zone the legacy system actually ran in is Not available.</strong> Both
     * producers yield region local time and neither stores an offset: {@code EXEC CICS ASKTIME} at
     * {@code app/cbl/COBIL00C.cbl:L251} and {@code FUNCTION CURRENT-DATE} at
     * {@code app/cbl/CBACT04C.cbl:L614} write into 26 byte fields whose every byte is accounted for
     * by the layouts of {@code app/cpy/CSDAT01Y.cpy:L42-L55} and
     * {@code app/cbl/CBTRN02C.cbl:L160-L174}, leaving no room for one. Determining it would require
     * information that is not in this repository: the CICS region's time zone configuration or the
     * z/OS {@code CVTLDTO} local time offset from the system that produced
     * {@code app/data/ASCII/dailytran.txt}. Neither is present, so a zone has to be chosen rather
     * than recovered, and the choice is recorded here rather than left implicit.
     *
     * <p>UTC is the choice because it is offset free and never observes daylight saving, which makes
     * {@link #CANONICAL_INSTANT} render to the frozen fixture bytes on every host. The consequence
     * is bounded and worth stating: the rendered text is reproducible everywhere, but it is not a
     * claim about which wall clock the mainframe read.
     */
    public static final ZoneId CANONICAL_ZONE = ZoneOffset.UTC;

    /**
     * The default instant every clock from {@link #canonicalClock()} reports,
     * {@code 2022-06-10T19:27:53Z}.
     *
     * <p>Evidence based rather than arbitrary. Columns 279 to 304 of all 300 rows of
     * {@code app/data/ASCII/dailytran.txt} hold exactly one distinct value, the originating
     * timestamp {@code 2022-06-10 19:27:53.000000}. Interpreted in {@link #CANONICAL_ZONE} this
     * instant renders through {@link #onlineTimestamp(Clock)} to that value character for character,
     * so a test can assert against the frozen fixture without transcribing a literal.
     *
     * <p>The instant carries no sub second component, which means
     * {@link #batchTimestamp(Clock)} renders its hundredths field as {@code 00}. To exercise a
     * non zero hundredths field, build a clock over an instant that has one by calling
     * {@link #fixedClock(Instant)}.
     */
    public static final Instant CANONICAL_INSTANT = Instant.parse("2022-06-10T19:27:53Z");

    /**
     * The online rendering of {@link #CANONICAL_INSTANT}, {@code 2022-06-10 19:27:53.000000}.
     *
     * <p>Exactly the 26 bytes found at columns 279 to 304 of every row of
     * {@code app/data/ASCII/dailytran.txt}. Held as a constant so an assertion can name the expected
     * value without repeating the literal, and so a change to either the canonical instant or the
     * rendering is caught by comparing the two.
     */
    public static final String CANONICAL_ONLINE_TIMESTAMP = "2022-06-10 19:27:53.000000";

    /**
     * The batch rendering of {@link #CANONICAL_INSTANT}, {@code 2022-06-10-19.27.53.000000}.
     *
     * <p>The same instant as {@link #CANONICAL_ONLINE_TIMESTAMP} and the same length, differing only
     * in separator geometry: a dash rather than a space at position 11 and periods rather than
     * colons between the time components, per
     * {@code MOVE '-' TO DB2-STREEP-1 DB2-STREEP-2 DB2-STREEP-3} and
     * {@code MOVE '.' TO DB2-DOT-1 DB2-DOT-2 DB2-DOT-3} at
     * {@code app/cbl/CBACT04C.cbl:L623-L624}. The trailing six characters are the two digit
     * hundredths field, {@code 00} here, followed by the four literal zeros of
     * {@code MOVE '0000' TO DB2-REST}.
     */
    public static final String CANONICAL_BATCH_TIMESTAMP = "2022-06-10-19.27.53.000000";

    /**
     * Bytes 1 to 19 of the online rendering: {@code uuuu-MM-dd HH:mm:ss}.
     *
     * <p>Mirrors the fixed layout of {@code WS-TIMESTAMP} at {@code app/cpy/CSDAT01Y.cpy:L42-L54}.
     * The single space between the day and the hour is the copybook's
     * {@code FILLER PIC X(01) VALUE ' '}, which is why byte 11 of the online rendering is a space
     * where the batch rendering carries a dash. Built with {@link java.util.Locale#ROOT} so digit
     * shaping cannot vary with the host locale.
     *
     * <p><strong>The year uses the proleptic pattern letter {@code u}, not {@code y}.</strong> This
     * is a correctness requirement, not a preference. {@code y} is year-of-era, so it renders the
     * proleptic year 0 - which is 1 BCE - as {@code 0001}, a value that is well formed, plausible and
     * wrong. The source has no era concept to render: {@code WS-TIMESTAMP-DT-YYYY} is
     * {@code PIC 9(04)} at {@code app/cpy/CSDAT01Y.cpy:L43}, an unsigned four digit absolute year,
     * and {@code DB2-YYYY} is {@code PIC X(004)} at {@code app/cbl/CBTRN02C.cbl:L161}, filled from
     * the leading four characters of {@code FUNCTION CURRENT-DATE}. {@code u} is the proleptic year
     * and therefore the faithful translation of both.
     */
    private static final DateTimeFormatter ONLINE_DATE_TIME =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss", Locale.ROOT);

    /**
     * Bytes 20 to 26 of the online rendering: the period and the six microsecond digits.
     *
     * <p>The period is the {@code FILLER PIC X(01) VALUE '.'} of
     * {@code app/cpy/CSDAT01Y.cpy:L54} and the six zeros are
     * {@code MOVE ZEROS TO WS-TIMESTAMP-TM-MS6} at {@code app/cbl/COBIL00C.cbl:L266}. They are
     * literal, not derived: the online rendering overwrites any sub second precision rather than
     * rounding it, which is why an instant carrying nanoseconds still renders {@code .000000}.
     */
    private static final String ONLINE_MICROSECONDS = ".000000";

    /**
     * Bytes 1 to 22 of the batch rendering: {@code uuuu-MM-dd-HH.mm.ss.SS}.
     *
     * <p>Mirrors the redefinition of {@code DB2-FORMAT-TS} at
     * {@code app/cbl/CBTRN02C.cbl:L160-L173}: the three dashes of
     * {@code MOVE '-' TO DB2-STREEP-1 DB2-STREEP-2 DB2-STREEP-3} and the three periods of
     * {@code MOVE '.' TO DB2-DOT-1 DB2-DOT-2 DB2-DOT-3}, both at
     * {@code app/cbl/CBACT04C.cbl:L623-L624}. The trailing {@code SS} emits two fractional digits,
     * that is hundredths of a second, truncated rather than rounded, matching {@code DB2-MIL} which
     * is filled from {@code COB-MIL} - the hundredths pair of the twenty one character
     * {@code FUNCTION CURRENT-DATE} result laid out at {@code app/cbl/CBTRN02C.cbl:L150-L158}.
     *
     * <p>The year uses the proleptic pattern letter {@code u} for the reason given on
     * {@link #ONLINE_DATE_TIME}.
     */
    private static final DateTimeFormatter BATCH_DATE_TIME =
            DateTimeFormatter.ofPattern("uuuu-MM-dd-HH.mm.ss.SS", Locale.ROOT);

    /**
     * Bytes 23 to 26 of the batch rendering: the four literal zeros of
     * {@code MOVE '0000' TO DB2-REST} at {@code app/cbl/CBACT04C.cbl:L622} and
     * {@code app/cbl/CBTRN02C.cbl:L701}.
     */
    private static final String BATCH_REST = "0000";

    /**
     * The character a COBOL alphanumeric move uses to fill a receiving field wider than its sender.
     */
    private static final char PAD_CHARACTER = ' ';

    /**
     * Lowest year the four digit year field can hold, given {@code DB2-YYYY PIC X(004)} at
     * {@code app/cbl/CBTRN02C.cbl:L161} and {@code WS-TIMESTAMP-DT-YYYY PIC 9(04)} at
     * {@code app/cpy/CSDAT01Y.cpy:L43}.
     *
     * <p>Because both formatters emit the proleptic year rather than the year of era, this bound and
     * {@link #MAX_REPRESENTABLE_YEAR} map exactly onto the unsigned four digit field: proleptic year
     * 0 renders {@code 0000} and proleptic year 9999 renders {@code 9999}. Anything below 0 would
     * render with a leading sign and so exceed {@link #TIMESTAMP_LENGTH} characters, and anything
     * above 9999 would need a fifth digit; both are rejected rather than silently emitted.
     */
    private static final int MIN_REPRESENTABLE_YEAR = 0;

    /**
     * Highest year the four digit year field can hold; see {@link #MIN_REPRESENTABLE_YEAR}.
     */
    private static final int MAX_REPRESENTABLE_YEAR = 9999;

    /**
     * Never instantiated. This type is a stateless factory over immutable constants, so an instance
     * would carry no state and confer no capability.
     *
     * @throws AssertionError always, including when invoked reflectively, so the type cannot be
     *                        turned into a holder for mutable state by accident
     */
    private FixedClockProvider() {
        throw new AssertionError(
                "FixedClockProvider is a stateless factory over immutable constants "
                        + "and must not be instantiated");
    }

    /**
     * Returns a fixed clock reporting {@link #CANONICAL_INSTANT} in {@link #CANONICAL_ZONE}.
     *
     * <p>This is the default time source for the unit tier. Rendering it through
     * {@link #onlineTimestamp(Clock)} yields {@link #CANONICAL_ONLINE_TIMESTAMP}, which is byte for
     * byte the originating timestamp carried by every row of {@code app/data/ASCII/dailytran.txt};
     * rendering it through {@link #batchTimestamp(Clock)} yields
     * {@link #CANONICAL_BATCH_TIMESTAMP}.
     *
     * <p>Inputs: none. Output: a non-null clock whose {@code instant()} is invariant across calls
     * and across threads, and whose zone is UTC. Side effects: none; the returned clock reads no
     * system state and each call yields an equal but independent instance. Error modes: none, the
     * method cannot fail.
     *
     * @return a fixed clock at {@link #CANONICAL_INSTANT} in {@link #CANONICAL_ZONE}, never
     *         {@code null}
     */
    public static Clock canonicalClock() {
        return Clock.fixed(CANONICAL_INSTANT, CANONICAL_ZONE);
    }

    /**
     * Returns a fixed clock reporting the supplied instant in {@link #CANONICAL_ZONE}.
     *
     * <p>Use this when a test needs a specific moment rather than the canonical one, for example an
     * instant carrying a sub second component so that {@link #batchTimestamp(Clock)} renders a
     * non zero hundredths field. The zone is still explicit: it is
     * {@link #CANONICAL_ZONE}, never the host default.
     *
     * <p>Inputs: {@code instant}, required. Output: a non-null fixed clock. Side effects: none.
     * Error modes: a {@code null} instant is rejected rather than defaulted, because silently
     * substituting a default would hide the mistake the caller made.
     *
     * @param instant the moment the clock reports; must not be {@code null}
     * @return a fixed clock at {@code instant} in {@link #CANONICAL_ZONE}, never {@code null}
     * @throws NullPointerException if {@code instant} is {@code null}
     */
    public static Clock fixedClock(final Instant instant) {
        return fixedClock(instant, CANONICAL_ZONE);
    }

    /**
     * Returns a fixed clock reporting the supplied instant in the supplied zone.
     *
     * <p>The zone is a required argument rather than an optional one on purpose. The legacy corpus
     * stores no offset in its 26 byte timestamp fields, so the zone is a property of the test and
     * not of the data, and leaving it implicit would let the rendered text vary with the host.
     *
     * <p>Inputs: {@code instant} and {@code zone}, both required. Output: a non-null fixed clock.
     * Side effects: none. Error modes: either argument being {@code null} is rejected; no default is
     * substituted for the zone, and in particular the JVM default zone accessor is never consulted.
     *
     * @param instant the moment the clock reports; must not be {@code null}
     * @param zone    the zone the clock reports; must not be {@code null}
     * @return a fixed clock at {@code instant} in {@code zone}, never {@code null}
     * @throws NullPointerException if {@code instant} or {@code zone} is {@code null}
     */
    public static Clock fixedClock(final Instant instant, final ZoneId zone) {
        Objects.requireNonNull(instant,
                "instant must not be null: a fixed clock has to be pinned to an explicit moment, "
                        + "and defaulting one here would reintroduce the ambient clock this class exists to remove");
        Objects.requireNonNull(zone,
                "zone must not be null: the legacy 26 byte timestamp fields store no offset, so the "
                        + "zone belongs to the test and must be stated rather than inherited from the host");
        return Clock.fixed(instant, zone);
    }

    /**
     * Renders the supplied clock in the online timestamp layout,
     * {@code yyyy-MM-dd HH:mm:ss.000000}.
     *
     * <p>Reproduces {@code GET-CURRENT-TIMESTAMP} at {@code app/cbl/COBIL00C.cbl:L249-L267} over the
     * {@code WS-TIMESTAMP} geometry of {@code app/cpy/CSDAT01Y.cpy:L42-L55}. Byte 11 is a space and
     * bytes 21 to 26 are six literal zeros, so <strong>any sub second component of the clock is
     * discarded, not rounded</strong>: that is what {@code MOVE ZEROS TO WS-TIMESTAMP-TM-MS6} at
     * {@code app/cbl/COBIL00C.cbl:L266} does.
     *
     * <p>Inputs: {@code clock}, required and expected to be fixed. Output: a non-null string of
     * exactly {@link #TIMESTAMP_LENGTH} characters, suitable for direct comparison against a
     * {@code CHAR(26)} column or against columns 279 to 304 of a 350 byte transaction record. Side
     * effects: none; the clock is read but not modified and nothing is logged. Error modes: a
     * {@code null} clock, a clock with a {@code null} zone, a clock whose two successive reads
     * disagree, and a moment whose year falls outside the four digit field are each rejected with a
     * message naming the cause.
     *
     * @param clock the fixed clock to render; must not be {@code null}
     * @return the 26 character online rendering, never {@code null}
     * @throws NullPointerException     if {@code clock} is {@code null}
     * @throws IllegalArgumentException if {@code clock} does not report a stable instant, if its
     *                                  zone is {@code null}, or if the resulting year cannot be
     *                                  represented in four digits
     */
    public static String onlineTimestamp(final Clock clock) {
        return ONLINE_DATE_TIME.format(momentOf(clock)) + ONLINE_MICROSECONDS;
    }

    /**
     * Renders the supplied clock in the batch timestamp layout,
     * {@code yyyy-MM-dd-HH.mm.ss.SS0000}.
     *
     * <p>Reproduces {@code Z-GET-DB2-FORMAT-TIMESTAMP} at {@code app/cbl/CBACT04C.cbl:L613-L625}
     * and, character for character, at {@code app/cbl/CBTRN02C.cbl:L692-L706}, over the
     * {@code DB2-FORMAT-TS} redefinition at {@code app/cbl/CBTRN02C.cbl:L159-L175}. Two differences
     * from {@link #onlineTimestamp(Clock)} are load bearing: byte 11 is a <strong>dash</strong>
     * rather than a space, because the paragraph moves a dash into three separator bytes; and the
     * fractional field is <strong>hundredths</strong> of a second followed by four literal zeros,
     * never nanoseconds, because {@code DB2-MIL} is a two digit field and {@code DB2-REST} is the
     * literal {@code '0000'}. Hundredths are truncated rather than rounded, matching the
     * {@code FUNCTION CURRENT-DATE} hundredths pair the paragraph copies.
     *
     * <p>Inputs: {@code clock}, required and expected to be fixed. Output: a non-null string of
     * exactly {@link #TIMESTAMP_LENGTH} characters. Side effects: none. Error modes: identical to
     * {@link #onlineTimestamp(Clock)}.
     *
     * @param clock the fixed clock to render; must not be {@code null}
     * @return the 26 character batch rendering, never {@code null}
     * @throws NullPointerException     if {@code clock} is {@code null}
     * @throws IllegalArgumentException if {@code clock} does not report a stable instant, if its
     *                                  zone is {@code null}, or if the resulting year cannot be
     *                                  represented in four digits
     */
    public static String batchTimestamp(final Clock clock) {
        return BATCH_DATE_TIME.format(momentOf(clock)) + BATCH_REST;
    }

    /**
     * Reproduces a COBOL alphanumeric move of the supplied text into a
     * {@code PIC X(}{@link #TIMESTAMP_LENGTH}{@code )} receiving field: left justify, fill the
     * remainder with spaces, and truncate on the right anything beyond the field width.
     *
     * <p>This is the third and least obvious of the three legacy timestamp behaviours, and it is not
     * an identity function. {@code app/cbl/COTRN02C.cbl:L464-L465} moves the screen fields
     * {@code TORIGDTI} and {@code TPROCDTI}, declared {@code PIC X(10)} at
     * {@code app/cpy-bms/COTRN02.CPY:L102} and {@code :L108}, into {@code TRAN-ORIG-TS} and
     * {@code TRAN-PROC-TS}, declared {@code PIC X(26)} at {@code app/cpy/CVTRA05Y.cpy:L16-L17}, so
     * ten characters of screen text arrive in a 26 character field followed by sixteen spaces. Where
     * the widths already agree - {@code app/cbl/CBTRN02C.cbl:L436} moving
     * {@code DALYTRAN-ORIG-TS} into {@code TRAN-ORIG-TS}, both {@code PIC X(26)} - the operation
     * degenerates to an exact copy, which is the case the frozen fixture exercises.
     *
     * <p>Inputs: {@code sendingField}, required; the empty string is accepted and yields
     * {@link #TIMESTAMP_LENGTH} spaces, which is what a move from a blank field produces. Output: a
     * non-null string of exactly {@link #TIMESTAMP_LENGTH} characters. Side effects: none; the
     * argument is not modified, since Java strings are immutable. Error modes: {@code null} is
     * rejected rather than treated as blank, because a COBOL move has no null and conflating the two
     * would mask a missing value; text containing a supplementary character is also rejected, since
     * such a character occupies two Java {@code char} positions but one field position and would
     * silently break the fixed width contract.
     *
     * @param sendingField the text being moved; must not be {@code null}, may be empty, may be
     *                     shorter or longer than the receiving field
     * @return the text as it would appear in the {@link #TIMESTAMP_LENGTH} character receiving
     *         field, never {@code null}
     * @throws NullPointerException     if {@code sendingField} is {@code null}
     * @throws IllegalArgumentException if {@code sendingField} contains a supplementary character
     */
    public static String passThroughTimestamp(final String sendingField) {
        Objects.requireNonNull(sendingField,
                "sendingField must not be null: a COBOL alphanumeric move has no null sender, so "
                        + "null here means a value went missing upstream and must not be read as blank");
        final int length = sendingField.length();
        if (sendingField.codePointCount(0, length) != length) {
            throw new IllegalArgumentException(
                    "sendingField contains a supplementary character, which occupies two char "
                            + "positions but one field position and would break the fixed width "
                            + "contract of the " + TIMESTAMP_LENGTH + " character receiving field");
        }
        if (length >= TIMESTAMP_LENGTH) {
            return sendingField.substring(0, TIMESTAMP_LENGTH);
        }
        final StringBuilder receivingField = new StringBuilder(TIMESTAMP_LENGTH);
        receivingField.append(sendingField);
        while (receivingField.length() < TIMESTAMP_LENGTH) {
            receivingField.append(PAD_CHARACTER);
        }
        return receivingField.toString();
    }

    /**
     * Reads the supplied clock once and returns the moment it reports, after proving that the clock
     * is usable as a deterministic time source.
     *
     * <p>Three conditions are checked, each of which produces a wrong or non-reproducible rendering
     * if it is left unchecked. The clock and its zone must be present. The clock must report the
     * same instant on two successive reads, which every {@code Clock.fixed} instance does and a wall
     * clock effectively never does; this is a diagnostic aid rather than a proof, because two reads
     * of a moving clock could in principle coincide. And the resulting year must fit the unsigned
     * four digit year field the source declares, because a year below
     * {@link #MIN_REPRESENTABLE_YEAR} renders with a leading sign and a year above
     * {@link #MAX_REPRESENTABLE_YEAR} needs a fifth digit, either of which would silently push the
     * rendering past {@link #TIMESTAMP_LENGTH} characters.
     *
     * <p>The clock is read through {@code instant()} rather than through any accessor named after
     * the present moment, so that no ambient clock call site exists anywhere in this file to be
     * copied by a reader.
     *
     * @param clock the clock to validate and read; must not be {@code null}
     * @return the moment the clock reports, resolved in the clock's own zone, never {@code null}
     * @throws NullPointerException     if {@code clock} is {@code null}
     * @throws IllegalArgumentException if the clock's zone is {@code null}, if two successive reads
     *                                  disagree, or if the year cannot be represented in four digits
     */
    private static ZonedDateTime momentOf(final Clock clock) {
        Objects.requireNonNull(clock,
                "clock must not be null: obtain one from canonicalClock() or fixedClock(...) so the "
                        + "rendered timestamp is reproducible");
        final ZoneId zone = clock.getZone();
        if (zone == null) {
            throw new IllegalArgumentException(
                    "clock.getZone() returned null, so the instant cannot be resolved to a local "
                            + "date and time; obtain the clock from canonicalClock() or fixedClock(...)");
        }
        final Instant first = clock.instant();
        final Instant second = clock.instant();
        if (!first.equals(second)) {
            throw new IllegalArgumentException(
                    "clock is not fixed: two successive reads returned different instants, so any "
                            + "rendering of it would be irreproducible; obtain the clock from "
                            + "canonicalClock() or fixedClock(...) instead");
        }
        final ZonedDateTime moment = first.atZone(zone);
        final int year = moment.getYear();
        if (year < MIN_REPRESENTABLE_YEAR || year > MAX_REPRESENTABLE_YEAR) {
            throw new IllegalArgumentException(
                    "year " + year + " cannot be represented in the four digit year field the source "
                            + "declares, so the rendering would not be " + TIMESTAMP_LENGTH
                            + " characters; supply an instant between years " + MIN_REPRESENTABLE_YEAR
                            + " and " + MAX_REPRESENTABLE_YEAR + " inclusive");
        }
        return moment;
    }
}
