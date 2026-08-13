package com.vsergeychik.carddemo.common;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The CICS communication area of the CardDemo online application - {@code 01 CARDDEMO-COMMAREA} of
 * {@code app/cpy/COCOM01Y.cpy} - as an immutable value carried in REST request and response bodies.
 *
 * <p>Widening the two map fields to eight characters "for consistency" would make {@code CDEMO-MORE-INFO}
 * sixteen bytes and the record 162, which is why that change cannot be made quietly: {@link #LAYOUT} would
 * fail to initialise.
 *
 * @param fromTranid {@code CDEMO-FROM-TRANID PIC X(04)}: the transaction the caller ran
 * @param fromProgram {@code CDEMO-FROM-PROGRAM PIC X(08)}: the program the caller ran
 * @param toTranid {@code CDEMO-TO-TRANID PIC X(04)}: the transaction to run next
 * @param toProgram {@code CDEMO-TO-PROGRAM PIC X(08)}: the program to transfer to next, the target of the
 *     four COMMAREA-driven {@code XCTL} sites
 * @param userId {@code CDEMO-USER-ID PIC X(08)}: the signed-on user, carried in the clear
 * @param userType {@code CDEMO-USER-TYPE PIC X(01)}: {@link #USER_TYPE_ADMIN} for an administrator,
 *     {@link #USER_TYPE_USER} for a regular user, and possibly neither - a space before sign-on
 * @param pgmContext {@code CDEMO-PGM-CONTEXT PIC 9(01)}: {@value #PGM_CONTEXT_ENTER} on first entry,
 *     {@value #PGM_CONTEXT_REENTER} on re-entry, and possibly neither
 * @param custId {@code CDEMO-CUST-ID PIC 9(09)}: the carried customer id
 * @param custFname {@code CDEMO-CUST-FNAME PIC X(25)}: the carried customer first name
 * @param custMname {@code CDEMO-CUST-MNAME PIC X(25)}: the carried customer middle name
 * @param custLname {@code CDEMO-CUST-LNAME PIC X(25)}: the carried customer last name
 * @param acctId {@code CDEMO-ACCT-ID PIC 9(11)}: the carried account id
 * @param acctStatus {@code CDEMO-ACCT-STATUS PIC X(01)}: the carried account status
 * @param cardNum {@code CDEMO-CARD-NUM PIC 9(16)}: the carried card number, sixteen digits
 * @param lastMap {@code CDEMO-LAST-MAP PIC X(7)}: the last map displayed - seven characters, not eight
 * @param lastMapset {@code CDEMO-LAST-MAPSET PIC X(7)}: the last mapset displayed - seven characters, not
 *     eight
 */
public record NavigationContext(String fromTranid,
                                String fromProgram,
                                String toTranid,
                                String toProgram,
                                String userId,
                                String userType,
                                int pgmContext,
                                int custId,
                                String custFname,
                                String custMname,
                                String custLname,
                                long acctId,
                                String acctStatus,
                                @JsonSerialize(using = CardNumberSerializer.class)
                                @JsonDeserialize(using = CardNumberDeserializer.class)
                                long cardNum,
                                String lastMap,
                                String lastMapset) {
    /**
     * Copybook name of {@link #fromTranid()}: {@code CDEMO-FROM-TRANID}, line 21.
     */
    public static final String FROM_TRANID_FIELD = "CDEMO-FROM-TRANID";

    /**
     * Copybook name of {@link #fromProgram()}: {@code CDEMO-FROM-PROGRAM}, line 22.
     */
    public static final String FROM_PROGRAM_FIELD = "CDEMO-FROM-PROGRAM";

    /**
     * Copybook name of {@link #toTranid()}: {@code CDEMO-TO-TRANID}, line 23.
     */
    public static final String TO_TRANID_FIELD = "CDEMO-TO-TRANID";

    /**
     * Copybook name of {@link #toProgram()}: {@code CDEMO-TO-PROGRAM}, line 24.
     */
    public static final String TO_PROGRAM_FIELD = "CDEMO-TO-PROGRAM";

    /**
     * Copybook name of {@link #userId()}: {@code CDEMO-USER-ID}, line 25.
     */
    public static final String USER_ID_FIELD = "CDEMO-USER-ID";

    /**
     * Copybook name of {@link #userType()}: {@code CDEMO-USER-TYPE}, line 26.
     */
    public static final String USER_TYPE_FIELD = "CDEMO-USER-TYPE";

    /**
     * Copybook name of {@link #pgmContext()}: {@code CDEMO-PGM-CONTEXT}, line 29.
     */
    public static final String PGM_CONTEXT_FIELD = "CDEMO-PGM-CONTEXT";

    /**
     * Copybook name of {@link #custId()}: {@code CDEMO-CUST-ID}, line 33.
     */
    public static final String CUST_ID_FIELD = "CDEMO-CUST-ID";

    /**
     * Copybook name of {@link #custFname()}: {@code CDEMO-CUST-FNAME}, line 34.
     */
    public static final String CUST_FNAME_FIELD = "CDEMO-CUST-FNAME";

    /**
     * Copybook name of {@link #custMname()}: {@code CDEMO-CUST-MNAME}, line 35.
     */
    public static final String CUST_MNAME_FIELD = "CDEMO-CUST-MNAME";

    /**
     * Copybook name of {@link #custLname()}: {@code CDEMO-CUST-LNAME}, line 36.
     */
    public static final String CUST_LNAME_FIELD = "CDEMO-CUST-LNAME";

    /**
     * Copybook name of {@link #acctId()}: {@code CDEMO-ACCT-ID}, line 38.
     */
    public static final String ACCT_ID_FIELD = "CDEMO-ACCT-ID";

    /**
     * Copybook name of {@link #acctStatus()}: {@code CDEMO-ACCT-STATUS}, line 39.
     */
    public static final String ACCT_STATUS_FIELD = "CDEMO-ACCT-STATUS";

    /**
     * Copybook name of {@link #cardNum()}: {@code CDEMO-CARD-NUM}, line 41.
     */
    public static final String CARD_NUM_FIELD = "CDEMO-CARD-NUM";

    /**
     * Copybook name of {@link #lastMap()}: {@code CDEMO-LAST-MAP}, line 43.
     */
    public static final String LAST_MAP_FIELD = "CDEMO-LAST-MAP";

    /**
     * Copybook name of {@link #lastMapset()}: {@code CDEMO-LAST-MAPSET}, line 44.
     */
    public static final String LAST_MAPSET_FIELD = "CDEMO-LAST-MAPSET";

    /**
     * Declared width of {@code CDEMO-FROM-TRANID PIC X(04)}.
     */
    public static final int FROM_TRANID_LENGTH = 4;

    /**
     * Declared width of {@code CDEMO-FROM-PROGRAM PIC X(08)} - a program name is eight characters.
     */
    public static final int FROM_PROGRAM_LENGTH = 8;

    /**
     * Declared width of {@code CDEMO-TO-TRANID PIC X(04)}.
     */
    public static final int TO_TRANID_LENGTH = 4;

    /**
     * Declared width of {@code CDEMO-TO-PROGRAM PIC X(08)} - a program name is eight characters.
     */
    public static final int TO_PROGRAM_LENGTH = 8;

    /**
     * Declared width of {@code CDEMO-USER-ID PIC X(08)}.
     */
    public static final int USER_ID_LENGTH = 8;

    /**
     * Declared width of {@code CDEMO-USER-TYPE PIC X(01)}.
     */
    public static final int USER_TYPE_LENGTH = 1;

    /**
     * Declared digit count of {@code CDEMO-PGM-CONTEXT PIC 9(01)}.
     */
    public static final int PGM_CONTEXT_LENGTH = 1;

    /**
     * Declared digit count of {@code CDEMO-CUST-ID PIC 9(09)}.
     */
    public static final int CUST_ID_LENGTH = 9;

    /**
     * Declared width of {@code CDEMO-CUST-FNAME PIC X(25)}.
     */
    public static final int CUST_FNAME_LENGTH = 25;

    /**
     * Declared width of {@code CDEMO-CUST-MNAME PIC X(25)}.
     */
    public static final int CUST_MNAME_LENGTH = 25;

    /**
     * Declared width of {@code CDEMO-CUST-LNAME PIC X(25)}.
     */
    public static final int CUST_LNAME_LENGTH = 25;

    /**
     * Declared digit count of {@code CDEMO-ACCT-ID PIC 9(11)}.
     */
    public static final int ACCT_ID_LENGTH = 11;

    /**
     * Declared width of {@code CDEMO-ACCT-STATUS PIC X(01)}.
     */
    public static final int ACCT_STATUS_LENGTH = 1;

    /**
     * Declared digit count of {@code CDEMO-CARD-NUM PIC 9(16)}.
     */
    public static final int CARD_NUM_LENGTH = 16;

    /**
     * Declared width of {@code CDEMO-LAST-MAP PIC X(7)} - seven, not eight.
     */
    public static final int LAST_MAP_LENGTH = 7;

    /**
     * Declared width of {@code CDEMO-LAST-MAPSET PIC X(7)} - seven, not eight, for the same reason as
     * {@link #LAST_MAP_LENGTH}.
     */
    public static final int LAST_MAPSET_LENGTH = 7;

    /**
     * Offset of {@code CDEMO-GENERAL-INFO}: the record starts here.
     */
    public static final int GENERAL_INFO_OFFSET = 0;

    /**
     * Width of {@code CDEMO-GENERAL-INFO}: {@code 4 + 8 + 4 + 8 + 8 + 1 + 1}.
     */
    public static final int GENERAL_INFO_LENGTH = 34;

    /**
     * Offset of {@code CDEMO-CUSTOMER-INFO}, immediately after {@code CDEMO-GENERAL-INFO}.
     */
    public static final int CUSTOMER_INFO_OFFSET = GENERAL_INFO_OFFSET + GENERAL_INFO_LENGTH;

    /**
     * Width of {@code CDEMO-CUSTOMER-INFO}: {@code 9 + 25 + 25 + 25}.
     */
    public static final int CUSTOMER_INFO_LENGTH = 84;

    /**
     * Offset of {@code CDEMO-ACCOUNT-INFO}, immediately after {@code CDEMO-CUSTOMER-INFO}.
     */
    public static final int ACCOUNT_INFO_OFFSET = CUSTOMER_INFO_OFFSET + CUSTOMER_INFO_LENGTH;

    /**
     * Width of {@code CDEMO-ACCOUNT-INFO}: {@code 11 + 1}.
     */
    public static final int ACCOUNT_INFO_LENGTH = 12;

    /**
     * Offset of {@code CDEMO-CARD-INFO}, immediately after {@code CDEMO-ACCOUNT-INFO}.
     */
    public static final int CARD_INFO_OFFSET = ACCOUNT_INFO_OFFSET + ACCOUNT_INFO_LENGTH;

    /**
     * Width of {@code CDEMO-CARD-INFO}: the sixteen digits of {@code CDEMO-CARD-NUM}.
     */
    public static final int CARD_INFO_LENGTH = 16;

    /**
     * Offset of {@code CDEMO-MORE-INFO}, immediately after {@code CDEMO-CARD-INFO}.
     */
    public static final int MORE_INFO_OFFSET = CARD_INFO_OFFSET + CARD_INFO_LENGTH;

    /**
     * Width of {@code CDEMO-MORE-INFO}: {@code 7 + 7}.
     */
    public static final int MORE_INFO_LENGTH = 14;

    /**
     * The declared width of {@code 01 CARDDEMO-COMMAREA} in bytes: {@code 34 + 84 + 12 + 16 + 14}.
     */
    public static final int COMMAREA_LENGTH = 160;

    /**
     * Offset of {@code CDEMO-FROM-TRANID}.
     */
    public static final int FROM_TRANID_OFFSET = GENERAL_INFO_OFFSET;

    /**
     * Offset of {@code CDEMO-FROM-PROGRAM}.
     */
    public static final int FROM_PROGRAM_OFFSET = FROM_TRANID_OFFSET + FROM_TRANID_LENGTH;

    /**
     * Offset of {@code CDEMO-TO-TRANID}.
     */
    public static final int TO_TRANID_OFFSET = FROM_PROGRAM_OFFSET + FROM_PROGRAM_LENGTH;

    /**
     * Offset of {@code CDEMO-TO-PROGRAM}.
     */
    public static final int TO_PROGRAM_OFFSET = TO_TRANID_OFFSET + TO_TRANID_LENGTH;

    /**
     * Offset of {@code CDEMO-USER-ID}.
     */
    public static final int USER_ID_OFFSET = TO_PROGRAM_OFFSET + TO_PROGRAM_LENGTH;

    /**
     * Offset of {@code CDEMO-USER-TYPE}.
     */
    public static final int USER_TYPE_OFFSET = USER_ID_OFFSET + USER_ID_LENGTH;

    /**
     * Offset of {@code CDEMO-PGM-CONTEXT}, the last item of {@code CDEMO-GENERAL-INFO}.
     */
    public static final int PGM_CONTEXT_OFFSET = USER_TYPE_OFFSET + USER_TYPE_LENGTH;

    /**
     * Offset of {@code CDEMO-CUST-ID}, anchored to the start of {@code CDEMO-CUSTOMER-INFO}.
     */
    public static final int CUST_ID_OFFSET = CUSTOMER_INFO_OFFSET;

    /**
     * Offset of {@code CDEMO-CUST-FNAME}.
     */
    public static final int CUST_FNAME_OFFSET = CUST_ID_OFFSET + CUST_ID_LENGTH;

    /**
     * Offset of {@code CDEMO-CUST-MNAME}.
     */
    public static final int CUST_MNAME_OFFSET = CUST_FNAME_OFFSET + CUST_FNAME_LENGTH;

    /**
     * Offset of {@code CDEMO-CUST-LNAME}, the last item of {@code CDEMO-CUSTOMER-INFO}.
     */
    public static final int CUST_LNAME_OFFSET = CUST_MNAME_OFFSET + CUST_MNAME_LENGTH;

    /**
     * Offset of {@code CDEMO-ACCT-ID}, anchored to the start of {@code CDEMO-ACCOUNT-INFO}.
     */
    public static final int ACCT_ID_OFFSET = ACCOUNT_INFO_OFFSET;

    /**
     * Offset of {@code CDEMO-ACCT-STATUS}, the last item of {@code CDEMO-ACCOUNT-INFO}.
     */
    public static final int ACCT_STATUS_OFFSET = ACCT_ID_OFFSET + ACCT_ID_LENGTH;

    /**
     * Offset of {@code CDEMO-CARD-NUM}, the only item of {@code CDEMO-CARD-INFO}.
     */
    public static final int CARD_NUM_OFFSET = CARD_INFO_OFFSET;

    /**
     * Offset of {@code CDEMO-LAST-MAP}, anchored to the start of {@code CDEMO-MORE-INFO}.
     */
    public static final int LAST_MAP_OFFSET = MORE_INFO_OFFSET;

    /**
     * Offset of {@code CDEMO-LAST-MAPSET}, the last item of the record.
     */
    public static final int LAST_MAPSET_OFFSET = LAST_MAP_OFFSET + LAST_MAP_LENGTH;

    /**
     * The {@code CDEMO-USER-TYPE} value that satisfies {@code 88 CDEMO-USRTYP-ADMIN VALUE 'A'}, declared at
     * {@code app/cpy/COCOM01Y.cpy} line 27.
     */
    public static final String USER_TYPE_ADMIN = "A";

    /**
     * The {@code CDEMO-USER-TYPE} value that satisfies {@code 88 CDEMO-USRTYP-USER VALUE 'U'}, declared at
     * {@code app/cpy/COCOM01Y.cpy} line 28.
     */
    public static final String USER_TYPE_USER = "U";

    /**
     * The {@code CDEMO-PGM-CONTEXT} value that satisfies {@code 88 CDEMO-PGM-ENTER VALUE 0}, declared at
     * {@code app/cpy/COCOM01Y.cpy} line 30.
     */
    public static final int PGM_CONTEXT_ENTER = 0;

    /**
     * The {@code CDEMO-PGM-CONTEXT} value that satisfies {@code 88 CDEMO-PGM-REENTER VALUE 1}, declared at
     * {@code app/cpy/COCOM01Y.cpy} line 31.
     */
    public static final int PGM_CONTEXT_REENTER = 1;

    private static final String SPACE = " ";

    public static final String REDACTED = "[REDACTED]";

    private static final String ZERO = "0";

    /**
     * The ordered, self-checking descriptor list of {@code 01 CARDDEMO-COMMAREA}: sixteen storage spans in
     * copybook declaration order, no {@code FILLER} - the copybook declares none - and no {@code REDEFINES}
     * overlay.
     */
    public static final FixedWidthRecord.RecordLayout LAYOUT = FixedWidthRecord.RecordLayout.of(
            COMMAREA_LENGTH,
            FixedWidthRecord.FieldSpan.alphanumeric(
                    FROM_TRANID_FIELD, FROM_TRANID_OFFSET, FROM_TRANID_LENGTH),
            FixedWidthRecord.FieldSpan.alphanumeric(
                    FROM_PROGRAM_FIELD, FROM_PROGRAM_OFFSET, FROM_PROGRAM_LENGTH),
            FixedWidthRecord.FieldSpan.alphanumeric(
                    TO_TRANID_FIELD, TO_TRANID_OFFSET, TO_TRANID_LENGTH),
            FixedWidthRecord.FieldSpan.alphanumeric(
                    TO_PROGRAM_FIELD, TO_PROGRAM_OFFSET, TO_PROGRAM_LENGTH),
            FixedWidthRecord.FieldSpan.alphanumeric(
                    USER_ID_FIELD, USER_ID_OFFSET, USER_ID_LENGTH),
            FixedWidthRecord.FieldSpan.alphanumeric(
                    USER_TYPE_FIELD, USER_TYPE_OFFSET, USER_TYPE_LENGTH),
            FixedWidthRecord.FieldSpan.unsignedNumeric(
                    PGM_CONTEXT_FIELD, PGM_CONTEXT_OFFSET, PGM_CONTEXT_LENGTH),
            FixedWidthRecord.FieldSpan.unsignedNumeric(
                    CUST_ID_FIELD, CUST_ID_OFFSET, CUST_ID_LENGTH),
            FixedWidthRecord.FieldSpan.alphanumeric(
                    CUST_FNAME_FIELD, CUST_FNAME_OFFSET, CUST_FNAME_LENGTH),
            FixedWidthRecord.FieldSpan.alphanumeric(
                    CUST_MNAME_FIELD, CUST_MNAME_OFFSET, CUST_MNAME_LENGTH),
            FixedWidthRecord.FieldSpan.alphanumeric(
                    CUST_LNAME_FIELD, CUST_LNAME_OFFSET, CUST_LNAME_LENGTH),
            FixedWidthRecord.FieldSpan.unsignedNumeric(
                    ACCT_ID_FIELD, ACCT_ID_OFFSET, ACCT_ID_LENGTH),
            FixedWidthRecord.FieldSpan.alphanumeric(
                    ACCT_STATUS_FIELD, ACCT_STATUS_OFFSET, ACCT_STATUS_LENGTH),
            FixedWidthRecord.FieldSpan.unsignedNumeric(
                    CARD_NUM_FIELD, CARD_NUM_OFFSET, CARD_NUM_LENGTH),
            FixedWidthRecord.FieldSpan.alphanumeric(
                    LAST_MAP_FIELD, LAST_MAP_OFFSET, LAST_MAP_LENGTH),
            FixedWidthRecord.FieldSpan.alphanumeric(
                    LAST_MAPSET_FIELD, LAST_MAPSET_OFFSET, LAST_MAPSET_LENGTH));

    /**
     * Validates every component at construction time, so an instance either exists and fits the
     * {@value #COMMAREA_LENGTH}-byte communication area or does not exist at all.
     *
     * <p>A shorter value is accepted and is padded on the right with spaces by the codec when the image is
     * produced, exactly as a COBOL {@code MOVE} into a wider {@code PIC X} receiver pads.
     */
    public NavigationContext {
        fromTranid = requireWidth(fromTranid, FROM_TRANID_LENGTH, FROM_TRANID_FIELD);
        fromProgram = requireWidth(fromProgram, FROM_PROGRAM_LENGTH, FROM_PROGRAM_FIELD);
        toTranid = requireWidth(toTranid, TO_TRANID_LENGTH, TO_TRANID_FIELD);
        toProgram = requireWidth(toProgram, TO_PROGRAM_LENGTH, TO_PROGRAM_FIELD);
        userId = requireWidth(userId, USER_ID_LENGTH, USER_ID_FIELD);
        userType = requireWidth(userType, USER_TYPE_LENGTH, USER_TYPE_FIELD);
        pgmContext = requireUnsignedDigits(pgmContext, PGM_CONTEXT_LENGTH, PGM_CONTEXT_FIELD);
        custId = requireUnsignedDigits(custId, CUST_ID_LENGTH, CUST_ID_FIELD);
        custFname = requireWidth(custFname, CUST_FNAME_LENGTH, CUST_FNAME_FIELD);
        custMname = requireWidth(custMname, CUST_MNAME_LENGTH, CUST_MNAME_FIELD);
        custLname = requireWidth(custLname, CUST_LNAME_LENGTH, CUST_LNAME_FIELD);
        acctId = requireUnsignedDigits(acctId, ACCT_ID_LENGTH, ACCT_ID_FIELD);
        acctStatus = requireWidth(acctStatus, ACCT_STATUS_LENGTH, ACCT_STATUS_FIELD);
        cardNum = requireUnsignedDigits(cardNum, CARD_NUM_LENGTH, CARD_NUM_FIELD);
        lastMap = requireWidth(lastMap, LAST_MAP_LENGTH, LAST_MAP_FIELD);
        lastMapset = requireWidth(lastMapset, LAST_MAPSET_LENGTH, LAST_MAPSET_FIELD);
    }

    /**
     * A freshly initialised communication area: every {@code PIC X} field a run of spaces of its declared
     * width, every {@code PIC 9} field zero.
     *
     * @return the initial communication area, never {@code null}
     */
    public static NavigationContext empty() {
        return new NavigationContext(spaces(FROM_TRANID_LENGTH),
                spaces(FROM_PROGRAM_LENGTH),
                spaces(TO_TRANID_LENGTH),
                spaces(TO_PROGRAM_LENGTH),
                spaces(USER_ID_LENGTH),
                spaces(USER_TYPE_LENGTH),
                PGM_CONTEXT_ENTER,
                0,
                spaces(CUST_FNAME_LENGTH),
                spaces(CUST_MNAME_LENGTH),
                spaces(CUST_LNAME_LENGTH),
                0L,
                spaces(ACCT_STATUS_LENGTH),
                0L,
                spaces(LAST_MAP_LENGTH),
                spaces(LAST_MAPSET_LENGTH));
    }

    /**
     * Whether {@code 88 CDEMO-USRTYP-ADMIN VALUE 'A'} holds - that is, whether {@link #userType()} is
     * exactly {@link #USER_TYPE_ADMIN}.
     *
     * @return {@code true} only when the user type is exactly {@link #USER_TYPE_ADMIN}
     */
    @JsonIgnore
    public boolean isAdmin() {
        return USER_TYPE_ADMIN.equals(userType);
    }

    /**
     * Whether {@code 88 CDEMO-USRTYP-USER VALUE 'U'} holds - that is, whether {@link #userType()} is
     * exactly {@link #USER_TYPE_USER}.
     *
     * @return {@code true} only when the user type is exactly {@link #USER_TYPE_USER}
     */
    @JsonIgnore
    public boolean isUser() {
        return USER_TYPE_USER.equals(userType);
    }

    /**
     * Whether {@code 88 CDEMO-PGM-ENTER VALUE 0} holds - that is, whether {@link #pgmContext()} is
     * {@value #PGM_CONTEXT_ENTER}.
     *
     * @return {@code true} only when the program context is exactly {@value #PGM_CONTEXT_ENTER}
     */
    @JsonIgnore
    public boolean isEnter() {
        return pgmContext == PGM_CONTEXT_ENTER;
    }

    /**
     * Whether {@code 88 CDEMO-PGM-REENTER VALUE 1} holds - that is, whether {@link #pgmContext()} is
     * {@value #PGM_CONTEXT_REENTER}.
     *
     * <p>Deliberately not written as {@code !isEnter()}: {@code CDEMO-PGM-CONTEXT} is {@code PIC 9(01)} and
     * can hold any digit, so a context of, say, {@code 9} satisfies neither condition.
     *
     * @return {@code true} only when the program context is exactly {@value #PGM_CONTEXT_REENTER}
     */
    @JsonIgnore
    public boolean isReenter() {
        return pgmContext == PGM_CONTEXT_REENTER;
    }

    /**
     * Returns a copy carrying a new {@code CDEMO-FROM-TRANID}, the
     * {@code MOVE WS-TRANID TO CDEMO-FROM-TRANID} of {@code app/cbl/COSGN00C.cbl:224}.
     *
     * @param newFromTranid the calling transaction identifier, at most {@value #FROM_TRANID_LENGTH}
     *     characters
     * @return a new instance; this one is unchanged
     * @throws NullPointerException if {@code newFromTranid} is {@code null}
     * @throws IllegalArgumentException if it is longer than {@value #FROM_TRANID_LENGTH}
     */
    public NavigationContext withFromTranid(String newFromTranid) {
        return new NavigationContext(newFromTranid, fromProgram, toTranid, toProgram, userId,
                userType, pgmContext, custId, custFname, custMname, custLname, acctId, acctStatus,
                cardNum, lastMap, lastMapset);
    }

    /**
     * Returns a copy carrying a new {@code CDEMO-FROM-PROGRAM}, the
     * {@code MOVE WS-PGMNAME TO CDEMO-FROM-PROGRAM} of {@code app/cbl/COSGN00C.cbl:225} and the
     * {@code MOVE LIT-THISPGM TO CDEMO-FROM-PROGRAM} of {@code app/cbl/COCRDLIC.cbl:319}.
     *
     * @param newFromProgram the calling program name, at most {@value #FROM_PROGRAM_LENGTH} characters
     * @return a new instance; this one is unchanged
     * @throws NullPointerException if {@code newFromProgram} is {@code null}
     * @throws IllegalArgumentException if it is longer than {@value #FROM_PROGRAM_LENGTH}
     */
    public NavigationContext withFromProgram(String newFromProgram) {
        return new NavigationContext(fromTranid, newFromProgram, toTranid, toProgram, userId,
                userType, pgmContext, custId, custFname, custMname, custLname, acctId, acctStatus,
                cardNum, lastMap, lastMapset);
    }

    /**
     * Returns a copy carrying a new {@code CDEMO-TO-TRANID}: the transaction the client should run next.
     *
     * @param newToTranid the target transaction identifier, at most {@value #TO_TRANID_LENGTH} characters
     * @return a new instance; this one is unchanged
     * @throws NullPointerException if {@code newToTranid} is {@code null}
     * @throws IllegalArgumentException if it is longer than {@value #TO_TRANID_LENGTH}
     */
    public NavigationContext withToTranid(String newToTranid) {
        return new NavigationContext(fromTranid, fromProgram, newToTranid, toProgram, userId,
                userType, pgmContext, custId, custFname, custMname, custLname, acctId, acctStatus,
                cardNum, lastMap, lastMapset);
    }

    /**
     * Returns a copy carrying a new {@code CDEMO-TO-PROGRAM}: the program the client should call next.
     *
     * @param newToProgram the target program name, at most {@value #TO_PROGRAM_LENGTH} characters
     * @return a new instance; this one is unchanged
     * @throws NullPointerException if {@code newToProgram} is {@code null}
     * @throws IllegalArgumentException if it is longer than {@value #TO_PROGRAM_LENGTH}
     */
    public NavigationContext withToProgram(String newToProgram) {
        return new NavigationContext(fromTranid, fromProgram, toTranid, newToProgram, userId,
                userType, pgmContext, custId, custFname, custMname, custLname, acctId, acctStatus,
                cardNum, lastMap, lastMapset);
    }

    /**
     * Returns a copy carrying a new {@code CDEMO-USER-ID}, the {@code MOVE WS-USER-ID TO CDEMO-USER-ID} of
     * {@code app/cbl/COSGN00C.cbl:226}.
     *
     * @param newUserId the signed-on user identifier, at most {@value #USER_ID_LENGTH} characters
     * @return a new instance; this one is unchanged
     * @throws NullPointerException if {@code newUserId} is {@code null}
     * @throws IllegalArgumentException if it is longer than {@value #USER_ID_LENGTH}
     */
    public NavigationContext withUserId(String newUserId) {
        return new NavigationContext(fromTranid, fromProgram, toTranid, toProgram, newUserId,
                userType, pgmContext, custId, custFname, custMname, custLname, acctId, acctStatus,
                cardNum, lastMap, lastMapset);
    }

    /**
     * Returns a copy carrying a new {@code CDEMO-USER-TYPE}, the
     * {@code MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE} of {@code app/cbl/COSGN00C.cbl:227}, which moves the
     * {@code SEC-USR-TYPE} byte of the security record straight into the communication area.
     *
     * @param newUserType the user type character, at most {@value #USER_TYPE_LENGTH} character
     * @return a new instance; this one is unchanged
     * @throws NullPointerException if {@code newUserType} is {@code null}
     * @throws IllegalArgumentException if it is longer than {@value #USER_TYPE_LENGTH}
     */
    public NavigationContext withUserType(String newUserType) {
        return new NavigationContext(fromTranid, fromProgram, toTranid, toProgram, userId,
                newUserType, pgmContext, custId, custFname, custMname, custLname, acctId, acctStatus,
                cardNum, lastMap, lastMapset);
    }

    /**
     * Returns a copy whose {@code CDEMO-USER-TYPE} is {@link #USER_TYPE_ADMIN}: the equivalent of COBOL's
     * {@code SET CDEMO-USRTYP-ADMIN TO TRUE}, which stores the condition's declared value into the field it
     * is declared over.
     *
     * @return a new instance for which {@link #isAdmin()} is true and {@link #isUser()} is false
     */
    public NavigationContext withUserTypeAdmin() {
        return withUserType(USER_TYPE_ADMIN);
    }

    /**
     * Returns a copy whose {@code CDEMO-USER-TYPE} is {@link #USER_TYPE_USER}: the equivalent of
     * {@code SET CDEMO-USRTYP-USER TO TRUE}, which {@code app/cbl/COCRDLIC.cbl} performs at lines 320, 388,
     * 466, 522 and 550.
     *
     * @return a new instance for which {@link #isUser()} is true and {@link #isAdmin()} is false
     */
    public NavigationContext withUserTypeUser() {
        return withUserType(USER_TYPE_USER);
    }

    /**
     * Returns a copy carrying a new {@code CDEMO-PGM-CONTEXT}.
     *
     * @param newPgmContext the program context digit, from {@code 0} to {@code 9}
     * @return a new instance; this one is unchanged
     * @throws IllegalArgumentException if the value is negative or needs more than
     *     {@value #PGM_CONTEXT_LENGTH} digit
     */
    public NavigationContext withPgmContext(int newPgmContext) {
        return new NavigationContext(fromTranid, fromProgram, toTranid, toProgram, userId,
                userType, newPgmContext, custId, custFname, custMname, custLname, acctId, acctStatus,
                cardNum, lastMap, lastMapset);
    }

    /**
     * Returns a copy in first-entry state, {@code CDEMO-PGM-CONTEXT} set to {@value #PGM_CONTEXT_ENTER}:
     * the equivalent of {@code SET CDEMO-PGM-ENTER TO TRUE} ({@code app/cbl/COCRDLIC.cbl:321} and six
     * sibling sites) and of the {@code MOVE ZEROS TO CDEMO-PGM-CONTEXT} that sixteen programs perform,
     * among them {@code app/cbl/COSGN00C.cbl:228}.
     *
     * @return a new instance for which {@link #isEnter()} is true and {@link #isReenter()} is false
     */
    public NavigationContext withPgmEnter() {
        return withPgmContext(PGM_CONTEXT_ENTER);
    }

    /**
     * Returns a copy in re-entry state, {@code CDEMO-PGM-CONTEXT} set to {@value #PGM_CONTEXT_REENTER}: the
     * equivalent of {@code SET CDEMO-PGM-REENTER TO TRUE}.
     *
     * @return a new instance for which {@link #isReenter()} is true and {@link #isEnter()} is false
     */
    public NavigationContext withPgmReenter() {
        return withPgmContext(PGM_CONTEXT_REENTER);
    }

    /**
     * Returns a copy carrying a new {@code CDEMO-CUST-ID}.
     *
     * @param newCustId the customer identifier, at most {@value #CUST_ID_LENGTH} digits and never negative
     * @return a new instance; this one is unchanged
     * @throws IllegalArgumentException if the value is negative or needs more than {@value #CUST_ID_LENGTH}
     *     digits
     */
    public NavigationContext withCustId(int newCustId) {
        return new NavigationContext(fromTranid, fromProgram, toTranid, toProgram, userId,
                userType, pgmContext, newCustId, custFname, custMname, custLname, acctId, acctStatus,
                cardNum, lastMap, lastMapset);
    }

    /**
     * Returns a copy carrying a new {@code CDEMO-CUST-FNAME}.
     *
     * @param newCustFname the customer first name, at most {@value #CUST_FNAME_LENGTH} characters
     * @return a new instance; this one is unchanged
     * @throws NullPointerException if {@code newCustFname} is {@code null}
     * @throws IllegalArgumentException if it is longer than {@value #CUST_FNAME_LENGTH}
     */
    public NavigationContext withCustFname(String newCustFname) {
        return new NavigationContext(fromTranid, fromProgram, toTranid, toProgram, userId,
                userType, pgmContext, custId, newCustFname, custMname, custLname, acctId, acctStatus,
                cardNum, lastMap, lastMapset);
    }

    /**
     * Returns a copy carrying a new {@code CDEMO-CUST-MNAME}.
     *
     * @param newCustMname the customer middle name, at most {@value #CUST_MNAME_LENGTH} characters
     * @return a new instance; this one is unchanged
     * @throws NullPointerException if {@code newCustMname} is {@code null}
     * @throws IllegalArgumentException if it is longer than {@value #CUST_MNAME_LENGTH}
     */
    public NavigationContext withCustMname(String newCustMname) {
        return new NavigationContext(fromTranid, fromProgram, toTranid, toProgram, userId,
                userType, pgmContext, custId, custFname, newCustMname, custLname, acctId, acctStatus,
                cardNum, lastMap, lastMapset);
    }

    /**
     * Returns a copy carrying a new {@code CDEMO-CUST-LNAME}.
     *
     * @param newCustLname the customer last name, at most {@value #CUST_LNAME_LENGTH} characters
     * @return a new instance; this one is unchanged
     * @throws NullPointerException if {@code newCustLname} is {@code null}
     * @throws IllegalArgumentException if it is longer than {@value #CUST_LNAME_LENGTH}
     */
    public NavigationContext withCustLname(String newCustLname) {
        return new NavigationContext(fromTranid, fromProgram, toTranid, toProgram, userId,
                userType, pgmContext, custId, custFname, custMname, newCustLname, acctId, acctStatus,
                cardNum, lastMap, lastMapset);
    }

    /**
     * Returns a copy carrying a new {@code CDEMO-ACCT-ID}.
     *
     * @param newAcctId the account identifier, at most {@value #ACCT_ID_LENGTH} digits and never negative
     * @return a new instance; this one is unchanged
     * @throws IllegalArgumentException if the value is negative or needs more than {@value #ACCT_ID_LENGTH}
     *     digits
     */
    public NavigationContext withAcctId(long newAcctId) {
        return new NavigationContext(fromTranid, fromProgram, toTranid, toProgram, userId,
                userType, pgmContext, custId, custFname, custMname, custLname, newAcctId, acctStatus,
                cardNum, lastMap, lastMapset);
    }

    /**
     * Returns a copy carrying a new {@code CDEMO-ACCT-STATUS}.
     *
     * @param newAcctStatus the account status character, at most {@value #ACCT_STATUS_LENGTH} character
     * @return a new instance; this one is unchanged
     * @throws NullPointerException if {@code newAcctStatus} is {@code null}
     * @throws IllegalArgumentException if it is longer than {@value #ACCT_STATUS_LENGTH}
     */
    public NavigationContext withAcctStatus(String newAcctStatus) {
        return new NavigationContext(fromTranid, fromProgram, toTranid, toProgram, userId,
                userType, pgmContext, custId, custFname, custMname, custLname, acctId, newAcctStatus,
                cardNum, lastMap, lastMapset);
    }

    /**
     * Returns a copy carrying a new {@code CDEMO-CARD-NUM}, the
     * {@code MOVE CC-CARD-NUM-N TO CDEMO-CARD-NUM} of {@code app/cbl/COCRDLIC.cbl:1064}.
     *
     * @param newCardNum the card number, at most {@value #CARD_NUM_LENGTH} digits and never negative
     * @return a new instance; this one is unchanged
     * @throws IllegalArgumentException if the value is negative or needs more than
     *     {@value #CARD_NUM_LENGTH} digits
     */
    public NavigationContext withCardNum(long newCardNum) {
        return new NavigationContext(fromTranid, fromProgram, toTranid, toProgram, userId,
                userType, pgmContext, custId, custFname, custMname, custLname, acctId, acctStatus,
                newCardNum, lastMap, lastMapset);
    }

    /**
     * Returns a copy carrying a new {@code CDEMO-LAST-MAP}, the {@code MOVE LIT-THISMAP TO CDEMO-LAST-MAP}
     * of {@code app/cbl/COCRDLIC.cbl:322} and its four sibling sites.
     *
     * @param newLastMap the map name, at most {@value #LAST_MAP_LENGTH} characters - seven, not eight; an
     *     eight-character value is rejected rather than silently shortened
     * @return a new instance; this one is unchanged
     * @throws NullPointerException if {@code newLastMap} is {@code null}
     * @throws IllegalArgumentException if it is longer than {@value #LAST_MAP_LENGTH}
     */
    public NavigationContext withLastMap(String newLastMap) {
        return new NavigationContext(fromTranid, fromProgram, toTranid, toProgram, userId,
                userType, pgmContext, custId, custFname, custMname, custLname, acctId, acctStatus,
                cardNum, newLastMap, lastMapset);
    }

    /**
     * Returns a copy carrying a new {@code CDEMO-LAST-MAPSET}, the
     * {@code MOVE LIT-THISMAPSET TO CDEMO-LAST-MAPSET} of {@code app/cbl/COCRDLIC.cbl:323} and its four
     * sibling sites.
     *
     * @param newLastMapset the mapset name, at most {@value #LAST_MAPSET_LENGTH} characters - seven, not
     *     eight
     * @return a new instance; this one is unchanged
     * @throws NullPointerException if {@code newLastMapset} is {@code null}
     * @throws IllegalArgumentException if it is longer than {@value #LAST_MAPSET_LENGTH}
     */
    public NavigationContext withLastMapset(String newLastMapset) {
        return new NavigationContext(fromTranid, fromProgram, toTranid, toProgram, userId,
                userType, pgmContext, custId, custFname, custMname, custLname, acctId, acctStatus,
                cardNum, lastMap, newLastMapset);
    }

    /**
     * Renders this context as the {@value #COMMAREA_LENGTH}-byte image of {@code 01 CARDDEMO-COMMAREA}, in
     * the code page of the supplied codec.
     *
     * @param codec the fixed-width codec, carrying the code page explicitly - {@code US-ASCII} for the text
     *     fixtures, {@code IBM037} for EBCDIC data
     * @return exactly {@value #COMMAREA_LENGTH} bytes, in the codec's code page
     * @throws NullPointerException if {@code codec} is {@code null}
     */
    public byte[] toFixedWidth(FixedWidthCodec codec) {
        Objects.requireNonNull(codec, "A FixedWidthCodec is required to render CARDDEMO-COMMAREA: the "
                + "code page of a fixed-width image must be stated explicitly and is never derived "
                + "from the platform");
        Map<String, String> images = new LinkedHashMap<>();
        images.put(FROM_TRANID_FIELD, fromTranid);
        images.put(FROM_PROGRAM_FIELD, fromProgram);
        images.put(TO_TRANID_FIELD, toTranid);
        images.put(TO_PROGRAM_FIELD, toProgram);
        images.put(USER_ID_FIELD, userId);
        images.put(USER_TYPE_FIELD, userType);
        images.put(PGM_CONTEXT_FIELD, Integer.toString(pgmContext));
        images.put(CUST_ID_FIELD, Integer.toString(custId));
        images.put(CUST_FNAME_FIELD, custFname);
        images.put(CUST_MNAME_FIELD, custMname);
        images.put(CUST_LNAME_FIELD, custLname);
        images.put(ACCT_ID_FIELD, Long.toString(acctId));
        images.put(ACCT_STATUS_FIELD, acctStatus);
        images.put(CARD_NUM_FIELD, Long.toString(cardNum));
        images.put(LAST_MAP_FIELD, lastMap);
        images.put(LAST_MAPSET_FIELD, lastMapset);
        return codec.serialise(LAYOUT, images);
    }

    /**
     * Reads a {@value #COMMAREA_LENGTH}-byte image of {@code 01 CARDDEMO-COMMAREA} back into a context.
     *
     * @param codec the fixed-width codec, carrying the code page explicitly
     * @param image exactly {@value #COMMAREA_LENGTH} bytes
     * @return the context the image denotes, never {@code null}
     * @throws NullPointerException if {@code codec} or {@code image} is {@code null}
     * @throws IllegalArgumentException if {@code image} is not exactly {@value #COMMAREA_LENGTH} bytes
     *     long, or a numeric span does not hold digits
     */
    public static NavigationContext fromFixedWidth(FixedWidthCodec codec, byte[] image) {
        Objects.requireNonNull(codec, "A FixedWidthCodec is required to read CARDDEMO-COMMAREA: the "
                + "code page of a fixed-width image must be stated explicitly and is never derived "
                + "from the platform");
        Objects.requireNonNull(image, "A " + COMMAREA_LENGTH + "-byte image is required to read "
                + "CARDDEMO-COMMAREA; call empty() for a freshly initialised communication area");
        Map<String, String> images = codec.deserialise(LAYOUT, image);
        return new NavigationContext(images.get(FROM_TRANID_FIELD),
                images.get(FROM_PROGRAM_FIELD),
                images.get(TO_TRANID_FIELD),
                images.get(TO_PROGRAM_FIELD),
                images.get(USER_ID_FIELD),
                images.get(USER_TYPE_FIELD),
                codec.decodePic9AsInt(images.get(PGM_CONTEXT_FIELD)),
                codec.decodePic9AsInt(images.get(CUST_ID_FIELD)),
                images.get(CUST_FNAME_FIELD),
                images.get(CUST_MNAME_FIELD),
                images.get(CUST_LNAME_FIELD),
                codec.decodePic9(images.get(ACCT_ID_FIELD)),
                images.get(ACCT_STATUS_FIELD),
                codec.decodePic9(images.get(CARD_NUM_FIELD)),
                images.get(LAST_MAP_FIELD),
                images.get(LAST_MAPSET_FIELD));
    }

    private static String spaces(int width) {
        return SPACE.repeat(width);
    }

    private static String requireWidth(String value, int declaredWidth, String cobolName) {
        Objects.requireNonNull(value, "Field " + cobolName + " requires a value; there is no null in a "
                + "COBOL record, so move SPACES explicitly or start from NavigationContext.empty()");
        if (value.length() > declaredWidth) {
            throw new IllegalArgumentException("Field " + cobolName + " is declared PIC X("
                    + declaredWidth + ") but was given " + value.length() + " character(s), value "
                    + REDACTED + ". CARDDEMO-COMMAREA is " + COMMAREA_LENGTH + " bytes and cannot "
                    + "hold the surplus. To shorten the value deliberately, pass it through "
                    + "FixedWidthCodec.movePicX(value, " + declaredWidth + "), which truncates on the "
                    + "right as a COBOL alphanumeric MOVE does");
        }
        return value;
    }

    private static long requireUnsignedDigits(long value, int declaredDigits, String cobolName) {
        if (value < 0) {
            throw new IllegalArgumentException("Field " + cobolName + " is declared PIC 9("
                    + declaredDigits + "), an unsigned picture with no sign position, so it cannot "
                    + "hold a negative value (" + REDACTED + "). A signed value belongs in a PIC S9 "
                    + "field");
        }
        String digits = Long.toString(value);
        if (digits.length() > declaredDigits) {
            throw new IllegalArgumentException("Field " + cobolName + " is declared PIC 9("
                    + declaredDigits + ") but the value given (" + REDACTED + ") needs "
                    + digits.length() + " digit(s). Storing it would silently drop the high-order "
                    + "digit(s); to do that deliberately, pass the value through "
                    + "FixedWidthCodec.movePic9(value, " + declaredDigits + ")");
        }
        return value;
    }

    private static int requireUnsignedDigits(int value, int declaredDigits, String cobolName) {
        return (int) requireUnsignedDigits((long) value, declaredDigits, cobolName);
    }

    /**
     * A diagnostic rendering that discloses the navigation state and withholds the cardholder data, per
     * {@link SensitiveDiagnostics}.
     *
     * @return a rendering safe to log, never {@code null}
     */
    @Override
    public String toString() {
        return "NavigationContext[fromTranid=" + fromTranid
                + ", fromProgram=" + fromProgram
                + ", toTranid=" + toTranid
                + ", toProgram=" + toProgram
                + ", userId=" + userId
                + ", userType=" + userType
                + ", pgmContext=" + pgmContext
                + ", custId=" + SensitiveDiagnostics.maskIdentifier(custId, CUST_ID_LENGTH)
                + ", custFname=" + SensitiveDiagnostics.describeText(custFname)
                + ", custMname=" + SensitiveDiagnostics.describeText(custMname)
                + ", custLname=" + SensitiveDiagnostics.describeText(custLname)
                + ", acctId=" + SensitiveDiagnostics.maskIdentifier(acctId, ACCT_ID_LENGTH)
                + ", acctStatus=" + acctStatus
                + ", cardNum=" + SensitiveDiagnostics.maskPan(cardNum, CARD_NUM_LENGTH)
                + ", lastMap=" + lastMap
                + ", lastMapset=" + lastMapset
                + ']';
    }

    /**
     * The {@value #CARD_NUM_LENGTH}-digit decimal image of {@link #cardNum()}, zero-filled on the left
     * exactly as {@code PIC 9(16)} stores it.
     *
     * @param cardNumber the value of {@code CDEMO-CARD-NUM}; must be zero or positive and at most
     *     {@value #CARD_NUM_LENGTH} digits
     * @return exactly {@value #CARD_NUM_LENGTH} decimal digits
     */
    public static String cardNumberImage(long cardNumber) {
        String digits = Long.toString(requireUnsignedDigits(cardNumber, CARD_NUM_LENGTH,
                CARD_NUM_FIELD));
        return ZERO.repeat(CARD_NUM_LENGTH - digits.length()) + digits;
    }

    /**
     * Reads a {@code CDEMO-CARD-NUM} wire value: a string of at most {@value #CARD_NUM_LENGTH} decimal
     * digits, with or without its leading zeros.
     *
     * @param image the digits as they arrived on the wire; must not be {@code null}
     * @return the value the digits denote
     * @throws IllegalArgumentException if {@code image} is empty, longer than {@value #CARD_NUM_LENGTH}
     *     characters, or holds anything other than {@code '0'} through {@code '9'} - a sign, a space, a
     *     separator or a decimal point included
     */
    public static long cardNumberOfImage(String image) {
        Objects.requireNonNull(image, "Field " + CARD_NUM_FIELD + " requires a value; send the "
                + CARD_NUM_LENGTH + "-digit string, or 0 for the initialised state");
        if (image.isEmpty() || image.length() > CARD_NUM_LENGTH) {
            throw new IllegalArgumentException("Field " + CARD_NUM_FIELD + " is declared PIC 9("
                    + CARD_NUM_LENGTH + ") and is carried as a decimal string of 1 to "
                    + CARD_NUM_LENGTH + " digits, but " + image.length() + " character(s) arrived "
                    + "(value " + REDACTED + ")");
        }
        for (int index = 0; index < image.length(); index++) {
            char character = image.charAt(index);
            if (character < '0' || character > '9') {
                throw new IllegalArgumentException("Field " + CARD_NUM_FIELD + " is declared PIC 9("
                        + CARD_NUM_LENGTH + ") and accepts only the digits 0 to 9, but the character "
                        + "at position " + (index + 1) + " is neither (value " + REDACTED + ")");
            }
        }
        return Long.parseLong(image);
    }

    /**
     * Writes {@code CDEMO-CARD-NUM} to JSON as a {@value NavigationContext#CARD_NUM_LENGTH}-digit decimal
     * string.
     *
     * <p>{@code CDEMO-CARD-NUM} is {@code PIC 9(16)} - {@code app/cpy/COCOM01Y.cpy} line 41 - so a
     * populated value has sixteen significant digits and can exceed 9,007,199,254,740,991, the largest
     * integer a IEEE-754 double can represent exactly.
     */
    public static final class CardNumberSerializer extends JsonSerializer<Long> {
        public CardNumberSerializer() {
        }

        /**
         * Writes the value as its {@value NavigationContext#CARD_NUM_LENGTH}-digit string.
         *
         * @param value the card number; never {@code null} for a primitive component
         * @param generator the JSON generator to write to
         * @param serializers the provider, unused
         * @throws IOException if the generator cannot be written to
         */
        @Override
        public void serialize(Long value, JsonGenerator generator, SerializerProvider serializers)
                throws IOException {
            generator.writeString(cardNumberImage(value));
        }
    }

    /**
     * Reads {@code CDEMO-CARD-NUM} from JSON, accepting only a string of decimal digits.
     */
    public static final class CardNumberDeserializer extends JsonDeserializer<Long> {
        public CardNumberDeserializer() {
        }

        /**
         * Reads the digits and returns the value they denote.
         *
         * @param parser the parser positioned on the value
         * @param context the deserialization context, unused
         * @return the card number
         * @throws IOException if the parser cannot be read
         * @throws IllegalArgumentException if the token is not a string, or the string is not 1 to
         *     {@value NavigationContext#CARD_NUM_LENGTH} decimal digits
         */
        @Override
        public Long deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.currentToken() != JsonToken.VALUE_STRING) {
                throw new IllegalArgumentException("Field " + CARD_NUM_FIELD + " is declared PIC 9("
                        + CARD_NUM_LENGTH + ") and must be sent as a decimal string of up to "
                        + CARD_NUM_LENGTH + " digits, because sixteen digits exceed the exact-integer "
                        + "range of a JSON number in many clients");
            }
            return cardNumberOfImage(parser.getText());
        }

        /**
         * The value an explicit JSON {@code null} reads as: {@code 0}, the initialised state of a
         * {@code PIC 9} field.
         *
         * @param context the deserialization context, unused
         * @return {@code 0}
         */
        @Override
        public Long getNullValue(DeserializationContext context) {
            return 0L;
        }
    }
}
