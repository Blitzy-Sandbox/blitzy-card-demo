package com.vsergeychik.carddemo.admin.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.NavigationContext;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The outbound payload of {@code GET /api/admin/menu}: a field-for-field projection of the
 * <em>output</em> view of the admin menu screen.
 *
 * <table border="1">
 *   <caption>What this type is the Java form of</caption>
 *   <tr><th>CICS artefact</th><th>Name</th><th>Source</th></tr>
 *   <tr><td>Transaction</td><td>{@value #TRANSACTION_ID}</td><td>{@code app/csd/CARDDEMO.CSD}</td></tr>
 *   <tr><td>Program</td><td>{@value #PROGRAM_NAME}</td><td>{@code app/cbl/COADM01C.cbl}</td></tr>
 *   <tr><td>Mapset</td><td>{@value #MAPSET_NAME}</td><td>{@code app/bms/COADM01.bms}</td></tr>
 *   <tr><td>Map</td><td>{@value #MAP_NAME}</td><td>{@code app/bms/COADM01.bms} {@code DFHMDI}</td></tr>
 *   <tr><td>Symbolic map, output view</td><td>{@code COADM1AO}</td>
 *       <td>{@code app/cpy-bms/COADM01.CPY:139}</td></tr>
 * </table>
 *
 * <p>This is <strong>pure data</strong>. It holds no business logic, performs no I/O, builds no menu,
 * composes no option line, validates no option number, decides no colour and renders no fixed-width
 * image. {@code admin.AdminMenuService} owns every one of those decisions and hands this type the
 * already-finished strings; {@code admin.AdminMenuController} owns the one width narrowing the COBOL
 * performs on the way out (see <em>The {@code errMsg} width is 78</em> below).
 *
 * <h2>Why the members are the {@code xxxO} items and nothing else</h2>
 *
 * {@code app/cpy-bms/COADM01.CPY:139} opens {@code 01 COADM1AO REDEFINES COADM1AI.} with a
 * {@value #TIOAPFX_FILLER_LENGTH}-byte {@code TIOAPFX} {@code FILLER}, then repeats one
 * {@value #ATTRIBUTE_PREFIX_LENGTH}-byte prefix plus one payload item per screen field:
 *
 * <pre>
 *  02  FILLER PICTURE X(3).      &lt;- {@value #ATTRIBUTE_FILLER_LENGTH}-byte reserved span
 *  02  xxxC    PICTURE X.        &lt;- colour byte
 *  02  xxxP    PICTURE X.        &lt;- programmed-symbol byte
 *  02  xxxH    PICTURE X.        &lt;- highlight byte
 *  02  xxxV    PICTURE X.        &lt;- validation byte
 *  02  xxxO  PIC X(n).           &lt;- THE PAYLOAD ITEM, and the only one modelled here
 * </pre>
 *
 * The stride is {@value #ATTRIBUTE_PREFIX_LENGTH} + n, which is byte-for-byte the stride of the
 * {@code COADM1AI} input group ({@code xxxL} {@code COMP PIC S9(4)} = 2, {@code xxxF} = 1,
 * {@code FILLER X(4)}, then {@code xxxI PIC X(n)}). That identity is precisely why {@code AO} is
 * able to {@code REDEFINES} {@code AI} over the same storage.
 *
 * <p>The four attribute bytes {@code xxxC}, {@code xxxP}, {@code xxxH} and {@code xxxV} are
 * <strong>presentation metadata, not screen text</strong>, and none of them is a member of this
 * payload. The single presentation fact this screen actually varies at runtime - the error-message
 * colour - is carried by {@link #messageColour()}, one explicit member deliberately <em>not</em>
 * named after {@code ERRMSGC}, so that no JSON key of this type is a metadata item.
 *
 * <p>The {@code xxxI} items of {@code COADM1AI} are the inbound direction and belong to
 * {@code AdminMenuRequest}, not here.
 *
 * <h2>The geometry, and why the numbers are worth stating</h2>
 *
 * <table border="1">
 *   <caption>Byte arithmetic of the {@code COADM1AO} image</caption>
 *   <tr><th>Component</th><th>Count</th><th>Each</th><th>Bytes</th></tr>
 *   <tr><td>{@code TIOAPFX FILLER PIC X(12)}</td><td>1</td>
 *       <td>{@value #TIOAPFX_FILLER_LENGTH}</td><td>{@value #TIOAPFX_FILLER_LENGTH}</td></tr>
 *   <tr><td>Attribute prefixes</td><td>{@value #PAYLOAD_FIELD_COUNT}</td>
 *       <td>{@value #ATTRIBUTE_PREFIX_LENGTH}</td><td>140</td></tr>
 *   <tr><td>{@code xxxO} payload items</td><td>{@value #PAYLOAD_FIELD_COUNT}</td>
 *       <td>4, 40, 8, 8, 40, 8, 40 times 12, 2, 78</td>
 *       <td>{@value #PAYLOAD_DATA_LENGTH}</td></tr>
 *   <tr><td><strong>Whole image</strong></td><td></td><td></td>
 *       <td><strong>{@value #SYMBOLIC_MAP_LENGTH}</strong></td></tr>
 * </table>
 *
 * {@value #TIOAPFX_FILLER_LENGTH} + {@value #PAYLOAD_FIELD_COUNT} times
 * {@value #ATTRIBUTE_PREFIX_LENGTH} + {@value #PAYLOAD_DATA_LENGTH} =
 * {@value #SYMBOLIC_MAP_LENGTH}. {@link #SYMBOLIC_MAP_LENGTH} is computed from its parts rather than
 * written as a literal, so a mistyped width cannot leave the documented total standing.
 *
 * <p>The image itself is <strong>not</strong> rendered by this type. Nothing on the response path
 * needs it, and were it ever needed it would have to be produced by {@code common.FixedWidthCodec}
 * with every {@code FILLER} span emitted, not hand-rolled here.
 *
 * <h2>{@value #MAPSET_FIELD_COUNT} {@code DFHMDF} definitions, {@value #NAMED_MAP_FIELD_COUNT} of
 * them payload</h2>
 *
 * {@code app/bms/COADM01.bms} declares {@value #MAPSET_FIELD_COUNT} {@code DFHMDF} fields inside a
 * single {@code DFHMDI COLUMN=1 LINE=1 SIZE=({@value #SCREEN_ROWS},{@value #SCREEN_COLUMNS})}.
 * Exactly {@value #NAMED_MAP_FIELD_COUNT} carry a name label and therefore reach the symbolic map;
 * the other {@value #UNNAMED_MAP_FIELD_COUNT} are unnamed literals - {@code 'Tran:'},
 * {@code 'Date:'}, {@code 'Prog:'}, {@code 'Time:'}, the {@code 'Admin Menu'} heading at
 * {@code POS=(4,35)}, {@code 'Please select an option :'} at {@code POS=(20,15)}, a
 * {@code LENGTH=0} stopper at {@code POS=(20,44)} and {@code 'ENTER=Continue  F3=Exit'} at
 * {@code POS=(24,1)}. Screen furniture is not payload, so those eight are absent by design.
 *
 * <h2>All twelve option slots exist, and that is deliberate</h2>
 *
 * Two independent facts make most of {@link #optionLines()} unreachable in the running program, and
 * <strong>neither</strong> is a licence to shrink this type:
 *
 * <ol>
 *   <li>{@code app/cpy/COADM02Y.cpy} declares {@code CDEMO-ADMIN-OPT-COUNT PIC 9(02) VALUE 4}, and
 *       {@code BUILD-MENU-OPTIONS} loops {@code UNTIL WS-IDX > CDEMO-ADMIN-OPT-COUNT}
 *       ({@code app/cbl/COADM01C.cbl:229-230}). Only slots 1 to 4 are ever written on this screen.
 *       Slots 5 to 10 are reachable code that is never exercised.</li>
 *   <li>{@code BUILD-MENU-OPTIONS}' {@code EVALUATE WS-IDX} ({@code app/cbl/COADM01C.cbl:237-261})
 *       enumerates {@code WHEN 1} through {@code WHEN 10} and then {@code WHEN OTHER CONTINUE}.
 *       {@code OPTN011O} and {@code OPTN012O} therefore have <strong>no assignment anywhere in the
 *       program at all</strong> - not an unreached one, none - and the same hole exists in
 *       {@code COMEN01C}.</li>
 * </ol>
 *
 * Yet {@code app/bms/COADM01.bms} declares all twelve as {@code DFHMDF} fields at
 * {@code POS=(6,20)} through {@code POS=(17,20)}, and {@code app/cpy-bms/COADM01.CPY} declares all
 * twelve {@code xxxO} items. The screen contract is twelve. Trimming this type to the four active
 * slots, or to the ten writable ones, would change the observable field set of a migration whose
 * whole premise is that the observable field set does not change. All twelve are present, all twelve
 * are addressable when unset, and the two unwritable ones are documented rather than deleted.
 *
 * <h2>{@code COMEN01.CPY} is byte-identical, and {@code MainMenuResponse} is still a separate type</h2>
 *
 * {@code diff app/cpy-bms/COADM01.CPY app/cpy-bms/COMEN01.CPY} reports differences at exactly two
 * lines - 17 ({@code 01 COADM1AI} versus {@code 01 COMEN1AI}) and 139 ({@code 01 COADM1AO REDEFINES
 * COADM1AI} versus {@code 01 COMEN1AO REDEFINES COMEN1AI}). Every field name, every
 * {@code PICTURE} clause and every byte of geometry is otherwise the same.
 *
 * <p>The two screens are nevertheless <strong>not</strong> folded into one Java type, no shared base
 * class, no interface, no generic parameterised by map name and no shared helper. They are distinct
 * BMS mapsets driven by distinct programs behind distinct transactions ({@value #TRANSACTION_ID}
 * versus {@code CM00}), backed by distinct option tables ({@code COADM02Y} with four entries and no
 * authorisation column, {@code COMEN02Y} with ten and an {@code X(01)} user-type column), and they
 * already diverge in observable behaviour - see the next section. Collapsing them would couple two
 * contracts that the legacy system keeps apart, and a later change to one would silently move the
 * other. The duplication is recorded here as a finding, not repaired.
 *
 * <h2>Two source quirks that must survive, and are not this type's to implement</h2>
 *
 * <ul>
 *   <li><strong>The admin "coming soon" message carries no option name.</strong>
 *       {@code app/cbl/COADM01C.cbl:149-153} builds it with the two lines that would have
 *       interpolated {@code CDEMO-ADMIN-OPT-NAME(WS-OPTION)} <em>commented out</em> at 150-151, so
 *       the admin text is literally {@code 'This option is coming soon ...'} while {@code COMEN01C}
 *       includes the option name. That divergence is real, it belongs to the services, and it is one
 *       more reason the two response types stay separate.</li>
 *   <li><strong>The colour override is the only attribute byte this screen moves.</strong>
 *       {@code app/bms/COADM01.bms:154-157} declares {@code ERRMSG ... COLOR=RED}, and
 *       {@code app/cbl/COADM01C.cbl:148} executes {@code MOVE DFHGREEN TO ERRMSGC OF COADM1AO} on
 *       the coming-soon path only. {@link #messageColour()} therefore starts at
 *       {@link BmsAttributes#DFHRED} and is moved to {@link BmsAttributes#DFHGREEN} by the service,
 *       never by this type.</li>
 * </ul>
 *
 * <h2>{@code option} is a two-character string, not a number</h2>
 *
 * {@code app/cbl/COADM01C.cbl:125} is {@code MOVE WS-OPTION TO OPTIONO OF COADM1AO}, moving a
 * {@code PIC 9(02)} into a {@code PIC X(2)}. A numeric-to-alphanumeric {@code MOVE} renders the
 * sending field's digits including its leading zeros, so option 1 arrives on the screen as
 * {@code "01"}, not {@code "1"} - and {@code app/bms/COADM01.bms:145-149} corroborates it with
 * {@code JUSTIFY=(RIGHT,ZERO)}. Before any option has been entered the field is spaces, which is a
 * third state that no integer has. Modelling this as an {@code int} would lose the leading zero and
 * would have to invent a sentinel for "blank", so it is a {@link String} of width
 * {@value #OPTION_LENGTH}.
 *
 * <h2>The {@code errMsg} width is 78</h2>
 *
 * {@code COADM01C} composes its text in {@code WS-MESSAGE PIC X(80)} and
 * {@code app/cbl/COADM01C.cbl:177} moves it to {@code ERRMSGO}, declared {@code PIC X(78)} at
 * {@code app/cpy-bms/COADM01.CPY:260}. That is a genuine two-byte right truncation, and
 * {@code CCDA-MSG-INVALID-KEY} - {@code PIC X(50)} in {@code app/cpy/CSMSG01Y.cpy}, moved to
 * {@code WS-MESSAGE} at {@code app/cbl/COADM01C.cbl:103} - narrows through the same 80 on its way to
 * the same 78. This member is declared at {@value #ERR_MSG_LENGTH}: never 80, never 50. Performing
 * the truncation is {@code AdminMenuController}'s work through {@code common.FixedWidthCodec}, whose
 * alphanumeric {@code MOVE} rule is the single place that behaviour lives.
 *
 * <h2>No numeric type appears here</h2>
 *
 * Every payload member is a {@link String} and the only non-textual members are one
 * {@code byte} attribute and one {@code boolean} flag. No binary floating-point primitive and no
 * arbitrary-precision decimal type appears anywhere in this file, because there is no decimal
 * {@code PICTURE} anywhere on this screen - all twenty {@code xxxO} items are {@code PIC X(n)}, so
 * there is no monetary or fractional value here for a scale and a rounding mode to apply to.
 *
 * <h2>There is no server-side conversation</h2>
 *
 * CICS is pseudo-conversational: {@code COADM01C} paints a screen, returns, and is re-entered from
 * the top with only what it handed back in its communication area. This type reproduces that shape
 * exactly. {@link #navigationContext()} carries the {@value NavigationContext#COMMAREA_LENGTH}-byte
 * {@code CARDDEMO-COMMAREA} of {@code app/cpy/COCOM01Y.cpy} - including
 * {@code CDEMO-USER-TYPE X(01)} with its {@code 88 CDEMO-USRTYP-ADMIN VALUE 'A'} and
 * {@code 88 CDEMO-USRTYP-USER VALUE 'U'}, and {@code CDEMO-PGM-CONTEXT 9(01)} with its
 * {@code 88 CDEMO-PGM-ENTER VALUE 0} and {@code 88 CDEMO-PGM-REENTER VALUE 1} - straight back to the
 * client, which returns it on the next call.
 *
 * <p>Accordingly this type touches no servlet session, no session-scoped attribute or bean, no
 * thread-local, no cache keyed by user or terminal, and no static mutable holder. A static "current
 * response" would be a session by another name and would break request isolation as well. It is
 * immutable, every member is final by virtue of being a record
 * component, {@link #optionLines()} and {@link #payloadValues()} hand back unmodifiable lists, and a
 * modified copy is obtained through {@link #toBuilder()} rather than by mutation.
 *
 * <h2>{@code EXEC CICS XCTL} becomes three response members</h2>
 *
 * {@code COADM01C} transfers control at two sites, and both become data the client acts on:
 *
 * <ul>
 *   <li>{@code app/cbl/COADM01C.cbl:142-145}
 *       {@code XCTL PROGRAM(CDEMO-ADMIN-OPT-PGMNAME(WS-OPTION))} - the selected option's target,
 *       which for this screen is one of the four programs named in {@code app/cpy/COADM02Y.cpy}:
 *       {@code COUSR00C}, {@code COUSR01C}, {@code COUSR02C} or {@code COUSR03C}.</li>
 *   <li>{@code app/cbl/COADM01C.cbl:165-167} {@code XCTL PROGRAM(CDEMO-TO-PROGRAM)} in
 *       {@code RETURN-TO-SIGNON-SCREEN}, whose guard at 162-164 substitutes
 *       {@value #SIGNON_PROGRAM} when {@code CDEMO-TO-PROGRAM} is {@code LOW-VALUES} or spaces - the
 *       same literal moved at {@code app/cbl/COADM01C.cbl:83} when {@code EIBCALEN = 0}.</li>
 * </ul>
 *
 * {@link #nextProgram()} carries whichever target applies, {@link #nextMapset()} and
 * {@link #nextMap()} name the screen to render, and the client issues the follow-up request. There
 * is no server-side forward and no redirect chain. {@link #empty()} seeds the two screen members
 * with {@value #MAPSET_NAME} and {@value #MAP_NAME} - this screen's own identity - and leaves
 * {@link #nextProgram()} blank, because <em>which</em> program comes next is a decision, and
 * decisions belong to {@code AdminMenuService}.
 *
 * @param trnName               {@code TRNNAMEO PIC X(4)}, {@code app/cpy-bms/COADM01.CPY:146}. The
 *                              transaction identifier, {@value #TRANSACTION_ID}, written by
 *                              {@code MOVE WS-TRANID} at {@code app/cbl/COADM01C.cbl:208}
 * @param title01               {@code TITLE01O PIC X(40)}, {@code app/cpy-bms/COADM01.CPY:152}. The
 *                              first title line, written by {@code MOVE CCDA-TITLE01} at
 *                              {@code app/cbl/COADM01C.cbl:206} from {@code app/cpy/COTTL01Y.cpy}
 * @param curDate               {@code CURDATEO PIC X(8)}, {@code app/cpy-bms/COADM01.CPY:158}. The
 *                              {@code MM/DD/YY} header date, written from
 *                              {@code WS-CURDATE-MM-DD-YY} at {@code app/cbl/COADM01C.cbl:215}
 * @param pgmName               {@code PGMNAMEO PIC X(8)}, {@code app/cpy-bms/COADM01.CPY:164}. The
 *                              program name, {@value #PROGRAM_NAME}, written by
 *                              {@code MOVE WS-PGMNAME} at {@code app/cbl/COADM01C.cbl:209}
 * @param title02               {@code TITLE02O PIC X(40)}, {@code app/cpy-bms/COADM01.CPY:170}. The
 *                              second title line, written by {@code MOVE CCDA-TITLE02} at
 *                              {@code app/cbl/COADM01C.cbl:207}
 * @param curTime               {@code CURTIMEO PIC X(8)}, {@code app/cpy-bms/COADM01.CPY:176}. The
 *                              {@code HH:MM:SS} header time, written from
 *                              {@code WS-CURTIME-HH-MM-SS} at {@code app/cbl/COADM01C.cbl:221}
 * @param optn001               {@code OPTN001O PIC X(40)}, {@code app/cpy-bms/COADM01.CPY:182}.
 *                              Menu line 1 at {@code POS=(6,20)}; written by
 *                              {@code BUILD-MENU-OPTIONS} {@code WHEN 1}
 * @param optn002               {@code OPTN002O PIC X(40)}, {@code app/cpy-bms/COADM01.CPY:188}.
 *                              Menu line 2 at {@code POS=(7,20)}; written {@code WHEN 2}
 * @param optn003               {@code OPTN003O PIC X(40)}, {@code app/cpy-bms/COADM01.CPY:194}.
 *                              Menu line 3 at {@code POS=(8,20)}; written {@code WHEN 3}
 * @param optn004               {@code OPTN004O PIC X(40)}, {@code app/cpy-bms/COADM01.CPY:200}.
 *                              Menu line 4 at {@code POS=(9,20)}; written {@code WHEN 4} - the last
 *                              slot this screen fills, because {@code CDEMO-ADMIN-OPT-COUNT} is 4
 * @param optn005               {@code OPTN005O PIC X(40)}, {@code app/cpy-bms/COADM01.CPY:206}.
 *                              Menu line 5 at {@code POS=(10,20)}; writable {@code WHEN 5}, never
 *                              reached on this screen
 * @param optn006               {@code OPTN006O PIC X(40)}, {@code app/cpy-bms/COADM01.CPY:212}.
 *                              Menu line 6 at {@code POS=(11,20)}; writable, never reached
 * @param optn007               {@code OPTN007O PIC X(40)}, {@code app/cpy-bms/COADM01.CPY:218}.
 *                              Menu line 7 at {@code POS=(12,20)}; writable, never reached
 * @param optn008               {@code OPTN008O PIC X(40)}, {@code app/cpy-bms/COADM01.CPY:224}.
 *                              Menu line 8 at {@code POS=(13,20)}; writable, never reached
 * @param optn009               {@code OPTN009O PIC X(40)}, {@code app/cpy-bms/COADM01.CPY:230}.
 *                              Menu line 9 at {@code POS=(14,20)}; writable, never reached
 * @param optn010               {@code OPTN010O PIC X(40)}, {@code app/cpy-bms/COADM01.CPY:236}.
 *                              Menu line 10 at {@code POS=(15,20)}; the last slot the
 *                              {@code EVALUATE} can reach at all
 * @param optn011               {@code OPTN011O PIC X(40)}, {@code app/cpy-bms/COADM01.CPY:242}.
 *                              Menu line 11 at {@code POS=(16,20)}. <strong>The program contains no
 *                              assignment to this field</strong>; the mapset declares it, so it
 *                              stays
 * @param optn012               {@code OPTN012O PIC X(40)}, {@code app/cpy-bms/COADM01.CPY:248}.
 *                              Menu line 12 at {@code POS=(17,20)}. <strong>Likewise never
 *                              written</strong>, likewise kept
 * @param option                {@code OPTIONO PIC X(2)}, {@code app/cpy-bms/COADM01.CPY:254}. The
 *                              normalised, zero-filled option echo written by
 *                              {@code app/cbl/COADM01C.cbl:125}; spaces before any entry
 * @param errMsg                {@code ERRMSGO PIC X(78)}, {@code app/cpy-bms/COADM01.CPY:260}. The
 *                              already-narrowed message written by {@code app/cbl/COADM01C.cbl:177}
 * @param navigationContext     the echoed {@code CARDDEMO-COMMAREA} of
 *                              {@code app/cpy/COCOM01Y.cpy}, returned to the client so no
 *                              conversation state is held on the server
 * @param nextProgram           the {@code EXEC CICS XCTL} target, {@code PIC X(08)} wide like
 *                              {@code CDEMO-TO-PROGRAM}: an option target from
 *                              {@code app/cpy/COADM02Y.cpy}, or {@value #SIGNON_PROGRAM} on the
 *                              return route
 * @param nextMapset            the mapset the client should render, {@code X(7)} wide like
 *                              {@code CDEMO-LAST-MAPSET}; {@value #MAPSET_NAME} for this screen
 * @param nextMap               the map the client should render, {@code X(7)} wide like
 *                              {@code CDEMO-LAST-MAP}; {@value #MAP_NAME} for this screen
 * @param messageColour         the BMS colour attribute for the message line: presentation metadata,
 *                              {@link BmsAttributes#DFHRED} by map declaration and
 *                              {@link BmsAttributes#DFHGREEN} on the coming-soon path
 * @param resetAllOutputFields  whether the client should clear every output field before painting,
 *                              mirroring {@code MOVE LOW-VALUES TO COADM1AO} at
 *                              {@code app/cbl/COADM01C.cbl:89}
 */
public record AdminMenuResponse(
        @Size(max = TRN_NAME_LENGTH) String trnName,
        @Size(max = TITLE_LENGTH) String title01,
        @Size(max = CUR_DATE_LENGTH) String curDate,
        @Size(max = PGM_NAME_LENGTH) String pgmName,
        @Size(max = TITLE_LENGTH) String title02,
        @Size(max = CUR_TIME_LENGTH) String curTime,
        @Size(max = OPTION_LINE_LENGTH) String optn001,
        @Size(max = OPTION_LINE_LENGTH) String optn002,
        @Size(max = OPTION_LINE_LENGTH) String optn003,
        @Size(max = OPTION_LINE_LENGTH) String optn004,
        @Size(max = OPTION_LINE_LENGTH) String optn005,
        @Size(max = OPTION_LINE_LENGTH) String optn006,
        @Size(max = OPTION_LINE_LENGTH) String optn007,
        @Size(max = OPTION_LINE_LENGTH) String optn008,
        @Size(max = OPTION_LINE_LENGTH) String optn009,
        @Size(max = OPTION_LINE_LENGTH) String optn010,
        @Size(max = OPTION_LINE_LENGTH) String optn011,
        @Size(max = OPTION_LINE_LENGTH) String optn012,
        @Size(max = OPTION_LENGTH) String option,
        @Size(max = ERR_MSG_LENGTH) String errMsg,
        NavigationContext navigationContext,
        @Size(max = NEXT_PROGRAM_LENGTH) String nextProgram,
        @Size(max = NEXT_MAPSET_LENGTH) String nextMapset,
        @Size(max = NEXT_MAP_LENGTH) String nextMap,
        byte messageColour,
        boolean resetAllOutputFields) {

    // =================================================================================================
    // Screen identity. These four literals are what the CSD, the program and the mapset agree on, and
    // they are stated once here so no caller has to retype a name the parity differ will compare.
    // =================================================================================================

    /**
     * The CICS transaction identifier of the admin menu, {@code CA00}.
     *
     * <p>{@code app/csd/CARDDEMO.CSD} maps {@code TRANSACTION(CA00)} to {@code PROGRAM(COADM01C)},
     * and {@code app/cbl/COADM01C.cbl} carries the same literal in {@code WS-TRANID}, from where
     * {@code app/cbl/COADM01C.cbl:208} moves it into {@code TRNNAMEO}. Four characters, matching
     * {@link #TRN_NAME_LENGTH} and {@code CDEMO-FROM-TRANID PIC X(04)}.
     */
    public static final String TRANSACTION_ID = "CA00";

    /**
     * The COBOL program this screen is the response of, {@code COADM01C}.
     *
     * <p>Held in {@code WS-PGMNAME} and moved into {@code PGMNAMEO} by
     * {@code app/cbl/COADM01C.cbl:209}. Eight characters, matching {@link #PGM_NAME_LENGTH} and
     * {@code CDEMO-FROM-PROGRAM PIC X(08)}.
     */
    public static final String PROGRAM_NAME = "COADM01C";

    /**
     * The BMS mapset name, {@code COADM01} - the {@code DFHMSD} of {@code app/bms/COADM01.bms} and
     * the {@code MAPSET('COADM01')} operand of the {@code EXEC CICS SEND} at
     * {@code app/cbl/COADM01C.cbl:180-186}.
     *
     * <p>Seven characters, which is exactly {@value NavigationContext#LAST_MAPSET_LENGTH} - the width
     * of {@code CDEMO-LAST-MAPSET PIC X(7)}. Mapset and map names are seven, not eight, because a
     * symbolic-map group item is a seven-character name plus a one-character direction suffix:
     * {@code COADM1AI} inbound, {@code COADM1AO} outbound.
     */
    public static final String MAPSET_NAME = "COADM01";

    /**
     * The BMS map name, {@code COADM1A} - the {@code DFHMDI} label of {@code app/bms/COADM01.bms} and
     * the {@code MAP('COADM1A')} operand of the {@code EXEC CICS SEND} at
     * {@code app/cbl/COADM01C.cbl:180-186}. Seven characters, matching
     * {@value NavigationContext#LAST_MAP_LENGTH}.
     */
    public static final String MAP_NAME = "COADM1A";

    /**
     * The sign-on program, {@code COSGN00C}: the {@code XCTL} target of the return route.
     *
     * <p>Recorded because it is a literal of <em>this</em> program, moved at
     * {@code app/cbl/COADM01C.cbl:83} when {@code EIBCALEN = 0}, again at
     * {@code app/cbl/COADM01C.cbl:97} on {@code DFHPF3}, and substituted for an empty
     * {@code CDEMO-TO-PROGRAM} by the guard at {@code app/cbl/COADM01C.cbl:162-164} immediately
     * before {@code XCTL PROGRAM(CDEMO-TO-PROGRAM)}.
     *
     * <p>It is a documented source literal, <strong>not</strong> a default: {@link #empty()} leaves
     * {@link #nextProgram()} blank, because choosing a navigation target is a decision and decisions
     * belong to {@code admin.AdminMenuService}. The four option targets are deliberately not
     * duplicated here - they live in {@code app/cpy/COADM02Y.cpy} and in the model type that
     * translates it, and a second copy could drift from the first.
     */
    public static final String SIGNON_PROGRAM = "COSGN00C";

    // =================================================================================================
    // Screen geometry, from the mapset. Every value is traced to the declaration it comes from, and
    // the totals are computed from their parts rather than restated as literals.
    // =================================================================================================

    /** Rows on the 3270 screen: the {@code SIZE=(24,80)} of {@code app/bms/COADM01.bms} {@code DFHMDI}. */
    public static final int SCREEN_ROWS = 24;

    /** Columns on the 3270 screen: the other half of {@code SIZE=(24,80)}. */
    public static final int SCREEN_COLUMNS = 80;

    /** Total {@code DFHMDF} field definitions in {@code app/bms/COADM01.bms}: 28. */
    public static final int MAPSET_FIELD_COUNT = 28;

    /**
     * {@code DFHMDF} definitions carrying a name label, and therefore reaching the symbolic map: 20.
     * This is the number of payload members of this type, and {@link #PAYLOAD_FIELDS} is asserted
     * against it.
     */
    public static final int NAMED_MAP_FIELD_COUNT = 20;

    /**
     * Unnamed {@code DFHMDF} literals - screen furniture that never reaches the symbolic map and is
     * therefore never payload: {@value #MAPSET_FIELD_COUNT} minus
     * {@value #NAMED_MAP_FIELD_COUNT} = 8.
     */
    public static final int UNNAMED_MAP_FIELD_COUNT = MAPSET_FIELD_COUNT - NAMED_MAP_FIELD_COUNT;

    /**
     * The {@code TIOAPFX} prefix that opens the symbolic map: {@code 02 FILLER PIC X(12).} at
     * {@code app/cpy-bms/COADM01.CPY:140}, present because the mapset declares
     * {@code TIOAPFX=YES}.
     */
    public static final int TIOAPFX_FILLER_LENGTH = 12;

    /**
     * The reserved span that opens each field's attribute prefix in the output view:
     * {@code 02 FILLER PICTURE X(3).}, for instance at {@code app/cpy-bms/COADM01.CPY:141}.
     */
    public static final int ATTRIBUTE_FILLER_LENGTH = 3;

    /**
     * Bytes preceding every {@code xxxO} item: {@value #ATTRIBUTE_FILLER_LENGTH} of {@code FILLER}
     * plus the four one-byte attributes {@code xxxC}, {@code xxxP}, {@code xxxH} and {@code xxxV},
     * so 7.
     *
     * <p>The matching {@code COADM1AI} prefix is also 7 - {@code xxxL COMP PIC S9(4)} occupying two
     * bytes, {@code xxxF PICTURE X} one, then {@code FILLER PICTURE X(4)} - which is what allows
     * {@code COADM1AO} to {@code REDEFINES COADM1AI} field for field.
     */
    public static final int ATTRIBUTE_PREFIX_LENGTH = ATTRIBUTE_FILLER_LENGTH + 4;

    /** Payload ({@code xxxO}) items in the output view, and hence payload members here: 20. */
    public static final int PAYLOAD_FIELD_COUNT = NAMED_MAP_FIELD_COUNT;

    // =================================================================================================
    // Member widths. Each is the n of that member's own xxxO PICTURE X(n), cited to the copybook line
    // that declares it. Nothing in this file - including the Bean Validation bounds on the record
    // header - uses a bare integer literal for a width.
    // =================================================================================================

    /** {@code TRNNAMEO PIC X(4)}, {@code app/cpy-bms/COADM01.CPY:146}. Width of {@link #trnName()}. */
    public static final int TRN_NAME_LENGTH = 4;

    /**
     * {@code TITLE01O PIC X(40)} at {@code app/cpy-bms/COADM01.CPY:152} and
     * {@code TITLE02O PIC X(40)} at {@code app/cpy-bms/COADM01.CPY:170} - one constant because the
     * two are declared identically, and because {@code CCDA-TITLE01} and {@code CCDA-TITLE02} in
     * {@code app/cpy/COTTL01Y.cpy} are both {@code PIC X(40)} on the sending side.
     */
    public static final int TITLE_LENGTH = 40;

    /**
     * {@code CURDATEO PIC X(8)}, {@code app/cpy-bms/COADM01.CPY:158}. Eight is exactly
     * {@code WS-CURDATE-MM-DD-YY} of {@code app/cpy/CSDAT01Y.cpy}: {@code 9(02)} slash
     * {@code 9(02)} slash {@code 9(02)}.
     */
    public static final int CUR_DATE_LENGTH = 8;

    /** {@code PGMNAMEO PIC X(8)}, {@code app/cpy-bms/COADM01.CPY:164}. Width of {@link #pgmName()}. */
    public static final int PGM_NAME_LENGTH = 8;

    /**
     * {@code CURTIMEO PIC X(8)}, {@code app/cpy-bms/COADM01.CPY:176}. Eight is exactly
     * {@code WS-CURTIME-HH-MM-SS} of {@code app/cpy/CSDAT01Y.cpy}: {@code 9(02)} colon
     * {@code 9(02)} colon {@code 9(02)}.
     */
    public static final int CUR_TIME_LENGTH = 8;

    /**
     * {@code OPTN001O} through {@code OPTN012O}, each {@code PIC X(40)}, at
     * {@code app/cpy-bms/COADM01.CPY} lines 182, 188, 194, 200, 206, 212, 218, 224, 230, 236, 242
     * and 248, and each {@code LENGTH=40} in the mapset.
     *
     * <p>Forty is wider than what the program can put there: {@code BUILD-MENU-OPTIONS} composes
     * {@code CDEMO-ADMIN-OPT-NUM PIC 9(02)} plus the two characters {@code ". "} plus
     * {@code CDEMO-ADMIN-OPT-NAME PIC X(35)}, which is 39. The fortieth byte is a space the screen
     * declares and the program never fills, and it is part of the contract all the same.
     */
    public static final int OPTION_LINE_LENGTH = 40;

    /**
     * Menu option lines the screen declares: 12.
     *
     * <p>Not 4, which is {@code CDEMO-ADMIN-OPT-COUNT} and therefore how many the program fills, and
     * not 10, which is how far {@code BUILD-MENU-OPTIONS}' {@code EVALUATE} can reach. Twelve is what
     * {@code app/bms/COADM01.bms} declares at {@code POS=(6,20)} through {@code POS=(17,20)} and what
     * {@code app/cpy-bms/COADM01.CPY} declares as {@code xxxO} items, so twelve is the contract. See
     * the class documentation for why this number is never reduced to either of the other two.
     */
    public static final int OPTION_LINE_COUNT = 12;

    /**
     * {@code OPTIONO PIC X(2)}, {@code app/cpy-bms/COADM01.CPY:254}, and {@code LENGTH=2} with
     * {@code JUSTIFY=(RIGHT,ZERO)} in the mapset. Width of {@link #option()}, which is textual - see
     * the class documentation.
     */
    public static final int OPTION_LENGTH = 2;

    /**
     * {@code ERRMSGO PIC X(78)}, {@code app/cpy-bms/COADM01.CPY:260}, and {@code LENGTH=78} at
     * {@code POS=(23,1)} in the mapset.
     *
     * <p>78, not the 80 of {@code WS-MESSAGE} and not the 50 of {@code CCDA-MSG-INVALID-KEY}. The
     * narrowing from 80 is real and is performed upstream of this type.
     */
    public static final int ERR_MSG_LENGTH = 78;

    /**
     * The sum of the twenty {@code xxxO} widths:
     * {@value #TRN_NAME_LENGTH} + {@value #TITLE_LENGTH} + {@value #CUR_DATE_LENGTH} +
     * {@value #PGM_NAME_LENGTH} + {@value #TITLE_LENGTH} + {@value #CUR_TIME_LENGTH} +
     * {@value #OPTION_LINE_COUNT} times {@value #OPTION_LINE_LENGTH} + {@value #OPTION_LENGTH} +
     * {@value #ERR_MSG_LENGTH} = 668.
     *
     * <p>Written as the expression rather than as {@code 668} so that changing any single width moves
     * this total with it instead of silently contradicting it.
     */
    public static final int PAYLOAD_DATA_LENGTH = TRN_NAME_LENGTH
            + TITLE_LENGTH
            + CUR_DATE_LENGTH
            + PGM_NAME_LENGTH
            + TITLE_LENGTH
            + CUR_TIME_LENGTH
            + OPTION_LINE_COUNT * OPTION_LINE_LENGTH
            + OPTION_LENGTH
            + ERR_MSG_LENGTH;

    /**
     * The whole {@code COADM1AO} image: {@value #TIOAPFX_FILLER_LENGTH} +
     * {@value #PAYLOAD_FIELD_COUNT} times {@value #ATTRIBUTE_PREFIX_LENGTH} +
     * {@value #PAYLOAD_DATA_LENGTH} = 820 bytes.
     *
     * <p>Documentary and assertable. No method here renders it: producing the image is
     * {@code common.FixedWidthCodec}'s responsibility, {@code FILLER} spans included, and the
     * response path does not need it.
     */
    public static final int SYMBOLIC_MAP_LENGTH = TIOAPFX_FILLER_LENGTH
            + PAYLOAD_FIELD_COUNT * ATTRIBUTE_PREFIX_LENGTH
            + PAYLOAD_DATA_LENGTH;

    // =================================================================================================
    // Widths of the three navigation members. They are not xxxO items; they take their widths from the
    // COMMAREA fields that carry the same values, so a program name stays 8 and a map name stays 7.
    // =================================================================================================

    /**
     * Width of {@link #nextProgram()}: 8, from {@code CDEMO-TO-PROGRAM PIC X(08)} of
     * {@code app/cpy/COCOM01Y.cpy}, which is the field {@code XCTL PROGRAM(CDEMO-TO-PROGRAM)} reads
     * at {@code app/cbl/COADM01C.cbl:166}.
     */
    public static final int NEXT_PROGRAM_LENGTH = NavigationContext.TO_PROGRAM_LENGTH;

    /**
     * Width of {@link #nextMapset()}: 7, from {@code CDEMO-LAST-MAPSET PIC X(7)}. Seven, not eight -
     * see {@link #MAPSET_NAME}.
     */
    public static final int NEXT_MAPSET_LENGTH = NavigationContext.LAST_MAPSET_LENGTH;

    /** Width of {@link #nextMap()}: 7, from {@code CDEMO-LAST-MAP PIC X(7)}. */
    public static final int NEXT_MAP_LENGTH = NavigationContext.LAST_MAP_LENGTH;

    // =================================================================================================
    // Symbolic-map names, spelled EXACTLY as the copybook spells them, trailing O and all. These are
    // the names a field-for-field parity diff keys on, so a name tidied into Java style here would make
    // a genuine difference invisible there.
    // =================================================================================================

    /** Copybook name of {@link #trnName()}: {@code TRNNAMEO}, {@code app/cpy-bms/COADM01.CPY:146}. */
    public static final String TRN_NAME_FIELD = "TRNNAMEO";

    /** Copybook name of {@link #title01()}: {@code TITLE01O}, {@code app/cpy-bms/COADM01.CPY:152}. */
    public static final String TITLE01_FIELD = "TITLE01O";

    /** Copybook name of {@link #curDate()}: {@code CURDATEO}, {@code app/cpy-bms/COADM01.CPY:158}. */
    public static final String CUR_DATE_FIELD = "CURDATEO";

    /** Copybook name of {@link #pgmName()}: {@code PGMNAMEO}, {@code app/cpy-bms/COADM01.CPY:164}. */
    public static final String PGM_NAME_FIELD = "PGMNAMEO";

    /** Copybook name of {@link #title02()}: {@code TITLE02O}, {@code app/cpy-bms/COADM01.CPY:170}. */
    public static final String TITLE02_FIELD = "TITLE02O";

    /** Copybook name of {@link #curTime()}: {@code CURTIMEO}, {@code app/cpy-bms/COADM01.CPY:176}. */
    public static final String CUR_TIME_FIELD = "CURTIMEO";

    /** Copybook name of {@link #option()}: {@code OPTIONO}, {@code app/cpy-bms/COADM01.CPY:254}. */
    public static final String OPTION_FIELD = "OPTIONO";

    /** Copybook name of {@link #errMsg()}: {@code ERRMSGO}, {@code app/cpy-bms/COADM01.CPY:260}. */
    public static final String ERR_MSG_FIELD = "ERRMSGO";

    /**
     * The twelve menu-line names in map order: {@code OPTN001O} through {@code OPTN012O}.
     *
     * <p>Unmodifiable. Written out in full rather than generated from a loop, so that the two names
     * the program never assigns - {@code OPTN011O} and {@code OPTN012O} - are visible in the source
     * of the type that keeps them.
     */
    public static final List<String> OPTION_LINE_FIELDS = List.of("OPTN001O",
            "OPTN002O",
            "OPTN003O",
            "OPTN004O",
            "OPTN005O",
            "OPTN006O",
            "OPTN007O",
            "OPTN008O",
            "OPTN009O",
            "OPTN010O",
            "OPTN011O",
            "OPTN012O");

    /**
     * All {@value #PAYLOAD_FIELD_COUNT} symbolic-map names in map order, from {@code TRNNAMEO} at
     * {@code app/cpy-bms/COADM01.CPY:146} to {@code ERRMSGO} at
     * {@code app/cpy-bms/COADM01.CPY:260}.
     *
     * <p>Unmodifiable, and positionally aligned with {@link #payloadValues()} and with
     * {@link #PAYLOAD_FIELD_LENGTHS}: index i of all three describes the same screen field. That
     * alignment is what lets a parity differ walk the screen field by field without knowing anything
     * about this type.
     */
    public static final List<String> PAYLOAD_FIELDS = buildPayloadFields();

    /**
     * The {@value #PAYLOAD_FIELD_COUNT} declared widths in map order, positionally aligned with
     * {@link #PAYLOAD_FIELDS}. Unmodifiable, and summing to {@value #PAYLOAD_DATA_LENGTH}.
     */
    public static final List<Integer> PAYLOAD_FIELD_LENGTHS = buildPayloadFieldLengths();

    // =================================================================================================
    // Construction. The canonical constructor substitutes a space-filled value for every absent
    // character member and the initial communication area for an absent context, so that an unset
    // field is addressable rather than a null dereference - COBOL has no null, and a screen field that
    // has not been written is spaces, exactly as MOVE LOW-VALUES leaves it before SEND.
    //
    // Both substitutions route through ONE decision each, shared by all twenty-four members, rather
    // than twenty-four separate guards. That is not only less code: it is one branch pair to prove
    // rather than forty-eight, which is what makes this package's branch coverage honest instead of
    // merely high.
    // =================================================================================================

    /**
     * Canonical constructor, normalising absent members to their COBOL-initial values.
     *
     * <p>No length is enforced here and no value is truncated. The declared widths are advertised as
     * Bean Validation bounds on the record header and as the {@code *_LENGTH} constants; performing an
     * alphanumeric {@code MOVE} - pad a short value on the right, truncate a long one on the right -
     * is {@code common.FixedWidthCodec}'s single responsibility, and duplicating it here would create
     * a second place for the rule to drift.
     */
    public AdminMenuResponse {
        trnName = orSpaces(trnName, TRN_NAME_LENGTH);
        title01 = orSpaces(title01, TITLE_LENGTH);
        curDate = orSpaces(curDate, CUR_DATE_LENGTH);
        pgmName = orSpaces(pgmName, PGM_NAME_LENGTH);
        title02 = orSpaces(title02, TITLE_LENGTH);
        curTime = orSpaces(curTime, CUR_TIME_LENGTH);
        optn001 = orSpaces(optn001, OPTION_LINE_LENGTH);
        optn002 = orSpaces(optn002, OPTION_LINE_LENGTH);
        optn003 = orSpaces(optn003, OPTION_LINE_LENGTH);
        optn004 = orSpaces(optn004, OPTION_LINE_LENGTH);
        optn005 = orSpaces(optn005, OPTION_LINE_LENGTH);
        optn006 = orSpaces(optn006, OPTION_LINE_LENGTH);
        optn007 = orSpaces(optn007, OPTION_LINE_LENGTH);
        optn008 = orSpaces(optn008, OPTION_LINE_LENGTH);
        optn009 = orSpaces(optn009, OPTION_LINE_LENGTH);
        optn010 = orSpaces(optn010, OPTION_LINE_LENGTH);
        optn011 = orSpaces(optn011, OPTION_LINE_LENGTH);
        optn012 = orSpaces(optn012, OPTION_LINE_LENGTH);
        option = orSpaces(option, OPTION_LENGTH);
        errMsg = orSpaces(errMsg, ERR_MSG_LENGTH);
        navigationContext = orEmptyContext(navigationContext);
        nextProgram = orSpaces(nextProgram, NEXT_PROGRAM_LENGTH);
        nextMapset = orSpaces(nextMapset, NEXT_MAPSET_LENGTH);
        nextMap = orSpaces(nextMap, NEXT_MAP_LENGTH);
    }

    /**
     * The screen as {@code COADM01C} leaves it immediately after
     * {@code MOVE LOW-VALUES TO COADM1AO} at {@code app/cbl/COADM01C.cbl:89} and before
     * {@code POPULATE-HEADER-INFO} runs: every text field space-filled to its declared width, the
     * communication area at its own initial value, the message colour at the map's declared
     * {@link BmsAttributes#DFHRED}, and the screen to render named as this screen.
     *
     * <p>{@link #nextProgram()} is left blank on purpose. Both {@code XCTL} targets are chosen at
     * runtime - one from the option table, one from {@code CDEMO-TO-PROGRAM} - so pre-seeding either
     * would be this type deciding navigation, which is {@code admin.AdminMenuService}'s job.
     *
     * <p>{@link #resetAllOutputFields()} is {@code false}: it is a signal the service raises for the
     * first-entry repaint, not a property of a blank instance.
     *
     * @return the space-filled initial response, never {@code null}
     */
    public static AdminMenuResponse empty() {
        return new AdminMenuResponse(spaces(TRN_NAME_LENGTH),
                spaces(TITLE_LENGTH),
                spaces(CUR_DATE_LENGTH),
                spaces(PGM_NAME_LENGTH),
                spaces(TITLE_LENGTH),
                spaces(CUR_TIME_LENGTH),
                spaces(OPTION_LINE_LENGTH),
                spaces(OPTION_LINE_LENGTH),
                spaces(OPTION_LINE_LENGTH),
                spaces(OPTION_LINE_LENGTH),
                spaces(OPTION_LINE_LENGTH),
                spaces(OPTION_LINE_LENGTH),
                spaces(OPTION_LINE_LENGTH),
                spaces(OPTION_LINE_LENGTH),
                spaces(OPTION_LINE_LENGTH),
                spaces(OPTION_LINE_LENGTH),
                spaces(OPTION_LINE_LENGTH),
                spaces(OPTION_LINE_LENGTH),
                spaces(OPTION_LENGTH),
                spaces(ERR_MSG_LENGTH),
                NavigationContext.empty(),
                spaces(NEXT_PROGRAM_LENGTH),
                MAPSET_NAME,
                MAP_NAME,
                BmsAttributes.DFHRED,
                false);
    }

    // =================================================================================================
    // Derived views. Every one of these is computed from the members rather than stored beside them, so
    // every one is @JsonIgnore: the wire format carries the twenty screen fields, the communication
    // area, the three navigation members, the message colour and the repaint flag - and nothing else.
    // =================================================================================================

    /**
     * The twelve menu lines in map order, positionally aligned with {@link #OPTION_LINE_FIELDS}.
     *
     * <p>All twelve, always - including the two the program cannot write. Index 0 is
     * {@code OPTN001O}, index 11 is {@code OPTN012O}.
     *
     * @return an unmodifiable list of exactly {@value #OPTION_LINE_COUNT} lines, never {@code null}
     *         and never containing {@code null}
     */
    @JsonIgnore
    public List<String> optionLines() {
        return List.of(optn001,
                optn002,
                optn003,
                optn004,
                optn005,
                optn006,
                optn007,
                optn008,
                optn009,
                optn010,
                optn011,
                optn012);
    }

    /**
     * One menu line by its <strong>COBOL subscript</strong>, 1 through
     * {@value #OPTION_LINE_COUNT}.
     *
     * <p>This is the only place in this type where a COBOL subscript is turned into a Java index, and
     * it exists precisely so that the conversion happens once. COBOL {@code OCCURS} tables are
     * one-based and {@code BUILD-MENU-OPTIONS} counts {@code WS-IDX} from 1, while Java lists are
     * zero-based; silently mixing the two is the single most productive source of off-by-one defects
     * in this migration. Callers that think in {@code WS-IDX} should use this method; callers that
     * think in list positions should use {@link #optionLines()}.
     *
     * @param cobolSubscript the one-based line number, as {@code WS-IDX} counts it
     * @return that menu line, space-filled if it has never been written
     * @throws IndexOutOfBoundsException if {@code cobolSubscript} is outside 1 to
     *                                   {@value #OPTION_LINE_COUNT}. The bound is the declared twelve,
     *                                   not the four the program fills, because slots 5 to 12 are part
     *                                   of the screen and are legitimately readable
     */
    @JsonIgnore
    public String optionLine(final int cobolSubscript) {
        return optionLines().get(zeroBasedIndex(cobolSubscript));
    }

    /**
     * The {@value #PAYLOAD_FIELD_COUNT} screen-field values in map order, positionally aligned with
     * {@link #PAYLOAD_FIELDS} and {@link #PAYLOAD_FIELD_LENGTHS}.
     *
     * <p>Provided so a field-for-field comparison can walk the whole screen - name, declared width and
     * value together - without reflecting over this type or hard-coding its member order.
     *
     * @return an unmodifiable list of exactly {@value #PAYLOAD_FIELD_COUNT} values, never {@code null}
     *         and never containing {@code null}
     */
    @JsonIgnore
    public List<String> payloadValues() {
        final List<String> values = new ArrayList<>(PAYLOAD_FIELD_COUNT);
        values.add(trnName);
        values.add(title01);
        values.add(curDate);
        values.add(pgmName);
        values.add(title02);
        values.add(curTime);
        values.addAll(optionLines());
        values.add(option);
        values.add(errMsg);
        return List.copyOf(values);
    }

    /**
     * The message colour as its IBM {@code DFHBMSCA} mnemonic, for example {@code DFHRED} or
     * {@code DFHGREEN}.
     *
     * <p>Delegated to {@link BmsAttributes#colourMnemonic(byte)} so that the attribute-to-name table
     * has exactly one home. Diagnostic only.
     *
     * @return the mnemonic, or an unrecognised-value description if the byte is not a BMS colour
     */
    @JsonIgnore
    public String messageColourMnemonic() {
        return BmsAttributes.colourMnemonic(messageColour);
    }

    /**
     * The message colour as two hexadecimal digits, for example {@code F2} for
     * {@link BmsAttributes#DFHRED}.
     *
     * <p>Useful because {@link #messageColour()} is a signed Java {@code byte}: the attribute IBM
     * documents as {@code X'F2'} reads back as {@code -14} in decimal, which is correct but unhelpful
     * in a failure message.
     *
     * @return the unsigned hexadecimal rendering
     */
    @JsonIgnore
    public String messageColourHex() {
        return BmsAttributes.toHex(messageColour);
    }

    // =================================================================================================
    // Modification by copy. There is no setter: a response already handed to a caller cannot be
    // changed underneath it, so a variant is produced as a new instance.
    // =================================================================================================

    /**
     * A builder pre-loaded with every member of this instance, for producing a modified copy.
     *
     * <p>{@code response.toBuilder().messageColour(BmsAttributes.DFHGREEN).build()} is how the service
     * reproduces {@code MOVE DFHGREEN TO ERRMSGC OF COADM1AO} at {@code app/cbl/COADM01C.cbl:148}
     * without mutating anything.
     *
     * @return a fresh builder, never {@code null}
     */
    public Builder toBuilder() {
        final Builder builder = new Builder();
        builder.trnName = trnName;
        builder.title01 = title01;
        builder.curDate = curDate;
        builder.pgmName = pgmName;
        builder.title02 = title02;
        builder.curTime = curTime;
        builder.optionLines = optionLines().toArray(new String[0]);
        builder.option = option;
        builder.errMsg = errMsg;
        builder.navigationContext = navigationContext;
        builder.nextProgram = nextProgram;
        builder.nextMapset = nextMapset;
        builder.nextMap = nextMap;
        builder.messageColour = messageColour;
        builder.resetAllOutputFields = resetAllOutputFields;
        return builder;
    }

    /**
     * A builder seeded exactly as {@link #empty()} is: space-filled text, the initial communication
     * area, {@link BmsAttributes#DFHRED}, this screen's mapset and map, and no repaint signal.
     *
     * <p>Defined as {@code empty().toBuilder()} rather than by repeating those defaults, so the two
     * entry points cannot drift apart - {@code builder().build()} equals {@code empty()} by
     * construction.
     *
     * @return a fresh builder at the initial state, never {@code null}
     */
    public static Builder builder() {
        return empty().toBuilder();
    }

    // =================================================================================================
    // Private helpers. Each of the two normalisations and the one subscript check lives here exactly
    // once; see the note above the canonical constructor for why that matters.
    // =================================================================================================

    /**
     * {@code width} spaces - a character field as COBOL leaves it when nothing has been moved into it.
     *
     * @param width the declared width
     * @return the space-filled value
     */
    private static String spaces(final int width) {
        return " ".repeat(width);
    }

    /**
     * {@code value}, or {@code width} spaces when it is absent.
     *
     * <p>The single null decision for all twenty-four character members. COBOL has no null: an
     * unwritten screen field is spaces, so that is what an absent value becomes, which is what makes
     * {@code OPTN005O} through {@code OPTN012O} addressable even though nothing ever writes them.
     *
     * @param value the supplied value, possibly {@code null}
     * @param width the declared width to fall back to
     * @return a non-{@code null} value
     */
    private static String orSpaces(final String value, final int width) {
        return value == null ? spaces(width) : value;
    }

    /**
     * {@code context}, or {@link NavigationContext#empty()} when it is absent.
     *
     * <p>Substituting rather than rejecting keeps the type usable in the same way an unwritten screen
     * field is: a caller can always read the communication area back. A blank context is also the
     * honest translation of {@code EIBCALEN = 0}, the no-commarea entry that
     * {@code app/cbl/COADM01C.cbl:82-84} handles by routing to the sign-on screen.
     *
     * @param context the supplied context, possibly {@code null}
     * @return a non-{@code null} communication area
     */
    private static NavigationContext orEmptyContext(final NavigationContext context) {
        return context == null ? NavigationContext.empty() : context;
    }

    /**
     * Converts a one-based COBOL subscript into a zero-based Java index, rejecting anything outside
     * the declared twelve.
     *
     * @param cobolSubscript the one-based line number
     * @return {@code cobolSubscript - 1}
     * @throws IndexOutOfBoundsException if the subscript is not between 1 and
     *                                   {@value #OPTION_LINE_COUNT} inclusive
     */
    private static int zeroBasedIndex(final int cobolSubscript) {
        if (cobolSubscript < 1 || cobolSubscript > OPTION_LINE_COUNT) {
            throw new IndexOutOfBoundsException("COBOL menu-line subscript must be 1 to "
                    + OPTION_LINE_COUNT + " (the twelve OPTN0nnO fields declared by "
                    + MAPSET_NAME + "), but was " + cobolSubscript);
        }
        return cobolSubscript - 1;
    }

    /**
     * Assembles {@link #PAYLOAD_FIELDS} in map order.
     *
     * @return the unmodifiable name list
     */
    private static List<String> buildPayloadFields() {
        final List<String> names = new ArrayList<>(PAYLOAD_FIELD_COUNT);
        names.add(TRN_NAME_FIELD);
        names.add(TITLE01_FIELD);
        names.add(CUR_DATE_FIELD);
        names.add(PGM_NAME_FIELD);
        names.add(TITLE02_FIELD);
        names.add(CUR_TIME_FIELD);
        names.addAll(OPTION_LINE_FIELDS);
        names.add(OPTION_FIELD);
        names.add(ERR_MSG_FIELD);
        return List.copyOf(names);
    }

    /**
     * Assembles {@link #PAYLOAD_FIELD_LENGTHS} in map order, aligned with
     * {@link #buildPayloadFields()}.
     *
     * @return the unmodifiable width list
     */
    private static List<Integer> buildPayloadFieldLengths() {
        final List<Integer> widths = new ArrayList<>(PAYLOAD_FIELD_COUNT);
        widths.add(TRN_NAME_LENGTH);
        widths.add(TITLE_LENGTH);
        widths.add(CUR_DATE_LENGTH);
        widths.add(PGM_NAME_LENGTH);
        widths.add(TITLE_LENGTH);
        widths.add(CUR_TIME_LENGTH);
        widths.addAll(Collections.nCopies(OPTION_LINE_COUNT, OPTION_LINE_LENGTH));
        widths.add(OPTION_LENGTH);
        widths.add(ERR_MSG_LENGTH);
        return List.copyOf(widths);
    }

    /**
     * Assembles an {@link AdminMenuResponse} without a twenty-six-argument constructor call at every
     * site, and produces a modified copy of an existing one through {@link #toBuilder()}.
     *
     * <p>Deliberately hand-written. Annotation-processor libraries that generate accessors, builders
     * or mappers are outside the closed dependency set of this migration, and a generated builder
     * would put the copybook-to-member correspondence behind a code generator at exactly the point a
     * reviewer needs to read it.
     *
     * <p>Mutable by design and <strong>not</strong> thread-safe: an instance is a short-lived local,
     * created by {@link AdminMenuResponse#builder()} or {@link AdminMenuResponse#toBuilder()} and
     * discarded at {@link #build()}. It holds no static state, it is never shared, and the value it
     * produces is immutable. The constructor is private so a builder can only come into existence
     * already fully seeded, which removes any chance of a primitive member defaulting to something the
     * screen never declares - {@link AdminMenuResponse#messageColour()} would otherwise default to
     * {@link BmsAttributes#DFHDFCOL} rather than the map's {@link BmsAttributes#DFHRED}.
     */
    public static final class Builder {

        private String trnName;
        private String title01;
        private String curDate;
        private String pgmName;
        private String title02;
        private String curTime;
        private String[] optionLines = new String[OPTION_LINE_COUNT];
        private String option;
        private String errMsg;
        private NavigationContext navigationContext;
        private String nextProgram;
        private String nextMapset;
        private String nextMap;
        private byte messageColour = BmsAttributes.DFHRED;
        private boolean resetAllOutputFields;

        private Builder() {
            // Seeded exclusively by AdminMenuResponse.toBuilder(); see the class documentation.
        }

        /**
         * Sets {@code TRNNAMEO} - {@value AdminMenuResponse#TRN_NAME_FIELD}, {@code PIC X(4)}.
         *
         * @param value the transaction identifier, or {@code null} for spaces
         * @return this builder
         */
        public Builder trnName(final String value) {
            this.trnName = value;
            return this;
        }

        /**
         * Sets {@code TITLE01O} - {@value AdminMenuResponse#TITLE01_FIELD}, {@code PIC X(40)}.
         *
         * @param value the first title line, or {@code null} for spaces
         * @return this builder
         */
        public Builder title01(final String value) {
            this.title01 = value;
            return this;
        }

        /**
         * Sets {@code CURDATEO} - {@value AdminMenuResponse#CUR_DATE_FIELD}, {@code PIC X(8)}.
         *
         * @param value the {@code MM/DD/YY} header date, or {@code null} for spaces
         * @return this builder
         */
        public Builder curDate(final String value) {
            this.curDate = value;
            return this;
        }

        /**
         * Sets {@code PGMNAMEO} - {@value AdminMenuResponse#PGM_NAME_FIELD}, {@code PIC X(8)}.
         *
         * @param value the program name, or {@code null} for spaces
         * @return this builder
         */
        public Builder pgmName(final String value) {
            this.pgmName = value;
            return this;
        }

        /**
         * Sets {@code TITLE02O} - {@value AdminMenuResponse#TITLE02_FIELD}, {@code PIC X(40)}.
         *
         * @param value the second title line, or {@code null} for spaces
         * @return this builder
         */
        public Builder title02(final String value) {
            this.title02 = value;
            return this;
        }

        /**
         * Sets {@code CURTIMEO} - {@value AdminMenuResponse#CUR_TIME_FIELD}, {@code PIC X(8)}.
         *
         * @param value the {@code HH:MM:SS} header time, or {@code null} for spaces
         * @return this builder
         */
        public Builder curTime(final String value) {
            this.curTime = value;
            return this;
        }

        /**
         * Sets one menu line by its <strong>COBOL subscript</strong>, 1 through
         * {@value AdminMenuResponse#OPTION_LINE_COUNT} - the shape
         * {@code BUILD-MENU-OPTIONS} works in, where {@code WS-IDX} counts from 1.
         *
         * <p>All twelve slots are settable, including the two the program never assigns. The bound is
         * the declared twelve rather than {@code CDEMO-ADMIN-OPT-COUNT}, because this builder carries
         * the screen's contract and not one screen's population policy.
         *
         * @param cobolSubscript the one-based line number
         * @param value          the composed {@code PIC X(40)} line, or {@code null} for spaces
         * @return this builder
         * @throws IndexOutOfBoundsException if the subscript is outside 1 to
         *                                   {@value AdminMenuResponse#OPTION_LINE_COUNT}
         */
        public Builder optionLine(final int cobolSubscript, final String value) {
            this.optionLines[zeroBasedIndex(cobolSubscript)] = value;
            return this;
        }

        /**
         * Sets {@code OPTN001O} - menu line 1 at {@code POS=(6,20)}, written {@code WHEN 1}.
         *
         * @param value the composed line, or {@code null} for spaces
         * @return this builder
         */
        public Builder optn001(final String value) {
            return optionLine(1, value);
        }

        /**
         * Sets {@code OPTN002O} - menu line 2 at {@code POS=(7,20)}, written {@code WHEN 2}.
         *
         * @param value the composed line, or {@code null} for spaces
         * @return this builder
         */
        public Builder optn002(final String value) {
            return optionLine(2, value);
        }

        /**
         * Sets {@code OPTN003O} - menu line 3 at {@code POS=(8,20)}, written {@code WHEN 3}.
         *
         * @param value the composed line, or {@code null} for spaces
         * @return this builder
         */
        public Builder optn003(final String value) {
            return optionLine(3, value);
        }

        /**
         * Sets {@code OPTN004O} - menu line 4 at {@code POS=(9,20)}, written {@code WHEN 4}. The last
         * line this screen populates, because {@code CDEMO-ADMIN-OPT-COUNT} is 4.
         *
         * @param value the composed line, or {@code null} for spaces
         * @return this builder
         */
        public Builder optn004(final String value) {
            return optionLine(4, value);
        }

        /**
         * Sets {@code OPTN005O} - menu line 5 at {@code POS=(10,20)}. Writable by
         * {@code BUILD-MENU-OPTIONS} {@code WHEN 5}, never reached on the admin screen.
         *
         * @param value the composed line, or {@code null} for spaces
         * @return this builder
         */
        public Builder optn005(final String value) {
            return optionLine(5, value);
        }

        /**
         * Sets {@code OPTN006O} - menu line 6 at {@code POS=(11,20)}. Writable, never reached.
         *
         * @param value the composed line, or {@code null} for spaces
         * @return this builder
         */
        public Builder optn006(final String value) {
            return optionLine(6, value);
        }

        /**
         * Sets {@code OPTN007O} - menu line 7 at {@code POS=(12,20)}. Writable, never reached.
         *
         * @param value the composed line, or {@code null} for spaces
         * @return this builder
         */
        public Builder optn007(final String value) {
            return optionLine(7, value);
        }

        /**
         * Sets {@code OPTN008O} - menu line 8 at {@code POS=(13,20)}. Writable, never reached.
         *
         * @param value the composed line, or {@code null} for spaces
         * @return this builder
         */
        public Builder optn008(final String value) {
            return optionLine(8, value);
        }

        /**
         * Sets {@code OPTN009O} - menu line 9 at {@code POS=(14,20)}. Writable, never reached.
         *
         * @param value the composed line, or {@code null} for spaces
         * @return this builder
         */
        public Builder optn009(final String value) {
            return optionLine(9, value);
        }

        /**
         * Sets {@code OPTN010O} - menu line 10 at {@code POS=(15,20)}. The last line
         * {@code BUILD-MENU-OPTIONS}' {@code EVALUATE} can reach at all.
         *
         * @param value the composed line, or {@code null} for spaces
         * @return this builder
         */
        public Builder optn010(final String value) {
            return optionLine(10, value);
        }

        /**
         * Sets {@code OPTN011O} - menu line 11 at {@code POS=(16,20)}. <strong>No statement in
         * {@code COADM01C} assigns this field</strong>; the mapset declares it, so it is settable
         * here.
         *
         * @param value the composed line, or {@code null} for spaces
         * @return this builder
         */
        public Builder optn011(final String value) {
            return optionLine(11, value);
        }

        /**
         * Sets {@code OPTN012O} - menu line 12 at {@code POS=(17,20)}. <strong>Likewise never
         * assigned by the program</strong>, likewise settable.
         *
         * @param value the composed line, or {@code null} for spaces
         * @return this builder
         */
        public Builder optn012(final String value) {
            return optionLine(12, value);
        }

        /**
         * Sets {@code OPTIONO} - {@value AdminMenuResponse#OPTION_FIELD}, {@code PIC X(2)}: the
         * zero-filled echo written by {@code app/cbl/COADM01C.cbl:125}.
         *
         * @param value the two-character option echo, or {@code null} for spaces
         * @return this builder
         */
        public Builder option(final String value) {
            this.option = value;
            return this;
        }

        /**
         * Sets {@code ERRMSGO} - {@value AdminMenuResponse#ERR_MSG_FIELD}, {@code PIC X(78)}.
         *
         * <p>The value is stored exactly as supplied. Narrowing {@code WS-MESSAGE PIC X(80)} down to
         * 78 is the controller's work through {@code common.FixedWidthCodec}, not this builder's.
         *
         * @param value the already-narrowed message, or {@code null} for spaces
         * @return this builder
         */
        public Builder errMsg(final String value) {
            this.errMsg = value;
            return this;
        }

        /**
         * Sets the echoed {@code CARDDEMO-COMMAREA}.
         *
         * @param value the communication area, or {@code null} for {@link NavigationContext#empty()}
         * @return this builder
         */
        public Builder navigationContext(final NavigationContext value) {
            this.navigationContext = value;
            return this;
        }

        /**
         * Sets the {@code EXEC CICS XCTL} target the client should call next.
         *
         * @param value an option target from {@code app/cpy/COADM02Y.cpy}, or
         *              {@value AdminMenuResponse#SIGNON_PROGRAM} on the return route, or {@code null}
         *              for spaces
         * @return this builder
         */
        public Builder nextProgram(final String value) {
            this.nextProgram = value;
            return this;
        }

        /**
         * Sets the mapset the client should render.
         *
         * @param value the mapset name, or {@code null} for spaces
         * @return this builder
         */
        public Builder nextMapset(final String value) {
            this.nextMapset = value;
            return this;
        }

        /**
         * Sets the map the client should render.
         *
         * @param value the map name, or {@code null} for spaces
         * @return this builder
         */
        public Builder nextMap(final String value) {
            this.nextMap = value;
            return this;
        }

        /**
         * Sets the message-line colour attribute - {@link BmsAttributes#DFHRED} as the map declares
         * it, or {@link BmsAttributes#DFHGREEN} on the coming-soon path of
         * {@code app/cbl/COADM01C.cbl:148}.
         *
         * @param value the BMS colour attribute byte
         * @return this builder
         */
        public Builder messageColour(final byte value) {
            this.messageColour = value;
            return this;
        }

        /**
         * Sets the repaint signal mirroring {@code MOVE LOW-VALUES TO COADM1AO} at
         * {@code app/cbl/COADM01C.cbl:89}.
         *
         * @param value whether the client should clear every output field before painting
         * @return this builder
         */
        public Builder resetAllOutputFields(final boolean value) {
            this.resetAllOutputFields = value;
            return this;
        }

        /**
         * Builds the immutable response. Any member left unset becomes its COBOL-initial value through
         * the canonical constructor: spaces for text, {@link NavigationContext#empty()} for the
         * communication area.
         *
         * <p>The builder may be reused afterwards; the value produced is a snapshot and is unaffected
         * by later calls, because the twelve menu lines are copied out positionally rather than shared.
         *
         * @return the response, never {@code null}
         */
        public AdminMenuResponse build() {
            return new AdminMenuResponse(trnName,
                    title01,
                    curDate,
                    pgmName,
                    title02,
                    curTime,
                    optionLines[0],
                    optionLines[1],
                    optionLines[2],
                    optionLines[3],
                    optionLines[4],
                    optionLines[5],
                    optionLines[6],
                    optionLines[7],
                    optionLines[8],
                    optionLines[9],
                    optionLines[10],
                    optionLines[11],
                    option,
                    errMsg,
                    navigationContext,
                    nextProgram,
                    nextMapset,
                    nextMap,
                    messageColour,
                    resetAllOutputFields);
        }
    }
}
