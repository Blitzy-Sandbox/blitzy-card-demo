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

import com.awsm2.carddemo.domain.CardCrossReference;
import com.awsm2.carddemo.repository.CardCrossReferenceRepository;
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
 * Card cross-reference file reader batch service &mdash; the Java target
 * for the COBOL batch program {@code app/cbl/CBACT03C.cbl}.
 *
 * <p>This service implements the diagnostic scan of the
 * {@code CARDXREF} VSAM cluster. The COBOL source opens the cluster,
 * sequentially reads every record, displays each field, and closes the
 * cluster.</p>
 *
 * <h2>Source provenance (AAP &sect;0.7.3)</h2>
 * <ul>
 *   <li><b>COBOL program:</b> {@code app/cbl/CBACT03C.cbl} &mdash;
 *       diagnostic XREF reader invoked by JCL job
 *       {@code app/jcl/READXREF.jcl}.</li>
 *   <li><b>Record layout:</b> {@code app/cpy/CVACT03Y.cpy}
 *       ({@code CARD-XREF-RECORD}, 50 bytes;
 *       {@link CardCrossReference}).</li>
 *   <li><b>VSAM cluster:</b>
 *       {@code AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS}.</li>
 * </ul>
 *
 * <h2>COBOL paragraph translation</h2>
 * <table>
 *   <caption>CBACT03C.cbl &harr; XrefFileReaderService</caption>
 *   <tr><th>COBOL paragraph</th><th>Java equivalent</th></tr>
 *   <tr><td>{@code PROCEDURE DIVISION} main loop</td>
 *       <td>{@link #scan(Consumer)} / {@link #count()}</td></tr>
 *   <tr><td>{@code 0000-XREFFILE-OPEN}</td>
 *       <td>Spring Data JPA implicit DB connection acquisition</td></tr>
 *   <tr><td>{@code 1000-XREFFILE-GET-NEXT}</td>
 *       <td>{@link CardCrossReferenceRepository#findAll(org.springframework.data.domain.Pageable)} (paged)</td></tr>
 *   <tr><td>{@code 1100-DISPLAY-XREF-RECORD}</td>
 *       <td>{@link #logXrefRecord(CardCrossReference)} (SLF4J INFO)
 *       &mdash; PAN masked per PCI-DSS</td></tr>
 *   <tr><td>{@code 9000-XREFFILE-CLOSE}</td>
 *       <td>(automatic by Spring)</td></tr>
 * </table>
 *
 * <h2>PCI-DSS notes</h2>
 * <p>The COBOL XREF record contains the 16-byte {@code XREF-CARD-NUM}.
 * The Java target masks this to last-4 in all logs per PCI-DSS Req 3.3
 * (AAP &sect;0.7.2).</p>
 */
@Service
public class XrefFileReaderService {

    private static final Logger LOG =
            LoggerFactory.getLogger(XrefFileReaderService.class);

    static final int PAGE_SIZE = 500;

    private final CardCrossReferenceRepository xrefRepository;

    public XrefFileReaderService(CardCrossReferenceRepository xrefRepository) {
        this.xrefRepository = Objects.requireNonNull(xrefRepository,
                "xrefRepository");
    }

    /**
     * @param recordsRead total number of {@link CardCrossReference}
     *                    rows scanned
     */
    public record Result(long recordsRead) {
    }

    @Transactional(readOnly = true)
    public Result scan() {
        return scan(this::logXrefRecord);
    }

    @Transactional(readOnly = true)
    public Result scan(Consumer<CardCrossReference> action) {
        Objects.requireNonNull(action, "action");
        LOG.info("CBACT03C: START OF EXECUTION OF PROGRAM CBACT03C");

        long total = 0L;
        int page = 0;
        Page<CardCrossReference> currentPage;
        do {
            currentPage = xrefRepository.findAll(
                    PageRequest.of(page, PAGE_SIZE, Sort.by("xrefCardNum")));
            for (CardCrossReference xref : currentPage.getContent()) {
                action.accept(xref);
                total++;
            }
            page++;
        } while (currentPage.hasNext());

        LOG.info("CBACT03C: END OF EXECUTION OF PROGRAM CBACT03C; recordsRead={}",
                total);
        return new Result(total);
    }

    @Transactional(readOnly = true)
    public long count() {
        return xrefRepository.count();
    }

    /**
     * COBOL: 1100-DISPLAY-XREF-RECORD. PAN masked to last-4.
     */
    void logXrefRecord(CardCrossReference xref) {
        if (xref == null) {
            return;
        }
        if (LOG.isInfoEnabled()) {
            LOG.info("XREF-CARD-NUM           :{}", maskPan(xref.getXrefCardNum()));
            LOG.info("XREF-CUST-ID            :{}", xref.getXrefCustId());
            LOG.info("XREF-ACCT-ID            :{}", xref.getXrefAcctId());
            LOG.info("-------------------------------------------------");
        }
    }

    private static String maskPan(String pan) {
        if (pan == null || pan.length() < 4) {
            return "****";
        }
        return "****-****-****-" + pan.substring(pan.length() - 4);
    }
}
