/*
 * ******************************************************************
 * Program     : WebConfig.java
 * Application : CardDemo
 * Type        : Java Spring Configuration (web layer)
 * Function    : URL routing and message conversion replacing the BMS
 *               navigation/AID state, plus the two distinct COBOL
 *               numeric parsers and the edited-amount formatter.
 * Source      : app/cpy/CVCRD01Y.cpy (46 lines; CC-WORK-AREAS,
 *                 CCARD-AID X(5) with 16 88-levels, CCARD-NEXT-PROG,
 *                 CCARD-NEXT-MAPSET, CCARD-NEXT-MAP, and the three
 *                 X/9 REDEFINES pairs at L34-L42)
 *               + app/cpy/CSSTRPFY.cpy (85 lines; the procedural
 *                 paragraph YYYY-STORE-PFKEY at L17 evaluating EIBAID)
 *               + app/cbl/COTRN02C.cbl:L55-L60, L204, L218, L383-L386,
 *                 L456-L457 (the two numeric intrinsics and the
 *                 edited-amount round trip)
 *               @ 7756d89
 * Replaces    : BMS mapset navigation, the CICS attention-identifier
 *               dispatch, and FUNCTION NUMVAL / FUNCTION NUMVAL-C
 * Note        : app/cpy/CVCRD01Y.cpy carries NO Apache banner - it is
 *               one of the 16 of 28 app/cpy members without one.
 *               DFHAID, DFHBMSCA and DFHATTR are CICS-supplied, are
 *               absent from this repository, and have no import.
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
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License
 * ******************************************************************
 */
package com.cardemo.config;

import com.fasterxml.jackson.core.StreamReadConstraints;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.format.FormatterRegistry;
import org.springframework.format.Printer;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.AbstractJackson2HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.cardemo.exception.ValidationException;

/**
 * Web-layer configuration: the request-binding half of the CICS-to-REST substitution.
 *
 * <p><b>What it does.</b> It publishes exactly three singleton beans, contributes exactly those same three
 * objects as registrations to the Spring MVC conversion service, and overrides exactly two methods,
 * {@link #addFormatters(FormatterRegistry)} and
 * {@link #extendMessageConverters(List)}. All three reproduce COBOL behaviour that has no Spring default
 * equivalent:</p>
 * <ul>
 *   <li><b>Numeric converter 1 of 2</b> - {@link StrictIdentifierConverter}, the digits-only parser standing
 *       in for plain {@code FUNCTION NUMVAL} on identifiers and card numbers.</li>
 *   <li><b>Numeric converter 2 of 2</b> - {@link CurrencyAwareAmountConverter}, the currency-tolerant parser
 *       standing in for {@code FUNCTION NUMVAL-C} on amounts, and on amounts only.</li>
 *   <li><b>The edited-amount formatter</b> - {@link EditedAmountPrinter}, reproducing the display picture
 *       {@code PIC +99999999.99} character for character.</li>
 *   </ul>
 *
 * <p><b>One object per rule, injected and registered.</b> Each of the three is a bean, and
 * {@link #addFormatters(FormatterRegistry)} registers the bean instances rather than fresh copies, so a
 * service that injects one of them and a request that binds through the conversion service exercise the same
 * object. A request body never passes through the MVC conversion service at all - Jackson binds it - which is
 * why the services that must apply {@code NUMVAL} and {@code NUMVAL-C} to a JSON payload inject these beans
 * and invoke them explicitly.
 *
 * <p><b>There is no navigation-action converter and no navigation-action type.</b> One existed and had no
 * consumer anywhere in the application: each operation that carries a navigation intent declares its own
 * closed set of actions and validates the submitted token exactly, refusing every alias, case variant and
 * surrounding space. A shared converter that trimmed and folded its input would have re-introduced exactly
 * the coercion those operations exist to refuse, so it was removed rather than wired to something.
 *
 * <p><b>There are exactly two numeric converters and there is deliberately no third.</b> The frozen corpus
 * uses two different numeric intrinsics for two different jobs and never interchanges them. A census over
 * {@code app/cbl} at commit 7756d89 finds plain {@code FUNCTION NUMVAL(} at exactly three sites -
 * {@code app/cbl/COTRN02C.cbl}:204, {@code app/cbl/COTRN02C.cbl}:218 and {@code app/cbl/COACTUPC.cbl}:2156 -
 * and {@code FUNCTION NUMVAL-C} at thirteen, of which the six in {@code app/cbl/CORPT00C.cbl} (305, 309, 313,
 * 317, 321 and 325) act on screen <em>date components</em> rather than on money and are therefore not modelled
 * as amount parsing here. Collapsing the two parsers into one tolerant parser would accept input the legacy
 * system rejects and reject input it accepts; severity <b>High</b>. Adding a third numeric parser would break
 * the same contract from the other direction.
 *
 * <p><b>Scope of these registrations - read this before assuming a payload goes through them.</b>
 * {@code addFormatters} populates the MVC conversion service, which Spring uses for
 * {@code @RequestParam}, {@code @PathVariable}, {@code @RequestHeader} and {@code @ModelAttribute} binding.
 * It does <em>not</em> participate in JSON request-body deserialisation or response serialisation - Jackson
 * owns that, configured entirely in the application configuration resource and not restated here - and it
 * does <em>not</em> participate in {@code @Value} or {@code @ConfigurationProperties} property binding, which
 * uses a separate application conversion service. An amount arriving inside a JSON body is therefore bound by
 * Jackson and never by {@link CurrencyAwareAmountConverter}.
 *
 * <p><b>What this class deliberately does not do.</b> Each omission is a decision, not an oversight:</p>
 * <ul>
 *   <li><b>No {@code @EnableWebMvc}.</b> That annotation switches off Spring Boot's WebMvc
 *       auto-configuration wholesale, taking the Jackson customisation, the content negotiation and the
 *       error handling with it. Implementing {@code WebMvcConfigurer} is the supported extension point.</li>
 *   <li><b>No cross-origin configuration.</b> {@code addCorsMappings} is not overridden, so no origin is
 *       permitted by this class. A permissive default would be an unsafe default.</li>
 *   <li><b>No static-resource handler, no directory listing, no view resolver and no user-interface route.</b>
 *       The target exposes REST and JSON plus actuator endpoints. There is no HTML, CSS, JavaScript,
 *       single-page front end or component library anywhere in scope, and the <b>441</b> BMS input fields
 *       are consumed as data-transfer-object field contracts only. That total is a recount of the seventeen
 *       symbolic maps under {@code app/cpy-bms/}, in which {@code COACTVW} contributes 37.</li>
 *   <li><b>No message converter and no second object mapper.</b> The auto-configured converter is sufficient,
 *       so registering one would duplicate configuration that the application configuration resource owns.</li>
 *   <li><b>No global default page size and no pagination argument resolver.</b> See the pagination note.</li>
 *   <li><b>No string-to-temporal converter of any kind.</b> See the timestamp note.</li>
 *   <li><b>No {@code @ControllerAdvice}, {@code @ExceptionHandler} or {@code @ResponseStatus}.</b> Mapping a
 *       {@code com.cardemo.exception.ValidationException} onto a status code belongs to the error-handling
 *       layer, not to the binding layer.</li>
 *   </ul>
 *
 * <p><b>Pagination.</b> Transformation rule 7 moves paging state out of the COMMAREA into request parameters
 * and response metadata, and {@code com.cardemo.model.dto.PageResponse} carries the outbound half. The page
 * sizes are fixed by the source - 7 for the card list at {@code app/cbl/COCRDLIC.cbl}:177-178, 10 for the
 * transaction list enforced by the loop bounds at {@code app/cbl/COTRN00C.cbl}:290 and :297, and 10 for the
 * user list at {@code app/cbl/COUSR00C.cbl}:57 - and each is owned by the service bean that serves that list.
 * This class therefore registers <b>nothing</b> that could impose a page size: a global default differing from
 * 7, 10 and 10 would silently override all three, severity <b>High</b>. The twenty-lines-per-page figure at
 * {@code app/cbl/CBTRN03C.cbl}:131-132 is a batch report-line count belonging to the transaction report and is
 * not a page size at all.
 *
 * <p><b>Timestamps: character columns, not temporal types.</b> {@code TRAN-ORIG-TS} and {@code TRAN-PROC-TS} are
 * {@code PIC X(26)} and map to {@code CHAR(26)} character columns carried as {@code String}. The generated value is
 * 26 characters at <b>centisecond</b> precision followed by four literal zero digits - two fraction digits, not three
 * and not nine. {@code app/cbl/CBTRN02C.cbl:L159-L174} splits the fraction into {@code DB2-MIL PIC 9(002)} and
 * {@code DB2-REST PIC X(04)}, and {@code :L700-L701} moves {@code COB-MIL} into the two-digit field and the literal
 * {@code '0000'} into the four-character remainder, giving the shape {@code yyyy-MM-dd-HH.mm.ss.SS0000}. Any
 * converter that parsed and re-rendered such a value would change every one of them; severity <b>Blocker</b>. This
 * class registers no string-to-temporal converter, applies no date format and leaves those fields as plain string
 * passthrough.
 *
 * <p><b>Locale, charset and time zone.</b> Every case-folding and formatting operation in this file uses
 * {@link Locale#ROOT}. The corpus applies {@code FUNCTION UPPER-CASE} and {@code FUNCTION LOWER-CASE} with no
 * locale sensitivity, and a default-locale {@code toUpperCase} under a Turkish locale maps {@code i} to a
 * dotted capital and silently breaks token comparison; severity <b>High</b>. No default charset is relied on -
 * no byte conversion happens here at all - and no JVM default time zone is set: the JDBC time zone is UTC and
 * is owned by the application configuration resource. Credential case folding is owned exclusively by
 * {@code com.cardemo.security.CardDemoUserDetailsService}; this class never touches a credential.
 *
 * <p><b>State and thread safety.</b> The class has no collaborators, no injected state, no instance state and
 * no mutable static state. Every nested type is stateless and immutable, so a single instance of each is shared
 * safely across every request thread. No {@code java.text.DecimalFormat} or {@code java.text.SimpleDateFormat}
 * instance is created anywhere in this file - both are mutable and unsafe to share - and every rendering is
 * produced from {@link BigDecimal#unscaledValue()} and string concatenation instead, which makes the output
 * locale-independent by construction rather than by remembering to pass a locale.
 *
 * <p><b>Arithmetic.</b> Money is {@link BigDecimal} throughout, at scale {@value #AMOUNT_SCALE} with
 * {@code RoundingMode.HALF_EVEN}. There is no {@code float} or {@code double} anywhere in this file, and
 * magnitude comparison uses {@link BigDecimal#compareTo(BigDecimal)} rather than
 * {@link BigDecimal#equals(Object)}, because the latter also compares scale and would treat
 * {@code 1.0} and {@code 1.00} as different amounts.
 *
 * <p><b>Key configuration and defaults.</b> This class reads no property and declares no property. It calls no
 * environment accessor, so nothing here can vary with the host. The keys relevant to the web layer are owned by
 * the application configuration resource and are listed here only so their defaults are on record:</p>
 * <dl>
 *   <dt>{@code server.port}</dt>
 *   <dd>Resolved from the {@code SERVER_PORT} environment variable with a default of {@code 8080}.</dd>
 *   <dt>{@code spring.jackson.serialization.write-dates-as-timestamps}</dt>
 *   <dd>{@code false}; dates serialise as ISO-8601 text rather than epoch numbers. Honoured, never flipped.</dd>
 *   <dt>{@code spring.jackson.serialization.write-bigdecimal-as-plain}</dt>
 *   <dd>{@code true}; a decimal never serialises in scientific notation. Honoured, never flipped.</dd>
 *   <dt>{@code spring.jackson.deserialization.fail-on-unknown-properties}</dt>
 *   <dd>{@code true}; an unrecognised body property is rejected rather than ignored. Honoured, never
 *       relaxed.</dd>
 *   <dt>{@code spring.profiles.active}</dt>
 *   <dd><b>No default, and deliberately unset</b>, so the active profile is chosen at deployment time.</dd>
 *   <dt>{@code spring.session.*}</dt>
 *   <dd><b>Not present, at any key.</b> The application is stateless; there is no session to configure.</dd>
 * </dl>
 *
 * <p><b>Error modes.</b> Every failure raised from this file is a
 * {@code com.cardemo.exception.ValidationException}. Nothing is swallowed, no exception is caught and
 * discarded, and there is no fallback to zero on a failed conversion - a silent zero would post a
 * zero-value financial transaction. Where an underlying {@code NumberFormatException} exists it is attached as
 * the cause so the root cause survives. No exception message, and no log statement, ever contains the value
 * that was rejected: {@code CC-CARD-NUM} is {@code PIC X(16)} cardholder data, and a converter cannot know
 * whether the string it was handed is a card number, so it treats every rejected value as though it were one.
 *
 * <p><b>How to build and test.</b> This file belongs to the single Maven module at the repository root.
 * Compile it with {@code ./mvnw -B -ntp clean compile} and exercise it with {@code ./mvnw -B -ntp test}. The
 * module compiles under {@code -Xlint:all} with {@code -Werror} and {@code failOnWarning}, so any warning
 * introduced here fails the build rather than being reported. Tests for this class live under
 * {@code src/test/java/com/cardemo/unit}, never in this package.
 *
 * <p><b>Common failure modes and troubleshooting.</b></p>
 * <dl>
 *   <dt>A request-parameter amount is rejected with a 400 although it looks well formed.</dt>
 *   <dd>Check which parser applies. {@link StrictIdentifierConverter} binds to {@code Long} and rejects a
 *       sign, a currency symbol, a group separator, a decimal point and any whitespace; it is for identifiers.
 *       {@link CurrencyAwareAmountConverter} binds to {@link BigDecimal} and accepts all of those; it is for
 *       amounts. A formatted amount sent to a {@code Long} parameter is correctly rejected. This is the
 *       intended asymmetry, not a defect.</dd>
 *   <dt>A rendered amount is missing its leading digit.</dt>
 *   <dd>Expected, and preserved. The display picture holds eight integer digits while the numeric field holds
 *       nine, so a magnitude of 100000000.00 or more loses its high-order digit. See
 *       {@link EditedAmountPrinter} for the two locators and the severity.</dd>
 *   <dt>A navigation action is rejected as unknown.</dt>
 *   <dd>That refusal comes from the operation, not from this class. Each controller that accepts a navigation
 *       intent declares its own closed action set and compares the submitted token to it exactly, so a
 *       lower-case token, a padded token or an alias is refused. Send the canonical token the operation
 *       documents. The source's {@code EVALUATE} at {@code app/cpy/CSSTRPFY.cpy}:21-78 had no
 *       {@code WHEN OTHER} and silently retained its previous value; a stateless service has no previous
 *       value to retain, so refusing is the only explicit behaviour available and it is a labelled
 *       deviation.</dd>
 *   <dt>An empty parameter binds to null instead of failing.</dt>
 *   <dd>Intended. Both parsers translate blank input to null, mirroring the source's own
 *       {@code NOT = SPACES AND LOW-VALUES} guard, and the field-specific "must be entered" message belongs to
 *       the layer that knows which field was blank. Enforce presence with a validation constraint or a
 *       required parameter, not with a converter.</dd>
 *   </dl>
 *
 * <p><b>Not available.</b> {@code DFHAID}, {@code DFHBMSCA} and {@code DFHATTR} are supplied by the
 * transaction monitor, are absent from this repository, and consequently have no Java import, no type and no
 * citation here. What would be needed to model them: the CICS-supplied copybook members themselves, plus a
 * decision to emulate 3270 terminal semantics - which is explicitly out of scope, so the requirement is not
 * expected to arise.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /**
     * The widest unsigned identifier picture in the corpus, {@code CC-CARD-NUM ... PIC 9(16)} at
     * {@code app/cpy/CVCRD01Y.cpy}:39, expressed as a count of significant digits.
     *
     * <p>The three redefinition pairs at {@code app/cpy/CVCRD01Y.cpy}:34-42 declare widths 11, 16 and 9 -
     * {@code CC-ACCT-ID}, {@code CC-CARD-NUM} and {@code CC-CUST-ID} - which are exactly the catalogued key
     * lengths of the account, card and customer clusters. Sixteen digits is the widest of the three and is
     * comfortably inside the range of a {@code long}, so no identifier the source can represent can overflow
     * the target type.
     */
    public static final int IDENTIFIER_MAX_DIGITS = 16;

    /**
     * Integer digits the amount work field can hold: nine, from {@code 05 WS-TRAN-AMT-N PIC S9(9)V99} at
     * {@code app/cbl/COTRN02C.cbl}:58.
     *
     * <p>This is the <em>storage</em> capacity and it is one digit wider than the display picture. The two
     * figures are genuinely different and both are reproduced; see {@link #AMOUNT_MASK_INTEGER_DIGITS}.
     */
    public static final int AMOUNT_INTEGER_DIGITS = 9;

    /**
     * Decimal places every monetary value carries, from the {@code V99} of
     * {@code app/cbl/COTRN02C.cbl}:58.
     */
    public static final int AMOUNT_SCALE = 2;

    /**
     * The largest magnitude {@code PIC S9(9)V99} can hold, and therefore the inclusive bound
     * {@link CurrencyAwareAmountConverter} enforces on parsed input.
     */
    public static final BigDecimal AMOUNT_MAX_MAGNITUDE = new BigDecimal("999999999.99");

    /**
     * Rounding applied wherever a monetary scale is imposed.
     *
     * <p>Half-even is the platform-wide rule for every financial field in the target. A note on fidelity, kept
     * here rather than buried: {@code COMPUTE WS-TRAN-AMT-N = FUNCTION NUMVAL-C(...)} at
     * {@code app/cbl/COTRN02C.cbl}:383-384 and :456-457 carries no {@code ROUNDED} phrase, so the legacy
     * compute <em>truncates</em> a third decimal place toward zero where this converter rounds it. The
     * divergence is reachable only for input carrying more than two decimals, which the twelve-character
     * edited round trip immediately re-renders; severity <b>Medium</b>, recorded for the repository decision
     * log with those two locators. The platform rule governs, and the divergence is disclosed rather than
     * absorbed.
     */
    public static final RoundingMode AMOUNT_ROUNDING_MODE = RoundingMode.HALF_EVEN;

    /**
     * The legacy edited display picture for an amount, {@code 05 WS-TRAN-AMT-E PIC +99999999.99 VALUE ZEROS}
     * at {@code app/cbl/COTRN02C.cbl}:59. The same picture is declared a second time in that program, on
     * {@code 05 WS-TRAN-AMT} at :53.
     */
    public static final String AMOUNT_EDITED_MASK = "+99999999.99";

    /**
     * Integer positions the edited picture provides: eight, counted directly from the {@code 99999999} of
     * {@link #AMOUNT_EDITED_MASK}. One fewer than {@link #AMOUNT_INTEGER_DIGITS}, deliberately.
     */
    public static final int AMOUNT_MASK_INTEGER_DIGITS = 8;

    /**
     * Characters the edited picture always produces: one mandatory sign, eight integer digits, one decimal
     * point and two decimals.
     */
    public static final int AMOUNT_EDITED_LENGTH = 12;

    /**
     * Request-parameter name carrying the navigation action, and the field name each operation reports on a
     * {@code com.cardemo.exception.ValidationException} when the submitted token is not one of its own
     * declared actions.
     *
     * <p>It is the stateless counterpart of {@code CCARD-AID} at {@code app/cpy/CVCRD01Y.cpy}:3, which the
     * procedural paragraph {@code YYYY-STORE-PFKEY} at {@code app/cpy/CSSTRPFY.cpy}:17 populated from the
     * terminal's attention identifier. It is defined here, once, so that the two operations carrying a
     * navigation intent name the parameter identically on the wire and in their refusals.
     */
    public static final String NAVIGATION_ACTION_PARAMETER = "action";

    /**
     * The deepest nesting a request body may declare, namely 8.
     *
     * <p>The deepest structure any request type in this application declares is three: the request object, one
     * of its two snapshot groups, and that group's leaf fields. {@code AccountUpdateRequest} and
     * {@code CardUpdateRequest} are both that shape. Eight therefore admits every legitimate document with
     * more than double the margin, while refusing the thousands-deep document whose only purpose is to turn
     * parsing into recursion. Jackson's own default is 1000, which no body here comes within two orders of
     * magnitude of needing.
     */
    public static final int MAX_JSON_NESTING_DEPTH = 8;

    /**
     * The longest single string value a request body may carry, namely 4096 characters.
     *
     * <p>The widest field any symbolic map in {@code app/cpy-bms} declares is 80 characters, so no legitimate
     * value approaches this. The margin is deliberate: a value between 80 and 4096 must be refused by the
     * field's own {@code @Size} constraint, which reports WHICH field was wrong in the shape the
     * {@code app/cpy/CSSETATY.cpy} error-marker contract requires. A parser limit set near the field widths
     * would pre-empt that report with an opaque parse failure, so this bound exists to stop the pathological
     * case only and leaves the field contract to do the field-level work.
     */
    public static final int MAX_JSON_STRING_LENGTH = 4096;

    /**
     * The longest numeric token a request body may carry, namely 64 characters.
     *
     * <p>The largest number any field contract admits is the 16-digit identifier of
     * {@link #IDENTIFIER_MAX_DIGITS} and the {@code PIC S9(9)V99} amount, which is 12 characters with its sign
     * and point. 64 is far beyond both and well below the length at which decoding a numeric literal becomes
     * the expensive operation - the concern being a token long enough to make {@code BigDecimal} conversion
     * itself the attack, which is why the bound is on the token and not on the value.
     */
    public static final int MAX_JSON_NUMBER_LENGTH = 64;

    /**
     * The longest property name a request body may carry, namely 256 characters.
     *
     * <p>The longest name any DTO declares is well under 40 characters. The bound matters even though
     * {@code spring.jackson.deserialization.fail-on-unknown-properties} is true and an unrecognised property
     * is therefore refused: the name is read and buffered BEFORE it can be compared against the known set, so
     * without this an unknown property could be arbitrarily long and still allocate before being rejected.
     */
    public static final int MAX_JSON_NAME_LENGTH = 256;

    /**
     * The greatest total document length the parser will accept, namely 16384 bytes.
     *
     * <p>Deliberately equal to the request-body bound enforced by the filter in {@code SecurityConfig}, so the
     * two agree rather than leaving a window in which one permits what the other refuses. It is not redundant
     * with that filter: this one also governs bodies that reach the parser by another route, and it makes the
     * limit visible to a reader of this class rather than only to a reader of the security configuration.
     */
    public static final int MAX_JSON_DOCUMENT_LENGTH = 16384;

    /**
     * Logger for this configuration. A {@code static final} holder, so no mutable static state exists here.
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(WebConfig.class);

    /**
     * Ten raised to {@link #AMOUNT_MASK_INTEGER_DIGITS}: the modulus that discards the ninth integer digit the
     * edited picture cannot render.
     */
    private static final BigDecimal AMOUNT_MASK_MODULUS = new BigDecimal("100000000");

    /**
     * Total digit positions the edited picture renders: eight integer plus two decimal.
     */
    private static final int AMOUNT_MASK_TOTAL_DIGITS = AMOUNT_MASK_INTEGER_DIGITS + AMOUNT_SCALE;

    /**
     * Lowest ASCII digit accepted by either parser.
     */
    private static final char ASCII_ZERO = '0';

    /**
     * Highest ASCII digit accepted by either parser.
     */
    private static final char ASCII_NINE = '9';

    /**
     * Single-character forms used when composing and decomposing an edited amount.
     */
    private static final String ZERO_DIGIT = "0";

    /**
     * The sign the edited picture emits for a value that is not negative. The picture leads with {@code +}, so
     * the sign is mandatory and a positive amount renders a plus rather than a blank.
     */
    private static final String PLUS_SIGN = "+";

    /**
     * The sign the edited picture emits for a negative value.
     */
    private static final String MINUS_SIGN = "-";

    /**
     * Decimal point of the edited picture, and the only non-digit the currency-aware parser retains.
     */
    private static final String DECIMAL_POINT = ".";

    /**
     * Character form of {@link #DECIMAL_POINT}, for scanning.
     */
    private static final char DECIMAL_POINT_CHAR = '.';

    /**
     * Group separator the currency-aware parser tolerates and discards, matching the thousands separator
     * {@code FUNCTION NUMVAL-C} accepts.
     */
    private static final String GROUP_SEPARATOR = ",";

    /**
     * Currency symbol the currency-aware parser tolerates and discards, matching the default COBOL currency
     * sign.
     */
    private static final String CURRENCY_SYMBOL = "$";

    /**
     * Trailing credit indicator; {@code FUNCTION NUMVAL-C} reads it as a negative value.
     */
    private static final String CREDIT_INDICATOR = "CR";

    /**
     * Trailing debit indicator; {@code FUNCTION NUMVAL-C} reads it as a negative value too.
     */
    private static final String DEBIT_INDICATOR = "DB";

    /**
     * Length shared by {@link #CREDIT_INDICATOR} and {@link #DEBIT_INDICATOR}, so the same value governs both
     * the guard that keeps a bare indicator from being read as a number and the substring that removes it.
     */
    private static final int INDICATOR_LENGTH = 2;

    /**
     * Empty replacement used when discarding a tolerated separator.
     */
    private static final String EMPTY = "";

    /**
     * Rejection message for an identifier that is present but not made entirely of ASCII digits. It names no
     * value, because the value may be cardholder data.
     */
    private static final String IDENTIFIER_NOT_NUMERIC_MESSAGE =
            "Identifier must be Numeric: only the ASCII digits 0 through 9 are accepted, with no sign, "
                    + "currency symbol, group separator, decimal point or whitespace";

    /**
     * Rejection message for an identifier whose significant digits exceed the widest identifier picture.
     */
    private static final String IDENTIFIER_TOO_LONG_MESSAGE =
            "Identifier is too long: at most " + IDENTIFIER_MAX_DIGITS + " significant digits are accepted";

    /**
     * Rejection message for an amount that survives currency normalisation but is still not a plain decimal.
     */
    private static final String AMOUNT_NOT_NUMERIC_MESSAGE =
            "Transaction amount is not a valid decimal: an optional sign, an optional currency symbol, "
                    + "optional group separators, digits and at most one decimal point are accepted";

    /**
     * Rejection message for an amount carrying two signs.
     */
    private static final String AMOUNT_DOUBLE_SIGN_MESSAGE =
            "Transaction amount carries more than one sign";

    /**
     * Rejection message for an amount whose magnitude exceeds the capacity of the legacy numeric field.
     */
    private static final String AMOUNT_OUT_OF_RANGE_MESSAGE =
            "Transaction amount exceeds the " + AMOUNT_INTEGER_DIGITS + " integer digits of the legacy field";

    /**
     * Rejection message for a navigation action that was not supplied at all.
     *
     * <p>Published so that every operation carrying a navigation intent refuses a blank action in the same
     * words. It names no submitted value, because the value is untrusted input.
     */
    public static final String ACTION_BLANK_MESSAGE =
            "A navigation action must be supplied";

    /**
     * Rejection message for a navigation action token the operation does not declare.
     *
     * <p>Published on the same terms as {@link #ACTION_BLANK_MESSAGE}. It never repeats the rejected token:
     * echoing an untrusted value into a response body is how a diagnostic becomes an injection vector.
     */
    public static final String ACTION_UNKNOWN_MESSAGE =
            "Unrecognised navigation action";

    /**
     * The one strict identifier converter. Constructed here, published by
     * {@link #strictIdentifierConverter()} and registered by {@link #addFormatters(FormatterRegistry)}.
     */
    private final StrictIdentifierConverter strictIdentifierConverter;

    /** The one currency-aware amount converter, on the same terms. */
    private final CurrencyAwareAmountConverter currencyAwareAmountConverter;

    /** The one edited-amount printer, on the same terms. */
    private final EditedAmountPrinter editedAmountPrinter;

    /**
     * Creates the configuration and the three parsing objects it owns.
     *
     * <p>Two facts are made auditable here rather than implied. First, this class has <b>no injected
     * collaborators</b>: it needs no service, no repository, no clock and no property, and inventing a
     * dependency to demonstrate constructor injection would introduce unused state, which the no-dead-code
     * standard forbids. Second, the three parsing objects are created <b>once, here</b>, and both the bean
     * methods and the registry registration hand out those same three instances.
     *
     * <p>Constructing them in the constructor rather than inside each bean method is deliberate and is the
     * point of the arrangement. A {@code @Configuration} class is normally CGLIB-subclassed so that a bean
     * method called twice returns one object, but relying on that interception to establish the identity
     * would make the identity a property of the container rather than of this class - untestable without a
     * context, and silently lost if the class were ever consumed directly. Holding the instances as final
     * fields makes "one object per parsing rule" true by construction, container or no container.
     *
     * <p>The three fields are immutable, stateless and documented thread safe, so sharing them is safe and
     * this class remains free of mutable state.
     *
     * <p>The class is intentionally not {@code final}: a {@code @Configuration} class is subclassed by CGLIB.
     */
    public WebConfig() {
        this.strictIdentifierConverter = new StrictIdentifierConverter();
        this.currencyAwareAmountConverter = new CurrencyAwareAmountConverter();
        this.editedAmountPrinter = new EditedAmountPrinter();
    }

    /**
     * Publishes the digits-only parser as the application's single instance of it.
     *
     * <p>It is a bean, and it returns the field this class constructed, because there must be exactly
     * <b>one</b> conversion path for {@code FUNCTION NUMVAL}. Before this became a bean the MVC registry held
     * one instance while {@code com.cardemo.service.transaction.TransactionAddService} constructed a second
     * privately, so the registered object was never the object that actually parsed a transaction amount or
     * card number - two copies of one rule, either of which could drift from the other. Returning the field
     * makes the registered instance and the injected instance the same object by construction, without
     * depending on {@code @Configuration} proxying to make it so.
     *
     * <p>Side effects: none. The returned object is immutable and stateless.
     *
     * @return the single strict identifier converter; never null
     */
    @Bean
    public StrictIdentifierConverter strictIdentifierConverter() {
        return this.strictIdentifierConverter;
    }

    /**
     * Publishes the currency-tolerant parser as the application's single instance of it.
     *
     * <p>Same rationale as {@link #strictIdentifierConverter()}: one {@code FUNCTION NUMVAL-C} rule, one
     * object implementing it, shared by request binding and by the services that must apply it to a JSON
     * body - which request binding never sees.
     *
     * <p>Side effects: none. The returned object is immutable and stateless.
     *
     * @return the single currency-aware amount converter; never null
     */
    @Bean
    public CurrencyAwareAmountConverter currencyAwareAmountConverter() {
        return this.currencyAwareAmountConverter;
    }

    /**
     * Publishes the edited-amount printer as the application's single instance of it.
     *
     * <p>Same rationale as the two converters. The outbound half of the {@code PIC +99999999.99} round trip
     * has to render identically wherever it is applied, and one shared object is the only way to guarantee
     * that without asserting it.
     *
     * <p>Side effects: none. The returned object is immutable and stateless.
     *
     * @return the single edited-amount printer; never null
     */
    @Bean
    public EditedAmountPrinter editedAmountPrinter() {
        return this.editedAmountPrinter;
    }

    /**
     * Registers the two numeric converters and the edited-amount printer, using the published bean instances
     * rather than fresh copies.
     *
     * <p>Registration order is fixed by this method body rather than by any collection's iteration order, so it
     * is identical on every run and on every JVM. The two numeric converters are labelled in the body so an
     * audit of the "exactly two numeric converters" contract can be performed by reading it.
     *
     * <p><b>The three registered objects are the injected beans.</b> That identity is the point of the change:
     * a caller that injects {@link StrictIdentifierConverter} and a request that binds through the MVC
     * conversion service now run the same code on the same object, so there is exactly one parser per COBOL
     * intrinsic in the whole application and no second copy that could be maintained separately.
     *
     * <p>The printer is registered through {@code addPrinter} rather than as a full formatter on purpose. A
     * formatter would supply both a printer and a parser for {@link BigDecimal} and would therefore supersede
     * {@link CurrencyAwareAmountConverter}, leaving one of the two registrations unreachable. Splitting the
     * responsibilities keeps the inbound direction with the converter, where the {@code NUMVAL-C} semantics
     * live, and the outbound direction with the printer, where the display picture lives.
     *
     * <p>Named nested classes are used rather than lambdas because {@code addConverter(Converter)} resolves the
     * source and target types by reflecting over the implemented generic interface, and a lambda carries no
     * such type information; passing one fails at startup with an inability to determine the source and target
     * type.
     *
     * <p><b>No navigation-action converter is registered, and no navigation-action type exists.</b> One did,
     * and it had no consumer anywhere in the application: every operation that carries a navigation intent
     * declares its own closed set of actions and now validates the submitted token itself, exactly, so that no
     * alias, no case variant and no surrounding whitespace is accepted. A converter that normalised its input
     * would have re-introduced precisely the coercion those operations exist to refuse, so it was removed
     * rather than wired.
     *
     * <p>Side effects: this method mutates only the registry it is handed, which Spring owns and calls once
     * during context refresh. It performs no input or output, opens no resource and starts no thread.
     *
     * @param registry the MVC formatter registry supplied by Spring during context refresh; never null
     */
    @Override
    public void addFormatters(final FormatterRegistry registry) {

        // NUMERIC CONVERTER 1 OF 2 - plain FUNCTION NUMVAL, for identifiers and card numbers.
        registry.addConverter(this.strictIdentifierConverter);

        // NUMERIC CONVERTER 2 OF 2 - FUNCTION NUMVAL-C, for amounts and nothing else.
        registry.addConverter(this.currencyAwareAmountConverter);

        // THE EDITED-AMOUNT FORMATTER - the outbound half of the PIC +99999999.99 round trip.
        registry.addPrinter(this.editedAmountPrinter);

        LOGGER.debug(
                "Registered CardDemo web converters: 2 numeric (strict identifier, currency-aware amount) "
                        + "and 1 edited-amount printer on mask {}, all three being the published singleton "
                        + "beans rather than private copies",
                AMOUNT_EDITED_MASK);
    }

    /**
     * Bounds what the JSON parser will accept from a request body, at the parser rather than at the type.
     *
     * <p><strong>Finding, severity High - remediated here, together with the request-body bound in
     * {@code SecurityConfig}.</strong> The two are complementary and neither substitutes for the other. That
     * one caps how many BYTES a caller may make this application read; this one caps what a document of legal
     * size may ask the parser to DO. A 16 KB body is not large, and it is still ample room for a nesting depth
     * of thousands - and it is depth, not size, that turns parsing into recursion deep enough to exhaust the
     * stack. A body bound alone would leave that open; parser constraints alone would leave an unbounded read
     * open.
     *
     * <p>These limits cannot be expressed as properties. Spring Boot exposes much of Jackson through
     * {@code spring.jackson.*}, but {@link StreamReadConstraints} is a property of the underlying
     * {@code JsonFactory} and has no property binding, which is why this is configured in code.
     *
     * <p>Every value below is derived from the field contracts of {@code app/cpy-bms}, not chosen for
     * roundness; the reasoning for each is on its constant. The converters are reached through the list Spring
     * hands over, so the constraints apply to the very {@code ObjectMapper} that binds
     * {@code @RequestBody} - configuring a mapper of our own would leave the one actually in use untouched.
     *
     * <p>Side effects: mutates the parser factory of the JSON converters in the list Spring owns, once, during
     * context refresh. Performs no input or output and starts no thread.
     *
     * @param converters the message converters Spring has already assembled; never null
     */
    @Override
    public void extendMessageConverters(final List<HttpMessageConverter<?>> converters) {

        final StreamReadConstraints constraints = StreamReadConstraints.builder()
                .maxNestingDepth(MAX_JSON_NESTING_DEPTH)
                .maxStringLength(MAX_JSON_STRING_LENGTH)
                .maxNumberLength(MAX_JSON_NUMBER_LENGTH)
                .maxNameLength(MAX_JSON_NAME_LENGTH)
                .maxDocumentLength(MAX_JSON_DOCUMENT_LENGTH)
                .build();

        int constrained = 0;
        for (final HttpMessageConverter<?> converter : converters) {
            if (converter instanceof AbstractJackson2HttpMessageConverter jacksonConverter) {
                jacksonConverter.getObjectMapper().getFactory().setStreamReadConstraints(constraints);
                constrained++;
            }
        }

        LOGGER.debug(
                "Applied JSON stream-read constraints to {} Jackson converter(s): nesting depth {}, string "
                        + "length {}, number length {}, name length {}, document length {}",
                constrained,
                MAX_JSON_NESTING_DEPTH,
                MAX_JSON_STRING_LENGTH,
                MAX_JSON_NUMBER_LENGTH,
                MAX_JSON_NAME_LENGTH,
                MAX_JSON_DOCUMENT_LENGTH);
    }

    /**
     * Reports whether every character of a value is an ASCII digit, which is what COBOL's {@code IS NUMERIC}
     * class test asks of a display item.
     *
     * <p>The digit range is written out rather than delegated to {@link Character#isDigit(char)} on purpose,
     * and the distinction is a correctness and a security one rather than a stylistic one.
     * {@link Character#isDigit(char)} is true for every decimal digit in Unicode, so it accepts Arabic-Indic
     * and other non-ASCII digit forms - and {@link Long#parseLong(String)} accepts them too, because it
     * consults the same table. A guard written with {@link Character#isDigit(char)} would therefore let a
     * non-ASCII digit string through and convert it to a number that no 3270 terminal could ever have sent.
     * The legacy class test admits only the characters zero through nine, so this one does the same.
     *
     * @param candidate the value to test; must not be null and is expected to be non-empty
     * @return true when {@code candidate} is non-empty and consists solely of the characters 0 through 9
     */
    private static boolean isAsciiDigits(final String candidate) {
        if (candidate.isEmpty()) {
            return false;
        }
        for (int index = 0; index < candidate.length(); index++) {
            final char character = candidate.charAt(index);
            if (character < ASCII_ZERO || character > ASCII_NINE) {
                return false;
            }
        }
        return true;
    }

    /**
     * Reports whether a value is a plain decimal: ASCII digits with at most one decimal point, and at least one
     * digit. No sign, no separator and no whitespace, because the currency-aware parser has already removed
     * every one of those before calling this.
     *
     * @param candidate the value to test; must not be null
     * @return true when {@code candidate} holds at least one ASCII digit and no character other than an ASCII
     * digit or a single decimal point
     */
    private static boolean isPlainDecimal(final String candidate) {
        boolean pointSeen = false;
        boolean digitSeen = false;
        for (int index = 0; index < candidate.length(); index++) {
            final char character = candidate.charAt(index);
            if (character == DECIMAL_POINT_CHAR) {
                if (pointSeen) {
                    return false;
                }
                pointSeen = true;
            } else if (character >= ASCII_ZERO && character <= ASCII_NINE) {
                digitSeen = true;
            } else {
                return false;
            }
        }
        return digitSeen;
    }

    /**
     * Numeric converter 1 of 2: the strict, digits-only parser for identifiers and card numbers.
     *
     * <p>It reproduces plain {@code FUNCTION NUMVAL} as the corpus uses it. Both of its call sites are in
     * {@code app/cbl/COTRN02C.cbl} and both are guarded by an explicit class test rather than by a
     * numeric-test intrinsic:</p>
     * <ul>
     *   <li>{@code app/cbl/COTRN02C.cbl}:197 tests {@code IF ACTIDINI OF COTRN2AI IS NOT NUMERIC} and, on
     *       failure, stages the message {@code 'Account ID must be Numeric...'} at :199-200; :204-205 then
     *       runs {@code COMPUTE WS-ACCT-ID-N = FUNCTION NUMVAL(ACTIDINI OF COTRN2AI)} into
     *       {@code 05 WS-ACCT-ID-N PIC 9(11)} at :55.</li>
     *   <li>{@code app/cbl/COTRN02C.cbl}:211 tests {@code IF CARDNINI OF COTRN2AI IS NOT NUMERIC} and stages
     *       {@code 'Card Number must be Numeric...'} at :213-214; :218-219 then runs
     *       {@code COMPUTE WS-CARD-NUM-N = FUNCTION NUMVAL(CARDNINI OF COTRN2AI)} into
     *       {@code 05 WS-CARD-NUM-N PIC 9(16)} at :56.</li>
     * </ul>
     *
     * <p>The target type is {@code Long} because that is what the model uses for a numeric identifier -
     * {@code accountId} and {@code customerId} are both {@code Long} on the account, card, cross-reference and
     * customer entities - and because sixteen digits fit a {@code long} with room to spare. A card number
     * remains a fixed-width {@code String} in the model, faithfully to {@code PIC X(16)}, so this converter
     * governs the numeric identifiers while the digits-only rule for the card number itself is enforced by the
     * field constraint on the data-transfer object that declares it.
     *
     * <p><b>Why digits only, structurally.</b> {@code app/cpy/CVCRD01Y.cpy}:34-42 redefines each of the three
     * character identifier fields as an <em>unsigned</em> numeric picture - {@code CC-ACCT-ID PIC X(11)}
     * redefined {@code PIC 9(11)}, {@code CC-CARD-NUM PIC X(16)} redefined {@code PIC 9(16)} and
     * {@code CC-CUST-ID PIC X(09)} redefined {@code PIC 9(9)}. An unsigned display picture has no room for a
     * sign, a separator or a point, so the redefinition itself is proof that these fields admit digits and
     * nothing else. Leading zeros are ordinary rather than exceptional: the first account record in
     * {@code app/data/ASCII/acctdata.txt} begins {@code 00000000001}, so they are accepted and are not counted
     * against the width limit.
     *
     * <p><b>Rejections.</b> Any character outside the range zero to nine - a sign, a currency symbol, a group
     * separator, a decimal point, whitespace anywhere in the value, or a non-ASCII digit form - is rejected
     * with a {@code com.cardemo.exception.ValidationException}. Whitespace is rejected rather than trimmed
     * because COBOL's class test rejects it too: a display item holding a space is not numeric.
     *
     * <p><b>Blank input binds to null, and that is deliberate.</b> Before either class test runs, the source
     * asks whether the field was entered at all -
     * {@code app/cbl/COTRN02C.cbl}:196 {@code WHEN ACTIDINI OF COTRN2AI NOT = SPACES AND LOW-VALUES} and
     * :210 for the card number - and routes an entirely blank screen to a different message,
     * {@code 'Account or Card Number must be entered...'} at :226-227. A converter is handed a value but not
     * the name of the parameter it came from, so it cannot produce that field-specific message; returning null
     * for blank input hands the decision to the layer that does know the field, and matches what Spring's own
     * string-to-number conversion does with a blank. This is not a fallback to zero - zero is a real
     * identifier, null is the absence of one.
     *
     * <p><b>Fixed-width padding is not reproduced.</b> On a 3270 screen a short value typed into
     * {@code PIC X(11)} arrives space-padded and therefore fails the class test, so the legacy system in effect
     * required the full field width. A URL path segment has no width to pad, so that consequence is a screen
     * artefact with no counterpart here; severity <b>Low</b>. Where a specific width genuinely matters it is
     * asserted by the data-transfer object that declares the field.
     *
     * <p>Stateless, immutable and safe to share across request threads.
     */
    public static final class StrictIdentifierConverter implements Converter<String, Long> {

        /**
         * Creates the converter. It holds no state.
         */
        public StrictIdentifierConverter() {
            // Stateless: nothing to initialise.
        }

        /**
         * Converts an unsigned decimal identifier into its numeric value.
         *
         * @param source the raw parameter value. Null and entirely blank values are accepted and yield null.
         * @return the identifier value, or null when {@code source} is null or blank
         * @throws ValidationException when {@code source} is non-blank and is not composed solely of the ASCII
         * digits 0 through 9, or when its significant digits exceed {@link WebConfig#IDENTIFIER_MAX_DIGITS}.
         * The message never contains the rejected value, because that value may be a card number.
         */
        @Override
        public Long convert(final String source) {
            if (source == null || source.isBlank()) {
                // COTRN02C.cbl:196 and :210 - the SPACES / LOW-VALUES sentinel meaning "not entered".
                return null;
            }

            // COTRN02C.cbl:197 and :211 - IS NOT NUMERIC, applied to the value as received and not to a
            // trimmed copy, because a display item containing a space fails the COBOL class test.
            if (!isAsciiDigits(source)) {
                throw new ValidationException(IDENTIFIER_NOT_NUMERIC_MESSAGE);
            }

            // Leading zeros are legitimate zero padding, so bound the significant digits rather than the raw
            // length. At least one digit is always retained, so an all-zero value converts to zero.
            int firstSignificant = 0;
            while (firstSignificant < source.length() - 1 && source.charAt(firstSignificant) == ASCII_ZERO) {
                firstSignificant++;
            }
            final String significant = source.substring(firstSignificant);
            if (significant.length() > IDENTIFIER_MAX_DIGITS) {
                throw new ValidationException(IDENTIFIER_TOO_LONG_MESSAGE);
            }

            try {
                return Long.valueOf(significant);
            } catch (final NumberFormatException cause) {
                // Unreachable in practice: the checks above admit at most sixteen ASCII digits, which cannot
                // overflow a long. The guard is kept, and the root cause preserved, because an input path is
                // exactly where an unreachability argument must not be relied upon.
                throw new ValidationException(IDENTIFIER_NOT_NUMERIC_MESSAGE, cause);
            }
        }
    }

    /**
     * Numeric converter 2 of 2: the currency-aware parser, for amounts and for nothing else.
     *
     * <p>It reproduces {@code FUNCTION NUMVAL-C}, which differs from plain {@code FUNCTION NUMVAL} in
     * tolerating a currency symbol and thousands separators. The corpus reserves it for money and the
     * distinction is deliberate: within {@code app/cbl/COTRN02C.cbl} the identifier conversions at :204 and
     * :218 use the plain intrinsic while the amount conversions use the currency-aware one, at :383-384
     * {@code COMPUTE WS-TRAN-AMT-N = FUNCTION NUMVAL-C(TRNAMTI OF COTRN2AI)} and again at :456-457 before
     * :458 moves the result into the transaction record.
     *
     * <p><b>The amount conversions are unguarded, and this converter is stricter on purpose.</b> Neither :383
     * nor :456 is preceded by a numeric test - the {@code EVALUATE} that ends at :381 falls through its
     * {@code WHEN OTHER ... CONTINUE} at :379-380 - and {@code FUNCTION NUMVAL-C} applied to spaces yields
     * zero. The legacy program would therefore post a zero-value transaction for a blank amount. Returning
     * null instead of zero is a labelled deviation: a silent zero on a financial field is precisely the
     * failure mode a converter must not introduce. Severity <b>Medium</b>, recorded for the repository
     * decision log with those two locators. In {@code app/cbl/COACTUPC.cbl} the money conversions <em>are</em>
     * guarded, each by a preceding {@code FUNCTION TEST-NUMVAL-C} - :1078 before :1080, :1092 before :1094,
     * :1106 before :1108, :1120 before :1122 and :1134 before :1136 - which is the behaviour this converter
     * generalises to every amount.
     *
     * <p><b>The date-component sites are not amounts.</b> The six further {@code FUNCTION NUMVAL-C} sites in
     * {@code app/cbl/CORPT00C.cbl} - :305, :309, :313, :317, :321 and :325 - convert screen date subfields
     * into the work fields {@code WS-NUM-99} and {@code WS-NUM-9999}, not money, and are modelled by the date
     * validation service rather than here.
     *
     * <p><b>What is tolerated.</b> Surrounding whitespace; one currency symbol, before or after a leading
     * sign; group separators anywhere, which are discarded; one sign, written leading or trailing; and a
     * trailing {@code CR} or {@code DB}, both of which the intrinsic reads as negative. At most one sign in
     * total is accepted, so a doubly-signed value is rejected rather than silently resolved.
     *
     * <p><b>What is rejected.</b> Anything that is not a plain decimal once those tolerated forms have been
     * removed, and any magnitude beyond {@link WebConfig#AMOUNT_MAX_MAGNITUDE}, which is the capacity of
     * {@code 05 WS-TRAN-AMT-N PIC S9(9)V99} at {@code app/cbl/COTRN02C.cbl}:58. A COBOL move into that field
     * would silently discard high-order digits; rejecting is a labelled deviation, taken because silently
     * discarding the high-order digits of an <em>inbound</em> amount corrupts money rather than merely
     * misrendering it.
     *
     * <p><b>No sign normalisation.</b> A negative amount is returned negative and is never replaced by its
     * magnitude. Negative amounts are real data - {@code app/data/ASCII/dailytran.txt} carries both overpunch
     * signs - and the posting logic accumulates them into the cycle-debit total, so an absolute value here
     * would corrupt the over-limit arithmetic downstream.
     *
     * <p>Stateless, immutable and safe to share across request threads.
     */
    public static final class CurrencyAwareAmountConverter implements Converter<String, BigDecimal> {

        /**
         * Creates the converter. It holds no state.
         */
        public CurrencyAwareAmountConverter() {
            // Stateless: nothing to initialise.
        }

        /**
         * Converts a currency-decorated decimal into an exact amount at scale {@value WebConfig#AMOUNT_SCALE}.
         *
         * @param source the raw parameter value. Null and entirely blank values are accepted and yield null,
         * rather than the zero the unguarded legacy compute would have produced.
         * @return the signed amount at scale {@value WebConfig#AMOUNT_SCALE}, or null when {@code source} is
         * null or blank
         * @throws ValidationException when {@code source} carries more than one sign, is not a plain decimal
         * once the tolerated currency decoration has been removed, or exceeds the capacity of the legacy
         * numeric field. The message never contains the rejected value.
         */
        @Override
        public BigDecimal convert(final String source) {
            if (source == null || source.isBlank()) {
                return null;
            }

            // Locale.ROOT so that CR and DB are recognised identically under every host locale.
            String work = source.trim().toUpperCase(Locale.ROOT);
            boolean negative = false;
            boolean signSeen = false;

            // Trailing credit or debit indicator. Both mean negative to FUNCTION NUMVAL-C. The length guard
            // keeps a bare "CR" or "DB" from being read as a signed empty value.
            if (work.length() > INDICATOR_LENGTH
                    && (work.endsWith(CREDIT_INDICATOR) || work.endsWith(DEBIT_INDICATOR))) {
                negative = true;
                signSeen = true;
                work = work.substring(0, work.length() - INDICATOR_LENGTH).trim();
            } else if (work.length() > 1 && (work.endsWith(MINUS_SIGN) || work.endsWith(PLUS_SIGN))) {
                negative = work.endsWith(MINUS_SIGN);
                signSeen = true;
                work = work.substring(0, work.length() - 1).trim();
            }

            // Leading sign, then a currency symbol, then a sign that followed the symbol: the intrinsic
            // accepts both "-$1,234.56" and "$-1,234.56", so both orders are handled explicitly.
            if (work.startsWith(MINUS_SIGN) || work.startsWith(PLUS_SIGN)) {
                if (signSeen) {
                    throw new ValidationException(AMOUNT_DOUBLE_SIGN_MESSAGE);
                }
                negative = work.startsWith(MINUS_SIGN);
                signSeen = true;
                work = work.substring(1).trim();
            }
            if (work.startsWith(CURRENCY_SYMBOL)) {
                work = work.substring(1).trim();
                if (work.startsWith(MINUS_SIGN) || work.startsWith(PLUS_SIGN)) {
                    if (signSeen) {
                        throw new ValidationException(AMOUNT_DOUBLE_SIGN_MESSAGE);
                    }
                    negative = work.startsWith(MINUS_SIGN);
                    // This is the last position at which a sign can legally appear, so signSeen is not
                    // updated: nothing reads it beyond this point and a dead store would only mislead.
                    work = work.substring(1).trim();
                }
            }
            work = work.replace(GROUP_SEPARATOR, EMPTY);

            if (!isPlainDecimal(work)) {
                throw new ValidationException(AMOUNT_NOT_NUMERIC_MESSAGE);
            }

            final BigDecimal magnitude;
            try {
                // Every sign has been removed above, so this value is never negative and needs no abs().
                magnitude = new BigDecimal(work).setScale(AMOUNT_SCALE, AMOUNT_ROUNDING_MODE);
            } catch (final NumberFormatException cause) {
                // Unreachable in practice once isPlainDecimal has passed, but the cause is preserved rather
                // than assumed away, for the same reason as in the identifier converter.
                throw new ValidationException(AMOUNT_NOT_NUMERIC_MESSAGE, cause);
            }

            // compareTo rather than equals: equals would also compare scale and would make 1.0 and 1.00
            // different amounts.
            if (magnitude.compareTo(AMOUNT_MAX_MAGNITUDE) > 0) {
                throw new ValidationException(AMOUNT_OUT_OF_RANGE_MESSAGE);
            }

            return negative ? magnitude.negate() : magnitude;
        }
    }

    /**
     * The edited-amount formatter: the outbound half of the legacy display round trip.
     *
     * <p>It reproduces {@code 05 WS-TRAN-AMT-E PIC +99999999.99 VALUE ZEROS} at
     * {@code app/cbl/COTRN02C.cbl}:59, which the program reaches through the two-step move at :385
     * {@code MOVE WS-TRAN-AMT-N TO WS-TRAN-AMT-E} and :386 {@code MOVE WS-TRAN-AMT-E TO TRNAMTI OF COTRN2AI}.
     * The same picture is declared a second time in that program on {@code 05 WS-TRAN-AMT} at :53.
     *
     * <p>The output is always exactly {@value WebConfig#AMOUNT_EDITED_LENGTH} characters: a mandatory sign,
     * {@value WebConfig#AMOUNT_MASK_INTEGER_DIGITS} zero-filled integer digits, a decimal point and
     * {@value WebConfig#AMOUNT_SCALE} decimals. The sign is mandatory because the picture leads with a plus,
     * so a positive amount renders {@code +} rather than a blank and zero renders {@code +00000000.00}.
     *
     * <p><b>The picture is one digit narrower than the field it renders, and that is preserved.</b>
     * {@code 05 WS-TRAN-AMT-N} at {@code app/cbl/COTRN02C.cbl}:58 is {@code PIC S9(9)V99} and holds
     * {@value WebConfig#AMOUNT_INTEGER_DIGITS} integer digits, while the picture at :59 provides
     * {@value WebConfig#AMOUNT_MASK_INTEGER_DIGITS}. A COBOL move between them discards the high-order digit,
     * so a magnitude of 100000000.00 or more renders without it and 123456789.99 renders as
     * {@code +23456789.99}. {@link WebConfig#AMOUNT_MASK_MODULUS} reproduces that discard exactly. This is a
     * legacy display defect, severity <b>Medium</b>, recorded for the repository decision log against those two
     * locators. It is reproduced and not corrected, because the parity comparison is made on the rendered
     * characters.
     *
     * <p><b>Locale independence by construction.</b> The locale argument is accepted because the interface
     * requires it and is then deliberately ignored: the digits are taken from
     * {@link BigDecimal#unscaledValue()} and assembled by concatenation, so there is no formatter, no symbol
     * lookup and nothing for a locale to influence. No {@code java.text.DecimalFormat} is created here - such
     * an instance is mutable and unsafe to share between request threads - and consequently there is nothing to
     * share incorrectly.
     *
     * <p><b>Where this printer applies.</b> It renders a decimal through the MVC conversion service. It is not
     * involved in JSON serialisation, which Jackson performs under the plain-decimal setting the application
     * configuration resource owns, so a response body carries the unmasked value and this picture is applied
     * only where a conversion to text is explicitly requested.
     *
     * <p>Stateless, immutable and safe to share across request threads.
     */
    public static final class EditedAmountPrinter implements Printer<BigDecimal> {

        /**
         * Creates the printer. It holds no state.
         */
        public EditedAmountPrinter() {
            // Stateless: nothing to initialise.
        }

        /**
         * Renders an amount on the picture {@value WebConfig#AMOUNT_EDITED_MASK}.
         *
         * @param amount the amount to render. Null yields an empty string; the conversion service does not pass
         * null, so the branch is defensive. A screen would show an absent value as spaces, but screen
         * emulation is out of scope and no width can be assumed at this layer.
         * @param locale required by the interface and deliberately unused, because the rendering is locale
         * independent by construction
         * @return exactly {@value WebConfig#AMOUNT_EDITED_LENGTH} characters, or an empty string when
         * {@code amount} is null
         */
        @Override
        public String print(final BigDecimal amount, final Locale locale) {
            if (amount == null) {
                return EMPTY;
            }

            final BigDecimal scaled = amount.setScale(AMOUNT_SCALE, AMOUNT_ROUNDING_MODE);

            // signum rather than a comparison against zero, so the sign is decided without constructing a
            // second BigDecimal and without any dependence on scale.
            final String sign = scaled.signum() < 0 ? MINUS_SIGN : PLUS_SIGN;

            // abs() first so the magnitude is unsigned and the sign is carried by the picture alone; the
            // remainder against ten to the eighth is what discards the ninth integer digit, exactly as the
            // COBOL move at :385 does.
            final BigDecimal magnitude = scaled.abs()
                    .remainder(AMOUNT_MASK_MODULUS)
                    .setScale(AMOUNT_SCALE, AMOUNT_ROUNDING_MODE);

            final String digits = magnitude.unscaledValue().toString();

            // The remainder bounds the magnitude below ten to the eighth, so at scale two the unscaled value
            // has at most AMOUNT_MASK_TOTAL_DIGITS digits and the pad count cannot go negative. Math.max keeps
            // that reasoning from becoming load bearing.
            final String padded =
                    ZERO_DIGIT.repeat(Math.max(0, AMOUNT_MASK_TOTAL_DIGITS - digits.length())) + digits;

            return new StringBuilder(AMOUNT_EDITED_LENGTH)
                    .append(sign)
                    .append(padded, 0, AMOUNT_MASK_INTEGER_DIGITS)
                    .append(DECIMAL_POINT)
                    .append(padded, AMOUNT_MASK_INTEGER_DIGITS, padded.length())
                    .toString();
        }
    }
}
