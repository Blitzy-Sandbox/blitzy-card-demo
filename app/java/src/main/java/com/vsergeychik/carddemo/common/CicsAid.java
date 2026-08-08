package com.vsergeychik.carddemo.common;

import java.util.Map;

/**
 * Attention Identifier (AID) constants, reproducing the IBM-supplied CICS {@code DFHAID}
 * copybook byte-for-byte.
 *
 * <h2>Why this class exists at all: provenance and the missing copybook</h2>
 *
 * <p>Every one of the seventeen CICS online programs migrated by this module contains the
 * statement {@code COPY DFHAID.} in its {@code WORKING-STORAGE SECTION}, and each then tests
 * the CICS {@code EIBAID} field against the mnemonics that copybook defines in order to
 * discover which key the terminal operator pressed. {@code DFHAID} is supplied by CICS itself,
 * not by the application, and it is therefore <strong>absent from this repository</strong>:
 * {@code ls app/cpy/DFH*} fails with "No such file or directory", and none of the twenty-eight
 * copybooks present in {@code app/cpy} is {@code DFHAID} (nor {@code DFHBMSCA}, nor
 * {@code DFHATTR}). The seventeen {@code COPY DFHAID.} sites were counted directly and match
 * the consumer count recorded in the Agent Action Plan exactly.
 *
 * <p>The consequence is unusual and worth stating plainly for any future reader: unlike every
 * other class in this migration, <strong>there is no in-repository source that this file can be
 * diffed against</strong>. The constant values below could not be read out of the checkout. They
 * were instead <strong>sourced from IBM CICS documentation</strong> and then cross-checked, as
 * described under "How each value was verified" below. This is the environmental limitation
 * recorded as implicit requirement <strong>I6</strong> and tracked as open risk
 * <strong>R-D</strong> in the Agent Action Plan; writing its provenance into the file, rather
 * than absorbing it silently, is what practice <strong>B12</strong> requires. Practice
 * <strong>B11</strong> is why the values are hand-written here instead of being pulled in from
 * an IBM CICS artifact: the module's dependency set is deliberately closed, and a reviewer must
 * be able to audit every byte in place.
 *
 * <h2>The values are EBCDIC single bytes, and that distinction is load-bearing</h2>
 *
 * <p>Each constant is a single {@code byte} holding an <strong>EBCDIC</strong> code point, which
 * is the encoding of a COBOL {@code PIC X(1)} field on the mainframe this application came from.
 * The trap this guards against is subtle and silent: writing {@code '1'} as a Java {@code char}
 * literal would yield Unicode {@code U+0031} (ASCII {@code 0x31}), whereas the AID byte the
 * terminal actually transmits for PF1 is {@code 0xF1}. The two compile identically and differ
 * completely. That is why no constant here is declared as {@code char}, and why
 * {@link #AID_CODE_PAGE} names the code page explicitly rather than leaving it implied
 * (practice <strong>B8</strong>).
 *
 * <p>Note the precise relationship, because it is easy to overstate: the numeric values below
 * <em>are</em> the 3270 data-stream AID codes as published by IBM, and those are absolute byte
 * values independent of any code page. Code page 037 is simply the encoding under which those
 * bytes render as the character literals the {@code DFHAID} copybook is written with. This class
 * performs <strong>no</strong> character conversion of any kind: it neither encodes a string to
 * bytes nor decodes bytes back to a string, and it never consults the platform default charset -
 * which is never a correct choice for mainframe data. Every value below is written as a literal
 * byte, so there is no conversion step in which an encoding could be got wrong.
 *
 * <h2>How each value was verified</h2>
 *
 * <p>Three independent sources were required to agree before a value was accepted, precisely
 * because a mistyped byte here would compile cleanly, survive a careless test, and then
 * mis-route a function key at run time with no diagnostic whatsoever:
 *
 * <ol>
 *   <li><strong>IBM's published 3270 AID code table</strong>, which appears identically in the
 *       <em>CICS Codes</em> manual across three separate CICS Transaction Server releases. It
 *       gives PF1-PF12, PF13-PF24, PA1-PA3, the light pen and the operator-identification
 *       reader as explicit hexadecimal bytes.</li>
 *   <li><strong>The {@code DFHAID} character literals</strong> as documented by IBM, together
 *       with IBM's list of the constant names the copybook declares, which confirms the member
 *       set reproduced here is complete and correctly named.</li>
 *   <li><strong>A local conversion of every character literal through the explicitly named
 *       {@code IBM037} charset</strong>, which reproduced all thirty-six byte values from
 *       source (1) with zero mismatches.</li>
 * </ol>
 *
 * <h2>Discrepancies recorded rather than resolved (practice B4)</h2>
 *
 * <p>Practice <strong>B4</strong> requires that conflicts between the migration plan and the
 * evidence be documented where a reader will find them, never quietly corrected away. Five
 * apply to this file:
 *
 * <ol>
 *   <li><strong>PF22 through PF24 are not letters.</strong> The plan's cross-check hint stated
 *       that {@code DFHPF13} through {@code DFHPF24} correspond to "the twelve EBCDIC letters
 *       'A' through 'L'". IBM's AID table shows the letters <em>stop at</em> {@code 'I'}:
 *       {@code DFHPF21} is {@code 0xC9}, and {@code DFHPF22}, {@code DFHPF23} and
 *       {@code DFHPF24} are {@code 0x4A}, {@code 0x4B} and {@code 0x4C} - the cent sign, the
 *       period and the less-than sign. There are nine letters in the run, not twelve. IBM's
 *       documentation is authoritative and is what this file follows. Had the hint been taken
 *       literally, three constants would have been silently wrong.</li>
 *   <li><strong>{@code DFHENTER} is {@code 0x7D}, not {@code 0x27}.</strong> {@code 0x27} is the
 *       <em>ASCII</em> apostrophe; the EBCDIC apostrophe, and the documented ENTER AID, is
 *       {@code 0x7D}. This is the {@code char}-literal trap described above, caught in review.</li>
 *   <li><strong>A secondary source claiming {@code DFHPF12 = 0xFC} was rejected.</strong> IBM's
 *       AID table gives PF12 as {@code 0x7C}, and the copybook literal for PF12 is the
 *       commercial-at sign, which is {@code 0x7C} in code page 037. The conflict was
 *       adjudicated in IBM's favour; it is noted here so a reviewer can see it was decided
 *       rather than overlooked.</li>
 *   <li><strong>{@code DFHPA3} has no consumer in this repository.</strong> A search of the
 *       entire checkout finds zero references to it, and {@code app/cpy/CSSTRPFY.cpy} has no
 *       PA3 branch. The Agent Action Plan mandates the {@code DFHPA1}-{@code DFHPA3} range
 *       regardless, and practice <strong>B5</strong> forbids pruning code merely because it is
 *       unreferenced, so the constant is defined and its zero-consumer status is documented on
 *       the field itself.</li>
 *   <li><strong>{@code CVCRD01Y} declares sixteen AID conditions, not fifteen.</strong> The plan
 *       describes {@code CCARD-AID PIC X(5)} as carrying fifteen condition names; reading
 *       {@code app/cpy/CVCRD01Y.cpy} shows sixteen - {@code ENTER}, {@code CLEAR},
 *       {@code PA1}, {@code PA2} and {@code PFK01} through {@code PFK12}. Recorded as an
 *       observation only; no constant was added or dropped to make a count agree.</li>
 * </ol>
 *
 * <h2>Membership, and how the counts reconcile</h2>
 *
 * <p>Thirty-six constants are declared. The arithmetic is worth spelling out, because three
 * different totals are all correct about different things and a reader auditing completeness
 * needs to know which is which:
 *
 * <ul>
 *   <li><strong>Twenty-eight</strong> distinct AID mnemonics are actually <em>referenced</em>
 *       anywhere in the COBOL: {@code DFHENTER}, {@code DFHCLEAR}, {@code DFHPA1},
 *       {@code DFHPA2}, and {@code DFHPF1} through {@code DFHPF24}.</li>
 *   <li><strong>Twenty-nine</strong> are <em>mandated</em>, because the Agent Action Plan calls
 *       for the whole {@code DFHPA1}-{@code DFHPA3} range and {@code DFHPA3} is referenced
 *       nowhere.</li>
 *   <li><strong>Thirty-six</strong> are <em>declared</em> here, the remaining seven being the
 *       other members the IBM copybook defines - {@code DFHNULL}, {@code DFHCLRP},
 *       {@code DFHPEN}, {@code DFHOPID}, {@code DFHMSRE}, {@code DFHSTRF} and
 *       {@code DFHTRIG} - included so that this file is a faithful and complete reproduction of
 *       the named copybook rather than a partial extract of it. Each of the seven is documented
 *       on its own field as having no consumer in this repository.</li>
 * </ul>
 *
 * <h2>Scope: what this class deliberately does not do</h2>
 *
 * <p>This is a constants holder and nothing more. Translating a raw {@code EIBAID} byte into the
 * five-character AID token this codebase carries in its own work area - {@code ENTER},
 * {@code CLEAR}, {@code PA1}, {@code PA2}, {@code PFK01} through {@code PFK12}, as
 * {@code app/cpy/CVCRD01Y.cpy} defines them - is the responsibility of the sibling PF-key
 * resolver, which translates the {@code EVALUATE TRUE} chain of
 * {@code app/cpy/CSSTRPFY.cpy} and imports this class. No resolution logic lives here, and this
 * file imports nothing from any other package of this application: it is a root of the
 * dependency graph, and its only import at all is {@link java.util.Map}.
 *
 * <p>One behavioural detail of that resolver is worth recording here because it explains why all
 * twenty-four PF constants must exist as <em>distinct</em> values even though only twelve tokens
 * result from them: {@code CSSTRPFY} folds {@code DFHPF13} through {@code DFHPF24} back onto
 * {@code PFK01} through {@code PFK12}. The fold happens in the resolver, on distinct inputs.
 * Were two constants here to share a byte, that {@code EVALUATE} would become order-dependent in
 * a way the COBOL is not.
 *
 * <p>Members are declared in the same order as the IBM {@code DFHAID} copybook so that this file
 * can be read side by side with the IBM listing and checked line for line.
 *
 * <p>Thread safety: every member is a compile-time constant or an immutable map, so this class is
 * inherently thread safe and holds no mutable static state (practice <strong>B9</strong>).
 *
 * <p>No project-specific rules were supplied for this migration; the enterprise practices
 * <strong>B1</strong> through <strong>B12</strong> referenced above govern in their place, and
 * their absence was not treated as licence to relax any of them.
 */
public final class CicsAid {

    /**
     * Java charset name of the EBCDIC code page in which every AID constant in this class is
     * expressed, stated explicitly so that no caller and no reader has to infer it (practice
     * <strong>B8</strong>).
     *
     * <p>Each constant below equals the byte that this code page assigns to the character literal
     * the IBM {@code DFHAID} copybook declares, and equals IBM's published 3270 attention
     * identifier code for the same key. Callers that must decode a raw {@code EIBAID} byte from,
     * or encode one to, mainframe-encoded data should name this charset rather than relying on a
     * platform default, which is never correct for mainframe data.
     *
     * <p>This class itself performs no conversion; the constant is documentation of the byte
     * values' encoding and a single point of reference for code that does convert.
     */
    public static final String AID_CODE_PAGE = "IBM037";

    /**
     * No AID recorded - {@code DFHNULL}, the space character, EBCDIC {@code 0x40}.
     *
     * <p>The {@code DFHAID} copybook declares this member with a literal space, which is the
     * value an AID field carries before any key has been recorded into it. It is a sentinel and
     * not a key: it is distinct from the 3270 "no AID generated" byte, which {@code DFHAID} does
     * not define at all.
     *
     * <p>Consumers in this repository: <strong>none</strong>. Reproduced for completeness of the
     * copybook.
     */
    public static final byte DFHNULL = (byte) 0x40;

    /**
     * ENTER key - {@code DFHENTER}, the EBCDIC apostrophe, {@code 0x7D}.
     *
     * <p>By far the most heavily used AID in this application, and the one every screen depends
     * on: seventeen references in total, sixteen of them inline {@code EIBAID} tests in the
     * online programs plus one branch in {@code app/cpy/CSSTRPFY.cpy}.
     *
     * <p>Note the value: {@code 0x7D}, not {@code 0x27}. {@code 0x27} is the ASCII apostrophe and
     * would be wrong here.
     */
    public static final byte DFHENTER = (byte) 0x7D;

    /**
     * CLEAR key - {@code DFHCLEAR}, the EBCDIC underscore, {@code 0x6D}.
     *
     * <p>Consumers in this repository: one, the {@code DFHCLEAR} branch of
     * {@code app/cpy/CSSTRPFY.cpy}, which maps it to the {@code CLEAR} AID token.
     */
    public static final byte DFHCLEAR = (byte) 0x6D;

    /**
     * CLEAR PARTITION key - {@code DFHCLRP}, the EBCDIC broken bar ({@code &#166;}),
     * {@code 0x6A}.
     *
     * <p>Secondary reproductions of the copybook render this literal inconsistently - as a broken
     * bar, a pilcrow, or nothing at all - because the glyph varies between code pages. The byte
     * does not vary: the 3270 clear-partition attention identifier is {@code 0x6A}.
     *
     * <p>Consumers in this repository: <strong>none</strong>. Reproduced for completeness of the
     * copybook.
     */
    public static final byte DFHCLRP = (byte) 0x6A;

    /**
     * CURSOR SELECT key, also known as the light pen attention - {@code DFHPEN}, the EBCDIC
     * equals sign, {@code 0x7E}. IBM's AID table lists the same byte for the light pen.
     *
     * <p>Consumers in this repository: <strong>none</strong>. Reproduced for completeness of the
     * copybook.
     */
    public static final byte DFHPEN = (byte) 0x7E;

    /**
     * Operator identification card reader - {@code DFHOPID}, the EBCDIC capital {@code W},
     * {@code 0xE6}.
     *
     * <p>Consumers in this repository: <strong>none</strong>. Reproduced for completeness of the
     * copybook.
     */
    public static final byte DFHOPID = (byte) 0xE6;

    /**
     * Extended (standard) magnetic slot reader - {@code DFHMSRE}, the EBCDIC capital {@code X},
     * {@code 0xE7}.
     *
     * <p>Consumers in this repository: <strong>none</strong>. Reproduced for completeness of the
     * copybook.
     */
    public static final byte DFHMSRE = (byte) 0xE7;

    /**
     * Structured field pseudo-AID - {@code DFHSTRF}, the EBCDIC lower-case {@code h},
     * {@code 0x88}.
     *
     * <p>Consumers in this repository: <strong>none</strong>. Reproduced for completeness of the
     * copybook.
     */
    public static final byte DFHSTRF = (byte) 0x88;

    /**
     * Trigger field - {@code DFHTRIG}, the EBCDIC quotation mark, {@code 0x7F}.
     *
     * <p>Consumers in this repository: <strong>none</strong>. Reproduced for completeness of the
     * copybook.
     */
    public static final byte DFHTRIG = (byte) 0x7F;

    /**
     * PA1 key - {@code DFHPA1}, the EBCDIC percent sign, {@code 0x6C}.
     *
     * <p>Consumers in this repository: one, the {@code DFHPA1} branch of
     * {@code app/cpy/CSSTRPFY.cpy}, which maps it to the {@code PA1} AID token.
     */
    public static final byte DFHPA1 = (byte) 0x6C;

    /**
     * PA2 key - {@code DFHPA2}, the EBCDIC greater-than sign ({@code >}), {@code 0x6E}.
     *
     * <p>Consumers in this repository: one, the {@code DFHPA2} branch of
     * {@code app/cpy/CSSTRPFY.cpy}, which maps it to the {@code PA2} AID token.
     */
    public static final byte DFHPA2 = (byte) 0x6E;

    /**
     * PA3 key - {@code DFHPA3}, the EBCDIC comma, {@code 0x6B}.
     *
     * <p>Consumers in this repository: <strong>zero</strong>. A search of the entire checkout
     * finds no reference to this mnemonic, and {@code app/cpy/CSSTRPFY.cpy} - which does branch
     * on {@code DFHPA1} and {@code DFHPA2} - has no PA3 branch at all. The constant is defined
     * regardless: the Agent Action Plan mandates the {@code DFHPA1}-{@code DFHPA3} range, and
     * practice <strong>B5</strong> forbids removing an unreferenced member on the grounds that
     * nothing currently uses it. Do not "tidy" this field away.
     */
    public static final byte DFHPA3 = (byte) 0x6B;

    /*
     * PF1 through PF9 - the EBCDIC digits '1' through '9', 0xF1 through 0xF9. This is the only
     * contiguous, intuitive run in the whole AID set; PF10 onwards is not, which is why each of
     * the remaining constants carries its character literal in the documentation.
     */

    /**
     * PF1 key - {@code DFHPF1}, the EBCDIC digit {@code 1}, {@code 0xF1}.
     *
     * <p>Consumers in this repository: one, the {@code DFHPF1} branch of
     * {@code app/cpy/CSSTRPFY.cpy}, which maps it to the {@code PFK01} AID token.
     */
    public static final byte DFHPF1 = (byte) 0xF1;

    /**
     * PF2 key - {@code DFHPF2}, the EBCDIC digit {@code 2}, {@code 0xF2}.
     *
     * <p>Consumers in this repository: one, the {@code DFHPF2} branch of
     * {@code app/cpy/CSSTRPFY.cpy}, which maps it to the {@code PFK02} AID token.
     */
    public static final byte DFHPF2 = (byte) 0xF2;

    /**
     * PF3 key - {@code DFHPF3}, the EBCDIC digit {@code 3}, {@code 0xF3}.
     *
     * <p>The application's universal "back out of this screen" key, and after {@code DFHENTER}
     * the most referenced AID in the codebase: fifteen references, fourteen of them inline
     * {@code EIBAID} tests in the online programs plus one branch in
     * {@code app/cpy/CSSTRPFY.cpy}.
     */
    public static final byte DFHPF3 = (byte) 0xF3;

    /**
     * PF4 key - {@code DFHPF4}, the EBCDIC digit {@code 4}, {@code 0xF4}.
     *
     * <p>Consumers in this repository: seven references, six of them inline {@code EIBAID} tests
     * plus one branch in {@code app/cpy/CSSTRPFY.cpy}.
     */
    public static final byte DFHPF4 = (byte) 0xF4;

    /**
     * PF5 key - {@code DFHPF5}, the EBCDIC digit {@code 5}, {@code 0xF5}.
     *
     * <p>Consumers in this repository: five references, four of them inline {@code EIBAID} tests
     * plus one branch in {@code app/cpy/CSSTRPFY.cpy}.
     */
    public static final byte DFHPF5 = (byte) 0xF5;

    /**
     * PF6 key - {@code DFHPF6}, the EBCDIC digit {@code 6}, {@code 0xF6}.
     *
     * <p>Consumers in this repository: one, the {@code DFHPF6} branch of
     * {@code app/cpy/CSSTRPFY.cpy}, which maps it to the {@code PFK06} AID token.
     */
    public static final byte DFHPF6 = (byte) 0xF6;

    /**
     * PF7 key - {@code DFHPF7}, the EBCDIC digit {@code 7}, {@code 0xF7}.
     *
     * <p>The page-backward key on the paginated list screens. Consumers in this repository: five
     * references, four of them inline {@code EIBAID} tests plus one branch in
     * {@code app/cpy/CSSTRPFY.cpy}.
     */
    public static final byte DFHPF7 = (byte) 0xF7;

    /**
     * PF8 key - {@code DFHPF8}, the EBCDIC digit {@code 8}, {@code 0xF8}.
     *
     * <p>The page-forward key on the paginated list screens. Consumers in this repository: five
     * references, four of them inline {@code EIBAID} tests plus one branch in
     * {@code app/cpy/CSSTRPFY.cpy}.
     */
    public static final byte DFHPF8 = (byte) 0xF8;

    /**
     * PF9 key - {@code DFHPF9}, the EBCDIC digit {@code 9}, {@code 0xF9}.
     *
     * <p>Consumers in this repository: one, the {@code DFHPF9} branch of
     * {@code app/cpy/CSSTRPFY.cpy}, which maps it to the {@code PFK09} AID token.
     */
    public static final byte DFHPF9 = (byte) 0xF9;

    /*
     * First discontinuity: PF10, PF11 and PF12 are NOT 0xFA, 0xFB and 0xFC. The digit run ends at
     * PF9. PF10 through PF12 are the EBCDIC colon, number sign and commercial-at, 0x7A through
     * 0x7C. A secondary source asserting PF12 = 0xFC was checked against IBM's attention
     * identifier table and rejected; see the class documentation.
     */

    /**
     * PF10 key - {@code DFHPF10}, the EBCDIC colon ({@code :}), {@code 0x7A}.
     *
     * <p>Not {@code 0xFA}: the digit run ends at PF9. Consumers in this repository: one, the
     * {@code DFHPF10} branch of {@code app/cpy/CSSTRPFY.cpy}, which maps it to the {@code PFK10}
     * AID token.
     */
    public static final byte DFHPF10 = (byte) 0x7A;

    /**
     * PF11 key - {@code DFHPF11}, the EBCDIC number sign ({@code #}), {@code 0x7B}.
     *
     * <p>Consumers in this repository: one, the {@code DFHPF11} branch of
     * {@code app/cpy/CSSTRPFY.cpy}, which maps it to the {@code PFK11} AID token.
     */
    public static final byte DFHPF11 = (byte) 0x7B;

    /**
     * PF12 key - {@code DFHPF12}, the EBCDIC commercial-at sign ({@code @}), {@code 0x7C}.
     *
     * <p>Not {@code 0xFC}, despite one secondary reproduction of the copybook claiming so; IBM's
     * attention identifier table gives {@code 0x7C} and the copybook literal is the
     * commercial-at, which is {@code 0x7C} in this code page.
     *
     * <p>Consumers in this repository: three references, two of them inline {@code EIBAID} tests
     * plus one branch in {@code app/cpy/CSSTRPFY.cpy}.
     */
    public static final byte DFHPF12 = (byte) 0x7C;

    /*
     * Second discontinuity: PF13 onwards is the EBCDIC capital letter run - but it covers only
     * 'A' through 'I', because EBCDIC letters are themselves not contiguous (0xC9 is followed by
     * 0xD1, not 0xCA). The run therefore stops at PF21, and PF22 through PF24 revert to
     * punctuation at 0x4A through 0x4C. The migration plan's cross-check hint described this
     * range as "the twelve EBCDIC letters 'A' through 'L'", which IBM's attention identifier
     * table contradicts; see the class documentation.
     */

    /**
     * PF13 key - {@code DFHPF13}, the EBCDIC capital {@code A}, {@code 0xC1}.
     *
     * <p>Consumers in this repository: one, the {@code DFHPF13} branch of
     * {@code app/cpy/CSSTRPFY.cpy}, which folds it onto the {@code PFK01} AID token.
     */
    public static final byte DFHPF13 = (byte) 0xC1;

    /**
     * PF14 key - {@code DFHPF14}, the EBCDIC capital {@code B}, {@code 0xC2}.
     *
     * <p>Consumers in this repository: one, the {@code DFHPF14} branch of
     * {@code app/cpy/CSSTRPFY.cpy}, which folds it onto the {@code PFK02} AID token.
     */
    public static final byte DFHPF14 = (byte) 0xC2;

    /**
     * PF15 key - {@code DFHPF15}, the EBCDIC capital {@code C}, {@code 0xC3}.
     *
     * <p>Consumers in this repository: one, the {@code DFHPF15} branch of
     * {@code app/cpy/CSSTRPFY.cpy}, which folds it onto the {@code PFK03} AID token.
     */
    public static final byte DFHPF15 = (byte) 0xC3;

    /**
     * PF16 key - {@code DFHPF16}, the EBCDIC capital {@code D}, {@code 0xC4}.
     *
     * <p>Consumers in this repository: one, the {@code DFHPF16} branch of
     * {@code app/cpy/CSSTRPFY.cpy}, which folds it onto the {@code PFK04} AID token.
     */
    public static final byte DFHPF16 = (byte) 0xC4;

    /**
     * PF17 key - {@code DFHPF17}, the EBCDIC capital {@code E}, {@code 0xC5}.
     *
     * <p>Consumers in this repository: one, the {@code DFHPF17} branch of
     * {@code app/cpy/CSSTRPFY.cpy}, which folds it onto the {@code PFK05} AID token.
     */
    public static final byte DFHPF17 = (byte) 0xC5;

    /**
     * PF18 key - {@code DFHPF18}, the EBCDIC capital {@code F}, {@code 0xC6}.
     *
     * <p>Consumers in this repository: one, the {@code DFHPF18} branch of
     * {@code app/cpy/CSSTRPFY.cpy}, which folds it onto the {@code PFK06} AID token.
     */
    public static final byte DFHPF18 = (byte) 0xC6;

    /**
     * PF19 key - {@code DFHPF19}, the EBCDIC capital {@code G}, {@code 0xC7}.
     *
     * <p>Consumers in this repository: one, the {@code DFHPF19} branch of
     * {@code app/cpy/CSSTRPFY.cpy}, which folds it onto the {@code PFK07} AID token.
     */
    public static final byte DFHPF19 = (byte) 0xC7;

    /**
     * PF20 key - {@code DFHPF20}, the EBCDIC capital {@code H}, {@code 0xC8}.
     *
     * <p>Consumers in this repository: one, the {@code DFHPF20} branch of
     * {@code app/cpy/CSSTRPFY.cpy}, which folds it onto the {@code PFK08} AID token.
     */
    public static final byte DFHPF20 = (byte) 0xC8;

    /**
     * PF21 key - {@code DFHPF21}, the EBCDIC capital {@code I}, {@code 0xC9}.
     *
     * <p>The last constant in the letter run: EBCDIC capital {@code J} is {@code 0xD1}, not
     * {@code 0xCA}, and in any case PF22 onwards is not a letter at all.
     *
     * <p>Consumers in this repository: one, the {@code DFHPF21} branch of
     * {@code app/cpy/CSSTRPFY.cpy}, which folds it onto the {@code PFK09} AID token.
     */
    public static final byte DFHPF21 = (byte) 0xC9;

    /**
     * PF22 key - {@code DFHPF22}, the EBCDIC cent sign ({@code &#162;}), {@code 0x4A}.
     *
     * <p>Not a letter, and not {@code 0xCA}. IBM's attention identifier table gives {@code 0x4A}.
     *
     * <p>Consumers in this repository: one, the {@code DFHPF22} branch of
     * {@code app/cpy/CSSTRPFY.cpy}, which folds it onto the {@code PFK10} AID token.
     */
    public static final byte DFHPF22 = (byte) 0x4A;

    /**
     * PF23 key - {@code DFHPF23}, the EBCDIC period ({@code .}), {@code 0x4B}.
     *
     * <p>Consumers in this repository: one, the {@code DFHPF23} branch of
     * {@code app/cpy/CSSTRPFY.cpy}, which folds it onto the {@code PFK11} AID token.
     */
    public static final byte DFHPF23 = (byte) 0x4B;

    /**
     * PF24 key - {@code DFHPF24}, the EBCDIC less-than sign ({@code <}), {@code 0x4C}.
     *
     * <p>Consumers in this repository: one, the {@code DFHPF24} branch of
     * {@code app/cpy/CSSTRPFY.cpy}, which folds it onto the {@code PFK12} AID token.
     */
    public static final byte DFHPF24 = (byte) 0x4C;

    /**
     * Every AID byte defined by this class mapped to the {@code DFHAID} mnemonic that declares
     * it, for diagnostics and for logging an unrecognised {@code EIBAID} in human-readable form.
     *
     * <p>Immutable by construction, and safe to hand out directly, because {@code Map.ofEntries}
     * returns an unmodifiable map (practice <strong>B9</strong> - no mutable static state, and no
     * static array).
     *
     * <p>Populating this successfully is itself a standing assertion that the <strong>thirty-six</strong>
     * AID values this class declares are pairwise distinct: {@code Map.ofEntries} rejects duplicate
     * keys, so a copy-paste error that gave two mnemonics the same byte would fail at class
     * initialisation rather than silently corrupt key routing. The count is thirty-six -
     * {@code DFHNULL}, {@code DFHENTER}, {@code DFHCLEAR}, {@code DFHCLRP}, {@code DFHPEN},
     * {@code DFHOPID}, {@code DFHMSRE}, {@code DFHSTRF}, {@code DFHTRIG}, the three {@code DFHPA}
     * attention keys and the twenty-four {@code DFHPF} function keys - which is the same thirty-six
     * this class's own header records, and {@link #mnemonicsByAid()}{@code .size()} reports it from
     * the map itself so a reader never has to trust a hand-written number.
     */
    private static final Map<Byte, String> MNEMONICS_BY_AID = Map.ofEntries(
            Map.entry(DFHNULL, "DFHNULL"),
            Map.entry(DFHENTER, "DFHENTER"),
            Map.entry(DFHCLEAR, "DFHCLEAR"),
            Map.entry(DFHCLRP, "DFHCLRP"),
            Map.entry(DFHPEN, "DFHPEN"),
            Map.entry(DFHOPID, "DFHOPID"),
            Map.entry(DFHMSRE, "DFHMSRE"),
            Map.entry(DFHSTRF, "DFHSTRF"),
            Map.entry(DFHTRIG, "DFHTRIG"),
            Map.entry(DFHPA1, "DFHPA1"),
            Map.entry(DFHPA2, "DFHPA2"),
            Map.entry(DFHPA3, "DFHPA3"),
            Map.entry(DFHPF1, "DFHPF1"),
            Map.entry(DFHPF2, "DFHPF2"),
            Map.entry(DFHPF3, "DFHPF3"),
            Map.entry(DFHPF4, "DFHPF4"),
            Map.entry(DFHPF5, "DFHPF5"),
            Map.entry(DFHPF6, "DFHPF6"),
            Map.entry(DFHPF7, "DFHPF7"),
            Map.entry(DFHPF8, "DFHPF8"),
            Map.entry(DFHPF9, "DFHPF9"),
            Map.entry(DFHPF10, "DFHPF10"),
            Map.entry(DFHPF11, "DFHPF11"),
            Map.entry(DFHPF12, "DFHPF12"),
            Map.entry(DFHPF13, "DFHPF13"),
            Map.entry(DFHPF14, "DFHPF14"),
            Map.entry(DFHPF15, "DFHPF15"),
            Map.entry(DFHPF16, "DFHPF16"),
            Map.entry(DFHPF17, "DFHPF17"),
            Map.entry(DFHPF18, "DFHPF18"),
            Map.entry(DFHPF19, "DFHPF19"),
            Map.entry(DFHPF20, "DFHPF20"),
            Map.entry(DFHPF21, "DFHPF21"),
            Map.entry(DFHPF22, "DFHPF22"),
            Map.entry(DFHPF23, "DFHPF23"),
            Map.entry(DFHPF24, "DFHPF24"));

    /**
     * Returns every AID byte this class defines, mapped to its {@code DFHAID} mnemonic.
     *
     * <p>This is a lookup only, deliberately carrying no behaviour and no fallback: a caller that
     * wants a default for an unrecognised byte supplies its own, for instance with
     * {@code CicsAid.mnemonicsByAid().getOrDefault(aid, "UNKNOWN")}. Interpreting an
     * {@code EIBAID} byte as a screen action is the PF-key resolver's job, not this class's.
     *
     * <p>Keys are boxed AID bytes; the map is immutable and may be retained by the caller.
     *
     * @return an unmodifiable map from AID byte to {@code DFHAID} mnemonic name, never
     *         {@code null} and never empty
     */
    public static Map<Byte, String> mnemonicsByAid() {
        return MNEMONICS_BY_AID;
    }

    /**
     * Not instantiable: this class reproduces a COBOL copybook of constants and has no state and
     * no instance behaviour.
     *
     * @throws AssertionError always, including when invoked reflectively
     */
    private CicsAid() {
        throw new AssertionError("CicsAid is a constants holder and must not be instantiated");
    }
}
