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

import com.aws.carddemo.entity.Customer;

import java.util.Objects;

/**
 * Java migration of the COBOL batch program {@code CBCUS01C.cbl} — read and
 * print the {@code CUSTDAT} VSAM KSDS customer-master file.
 *
 * <h2>Source of Truth</h2>
 *
 * <p>{@code app/cbl/CBCUS01C.cbl} reads every record from the
 * CUSTFILE-FILE sequentially and emits a formatted human-readable
 * DISPLAY block to SYSOUT for each. The customer record layout is
 * defined in {@code app/cpy/CUSTREC.cpy} / {@code CVCUS01Y.cpy}.
 *
 * <p>Relevant COBOL paragraphs:
 * <pre>
 *   PROCEDURE DIVISION
 *      0000-CUSTFILE-OPEN
 *      PERFORM UNTIL END-OF-FILE = 'Y'
 *          1000-CUSTFILE-GET-NEXT
 *              READ CUSTFILE-FILE INTO CUSTOMER-RECORD
 *              ON STATUS '00' -> 1100-DISPLAY-CUST-RECORD
 *              ON STATUS '10' -> END-OF-FILE = 'Y'
 *              ON OTHER       -> ABEND
 *          1100-DISPLAY-CUST-RECORD
 *              DISPLAY 'CUST-ID            :' CUST-ID
 *              DISPLAY 'CUST-FIRST-NAME    :' CUST-FIRST-NAME
 *              ... etc
 *      9000-CUSTFILE-CLOSE
 * </pre>
 *
 * <h2>Security Note</h2>
 *
 * <p>The COBOL record carries SSN, government-issued ID, FICO score,
 * and date-of-birth — sensitive PII. The COBOL DISPLAY echoes
 * everything; the Java {@link #format(Customer)} preserves this for
 * byte-equality parity. Production SYSOUT routing must redact PII;
 * test-time logger config under {@code logback-test.xml} masks PII
 * substrings in test loggers.
 *
 * @see com.aws.carddemo.entity.Customer
 * @see AccountFileProcessor
 */
public class CustomerFileProcessor {

    /** No-arg constructor. */
    public CustomerFileProcessor() {
        // No collaborators to inject.
    }

    /**
     * Process one {@link Customer} item — null in yields null out (EOF/skip).
     */
    public String process(Customer customer) {
        if (customer == null) {
            return null;
        }
        return format(customer);
    }

    /**
     * Format one {@link Customer} as a multi-line DISPLAY block — Java
     * equivalent of COBOL paragraph {@code 1100-DISPLAY-CUST-RECORD}.
     */
    public String format(Customer customer) {
        Objects.requireNonNull(customer, "customer must not be null");

        StringBuilder sb = new StringBuilder(512);
        sb.append("CUST-ID                :").append(nullSafe(customer.getCustomerId())).append('\n');
        sb.append("CUST-FIRST-NAME        :").append(nullSafe(customer.getFirstName())).append('\n');
        sb.append("CUST-MIDDLE-NAME       :").append(nullSafe(customer.getMiddleName())).append('\n');
        sb.append("CUST-LAST-NAME         :").append(nullSafe(customer.getLastName())).append('\n');
        sb.append("CUST-ADDR-LINE-1       :").append(nullSafe(customer.getAddressLine1())).append('\n');
        sb.append("CUST-ADDR-LINE-2       :").append(nullSafe(customer.getAddressLine2())).append('\n');
        sb.append("CUST-ADDR-LINE-3       :").append(nullSafe(customer.getAddressLine3())).append('\n');
        sb.append("CUST-ADDR-STATE-CD     :").append(nullSafe(customer.getAddressStateCode())).append('\n');
        sb.append("CUST-ADDR-COUNTRY-CD   :").append(nullSafe(customer.getAddressCountryCode())).append('\n');
        sb.append("CUST-ADDR-ZIP          :").append(nullSafe(customer.getAddressZip())).append('\n');
        sb.append("CUST-PHONE-NUM-1       :").append(nullSafe(customer.getPhoneNumber1())).append('\n');
        sb.append("CUST-PHONE-NUM-2       :").append(nullSafe(customer.getPhoneNumber2())).append('\n');
        sb.append("-------------------------------------------------");
        return sb.toString();
    }

    /** Increment a record counter. */
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
