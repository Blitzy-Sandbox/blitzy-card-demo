/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */
package com.blitzy.carddemo.domain.commarea;

import com.blitzy.carddemo.domain.annotation.CobolProgram;
import com.blitzy.carddemo.domain.status.PgmContext;
import com.blitzy.carddemo.domain.status.UserType;

/**
 * Domain record translated from the COBOL {@code CARDDEMO-COMMAREA} copybook
 * at {@code app/cpy/COCOM01Y.cpy}. The DFHCOMMAREA value flowing between
 * CICS transactions is rendered here as a Java record passed by value across
 * method calls. Per AAP &sect;0.6.10 the 88-level value-space taxonomies
 * ({@link UserType}, {@link PgmContext}) are referenced as sealed types so
 * dispatch sites are exhaustiveness-checked at compile time.
 *
 * <pre>{@code
 * 01 CARDDEMO-COMMAREA.
 *    05 CDEMO-GENERAL-INFO.
 *       10 CDEMO-FROM-TRANID    PIC X(04).
 *       10 CDEMO-FROM-PROGRAM   PIC X(08).
 *       10 CDEMO-TO-TRANID      PIC X(04).
 *       10 CDEMO-TO-PROGRAM     PIC X(08).
 *       10 CDEMO-USER-ID        PIC X(08).
 *       10 CDEMO-USER-TYPE      PIC X(01).  -> UserType sealed
 *       10 CDEMO-PGM-CONTEXT    PIC 9(01).  -> PgmContext sealed
 *    05 CDEMO-CUSTOMER-INFO.
 *       10 CDEMO-CUST-ID        PIC 9(09).
 *       10 CDEMO-CUST-FNAME     PIC X(25).
 *       10 CDEMO-CUST-MNAME     PIC X(25).
 *       10 CDEMO-CUST-LNAME     PIC X(25).
 *    05 CDEMO-ACCOUNT-INFO.
 *       10 CDEMO-ACCT-ID        PIC 9(11).
 *       10 CDEMO-ACCT-STATUS    PIC X(01).
 *    05 CDEMO-CARD-INFO.
 *       10 CDEMO-CARD-NUM       PIC 9(16).
 *    05 CDEMO-MORE-INFO.
 *       10 CDEMO-LAST-MAP       PIC X(7).
 *       10 CDEMO-LAST-MAPSET    PIC X(7).
 * }</pre>
 *
 * <p>The commarea is the canonical value object carried across XCTL/LINK
 * boundaries; Java translates those COBOL constructs to direct method calls
 * carrying this record (see AAP &sect;0.1.2).
 */
@CobolProgram(
        value = "COCOM01Y",
        sourcePath = "app/cpy/COCOM01Y.cpy",
        notes = "CARDDEMO-COMMAREA; uses sealed UserType + PgmContext per AAP §0.6.10"
)
public record CardDemoCommarea(
        GeneralInfo generalInfo,
        CustomerInfo customerInfo,
        AccountInfo accountInfo,
        CardInfo cardInfo,
        MoreInfo moreInfo) {

    /**
     * General routing information: who sent us here, who we are sending to,
     * who the user is, what context we are in.
     */
    public record GeneralInfo(
            String fromTranid,
            String fromProgram,
            String toTranid,
            String toProgram,
            String userId,
            UserType userType,
            PgmContext pgmContext) {

        public GeneralInfo {
            fromTranid = fromTranid == null ? "" : fromTranid;
            fromProgram = fromProgram == null ? "" : fromProgram;
            toTranid = toTranid == null ? "" : toTranid;
            toProgram = toProgram == null ? "" : toProgram;
            userId = userId == null ? "" : userId;
            // userType and pgmContext may be null (e.g., on first sign-on)
        }

        public static GeneralInfo empty() {
            return new GeneralInfo("", "", "", "", "", null, null);
        }
    }

    /** Customer demographic context. */
    public record CustomerInfo(long custId, String custFname, String custMname, String custLname) {
        public CustomerInfo {
            if (custId < 0L) {
                throw new IllegalArgumentException("custId must be non-negative");
            }
            custFname = custFname == null ? "" : custFname;
            custMname = custMname == null ? "" : custMname;
            custLname = custLname == null ? "" : custLname;
        }

        public static CustomerInfo empty() {
            return new CustomerInfo(0L, "", "", "");
        }
    }

    /** Account context (the account currently in focus). */
    public record AccountInfo(long acctId, String acctStatus) {
        public AccountInfo {
            if (acctId < 0L) {
                throw new IllegalArgumentException("acctId must be non-negative");
            }
            acctStatus = acctStatus == null ? "" : acctStatus;
        }

        public static AccountInfo empty() {
            return new AccountInfo(0L, "");
        }
    }

    /** Card context (the card currently in focus); PAN value passes through unredacted within the commarea. */
    public record CardInfo(long cardNum) {
        public CardInfo {
            if (cardNum < 0L) {
                throw new IllegalArgumentException("cardNum must be non-negative");
            }
        }

        public static CardInfo empty() {
            return new CardInfo(0L);
        }

        /** PAN masked to last 4 digits for logging. */
        public String maskedCardNum() {
            String full = String.format("%016d", cardNum);
            return "*".repeat(12) + full.substring(12);
        }
    }

    /** Last-map / last-mapset tracking for re-entry to the same screen. */
    public record MoreInfo(String lastMap, String lastMapset) {
        public MoreInfo {
            lastMap = lastMap == null ? "" : lastMap;
            lastMapset = lastMapset == null ? "" : lastMapset;
        }

        public static MoreInfo empty() {
            return new MoreInfo("", "");
        }
    }

    /** Returns an empty commarea — first invocation prior to any sign-on. */
    public static CardDemoCommarea empty() {
        return new CardDemoCommarea(
                GeneralInfo.empty(),
                CustomerInfo.empty(),
                AccountInfo.empty(),
                CardInfo.empty(),
                MoreInfo.empty());
    }

    /** Returns a new commarea with the supplied {@link GeneralInfo} substituted. */
    public CardDemoCommarea withGeneralInfo(GeneralInfo info) {
        return new CardDemoCommarea(info, customerInfo, accountInfo, cardInfo, moreInfo);
    }

    /** Returns a new commarea with the supplied {@link CustomerInfo} substituted. */
    public CardDemoCommarea withCustomerInfo(CustomerInfo info) {
        return new CardDemoCommarea(generalInfo, info, accountInfo, cardInfo, moreInfo);
    }

    /** Returns a new commarea with the supplied {@link AccountInfo} substituted. */
    public CardDemoCommarea withAccountInfo(AccountInfo info) {
        return new CardDemoCommarea(generalInfo, customerInfo, info, cardInfo, moreInfo);
    }

    /** Returns a new commarea with the supplied {@link CardInfo} substituted. */
    public CardDemoCommarea withCardInfo(CardInfo info) {
        return new CardDemoCommarea(generalInfo, customerInfo, accountInfo, info, moreInfo);
    }

    /** Returns a new commarea with the supplied {@link MoreInfo} substituted. */
    public CardDemoCommarea withMoreInfo(MoreInfo info) {
        return new CardDemoCommarea(generalInfo, customerInfo, accountInfo, cardInfo, info);
    }
}
