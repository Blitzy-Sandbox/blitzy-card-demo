package com.vsergeychik.carddemo.common;

import java.util.Objects;
import java.util.Optional;

/**
 * Translation of {@code YYYY-STORE-PFKEY}, the shared PF-key mapping paragraph held in
 * {@code app/cpy/CSSTRPFY.cpy}, which maps a raw CICS {@code EIBAID} byte onto the five-character
 * AID token this application carries in its own work area.
 *
 * <h2>The source, and what it is</h2>
 *
 * <p>{@code app/cpy/CSSTRPFY.cpy} is unusual among the twenty-eight copybooks in this repository:
 * it is not a data structure at all but a <em>Procedure Division fragment</em>. Lines 17 to 82
 * declare the paragraph {@code YYYY-STORE-PFKEY.}, a single {@code EVALUATE TRUE} chain running
 * from line 21 to line 78, and then a {@code YYYY-STORE-PFKEY-EXIT.} paragraph whose whole body is
 * {@code EXIT}. Its own comment banner states its purpose exactly: "Map AID to PFKey in COMMON
 * Area". Every branch performs a {@code SET CCARD-AID-xxx TO TRUE} against the
 * {@code CCARD-AID PIC X(5)} field declared at {@code app/cpy/CVCRD01Y.cpy} line 3, and nothing
 * else. There is no I/O, no arithmetic and no other side effect, which is why the translation below
 * is a pure function rather than a mutator.
 *
 * <h2>What this class deliberately does NOT do (practices B4 and B5)</h2>
 *
 * <p>Two features of the source paragraph read like defects and are faithfully reproduced rather
 * than repaired. Practice <strong>B4</strong> requires that such conflicts be documented where a
 * reader will find them, never quietly corrected; practice <strong>B5</strong> forbids tidying
 * behaviour merely because it looks untidy. This is a like-for-like migration, so where the COBOL
 * does something odd, this class does the same odd thing.
 *
 * <ol>
 *   <li><strong>There is no {@code WHEN OTHER}.</strong> The {@code EVALUATE} ends at the
 *       {@code DFHPF24} branch on line 76 and falls straight through to {@code END-EVALUATE} on
 *       line 78. Critically, <strong>{@code CCARD-AID} is not cleared before the
 *       {@code EVALUATE}</strong>. So when {@code EIBAID} matches nothing, no {@code SET} executes
 *       and {@code CCARD-AID} retains <em>whatever the previous key left there</em>. A Java
 *       author's instinct is to add a {@code default} arm returning something sensible - treat the
 *       unknown key as {@code ENTER}, say, or clear the field, or throw. Every one of those would
 *       erase real behaviour. {@link #resolve(byte)} therefore returns an <em>empty</em>
 *       {@link Optional} for an unrecognised byte: it does not substitute a default, does not
 *       mutate anything, and does not throw. {@link #storePfKey(byte, Optional)} expresses the
 *       consequence directly by returning the caller's existing token untouched. This is what
 *       keeps the five consuming programs' own {@code IF CCARD-AID-...} chains behaving as they do
 *       today, because those chains see the stale value exactly as they would on the mainframe.</li>
 *   <li><strong>There is no {@code DFHPA3} branch.</strong> {@link CicsAid} defines
 *       {@code DFHPA3} because the migration plan mandates the whole {@code DFHPA1}-{@code DFHPA3}
 *       range, but {@code CSSTRPFY} has no {@code WHEN} for it and a search of the entire checkout
 *       finds zero references to it in any COBOL source. {@code app/cpy/CVCRD01Y.cpy} has no
 *       {@code CCARD-AID-PA3} condition either, so there would be nothing to set even if a branch
 *       existed. {@code DFHPA3} consequently resolves to <strong>no match</strong>, and neither a
 *       PA3 branch nor a PA3 token appears anywhere in this class.</li>
 * </ol>
 *
 * <h2>Two count discrepancies recorded rather than resolved (practice B4)</h2>
 *
 * <ol>
 *   <li><strong>The paragraph has 28 {@code WHEN} clauses, not 26.</strong> The migration brief's
 *       summary sentence gives the total as 26 while its own itemised table numbers the branch rows
 *       1-4, 5-16 and 17-<em>28</em>; four plus twelve plus twelve is twenty-eight, and
 *       {@code grep -c "WHEN EIBAID" app/cpy/CSSTRPFY.cpy} returns <strong>28</strong>. The
 *       brief's total is an arithmetic slip, and the copybook is authoritative. All twenty-eight
 *       branches are reproduced below. Trimming two of them to make a stated count agree would
 *       have silently broken two function keys - precisely the class of defect this migration
 *       exists to avoid.</li>
 *   <li><strong>{@code CCARD-AID} carries 16 condition names, not 15.</strong> The migration plan
 *       describes it as having fifteen; reading {@code app/cpy/CVCRD01Y.cpy} lines 4-19 shows
 *       sixteen - {@code ENTER}, {@code CLEAR}, {@code PA1}, {@code PA2} and {@code PFK01}
 *       through {@code PFK12}. All sixteen appear in {@link AidKey}. No token was added or dropped
 *       to make a count agree. (The same observation is recorded independently in
 *       {@link CicsAid}.)</li>
 * </ol>
 *
 * <h2>The PF13-PF24 fold is data, not logic</h2>
 *
 * <p>The paragraph maps twenty-four distinct function keys onto only twelve tokens:
 * {@code DFHPF13} sets {@code CCARD-AID-PFK01}, {@code DFHPF14} sets {@code CCARD-AID-PFK02}, and
 * so on through {@code DFHPF24} setting {@code CCARD-AID-PFK12}. On a 3270 terminal PF13 through
 * PF24 are the shifted upper row, so the application treats them as aliases of PF1 through PF12.
 *
 * <p>It is tempting to collapse that into a single modulo-twelve expression over the key number,
 * and it would look cleaner. It is deliberately <strong>not</strong> done - and to keep that
 * decision mechanically auditable, no such expression appears anywhere in this file, not even in
 * these comments, so a reviewer can grep for one and find nothing. The copybook enumerates the fold
 * branch by branch; the migration's control-flow gate is stated in terms of preserved {@code WHEN}
 * order; and the coverage gate counts <em>branches</em>, so an arithmetic shortcut would change
 * both the structure being audited and the structure being measured, while also destroying the
 * one-to-one correspondence that lets a reviewer diff {@link #resolve(byte)} against the copybook
 * top to bottom. Every branch below carries the copybook line number it came from for exactly that
 * purpose.
 *
 * <h2>Order matters: {@code EVALUATE} is first-match-wins</h2>
 *
 * <p>A COBOL {@code EVALUATE} selects the first {@code WHEN} whose condition holds, so branch order
 * is semantic, not cosmetic. {@link #resolve(byte)} preserves the copybook's order exactly, with
 * the no-match arm last. Order-sensitivity would only be observable if two {@link CicsAid}
 * constants shared a byte value; they do not, and that precondition is asserted by this class's
 * unit test because this is where a collision's consequence would land.
 *
 * <h2>Serving all seventeen online programs, including the twelve that never used this paragraph</h2>
 *
 * <p>{@code CSSTRPFY} is copied by exactly <strong>five</strong> of the seventeen CICS programs -
 * note the quoted {@code COPY 'CSSTRPFY'} form, since {@code COPY} appears in both bare and quoted
 * spellings in this codebase and both resolve identically:
 *
 * <ul>
 *   <li>{@code app/cbl/COACTUPC.cbl} line 4199</li>
 *   <li>{@code app/cbl/COACTVWC.cbl} line 913</li>
 *   <li>{@code app/cbl/COCRDLIC.cbl} line 1416</li>
 *   <li>{@code app/cbl/COCRDSLC.cbl} line 855</li>
 *   <li>{@code app/cbl/COCRDUPC.cbl} line 1528</li>
 * </ul>
 *
 * <p>Those five consume the <em>token</em>, through chains such as
 * {@code IF CCARD-AID-ENTER OR CCARD-AID-PFK03 ...} and {@code EVALUATE ... WHEN CCARD-AID-PFK07},
 * which is why token identity must be exact down to the trailing spaces.
 *
 * <p>The other <strong>twelve</strong> programs - {@code COADM01C}, {@code COBIL00C},
 * {@code COMEN01C}, {@code CORPT00C}, {@code COSGN00C}, {@code COTRN00C}, {@code COTRN01C},
 * {@code COTRN02C}, {@code COUSR00C}, {@code COUSR01C}, {@code COUSR02C} and {@code COUSR03C} -
 * never copied the paragraph. They test {@code EIBAID} <em>inline</em> instead, in the shape found
 * at {@code app/cbl/COMEN01C.cbl} lines 93-102: {@code EVALUATE EIBAID / WHEN DFHENTER ... /
 * WHEN DFHPF3 ... / WHEN OTHER ...}. The migration plan consolidates all seventeen onto this one
 * resolver while "preserving the inline tests as identical boolean outcomes", so
 * {@link #isAid(byte, byte)} and its named delegates exist to give those twelve programs the plain
 * byte comparison they performed, with no token lookup interposed. That way the boolean they get is
 * provably the same one {@code EIBAID = DFHPF3} produced.
 *
 * <p>The named delegates cover exactly the mnemonics those twelve programs are verified to test -
 * {@code DFHENTER} (16 occurrences across {@code app/cbl}), {@code DFHPF3} (14), {@code DFHPF4}
 * (6), {@code DFHPF5} (4), {@code DFHPF7} (4), {@code DFHPF8} (4) and {@code DFHPF12} (2) - and no
 * others, because inventing predicates for keys nothing tests would be speculative surface. Any
 * other key is reachable through {@link #isAid(byte, byte)} with the relevant {@link CicsAid}
 * constant.
 *
 * <h2>Encoding: the input is a raw EBCDIC byte</h2>
 *
 * <p>{@link CicsAid}'s constants are single <strong>EBCDIC</strong> bytes, so the {@code EIBAID}
 * value compared against them must be in the same representation. Every method here accordingly
 * takes a {@code byte}, never a {@code char} and never an {@code int}. That choice is load-bearing
 * rather than stylistic: PF1's AID byte is {@code 0xF1}, which as a Java {@code byte} is the
 * negative value {@code -15}. Widening it to an {@code int} would yield {@code 241} and silently
 * fail to match, whereas {@code byte}-to-{@code byte} comparison is exact. Writing {@code '1'} as
 * a {@code char} literal would give ASCII {@code 0x31} and fail the same way.
 *
 * <p>This class performs <strong>no</strong> character conversion whatsoever: it never encodes a
 * string to bytes, never decodes bytes to a string, and never consults the platform default
 * charset, which is never correct for mainframe data. Callers that must decode a raw {@code EIBAID}
 * from mainframe-encoded data should name {@link CicsAid#AID_CODE_PAGE} explicitly (practice
 * <strong>B8</strong>).
 *
 * <h2>Scope and structure</h2>
 *
 * <p>This class holds the mapping and nothing else. It does <em>not</em> model
 * {@code app/cpy/CVCRD01Y.cpy}: that copybook's remaining fields - the next program, mapset and
 * map, the error and return messages, and the account, card and customer identifier pairs - belong
 * to the card screen-state DTO, which is where the resolved token travels in the response payload.
 * {@link AidKey} reproduces only the {@code CCARD-AID} condition names.
 *
 * <p>Its only dependency is the sibling {@link CicsAid}, in this same package; it imports nothing
 * from any other package of this application and nothing from any framework. Every member is
 * {@code static} and every method is a pure function of its arguments: there is no field, no
 * mutable static state and no instance to hold any (practices <strong>B9</strong> and
 * <strong>B8</strong>), which makes the class inherently thread safe and its results reproducible.
 *
 * <p>No project-specific rules were supplied for this migration; the enterprise practices
 * <strong>B1</strong> through <strong>B12</strong> referenced above govern in their place, and
 * their absence was not treated as licence to relax any of them.
 *
 * @see CicsAid
 */
public final class PfKeyResolver {

    /**
     * Width in characters of every AID token, from {@code 10 CCARD-AID PIC X(5)} at
     * {@code app/cpy/CVCRD01Y.cpy} line 3.
     *
     * <p>Exposed because the width is part of the contract, not an implementation detail: the token
     * occupies a fixed five-byte field in the work area, so the three-character mnemonics are
     * space-padded to five and {@link AidKey#token()} returns the padded form. A caller that
     * trimmed the value would produce a four-byte field and corrupt every offset after it.
     */
    public static final int AID_TOKEN_LENGTH = 5;

    /**
     * The sixteen AID tokens declared as {@code 88}-level condition names on
     * {@code CCARD-AID PIC X(5)} at {@code app/cpy/CVCRD01Y.cpy} lines 4-19, each carrying its
     * exact five-character value.
     *
     * <p>Constants appear in copybook order. The enum name matches the COBOL condition name with
     * the {@code CCARD-AID-} prefix removed, while {@link #token()} returns the literal the
     * copybook assigns - which is the value that must appear on the wire, and the value the five
     * consuming programs test against.
     *
     * <p><strong>{@code PA1} and {@code PA2} are space-padded.</strong> The copybook declares them
     * as {@code VALUE 'PA1  '} and {@code VALUE 'PA2  '} - three characters plus two trailing
     * spaces, filling the five-byte field. Those trailing spaces are part of the value, not
     * incidental formatting, and are preserved verbatim. This is the one place in the class where a
     * well-meaning trim would break parity invisibly, which is why the unit test asserts token
     * length rather than merely token prefix.
     *
     * <p>There are deliberately <strong>no</strong> other constants. In particular there is no
     * {@code PA3} - the copybook declares no such condition - and no {@code NONE} or
     * {@code UNRECOGNISED} member: adding one would require inventing a five-character token that
     * {@code CVCRD01Y} does not define, and would let an absent result be mistaken for a
     * seventeenth AID condition. The absence of a match is represented instead by an empty
     * {@link Optional}, which cannot be confused with a token.
     */
    public enum AidKey {

        /** {@code CCARD-AID-ENTER}, {@code VALUE 'ENTER'} - the ENTER key. */
        ENTER("ENTER"),

        /** {@code CCARD-AID-CLEAR}, {@code VALUE 'CLEAR'} - the CLEAR key. */
        CLEAR("CLEAR"),

        /** {@code CCARD-AID-PA1}, {@code VALUE 'PA1  '} - the PA1 key; note the two trailing spaces. */
        PA1("PA1  "),

        /** {@code CCARD-AID-PA2}, {@code VALUE 'PA2  '} - the PA2 key; note the two trailing spaces. */
        PA2("PA2  "),

        /** {@code CCARD-AID-PFK01}, {@code VALUE 'PFK01'} - set by both PF1 and PF13. */
        PFK01("PFK01"),

        /** {@code CCARD-AID-PFK02}, {@code VALUE 'PFK02'} - set by both PF2 and PF14. */
        PFK02("PFK02"),

        /** {@code CCARD-AID-PFK03}, {@code VALUE 'PFK03'} - set by both PF3 and PF15. */
        PFK03("PFK03"),

        /** {@code CCARD-AID-PFK04}, {@code VALUE 'PFK04'} - set by both PF4 and PF16. */
        PFK04("PFK04"),

        /** {@code CCARD-AID-PFK05}, {@code VALUE 'PFK05'} - set by both PF5 and PF17. */
        PFK05("PFK05"),

        /** {@code CCARD-AID-PFK06}, {@code VALUE 'PFK06'} - set by both PF6 and PF18. */
        PFK06("PFK06"),

        /** {@code CCARD-AID-PFK07}, {@code VALUE 'PFK07'} - set by both PF7 and PF19. */
        PFK07("PFK07"),

        /** {@code CCARD-AID-PFK08}, {@code VALUE 'PFK08'} - set by both PF8 and PF20. */
        PFK08("PFK08"),

        /** {@code CCARD-AID-PFK09}, {@code VALUE 'PFK09'} - set by both PF9 and PF21. */
        PFK09("PFK09"),

        /** {@code CCARD-AID-PFK10}, {@code VALUE 'PFK10'} - set by both PF10 and PF22. */
        PFK10("PFK10"),

        /** {@code CCARD-AID-PFK11}, {@code VALUE 'PFK11'} - set by both PF11 and PF23. */
        PFK11("PFK11"),

        /** {@code CCARD-AID-PFK12}, {@code VALUE 'PFK12'} - set by both PF12 and PF24. */
        PFK12("PFK12");

        /**
         * The copybook literal for this condition name, always exactly
         * {@link PfKeyResolver#AID_TOKEN_LENGTH} characters including any trailing padding.
         */
        private final String token;

        AidKey(String token) {
            this.token = token;
        }

        /**
         * Returns the five-character {@code CCARD-AID} literal for this key.
         *
         * <p>This is the value that travels in the response payload and that the five consuming
         * COBOL programs compare against, so it is returned exactly as
         * {@code app/cpy/CVCRD01Y.cpy} declares it - including the two trailing spaces on
         * {@link #PA1} and {@link #PA2}. Callers must not trim it: the field is
         * {@code PIC X(5)} and a shorter value would be the wrong width.
         *
         * @return the exact copybook literal, never {@code null}, always
         *         {@link PfKeyResolver#AID_TOKEN_LENGTH} characters long
         */
        public String token() {
            return token;
        }
    }

    /**
     * Maps a raw CICS {@code EIBAID} byte onto its AID token, reproducing the
     * {@code EVALUATE TRUE} chain of {@code app/cpy/CSSTRPFY.cpy} lines 21-78 branch for branch, in
     * source order.
     *
     * <p>Each arm below carries the copybook line numbers it was translated from, so the method can
     * be read side by side with {@code CSSTRPFY} and checked top to bottom. All twenty-eight
     * {@code WHEN} clauses are present, including the twelve that fold {@code DFHPF13} through
     * {@code DFHPF24} back onto {@code PFK01} through {@code PFK12}; the fold is written out rather
     * than computed, for the reasons given in the class documentation.
     *
     * <p><strong>An unrecognised byte returns an empty {@link Optional}, and that is the whole point
     * of this method's contract.</strong> The source {@code EVALUATE} has no {@code WHEN OTHER} and
     * does not clear {@code CCARD-AID} beforehand, so an unmatched AID leaves the previous key's
     * token standing on the mainframe. An empty result is the faithful translation of "nothing was
     * set": this method never substitutes a default such as {@link AidKey#ENTER}, never clears or
     * mutates any state, and never throws. The caller decides what an absent result means -
     * {@link #storePfKey(byte, Optional)} implements the specific decision the COBOL makes.
     * {@code DFHPA3}, {@code DFHNULL} and every other AID {@link CicsAid} defines but
     * {@code CSSTRPFY} does not test all fall through to the empty result.
     *
     * <p>Because {@link AidKey} constants are enum singletons, the fold is observable by identity as
     * well as by value: {@code resolve(CicsAid.DFHPF13).orElseThrow()} is the very same object as
     * {@code resolve(CicsAid.DFHPF1).orElseThrow()}.
     *
     * <p>The argument must be the raw EBCDIC AID byte, exactly as CICS places it in {@code EIBAID}.
     * See the class documentation for why the parameter is a {@code byte} and must not be widened to
     * an {@code int} or written as a {@code char}.
     *
     * @param eibAid the raw EBCDIC attention-identifier byte to map
     * @return the matching AID token, or {@link Optional#empty()} if the byte matches none of the
     *         twenty-eight tested AIDs; never {@code null}
     */
    public static Optional<AidKey> resolve(byte eibAid) {
        return switch (eibAid) {
            // ---- CSSTRPFY.cpy L22-L29: ENTER, CLEAR and the two tested PA keys ----------------
            case CicsAid.DFHENTER -> Optional.of(AidKey.ENTER); // L22-23 SET CCARD-AID-ENTER
            case CicsAid.DFHCLEAR -> Optional.of(AidKey.CLEAR); // L24-25 SET CCARD-AID-CLEAR
            case CicsAid.DFHPA1 -> Optional.of(AidKey.PA1);     // L26-27 SET CCARD-AID-PA1
            case CicsAid.DFHPA2 -> Optional.of(AidKey.PA2);     // L28-29 SET CCARD-AID-PA2

            // ---- CSSTRPFY.cpy L30-L53: PF1..PF12 map one-to-one onto PFK01..PFK12 -------------
            case CicsAid.DFHPF1 -> Optional.of(AidKey.PFK01);   // L30-31 SET CCARD-AID-PFK01
            case CicsAid.DFHPF2 -> Optional.of(AidKey.PFK02);   // L32-33 SET CCARD-AID-PFK02
            case CicsAid.DFHPF3 -> Optional.of(AidKey.PFK03);   // L34-35 SET CCARD-AID-PFK03
            case CicsAid.DFHPF4 -> Optional.of(AidKey.PFK04);   // L36-37 SET CCARD-AID-PFK04
            case CicsAid.DFHPF5 -> Optional.of(AidKey.PFK05);   // L38-39 SET CCARD-AID-PFK05
            case CicsAid.DFHPF6 -> Optional.of(AidKey.PFK06);   // L40-41 SET CCARD-AID-PFK06
            case CicsAid.DFHPF7 -> Optional.of(AidKey.PFK07);   // L42-43 SET CCARD-AID-PFK07
            case CicsAid.DFHPF8 -> Optional.of(AidKey.PFK08);   // L44-45 SET CCARD-AID-PFK08
            case CicsAid.DFHPF9 -> Optional.of(AidKey.PFK09);   // L46-47 SET CCARD-AID-PFK09
            case CicsAid.DFHPF10 -> Optional.of(AidKey.PFK10);  // L48-49 SET CCARD-AID-PFK10
            case CicsAid.DFHPF11 -> Optional.of(AidKey.PFK11);  // L50-51 SET CCARD-AID-PFK11
            case CicsAid.DFHPF12 -> Optional.of(AidKey.PFK12);  // L52-53 SET CCARD-AID-PFK12

            // ---- CSSTRPFY.cpy L54-L77: PF13..PF24 FOLD BACK onto PFK01..PFK12 ----------------
            // Enumerated exactly as the copybook enumerates them. Deliberately NOT expressed as
            // modular arithmetic: see "The PF13-PF24 fold is data, not logic" in the class Javadoc.
            case CicsAid.DFHPF13 -> Optional.of(AidKey.PFK01);  // L54-55 SET CCARD-AID-PFK01
            case CicsAid.DFHPF14 -> Optional.of(AidKey.PFK02);  // L56-57 SET CCARD-AID-PFK02
            case CicsAid.DFHPF15 -> Optional.of(AidKey.PFK03);  // L58-59 SET CCARD-AID-PFK03
            case CicsAid.DFHPF16 -> Optional.of(AidKey.PFK04);  // L60-61 SET CCARD-AID-PFK04
            case CicsAid.DFHPF17 -> Optional.of(AidKey.PFK05);  // L62-63 SET CCARD-AID-PFK05
            case CicsAid.DFHPF18 -> Optional.of(AidKey.PFK06);  // L64-65 SET CCARD-AID-PFK06
            case CicsAid.DFHPF19 -> Optional.of(AidKey.PFK07);  // L66-67 SET CCARD-AID-PFK07
            case CicsAid.DFHPF20 -> Optional.of(AidKey.PFK08);  // L68-69 SET CCARD-AID-PFK08
            case CicsAid.DFHPF21 -> Optional.of(AidKey.PFK09);  // L70-71 SET CCARD-AID-PFK09
            case CicsAid.DFHPF22 -> Optional.of(AidKey.PFK10);  // L72-73 SET CCARD-AID-PFK10
            case CicsAid.DFHPF23 -> Optional.of(AidKey.PFK11);  // L74-75 SET CCARD-AID-PFK11
            case CicsAid.DFHPF24 -> Optional.of(AidKey.PFK12);  // L76-77 SET CCARD-AID-PFK12

            // ---- CSSTRPFY.cpy L78: END-EVALUATE, reached with NO WHEN OTHER ------------------
            // The source has no default branch and does not pre-clear CCARD-AID, so an unmatched
            // AID sets nothing at all. Returning the absent result is that behaviour, faithfully;
            // returning a substitute token here would erase it. This arm therefore yields no token,
            // throws nothing and mutates nothing.
            default -> Optional.empty();
        };
    }

    /**
     * Applies the whole {@code YYYY-STORE-PFKEY} paragraph, including its most easily lost
     * property: on no match the existing token is left exactly as it was.
     *
     * <p>{@link #resolve(byte)} answers "which token does this byte map to". This method answers the
     * question the paragraph actually answers - "what does {@code CCARD-AID} hold once the paragraph
     * has run" - and the two differ precisely when the byte matches nothing. Because the source
     * {@code EVALUATE} neither has a {@code WHEN OTHER} nor clears {@code CCARD-AID} first, an
     * unmatched AID leaves the field holding whatever the previous interaction put there. This
     * method reproduces that by returning {@code currentAid} unchanged, which is why the consuming
     * programs' {@code IF CCARD-AID-...} chains continue to behave as they do on the mainframe.
     *
     * <p>The current value is passed in and a new value is returned rather than any state being
     * mutated, keeping the translation a pure function (practice <strong>B9</strong>). Passing
     * {@link Optional#empty()} models a work area in which no key has yet been recorded; the result
     * is then empty too when the byte matches nothing, since there is no earlier token to retain.
     *
     * @param eibAid     the raw EBCDIC attention-identifier byte to map
     * @param currentAid the token {@code CCARD-AID} holds on entry, or {@link Optional#empty()} if
     *                   no key has been recorded yet; returned unchanged when {@code eibAid} matches
     *                   no tested AID
     * @return the newly matched token, or {@code currentAid} unchanged if there was no match; never
     *         {@code null}
     * @throws NullPointerException if {@code currentAid} is {@code null}. An absent value must be
     *                              expressed as {@link Optional#empty()}, never as {@code null}.
     */
    public static Optional<AidKey> storePfKey(byte eibAid, Optional<AidKey> currentAid) {
        Objects.requireNonNull(currentAid, "currentAid must not be null; use Optional.empty()");
        Optional<AidKey> resolved = resolve(eibAid);
        // Written as an explicit two-way choice rather than Optional.or(...) so that the COBOL it
        // stands for is legible: SET the new token, or leave CCARD-AID untouched. Never cleared.
        return resolved.isPresent() ? resolved : currentAid;
    }

    /**
     * Tests a raw {@code EIBAID} byte against a single {@link CicsAid} constant - the direct
     * translation of a COBOL {@code EIBAID = DFHxxx} comparison.
     *
     * <p>This exists for the twelve online programs that never copied {@code CSSTRPFY} and instead
     * tested {@code EIBAID} inline, in the shape {@code EVALUATE EIBAID / WHEN DFHENTER / WHEN
     * DFHPF3 / WHEN OTHER}. The migration plan consolidates all seventeen programs onto this one
     * resolver while requiring the inline tests keep "identical boolean outcomes", so those programs
     * must be able to ask the equality question directly rather than route through a token. The
     * implementation is a single {@code ==} on two bytes, which is exactly the comparison the COBOL
     * performs - there is no normalisation, no widening and no conversion in which the two could
     * diverge.
     *
     * <p>Usable with any AID, including ones {@code CSSTRPFY} has no branch for: this is a plain
     * equality test and carries none of {@link #resolve(byte)}'s mapping semantics.
     *
     * @param eibAid      the raw EBCDIC attention-identifier byte received from the terminal
     * @param aidConstant the AID to compare against, normally a {@link CicsAid} constant
     * @return {@code true} if the two bytes are equal
     */
    public static boolean isAid(byte eibAid, byte aidConstant) {
        return eibAid == aidConstant;
    }

    /**
     * Tests for the ENTER key, {@link CicsAid#DFHENTER} - the most heavily tested AID in the
     * application, with sixteen inline occurrences across {@code app/cbl}.
     *
     * @param eibAid the raw EBCDIC attention-identifier byte
     * @return {@code true} if ENTER was pressed
     */
    public static boolean isEnter(byte eibAid) {
        return isAid(eibAid, CicsAid.DFHENTER);
    }

    /**
     * Tests for PF3, {@link CicsAid#DFHPF3} - the conventional "back" key in this application, with
     * fourteen inline occurrences across {@code app/cbl}.
     *
     * @param eibAid the raw EBCDIC attention-identifier byte
     * @return {@code true} if PF3 was pressed
     */
    public static boolean isPf3(byte eibAid) {
        return isAid(eibAid, CicsAid.DFHPF3);
    }

    /**
     * Tests for PF4, {@link CicsAid#DFHPF4} - six inline occurrences across {@code app/cbl}.
     *
     * @param eibAid the raw EBCDIC attention-identifier byte
     * @return {@code true} if PF4 was pressed
     */
    public static boolean isPf4(byte eibAid) {
        return isAid(eibAid, CicsAid.DFHPF4);
    }

    /**
     * Tests for PF5, {@link CicsAid#DFHPF5} - four inline occurrences across {@code app/cbl}.
     *
     * @param eibAid the raw EBCDIC attention-identifier byte
     * @return {@code true} if PF5 was pressed
     */
    public static boolean isPf5(byte eibAid) {
        return isAid(eibAid, CicsAid.DFHPF5);
    }

    /**
     * Tests for PF7, {@link CicsAid#DFHPF7} - the conventional "page backward" key, with four
     * inline occurrences across {@code app/cbl}.
     *
     * @param eibAid the raw EBCDIC attention-identifier byte
     * @return {@code true} if PF7 was pressed
     */
    public static boolean isPf7(byte eibAid) {
        return isAid(eibAid, CicsAid.DFHPF7);
    }

    /**
     * Tests for PF8, {@link CicsAid#DFHPF8} - the conventional "page forward" key, with four inline
     * occurrences across {@code app/cbl}.
     *
     * @param eibAid the raw EBCDIC attention-identifier byte
     * @return {@code true} if PF8 was pressed
     */
    public static boolean isPf8(byte eibAid) {
        return isAid(eibAid, CicsAid.DFHPF8);
    }

    /**
     * Tests for PF12, {@link CicsAid#DFHPF12} - two inline occurrences across {@code app/cbl}.
     *
     * @param eibAid the raw EBCDIC attention-identifier byte
     * @return {@code true} if PF12 was pressed
     */
    public static boolean isPf12(byte eibAid) {
        return isAid(eibAid, CicsAid.DFHPF12);
    }

    /**
     * Not instantiable: this class translates a COBOL procedure fragment into pure static functions
     * and has no state and no instance behaviour.
     *
     * @throws AssertionError always, including when invoked reflectively
     */
    private PfKeyResolver() {
        throw new AssertionError("PfKeyResolver is a stateless resolver and must not be instantiated");
    }
}
