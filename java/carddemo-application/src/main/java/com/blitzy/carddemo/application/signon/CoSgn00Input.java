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
 * Entry-contract DTO record representing the INPUT side of the
 * {@code COSGN00} BMS map (symbolic map {@code COSGN0AI}).
 *
 * <p>This record translates the symbolic input view of the BMS map
 * definition at {@code app/bms/COSGN00.bms} and the symbolic copybook
 * at {@code app/cpy-bms/COSGN00.CPY} (group {@code 01 COSGN0AI},
 * lines L17-L84). It carries the user-typed fields (USERID, PASSWD)
 * plus the header/title positions that CICS RECEIVE MAP returns to
 * the program (most of which are {@code ASKIP}/{@code PROT} and echo
 * the previous SEND MAP values).
 *
 * <h2>Translation of {@code EXEC CICS RECEIVE MAP}</h2>
 * <p>The COBOL construct
 * {@code EXEC CICS RECEIVE MAP('COSGN0A') MAPSET('COSGN00')}
 * is translated to a Java method parameter of this type on
 * {@link CoSgn00C}. The signon program receives the user inputs via
 * this record and renders results via {@link CoSgn00Output}.
 *
 * <h2>Source artifact mapping</h2>
 * <p>For the INPUT side, the BMS symbolic copybook produces an
 * {@code -I} value field per BMS map position. Each input field is
 * preceded in the symbolic copybook by four BMS plumbing components
 * (length {@code -L}, flag {@code -F}, alias {@code -A REDEFINES F},
 * and a four-byte FILLER) that are <strong>not</strong> exposed at the
 * Java layer: they are CICS BMS internals handled by the BMS-to-Java
 * adapter that materialises this record from a 3270 RECEIVE-MAP
 * buffer.
 *
 * <p>This DTO captures the eleven {@code -I} value fields:
 * <ol>
 *   <li>{@link #trnName()}  &harr; {@code TRNNAMEI}  &mdash; 4 chars  (POS 1,8)</li>
 *   <li>{@link #title01()}  &harr; {@code TITLE01I}  &mdash; 40 chars (POS 1,21)</li>
 *   <li>{@link #curDate()}  &harr; {@code CURDATEI}  &mdash; 8 chars  (POS 1,71)</li>
 *   <li>{@link #pgmName()}  &harr; {@code PGMNAMEI}  &mdash; 8 chars  (POS 2,8)</li>
 *   <li>{@link #title02()}  &harr; {@code TITLE02I}  &mdash; 40 chars (POS 2,21)</li>
 *   <li>{@link #curTime()}  &harr; {@code CURTIMEI}  &mdash; 9 chars  (POS 2,71)</li>
 *   <li>{@link #applId()}   &harr; {@code APPLIDI}   &mdash; 8 chars  (POS 3,8)</li>
 *   <li>{@link #sysId()}    &harr; {@code SYSIDI}    &mdash; 8 chars  (POS 3,71)</li>
 *   <li>{@link #userId()}   &harr; {@code USERIDI}   &mdash; 8 chars  (POS 19,43) &mdash; user input, IC (initial cursor)</li>
 *   <li>{@link #passwd()}   &harr; {@code PASSWDI}   &mdash; 8 chars  (POS 20,43) &mdash; user input, DRK (non-display)</li>
 *   <li>{@link #errMsg()}   &harr; {@code ERRMSGI}   &mdash; 78 chars (POS 23,1)</li>
 * </ol>
 *
 * <h2>BMS LEN attribute is the source of truth</h2>
 * <p>The width constants exposed by this class match the
 * {@code LENGTH=n} attribute on the corresponding {@code DFHMDF}
 * macro in {@code app/bms/COSGN00.bms}. Notably,
 * {@link #TIME_WIDTH} is <strong>9</strong> (not 8) because the BMS
 * map declares {@code CURTIME LENGTH=9} with
 * {@code INITIAL='Ahh:mm:ss'} (the leading {@code 'A'} is part of the
 * compile-time INITIAL value).
 *
 * <h2>All eleven fields are present</h2>
 * <p>Even though {@code TRNNAME}, {@code TITLE01}, {@code CURDATE},
 * {@code PGMNAME}, {@code TITLE02}, {@code CURTIME}, {@code APPLID},
 * {@code SYSID}, and {@code ERRMSG} are {@code ASKIP}/{@code PROT}
 * (not user-modifiable), the BMS symbolic input map still provides an
 * {@code -I} value for each of them representing the bytes currently
 * in the screen buffer when RECEIVE MAP is executed. This Java
 * translation preserves that fidelity field-for-field per AAP
 * &sect;0.4.1.
 *
 * <h2>Non-null contract</h2>
 * <p>All eleven {@link String} components are <strong>required</strong>
 * (non-null). The compact canonical constructor (JEP 513 Flexible
 * Constructor Bodies) calls {@link Objects#requireNonNull(Object, String)}
 * for every component. Callers that have no map data yet (CICS first
 * entry, {@code EIBCALEN=0}) should construct via {@link #empty()},
 * which provides empty-string defaults.
 *
 * <h2>BMS-side trimming and padding</h2>
 * <p>BMS may return shorter strings than the declared width if the
 * user typed fewer characters; the BMS adapter is responsible for any
 * required padding to the declared {@code PIC X(n)} width. The DTO
 * does <strong>not</strong> enforce maximum lengths because doing so
 * would refuse legitimate short input. Trailing spaces are preserved
 * verbatim per the AAP &sect;0.7.1 byte-fidelity mandate.
 *
 * <h2>Plaintext password preservation</h2>
 * <p>Per AAP &sect;0.1.3, {@code SEC-USER-DATA} ({@code app/cpy/CSUSR01Y.cpy})
 * stores passwords as plain {@code PIC X(8)} text. This Java
 * translation preserves that behaviour: the {@link #passwd()}
 * component carries the cleartext value the user typed in the
 * {@code DRK}-attribute {@code PASSWDI} field. Introducing
 * BCrypt/Argon2 hashing is a separate effort flagged in
 * {@code MIGRATION_NOTES.md}.
 *
 * <h2>Immutability and concurrency</h2>
 * <p>Records, {@code final} components, no setters &mdash; safe to
 * share across threads including the virtual-thread workers per AAP
 * &sect;0.6.6. Use the {@link #withTrnName(String)}, ...,
 * {@link #withErrMsg(String)} copy methods for field-by-field
 * updates; Java records in finalized form do not have built-in
 * {@code with*} syntax.
 *
 * <h2>Non-goals (AAP &sect;0.7.4)</h2>
 * <ul>
 *   <li>No Spring, Hibernate, JPA, or Jakarta Bean Validation
 *       annotations.</li>
 *   <li>No Lombok &mdash; record components and accessors are
 *       compiler-generated.</li>
 *   <li>No {@code ThreadLocal}.</li>
 *   <li>No preview Java features (JEP 502 Stable Values, JEP 505
 *       Structured Concurrency, JEP 507 Primitive Patterns).</li>
 *   <li>No {@code import module} declaration &mdash; the explicit
 *       import set here is short and clearer.</li>
 *   <li>No mutable state, no setters.</li>
 *   <li>No {@code double}/{@code float}; no {@code java.util.Date};
 *       no {@code java.io.File}; no reflection.</li>
 * </ul>
 *
 * @param trnName  TRNNAMEI &mdash; 4-char transaction code echo (ASKIP,FSET,NORM)
 * @param title01  TITLE01I &mdash; 40-char line-1 title bar echo (ASKIP,FSET,NORM,YELLOW)
 * @param curDate  CURDATEI &mdash; 8-char date {@code "mm/dd/yy"} echo (ASKIP,FSET,NORM,BLUE)
 * @param pgmName  PGMNAMEI &mdash; 8-char program-id echo (FSET,NORM,PROT,BLUE)
 * @param title02  TITLE02I &mdash; 40-char line-2 title bar echo (ASKIP,FSET,NORM,YELLOW)
 * @param curTime  CURTIMEI &mdash; 9-char time {@code "Ahh:mm:ss"} echo (FSET,NORM,PROT,BLUE);
 *                 width is 9 because of the leading attribute byte in the BMS INITIAL value.
 * @param applId   APPLIDI  &mdash; 8-char CICS APPLID echo (FSET,NORM,PROT,BLUE)
 * @param sysId    SYSIDI   &mdash; 8-char CICS SYSID echo (FSET,NORM,PROT,BLUE)
 * @param userId   USERIDI  &mdash; 8-char user-id input (FSET,IC,NORM,UNPROT,GREEN,HILIGHT=OFF);
 *                 IC = Initial Cursor lands here on each SEND MAP.
 * @param passwd   PASSWDI  &mdash; 8-char password input (DRK,FSET,UNPROT,GREEN,HILIGHT=OFF);
 *                 DRK = dark/non-display; cleartext per AAP &sect;0.1.3 plaintext-preservation
 *                 mandate.
 * @param errMsg   ERRMSGI  &mdash; 78-char error message echo (ASKIP,BRT,FSET,RED)
 *
 * @see CoSgn00Output
 * @see CoSgn00C
 * @see <a href="https://www.ibm.com/docs/en/cics-ts">CICS/TS BMS reference</a>
 * @since 1.0.0
 */
@CobolProgram(
        value = "COSGN00",
        sourcePath = "app/cpy-bms/COSGN00.CPY",
        notes = "BMS input map COSGN0AI fields. Translated from BMS symbolic structure in "
                + "COSGN00.CPY and BMS map definition in COSGN00.bms. Driven by online "
                + "program app/cbl/COSGN00C.cbl (signon, transaction CC00). USRSEC password "
                + "is preserved as PIC X(8) plaintext per AAP §0.1.3; masked in toString() "
                + "per AAP §0.7.2 to prevent credential leakage into log surfaces."
)
public record CoSgn00Input(
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
        String errMsg
) {

    // ====================================================================
    // BMS field-width constants (LENGTH= attribute on the matching DFHMDF
    // macro in app/bms/COSGN00.bms). Exported public for use by the
    // application class, tests, and any future BMS-to-Java adapter that
    // needs to pad/truncate to the declared width.
    // ====================================================================

    /** BMS {@code LENGTH=4} for the {@code TRNNAME} field. */
    public static final int TRN_NAME_WIDTH = 4;

    /**
     * BMS {@code LENGTH=40} for the {@code TITLE01} and {@code TITLE02}
     * fields. Both titles share the same width; one constant suffices.
     */
    public static final int TITLE_WIDTH = 40;

    /** BMS {@code LENGTH=8} for the {@code CURDATE} field ({@code mm/dd/yy}). */
    public static final int DATE_WIDTH = 8;

    /** BMS {@code LENGTH=8} for the {@code PGMNAME} field. */
    public static final int PGM_NAME_WIDTH = 8;

    /**
     * BMS {@code LENGTH=9} for the {@code CURTIME} field. Width is 9
     * (not 8) because the BMS map declares
     * {@code INITIAL='Ahh:mm:ss'} &mdash; the leading {@code 'A'}
     * (attribute byte) is part of the compile-time initial value
     * carried on the wire.
     */
    public static final int TIME_WIDTH = 9;

    /** BMS {@code LENGTH=8} for the {@code APPLID} field. */
    public static final int APPL_ID_WIDTH = 8;

    /** BMS {@code LENGTH=8} for the {@code SYSID} field. */
    public static final int SYS_ID_WIDTH = 8;

    /** BMS {@code LENGTH=8} for the {@code USERID} field (user input). */
    public static final int USER_ID_WIDTH = 8;

    /**
     * BMS {@code LENGTH=8} for the {@code PASSWD} field (user input,
     * displayed dark per the {@code DRK} attribute).
     */
    public static final int PASSWD_WIDTH = 8;

    /** BMS {@code LENGTH=78} for the {@code ERRMSG} field. */
    public static final int ERRMSG_WIDTH = 78;

    // ====================================================================
    // Compact canonical constructor — JEP 513 Flexible Constructor Bodies
    // ====================================================================

    /**
     * Compact canonical constructor enforcing the non-null contract on
     * every component. Per JEP 513 (finalized in Java 25), validation
     * runs <strong>before</strong> the implicit canonical field
     * assignments &mdash; the right place for this style of input
     * validation per AAP &sect;0.6.7.
     *
     * <p>Maximum lengths are intentionally NOT enforced here: BMS may
     * legitimately return shorter strings if the user typed fewer
     * characters, and any padding/truncation is the responsibility of
     * the BMS-to-Java adapter layer.
     *
     * @throws NullPointerException if any of the eleven components is
     *                              {@code null}
     */
    public CoSgn00Input {
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
    }

    // ====================================================================
    // Static factory — empty()
    // ====================================================================

    /**
     * Returns an instance with all eleven components set to the empty
     * string. Used at first-time entry (COBOL {@code EIBCALEN=0})
     * before any BMS {@code RECEIVE MAP} has occurred, and by tests
     * that wish to populate only a few components via the
     * {@link #withTrnName(String) with*} copy methods.
     *
     * @return a fresh empty {@code CoSgn00Input} (never {@code null});
     *         every component equals {@code ""}
     */
    public static CoSgn00Input empty() {
        return new CoSgn00Input("", "", "", "", "", "", "", "", "", "", "");
    }

    // ====================================================================
    // with*() copy methods — one per component
    //
    // Records in finalized Java 25 do not have built-in `with` syntax;
    // these hand-written copy methods let callers update one component
    // at a time without rebuilding the full eleven-argument constructor
    // call site. Each returns a new record (records are immutable).
    // ====================================================================

    /**
     * Returns a copy of this record with {@link #trnName()} replaced.
     *
     * @param v the replacement value (must be non-null per
     *          {@link #CoSgn00Input(String, String, String, String, String, String, String, String, String, String, String) canonical constructor})
     * @return a new {@code CoSgn00Input} with the specified component changed
     */
    public CoSgn00Input withTrnName(String v) {
        return new CoSgn00Input(v, title01, curDate, pgmName, title02, curTime,
                applId, sysId, userId, passwd, errMsg);
    }

    /**
     * Returns a copy of this record with {@link #title01()} replaced.
     *
     * @param v the replacement value (must be non-null)
     * @return a new {@code CoSgn00Input} with the specified component changed
     */
    public CoSgn00Input withTitle01(String v) {
        return new CoSgn00Input(trnName, v, curDate, pgmName, title02, curTime,
                applId, sysId, userId, passwd, errMsg);
    }

    /**
     * Returns a copy of this record with {@link #curDate()} replaced.
     *
     * @param v the replacement value (must be non-null)
     * @return a new {@code CoSgn00Input} with the specified component changed
     */
    public CoSgn00Input withCurDate(String v) {
        return new CoSgn00Input(trnName, title01, v, pgmName, title02, curTime,
                applId, sysId, userId, passwd, errMsg);
    }

    /**
     * Returns a copy of this record with {@link #pgmName()} replaced.
     *
     * @param v the replacement value (must be non-null)
     * @return a new {@code CoSgn00Input} with the specified component changed
     */
    public CoSgn00Input withPgmName(String v) {
        return new CoSgn00Input(trnName, title01, curDate, v, title02, curTime,
                applId, sysId, userId, passwd, errMsg);
    }

    /**
     * Returns a copy of this record with {@link #title02()} replaced.
     *
     * @param v the replacement value (must be non-null)
     * @return a new {@code CoSgn00Input} with the specified component changed
     */
    public CoSgn00Input withTitle02(String v) {
        return new CoSgn00Input(trnName, title01, curDate, pgmName, v, curTime,
                applId, sysId, userId, passwd, errMsg);
    }

    /**
     * Returns a copy of this record with {@link #curTime()} replaced.
     *
     * @param v the replacement value (must be non-null)
     * @return a new {@code CoSgn00Input} with the specified component changed
     */
    public CoSgn00Input withCurTime(String v) {
        return new CoSgn00Input(trnName, title01, curDate, pgmName, title02, v,
                applId, sysId, userId, passwd, errMsg);
    }

    /**
     * Returns a copy of this record with {@link #applId()} replaced.
     *
     * @param v the replacement value (must be non-null)
     * @return a new {@code CoSgn00Input} with the specified component changed
     */
    public CoSgn00Input withApplId(String v) {
        return new CoSgn00Input(trnName, title01, curDate, pgmName, title02, curTime,
                v, sysId, userId, passwd, errMsg);
    }

    /**
     * Returns a copy of this record with {@link #sysId()} replaced.
     *
     * @param v the replacement value (must be non-null)
     * @return a new {@code CoSgn00Input} with the specified component changed
     */
    public CoSgn00Input withSysId(String v) {
        return new CoSgn00Input(trnName, title01, curDate, pgmName, title02, curTime,
                applId, v, userId, passwd, errMsg);
    }

    /**
     * Returns a copy of this record with {@link #userId()} replaced.
     *
     * @param v the replacement value (must be non-null)
     * @return a new {@code CoSgn00Input} with the specified component changed
     */
    public CoSgn00Input withUserId(String v) {
        return new CoSgn00Input(trnName, title01, curDate, pgmName, title02, curTime,
                applId, sysId, v, passwd, errMsg);
    }

    /**
     * Returns a copy of this record with {@link #passwd()} replaced.
     *
     * <p>Per AAP &sect;0.1.3, the password is stored as cleartext in
     * the {@code USRSEC} file and is therefore carried verbatim in
     * this DTO. Callers should avoid logging the returned record
     * via auto-generated string conversion.
     *
     * @param v the replacement value (must be non-null)
     * @return a new {@code CoSgn00Input} with the specified component changed
     */
    public CoSgn00Input withPasswd(String v) {
        return new CoSgn00Input(trnName, title01, curDate, pgmName, title02, curTime,
                applId, sysId, userId, v, errMsg);
    }

    /**
     * Returns a copy of this record with {@link #errMsg()} replaced.
     *
     * @param v the replacement value (must be non-null)
     * @return a new {@code CoSgn00Input} with the specified component changed
     */
    public CoSgn00Input withErrMsg(String v) {
        return new CoSgn00Input(trnName, title01, curDate, pgmName, title02, curTime,
                applId, sysId, userId, passwd, v);
    }

    // ====================================================================
    // Password-masking toString override (AAP §0.7.2 / §0.1.3)
    //
    // The default record-generated toString() emits every component,
    // including the cleartext PASSWD field. That cleartext value would
    // leak the user-typed credential into any sink that consumes
    // Object.toString(): SLF4J loggers (log.info("{}", input)), AssertJ
    // failure messages, IDE debugger displays, exception getMessage()
    // calls, and accidental System.out.println(this) statements.
    //
    // The override replaces the passwd component with the fixed
    // PASSWORD_MASK constant while emitting every other component in
    // its canonical form. The masking is purely a presentation/logging
    // concern: it does NOT alter the stored value, which remains
    // accessible via the canonical passwd() accessor for the
    // CoSgn00C credential-comparison path.
    //
    // This is the same defense-in-depth pattern applied to
    // SecUserData, CoUsr01Output, CoUsr01Input, and CoUsr02Input.
    // ====================================================================

    /**
     * Defense-in-depth mask used by {@link #toString()} so the cleartext
     * password the operator typed never leaks into logs, stack traces,
     * or debugger output. The mask preserves the COBOL/BMS field length
     * ({@value #PASSWD_WIDTH} characters) and uses the conventional
     * asterisk glyph.
     */
    private static final String PASSWORD_MASK = "********";

    /**
     * Returns a debug-friendly string representation of this DTO with the
     * plaintext password component <strong>masked</strong>. This override
     * is REQUIRED so that an accidental
     * {@code log.info("{}", input)} does NOT leak the cleartext password
     * the user typed into the {@code COSGN00} signon screen. Per AAP
     * &sect;0.7.2 (<em>"preserve all existing PCI-relevant controls"</em>)
     * and AAP &sect;0.1.3 (the plaintext-storage-but-no-cleartext-logging
     * separation of concerns), the password value MUST be replaced by a
     * non-reversible mask in every string-coerced representation of this
     * record.
     *
     * <p>The masking is purely a defense-in-depth measure; it does not
     * alter the stored password value, which remains accessible via
     * {@link #passwd()} for the {@code COSGN00C} credential-comparison
     * path.
     *
     * @return a credential-safe diagnostic string of the form
     *         {@code "CoSgn00Input[trnName=..., title01=..., curDate=...,
     *         pgmName=..., title02=..., curTime=..., applId=..., sysId=...,
     *         userId=..., passwd=********, errMsg=...]"}
     */
    @Override
    public String toString() {
        return "CoSgn00Input["
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
                + "]";
    }
}
