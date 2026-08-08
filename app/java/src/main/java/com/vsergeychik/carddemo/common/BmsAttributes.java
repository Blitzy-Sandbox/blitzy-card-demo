package com.vsergeychik.carddemo.common;

import java.util.Map;

/**
 * BMS field-attribute and extended-attribute constants: the Java reproduction of the two
 * IBM-supplied copybooks {@code DFHBMSCA} and {@code DFHATTR}.
 *
 * <h2>Why this class exists at all</h2>
 * Seventeen of the twenty-eight COBOL programs in {@code app/cbl} contain {@code COPY DFHBMSCA.},
 * and two of them additionally name {@code DFHATTR}. Neither copybook is present in this
 * repository: {@code ls app/cpy/DFH*} fails outright, because {@code app/cpy} holds the twenty-eight
 * <em>application</em> copybooks only. {@code DFHBMSCA}, {@code DFHATTR} and {@code DFHAID} ship with
 * IBM CICS itself, from the {@code SDFHCOB} library, and were never checked in here.
 *
 * <p>That absence is a genuine environmental limit, not an oversight in the migration, so it is
 * recorded in the code rather than absorbed silently. <strong>Every byte value below is sourced from
 * IBM CICS documentation</strong> - the {@code DFHBMSCA} standard attribute and printer control
 * character list, its companion "bit map of attributes" table, and the 3270 extended colour and
 * extended highlighting code assignments - and <strong>not one value is guessed</strong>. The gap is
 * tracked as <strong>risk R-D</strong> in the migration plan; this Javadoc is the place a reviewer
 * can see the provenance without leaving the source file.
 *
 * <p>No dependency was added to obtain these constants. Introducing an IBM CICS artifact to import
 * thirty single bytes would breach the closed dependency set declared in {@code app/java/pom.xml},
 * and hand-writing them keeps every value reviewable against the documentation that produced it.
 *
 * <h2>These are EBCDIC bytes, and that distinction is load-bearing</h2>
 * A BMS attribute is one byte whose bit pattern is fixed by 3270 hardware. The copybooks express
 * each byte as a COBOL {@code PIC X} character literal, so the mnemonic {@code DFHRED} appears in
 * IBM's listings as the digit {@code '2'} - but only because <em>EBCDIC</em> {@code '2'} happens to
 * be {@code 0xF2}, the architected 3270 colour code for red. In Java a {@code char} literal is a
 * Unicode code point, so {@code '2'} would be {@code 0x32} and the wrong byte would reach the
 * terminal.
 *
 * <p>Every constant here is therefore declared as a {@code byte} holding the <strong>EBCDIC</strong>
 * value, written as an explicit hexadecimal literal. The graphic character each byte corresponds to
 * is recorded in the Javadoc for readability only and must never be used as the value. For the same
 * reason this class performs no character encoding or decoding whatsoever: it never encodes text to
 * bytes, never decodes bytes back into text, and never consults the platform default charset. Bytes
 * in, bytes out.
 *
 * <p>Because {@code byte} is signed in Java, values above {@code 0x7F} - which is most of the basic
 * attribute bytes - read back negative. Use {@link #unsigned(byte)} whenever a 0-255 view is wanted;
 * comparisons between two of these constants are unaffected and need no conversion.
 *
 * <h2>Three attribute planes, and why they overlap</h2>
 * The 3270 data stream carries these values in three independent planes, mirrored by the three
 * sections below:
 * <ol>
 *   <li><strong>Basic field attribute</strong> - one byte per field encoding protection, numeric or
 *       autoskip, intensity, selector-pen detectability, non-display and the modified data tag. The
 *       COBOL moves these into a symbolic map's attribute item {@code xxxA}, the {@code REDEFINES}
 *       of {@code xxxF}, on the <em>input</em> map. Example: {@code MOVE DFHBMFSE TO CRDSEL1A OF
 *       CCRDLIAI} at {@code app/cbl/COCRDLIC.cbl:L761}.</li>
 *   <li><strong>Extended colour</strong> - moved into a colour item {@code xxxC} on the
 *       <em>output</em> map. Example: {@code MOVE DFHRED TO CRDSEL1C OF CCRDLIAO} at
 *       {@code app/cbl/COCRDLIC.cbl:L756}.</li>
 *   <li><strong>Extended highlighting</strong> - blink, reverse video and underscore.</li>
 * </ol>
 * The planes deliberately share byte values: {@code DFHBMASF}, {@code DFHBLUE} and {@code DFHBLINK}
 * are all {@code 0xF1}, and that is correct, because the receiving terminal interprets the byte
 * according to the plane it arrived in. It follows that uniqueness only ever holds
 * <em>within</em> a plane. Each plane's constants are pairwise distinct; across planes they are not,
 * and asserting otherwise would be asserting something false about the hardware.
 *
 * <h2>How the required set was determined</h2>
 * The migration plan names four mnemonics explicitly and then says "and related attribute
 * constants". Read literally that would have omitted seven mnemonics the COBOL actively uses, and
 * the omission would have surfaced only as a compile error deep in controller generation - or worse,
 * as a silently wrong screen attribute. The set was therefore derived mechanically, by extracting
 * every {@code DFH*} symbol from {@code app/cbl} and {@code app/cpy} and classifying each one. Every
 * attribute or colour mnemonic that appears anywhere in the COBOL is defined below, and each
 * constant's Javadoc records its verified occurrence count and consuming programs so the claim stays
 * auditable.
 *
 * <h2>Observations recorded rather than resolved</h2>
 * Four discrepancies were found between the migration plan and the COBOL. They are documented here
 * and left exactly as the source has them; correcting a source of truth is not this file's job.
 * <ul>
 *   <li>The plan describes {@code COSGN00C} and {@code COUSR01C} as {@code DFHATTR}'s two consumers.
 *       In both programs the copy statement is <strong>commented out</strong> -
 *       {@code app/cbl/COSGN00C.cbl:L59} and {@code app/cbl/COUSR01C.cbl:L57} both read
 *       {@code *COPY DFHATTR.} - so no program actually includes {@code DFHATTR} at compile time.
 *       Both programs do actively copy {@code DFHBMSCA}, which supplies every mnemonic they use.
 *       This class merges both copybooks as the plan directs; the note exists so nobody later
 *       concludes the merge was unnecessary, or that {@code DFHATTR} was overlooked.</li>
 *   <li>{@code DFHBMDAR} is moved into <strong>both</strong> plane 1 and plane 2 by different
 *       programs: into the attribute item at {@code app/cbl/COACTUPC.cbl:L3568} and
 *       {@code app/cbl/COCRDUPC.cbl:L1310}, but into the colour item at
 *       {@code app/cbl/COCRDLIC.cbl:L671}, {@code app/cbl/COACTVWC.cbl:L568} and
 *       {@code app/cbl/COCRDUPC.cbl:L1285}. Inconsistent, and preserved. It is also the reason these
 *       constants are plain bytes rather than plane-tagged types: the COBOL is free to move any of
 *       them into either item, and a type system that forbade it would not be a faithful
 *       translation.</li>
 *   <li>{@code app/cbl/COACTUPC.cbl:L3201} holds a commented-out {@code MOVE DFHRED TO ACSTTUSC}.
 *       Dead COBOL, left dead - the live equivalent is generated by the {@code CSSETATY} copy at
 *       {@code L3208}.</li>
 *   <li>{@code DFHUNIMD} is defined below on the plan's explicit instruction even though it has
 *       <strong>zero</strong> occurrences anywhere in this repository. Unused code is preserved, not
 *       tidied away.</li>
 * </ul>
 *
 * <h2>What deliberately is not here</h2>
 * <ul>
 *   <li><strong>{@code DFHCOMMAREA}</strong> (38 occurrences) is not an attribute. It is the CICS
 *       communication area, and it belongs to the controllers that carry navigation state in their
 *       payloads.</li>
 *   <li><strong>{@code DFHRESP}</strong> (84 occurrences) is a response-code function, not an
 *       attribute. Its equivalents live in {@code FileStatus} alongside the batch {@code FILE STATUS}
 *       codes.</li>
 *   <li><strong>{@code DFHENTER}, {@code DFHCLEAR}, {@code DFHPA1}-{@code DFHPA3} and
 *       {@code DFHPF1}-{@code DFHPF24}</strong> are attention identifiers from {@code DFHAID}, a
 *       third absent copybook, and belong to the sibling attention-identifier constant holder in this
 *       same package.</li>
 *   <li><strong>Error-highlight logic.</strong> The {@code CSSETATY} rule - move {@code DFHRED} into
 *       the colour item, and an asterisk into the data item, when a field is in error or blank and
 *       the program is re-entered - is behaviour, not a constant, and lives in the sibling
 *       field-attribute helper in this same package. That helper depends on this class; this class
 *       depends on nothing in the application, so the relationship stays acyclic and this file remains
 *       a root of the type graph. It declares no import from any {@code com.vsergeychik.carddemo}
 *       package, and none may ever be added.</li>
 *   <li><strong>Printer control, Set Attribute order builders, inbound flag bytes, validation,
 *       field outlining, programmed symbols and background transparency.</strong> The remaining
 *       {@code DFHBMSCA} members - {@code DFHBMPEM}, {@code DFHBMPNL}, {@code DFHBMPFF},
 *       {@code DFHBMPCR}, {@code DFHBMPSO}, {@code DFHBMPSI}, {@code DFHSA}, {@code DFHERROR},
 *       {@code DFHCOLOR}, {@code DFHPS}, {@code DFHHLT}, {@code DFH3270}, {@code DFHALL},
 *       {@code DFHDFT}, {@code DFHBMEOF}, {@code DFHBMCUR}, {@code DFHBMEC}, {@code DFHBMFLG},
 *       {@code DFHBMDET}, {@code DFHVAL}, {@code DFHMFIL}, {@code DFHMENT}, {@code DFHMFE},
 *       {@code DFHMT}, {@code DFHMFT}, {@code DFHOUTLN}, {@code DFHBKTRN}, {@code DFHBASE},
 *       {@code DFHSOSI} and the outlining group - have no consumer in these twenty-eight programs.
 *       Several are non-displayable control bytes whose values the available documentation describes
 *       by meaning rather than by hexadecimal code. Defining them would mean guessing, and a wrong
 *       constant nobody uses is worse than an absent one: it looks authoritative. They are omitted
 *       on purpose, and this list is the record of that decision. Should a consumer ever appear, add
 *       the member with a documented value - never an inferred one.</li>
 * </ul>
 *
 * <p>This class is a pure constant holder. It is {@code final}, cannot be instantiated, declares no
 * mutable state of any kind - no arrays, since a {@code static final} array is still mutable
 * content - and every exposed collection is unmodifiable by construction.
 */
public final class BmsAttributes {

    // =================================================================================================
    // SECTION 1 - DFHBMSCA: 3270 BASIC FIELD ATTRIBUTE BYTES
    //
    // One byte per field. The bit layout, numbering bits 0-7 from the most significant, is:
    //   bits 0-1  forced by the architecture so that the byte is a displayable EBCDIC graphic
    //   bit 2     protected
    //   bit 3     numeric  (protected + numeric together give autoskip)
    //   bits 4-5  display and detectability: 00 normal non-detectable, 01 normal detectable,
    //             10 intensified detectable, 11 non-display non-detectable
    //   bit 6     reserved, always zero
    //   bit 7     modified data tag (MDT)
    //
    // The values below are the combinations IBM assigns names to in DFHBMSCA. Each was reconciled
    // against IBM's bit-map-of-attributes table, which lists the same combinations with their EBCDIC
    // code and graphic character - so both the mnemonic's documented meaning and its byte agree.
    // All eighteen are pairwise distinct. Ordering follows that table: unprotected alphanumeric,
    // unprotected numeric, protected, then autoskip.
    // =================================================================================================

    /**
     * Unprotected, normal intensity, MDT off: {@code 0x40}, EBCDIC space.
     *
     * <p>The plain data-entry field, and the reset value a field returns to once its error condition
     * clears. Zero direct occurrences in these twenty-eight programs, because the BMS mapsets declare
     * the initial {@code ATTRB} for unprotected fields and no program needs to restate it - but it is
     * the baseline every other combination in this section is a variation on, so it is defined.
     */
    public static final byte DFHBMUNP = (byte) 0x40;

    /**
     * Unprotected, normal intensity, MDT set: {@code 0x61} in ASCII terms but {@code 0xC1} here,
     * EBCDIC {@code 'A'}. Named {@code DFHBMFSE} in the copybook, for "field set".
     *
     * <p>Setting the modified data tag makes CICS return the field on the next input even when the
     * operator did not retype it, which is how these programs redisplay a screen without losing what
     * is already on it. The second heaviest attribute mnemonic in the codebase: <strong>23
     * occurrences</strong>, in {@code COACTUPC}, {@code COACTVWC}, {@code COCRDLIC}, {@code COCRDSLC}
     * and {@code COCRDUPC}. Representative use is {@code MOVE DFHBMFSE TO CRDSEL1A OF CCRDLIAI} at
     * {@code app/cbl/COCRDLIC.cbl:L761}, re-enabling a card-selection cell that has live data behind
     * it.
     */
    public static final byte DFHBMFSE = (byte) 0xC1;

    /**
     * Unprotected, intensified, selector-pen detectable, MDT off: {@code 0xC8}, EBCDIC {@code 'H'}.
     *
     * <p>The copybook calls this "bright". <strong>Two occurrences</strong>, both in
     * {@code COCRDUPC}: {@code app/cbl/COCRDUPC.cbl:L1312} brightens the information message when
     * there is one to show, and {@code L1316} brightens the function-key legend. Contrast
     * {@code L1310} in the same paragraph, which uses {@link #DFHBMDAR} to hide the message when
     * there is not - the two mnemonics are the on and off states of one decision.
     */
    public static final byte DFHBMBRY = (byte) 0xC8;

    /**
     * Unprotected, intensified, selector-pen detectable, MDT set: {@code 0xC9}, EBCDIC {@code 'I'}.
     * Documented by IBM as "unprotected, intensify, detectable, MDT".
     *
     * <p><strong>Zero occurrences anywhere in this repository.</strong> It is defined because the
     * migration plan names it explicitly, and because unused code is preserved rather than tidied
     * away: deciding it was surplus would be a judgement this migration is not entitled to make. It
     * is {@link #DFHBMBRY} with the modified data tag pre-set, so it is what a program would use to
     * both highlight a field and guarantee its return on the next input.
     */
    public static final byte DFHUNIMD = (byte) 0xC9;

    /**
     * Unprotected, non-display, non-detectable, MDT off: {@code 0x4C}, EBCDIC {@code '<'}.
     *
     * <p>"Dark". The field keeps its content and the operator can still type into it, but nothing is
     * rendered - the classic password treatment, and equally the way these programs blank a message
     * line without erasing it. <strong>Six occurrences</strong>, in {@code COACTUPC},
     * {@code COACTVWC}, {@code COCRDLIC}, {@code COCRDSLC} and {@code COCRDUPC}.
     *
     * <p>This is the mnemonic the COBOL uses in two different planes, noted in the class Javadoc:
     * into the attribute item at {@code app/cbl/COACTUPC.cbl:L3568}
     * ({@code MOVE DFHBMDAR TO INFOMSGA OF CACTUPAI}), but into the colour item at
     * {@code app/cbl/COCRDLIC.cbl:L671} ({@code MOVE DFHBMDAR TO INFOMSGC OF CCRDLIAO}). Both are
     * reproduced as written.
     */
    public static final byte DFHBMDAR = (byte) 0x4C;

    /**
     * Unprotected, non-display, non-detectable, MDT set: {@code 0x4D}, EBCDIC {@code '('}.
     * Documented by IBM as "unprotected, non-display, non-print, nondetectable, MDT".
     *
     * <p>{@link #DFHBMDAR} with the modified data tag pre-set. Zero occurrences in these programs;
     * defined so this section is the complete unprotected-alphanumeric set rather than a subset, and
     * so a reviewer comparing against IBM's table finds no unexplained holes.
     */
    public static final byte DFHUNNOD = (byte) 0x4D;

    /**
     * Unprotected, numeric, normal intensity, MDT off: {@code 0x50}, EBCDIC {@code '&'}.
     *
     * <p>The numeric attribute makes a data-entry keyboard shift to its numeric layout; it is an input
     * convenience, never a validation guarantee, so the Java translation still validates the field
     * exactly as the COBOL does. Zero direct occurrences - the mapsets declare {@code ATTRB=(NUM...)}
     * where a numeric field is wanted - but it is the baseline of the numeric group.
     */
    public static final byte DFHBMUNN = (byte) 0x50;

    /**
     * Unprotected, numeric, normal intensity, MDT set: {@code 0xD1}, EBCDIC {@code 'J'}.
     * Documented by IBM as "unprotected, numeric, MDT".
     *
     * <p>The numeric counterpart of {@link #DFHBMFSE}. Zero occurrences in these programs.
     */
    public static final byte DFHUNNUM = (byte) 0xD1;

    /**
     * Unprotected, numeric, intensified, selector-pen detectable, MDT off: {@code 0xD8}, EBCDIC
     * {@code 'Q'}.
     *
     * <p>The numeric counterpart of {@link #DFHBMBRY}. Zero occurrences in these programs.
     */
    public static final byte DFHUNNUB = (byte) 0xD8;

    /**
     * Unprotected, numeric, intensified, selector-pen detectable, MDT set: {@code 0xD9}, EBCDIC
     * {@code 'R'}. Documented by IBM as "unprotected, numeric, intensify, detectable, MDT".
     *
     * <p>Zero occurrences in these programs.
     */
    public static final byte DFHUNINT = (byte) 0xD9;

    /**
     * Unprotected, numeric, non-display, non-detectable, MDT set: {@code 0x5D}, EBCDIC {@code ')'}.
     * Documented by IBM as "unprotected, numeric, non-display, non-print, nondetectable, MDT".
     *
     * <p>The numeric non-display combination. Zero occurrences in these programs; defined to complete
     * the numeric group.
     */
    public static final byte DFHUNNON = (byte) 0x5D;

    /**
     * Protected, normal intensity, MDT off: {@code 0x60}, EBCDIC {@code '-'} (hyphen).
     *
     * <p>Read-only. The operator's cursor can still land in the field, but keystrokes are rejected -
     * unlike {@link #DFHBMASK}, which skips over it. <strong>Six occurrences, all in
     * {@code COCRDLIC}</strong>, and the pattern there is worth understanding because it is not
     * uniform: rows two through seven of the card list lock their selection cell with
     * {@code DFHBMPRO} ({@code app/cbl/COCRDLIC.cbl:L766}, {@code L777}, {@code L789}, {@code L801},
     * {@code L812}, {@code L824}) whereas row one uses {@link #DFHBMPRF} at {@code L753}. Row one
     * differs from rows two to seven in the original; it is translated as it stands.
     */
    public static final byte DFHBMPRO = (byte) 0x60;

    /**
     * Protected, normal intensity, MDT set: {@code 0x61}, EBCDIC {@code '/'}.
     *
     * <p>Read-only, but still returned on the next input because the modified data tag is set - the
     * combination for a field the operator may not change while the program must keep seeing its
     * value. <strong>Eleven occurrences</strong>, in {@code COACTUPC}, {@code COCRDLIC},
     * {@code COCRDSLC} and {@code COCRDUPC}: for instance {@code app/cbl/COACTUPC.cbl:L3442} locks
     * the account-identifier field once a record has been fetched, {@code L3531} and {@code L3547}
     * lock derived fields, {@code L3560} locks the information-message line, and
     * {@code app/cbl/COCRDUPC.cbl:L1176}, {@code L1183}, {@code L1193} and {@code L1203} lock the
     * card-name and account-identifier fields during an update.
     */
    public static final byte DFHBMPRF = (byte) 0x61;

    /**
     * Protected, intensified, selector-pen detectable, MDT off: {@code 0xE8}, EBCDIC {@code 'Y'}.
     * Documented by IBM as "protected, intensify, detectable".
     *
     * <p>A highlighted read-only field - a heading or a prompt the program wants to emphasise. Zero
     * occurrences in these programs, which reach for {@link #DFHBMASB} instead when they want to
     * emphasise a protected field.
     */
    public static final byte DFHPROTI = (byte) 0xE8;

    /**
     * Protected, non-display, non-detectable, MDT off: {@code 0x6C}, EBCDIC {@code '%'}.
     * Documented by IBM as "protected, nondisplay, nonprint, nondetectable".
     *
     * <p>Zero occurrences in these programs.
     */
    public static final byte DFHPROTN = (byte) 0x6C;

    /**
     * Autoskip - protected and numeric - normal intensity, MDT off: {@code 0xF0}, EBCDIC {@code '0'}.
     *
     * <p>Stronger than {@link #DFHBMPRO}: the cursor jumps straight past the field rather than
     * stopping in it, which is how literal text and column headings are made unreachable. Zero direct
     * occurrences, because the mapsets declare {@code ATTRB=ASKIP} on every such field at map
     * definition time and no program needs to set it at run time.
     */
    public static final byte DFHBMASK = (byte) 0xF0;

    /**
     * Autoskip, normal intensity, MDT set: {@code 0xF1}, EBCDIC {@code '1'}.
     *
     * <p>Zero occurrences in these programs. Note that this byte is numerically equal to
     * {@link #DFHBLUE} and {@link #DFHBLINK}, which sit in different planes - see the class Javadoc.
     * The collision is architectural and expected.
     */
    public static final byte DFHBMASF = (byte) 0xF1;

    /**
     * Autoskip, intensified, selector-pen detectable, MDT off: {@code 0xF8}, EBCDIC {@code '8'}.
     *
     * <p>The copybook calls this "autoskip and bright" - an unreachable field, emphasised. <strong>Four
     * occurrences, all in {@code COACTUPC}</strong>, and every one of them draws the operator's eye to
     * something actionable: {@code app/cbl/COACTUPC.cbl:L3570} brightens the information message when
     * there is one, {@code L3575} brightens the F12 legend once changes have been made but not yet
     * confirmed, and {@code L3579} together with {@code L3580} brightens both F5 and F12 while a
     * confirmation is outstanding. The complementary "no message" branch at {@code L3568} uses
     * {@link #DFHBMDAR}.
     */
    public static final byte DFHBMASB = (byte) 0xF8;

    // =================================================================================================
    // SECTION 2 - DFHBMSCA / DFHATTR: EXTENDED COLOUR VALUES
    //
    // A separate plane from Section 1. The 3270 extended data stream carries colour as its own code:
    // 0x00 selects the default, and 0xF1 through 0xF7 select the seven colours a display supports for
    // alphanumeric text. The copybooks express these as the characters '1' to '7', which in EBCDIC are
    // exactly 0xF1 to 0xF7 - the architected codes and the character literals coincide, which is why
    // the copybook can be written in printable characters at all.
    //
    // The COBOL moves these into a symbolic map's colour item xxxC on the output map. All eight are
    // pairwise distinct within this plane.
    // =================================================================================================

    /**
     * Default colour: {@code 0x00}.
     *
     * <p>Resets a field to whatever colour its map definition specifies, discarding any override the
     * program applied earlier. In the 3270 extended data stream {@code 0x00} is the architected default
     * colour code, distinct from every explicit colour.
     *
     * <p><strong>Six occurrences</strong>, in {@code COACTUPC}, {@code COACTVWC}, {@code COCRDSLC} and
     * {@code COCRDUPC}, and always in the same role - clearing a previous red before validation
     * decides whether to reapply it. {@code app/cbl/COACTUPC.cbl:L3172} resets the account-identifier
     * colour when the screen was reached from the card-list mapset, immediately before {@code L3177}
     * and {@code L3183} may set it red again. {@code app/cbl/COACTVWC.cbl:L555} does the same, and
     * {@code app/cbl/COCRDUPC.cbl:L1239} and {@code L1240} reset the account and card identifier
     * colours together. Getting this reset wrong leaves a field stuck red after the operator has
     * corrected it, which is precisely the kind of divergence the parity gate is meant to catch.
     */
    public static final byte DFHDFCOL = (byte) 0x00;

    /**
     * Blue: {@code 0xF1}, EBCDIC {@code '1'}, 3270 colour code 1.
     *
     * <p>Zero occurrences in these programs. Numerically equal to {@link #DFHBMASF} and
     * {@link #DFHBLINK} in the other two planes.
     */
    public static final byte DFHBLUE = (byte) 0xF1;

    /**
     * Red: {@code 0xF2}, EBCDIC {@code '2'}, 3270 colour code 2.
     *
     * <p><strong>The single most used attribute mnemonic in the entire codebase: 32 occurrences</strong>
     * - 31 across {@code COACTUPC}, {@code COACTVWC}, {@code COCRDLIC}, {@code COCRDSLC},
     * {@code COCRDUPC} and {@code COUSR02C}, plus one inside {@code app/cpy/CSSETATY.cpy} itself.
     *
     * <p>Red is this application's universal signal that a field failed validation. The
     * {@code CSSETATY} copybook is the generic form of the rule - if a field is flagged not-valid or
     * blank <em>and</em> the program is being re-entered, move {@code DFHRED} into that field's colour
     * item, and additionally move an asterisk into its data item when the field is blank. Hand-written
     * equivalents appear where the rule differs slightly, for example
     * {@code app/cbl/COACTUPC.cbl:L3177} and {@code L3183}, {@code app/cbl/COCRDLIC.cbl:L756},
     * {@code L769}, {@code L781}, {@code L793}, {@code L804}, {@code L816}, {@code L827},
     * {@code L873} and {@code L878}, and {@code app/cbl/COUSR02C.cbl:L241}.
     *
     * <p>The decision logic that chooses when to apply it belongs to the sibling field-attribute
     * helper, not to this class; only the byte lives here.
     */
    public static final byte DFHRED = (byte) 0xF2;

    /**
     * Pink: {@code 0xF3}, EBCDIC {@code '3'}, 3270 colour code 3.
     *
     * <p>Zero occurrences in these programs.
     */
    public static final byte DFHPINK = (byte) 0xF3;

    /**
     * Green: {@code 0xF4}, EBCDIC {@code '4'}, 3270 colour code 4.
     *
     * <p>The success signal, and the exact counterpart of {@link #DFHRED}. <strong>Eight
     * occurrences</strong>, one in each of {@code COADM01C}, {@code COBIL00C}, {@code COMEN01C},
     * {@code CORPT00C}, {@code COTRN02C}, {@code COUSR01C}, {@code COUSR02C} and {@code COUSR03C} -
     * every program that completes a unit of work and confirms it. {@code app/cbl/COUSR01C.cbl:L254}
     * is representative: on a normal response from the write, the message colour goes green and the
     * text becomes a confirmation naming the user just added. See also
     * {@code app/cbl/COUSR02C.cbl:L371} and {@code app/cbl/COUSR03C.cbl:L317}.
     */
    public static final byte DFHGREEN = (byte) 0xF4;

    /**
     * Turquoise: {@code 0xF5}, EBCDIC {@code '5'}, 3270 colour code 5.
     *
     * <p>Zero occurrences in these programs.
     */
    public static final byte DFHTURQ = (byte) 0xF5;

    /**
     * Yellow: {@code 0xF6}, EBCDIC {@code '6'}, 3270 colour code 6.
     *
     * <p>Zero occurrences in these programs.
     */
    public static final byte DFHYELLO = (byte) 0xF6;

    /**
     * Neutral: {@code 0xF7}, EBCDIC {@code '7'}, 3270 colour code 7. White on a display, black on a
     * printer.
     *
     * <p>The informational colour - neither an error nor a success, just text the operator should read.
     * <strong>Five occurrences</strong>, in {@code COACTVWC}, {@code COCRDLIC}, {@code COCRDSLC},
     * {@code COUSR02C} and {@code COUSR03C}. The clearest illustration is the pair in
     * {@code COCRDLIC}: {@code app/cbl/COCRDLIC.cbl:L671} darkens the information line with
     * {@link #DFHBMDAR} while there is no message, and {@code L929} switches it to {@code DFHNEUTR}
     * once there is one and no "no records found" condition is in force.
     * {@code app/cbl/COACTVWC.cbl:L568} and {@code L570} are the same two-state pattern, as are
     * {@code app/cbl/COUSR02C.cbl:L338} and {@code app/cbl/COUSR03C.cbl:L285}.
     */
    public static final byte DFHNEUTR = (byte) 0xF7;

    // =================================================================================================
    // SECTION 3 - DFHBMSCA / DFHATTR: EXTENDED HIGHLIGHTING VALUES
    //
    // The third plane. 0x00 selects no extended highlighting, 0xF1 blink, 0xF2 reverse video and 0xF4
    // underscore. Only one highlight may be in force at a time, so these are alternatives rather than
    // flags to be combined - 0xF3 does not mean "blink and reverse", it is simply invalid. The four
    // values are pairwise distinct within this plane.
    //
    // No program in this codebase sets extended highlighting: the CardDemo screens signal state through
    // colour and intensity alone. The plane is reproduced so the class is a faithful rendering of the
    // copybooks it stands in for, and every member is marked as having no consumer here.
    // =================================================================================================

    /**
     * Default highlighting, that is none: {@code 0x00}. The copybook calls this "normal".
     *
     * <p>Zero occurrences in these programs. Numerically equal to {@link #DFHDFCOL} in the colour
     * plane; both encode "use the default", which is the same architected {@code 0x00} in either plane.
     */
    public static final byte DFHDFHI = (byte) 0x00;

    /**
     * Blink: {@code 0xF1}, EBCDIC {@code '1'}.
     *
     * <p>Zero occurrences in these programs.
     */
    public static final byte DFHBLINK = (byte) 0xF1;

    /**
     * Reverse video: {@code 0xF2}, EBCDIC {@code '2'}.
     *
     * <p>Zero occurrences in these programs.
     */
    public static final byte DFHREVRS = (byte) 0xF2;

    /**
     * Underscore: {@code 0xF4}, EBCDIC {@code '4'}.
     *
     * <p>Zero occurrences in these programs.
     */
    public static final byte DFHUNDLN = (byte) 0xF4;

    // =================================================================================================
    // BIT MASKS FOR THE BASIC FIELD ATTRIBUTE BYTE (Section 1 only)
    //
    // Named so the predicates below read as the hardware specification rather than as magic numbers.
    // Bits are numbered 0-7 from the most significant, matching IBM's presentation.
    // =================================================================================================

    /** Bit 2 of a basic field attribute byte: the field is protected against keyboard entry. */
    private static final int MASK_PROTECTED = 0x20;

    /** Bit 3 of a basic field attribute byte: the field is numeric. */
    private static final int MASK_NUMERIC = 0x10;

    /** Bits 4-5 of a basic field attribute byte: the display and detectability selector. */
    private static final int MASK_DISPLAY = 0x0C;

    /** Bits 4-5 set to binary 10: intensified and selector-pen detectable. */
    private static final int DISPLAY_INTENSIFIED = 0x08;

    /** Bits 4-5 set to binary 11: non-display, non-print and non-detectable. */
    private static final int DISPLAY_NON_DISPLAY = 0x0C;

    /** Bit 7 of a basic field attribute byte: the modified data tag. */
    private static final int MASK_MODIFIED_DATA_TAG = 0x01;

    // =================================================================================================
    // DIAGNOSTIC LOOKUPS
    //
    // Byte-to-mnemonic maps, one per plane, so a failing parity comparison can report "DFHRED" instead
    // of "-14". Built with Map.ofEntries, which yields an unmodifiable map and additionally rejects a
    // duplicate key at class-initialisation time - a free guard against two constants in one plane
    // being given the same value by mistake. Deliberately three separate maps and not one merged map:
    // the planes share byte values, so a merged map could not be built at all.
    // =================================================================================================

    /**
     * Every Section 1 basic field attribute byte, mapped to its copybook mnemonic. Unmodifiable.
     *
     * @see #fieldAttributeMnemonic(byte)
     */
    public static final Map<Byte, String> FIELD_ATTRIBUTE_MNEMONICS = Map.ofEntries(
            Map.entry(DFHBMUNP, "DFHBMUNP"),
            Map.entry(DFHBMFSE, "DFHBMFSE"),
            Map.entry(DFHBMBRY, "DFHBMBRY"),
            Map.entry(DFHUNIMD, "DFHUNIMD"),
            Map.entry(DFHBMDAR, "DFHBMDAR"),
            Map.entry(DFHUNNOD, "DFHUNNOD"),
            Map.entry(DFHBMUNN, "DFHBMUNN"),
            Map.entry(DFHUNNUM, "DFHUNNUM"),
            Map.entry(DFHUNNUB, "DFHUNNUB"),
            Map.entry(DFHUNINT, "DFHUNINT"),
            Map.entry(DFHUNNON, "DFHUNNON"),
            Map.entry(DFHBMPRO, "DFHBMPRO"),
            Map.entry(DFHBMPRF, "DFHBMPRF"),
            Map.entry(DFHPROTI, "DFHPROTI"),
            Map.entry(DFHPROTN, "DFHPROTN"),
            Map.entry(DFHBMASK, "DFHBMASK"),
            Map.entry(DFHBMASF, "DFHBMASF"),
            Map.entry(DFHBMASB, "DFHBMASB"));

    /**
     * Every Section 2 extended colour value, mapped to its copybook mnemonic. Unmodifiable.
     *
     * @see #colourMnemonic(byte)
     */
    public static final Map<Byte, String> COLOUR_MNEMONICS = Map.ofEntries(
            Map.entry(DFHDFCOL, "DFHDFCOL"),
            Map.entry(DFHBLUE, "DFHBLUE"),
            Map.entry(DFHRED, "DFHRED"),
            Map.entry(DFHPINK, "DFHPINK"),
            Map.entry(DFHGREEN, "DFHGREEN"),
            Map.entry(DFHTURQ, "DFHTURQ"),
            Map.entry(DFHYELLO, "DFHYELLO"),
            Map.entry(DFHNEUTR, "DFHNEUTR"));

    /**
     * Every Section 3 extended highlighting value, mapped to its copybook mnemonic. Unmodifiable.
     *
     * @see #highlightMnemonic(byte)
     */
    public static final Map<Byte, String> HIGHLIGHT_MNEMONICS = Map.ofEntries(
            Map.entry(DFHDFHI, "DFHDFHI"),
            Map.entry(DFHBLINK, "DFHBLINK"),
            Map.entry(DFHREVRS, "DFHREVRS"),
            Map.entry(DFHUNDLN, "DFHUNDLN"));

    /**
     * Reads an attribute byte as an unsigned 0-255 integer.
     *
     * <p>Java's {@code byte} is signed, so most of the Section 1 constants - every value above
     * {@code 0x7F} - print as negative numbers and sort in a counter-intuitive order. Nothing is wrong
     * with the stored value; only its interpretation. Use this wherever an attribute byte is logged,
     * formatted, compared against a documented hexadecimal code, or asserted to lie in the 0-255 range.
     * Comparing two of these constants directly needs no conversion, because both sides are skewed
     * identically.
     *
     * @param attribute any attribute, colour or highlighting byte
     * @return the same bit pattern as an integer in the range 0 through 255 inclusive
     */
    public static int unsigned(final byte attribute) {
        return attribute & 0xFF;
    }

    /**
     * Renders an attribute byte as the EBCDIC hexadecimal notation the copybooks and IBM documentation
     * use, for example {@code X'C1'}.
     *
     * <p>Formatted with {@link java.util.Locale#ROOT} so the text is identical on every host: digit
     * shaping and case conversion are locale-sensitive in Java, and a diagnostic string that varies by
     * machine is worthless when a parity diff has to be reproduced elsewhere.
     *
     * @param attribute any attribute, colour or highlighting byte
     * @return the byte in {@code X'hh'} form, always two upper-case hexadecimal digits
     */
    public static String toHex(final byte attribute) {
        return String.format(java.util.Locale.ROOT, "X'%02X'", unsigned(attribute));
    }

    /**
     * Names a Section 1 basic field attribute byte.
     *
     * @param attribute the byte to identify
     * @return the copybook mnemonic when the byte is one this class defines, otherwise the byte in
     *         {@code X'hh'} form. An unrecognised byte is a legitimate outcome and not an error: the
     *         3270 architecture permits combinations {@code DFHBMSCA} never named, and a diagnostic
     *         helper must describe them rather than reject them.
     */
    public static String fieldAttributeMnemonic(final byte attribute) {
        final String mnemonic = FIELD_ATTRIBUTE_MNEMONICS.get(attribute);
        return mnemonic != null ? mnemonic : toHex(attribute);
    }

    /**
     * Names a Section 2 extended colour value.
     *
     * @param colour the byte to identify
     * @return the copybook mnemonic when the byte is one this class defines, otherwise the byte in
     *         {@code X'hh'} form
     */
    public static String colourMnemonic(final byte colour) {
        final String mnemonic = COLOUR_MNEMONICS.get(colour);
        return mnemonic != null ? mnemonic : toHex(colour);
    }

    /**
     * Names a Section 3 extended highlighting value.
     *
     * @param highlight the byte to identify
     * @return the copybook mnemonic when the byte is one this class defines, otherwise the byte in
     *         {@code X'hh'} form
     */
    public static String highlightMnemonic(final byte highlight) {
        final String mnemonic = HIGHLIGHT_MNEMONICS.get(highlight);
        return mnemonic != null ? mnemonic : toHex(highlight);
    }

    /**
     * Whether a basic field attribute byte protects its field against keyboard entry - bit 2 set.
     *
     * <p>Applies to Section 1 bytes only. Feeding a colour or highlighting value in is meaningless,
     * because those planes do not carry protection at all.
     *
     * @param attribute a basic field attribute byte
     * @return {@code true} for the protected and autoskip combinations, {@code false} for unprotected
     */
    public static boolean isProtected(final byte attribute) {
        return (unsigned(attribute) & MASK_PROTECTED) != 0;
    }

    /**
     * Whether a basic field attribute byte marks its field numeric - bit 3 set.
     *
     * @param attribute a basic field attribute byte
     * @return {@code true} when the numeric bit is on
     */
    public static boolean isNumeric(final byte attribute) {
        return (unsigned(attribute) & MASK_NUMERIC) != 0;
    }

    /**
     * Whether a basic field attribute byte makes its field autoskip - protected and numeric together.
     *
     * <p>Autoskip is strictly stronger than protection: the cursor jumps past the field instead of
     * stopping inside it. {@link #DFHBMASK}, {@link #DFHBMASF} and {@link #DFHBMASB} satisfy this;
     * {@link #DFHBMPRO} and {@link #DFHBMPRF} are protected but not autoskip.
     *
     * @param attribute a basic field attribute byte
     * @return {@code true} only when both the protected and numeric bits are on
     */
    public static boolean isAutoskip(final byte attribute) {
        return isProtected(attribute) && isNumeric(attribute);
    }

    /**
     * Whether a basic field attribute byte requests high intensity - bits 4-5 set to binary 10.
     *
     * <p>Tested as a two-bit field rather than a single bit, because bits 4-5 form one selector with
     * four states. Binary 11 is non-display, not "even brighter", so a naive single-bit test would
     * report a hidden field as intensified.
     *
     * @param attribute a basic field attribute byte
     * @return {@code true} for {@link #DFHBMBRY}, {@link #DFHUNIMD}, {@link #DFHUNNUB},
     *         {@link #DFHUNINT}, {@link #DFHPROTI} and {@link #DFHBMASB}
     */
    public static boolean isIntensified(final byte attribute) {
        return (unsigned(attribute) & MASK_DISPLAY) == DISPLAY_INTENSIFIED;
    }

    /**
     * Whether a basic field attribute byte suppresses display - bits 4-5 set to binary 11.
     *
     * @param attribute a basic field attribute byte
     * @return {@code true} for {@link #DFHBMDAR}, {@link #DFHUNNOD}, {@link #DFHUNNON} and
     *         {@link #DFHPROTN}
     */
    public static boolean isNonDisplay(final byte attribute) {
        return (unsigned(attribute) & MASK_DISPLAY) == DISPLAY_NON_DISPLAY;
    }

    /**
     * Whether a basic field attribute byte has the modified data tag pre-set - bit 7 set.
     *
     * <p>This is the bit that decides whether CICS returns the field on the next input even though the
     * operator never touched it, which is how these programs redisplay a screen without losing the
     * values already on it. It is the only difference between {@link #DFHBMPRO} and
     * {@link #DFHBMPRF}, and between {@link #DFHBMUNP} and {@link #DFHBMFSE}.
     *
     * @param attribute a basic field attribute byte
     * @return {@code true} when the modified data tag bit is on
     */
    public static boolean isModifiedDataTagSet(final byte attribute) {
        return (unsigned(attribute) & MASK_MODIFIED_DATA_TAG) != 0;
    }

    /**
     * Not instantiable: this type is a namespace for constants, never an object.
     *
     * @throws AssertionError always, so reflective instantiation fails loudly rather than silently
     *                        producing a useless instance
     */
    private BmsAttributes() {
        throw new AssertionError("BmsAttributes is a constant holder and must not be instantiated");
    }
}
