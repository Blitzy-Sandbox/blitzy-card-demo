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
package com.aws.carddemo.batch;

import com.aws.carddemo.entity.CardXref;

import java.util.Objects;

/**
 * Java migration of the COBOL batch program {@code CBACT03C.cbl} — read and
 * print the {@code CARDXREF} VSAM KSDS card-cross-reference file.
 *
 * <h2>Source of Truth</h2>
 *
 * <p>{@code app/cbl/CBACT03C.cbl} reads every record from the XREF-FILE
 * sequentially and emits a formatted human-readable display block to
 * SYSOUT for each. The cross-reference file maps card numbers to
 * customer IDs and account IDs (per {@code app/cpy/CVACT03Y.cpy}).
 *
 * <p>Relevant COBOL paragraphs:
 * <pre>
 *   PROCEDURE DIVISION
 *      0000-XREFFILE-OPEN
 *      PERFORM UNTIL END-OF-FILE = 'Y'
 *          1000-XREFFILE-GET-NEXT
 *              READ XREF-FILE INTO CARD-XREF-RECORD
 *              ON STATUS '00' -> 1100-DISPLAY-XREF-RECORD
 *              ON STATUS '10' -> END-OF-FILE = 'Y'
 *              ON OTHER       -> ABEND
 *          1100-DISPLAY-XREF-RECORD
 *              DISPLAY 'XREF-CARD-NUM       :' XREF-CARD-NUM
 *              DISPLAY 'XREF-CUST-NUM       :' XREF-CUST-NUM
 *              DISPLAY 'XREF-ACCT-ID        :' XREF-ACCT-ID
 *      9000-XREFFILE-CLOSE
 * </pre>
 *
 * <h2>Java Migration Shape</h2>
 *
 * <p>Identical shape to {@link AccountFileProcessor} and
 * {@link CardFileProcessor}.
 *
 * @see com.aws.carddemo.entity.CardXref
 * @see AccountFileProcessor
 */
public class CardXrefFileProcessor {

    /** No-arg constructor. */
    public CardXrefFileProcessor() {
        // No collaborators to inject.
    }

    /**
     * Process one {@link CardXref} item — null in yields null out
     * (EOF/skip); non-null in yields the formatted DISPLAY block.
     */
    public String process(CardXref xref) {
        if (xref == null) {
            return null;
        }
        return format(xref);
    }

    /**
     * Format one {@link CardXref} as a multi-line DISPLAY block — Java
     * equivalent of COBOL paragraph {@code 1100-DISPLAY-XREF-RECORD}.
     */
    public String format(CardXref xref) {
        Objects.requireNonNull(xref, "xref must not be null");

        StringBuilder sb = new StringBuilder(192);
        sb.append("XREF-CARD-NUM       :").append(nullSafe(xref.getCardNumber())).append('\n');
        sb.append("XREF-CUST-NUM       :").append(nullSafe(xref.getCustomerId())).append('\n');
        sb.append("XREF-ACCT-ID        :").append(nullSafe(xref.getAccountId())).append('\n');
        sb.append("-------------------------------------------------");
        return sb.toString();
    }

    /** Increment a record counter — Java equivalent of {@code ADD 1 TO WS-RECORD-COUNT}. */
    public int countRecord(int previousCount) {
        if (previousCount < 0) {
            throw new IllegalArgumentException(
                    "previousCount must be non-negative; got " + previousCount);
        }
        return previousCount + 1;
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
