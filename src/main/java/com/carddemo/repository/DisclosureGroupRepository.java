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

import com.carddemo.entity.DisclosureGroup;
import com.carddemo.entity.DisclosureGroupId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for disclosure-group interest rates
 * (copybook {@code CVTRA02Y}, {@code DIS-GROUP-RECORD} @ {@code 27d6c6f}).
 *
 * <p>Replaces the legacy VSAM {@code DISCGRP} KSDS keyed on the three-part
 * {@code DIS-GROUP-KEY} ({@code DIS-ACCT-GROUP-ID PIC X(10)} +
 * {@code DIS-TRAN-TYPE-CD PIC X(02)} + {@code DIS-TRAN-CAT-CD PIC 9(04)}).
 * The interest-calculation batch {@code CBACT04C} issues a keyed
 * {@code READ DISCGRP-FILE} on that composite key to resolve the
 * {@code DIS-INT-RATE} feeding the monthly interest formula
 * {@code (TRAN-CAT-BAL * DIS-INT-RATE) / 1200}. The mainframe access mechanics
 * map to inherited repository operations as follows:</p>
 *
 * <ul>
 *   <li>Keyed {@code READ} by {@code DIS-GROUP-KEY} &rarr;
 *       {@link JpaRepository#findById(Object)} with a {@link DisclosureGroupId}
 *       composite key.</li>
 *   <li>{@code WRITE}/{@code REWRITE} of {@code DISCGRP} &rarr;
 *       {@link JpaRepository#save(Object)}.</li>
 * </ul>
 *
 * <p>The key type is the composite {@link DisclosureGroupId}
 * ({@code acctGroupId}, {@code tranTypeCd}, {@code tranCatCd}), matching the
 * entity's {@code @EmbeddedId}. The disclosure rate is carried on
 * {@link DisclosureGroup} as a {@link java.math.BigDecimal} so the value is read
 * with exact decimal precision; the {@code DEFAULT}/{@code ZEROAPR} fallback
 * lookup (a second keyed read when the account-group rate is absent) is the
 * responsibility of the interest service/processor, not this data-access
 * interface.</p>
 */
@Repository
public interface DisclosureGroupRepository extends JpaRepository<DisclosureGroup, DisclosureGroupId> {
}
