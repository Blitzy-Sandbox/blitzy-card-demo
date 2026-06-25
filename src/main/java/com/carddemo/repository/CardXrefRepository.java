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
package com.carddemo.repository;

import com.carddemo.entity.CardXref;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for the {@link CardXref} cross-reference record.
 *
 * <p>Translates the legacy COBOL VSAM {@code XREFFILE} KSDS (copybook
 * {@code CVACT03Y}, record length 50, at source commit {@code 27d6c6f}) into
 * relational data access. The cross-reference is the join hub linking a card
 * number to its owning customer and account.
 *
 * <p>The VSAM base cluster is keyed on {@code XREF-CARD-NUM}; that primary-key
 * read path (e.g. the {@code CCXREF}/{@code CCDA} lookups in {@code COTRN02C},
 * {@code CBTRN03C}, and {@code CBSTM03A}, and the sequential master read in
 * {@code CBACT03C}) is served by the inherited
 * {@link JpaRepository#findById(Object)}. The non-unique alternate index defined
 * by {@code app/jcl/XREFFILE.jcl} ({@code KEYS(11,25) NONUNIQUEKEY}) on
 * {@code XREF-ACCT-ID} is the one access path the primary key cannot express and
 * is exposed by {@link #findByXrefAcctId(Long)}.
 */
@Repository
public interface CardXrefRepository extends JpaRepository<CardXref, String> {

    /**
     * Retrieves the cross-reference rows for an account via the
     * {@code XREF-ACCT-ID} alternate-index path.
     *
     * <p>Mirrors the COBOL account-keyed reads such as
     * {@code 9200-GETCARDXREF-BYACCT} in {@code COACTVWC} (account-view
     * ACCT+CUST join) and the {@code CXACAIX}/alternate-key reads in
     * {@code COACTUPC}, {@code COBIL00C}, {@code COTRN02C}, and {@code CBACT04C}.
     * Because the alternate index is non-unique, an account may map to multiple
     * cards, so the result is returned as a {@link List}; callers that need a
     * single record select from the list at the service layer.
     *
     * @param xrefAcctId the owning account identifier ({@code XREF-ACCT-ID})
     * @return the matching cross-reference rows, or an empty list when none exist
     */
    List<CardXref> findByXrefAcctId(Long xrefAcctId);
}
