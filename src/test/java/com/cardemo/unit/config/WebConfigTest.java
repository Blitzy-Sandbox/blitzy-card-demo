/*
 * ****************************************************************************
 * Program     : WebConfigTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11 (Surefire tier)
 * Function    : Verifies WebConfig: exactly one object per COBOL numeric
 *               intrinsic, the registered instances being the published beans,
 *               and the absence of any navigation-action converter.
 * Source      : app/cbl/COTRN02C.cbl:L204, :L218 (plain FUNCTION NUMVAL),
 *                 :L383-L384, :L456-L457 (FUNCTION NUMVAL-C), :L58-L59
 *                 (WS-TRAN-AMT-N PIC S9(9)V99 and WS-TRAN-AMT-E PIC +99999999.99)
 *               + app/cpy/CSSTRPFY.cpy:L21-L78 (EVALUATE with no WHEN OTHER)
 *               + app/cpy/CVCRD01Y.cpy:L3 (CCARD-AID PIC X(5))
 *               @ 7756d89
 * ****************************************************************************
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
 * language governing permissions and limitations under the License
 * ****************************************************************************
 */
package com.cardemo.unit.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.convert.converter.Converter;
import org.springframework.format.Formatter;
import org.springframework.format.FormatterRegistry;
import org.springframework.format.Parser;
import org.springframework.format.Printer;
import org.springframework.format.support.FormattingConversionService;

import com.cardemo.config.WebConfig;

/**
 * Unit tests for {@code com.cardemo.config.WebConfig}.
 *
 * <p>The contract under test is an identity contract rather than a behavioural one: there must be exactly
 * <b>one</b> object implementing each COBOL numeric intrinsic in the whole application, and the object the
 * MVC conversion service is registered with must be that same object. A second privately constructed copy
 * is what made the registration unreachable, so these tests assert the identity directly.</p>
 */
@DisplayName("WebConfig: one object per COBOL numeric intrinsic, registered and injectable")
class WebConfigTest {

    /**
     * A recording registry. {@link FormatterRegistry} has many methods and only three are used, so a
     * recorder is written rather than a mock: it keeps the assertions about what was registered exact.
     */
    private static final class RecordingRegistry extends FormattingConversionService {

        /**
         * Every converter passed to {@link #addConverter(Converter)}, in registration order.
         *
         * <p>Lazily created rather than field-initialised: the superclass constructor runs first and is
         * entitled to register converters of its own, which would reach an overridden method before this
         * object's field initialisers had run.
         */
        private List<Object> converters;

        /** Every printer passed to {@link #addPrinter(Printer)}, in registration order. */
        private List<Object> printers;

        /**
         * Returns the recorded converters, never null.
         *
         * @return the converters registered so far, in order
         */
        private List<Object> converters() {
            if (this.converters == null) {
                this.converters = new ArrayList<>();
            }
            return this.converters;
        }

        /**
         * Returns the recorded printers, never null.
         *
         * @return the printers registered so far, in order
         */
        private List<Object> printers() {
            if (this.printers == null) {
                this.printers = new ArrayList<>();
            }
            return this.printers;
        }

        @Override
        public void addConverter(final Converter<?, ?> converter) {
            converters().add(converter);
            super.addConverter(converter);
        }

        @Override
        public void addPrinter(final Printer<?> printer) {
            printers().add(printer);
            super.addPrinter(printer);
        }

        @Override
        public void addFormatter(final Formatter<?> formatter) {
            throw new AssertionError("WebConfig must not register a full formatter: a BigDecimal formatter "
                    + "would supply a parser too and supersede CurrencyAwareAmountConverter");
        }

        @Override
        public void addParser(final Parser<?> parser) {
            throw new AssertionError("WebConfig registers no standalone parser");
        }
    }

    @Test
    @DisplayName("The three registered objects are the three published bean instances, not copies")
    void registeredObjectsAreThePublishedBeans() {
        final WebConfig config = new WebConfig();
        final WebConfig.StrictIdentifierConverter identifier = config.strictIdentifierConverter();
        final WebConfig.CurrencyAwareAmountConverter amount = config.currencyAwareAmountConverter();
        final WebConfig.EditedAmountPrinter printer = config.editedAmountPrinter();
        final RecordingRegistry registry = new RecordingRegistry();

        config.addFormatters(registry);

        assertThat(registry.converters())
                .as("exactly the two numeric converters, in the documented order")
                .hasSize(2)
                .element(0).isSameAs(identifier);
        assertThat(registry.converters()).element(1).isSameAs(amount);
        assertThat(registry.printers())
                .as("exactly the one edited-amount printer")
                .hasSize(1)
                .element(0).isSameAs(printer);
    }

    @Test
    @DisplayName("A bean method returns one shared instance per call site, as Spring will proxy it")
    void beanMethodsReturnTheDeclaredTypes() {
        final WebConfig config = new WebConfig();

        assertThat(config.strictIdentifierConverter())
                .isExactlyInstanceOf(WebConfig.StrictIdentifierConverter.class);
        assertThat(config.currencyAwareAmountConverter())
                .isExactlyInstanceOf(WebConfig.CurrencyAwareAmountConverter.class);
        assertThat(config.editedAmountPrinter())
                .isExactlyInstanceOf(WebConfig.EditedAmountPrinter.class);
    }

    @Test
    @DisplayName("No navigation-action converter is registered, because none exists to register")
    void noNavigationActionConverterIsRegistered() {
        final WebConfig config = new WebConfig();
        final RecordingRegistry registry = new RecordingRegistry();

        config.addFormatters(registry);

        assertThat(registry.converters())
                .as("only the two numeric converters; a third would be the removed navigation converter")
                .allSatisfy(registered -> assertThat(registered.getClass().getName())
                        .doesNotContain("NavigationAction"));
        assertThat(WebConfig.class.getDeclaredClasses())
                .as("the unreachable navigation types were removed rather than left unwired")
                .noneSatisfy(nested -> assertThat(nested.getSimpleName()).contains("NavigationAction"));
    }

    @Test
    @DisplayName("The two intrinsics stay distinct: NUMVAL rejects what NUMVAL-C accepts")
    void theTwoIntrinsicsRemainDistinct() {
        final WebConfig config = new WebConfig();

        assertThat(config.strictIdentifierConverter().convert("0000000000000001")).isEqualTo(1L);
        assertThat(config.currencyAwareAmountConverter().convert("$1,234.56"))
                .isEqualByComparingTo(new BigDecimal("1234.56"));
        assertThat(config.editedAmountPrinter().print(new BigDecimal("1234.56"), Locale.ROOT))
                .hasSize(WebConfig.AMOUNT_EDITED_LENGTH);
    }

    @Test
    @DisplayName("The published action-parameter name and its two refusal messages are available to callers")
    void actionVocabularyIsPublished() {
        assertThat(WebConfig.NAVIGATION_ACTION_PARAMETER).isEqualTo("action");
        assertThat(WebConfig.ACTION_BLANK_MESSAGE).isNotBlank();
        assertThat(WebConfig.ACTION_UNKNOWN_MESSAGE)
                .as("a refusal never repeats the rejected token")
                .isNotBlank()
                .doesNotContain("{");
    }
}
