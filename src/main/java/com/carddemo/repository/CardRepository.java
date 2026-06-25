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

import com.carddemo.entity.Card;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for the {@link Card} entity, modeling the COBOL
 * {@code CARD-RECORD} layout from copybook {@code app/cpy/CVACT02Y.cpy} at source
 * commit {@code 27d6c6f}.
 *
 * <p>This interface replaces the legacy VSAM {@code CARDDAT} KSDS (keyed on
 * {@code CARD-NUM}) and its alternate index {@code CARDAIX} (keyed on
 * {@code CARD-ACCT-ID}). The mainframe access mechanics map to repository
 * operations as follows:</p>
 *
 * <ul>
 *   <li>Keyed {@code READ} by {@code CARD-NUM} (programs {@code COCRDSLC} detail
 *       and {@code COCRDUPC} read-before-update) → inherited {@code findById}
 *       with a {@code String} key.</li>
 *   <li>{@code REWRITE}/{@code WRITE} of {@code CARDDAT} (program
 *       {@code COCRDUPC} update) → inherited {@code save(Card)}; optimistic
 *       concurrency is enforced by the entity {@code @Version} field within the
 *       service transaction, reproducing the {@code 9300-CHECK-CHANGE-IN-REC}
 *       re-read-and-compare guard.</li>
 *   <li>{@code CARDAIX} alternate-index access by {@code CARD-ACCT-ID} (programs
 *       {@code COCRDSLC} and {@code COCRDLIC}) → {@link #findByCardAcctId(Long)}.
 *       The AIX is defined {@code NONUNIQUEKEY} in {@code app/jcl/CARDFILE.jcl}
 *       ({@code KEYS(11 16) NONUNIQUEKEY}), so a single account may own multiple
 *       cards and the lookup returns a {@code List}.</li>
 *   <li>{@code STARTBR}/{@code READNEXT}/{@code READPREV} browse of the card list
 *       (program {@code COCRDLIC}, PF8 forward / PF7 backward) → inherited
 *       {@code findAll(Pageable)} for the unfiltered browse and
 *       {@link #findByCardAcctId(Long, Pageable)} for the browse filtered by
 *       account through the {@code CARDAIX} path.</li>
 * </ul>
 */
@Repository
public interface CardRepository extends JpaRepository<Card, String> {

    /**
     * Returns every card owned by the given account, modeling the
     * {@code CARDAIX} alternate-index access path keyed on {@code CARD-ACCT-ID}.
     *
     * <p>Because {@code CARDAIX} is a {@code NONUNIQUEKEY} alternate index, one
     * account may own several cards; the result is therefore a collection.</p>
     *
     * @param cardAcctId the owning account identifier ({@code CARD-ACCT-ID})
     * @return the cards associated with the account, never {@code null}
     */
    List<Card> findByCardAcctId(Long cardAcctId);

    /**
     * Returns a page of cards owned by the given account, modeling the paginated
     * {@code CARDAIX} browse (program {@code COCRDLIC} PF7/PF8 navigation).
     *
     * <p>The caller supplies ordering through the {@link Pageable} (for example
     * {@code Sort.by("cardNum")}) to preserve the legacy card-number browse
     * order.</p>
     *
     * @param cardAcctId the owning account identifier ({@code CARD-ACCT-ID})
     * @param pageable   the pagination and sort specification
     * @return the requested page of cards for the account
     */
    Page<Card> findByCardAcctId(Long cardAcctId, Pageable pageable);
}
