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

import com.awsm2.carddemo.domain.Card;
import com.awsm2.carddemo.repository.CardRepository;
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
 * Card-file reader batch service &mdash; the Java target for the COBOL
 * batch program {@code app/cbl/CBACT02C.cbl}.
 *
 * <p>This service implements the diagnostic scan of the
 * {@code CARDFILE} VSAM cluster. The COBOL source opens the cluster,
 * sequentially reads every record, displays each field via
 * {@code DISPLAY} statements (paragraph
 * {@code 1100-DISPLAY-CARD-RECORD}), and closes the cluster.</p>
 *
 * <h2>Source provenance (AAP &sect;0.7.3)</h2>
 * <ul>
 *   <li><b>COBOL program:</b> {@code app/cbl/CBACT02C.cbl} &mdash;
 *       diagnostic card-file reader invoked by JCL job
 *       {@code app/jcl/READCARD.jcl}.</li>
 *   <li><b>Record layout:</b> {@code app/cpy/CVACT02Y.cpy}
 *       ({@code CARD-RECORD}, 150 bytes; {@link Card}).</li>
 *   <li><b>VSAM cluster:</b>
 *       {@code AWS.M2.CARDDEMO.CARDDATA.VSAM.KSDS}.</li>
 * </ul>
 *
 * <h2>COBOL paragraph translation</h2>
 * <table>
 *   <caption>CBACT02C.cbl &harr; CardFileReaderService</caption>
 *   <tr><th>COBOL paragraph</th><th>Java equivalent</th></tr>
 *   <tr><td>{@code PROCEDURE DIVISION} main loop</td>
 *       <td>{@link #scan(Consumer)} / {@link #count()}</td></tr>
 *   <tr><td>{@code 0000-CARDFILE-OPEN}</td>
 *       <td>Spring Data JPA implicit DB connection acquisition</td></tr>
 *   <tr><td>{@code 1000-CARDFILE-GET-NEXT}</td>
 *       <td>{@link CardRepository#findAll(org.springframework.data.domain.Pageable)} (paged)</td></tr>
 *   <tr><td>{@code 1100-DISPLAY-CARD-RECORD}</td>
 *       <td>{@link #logCardRecord(Card)} (SLF4J INFO) &mdash; PAN
 *       masked per PCI-DSS Req 3.3 (last-4 only)</td></tr>
 *   <tr><td>{@code 9000-CARDFILE-CLOSE}</td>
 *       <td>(automatic by Spring) &mdash; JDBC connection returned to
 *       pool</td></tr>
 * </table>
 *
 * <h2>PCI-DSS notes</h2>
 * <p>The COBOL source displays the full 16-digit PAN
 * ({@code CARD-NUM}); the Java target <strong>never</strong> emits
 * the full PAN to logs &mdash; it always masks to last-4 per
 * {@link Card#toString()} (AAP &sect;0.6.6, &sect;0.7.2 PCI-DSS
 * compliance). This is a deliberate, AAP-approved security upgrade.</p>
 */
@Service
public class CardFileReaderService {

    private static final Logger LOG =
            LoggerFactory.getLogger(CardFileReaderService.class);

    static final int PAGE_SIZE = 500;

    private final CardRepository cardRepository;

    public CardFileReaderService(CardRepository cardRepository) {
        this.cardRepository = Objects.requireNonNull(cardRepository,
                "cardRepository");
    }

    /**
     * @param recordsRead total number of {@link Card} rows scanned
     */
    public record Result(long recordsRead) {
    }

    @Transactional(readOnly = true)
    public Result scan() {
        return scan(this::logCardRecord);
    }

    @Transactional(readOnly = true)
    public Result scan(Consumer<Card> action) {
        Objects.requireNonNull(action, "action");
        LOG.info("CBACT02C: START OF EXECUTION OF PROGRAM CBACT02C");

        long total = 0L;
        int page = 0;
        Page<Card> currentPage;
        do {
            currentPage = cardRepository.findAll(
                    PageRequest.of(page, PAGE_SIZE, Sort.by("cardNum")));
            for (Card card : currentPage.getContent()) {
                action.accept(card);
                total++;
            }
            page++;
        } while (currentPage.hasNext());

        LOG.info("CBACT02C: END OF EXECUTION OF PROGRAM CBACT02C; recordsRead={}",
                total);
        return new Result(total);
    }

    @Transactional(readOnly = true)
    public long count() {
        return cardRepository.count();
    }

    /**
     * COBOL: 1100-DISPLAY-CARD-RECORD. Logs each field with the
     * original 25-character label prefix. The card number is masked
     * to last-4 per PCI-DSS &mdash; <strong>never</strong> the full
     * PAN.
     */
    void logCardRecord(Card card) {
        if (card == null) {
            return;
        }
        if (LOG.isInfoEnabled()) {
            // PCI-DSS: NEVER log full PAN &mdash; mask to last-4.
            String maskedPan = maskPan(card.getCardNum());
            LOG.info("CARD-NUM                :{}", maskedPan);
            LOG.info("CARD-ACCT-ID            :{}", card.getCardAcctId());
            LOG.info("CARD-EMBOSSED-NAME      :{}", card.getCardEmbossedName());
            LOG.info("CARD-EXPIRATION-DATE    :{}", card.getCardExpirationDate());
            LOG.info("CARD-ACTIVE-STATUS      :{}", card.getCardActiveStatus());
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
