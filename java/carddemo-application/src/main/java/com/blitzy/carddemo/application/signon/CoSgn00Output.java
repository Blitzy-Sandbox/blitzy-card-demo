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
package com.blitzy.carddemo.application.signon;

import com.blitzy.carddemo.domain.annotation.CobolProgram;

import java.util.Objects;

/**
 * Entry-contract DTO record representing the OUTPUT side of the
 * {@code COSGN00} BMS map (symbolic map {@code COSGN0AO}).
 *
 * <p>This record translates the symbolic output view of the BMS map
 * definition at {@code app/bms/COSGN00.bms} and the symbolic copybook
 * at {@code app/cpy-bms/COSGN00.CPY} (group {@code 01 COSGN0AO REDEFINES
 * COSGN0AI}, lines L85-L152). It carries the values the signon program
 * writes to the screen via {@code EXEC CICS SEND MAP}, plus the
 * dynamic {@code ERRMSGC} color attribute byte controlled at runtime.
 *
 * <h2>Translation of {@code EXEC CICS SEND MAP}</h2>
 * <p>The COBOL construct
 * {@code EXEC CICS SEND MAP('COSGN0A') MAPSET('COSGN00')}
 * is translated to a Java method that returns an instance of this
 * record from {@link CoSgn00C}. The BMS adapter then renders the
 * instance to the 3270 wire (or its modern equivalent).
 *
 * <h2>Source artifact mapping</h2>
 * <p>For the OUTPUT side, the symbolic copybook redefines the input
 * area. Each input {@code -I} leaf becomes a quartet of three
 * attribute bytes ({@code -C} color, {@code -P} PS, {@code -H}
 * highlight, {@code -V} validation) followed by an output payload
 * {@code -O}. This DTO captures:
 * <ol>
 *   <li>All eleven output value fields ({@code -O} suffix) as
 *       {@link String}.</li>
 *   <li>One additional field, {@link #errMsgColor()}, representing the
 *       dynamically-settable {@code ERRMSGC} color attribute. Per
 *       {@code EXTATT=YES} on the BMS {@code DFHMSD}, attributes can
 *       be sent dynamically; this drives the field color (e.g.,
 *       {@code "R"} for RED on validation errors, blank for the BMS
 *       compile-time default).</li>
 * </ol>
 * <p>The {@code -C}, {@code -P}, {@code -H}, and {@code -V} attribute
 * bytes for the other fields are NOT captured because {@code COSGN00C}
 * does not dynamically modify them &mdash; they are statically defined
 * in the BMS map ({@code COLOR=YELLOW} for titles, {@code COLOR=BLUE}
 * for header labels, etc.). Only {@code ERRMSG} has its color modified
 * at runtime.
 *
 * <h2>Field inventory</h2>
 * <table border="1" summary="BMS-to-record component mapping">
 *   <thead>
 *     <tr><th>BMS field</th><th>Symbolic name</th><th>Width</th>
 *         <th>Record component</th></tr>
 *   </thead>
 *   <tbody>
 *     <tr><td>TRNNAME</td><td>TRNNAMEO</td><td>4</td>
 *         <td>{@link #trnName()}</td></tr>
 *     <tr><td>TITLE01</td><td>TITLE01O</td><td>40</td>
 *         <td>{@link #title01()}</td></tr>
 *     <tr><td>CURDATE</td><td>CURDATEO</td><td>8</td>
 *         <td>{@link #curDate()}</td></tr>
 *     <tr><td>PGMNAME</td><td>PGMNAMEO</td><td>8</td>
 *         <td>{@link #pgmName()}</td></tr>
 *     <tr><td>TITLE02</td><td>TITLE02O</td><td>40</td>
 *         <td>{@link #title02()}</td></tr>
 *     <tr><td>CURTIME</td><td>CURTIMEO</td><td>9</td>
 *         <td>{@link #curTime()}</td></tr>
 *     <tr><td>APPLID</td><td>APPLIDO</td><td>8</td>
 *         <td>{@link #applId()}</td></tr>
 *     <tr><td>SYSID</td><td>SYSIDO</td><td>8</td>
 *         <td>{@link #sysId()}</td></tr>
 *     <tr><td>USERID</td><td>USERIDO</td><td>8</td>
 *         <td>{@link #userId()}</td></tr>
 *     <tr><td>PASSWD</td><td>PASSWDO</td><td>8</td>
 *         <td>{@link #passwd()} &mdash; conventionally SPACES on SEND</td></tr>
 *     <tr><td>ERRMSG</td><td>ERRMSGO</td><td>78</td>
 *         <td>{@link #errMsg()}</td></tr>
 *     <tr><td>(attribute)</td><td>ERRMSGC</td><td>1</td>
 *         <td>{@link #errMsgColor()}</td></tr>
 *   </tbody>
 * </table>
 *
 * <h2>Password handling on the output side</h2>
 * <p>The BMS map declares {@code PASSWDO} with the {@code DRK}
 * (non-display) attribute so the 3270 controller suppresses it from
 * the rendered screen. The COBOL convention in {@code COSGN00C} is to
 * clear the field with {@code MOVE SPACES TO PASSWDO} on every
 * {@code SEND MAP} so that no cleartext is ever transmitted. Java
 * application code replicates that by sending an empty string via
 * {@link #passwd()} (typically {@code ""} or
 * {@link #withPasswd(String)} with blanks).
 *
 * <h2>Null-safety contract</h2>
 * <p>All twelve components are required (non-null). The compact
 * canonical constructor enforces this via
 * {@link Objects#requireNonNull(Object, String)}; passing
 * {@code null} for any component throws {@link NullPointerException}.
 * Use {@link #empty()} to construct a fully-blank instance, then mutate
 * via the {@code with*} methods to build up the rendered output
 * incrementally.
 *
 * <h2>Build-up pattern</h2>
 * <pre>{@code
 * CoSgn00Output out = CoSgn00Output.empty()
 *         .withTrnName("CC00")
 *         .withTitle01("AWS Mainframe Modernization")
 *         .withTitle02("CardDemo")
 *         .withCurDate("12/31/25")
 *         .withCurTime("23:59:59")
 *         .withApplId("APP00001")
 *         .withSysId("SYS00001")
 *         .withPgmName("COSGN00C")
 *         .withUserId("USER0001")
 *         .withErrMsg("Wrong Password. Try again ...")
 *         .withErrMsgColor("R");
 * }</pre>
 *
 * <h2>Immutability and concurrency</h2>
 * <p>Records, final components, no setters &mdash; safe to share
 * across threads including virtual-thread workers per AAP &sect;0.6.6.
 *
 * <h2>Non-goals (AAP &sect;0.7.4)</h2>
 * <p>No Spring, Hibernate/JPA, Lombok, or framework annotations.
 * No {@code ThreadLocal}, no preview features
 * (JEP 502/505/507/512), no {@code double}/{@code float},
 * no {@code java.util.Date}/{@code Calendar}, no
 * {@code java.io.File}, no reflection.
 *
 * @param trnName     TRNNAMEO &mdash; 4-char transaction code echo
 * @param title01     TITLE01O &mdash; 40-char line-1 title bar
 * @param curDate     CURDATEO &mdash; 8-char date {@code "MM/DD/YY"}
 * @param pgmName     PGMNAMEO &mdash; 8-char program-id echo
 * @param title02     TITLE02O &mdash; 40-char line-2 title bar
 * @param curTime     CURTIMEO &mdash; 9-char time {@code "HH:MM:SS"}
 *                    (BMS {@code INITIAL='Ahh:mm:ss'} reserves one
 *                    leading byte; the modern Java translation
 *                    surfaces 9 characters total)
 * @param applId      APPLIDO &mdash; 8-char CICS APPLID echo
 * @param sysId       SYSIDO &mdash; 8-char CICS SYSID echo
 * @param userId      USERIDO &mdash; 8-char user-id echo
 * @param passwd      PASSWDO &mdash; 8-char password field
 *                    (conventionally SPACES on SEND because the BMS
 *                    {@code DRK} attribute makes the wire
 *                    representation invisible regardless)
 * @param errMsg      ERRMSGO &mdash; 78-char error message
 * @param errMsgColor ERRMSGC &mdash; 1-byte color attribute override
 *                    (e.g., {@code "R"} for RED, {@code "G"} for
 *                    GREEN, {@code ""} for the BMS compile-time
 *                    default)
 *
 * @see CoSgn00Input
 * @see CoSgn00C
 * @since 1.0.0
 */
@CobolProgram(
        value = "COSGN00",
        sourcePath = "app/cpy-bms/COSGN00.CPY",
        notes = "BMS output map COSGN0AO fields plus dynamic ERRMSG color attribute "
                + "(ERRMSGC). Translated from BMS symbolic structure in COSGN00.CPY "
                + "and BMS map definition in COSGN00.bms. PASSWD component (BMS DRK "
                + "attribute, conventionally SPACES on SEND) is masked in toString() "
                + "per AAP §0.7.2 to prevent credential leakage into log surfaces."
)
public record CoSgn00Output(
        String trnName,
        String title01,
        String curDate,
        String pgmName,
        String title02,
        String curTime,
        String applId,
        String sysId,
        String userId,
        String passwd,
        String errMsg,
        String errMsgColor
) {

    // ====================================================================
    // BMS field width constants (per app/bms/COSGN00.bms LEN= attributes
    // and app/cpy-bms/COSGN00.CPY PIC X(n) widths)
    // ====================================================================

    /** BMS {@code LEN=4} for {@code TRNNAME} field. */
    public static final int TRN_NAME_WIDTH = 4;

    /** BMS {@code LEN=40} for {@code TITLE01} and {@code TITLE02} fields. */
    public static final int TITLE_WIDTH = 40;

    /** BMS {@code LEN=8} for {@code CURDATE} field (MM/DD/YY format). */
    public static final int DATE_WIDTH = 8;

    /** BMS {@code LEN=8} for {@code PGMNAME} field. */
    public static final int PGM_NAME_WIDTH = 8;

    /**
     * BMS {@code LEN=9} for {@code CURTIME} field
     * (HH:MM:SS with leading 'A' attribute byte per
     * {@code INITIAL='Ahh:mm:ss'}).
     */
    public static final int TIME_WIDTH = 9;

    /** BMS {@code LEN=8} for {@code APPLID} field. */
    public static final int APPL_ID_WIDTH = 8;

    /** BMS {@code LEN=8} for {@code SYSID} field. */
    public static final int SYS_ID_WIDTH = 8;

    /** BMS {@code LEN=8} for {@code USERID} field. */
    public static final int USER_ID_WIDTH = 8;

    /** BMS {@code LEN=8} for {@code PASSWD} field. */
    public static final int PASSWD_WIDTH = 8;

    /** BMS {@code LEN=78} for {@code ERRMSG} field. */
    public static final int ERRMSG_WIDTH = 78;

    /**
     * Width of the BMS color attribute byte ({@code ERRMSGC}).
     * Per {@code EXTATT=YES} on the {@code DFHMSD}, the program may
     * dynamically override the field color via this 1-byte attribute.
     */
    public static final int COLOR_ATTR_WIDTH = 1;

    /**
     * Compact canonical constructor enforcing the non-null contract on
     * every component (JEP 513 Flexible Constructor Bodies pattern).
     *
     * <p>The validation runs before the canonical field assignments and
     * throws {@link NullPointerException} for the first {@code null}
     * component encountered. Callers must supply non-null strings;
     * use {@link #empty()} as a baseline and the {@code with*} methods
     * to populate fields incrementally.
     *
     * @throws NullPointerException if any of the twelve components is
     *                              {@code null}
     */
    public CoSgn00Output {
        Objects.requireNonNull(trnName, "trnName");
        Objects.requireNonNull(title01, "title01");
        Objects.requireNonNull(curDate, "curDate");
        Objects.requireNonNull(pgmName, "pgmName");
        Objects.requireNonNull(title02, "title02");
        Objects.requireNonNull(curTime, "curTime");
        Objects.requireNonNull(applId, "applId");
        Objects.requireNonNull(sysId, "sysId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(passwd, "passwd");
        Objects.requireNonNull(errMsg, "errMsg");
        Objects.requireNonNull(errMsgColor, "errMsgColor");
    }

    // ====================================================================
    // Factory methods
    // ====================================================================

    /**
     * Returns an instance with all twelve fields set to empty strings
     * ({@code ""}).
     *
     * <p>Used as a starting point that the application class can mutate
     * via the {@code with*} methods to build up the rendered
     * {@code SEND MAP} output. This mirrors the COBOL pattern of
     * {@code INITIALIZE COSGN0AO} followed by individual
     * {@code MOVE} statements that populate each field.
     *
     * @return an Output record with all twelve fields equal to
     *         {@code ""} (never {@code null})
     */
    public static CoSgn00Output empty() {
        return new CoSgn00Output(
                "", "", "", "", "", "",
                "", "", "", "", "", ""
        );
    }

    // ====================================================================
    // Wither methods (one per component, in declaration order)
    // ====================================================================

    /**
     * Returns a copy of this record with {@link #trnName()} replaced.
     *
     * @param v the new value for the {@code trnName} component (must be
     *          non-null; pass {@code ""} to clear the field)
     * @return a new {@code CoSgn00Output} with all other fields
     *         unchanged
     * @throws NullPointerException if {@code v} is {@code null}
     */
    public CoSgn00Output withTrnName(String v) {
        return new CoSgn00Output(v, title01, curDate, pgmName, title02, curTime,
                applId, sysId, userId, passwd, errMsg, errMsgColor);
    }

    /**
     * Returns a copy of this record with {@link #title01()} replaced.
     *
     * @param v the new value for the {@code title01} component (must be
     *          non-null; pass {@code ""} to clear the field)
     * @return a new {@code CoSgn00Output} with all other fields
     *         unchanged
     * @throws NullPointerException if {@code v} is {@code null}
     */
    public CoSgn00Output withTitle01(String v) {
        return new CoSgn00Output(trnName, v, curDate, pgmName, title02, curTime,
                applId, sysId, userId, passwd, errMsg, errMsgColor);
    }

    /**
     * Returns a copy of this record with {@link #curDate()} replaced.
     *
     * @param v the new value for the {@code curDate} component (must be
     *          non-null; pass {@code ""} to clear the field)
     * @return a new {@code CoSgn00Output} with all other fields
     *         unchanged
     * @throws NullPointerException if {@code v} is {@code null}
     */
    public CoSgn00Output withCurDate(String v) {
        return new CoSgn00Output(trnName, title01, v, pgmName, title02, curTime,
                applId, sysId, userId, passwd, errMsg, errMsgColor);
    }

    /**
     * Returns a copy of this record with {@link #pgmName()} replaced.
     *
     * @param v the new value for the {@code pgmName} component (must be
     *          non-null; pass {@code ""} to clear the field)
     * @return a new {@code CoSgn00Output} with all other fields
     *         unchanged
     * @throws NullPointerException if {@code v} is {@code null}
     */
    public CoSgn00Output withPgmName(String v) {
        return new CoSgn00Output(trnName, title01, curDate, v, title02, curTime,
                applId, sysId, userId, passwd, errMsg, errMsgColor);
    }

    /**
     * Returns a copy of this record with {@link #title02()} replaced.
     *
     * @param v the new value for the {@code title02} component (must be
     *          non-null; pass {@code ""} to clear the field)
     * @return a new {@code CoSgn00Output} with all other fields
     *         unchanged
     * @throws NullPointerException if {@code v} is {@code null}
     */
    public CoSgn00Output withTitle02(String v) {
        return new CoSgn00Output(trnName, title01, curDate, pgmName, v, curTime,
                applId, sysId, userId, passwd, errMsg, errMsgColor);
    }

    /**
     * Returns a copy of this record with {@link #curTime()} replaced.
     *
     * @param v the new value for the {@code curTime} component (must be
     *          non-null; pass {@code ""} to clear the field)
     * @return a new {@code CoSgn00Output} with all other fields
     *         unchanged
     * @throws NullPointerException if {@code v} is {@code null}
     */
    public CoSgn00Output withCurTime(String v) {
        return new CoSgn00Output(trnName, title01, curDate, pgmName, title02, v,
                applId, sysId, userId, passwd, errMsg, errMsgColor);
    }

    /**
     * Returns a copy of this record with {@link #applId()} replaced.
     *
     * @param v the new value for the {@code applId} component (must be
     *          non-null; pass {@code ""} to clear the field)
     * @return a new {@code CoSgn00Output} with all other fields
     *         unchanged
     * @throws NullPointerException if {@code v} is {@code null}
     */
    public CoSgn00Output withApplId(String v) {
        return new CoSgn00Output(trnName, title01, curDate, pgmName, title02, curTime,
                v, sysId, userId, passwd, errMsg, errMsgColor);
    }

    /**
     * Returns a copy of this record with {@link #sysId()} replaced.
     *
     * @param v the new value for the {@code sysId} component (must be
     *          non-null; pass {@code ""} to clear the field)
     * @return a new {@code CoSgn00Output} with all other fields
     *         unchanged
     * @throws NullPointerException if {@code v} is {@code null}
     */
    public CoSgn00Output withSysId(String v) {
        return new CoSgn00Output(trnName, title01, curDate, pgmName, title02, curTime,
                applId, v, userId, passwd, errMsg, errMsgColor);
    }

    /**
     * Returns a copy of this record with {@link #userId()} replaced.
     *
     * @param v the new value for the {@code userId} component (must be
     *          non-null; pass {@code ""} to clear the field)
     * @return a new {@code CoSgn00Output} with all other fields
     *         unchanged
     * @throws NullPointerException if {@code v} is {@code null}
     */
    public CoSgn00Output withUserId(String v) {
        return new CoSgn00Output(trnName, title01, curDate, pgmName, title02, curTime,
                applId, sysId, v, passwd, errMsg, errMsgColor);
    }

    /**
     * Returns a copy of this record with {@link #passwd()} replaced.
     *
     * <p>The BMS map declares {@code PASSWDO} with the {@code DRK}
     * attribute (non-display). The COBOL convention is to send SPACES
     * via this field so that no cleartext is ever transmitted on the
     * wire; the Java translation typically uses {@code ""}.
     *
     * @param v the new value for the {@code passwd} component (must be
     *          non-null; pass {@code ""} to clear the field)
     * @return a new {@code CoSgn00Output} with all other fields
     *         unchanged
     * @throws NullPointerException if {@code v} is {@code null}
     */
    public CoSgn00Output withPasswd(String v) {
        return new CoSgn00Output(trnName, title01, curDate, pgmName, title02, curTime,
                applId, sysId, userId, v, errMsg, errMsgColor);
    }

    /**
     * Returns a copy of this record with {@link #errMsg()} replaced.
     *
     * @param v the new value for the {@code errMsg} component (must be
     *          non-null; pass {@code ""} to clear the field)
     * @return a new {@code CoSgn00Output} with all other fields
     *         unchanged
     * @throws NullPointerException if {@code v} is {@code null}
     */
    public CoSgn00Output withErrMsg(String v) {
        return new CoSgn00Output(trnName, title01, curDate, pgmName, title02, curTime,
                applId, sysId, userId, passwd, v, errMsgColor);
    }

    /**
     * Returns a copy of this record with {@link #errMsgColor()}
     * replaced.
     *
     * <p>Per {@code EXTATT=YES} on the BMS {@code DFHMSD}, the program
     * may dynamically override the {@code ERRMSG} color through this
     * 1-byte attribute. Common values: {@code "R"} for RED (used for
     * validation/auth errors), {@code "G"} for GREEN (used for
     * informational/success messages), {@code ""} to inherit the BMS
     * compile-time default.
     *
     * @param v the new value for the {@code errMsgColor} component
     *          (must be non-null; pass {@code ""} to clear the
     *          override)
     * @return a new {@code CoSgn00Output} with all other fields
     *         unchanged
     * @throws NullPointerException if {@code v} is {@code null}
     */
    public CoSgn00Output withErrMsgColor(String v) {
        return new CoSgn00Output(trnName, title01, curDate, pgmName, title02, curTime,
                applId, sysId, userId, passwd, errMsg, v);
    }

    // ====================================================================
    // Password-masking toString override (AAP §0.7.2 / §0.1.3)
    //
    // The default record-generated toString() emits every component,
    // including the cleartext PASSWD field. Although the BMS DRK attribute
    // means the value is never displayed on screen and SEND MAP
    // conventionally sets it to SPACES, the in-memory Java DTO can carry
    // an arbitrary string value at any point in the request cycle (e.g.,
    // when this output record is constructed by withPasswd(...) before
    // the BMS DRK semantics scrub it). Any sink that consumes
    // Object.toString() — SLF4J loggers, AssertJ failure messages, IDE
    // debugger displays, exception getMessage() calls, accidental
    // System.out.println(this) — would leak the in-memory password.
    //
    // The override replaces the passwd component with the fixed
    // PASSWORD_MASK constant while emitting every other component in
    // its canonical form. The masking is purely a presentation/logging
    // concern: it does NOT alter the stored value, which remains
    // accessible via the canonical passwd() accessor.
    //
    // This is the same defense-in-depth pattern applied to
    // SecUserData, CoUsr01Output, CoUsr01Input, CoUsr02Input, and
    // CoSgn00Input.
    // ====================================================================

    /**
     * Defense-in-depth mask used by {@link #toString()} so the password
     * field never leaks into logs, stack traces, or debugger output.
     * The mask preserves the COBOL/BMS field length ({@value #PASSWD_WIDTH}
     * characters) and uses the conventional asterisk glyph.
     */
    private static final String PASSWORD_MASK = "********";

    /**
     * Returns a debug-friendly string representation of this DTO with the
     * plaintext password component <strong>masked</strong>. This override
     * is REQUIRED so that an accidental
     * {@code log.info("{}", output)} does NOT leak whatever cleartext
     * password value is carried in memory (even though BMS DRK and the
     * SEND-MAP convention typically force this field to SPACES on the
     * wire). Per AAP &sect;0.7.2 (<em>"preserve all existing PCI-relevant
     * controls"</em>) and AAP &sect;0.1.3 (the plaintext-storage-but-no-
     * cleartext-logging separation of concerns), the password value MUST
     * be replaced by a non-reversible mask in every string-coerced
     * representation of this record.
     *
     * <p>The masking is purely a defense-in-depth measure; it does not
     * alter the stored password value, which remains accessible via
     * {@link #passwd()}.
     *
     * @return a credential-safe diagnostic string of the form
     *         {@code "CoSgn00Output[trnName=..., title01=..., curDate=...,
     *         pgmName=..., title02=..., curTime=..., applId=..., sysId=...,
     *         userId=..., passwd=********, errMsg=..., errMsgColor=...]"}
     */
    @Override
    public String toString() {
        return "CoSgn00Output["
                + "trnName=" + trnName
                + ", title01=" + title01
                + ", curDate=" + curDate
                + ", pgmName=" + pgmName
                + ", title02=" + title02
                + ", curTime=" + curTime
                + ", applId=" + applId
                + ", sysId=" + sysId
                + ", userId=" + userId
                + ", passwd=" + PASSWORD_MASK
                + ", errMsg=" + errMsg
                + ", errMsgColor=" + errMsgColor
                + "]";
    }
}
