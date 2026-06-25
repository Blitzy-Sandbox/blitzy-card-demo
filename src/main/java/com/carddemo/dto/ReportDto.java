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
package com.carddemo.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Report-submission data transfer objects for {@code POST /api/reports/submit},
 * replacing CICS transaction {@code CR00} / program {@code CORPT00C}.
 *
 * <p>Field names, widths, and value domains are derived from the BMS symbolic
 * map {@code app/cpy-bms/CORPT00.CPY} and mapset {@code app/bms/CORPT00.bms} at
 * SHA {@code 27d6c6f}.</p>
 *
 * <p>This type is a non-instantiable container for the nested request record.</p>
 */
public final class ReportDto {

    private ReportDto() {
    }

    /**
     * Request body for {@code POST /api/reports/submit}, mapping the input
     * fields of the {@code CORPT00} symbolic map at SHA {@code 27d6c6f}.
     *
     * @param monthly    monthly report-type flag ({@code MONTHLY X(1)})
     * @param yearly     yearly report-type flag ({@code YEARLY X(1)})
     * @param custom     custom date-range report-type flag ({@code CUSTOM X(1)})
     * @param startMonth start-date month segment ({@code SDTMM X(2)})
     * @param startDay   start-date day segment ({@code SDTDD X(2)})
     * @param startYear  start-date year segment ({@code SDTYYYY X(4)})
     * @param endMonth   end-date month segment ({@code EDTMM X(2)})
     * @param endDay     end-date day segment ({@code EDTDD X(2)})
     * @param endYear    end-date year segment ({@code EDTYYYY X(4)})
     * @param confirm    submission confirmation flag ({@code CONFIRM X(1)})
     */
    public record SubmitRequest(
            @Size(max = 1) @Pattern(regexp = "[Yy ]?") String monthly,
            @Size(max = 1) @Pattern(regexp = "[Yy ]?") String yearly,
            @Size(max = 1) @Pattern(regexp = "[Yy ]?") String custom,
            @Size(max = 2) @Pattern(regexp = "\\d{0,2}") String startMonth,
            @Size(max = 2) @Pattern(regexp = "\\d{0,2}") String startDay,
            @Size(max = 4) @Pattern(regexp = "\\d{0,4}") String startYear,
            @Size(max = 2) @Pattern(regexp = "\\d{0,2}") String endMonth,
            @Size(max = 2) @Pattern(regexp = "\\d{0,2}") String endDay,
            @Size(max = 4) @Pattern(regexp = "\\d{0,4}") String endYear,
            @Size(max = 1) @Pattern(regexp = "[YyNn]?") String confirm
    ) {
    }
}
