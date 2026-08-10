package com.vsergeychik.carddemo.billing;

import com.vsergeychik.carddemo.billing.BillPaymentService.PaymentState;
import com.vsergeychik.carddemo.billing.dto.BillPaymentRequest;
import com.vsergeychik.carddemo.billing.dto.BillPaymentResponse;
import com.vsergeychik.carddemo.billing.dto.BillPaymentResponse.CursorField;
import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.DateHeader;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.PfKeyResolver;
import com.vsergeychik.carddemo.common.PfKeyResolver.AidKey;
import com.vsergeychik.carddemo.common.ScreenTitles;
import jakarta.validation.Valid;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * CICS transaction {@code CB00} - program {@code COBIL00C}, "Bill Payment" - as a stateless REST
 * resource on {@value #BILL_PAY_PATH}.
 *
 * <p>{@code app/csd/CARDDEMO.CSD:337-338} binds the transaction:
 * {@code DEFINE TRANSACTION(CB00) GROUP(CARDDEMO) PROGRAM(COBIL00C)}, and
 * {@code app/csd/CARDDEMO.CSD:196-202} defines the program itself as
 * {@code LANGUAGE(COBOL)}. The screen is {@code MAPSET(COBIL00)} at
 * {@code app/csd/CARDDEMO.CSD:114}, map {@code COBIL0A}.
 *
 * <h2>What this class does, and what it deliberately does not</h2>
 *
 * <p>It reproduces {@code MAIN-PARA} ({@code app/cbl/COBIL00C.cbl:99-149}) and the two paragraphs
 * {@code MAIN-PARA} owns outright - {@code RETURN-TO-PREV-SCREEN} ({@code :273-284}) and
 * {@code POPULATE-HEADER-INFO} ({@code :319-338}) - and it marshals the symbolic map on and off the
 * payload. That is the whole of its remit: <strong>navigation and projection</strong>.
 *
 * <p>Everything else belongs to {@link BillPaymentService}, which is the translation of
 * {@code PROCESS-ENTER-KEY} ({@code :154-244}) and of the five file paragraphs beneath it. In
 * particular this class contains:
 *
 * <ul>
 *   <li><strong>no arithmetic at all.</strong> The program's single {@code COMPUTE}
 *       ({@code :234 COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT}) and its single {@code ADD}
 *       ({@code :217 ADD 1 TO WS-TRAN-ID-NUM}) are the service's, and so is the
 *       {@code PIC +9999999999.99} balance mask. No {@code java.math.BigDecimal} appears here, and
 *       no primitive real number appears anywhere in the module;</li>
 *   <li><strong>no repository and no dataset access.</strong> {@code ACCTDAT}, {@code CXACAIX} and
 *       {@code TRANSACT} are reached by the service alone; this package declares no repository of
 *       its own, and this file names no dataset;</li>
 *   <li><strong>no validation rule and no message text.</strong> Every literal
 *       {@code PROCESS-ENTER-KEY} moves into {@code WS-MESSAGE}, including the shared invalid-key
 *       text of {@code :140}, is the service's - see {@link BillPaymentService#invalidKeyPressed}.</li>
 * </ul>
 *
 * <p>That division is not stylistic. It is what lets the parity cases assert the arithmetic with no
 * HTTP layer in the path, and what keeps the branch surface of the {@code billing} package inside
 * plain JUnit reach.
 *
 * <h2>Statelessness</h2>
 *
 * <p>{@code COBIL00C} is pseudo-conversational: every path ends in either
 * {@code EXEC CICS RETURN TRANSID('CB00') COMMAREA(CARDDEMO-COMMAREA)} ({@code :146-149}) or the one
 * {@code EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM) COMMAREA(CARDDEMO-COMMAREA)} ({@code :281-284}),
 * and it expects the next keystroke to bring that area back. Its conversation state is exactly three
 * things and <strong>all three travel in the request and response payloads</strong>:
 *
 * <ol>
 *   <li>the {@value NavigationContext#COMMAREA_LENGTH}-byte {@code CARDDEMO-COMMAREA}, as
 *       {@link NavigationContext}, plus the 58-byte {@code CDEMO-CB00-INFO} extension the program
 *       appends at {@code :64-72}, as six flat payload members;</li>
 *   <li>the attention identifier - which key was pressed - as {@link BillPaymentRequest#getAid()};</li>
 *   <li>the first-entry versus re-entry context, as {@code CDEMO-PGM-CONTEXT} inside that
 *       communication area.</li>
 * </ol>
 *
 * <p>Nothing is retained between calls. There is no servlet session, no session-scoped bean, no
 * request-attribute stash, no cache and no thread-bound storage anywhere in this file. The class
 * holds exactly three fields, all {@code private final} and all immutable or thread-safe, and its
 * only {@code static} members are constants and one immutable lookup table. Two identical requests
 * therefore produce two identical responses, which is the property the statelessness requirement
 * actually asks for.
 *
 * <h2>The one transfer of control is a named target, not a redirect</h2>
 *
 * <p>{@code RETURN-TO-PREV-SCREEN} is the program's only {@code XCTL} site and it takes the
 * communication-area-driven form. It becomes {@link BillPaymentResponse#getNextProgram()}: the client
 * reads the member and issues the follow-up call itself. There is no server-side forward, no HTTP
 * redirect, no request dispatch and no session affinity.
 *
 * <h2>The presentation contract</h2>
 *
 * <p>No component library and no design system exists in this repository, and none is permitted.
 * {@code app/bms/COBIL00.bms} together with {@code app/cpy-bms/COBIL00.CPY} <em>is</em> the screen
 * definition: {@code SIZE=(24,80)} under
 * {@code DFHMSD CTRL=(ALARM,FREEKB) EXTATT=YES LANG=COBOL MODE=INOUT STORAGE=AUTO TIOAPFX=YES}, with
 * ten name-labelled {@code DFHMDF} fields among its twenty-four field definitions - the other
 * fourteen are unnamed literals and stoppers, which are screen furniture and become no payload
 * member. {@link BillPaymentRequest} and {@link BillPaymentResponse} own that projection; this class
 * only fills it.
 *
 * <p>Two of the ten are declared {@code UNPROT} and are therefore the only fields an operator can
 * type into: {@code ACTIDIN} at {@code (6,21)}, which is also the mapset's only {@code IC} field, and
 * {@code CONFIRM} at {@code (15,60)}. The other eight are {@code ASKIP}. All ten nonetheless travel
 * on both sides, because {@code RECEIVE MAP} returns every field whose modified-data tag is set and
 * all ten carry {@code FSET}.
 *
 * <h3>The overlay ruling</h3>
 *
 * <p>{@code app/cpy-bms/COBIL00.CPY:79} declares {@code 01 COBIL0AO REDEFINES COBIL0AI}. After a
 * shared twelve-byte {@code TIOAPFX} filler each field occupies a stride of seven plus its declared
 * width on both views - input side {@code xxxL} two, {@code xxxF} one, filler four, {@code xxxI}
 * <em>n</em>; output side filler three, {@code xxxC}, {@code xxxP}, {@code xxxH}, {@code xxxV} one
 * each, {@code xxxO} <em>n</em> - so <strong>{@code xxxI} and {@code xxxO} occupy the same byte
 * span</strong> and the whole map is 12 + 282 = 294 bytes.
 *
 * <p>That single fact explains three statements that would otherwise look wrong. The program writes
 * the <em>input</em> items {@code ACTIDINI} ({@code :119}, {@code :563}), {@code CURBALI}
 * ({@code :194}, {@code :564}) and {@code CONFIRMI} ({@code :565}), yet those bytes are exactly what
 * {@code SEND MAP ... FROM(COBIL0AO)} transmits as {@code ACTIDINO}, {@code CURBALO} and
 * {@code CONFIRMO}. Every such write therefore populates the <strong>response</strong> here. And
 * {@code MOVE LOW-VALUES TO COBIL0AO} at {@code :114} clears all 294 bytes - both views at once.
 *
 * <h3>The cursor ruling</h3>
 *
 * <p>{@code MOVE -1 TO <field>L} is the CICS idiom for "place the cursor here", honoured by the
 * {@code CURSOR} option of the {@code SEND} at {@code :300}. It writes an {@code xxxL} length item,
 * which is metadata and never a payload member, so the program's seventeen such statements collapse
 * into the single {@link BillPaymentResponse#getCursorField()} indicator. There is deliberately no
 * member named after {@code ACTIDINL} or {@code CONFIRML}, and none named after any {@code xxxF},
 * {@code xxxA}, {@code xxxC}, {@code xxxP}, {@code xxxH} or {@code xxxV} item either.
 *
 * <h3>The attribute ruling</h3>
 *
 * <p>{@code COBIL00C} copies {@code DFHBMSCA}, and its <strong>only</strong> field-attribute write in
 * all 572 lines is {@code MOVE DFHGREEN TO ERRMSGC OF COBIL0AO} at {@code :526}, on the
 * successful-{@code WRITE} arm. The program does <em>not</em> copy {@code CSSETATY}, never writes
 * {@code DFHRED} and never writes an asterisk into a field.
 *
 * <p>Two consequences are load-bearing and are why the shared highlight helper is absent from this
 * file's imports. {@code com.vsergeychik.carddemo.common.FieldAttributeSetter} is
 * <strong>deliberately not used here</strong>: inventing a red-plus-asterisk error highlight would be
 * a new feature on a screen that has none. And
 * {@code com.vsergeychik.carddemo.common.BmsAttributes} is not imported either, because
 * {@link BillPaymentService} already resolves {@code DFHGREEN} into the one-character carrier that
 * {@link BillPaymentResponse#getMessageHighlight()} transports - so the constant is consumed from its
 * single home rather than re-read, and nothing resembling it is redefined locally.
 *
 * <h2>Why the injected set is two collaborators and not four</h2>
 *
 * <p>The behaviour this class needs comes from four shared types, and only two of them are - or can be -
 * injected. Stated explicitly, because a reader auditing the constructor against the migration plan
 * should not have to work it out:
 *
 * <ul>
 *   <li>{@link BillPaymentService} is a Spring bean and is <strong>constructor-injected</strong>.</li>
 *   <li>{@link DateHeader} is an immutable value type with no bean definition anywhere in the module: it
 *       is obtained per call from {@link DateHeader#from(FixedWidthCodec, Clock)}, which takes the clock
 *       and reads it once. The injected collaborator is therefore the {@link Clock} the module publishes
 *       - which is exactly the seam that makes the header assertable - and injecting a
 *       {@code DateHeader} instead would mean injecting a frozen instant.</li>
 *   <li>{@link PfKeyResolver} is a {@code final} class of {@code static} methods with no instance to
 *       inject. It is consumed statically, as it is by every other online controller in this module.</li>
 *   <li>{@link ScreenTitles} and {@link CicsAid} are likewise constant holders and are read
 *       statically.</li>
 * </ul>
 *
 * <h2>Defects and dead paths are preserved</h2>
 *
 * <ul>
 *   <li>{@code RECEIVE-BILLPAY-SCREEN} captures {@code RESP} and {@code RESP2} at {@code :312-313}
 *       and <strong>never tests them</strong>. They are captured here too, and no error handling the
 *       COBOL lacks is added - see {@link #receiveBillpayScreen}.</li>
 *   <li>On first entry with a transaction already selected, {@code PROCESS-ENTER-KEY} sends the
 *       screen and {@code MAIN-PARA} then sends it again at {@code :122}. Both sends are issued -
 *       see {@link #mainPara}.</li>
 *   <li>The {@code WHEN OTHER} arm is reproduced exactly as coded, and no key the source does not
 *       name is given an arm of its own.</li>
 *   <li>The source's own {@code Function :} header contains a typographical error
 *       ({@code app/cbl/COBIL00C.cbl:5-6}) and the mapset's comment header calls the screen a main
 *       menu ({@code app/bms/COBIL00.bms}). Neither is corrected, and neither is allowed to mislead:
 *       this is the bill-payment screen.</li>
 * </ul>
 *
 * <h2>Naming: this package carries no divergence</h2>
 *
 * <p>Worth stating plainly, because sixteen other programs in this migration do carry one. The
 * migration honours prompt-mandated class names verbatim and takes behaviour from the COBOL, which
 * elsewhere leaves the two disagreeing - {@code transaction.TransactionAddController} views,
 * {@code transaction.TransactionViewController} adds, {@code user.UserMenuController} lists users.
 * Here they agree: {@code COBIL00C} really does pay a bill, so there is no swap to hunt for in this
 * package.
 *
 * <p>Two further non-authoritative artefacts are called out for the same reason: {@code docs/**}
 * describes a superseded design targeting a separate repository, and {@code catalog-info.yaml} claims
 * a Java version this module does not use. Neither is consulted here and neither is corrected -
 * conflicts are documented, not silently fixed.
 *
 * <h2>Rules</h2>
 *
 * <p>No user-specified rules were provided for this project: {@code review_rules} returns the single
 * line "No user rules provided.", and that is the whole document. Their absence is not treated as
 * licence to lower the bar - the enterprise-standard practices recorded in the migration plan govern
 * instead, and the ones bearing on this file are the reasons given throughout this comment: the
 * closed dependency set (Spring Web and Bean Validation only, nothing added to the build), the
 * immutability of every reference input under {@code app/cbl}, {@code app/cpy}, {@code app/cpy-bms},
 * {@code app/bms} and {@code app/csd}, the preservation of defects and dead paths, explicitness over
 * implicitness - no wildcard import, every width named, every truncation routed through the codec -
 * constructor injection with no mutable static state, and a controller that holds no business logic.
 *
 * @see BillPaymentService
 * @see BillPaymentRequest
 * @see BillPaymentResponse
 */
@RestController
public final class BillPaymentController {

    // =================================================================================================
    // Identity of the transaction, the program and the screen. Sourced from BillPaymentService and the
    // two payload types rather than retyped, so the values a client reads back in trnName, pgmName,
    // nextMapset and nextMap have exactly one definition in this module.
    // =================================================================================================

    /**
     * The route, {@value}: {@code POST} to it is one invocation of transaction {@code CB00}.
     *
     * <p>{@code POST} because a confirmed invocation writes a transaction record and rewrites the
     * account balance. One route and one verb: the screen is a single BMS map driven by an attention
     * identifier, so a second endpoint would be a second way to reach the same {@code MAIN-PARA} and
     * would have to be kept in step with it forever.
     */
    public static final String BILL_PAY_PATH = "/api/billpay";

    /**
     * {@code WS-PGMNAME PIC X(08) VALUE 'COBIL00C'} - {@code app/cbl/COBIL00C.cbl:37}.
     *
     * <p>Moved into {@code PGMNAMEO} by {@code POPULATE-HEADER-INFO} at {@code :326} and into
     * {@code CDEMO-FROM-PROGRAM} by {@code RETURN-TO-PREV-SCREEN} at {@code :279}.
     */
    public static final String PROGRAM_NAME = BillPaymentService.WS_PGMNAME;

    /**
     * {@code WS-TRANID PIC X(04) VALUE 'CB00'} - {@code app/cbl/COBIL00C.cbl:38}.
     *
     * <p>Moved into {@code TRNNAMEO} at {@code :325}, into {@code CDEMO-FROM-TRANID} at {@code :278},
     * and handed to {@code EXEC CICS RETURN TRANSID(WS-TRANID)} at {@code :147}.
     */
    public static final String TRANSACTION_ID = BillPaymentService.WS_TRANID;

    /**
     * {@code MAPSET('COBIL00')} - the operand of both the {@code SEND} at
     * {@code app/cbl/COBIL00C.cbl:297} and the {@code RECEIVE} at {@code :310}.
     */
    public static final String MAPSET_NAME = BillPaymentResponse.MAPSET_NAME;

    /**
     * {@code MAP('COBIL0A')} - the operand of both the {@code SEND} at
     * {@code app/cbl/COBIL00C.cbl:296} and the {@code RECEIVE} at {@code :309}.
     */
    public static final String MAP_NAME = BillPaymentResponse.MAP_NAME;

    /**
     * {@code 'COSGN00C'} - the sign-on program, and the target of two distinct arms.
     *
     * <p>{@code app/cbl/COBIL00C.cbl:108} moves it into {@code CDEMO-TO-PROGRAM} when
     * {@code EIBCALEN = 0}, and {@code :276} substitutes it inside {@code RETURN-TO-PREV-SCREEN}
     * whenever {@code CDEMO-TO-PROGRAM} arrives blank.
     */
    public static final String SIGN_ON_PROGRAM = BillPaymentResponse.SIGN_ON_PROGRAM;

    /**
     * {@code 'COMEN01C'} - the main menu, and the {@code DFHPF3} fallback of
     * {@code app/cbl/COBIL00C.cbl:130} when {@code CDEMO-FROM-PROGRAM} names no caller to go back to.
     */
    public static final String MAIN_MENU_PROGRAM = BillPaymentResponse.MAIN_MENU_PROGRAM;

    // =================================================================================================
    // The two figurative constants the program tests and moves. Named, because a bare ' ' or '\u0000'
    // at a call site says nothing about which COBOL constant is meant.
    // =================================================================================================

    /** The {@code SPACES} figurative constant, one character of it. */
    public static final char SPACE = ' ';

    /**
     * The {@code LOW-VALUES} figurative constant, one character of it: {@code X'00'}.
     *
     * <p>Distinct from {@link #SPACE} throughout, because this program tests the two separately -
     * {@code :116-117}, {@code :129} and {@code :275} each name both - so a field of blanks and a
     * field of {@code X'00'} bytes must never be collapsed into one another.
     */
    public static final char LOW_VALUE = '\u0000';

    /**
     * Every {@code CCARD-AID} token mapped to the {@code EIBAID} byte it stands for.
     *
     * <p>{@link BillPaymentRequest#getAid()} carries the five-character token that
     * {@code CCARD-AID PIC X(5)} of {@code app/cpy/CVCRD01Y.cpy} declares, because that is the form
     * the whole module's online payloads use. {@code app/cbl/COBIL00C.cbl:125} evaluates the raw
     * {@code EIBAID} byte, so the two have to be reconciled somewhere, and this table is where.
     *
     * <p>Built from the tokens {@link AidKey} itself publishes rather than from retyped literals, so
     * the two cannot drift apart - including the two trailing spaces on {@code 'PA1  '} and
     * {@code 'PA2  '}, which are part of the five-byte value and not incidental formatting.
     *
     * <p>Immutable, and the only {@code static} member here that is not a scalar constant.
     * {@link Map#ofEntries} returns an unmodifiable map, so this is shared state that cannot be
     * mutated by anything - which is what makes it admissible where a mutable static field would not
     * be. A lookup with a default also keeps the reconciliation branch-free at the call site, which
     * matters because the branch counter for this package is gated independently of every other.
     */
    private static final Map<String, Byte> EIBAID_BY_TOKEN = Map.ofEntries(
            Map.entry(AidKey.ENTER.token(), CicsAid.DFHENTER),
            Map.entry(AidKey.CLEAR.token(), CicsAid.DFHCLEAR),
            Map.entry(AidKey.PA1.token(), CicsAid.DFHPA1),
            Map.entry(AidKey.PA2.token(), CicsAid.DFHPA2),
            Map.entry(AidKey.PFK01.token(), CicsAid.DFHPF1),
            Map.entry(AidKey.PFK02.token(), CicsAid.DFHPF2),
            Map.entry(AidKey.PFK03.token(), CicsAid.DFHPF3),
            Map.entry(AidKey.PFK04.token(), CicsAid.DFHPF4),
            Map.entry(AidKey.PFK05.token(), CicsAid.DFHPF5),
            Map.entry(AidKey.PFK06.token(), CicsAid.DFHPF6),
            Map.entry(AidKey.PFK07.token(), CicsAid.DFHPF7),
            Map.entry(AidKey.PFK08.token(), CicsAid.DFHPF8),
            Map.entry(AidKey.PFK09.token(), CicsAid.DFHPF9),
            Map.entry(AidKey.PFK10.token(), CicsAid.DFHPF10),
            Map.entry(AidKey.PFK11.token(), CicsAid.DFHPF11),
            Map.entry(AidKey.PFK12.token(), CicsAid.DFHPF12));

    // =================================================================================================
    // Collaborators. Three fields, all final, all constructor-injected. No field injection, no setter,
    // no @Autowired annotation, and no field holding anything belonging to a single request.
    // =================================================================================================

    /**
     * The translated {@code PROCESS-ENTER-KEY} and the five file paragraphs beneath it.
     *
     * <p>Also the owner of this package's fixed-width codec, and of the three repositories this class
     * deliberately does not see.
     */
    private final BillPaymentService billPaymentService;

    /**
     * The clock behind {@code MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA} at
     * {@code app/cbl/COBIL00C.cbl:321}.
     *
     * <p>Injected and read only through {@link DateHeader}, never as a direct call for the current
     * instant, so the eight-character date and the eight-character time the header carries can be
     * compared byte for byte against an expected image. The module supplies exactly one such bean and
     * this class declares none of its own.
     *
     * <p>The service reads the same bean for {@code EXEC CICS ASKTIME} at {@code :251-253}, which is
     * what keeps the painted header and the stored transaction timestamp consistent within one task.
     */
    private final Clock clock;

    /**
     * The {@code PICTURE} move rules and the code page every image this class renders is encoded in.
     *
     * <p>Taken from {@link BillPaymentService#codec()} rather than constructed, so the controller and
     * the service cannot disagree about which byte is a digit, a space or a sign overpunch. No charset
     * is named in this file and the platform default is never relied on.
     *
     * <p>It is the owner of every pad and truncate decision here. A COBOL {@code MOVE} fills a
     * {@code PIC X} receiver from its leftmost position and discards the overflow on the
     * <strong>right</strong>; that rule is applied by calling the codec, never by writing a substring
     * at a call site.
     */
    private final FixedWidthCodec codec;

    /**
     * Wires the controller.
     *
     * <p>One constructor, both arguments required, every field {@code final}. There is no repository
     * argument, and that is a property of the division of labour rather than an omission: the three
     * datasets {@code COBIL00C} touches are reached by {@link BillPaymentService} alone.
     *
     * @param billPaymentService the translated {@code COBIL00C} decision logic; also supplies this
     *                           package's single fixed-width codec. Must not be {@code null}
     * @param clock              the module's clock bean, read only through {@link DateHeader}; must
     *                           not be {@code null}
     * @throws NullPointerException if either argument is {@code null}, or if the service reports no
     *                              codec - which would leave every fixed-width image this class
     *                              renders unrenderable, and is refused loudly here rather than
     *                              surfacing later from inside a projection
     */
    public BillPaymentController(final BillPaymentService billPaymentService, final Clock clock) {
        this.billPaymentService = Objects.requireNonNull(billPaymentService,
                "A BillPaymentService is required: it is the translated PROCESS-ENTER-KEY of "
                        + "app/cbl/COBIL00C.cbl:154-244, and this controller makes no decision about "
                        + "the payment itself");
        this.clock = Objects.requireNonNull(clock,
                "A Clock is required: POPULATE-HEADER-INFO at app/cbl/COBIL00C.cbl:319-338 renders the "
                        + "date and time header from FUNCTION CURRENT-DATE, and reading the wall clock "
                        + "directly would make that header impossible to assert byte for byte");
        this.codec = Objects.requireNonNull(billPaymentService.codec(),
                "The service reported no fixed-width codec. Every image this controller renders - the "
                        + "eleven-character account field, the seventy-eight-character message line, "
                        + "the eight-character date and time - is fixed-width, so the code page is "
                        + "always stated explicitly by configuration and never taken from the platform "
                        + "default");
    }

    // =================================================================================================
    // The HTTP surface - POST /api/billpay, CSD transaction CB00.
    //
    // One route, one verb, one call to mainPara. Nothing else belongs here: the endpoint applies the
    // absent-body default and hands the payload straight on.
    // =================================================================================================

    /**
     * Runs one invocation of {@code COBIL00C}: {@code POST} {@value #BILL_PAY_PATH}, transaction
     * {@code CB00}, mapset {@code COBIL00}, map {@code COBIL0A}, ten fields.
     *
     * <p><strong>An absent body is the cold start.</strong> {@code app/cbl/COBIL00C.cbl:107} tests
     * {@code IF EIBCALEN = 0} to tell a transaction typed at a clear screen from one transferred into
     * mid-conversation, and it answers the former by handing control to {@value #SIGN_ON_PROGRAM}. The
     * body parameter is therefore {@code required = false}, and an absent one becomes an empty payload
     * whose communication area is absent - which is the same state a body that simply omits the
     * communication area arrives in. Both reach the arm, because absence is the encoding of
     * {@code EIBCALEN = 0} and there is deliberately no second discriminator that could disagree with
     * it.
     *
     * <p>A fresh payload is allocated for that default rather than a shared instance being reused:
     * {@link BillPaymentRequest} is a mutable bean, so one shared instance would be mutable state
     * reachable from every request.
     *
     * <p>Always answers {@code 200 OK}, because every path through {@code COBIL00C} ends in either
     * {@code EXEC CICS RETURN} or {@code EXEC CICS XCTL} and both are successful outcomes. A rejected
     * account identifier, an unrecognised confirmation character or an unhandled key is a message on
     * the screen and not a {@code 4xx}: the program moves the text into {@code WS-MESSAGE} and
     * repaints. The only non-{@code 200} responses this endpoint can produce come from the module's
     * global error mapper - a payload that breaches a declared field width, or a body that will not
     * parse.
     *
     * @param request the inbound screen and communication area, or {@code null} for the cold start;
     *                validated against the symbolic map's declared widths
     * @return the outbound screen: the ten map members, the cursor indicator, the message colour, the
     *         communication area to send back next time, the six communication-area extension members,
     *         and the navigation triple naming where the client goes if control transferred. Never
     *         {@code null}
     */
    @PostMapping(path = BILL_PAY_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public BillPaymentResponse payBill(
            @Valid @RequestBody(required = false) final BillPaymentRequest request) {
        return mainPara(Objects.requireNonNullElse(request, new BillPaymentRequest())).response();
    }

    // =================================================================================================
    // MAIN-PARA - app/cbl/COBIL00C.cbl:99-149.
    // =================================================================================================

    /**
     * {@code MAIN-PARA} - the program's entry point, lines 99 to 149.
     *
     * <p>Returns the terminal {@link Invocation} rather than only the response, because two observable
     * things this program produces have no home in the ten-field payload: how many times the screen was
     * sent, and the working-storage flags a parity case asserts on. Both are reported on the
     * {@link PaymentState}; {@link #payBill} projects {@link Invocation#response()}.
     *
     * <p>The branch structure is the source's, arm for arm and in the source's order:
     *
     * <ol>
     *   <li>{@code :107} {@code IF EIBCALEN = 0} - nothing was passed, so the program cannot know who
     *       called it and hands control to {@value #SIGN_ON_PROGRAM}. This arm transfers and never
     *       paints the screen.</li>
     *   <li>{@code :112} {@code IF NOT CDEMO-PGM-REENTER} - a first entry. The context is advanced to
     *       re-enter, the whole output map is cleared to {@code LOW-VALUES}, the cursor is placed in the
     *       account field, and <strong>if another screen navigated here with a transaction already
     *       selected the payment processing runs immediately</strong> ({@code :116-121}); either way the
     *       screen is then sent ({@code :122}).</li>
     *   <li>{@code :123} otherwise a re-entry: receive the map ({@code :124}) and dispatch on
     *       {@code EIBAID} ({@code :125-142}) with {@code WHEN OTHER} last.</li>
     * </ol>
     *
     * <p><strong>The screen is sent twice on the selected-transaction path, and both sends are
     * issued.</strong> {@code PERFORM PROCESS-ENTER-KEY} at {@code :120} is a paragraph call that
     * returns, and every arm of that paragraph performs {@code SEND-BILLPAY-SCREEN} itself; control
     * then reaches {@code :122}, which sends again unconditionally. Collapsing the two would change
     * {@link PaymentState#screensSent()}, which the parity harness measures, so they are not collapsed.
     *
     * <p><strong>Where the four screen data fields come from differs by arm, and deliberately
     * so.</strong> On a first entry they come from the cleared map. On a re-entry they come from
     * {@code RECEIVE MAP}. And on the enter-key arm they come from
     * {@link BillPaymentService#processEnterKey(String, String, NavigationContext)}, which builds its
     * own working storage from the three items {@code PROCESS-ENTER-KEY} actually consults -
     * {@code ACTIDINI} at {@code :159}, {@code CONFIRMI} at {@code :173} and the communication area.
     * {@code CURBALI} is not among them, because the paragraph <em>writes</em> that field at
     * {@code :194} and never reads it; on the one arm that leaves it unwritten - a blank account
     * identifier, rejected at {@code :159-164} before the guard at {@code :169} is reached - the field
     * therefore carries the service's declared initial blanks. That boundary belongs to the service and
     * is not second-guessed here.
     *
     * @param request the inbound screen; must not be {@code null}. A {@code null} communication area
     *                inside it still means {@code EIBCALEN = 0}
     * @return the state at the moment the task returned to CICS or transferred, never {@code null}
     * @throws NullPointerException if {@code request} is {@code null}
     */
    Invocation mainPara(final BillPaymentRequest request) {
        Objects.requireNonNull(request, "A payload is required: COBIL00C is driven entirely by its "
                + "communication area, the EIBAID and the received map, all of which travel in it. An "
                + "absent body is represented by an empty payload whose communication area is null, "
                + "and not by a null payload");

        // ---------------------------------------------------------------------------------------------
        // :101-:105  the prologue.
        //
        //   :101 SET ERR-FLG-OFF TO TRUE      -> PaymentState's declared initial value, 'N'
        //   :102 SET USR-MODIFIED-NO TO TRUE  -> PaymentState's declared initial value, 'N'
        //   :104 MOVE SPACES TO WS-MESSAGE    -> PaymentState's declared initial value, 80 spaces
        //   :105 MOVE SPACES TO ERRMSGO OF COBIL0AO
        //
        // The first three are working storage and belong to the state, which starts in exactly that
        // condition; the fourth is a map field and is written here. It is written FIRST, before the
        // :114 LOW-VALUES clear can overwrite it on the first-entry arm and before the send copies
        // WS-MESSAGE over it at :293 - the order is the source's and is kept.
        // ---------------------------------------------------------------------------------------------
        final BillPaymentResponse response = new BillPaymentResponse();
        response.setErrMsg(spaces(BillPaymentResponse.ERR_MSG_LENGTH));

        final NavigationContext passed = request.getNavigationContext();

        // ---------------------------------------------------------------------------------------------
        // :107-:109  IF EIBCALEN = 0.
        //
        // No communication area travelled, so :111 never runs and CARDDEMO-COMMAREA stays in its
        // WORKING-STORAGE initial state. Note what else does not run: the symbolic map is never cleared
        // and POPULATE-HEADER-INFO is never performed, because SEND-BILLPAY-SCREEN is the only caller of
        // it and this arm does not send. The nine map members other than the message therefore stay in
        // their uninitialised state, which this payload represents as an absent member, and the six
        // communication-area extension members stay at their own declared initial values rather than
        // echoing anything a client may have sent alongside a missing area.
        // ---------------------------------------------------------------------------------------------
        if (passed == null) {
            final PaymentState state = new PaymentState(codec, NavigationContext.empty());
            // :108 MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM
            final NavigationContext coldStart =
                    NavigationContext.empty().withToProgram(SIGN_ON_PROGRAM);
            // :109 PERFORM RETURN-TO-PREV-SCREEN
            response.setNavigationContext(returnToPrevScreen(response, coldStart));
            return new Invocation(response, state);
        }

        // ---------------------------------------------------------------------------------------------
        // :111  MOVE DFHCOMMAREA(1:EIBCALEN) TO CARDDEMO-COMMAREA.
        //
        // The 58-byte CDEMO-CB00-INFO extension declared at :64-72 travels with it, because in this
        // program it is part of the same 01 group - which is what makes the passed area 218 bytes rather
        // than 160. It arrives as six flat payload members and is echoed by echoCommarea.
        // ---------------------------------------------------------------------------------------------

        // :112  IF NOT CDEMO-PGM-REENTER - a first entry.
        if (!passed.isReenter()) {
            return firstEntry(response, request, passed);
        }

        return reentry(response, request, passed);
    }

    /**
     * The first-entry arm of {@code MAIN-PARA} - {@code app/cbl/COBIL00C.cbl:113-122}.
     *
     * <p>Five statements, in the source's order:
     *
     * <ol>
     *   <li>{@code :113 SET CDEMO-PGM-REENTER TO TRUE} - the context is advanced <strong>before</strong>
     *       anything else, which is what makes the next keystroke take the other branch. It is advanced
     *       before {@code PROCESS-ENTER-KEY} is reached too, so the communication area that paragraph
     *       carries is already at re-enter;</li>
     *   <li>{@code :114 MOVE LOW-VALUES TO COBIL0AO} - all 294 bytes of the map, both views at once;</li>
     *   <li>{@code :115 MOVE -1 TO ACTIDINL OF COBIL0AI} - the cursor into the account field, which is
     *       also the field the mapset marks {@code IC};</li>
     *   <li>{@code :116-121} the selected-transaction test and, where it passes, the immediate
     *       processing;</li>
     *   <li>{@code :122 PERFORM SEND-BILLPAY-SCREEN} - unconditional.</li>
     * </ol>
     *
     * <p><strong>{@code CDEMO-CB00-TRN-SELECTED} is the one member of the extension the program reads.</strong>
     * The test at {@code :116-117} is {@code NOT = SPACES AND LOW-VALUES}, an abbreviated combined
     * relation meaning "equal to neither", so a field that is entirely blank and a field that is
     * entirely {@code X'00'} both fail it while a partially blank one passes - which is why an
     * eleven-character account identifier padded into a sixteen-character carrier still triggers the
     * lookup. The move at {@code :118-119} is {@code PIC X(16)} into {@code PIC X(11)}, so it keeps the
     * leading eleven characters and discards the rest on the right; that direction is chosen by calling
     * the codec rather than by writing a substring here.
     *
     * <p>{@code CONFIRMI} is still {@code LOW-VALUES} at that point, cleared two statements earlier by
     * {@code :114} and untouched since, so the confirmation switch at {@code :173-191} takes its
     * {@code WHEN LOW-VALUES} arm and reads the account without paying anything. The value is passed
     * explicitly rather than left to a default, because the arm it selects is the difference between
     * displaying a balance and debiting one.
     *
     * <p>The cursor set at {@code :115} is not observable on the selected-transaction path, because
     * every arm of {@code PROCESS-ENTER-KEY} reaches a cursor statement of its own - {@code :163},
     * {@code :189}, {@code :203}, {@code :239}, {@code :363}, {@code :370} and the rest - and the last
     * one to run wins. It is still set, at the statement the source sets it, on the state this arm
     * creates.
     *
     * @param response the payload being filled, already carrying the blanked message line of {@code :105}
     * @param request  the inbound screen
     * @param passed   {@code CARDDEMO-COMMAREA} exactly as it arrived
     * @return the state at the moment the task returned to CICS, never {@code null}
     */
    private Invocation firstEntry(final BillPaymentResponse response,
                                  final BillPaymentRequest request,
                                  final NavigationContext passed) {

        // :113  SET CDEMO-PGM-REENTER TO TRUE.
        final NavigationContext commarea = passed.withPgmReenter();

        // :114  MOVE LOW-VALUES TO COBIL0AO - the whole overlay. On the payload the nine unwritten
        //       members are already absent, which is how this projection represents LOW-VALUES; on the
        //       working storage the four data fields are set to it explicitly, at their declared widths,
        //       so that the SPACES-versus-LOW-VALUES tests downstream see what the source sees.
        final PaymentState state = new PaymentState(codec, commarea);
        state.setActIdIn(lowValues(BillPaymentResponse.ACT_ID_IN_LENGTH));
        state.setCurBal(lowValues(BillPaymentResponse.CUR_BAL_LENGTH));
        state.setConfirm(lowValues(BillPaymentResponse.CONFIRM_LENGTH));
        state.setErrMsg(lowValues(BillPaymentResponse.ERR_MSG_LENGTH));

        // :115  MOVE -1 TO ACTIDINL OF COBIL0AI.
        state.setCursorField(CursorField.ACTIDIN);

        // :116-:117  IF CDEMO-CB00-TRN-SELECTED NOT = SPACES AND LOW-VALUES.
        final String trnSelected =
                materialise(request.getTrnSelected(), BillPaymentResponse.TRN_SELECTED_LENGTH);
        PaymentState terminal = state;
        if (!isSpacesOrLowValues(trnSelected)) {
            // :118-:119  MOVE CDEMO-CB00-TRN-SELECTED TO ACTIDINI OF COBIL0AI - X(16) into X(11).
            // :120       PERFORM PROCESS-ENTER-KEY, which sends the screen on every arm it can take.
            terminal = billPaymentService.processEnterKey(
                    codec.movePicX(trnSelected, BillPaymentResponse.ACT_ID_IN_LENGTH),
                    state.confirm(),
                    commarea);
        }

        // :122  PERFORM SEND-BILLPAY-SCREEN - unconditional, and therefore the second send whenever the
        //       branch above ran. Both are issued; neither is collapsed into the other.
        billPaymentService.sendBillpayScreen(terminal);
        projectSentScreen(response, terminal);
        echoCommarea(response, request, commarea);
        return new Invocation(response, terminal);
    }

    /**
     * The re-entry arm of {@code MAIN-PARA} - {@code app/cbl/COBIL00C.cbl:124-142}: receive the map,
     * then dispatch on the attention identifier.
     *
     * <p>The {@code EVALUATE EIBAID} at {@code :125} has exactly four arms and they are reproduced in
     * the source's order, with {@code WHEN OTHER} last so that the first match wins:
     *
     * <ol>
     *   <li>{@code :126-127} {@code DFHENTER} - process the screen.</li>
     *   <li>{@code :128-135} {@code DFHPF3} - navigate back, to {@code CDEMO-FROM-PROGRAM} where one is
     *       set and to {@value #MAIN_MENU_PROGRAM} otherwise.</li>
     *   <li>{@code :136-137} {@code DFHPF4} - clear the screen.</li>
     *   <li>{@code :138-141} {@code WHEN OTHER} - the standard invalid-key message.</li>
     * </ol>
     *
     * <p><strong>There is no {@code PF7}, {@code PF8}, {@code PF12}, {@code CLEAR} or {@code PA1} arm in
     * this program</strong>, and none is invented: every other key falls into {@code WHEN OTHER}, which
     * is also where a token naming no key at all lands. The mapset advertises exactly that subset at
     * {@code (24,1)} with {@code INITIAL='ENTER=Continue  F3=Back  F4=Clear'}.
     *
     * <p>{@code PROCESS-ENTER-KEY} is given the map <em>as {@code RECEIVE} left it</em> rather than the
     * raw payload, because {@code :159} and {@code :173} read {@code ACTIDINI OF COBIL0AI} and
     * {@code CONFIRMI OF COBIL0AI} - the received map, at its declared widths, with an untransmitted
     * field standing at {@code LOW-VALUES}.
     *
     * @param response the payload being filled, already carrying the blanked message line of {@code :105}
     * @param request  the inbound screen
     * @param passed   {@code CARDDEMO-COMMAREA} exactly as it arrived
     * @return the state at the moment the task returned to CICS or transferred, never {@code null}
     */
    private Invocation reentry(final BillPaymentResponse response,
                               final BillPaymentRequest request,
                               final NavigationContext passed) {

        // :124  PERFORM RECEIVE-BILLPAY-SCREEN.
        final PaymentState state = new PaymentState(codec, passed);
        receiveBillpayScreen(response, state, request);

        // :125  EVALUATE EIBAID. Raw-byte equalities, exactly as the source's EVALUATE compares them.
        final byte eibAid = eibAidOf(request.getAid());

        if (PfKeyResolver.isEnter(eibAid)) {                                              // :126
            // :127  PERFORM PROCESS-ENTER-KEY. The paragraph owns its own working storage, because it
            //       owns the unit of work the UPDATE read at :351 needs, so the state it returns - not
            //       the one the receive filled - is the terminal one.
            final PaymentState afterEntry = billPaymentService.processEnterKey(
                    state.actIdIn(), state.confirm(), passed);
            projectSentScreen(response, afterEntry);
            echoCommarea(response, request, passed);
            return new Invocation(response, afterEntry);
        } else if (PfKeyResolver.isPf3(eibAid)) {                                         // :128
            NavigationContext commarea = passed;
            // :129  IF CDEMO-FROM-PROGRAM = SPACES OR LOW-VALUES.
            if (isSpacesOrLowValues(commarea.fromProgram())) {
                commarea = commarea.withToProgram(MAIN_MENU_PROGRAM);                     // :130
            } else {
                commarea = commarea.withToProgram(commarea.fromProgram());                // :132-133
            }
            // :135  PERFORM RETURN-TO-PREV-SCREEN. No send on this arm, so the map keeps whatever the
            //       receive put in it and the header stays unpainted.
            echoCommarea(response, request, returnToPrevScreen(response, commarea));
            return new Invocation(response, state);
        } else if (PfKeyResolver.isPf4(eibAid)) {                                         // :136
            billPaymentService.clearCurrentScreen(state);                                 // :137
            projectSentScreen(response, state);
            echoCommarea(response, request, passed);
            return new Invocation(response, state);
        } else {                                                                          // :138
            // :139-:141  MOVE 'Y' TO WS-ERR-FLG, MOVE CCDA-MSG-INVALID-KEY TO WS-MESSAGE,
            //            PERFORM SEND-BILLPAY-SCREEN. All three are the service's, so that the flag,
            //            the text and the send stay in one place with the program's other decision arms.
            //            No cursor statement appears on this arm, so the cursor stays where it was.
            billPaymentService.invalidKeyPressed(state);
            projectSentScreen(response, state);
            echoCommarea(response, request, passed);
            return new Invocation(response, state);
        }
    }

    // =================================================================================================
    // RETURN-TO-PREV-SCREEN - app/cbl/COBIL00C.cbl:273-284. The program's only XCTL.
    // =================================================================================================

    /**
     * {@code RETURN-TO-PREV-SCREEN} - lines 273 to 284.
     *
     * <p>Four statements and one transfer of control:
     *
     * <ol>
     *   <li>{@code :275-277} {@code IF CDEMO-TO-PROGRAM = LOW-VALUES OR SPACES} substitute
     *       {@value #SIGN_ON_PROGRAM}. The guard is a real one: the cold-start arm at {@code :108} has
     *       already named a target, so it passes through untouched, whereas a re-entry whose caller left
     *       the field blank is diverted to sign-on rather than transferring to nothing;</li>
     *   <li>{@code :278} {@code MOVE WS-TRANID TO CDEMO-FROM-TRANID} - {@value #TRANSACTION_ID};</li>
     *   <li>{@code :279} {@code MOVE WS-PGMNAME TO CDEMO-FROM-PROGRAM} - {@value #PROGRAM_NAME}. The two
     *       together are how the next screen knows who called it, and are what makes <em>its</em> own
     *       {@code PF3} come back here;</li>
     *   <li>{@code :280} {@code MOVE ZEROS TO CDEMO-PGM-CONTEXT} - back to enter, so the next screen
     *       paints itself rather than validating a map nobody typed into.</li>
     * </ol>
     *
     * <p>{@code :281-284 EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM) COMMAREA(CARDDEMO-COMMAREA)} becomes a
     * <strong>named target on the response</strong>. The client reads
     * {@link BillPaymentResponse#getNextProgram()} and issues the follow-up call itself: no server-side
     * forward, no HTTP redirect, no request dispatch, no session affinity.
     *
     * <p>{@code nextMapset} and {@code nextMap} are deliberately left absent on this path. They report
     * the {@code MAPSET} and {@code MAP} operands of the {@code SEND} at {@code :296-297}, and this
     * paragraph does not send - the program transfers control, and the program it transfers to renders
     * its own screen. Naming {@code COBIL00} and {@code COBIL0A} here would tell the client to paint the
     * bill-payment screen after leaving it.
     *
     * <p>The communication area is returned rather than mutated: {@link NavigationContext} is an
     * immutable record, and {@link PaymentState#commarea()} is final for the same reason - one
     * invocation cannot alter the area another invocation is holding.
     *
     * @param response the payload being filled; receives the transfer target
     * @param commarea the communication area as this arm has left it, with {@code CDEMO-TO-PROGRAM}
     *                 already naming the intended destination where the caller set one
     * @return the stamped communication area, ready to travel back in the payload; never {@code null}
     */
    NavigationContext returnToPrevScreen(final BillPaymentResponse response,
                                         final NavigationContext commarea) {
        NavigationContext stamped = commarea;

        // :275-:277  IF CDEMO-TO-PROGRAM = LOW-VALUES OR SPACES.
        if (isSpacesOrLowValues(stamped.toProgram())) {
            stamped = stamped.withToProgram(SIGN_ON_PROGRAM);                             // :276
        }

        stamped = stamped
                .withFromTranid(TRANSACTION_ID)                                           // :278
                .withFromProgram(PROGRAM_NAME)                                            // :279
                .withPgmEnter();                                                          // :280

        // :281-:284  EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM) COMMAREA(CARDDEMO-COMMAREA).
        response.setNextProgram(stamped.toProgram());
        return stamped;
    }

    // =================================================================================================
    // RECEIVE-BILLPAY-SCREEN - app/cbl/COBIL00C.cbl:306-314.
    // =================================================================================================

    /**
     * {@code RECEIVE-BILLPAY-SCREEN} - lines 306 to 314:
     * {@code EXEC CICS RECEIVE MAP('COBIL0A') MAPSET('COBIL00') INTO(COBIL0AI) RESP(WS-RESP-CD)
     * RESP2(WS-REAS-CD)}.
     *
     * <p>All ten {@code xxxI} items are filled, not only the two the operator can type into. Every field
     * on this mapset carries {@code FSET}, which sets the modified-data tag when the field is sent, and a
     * 3270 returns every field whose tag is set - so the eight {@code ASKIP} items come back too and are
     * echoed rather than discarded. That is what the source's buffer holds after this statement, and the
     * arms that do not send afterwards - notably {@code DFHPF3} - leave exactly those bytes in place.
     *
     * <p>An untransmitted field arrives as {@code LOW-VALUES}, which is what CICS leaves in a symbolic
     * map item it received nothing for, so an absent payload member becomes a run of {@code X'00'} at the
     * field's declared width rather than blanks or {@code null}. The distinction matters: {@code :159},
     * {@code :182-183} and {@code :199} each test blanks and {@code LOW-VALUES} separately.
     *
     * <p><strong>{@code RESP} and {@code RESP2} are captured and never tested.</strong> The source
     * declares {@code WS-RESP-CD} and {@code WS-REAS-CD} at {@code :46-47}, names them on this command,
     * and then does not examine them on any path - unlike the five file paragraphs, every one of which
     * evaluates them. They are recorded on the working storage here for the same reason: so that a parity
     * case can see the values the command reported, and so that the absence of a test is visible as a
     * deliberate omission rather than as something forgotten. No error handling the COBOL lacks is added.
     * The values recorded are the successful outcome, because a payload that bound is a map that arrived.
     *
     * @param response the payload being filled; receives all ten received field values
     * @param state    this invocation's working storage; receives the four data fields the program reads
     *                 or writes, and the two response codes
     * @param request  the inbound screen
     */
    void receiveBillpayScreen(final BillPaymentResponse response,
                              final PaymentState state,
                              final BillPaymentRequest request) {

        // The six header items. Received because they carry FSET, and held on the payload rather than on
        // the working storage because the program has no working-storage item for any of them: it writes
        // them from literals and from FUNCTION CURRENT-DATE in POPULATE-HEADER-INFO and never reads them.
        response.setTrnName(materialise(request.getTrnName(), BillPaymentResponse.TRN_NAME_LENGTH));
        response.setTitle01(materialise(request.getTitle01(), BillPaymentResponse.TITLE01_LENGTH));
        response.setCurDate(materialise(request.getCurDate(), BillPaymentResponse.CUR_DATE_LENGTH));
        response.setPgmName(materialise(request.getPgmName(), BillPaymentResponse.PGM_NAME_LENGTH));
        response.setTitle02(materialise(request.getTitle02(), BillPaymentResponse.TITLE02_LENGTH));
        response.setCurTime(materialise(request.getCurTime(), BillPaymentResponse.CUR_TIME_LENGTH));

        // The four data items. ACTIDINI at :159 and CONFIRMI at :173 are read by PROCESS-ENTER-KEY;
        // CURBALI at :194 and ERRMSGO at :293 are written by it and by the send. All four are held on the
        // working storage as well as the payload, because the buffer is one buffer.
        state.setActIdIn(materialise(request.getActIdIn(), BillPaymentResponse.ACT_ID_IN_LENGTH));
        state.setCurBal(materialise(request.getCurBal(), BillPaymentResponse.CUR_BAL_LENGTH));
        state.setConfirm(materialise(request.getConfirm(), BillPaymentResponse.CONFIRM_LENGTH));
        state.setErrMsg(materialise(request.getErrMsg(), BillPaymentResponse.ERR_MSG_LENGTH));
        response.setActIdIn(state.actIdIn());
        response.setCurBal(state.curBal());
        response.setConfirm(state.confirm());
        response.setErrMsg(state.errMsg());

        // :312-:313  RESP(WS-RESP-CD) RESP2(WS-REAS-CD) - captured, and tested by nothing.
        state.setResponseCodes(FileStatus.NORMAL, FileStatus.NO_REASON_CODE);
    }

    // =================================================================================================
    // SEND-BILLPAY-SCREEN and POPULATE-HEADER-INFO - app/cbl/COBIL00C.cbl:289-301 and :319-338.
    // =================================================================================================

    /**
     * Projects onto the payload what {@code SEND-BILLPAY-SCREEN} transmitted - lines 289 to 301.
     *
     * <p>The paragraph is three statements and they are split between two owners. The middle one,
     * {@code :293 MOVE WS-MESSAGE TO ERRMSGO OF COBIL0AO}, and the {@code SEND} itself belong to
     * {@link BillPaymentService#sendBillpayScreen(PaymentState)}, which is where the message truncation
     * and the send count live. The first, {@code :291 PERFORM POPULATE-HEADER-INFO}, is pure presentation
     * with no decision in it and is performed here. This method finishes the job by copying the map out
     * of the working storage and onto the payload.
     *
     * <p>It is called on every arm that sends and on no arm that does not, which is what keeps the header
     * unpainted on the two arms the source never paints - {@code EIBCALEN = 0} at {@code :107-109} and
     * {@code DFHPF3} at {@code :128-135}. It records no send of its own: the arms that reach it have
     * already sent, either inside {@code PROCESS-ENTER-KEY}, inside {@code CLEAR-CURRENT-SCREEN}, inside
     * the invalid-key arm, or through the explicit send at {@code :122}.
     *
     * <p>The four data members come from the working storage because
     * {@code 01 COBIL0AO REDEFINES COBIL0AI} makes the input and output views one buffer: the program
     * writes {@code CURBALI} at {@code :194} and {@code ACTIDINI}, {@code CURBALI} and {@code CONFIRMI}
     * at {@code :563-565}, and those are the bytes {@code FROM(COBIL0AO)} transmits.
     *
     * <p>Two members beside the ten are metadata rather than field projections, and both are passed
     * through exactly as the service resolved them. The cursor indicator collapses the program's
     * seventeen {@code MOVE -1 TO xxxL} statements. The message colour carries the program's one and only
     * field-attribute write, {@code MOVE DFHGREEN TO ERRMSGC OF COBIL0AO} at {@code :526}: absent on
     * every other path, where the mapset's declared {@code COLOR=RED} stands. Nothing here writes an
     * attribute of its own, and no path in this program produces a red-plus-asterisk highlight, because
     * the program does not copy {@code CSSETATY}.
     *
     * <p>{@code nextMapset} and {@code nextMap} report the {@code MAPSET} and {@code MAP} operands of the
     * {@code SEND} at {@code :296-297}, so a stateless client knows which screen the payload belongs to.
     * {@code nextProgram} stays absent, which is this payload's way of saying that the screen
     * re-displays itself and no transfer of control was requested.
     *
     * @param response the payload being filled
     * @param state    the working storage as the last send left it
     */
    void projectSentScreen(final BillPaymentResponse response, final PaymentState state) {
        // :291  PERFORM POPULATE-HEADER-INFO.
        populateHeaderInfo(response);

        // :298  FROM(COBIL0AO) - the four data items, out of the one buffer both views share.
        response.setActIdIn(state.actIdIn());
        response.setCurBal(state.curBal());
        response.setConfirm(state.confirm());
        response.setErrMsg(state.errMsg());

        // :300  CURSOR, honouring whichever MOVE -1 TO xxxL ran last, and :526 MOVE DFHGREEN TO ERRMSGC.
        response.setCursorField(state.cursorField());
        response.setMessageHighlight(state.messageHighlight());

        // :296-:297  MAP('COBIL0A') MAPSET('COBIL00').
        response.setNextMapset(MAPSET_NAME);
        response.setNextMap(MAP_NAME);
    }

    /**
     * {@code POPULATE-HEADER-INFO} - lines 319 to 338: the six header fields.
     *
     * <p>{@code :321 MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA} is read from the injected clock,
     * exactly once per call, through {@link DateHeader#from(FixedWidthCodec, Clock)}. Reading it once is
     * not an optimisation: the date and the time are two separate eight-character renderings, and two
     * reads could straddle a second boundary and produce a header no expected image could match. The
     * wall clock is never consulted directly.
     *
     * <p>The two title lines are the {@code COTTL01Y} literals, byte-exact from
     * {@link ScreenTitles} and never retyped here - {@code CCDA-TITLE02} in particular has a commented-out
     * alternative directly above it in the copybook, and resolving which of the two is live is that
     * class's job, not a decision to re-take at a call site. Both are {@code PIC X(40)} and both
     * receivers are {@code PIC X(40)}, so the move is an exact fit with nothing padded and nothing lost.
     * {@code CCDA-THANK-YOU}, the third literal in that copybook, is not used by this program and is not
     * referenced here.
     *
     * <p>The date is {@code MM/DD/YY}: {@code :328-330} assemble it from {@code WS-CURDATE-MONTH},
     * {@code WS-CURDATE-DAY} and {@code WS-CURDATE-YEAR(3:2)} - the last two digits of the four-digit
     * year, taken by reference modification - into {@code WS-CURDATE-MM-DD-YY}, whose two {@code FILLER}
     * items carry the {@code '/'} separators from {@code app/cpy/CSDAT01Y.cpy}. The time is
     * {@code HH:MM:SS}, assembled at {@code :334-336} with {@code ':'} separators from the same copybook;
     * {@code WS-CURTIME} also declares a two-digit {@code MILSEC} item, which this header does not use.
     * Both renderings, both separator sets and the two-digit year all belong to {@link DateHeader}, so
     * neither is re-derived here.
     *
     * @param response the payload being filled; receives the six header members
     */
    void populateHeaderInfo(final BillPaymentResponse response) {
        // :321  MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA.
        final DateHeader header = DateHeader.from(codec, clock);

        response.setTitle01(ScreenTitles.CCDA_TITLE01);                                   // :323
        response.setTitle02(ScreenTitles.CCDA_TITLE02);                                   // :324
        response.setTrnName(TRANSACTION_ID);                                              // :325
        response.setPgmName(PROGRAM_NAME);                                                // :326
        response.setCurDate(header.wsCurdateMmDdYy());                                     // :328-:332
        response.setCurTime(header.wsCurtimeHhMmSs());                                     // :334-:338
    }

    // =================================================================================================
    // COMMAREA(CARDDEMO-COMMAREA) - the 218 bytes both the RETURN at :148 and the XCTL at :283 pass.
    // =================================================================================================

    /**
     * Echoes the communication area the program leaves behind: the
     * {@value NavigationContext#COMMAREA_LENGTH}-byte {@code CARDDEMO-COMMAREA} plus the 58-byte
     * {@code CDEMO-CB00-INFO} extension {@code app/cbl/COBIL00C.cbl:64-72} appends to it.
     *
     * <p>{@code EXEC CICS RETURN TRANSID('CB00') COMMAREA(CARDDEMO-COMMAREA)} at {@code :146-149} routes
     * the operator's next keystroke straight back to this transaction and hands the area over with it;
     * {@code EXEC CICS XCTL ... COMMAREA(CARDDEMO-COMMAREA)} at {@code :281-284} hands the same area to
     * whichever program takes over. Either way the client carries it into the next call, which is what
     * makes the conversation work with nothing remembered on the server.
     *
     * <p><strong>Five of the extension's six members are never read by this program.</strong> Verified by
     * exhaustive search: {@code CDEMO-CB00-TRNID-FIRST}, {@code -TRNID-LAST}, {@code -PAGE-NUM},
     * {@code -NEXT-PAGE-FLG} and {@code -TRN-SEL-FLG} are declared and referenced nowhere in the
     * procedure division. Only {@code CDEMO-CB00-TRN-SELECTED} is read, at {@code :116-119}. All six are
     * echoed anyway, because the source declares all six: a like-for-like migration preserves the
     * declared shape of the area, and dropping a member would change what the next screen receives.
     *
     * <p>The program writes none of the six and none of the sixteen inner members of the area itself
     * beyond the four {@code RETURN-TO-PREV-SCREEN} stamps, so this is an echo and not a projection.
     * {@code CDEMO-LAST-MAP} and {@code CDEMO-LAST-MAPSET} in particular - {@code PIC X(7)} each, and not
     * {@code X(8)} - are carried through untouched: {@code COBIL00C} contains no reference to either,
     * confirmed by exhaustive search, so writing the map names into them would be a change the source
     * does not make.
     *
     * @param response the payload being filled
     * @param request  the inbound screen, carrying the extension members to echo
     * @param commarea the communication area as the program leaves it
     */
    void echoCommarea(final BillPaymentResponse response,
                      final BillPaymentRequest request,
                      final NavigationContext commarea) {
        response.setNavigationContext(commarea);
        response.setTrnIdFirst(request.getTrnIdFirst());
        response.setTrnIdLast(request.getTrnIdLast());
        response.setPageNum(request.getPageNum());
        response.setNextPageFlg(request.getNextPageFlg());
        response.setTrnSelFlg(request.getTrnSelFlg());
        response.setTrnSelected(request.getTrnSelected());
    }

    // =================================================================================================
    // EIBAID, and the three COBOL semantics this class needs expressed once each.
    // =================================================================================================

    /**
     * The raw {@code EIBAID} byte that the {@code EVALUATE} at {@code app/cbl/COBIL00C.cbl:125} compares
     * against, resolved from the five-character token the payload carries.
     *
     * <p>Three sources, in precedence order:
     *
     * <ol>
     *   <li>a token this module knows, resolved through {@link #EIBAID_BY_TOKEN}. The value is brought to
     *       its declared {@value PfKeyResolver#AID_TOKEN_LENGTH}-character width by the codec's
     *       {@code PIC X} rule first, so both {@code "PA1"} and {@code "PA1  "} resolve - which is the
     *       same {@code MOVE} the field would have performed on a terminal;</li>
     *   <li>a token naming no key at all, which becomes {@link CicsAid#DFHNULL}. That byte matches none
     *       of the program's three named values, so it reaches {@code WHEN OTHER} at {@code :138} and is
     *       answered with the standard invalid-key message - the same answer the program gives any key it
     *       does not handle. This is where {@link PfKeyResolver}'s explicit no-match outcome lands: the
     *       {@code EVALUATE} in {@code app/cpy/CSSTRPFY.cpy} has no {@code WHEN OTHER} arm and no
     *       {@code DFHPA3} arm, so "nothing matched" is a real state rather than a defaulted one;</li>
     *   <li>an absent or empty token, which becomes {@link CicsAid#DFHENTER}. A CICS terminal always
     *       presents some attention identifier, so absence is not a state the source can be in, and
     *       {@code ENTER} is the only default that cannot reach a branch the operator could not have
     *       reached. It is also safe on this screen in particular: {@code ENTER} with no confirmation
     *       character displays the balance and asks for confirmation at {@code :237-239}; the payment
     *       itself is gated on {@code CONFIRMI} holding {@code 'Y'} or {@code 'y'} at {@code :173-177},
     *       and that gate is untouched.</li>
     * </ol>
     *
     * <p>The dispatch that follows uses {@link PfKeyResolver#isEnter(byte)},
     * {@link PfKeyResolver#isPf3(byte)} and {@link PfKeyResolver#isPf4(byte)}, which are byte equalities
     * exactly as the source's {@code EVALUATE EIBAID} compares them. The folding form -
     * {@link PfKeyResolver#resolve(byte)}, which maps {@code PF13} through {@code PF24} back onto
     * {@code PFK01} through {@code PFK12} - is deliberately <strong>not</strong> used for the dispatch:
     * {@code COBIL00C} does not copy {@code CSSTRPFY} and compares the raw byte, so folding would let one
     * key behave like another in a program that has no such behaviour.
     *
     * <p>No query parameter carrying a raw byte is accepted. The attention identifier is conversation
     * state and travels in the payload with the communication area and the screen values, which is the
     * whole of what keeps this endpoint stateless; a second carrier would be a second source of truth
     * able to contradict the first.
     *
     * @param aid the token from the payload, possibly {@code null}
     * @return the {@code EIBAID} byte to evaluate
     */
    byte eibAidOf(final String aid) {
        if (aid == null || aid.isEmpty()) {
            return CicsAid.DFHENTER;
        }
        return EIBAID_BY_TOKEN.getOrDefault(codec.movePicX(aid, PfKeyResolver.AID_TOKEN_LENGTH),
                CicsAid.DFHNULL);
    }

    /**
     * The COBOL test {@code IF <field> = SPACES OR LOW-VALUES}, which this program performs three times:
     * on {@code CDEMO-CB00-TRN-SELECTED} at {@code app/cbl/COBIL00C.cbl:116-117} (negated, as
     * {@code NOT = SPACES AND LOW-VALUES}), on {@code CDEMO-FROM-PROGRAM} at {@code :129} and on
     * {@code CDEMO-TO-PROGRAM} at {@code :275}.
     *
     * <p>Two figurative constants, tested separately, because COBOL treats them as different values: a
     * field of blanks and a field of {@code X'00'} bytes are not equal to one another, and a program that
     * tested only one of them would behave differently from this one. A field equals a figurative
     * constant when <strong>every</strong> position matches it, so a partially blank field satisfies
     * neither test - which is exactly why a selected transaction identifier padded into its
     * sixteen-character carrier still triggers the immediate lookup at {@code :120}.
     *
     * <p>{@code null} answers {@code true}. An absent payload member is a field that was never given a
     * value, and an unfilled {@code PIC X} item holds {@code LOW-VALUES} - which is one of the two
     * constants being tested. The two negated call sites therefore treat an absent member as "no target
     * was named", which is the arm the source takes for a blank field.
     *
     * <p>Note that {@code NOT = SPACES AND LOW-VALUES} at {@code :116-117} is an abbreviated combined
     * relation and expands to "not equal to blanks <em>and</em> not equal to {@code LOW-VALUES}", which is
     * the negation of this method rather than a different test. That equivalence is the reason one method
     * serves all three sites.
     *
     * @param value the field's characters, or {@code null} where the payload carried none
     * @return {@code true} when every position is a space, or every position is {@code LOW-VALUES}, or
     *         the value is absent
     */
    static boolean isSpacesOrLowValues(final String value) {
        if (value == null) {
            return true;
        }
        boolean allSpaces = true;
        boolean allLowValues = true;
        for (int position = 0; position < value.length(); position++) {
            final char character = value.charAt(position);
            if (character != SPACE) {
                allSpaces = false;
            }
            if (character != LOW_VALUE) {
                allLowValues = false;
            }
        }
        // An empty field satisfies both loops trivially, and an empty screen field is a blank one, so the
        // disjunction is the right answer for it too.
        return allSpaces || allLowValues;
    }

    /**
     * Brings a payload member to a symbolic-map field's declared width, the way {@code RECEIVE MAP} and
     * a COBOL {@code MOVE} between them do.
     *
     * <p>A COBOL screen field is always exactly its declared width; a JSON string is not, so this is the
     * one place the two are reconciled:
     *
     * <ul>
     *   <li>an absent member becomes the field filled with {@code LOW-VALUES}, which is what CICS leaves
     *       in a symbolic-map item it received nothing for, and what {@code MOVE LOW-VALUES TO COBIL0AO}
     *       at {@code app/cbl/COBIL00C.cbl:114} produces. It stays distinguishable from blanks, because
     *       {@code :159}, {@code :182-183} and {@code :199} test the two separately;</li>
     *   <li>anything else goes through the codec's alphanumeric move rule - left-aligned, padded with
     *       blanks on the right, truncated on the right - because that is how the value reached the field
     *       in the first place.</li>
     * </ul>
     *
     * @param value the bound value, possibly {@code null} and of any length
     * @param width the field's declared width
     * @return exactly {@code width} characters
     */
    private String materialise(final String value, final int width) {
        if (value == null) {
            return lowValues(width);
        }
        return codec.movePicX(value, width);
    }

    /**
     * The {@code SPACES} figurative constant at a field's declared width.
     *
     * <p>COBOL's unconditional blank fill, not the alphanumeric {@code MOVE} rule: the {@code MOVE} rule
     * pads a shorter sending value and truncates a longer one, and it lives in the codec alone.
     *
     * @param width the field's declared width; never negative
     * @return exactly {@code width} blanks
     */
    static String spaces(final int width) {
        return String.valueOf(SPACE).repeat(width);
    }

    /**
     * The {@code LOW-VALUES} figurative constant at a field's declared width - the state
     * {@code MOVE LOW-VALUES TO COBIL0AO} at {@code app/cbl/COBIL00C.cbl:114} leaves a field in, and the
     * state CICS leaves an item it received nothing for.
     *
     * @param width the field's declared width; never negative
     * @return exactly {@code width} {@code X'00'} characters
     */
    static String lowValues(final int width) {
        return String.valueOf(LOW_VALUE).repeat(width);
    }

    // =================================================================================================
    // One invocation, as it stood at EXEC CICS RETURN or EXEC CICS XCTL.
    // =================================================================================================

    /**
     * The outcome of one invocation of {@code COBIL00C}: the payload the client receives, and the working
     * storage the program left behind.
     *
     * <p>Both, because the payload alone is not a complete record of what happened. Two things the screen
     * cannot show are observable and are asserted by the parity cases: how many times the screen was sent
     * - which is two on the selected-transaction path of {@code app/cbl/COBIL00C.cbl:116-122} and one
     * everywhere else that paints - and the working-storage flags {@code WS-ERR-FLG},
     * {@code WS-USR-MODIFIED} and {@code WS-CONF-PAY-FLG}. {@link PaymentState} carries all of it,
     * including the text of every {@code DISPLAY} the program reached and a snapshot of every screen at
     * the moment it was transmitted.
     *
     * <p>{@link #payBill} projects {@link #response()} and nothing else; the state is the seam a test or a
     * parity case drives when it wants the translation rather than the wire format.
     *
     * @param response the payload the client receives; never {@code null}
     * @param state    the working storage as the program left it; never {@code null}
     */
    record Invocation(BillPaymentResponse response, PaymentState state) {

        /**
         * Checks both components, so a caller cannot be handed a half-built outcome.
         *
         * @throws NullPointerException if either component is {@code null}
         */
        Invocation {
            Objects.requireNonNull(response, "A response is required: every path through COBIL00C ends "
                    + "in either EXEC CICS RETURN or EXEC CICS XCTL, and both hand a payload back");
            Objects.requireNonNull(state, "A PaymentState is required: it holds the WORKING-STORAGE of "
                    + "app/cbl/COBIL00C.cbl:36-85 for this invocation, including the send count and the "
                    + "three flags the screen cannot show");
        }
    }
}
