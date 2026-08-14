/*
 * ******************************************************************
 * Program     : MenuFixedWidthPolicyTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11
 * Function    : Asserts one fixed-width policy across both menu renderers in
 *               bytes: every rendered option line is exactly the PIC X(40)
 *               slot width, composed of a PIC 9(02) number, the ". " literal
 *               and the whole PIC X(35) caption.
 * Source      : app/cbl/COADM01C.cbl:L48,L231-L236 (WS-ADMIN-OPT-TXT)
 *               app/cbl/COMEN01C.cbl:L48,L241-L246 (WS-MENU-OPT-TXT)
 *               app/cpy/COADM02Y.cpy:L47           (PIC X(35) caption)
 *               @ 7756d89
 * ******************************************************************
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License.
 * ******************************************************************
 */
package com.cardemo.unit.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.cardemo.model.dto.MenuResponse;
import com.cardemo.model.enums.UserType;
import com.cardemo.service.menu.AdminMenuService;
import com.cardemo.service.menu.MainMenuService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * One fixed-width policy for both menu renderers, asserted in bytes.
 *
 * <p>{@code BUILD-MENU-OPTIONS} exists twice in the corpus and the two copies are byte-identical but for their
 * data names. {@code app/cbl/COADM01C.cbl:L231-L236} and {@code app/cbl/COMEN01C.cbl:L241-L246} each move
 * spaces to a {@code PIC X(40)} work field and then {@code STRING} three operands into it, all
 * {@code DELIMITED BY SIZE}: a {@code PIC 9(02)} option number, the two-character literal {@code '. '}, and a
 * {@code PIC X(35)} caption. Thirty-nine bytes are written; the fortieth is the space the {@code MOVE SPACES}
 * left. The result is moved into {@code OPTN001O}, itself {@code PIC X(40)} on both maps.
 *
 * <p>The two Java renderers had diverged: the main menu padded to the slot width while the administrator menu
 * applied {@code stripTrailing()}, producing ragged lines of 24, 23, 26 and 26 characters out of a fixed-width
 * screen field. Nothing detected it, because each service's own tests asserted its own behaviour and one of
 * them had recorded the divergence as intentional.
 *
 * <p>This class asserts the policy across both renderers together, in bytes rather than in characters, so a
 * future edit to either one has to break a shared assertion rather than only its own.
 */
@DisplayName("Menus: one fixed-width policy, asserted in bytes on both renderers")
class MenuFixedWidthPolicyTest {

    /** The two digits of {@code PIC 9(02)} plus the two characters of the {@code '. '} literal. */
    private static final int PREFIX_LENGTH = 4;

    @Nested
    @DisplayName("1. Both renderers emit lines of exactly the declared slot width")
    class BothEmitTheSlotWidth {

        @Test
        @DisplayName("Every main-menu line is exactly forty bytes, for both user types")
        void everyMainMenuLineIsFortyBytes() {
            for (UserType userType : UserType.values()) {
                assertThat(byteLengths(new MainMenuService().getMainMenu(userType).optionLabels()))
                        .as("app/cbl/COMEN01C.cbl:L48 WS-MENU-OPT-TXT PIC X(40), for user type %s", userType)
                        .isNotEmpty()
                        .containsOnly(MenuResponse.SCREEN_OPTION_SLOT_LENGTH);
            }
        }

        @Test
        @DisplayName("Every administrator-menu line is exactly forty bytes")
        void everyAdminMenuLineIsFortyBytes() {
            assertThat(byteLengths(new AdminMenuService().getMenuScreen().optionLabels()))
                    .as("app/cbl/COADM01C.cbl:L48 WS-ADMIN-OPT-TXT PIC X(40)")
                    .isNotEmpty()
                    .containsOnly(MenuResponse.SCREEN_OPTION_SLOT_LENGTH);
        }

        @Test
        @DisplayName("The two renderers agree on the width, because their source statements are identical")
        void theTwoRenderersAgreeOnTheWidth() {
            final List<Integer> mainWidths =
                    byteLengths(new MainMenuService().getMainMenu(UserType.ADMIN).optionLabels());
            final List<Integer> adminWidths =
                    byteLengths(new AdminMenuService().getMenuScreen().optionLabels());

            assertThat(adminWidths.stream().distinct().toList())
                    .as("one policy for two byte-identical STRING statements")
                    .isEqualTo(mainWidths.stream().distinct().toList());
        }
    }

    @Nested
    @DisplayName("2. The composition is the source's three operands, at their declared widths")
    class TheCompositionIsThreeOperands {

        @Test
        @DisplayName("Every line opens with a zero-padded two-digit number and the '. ' literal")
        void everyLineOpensWithTheNumberAndSeparator() {
            for (String label : allLabels()) {
                assertThat(label.substring(0, PREFIX_LENGTH))
                        .as("CDEMO-*-OPT-NUM PIC 9(02) then '. ' DELIMITED BY SIZE, hence 01. not 1.")
                        .matches("\\d\\d\\. ");
            }
        }

        @Test
        @DisplayName("The caption occupies its whole PIC X(35) width on every line")
        void theCaptionOccupiesItsDeclaredWidth() {
            // 4 + 35 = 39, so a forty-byte line always leaves exactly the one residual space the MOVE
            // SPACES contributed. This is the assertion that fails if either renderer starts trimming.
            for (String label : allLabels()) {
                assertThat(label.length() - PREFIX_LENGTH)
                        .as("[%s] must carry all thirty-five caption bytes", label)
                        .isGreaterThanOrEqualTo(MenuResponse.OPTION_NAME_LENGTH);
            }
        }

        @Test
        @DisplayName("Every line ends with the residual blank, on both renderers")
        void everyLineEndsWithTheResidualBlank() {
            assertThat(allLabels()).allSatisfy(label -> assertThat(label).endsWith(" "));
        }

        @Test
        @DisplayName("The widths the renderers use are declared once, not once per service")
        void theWidthsAreDeclaredOnce() {
            // The divergence was possible because each service declared its own slot width. Both now read
            // these two constants, so the two cannot drift again without changing a shared declaration.
            assertThat(MenuResponse.SCREEN_OPTION_SLOT_LENGTH).isEqualTo(40);
            assertThat(MenuResponse.OPTION_NAME_LENGTH).isEqualTo(35);
            assertThat(PREFIX_LENGTH + MenuResponse.OPTION_NAME_LENGTH)
                    .as("thirty-nine written bytes leave exactly one residual space in a PIC X(40) field")
                    .isEqualTo(MenuResponse.SCREEN_OPTION_SLOT_LENGTH - 1);
        }
    }

    @Nested
    @DisplayName("3. Every line is single-byte ASCII, so byte width equals character width")
    class EveryLineIsSingleByte {

        @Test
        @DisplayName("No caption introduces a multi-byte character, which would break the fixed width")
        void noLineIsMultiByte() {
            // A fixed-width screen field counts bytes. A caption carrying a non-ASCII character would
            // measure forty characters and more than forty bytes, so the two must be asserted separately.
            assertThat(allLabels()).allSatisfy(label ->
                    assertThat(label.getBytes(StandardCharsets.UTF_8).length)
                            .as("[%s] must be single-byte throughout", label)
                            .isEqualTo(label.length()));
        }
    }

    /**
     * Every rendered line from both renderers, for every user type.
     *
     * @return the lines, never {@code null} and never empty
     */
    private static List<String> allLabels() {
        final List<String> labels = new java.util.ArrayList<>();
        for (UserType userType : UserType.values()) {
            labels.addAll(new MainMenuService().getMainMenu(userType).optionLabels());
        }
        labels.addAll(new AdminMenuService().getMenuScreen().optionLabels());
        return List.copyOf(labels);
    }

    /**
     * The byte length of each line, measured rather than assumed from the character count.
     *
     * @param labels the rendered lines, never {@code null}
     * @return one byte length per line, never {@code null}
     */
    private static List<Integer> byteLengths(final List<String> labels) {
        return labels.stream()
                .map(label -> Integer.valueOf(label.getBytes(StandardCharsets.UTF_8).length))
                .toList();
    }
}
