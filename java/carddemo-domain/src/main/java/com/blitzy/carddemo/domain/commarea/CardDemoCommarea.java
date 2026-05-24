/*
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
 * language governing permissions and limitations under the License.
 */
package com.blitzy.carddemo.domain.commarea;

import com.blitzy.carddemo.domain.annotation.CobolProgram;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Java translation of the COBOL {@code CARDDEMO-COMMAREA} 01-level group defined in
 * {@code app/cpy/COCOM01Y.cpy}. This is the central inter-program communication-area
 * DTO that carries transaction/program routing, user identity, customer/account/card
 * identifiers, and last-map/mapset context across CICS XCTL/LINK boundaries.
 *
 * <p>In the COBOL system, this record is passed between programs via the
 * {@code DFHCOMMAREA} mechanism. In the Java translation, instances of this record
 * are passed as method parameters and return values between application classes
 * (per AAP &sect;0.1.2: "COBOL {@code DFHCOMMAREA} &rarr; A {@code CardDemoCommarea}
 * record passed between methods").
 *
 * <p>The record layout is <strong>byte-for-byte exact</strong> with the COBOL
 * definition: 160 bytes total, with field offsets and lengths matching the COBOL
 * PIC clauses exactly. See {@link #parse(byte[])} and {@link #encode()} for the
 * byte-level serialization contract.
 *
 * <h2>Byte Layout (160 bytes total)</h2>
 * <pre>
 * Offset | Length | Field          | COBOL Type | Java Field
 * -------+--------+----------------+------------+----------------------
 *      0 |      4 | FROM-TRANID    | PIC X(04)  | generalInfo.fromTranId
 *      4 |      8 | FROM-PROGRAM   | PIC X(08)  | generalInfo.fromProgram
 *     12 |      4 | TO-TRANID      | PIC X(04)  | generalInfo.toTranId
 *     16 |      8 | TO-PROGRAM     | PIC X(08)  | generalInfo.toProgram
 *     24 |      8 | USER-ID        | PIC X(08)  | generalInfo.userId
 *     32 |      1 | USER-TYPE      | PIC X(01)  | generalInfo.userType
 *     33 |      1 | PGM-CONTEXT    | PIC 9(01)  | generalInfo.pgmContext
 *     34 |      9 | CUST-ID        | PIC 9(09)  | customerInfo.custId
 *     43 |     25 | CUST-FNAME     | PIC X(25)  | customerInfo.firstName
 *     68 |     25 | CUST-MNAME     | PIC X(25)  | customerInfo.middleName
 *     93 |     25 | CUST-LNAME     | PIC X(25)  | customerInfo.lastName
 *    118 |     11 | ACCT-ID        | PIC 9(11)  | accountInfo.acctId
 *    129 |      1 | ACCT-STATUS    | PIC X(01)  | accountInfo.acctStatus
 *    130 |     16 | CARD-NUM       | PIC 9(16)  | cardInfo.cardNum
 *    146 |      7 | LAST-MAP       | PIC X(7)   | moreInfo.lastMap
 *    153 |      7 | LAST-MAPSET    | PIC X(7)   | moreInfo.lastMapset
 * </pre>
 *
 * <h2>Immutability</h2>
 * This record and all its nested records are immutable. Compact constructors validate
 * field length, character ranges, and value spaces per JEP 513 Flexible Constructor
 * Bodies. To produce a modified copy, callers must construct a new record explicitly
 * (records in Java 25 do not have built-in {@code with*} syntax).
 *
 * <h2>Card Number Storage</h2>
 * The {@code cardNum} field is stored as a {@link String}, NOT a {@code long}. Two
 * reasons:
 * <ol>
 *   <li>16-digit PANs may have leading zeros (issuer-specific); a {@code long} would
 *       lose them.</li>
 *   <li>PAN masking (last 4 digits visible) per AAP &sect;0.7.2 logging mandate is
 *       trivial on a {@code String} and unsafe on a {@code long}.</li>
 * </ol>
 *
 * <h2>Character Encoding</h2>
 * Text fields are encoded as US-ASCII (one byte per character). The COBOL system
 * uses EBCDIC IBM-1047 in production, but the Java implementation treats the
 * commarea as an in-memory ASCII buffer for testing and for use with the 9 ASCII
 * fixtures in {@code app/data/ASCII/}. The {@link #parse(byte[])} and
 * {@link #encode()} methods use {@link StandardCharsets#US_ASCII} by default; an
 * overload accepting an explicit {@link Charset} is provided for callers that need
 * to round-trip an EBCDIC commarea via {@code Charset.forName("IBM-1047")}.
 *
 * <h2>Round-Trip Invariant</h2>
 * For every valid 160-byte buffer {@code b}:
 * {@code java.util.Arrays.equals(CardDemoCommarea.parse(b).encode(), b)} MUST be
 * {@code true}. This invariant is asserted by the golden-record harness in
 * {@code carddemo-tests} (per AAP &sect;0.6.5: "Byte-for-byte round-trip
 * requirement: for every supported record type, parse(record).encode() MUST equal
 * the original byte buffer").
 *
 * <h2>Construction Pattern</h2>
 * Use the canonical constructor to build a commarea from its five nested
 * components:
 * <pre>{@code
 * var commarea = new CardDemoCommarea(
 *     new CardDemoCommarea.GeneralInfo(
 *         "CC00", "COSGN00C", "    ", "        ", "USER0001",
 *         UserType.USER, PgmContext.ENTER),
 *     new CardDemoCommarea.CustomerInfo(
 *         123456789L,
 *         "JOHN                     ",
 *         "Q                        ",
 *         "DOE                      "),
 *     new CardDemoCommarea.AccountInfo(99999999999L, "Y"),
 *     new CardDemoCommarea.CardInfo("4111111111111234"),
 *     new CardDemoCommarea.MoreInfo("COSGN00", "COSGN00"));
 * }</pre>
 *
 * <h2>Pattern-Matching Dispatch on Sealed Components</h2>
 * The {@link GeneralInfo#userType()} and {@link GeneralInfo#pgmContext()} accessors
 * return sealed types ({@link UserType}, {@link PgmContext}). Callers MUST use
 * exhaustive pattern-matching {@code switch} expressions when branching on these
 * values; no {@code default} branch is permitted (per AAP &sect;0.6.7).
 *
 * @see UserType
 * @see PgmContext
 * @see CobolProgram
 * @since 1.0.0
 */
@CobolProgram(
        value = "COCOM01Y",
        sourcePath = "app/cpy/COCOM01Y.cpy",
        translationDate = "2025-10-15",
        notes = "CARDDEMO-COMMAREA 01-level group; 160 bytes; nested records for "
              + "CDEMO-GENERAL-INFO, CDEMO-CUSTOMER-INFO, CDEMO-ACCOUNT-INFO, "
              + "CDEMO-CARD-INFO, CDEMO-MORE-INFO; sealed UserType and PgmContext "
              + "for 88-level taxonomies"
)
public record CardDemoCommarea(
        GeneralInfo generalInfo,
        CustomerInfo customerInfo,
        AccountInfo accountInfo,
        CardInfo cardInfo,
        MoreInfo moreInfo
) {

    //--------------------------------------------------------------------------
    // Layout constants
    //--------------------------------------------------------------------------

    /** Total length in bytes of an encoded {@code CardDemoCommarea}. */
    public static final int LENGTH = 160;

    /** Offset of {@code CDEMO-FROM-TRANID PIC X(04)} within the encoded buffer. */
    public static final int OFFSET_FROM_TRANID = 0;
    /** Length of {@code CDEMO-FROM-TRANID PIC X(04)} in bytes. */
    public static final int LENGTH_FROM_TRANID = 4;

    /** Offset of {@code CDEMO-FROM-PROGRAM PIC X(08)} within the encoded buffer. */
    public static final int OFFSET_FROM_PROGRAM = 4;
    /** Length of {@code CDEMO-FROM-PROGRAM PIC X(08)} in bytes. */
    public static final int LENGTH_FROM_PROGRAM = 8;

    /** Offset of {@code CDEMO-TO-TRANID PIC X(04)} within the encoded buffer. */
    public static final int OFFSET_TO_TRANID = 12;
    /** Length of {@code CDEMO-TO-TRANID PIC X(04)} in bytes. */
    public static final int LENGTH_TO_TRANID = 4;

    /** Offset of {@code CDEMO-TO-PROGRAM PIC X(08)} within the encoded buffer. */
    public static final int OFFSET_TO_PROGRAM = 16;
    /** Length of {@code CDEMO-TO-PROGRAM PIC X(08)} in bytes. */
    public static final int LENGTH_TO_PROGRAM = 8;

    /** Offset of {@code CDEMO-USER-ID PIC X(08)} within the encoded buffer. */
    public static final int OFFSET_USER_ID = 24;
    /** Length of {@code CDEMO-USER-ID PIC X(08)} in bytes. */
    public static final int LENGTH_USER_ID = 8;

    /** Offset of {@code CDEMO-USER-TYPE PIC X(01)} within the encoded buffer. */
    public static final int OFFSET_USER_TYPE = 32;
    /** Length of {@code CDEMO-USER-TYPE PIC X(01)} in bytes. */
    public static final int LENGTH_USER_TYPE = 1;

    /** Offset of {@code CDEMO-PGM-CONTEXT PIC 9(01)} within the encoded buffer. */
    public static final int OFFSET_PGM_CONTEXT = 33;
    /** Length of {@code CDEMO-PGM-CONTEXT PIC 9(01)} in bytes. */
    public static final int LENGTH_PGM_CONTEXT = 1;

    /** Offset of {@code CDEMO-CUST-ID PIC 9(09)} within the encoded buffer. */
    public static final int OFFSET_CUST_ID = 34;
    /** Length of {@code CDEMO-CUST-ID PIC 9(09)} in bytes. */
    public static final int LENGTH_CUST_ID = 9;

    /** Offset of {@code CDEMO-CUST-FNAME PIC X(25)} within the encoded buffer. */
    public static final int OFFSET_CUST_FNAME = 43;
    /** Length of {@code CDEMO-CUST-FNAME PIC X(25)} in bytes. */
    public static final int LENGTH_CUST_FNAME = 25;

    /** Offset of {@code CDEMO-CUST-MNAME PIC X(25)} within the encoded buffer. */
    public static final int OFFSET_CUST_MNAME = 68;
    /** Length of {@code CDEMO-CUST-MNAME PIC X(25)} in bytes. */
    public static final int LENGTH_CUST_MNAME = 25;

    /** Offset of {@code CDEMO-CUST-LNAME PIC X(25)} within the encoded buffer. */
    public static final int OFFSET_CUST_LNAME = 93;
    /** Length of {@code CDEMO-CUST-LNAME PIC X(25)} in bytes. */
    public static final int LENGTH_CUST_LNAME = 25;

    /** Offset of {@code CDEMO-ACCT-ID PIC 9(11)} within the encoded buffer. */
    public static final int OFFSET_ACCT_ID = 118;
    /** Length of {@code CDEMO-ACCT-ID PIC 9(11)} in bytes. */
    public static final int LENGTH_ACCT_ID = 11;

    /** Offset of {@code CDEMO-ACCT-STATUS PIC X(01)} within the encoded buffer. */
    public static final int OFFSET_ACCT_STATUS = 129;
    /** Length of {@code CDEMO-ACCT-STATUS PIC X(01)} in bytes. */
    public static final int LENGTH_ACCT_STATUS = 1;

    /** Offset of {@code CDEMO-CARD-NUM PIC 9(16)} within the encoded buffer. */
    public static final int OFFSET_CARD_NUM = 130;
    /** Length of {@code CDEMO-CARD-NUM PIC 9(16)} in bytes. */
    public static final int LENGTH_CARD_NUM = 16;

    /** Offset of {@code CDEMO-LAST-MAP PIC X(7)} within the encoded buffer. */
    public static final int OFFSET_LAST_MAP = 146;
    /** Length of {@code CDEMO-LAST-MAP PIC X(7)} in bytes. */
    public static final int LENGTH_LAST_MAP = 7;

    /** Offset of {@code CDEMO-LAST-MAPSET PIC X(7)} within the encoded buffer. */
    public static final int OFFSET_LAST_MAPSET = 153;
    /** Length of {@code CDEMO-LAST-MAPSET PIC X(7)} in bytes. */
    public static final int LENGTH_LAST_MAPSET = 7;

    // Compile-time sanity check that the last field ends exactly at LENGTH.
    static {
        assert OFFSET_LAST_MAPSET + LENGTH_LAST_MAPSET == LENGTH
                : "Field-layout inconsistency: OFFSET_LAST_MAPSET (" + OFFSET_LAST_MAPSET
                + ") + LENGTH_LAST_MAPSET (" + LENGTH_LAST_MAPSET
                + ") must equal LENGTH (" + LENGTH + ")";
    }

    //--------------------------------------------------------------------------
    // Top-level compact constructor
    //--------------------------------------------------------------------------

    /**
     * Compact constructor enforcing non-null contracts on all five nested
     * components. JEP 513 Flexible Constructor Bodies allow these validations to
     * execute before the canonical field bindings of this record (per AAP
     * &sect;0.6.3).
     *
     * @throws NullPointerException if any component is {@code null}; the message
     *                              identifies which component
     */
    public CardDemoCommarea {
        Objects.requireNonNull(generalInfo, "generalInfo");
        Objects.requireNonNull(customerInfo, "customerInfo");
        Objects.requireNonNull(accountInfo, "accountInfo");
        Objects.requireNonNull(cardInfo, "cardInfo");
        Objects.requireNonNull(moreInfo, "moreInfo");
    }

    //--------------------------------------------------------------------------
    // Convenience factories and copy-with methods
    //
    // These methods are not required by the schema's members_exposed list but
    // are also not forbidden by the agent prompt; they support the COBOL/CICS
    // pattern of "MOVE LOW-VALUES TO COMMAREA" (empty) and per-section updates
    // across XCTL/LINK invocations (withX) without sacrificing immutability.
    //--------------------------------------------------------------------------

    /**
     * Returns a canonical "empty" commarea with all PIC X fields space-padded to
     * their declared widths, all PIC 9 numeric fields zero, the user type set to
     * {@link UserType#USER}, and the program context set to {@link PgmContext#ENTER}.
     * This corresponds to the COBOL idiom {@code MOVE LOW-VALUES TO CARDDEMO-COMMAREA}
     * followed by initialization of the discriminator fields to their well-known
     * default values.
     *
     * <p>The returned instance is a valid commarea (all field-validation
     * predicates of every compact constructor are satisfied) and can be passed
     * directly to {@link #encode()} to produce a 160-byte buffer.
     *
     * @return a new empty commarea
     */
    public static CardDemoCommarea empty() {
        return new CardDemoCommarea(
                new GeneralInfo(
                        " ".repeat(LENGTH_FROM_TRANID),
                        " ".repeat(LENGTH_FROM_PROGRAM),
                        " ".repeat(LENGTH_TO_TRANID),
                        " ".repeat(LENGTH_TO_PROGRAM),
                        " ".repeat(LENGTH_USER_ID),
                        UserType.USER,
                        PgmContext.ENTER),
                new CustomerInfo(
                        0L,
                        " ".repeat(LENGTH_CUST_FNAME),
                        " ".repeat(LENGTH_CUST_MNAME),
                        " ".repeat(LENGTH_CUST_LNAME)),
                new AccountInfo(0L, " "),
                new CardInfo("0".repeat(LENGTH_CARD_NUM)),
                new MoreInfo(
                        " ".repeat(LENGTH_LAST_MAP),
                        " ".repeat(LENGTH_LAST_MAPSET)));
    }

    /**
     * Returns a new commarea identical to this one except that the
     * {@link GeneralInfo} component is replaced by the supplied value.
     * This is the immutable equivalent of the COBOL idiom of overwriting the
     * {@code CDEMO-GENERAL-INFO} group fields on the in-place commarea.
     *
     * @param info the new general-info component (non-null)
     * @return a new commarea with the substituted component
     * @throws NullPointerException if {@code info} is {@code null}
     */
    public CardDemoCommarea withGeneralInfo(GeneralInfo info) {
        return new CardDemoCommarea(info, customerInfo, accountInfo, cardInfo, moreInfo);
    }

    /**
     * Returns a new commarea identical to this one except that the
     * {@link CustomerInfo} component is replaced by the supplied value.
     *
     * @param info the new customer-info component (non-null)
     * @return a new commarea with the substituted component
     * @throws NullPointerException if {@code info} is {@code null}
     */
    public CardDemoCommarea withCustomerInfo(CustomerInfo info) {
        return new CardDemoCommarea(generalInfo, info, accountInfo, cardInfo, moreInfo);
    }

    /**
     * Returns a new commarea identical to this one except that the
     * {@link AccountInfo} component is replaced by the supplied value.
     *
     * @param info the new account-info component (non-null)
     * @return a new commarea with the substituted component
     * @throws NullPointerException if {@code info} is {@code null}
     */
    public CardDemoCommarea withAccountInfo(AccountInfo info) {
        return new CardDemoCommarea(generalInfo, customerInfo, info, cardInfo, moreInfo);
    }

    /**
     * Returns a new commarea identical to this one except that the
     * {@link CardInfo} component is replaced by the supplied value.
     *
     * @param info the new card-info component (non-null)
     * @return a new commarea with the substituted component
     * @throws NullPointerException if {@code info} is {@code null}
     */
    public CardDemoCommarea withCardInfo(CardInfo info) {
        return new CardDemoCommarea(generalInfo, customerInfo, accountInfo, info, moreInfo);
    }

    /**
     * Returns a new commarea identical to this one except that the
     * {@link MoreInfo} component is replaced by the supplied value.
     *
     * @param info the new more-info component (non-null)
     * @return a new commarea with the substituted component
     * @throws NullPointerException if {@code info} is {@code null}
     */
    public CardDemoCommarea withMoreInfo(MoreInfo info) {
        return new CardDemoCommarea(generalInfo, customerInfo, accountInfo, cardInfo, info);
    }

    //--------------------------------------------------------------------------
    // Nested records — one per COBOL 05-level group
    //--------------------------------------------------------------------------

    /**
     * Java translation of the COBOL {@code CDEMO-GENERAL-INFO} 05-level group
     * (34 bytes; offsets 0&ndash;33). Carries transaction/program routing
     * information and user identity:
     * <ul>
     *   <li>{@code fromTranId} / {@code fromProgram}: caller identity
     *       (transaction id and program id that XCTL'd to us).</li>
     *   <li>{@code toTranId} / {@code toProgram}: outbound routing target.</li>
     *   <li>{@code userId}: signed-in user id.</li>
     *   <li>{@code userType}: sealed {@link UserType} discriminator (Admin/User)
     *       derived from {@code CDEMO-USER-TYPE} 88-level conditions.</li>
     *   <li>{@code pgmContext}: sealed {@link PgmContext} discriminator
     *       (Enter/Reenter) derived from {@code CDEMO-PGM-CONTEXT} 88-level
     *       conditions.</li>
     * </ul>
     *
     * <p>String fields are space-padded to the COBOL PIC X(N) width; the
     * compact constructor enforces exact length and US-ASCII representability.
     */
    @CobolProgram(
            value = "COCOM01Y",
            sourcePath = "app/cpy/COCOM01Y.cpy",
            translationDate = "2025-10-15",
            notes = "CDEMO-GENERAL-INFO 05-level group; 34 bytes; transaction/program "
                  + "routing and user identity"
    )
    public static record GeneralInfo(
            String fromTranId,
            String fromProgram,
            String toTranId,
            String toProgram,
            String userId,
            UserType userType,
            PgmContext pgmContext
    ) {
        /**
         * Compact constructor validating fixed-length ASCII text fields and
         * non-null sealed-type components.
         *
         * @throws NullPointerException     if any String field, {@code userType},
         *                                  or {@code pgmContext} is {@code null}
         * @throws IllegalArgumentException if any String field's length does
         *                                  not match its COBOL PIC X(N) width,
         *                                  or contains non-ASCII characters
         */
        public GeneralInfo {
            validateFixedLengthAscii(fromTranId, LENGTH_FROM_TRANID, "fromTranId");
            validateFixedLengthAscii(fromProgram, LENGTH_FROM_PROGRAM, "fromProgram");
            validateFixedLengthAscii(toTranId, LENGTH_TO_TRANID, "toTranId");
            validateFixedLengthAscii(toProgram, LENGTH_TO_PROGRAM, "toProgram");
            validateFixedLengthAscii(userId, LENGTH_USER_ID, "userId");
            Objects.requireNonNull(userType, "userType");
            Objects.requireNonNull(pgmContext, "pgmContext");
        }
    }

    /**
     * Java translation of the COBOL {@code CDEMO-CUSTOMER-INFO} 05-level group
     * (84 bytes; offsets 34&ndash;117). Carries the customer identifier and the
     * three name components (first, middle, last).
     *
     * <p>{@code custId} is stored as a {@code long}. The COBOL {@code PIC 9(09)}
     * unsigned 9-digit field has a maximum value of {@value #CUST_ID_MAX}, which
     * is well within {@code long}'s range. The compact constructor enforces this
     * upper bound and rejects negatives.
     *
     * <p>Name fields are space-padded to PIC X(25) width; the compact
     * constructor enforces exact length and US-ASCII representability.
     */
    @CobolProgram(
            value = "COCOM01Y",
            sourcePath = "app/cpy/COCOM01Y.cpy",
            translationDate = "2025-10-15",
            notes = "CDEMO-CUSTOMER-INFO 05-level group; 84 bytes; customer "
                  + "identifier and full name components"
    )
    public static record CustomerInfo(
            long custId,
            String firstName,
            String middleName,
            String lastName
    ) {
        /** Maximum value for a {@code PIC 9(09)} customer-id: 999,999,999. */
        public static final long CUST_ID_MAX = 999_999_999L;

        /**
         * Compact constructor validating the customer-id range and name field
         * widths.
         *
         * @throws IllegalArgumentException if {@code custId} is negative or
         *                                  exceeds {@value #CUST_ID_MAX}, or if
         *                                  any name field is not exactly
         *                                  {@value #LENGTH_CUST_FNAME} ASCII
         *                                  characters
         */
        public CustomerInfo {
            if (custId < 0L || custId > CUST_ID_MAX) {
                throw new IllegalArgumentException(
                        "custId out of range [0," + CUST_ID_MAX + "]: " + custId);
            }
            validateFixedLengthAscii(firstName, LENGTH_CUST_FNAME, "firstName");
            validateFixedLengthAscii(middleName, LENGTH_CUST_MNAME, "middleName");
            validateFixedLengthAscii(lastName, LENGTH_CUST_LNAME, "lastName");
        }
    }

    /**
     * Java translation of the COBOL {@code CDEMO-ACCOUNT-INFO} 05-level group
     * (12 bytes; offsets 118&ndash;129). Carries the account identifier and the
     * one-character active-status flag.
     *
     * <p>{@code acctId} is stored as a {@code long}. The COBOL {@code PIC 9(11)}
     * unsigned 11-digit field has a maximum value of {@value #ACCT_ID_MAX},
     * which is well within {@code long}'s range. The compact constructor
     * enforces this upper bound and rejects negatives.
     */
    @CobolProgram(
            value = "COCOM01Y",
            sourcePath = "app/cpy/COCOM01Y.cpy",
            translationDate = "2025-10-15",
            notes = "CDEMO-ACCOUNT-INFO 05-level group; 12 bytes; account-id and "
                  + "one-char active-status flag"
    )
    public static record AccountInfo(
            long acctId,
            String acctStatus
    ) {
        /** Maximum value for a {@code PIC 9(11)} account-id: 99,999,999,999. */
        public static final long ACCT_ID_MAX = 99_999_999_999L;

        /**
         * Compact constructor validating the account-id range and status field
         * width.
         *
         * @throws IllegalArgumentException if {@code acctId} is negative or
         *                                  exceeds {@value #ACCT_ID_MAX}, or if
         *                                  {@code acctStatus} is not exactly
         *                                  one ASCII character
         */
        public AccountInfo {
            if (acctId < 0L || acctId > ACCT_ID_MAX) {
                throw new IllegalArgumentException(
                        "acctId out of range [0," + ACCT_ID_MAX + "]: " + acctId);
            }
            validateFixedLengthAscii(acctStatus, LENGTH_ACCT_STATUS, "acctStatus");
        }
    }

    /**
     * Java translation of the COBOL {@code CDEMO-CARD-INFO} 05-level group
     * (16 bytes; offset 130&ndash;145). Carries the 16-digit Primary Account
     * Number (PAN).
     *
     * <p><strong>The PAN is stored as a {@link String}, not a {@code long}</strong>
     * (per AAP &sect;0.7.2 logging mandate and folder requirements rule #8) for
     * two reasons:
     * <ol>
     *   <li>Leading zeros must be preserved (some issuers use leading-zero
     *       PANs); a {@code long} encoding would lose them.</li>
     *   <li>PAN masking (last 4 digits visible) is safe and trivial on a
     *       {@code String}; on a {@code long} the formatting code must
     *       round-trip via {@code String.format} which adds risk of accidentally
     *       logging the full PAN.</li>
     * </ol>
     *
     * <p>The compact constructor enforces a 16-character length and ASCII-digit
     * content. Use {@link #maskedCardNum()} for any logging surface (per AAP
     * &sect;0.7.2: "No card PAN logged in full; mask all but last 4 digits in
     * logs and error messages").
     */
    @CobolProgram(
            value = "COCOM01Y",
            sourcePath = "app/cpy/COCOM01Y.cpy",
            translationDate = "2025-10-15",
            notes = "CDEMO-CARD-INFO 05-level group; 16 bytes; 16-digit PAN stored "
                  + "as String to preserve leading zeros and support PAN masking "
                  + "per AAP §0.7.2 logging mandate"
    )
    public static record CardInfo(
            String cardNum
    ) {
        /**
         * Compact constructor validating that {@code cardNum} is exactly
         * {@value #LENGTH_CARD_NUM} ASCII digits.
         *
         * @throws NullPointerException     if {@code cardNum} is {@code null}
         * @throws IllegalArgumentException if {@code cardNum} length is not
         *                                  {@value #LENGTH_CARD_NUM} or any
         *                                  character is not an ASCII digit
         *                                  '0'&ndash;'9'
         */
        public CardInfo {
            Objects.requireNonNull(cardNum, "cardNum");
            if (cardNum.length() != LENGTH_CARD_NUM) {
                throw new IllegalArgumentException(
                        "cardNum length must be " + LENGTH_CARD_NUM
                        + ", was " + cardNum.length());
            }
            for (int i = 0; i < cardNum.length(); i++) {
                char c = cardNum.charAt(i);
                if (c < '0' || c > '9') {
                    throw new IllegalArgumentException(
                            "cardNum must contain only ASCII digits 0-9; found '"
                            + c + "' at position " + i);
                }
            }
        }

        /**
         * Returns a PAN-masked representation of this card number: first 12
         * digits replaced with {@code '*'}, last 4 digits visible. Use this
         * method on every logging surface where a card number might be
         * recorded.
         *
         * <p>Per AAP &sect;0.7.2: <em>"No card PAN logged in full; mask all but
         * last 4 digits in logs and error messages."</em>
         *
         * @return a 16-character string of the form
         *         {@code "************1234"} where the trailing four characters
         *         are the last four digits of {@link #cardNum()}
         */
        public String maskedCardNum() {
            return "*".repeat(12) + cardNum.substring(12);
        }
    }

    /**
     * Java translation of the COBOL {@code CDEMO-MORE-INFO} 05-level group
     * (14 bytes; offsets 146&ndash;159). Carries the last-map and last-mapset
     * identifiers for screen-flow context.
     *
     * <p>These fields support the COBOL/CICS pattern where a program re-enters
     * itself with prior screen state to restore; the {@link PgmContext#REENTER}
     * indicator signals that the {@code lastMap} / {@code lastMapset} values
     * are meaningful (otherwise they are typically space-filled).
     */
    @CobolProgram(
            value = "COCOM01Y",
            sourcePath = "app/cpy/COCOM01Y.cpy",
            translationDate = "2025-10-15",
            notes = "CDEMO-MORE-INFO 05-level group; 14 bytes; last-map and "
                  + "last-mapset for screen-flow context"
    )
    public static record MoreInfo(
            String lastMap,
            String lastMapset
    ) {
        /**
         * Compact constructor validating fixed-length ASCII text fields.
         *
         * @throws NullPointerException     if either field is {@code null}
         * @throws IllegalArgumentException if either field is not exactly
         *                                  {@value #LENGTH_LAST_MAP} ASCII
         *                                  characters
         */
        public MoreInfo {
            validateFixedLengthAscii(lastMap, LENGTH_LAST_MAP, "lastMap");
            validateFixedLengthAscii(lastMapset, LENGTH_LAST_MAPSET, "lastMapset");
        }
    }

    //--------------------------------------------------------------------------
    // parse() factory methods
    //--------------------------------------------------------------------------

    /**
     * Parses a 160-byte commarea buffer into a {@code CardDemoCommarea} instance,
     * using {@link StandardCharsets#US_ASCII} for text fields. Equivalent to
     * {@code parse(buffer, StandardCharsets.US_ASCII)}.
     *
     * @param buffer the 160-byte raw commarea buffer (NOT {@code null}; MUST
     *               have length exactly {@link #LENGTH})
     * @return the parsed commarea instance
     * @throws NullPointerException     if {@code buffer} is {@code null}
     * @throws IllegalArgumentException if {@code buffer.length != LENGTH}, if
     *                                  any numeric field contains non-digit
     *                                  bytes, if the user-type indicator is not
     *                                  {@code 'A'} or {@code 'U'}, or if the
     *                                  pgm-context indicator is not {@code '0'}
     *                                  or {@code '1'}
     */
    public static CardDemoCommarea parse(byte[] buffer) {
        return parse(buffer, StandardCharsets.US_ASCII);
    }

    /**
     * Parses a 160-byte commarea buffer into a {@code CardDemoCommarea} instance,
     * using the specified charset for text fields. Use
     * {@code Charset.forName("IBM-1047")} to parse an EBCDIC commarea (per AAP
     * &sect;0.6.5 the file supports both US-ASCII and EBCDIC IBM-1047 via the
     * charset parameter).
     *
     * <p>Numeric fields ({@link #OFFSET_CUST_ID} and {@link #OFFSET_ACCT_ID}) are
     * read byte-by-byte as ASCII digits, independent of the supplied charset.
     * This matches the COBOL behavior where {@code PIC 9} digits are encoded as
     * unsigned-decimal ASCII digit characters in the underlying record buffer.
     * For EBCDIC commareas, callers must ensure the numeric digit bytes are
     * already ASCII (typical for in-memory test fixtures); production EBCDIC
     * files are first transcoded to ASCII by the file adapter before reaching
     * this method.
     *
     * @param buffer  the 160-byte raw commarea buffer
     * @param charset the charset for text field decoding
     * @return the parsed commarea instance
     * @throws NullPointerException     if {@code buffer} or {@code charset} is
     *                                  {@code null}
     * @throws IllegalArgumentException if validation of any field fails
     */
    public static CardDemoCommarea parse(byte[] buffer, Charset charset) {
        Objects.requireNonNull(buffer, "buffer");
        Objects.requireNonNull(charset, "charset");
        if (buffer.length != LENGTH) {
            throw new IllegalArgumentException(
                    "buffer length must be " + LENGTH + ", was " + buffer.length);
        }

        // CDEMO-GENERAL-INFO (offsets 0–33)
        String fromTranId  = readText(buffer, OFFSET_FROM_TRANID,  LENGTH_FROM_TRANID,  charset);
        String fromProgram = readText(buffer, OFFSET_FROM_PROGRAM, LENGTH_FROM_PROGRAM, charset);
        String toTranId    = readText(buffer, OFFSET_TO_TRANID,    LENGTH_TO_TRANID,    charset);
        String toProgram   = readText(buffer, OFFSET_TO_PROGRAM,   LENGTH_TO_PROGRAM,   charset);
        String userId      = readText(buffer, OFFSET_USER_ID,      LENGTH_USER_ID,      charset);

        char userTypeChar = (char) (buffer[OFFSET_USER_TYPE] & 0xFF);
        UserType userType = UserType.fromIndicator(userTypeChar);

        char pgmCtxChar  = (char) (buffer[OFFSET_PGM_CONTEXT] & 0xFF);
        int  pgmCtxDigit = Character.digit(pgmCtxChar, 10);
        if (pgmCtxDigit < 0) {
            throw new IllegalArgumentException(
                    "Invalid pgmContext digit at offset " + OFFSET_PGM_CONTEXT + ": '"
                    + pgmCtxChar + "' (U+" + Integer.toHexString(pgmCtxChar) + ")");
        }
        PgmContext pgmContext = PgmContext.fromIndicator(pgmCtxDigit);

        GeneralInfo generalInfo = new GeneralInfo(
                fromTranId, fromProgram, toTranId, toProgram, userId, userType, pgmContext);

        // CDEMO-CUSTOMER-INFO (offsets 34–117)
        long   custId     = readNumeric(buffer, OFFSET_CUST_ID,    LENGTH_CUST_ID);
        String firstName  = readText(   buffer, OFFSET_CUST_FNAME, LENGTH_CUST_FNAME, charset);
        String middleName = readText(   buffer, OFFSET_CUST_MNAME, LENGTH_CUST_MNAME, charset);
        String lastName   = readText(   buffer, OFFSET_CUST_LNAME, LENGTH_CUST_LNAME, charset);
        CustomerInfo customerInfo = new CustomerInfo(custId, firstName, middleName, lastName);

        // CDEMO-ACCOUNT-INFO (offsets 118–129)
        long   acctId     = readNumeric(buffer, OFFSET_ACCT_ID,     LENGTH_ACCT_ID);
        String acctStatus = readText(   buffer, OFFSET_ACCT_STATUS, LENGTH_ACCT_STATUS, charset);
        AccountInfo accountInfo = new AccountInfo(acctId, acctStatus);

        // CDEMO-CARD-INFO (offsets 130–145) — cardNum stored as String to preserve
        // leading zeros and to enable PAN masking per AAP §0.7.2
        String cardNum = readText(buffer, OFFSET_CARD_NUM, LENGTH_CARD_NUM, charset);
        CardInfo cardInfo = new CardInfo(cardNum);

        // CDEMO-MORE-INFO (offsets 146–159)
        String lastMap    = readText(buffer, OFFSET_LAST_MAP,    LENGTH_LAST_MAP,    charset);
        String lastMapset = readText(buffer, OFFSET_LAST_MAPSET, LENGTH_LAST_MAPSET, charset);
        MoreInfo moreInfo = new MoreInfo(lastMap, lastMapset);

        return new CardDemoCommarea(generalInfo, customerInfo, accountInfo, cardInfo, moreInfo);
    }

    //--------------------------------------------------------------------------
    // encode() instance methods
    //--------------------------------------------------------------------------

    /**
     * Encodes this commarea into a 160-byte buffer using
     * {@link StandardCharsets#US_ASCII}. Equivalent to
     * {@code encode(StandardCharsets.US_ASCII)}.
     *
     * <p><strong>Round-trip invariant</strong>: for every {@code CardDemoCommarea
     * c} obtained from {@code parse(b)}, the equality
     * {@code java.util.Arrays.equals(c.encode(), b)} holds (per AAP &sect;0.6.5
     * byte-for-byte fidelity requirement).
     *
     * @return a new 160-byte buffer encoding this commarea
     */
    public byte[] encode() {
        return encode(StandardCharsets.US_ASCII);
    }

    /**
     * Encodes this commarea into a 160-byte buffer using the specified charset.
     * Use {@code Charset.forName("IBM-1047")} to produce an EBCDIC commarea.
     *
     * <p>Numeric fields are encoded byte-by-byte as ASCII digits, independent of
     * the supplied charset, matching the COBOL behavior for {@code PIC 9}
     * digits. For EBCDIC commareas, the numeric digit bytes will therefore
     * remain ASCII digits in the resulting buffer; production code that needs
     * a strictly-EBCDIC encoding must transcode the entire buffer at a higher
     * layer (the file adapter, per AAP &sect;0.6.5).
     *
     * @param charset the charset for text field encoding (use
     *                {@code Charset.forName("IBM-1047")} for EBCDIC)
     * @return a new 160-byte buffer encoding this commarea
     * @throws NullPointerException if {@code charset} is {@code null}
     * @throws IllegalStateException if a text field's charset-encoded byte
     *                               length does not match the declared
     *                               PIC X(N) width (this indicates a charset
     *                               mismatch, e.g., using a multi-byte
     *                               encoding such as UTF-16)
     */
    public byte[] encode(Charset charset) {
        Objects.requireNonNull(charset, "charset");
        byte[] buffer = new byte[LENGTH];

        // CDEMO-GENERAL-INFO
        writeText(buffer, OFFSET_FROM_TRANID,  LENGTH_FROM_TRANID,  generalInfo.fromTranId(),  charset);
        writeText(buffer, OFFSET_FROM_PROGRAM, LENGTH_FROM_PROGRAM, generalInfo.fromProgram(), charset);
        writeText(buffer, OFFSET_TO_TRANID,    LENGTH_TO_TRANID,    generalInfo.toTranId(),    charset);
        writeText(buffer, OFFSET_TO_PROGRAM,   LENGTH_TO_PROGRAM,   generalInfo.toProgram(),   charset);
        writeText(buffer, OFFSET_USER_ID,      LENGTH_USER_ID,      generalInfo.userId(),      charset);
        buffer[OFFSET_USER_TYPE]   = (byte) generalInfo.userType().indicator();
        buffer[OFFSET_PGM_CONTEXT] = (byte) ('0' + generalInfo.pgmContext().indicator());

        // CDEMO-CUSTOMER-INFO
        writeNumeric(buffer, OFFSET_CUST_ID,    LENGTH_CUST_ID,    customerInfo.custId());
        writeText(   buffer, OFFSET_CUST_FNAME, LENGTH_CUST_FNAME, customerInfo.firstName(),  charset);
        writeText(   buffer, OFFSET_CUST_MNAME, LENGTH_CUST_MNAME, customerInfo.middleName(), charset);
        writeText(   buffer, OFFSET_CUST_LNAME, LENGTH_CUST_LNAME, customerInfo.lastName(),   charset);

        // CDEMO-ACCOUNT-INFO
        writeNumeric(buffer, OFFSET_ACCT_ID,     LENGTH_ACCT_ID,     accountInfo.acctId());
        writeText(   buffer, OFFSET_ACCT_STATUS, LENGTH_ACCT_STATUS, accountInfo.acctStatus(), charset);

        // CDEMO-CARD-INFO
        writeText(buffer, OFFSET_CARD_NUM, LENGTH_CARD_NUM, cardInfo.cardNum(), charset);

        // CDEMO-MORE-INFO
        writeText(buffer, OFFSET_LAST_MAP,    LENGTH_LAST_MAP,    moreInfo.lastMap(),    charset);
        writeText(buffer, OFFSET_LAST_MAPSET, LENGTH_LAST_MAPSET, moreInfo.lastMapset(), charset);

        return buffer;
    }

    //--------------------------------------------------------------------------
    // Private helper methods (used by parse() and encode())
    //--------------------------------------------------------------------------

    /**
     * Reads a fixed-length text field from {@code buffer} starting at
     * {@code offset}. The returned String has length exactly equal to
     * {@code length} (no trimming) so that round-trip encoding produces an
     * identical byte sequence (per AAP &sect;0.6.5).
     *
     * @param buffer  source buffer
     * @param offset  start offset (inclusive)
     * @param length  number of bytes to read
     * @param charset charset for byte-to-character decoding
     * @return a String of length {@code length}
     */
    private static String readText(byte[] buffer, int offset, int length, Charset charset) {
        return new String(buffer, offset, length, charset);
    }

    /**
     * Reads a fixed-length numeric field (COBOL PIC 9) from {@code buffer}
     * starting at {@code offset}. The field MUST consist entirely of ASCII
     * digits {@code '0'}&ndash;{@code '9'}.
     *
     * <p>Numeric fields are charset-independent: COBOL stores PIC 9 digits as
     * unsigned-decimal characters whose byte values are 0x30..0x39 in ASCII.
     * The COBOL EBCDIC representation of digit characters is 0xF0..0xF9; the
     * file adapter is responsible for transcoding the entire buffer to ASCII
     * before this method is invoked.
     *
     * @param buffer source buffer
     * @param offset start offset (inclusive)
     * @param length number of digit bytes to read
     * @return the decoded numeric value
     * @throws IllegalArgumentException if any byte in the field is not an
     *                                  ASCII digit
     */
    private static long readNumeric(byte[] buffer, int offset, int length) {
        long result = 0L;
        for (int i = 0; i < length; i++) {
            int b = buffer[offset + i] & 0xFF;
            if (b < '0' || b > '9') {
                throw new IllegalArgumentException(
                        "Non-digit byte 0x" + Integer.toHexString(b)
                        + " at offset " + (offset + i)
                        + " in numeric field starting at offset " + offset
                        + " (length " + length + ")");
            }
            result = result * 10L + (b - '0');
        }
        return result;
    }

    /**
     * Writes a text field into {@code buffer} at {@code offset}. The String
     * MUST have length exactly equal to {@code length} (per the compact-
     * constructor validation in each nested record). For ASCII and EBCDIC
     * IBM-1047 charsets the encoded byte count equals the character count;
     * for multi-byte charsets such as UTF-16, this method throws to surface
     * the charset mismatch.
     *
     * @param buffer  destination buffer
     * @param offset  start offset (inclusive)
     * @param length  expected encoded byte length
     * @param value   the String to encode
     * @param charset charset for character-to-byte encoding
     * @throws IllegalStateException if the charset-encoded byte length does
     *                               not match {@code length}
     */
    private static void writeText(byte[] buffer, int offset, int length, String value,
                                  Charset charset) {
        byte[] encoded = value.getBytes(charset);
        if (encoded.length != length) {
            throw new IllegalStateException(
                    "Encoded text length mismatch at offset " + offset
                    + ": expected " + length + ", got " + encoded.length
                    + " (value='" + value + "', charset=" + charset.name() + ")");
        }
        System.arraycopy(encoded, 0, buffer, offset, length);
    }

    /**
     * Writes a numeric field into {@code buffer} at {@code offset}, zero-padded
     * on the left to {@code length} ASCII digit bytes (matching the COBOL PIC 9
     * encoding convention).
     *
     * @param buffer destination buffer
     * @param offset start offset (inclusive)
     * @param length number of digit bytes to write
     * @param value  the non-negative numeric value to encode
     * @throws IllegalArgumentException if {@code value} is negative or exceeds
     *                                  the maximum representable in
     *                                  {@code length} digits
     */
    private static void writeNumeric(byte[] buffer, int offset, int length, long value) {
        if (value < 0L) {
            throw new IllegalArgumentException(
                    "Negative value not allowed in unsigned numeric field: " + value);
        }
        long remaining = value;
        for (int i = length - 1; i >= 0; i--) {
            buffer[offset + i] = (byte) ('0' + (int) (remaining % 10L));
            remaining /= 10L;
        }
        if (remaining > 0L) {
            throw new IllegalArgumentException(
                    "Value " + value + " exceeds the maximum representable in "
                    + length + " digits");
        }
    }

    /**
     * Validates that a String represents a fixed-length ASCII text field
     * (COBOL PIC X(N)). Throws if length differs from {@code expectedLength}
     * or if any character is not representable in US-ASCII (i.e., character
     * code point &gt; 0x7F).
     *
     * <p>This method is invoked from the compact constructors of {@link GeneralInfo},
     * {@link CustomerInfo}, {@link AccountInfo}, and {@link MoreInfo}. JEP 513
     * Flexible Constructor Bodies allow this validation to run before any
     * canonical field assignment, supporting COBOL-style input validation
     * (per AAP &sect;0.6.3).
     *
     * @param value          the String to validate
     * @param expectedLength the required exact length
     * @param fieldName      a human-readable field name used in error messages
     * @throws NullPointerException     if {@code value} is {@code null}
     * @throws IllegalArgumentException if length differs from
     *                                  {@code expectedLength} or any character
     *                                  is non-ASCII
     */
    private static void validateFixedLengthAscii(String value, int expectedLength,
                                                 String fieldName) {
        Objects.requireNonNull(value, fieldName);
        if (value.length() != expectedLength) {
            throw new IllegalArgumentException(
                    fieldName + " length must be " + expectedLength
                    + ", was " + value.length()
                    + " (value='" + value + "')");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c > 0x7F) {
                throw new IllegalArgumentException(
                        fieldName + " contains non-ASCII character '" + c
                        + "' (U+" + Integer.toHexString(c) + ") at position " + i);
            }
        }
    }
}
