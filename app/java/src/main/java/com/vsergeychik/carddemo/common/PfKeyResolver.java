package com.vsergeychik.carddemo.common;

import java.util.Objects;
import java.util.Optional;

/**
 * Translation of {@code YYYY-STORE-PFKEY}, the shared PF-key mapping paragraph held in
 * {@code app/cpy/CSSTRPFY.cpy}, which maps a raw CICS {@code EIBAID} byte onto the five-character AID token
 * this application carries in its own work area.
 *
 * <p>Every branch below carries the copybook line number it came from for exactly that purpose.
 */
public final class PfKeyResolver {
    /**
     * Width in characters of every AID token, from {@code 10 CCARD-AID PIC X(5)} at
     * {@code app/cpy/CVCRD01Y.cpy} line 3.
     */
    public static final int AID_TOKEN_LENGTH = 5;

    /**
     * The sixteen AID tokens declared as {@code 88}-level condition names on {@code CCARD-AID PIC X(5)} at
     * {@code app/cpy/CVCRD01Y.cpy} lines 4-19, each carrying its exact five-character value.
     *
     * <p>The enum name matches the COBOL condition name with the {@code CCARD-AID-} prefix removed, while
     * {@link #token()} returns the literal the copybook assigns - which is the value that must appear on
     * the wire, and the value the five consuming programs test against.
     */
    public enum AidKey {
        /**
         * {@code CCARD-AID-ENTER}, {@code VALUE 'ENTER'} - the ENTER key.
         */
        ENTER("ENTER", CicsAid.DFHENTER),

        /**
         * {@code CCARD-AID-CLEAR}, {@code VALUE 'CLEAR'} - the CLEAR key.
         */
        CLEAR("CLEAR", CicsAid.DFHCLEAR),

        /**
         * {@code CCARD-AID-PA1}, {@code VALUE 'PA1 '} - the PA1 key; note the two trailing spaces.
         */
        PA1("PA1  ", CicsAid.DFHPA1),

        /**
         * {@code CCARD-AID-PA2}, {@code VALUE 'PA2 '} - the PA2 key; note the two trailing spaces.
         */
        PA2("PA2  ", CicsAid.DFHPA2),

        /**
         * {@code CCARD-AID-PFK01}, {@code VALUE 'PFK01'} - set by both PF1 and PF13.
         */
        PFK01("PFK01", CicsAid.DFHPF1),

        /**
         * {@code CCARD-AID-PFK02}, {@code VALUE 'PFK02'} - set by both PF2 and PF14.
         */
        PFK02("PFK02", CicsAid.DFHPF2),

        /**
         * {@code CCARD-AID-PFK03}, {@code VALUE 'PFK03'} - set by both PF3 and PF15.
         */
        PFK03("PFK03", CicsAid.DFHPF3),

        /**
         * {@code CCARD-AID-PFK04}, {@code VALUE 'PFK04'} - set by both PF4 and PF16.
         */
        PFK04("PFK04", CicsAid.DFHPF4),

        /**
         * {@code CCARD-AID-PFK05}, {@code VALUE 'PFK05'} - set by both PF5 and PF17.
         */
        PFK05("PFK05", CicsAid.DFHPF5),

        /**
         * {@code CCARD-AID-PFK06}, {@code VALUE 'PFK06'} - set by both PF6 and PF18.
         */
        PFK06("PFK06", CicsAid.DFHPF6),

        /**
         * {@code CCARD-AID-PFK07}, {@code VALUE 'PFK07'} - set by both PF7 and PF19.
         */
        PFK07("PFK07", CicsAid.DFHPF7),

        /**
         * {@code CCARD-AID-PFK08}, {@code VALUE 'PFK08'} - set by both PF8 and PF20.
         */
        PFK08("PFK08", CicsAid.DFHPF8),

        /**
         * {@code CCARD-AID-PFK09}, {@code VALUE 'PFK09'} - set by both PF9 and PF21.
         */
        PFK09("PFK09", CicsAid.DFHPF9),

        /**
         * {@code CCARD-AID-PFK10}, {@code VALUE 'PFK10'} - set by both PF10 and PF22.
         */
        PFK10("PFK10", CicsAid.DFHPF10),

        /**
         * {@code CCARD-AID-PFK11}, {@code VALUE 'PFK11'} - set by both PF11 and PF23.
         */
        PFK11("PFK11", CicsAid.DFHPF11),

        /**
         * {@code CCARD-AID-PFK12}, {@code VALUE 'PFK12'} - set by both PF12 and PF24.
         */
        PFK12("PFK12", CicsAid.DFHPF12);

        private final String token;

        private final byte primaryAid;

        AidKey(String token, byte primaryAid) {
            this.token = token;
            this.primaryAid = primaryAid;
        }

        /**
         * Returns the five-character {@code CCARD-AID} literal for this key.
         *
         * <p>Callers must not trim it: the field is {@code PIC X(5)} and a shorter value would be the wrong
         * width.
         *
         * @return the exact copybook literal, never {@code null}, always
         *     {@link PfKeyResolver#AID_TOKEN_LENGTH} characters long
         */
        public String token() {
            return token;
        }

        /**
         * The {@code EIBAID} byte that names this key rather than folding onto it.
         *
         * @return the raw EBCDIC attention-identifier byte of this key's own {@code WHEN} clause
         */
        public byte primaryAid() {
            return primaryAid;
        }
    }

    /**
     * Maps a raw CICS {@code EIBAID} byte onto its AID token, reproducing the {@code EVALUATE TRUE} chain
     * of {@code app/cpy/CSSTRPFY.cpy} lines 21-78 branch for branch, in source order.
     *
     * <p>The argument must be the raw EBCDIC AID byte, exactly as CICS places it in {@code EIBAID}.
     *
     * @param eibAid the raw EBCDIC attention-identifier byte to map
     * @return the matching AID token, or {@link Optional#empty()} if the byte matches none of the
     *     twenty-eight tested AIDs; never {@code null}
     */
    public static Optional<AidKey> resolve(byte eibAid) {
        return switch (eibAid) {
            case CicsAid.DFHENTER -> Optional.of(AidKey.ENTER);
            case CicsAid.DFHCLEAR -> Optional.of(AidKey.CLEAR);
            case CicsAid.DFHPA1 -> Optional.of(AidKey.PA1);
            case CicsAid.DFHPA2 -> Optional.of(AidKey.PA2);

            case CicsAid.DFHPF1 -> Optional.of(AidKey.PFK01);
            case CicsAid.DFHPF2 -> Optional.of(AidKey.PFK02);
            case CicsAid.DFHPF3 -> Optional.of(AidKey.PFK03);
            case CicsAid.DFHPF4 -> Optional.of(AidKey.PFK04);
            case CicsAid.DFHPF5 -> Optional.of(AidKey.PFK05);
            case CicsAid.DFHPF6 -> Optional.of(AidKey.PFK06);
            case CicsAid.DFHPF7 -> Optional.of(AidKey.PFK07);
            case CicsAid.DFHPF8 -> Optional.of(AidKey.PFK08);
            case CicsAid.DFHPF9 -> Optional.of(AidKey.PFK09);
            case CicsAid.DFHPF10 -> Optional.of(AidKey.PFK10);
            case CicsAid.DFHPF11 -> Optional.of(AidKey.PFK11);
            case CicsAid.DFHPF12 -> Optional.of(AidKey.PFK12);

            // ---- CSSTRPFY.cpy L54-L77: PF13..PF24 FOLD BACK onto PFK01..PFK12 Enumerated exactly as the
            // copybook enumerates them. Deliberately NOT expressed as modular arithmetic: see "The
            // PF13-PF24 fold is data, not logic" in the class Javadoc.
            case CicsAid.DFHPF13 -> Optional.of(AidKey.PFK01);
            case CicsAid.DFHPF14 -> Optional.of(AidKey.PFK02);
            case CicsAid.DFHPF15 -> Optional.of(AidKey.PFK03);
            case CicsAid.DFHPF16 -> Optional.of(AidKey.PFK04);
            case CicsAid.DFHPF17 -> Optional.of(AidKey.PFK05);
            case CicsAid.DFHPF18 -> Optional.of(AidKey.PFK06);
            case CicsAid.DFHPF19 -> Optional.of(AidKey.PFK07);
            case CicsAid.DFHPF20 -> Optional.of(AidKey.PFK08);
            case CicsAid.DFHPF21 -> Optional.of(AidKey.PFK09);
            case CicsAid.DFHPF22 -> Optional.of(AidKey.PFK10);
            case CicsAid.DFHPF23 -> Optional.of(AidKey.PFK11);
            case CicsAid.DFHPF24 -> Optional.of(AidKey.PFK12);

            default -> Optional.empty();
        };
    }

    /**
     * Maps a raw {@code EIBAID} byte onto the token whose own {@code WHEN} clause it is, refusing the
     * {@code CSSTRPFY} fold - the resolver the twelve programs that never copied {@code CSSTRPFY} need.
     *
     * @param eibAid the raw EBCDIC attention-identifier byte to map
     * @return the token this byte names in its own right, or {@link Optional#empty()} if the byte is a
     *     folded PF13-PF24 partner or matches no tested AID at all; never {@code null}
     */
    public static Optional<AidKey> resolveWithoutFolding(byte eibAid) {
        return resolve(eibAid).filter(key -> key.primaryAid() == eibAid);
    }

    /**
     * Applies the whole {@code YYYY-STORE-PFKEY} paragraph, including its most easily lost property: on no
     * match the existing token is left exactly as it was.
     *
     * @param eibAid the raw EBCDIC attention-identifier byte to map
     * @param currentAid the token {@code CCARD-AID} holds on entry, or {@link Optional#empty()} if no key
     *     has been recorded yet; returned unchanged when {@code eibAid} matches no tested AID
     * @return the newly matched token, or {@code currentAid} unchanged if there was no match; never
     *     {@code null}
     * @throws NullPointerException if {@code currentAid} is {@code null}
     */
    public static Optional<AidKey> storePfKey(byte eibAid, Optional<AidKey> currentAid) {
        Objects.requireNonNull(currentAid, "currentAid must not be null; use Optional.empty()");
        Optional<AidKey> resolved = resolve(eibAid);
        return resolved.isPresent() ? resolved : currentAid;
    }

    /**
     * Tests a raw {@code EIBAID} byte against a single {@link CicsAid} constant - the direct translation of
     * a COBOL {@code EIBAID = DFHxxx} comparison.
     *
     * <p>The implementation is a single {@code ==} on two bytes, which is exactly the comparison the COBOL
     * performs - there is no normalisation, no widening and no conversion in which the two could diverge.
     *
     * @param eibAid the raw EBCDIC attention-identifier byte received from the terminal
     * @param aidConstant the AID to compare against, normally a {@link CicsAid} constant
     * @return {@code true} if the two bytes are equal
     */
    public static boolean isAid(byte eibAid, byte aidConstant) {
        return eibAid == aidConstant;
    }

    /**
     * The JSON carrier form of an {@code EIBAID} byte: the one character whose code point is that byte.
     *
     * @param eibAid the raw EBCDIC attention-identifier byte
     * @return a string of exactly one character; never {@code null}
     */
    public static String aidImage(byte eibAid) {
        return String.valueOf((char) (eibAid & 0xFF));
    }

    /**
     * Tests for the ENTER key, {@link CicsAid#DFHENTER} - the most heavily tested AID in the application,
     * with sixteen inline occurrences across {@code app/cbl}.
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
     * Tests for PF7, {@link CicsAid#DFHPF7} - the conventional "page backward" key, with four inline
     * occurrences across {@code app/cbl}.
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

    private PfKeyResolver() {
        throw new AssertionError("PfKeyResolver is a stateless resolver and must not be instantiated");
    }
}
