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
package com.awsm2.carddemo.service;

import com.awsm2.carddemo.domain.Customer;
import com.awsm2.carddemo.repository.CustomerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Customer-file reader batch service &mdash; the Java target for the
 * COBOL batch program {@code app/cbl/CBCUS01C.cbl}.
 *
 * <p>This service implements the diagnostic scan of the
 * {@code CUSTFILE} VSAM cluster. The COBOL source opens the cluster,
 * sequentially reads every record, displays each field, and closes the
 * cluster.</p>
 *
 * <h2>Source provenance (AAP &sect;0.7.3)</h2>
 * <ul>
 *   <li><b>COBOL program:</b> {@code app/cbl/CBCUS01C.cbl} &mdash;
 *       diagnostic customer-file reader invoked by JCL job
 *       {@code app/jcl/READCUST.jcl}.</li>
 *   <li><b>Record layouts:</b> {@code app/cpy/CVCUS01Y.cpy} and
 *       {@code app/cpy/CUSTREC.cpy} ({@code CUSTOMER-RECORD},
 *       500 bytes; {@link Customer}).</li>
 *   <li><b>VSAM cluster:</b>
 *       {@code AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS}.</li>
 * </ul>
 *
 * <h2>COBOL paragraph translation</h2>
 * <table>
 *   <caption>CBCUS01C.cbl &harr; CustomerFileReaderService</caption>
 *   <tr><th>COBOL paragraph</th><th>Java equivalent</th></tr>
 *   <tr><td>{@code PROCEDURE DIVISION} main loop</td>
 *       <td>{@link #scan(Consumer)} / {@link #count()}</td></tr>
 *   <tr><td>{@code 0000-CUSTFILE-OPEN}</td>
 *       <td>Spring Data JPA implicit DB connection acquisition</td></tr>
 *   <tr><td>{@code 1000-CUSTFILE-GET-NEXT}</td>
 *       <td>{@link CustomerRepository#findAll(org.springframework.data.domain.Pageable)} (paged)</td></tr>
 *   <tr><td>{@code 1100-DISPLAY-CUST-RECORD}</td>
 *       <td>{@link #logCustomerRecord(Customer)} (SLF4J INFO)
 *       &mdash; SSN and phone numbers masked per PCI-DSS</td></tr>
 *   <tr><td>{@code 9000-CUSTFILE-CLOSE}</td>
 *       <td>(automatic by Spring)</td></tr>
 * </table>
 *
 * <h2>PII safety (AAP &sect;0.6.6)</h2>
 * <p>The COBOL source displays the full SSN, phone numbers, and
 * government ID; the Java target masks all of these in any log
 * emission. This is a deliberate, AAP-approved security upgrade.</p>
 */
@Service
public class CustomerFileReaderService {

    private static final Logger LOG =
            LoggerFactory.getLogger(CustomerFileReaderService.class);

    static final int PAGE_SIZE = 500;

    private final CustomerRepository customerRepository;

    public CustomerFileReaderService(CustomerRepository customerRepository) {
        this.customerRepository = Objects.requireNonNull(customerRepository,
                "customerRepository");
    }

    /**
     * @param recordsRead total number of {@link Customer} rows scanned
     */
    public record Result(long recordsRead) {
    }

    @Transactional(readOnly = true)
    public Result scan() {
        return scan(this::logCustomerRecord);
    }

    @Transactional(readOnly = true)
    public Result scan(Consumer<Customer> action) {
        Objects.requireNonNull(action, "action");
        LOG.info("CBCUS01C: START OF EXECUTION OF PROGRAM CBCUS01C");

        long total = 0L;
        int page = 0;
        Page<Customer> currentPage;
        do {
            currentPage = customerRepository.findAll(
                    PageRequest.of(page, PAGE_SIZE, Sort.by("custId")));
            for (Customer customer : currentPage.getContent()) {
                action.accept(customer);
                total++;
            }
            page++;
        } while (currentPage.hasNext());

        LOG.info("CBCUS01C: END OF EXECUTION OF PROGRAM CBCUS01C; recordsRead={}",
                total);
        return new Result(total);
    }

    @Transactional(readOnly = true)
    public long count() {
        return customerRepository.count();
    }

    /**
     * COBOL: 1100-DISPLAY-CUST-RECORD. SSN, phone numbers, and
     * government ID masked per PCI-DSS Req 3.3 &mdash; never emit full
     * values to logs.
     */
    void logCustomerRecord(Customer customer) {
        if (customer == null) {
            return;
        }
        if (LOG.isInfoEnabled()) {
            LOG.info("CUST-ID                 :{}", customer.getCustId());
            LOG.info("CUST-FIRST-NAME         :{}", customer.getCustFirstName());
            LOG.info("CUST-MIDDLE-NAME        :{}", customer.getCustMiddleName());
            LOG.info("CUST-LAST-NAME          :{}", customer.getCustLastName());
            LOG.info("CUST-ADDR-STATE-CD      :{}", customer.getCustAddrStateCd());
            LOG.info("CUST-ADDR-COUNTRY-CD    :{}", customer.getCustAddrCountryCd());
            LOG.info("CUST-ADDR-ZIP           :{}", customer.getCustAddrZip());
            LOG.info("CUST-PRI-CARD-HOLDER-IND:{}", customer.getCustPriCardHolderInd());
            LOG.info("CUST-FICO-CREDIT-SCORE  :{}", maskFico(customer.getCustFicoCreditScore()));
            // PII fields below are MASKED &mdash; never log full values
            LOG.info("CUST-SSN                :{}", maskSsn(customer.getCustSsn()));
            LOG.info("CUST-PHONE-NUM-1        :{}", maskPhone(customer.getCustPhoneNum1()));
            LOG.info("CUST-PHONE-NUM-2        :{}", maskPhone(customer.getCustPhoneNum2()));
            LOG.info("CUST-GOVT-ISSUED-ID     :{}",
                    customer.getCustGovtIssuedId() != null ? "***-MASKED***" : "(none)");
            LOG.info("-------------------------------------------------");
        }
    }

    private static String maskSsn(Long ssn) {
        if (ssn == null) {
            return "(none)";
        }
        String digits = String.format("%09d", ssn);
        return "***-**-" + digits.substring(digits.length() - 4);
    }

    private static String maskPhone(String phone) {
        if (phone == null || phone.length() < 4) {
            return "(none)";
        }
        return "***-***-" + phone.substring(phone.length() - 4);
    }

    private static String maskFico(Integer fico) {
        // FICO is sensitive but not strictly PII per PCI-DSS;
        // present only the broad band so debug logs do not leak
        // exact scores.
        if (fico == null) {
            return "(none)";
        }
        if (fico < 580) {
            return "<580";
        } else if (fico < 670) {
            return "580-669";
        } else if (fico < 740) {
            return "670-739";
        } else if (fico < 800) {
            return "740-799";
        } else {
            return "800+";
        }
    }
}
