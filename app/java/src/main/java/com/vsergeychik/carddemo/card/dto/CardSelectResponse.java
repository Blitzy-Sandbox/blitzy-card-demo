package com.vsergeychik.carddemo.card.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.vsergeychik.carddemo.common.DiagnosticText;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.SensitiveDiagnostics;
import com.vsergeychik.carddemo.common.DateHeader;
import com.vsergeychik.carddemo.common.FieldAttributeSetter;
import com.vsergeychik.carddemo.common.FieldAttributeSetter.FieldHighlight;
import com.vsergeychik.carddemo.common.FieldAttributeSetter.FieldValidationState;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.card.dto.CardSelectRequest.ThisProgCommarea;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.ScreenMetadata;
import com.vsergeychik.carddemo.common.ScreenTitles;
import com.vsergeychik.carddemo.common.SystemMessages;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The outbound payload of the card-detail screen: a field-for-field projection of the
 * <strong>output</strong> half of the {@code COCRDSL} BMS symbolic map.
 *
 * <h2>What this type stands for</h2>
 * <table border="1">
 *   <caption>Provenance</caption>
 *   <tr><td>REST endpoint</td><td>{@code GET /api/cards/{cardNum}}</td></tr>
 *   <tr><td>CICS transaction</td><td>{@code CCDL}, "CREDIT CARD DETAIL"</td></tr>
 *   <tr><td>Backing program</td><td>{@code app/cbl/COCRDSLC.cbl}, 887 lines</td></tr>
 *   <tr><td>Symbolic map</td><td>{@code app/cpy-bms/COCRDSL.CPY}, 200 lines</td></tr>
 *   <tr><td>Mapset</td><td>{@code app/bms/COCRDSL.bms}, 157 lines</td></tr>
 *   <tr><td>Map name</td><td>{@code CCRDSLA}, seven characters</td></tr>
 *   <tr><td>Output group</td><td>{@code 01 CCRDSLAO REDEFINES CCRDSLAI}, {@code COCRDSL.CPY:L109}</td></tr>
 * </table>
 *
 * <h2>The projection rule, and why it is binding</h2>
 * There is no design system and no component library anywhere in this repository, and there are no
 * Figma attachments. The authoritative presentation contract is therefore the BMS layer itself, and
 * it binds with the same force a design system would: <strong>every member of this payload traces to
 * a name-labelled {@code DFHMDF} definition in {@code app/bms/COCRDSL.bms}, and every width traces to
 * an {@code xxxO} {@code PICTURE} clause in {@code app/cpy-bms/COCRDSL.CPY}.</strong> Nothing else may
 * appear here.
 *
 * <p>{@code app/bms/COCRDSL.bms} declares <strong>31</strong> {@code DFHMDF} fields of which exactly
 * <strong>15 carry a name</strong>. The other 16 are unnamed screen furniture - the {@code INITIAL}
 * literals {@code 'Tran:'}, {@code 'Date:'}, {@code 'Prog:'}, {@code 'Time:'},
 * {@code 'View Credit Card Detail'}, {@code 'Account Number    :'}, {@code 'Card Number       :'},
 * {@code 'Name on card      :'}, {@code 'Card Active Y/N   : '}, {@code 'Expiry Date       : '} and
 * the {@code '/'} between the two expiry items, plus five {@code LENGTH=0} field stoppers. An unnamed
 * {@code DFHMDF} produces no symbolic-map item, so it gets no Java member.
 *
 * <h2>Output items only - {@code xxxO}, never {@code xxxI}</h2>
 * The symbolic map declares the same 504 bytes twice. {@code 01 CCRDSLAI} at
 * {@code COCRDSL.CPY:L17} is the input view a {@code RECEIVE MAP} fills
 * ({@code app/cbl/COCRDSLC.cbl:599}); {@code 01 CCRDSLAO REDEFINES CCRDSLAI} at
 * {@code COCRDSL.CPY:L109} is the output view a {@code SEND MAP} transmits
 * ({@code app/cbl/COCRDSLC.cbl:571}). This class projects the <strong>output</strong> view, so its
 * members come from the {@code xxxO} items and from nowhere else; the {@code xxxI} items belong to the
 * inbound sibling. Mixing the two halves is what would make a 441-field presentation contract
 * meaningless, so the rule is absolute.
 *
 * <h2>How the two views overlay, byte for byte</h2>
 * Both groups open with {@code 02 FILLER PIC X(12)}, the {@code TIOAPFX=YES} prefix
 * ({@code COCRDSL.CPY:L18} and {@code L110}). Thereafter each screen field occupies the same
 * {@code 7 + n} bytes in both views, which is exactly why the {@code REDEFINES} is legal:
 *
 * <table border="1">
 *   <caption>Per-field stride, verified item by item against the copybook</caption>
 *   <tr><th>Offset within the field</th><th>Input view ({@code CCRDSLAI})</th><th>Output view ({@code CCRDSLAO})</th></tr>
 *   <tr><td>+0 .. +1</td><td>{@code xxxL COMP PIC S9(4)} - the length CICS reports</td><td rowspan="2">{@code FILLER PICTURE X(3)}</td></tr>
 *   <tr><td>+2</td><td>{@code xxxF PICTURE X}, redefined as {@code xxxA} - the flag and attribute byte</td></tr>
 *   <tr><td>+3</td><td rowspan="4">{@code FILLER PICTURE X(4)}</td><td>{@code xxxC PICTURE X} - COLOR</td></tr>
 *   <tr><td>+4</td><td>{@code xxxP PICTURE X} - PS, the programmed symbol set</td></tr>
 *   <tr><td>+5</td><td>{@code xxxH PICTURE X} - HILIGHT</td></tr>
 *   <tr><td>+6</td><td>{@code xxxV PICTURE X} - VALIDN</td></tr>
 *   <tr><td>+7 .. +6+n</td><td>{@code xxxI PIC X(n)} - the inbound value</td><td>{@code xxxO PIC X(n)} - the outbound value</td></tr>
 * </table>
 *
 * <p>So the output view's three-byte {@code FILLER} overlays the input view's {@code xxxL} plus
 * {@code xxxF}, and the output view's {@code xxxC}/{@code xxxP}/{@code xxxH}/{@code xxxV} quad overlays
 * the input view's {@code FILLER X(4)}. The quad's byte order is not arbitrary: it is the
 * {@code DSATTS}/{@code MAPATTS=(COLOR,HILIGHT,PS,VALIDN)} set declared on the {@code DFHMDI}, laid out
 * as <strong>C = COLOR, P = PS, H = HILIGHT, V = VALIDN</strong>.
 *
 * <h2>The verified mapset attribute form</h2>
 * Read directly from {@code app/bms/COCRDSL.bms}, because the summary elsewhere in the specification
 * generalises across all 17 mapsets and this one is not typical:
 * <ul>
 *   <li>{@code COCRDSL DFHMSD LANG=COBOL, MODE=INOUT, STORAGE=AUTO, TIOAPFX=YES, TYPE=&&SYSPARM}
 *       ({@code L20-L24}) - it declares <strong>no {@code CTRL=}</strong> and
 *       <strong>no {@code EXTATT=}</strong>.</li>
 *   <li>{@code CCRDSLA DFHMDI CTRL=(FREEKB), DSATTS=(COLOR,HILIGHT,PS,VALIDN),
 *       MAPATTS=(COLOR,HILIGHT,PS,VALIDN), SIZE=(24,80)} ({@code L25-L28}) - the {@code DFHMDI}
 *       carries them, and {@code SIZE=(24,80)} is confirmed.</li>
 * </ul>
 *
 * <h2>The attribute quad is metadata, never a JSON member</h2>
 * The 60 attribute items exist because {@code COCRDSLC} genuinely writes them. Its
 * {@code 1300-SETUP-SCREEN-ATTRS} paragraph moves {@code DFHDFCOL} into {@code ACCTSIDC} and
 * {@code CARDSIDC} ({@code COCRDSLC.cbl:529-530}), {@code DFHRED} into either on a failed edit
 * ({@code L534}, {@code L538}), and {@code DFHBMDAR} or {@code DFHNEUTR} into {@code INFOMSGC}
 * ({@code L554}, {@code L556}). They are reachable here through {@link #attributes(ScreenField)} and
 * are annotated out of the JSON wire, so the serialised payload stays exactly the 15 data members plus
 * the navigation carriers.
 *
 * <p>The error highlight itself is {@code app/cpy/CSSETATY.cpy:L18-L27}, which {@code COCRDSLC} spells
 * out inline at {@code L542-L551}: {@code DFHRED} goes to the offending field's <strong>{@code xxxC}
 * item</strong> and, only when that field is blank, {@code '*'} goes to its <strong>{@code xxxO}
 * item</strong> - and both only when {@code CDEMO-PGM-REENTER} holds. That decision belongs to
 * {@link FieldAttributeSetter}; applying it belongs here, and
 * {@link #applyHighlight(ScreenField, FieldHighlight)} performs exactly those two moves and no others.
 *
 * <h2>Navigation replaces {@code XCTL}; state travels in the payload</h2>
 * {@code app/cbl/COCRDSLC.cbl:331} issues {@code EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM)
 * COMMAREA(CARDDEMO-COMMAREA)}. There is no Java equivalent of a program transfer that keeps the
 * screen conversation alive, so the transfer becomes three response members - {@link #getNextProgram()},
 * {@link #getNextMapset()} and {@link #getNextMap()} - and the client issues the follow-up call. There
 * is no server-side forward, no redirect chain and no session affinity.
 *
 * <p>For the same reason the conversation state travels in the payload and never on the server: this
 * response carries a {@link CardScreenState} ({@code COCRDSLC} copies {@code CVCRD01Y} at
 * {@code L194}) and a {@link NavigationContext} ({@code COCOM01Y} at {@code L198}), and the client
 * sends them back on the next call. This class holds no {@code HttpSession}, no session attribute, no
 * cache, no static mutable field and no {@code ThreadLocal}.
 *
 * <h2>A preserved source defect - do not "fix" it downstream</h2>
 * {@code COCRDSLC} declares {@code LIT-CCLISTMAP PIC X(7) VALUE 'CCRDSLA'} at
 * {@code app/cbl/COCRDSLC.cbl:177-178}, although the card-list map is really {@code 'CCRDLIA'}; the
 * neighbouring {@code LIT-CCLISTMAPSET} is correctly {@code 'COCRDLI'}. That defect is part of the
 * observable behaviour and is preserved. Consequently the three navigation members are
 * <strong>opaque tokens</strong>: they are stored at their declared width and are never validated
 * against a list of known programs, never case-normalised and never trimmed.
 *
 * <p>A second width subtlety in the same program: {@code LIT-THISMAPSET} is declared
 * {@code PIC X(8) VALUE 'COCRDSL '} ({@code L167-L168}) and is moved into
 * {@code CCARD-NEXT-MAPSET PIC X(7)} ({@code L565}, {@code L589}). A {@code PIC X} receiver truncates
 * on the <em>right</em>, so the surviving value is {@code "COCRDSL"}. A plain Java assignment would
 * have produced an eight-character mapset and the defect would have been invisible, which is why every
 * store on this class routes through {@link FixedWidthCodec#movePicX(String, int)}.
 *
 * <h2>A documented conflict in the shared map pair</h2>
 * The two programs that reference this mapset do not agree, and the disagreement is recorded rather
 * than resolved:
 * <ul>
 *   <li>{@code app/cbl/COCRDSLC.cbl:215} reads {@code COPY COCRDSL.} - <strong>live</strong>.
 *       {@code COCRDSLC} owns and populates this map.</li>
 *   <li>{@code app/cbl/COCRDLIC.cbl:274} reads {@code *COPY COCRDSL.} - <strong>commented out</strong> -
 *       while {@code COPY COCRDLI.} at {@code L276} is live. {@code COCRDLIC} therefore has no
 *       {@code COCRDSL} data area at all and never populates one, even though the card-select payload
 *       remains the card-list controller's declared <em>navigation target</em>.</li>
 * </ul>
 * This class serves both callers without either needing the other's behaviour: it carries no
 * {@code COCRDLIC}-specific member, and the commented-out {@code COPY} is not "restored".
 *
 * <h2>Deliberate absences</h2>
 * <ul>
 *   <li><strong>No {@code EXPDAY}.</strong> Only {@code COCRDUP} declares an expiry-day item.</li>
 *   <li><strong>No {@code PAGENO}.</strong> Only {@code COCRDLI} declares a page-number item.</li>
 *   <li><strong>No unified message widths.</strong> {@code INFOMSG} and {@code ERRMSG} are 40 and 80
 *       here but 45 and 78 in {@code COCRDLI}; {@code FKEYS} is 75 here, 21 in {@code COCRDUP} and
 *       absent from {@code COCRDLI}. The divergence <em>is</em> the contract, so there is no shared
 *       abstract base and no cross-map width constant.</li>
 *   <li><strong>No declarative size validation.</strong> Every store already produces exactly the
 *       declared width through the {@code PIC X} move rule, so a {@code max} constraint could never
 *       fire. {@code COCRDSLC} owns its own edits and their message ordering, and none of them is
 *       reproduced here.</li>
 *   <li><strong>No masking.</strong> {@link #getCardsido()} carries a full 16-digit card number and
 *       {@link #getAcctsido()} an account identifier in the clear, exactly as the symbolic map does.
 *       Redacting either would change observable output; no new exposure is added either.</li>
 * </ul>
 *
 * <h2>Mutability and thread safety</h2>
 * A mutable class rather than a {@code record}, because the highlight rule writes into the colour item
 * after the payload has been assembled. Instances are <strong>not</strong> thread safe and are meant to
 * be confined to the request that builds them, exactly as a CICS symbolic map is confined to one task.
 * There is no static mutable state: the only static reference is an immutable codec.
 *
 * @see CardScreenState
 * @see NavigationContext
 * @see FieldAttributeSetter
 */
public final class CardSelectResponse {

    // =================================================================================================
    // Screen identity. Literal values transcribed from app/cbl/COCRDSLC.cbl's WS-LITERALS group, so the
    // program, transaction, mapset and map names on this payload are the ones the COBOL actually sends.
    // =================================================================================================

    /** {@code LIT-THISPGM PIC X(8) VALUE 'COCRDSLC'}, {@code app/cbl/COCRDSLC.cbl:163-164}. */
    public static final String THIS_PROGRAM = "COCRDSLC";

    /** {@code LIT-THISTRANID PIC X(4) VALUE 'CCDL'}, {@code app/cbl/COCRDSLC.cbl:165-166}. */
    public static final String THIS_TRANID = "CCDL";

    /**
     * {@code LIT-THISMAPSET PIC X(8) VALUE 'COCRDSL '}, {@code app/cbl/COCRDSLC.cbl:167-168}, as it
     * arrives in {@code CCARD-NEXT-MAPSET PIC X(7)}: right-truncated to seven characters.
     */
    public static final String THIS_MAPSET = "COCRDSL";

    /**
     * {@code LIT-THISMAP PIC X(7) VALUE 'CCRDSLA'}, {@code app/cbl/COCRDSLC.cbl:169-170}. This is also
     * the {@code (MAPNAME3)} token {@code CSSETATY} qualifies its two moves with, which is why
     * {@link #applyHighlight(ScreenField, FieldValidationState, boolean)} passes it to
     * {@link FieldAttributeSetter}.
     */
    public static final String MAP_NAME = "CCRDSLA";

    // =================================================================================================
    // Group geometry. Every number below is either read from a PICTURE clause or derived from ones that
    // were, and GROUP_GEOMETRY proves at class-initialisation time that they tile the group exactly.
    // =================================================================================================

    /** The number of name-labelled {@code DFHMDF} fields, and therefore of data members: 15 of 31. */
    public static final int FIELD_COUNT = 15;

    /** {@code 02 FILLER PIC X(12)} at {@code COCRDSL.CPY:L110} - the {@code TIOAPFX=YES} prefix. */
    public static final int TIOAPFX_LENGTH = 12;

    /**
     * {@code 02 FILLER PICTURE X(3)} - the output view's per-field filler, overlaying the input view's
     * {@code xxxL COMP PIC S9(4)} plus {@code xxxF PICTURE X}.
     */
    public static final int FILLER_LENGTH = 3;

    /** Each of {@code xxxC}, {@code xxxP}, {@code xxxH} and {@code xxxV} is {@code PICTURE X}: one byte. */
    public static final int ATTRIBUTE_ITEM_LENGTH = 1;

    /** {@code COLOR}, {@code PS}, {@code HILIGHT}, {@code VALIDN} - the four {@code DSATTS} items. */
    public static final int ATTRIBUTE_ITEMS_PER_FIELD = 4;

    /**
     * The bytes preceding every {@code xxxO} item within its field: {@value #FILLER_LENGTH} of filler
     * plus {@value #ATTRIBUTE_ITEMS_PER_FIELD} attribute items, so 7. The input view's
     * {@code 2 + 1 + 4} is the same 7, which is what makes the {@code REDEFINES} legal.
     */
    public static final int FIELD_PREFIX_LENGTH =
            FILLER_LENGTH + (ATTRIBUTE_ITEMS_PER_FIELD * ATTRIBUTE_ITEM_LENGTH);

    // -------------------------------------------------------------------------------------------------
    // The fifteen declared widths, each read from its own xxxO PICTURE clause in app/cpy-bms/COCRDSL.CPY
    // and named so no caller ever writes the number itself (practice B8). These widths belong to THIS
    // map only - see the "Deliberate absences" note about COCRDLI and COCRDUP.
    // -------------------------------------------------------------------------------------------------

    /** {@code 02 TRNNAMEO PIC X(4)}, {@code COCRDSL.CPY:L116}. */
    public static final int TRNNAMEO_LENGTH = 4;

    /** {@code 02 TITLE01O PIC X(40)}, {@code COCRDSL.CPY:L122}. */
    public static final int TITLE01O_LENGTH = 40;

    /** {@code 02 CURDATEO PIC X(8)}, {@code COCRDSL.CPY:L128}. */
    public static final int CURDATEO_LENGTH = 8;

    /** {@code 02 PGMNAMEO PIC X(8)}, {@code COCRDSL.CPY:L134}. */
    public static final int PGMNAMEO_LENGTH = 8;

    /** {@code 02 TITLE02O PIC X(40)}, {@code COCRDSL.CPY:L140}. */
    public static final int TITLE02O_LENGTH = 40;

    /** {@code 02 CURTIMEO PIC X(8)}, {@code COCRDSL.CPY:L146}. */
    public static final int CURTIMEO_LENGTH = 8;

    /** {@code 02 ACCTSIDO PIC X(11)}, {@code COCRDSL.CPY:L152}. */
    public static final int ACCTSIDO_LENGTH = 11;

    /** {@code 02 CARDSIDO PIC X(16)}, {@code COCRDSL.CPY:L158}. */
    public static final int CARDSIDO_LENGTH = 16;

    /** {@code 02 CRDNAMEO PIC X(50)}, {@code COCRDSL.CPY:L164}. */
    public static final int CRDNAMEO_LENGTH = 50;

    /** {@code 02 CRDSTCDO PIC X(1)}, {@code COCRDSL.CPY:L170}. */
    public static final int CRDSTCDO_LENGTH = 1;

    /** {@code 02 EXPMONO PIC X(2)}, {@code COCRDSL.CPY:L176}. */
    public static final int EXPMONO_LENGTH = 2;

    /** {@code 02 EXPYEARO PIC X(4)}, {@code COCRDSL.CPY:L182}. */
    public static final int EXPYEARO_LENGTH = 4;

    /**
     * {@code 02 INFOMSGO PIC X(40)}, {@code COCRDSL.CPY:L188}. Forty here; {@code COCRDLI} declares 45.
     */
    public static final int INFOMSGO_LENGTH = 40;

    /**
     * {@code 02 ERRMSGO PIC X(80)}, {@code COCRDSL.CPY:L194}. Eighty here; {@code COCRDLI} declares 78.
     */
    public static final int ERRMSGO_LENGTH = 80;

    /**
     * {@code 02 FKEYSO PIC X(75)}, {@code COCRDSL.CPY:L200}. Seventy-five here, 21 in {@code COCRDUP},
     * and {@code COCRDLI} has no such item at all.
     */
    public static final int FKEYSO_LENGTH = 75;

    /**
     * The total width of the fifteen {@code xxxO} data items: 4 + 40 + 8 + 8 + 40 + 8 + 11 + 16 + 50 +
     * 1 + 2 + 4 + 40 + 80 + 75 = <strong>387</strong>. Summed from the constants above rather than
     * written out, so the two can never disagree.
     */
    public static final int DATA_LENGTH =
            TRNNAMEO_LENGTH + TITLE01O_LENGTH + CURDATEO_LENGTH + PGMNAMEO_LENGTH + TITLE02O_LENGTH
            + CURTIMEO_LENGTH + ACCTSIDO_LENGTH + CARDSIDO_LENGTH + CRDNAMEO_LENGTH + CRDSTCDO_LENGTH
            + EXPMONO_LENGTH + EXPYEARO_LENGTH + INFOMSGO_LENGTH + ERRMSGO_LENGTH + FKEYSO_LENGTH;

    /**
     * The width of {@code 01 CCRDSLAO} in full:
     * {@value #TIOAPFX_LENGTH} + ({@value #FIELD_COUNT} x {@value #FIELD_PREFIX_LENGTH}) +
     * {@value #DATA_LENGTH} = 12 + 105 + 387 = <strong>504</strong> bytes.
     *
     * <p>Because {@code 01 CCRDSLAO REDEFINES CCRDSLAI} this is also the width of the input view, and
     * it is the length of the area {@code EXEC CICS SEND MAP ... FROM(CCRDSLAO)} transmits at
     * {@code app/cbl/COCRDSLC.cbl:571}. {@link #groupGeometry()} enumerates all 504 bytes and its
     * class-initialisation self-check fails loudly if they do not tile the group exactly - which is
     * what makes an omitted {@code FILLER} impossible to miss.
     */
    public static final int GROUP_LENGTH =
            TIOAPFX_LENGTH + (FIELD_COUNT * FIELD_PREFIX_LENGTH) + DATA_LENGTH;

    // -------------------------------------------------------------------------------------------------
    // Navigation carrier widths, taken from app/cpy/CVCRD01Y.cpy rather than restated, so the payload
    // and the work area cannot drift apart.
    // -------------------------------------------------------------------------------------------------

    /** {@code CCARD-NEXT-PROG PIC X(8)}, {@code app/cpy/CVCRD01Y.cpy:L21}. */
    public static final int NEXT_PROGRAM_LENGTH = CardScreenState.CCARD_NEXT_PROG_LENGTH;

    /**
     * {@code CCARD-NEXT-MAPSET PIC X(7)}, {@code app/cpy/CVCRD01Y.cpy:L23}. Seven, not eight: the
     * program's own {@code LIT-THISMAPSET} is {@code PIC X(8)} and is right-truncated on the way in.
     */
    public static final int NEXT_MAPSET_LENGTH = CardScreenState.CCARD_NEXT_MAPSET_LENGTH;

    /**
     * {@code CCARD-NEXT-MAP PIC X(7)}, {@code app/cpy/CVCRD01Y.cpy:L24}. Map names are seven characters
     * because the symbolic group is a seven-character map name plus an {@code I} or {@code O} suffix -
     * {@code CCRDSLA} yields {@code CCRDSLAI} and {@code CCRDSLAO}.
     */
    public static final int NEXT_MAP_LENGTH = CardScreenState.CCARD_NEXT_MAP_LENGTH;

    // -------------------------------------------------------------------------------------------------
    // The standard-message width, for the record. COCRDSLC copies CSMSG01Y at L221 and CSMSG02Y at L224,
    // so the PIC X(50) standard messages are in scope for this screen even though its own populate
    // paragraph moves its program-local WS-INFO-MSG PIC X(40) and WS-RETURN-MSG PIC X(75) instead
    // (COCRDSLC.cbl:494-496). Either standard message reaching a message item is a cross-width move, and
    // the two constants below state the direction, which is why both setters go through movePicX.
    // -------------------------------------------------------------------------------------------------

    /** {@code CCDA-MSG-THANK-YOU} and {@code CCDA-MSG-INVALID-KEY} are both {@code PIC X(50)}. */
    public static final int STANDARD_MESSAGE_LENGTH = SystemMessages.MESSAGE_LENGTH;

    /**
     * Characters a standard message loses on the right when it is moved into
     * {@code INFOMSGO PIC X(40)}: {@value #STANDARD_MESSAGE_LENGTH} - {@value #INFOMSGO_LENGTH} = 10.
     */
    public static final int INFOMSGO_STANDARD_MESSAGE_TRUNCATION =
            STANDARD_MESSAGE_LENGTH - INFOMSGO_LENGTH;

    /**
     * Spaces a standard message gains on the right when it is moved into {@code ERRMSGO PIC X(80)}:
     * {@value #ERRMSGO_LENGTH} - {@value #STANDARD_MESSAGE_LENGTH} = 30. The same widening applies to
     * the program's own {@code WS-RETURN-MSG PIC X(75)}, which gains 5.
     */
    public static final int ERRMSGO_STANDARD_MESSAGE_PADDING =
            ERRMSGO_LENGTH - STANDARD_MESSAGE_LENGTH;

    // =================================================================================================
    // The one static reference on this class. FixedWidthCodec is documented immutable and thread safe -
    // it holds nothing but a Charset - so a static final reference to one is a constant, not mutable
    // static state.
    //
    // Only movePicX(String, int) is used from it, and that operation is charset independent: it pads and
    // truncates characters and never encodes a byte. US-ASCII is named explicitly because the codec's
    // constructor demands a charset and a platform default is never acceptable in this system; the
    // choice cannot influence any value this class produces. Converting a rendered image to real bytes
    // belongs to the repository or codec layer, where the code page is a deliberate decision.
    // =================================================================================================

    /** The {@code PIC X} move rule: right-pad with spaces when short, truncate on the right when long. */
    private static final FixedWidthCodec PIC_X_CODEC = new FixedWidthCodec(StandardCharsets.US_ASCII);

    /**
     * The byte {@code MOVE LOW-VALUES TO CCRDSLAO} ({@code app/cbl/COCRDSLC.cbl:428}) leaves in every
     * attribute item before the program writes one: {@code 0x00} under either code page.
     *
     * <p>It is worth noting that this is the same byte as {@link BmsAttributes#DFHDFCOL} and
     * {@link BmsAttributes#DFHDFHI}, so "not yet written" and "default colour, default highlight" are
     * indistinguishable in the transmitted map - which is exactly the 3270 convention and the reason the
     * program can rely on a single {@code MOVE LOW-VALUES} for initialisation.
     */
    private static final byte LOW_VALUES_BYTE = 0x00;

    /**
     * The {@code FKEYS} legend as the mapset declares it, space-padded to the item's declared
     * {@value #FKEYSO_LENGTH} characters:
     * {@code DFHMDF ... LENGTH=75, POS=(24,1), INITIAL='ENTER=Search Cards  F3=Exit'} at
     * {@code app/bms/COCRDSL.bms:148-152}. The two spaces between {@code Cards} and {@code F3} are part
     * of the literal.
     *
     * <p>Exposed as a constant because {@code COCRDSLC} never writes {@code FKEYSO} - the literal is
     * what the terminal actually shows - while the item's own default on a fresh instance stays
     * {@code LOW-VALUES}, which is what the program leaves there after
     * {@code MOVE LOW-VALUES TO CCRDSLAO}. A caller reproducing the rendered screen assigns this
     * constant explicitly; nothing here assigns it implicitly, because the COBOL does not.
     */
    public static final String BMS_INITIAL_FKEYS =
            PIC_X_CODEC.movePicX("ENTER=Search Cards  F3=Exit", FKEYSO_LENGTH);

    // =================================================================================================
    // The fifteen screen fields as a closed vocabulary.
    //
    // This enum is what makes gate G9 mechanical rather than a matter of trust: each constant names its
    // DFHMDF label in app/bms/COCRDSL.bms, its xxxO item and PICTURE width in app/cpy-bms/COCRDSL.CPY,
    // and the line each was read from. It is also how FieldAttributeSetter's decision is addressed to a
    // field, and how the parity differ keys a field-by-field comparison.
    // =================================================================================================

    /**
     * One of the fifteen name-labelled {@code DFHMDF} fields of mapset {@code COCRDSL}, in copybook
     * declaration order.
     *
     * <p>Declaration order is significant and is not cosmetic: every offset accessor derives from
     * {@link #ordinal()} by summing the strides of the preceding constants, so the order here
     * <strong>is</strong> the copybook's order and reordering these constants would silently move every
     * span. The order below is the order of the {@code xxxO} declarations at {@code COCRDSL.CPY}
     * {@code L116}, {@code L122}, {@code L128}, {@code L134}, {@code L140}, {@code L146}, {@code L152},
     * {@code L158}, {@code L164}, {@code L170}, {@code L176}, {@code L182}, {@code L188}, {@code L194}
     * and {@code L200}.
     */
    public enum ScreenField {

        /**
         * {@code TRNNAME} - the transaction identifier, {@code app/bms/COCRDSL.bms:34}
         * ({@code ATTRB=(ASKIP,FSET,NORM) COLOR=BLUE POS=(1,7)}); {@code TRNNAMEO PIC X(4)},
         * {@code COCRDSL.CPY:L116}. Populated from {@code LIT-THISTRANID} at
         * {@code app/cbl/COCRDSLC.cbl:434}.
         */
        TRNNAME("TRNNAME", "TRNNAMEO", TRNNAMEO_LENGTH, 116, 34),

        /**
         * {@code TITLE01} - the upper heading, {@code app/bms/COCRDSL.bms:38}
         * ({@code ATTRB=(ASKIP,NORM) COLOR=YELLOW POS=(1,21)}); {@code TITLE01O PIC X(40)},
         * {@code COCRDSL.CPY:L122}. Populated from {@code CCDA-TITLE01} at
         * {@code app/cbl/COCRDSLC.cbl:432}.
         */
        TITLE01("TITLE01", "TITLE01O", TITLE01O_LENGTH, 122, 38),

        /**
         * {@code CURDATE} - the current date, {@code app/bms/COCRDSL.bms:47}
         * ({@code COLOR=BLUE POS=(1,71) INITIAL='mm/dd/yy'}); {@code CURDATEO PIC X(8)},
         * {@code COCRDSL.CPY:L128}. Populated from {@code WS-CURDATE-MM-DD-YY} at
         * {@code app/cbl/COCRDSLC.cbl:443}.
         */
        CURDATE("CURDATE", "CURDATEO", CURDATEO_LENGTH, 128, 47),

        /**
         * {@code PGMNAME} - the program name, {@code app/bms/COCRDSL.bms:57}
         * ({@code ATTRB=(ASKIP,NORM) COLOR=BLUE POS=(2,7)}); {@code PGMNAMEO PIC X(8)},
         * {@code COCRDSL.CPY:L134}. Populated from {@code LIT-THISPGM} at
         * {@code app/cbl/COCRDSLC.cbl:435}.
         */
        PGMNAME("PGMNAME", "PGMNAMEO", PGMNAMEO_LENGTH, 134, 57),

        /**
         * {@code TITLE02} - the lower heading, {@code app/bms/COCRDSL.bms:61}
         * ({@code ATTRB=(ASKIP,NORM) COLOR=YELLOW POS=(2,21)}); {@code TITLE02O PIC X(40)},
         * {@code COCRDSL.CPY:L140}. Populated from {@code CCDA-TITLE02} at
         * {@code app/cbl/COCRDSLC.cbl:433}.
         */
        TITLE02("TITLE02", "TITLE02O", TITLE02O_LENGTH, 140, 61),

        /**
         * {@code CURTIME} - the current time, {@code app/bms/COCRDSL.bms:70}
         * ({@code COLOR=BLUE POS=(2,71) INITIAL='hh:mm:ss'}); {@code CURTIMEO PIC X(8)},
         * {@code COCRDSL.CPY:L146}. Populated from {@code WS-CURTIME-HH-MM-SS} at
         * {@code app/cbl/COCRDSLC.cbl:449}.
         */
        CURTIME("CURTIME", "CURTIMEO", CURTIMEO_LENGTH, 146, 70),

        /**
         * {@code ACCTSID} - the account number the user searched on, {@code app/bms/COCRDSL.bms:84}
         * ({@code ATTRB=(FSET,IC,NORM,UNPROT) COLOR=DEFAULT HILIGHT=UNDERLINE POS=(7,45)});
         * {@code ACCTSIDO PIC X(11)}, {@code COCRDSL.CPY:L152}. Populated from {@code CC-ACCT-ID} at
         * {@code app/cbl/COCRDSLC.cbl:465}, or set to {@code LOW-VALUES} at {@code L463} when the
         * commarea carried no account. One of the two fields whose colour item the program writes and
         * whose {@code xxxO} item can receive the {@code '*'} blank marker
         * ({@code app/cbl/COCRDSLC.cbl:543-544}).
         */
        ACCTSID("ACCTSID", "ACCTSIDO", ACCTSIDO_LENGTH, 152, 84),

        /**
         * {@code CARDSID} - the card number the user searched on, {@code app/bms/COCRDSL.bms:96}
         * ({@code ATTRB=(FSET,NORM,UNPROT) COLOR=DEFAULT HILIGHT=UNDERLINE POS=(8,45)});
         * {@code CARDSIDO PIC X(16)}, {@code COCRDSL.CPY:L158}. Populated from {@code CC-CARD-NUM} at
         * {@code app/cbl/COCRDSLC.cbl:471}, or {@code LOW-VALUES} at {@code L469}. The second field
         * carrying the blank marker and the red colour ({@code app/cbl/COCRDSLC.cbl:549-550}).
         */
        CARDSID("CARDSID", "CARDSIDO", CARDSIDO_LENGTH, 158, 96),

        /**
         * {@code CRDNAME} - the embossed name, {@code app/bms/COCRDSL.bms:107}
         * ({@code HILIGHT=UNDERLINE POS=(11,25)}); {@code CRDNAMEO PIC X(50)},
         * {@code COCRDSL.CPY:L164}. Populated from {@code CARD-EMBOSSED-NAME} at
         * {@code app/cbl/COCRDSLC.cbl:476}, and only when a card was found.
         */
        CRDNAME("CRDNAME", "CRDNAMEO", CRDNAMEO_LENGTH, 164, 107),

        /**
         * {@code CRDSTCD} - the active status, {@code app/bms/COCRDSL.bms:116}
         * ({@code ATTRB=(ASKIP) HILIGHT=UNDERLINE POS=(13,25)}); {@code CRDSTCDO PIC X(1)},
         * {@code COCRDSL.CPY:L170}. Populated from {@code CARD-ACTIVE-STATUS} at
         * {@code app/cbl/COCRDSLC.cbl:484}.
         */
        CRDSTCD("CRDSTCD", "CRDSTCDO", CRDSTCDO_LENGTH, 170, 116),

        /**
         * {@code EXPMON} - the expiry month, {@code app/bms/COCRDSL.bms:126}
         * ({@code ATTRB=(ASKIP) HILIGHT=UNDERLINE POS=(15,25)}); {@code EXPMONO PIC X(2)},
         * {@code COCRDSL.CPY:L176}. Populated from {@code CARD-EXPIRY-MONTH} at
         * {@code app/cbl/COCRDSLC.cbl:480}.
         */
        EXPMON("EXPMON", "EXPMONO", EXPMONO_LENGTH, 176, 126),

        /**
         * {@code EXPYEAR} - the expiry year, {@code app/bms/COCRDSL.bms:133}
         * ({@code ATTRB=(ASKIP) HILIGHT=UNDERLINE POS=(15,30)}); {@code EXPYEARO PIC X(4)},
         * {@code COCRDSL.CPY:L182}. Populated from {@code CARD-EXPIRY-YEAR} at
         * {@code app/cbl/COCRDSLC.cbl:482}. There is deliberately no expiry-day companion: only
         * {@code COCRDUP} declares one.
         */
        EXPYEAR("EXPYEAR", "EXPYEARO", EXPYEARO_LENGTH, 182, 133),

        /**
         * {@code INFOMSG} - the informational line, {@code app/bms/COCRDSL.bms:139}
         * ({@code ATTRB=(PROT) COLOR=NEUTRAL HILIGHT=OFF POS=(20,25)}); {@code INFOMSGO PIC X(40)},
         * {@code COCRDSL.CPY:L188}. Populated from {@code WS-INFO-MSG PIC X(40)} at
         * {@code app/cbl/COCRDSLC.cbl:496} - a same-width move. Its colour item is the third the
         * program writes: {@code DFHBMDAR} when there is no message, {@code DFHNEUTR} when there is
         * ({@code app/cbl/COCRDSLC.cbl:554-556}).
         */
        INFOMSG("INFOMSG", "INFOMSGO", INFOMSGO_LENGTH, 188, 139),

        /**
         * {@code ERRMSG} - the error line, {@code app/bms/COCRDSL.bms:144}
         * ({@code ATTRB=(ASKIP,BRT,FSET) COLOR=RED POS=(23,1)}); {@code ERRMSGO PIC X(80)},
         * {@code COCRDSL.CPY:L194}. Populated from {@code WS-RETURN-MSG PIC X(75)} at
         * {@code app/cbl/COCRDSLC.cbl:494}, so the move widens by five and the receiver is
         * space-padded on the right.
         */
        ERRMSG("ERRMSG", "ERRMSGO", ERRMSGO_LENGTH, 194, 144),

        /**
         * {@code FKEYS} - the function-key legend, {@code app/bms/COCRDSL.bms:148}
         * ({@code ATTRB=(ASKIP,NORM) COLOR=YELLOW POS=(24,1) INITIAL='ENTER=Search Cards  F3=Exit'});
         * {@code FKEYSO PIC X(75)}, {@code COCRDSL.CPY:L200}.
         *
         * <p>{@code COCRDSLC} never moves anything into this item - it is absent from every
         * {@code CCRDSLAO} write site in the program - so on the real screen it displays the mapset's
         * {@code INITIAL} literal. {@link CardSelectResponse#BMS_INITIAL_FKEYS} carries that literal for
         * callers that want to reproduce the rendered screen; the item's own default here stays
         * {@code LOW-VALUES}, which is what the program leaves in it.
         */
        FKEYS("FKEYS", "FKEYSO", FKEYSO_LENGTH, 200, 148);

        private final String dfhmdfLabel;
        private final String cobolName;
        private final int length;
        private final int copybookLine;
        private final int mapsetLine;

        ScreenField(String dfhmdfLabel, String cobolName, int length, int copybookLine,
                int mapsetLine) {
            this.dfhmdfLabel = dfhmdfLabel;
            this.cobolName = cobolName;
            this.length = length;
            this.copybookLine = copybookLine;
            this.mapsetLine = mapsetLine;
        }

        /**
         * The field's {@code DFHMDF} label in {@code app/bms/COCRDSL.bms}, which is also the
         * {@code (SCRNVAR2)} token {@code app/cpy/CSSETATY.cpy} substitutes and the prefix every
         * symbolic-map item name is built from.
         *
         * @return the label, for example {@code "ACCTSID"}; never {@code null} and never empty
         */
        public String dfhmdfLabel() {
            return dfhmdfLabel;
        }

        /**
         * The output data item's name exactly as {@code app/cpy-bms/COCRDSL.CPY} spells it - the label
         * followed by {@code O}. This is the key the parity differ compares by, so it is carried
         * verbatim.
         *
         * @return the item name, for example {@code "ACCTSIDO"}; never {@code null} and never empty
         */
        public String cobolName() {
            return cobolName;
        }

        /**
         * The name of the field's colour item, the label followed by {@code C}: the receiver of
         * {@code MOVE DFHRED} in {@code app/cpy/CSSETATY.cpy:L21-L22}.
         *
         * @return the item name, for example {@code "ACCTSIDC"}; never {@code null}
         */
        public String colourItemName() {
            return dfhmdfLabel + "C";
        }

        /**
         * The name of the field's programmed-symbol item, the label followed by {@code P}.
         *
         * @return the item name, for example {@code "ACCTSIDP"}; never {@code null}
         */
        public String psItemName() {
            return dfhmdfLabel + "P";
        }

        /**
         * The name of the field's highlight item, the label followed by {@code H}.
         *
         * @return the item name, for example {@code "ACCTSIDH"}; never {@code null}
         */
        public String hilightItemName() {
            return dfhmdfLabel + "H";
        }

        /**
         * The name of the field's validation item, the label followed by {@code V}.
         *
         * @return the item name, for example {@code "ACCTSIDV"}; never {@code null}
         */
        public String validnItemName() {
            return dfhmdfLabel + "V";
        }

        /**
         * The declared width of the {@code xxxO} item, read from its {@code PICTURE} clause.
         *
         * @return the width in characters; always at least 1
         */
        public int length() {
            return length;
        }

        /**
         * The line of {@code app/cpy-bms/COCRDSL.CPY} the {@code xxxO} declaration was read from.
         *
         * @return the one-based line number
         */
        public int copybookLine() {
            return copybookLine;
        }

        /**
         * The line of {@code app/bms/COCRDSL.bms} the name-labelled {@code DFHMDF} was read from.
         *
         * @return the one-based line number
         */
        public int mapsetLine() {
            return mapsetLine;
        }

        /**
         * The offset of the field's whole seven-plus-{@code n} byte span within {@code 01 CCRDSLAO},
         * counted from zero.
         *
         * <p>Derived rather than declared: it is the twelve-byte {@code TIOAPFX} prefix plus the strides
         * of every preceding constant. Summing at most fourteen integers costs nothing, and it removes
         * fifteen hand-typed offsets - and with them the possibility that one of them is wrong.
         *
         * @return the zero-based offset of the field's leading {@code FILLER}
         */
        public int fieldOffset() {
            int offset = TIOAPFX_LENGTH;
            ScreenField[] fields = values();
            for (int index = 0; index < ordinal(); index++) {
                offset += FIELD_PREFIX_LENGTH + fields[index].length;
            }
            return offset;
        }

        /**
         * The offset of the field's leading {@code 02 FILLER PICTURE X(3)}, which is the same as
         * {@link #fieldOffset()} and is named separately so geometry code reads as the copybook does.
         *
         * @return the zero-based offset of the three filler bytes
         */
        public int fillerOffset() {
            return fieldOffset();
        }

        /**
         * The offset of the {@code xxxC} colour item - the first byte after the filler.
         *
         * @return the zero-based offset of the colour byte
         */
        public int colourOffset() {
            return fieldOffset() + FILLER_LENGTH;
        }

        /**
         * The offset of the {@code xxxP} programmed-symbol item.
         *
         * @return the zero-based offset of the programmed-symbol byte
         */
        public int psOffset() {
            return colourOffset() + ATTRIBUTE_ITEM_LENGTH;
        }

        /**
         * The offset of the {@code xxxH} highlight item.
         *
         * @return the zero-based offset of the highlight byte
         */
        public int hilightOffset() {
            return psOffset() + ATTRIBUTE_ITEM_LENGTH;
        }

        /**
         * The offset of the {@code xxxV} validation item.
         *
         * @return the zero-based offset of the validation byte
         */
        public int validnOffset() {
            return hilightOffset() + ATTRIBUTE_ITEM_LENGTH;
        }

        /**
         * The offset of the {@code xxxO} data item, which begins immediately after the four attribute
         * items.
         *
         * @return the zero-based offset of the first data character
         */
        public int dataOffset() {
            return fieldOffset() + FIELD_PREFIX_LENGTH;
        }

        /**
         * Renders this field's full provenance in one line, for a parity report or a review note.
         *
         * <p>For example {@code "ACCTSID: ACCTSIDO PIC X(11) at COCRDSL.CPY:152, DFHMDF at
         * COCRDSL.bms:84, bytes 169-179 of CCRDSLAO"}.
         *
         * @return the description; never {@code null} and never empty
         */
        public String describe() {
            int start = dataOffset();
            return dfhmdfLabel + ": " + cobolName + " PIC X(" + length + ") at COCRDSL.CPY:"
                    + copybookLine + ", DFHMDF at COCRDSL.bms:" + mapsetLine + ", bytes " + start
                    + "-" + (start + length - 1) + " of CCRDSLAO";
        }
    }

    // =================================================================================================
    // The group's byte geometry, enumerated in full.
    //
    // Every one of the 504 bytes of 01 CCRDSLAO is accounted for by exactly one descriptor, FILLER
    // included. That is the point: a codec that omits a FILLER span produces a record that is quietly
    // the wrong width and every offset after the omission is wrong. Here the omission is impossible,
    // because buildGroupGeometry refuses to return a list whose spans do not tile [0, 504) without a gap
    // and without an overlap, and it runs during class initialisation.
    // =================================================================================================

    /** The name COBOL gives an unnamed span. There are sixteen of them in this group. */
    public static final String FILLER_ITEM_NAME = "FILLER";

    /** What a span of {@code 01 CCRDSLAO} is for. */
    public enum SpanKind {

        /** The leading {@code 02 FILLER PIC X(12)} that {@code TIOAPFX=YES} prepends, once per group. */
        TIOAPFX_PREFIX,

        /**
         * A per-field {@code 02 FILLER PICTURE X(3)}, overlaying the input view's {@code xxxL} plus
         * {@code xxxF}.
         */
        FILLER,

        /** An {@code xxxC} item: the extended-attribute {@code COLOR} byte. */
        COLOUR,

        /** An {@code xxxP} item: the {@code PS} programmed-symbol-set byte. */
        PS,

        /** An {@code xxxH} item: the {@code HILIGHT} byte. */
        HILIGHT,

        /** An {@code xxxV} item: the {@code VALIDN} byte. */
        VALIDN,

        /** An {@code xxxO} item: the field's outbound character data. */
        DATA
    }

    /**
     * One span of {@code 01 CCRDSLAO}, named as the copybook names it.
     *
     * @param cobolName the item name, or {@link CardSelectResponse#FILLER_ITEM_NAME} for an unnamed span
     * @param kind      what the span carries
     * @param offset    the zero-based offset of its first byte within the 504-byte group
     * @param length    its width in bytes
     */
    public record SpanDescriptor(String cobolName, SpanKind kind, int offset, int length) {

        /**
         * Rejects a span that could not exist in a fixed-width group.
         *
         * @throws NullPointerException     if {@code cobolName} or {@code kind} is {@code null}
         * @throws IllegalArgumentException if {@code offset} is negative or {@code length} is below 1
         */
        public SpanDescriptor {
            Objects.requireNonNull(cobolName, "A span must be named, even when the name is FILLER");
            Objects.requireNonNull(kind, "A span must state what it carries");
            if (offset < 0) {
                throw new IllegalArgumentException(
                        "Span " + cobolName + " cannot start at negative offset " + offset);
            }
            if (length < 1) {
                throw new IllegalArgumentException("Span " + cobolName + " cannot be " + length
                        + " bytes wide; a PICTURE clause always declares at least one");
            }
        }

        /**
         * The offset one past the span's last byte, which is where the next span must begin.
         *
         * @return {@code offset + length}
         */
        public int endOffset() {
            return offset + length;
        }
    }

    /**
     * The 91 spans of {@code 01 CCRDSLAO} in offset order: one {@code TIOAPFX} prefix plus, for each of
     * the {@value #FIELD_COUNT} fields, its {@code FILLER}, its four attribute items and its data item.
     * Built and verified once, during class initialisation.
     */
    private static final List<SpanDescriptor> GROUP_GEOMETRY = buildGroupGeometry();

    /**
     * Enumerates every span of the output group from the copybook's declared widths and proves that they
     * tile the group exactly.
     *
     * @return the spans in ascending offset order
     * @throws IllegalStateException if the spans leave a gap, overlap, or do not total
     *                               {@value #GROUP_LENGTH} bytes - any of which would mean a constant
     *                               above disagrees with {@code app/cpy-bms/COCRDSL.CPY}
     * @see #verifyGroupTiling(List)
     */
    private static List<SpanDescriptor> buildGroupGeometry() {
        List<SpanDescriptor> spans =
                new ArrayList<>(1 + (FIELD_COUNT * (2 + ATTRIBUTE_ITEMS_PER_FIELD)));

        spans.add(new SpanDescriptor(FILLER_ITEM_NAME, SpanKind.TIOAPFX_PREFIX, 0, TIOAPFX_LENGTH));

        for (ScreenField field : ScreenField.values()) {
            spans.add(new SpanDescriptor(
                    FILLER_ITEM_NAME, SpanKind.FILLER, field.fillerOffset(), FILLER_LENGTH));
            spans.add(new SpanDescriptor(field.colourItemName(), SpanKind.COLOUR,
                    field.colourOffset(), ATTRIBUTE_ITEM_LENGTH));
            spans.add(new SpanDescriptor(field.psItemName(), SpanKind.PS,
                    field.psOffset(), ATTRIBUTE_ITEM_LENGTH));
            spans.add(new SpanDescriptor(field.hilightItemName(), SpanKind.HILIGHT,
                    field.hilightOffset(), ATTRIBUTE_ITEM_LENGTH));
            spans.add(new SpanDescriptor(field.validnItemName(), SpanKind.VALIDN,
                    field.validnOffset(), ATTRIBUTE_ITEM_LENGTH));
            spans.add(new SpanDescriptor(field.cobolName(), SpanKind.DATA,
                    field.dataOffset(), field.length()));
        }

        return List.copyOf(verifyGroupTiling(spans));
    }

    /**
     * Proves that a list of spans tiles a {@value #GROUP_LENGTH}-byte group exactly: the first span
     * starts at offset zero, each subsequent span starts where its predecessor ended, and the last one
     * ends at {@value #GROUP_LENGTH}.
     *
     * <p>This is the check {@link #groupGeometry()} runs during class initialisation, exposed as its own
     * operation for two reasons. It is the one guarantee that makes an omitted {@code FILLER} impossible
     * to miss, so it deserves to be exercised directly rather than only implicitly. And a repository or
     * codec assembling the real 504-byte record from its own span list can run the identical check over
     * that list, rather than reimplementing it and getting the edge cases subtly different.
     *
     * @param spans the spans to check, in the order they are meant to occupy the group
     * @return {@code spans}, unchanged, so the call can wrap a construction expression
     * @throws NullPointerException  if {@code spans} or any element is {@code null}
     * @throws IllegalStateException if the spans leave a gap, overlap, or do not total
     *                               {@value #GROUP_LENGTH} bytes
     */
    public static List<SpanDescriptor> verifyGroupTiling(List<SpanDescriptor> spans) {
        Objects.requireNonNull(spans, "A span list is required to verify a group's tiling");

        int expectedOffset = 0;
        for (SpanDescriptor span : spans) {
            Objects.requireNonNull(span, "A span list may not contain a null span");
            if (span.offset() != expectedOffset) {
                throw new IllegalStateException("Span " + span.cobolName() + " (" + span.kind()
                        + ") starts at offset " + span.offset() + " but the preceding span ends at "
                        + expectedOffset + "; the spans of 01 CCRDSLAO must tile the group with no gap "
                        + "and no overlap");
            }
            expectedOffset = span.endOffset();
        }
        if (expectedOffset != GROUP_LENGTH) {
            throw new IllegalStateException("The spans of 01 CCRDSLAO total " + expectedOffset
                    + " bytes but the group is declared " + GROUP_LENGTH + "; check the fifteen xxxO "
                    + "PICTURE widths against app/cpy-bms/COCRDSL.CPY");
        }

        return spans;
    }

    /**
     * The complete byte geometry of {@code 01 CCRDSLAO}, in ascending offset order, {@code FILLER}
     * spans included.
     *
     * <p>Supplied so a repository or a parity harness can build the real 504-byte record under an
     * explicitly chosen code page. This class deliberately does not encode bytes itself: the group mixes
     * character data with 3270 attribute bytes, and the two cannot share one charset conversion without
     * one of them being wrong. Declaring the geometry and leaving the encoding to the layer that owns
     * the code page keeps that decision visible.
     *
     * @return an unmodifiable list of 91 spans totalling {@value #GROUP_LENGTH} bytes; never
     *         {@code null}
     */
    @JsonIgnore
    public static List<SpanDescriptor> groupGeometry() {
        return GROUP_GEOMETRY;
    }

    // =================================================================================================
    // The attribute quad, per field.
    //
    // Four bytes that never reach the JSON wire but must be addressable, because COCRDSLC genuinely
    // writes three of the fifteen colour items and because CSSETATY's error highlight has nowhere else
    // to go. Mutable on purpose: the highlight is applied after the payload is otherwise complete.
    // =================================================================================================

    /**
     * The {@code xxxC}, {@code xxxP}, {@code xxxH} and {@code xxxV} items of one screen field - the
     * {@code DSATTS}/{@code MAPATTS=(COLOR,HILIGHT,PS,VALIDN)} set the {@code CCRDSLA DFHMDI} declares,
     * held in the copybook's byte order: colour, then programmed symbols, then highlight, then
     * validation.
     *
     * <p>Every item starts at {@code LOW-VALUES}, which is what
     * {@code MOVE LOW-VALUES TO CCRDSLAO} ({@code app/cbl/COCRDSLC.cbl:428}) leaves in it and which the
     * 3270 reads as "use the default". {@link BmsAttributes#DFHDFCOL} and {@link BmsAttributes#DFHDFHI}
     * are both that same byte, so the initial state is simultaneously "untouched" and "default", exactly
     * as on the terminal.
     *
     * <p>These four items are excluded from the serialised payload. They are metadata about how a field
     * is painted, not data the field carries, and the fifteen {@code xxxO} members plus the navigation
     * carriers are the whole JSON contract.
     */
    public static final class FieldAttributes {

        /** {@code xxxC PICTURE X} - the extended {@code COLOR} attribute. */
        private byte colour = LOW_VALUES_BYTE;

        /** {@code xxxP PICTURE X} - the {@code PS} programmed-symbol-set attribute. */
        private byte ps = LOW_VALUES_BYTE;

        /** {@code xxxH PICTURE X} - the {@code HILIGHT} attribute. */
        private byte hilight = LOW_VALUES_BYTE;

        /** {@code xxxV PICTURE X} - the {@code VALIDN} attribute. */
        private byte validn = LOW_VALUES_BYTE;

        /** Creates a quad in the state {@code MOVE LOW-VALUES TO CCRDSLAO} leaves it in. */
        public FieldAttributes() {
            // All four items are initialised at their declarations above; nothing further is required.
        }

        /**
         * Copies an existing quad, so echoing a request's attributes into a response cannot alias them.
         *
         * @param other the quad to copy
         * @throws NullPointerException if {@code other} is {@code null}
         */
        public FieldAttributes(FieldAttributes other) {
            Objects.requireNonNull(other, "A source attribute quad is required to copy one");
            this.colour = other.colour;
            this.ps = other.ps;
            this.hilight = other.hilight;
            this.validn = other.validn;
        }

        /**
         * The {@code xxxC} colour byte - the item {@code app/cpy/CSSETATY.cpy:L21-L22} moves
         * {@code DFHRED} into, and the one {@code COCRDSLC} writes at {@code L529-L530},
         * {@code L534}, {@code L538}, {@code L544}, {@code L550}, {@code L554} and {@code L556}.
         *
         * @return the colour attribute byte
         */
        public byte getColour() {
            return colour;
        }

        /**
         * Sets the {@code xxxC} colour byte. No value is rejected: {@code DFHBMSCA} defines a fixed set
         * of colours but a symbolic map item is one unchecked byte, and validating it here would reject
         * a value the mainframe accepts.
         *
         * @param colour the colour attribute byte, for example {@link BmsAttributes#DFHRED}
         */
        public void setColour(byte colour) {
            this.colour = colour;
        }

        /**
         * The {@code xxxP} programmed-symbol byte. {@code COCRDSLC} never writes it, so it stays
         * {@code LOW-VALUES} throughout that program; it is modelled because the copybook declares it
         * and because omitting it would shift every following offset.
         *
         * @return the programmed-symbol attribute byte
         */
        public byte getPs() {
            return ps;
        }

        /**
         * Sets the {@code xxxP} programmed-symbol byte.
         *
         * @param ps the programmed-symbol attribute byte
         */
        public void setPs(byte ps) {
            this.ps = ps;
        }

        /**
         * The {@code xxxH} highlight byte. The mapset declares {@code HILIGHT=UNDERLINE} on six fields
         * and {@code HILIGHT=OFF} on {@code INFOMSG}, but {@code COCRDSLC} never overrides those
         * declarations at run time, so this item stays {@code LOW-VALUES} in that program.
         *
         * @return the highlight attribute byte
         */
        public byte getHilight() {
            return hilight;
        }

        /**
         * Sets the {@code xxxH} highlight byte.
         *
         * @param hilight the highlight attribute byte, for example {@link BmsAttributes#DFHUNDLN}
         */
        public void setHilight(byte hilight) {
            this.hilight = hilight;
        }

        /**
         * The {@code xxxV} validation byte. Never written by {@code COCRDSLC}.
         *
         * @return the validation attribute byte
         */
        public byte getValidn() {
            return validn;
        }

        /**
         * Sets the {@code xxxV} validation byte.
         *
         * @param validn the validation attribute byte
         */
        public void setValidn(byte validn) {
            this.validn = validn;
        }

        /**
         * Whether the colour item currently holds {@link BmsAttributes#DFHRED}, that is whether this
         * field is painted as being in error.
         *
         * <p>This is the assertion the highlight rule is verified by: after
         * {@link CardSelectResponse#applyHighlight(ScreenField, FieldValidationState, boolean)} runs on
         * a failing field it must be {@code true} when the program is re-entering and {@code false} on
         * first entry, which is what {@code app/cpy/CSSETATY.cpy:L20}'s {@code AND CDEMO-PGM-REENTER}
         * means.
         *
         * @return {@code true} when the colour item holds the red attribute byte
         */
        public boolean isRedHighlighted() {
            return colour == BmsAttributes.DFHRED;
        }

        /**
         * Whether the colour item still holds the default-colour byte, which is also
         * {@code LOW-VALUES}.
         *
         * @return {@code true} when the colour item holds {@link BmsAttributes#DFHDFCOL}
         */
        public boolean isDefaultColour() {
            return colour == BmsAttributes.DFHDFCOL;
        }

        /**
         * Restores all four items to {@code LOW-VALUES}, reproducing the effect of
         * {@code MOVE LOW-VALUES TO CCRDSLAO} on this field's quad.
         */
        public void resetToLowValues() {
            this.colour = LOW_VALUES_BYTE;
            this.ps = LOW_VALUES_BYTE;
            this.hilight = LOW_VALUES_BYTE;
            this.validn = LOW_VALUES_BYTE;
        }

        /**
         * Renders the quad with the {@code DFHBMSCA} mnemonics where one is known, for a parity report.
         *
         * <p>For example {@code "C=DFHRED P=X'00' H=DFHDFHI V=X'00'"}.
         *
         * @return the description; never {@code null} and never empty
         */
        public String describe() {
            return "C=" + BmsAttributes.colourMnemonic(colour)
                    + " P=" + BmsAttributes.toHex(ps)
                    + " H=" + BmsAttributes.highlightMnemonic(hilight)
                    + " V=" + BmsAttributes.toHex(validn);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof FieldAttributes quad)) {
                return false;
            }
            return colour == quad.colour && ps == quad.ps && hilight == quad.hilight
                    && validn == quad.validn;
        }

        @Override
        public int hashCode() {
            return Objects.hash(colour, ps, hilight, validn);
        }

        @Override
        public String toString() {
            return "FieldAttributes[" + describe() + "]";
        }
    }

    // =================================================================================================
    // The fifteen payload members, in the copybook's declaration order.
    //
    // Each is a String because each xxxO item is PIC X(n). Each is held at exactly its declared width,
    // space-padded on the right and truncated on the right, because that is what a PIC X receiver does -
    // and each is stored through FixedWidthCodec rather than by assignment so the direction of the
    // truncation is a decision the code makes rather than one it stumbles into.
    // =================================================================================================

    /** {@code TRNNAMEO PIC X(4)}, {@code COCRDSL.CPY:L116}; {@code DFHMDF TRNNAME}, {@code .bms:34}. */
    private String trnnameo;

    /** {@code TITLE01O PIC X(40)}, {@code COCRDSL.CPY:L122}; {@code DFHMDF TITLE01}, {@code .bms:38}. */
    private String title01o;

    /** {@code CURDATEO PIC X(8)}, {@code COCRDSL.CPY:L128}; {@code DFHMDF CURDATE}, {@code .bms:47}. */
    private String curdateo;

    /** {@code PGMNAMEO PIC X(8)}, {@code COCRDSL.CPY:L134}; {@code DFHMDF PGMNAME}, {@code .bms:57}. */
    private String pgmnameo;

    /** {@code TITLE02O PIC X(40)}, {@code COCRDSL.CPY:L140}; {@code DFHMDF TITLE02}, {@code .bms:61}. */
    private String title02o;

    /** {@code CURTIMEO PIC X(8)}, {@code COCRDSL.CPY:L146}; {@code DFHMDF CURTIME}, {@code .bms:70}. */
    private String curtimeo;

    /** {@code ACCTSIDO PIC X(11)}, {@code COCRDSL.CPY:L152}; {@code DFHMDF ACCTSID}, {@code .bms:84}. */
    private String acctsido;

    /** {@code CARDSIDO PIC X(16)}, {@code COCRDSL.CPY:L158}; {@code DFHMDF CARDSID}, {@code .bms:96}. */
    private String cardsido;

    /** {@code CRDNAMEO PIC X(50)}, {@code COCRDSL.CPY:L164}; {@code DFHMDF CRDNAME}, {@code .bms:107}. */
    private String crdnameo;

    /** {@code CRDSTCDO PIC X(1)}, {@code COCRDSL.CPY:L170}; {@code DFHMDF CRDSTCD}, {@code .bms:116}. */
    private String crdstcdo;

    /** {@code EXPMONO PIC X(2)}, {@code COCRDSL.CPY:L176}; {@code DFHMDF EXPMON}, {@code .bms:126}. */
    private String expmono;

    /** {@code EXPYEARO PIC X(4)}, {@code COCRDSL.CPY:L182}; {@code DFHMDF EXPYEAR}, {@code .bms:133}. */
    private String expyearo;

    /** {@code INFOMSGO PIC X(40)}, {@code COCRDSL.CPY:L188}; {@code DFHMDF INFOMSG}, {@code .bms:139}. */
    private String infomsgo;

    /** {@code ERRMSGO PIC X(80)}, {@code COCRDSL.CPY:L194}; {@code DFHMDF ERRMSG}, {@code .bms:144}. */
    private String errmsgo;

    /** {@code FKEYSO PIC X(75)}, {@code COCRDSL.CPY:L200}; {@code DFHMDF FKEYS}, {@code .bms:148}. */
    private String fkeyso;

    // -------------------------------------------------------------------------------------------------
    // Attribute metadata: fifteen quads, sixty bytes, none of them on the JSON wire.
    // -------------------------------------------------------------------------------------------------

    /**
     * One {@link FieldAttributes} quad per {@link ScreenField}. An {@link EnumMap} because the key space
     * is closed and fixed at {@value #FIELD_COUNT}; every entry is present from construction, so a
     * lookup never returns {@code null}.
     */
    private final Map<ScreenField, FieldAttributes> attributes = new EnumMap<>(ScreenField.class);

    // -------------------------------------------------------------------------------------------------
    // Conversation carriers. Rule R6: the CICS pseudo-conversation's state travels in the payload, so the
    // client sends every one of these back on its next call and the server keeps nothing.
    // -------------------------------------------------------------------------------------------------

    /**
     * The {@code CVCRD01Y} work area {@code COCRDSLC} copies at {@code app/cbl/COCRDSLC.cbl:194} -
     * the AID token, the next-screen triple, the two message lines and the three search identifiers.
     */
    private CardScreenState cardScreenState;

    /**
     * The {@code CARDDEMO-COMMAREA} {@code COCRDSLC} copies at {@code app/cbl/COCRDSLC.cbl:198} and
     * passes on the {@code XCTL} at {@code L333}. Immutable, so it is shared rather than copied.
     */
    private NavigationContext navigationContext;

    /**
     * {@code WS-THIS-PROGCOMMAREA} - the twelve bytes {@code COMMON-RETURN} appends to the
     * communication area at {@code app/cbl/COCRDSLC.cbl:397-400} before the
     * {@code EXEC CICS RETURN} at {@code :402-406}.
     *
     * <p>Deliberately {@link CardSelectRequest.ThisProgCommarea} - the very type the paired request
     * carries - because the area is one COBOL group and not two. The program restores it from what the
     * caller passed and returns it unchanged, so a response that omitted it would shorten the area the
     * next turn receives from {@value CardSelectRequest#PASSED_COMMAREA_LENGTH} bytes to
     * {@value NavigationContext#COMMAREA_LENGTH} and lose the calling program and transaction.
     *
     * <p>Never {@code null}: {@link CardSelectRequest.ThisProgCommarea#initialized()} is what
     * {@code :272} leaves in the area, and twelve spaces is a value.
     */
    private ThisProgCommarea thisProgCommarea = ThisProgCommarea.initialized();

    /**
     * {@code CCARD-NEXT-PROG PIC X(8)} as a response member: the program the {@code XCTL} at
     * {@code app/cbl/COCRDSLC.cbl:331} would have transferred to. An opaque token.
     */
    private String nextProgram;

    /**
     * {@code CCARD-NEXT-MAPSET PIC X(7)} as a response member. An opaque token.
     */
    private String nextMapset;

    /**
     * {@code CCARD-NEXT-MAP PIC X(7)} as a response member. An opaque token, and the one the preserved
     * {@code LIT-CCLISTMAP} defect travels in.
     */
    private String nextMap;

    /**
     * The {@code DFHMDF} label of the field {@code 1300-SETUP-SCREEN-ATTRS} aimed the cursor at, or
     * {@code null} when no cursor request has been recorded on this turn.
     *
     * <p>{@code app/cbl/COCRDSLC.cbl:514-524} is an ordered {@code EVALUATE TRUE} that always issues
     * exactly one {@code MOVE -1}: to {@code ACCTSIDL OF CCRDSLAI} at {@code :518}, to
     * {@code CARDSIDL OF CCRDSLAI} at {@code :521}, or to {@code ACCTSIDL} again on the
     * {@code WHEN OTHER} arm at {@code :523}. The {@code EXEC CICS SEND MAP} at {@code :569-576}
     * carries {@code CURSOR}, so where that {@code -1} landed is what the operator sees and is
     * therefore observable behaviour, not decoration.
     *
     * <p>It is held here rather than only on the request's {@code xxxL} metadata because the request
     * the controller edits is its own defensive copy: nothing the caller keeps a reference to sees the
     * move. Publishing it through {@link #screenMetadata()} is what keeps the cursor in the payload
     * instead of in server-side state (rule R6), and it is the same shape the sibling
     * {@code CardListResponse} already publishes for the {@code COCRDLI} and {@code COCRDSL} maps -
     * which matters, because {@code COCRDSL} is the one symbolic map two programs consume and the two
     * projections of it must not drift apart.
     *
     * <p><strong>Not a payload member and never serialised directly.</strong> Every field on the wire
     * beside the fifteen must trace to a {@code DFHMDF} definition (gate G9), and a cursor request
     * traces to an {@code xxxL} length item rather than to a field, so inventing a sixteenth sibling
     * for it would break that rule. It travels under {@link ScreenMetadata#cursorField()} instead,
     * which is what the envelope is for.
     *
     * <p>Not reset by {@link #initializeGroup()}. {@code MOVE LOW-VALUES TO CCRDSLAO} at {@code :428}
     * blanks the <em>output</em> group; the length items it would have to clear live in
     * {@code CCRDSLAI}, which that statement does not name.
     */
    private String cursorField;

    // =================================================================================================
    // Construction. No Spring context, no builder, no framework: a controller test, a MockMvc test and
    // the COCRDSLC parity test all construct an instance directly (practice B10).
    // =================================================================================================

    /**
     * Creates the payload in the state {@code app/cbl/COCRDSLC.cbl:428}'s
     * {@code MOVE LOW-VALUES TO CCRDSLAO} leaves the group in: every one of the fifteen data items
     * {@code LOW-VALUES}-filled to its declared width, and all sixty attribute bytes {@code 0x00}.
     *
     * <p>{@code LOW-VALUES} rather than spaces, deliberately. This is a BMS symbolic map, not a persisted
     * data record: {@code 1100-SCREEN-INIT} blanks the whole group with {@code LOW-VALUES} and the
     * program then writes only the items it has something to say about, relying on {@code 0x00} meaning
     * "no data, default attribute" to the 3270. The program is explicit about this elsewhere too - it
     * moves {@code LOW-VALUES} into {@code ACCTSIDO} at {@code L463} and into {@code CARDSIDO} at
     * {@code L469} when the commarea carried no search value. Space-filling instead would put 168 spaces
     * on the wire that the COBOL never sent.
     *
     * <p>The three navigation members and the two carriers start empty in the same spirit: the
     * next-screen triple is space-filled at its declared widths, {@link #getCardScreenState()} is a fresh
     * {@link CardScreenState} - which {@code INITIALIZE CC-WORK-AREA} at
     * {@code app/cbl/COCRDSLC.cbl:254} makes the program's own starting point - and
     * {@link #getNavigationContext()} is {@link NavigationContext#empty()}.
     */
    public CardSelectResponse() {
        initializeGroup();
        this.cardScreenState = new CardScreenState();
        this.navigationContext = NavigationContext.empty();
        this.nextProgram = CardScreenState.spaces(NEXT_PROGRAM_LENGTH);
        this.nextMapset = CardScreenState.spaces(NEXT_MAPSET_LENGTH);
        this.nextMap = CardScreenState.spaces(NEXT_MAP_LENGTH);
    }

    /**
     * Creates the payload with all fifteen data items supplied, each stored through the {@code PIC X}
     * move rule and therefore held at exactly its declared width. The attribute quads and the carriers
     * start as {@link #CardSelectResponse()} leaves them.
     *
     * <p>The parameter order is the copybook's order, so a reviewer can read this signature against
     * {@code app/cpy-bms/COCRDSL.CPY:L109-L200} top to bottom.
     *
     * @param trnnameo {@code TRNNAMEO PIC X(4)}
     * @param title01o {@code TITLE01O PIC X(40)}
     * @param curdateo {@code CURDATEO PIC X(8)}
     * @param pgmnameo {@code PGMNAMEO PIC X(8)}
     * @param title02o {@code TITLE02O PIC X(40)}
     * @param curtimeo {@code CURTIMEO PIC X(8)}
     * @param acctsido {@code ACCTSIDO PIC X(11)}
     * @param cardsido {@code CARDSIDO PIC X(16)}
     * @param crdnameo {@code CRDNAMEO PIC X(50)}
     * @param crdstcdo {@code CRDSTCDO PIC X(1)}
     * @param expmono  {@code EXPMONO PIC X(2)}
     * @param expyearo {@code EXPYEARO PIC X(4)}
     * @param infomsgo {@code INFOMSGO PIC X(40)}
     * @param errmsgo  {@code ERRMSGO PIC X(80)}
     * @param fkeyso   {@code FKEYSO PIC X(75)}
     * @throws NullPointerException if any argument is {@code null}; COBOL has no absent state, so the
     *                             caller must say whether it means spaces or {@code LOW-VALUES}
     */
    public CardSelectResponse(String trnnameo,
                              String title01o,
                              String curdateo,
                              String pgmnameo,
                              String title02o,
                              String curtimeo,
                              String acctsido,
                              String cardsido,
                              String crdnameo,
                              String crdstcdo,
                              String expmono,
                              String expyearo,
                              String infomsgo,
                              String errmsgo,
                              String fkeyso) {
        this();
        setTrnnameo(trnnameo);
        setTitle01o(title01o);
        setCurdateo(curdateo);
        setPgmnameo(pgmnameo);
        setTitle02o(title02o);
        setCurtimeo(curtimeo);
        setAcctsido(acctsido);
        setCardsido(cardsido);
        setCrdnameo(crdnameo);
        setCrdstcdo(crdstcdo);
        setExpmono(expmono);
        setExpyearo(expyearo);
        setInfomsgo(infomsgo);
        setErrmsgo(errmsgo);
        setFkeyso(fkeyso);
    }

    /**
     * Copies an existing payload member for member, including the fifteen attribute quads.
     *
     * <p>The quads are copied rather than shared, so highlighting one instance's field cannot repaint
     * another's. {@link CardScreenState} is copied for the same reason - it is mutable - while
     * {@link NavigationContext} is a record and is shared safely.
     *
     * @param other the payload to copy
     * @throws NullPointerException if {@code other} is {@code null}
     */
    public CardSelectResponse(CardSelectResponse other) {
        Objects.requireNonNull(other, "A source payload is required to copy one");
        this.trnnameo = other.trnnameo;
        this.title01o = other.title01o;
        this.curdateo = other.curdateo;
        this.pgmnameo = other.pgmnameo;
        this.title02o = other.title02o;
        this.curtimeo = other.curtimeo;
        this.acctsido = other.acctsido;
        this.cardsido = other.cardsido;
        this.crdnameo = other.crdnameo;
        this.crdstcdo = other.crdstcdo;
        this.expmono = other.expmono;
        this.expyearo = other.expyearo;
        this.infomsgo = other.infomsgo;
        this.errmsgo = other.errmsgo;
        this.fkeyso = other.fkeyso;
        for (ScreenField field : ScreenField.values()) {
            this.attributes.put(field, new FieldAttributes(other.attributes.get(field)));
        }
        this.cardScreenState = new CardScreenState(other.cardScreenState);
        this.navigationContext = other.navigationContext;
        // A record, so sharing it is safe - and it must be carried, or a copy would return a trailer of
        // twelve spaces where the original returned the caller's twelve bytes, shortening the area the
        // next turn receives from 172 to 160. CardSelectRequest's copy constructor makes the same point.
        this.thisProgCommarea = other.thisProgCommarea;
        this.nextProgram = other.nextProgram;
        this.nextMapset = other.nextMapset;
        this.nextMap = other.nextMap;
        this.cursorField = other.cursorField;
    }

    /**
     * Reproduces {@code MOVE LOW-VALUES TO CCRDSLAO} ({@code app/cbl/COCRDSLC.cbl:428}): every data item
     * {@code LOW-VALUES}-filled to its declared width and every attribute quad back to {@code 0x00}.
     *
     * <p>Public because {@code 1100-SCREEN-INIT} performs it on every pass through the program, not only
     * at construction, and a service reproducing that paragraph needs the same operation. The navigation
     * carriers are deliberately untouched: the COBOL statement covers the map area only, and
     * {@code CC-WORK-AREA} and the commarea live outside it.
     */
    public void initializeGroup() {
        this.trnnameo = CardScreenState.lowValues(TRNNAMEO_LENGTH);
        this.title01o = CardScreenState.lowValues(TITLE01O_LENGTH);
        this.curdateo = CardScreenState.lowValues(CURDATEO_LENGTH);
        this.pgmnameo = CardScreenState.lowValues(PGMNAMEO_LENGTH);
        this.title02o = CardScreenState.lowValues(TITLE02O_LENGTH);
        this.curtimeo = CardScreenState.lowValues(CURTIMEO_LENGTH);
        this.acctsido = CardScreenState.lowValues(ACCTSIDO_LENGTH);
        this.cardsido = CardScreenState.lowValues(CARDSIDO_LENGTH);
        this.crdnameo = CardScreenState.lowValues(CRDNAMEO_LENGTH);
        this.crdstcdo = CardScreenState.lowValues(CRDSTCDO_LENGTH);
        this.expmono = CardScreenState.lowValues(EXPMONO_LENGTH);
        this.expyearo = CardScreenState.lowValues(EXPYEARO_LENGTH);
        this.infomsgo = CardScreenState.lowValues(INFOMSGO_LENGTH);
        this.errmsgo = CardScreenState.lowValues(ERRMSGO_LENGTH);
        this.fkeyso = CardScreenState.lowValues(FKEYSO_LENGTH);
        for (ScreenField field : ScreenField.values()) {
            FieldAttributes quad = attributes.get(field);
            if (quad == null) {
                attributes.put(field, new FieldAttributes());
            } else {
                quad.resetToLowValues();
            }
        }
    }

    // =================================================================================================
    // The single store operation every data setter goes through.
    // =================================================================================================

    /**
     * Applies the COBOL alphanumeric {@code MOVE} rule for one named item: right-pad with spaces when the
     * value is short, truncate on the right when it is long, so the stored image is always exactly the
     * declared width.
     *
     * <p>Every setter on this class routes through here rather than assigning, because a plain assignment
     * would neither pad nor truncate and the resulting difference is invisible at the call site. The
     * program itself supplies the proof that this matters: {@code LIT-THISMAPSET PIC X(8) VALUE
     * 'COCRDSL '} moved into {@code CCARD-NEXT-MAPSET PIC X(7)} must lose its trailing space on the
     * right, and {@code WS-RETURN-MSG PIC X(75)} moved into {@code ERRMSGO PIC X(80)} must gain five
     * spaces on the right.
     *
     * @param value     the sending value; may be shorter or longer than the receiver, and may be empty
     * @param itemName  the receiving item's COBOL name, used only in the diagnostic
     * @param itemWidth the receiver's declared width
     * @return an image of exactly {@code itemWidth} characters
     * @throws NullPointerException if {@code value} is {@code null}
     */
    private static String moveIntoPicX(String value, String itemName, int itemWidth) {
        Objects.requireNonNull(value, () -> "A value is required for " + itemName + " PIC X("
                + itemWidth + "); COBOL has no absent state, so move an empty string for SPACES or "
                + "CardScreenState.lowValues(" + itemWidth + ") for LOW-VALUES");
        return PIC_X_CODEC.movePicX(value, itemWidth);
    }

    // =================================================================================================
    // The fifteen data items. One getter and one setter each, hand written - no Lombok, no generated
    // accessor - so the width every store enforces is visible in the method body.
    // =================================================================================================

    /**
     * {@code TRNNAMEO PIC X(4)} - the transaction identifier shown after the {@code 'Tran:'} literal.
     * Populated from {@code LIT-THISTRANID} at {@code app/cbl/COCRDSLC.cbl:434}.
     *
     * @return the image, exactly {@value #TRNNAMEO_LENGTH} characters
     */
    @JsonProperty("trnname")
    public String getTrnnameo() {
        return trnnameo;
    }

    /**
     * Stores {@code TRNNAMEO}, padded or right-truncated to {@value #TRNNAMEO_LENGTH} characters.
     *
     * @param trnnameo the sending value
     * @throws NullPointerException if {@code trnnameo} is {@code null}
     */
    public void setTrnnameo(String trnnameo) {
        this.trnnameo = moveIntoPicX(trnnameo, ScreenField.TRNNAME.cobolName(), TRNNAMEO_LENGTH);
    }

    /**
     * {@code TITLE01O PIC X(40)} - the upper heading. Populated from {@code CCDA-TITLE01} at
     * {@code app/cbl/COCRDSLC.cbl:432}; see {@link #applyScreenTitles()}.
     *
     * @return the image, exactly {@value #TITLE01O_LENGTH} characters
     */
    @JsonProperty("title01")
    public String getTitle01o() {
        return title01o;
    }

    /**
     * Stores {@code TITLE01O}, padded or right-truncated to {@value #TITLE01O_LENGTH} characters.
     *
     * @param title01o the sending value
     * @throws NullPointerException if {@code title01o} is {@code null}
     */
    public void setTitle01o(String title01o) {
        this.title01o = moveIntoPicX(title01o, ScreenField.TITLE01.cobolName(), TITLE01O_LENGTH);
    }

    /**
     * {@code CURDATEO PIC X(8)} - the current date as {@code mm/dd/yy}. Populated from
     * {@code WS-CURDATE-MM-DD-YY} at {@code app/cbl/COCRDSLC.cbl:443}; see
     * {@link #applyDateHeader(DateHeader)}.
     *
     * @return the image, exactly {@value #CURDATEO_LENGTH} characters
     */
    @JsonProperty("curdate")
    public String getCurdateo() {
        return curdateo;
    }

    /**
     * Stores {@code CURDATEO}, padded or right-truncated to {@value #CURDATEO_LENGTH} characters.
     *
     * @param curdateo the sending value
     * @throws NullPointerException if {@code curdateo} is {@code null}
     */
    public void setCurdateo(String curdateo) {
        this.curdateo = moveIntoPicX(curdateo, ScreenField.CURDATE.cobolName(), CURDATEO_LENGTH);
    }

    /**
     * {@code PGMNAMEO PIC X(8)} - the program name shown after the {@code 'Prog:'} literal. Populated
     * from {@code LIT-THISPGM} at {@code app/cbl/COCRDSLC.cbl:435}.
     *
     * @return the image, exactly {@value #PGMNAMEO_LENGTH} characters
     */
    @JsonProperty("pgmname")
    public String getPgmnameo() {
        return pgmnameo;
    }

    /**
     * Stores {@code PGMNAMEO}, padded or right-truncated to {@value #PGMNAMEO_LENGTH} characters.
     *
     * @param pgmnameo the sending value
     * @throws NullPointerException if {@code pgmnameo} is {@code null}
     */
    public void setPgmnameo(String pgmnameo) {
        this.pgmnameo = moveIntoPicX(pgmnameo, ScreenField.PGMNAME.cobolName(), PGMNAMEO_LENGTH);
    }

    /**
     * {@code TITLE02O PIC X(40)} - the lower heading. Populated from {@code CCDA-TITLE02} at
     * {@code app/cbl/COCRDSLC.cbl:433}.
     *
     * @return the image, exactly {@value #TITLE02O_LENGTH} characters
     */
    @JsonProperty("title02")
    public String getTitle02o() {
        return title02o;
    }

    /**
     * Stores {@code TITLE02O}, padded or right-truncated to {@value #TITLE02O_LENGTH} characters.
     *
     * @param title02o the sending value
     * @throws NullPointerException if {@code title02o} is {@code null}
     */
    public void setTitle02o(String title02o) {
        this.title02o = moveIntoPicX(title02o, ScreenField.TITLE02.cobolName(), TITLE02O_LENGTH);
    }

    /**
     * {@code CURTIMEO PIC X(8)} - the current time as {@code hh:mm:ss}. Populated from
     * {@code WS-CURTIME-HH-MM-SS} at {@code app/cbl/COCRDSLC.cbl:449}.
     *
     * @return the image, exactly {@value #CURTIMEO_LENGTH} characters
     */
    @JsonProperty("curtime")
    public String getCurtimeo() {
        return curtimeo;
    }

    /**
     * Stores {@code CURTIMEO}, padded or right-truncated to {@value #CURTIMEO_LENGTH} characters.
     *
     * @param curtimeo the sending value
     * @throws NullPointerException if {@code curtimeo} is {@code null}
     */
    public void setCurtimeo(String curtimeo) {
        this.curtimeo = moveIntoPicX(curtimeo, ScreenField.CURTIME.cobolName(), CURTIMEO_LENGTH);
    }

    /**
     * {@code ACCTSIDO PIC X(11)} - the account number, in the clear exactly as the symbolic map carries
     * it. Populated from {@code CC-ACCT-ID} at {@code app/cbl/COCRDSLC.cbl:465}, or set to
     * {@code LOW-VALUES} at {@code L463} when the commarea carried no account. May also hold the
     * single {@code '*'} blank marker, space-padded, from {@code L543}.
     *
     * <p>Not masked and not redacted: the COBOL sends the value as it is, and altering that would change
     * observable output.
     *
     * @return the image, exactly {@value #ACCTSIDO_LENGTH} characters
     */
    @JsonProperty("acctsid")
    public String getAcctsido() {
        return acctsido;
    }

    /**
     * Stores {@code ACCTSIDO}, padded or right-truncated to {@value #ACCTSIDO_LENGTH} characters.
     *
     * @param acctsido the sending value
     * @throws NullPointerException if {@code acctsido} is {@code null}
     */
    public void setAcctsido(String acctsido) {
        this.acctsido = moveIntoPicX(acctsido, ScreenField.ACCTSID.cobolName(), ACCTSIDO_LENGTH);
    }

    /**
     * {@code CARDSIDO PIC X(16)} - the full sixteen-digit card number, in the clear exactly as the
     * symbolic map carries it. Populated from {@code CC-CARD-NUM} at {@code app/cbl/COCRDSLC.cbl:471},
     * or {@code LOW-VALUES} at {@code L469}, and may hold the {@code '*'} marker from {@code L549}.
     *
     * <p>Not masked and not redacted, for the same reason as {@link #getAcctsido()}.
     *
     * @return the image, exactly {@value #CARDSIDO_LENGTH} characters
     */
    @JsonProperty("cardsid")
    public String getCardsido() {
        return cardsido;
    }

    /**
     * Stores {@code CARDSIDO}, padded or right-truncated to {@value #CARDSIDO_LENGTH} characters.
     *
     * @param cardsido the sending value
     * @throws NullPointerException if {@code cardsido} is {@code null}
     */
    public void setCardsido(String cardsido) {
        this.cardsido = moveIntoPicX(cardsido, ScreenField.CARDSID.cobolName(), CARDSIDO_LENGTH);
    }

    /**
     * {@code CRDNAMEO PIC X(50)} - the embossed name. Populated from {@code CARD-EMBOSSED-NAME} at
     * {@code app/cbl/COCRDSLC.cbl:476}, and only inside the {@code IF FOUND-CARDS-FOR-ACCOUNT} guard, so
     * it stays {@code LOW-VALUES} when no card was found.
     *
     * @return the image, exactly {@value #CRDNAMEO_LENGTH} characters
     */
    @JsonProperty("crdname")
    public String getCrdnameo() {
        return crdnameo;
    }

    /**
     * Stores {@code CRDNAMEO}, padded or right-truncated to {@value #CRDNAMEO_LENGTH} characters.
     *
     * @param crdnameo the sending value
     * @throws NullPointerException if {@code crdnameo} is {@code null}
     */
    public void setCrdnameo(String crdnameo) {
        this.crdnameo = moveIntoPicX(crdnameo, ScreenField.CRDNAME.cobolName(), CRDNAMEO_LENGTH);
    }

    /**
     * {@code CRDSTCDO PIC X(1)} - the active status shown after {@code 'Card Active Y/N   : '}.
     * Populated from {@code CARD-ACTIVE-STATUS} at {@code app/cbl/COCRDSLC.cbl:484}.
     *
     * @return the image, exactly {@value #CRDSTCDO_LENGTH} character
     */
    @JsonProperty("crdstcd")
    public String getCrdstcdo() {
        return crdstcdo;
    }

    /**
     * Stores {@code CRDSTCDO}, padded or right-truncated to {@value #CRDSTCDO_LENGTH} character.
     *
     * @param crdstcdo the sending value
     * @throws NullPointerException if {@code crdstcdo} is {@code null}
     */
    public void setCrdstcdo(String crdstcdo) {
        this.crdstcdo = moveIntoPicX(crdstcdo, ScreenField.CRDSTCD.cobolName(), CRDSTCDO_LENGTH);
    }

    /**
     * {@code EXPMONO PIC X(2)} - the expiry month. Populated from {@code CARD-EXPIRY-MONTH} at
     * {@code app/cbl/COCRDSLC.cbl:480}.
     *
     * @return the image, exactly {@value #EXPMONO_LENGTH} characters
     */
    @JsonProperty("expmon")
    public String getExpmono() {
        return expmono;
    }

    /**
     * Stores {@code EXPMONO}, padded or right-truncated to {@value #EXPMONO_LENGTH} characters.
     *
     * @param expmono the sending value
     * @throws NullPointerException if {@code expmono} is {@code null}
     */
    public void setExpmono(String expmono) {
        this.expmono = moveIntoPicX(expmono, ScreenField.EXPMON.cobolName(), EXPMONO_LENGTH);
    }

    /**
     * {@code EXPYEARO PIC X(4)} - the expiry year. Populated from {@code CARD-EXPIRY-YEAR} at
     * {@code app/cbl/COCRDSLC.cbl:482}. There is no expiry-day companion on this map.
     *
     * @return the image, exactly {@value #EXPYEARO_LENGTH} characters
     */
    @JsonProperty("expyear")
    public String getExpyearo() {
        return expyearo;
    }

    /**
     * Stores {@code EXPYEARO}, padded or right-truncated to {@value #EXPYEARO_LENGTH} characters.
     *
     * @param expyearo the sending value
     * @throws NullPointerException if {@code expyearo} is {@code null}
     */
    public void setExpyearo(String expyearo) {
        this.expyearo = moveIntoPicX(expyearo, ScreenField.EXPYEAR.cobolName(), EXPYEARO_LENGTH);
    }

    /**
     * {@code INFOMSGO PIC X(40)} - the informational line. Populated from
     * {@code WS-INFO-MSG PIC X(40)} at {@code app/cbl/COCRDSLC.cbl:496}, a same-width move; a
     * {@code CSMSG01Y} standard message would instead lose its last
     * {@value #INFOMSGO_STANDARD_MESSAGE_TRUNCATION} characters.
     *
     * @return the image, exactly {@value #INFOMSGO_LENGTH} characters
     */
    @JsonProperty("infomsg")
    public String getInfomsgo() {
        return infomsgo;
    }

    /**
     * Stores {@code INFOMSGO}, padded or right-truncated to {@value #INFOMSGO_LENGTH} characters.
     *
     * @param infomsgo the sending value
     * @throws NullPointerException if {@code infomsgo} is {@code null}
     */
    public void setInfomsgo(String infomsgo) {
        this.infomsgo = moveIntoPicX(infomsgo, ScreenField.INFOMSG.cobolName(), INFOMSGO_LENGTH);
    }

    /**
     * {@code ERRMSGO PIC X(80)} - the error line. Populated from {@code WS-RETURN-MSG PIC X(75)} at
     * {@code app/cbl/COCRDSLC.cbl:494}, so the value gains five trailing spaces; a {@code CSMSG01Y}
     * standard message would gain {@value #ERRMSGO_STANDARD_MESSAGE_PADDING}.
     *
     * @return the image, exactly {@value #ERRMSGO_LENGTH} characters
     */
    @JsonProperty("errmsg")
    public String getErrmsgo() {
        return errmsgo;
    }

    /**
     * Stores {@code ERRMSGO}, padded or right-truncated to {@value #ERRMSGO_LENGTH} characters.
     *
     * @param errmsgo the sending value
     * @throws NullPointerException if {@code errmsgo} is {@code null}
     */
    public void setErrmsgo(String errmsgo) {
        this.errmsgo = moveIntoPicX(errmsgo, ScreenField.ERRMSG.cobolName(), ERRMSGO_LENGTH);
    }

    /**
     * {@code FKEYSO PIC X(75)} - the function-key legend. {@code COCRDSLC} never writes it, so it holds
     * {@code LOW-VALUES} unless a caller assigns {@link #BMS_INITIAL_FKEYS}, which is the literal the
     * mapset paints.
     *
     * @return the image, exactly {@value #FKEYSO_LENGTH} characters
     */
    @JsonProperty("fkeys")
    public String getFkeyso() {
        return fkeyso;
    }

    /**
     * Stores {@code FKEYSO}, padded or right-truncated to {@value #FKEYSO_LENGTH} characters.
     *
     * @param fkeyso the sending value
     * @throws NullPointerException if {@code fkeyso} is {@code null}
     */
    public void setFkeyso(String fkeyso) {
        this.fkeyso = moveIntoPicX(fkeyso, ScreenField.FKEYS.cobolName(), FKEYSO_LENGTH);
    }

    // =================================================================================================
    // The same fifteen items addressed by ScreenField.
    //
    // COBOL writes ACCTSIDO OF CCRDSLAO - an item qualified by its group - and the highlight rule writes
    // (SCRNVAR2)O OF (MAPNAME3)O, an item chosen at generation time. These two methods are the Java
    // equivalent of that qualified reference, and they are what lets the highlight rule and the parity
    // differ address a field they were handed rather than one they were compiled against.
    // =================================================================================================

    /**
     * Reads one data item by field.
     *
     * @param field the field to read
     * @return the item's image, exactly {@code field.length()} characters
     * @throws NullPointerException if {@code field} is {@code null}
     */
    public String get(ScreenField field) {
        Objects.requireNonNull(field, "A screen field is required to read a data item");
        return switch (field) {
            case TRNNAME -> trnnameo;
            case TITLE01 -> title01o;
            case CURDATE -> curdateo;
            case PGMNAME -> pgmnameo;
            case TITLE02 -> title02o;
            case CURTIME -> curtimeo;
            case ACCTSID -> acctsido;
            case CARDSID -> cardsido;
            case CRDNAME -> crdnameo;
            case CRDSTCD -> crdstcdo;
            case EXPMON -> expmono;
            case EXPYEAR -> expyearo;
            case INFOMSG -> infomsgo;
            case ERRMSG -> errmsgo;
            case FKEYS -> fkeyso;
        };
    }

    /**
     * Stores one data item by field, applying the same {@code PIC X} move rule the named setter applies.
     *
     * @param field the field to write
     * @param value the sending value
     * @throws NullPointerException if {@code field} or {@code value} is {@code null}
     */
    public void set(ScreenField field, String value) {
        Objects.requireNonNull(field, "A screen field is required to write a data item");
        switch (field) {
            case TRNNAME -> setTrnnameo(value);
            case TITLE01 -> setTitle01o(value);
            case CURDATE -> setCurdateo(value);
            case PGMNAME -> setPgmnameo(value);
            case TITLE02 -> setTitle02o(value);
            case CURTIME -> setCurtimeo(value);
            case ACCTSID -> setAcctsido(value);
            case CARDSID -> setCardsido(value);
            case CRDNAME -> setCrdnameo(value);
            case CRDSTCD -> setCrdstcdo(value);
            case EXPMON -> setExpmono(value);
            case EXPYEAR -> setExpyearo(value);
            case INFOMSG -> setInfomsgo(value);
            case ERRMSG -> setErrmsgo(value);
            case FKEYS -> setFkeyso(value);
        }
    }

    // =================================================================================================
    // The attribute quads.
    // =================================================================================================

    /**
     * The mutable attribute quad of one field: its {@code xxxC}, {@code xxxP}, {@code xxxH} and
     * {@code xxxV} items.
     *
     * <p>Returned mutable on purpose. {@code COCRDSLC} writes these items after the data items are
     * already in place - {@code 1300-SETUP-SCREEN-ATTRS} runs after {@code 1200-SETUP-SCREEN-VARS} - and
     * the highlight rule repaints a field once its edit has failed. This accessor is the addressable
     * {@code xxxC} item that {@link FieldAttributeSetter}'s documentation expects the payload to provide.
     *
     * @param field the field whose quad is wanted
     * @return that field's quad, never {@code null} and never a copy
     * @throws NullPointerException if {@code field} is {@code null}
     */
    @JsonIgnore
    public FieldAttributes attributes(ScreenField field) {
        Objects.requireNonNull(field, "A screen field is required to reach its attribute quad");
        return attributes.get(field);
    }

    /**
     * All fifteen attribute quads, keyed by field.
     *
     * <p>The map itself is unmodifiable - no entry can be added, removed or replaced, so the key space
     * stays closed at the {@value #FIELD_COUNT} declared fields - while each quad it exposes remains
     * mutable, which is the whole point of holding them. It is a live view, so a quad repainted through
     * {@link #attributes(ScreenField)} is visible here immediately.
     *
     * @return an unmodifiable, live view of the fifteen quads; never {@code null}
     */
    @JsonIgnore
    public Map<ScreenField, FieldAttributes> attributeQuads() {
        return Collections.unmodifiableMap(attributes);
    }

    /**
     * The sixty attribute items as a flat map from COBOL item name to byte, in the copybook's offset
     * order: {@code TRNNAMEC}, {@code TRNNAMEP}, {@code TRNNAMEH}, {@code TRNNAMEV}, then
     * {@code TITLE01C} and so on.
     *
     * <p>A snapshot for the parity differ and for diagnostics, so later repainting does not change a
     * comparison already taken.
     *
     * @return an unmodifiable, insertion-ordered snapshot of 60 entries; never {@code null}
     */
    @JsonIgnore
    public Map<String, Byte> attributeItems() {
        Map<String, Byte> items = new LinkedHashMap<>();
        for (ScreenField field : ScreenField.values()) {
            FieldAttributes quad = attributes.get(field);
            items.put(field.colourItemName(), quad.getColour());
            items.put(field.psItemName(), quad.getPs());
            items.put(field.hilightItemName(), quad.getHilight());
            items.put(field.validnItemName(), quad.getValidn());
        }
        return Collections.unmodifiableMap(items);
    }

    /**
     * The fifteen data items as a map from COBOL item name to image, in the copybook's declaration order.
     *
     * <p>This is the fingerprint the parity harness compares field by field. The keys are the copybook's
     * own names - {@code TRNNAMEO}, {@code TITLE01O} and the rest - and the values are untrimmed images
     * at their declared widths, because the padding is part of what a {@code PIC X} field holds and
     * trimming it would discard bytes the differ exists to compare.
     *
     * @return an unmodifiable, insertion-ordered snapshot of {@value #FIELD_COUNT} entries; never
     *         {@code null}
     */
    @JsonIgnore
    public Map<String, String> fieldImages() {
        Map<String, String> images = new LinkedHashMap<>();
        for (ScreenField field : ScreenField.values()) {
            images.put(field.cobolName(), get(field));
        }
        return Collections.unmodifiableMap(images);
    }

    // =================================================================================================
    // The populate operations COCRDSLC actually performs. Each reproduces named MOVE statements and
    // nothing else; no operation here has a counterpart the program does not execute.
    // =================================================================================================

    /**
     * Reproduces {@code app/cbl/COCRDSLC.cbl:432-433}:
     * {@code MOVE CCDA-TITLE01 TO TITLE01O} and {@code MOVE CCDA-TITLE02 TO TITLE02O}.
     *
     * <p>The two literals come from {@link ScreenTitles}, which transcribes {@code app/cpy/COTTL01Y.cpy}
     * character for character including the leading and trailing spaces that make each exactly
     * {@value #TITLE01O_LENGTH} wide. They are not restated here, so there is one place in the system
     * where a heading's bytes are defined.
     */
    public void applyScreenTitles() {
        setTitle01o(ScreenTitles.CCDA_TITLE01);
        setTitle02o(ScreenTitles.CCDA_TITLE02);
    }

    /**
     * Reproduces {@code app/cbl/COCRDSLC.cbl:434-435}:
     * {@code MOVE LIT-THISTRANID TO TRNNAMEO} and {@code MOVE LIT-THISPGM TO PGMNAMEO}.
     */
    public void applyScreenIdentity() {
        setTrnnameo(THIS_TRANID);
        setPgmnameo(THIS_PROGRAM);
    }

    /**
     * Reproduces {@code app/cbl/COCRDSLC.cbl:443} and {@code L449}:
     * {@code MOVE WS-CURDATE-MM-DD-YY TO CURDATEO} and {@code MOVE WS-CURTIME-HH-MM-SS TO CURTIMEO}.
     *
     * <p>Both values are read from the supplied header rather than from a clock, so a parity case can fix
     * the instant and get a byte-identical screen. Each is already eight characters -
     * {@code mm/dd/yy} and {@code hh:mm:ss} - which is exactly what the two receivers declare.
     *
     * @param dateHeader the {@code CSDAT01Y} header {@code COCRDSLC} copies at {@code L218}
     * @throws NullPointerException if {@code dateHeader} is {@code null}
     */
    public void applyDateHeader(DateHeader dateHeader) {
        Objects.requireNonNull(dateHeader, "A date header is required; this payload never reads a clock "
                + "of its own, so the instant must be supplied by the caller");
        setCurdateo(dateHeader.wsCurdateMmDdYy());
        setCurtimeo(dateHeader.wsCurtimeHhMmSs());
    }

    // =================================================================================================
    // CSSETATY, applied.
    // =================================================================================================

    /**
     * Applies a highlight decision to one field: {@code DFHRED} into its {@code xxxC} item and, when the
     * decision says so, {@code '*'} into its {@code xxxO} item.
     *
     * <p>This is the second half of {@code app/cpy/CSSETATY.cpy:L18-L27}, which {@code COCRDSLC} spells
     * out inline at {@code L542-L551}. The first half - deciding whether either move happens, and in
     * particular the {@code AND CDEMO-PGM-REENTER} guard - belongs to {@link FieldAttributeSetter} and is
     * not re-implemented here. The two moves are performed in the copybook's order, and the nesting is
     * respected because {@link FieldHighlight} cannot report an assigned output item without an assigned
     * colour item.
     *
     * <p>The asterisk goes through the ordinary {@code PIC X} store, so it arrives space-padded to the
     * receiver's width - {@code "*"} into {@code ACCTSIDO PIC X(11)} becomes an asterisk and ten spaces,
     * which is what {@code MOVE '*' TO ACCTSIDO} produces.
     *
     * @param field     the field to repaint
     * @param highlight the decision to apply; {@link FieldHighlight#untouched()} decisions change nothing
     * @throws NullPointerException     if {@code field} or {@code highlight} is {@code null}
     * @throws IllegalArgumentException if the decision names a different field, which would mean it was
     *                                  resolved for one field and applied to another
     */
    public void applyHighlight(ScreenField field, FieldHighlight highlight) {
        Objects.requireNonNull(field, "A screen field is required to apply a highlight");
        Objects.requireNonNull(highlight, "A highlight decision is required; use "
                + "FieldHighlight.none(...) to express 'change nothing'");

        String decidedFor = highlight.screenFieldPrefix();
        if (!decidedFor.isEmpty() && !decidedFor.equals(field.dfhmdfLabel())) {
            throw new IllegalArgumentException("The highlight was resolved for " + decidedFor
                    + " but is being applied to " + field.dfhmdfLabel()
                    + "; CSSETATY qualifies both of its moves with one field, so a decision cannot be "
                    + "carried across fields");
        }

        // CSSETATY.cpy:L21-L22 - MOVE DFHRED TO (SCRNVAR2)C OF (MAPNAME3)O
        if (highlight.colourItemAssigned()) {
            attributes(field).setColour(highlight.colourItemValue());
        }
        // CSSETATY.cpy:L24-L25 - MOVE '*' TO (SCRNVAR2)O OF (MAPNAME3)O, nested inside the move above
        if (highlight.outputItemAssigned()) {
            set(field, highlight.outputItemValue());
        }
    }

    /**
     * Resolves the highlight for one field and applies it, in one call.
     *
     * <p>The map name passed to {@link FieldAttributeSetter} is {@link #MAP_NAME}, the seven-character
     * {@code CCRDSLA}, and the field name is the field's {@code DFHMDF} label - the two tokens
     * {@code CSSETATY} substitutes as {@code (MAPNAME3)} and {@code (SCRNVAR2)}. Because the decision
     * carries the {@code CDEMO-PGM-REENTER} guard, a failing field on first entry is left exactly as the
     * program left it: not red, and not marked with an asterisk.
     *
     * @param field   the field whose edit outcome is being reported
     * @param state   the field's validation state
     * @param reenter {@code true} when {@code CDEMO-PGM-REENTER} holds, that is when
     *                {@code CDEMO-PGM-CONTEXT} is {@link NavigationContext#PGM_CONTEXT_REENTER}
     * @return the decision that was applied, so a caller can log or assert it; never {@code null}
     * @throws NullPointerException if {@code field} or {@code state} is {@code null}
     */
    public FieldHighlight applyHighlight(ScreenField field, FieldValidationState state,
            boolean reenter) {
        Objects.requireNonNull(field, "A screen field is required to resolve a highlight");
        Objects.requireNonNull(state, "A validation state is required; use "
                + "FieldValidationState.of(notOk, blank) to derive one from the two 88-levels");

        FieldHighlight highlight =
                FieldAttributeSetter.resolve(state, reenter, field.dfhmdfLabel(), MAP_NAME);
        applyHighlight(field, highlight);
        return highlight;
    }

    // =================================================================================================
    // The conversation carriers.
    //
    // Rule R6, gate G37: everything CICS kept between two pseudo-conversational turns travels here, in the
    // payload. There is no HttpSession, no @SessionAttributes, no server-side cache, no static map and no
    // ThreadLocal anywhere in this class, and the client is required to send these values back.
    // =================================================================================================

    /**
     * The {@code CVCRD01Y} work area - {@code CCARD-AID}, the next-screen triple, the two message lines
     * and the three search identifiers - which {@code COCRDSLC} copies at
     * {@code app/cbl/COCRDSLC.cbl:194}.
     *
     * <p>Returned by reference and mutable, because the program mutates it in place: it moves
     * {@code WS-RETURN-MSG} into {@code CCARD-ERROR-MSG} at {@code L587} and the next-screen triple at
     * {@code L588-L590}, all after the map has been populated.
     *
     * @return the work area, never {@code null}
     */
    public CardScreenState getCardScreenState() {
        return cardScreenState;
    }

    /**
     * Replaces the {@code CVCRD01Y} work area.
     *
     * @param cardScreenState the work area to carry
     * @throws NullPointerException if {@code cardScreenState} is {@code null}; the program always has
     *                              a work area, having issued {@code INITIALIZE CC-WORK-AREA} at
     *                              {@code app/cbl/COCRDSLC.cbl:254}
     */
    public void setCardScreenState(CardScreenState cardScreenState) {
        this.cardScreenState = Objects.requireNonNull(cardScreenState,
                "A card screen state is required; COCRDSLC always has an initialised CC-WORK-AREA");
    }

    /**
     * {@code WS-THIS-PROGCOMMAREA} as this response returns it - the twelve bytes
     * {@code app/cbl/COCRDSLC.cbl:397-400} appends to the communication area.
     *
     * @return the trailer; never {@code null}
     */
    public ThisProgCommarea getThisProgCommarea() {
        return thisProgCommarea;
    }

    /**
     * Sets {@code WS-THIS-PROGCOMMAREA}.
     *
     * <p>{@code null} is normalised to {@link ThisProgCommarea#initialized()} rather than stored: the
     * area has no absent state, and every reader would otherwise have to defend against one.
     *
     * @param thisProgCommarea the trailer, or {@code null} for the initialised twelve spaces
     */
    public void setThisProgCommarea(ThisProgCommarea thisProgCommarea) {
        this.thisProgCommarea =
                thisProgCommarea == null ? ThisProgCommarea.initialized() : thisProgCommarea;
    }

    /**
     * This screen's presentation metadata, projected into the shared envelope every online response
     * publishes: the fifteen attribute quads, keyed by {@code DFHMDF} label in copybook order, and the
     * colour of the error line.
     *
     * <p>The quads are the {@code xxxC}, {@code xxxP}, {@code xxxH} and {@code xxxV} items of
     * {@code app/cpy-bms/COCRDSL.CPY} - metadata by the copybook's own declaration, and the items
     * {@code 1300-SETUP-SCREEN-ATTRS} and the {@code app/cpy/CSSETATY.cpy} highlight rule write into.
     * They are not payload fields and must not be siblings of the fifteen values, but a client that
     * cannot see them cannot repaint a field the program turned red, so they travel under
     * {@code screenMetadata} instead of not travelling at all.
     *
     * <p>{@code messageColour} is {@code ERRMSGC}, taken from the quads rather than stored twice.
     * {@code cursorField} is {@code getCursorField()} - the {@code DFHMDF} label
     * {@code 1300-SETUP-SCREEN-ATTRS} aimed the cursor at. It is {@code null} only until that paragraph
     * has run, because {@code app/cbl/COCRDSLC.cbl:514-524} always issues exactly one {@code MOVE -1}
     * and the {@code SEND MAP} at {@code :569-576} carries {@code CURSOR}. On a path that never paints
     * the screen - the {@code XCTL} at {@code :331} and the {@code SEND TEXT} at {@code :839} - no
     * cursor request was made and {@code null} is the faithful report rather than a filled-in slot.
     *
     * @return the metadata; never {@code null}
     */
    @JsonIgnore
    public ScreenMetadata screenMetadata() {
        Map<String, ScreenMetadata.FieldMetadata> quads = new LinkedHashMap<>();
        for (ScreenField field : ScreenField.values()) {
            FieldAttributes quad = attributes.get(field);
            quads.put(field.dfhmdfLabel(),
                    ScreenMetadata.FieldMetadata.of(quad.getColour(),
                            quad.getPs(),
                            quad.getHilight(),
                            quad.getValidn()));
        }
        return ScreenMetadata.of(cursorField,
                attributes.get(ScreenField.ERRMSG).getColour(),
                false,
                quads);
    }

    /**
     * The {@code DFHMDF} label of the field {@code 1300-SETUP-SCREEN-ATTRS} aimed the cursor at, or
     * {@code null} when this turn made no cursor request.
     *
     * <p>The label, not the {@code xxxL} item name, because that is the shape
     * {@link ScreenMetadata#cursorField()} publishes across all seventeen screens. A reader who needs the
     * symbolic-map length item appends the {@code L} suffix BMS appends, so {@code ACCTSID} is the
     * {@code MOVE -1} target {@code ACCTSIDL}.
     *
     * <p>{@link JsonIgnore}, and deliberately: this is not one of the fifteen {@code xxxI}-derived
     * members, so it does not belong beside them on the wire. It reaches a client through
     * {@link #screenMetadata()}, exactly as the sibling {@code CardListResponse} publishes its own.
     *
     * @return the label, for example {@code ACCTSID}; or {@code null} for no cursor request
     */
    @JsonIgnore
    public String getCursorField() {
        return cursorField;
    }

    /**
     * Records where {@code MOVE -1 TO xxxL OF CCRDSLAI} aimed the cursor, so
     * {@link #screenMetadata()} can publish it.
     *
     * <p>Stored verbatim and never validated against the fifteen labels: the value is written by
     * {@code 1300-SETUP-SCREEN-ATTRS} from a {@link ScreenField} constant, so an invented label cannot
     * arrive from inside this module, and rejecting one from outside it would add a failure mode the
     * screen does not have.
     *
     * @param cursorField the {@code DFHMDF} label, or {@code null} for no cursor request
     */
    public void setCursorField(String cursorField) {
        this.cursorField = cursorField;
    }

    /**
     * The {@code CARDDEMO-COMMAREA} that {@code COCRDSLC} copies at {@code app/cbl/COCRDSLC.cbl:198} and
     * passes on the {@code XCTL} at {@code L333}: the from and to transaction and program, the user
     * identity and type, {@code CDEMO-PGM-CONTEXT}, and the carried customer, account and card
     * identifiers.
     *
     * @return the commarea, never {@code null}
     */
    public NavigationContext getNavigationContext() {
        return navigationContext;
    }

    /**
     * Replaces the commarea. {@link NavigationContext} is a record, so the reference is stored as given
     * and no copy is needed.
     *
     * @param navigationContext the commarea to carry
     * @throws NullPointerException if {@code navigationContext} is {@code null}; use
     *                             {@link NavigationContext#empty()} for the {@code EIBCALEN = 0} case
     *                             the program tests at {@code app/cbl/COCRDSLC.cbl:458}
     */
    public void setNavigationContext(NavigationContext navigationContext) {
        this.navigationContext = Objects.requireNonNull(navigationContext,
                "A navigation context is required; use NavigationContext.empty() when no commarea was "
                + "received");
    }

    /**
     * The program the client should call next - the substitute for
     * {@code EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM)} at {@code app/cbl/COCRDSLC.cbl:331}.
     *
     * @return the image, exactly {@value #NEXT_PROGRAM_LENGTH} characters
     */
    public String getNextProgram() {
        return nextProgram;
    }

    /**
     * Stores the next program, padded or right-truncated to {@value #NEXT_PROGRAM_LENGTH} characters.
     *
     * <p>An opaque token. It is not checked against a list of known programs, not case-normalised and not
     * trimmed - see the class documentation on the preserved {@code LIT-CCLISTMAP} defect.
     *
     * @param nextProgram the program name
     * @throws NullPointerException if {@code nextProgram} is {@code null}
     */
    public void setNextProgram(String nextProgram) {
        this.nextProgram =
                moveIntoPicX(nextProgram, "CCARD-NEXT-PROG", NEXT_PROGRAM_LENGTH);
    }

    /**
     * The mapset of the next screen.
     *
     * @return the image, exactly {@value #NEXT_MAPSET_LENGTH} characters
     */
    public String getNextMapset() {
        return nextMapset;
    }

    /**
     * Stores the next mapset, padded or right-truncated to {@value #NEXT_MAPSET_LENGTH} characters.
     *
     * <p>Seven, not eight. {@code LIT-THISMAPSET} is {@code PIC X(8) VALUE 'COCRDSL '} and loses its
     * trailing space on the way into {@code CCARD-NEXT-MAPSET PIC X(7)}; this store reproduces that.
     * An opaque token, as {@link #setNextProgram(String)}.
     *
     * @param nextMapset the mapset name
     * @throws NullPointerException if {@code nextMapset} is {@code null}
     */
    public void setNextMapset(String nextMapset) {
        this.nextMapset = moveIntoPicX(nextMapset, "CCARD-NEXT-MAPSET", NEXT_MAPSET_LENGTH);
    }

    /**
     * The map of the next screen.
     *
     * @return the image, exactly {@value #NEXT_MAP_LENGTH} characters
     */
    public String getNextMap() {
        return nextMap;
    }

    /**
     * Stores the next map, padded or right-truncated to {@value #NEXT_MAP_LENGTH} characters.
     *
     * <p>An opaque token, and the one the preserved {@code LIT-CCLISTMAP} defect travels in: a caller
     * routing back to the card list legitimately sends {@code "CCRDSLA"} here, because that is the value
     * {@code app/cbl/COCRDSLC.cbl:178} declares. Rejecting or repairing it would discard behaviour this
     * migration is required to preserve.
     *
     * @param nextMap the map name
     * @throws NullPointerException if {@code nextMap} is {@code null}
     */
    public void setNextMap(String nextMap) {
        this.nextMap = moveIntoPicX(nextMap, "CCARD-NEXT-MAP", NEXT_MAP_LENGTH);
    }

    /**
     * Sets the next-screen triple in one call.
     *
     * @param nextProgram the program name
     * @param nextMapset  the mapset name
     * @param nextMap     the map name
     * @throws NullPointerException if any argument is {@code null}
     */
    public void applyNextTarget(String nextProgram, String nextMapset, String nextMap) {
        setNextProgram(nextProgram);
        setNextMapset(nextMapset);
        setNextMap(nextMap);
    }

    /**
     * Reproduces {@code app/cbl/COCRDSLC.cbl:588-590}, where the program names <em>itself</em> as the next
     * target so the user stays on the card-detail screen: {@code MOVE LIT-THISPGM TO CCARD-NEXT-PROG},
     * {@code MOVE LIT-THISMAPSET TO CCARD-NEXT-MAPSET} and {@code MOVE LIT-THISMAP TO CCARD-NEXT-MAP}.
     * {@code 1400-SEND-SCREEN} does the mapset and map halves again at {@code L565-L566}.
     */
    public void applyThisScreenAsNextTarget() {
        applyNextTarget(THIS_PROGRAM, THIS_MAPSET, MAP_NAME);
    }

    // =================================================================================================
    // Value semantics and diagnostics.
    // =================================================================================================

    /**
     * Renders every field's provenance and current image, one line each, for a parity report or a review
     * note. Attribute quads are included, because a field painted red is part of what the screen says.
     *
     * <p>Not masked: the card number and account identifier appear as they do in the payload, since a
     * diagnostic that hides them would make a card-number difference impossible to trace.
     *
     * @return the description; never {@code null} and never empty
     */
    @JsonIgnore
    public String describe() {
        StringBuilder rendered = new StringBuilder(2048)
                .append("CCRDSLAO (")
                .append(GROUP_LENGTH)
                .append(" bytes, ")
                .append(FIELD_COUNT)
                .append(" named DFHMDF fields of 31, map ")
                .append(MAP_NAME)
                .append(", program ")
                .append(THIS_PROGRAM)
                .append(", transaction ")
                .append(THIS_TRANID)
                .append(')');

        for (ScreenField field : ScreenField.values()) {
            rendered.append(System.lineSeparator())
                    .append("  ")
                    .append(field.describe())
                    .append(" = [")
                    .append(REDACTED_FIELDS.contains(field) ? redacted(get(field)) : get(field))
                    .append("] ")
                    .append(attributes.get(field).describe());
        }

        return rendered.append(System.lineSeparator())
                .append("  next: program=[")
                .append(nextProgram)
                .append("] mapset=[")
                .append(nextMapset)
                .append("] map=[")
                .append(nextMap)
                .append(']')
                .toString();
    }

    /**
     * Two payloads are equal when all fifteen data items, all fifteen attribute quads, both carriers and
     * the next-screen triple are equal.
     *
     * <p>Provided because a parity assertion compares a produced payload against an expected one, and
     * because {@link #fieldImages()} alone would ignore the attribute quads - and a field that should have
     * been painted red but was not is a genuine difference.
     *
     * @param other the object to compare with
     * @return {@code true} when every member is equal
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CardSelectResponse response)) {
            return false;
        }
        return trnnameo.equals(response.trnnameo)
                && title01o.equals(response.title01o)
                && curdateo.equals(response.curdateo)
                && pgmnameo.equals(response.pgmnameo)
                && title02o.equals(response.title02o)
                && curtimeo.equals(response.curtimeo)
                && acctsido.equals(response.acctsido)
                && cardsido.equals(response.cardsido)
                && crdnameo.equals(response.crdnameo)
                && crdstcdo.equals(response.crdstcdo)
                && expmono.equals(response.expmono)
                && expyearo.equals(response.expyearo)
                && infomsgo.equals(response.infomsgo)
                && errmsgo.equals(response.errmsgo)
                && fkeyso.equals(response.fkeyso)
                && attributes.equals(response.attributes)
                && cardScreenState.equals(response.cardScreenState)
                && navigationContext.equals(response.navigationContext)
                && nextProgram.equals(response.nextProgram)
                && nextMapset.equals(response.nextMapset)
                && nextMap.equals(response.nextMap);
    }

    /**
     * A hash consistent with {@link #equals(Object)} over the same members.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(trnnameo, title01o, curdateo, pgmnameo, title02o, curtimeo, acctsido,
                cardsido, crdnameo, crdstcdo, expmono, expyearo, infomsgo, errmsgo, fkeyso, attributes,
                cardScreenState, navigationContext, nextProgram, nextMapset, nextMap);
    }

    /**
     * A single-line rendering naming the map and the four fields that identify the screen's subject.
     *
     * <p><strong>{@code ACCTSIDO} and {@code CARDSIDO} are redacted here.</strong> They are an account
     * identifier and a sixteen-digit card number, and this method's output goes to log lines and test
     * failure messages - somewhere nobody chose to put them. Each shows as
     * {@value #REDACTED_VALUE} with its actual length, which is what a diagnostic is read for.
     * {@code CRDSTCDO} is a one-character status and {@code ERRMSGO} is a message, so both render
     * verbatim.
     *
     * <p>This changes no behaviour of the screen: the JSON body, {@link #fieldImages()} and
     * {@link #getCardsido()} all carry the number in the clear, exactly as the 3270 displays it.
     * {@link #describe()} redacts the same fields for the same reason.
     *
     * @return the rendering; never {@code null} and never empty
     */
    @Override
    public String toString() {
        return "CardSelectResponse[map=" + MAP_NAME
                + ", ACCTSIDO=" + SensitiveDiagnostics.maskIdentifier(acctsido)
                + ", CARDSIDO=" + SensitiveDiagnostics.maskPan(cardsido)
                + ", CRDSTCDO=" + crdstcdo
                + ", ERRMSGO=" + errmsgo
                + ']';
    }

    /**
     * What {@link #toString()} and {@link #describe()} substitute for a redacted value: {@value}.
     *
     * <p>The same marker every other payload in this module uses, so a log line reads consistently
     * whichever screen produced it.
     */
    private static final String REDACTED_VALUE = "[REDACTED]";

    /**
     * The three fields the two diagnostic renderings redact: the account identifier, the card number
     * and the cardholder's name.
     *
     * <p>Scoped to those three. {@code CRDSTCDO} is a one-character status, {@code EXPMONO} and
     * {@code EXPYEARO} are an expiry that identifies nobody alone, and the rest are titles, messages
     * and the function-key line. Read only by the diagnostics; the payload carries all three in the
     * clear.
     */
    private static final Set<ScreenField> REDACTED_FIELDS =
            Collections.unmodifiableSet(EnumSet.of(ScreenField.ACCTSID, ScreenField.CARDSID,
                    ScreenField.CRDNAME));

    /**
     * A value replaced by {@value #REDACTED_VALUE} and its length, for a diagnostic rendering.
     *
     * @param value the value being withheld; never {@code null}
     * @return the marker followed by the value's actual length in parentheses
     */
    private static String redacted(String value) {
        return REDACTED_VALUE + "(" + value.length() + ")";
    }
}
