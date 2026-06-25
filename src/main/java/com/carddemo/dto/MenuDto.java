/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.carddemo.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * REST data-transfer types for the CardDemo menu flow.
 *
 * <p>These DTOs replace the CICS pseudo-conversational menu screens
 * {@code CM00} (main menu, program {@code COMEN01C}) and {@code CA00}
 * (admin menu, program {@code COADM01C}). The field contract is derived
 * byte-accurately from the BMS symbolic maps {@code app/cpy-bms/COMEN01.CPY}
 * and {@code app/cpy-bms/COADM01.CPY} and the mapsets
 * {@code app/bms/COMEN01.bms} and {@code app/bms/COADM01.bms} @ {@code 27d6c6f}.</p>
 *
 * <p>Both maps share an identical option-line model: twelve physical option
 * slots {@code OPTN001I}..{@code OPTN012I} (each {@code PIC X(40)}), a
 * two-character option-entry field {@code OPTIONI} ({@code PIC X(2)}), and a
 * {@code TITLE01} heading ({@code PIC X(40)}). The twelve-slot screen contract
 * is preserved here; the controller and service populate only the active
 * subset. Because the two maps differ only in their static heading literal, a
 * single {@code MenuDto} serves both {@code GET /api/menu/main} and
 * {@code GET /api/menu/admin}.</p>
 *
 * <p>Terminal chrome ({@code TRNNAME}, {@code CURDATE}, {@code CURTIME},
 * {@code PGMNAME}, {@code TITLE02}), the {@code ERRMSG} field (surfaced by the
 * global exception handler), and BMS attribute mechanics are intentionally not
 * modeled; these types carry data only. PF-key and paging semantics are handled
 * by the controller through request parameters and resource paths.</p>
 */
public final class MenuDto {

    private MenuDto() {
    }

    /**
     * A single menu option line.
     *
     * <p>Maps one {@code OPTN0nnI} slot ({@code PIC X(40)}) from
     * {@code COMEN01}/{@code COADM01} @ {@code 27d6c6f}. {@code optionNumber} is
     * the one- or two-digit code the user types into {@code OPTIONI}
     * ({@code PIC X(2)}) to select the line; it is kept as a {@link String} to
     * preserve the two-character width contract.</p>
     *
     * @param optionNumber the numeric selection code, up to two digits
     * @param optionName   the display text of the option, up to forty characters
     */
    public record MenuOption(
            @Size(max = 2) @Pattern(regexp = "\\d{0,2}") String optionNumber,
            @Size(max = 40) String optionName
    ) {
    }

    /**
     * Response body for {@code GET /api/menu/main} and
     * {@code GET /api/menu/admin}.
     *
     * <p>{@code title} maps {@code TITLE01} ({@code PIC X(40)}); {@code options}
     * carries the populated subset of the twelve physical slots
     * {@code OPTN001I}..{@code OPTN012I}. The list can hold all twelve slots and
     * each element is cascade-validated.</p>
     *
     * @param title   the menu heading, up to forty characters
     * @param options the menu option lines, up to twelve elements
     */
    public record MenuResponse(
            @Size(max = 40) String title,
            List<@Valid MenuOption> options
    ) {
    }

    /**
     * Optional request body carrying a user's menu selection.
     *
     * <p>Maps {@code OPTIONI} ({@code PIC X(2)}, numeric) from
     * {@code COMEN01}/{@code COADM01} @ {@code 27d6c6f}. The controller maps the
     * selected option to its target resource.</p>
     *
     * @param option the selected option code, one or two digits; required
     */
    public record MenuSelectionRequest(
            @NotBlank @Size(max = 2) @Pattern(regexp = "\\d{1,2}") String option
    ) {
    }
}
