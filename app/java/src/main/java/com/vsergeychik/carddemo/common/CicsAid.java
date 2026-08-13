package com.vsergeychik.carddemo.common;

import java.util.Map;

/**
 * Attention Identifier (AID) constants, reproducing the IBM-supplied CICS {@code DFHAID} copybook
 * byte-for-byte.
 */
public final class CicsAid {
    /**
     * Each constant below equals the byte that this code page assigns to the character literal the IBM
     * {@code DFHAID} copybook declares, and equals IBM's published 3270 attention identifier code for the
     * same key.
     */
    public static final String AID_CODE_PAGE = "IBM037";

    /**
     * No AID recorded - {@code DFHNULL}, the space character, EBCDIC {@code 0x40}.
     */
    public static final byte DFHNULL = (byte) 0x40;

    /**
     * ENTER key - {@code DFHENTER}, the EBCDIC apostrophe, {@code 0x7D}.
     */
    public static final byte DFHENTER = (byte) 0x7D;

    /**
     * CLEAR key - {@code DFHCLEAR}, the EBCDIC underscore, {@code 0x6D}.
     */
    public static final byte DFHCLEAR = (byte) 0x6D;

    /**
     * CLEAR PARTITION key - {@code DFHCLRP}, the EBCDIC broken bar ({@code &#166;}), {@code 0x6A}.
     */
    public static final byte DFHCLRP = (byte) 0x6A;

    /**
     * CURSOR SELECT key, also known as the light pen attention - {@code DFHPEN}, the EBCDIC equals sign,
     * {@code 0x7E}.
     */
    public static final byte DFHPEN = (byte) 0x7E;

    /**
     * Operator identification card reader - {@code DFHOPID}, the EBCDIC capital {@code W}, {@code 0xE6}.
     */
    public static final byte DFHOPID = (byte) 0xE6;

    /**
     * Extended (standard) magnetic slot reader - {@code DFHMSRE}, the EBCDIC capital {@code X},
     * {@code 0xE7}.
     */
    public static final byte DFHMSRE = (byte) 0xE7;

    /**
     * Structured field pseudo-AID - {@code DFHSTRF}, the EBCDIC lower-case {@code h}, {@code 0x88}.
     */
    public static final byte DFHSTRF = (byte) 0x88;

    /**
     * Trigger field - {@code DFHTRIG}, the EBCDIC quotation mark, {@code 0x7F}.
     */
    public static final byte DFHTRIG = (byte) 0x7F;

    /**
     * PA1 key - {@code DFHPA1}, the EBCDIC percent sign, {@code 0x6C}.
     */
    public static final byte DFHPA1 = (byte) 0x6C;

    /**
     * PA2 key - {@code DFHPA2}, the EBCDIC greater-than sign ({@code >}), {@code 0x6E}.
     */
    public static final byte DFHPA2 = (byte) 0x6E;

    /**
     * PA3 key - {@code DFHPA3}, the EBCDIC comma, {@code 0x6B}.
     */
    public static final byte DFHPA3 = (byte) 0x6B;

    /**
     * PF1 key - {@code DFHPF1}, the EBCDIC digit {@code 1}, {@code 0xF1}.
     */
    public static final byte DFHPF1 = (byte) 0xF1;

    /**
     * PF2 key - {@code DFHPF2}, the EBCDIC digit {@code 2}, {@code 0xF2}.
     */
    public static final byte DFHPF2 = (byte) 0xF2;

    /**
     * PF3 key - {@code DFHPF3}, the EBCDIC digit {@code 3}, {@code 0xF3}.
     */
    public static final byte DFHPF3 = (byte) 0xF3;

    /**
     * PF4 key - {@code DFHPF4}, the EBCDIC digit {@code 4}, {@code 0xF4}.
     */
    public static final byte DFHPF4 = (byte) 0xF4;

    /**
     * PF5 key - {@code DFHPF5}, the EBCDIC digit {@code 5}, {@code 0xF5}.
     */
    public static final byte DFHPF5 = (byte) 0xF5;

    /**
     * PF6 key - {@code DFHPF6}, the EBCDIC digit {@code 6}, {@code 0xF6}.
     */
    public static final byte DFHPF6 = (byte) 0xF6;

    /**
     * PF7 key - {@code DFHPF7}, the EBCDIC digit {@code 7}, {@code 0xF7}.
     */
    public static final byte DFHPF7 = (byte) 0xF7;

    /**
     * PF8 key - {@code DFHPF8}, the EBCDIC digit {@code 8}, {@code 0xF8}.
     */
    public static final byte DFHPF8 = (byte) 0xF8;

    /**
     * PF9 key - {@code DFHPF9}, the EBCDIC digit {@code 9}, {@code 0xF9}.
     */
    public static final byte DFHPF9 = (byte) 0xF9;

    /**
     * PF10 key - {@code DFHPF10}, the EBCDIC colon ({@code :}), {@code 0x7A}.
     */
    public static final byte DFHPF10 = (byte) 0x7A;

    /**
     * PF11 key - {@code DFHPF11}, the EBCDIC number sign ({@code #}), {@code 0x7B}.
     */
    public static final byte DFHPF11 = (byte) 0x7B;

    /**
     * PF12 key - {@code DFHPF12}, the EBCDIC commercial-at sign ({@code @}), {@code 0x7C}.
     */
    public static final byte DFHPF12 = (byte) 0x7C;

    /**
     * PF13 key - {@code DFHPF13}, the EBCDIC capital {@code A}, {@code 0xC1}.
     */
    public static final byte DFHPF13 = (byte) 0xC1;

    /**
     * PF14 key - {@code DFHPF14}, the EBCDIC capital {@code B}, {@code 0xC2}.
     */
    public static final byte DFHPF14 = (byte) 0xC2;

    /**
     * PF15 key - {@code DFHPF15}, the EBCDIC capital {@code C}, {@code 0xC3}.
     */
    public static final byte DFHPF15 = (byte) 0xC3;

    /**
     * PF16 key - {@code DFHPF16}, the EBCDIC capital {@code D}, {@code 0xC4}.
     */
    public static final byte DFHPF16 = (byte) 0xC4;

    /**
     * PF17 key - {@code DFHPF17}, the EBCDIC capital {@code E}, {@code 0xC5}.
     */
    public static final byte DFHPF17 = (byte) 0xC5;

    /**
     * PF18 key - {@code DFHPF18}, the EBCDIC capital {@code F}, {@code 0xC6}.
     */
    public static final byte DFHPF18 = (byte) 0xC6;

    /**
     * PF19 key - {@code DFHPF19}, the EBCDIC capital {@code G}, {@code 0xC7}.
     */
    public static final byte DFHPF19 = (byte) 0xC7;

    /**
     * PF20 key - {@code DFHPF20}, the EBCDIC capital {@code H}, {@code 0xC8}.
     */
    public static final byte DFHPF20 = (byte) 0xC8;

    /**
     * PF21 key - {@code DFHPF21}, the EBCDIC capital {@code I}, {@code 0xC9}.
     */
    public static final byte DFHPF21 = (byte) 0xC9;

    /**
     * PF22 key - {@code DFHPF22}, the EBCDIC cent sign ({@code &#162;}), {@code 0x4A}.
     */
    public static final byte DFHPF22 = (byte) 0x4A;

    /**
     * PF23 key - {@code DFHPF23}, the EBCDIC period ({@code .}), {@code 0x4B}.
     */
    public static final byte DFHPF23 = (byte) 0x4B;

    /**
     * PF24 key - {@code DFHPF24}, the EBCDIC less-than sign ({@code <}), {@code 0x4C}.
     */
    public static final byte DFHPF24 = (byte) 0x4C;

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
     * @return an unmodifiable map from AID byte to {@code DFHAID} mnemonic name, never {@code null} and
     *     never empty
     */
    public static Map<Byte, String> mnemonicsByAid() {
        return MNEMONICS_BY_AID;
    }

    private CicsAid() {
        throw new AssertionError("CicsAid is a constants holder and must not be instantiated");
    }
}
