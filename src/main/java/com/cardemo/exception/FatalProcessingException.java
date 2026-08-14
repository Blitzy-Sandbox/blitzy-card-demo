/*
 * ******************************************************************
 * Program     : FatalProcessingException.java
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 exception
 * Function    : Typed abend carrying the CABENDD.CPY work areas; batch abend code 999, return code 12.
 * Source      : app/cpy/CSMSG02Y.cpy (CABENDD.CPY, abend work areas) + app/cbl/CBTRN02C.cbl:L707-710 @ 7756d89
 * Source      : app/cbl/COACTUPC.cbl:L4203-L4228 @ 7756d89 - the online ABEND-ROUTINE and its
 *               default-message substitution.
 * Source      : app/cbl/CBTRN02C.cbl:L714-L727 @ 7756d89 - 9910-DISPLAY-IO-STATUS, the four character
 *               status render that always precedes the abend.
 * Source      : app/cbl/CBACT04C.cbl:L443-L460 @ 7756d89 - 1200-A-GET-DEFAULT-INT-RATE, the
 *               disclosure group retry whose missing DEFAULT row abends instead of
 *               reporting a not found record.
 * Source      : app/cbl/CBSTM03A.CBL:L71-L80,L736-L911 @ 7756d89 - the file service call contract whose
 *               secondary success status must never reach this type.
 * ******************************************************************
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
 * language governing permissions and limitations under the License
 * ******************************************************************
 */
package com.cardemo.exception;

/**
 * The typed abend: the terminal failure of the CardDemo exception hierarchy.
 *
 * <p>It carries the four abend work area fields of the frozen legacy corpus into Java, and it marks a failure
 * the legacy system did not recover from. Where the seven sibling subtypes each name a <em>recognised</em>
 * condition, this type names the residual one: a status, response or state that the source code had no branch
 * for and answered by terminating the unit of work. It is deliberately the last resort of the hierarchy.
 *
 * <p>It does not terminate anything. The legacy batch routine ends the task with {@code CALL 'CEE3ABD'}
 * ({@code app/cbl/CBTRN02C.cbl:L711}) and the legacy online routine ends it with
 * {@code EXEC CICS ABEND} ({@code app/cbl/COACTUPC.cbl:L4222-L4224}). Neither has a Java counterpart
 * inside this class.
 *
 * @see CardDemoException
 */
public class FatalProcessingException extends CardDemoException {

    /**
     * Fixed serialization identity, on the contract described on {@link CardDemoException}.
     */
    private static final long serialVersionUID = 1L;

    /**
     * The batch abend code, {@code 999}. Declared at {@code app/cbl/CBTRN02C.cbl:L710}.
     */
    public static final int BATCH_ABEND_CODE = 999;

    /**
     * The process return code an abend yields, {@code 12}. Declared at {@code app/cbl/CBTRN02C.cbl:L707-L711}.
     */
    public static final int BATCH_RETURN_CODE = 12;

    /**
     * The substituted default abend message, exactly {@code UNEXPECTED ABEND OCCURRED.} - including the
     * trailing full stop, which is part of the literal. Declared at {@code app/cbl/COACTUPC.cbl:L4206}.
     */
    public static final String DEFAULT_ABEND_MESSAGE = "UNEXPECTED ABEND OCCURRED.";

    /**
     * The four character abend code, from {@code ABEND-CODE PIC X(4)} at
     * {@code app/cpy/CSMSG02Y.cpy:L22}.
     */
    private final String abendCode;

    /**
     * The culprit component, from {@code ABEND-CULPRIT PIC X(8)} at {@code app/cpy/CSMSG02Y.cpy:L24}.
     */
    private final String abendCulprit;

    /**
     * The reason for the abend, from {@code ABEND-REASON PIC X(50)} at
     * {@code app/cpy/CSMSG02Y.cpy:L26}.
     */
    private final String abendReason;

    /**
     * The abend message, from {@code ABEND-MSG PIC X(72)} at {@code app/cpy/CSMSG02Y.cpy:L28}.
     */
    private final String abendMessage;

    /**
     * Creates a fatal abend reporting a condition, with no underlying throwable and no abend payload.
     *
     * @param abendMessage the abend message and the detail message, carrying {@code ABEND-MSG}.
     */
    public FatalProcessingException(String abendMessage) {
        super(substituteAbendMessage(abendMessage));
        this.abendCode = null;
        this.abendCulprit = null;
        this.abendReason = null;
        this.abendMessage = substituteAbendMessage(abendMessage);
    }

    /**
     * Creates a fatal abend reporting a condition and preserving the throwable that caused it.
     *
     * @param abendMessage the abend message and the detail message, carrying {@code ABEND-MSG}.
     * @param cause the underlying throwable, retrievable through {@link Throwable#getCause()}.
     */
    public FatalProcessingException(String abendMessage, Throwable cause) {
        super(substituteAbendMessage(abendMessage), cause);
        this.abendCode = null;
        this.abendCulprit = null;
        this.abendReason = null;
        this.abendMessage = substituteAbendMessage(abendMessage);
    }

    /**
     * Creates a fatal abend carrying the complete {@code CABENDD.CPY} payload.
     *
     * @param abendCode the four character display code, carrying {@code ABEND-CODE PIC X(4)}.
     * @param abendCulprit the component that raised the abend, carrying {@code ABEND-CULPRIT PIC X(8)}.
     * @param abendReason the reason, carrying {@code ABEND-REASON PIC X(50)}.
     * @param abendMessage the abend message and the detail message, carrying {@code ABEND-MSG PIC X(72)}.
     */
    public FatalProcessingException(String abendCode, String abendCulprit, String abendReason,
            String abendMessage) {
        super(substituteAbendMessage(abendMessage));
        this.abendCode = abendCode;
        this.abendCulprit = abendCulprit;
        this.abendReason = abendReason;
        this.abendMessage = substituteAbendMessage(abendMessage);
    }

    /**
     * Creates a fatal abend carrying the complete {@code CABENDD.CPY} payload and preserving the throwable that
     * caused it.
     *
     * @param abendCode the four character display code, carrying {@code ABEND-CODE PIC X(4)}.
     * @param abendCulprit the component that raised the abend, carrying {@code ABEND-CULPRIT PIC X(8)}.
     * @param abendReason the reason, carrying {@code ABEND-REASON PIC X(50)}.
     * @param abendMessage the abend message and the detail message, carrying {@code ABEND-MSG PIC X(72)}.
     * @param cause the underlying throwable, retrievable through {@link Throwable#getCause()}.
     */
    public FatalProcessingException(String abendCode, String abendCulprit, String abendReason,
            String abendMessage, Throwable cause) {
        super(substituteAbendMessage(abendMessage), cause);
        this.abendCode = abendCode;
        this.abendCulprit = abendCulprit;
        this.abendReason = abendReason;
        this.abendMessage = substituteAbendMessage(abendMessage);
    }

    /**
     * Applies the legacy default-message substitution.
     *
     * @param abendMessage the candidate message, which may be {@code null}, blank or populated
     * @return {@link #DEFAULT_ABEND_MESSAGE} when {@code abendMessage} is {@code null}.
     */
    private static String substituteAbendMessage(String abendMessage) {
        return abendMessage == null ? DEFAULT_ABEND_MESSAGE : abendMessage;
    }

    /**
     * Returns the four character abend code carried by {@code ABEND-CODE PIC X(4)}
     * ({@code app/cpy/CSMSG02Y.cpy:L22}).
     *
     * @return the abend code exactly as supplied, or {@code null} when it was not supplied - the normal case
     * for the batch path and for both message-only constructors
     */
    public String getAbendCode() {
        return abendCode;
    }

    /**
     * Returns the culprit component carried by {@code ABEND-CULPRIT PIC X(8)}
     * ({@code app/cpy/CSMSG02Y.cpy:L24}).
     *
     * @return the culprit exactly as supplied, or {@code null} when it was not supplied
     */
    public String getAbendCulprit() {
        return abendCulprit;
    }

    /**
     * Returns the reason carried by {@code ABEND-REASON PIC X(50)}
     * ({@code app/cpy/CSMSG02Y.cpy:L26}).
     *
     * @return the reason exactly as supplied, or {@code null} when it was not supplied.
     */
    public String getAbendReason() {
        return abendReason;
    }

    /**
     * Returns the abend message carried by {@code ABEND-MSG PIC X(72)}
     * ({@code app/cpy/CSMSG02Y.cpy:L28}), after the low-values substitution.
     *
     * @return the supplied message exactly as supplied when it was non-null, blank values included.
     */
    public String getAbendMessage() {
        return abendMessage;
    }
}
