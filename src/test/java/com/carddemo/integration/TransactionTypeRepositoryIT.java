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
package com.carddemo.integration;

import com.carddemo.entity.TransactionType;
import com.carddemo.enums.TransactionTypeCode;
import com.carddemo.repository.TransactionTypeRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for {@link TransactionTypeRepository} against a real
 * PostgreSQL&nbsp;16 instance provisioned by Testcontainers and seeded by the
 * Flyway {@code V1}/{@code V2}/{@code V3} migrations.
 *
 * <p>The {@code transaction_type} table is the relational replacement for the
 * legacy VSAM {@code TRANTYPE} KSDS (copybook {@code CVTRA03Y}
 * {@code TRAN-TYPE-RECORD}, {@code RECLN 60}; defined and loaded by
 * {@code app/jcl/TRANTYPE.jcl}, {@code KEYS(2 0) RECORDSIZE(60 60)}) at source
 * commit {@code 27d6c6f}. It is a read-mostly reference / lookup table: the
 * batch report program {@code CBTRN03C} (paragraph
 * {@code 1500-B-LOOKUP-TRANTYPE}, migrated into
 * {@code TransactionReportProcessor}) issues a keyed
 * {@code READ TRANTYPE-FILE} on the two-character {@code TRAN-TYPE-CD} to resolve
 * the {@code TRAN-TYPE-DESC} description for each report line. That mainframe
 * access maps to {@link TransactionTypeRepository#findById(Object)} with a
 * {@link String} key.</p>
 *
 * <p>By extending {@link AbstractIntegrationIT} this test inherits the shared
 * {@code @SpringBootTest} context wired to the singleton PostgreSQL and
 * LocalStack containers, so Flyway applies the real schema and seed and
 * Hibernate runs with {@code ddl-auto=validate}. There is deliberately
 * <strong>no</strong> H2 / in-memory database and <strong>no</strong> mock
 * repository: the seven reference rows and their descriptions are asserted
 * against the genuine migrated data, and no datasource port is hardcoded
 * (Testcontainers injects the random coordinates at runtime).</p>
 *
 * <p>Every method here is read-only, so no test mutates the shared reference
 * data and none needs {@code @Transactional}.</p>
 */
@DisplayName("TransactionTypeRepository IT — PostgreSQL 16 + Flyway-seeded TRANTYPE reference table")
class TransactionTypeRepositoryIT extends AbstractIntegrationIT {

    /**
     * Expected, immutable count of reference rows seeded by Flyway {@code V3}
     * from {@code app/data/ASCII/trantype.txt} (codes {@code "01"}..{@code "07"}).
     */
    private static final int EXPECTED_REFERENCE_ROWS = 7;

    /** Repository under test, wired by the inherited Spring context. */
    @Autowired
    private TransactionTypeRepository repository;

    // =====================================================================
    // Phase 1 — Keyed lookups (the report-enrichment path: findById + getTranTypeDesc).
    // Mirrors CBTRN03C 1500-B-LOOKUP-TRANTYPE, which TransactionReportProcessor
    // performs as transactionTypeRepository.findById(code).getTranTypeDesc().
    // =====================================================================

    @Test
    @DisplayName("findById(\"01\") resolves the seeded 'Purchase' description (1500-B enrichment)")
    void findByIdPurchaseCodeResolvesDescription() {
        Optional<TransactionType> result = repository.findById("01");

        assertThat(result)
                .as("reference row for transaction-type code 01 must be seeded")
                .isPresent();
        TransactionType purchase = result.orElseThrow();
        assertThat(purchase.getTranType()).isEqualTo("01");
        assertThat(purchase.getTranTypeDesc())
                .as("the exact description CBTRN03C prints for a Purchase transaction")
                .isEqualTo("Purchase");
    }

    @Test
    @DisplayName("findById(\"02\") resolves the seeded 'Payment' description")
    void findByIdPaymentCodeResolvesDescription() {
        Optional<TransactionType> result = repository.findById("02");

        assertThat(result)
                .as("reference row for transaction-type code 02 must be seeded")
                .isPresent();
        assertThat(result.orElseThrow().getTranTypeDesc()).isEqualTo("Payment");
    }

    @Test
    @DisplayName("findById(\"99\") returns empty for an unknown transaction-type code")
    void findByIdUnknownCodeReturnsEmpty() {
        // The COBOL keyed READ on a missing key yields INVALID KEY; the JPA
        // equivalent is an empty Optional (the caller turns that into the abend).
        assertThat(repository.findById("99")).isEmpty();
    }

    // =====================================================================
    // Phase 2 — Full reference set: cardinality, coverage, and enum agreement.
    // =====================================================================

    @Test
    @DisplayName("count() == 7 seeded reference rows (trantype.txt / CVTRA03Y)")
    void countReturnsSevenSeededRows() {
        assertThat(repository.count()).isEqualTo(EXPECTED_REFERENCE_ROWS);
    }

    @Test
    @DisplayName("findAll() returns all seven codes \"01\"..\"07\", each with a non-blank description")
    void findAllReturnsAllSevenCodesWithDescriptions() {
        List<TransactionType> all = repository.findAll();

        assertThat(all).hasSize(EXPECTED_REFERENCE_ROWS);
        assertThat(all)
                .extracting(TransactionType::getTranType)
                .containsExactlyInAnyOrder("01", "02", "03", "04", "05", "06", "07");
        assertThat(all).allSatisfy(type ->
                assertThat(type.getTranTypeDesc())
                        .as("description for code %s must not be blank", type.getTranType())
                        .isNotBlank());
    }

    @Test
    @DisplayName("reference table and TransactionTypeCode enum agree (code + description, both directions)")
    void referenceTableAgreesWithTransactionTypeCodeEnum() {
        // 1) Same cardinality: no extra or missing rows versus the enum.
        assertThat(repository.count())
                .as("seeded reference rows must match the TransactionTypeCode constant count")
                .isEqualTo(TransactionTypeCode.values().length);

        // 2) Every enum constant has a matching reference row whose description
        //    equals the enum label (e.g. "01" -> PURCHASE -> "Purchase").
        for (TransactionTypeCode code : TransactionTypeCode.values()) {
            Optional<TransactionType> row = repository.findById(code.getCode());
            assertThat(row)
                    .as("seeded reference row for enum %s (code %s)", code.name(), code.getCode())
                    .isPresent();
            assertThat(row.orElseThrow().getTranTypeDesc())
                    .as("description for code %s must equal the enum label", code.getCode())
                    .isEqualTo(code.getLabel());
        }

        // 3) Every reference-row code resolves to a valid enum constant (no orphan codes);
        //    TransactionTypeCode.fromCode throws on an unknown code, so a clean
        //    resolution for all rows proves the table introduces nothing the enum lacks.
        assertThat(repository.findAll())
                .allSatisfy(type ->
                        assertThat(TransactionTypeCode.fromCode(type.getTranType()))
                                .as("reference code %s must map to a TransactionTypeCode constant",
                                        type.getTranType())
                                .isNotNull());
    }
}
