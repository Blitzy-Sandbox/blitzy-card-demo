package com.vsergeychik.carddemo.account;

import com.vsergeychik.carddemo.common.FixedWidthCodec;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Translation of {@code app/cpy/CSLKPCDY.cpy}, the CardDemo lookup-code repository: the North
 * American telephone area codes, the United States state codes, and the permitted
 * state-plus-first-two-digits-of-ZIP combinations.
 *
 * <h2>What the source is</h2>
 *
 * <p>{@code CSLKPCDY.cpy} is 1,318 lines long and contains no executable statement at all. It
 * declares three {@code 01}-level items &mdash; two elementary and one group of two subordinates
 * &mdash; and hangs five {@code 88}-level condition names off them, each condition name being an
 * exhaustive {@code VALUES} list. Its own banner names
 * the three lookups it holds: "1) North america phone area codes", "2) United States - state
 * codes", "3) United States - state + first 2 of zip". The structure, verified line by line, is:
 *
 * <table border="1">
 *   <caption>The declarations of {@code app/cpy/CSLKPCDY.cpy}</caption>
 *   <tr><th>Line</th><th>Declaration</th><th>Literals</th></tr>
 *   <tr><td>24</td><td>{@code 01 WS-US-PHONE-AREA-CODE-TO-EDIT PIC XXX.}</td><td>&mdash;</td></tr>
 *   <tr><td>30&ndash;520</td><td>{@code 88 VALID-PHONE-AREA-CODE VALUES '201', ...}</td>
 *       <td><strong>490</strong></td></tr>
 *   <tr><td>521&ndash;930</td><td>{@code 88 VALID-GENERAL-PURP-CODE VALUES '201', ...}</td>
 *       <td><strong>410</strong></td></tr>
 *   <tr><td>931&ndash;1010</td><td>{@code 88 VALID-EASY-RECOG-AREA-CODE VALUES '200', ...}</td>
 *       <td><strong>80</strong></td></tr>
 *   <tr><td>1012</td><td>{@code 01 US-STATE-CODE-TO-EDIT PIC X(2).}</td><td>&mdash;</td></tr>
 *   <tr><td>1013&ndash;1069</td><td>{@code 88 VALID-US-STATE-CODE VALUES ...}</td>
 *       <td><strong>56</strong></td></tr>
 *   <tr><td>1071</td><td>{@code 01 US-STATE-ZIPCODE-TO-EDIT.}</td><td>&mdash;</td></tr>
 *   <tr><td>1072</td><td>{@code 02 US-STATE-AND-FIRST-ZIP2 PIC X(4).}</td><td>&mdash;</td></tr>
 *   <tr><td>1073&ndash;1313</td><td>{@code 88 VALID-US-STATE-ZIP-CD2-COMBO VALUES 'AA34' ... 'WY83'}</td>
 *       <td><strong>240</strong></td></tr>
 *   <tr><td>1314</td><td>{@code 02 LAST-3-OF-ZIP PIC X(3).}</td><td>&mdash;</td></tr>
 * </table>
 *
 * <p>Two properties of that table are load-bearing and are asserted rather than assumed. First, the
 * literal counts: 490, 410, 80, 56 and 240, checked at class-initialisation time by
 * {@link #requireExactSize(Set, int, String)} so that a literal lost to a careless edit fails the
 * very first use of this class instead of silently rejecting a valid area code years later. Second,
 * the arithmetic between the three area-code lists:
 * {@code VALID-GENERAL-PURP-CODE (410) + VALID-EASY-RECOG-AREA-CODE (80) = VALID-PHONE-AREA-CODE
 * (490)}, the two subsets are disjoint, and both are proper subsets of the union.
 *
 * <h2>Why every literal is written out</h2>
 *
 * <p>A {@code VALUES} list is an <em>exact membership set</em>, not a range and not a rule. The
 * temptation with 490 three-digit codes is to notice that they look like a pattern and replace them
 * with a range test or a regular expression. That would be wrong, and provably so: {@code '201'} is
 * a member and {@code '221'} is not; {@code '199'} is not a member and {@code '200'} is;
 * {@code '999'} is a member and {@code '998'} is not; and {@code '200'} is an easily recognisable
 * code while {@code '201'} is a general purpose one. The trap is that one of the three lists really
 * does follow a pattern &mdash; the 80 easily recognisable codes are exactly {@code 'N00'},
 * {@code 'N11'}, {@code 'N22'} ... {@code 'N99'} for each of the eight hundreds &mdash; which makes
 * it tempting to compute the other two as well, and the other two follow no pattern whatsoever. Any
 * generated approximation admits or rejects at least one code the COBOL does not, and that is a
 * parity diff. So the lists below are transcribed in full, in the copybook's own declaration order,
 * one literal per line, each carrying the copybook line number it came from as a trailing comment.
 * A reviewer holding {@code CSLKPCDY.cpy} open beside this file can confirm every one of the 1,276
 * literals by eye.
 *
 * <p>Nothing here was retyped by hand. The lists were extracted mechanically from the copybook and
 * are verified by re-parsing this file and diffing it back against
 * {@code app/cpy/CSLKPCDY.cpy}, so a transcription slip cannot survive.
 *
 * <h2>The only consumer, and exactly how it probes</h2>
 *
 * <p>{@code app/cbl/COACTUPC.cbl} is the sole program that copies this copybook, at line 602, and it
 * tests these condition names at exactly three sites. Each site is reproduced below by one method,
 * and each site's own {@code MOVE} or {@code STRING} is reproduced along with it, because the width
 * rule of that statement is part of the test:
 *
 * <ol>
 *   <li><strong>{@code EDIT-AREA-CODE}</strong> (paragraph at line 2246). Lines 2296&ndash;2297 read
 *       {@code MOVE FUNCTION TRIM (WS-EDIT-US-PHONE-NUMA) TO WS-US-PHONE-AREA-CODE-TO-EDIT} and line
 *       2298 then tests {@code IF VALID-GENERAL-PURP-CODE} &mdash; the 410-entry <em>subset</em>,
 *       not the 490-entry union. Its failure message at line 2306 confirms it: "Not valid North
 *       America general purpose area code". Three guards run before the lookup is reached: blank or
 *       {@code LOW-VALUES} is rejected at line 2247, non-numeric at line 2264 and a value of zero at
 *       line 2280, each with its own message, so this class is only ever asked about a three-digit
 *       non-zero string. See {@link #isValidGeneralPurposeCode(String)}.</li>
 *   <li><strong>{@code 1270-EDIT-US-STATE-CD}</strong> (line 2493). Line 2494 reads
 *       {@code MOVE ACUP-NEW-CUST-ADDR-STATE-CD TO US-STATE-CODE-TO-EDIT} and line 2495 tests
 *       {@code IF VALID-US-STATE-CODE}. The sender is {@code PIC X(02)} (line 807) and the receiver
 *       is {@code PIC X(2)}, so this move is a straight same-width copy with no trim. See
 *       {@link #isValidUsStateCode(String)}.</li>
 *   <li><strong>{@code 1280-EDIT-US-STATE-ZIP-CD}</strong> (line 2536, introduced by the source's own
 *       comment "A crude zip code edit based on data from USPS web site"). Lines 2537&ndash;2540 read
 *       {@code STRING ACUP-NEW-CUST-ADDR-STATE-CD ACUP-NEW-CUST-ADDR-ZIP(1:2) DELIMITED BY SIZE INTO
 *       US-STATE-AND-FIRST-ZIP2} and line 2542 tests {@code IF VALID-US-STATE-ZIP-CD2-COMBO}. See
 *       {@link #isValidStateAndZipCombination(String, String)}.</li>
 * </ol>
 *
 * <p>{@code VALID-PHONE-AREA-CODE} and {@code VALID-EASY-RECOG-AREA-CODE} have <strong>no</strong>
 * consumer in this repository today. They are reproduced anyway, because they are part of the
 * copybook's declared contract and deleting an unused declaration is a change to the artefact being
 * migrated, not a tidy-up of it. The same reasoning keeps {@code LAST-3-OF-ZIP} (line 1314) present
 * below even though no statement in {@code COACTUPC} ever reads or writes it.
 *
 * <h2>Comparison semantics: a fixed-width, case-sensitive, byte-for-byte test</h2>
 *
 * <p>The three probes are {@code PIC XXX}, {@code PIC X(2)} and {@code PIC X(4)}. A COBOL
 * alphanumeric {@code MOVE} into such an item fills it from the left, <strong>pads on the right with
 * spaces</strong> when the sender is shorter and <strong>truncates on the right</strong> when the
 * sender is longer; the {@code 88}-level test that follows is then an equality comparison between
 * two same-width character images. Java's {@code equals} does none of that padding or truncating, so
 * every argument accepted below is first put through
 * {@link FixedWidthCodec#movePicX(String, int)} at the probe's declared width. Three consequences
 * are worth stating outright, because each of them is a place a naive {@code Set.contains(value)}
 * would diverge:
 *
 * <ul>
 *   <li>An over-long argument is <strong>truncated, not rejected</strong>. {@code "2011"} probed
 *       against {@code PIC XXX} becomes {@code "201"} and is therefore <em>valid</em>, exactly as
 *       {@code MOVE '2011' TO WS-US-PHONE-AREA-CODE-TO-EDIT} would make it. Truncation on the left
 *       would give {@code "011"} and rejection would give a different answer again; both are wrong.
 *   </li>
 *   <li>A short argument is <strong>space-padded on the right</strong>, so {@code "20"} becomes
 *       {@code "20 "} and matches nothing. A blank or empty argument becomes {@code "   "} and is
 *       likewise a member of no set: the copybook lists no all-blank literal.</li>
 *   <li>The comparison is <strong>case-sensitive</strong>. Every literal in the copybook is
 *       upper-case and COBOL performs no case folding in an {@code 88}-level test, so {@code "ca"}
 *       is not {@code "CA"} and {@code "wy83"} is not {@code "WY83"}. Upper-casing an argument here
 *       would make this class accept input the COBOL rejects.</li>
 * </ul>
 *
 * <p>No {@code trim()} and no width-adjusting {@code substring()} appears anywhere below. Width is
 * exclusively the codec's business, which is what makes the direction of every pad and every
 * truncation reviewable at the call site rather than buried in string arithmetic.
 *
 * <h2>Shape, state and wiring</h2>
 *
 * <p>A stateless, immutable, injectable {@link Component}. The five lookup tables are
 * {@code private static final} unmodifiable {@link LinkedHashSet}s: {@code static} because 1,276
 * literals should be interned once for the life of the JVM rather than per instance,
 * <em>unmodifiable</em> because binding practice B9 forbids mutable static state and an ordinary
 * {@code Set} field &mdash; or a {@code String[]} field, which is mutable however it is declared
 * &mdash; would be exactly that. {@link LinkedHashSet} rather than {@link Set#of} because the
 * copybook's declaration order is preserved deliberately, so that iterating a table here walks the
 * copybook top to bottom.
 *
 * <p>Two public constructors, and the distinction matters for wiring. Component scanning finds no
 * {@code @Autowired} constructor and more than one candidate, so Spring instantiates this bean
 * through the {@linkplain #AreaCodeLookup() no-argument constructor}, which builds its own codec.
 * That is deliberate: {@link FixedWidthCodec} is not itself a bean &mdash; it takes a mandatory
 * {@code Charset}, and {@code CobolCharsetConfig} publishes three of those with no {@code @Primary}
 * among them &mdash; so making the codec a mandatory constructor parameter here would leave this
 * bean unsatisfiable and break context startup. Callers that already hold a configured codec should
 * use {@link #AreaCodeLookup(FixedWidthCodec)} instead. Please do not "helpfully" add
 * {@code @Autowired} to either constructor.
 *
 * @see FixedWidthCodec#movePicX(String, int)
 */
@Component
public final class AreaCodeLookup {

    // =================================================================================================
    // Declared widths. Every one of these is a PICTURE clause, cited to the line that declares it.
    // =================================================================================================

    /**
     * Width of {@code 01 WS-US-PHONE-AREA-CODE-TO-EDIT PIC XXX} &mdash;
     * {@code app/cpy/CSLKPCDY.cpy:24}. {@code PIC XXX} is the character-by-character spelling of
     * {@code PIC X(3)}; the two are identical.
     */
    public static final int PHONE_AREA_CODE_LENGTH = 3;

    /** Width of {@code 01 US-STATE-CODE-TO-EDIT PIC X(2)} &mdash; {@code app/cpy/CSLKPCDY.cpy:1012}. */
    public static final int US_STATE_CODE_LENGTH = 2;

    /**
     * Width of {@code 02 US-STATE-AND-FIRST-ZIP2 PIC X(4)} &mdash;
     * {@code app/cpy/CSLKPCDY.cpy:1072}. Two characters of state code followed by two of ZIP.
     */
    public static final int STATE_AND_FIRST_ZIP2_LENGTH = 4;

    /**
     * Width of {@code 02 LAST-3-OF-ZIP PIC X(3)} &mdash; {@code app/cpy/CSLKPCDY.cpy:1314}. Declared,
     * and never referenced by any statement in {@code app/cbl/COACTUPC.cbl}; preserved because it is
     * part of the group's layout.
     */
    public static final int LAST_3_OF_ZIP_LENGTH = 3;

    /**
     * Width of the whole group {@code 01 US-STATE-ZIPCODE-TO-EDIT} &mdash;
     * {@code app/cpy/CSLKPCDY.cpy:1071}. A group item's width is the sum of its subordinates:
     * {@code 4 + 3 = 7}.
     */
    public static final int STATE_ZIPCODE_GROUP_LENGTH =
            STATE_AND_FIRST_ZIP2_LENGTH + LAST_3_OF_ZIP_LENGTH;

    /**
     * Zero-based offset of {@code US-STATE-AND-FIRST-ZIP2} within
     * {@code 01 US-STATE-ZIPCODE-TO-EDIT}: it is the first subordinate, so COBOL position 1.
     */
    public static final int STATE_AND_FIRST_ZIP2_OFFSET = 0;

    /**
     * Zero-based offset of {@code LAST-3-OF-ZIP} within {@code 01 US-STATE-ZIPCODE-TO-EDIT}: it
     * follows a four-character subordinate, so COBOL position 5.
     */
    public static final int LAST_3_OF_ZIP_OFFSET = STATE_AND_FIRST_ZIP2_LENGTH;

    /**
     * Width of {@code ACUP-NEW-CUST-ADDR-ZIP PIC X(10)} &mdash; {@code app/cbl/COACTUPC.cbl:809}.
     * Declared here rather than in the copybook because it is the sending field of the
     * {@code STRING} at {@code app/cbl/COACTUPC.cbl:2537-2540}, and its declared width is what makes
     * the reference modification {@code (1:2)} on the following line well defined.
     */
    public static final int CUSTOMER_ZIP_LENGTH = 10;

    /**
     * Number of ZIP characters the {@code STRING} at {@code app/cbl/COACTUPC.cbl:2538} contributes:
     * {@code ACUP-NEW-CUST-ADDR-ZIP(1:2)} takes exactly the first two.
     */
    public static final int ZIP_PREFIX_LENGTH = 2;

    // =================================================================================================
    // Declared cardinalities. Each is checked at class-initialisation time against the table it
    // describes, so a lost or duplicated literal is a hard failure rather than a silent one.
    // =================================================================================================

    /** Literals in {@code 88 VALID-PHONE-AREA-CODE} &mdash; {@code app/cpy/CSLKPCDY.cpy:30-520}. */
    public static final int VALID_PHONE_AREA_CODE_COUNT = 490;

    /** Literals in {@code 88 VALID-GENERAL-PURP-CODE} &mdash; {@code app/cpy/CSLKPCDY.cpy:521-930}. */
    public static final int VALID_GENERAL_PURP_CODE_COUNT = 410;

    /**
     * Literals in {@code 88 VALID-EASY-RECOG-AREA-CODE} &mdash;
     * {@code app/cpy/CSLKPCDY.cpy:931-1010}.
     */
    public static final int VALID_EASY_RECOG_AREA_CODE_COUNT = 80;

    /** Literals in {@code 88 VALID-US-STATE-CODE} &mdash; {@code app/cpy/CSLKPCDY.cpy:1013-1069}. */
    public static final int VALID_US_STATE_CODE_COUNT = 56;

    /**
     * Literals in {@code 88 VALID-US-STATE-ZIP-CD2-COMBO} &mdash;
     * {@code app/cpy/CSLKPCDY.cpy:1073-1313}.
     */
    public static final int VALID_US_STATE_ZIP_CD2_COMBO_COUNT = 240;

    // =================================================================================================
    // COBOL item names, used verbatim in diagnostics so a failure names the copybook item that failed
    // rather than a Java identifier a COBOL reader would not recognise.
    // =================================================================================================

    /** {@code app/cpy/CSLKPCDY.cpy:24}. */
    private static final String ITEM_PHONE_AREA_CODE_PROBE = "WS-US-PHONE-AREA-CODE-TO-EDIT";

    /** {@code app/cpy/CSLKPCDY.cpy:1012}. */
    private static final String ITEM_US_STATE_CODE_PROBE = "US-STATE-CODE-TO-EDIT";

    /** {@code app/cpy/CSLKPCDY.cpy:1072}. */
    private static final String ITEM_STATE_AND_FIRST_ZIP2 = "US-STATE-AND-FIRST-ZIP2";

    /** {@code app/cpy/CSLKPCDY.cpy:1071}. */
    private static final String ITEM_STATE_ZIPCODE_GROUP = "US-STATE-ZIPCODE-TO-EDIT";

    /** {@code app/cbl/COACTUPC.cbl:807}. */
    private static final String ITEM_CUSTOMER_STATE_CODE = "ACUP-NEW-CUST-ADDR-STATE-CD";

    /** {@code app/cbl/COACTUPC.cbl:809}. */
    private static final String ITEM_CUSTOMER_ZIP = "ACUP-NEW-CUST-ADDR-ZIP";

    // =================================================================================================
    // The five lookup tables. Each is built by a method at the foot of this file that holds its
    // literals one per line, in copybook order, with the copybook line number as a trailing comment.
    // =================================================================================================

    /**
     * {@code 88 VALID-PHONE-AREA-CODE} &mdash; the complete North American Numbering Plan area-code
     * list of {@code app/cpy/CSLKPCDY.cpy:30-520}, in declaration order, {@code '201'} through
     * {@code '999'}. The union of the two lists that follow, and the only one of the three that no
     * program in this repository currently tests.
     */
    private static final Set<String> VALID_PHONE_AREA_CODE = buildValidPhoneAreaCode();

    /**
     * {@code 88 VALID-GENERAL-PURP-CODE} &mdash; {@code app/cpy/CSLKPCDY.cpy:521-930}, in
     * declaration order, {@code '201'} through {@code '989'}. This is the list
     * {@code app/cbl/COACTUPC.cbl:2298} actually tests.
     */
    private static final Set<String> VALID_GENERAL_PURP_CODE = buildValidGeneralPurpCode();

    /**
     * {@code 88 VALID-EASY-RECOG-AREA-CODE} &mdash; {@code app/cpy/CSLKPCDY.cpy:931-1010}, in
     * declaration order, {@code '200'} through {@code '999'}. The copybook flags the start of this
     * range with an in-list comment at line 440, "Easily recognizable codes begin here." Note that
     * it starts at {@code '200'}, not {@code '201'}: the easily recognisable codes are the
     * service-style codes such as {@code '800'} and {@code '911'}, disjoint from the general purpose
     * list above.
     */
    private static final Set<String> VALID_EASY_RECOG_AREA_CODE = buildValidEasyRecogAreaCode();

    /**
     * {@code 88 VALID-US-STATE-CODE} &mdash; {@code app/cpy/CSLKPCDY.cpy:1013-1069}, in declaration
     * order, {@code 'AL'} through {@code 'VI'}. Fifty-six entries, and exactly which fifty-six
     * matters: the fifty states in postal-abbreviation order, then {@code 'DC'}, then the five
     * territory codes {@code 'AS'}, {@code 'GU'}, {@code 'MP'}, {@code 'PR'} and {@code 'VI'}. The
     * armed-forces codes {@code 'AA'}, {@code 'AE'} and {@code 'AP'} are <strong>not</strong> here,
     * even though the ZIP table below accepts prefixes built from them &mdash; see
     * {@link #isValidStateAndZipCombination(String, String)}.
     */
    private static final Set<String> VALID_US_STATE_CODE = buildValidUsStateCode();

    /**
     * {@code 88 VALID-US-STATE-ZIP-CD2-COMBO} &mdash; {@code app/cpy/CSLKPCDY.cpy:1073-1313}, in
     * declaration order, {@code 'AA34'} through {@code 'WY83'}. Each entry is a two-character state
     * code immediately followed by the first two digits of a ZIP code that the United States Postal
     * Service assigns to that state.
     */
    private static final Set<String> VALID_US_STATE_ZIP_CD2_COMBO = buildValidUsStateZipCd2Combo();

    // =================================================================================================
    // Instance state: the codec, and nothing else.
    // =================================================================================================

    /**
     * The {@code PICTURE} engine that applies the alphanumeric {@code MOVE} rule to every argument
     * before it is looked up. Held rather than created per call so the width rule has exactly one
     * implementation in play for the life of this instance.
     */
    private final FixedWidthCodec codec;

    /**
     * Creates a lookup with its own codec over {@link StandardCharsets#US_ASCII}.
     *
     * <p>This is the constructor Spring uses: component scanning finds no {@code @Autowired}
     * constructor and two candidates, so it instantiates through this one. It has to be able to,
     * because {@link FixedWidthCodec} is not a bean.
     *
     * <p>The charset is named explicitly, never taken from the platform, in keeping with the rule
     * that governs every encoding decision in this module. It is also, uniquely here,
     * <em>immaterial</em>: {@link FixedWidthCodec#movePicX(String, int)} is pure character work and
     * never consults the charset, and this class reads no dataset bytes whatsoever &mdash; it holds
     * a table of literals and compares strings. {@code US-ASCII} is chosen because every character
     * in play is a digit or an upper-case Latin letter, so it satisfies the codec's requirement that
     * the digits, the sign overpunch alphabets and the space each encode to a single byte. A caller
     * holding a codec configured for a real dataset code page should pass it to
     * {@link #AreaCodeLookup(FixedWidthCodec)} instead; the answers are identical either way.
     */
    public AreaCodeLookup() {
        this(new FixedWidthCodec(StandardCharsets.US_ASCII));
    }

    /**
     * Creates a lookup that applies {@code PICTURE} width rules through the supplied codec.
     *
     * @param codec the codec whose {@link FixedWidthCodec#movePicX(String, int)} normalises every
     *              argument to its probe's declared width; must not be {@code null}
     * @throws NullPointerException if {@code codec} is {@code null}
     */
    public AreaCodeLookup(FixedWidthCodec codec) {
        this.codec = Objects.requireNonNull(codec, "A FixedWidthCodec is required: every argument "
                + "is put through the COBOL alphanumeric MOVE rule at its probe's declared width "
                + "before it is looked up, and that rule is the codec's to apply");
    }

    // =================================================================================================
    // 88 VALID-PHONE-AREA-CODE, 88 VALID-GENERAL-PURP-CODE, 88 VALID-EASY-RECOG-AREA-CODE.
    // Probe: 01 WS-US-PHONE-AREA-CODE-TO-EDIT PIC XXX  (app/cpy/CSLKPCDY.cpy:24)
    // =================================================================================================

    /**
     * Tests {@code 88 VALID-PHONE-AREA-CODE} &mdash; is this one of the 490 North American Numbering
     * Plan area codes of {@code app/cpy/CSLKPCDY.cpy:30-520}?
     *
     * <p>The argument is first moved into {@code WS-US-PHONE-AREA-CODE-TO-EDIT PIC XXX}, so it is
     * space-padded on the right when shorter than three characters and truncated on the right when
     * longer. The comparison is then case-sensitive and byte-for-byte.
     *
     * <p>No program in this repository tests this condition name. It is reproduced because it is part
     * of the copybook's contract; {@code app/cbl/COACTUPC.cbl:2298} tests the narrower
     * {@link #isValidGeneralPurposeCode(String)} instead.
     *
     * @param areaCode the value being moved into the probe; must not be {@code null}, and may be of
     *                 any length
     * @return {@code true} when the three-character probe image is one of the 490 literals
     * @throws NullPointerException if {@code areaCode} is {@code null}
     */
    public boolean isValidPhoneAreaCode(String areaCode) {
        return VALID_PHONE_AREA_CODE.contains(phoneAreaCodeProbe(areaCode));
    }

    /**
     * Tests {@code 88 VALID-GENERAL-PURP-CODE} &mdash; is this one of the 410 general purpose area
     * codes of {@code app/cpy/CSLKPCDY.cpy:521-930}?
     *
     * <p>This is the condition {@code app/cbl/COACTUPC.cbl:2298} tests, inside the
     * {@code EDIT-AREA-CODE} paragraph that begins at line 2246, immediately after
     * {@code MOVE FUNCTION TRIM (WS-EDIT-US-PHONE-NUMA) TO WS-US-PHONE-AREA-CODE-TO-EDIT} at lines
     * 2296&ndash;2297. A {@code false} result there produces the message "Not valid North America
     * general purpose area code" (line 2306) and jumps to {@code EDIT-US-PHONE-PREFIX}.
     *
     * <p>Note that {@code COACTUPC} applies {@code FUNCTION TRIM} to its own working field
     * <em>before</em> the move. Trimming is the caller's business and is deliberately not repeated
     * here: this method reproduces the {@code MOVE} into the probe and the {@code 88}-level test,
     * which is precisely the copybook's half of the contract. A caller that has not trimmed gets the
     * same answer COBOL would give it untrimmed &mdash; {@code " 201"} becomes {@code " 20"} and
     * fails, in Java exactly as in COBOL.
     *
     * @param areaCode the value being moved into the probe; must not be {@code null}, and may be of
     *                 any length
     * @return {@code true} when the three-character probe image is one of the 410 literals
     * @throws NullPointerException if {@code areaCode} is {@code null}
     */
    public boolean isValidGeneralPurposeCode(String areaCode) {
        return VALID_GENERAL_PURP_CODE.contains(phoneAreaCodeProbe(areaCode));
    }

    /**
     * Tests {@code 88 VALID-EASY-RECOG-AREA-CODE} &mdash; is this one of the 80 easily recognisable
     * area codes of {@code app/cpy/CSLKPCDY.cpy:931-1010}?
     *
     * <p>These are the service-style codes, {@code '200'} through {@code '999'}, that the copybook
     * separates from the general purpose list with its in-list comment at line 440, "Easily
     * recognizable codes begin here." The two lists are disjoint, and together they are exactly
     * {@link #isValidPhoneAreaCode(String)}.
     *
     * <p>No program in this repository tests this condition name; it is reproduced because it is part
     * of the copybook's contract.
     *
     * @param areaCode the value being moved into the probe; must not be {@code null}, and may be of
     *                 any length
     * @return {@code true} when the three-character probe image is one of the 80 literals
     * @throws NullPointerException if {@code areaCode} is {@code null}
     */
    public boolean isValidEasyRecognitionAreaCode(String areaCode) {
        return VALID_EASY_RECOG_AREA_CODE.contains(phoneAreaCodeProbe(areaCode));
    }

    // =================================================================================================
    // 88 VALID-US-STATE-CODE.
    // Probe: 01 US-STATE-CODE-TO-EDIT PIC X(2)  (app/cpy/CSLKPCDY.cpy:1012)
    // =================================================================================================

    /**
     * Tests {@code 88 VALID-US-STATE-CODE} &mdash; is this one of the 56 United States state,
     * district and territory codes of {@code app/cpy/CSLKPCDY.cpy:1013-1069}?
     *
     * <p>This is the condition {@code app/cbl/COACTUPC.cbl:2495} tests, inside
     * {@code 1270-EDIT-US-STATE-CD} (line 2493), immediately after
     * {@code MOVE ACUP-NEW-CUST-ADDR-STATE-CD TO US-STATE-CODE-TO-EDIT} at line 2494. A
     * {@code false} result there produces "is not a valid state code" (line 2503). The sender is
     * {@code PIC X(02)} and the receiver {@code PIC X(2)}, so in the real program the move is a
     * same-width copy; the width rule is applied here regardless, so that a caller passing a shorter
     * or longer value is treated exactly as COBOL would treat it.
     *
     * <p>The table holds the fifty states, {@code 'DC'}, and the five territory codes {@code 'AS'},
     * {@code 'GU'}, {@code 'MP'}, {@code 'PR'} and {@code 'VI'} &mdash; and, notably, <em>not</em>
     * the armed-forces codes {@code 'AA'}, {@code 'AE'} and {@code 'AP'}, which the ZIP table does
     * recognise. See {@link #isValidStateAndZipCombination(String, String)} for why that
     * disagreement is preserved rather than reconciled.
     *
     * @param stateCode the value being moved into the probe; must not be {@code null}, and may be of
     *                  any length
     * @return {@code true} when the two-character probe image is one of the 56 literals
     * @throws NullPointerException if {@code stateCode} is {@code null}
     */
    public boolean isValidUsStateCode(String stateCode) {
        return VALID_US_STATE_CODE.contains(usStateCodeProbe(stateCode));
    }

    // =================================================================================================
    // 88 VALID-US-STATE-ZIP-CD2-COMBO, and the 01 US-STATE-ZIPCODE-TO-EDIT group it hangs off.
    // Probe: 02 US-STATE-AND-FIRST-ZIP2 PIC X(4)  (app/cpy/CSLKPCDY.cpy:1072)
    // =================================================================================================

    /**
     * Tests {@code 88 VALID-US-STATE-ZIP-CD2-COMBO} against an <em>already composed</em>
     * four-character probe &mdash; is this one of the 240 state-and-ZIP-prefix combinations of
     * {@code app/cpy/CSLKPCDY.cpy:1073-1313}?
     *
     * <p>This is the condition {@code app/cbl/COACTUPC.cbl:2542} tests. Prefer
     * {@link #isValidStateAndZipCombination(String, String)}, which composes the probe for you in the
     * order the {@code STRING} statement at lines 2537&ndash;2540 uses; reach for this overload only
     * when the four-character image is what you already hold.
     *
     * <p>The argument is moved into {@code US-STATE-AND-FIRST-ZIP2 PIC X(4)} first, so it is
     * space-padded on the right when shorter than four characters and truncated on the right when
     * longer.
     *
     * @param stateAndFirstZip2 the four-character image, two of state code followed by two of ZIP;
     *                          must not be {@code null}, and may be of any length
     * @return {@code true} when the four-character probe image is one of the 240 literals
     * @throws NullPointerException if {@code stateAndFirstZip2} is {@code null}
     */
    public boolean isValidStateZip2Combo(String stateAndFirstZip2) {
        return VALID_US_STATE_ZIP_CD2_COMBO.contains(
                stateAndFirstZip2Probe(stateAndFirstZip2));
    }

    /**
     * The whole of {@code 1280-EDIT-US-STATE-ZIP-CD} &mdash; composes the probe from a state code and
     * a ZIP code and then tests {@code 88 VALID-US-STATE-ZIP-CD2-COMBO}.
     *
     * <p>This is the form to call. It reproduces {@code app/cbl/COACTUPC.cbl:2537-2542} in one step,
     * which is the point: composing the probe by hand is the one place a caller can silently get the
     * order wrong, and {@code "34AA"} looks no less plausible than {@code "AA34"} until a customer's
     * address is rejected.
     *
     * <p><strong>The two state lists in this copybook disagree, and the disagreement is
     * preserved.</strong> The 240 ZIP combinations are built from <em>62</em> distinct two-character
     * prefixes, six of which &mdash; {@code 'AA'}, {@code 'AE'}, {@code 'AP'}, {@code 'FM'},
     * {@code 'MH'} and {@code 'PW'} &mdash; are absent from the 56-entry
     * {@code 88 VALID-US-STATE-CODE} table. So {@code isValidStateAndZipCombination("AA", "34000")}
     * answers {@code true} while {@code isValidUsStateCode("AA")} answers {@code false}. That is
     * what the copybook says, and it has an observable consequence in the only program that uses it:
     * {@code COACTUPC} runs {@code 1270-EDIT-US-STATE-CD} and {@code 1280-EDIT-US-STATE-ZIP-CD} as
     * separate edits, so an armed-forces address clears the ZIP edit and is still rejected by the
     * state edit. Reconciling the two lists here would be a change to the business rules, not a
     * translation of them.
     *
     * @param stateCode the {@code ACUP-NEW-CUST-ADDR-STATE-CD PIC X(02)} value; must not be
     *                  {@code null}
     * @param zipCode   the {@code ACUP-NEW-CUST-ADDR-ZIP PIC X(10)} value, of which only the first
     *                  two characters are used; must not be {@code null}
     * @return {@code true} when the composed four-character probe is one of the 240 literals
     * @throws NullPointerException if either argument is {@code null}
     * @see #composeStateAndFirstZip2(String, String)
     */
    public boolean isValidStateAndZipCombination(String stateCode, String zipCode) {
        return VALID_US_STATE_ZIP_CD2_COMBO.contains(
                composeStateAndFirstZip2(stateCode, zipCode));
    }

    /**
     * Composes {@code 02 US-STATE-AND-FIRST-ZIP2 PIC X(4)} exactly as the {@code STRING} statement
     * at {@code app/cbl/COACTUPC.cbl:2537-2540} does.
     *
     * <p>The COBOL is:
     * <pre>
     *   STRING ACUP-NEW-CUST-ADDR-STATE-CD
     *          ACUP-NEW-CUST-ADDR-ZIP(1:2)
     *     DELIMITED BY SIZE
     *     INTO US-STATE-AND-FIRST-ZIP2
     * </pre>
     * State code first, then the ZIP prefix &mdash; and {@code DELIMITED BY SIZE} means each operand
     * contributes its full declared width, padding included, with nothing trimmed. Two characters
     * plus two characters exactly fill the four-character receiver, so the transfer overwrites the
     * whole field and COBOL's rule that {@code STRING} leaves any unreached part of a receiver
     * untouched never comes into play here.
     *
     * <p>The reference modification {@code (1:2)} is modelled by bringing the ZIP to its declared
     * {@code PIC X(10)} width and then applying the alphanumeric {@code MOVE} rule at width 2. That
     * is not a shortcut: an alphanumeric move truncates on the <em>right</em>, so the surviving
     * characters are exactly positions 1 and 2, which is what {@code (1:2)} selects. Routing it
     * through the codec rather than calling {@code substring} keeps the width rule in one place and
     * makes a short ZIP impossible to trip over &mdash; {@code "1"} becomes {@code "1         "} and
     * then {@code "1 "}, rather than throwing.
     *
     * @param stateCode the {@code ACUP-NEW-CUST-ADDR-STATE-CD PIC X(02)} value; must not be
     *                  {@code null}
     * @param zipCode   the {@code ACUP-NEW-CUST-ADDR-ZIP PIC X(10)} value; must not be {@code null}
     * @return the four-character probe image, always exactly {@value #STATE_AND_FIRST_ZIP2_LENGTH}
     *         characters
     * @throws NullPointerException if either argument is {@code null}
     */
    public String composeStateAndFirstZip2(String stateCode, String zipCode) {
        Objects.requireNonNull(stateCode, "A state code is required to compose "
                + ITEM_STATE_AND_FIRST_ZIP2 + "; " + ITEM_CUSTOMER_STATE_CODE
                + " is PIC X(02) and COBOL has no null, so pass SPACES explicitly to compose a "
                + "blank probe");
        Objects.requireNonNull(zipCode, "A ZIP code is required to compose "
                + ITEM_STATE_AND_FIRST_ZIP2 + "; " + ITEM_CUSTOMER_ZIP
                + " is PIC X(10) and COBOL has no null, so pass SPACES explicitly to compose a "
                + "blank probe");

        // MOVE the operands to their declared widths first: DELIMITED BY SIZE contributes each
        // operand's full declared width, so an operand at the wrong width would shift every
        // character after it.
        String state = codec.movePicX(stateCode, US_STATE_CODE_LENGTH);
        String zipAtDeclaredWidth = codec.movePicX(zipCode, CUSTOMER_ZIP_LENGTH);
        // ACUP-NEW-CUST-ADDR-ZIP(1:2): an alphanumeric MOVE truncates on the right, so moving the
        // ten-character image into a two-character receiver yields exactly positions 1 and 2.
        String zipPrefix = codec.movePicX(zipAtDeclaredWidth, ZIP_PREFIX_LENGTH);

        String composed = codec.concatenateDelimitedBySize(state, zipPrefix);
        // The receiver is PIC X(4) and the concatenation is exactly 4, so this is an identity move;
        // it is written out so the receiver's declared width is stated at the point of assignment
        // rather than inferred from the operands.
        return codec.movePicX(composed, STATE_AND_FIRST_ZIP2_LENGTH);
    }

    /**
     * The image of the whole group {@code 01 US-STATE-ZIPCODE-TO-EDIT} after
     * {@code 1280-EDIT-US-STATE-ZIP-CD} has run &mdash; seven characters, the composed probe
     * followed by a blank {@code LAST-3-OF-ZIP}.
     *
     * <p>The group is 4 + 3 = 7 characters wide. {@code app/cbl/COACTUPC.cbl} writes only the first
     * subordinate: the {@code STRING} at lines 2537&ndash;2540 targets
     * {@code US-STATE-AND-FIRST-ZIP2}, and {@code LAST-3-OF-ZIP} is referenced by no statement
     * anywhere in the program. Its three characters therefore stand at whatever the group held
     * before, which for this copybook &mdash; a {@code WORKING-STORAGE} group with no {@code VALUE}
     * clause, never written &mdash; is blank. That is what this method returns, and it is why the
     * span is reachable at all rather than being a comment.
     *
     * @param stateCode the {@code ACUP-NEW-CUST-ADDR-STATE-CD PIC X(02)} value; must not be
     *                  {@code null}
     * @param zipCode   the {@code ACUP-NEW-CUST-ADDR-ZIP PIC X(10)} value; must not be {@code null}
     * @return the group image, always exactly {@value #STATE_ZIPCODE_GROUP_LENGTH} characters
     * @throws NullPointerException if either argument is {@code null}
     * @see #lastThreeOfZip(String)
     */
    public String stateZipcodeGroupImage(String stateCode, String zipCode) {
        // Padding the four-character subordinate out to the group's width is exactly what leaves
        // LAST-3-OF-ZIP blank: an alphanumeric MOVE pads on the right with spaces.
        return codec.movePicX(composeStateAndFirstZip2(stateCode, zipCode),
                STATE_ZIPCODE_GROUP_LENGTH);
    }

    /**
     * Reads {@code 02 US-STATE-AND-FIRST-ZIP2 PIC X(4)} out of a
     * {@code 01 US-STATE-ZIPCODE-TO-EDIT} group image &mdash; COBOL positions 1 through 4.
     *
     * <p>The symmetric counterpart of {@link #lastThreeOfZip(String)}, and together with it the
     * complete group: the two subordinates tile
     * {@value #STATE_ZIPCODE_GROUP_LENGTH} characters with no gap and no overlap. Feed the result
     * straight to {@link #isValidStateZip2Combo(String)} when what you hold is a group image rather
     * than a state code and a ZIP.
     *
     * <p>The argument is brought to the group's declared width first, so the span read that follows
     * cannot run off the end of a short image. The offset and length are the declared
     * {@link #STATE_AND_FIRST_ZIP2_OFFSET} and {@link #STATE_AND_FIRST_ZIP2_LENGTH}, which is a
     * subordinate-field reference at a fixed position rather than a width adjustment.
     *
     * @param stateZipcodeGroupImage an image of {@code 01 US-STATE-ZIPCODE-TO-EDIT}; must not be
     *                               {@code null}, and may be of any length
     * @return the four characters of {@code US-STATE-AND-FIRST-ZIP2}, always exactly
     *         {@value #STATE_AND_FIRST_ZIP2_LENGTH} characters
     * @throws NullPointerException if {@code stateZipcodeGroupImage} is {@code null}
     */
    public String stateAndFirstZip2(String stateZipcodeGroupImage) {
        String group = requireGroupImage(stateZipcodeGroupImage);
        return group.substring(STATE_AND_FIRST_ZIP2_OFFSET,
                STATE_AND_FIRST_ZIP2_OFFSET + STATE_AND_FIRST_ZIP2_LENGTH);
    }

    /**
     * Reads {@code 02 LAST-3-OF-ZIP PIC X(3)} out of a {@code 01 US-STATE-ZIPCODE-TO-EDIT} group
     * image &mdash; COBOL positions 5 through 7.
     *
     * <p>{@code LAST-3-OF-ZIP} is declared at {@code app/cpy/CSLKPCDY.cpy:1314} and referenced by no
     * statement in {@code app/cbl/COACTUPC.cbl}, the copybook's only consumer. It is modelled anyway,
     * with a real accessor rather than a comment, because a group item's layout is part of the
     * contract this class reproduces and because a span that cannot be read cannot be shown to be
     * where the copybook says it is.
     *
     * <p>The argument is brought to the group's declared width first, so the span read that follows
     * cannot run off the end of a short image. The offset and length are the declared
     * {@link #LAST_3_OF_ZIP_OFFSET} and {@link #LAST_3_OF_ZIP_LENGTH}, which is a subordinate-field
     * reference at a fixed position rather than a width adjustment.
     *
     * @param stateZipcodeGroupImage an image of {@code 01 US-STATE-ZIPCODE-TO-EDIT}; must not be
     *                               {@code null}, and may be of any length
     * @return the three characters of {@code LAST-3-OF-ZIP}, always exactly
     *         {@value #LAST_3_OF_ZIP_LENGTH} characters
     * @throws NullPointerException if {@code stateZipcodeGroupImage} is {@code null}
     */
    public String lastThreeOfZip(String stateZipcodeGroupImage) {
        String group = requireGroupImage(stateZipcodeGroupImage);
        return group.substring(LAST_3_OF_ZIP_OFFSET, LAST_3_OF_ZIP_OFFSET + LAST_3_OF_ZIP_LENGTH);
    }

    /**
     * Brings a {@code 01 US-STATE-ZIPCODE-TO-EDIT} image to the group's declared width, so that a
     * subordinate span can be read from it at a fixed offset without any possibility of running off
     * the end.
     *
     * @param stateZipcodeGroupImage the image to normalise
     * @return an image of exactly {@value #STATE_ZIPCODE_GROUP_LENGTH} characters
     * @throws NullPointerException if {@code stateZipcodeGroupImage} is {@code null}
     */
    private String requireGroupImage(String stateZipcodeGroupImage) {
        Objects.requireNonNull(stateZipcodeGroupImage, "A group image is required to read a "
                + "subordinate of " + ITEM_STATE_ZIPCODE_GROUP + "; the group is "
                + STATE_ZIPCODE_GROUP_LENGTH + " characters and COBOL has no null, so pass SPACES "
                + "explicitly for an unset group");
        return codec.movePicX(stateZipcodeGroupImage, STATE_ZIPCODE_GROUP_LENGTH);
    }

    // =================================================================================================
    // The tables, exposed for auditing. Every one is unmodifiable and iterates in copybook order.
    // =================================================================================================

    /**
     * {@code 88 VALID-PHONE-AREA-CODE}, in the copybook's declaration order.
     *
     * @return an unmodifiable set of {@value #VALID_PHONE_AREA_CODE_COUNT} three-character codes
     */
    public Set<String> validPhoneAreaCodes() {
        return VALID_PHONE_AREA_CODE;
    }

    /**
     * {@code 88 VALID-GENERAL-PURP-CODE}, in the copybook's declaration order.
     *
     * @return an unmodifiable set of {@value #VALID_GENERAL_PURP_CODE_COUNT} three-character codes
     */
    public Set<String> validGeneralPurposeCodes() {
        return VALID_GENERAL_PURP_CODE;
    }

    /**
     * {@code 88 VALID-EASY-RECOG-AREA-CODE}, in the copybook's declaration order.
     *
     * @return an unmodifiable set of {@value #VALID_EASY_RECOG_AREA_CODE_COUNT} three-character codes
     */
    public Set<String> validEasyRecognitionAreaCodes() {
        return VALID_EASY_RECOG_AREA_CODE;
    }

    /**
     * {@code 88 VALID-US-STATE-CODE}, in the copybook's declaration order.
     *
     * @return an unmodifiable set of {@value #VALID_US_STATE_CODE_COUNT} two-character codes
     */
    public Set<String> validUsStateCodes() {
        return VALID_US_STATE_CODE;
    }

    /**
     * {@code 88 VALID-US-STATE-ZIP-CD2-COMBO}, in the copybook's declaration order.
     *
     * @return an unmodifiable set of {@value #VALID_US_STATE_ZIP_CD2_COMBO_COUNT} four-character
     *         combinations
     */
    public Set<String> validStateZip2Combos() {
        return VALID_US_STATE_ZIP_CD2_COMBO;
    }

    // =================================================================================================
    // Probe construction. One method per 01-level item, each naming the item it fills.
    // =================================================================================================

    /**
     * Performs {@code MOVE ... TO WS-US-PHONE-AREA-CODE-TO-EDIT}: the argument as an image of exactly
     * {@value #PHONE_AREA_CODE_LENGTH} characters.
     */
    private String phoneAreaCodeProbe(String areaCode) {
        return moveIntoProbe(areaCode, PHONE_AREA_CODE_LENGTH, ITEM_PHONE_AREA_CODE_PROBE);
    }

    /**
     * Performs {@code MOVE ... TO US-STATE-CODE-TO-EDIT}: the argument as an image of exactly
     * {@value #US_STATE_CODE_LENGTH} characters.
     */
    private String usStateCodeProbe(String stateCode) {
        return moveIntoProbe(stateCode, US_STATE_CODE_LENGTH, ITEM_US_STATE_CODE_PROBE);
    }

    /**
     * Performs a move into {@code US-STATE-AND-FIRST-ZIP2}: the argument as an image of exactly
     * {@value #STATE_AND_FIRST_ZIP2_LENGTH} characters.
     */
    private String stateAndFirstZip2Probe(String stateAndFirstZip2) {
        return moveIntoProbe(stateAndFirstZip2, STATE_AND_FIRST_ZIP2_LENGTH,
                ITEM_STATE_AND_FIRST_ZIP2);
    }

    /**
     * The one place an argument becomes a probe image. Applies the COBOL alphanumeric {@code MOVE}
     * rule at the receiving item's declared width: right-padded with spaces when short, truncated on
     * the right when long, unchanged when already exact.
     *
     * @param value     the sending value
     * @param width     the receiving item's declared {@code PICTURE} width
     * @param cobolItem the receiving item's COBOL name, so a null argument is reported against the
     *                  copybook item it was destined for
     * @return an image of exactly {@code width} characters
     * @throws NullPointerException if {@code value} is {@code null}
     */
    private String moveIntoProbe(String value, int width, String cobolItem) {
        Objects.requireNonNull(value, "A value is required to probe " + cobolItem + ": the item is "
                + "PIC X(" + width + ") and COBOL has no null, so pass SPACES or an empty string "
                + "explicitly to probe a blank value - which matches no literal in "
                + "app/cpy/CSLKPCDY.cpy");
        return codec.movePicX(value, width);
    }

    // =================================================================================================
    // Table construction.
    // =================================================================================================

    /**
     * Builds one lookup table: an unmodifiable, insertion-ordered set of the copybook's literals,
     * checked against the cardinality the copybook declares.
     *
     * <p>Insertion order is preserved so that iterating a table walks {@code CSLKPCDY.cpy} from top
     * to bottom, which is what makes the tables below reviewable against the copybook. The result is
     * wrapped unmodifiable rather than merely kept private: these are {@code static} fields, and a
     * mutable collection in a static field is mutable static state however it is declared.
     *
     * @param cobolItem     the {@code 88}-level condition name, for diagnostics
     * @param declaredCount the number of literals the copybook declares for it
     * @param literals      the literals, in copybook declaration order
     * @return an unmodifiable set of exactly {@code declaredCount} literals, in the given order
     * @throws NullPointerException  if {@code literals} is {@code null}
     * @throws IllegalStateException if the set does not hold exactly {@code declaredCount} literals
     */
    private static Set<String> orderedSet(String cobolItem, int declaredCount, String... literals) {
        Objects.requireNonNull(literals, "The literals of " + cobolItem + " are required");
        Set<String> ordered = new LinkedHashSet<>(Arrays.asList(literals));
        return requireExactSize(Collections.unmodifiableSet(ordered), declaredCount, cobolItem);
    }

    /**
     * Fails unless a table holds exactly the number of literals its {@code 88}-level declares.
     *
     * <p>This is the transcription guard. The tables below are long, mechanical and edited by hand
     * only at a reviewer's peril; a literal deleted or duplicated by accident would otherwise turn
     * into a validation rule that quietly rejects a real area code or accepts an unassigned one.
     * Because the check runs from the static initialisers, that mistake fails on the first use of
     * this class rather than in production months later. Duplicates are caught too: the set is built
     * before the count is taken, so two copies of one literal collapse to one member and the count
     * comes up short.
     *
     * <p>Package-private rather than private so the test suite can drive the failing branch, which a
     * correctly transcribed table can never reach.
     *
     * @param values        the table to check
     * @param declaredCount the count the copybook declares
     * @param cobolItem     the {@code 88}-level condition name, for the message
     * @return {@code values}, unchanged, when the count matches
     * @throws NullPointerException  if {@code values} is {@code null}
     * @throws IllegalStateException if the count does not match
     */
    static Set<String> requireExactSize(Set<String> values, int declaredCount, String cobolItem) {
        Objects.requireNonNull(values, "A table is required to check the cardinality of " + cobolItem);
        if (values.size() != declaredCount) {
            throw new IllegalStateException("88 " + cobolItem + " holds " + values.size()
                    + " distinct literals but app/cpy/CSLKPCDY.cpy declares " + declaredCount
                    + "; a literal has been lost, duplicated or added, and this lookup would no "
                    + "longer agree with the COBOL. Re-derive the list from the copybook rather "
                    + "than adjusting the expected count");
        }
        return values;
    }

    // =================================================================================================
    // THE TABLES, transcribed from app/cpy/CSLKPCDY.cpy.
    //
    // Each literal sits on its own line, in the copybook's declaration order, and the trailing
    // comment is the 1-based line of app/cpy/CSLKPCDY.cpy it was taken from - so this file can be
    // diffed against the copybook line by line. The lists were extracted mechanically and are
    // re-verified against the copybook by AreaCodeLookupTest; none of them was retyped by hand.
    //
    // DO NOT compress these into ranges, a regular expression or a computed rule. A COBOL 88-level
    // VALUES clause is an exact membership set: '201' is a member and '211' is not, '999' is and
    // '998' is not. Any approximation admits or rejects a code the COBOL does not, and that is a
    // parity diff. DO NOT hand-edit an entry either - re-derive the list from the copybook.
    // =================================================================================================

    /**
     * The 490 literals of {@code 88 VALID-PHONE-AREA-CODE}, the complete North American Numbering
     * Plan area-code list, in the copybook's declaration order from {@code '201'} to
     * {@code '999'}. Exactly the union of the two tables that follow.
     *
     * <p>Source: {@code app/cpy/CSLKPCDY.cpy:30-520}.
     *
     * @return the table, unmodifiable and iterating in copybook declaration order
     */
    private static Set<String> buildValidPhoneAreaCode() {
        return orderedSet("VALID-PHONE-AREA-CODE", VALID_PHONE_AREA_CODE_COUNT,
                "201", // L30
                "202", // L31
                "203", // L32
                "204", // L33
                "205", // L34
                "206", // L35
                "207", // L36
                "208", // L37
                "209", // L38
                "210", // L39
                "212", // L40
                "213", // L41
                "214", // L42
                "215", // L43
                "216", // L44
                "217", // L45
                "218", // L46
                "219", // L47
                "220", // L48
                "223", // L49
                "224", // L50
                "225", // L51
                "226", // L52
                "228", // L53
                "229", // L54
                "231", // L55
                "234", // L56
                "236", // L57
                "239", // L58
                "240", // L59
                "242", // L60
                "246", // L61
                "248", // L62
                "249", // L63
                "250", // L64
                "251", // L65
                "252", // L66
                "253", // L67
                "254", // L68
                "256", // L69
                "260", // L70
                "262", // L71
                "264", // L72
                "267", // L73
                "268", // L74
                "269", // L75
                "270", // L76
                "272", // L77
                "276", // L78
                "279", // L79
                "281", // L80
                "284", // L81
                "289", // L82
                "301", // L83
                "302", // L84
                "303", // L85
                "304", // L86
                "305", // L87
                "306", // L88
                "307", // L89
                "308", // L90
                "309", // L91
                "310", // L92
                "312", // L93
                "313", // L94
                "314", // L95
                "315", // L96
                "316", // L97
                "317", // L98
                "318", // L99
                "319", // L100
                "320", // L101
                "321", // L102
                "323", // L103
                "325", // L104
                "326", // L105
                "330", // L106
                "331", // L107
                "332", // L108
                "334", // L109
                "336", // L110
                "337", // L111
                "339", // L112
                "340", // L113
                "341", // L114
                "343", // L115
                "345", // L116
                "346", // L117
                "347", // L118
                "351", // L119
                "352", // L120
                "360", // L121
                "361", // L122
                "364", // L123
                "365", // L124
                "367", // L125
                "368", // L126
                "380", // L127
                "385", // L128
                "386", // L129
                "401", // L130
                "402", // L131
                "403", // L132
                "404", // L133
                "405", // L134
                "406", // L135
                "407", // L136
                "408", // L137
                "409", // L138
                "410", // L139
                "412", // L140
                "413", // L141
                "414", // L142
                "415", // L143
                "416", // L144
                "417", // L145
                "418", // L146
                "419", // L147
                "423", // L148
                "424", // L149
                "425", // L150
                "430", // L151
                "431", // L152
                "432", // L153
                "434", // L154
                "435", // L155
                "437", // L156
                "438", // L157
                "440", // L158
                "441", // L159
                "442", // L160
                "443", // L161
                "445", // L162
                "447", // L163
                "448", // L164
                "450", // L165
                "458", // L166
                "463", // L167
                "464", // L168
                "469", // L169
                "470", // L170
                "473", // L171
                "474", // L172
                "475", // L173
                "478", // L174
                "479", // L175
                "480", // L176
                "484", // L177
                "501", // L178
                "502", // L179
                "503", // L180
                "504", // L181
                "505", // L182
                "506", // L183
                "507", // L184
                "508", // L185
                "509", // L186
                "510", // L187
                "512", // L188
                "513", // L189
                "514", // L190
                "515", // L191
                "516", // L192
                "517", // L193
                "518", // L194
                "519", // L195
                "520", // L196
                "530", // L197
                "531", // L198
                "534", // L199
                "539", // L200
                "540", // L201
                "541", // L202
                "548", // L203
                "551", // L204
                "559", // L205
                "561", // L206
                "562", // L207
                "563", // L208
                "564", // L209
                "567", // L210
                "570", // L211
                "571", // L212
                "572", // L213
                "573", // L214
                "574", // L215
                "575", // L216
                "579", // L217
                "580", // L218
                "581", // L219
                "582", // L220
                "585", // L221
                "586", // L222
                "587", // L223
                "601", // L224
                "602", // L225
                "603", // L226
                "604", // L227
                "605", // L228
                "606", // L229
                "607", // L230
                "608", // L231
                "609", // L232
                "610", // L233
                "612", // L234
                "613", // L235
                "614", // L236
                "615", // L237
                "616", // L238
                "617", // L239
                "618", // L240
                "619", // L241
                "620", // L242
                "623", // L243
                "626", // L244
                "628", // L245
                "629", // L246
                "630", // L247
                "631", // L248
                "636", // L249
                "639", // L250
                "640", // L251
                "641", // L252
                "646", // L253
                "647", // L254
                "649", // L255
                "650", // L256
                "651", // L257
                "656", // L258
                "657", // L259
                "658", // L260
                "659", // L261
                "660", // L262
                "661", // L263
                "662", // L264
                "664", // L265
                "667", // L266
                "669", // L267
                "670", // L268
                "671", // L269
                "672", // L270
                "678", // L271
                "680", // L272
                "681", // L273
                "682", // L274
                "683", // L275
                "684", // L276
                "689", // L277
                "701", // L278
                "702", // L279
                "703", // L280
                "704", // L281
                "705", // L282
                "706", // L283
                "707", // L284
                "708", // L285
                "709", // L286
                "712", // L287
                "713", // L288
                "714", // L289
                "715", // L290
                "716", // L291
                "717", // L292
                "718", // L293
                "719", // L294
                "720", // L295
                "721", // L296
                "724", // L297
                "725", // L298
                "726", // L299
                "727", // L300
                "731", // L301
                "732", // L302
                "734", // L303
                "737", // L304
                "740", // L305
                "742", // L306
                "743", // L307
                "747", // L308
                "753", // L309
                "754", // L310
                "757", // L311
                "758", // L312
                "760", // L313
                "762", // L314
                "763", // L315
                "765", // L316
                "767", // L317
                "769", // L318
                "770", // L319
                "771", // L320
                "772", // L321
                "773", // L322
                "774", // L323
                "775", // L324
                "778", // L325
                "779", // L326
                "780", // L327
                "781", // L328
                "782", // L329
                "784", // L330
                "785", // L331
                "786", // L332
                "787", // L333
                "801", // L334
                "802", // L335
                "803", // L336
                "804", // L337
                "805", // L338
                "806", // L339
                "807", // L340
                "808", // L341
                "809", // L342
                "810", // L343
                "812", // L344
                "813", // L345
                "814", // L346
                "815", // L347
                "816", // L348
                "817", // L349
                "818", // L350
                "819", // L351
                "820", // L352
                "825", // L353
                "826", // L354
                "828", // L355
                "829", // L356
                "830", // L357
                "831", // L358
                "832", // L359
                "838", // L360
                "839", // L361
                "840", // L362
                "843", // L363
                "845", // L364
                "847", // L365
                "848", // L366
                "849", // L367
                "850", // L368
                "854", // L369
                "856", // L370
                "857", // L371
                "858", // L372
                "859", // L373
                "860", // L374
                "862", // L375
                "863", // L376
                "864", // L377
                "865", // L378
                "867", // L379
                "868", // L380
                "869", // L381
                "870", // L382
                "872", // L383
                "873", // L384
                "876", // L385
                "878", // L386
                "901", // L387
                "902", // L388
                "903", // L389
                "904", // L390
                "905", // L391
                "906", // L392
                "907", // L393
                "908", // L394
                "909", // L395
                "910", // L396
                "912", // L397
                "913", // L398
                "914", // L399
                "915", // L400
                "916", // L401
                "917", // L402
                "918", // L403
                "919", // L404
                "920", // L405
                "925", // L406
                "928", // L407
                "929", // L408
                "930", // L409
                "931", // L410
                "934", // L411
                "936", // L412
                "937", // L413
                "938", // L414
                "939", // L415
                "940", // L416
                "941", // L417
                "943", // L418
                "945", // L419
                "947", // L420
                "948", // L421
                "949", // L422
                "951", // L423
                "952", // L424
                "954", // L425
                "956", // L426
                "959", // L427
                "970", // L428
                "971", // L429
                "972", // L430
                "973", // L431
                "978", // L432
                "979", // L433
                "980", // L434
                "983", // L435
                "984", // L436
                "985", // L437
                "986", // L438
                "989", // L439
                "200", // L441
                "211", // L442
                "222", // L443
                "233", // L444
                "244", // L445
                "255", // L446
                "266", // L447
                "277", // L448
                "288", // L449
                "299", // L450
                "300", // L451
                "311", // L452
                "322", // L453
                "333", // L454
                "344", // L455
                "355", // L456
                "366", // L457
                "377", // L458
                "388", // L459
                "399", // L460
                "400", // L461
                "411", // L462
                "422", // L463
                "433", // L464
                "444", // L465
                "455", // L466
                "466", // L467
                "477", // L468
                "488", // L469
                "499", // L470
                "500", // L471
                "511", // L472
                "522", // L473
                "533", // L474
                "544", // L475
                "555", // L476
                "566", // L477
                "577", // L478
                "588", // L479
                "599", // L480
                "600", // L481
                "611", // L482
                "622", // L483
                "633", // L484
                "644", // L485
                "655", // L486
                "666", // L487
                "677", // L488
                "688", // L489
                "699", // L490
                "700", // L491
                "711", // L492
                "722", // L493
                "733", // L494
                "744", // L495
                "755", // L496
                "766", // L497
                "777", // L498
                "788", // L499
                "799", // L500
                "800", // L501
                "811", // L502
                "822", // L503
                "833", // L504
                "844", // L505
                "855", // L506
                "866", // L507
                "877", // L508
                "888", // L509
                "899", // L510
                "900", // L511
                "911", // L512
                "922", // L513
                "933", // L514
                "944", // L515
                "955", // L516
                "966", // L517
                "977", // L518
                "988", // L519
                "999"); // L520
    }

    /**
     * The 410 literals of {@code 88 VALID-GENERAL-PURP-CODE}, in the copybook's declaration order
     * from {@code '201'} to {@code '989'}. This is the table {@code app/cbl/COACTUPC.cbl:2298}
     * tests.
     *
     * <p>Source: {@code app/cpy/CSLKPCDY.cpy:521-930}.
     *
     * @return the table, unmodifiable and iterating in copybook declaration order
     */
    private static Set<String> buildValidGeneralPurpCode() {
        return orderedSet("VALID-GENERAL-PURP-CODE", VALID_GENERAL_PURP_CODE_COUNT,
                "201", // L521
                "202", // L522
                "203", // L523
                "204", // L524
                "205", // L525
                "206", // L526
                "207", // L527
                "208", // L528
                "209", // L529
                "210", // L530
                "212", // L531
                "213", // L532
                "214", // L533
                "215", // L534
                "216", // L535
                "217", // L536
                "218", // L537
                "219", // L538
                "220", // L539
                "223", // L540
                "224", // L541
                "225", // L542
                "226", // L543
                "228", // L544
                "229", // L545
                "231", // L546
                "234", // L547
                "236", // L548
                "239", // L549
                "240", // L550
                "242", // L551
                "246", // L552
                "248", // L553
                "249", // L554
                "250", // L555
                "251", // L556
                "252", // L557
                "253", // L558
                "254", // L559
                "256", // L560
                "260", // L561
                "262", // L562
                "264", // L563
                "267", // L564
                "268", // L565
                "269", // L566
                "270", // L567
                "272", // L568
                "276", // L569
                "279", // L570
                "281", // L571
                "284", // L572
                "289", // L573
                "301", // L574
                "302", // L575
                "303", // L576
                "304", // L577
                "305", // L578
                "306", // L579
                "307", // L580
                "308", // L581
                "309", // L582
                "310", // L583
                "312", // L584
                "313", // L585
                "314", // L586
                "315", // L587
                "316", // L588
                "317", // L589
                "318", // L590
                "319", // L591
                "320", // L592
                "321", // L593
                "323", // L594
                "325", // L595
                "326", // L596
                "330", // L597
                "331", // L598
                "332", // L599
                "334", // L600
                "336", // L601
                "337", // L602
                "339", // L603
                "340", // L604
                "341", // L605
                "343", // L606
                "345", // L607
                "346", // L608
                "347", // L609
                "351", // L610
                "352", // L611
                "360", // L612
                "361", // L613
                "364", // L614
                "365", // L615
                "367", // L616
                "368", // L617
                "380", // L618
                "385", // L619
                "386", // L620
                "401", // L621
                "402", // L622
                "403", // L623
                "404", // L624
                "405", // L625
                "406", // L626
                "407", // L627
                "408", // L628
                "409", // L629
                "410", // L630
                "412", // L631
                "413", // L632
                "414", // L633
                "415", // L634
                "416", // L635
                "417", // L636
                "418", // L637
                "419", // L638
                "423", // L639
                "424", // L640
                "425", // L641
                "430", // L642
                "431", // L643
                "432", // L644
                "434", // L645
                "435", // L646
                "437", // L647
                "438", // L648
                "440", // L649
                "441", // L650
                "442", // L651
                "443", // L652
                "445", // L653
                "447", // L654
                "448", // L655
                "450", // L656
                "458", // L657
                "463", // L658
                "464", // L659
                "469", // L660
                "470", // L661
                "473", // L662
                "474", // L663
                "475", // L664
                "478", // L665
                "479", // L666
                "480", // L667
                "484", // L668
                "501", // L669
                "502", // L670
                "503", // L671
                "504", // L672
                "505", // L673
                "506", // L674
                "507", // L675
                "508", // L676
                "509", // L677
                "510", // L678
                "512", // L679
                "513", // L680
                "514", // L681
                "515", // L682
                "516", // L683
                "517", // L684
                "518", // L685
                "519", // L686
                "520", // L687
                "530", // L688
                "531", // L689
                "534", // L690
                "539", // L691
                "540", // L692
                "541", // L693
                "548", // L694
                "551", // L695
                "559", // L696
                "561", // L697
                "562", // L698
                "563", // L699
                "564", // L700
                "567", // L701
                "570", // L702
                "571", // L703
                "572", // L704
                "573", // L705
                "574", // L706
                "575", // L707
                "579", // L708
                "580", // L709
                "581", // L710
                "582", // L711
                "585", // L712
                "586", // L713
                "587", // L714
                "601", // L715
                "602", // L716
                "603", // L717
                "604", // L718
                "605", // L719
                "606", // L720
                "607", // L721
                "608", // L722
                "609", // L723
                "610", // L724
                "612", // L725
                "613", // L726
                "614", // L727
                "615", // L728
                "616", // L729
                "617", // L730
                "618", // L731
                "619", // L732
                "620", // L733
                "623", // L734
                "626", // L735
                "628", // L736
                "629", // L737
                "630", // L738
                "631", // L739
                "636", // L740
                "639", // L741
                "640", // L742
                "641", // L743
                "646", // L744
                "647", // L745
                "649", // L746
                "650", // L747
                "651", // L748
                "656", // L749
                "657", // L750
                "658", // L751
                "659", // L752
                "660", // L753
                "661", // L754
                "662", // L755
                "664", // L756
                "667", // L757
                "669", // L758
                "670", // L759
                "671", // L760
                "672", // L761
                "678", // L762
                "680", // L763
                "681", // L764
                "682", // L765
                "683", // L766
                "684", // L767
                "689", // L768
                "701", // L769
                "702", // L770
                "703", // L771
                "704", // L772
                "705", // L773
                "706", // L774
                "707", // L775
                "708", // L776
                "709", // L777
                "712", // L778
                "713", // L779
                "714", // L780
                "715", // L781
                "716", // L782
                "717", // L783
                "718", // L784
                "719", // L785
                "720", // L786
                "721", // L787
                "724", // L788
                "725", // L789
                "726", // L790
                "727", // L791
                "731", // L792
                "732", // L793
                "734", // L794
                "737", // L795
                "740", // L796
                "742", // L797
                "743", // L798
                "747", // L799
                "753", // L800
                "754", // L801
                "757", // L802
                "758", // L803
                "760", // L804
                "762", // L805
                "763", // L806
                "765", // L807
                "767", // L808
                "769", // L809
                "770", // L810
                "771", // L811
                "772", // L812
                "773", // L813
                "774", // L814
                "775", // L815
                "778", // L816
                "779", // L817
                "780", // L818
                "781", // L819
                "782", // L820
                "784", // L821
                "785", // L822
                "786", // L823
                "787", // L824
                "801", // L825
                "802", // L826
                "803", // L827
                "804", // L828
                "805", // L829
                "806", // L830
                "807", // L831
                "808", // L832
                "809", // L833
                "810", // L834
                "812", // L835
                "813", // L836
                "814", // L837
                "815", // L838
                "816", // L839
                "817", // L840
                "818", // L841
                "819", // L842
                "820", // L843
                "825", // L844
                "826", // L845
                "828", // L846
                "829", // L847
                "830", // L848
                "831", // L849
                "832", // L850
                "838", // L851
                "839", // L852
                "840", // L853
                "843", // L854
                "845", // L855
                "847", // L856
                "848", // L857
                "849", // L858
                "850", // L859
                "854", // L860
                "856", // L861
                "857", // L862
                "858", // L863
                "859", // L864
                "860", // L865
                "862", // L866
                "863", // L867
                "864", // L868
                "865", // L869
                "867", // L870
                "868", // L871
                "869", // L872
                "870", // L873
                "872", // L874
                "873", // L875
                "876", // L876
                "878", // L877
                "901", // L878
                "902", // L879
                "903", // L880
                "904", // L881
                "905", // L882
                "906", // L883
                "907", // L884
                "908", // L885
                "909", // L886
                "910", // L887
                "912", // L888
                "913", // L889
                "914", // L890
                "915", // L891
                "916", // L892
                "917", // L893
                "918", // L894
                "919", // L895
                "920", // L896
                "925", // L897
                "928", // L898
                "929", // L899
                "930", // L900
                "931", // L901
                "934", // L902
                "936", // L903
                "937", // L904
                "938", // L905
                "939", // L906
                "940", // L907
                "941", // L908
                "943", // L909
                "945", // L910
                "947", // L911
                "948", // L912
                "949", // L913
                "951", // L914
                "952", // L915
                "954", // L916
                "956", // L917
                "959", // L918
                "970", // L919
                "971", // L920
                "972", // L921
                "973", // L922
                "978", // L923
                "979", // L924
                "980", // L925
                "983", // L926
                "984", // L927
                "985", // L928
                "986", // L929
                "989"); // L930
    }

    /**
     * The 80 literals of {@code 88 VALID-EASY-RECOG-AREA-CODE}, in the copybook's declaration order
     * from {@code '200'} to {@code '999'}. Disjoint from the general purpose table above.
     *
     * <p>Source: {@code app/cpy/CSLKPCDY.cpy:931-1010}.
     *
     * @return the table, unmodifiable and iterating in copybook declaration order
     */
    private static Set<String> buildValidEasyRecogAreaCode() {
        return orderedSet("VALID-EASY-RECOG-AREA-CODE", VALID_EASY_RECOG_AREA_CODE_COUNT,
                "200", // L931
                "211", // L932
                "222", // L933
                "233", // L934
                "244", // L935
                "255", // L936
                "266", // L937
                "277", // L938
                "288", // L939
                "299", // L940
                "300", // L941
                "311", // L942
                "322", // L943
                "333", // L944
                "344", // L945
                "355", // L946
                "366", // L947
                "377", // L948
                "388", // L949
                "399", // L950
                "400", // L951
                "411", // L952
                "422", // L953
                "433", // L954
                "444", // L955
                "455", // L956
                "466", // L957
                "477", // L958
                "488", // L959
                "499", // L960
                "500", // L961
                "511", // L962
                "522", // L963
                "533", // L964
                "544", // L965
                "555", // L966
                "566", // L967
                "577", // L968
                "588", // L969
                "599", // L970
                "600", // L971
                "611", // L972
                "622", // L973
                "633", // L974
                "644", // L975
                "655", // L976
                "666", // L977
                "677", // L978
                "688", // L979
                "699", // L980
                "700", // L981
                "711", // L982
                "722", // L983
                "733", // L984
                "744", // L985
                "755", // L986
                "766", // L987
                "777", // L988
                "788", // L989
                "799", // L990
                "800", // L991
                "811", // L992
                "822", // L993
                "833", // L994
                "844", // L995
                "855", // L996
                "866", // L997
                "877", // L998
                "888", // L999
                "899", // L1000
                "900", // L1001
                "911", // L1002
                "922", // L1003
                "933", // L1004
                "944", // L1005
                "955", // L1006
                "966", // L1007
                "977", // L1008
                "988", // L1009
                "999"); // L1010
    }

    /**
     * The 56 literals of {@code 88 VALID-US-STATE-CODE}, in the copybook's declaration order from
     * {@code 'AL'} to {@code 'VI'}. The fifty states, the District of Columbia, the military
     * codes, and the four territories {@code 'GU'}, {@code 'MP'}, {@code 'PR'} and
     * {@code 'VI'} that close the list.
     *
     * <p>Source: {@code app/cpy/CSLKPCDY.cpy:1013-1069}.
     *
     * @return the table, unmodifiable and iterating in copybook declaration order
     */
    private static Set<String> buildValidUsStateCode() {
        return orderedSet("VALID-US-STATE-CODE", VALID_US_STATE_CODE_COUNT,
                "AL", // L1014
                "AK", // L1015
                "AZ", // L1016
                "AR", // L1017
                "CA", // L1018
                "CO", // L1019
                "CT", // L1020
                "DE", // L1021
                "FL", // L1022
                "GA", // L1023
                "HI", // L1024
                "ID", // L1025
                "IL", // L1026
                "IN", // L1027
                "IA", // L1028
                "KS", // L1029
                "KY", // L1030
                "LA", // L1031
                "ME", // L1032
                "MD", // L1033
                "MA", // L1034
                "MI", // L1035
                "MN", // L1036
                "MS", // L1037
                "MO", // L1038
                "MT", // L1039
                "NE", // L1040
                "NV", // L1041
                "NH", // L1042
                "NJ", // L1043
                "NM", // L1044
                "NY", // L1045
                "NC", // L1046
                "ND", // L1047
                "OH", // L1048
                "OK", // L1049
                "OR", // L1050
                "PA", // L1051
                "RI", // L1052
                "SC", // L1053
                "SD", // L1054
                "TN", // L1055
                "TX", // L1056
                "UT", // L1057
                "VT", // L1058
                "VA", // L1059
                "WA", // L1060
                "WV", // L1061
                "WI", // L1062
                "WY", // L1063
                "DC", // L1064
                "AS", // L1065
                "GU", // L1066
                "MP", // L1067
                "PR", // L1068
                "VI"); // L1069
    }

    /**
     * The 240 literals of {@code 88 VALID-US-STATE-ZIP-CD2-COMBO}, in the copybook's declaration
     * order from {@code 'AA34'} to {@code 'WY83'}. Each is a two-character state code followed
     * immediately by the first two digits of a ZIP code assigned to that state. The 240 entries
     * use 62 distinct prefixes - six more than the 56 codes of
     * {@code 88 VALID-US-STATE-CODE}, because {@code 'AA'}, {@code 'AE'}, {@code 'AP'},
     * {@code 'FM'}, {@code 'MH'} and {@code 'PW'} appear here and not there.
     *
     * <p>Source: {@code app/cpy/CSLKPCDY.cpy:1073-1313}.
     *
     * @return the table, unmodifiable and iterating in copybook declaration order
     */
    private static Set<String> buildValidUsStateZipCd2Combo() {
        return orderedSet("VALID-US-STATE-ZIP-CD2-COMBO", VALID_US_STATE_ZIP_CD2_COMBO_COUNT,
                "AA34", // L1074
                "AE90", // L1075
                "AE91", // L1076
                "AE92", // L1077
                "AE93", // L1078
                "AE94", // L1079
                "AE95", // L1080
                "AE96", // L1081
                "AE97", // L1082
                "AE98", // L1083
                "AK99", // L1084
                "AL35", // L1085
                "AL36", // L1086
                "AP96", // L1087
                "AR71", // L1088
                "AR72", // L1089
                "AS96", // L1090
                "AZ85", // L1091
                "AZ86", // L1092
                "CA90", // L1093
                "CA91", // L1094
                "CA92", // L1095
                "CA93", // L1096
                "CA94", // L1097
                "CA95", // L1098
                "CA96", // L1099
                "CO80", // L1100
                "CO81", // L1101
                "CT60", // L1102
                "CT61", // L1103
                "CT62", // L1104
                "CT63", // L1105
                "CT64", // L1106
                "CT65", // L1107
                "CT66", // L1108
                "CT67", // L1109
                "CT68", // L1110
                "CT69", // L1111
                "DC20", // L1112
                "DC56", // L1113
                "DC88", // L1114
                "DE19", // L1115
                "FL32", // L1116
                "FL33", // L1117
                "FL34", // L1118
                "FM96", // L1119
                "GA30", // L1120
                "GA31", // L1121
                "GA39", // L1122
                "GU96", // L1123
                "HI96", // L1124
                "IA50", // L1125
                "IA51", // L1126
                "IA52", // L1127
                "ID83", // L1128
                "IL60", // L1129
                "IL61", // L1130
                "IL62", // L1131
                "IN46", // L1132
                "IN47", // L1133
                "KS66", // L1134
                "KS67", // L1135
                "KY40", // L1136
                "KY41", // L1137
                "KY42", // L1138
                "LA70", // L1139
                "LA71", // L1140
                "MA10", // L1141
                "MA11", // L1142
                "MA12", // L1143
                "MA13", // L1144
                "MA14", // L1145
                "MA15", // L1146
                "MA16", // L1147
                "MA17", // L1148
                "MA18", // L1149
                "MA19", // L1150
                "MA20", // L1151
                "MA21", // L1152
                "MA22", // L1153
                "MA23", // L1154
                "MA24", // L1155
                "MA25", // L1156
                "MA26", // L1157
                "MA27", // L1158
                "MA55", // L1159
                "MD20", // L1160
                "MD21", // L1161
                "ME39", // L1162
                "ME40", // L1163
                "ME41", // L1164
                "ME42", // L1165
                "ME43", // L1166
                "ME44", // L1167
                "ME45", // L1168
                "ME46", // L1169
                "ME47", // L1170
                "ME48", // L1171
                "ME49", // L1172
                "MH96", // L1173
                "MI48", // L1174
                "MI49", // L1175
                "MN55", // L1176
                "MN56", // L1177
                "MO63", // L1178
                "MO64", // L1179
                "MO65", // L1180
                "MO72", // L1181
                "MP96", // L1182
                "MS38", // L1183
                "MS39", // L1184
                "MT59", // L1185
                "NC27", // L1186
                "NC28", // L1187
                "ND58", // L1188
                "NE68", // L1189
                "NE69", // L1190
                "NH30", // L1191
                "NH31", // L1192
                "NH32", // L1193
                "NH33", // L1194
                "NH34", // L1195
                "NH35", // L1196
                "NH36", // L1197
                "NH37", // L1198
                "NH38", // L1199
                "NJ70", // L1200
                "NJ71", // L1201
                "NJ72", // L1202
                "NJ73", // L1203
                "NJ74", // L1204
                "NJ75", // L1205
                "NJ76", // L1206
                "NJ77", // L1207
                "NJ78", // L1208
                "NJ79", // L1209
                "NJ80", // L1210
                "NJ81", // L1211
                "NJ82", // L1212
                "NJ83", // L1213
                "NJ84", // L1214
                "NJ85", // L1215
                "NJ86", // L1216
                "NJ87", // L1217
                "NJ88", // L1218
                "NJ89", // L1219
                "NM87", // L1220
                "NM88", // L1221
                "NV88", // L1222
                "NV89", // L1223
                "NY50", // L1224
                "NY54", // L1225
                "NY63", // L1226
                "NY10", // L1227
                "NY11", // L1228
                "NY12", // L1229
                "NY13", // L1230
                "NY14", // L1231
                "OH43", // L1232
                "OH44", // L1233
                "OH45", // L1234
                "OK73", // L1235
                "OK74", // L1236
                "OR97", // L1237
                "PA15", // L1238
                "PA16", // L1239
                "PA17", // L1240
                "PA18", // L1241
                "PA19", // L1242
                "PR60", // L1243
                "PR61", // L1244
                "PR62", // L1245
                "PR63", // L1246
                "PR64", // L1247
                "PR65", // L1248
                "PR66", // L1249
                "PR67", // L1250
                "PR68", // L1251
                "PR69", // L1252
                "PR70", // L1253
                "PR71", // L1254
                "PR72", // L1255
                "PR73", // L1256
                "PR74", // L1257
                "PR75", // L1258
                "PR76", // L1259
                "PR77", // L1260
                "PR78", // L1261
                "PR79", // L1262
                "PR90", // L1263
                "PR91", // L1264
                "PR92", // L1265
                "PR93", // L1266
                "PR94", // L1267
                "PR95", // L1268
                "PR96", // L1269
                "PR97", // L1270
                "PR98", // L1271
                "PW96", // L1272
                "RI28", // L1273
                "RI29", // L1274
                "SC29", // L1275
                "SD57", // L1276
                "TN37", // L1277
                "TN38", // L1278
                "TX73", // L1279
                "TX75", // L1280
                "TX76", // L1281
                "TX77", // L1282
                "TX78", // L1283
                "TX79", // L1284
                "TX88", // L1285
                "UT84", // L1286
                "VA20", // L1287
                "VA22", // L1288
                "VA23", // L1289
                "VA24", // L1290
                "VI80", // L1291
                "VI82", // L1292
                "VI83", // L1293
                "VI84", // L1294
                "VI85", // L1295
                "VT50", // L1296
                "VT51", // L1297
                "VT52", // L1298
                "VT53", // L1299
                "VT54", // L1300
                "VT56", // L1301
                "VT57", // L1302
                "VT58", // L1303
                "VT59", // L1304
                "WA98", // L1305
                "WA99", // L1306
                "WI53", // L1307
                "WI54", // L1308
                "WV24", // L1309
                "WV25", // L1310
                "WV26", // L1311
                "WY82", // L1312
                "WY83"); // L1313
    }
}
