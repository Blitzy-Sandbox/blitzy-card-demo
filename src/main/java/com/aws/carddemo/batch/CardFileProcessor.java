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

import com.aws.carddemo.entity.Card;

import java.util.Objects;

/**
 * Java migration of the COBOL batch program {@code CBACT02C.cbl} — read and
 * print the {@code CARDDAT} VSAM KSDS card-master file.
 *
 * <h2>Source of Truth</h2>
 *
 * <p>{@code app/cbl/CBACT02C.cbl} reads every record from the CARDFILE-FILE
 * sequentially and emits a formatted human-readable display block to
 * SYSOUT for each. Like {@link AccountFileProcessor}, this is a read-only
 * batch utility: no validation, no mutation, no joins.
 *
 * <p>Relevant COBOL paragraphs:
 * <pre>
 *   PROCEDURE DIVISION
 *      0000-CARDFILE-OPEN
 *      PERFORM UNTIL END-OF-FILE = 'Y'
 *          1000-CARDFILE-GET-NEXT
 *              READ CARDFILE-FILE INTO CARD-RECORD
 *              ON STATUS '00' -> 1100-DISPLAY-CARD-RECORD
 *              ON STATUS '10' -> END-OF-FILE = 'Y'
 *              ON OTHER       -> ABEND
 *          1100-DISPLAY-CARD-RECORD
 *              DISPLAY 'CARD-NUM         :' CARD-NUM
 *              DISPLAY 'CARD-ACCT-ID     :' CARD-ACCT-ID
 *              DISPLAY 'CARD-CVV-CD      :' CARD-CVV-CD
 *              DISPLAY 'CARD-EMBOSSED    :' CARD-EMBOSSED-NAME
 *              DISPLAY 'CARD-EXPIRAION-DATE :' CARD-EXPIRAION-DATE
 *              DISPLAY 'CARD-ACTIVE-STATUS  :' CARD-ACTIVE-STATUS
 *      9000-CARDFILE-CLOSE
 * </pre>
 *
 * <h2>Java Migration Shape</h2>
 *
 * <p>Identical shape to {@link AccountFileProcessor}: a pure
 * {@link #format(Card)} that converts one {@link Card} into a multi-line
 * DISPLAY block; a {@link #process(Card)} that returns null for null
 * input (EOF/skip semantics); a {@link #countRecord(int)} that
 * implements {@code ADD 1 TO WS-RECORD-COUNT}.
 *
 * <h2>Security Note</h2>
 *
 * <p>{@link Card#getCardNumber()} is a Primary Account Number (PAN)
 * — sensitive PCI-DSS data. The COBOL DISPLAY of CARD-NUM in the
 * original program echoes the PAN in plaintext to SYSOUT; the Java
 * migration preserves this behaviour for byte-equality baseline
 * parity with the COBOL reference output. Production systems must
 * NEVER persist or log SYSOUT containing PAN values without proper
 * tokenisation; this responsibility belongs to the operational
 * environment (e.g., the SYSOUT routing in JES2 or the
 * {@code logback-test.xml} filter under
 * {@code src/test/resources/logback-test.xml} which masks PAN
 * substrings in test-time loggers).
 *
 * @see com.aws.carddemo.entity.Card
 * @see AccountFileProcessor
 */
public class CardFileProcessor {

    /** No-arg constructor — see {@link AccountFileProcessor} for the rationale. */
    public CardFileProcessor() {
        // No collaborators to inject.
    }

    /**
     * Process one {@link Card} item — null in yields null out
     * (EOF/skip); non-null in yields the formatted DISPLAY block.
     *
     * @param card the input card record from the reader, or {@code null} at EOF
     * @return the DISPLAY block for {@code card}, or {@code null} if {@code card} is null
     */
    public String process(Card card) {
        if (card == null) {
            return null;
        }
        return format(card);
    }

    /**
     * Format one {@link Card} as a multi-line DISPLAY block — Java
     * equivalent of COBOL paragraph {@code 1100-DISPLAY-CARD-RECORD}.
     *
     * @param card the card to format; must not be {@code null}
     * @return the multi-line DISPLAY block
     * @throws NullPointerException if {@code card} is {@code null}
     */
    public String format(Card card) {
        Objects.requireNonNull(card, "card must not be null");

        StringBuilder sb = new StringBuilder(256);
        sb.append("CARD-NUM            :").append(nullSafe(card.getCardNumber())).append('\n');
        sb.append("CARD-ACCT-ID        :").append(nullSafe(card.getAccountId())).append('\n');
        sb.append("CARD-CVV-CD         :").append(nullSafe(card.getCvvCode())).append('\n');
        sb.append("CARD-EMBOSSED-NAME  :").append(nullSafe(card.getEmbossedName())).append('\n');
        sb.append("CARD-EXPIRAION-DATE :").append(nullSafe(card.getExpirationDate())).append('\n');
        sb.append("CARD-ACTIVE-STATUS  :").append(nullSafe(card.getActiveStatus())).append('\n');
        sb.append("-------------------------------------------------");
        return sb.toString();
    }

    /**
     * Increment a record counter — Java equivalent of COBOL
     * {@code ADD 1 TO WS-RECORD-COUNT}.
     *
     * @param previousCount running total before this record; must be {@code >= 0}
     * @return {@code previousCount + 1}
     */
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
