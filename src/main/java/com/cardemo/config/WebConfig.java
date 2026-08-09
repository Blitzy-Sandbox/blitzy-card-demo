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

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.BeanDeserializerModifier;
import com.fasterxml.jackson.databind.deser.std.DelegatingDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.StringJoiner;
import java.util.UUID;

import org.apache.catalina.Container;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.core.StandardHost;
import org.apache.catalina.valves.ErrorReportValve;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.format.FormatterRegistry;
import org.springframework.format.Printer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.AbstractJackson2HttpMessageConverter;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.security.web.firewall.RequestRejectedHandler;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.support.DefaultHandlerExceptionResolver;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.cardemo.exception.ValidationException;
import com.cardemo.observability.CorrelationIdFilter;

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
 *   <li><b>No message converter, and no second object mapper <em>configuration</em>.</b> The auto-configured
 *       converter is sufficient, so registering one would duplicate configuration that the application
 *       configuration resource owns. What {@link #extendMessageConverters(List)} does hand each Jackson
 *       converter is a {@link com.fasterxml.jackson.databind.ObjectMapper#copy()} of the shared bean carrying
 *       one additional inbound screen - not a mapper configured here. Every {@code spring.jackson.*} setting is
 *       inherited by that copy rather than restated, and the copy exists only so the screen cannot reach the
 *       three consumers of the shared bean that read something other than an inbound request body.</li>
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
     * The sentence every control-character refusal publishes, {@value}.
     *
     * <p><strong>Finding, severity High - remediated here.</strong> The 3270 data stream a BMS map received
     * could not carry a C0 control character: the terminal transmitted a field's declared width in the host
     * code page, and the one non-printing value the corpus reasons about is {@code LOW-VALUES}, which
     * {@code app/cbl/COACTUPC.cbl:1290} sets deliberately to mean "the operator did not transmit this field".
     * HTTP and JSON impose no such limit, so a caller may put {@code U+0000} inside any string, and
     * PostgreSQL's UTF-8 encoding cannot store one: the driver refuses the bind with {@code SQLSTATE 22021},
     * which surfaced as an input-output failure or an abend - a client error reported as a store failure,
     * which Rule 1 Clause A forbids.
     *
     * <p>The sentence names neither the value nor the offending character. The request DTOs of the operations
     * this guards carry a social security number, a date of birth, two telephone numbers and, on the
     * administration routes, a password, so a refusal must not quote what arrived. The offending property or
     * parameter name is published - that is a schema name the caller already knows - and the position is
     * written to the log.
     *
     * <p><strong>One value is admitted deliberately, and only in a request body:</strong> a string composed
     * entirely of {@code NUL} characters is the corpus's {@code LOW-VALUES}, which means "the operator did not
     * transmit this field". See {@link #isLowValues(String)} for the field contract that requires it. The
     * request line takes no such exception, because a URL has no untransmitted-field convention.
     */
    public static final String CONTROL_CHARACTER_REJECTION_MESSAGE =
            "Value must not contain control characters";

    /**
     * The exclusive upper bound of the C0 control range, namely 32.
     *
     * <p>Every code point below {@code U+0020} is a C0 control. Written as the boundary rather than as a set
     * so no member can be forgotten: {@code U+0000} is the one PostgreSQL refuses outright, but a carriage
     * return or a line feed inside a fixed-width field is equally unrepresentable on a 3270 and equally
     * capable of splitting one log record into two.
     */
    private static final int FIRST_PRINTABLE_CHARACTER = 0x20;

    /**
     * The DEL control character, {@code U+007F}.
     *
     * <p>Excluded alongside the C0 range because it is a control character that sits <em>above</em> the
     * printable ASCII block and so is missed by a bound test alone.
     */
    private static final char DELETE_CHARACTER = 0x7F;

    /**
     * The field name published when a control character is found in the request path rather than in a named
     * parameter, {@value}.
     */
    private static final String REQUEST_PATH_FIELD_NAME = "path";

    /**
     * The name published when a control character is found in a query-parameter <em>name</em>, {@value}.
     *
     * <p>A parameter whose own name is unusable has no name worth publishing, so the location is published
     * instead. Echoing the name back would relay attacker-chosen bytes onto the response.
     */
    private static final String QUERY_PARAMETER_FIELD_NAME = "queryParameter";

    /** The separator between query-string pairs, {@value}. */
    private static final String QUERY_PAIR_SEPARATOR = "&";

    /** The separator between a query-parameter name and its value, {@value}. */
    private static final char QUERY_VALUE_SEPARATOR = '=';

    /** The value {@link #indexOfControlCharacter(String)} returns when a value is clean, {@value}. */
    private static final int NO_CONTROL_CHARACTER = -1;

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
     * hands over, so the constraints reach the very mapper that binds {@code @RequestBody} rather than one
     * configured on the side, which would have left the one actually in use untouched.
     *
     * <p><strong>Finding F-1, severity High - the body half of the control-character screen is registered
     * here.</strong> Its request-line half is {@link #addInterceptors(InterceptorRegistry)}; both refuse the
     * same character set for the reason recorded on {@link #CONTROL_CHARACTER_REJECTION_MESSAGE}. A
     * {@code U+0000} inside a JSON string used to travel unexamined from the body into a character column,
     * where PostgreSQL refused the byte sequence and the refusal surfaced as an input-output failure or an
     * abend rather than as the field-level {@code 400} it is.
     *
     * <p><strong>Why the screen rides on a copy of the mapper and not on the mapper itself - read this before
     * simplifying it away.</strong> {@link AbstractJackson2HttpMessageConverter#getObjectMapper()} returns the
     * application-wide {@code ObjectMapper} bean, and three other consumers share that bean deliberately: the
     * queue boundary, whose message converter is handed it in {@code AwsConfig}; the snapshot token service,
     * which reads a token this application itself wrote; and the lookup table loader, which reads classpath
     * resources. None of the three reads an inbound request, and the queue boundary in particular is
     * <em>documented to carry hostile text safely by escaping it</em> rather than by refusing it. Registering
     * the screen on the shared bean silently changed all three - the queue boundary began refusing a body it is
     * contracted to round-trip. So the screen is registered on {@link ObjectMapper#copy()} of the shared bean
     * and that copy is handed straight back to this converter: the inbound binding gets the screen, and every
     * other consumer of the bean is left exactly as it was. The parser bounds are applied twice, once to the
     * shared bean so nothing that shares it loses a bound it already had, and once to the copy so the bound
     * cannot lapse on a library whose copy constructor stops carrying it.
     *
     * <p>Side effects: mutates the parser factory of the JSON converters in the list Spring owns and replaces
     * each such converter's mapper with a screened copy, once, during context refresh. The list itself is
     * neither reordered nor added to. Performs no input or output and starts no thread.
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
                final ObjectMapper shared = jacksonConverter.getObjectMapper();
                shared.getFactory().setStreamReadConstraints(constraints);

                // The second half of the control-character screen, whose first half is the request-line
                // interceptor of addInterceptors. It rides on a copy of the shared mapper rather than on the
                // shared mapper itself, and the copy is handed straight back to this converter, so the screen
                // reaches exactly one thing: the binding of an inbound @RequestBody. See the scoping note in
                // this method's documentation for the three other consumers of the shared bean that must not
                // inherit it. A copy rather than a mapper built here, so every setting spring.jackson.*
                // establishes is inherited rather than reasserted; the constraints are restated on the copy
                // because a bound that depends on a copy constructor carrying it is a bound that can silently
                // lapse on a library upgrade.
                final ObjectMapper inbound = shared.copy();
                inbound.getFactory().setStreamReadConstraints(constraints);
                inbound.registerModule(controlCharacterScreen());
                jacksonConverter.setObjectMapper(inbound);
                constrained++;
            }
        }

        LOGGER.debug(
                "Applied JSON stream-read constraints to {} Jackson converter(s): nesting depth {}, string "
                        + "length {}, number length {}, name length {}, document length {}; and gave each a "
                        + "screened copy of the shared mapper that refuses a control character in any inbound "
                        + "string value, leaving the shared mapper itself unscreened",
                constrained,
                MAX_JSON_NESTING_DEPTH,
                MAX_JSON_STRING_LENGTH,
                MAX_JSON_NUMBER_LENGTH,
                MAX_JSON_NAME_LENGTH,
                MAX_JSON_DOCUMENT_LENGTH);
    }

    /**
     * Builds the Jackson module that refuses a control character in any JSON string value.
     *
     * <p>It wraps whatever deserializer Jackson already chose for {@code String} rather than replacing it, so
     * every coercion rule the existing configuration establishes - single-value-as-array unwrapping, embedded
     * binary handling, the refusal of a structured value where a string is expected - is preserved exactly and
     * only the additional check is added. Replacing the deserializer outright would have re-specified all of
     * that by omission, and the operations' validation matrix depends on it.
     *
     * <p>The refusal is raised through the deserialization context, which is what makes Jackson decorate it
     * with the property path before it leaves the parser. Spring turns it into a read failure, and every
     * controller already answers a read failure with {@code 400} and its own envelope, writing the path to the
     * log rather than to the body.
     *
     * <p><strong>Register this on a mapper that binds inbound request bodies and on nothing else.</strong> The
     * module screens every {@code String} the mapper it is registered on will ever read, which is what the
     * inbound boundary wants and what no other reader does - see the scoping note on
     * {@link #extendMessageConverters(List)}.
     *
     * @return a module registering the screen; a fresh instance per call, so no mutable state is shared
     */
    private static Module controlCharacterScreen() {
        final SimpleModule module = new SimpleModule("CardDemoControlCharacterScreen");
        module.setDeserializerModifier(new BeanDeserializerModifier() {
            private static final long serialVersionUID = 1L;

            @Override
            public JsonDeserializer<?> modifyDeserializer(
                    final DeserializationConfig config,
                    final BeanDescription description,
                    final JsonDeserializer<?> deserializer) {

                if (String.class.equals(description.getBeanClass())) {
                    return new ControlCharacterFreeStringDeserializer(deserializer);
                }
                return deserializer;
            }
        });
        return module;
    }

    /**
     * Returns the index of the first C0 or DEL control character in a value, or {@link #NO_CONTROL_CHARACTER}
     * when there is none.
     *
     * <p>The index rather than a boolean, because the position is what an operator needs in the log to find the
     * offending byte in a payload that must not itself be logged. Iterating over {@code char} values is
     * sufficient and correct here: every code point this method refuses is in the Basic Multilingual Plane and
     * below {@code U+0080}, so no surrogate pair can hide one.
     *
     * @param candidate the value to scan; may be null, which is clean by definition because an absent value
     *                  carries no character at all
     * @return the zero-based index of the first refused character, or {@link #NO_CONTROL_CHARACTER}
     */
    private static int indexOfControlCharacter(final String candidate) {
        if (candidate == null) {
            return NO_CONTROL_CHARACTER;
        }
        for (int index = 0; index < candidate.length(); index++) {
            final char character = candidate.charAt(index);
            if (character < FIRST_PRINTABLE_CHARACTER || character == DELETE_CHARACTER) {
                return index;
            }
        }
        return NO_CONTROL_CHARACTER;
    }

    /**
     * Reports whether a value is the corpus's {@code LOW-VALUES}: non-empty and composed entirely of
     * {@code NUL} characters.
     *
     * <p><strong>This exception is a field contract, not a loophole, and the body half of the screen depends on
     * it.</strong> A 3270 field the operator did not transmit reached a BMS program as {@code LOW-VALUES}, and
     * the corpus tests for exactly that: {@code app/cbl/COBIL00C.cbl} gates its confirmation on
     * {@code IF CONFIRMI = LOW-VALUES OR SPACES}, and {@code app/cbl/COACTUPC.cbl:1290} sets a field to
     * {@code LOW-VALUES} deliberately to mean "not supplied". A JSON client that faithfully echoes an
     * untransmitted field therefore sends a value of {@code NUL} characters, and both that shape and an empty
     * string must read as "not supplied" - which is why
     * {@code com.cardemo.service.account.AccountUpdateService} normalises the same shape to null on its way in.
     * Refusing it would break the sentinel the seventeen operations are built on.
     *
     * <p>What is refused is a control character <em>mixed with other content</em> - {@code AB\u0000CD} - which
     * no terminal could have produced, which no field contract describes, and which PostgreSQL refuses to
     * store. The request line takes no such exception: a URL has no untransmitted-field convention, so a
     * {@code NUL} anywhere in a path or a query parameter is refused outright.
     *
     * @param candidate the value to test; must not be null
     * @return {@code true} when {@code candidate} is non-empty and every one of its characters is {@code NUL}
     */
    private static boolean isLowValues(final String candidate) {
        if (candidate.isEmpty()) {
            return false;
        }
        for (int index = 0; index < candidate.length(); index++) {
            if (candidate.charAt(index) != '\u0000') {
                return false;
            }
        }
        return true;
    }

    /**
     * Percent-decodes one query-string token, falling back to the token itself when the escaping is malformed.
     *
     * <p>A malformed escape is not this screen's concern: the container and the security firewall answer that,
     * and turning it into a validation refusal here would change the status of requests this remediation was
     * not asked to touch. Returning the raw token still lets the scan see a <em>literal</em> control character,
     * so the fallback narrows the screen rather than disabling it.
     *
     * @param token the raw token as it appeared in the query string; must not be null
     * @return the decoded token, or {@code token} unchanged when it cannot be decoded
     */
    private static String decodeQueryToken(final String token) {
        try {
            return URLDecoder.decode(token, StandardCharsets.UTF_8);
        } catch (final IllegalArgumentException malformedEscape) {
            return token;
        }
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

    /**
     * Refuses a C0 or DEL control character anywhere in the request line, before any operation is entered.
     *
     * <p>The request-line half of the screen documented on {@link #CONTROL_CHARACTER_REJECTION_MESSAGE}; the
     * body half is the Jackson module registered by {@link WebConfig#extendMessageConverters(java.util.List)}.
     * Three locations are scanned, in the order a reader would expect them to matter:</p>
     * <ol>
     *   <li>the request URI, so a control character reaching a path variable is refused;</li>
     *   <li>each query-parameter <em>name</em>, because a name is bound as text just as a value is;</li>
     *   <li>each query-parameter <em>value</em>, which is the location the transaction-detail operation was
     *       reached through.</li>
     * </ol>
     *
     * <p>The refusal is a {@code com.cardemo.exception.ValidationException}, so the answer is the controller's
     * own {@code 400} envelope carrying the parameter name in its {@code field} member - the same shape a
     * caller already receives when a service refuses a field, rather than a second dialect of refusal. The
     * value is never quoted back; the position goes to the log.
     *
     * <p>Stateless and therefore thread-safe: it holds no field, so one instance serves every request thread.
     */
    private static final class ControlCharacterRequestInterceptor implements HandlerInterceptor {

        /**
         * Creates the screen.
         *
         * <p>Stated explicitly rather than left implicit because the build's Javadoc gate treats an
         * undocumented default constructor as a warning and escalates every warning to a failure. There is
         * nothing to inject: the refused character set is a compile-time constant of the enclosing class.
         */
        private ControlCharacterRequestInterceptor() {
            super();
        }

        @Override
        public boolean preHandle(
                final HttpServletRequest request,
                final HttpServletResponse response,
                final Object handler) {

            screen(decodeQueryToken(request.getRequestURI()), REQUEST_PATH_FIELD_NAME);

            final String query = request.getQueryString();
            if (query == null || query.isEmpty()) {
                return true;
            }

            for (final String pair : query.split(QUERY_PAIR_SEPARATOR, -1)) {
                if (pair.isEmpty()) {
                    continue;
                }
                final int separator = pair.indexOf(QUERY_VALUE_SEPARATOR);
                final String rawName = separator < 0 ? pair : pair.substring(0, separator);
                final String name = decodeQueryToken(rawName);

                // The name is screened against a fixed location rather than against itself, so an unusable
                // name is never echoed onto the response.
                screen(name, QUERY_PARAMETER_FIELD_NAME);

                if (separator >= 0) {
                    screen(decodeQueryToken(pair.substring(separator + 1)), name);
                }
            }
            return true;
        }

        /**
         * Refuses one value when it carries a control character, and returns silently otherwise.
         *
         * @param candidate the decoded value to scan; may be null, which is clean
         * @param fieldName the name published in the refusal's {@code field} member; never the value
         * @throws ValidationException when {@code candidate} carries a C0 or DEL character
         */
        private static void screen(final String candidate, final String fieldName) {
            final int offendingIndex = indexOfControlCharacter(candidate);
            if (offendingIndex == NO_CONTROL_CHARACTER) {
                return;
            }
            LOGGER.warn("Refused a request with 400: {} carries a control character at index {}",
                    fieldName, offendingIndex);
            throw ValidationException.invalidField(fieldName, CONTROL_CHARACTER_REJECTION_MESSAGE);
        }
    }

    /**
     * Refuses a C0 or DEL control character in any JSON string value, wrapping the deserializer Jackson chose.
     *
     * <p>The body half of the screen documented on {@link #CONTROL_CHARACTER_REJECTION_MESSAGE}. It delegates
     * every decision about <em>how</em> a string is read and adds only the check, so the request DTOs' accepted
     * input is unchanged except for the characters that could never have reached a BMS field and cannot be
     * stored in a PostgreSQL character column.
     *
     * <p>The refusal is reported through the deserialization context so Jackson attaches the property path,
     * which reaches the log through the controller's read-failure handler. Neither the value nor the offending
     * character is put on the response, for the reason recorded on the message constant.
     */
    private static final class ControlCharacterFreeStringDeserializer extends DelegatingDeserializer {

        /** Serialisation identity, required because the base type is serializable. */
        private static final long serialVersionUID = 1L;

        /**
         * Wraps one string deserializer.
         *
         * @param delegate the deserializer Jackson selected for {@code String}; never null
         */
        ControlCharacterFreeStringDeserializer(final JsonDeserializer<?> delegate) {
            super(delegate);
        }

        @Override
        protected JsonDeserializer<?> newDelegatingInstance(final JsonDeserializer<?> replacement) {
            return new ControlCharacterFreeStringDeserializer(replacement);
        }

        @Override
        public Object deserialize(final JsonParser parser, final DeserializationContext context)
                throws IOException {

            final Object value = super.deserialize(parser, context);
            if (value instanceof String text) {
                final int offendingIndex = indexOfControlCharacter(text);
                if (offendingIndex != NO_CONTROL_CHARACTER && !isLowValues(text)) {
                    LOGGER.warn("Refused a request body with 400: a string value carries a control character "
                            + "at index {}", offendingIndex);
                    return context.reportInputMismatch(String.class, CONTROL_CHARACTER_REJECTION_MESSAGE);
                }
            }
            return value;
        }
    }

    // The framework boundary: refusals raised before, or instead of, a controller method
    //
    // Seventeen operations answer every refusal they own with an RFC 7807 ProblemDetail carrying an
    // errorCode and the request's correlationId, declared as an @ExceptionHandler on the controller that
    // owns the operation. That covers every failure a controller method can be reached to produce.
    //
    // It cannot cover a refusal decided BEFORE a controller method is selected. Three of those exist and
    // every one of them was answered outside the envelope:
    //
    //   1. A request declaring a non-concrete Content-Type - "application/*+json", "*/*", "application/*".
    //      A wildcard request type is COMPATIBLE with a consumes condition, so RequestMappingHandlerMapping
    //      selects the handler; the failure then happens inside the argument resolver, where
    //      HttpHeaders.setContentType rejects the wildcard, and a bare IllegalArgumentException became a
    //      500. A caller could provoke that unauthenticated, on POST /api/auth/signon.
    //   2. A request declaring a concrete but unreadable Content-Type - "text/plain", "application/xml",
    //      the empty string, or none at all. RequestMappingHandlerMapping raises
    //      HttpMediaTypeNotSupportedException during the MAPPING phase, so handler is null, no controller
    //      is consulted and Spring's default 415 body was returned instead of the envelope.
    //   3. An Accept header this API cannot satisfy. HttpMediaTypeNotAcceptableException produced a 406
    //      with NO body and NO Content-Type at all, because the error render could not be negotiated
    //      either.
    //
    // A FOURTH condition exists, and it is not a mapping-phase refusal at all: an exception that no
    // controller handler, no @ResponseStatus and no framework resolver claimed - a store that went away
    // mid-request being the reachable case. It escaped the dispatcher entirely, so the container logged it
    // with an empty diagnostic context and Spring Boot's /error page answered it with its default
    // attributes: no errorCode, no correlationId and Content-Type application/json rather than the
    // problem+json this application publishes everywhere else. UnhandledFailureExceptionResolver below
    // closes it, and it is a SEPARATE resolver rather than a fifth arm of the one above because the two
    // differ in both position and precondition: the framework-boundary resolver must run BEFORE
    // DefaultHandlerExceptionResolver to pre-empt the default bodies for its four types, while a
    // catch-everything arm must run AFTER it so that every framework mapping and every controller-local
    // handler still wins. One class cannot occupy both positions.
    //
    // Why this is not global advice. @ControllerAdvice, @RestControllerAdvice and a
    // ResponseEntityExceptionHandler subclass are prohibited in this application, and the prohibition is
    // asserted by a test that scans every main source. That prohibition is about where an operation's OWN
    // refusals are declared: they belong on the controller that owns the operation, so that status
    // selection is reviewable next to the paragraph it reproduces. Conditions 2 and 3 are not any
    // operation's refusal - condition 2 is decided before an operation has been chosen, and no controller
    // can ever be reached to answer it. A HandlerExceptionResolver is the one extension point Spring
    // consults with handler == null, so it is the only mechanism that can close the gap at all; it is a
    // distinct extension point rather than advice, it declares no @ExceptionHandler, and it claims exactly
    // four exception types and returns null for everything else, so it cannot intercept a refusal a
    // controller owns.
    //
    // Ordering is load bearing. The resolver is inserted immediately BEFORE
    // DefaultHandlerExceptionResolver and therefore AFTER ExceptionHandlerExceptionResolver, so every one
    // of the controller-local handlers still wins. Inserting it at the head of the list would silently
    // disable them. The fourth condition's resolver is APPENDED after DefaultHandlerExceptionResolver for
    // the same reason read the other way round: it must be the last resolver consulted, so that it claims
    // only what every other layer declined. It also declines two exception families outright - Spring
    // Security's AccessDeniedException and AuthenticationException - because ExceptionTranslationFilter
    // sits OUTSIDE the dispatcher and must still see them to publish the 401 and 403 envelopes.

    /**
     * The single media type this application reads and writes, used both to advertise what a rejected
     * {@code Content-Type} should have been and to serialise the refusal itself.
     */
    private static final MediaType SUPPORTED_REQUEST_MEDIA_TYPE = MediaType.APPLICATION_JSON;

    /**
     * The separator RFC 9110 prescribes for the {@code Allow} header's comma-separated method list. Written
     * as a constant because the header is assembled by hand rather than through {@code HttpHeaders}: the
     * refusal is rendered onto the raw {@link HttpServletResponse}, which has no typed header accessor.
     */
    private static final String ALLOW_HEADER_SEPARATOR = ", ";

    /** The member name every refusal carries so a client can branch on the cause without parsing prose. */
    private static final String ERROR_CODE_PROPERTY = "errorCode";

    /** The member name every refusal carries so a client can quote one token back to support. */
    private static final String CORRELATION_ID_PROPERTY = "correlationId";

    /**
     * The value published for {@link #CORRELATION_ID_PROPERTY} when no identifier is in scope. The literal
     * matches the one the eight controllers publish, so the member is never absent and never null.
     */
    private static final String CORRELATION_ID_UNAVAILABLE = "unavailable";

    /**
     * The {@code type} member of every refusal rendered here. {@code about:blank} is the RFC 7807 default
     * and is what {@link org.springframework.http.ProblemDetail} emits for the controller-owned refusals,
     * so a client sees one shape whichever boundary refused it.
     */
    private static final String PROBLEM_TYPE_BLANK = "about:blank";

    /** {@code errorCode} for a request whose {@code Content-Type} this application cannot read. */
    private static final String ERROR_CODE_UNSUPPORTED_MEDIA_TYPE = "CARDDEMO-UNSUPPORTED-MEDIA-TYPE";

    /** {@code errorCode} for a request whose {@code Accept} header this application cannot satisfy. */
    private static final String ERROR_CODE_NOT_ACCEPTABLE = "CARDDEMO-NOT-ACCEPTABLE";

    /** {@code errorCode} for a path that is mapped but not for the method the caller used. */
    private static final String ERROR_CODE_METHOD_NOT_ALLOWED = "CARDDEMO-METHOD-NOT-ALLOWED";

    /** {@code errorCode} for a path no operation is mapped to. */
    private static final String ERROR_CODE_RESOURCE_NOT_FOUND = "CARDDEMO-RESOURCE-NOT-FOUND";

    /**
     * {@code errorCode} for a request the servlet container itself refused, before any application code
     * ran - an illegal character in the request target, for instance, or a method the connector forbids.
     */
    private static final String ERROR_CODE_REQUEST_REJECTED = "CARDDEMO-REQUEST-REJECTED";

    /**
     * {@code errorCode} for a container-level failure with a 5xx status. The spelling is deliberately the
     * one the eight controllers already publish for an unexpected failure, because the condition is the
     * same one seen from one layer further out.
     */
    private static final String ERROR_CODE_INTERNAL_FAILURE = "CARDDEMO-INTERNAL-FAILURE";

    /** Title of the 415 refusal. */
    private static final String TITLE_UNSUPPORTED_MEDIA_TYPE = "Unsupported media type";

    /** Detail of the 415 refusal; names the one media type that would have worked. */
    private static final String DETAIL_UNSUPPORTED_MEDIA_TYPE =
            "The request declared a Content-Type this operation cannot read. Send application/json with a "
                    + "concrete type and subtype.";

    /** Title of the 406 refusal. */
    private static final String TITLE_NOT_ACCEPTABLE = "Not acceptable";

    /** Detail of the 406 refusal. */
    private static final String DETAIL_NOT_ACCEPTABLE =
            "This application produces application/json and application/problem+json only. Send an Accept "
                    + "header that admits one of them.";

    /** Title of the 405 refusal. */
    private static final String TITLE_METHOD_NOT_ALLOWED = "Method not allowed";

    /** Detail of the 405 refusal; the Allow header carries the methods that would have worked. */
    private static final String DETAIL_METHOD_NOT_ALLOWED =
            "The request method is not supported for this path. The Allow header lists the methods that are.";

    /** Title of the 404 refusal. */
    private static final String TITLE_RESOURCE_NOT_FOUND = "Not found";

    /** Detail of the 404 refusal. */
    private static final String DETAIL_RESOURCE_NOT_FOUND = "No operation is mapped to this path.";

    /** Title of a container-level 4xx refusal that is none of the four conditions named above. */
    private static final String TITLE_REQUEST_REJECTED = "Request rejected";

    /**
     * Detail of a container-level 4xx refusal. It deliberately says nothing about WHICH character or header
     * was at fault: the request never reached application code, so the only honest statement is that it was
     * refused at the boundary, and a precise one would echo attacker-supplied bytes back.
     */
    private static final String DETAIL_REQUEST_REJECTED =
            "The request was refused at the protocol boundary before it reached the application.";

    /**
     * Title of the {@code 501 Not Implemented} refusal. Distinguished from the generic 5xx title because the
     * only way this application reaches 501 is a request method the connector does not implement -
     * {@code CONNECT} being the reachable case - and reporting that as an internal error would blame the
     * server for the client's choice of method.
     */
    private static final String TITLE_METHOD_NOT_IMPLEMENTED = "Method not implemented";

    /** Detail of the {@code 501 Not Implemented} refusal. */
    private static final String DETAIL_METHOD_NOT_IMPLEMENTED =
            "The request method is not implemented by this application.";

    /** Title of a container-level 5xx failure. */
    private static final String TITLE_INTERNAL_FAILURE = "Internal server error";

    /** Detail of a container-level 5xx failure; carries no diagnostic, exactly as the controllers do not. */
    private static final String DETAIL_INTERNAL_FAILURE =
            "The request could not be completed. Quote the correlationId when reporting this.";

    /**
     * Registers the two request screens every one of the seventeen operations passes through: the
     * {@code Content-Type} screen that turns condition 1 above into a 415, and the control-character
     * screen that guards every path variable and query parameter.
     *
     * <p>It is an interceptor rather than a filter because it must run <em>after</em> a handler has been
     * mapped and <em>before</em> the argument resolver reads the body. At that point the failure carries the
     * {@link org.springframework.web.method.HandlerMethod} with it, which is what lets Spring resolve it
     * through the normal exception path rather than as an unhandled error.
     *
     *
     * <p><strong>The control-character screen is the second registration, and it covers the request line.</strong>
     * Its request-body half is the Jackson module of {@link #extendMessageConverters(List)}. Both refuse the
     * same character set for the same reason, recorded on {@link #CONTROL_CHARACTER_REJECTION_MESSAGE}: a C0
     * or DEL character cannot be stored in a PostgreSQL character column, cannot have reached a BMS field, and
     * used to surface as an input-output failure or an abend rather than as the refusal it is.
     *
     * <p><strong>Why an interceptor rather than a filter for that half too.</strong> A refusal raised from
     * {@code preHandle} is resolved by the same handler-exception machinery a mapped method's failure is, so a
     * {@code com.cardemo.exception.ValidationException} raised here reaches the controller's own
     * {@code @ExceptionHandler} and is answered with that controller's envelope - carrying the offending
     * parameter name in the {@code field} member, exactly as a field-level refusal raised inside a service is.
     * A filter placed earlier could only have written a generic envelope, and this class is deliberately not an
     * advice type: it declares no {@code @ControllerAdvice}, no {@code @ExceptionHandler} and no
     * {@code @ResponseStatus}, so no error-handling behaviour is centralised away from the controllers.
     *
     * <p><strong>Why the query string is parsed rather than read through the parameter map.</strong> Asking a
     * request for its parameters makes the container parse the body when the media type is
     * {@code application/x-www-form-urlencoded}, which would consume the body before the message converter
     * could read it. Parsing {@link jakarta.servlet.http.HttpServletRequest#getQueryString()} touches the
     * request line only, so no body is consumed on any route and the screen cannot change what an operation
     * receives.
     *
     * <p><strong>Order is deliberate.</strong> The media-type screen is registered first because whether a
     * request is admissible at all is decided before what its fields carry: a request whose declared type this
     * API cannot read is refused 415 without its field content ever being examined.
     *
     * <p>Side effects: mutates only the registry Spring owns and calls once during context refresh. Neither
     * interceptor performs any input or output, opens a resource or starts a thread, and neither holds state -
     * each is registered once and shared across every request thread.
     *
     * @param registry the interceptor registry Spring owns; never null
     */
    @Override
    public void addInterceptors(final InterceptorRegistry registry) {
        registry.addInterceptor(new ConcreteContentTypeInterceptor());
        registry.addInterceptor(new ControlCharacterRequestInterceptor());
        LOGGER.debug("Registered the control-character screen over the request path and query string; the C0 "
                + "range below {} and DEL are refused with a field-level 400", FIRST_PRINTABLE_CHARACTER);
    }

    /**
     * Inserts {@link FrameworkBoundaryExceptionResolver} immediately before
     * {@link DefaultHandlerExceptionResolver}, so the four framework-boundary refusals carry the envelope
     * while every controller-local {@code @ExceptionHandler} keeps precedence.
     *
     * <p>Spring hands over the list it assembled, which is
     * {@code [ExceptionHandlerExceptionResolver, ResponseStatusExceptionResolver,
     * DefaultHandlerExceptionResolver]}. Appending would be useless - the default resolver would already
     * have answered - and prepending would be actively harmful, because it would take precedence over the
     * controller-local handlers. The insertion point is found by type rather than by index so that a change
     * in the framework's default list cannot silently move it.
     *
     * <p>Side effects: mutates the resolver list Spring owns, once, during context refresh. Performs no
     * input or output and starts no thread.
     *
     * @param resolvers the exception resolvers Spring has already assembled; never null
     */
    @Override
    public void extendHandlerExceptionResolvers(final List<HandlerExceptionResolver> resolvers) {

        int insertAt = -1;
        for (int index = 0; index < resolvers.size(); index++) {
            if (resolvers.get(index) instanceof DefaultHandlerExceptionResolver) {
                insertAt = index;
                break;
            }
        }

        if (insertAt < 0) {
            // No default resolver is present, so nothing downstream can pre-empt this one and appending is
            // correct. Recorded at WARN because it means the framework's default list changed shape.
            LOGGER.warn("No DefaultHandlerExceptionResolver found among {} resolver(s); appending the "
                    + "framework-boundary resolver at the end of the list", resolvers.size());
            resolvers.add(new FrameworkBoundaryExceptionResolver());
            resolvers.add(new UnhandledFailureExceptionResolver());
            return;
        }

        resolvers.add(insertAt, new FrameworkBoundaryExceptionResolver());
        LOGGER.debug("Inserted the framework-boundary exception resolver at position {} of {}", insertAt,
                resolvers.size());

        // APPENDED LAST, and the position is the whole point. DefaultHandlerExceptionResolver and every
        // controller-local @ExceptionHandler are consulted first, so this one sees only what nothing else
        // answered - the residual set that used to escape the dispatcher, reach the container uncorrelated
        // and be rendered by Spring Boot's /error page without an errorCode or a correlationId.
        resolvers.add(new UnhandledFailureExceptionResolver());
        LOGGER.debug("Appended the unhandled-failure exception resolver at position {} of {}",
                resolvers.size() - 1, resolvers.size());
    }

    /**
     * Replaces the container's HTML error report with the same refusal envelope.
     *
     * <p>Some refusals never reach the servlet at all. A request target containing a {@code %00} escape is
     * rejected by the connector while it is still parsing the request line, so no filter runs, no
     * correlation identifier exists, and the response was rendered by Tomcat's own
     * {@link ErrorReportValve} as an HTML page naming the container and its version. That is both an
     * envelope gap and a needless disclosure of the runtime's identity.
     *
     * <p>The valve class is substituted rather than merely silenced. Turning the report off would remove the
     * disclosure but leave an empty body, which is what the 406 already did and is exactly the
     * inconsistency being closed.
     *
     * <p>Side effects: sets one property on the Tomcat {@code Host} during context creation, before the host
     * starts, which is what makes the substitution take effect. Performs no input or output and starts no
     * thread.
     *
     * @return the customizer that installs the JSON-rendering error report valve; never null
     */
    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> problemJsonErrorReportValve() {
        return factory -> factory.addContextCustomizers(context -> {
            final Container parent = context.getParent();
            if (parent instanceof StandardHost host) {
                host.setErrorReportValveClass(ProblemJsonErrorReportValve.class.getName());
                LOGGER.info("Container-level error reporting will render {} instead of an HTML page",
                        MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            } else {
                // Not reachable with the embedded Tomcat this application pins, and deliberately not fatal:
                // a missing envelope on a pre-servlet refusal must not stop the application from starting.
                LOGGER.warn("Servlet context parent is {}, not a StandardHost; container-level errors will "
                        + "keep the container's own rendering",
                        parent == null ? "absent" : parent.getClass().getName());
            }
        });
    }

    /**
     * Makes Spring Security's HTTP-firewall rejection carry the refusal envelope.
     *
     * <p><strong>Finding, severity Medium - remediated here.</strong> {@code TRACE}, and any method outside
     * the firewall's allowed set, answered {@code 400 Bad Request} with no body and no
     * {@code Content-Type} at all. The mechanism is a double refusal, and it is worth writing down because
     * the obvious remedies all address the wrong layer:
     *
     * <ol>
     *   <li>The connector refuses {@code TRACE} first, because {@code allowTrace} is false. It does so with
     *       {@code sendError}, which sets the status, adds an {@code Allow} header and dispatches to Spring
     *       Boot's registered {@code /error} page.</li>
     *   <li>That error dispatch is still a {@code TRACE} request, so
     *       {@code StrictHttpFirewall} refuses it a second time. The default
     *       {@code HttpStatusRequestRejectedHandler} answers with {@code sendError} as well - but a
     *       {@code sendError} inside an error dispatch has nowhere left to dispatch to, so it commits the
     *       response with a zero-length body.</li>
     * </ol>
     *
     * <p>By the time any Tomcat valve or Spring resolver could contribute a body, the response is committed
     * and nothing can be written. Widening the firewall's allowed methods, or setting {@code allowTrace},
     * would give the body back by admitting a request that is currently refused twice over - trading a
     * hardened default for the cosmetics of a refusal, which the least-privilege standard this application
     * is built to does not permit.
     *
     * <p>The actual root cause is narrower than either: the rejection handler writes its answer with
     * {@code sendError} instead of writing a body. Replacing it fixes every rejected method at once - the
     * refusal stays exactly as strict, no method list is duplicated anywhere, and the policy remains
     * entirely the firewall's.
     *
     * <p>Side effects: sets one collaborator on the security builder during context refresh. Performs no
     * input or output and starts no thread.
     *
     * @return the customizer that installs the enveloping rejection handler; never null
     */
    @Bean
    public WebSecurityCustomizer problemJsonRequestRejectedHandler() {
        return web -> web.requestRejectedHandler(new ProblemJsonRequestRejectedHandler());
    }

    /**
     * Renders the refusal envelope onto a response, byte for byte the shape the eight controllers publish.
     *
     * <p>The document is assembled by hand rather than through Jackson, and that is a deliberate choice
     * rather than an omission. Every value in it is either a compile-time constant declared above or a
     * correlation identifier constrained to {@code [A-Za-z0-9_-]}, so no value can require JSON escaping and
     * none can be attacker-shaped. Assembling it by hand means this method has no dependency on an
     * {@code ObjectMapper} bean, which matters because it is called from a Tomcat valve that runs outside
     * the Spring request scope entirely.
     *
     * <p>Member order matches {@link org.springframework.http.ProblemDetail}'s serialisation - {@code type},
     * {@code title}, {@code status}, {@code detail}, then the two extension members - so a client comparing
     * two refusals byte for byte sees the same ordering from both boundaries.
     *
     * @param status        the HTTP status being reported
     * @param title         the short, human-readable summary; must be a constant of this class
     * @param detail        the explanation; must be a constant of this class
     * @param errorCode     the machine-readable code; must be a constant of this class
     * @param correlationId the identifier to publish; may be null or empty, in which case
     *                      {@link #CORRELATION_ID_UNAVAILABLE} is published instead
     * @return the complete JSON document; never null and never empty
     */
    private static String renderProblemEnvelope(final int status, final String title, final String detail,
            final String errorCode, final String correlationId) {

        return "{\"type\":\"" + PROBLEM_TYPE_BLANK
                + "\",\"title\":\"" + title
                + "\",\"status\":" + status
                + ",\"detail\":\"" + detail
                + "\",\"" + ERROR_CODE_PROPERTY + "\":\"" + errorCode
                + "\",\"" + CORRELATION_ID_PROPERTY + "\":\"" + safeCorrelationId(correlationId)
                + "\"}";
    }

    /**
     * Writes a refusal envelope onto a raw servlet response, or records why it could not be written.
     *
     * <p>Both {@link HandlerExceptionResolver} implementations in this class render the same bytes in the
     * same way, so the sequence is declared once rather than twice (Rule 1 Clause C, avoid duplication).
     * Nothing about the shape is decided here: the caller supplies every value, and every value it supplies
     * is a constant of this class or a correlation identifier already constrained to {@code [A-Za-z0-9_-]}.
     *
     * <p><strong>A committed response is left exactly as the client already saw it.</strong> Nothing can be
     * written over a committed response, so the condition is reported rather than silently swallowed and the
     * status already on the wire is the one that stands.
     *
     * @param response      the response to render onto; never null
     * @param status        the HTTP status to set
     * @param title         the short, human-readable summary; must be a constant of this class
     * @param detail        the explanation; must be a constant of this class
     * @param errorCode     the machine-readable code; must be a constant of this class
     * @param correlationId the identifier to publish; may be null or empty
     */
    private static void writeProblemEnvelope(final HttpServletResponse response, final int status,
            final String title, final String detail, final String errorCode, final String correlationId) {

        if (response.isCommitted()) {
            LOGGER.warn("Response for correlationId {} was already committed; the refusal envelope could "
                    + "not be written", correlationId);
            return;
        }

        final byte[] body = renderProblemEnvelope(status, title, detail, errorCode, correlationId)
                .getBytes(StandardCharsets.UTF_8);

        response.setStatus(status);
        // No charset parameter, and none is needed: the document is ASCII by construction - every value
        // in it is a constant of this class or a correlation identifier restricted to [A-Za-z0-9_-] - and
        // RFC 8259 fixes JSON's encoding at UTF-8 regardless. Declaring one would also make this refusal
        // differ, character for character in its Content-Type, from the ones the eight controllers
        // publish, which is the very inconsistency being closed here.
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setContentLength(body.length);

        try {
            response.getOutputStream().write(body);
            response.flushBuffer();
        } catch (final IOException broken) {
            // The client went away mid-write. There is no response left to salvage and nothing to
            // escalate to, so it is recorded and swallowed - rethrowing would only produce a second,
            // equally unwritable failure.
            LOGGER.warn("Could not write the refusal envelope for correlationId {}: {}", correlationId,
                    broken.getMessage());
        }
    }

    /**
     * Reduces a correlation identifier to the character set the envelope can carry without escaping.
     *
     * <p>{@code CorrelationIdFilter} already admits only {@code [A-Za-z0-9_-]}, bounded at 64 characters, so
     * in practice this method returns its argument unchanged. It exists because the envelope is assembled by
     * string concatenation: if the constraint upstream were ever relaxed, a value containing a quotation
     * mark would produce a malformed document rather than a merely surprising one. Any character outside the
     * set is dropped, and an argument that is null, empty or reduced to nothing yields
     * {@link #CORRELATION_ID_UNAVAILABLE}.
     *
     * @param correlationId the identifier to sanitise; may be null
     * @return a value safe to embed in a JSON string literal; never null and never empty
     */
    private static String safeCorrelationId(final String correlationId) {

        if (correlationId == null || correlationId.isEmpty()) {
            return CORRELATION_ID_UNAVAILABLE;
        }

        final StringBuilder kept = new StringBuilder(correlationId.length());
        for (int index = 0; index < correlationId.length(); index++) {
            final char character = correlationId.charAt(index);
            final boolean permitted = character >= 'A' && character <= 'Z'
                    || character >= 'a' && character <= 'z'
                    || character >= '0' && character <= '9'
                    || character == '_' || character == '-';
            if (permitted) {
                kept.append(character);
            }
        }

        return kept.length() == 0 ? CORRELATION_ID_UNAVAILABLE : kept.toString();
    }

    /**
     * Reads the correlation identifier the request filter placed in the logging context.
     *
     * @return the identifier, or {@link #CORRELATION_ID_UNAVAILABLE} when none is in scope
     */
    private static String currentCorrelationId() {
        final String fromContext = MDC.get(CorrelationIdFilter.MDC_KEY_CORRELATION_ID);
        return fromContext == null || fromContext.isEmpty() ? CORRELATION_ID_UNAVAILABLE : fromContext;
    }

    /**
     * Maps a container-level HTTP status onto the {@code errorCode} the envelope publishes for it.
     *
     * <p>Only statuses the container can produce on its own are distinguished. Everything else in the 4xx
     * range collapses onto {@link #ERROR_CODE_REQUEST_REJECTED} and everything at or above 500 onto
     * {@link #ERROR_CODE_INTERNAL_FAILURE}, because at this layer nothing more specific is known: the
     * request did not reach the application, so there is no operation whose refusal this could be.
     *
     * @param status the status the container set
     * @return the code to publish; never null
     */
    private static String containerErrorCode(final int status) {
        return switch (status) {
            case HttpServletResponse.SC_NOT_FOUND -> ERROR_CODE_RESOURCE_NOT_FOUND;
            case HttpServletResponse.SC_METHOD_NOT_ALLOWED -> ERROR_CODE_METHOD_NOT_ALLOWED;
            case HttpServletResponse.SC_NOT_ACCEPTABLE -> ERROR_CODE_NOT_ACCEPTABLE;
            case HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE -> ERROR_CODE_UNSUPPORTED_MEDIA_TYPE;
            case HttpServletResponse.SC_NOT_IMPLEMENTED -> ERROR_CODE_METHOD_NOT_ALLOWED;
            default -> status >= HttpServletResponse.SC_INTERNAL_SERVER_ERROR
                    ? ERROR_CODE_INTERNAL_FAILURE
                    : ERROR_CODE_REQUEST_REJECTED;
        };
    }

    /**
     * Returns the title that accompanies {@link #containerErrorCode(int)} for a container-level status.
     *
     * @param status the status the container set
     * @return the title to publish; never null
     */
    private static String containerTitle(final int status) {
        return switch (status) {
            case HttpServletResponse.SC_NOT_FOUND -> TITLE_RESOURCE_NOT_FOUND;
            case HttpServletResponse.SC_METHOD_NOT_ALLOWED -> TITLE_METHOD_NOT_ALLOWED;
            case HttpServletResponse.SC_NOT_ACCEPTABLE -> TITLE_NOT_ACCEPTABLE;
            case HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE -> TITLE_UNSUPPORTED_MEDIA_TYPE;
            case HttpServletResponse.SC_NOT_IMPLEMENTED -> TITLE_METHOD_NOT_IMPLEMENTED;
            default -> status >= HttpServletResponse.SC_INTERNAL_SERVER_ERROR
                    ? TITLE_INTERNAL_FAILURE
                    : TITLE_REQUEST_REJECTED;
        };
    }

    /**
     * Returns the detail that accompanies {@link #containerErrorCode(int)} for a container-level status.
     *
     * @param status the status the container set
     * @return the detail to publish; never null
     */
    private static String containerDetail(final int status) {
        return switch (status) {
            case HttpServletResponse.SC_NOT_FOUND -> DETAIL_RESOURCE_NOT_FOUND;
            case HttpServletResponse.SC_METHOD_NOT_ALLOWED -> DETAIL_METHOD_NOT_ALLOWED;
            case HttpServletResponse.SC_NOT_ACCEPTABLE -> DETAIL_NOT_ACCEPTABLE;
            case HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE -> DETAIL_UNSUPPORTED_MEDIA_TYPE;
            case HttpServletResponse.SC_NOT_IMPLEMENTED -> DETAIL_METHOD_NOT_IMPLEMENTED;
            default -> status >= HttpServletResponse.SC_INTERNAL_SERVER_ERROR
                    ? DETAIL_INTERNAL_FAILURE
                    : DETAIL_REQUEST_REJECTED;
        };
    }

    /**
     * Refuses a request whose declared {@code Content-Type} is not a concrete type and subtype.
     *
     * <p><strong>Finding, severity High - remediated here.</strong> {@code POST /api/auth/signon} with
     * {@code Content-Type: application/*+json} answered {@code 500 Internal Server Error} with Spring's
     * default error body, and so did the other six body-binding operations. A caller needed no credential to
     * provoke it. The mechanism is specific and worth stating, because it is not the mechanism a reader
     * expects: a wildcard request type <em>satisfies</em> a {@code consumes} condition, because
     * {@code MediaType.includes} is asked whether the mapping's type is compatible with the request's rather
     * than the other way round. So the mapping matches, the handler is selected, and the failure surfaces
     * one layer further in - {@code ServletServerHttpRequest.getHeaders()} calls
     * {@code HttpHeaders.setContentType}, which asserts that the type is concrete and throws
     * {@link IllegalArgumentException}. An unhandled {@link IllegalArgumentException} is a 500, and rightly
     * so: mapping that exception type to a status globally would turn every programming error in the
     * application into a 4xx.
     *
     * <p>The screen therefore happens where the fact is unambiguous - a declared {@code Content-Type} that
     * is not concrete - and it raises the exception the condition actually is,
     * {@link HttpMediaTypeNotSupportedException}, which
     * {@link FrameworkBoundaryExceptionResolver} renders as a 415 inside the envelope. The result is that
     * the wildcard case and the {@code text/plain} case answer identically, which is the point: they are the
     * same condition.
     *
     * <p>Scope. A request that declares no {@code Content-Type} at all is not touched here - that is a
     * legitimate shape for a request without content, and the mapping already refuses it for an operation
     * that needs a body. A request on the {@code ERROR} dispatch is not touched either, so a refusal cannot
     * re-enter this screen while it is being rendered. Everything else with a declared type is screened
     * regardless of method, because a wildcard {@code Content-Type} is never a valid description of content
     * that was actually sent.
     *
     * <p>Thread safety: stateless and immutable, so the single registered instance is safe for concurrent
     * dispatch.
     */
    public static final class ConcreteContentTypeInterceptor implements HandlerInterceptor {

        /** Creates the screen. Public because the MVC registry instantiates one per context. */
        public ConcreteContentTypeInterceptor() {
            // No state to establish.
        }

        /**
         * Rejects a non-concrete declared {@code Content-Type} before the body is read.
         *
         * <p>Side effects: none on success. On refusal it throws and writes nothing itself, leaving the
         * rendering to the resolver so that one code path produces every 415.
         *
         * @param request  the request being dispatched; never null
         * @param response the response, untouched by this method; never null
         * @param handler  the handler Spring selected; never null
         * @return true, always, when the request is admitted
         * @throws HttpMediaTypeNotSupportedException when the declared type is absent-valued, unparseable,
         *                                            or not a concrete type and subtype
         */
        @Override
        public boolean preHandle(final HttpServletRequest request, final HttpServletResponse response,
                final Object handler) throws HttpMediaTypeNotSupportedException {

            if (request.getDispatcherType() == DispatcherType.ERROR) {
                return true;
            }

            final String declared = request.getContentType();
            if (declared == null || declared.isBlank()) {
                return true;
            }

            final MediaType parsed;
            try {
                parsed = MediaType.parseMediaType(declared);
            } catch (final InvalidMediaTypeException malformed) {
                // Not reachable through the mapping - an unparseable type is refused there first - but the
                // screen must not turn a malformed header into a 500 if the mapping ever stops matching.
                throw new HttpMediaTypeNotSupportedException(malformed.getMessage());
            }

            if (parsed.isWildcardType() || parsed.isWildcardSubtype()) {
                throw new HttpMediaTypeNotSupportedException(parsed,
                        List.of(SUPPORTED_REQUEST_MEDIA_TYPE));
            }

            return true;
        }
    }

    /**
     * Renders the four framework-boundary refusals inside the application's refusal envelope.
     *
     * <p>It claims exactly four exception types and returns {@code null} for every other, which is what
     * keeps it from becoming a general-purpose handler: a refusal that a controller owns is never seen here,
     * because {@code ExceptionHandlerExceptionResolver} runs first and this resolver is inserted after it.
     *
     * <dl>
     *   <dt>{@link HttpMediaTypeNotSupportedException} - 415</dt>
     *   <dd>Raised by the mapping for a concrete but unreadable type, and by
     *       {@link ConcreteContentTypeInterceptor} for a non-concrete one. Both render identically, and an
     *       {@code Accept} response header names the one type that would have worked.</dd>
     *   <dt>{@link HttpMediaTypeNotAcceptableException} - 406</dt>
     *   <dd>Raised when no converter can write the negotiated type. The response {@code Content-Type} is
     *       set explicitly, because the whole reason the body was empty before is that content negotiation
     *       could not choose one; declaring it removes the negotiation from the error path.</dd>
     *   <dt>{@link HttpRequestMethodNotSupportedException} - 405</dt>
     *   <dd>Raised when the path matched but the method did not. The {@code Allow} header is carried
     *       through, so the refusal keeps the one piece of information that makes it actionable.</dd>
     *   <dt>{@link NoResourceFoundException} - 404</dt>
     *   <dd>Raised when nothing is mapped. Largely unreachable in this application, because the security
     *       chain denies an unknown path first and answers 403 with the envelope already; it is claimed so
     *       that a future permitted path cannot open a gap.</dd>
     * </dl>
     *
     * <p>Thread safety: stateless and immutable, so the single registered instance is safe for concurrent
     * dispatch.
     */
    public static final class FrameworkBoundaryExceptionResolver implements HandlerExceptionResolver {

        /** Creates the resolver. Public because it is registered into the MVC resolver list. */
        public FrameworkBoundaryExceptionResolver() {
            // No state to establish.
        }

        /**
         * Answers one of the four framework-boundary refusals, or declines.
         *
         * <p>Side effects: on a claimed exception it sets the status, the {@code Content-Type}, one advisory
         * header and the body, then flushes. On any other exception it does nothing at all.
         *
         * @param request  the request being dispatched; never null
         * @param response the response to render onto; never null
         * @param handler  the handler, which is null when the failure preceded handler selection
         * @param failure  the exception raised; never null
         * @return an empty {@link ModelAndView} when this resolver answered, so that no view is rendered
         *         over the body it wrote; null when the exception is not one of the four
         */
        @Override
        public ModelAndView resolveException(final HttpServletRequest request,
                final HttpServletResponse response, final Object handler, final Exception failure) {

            final HttpStatus status;
            final String title;
            final String detail;
            final String errorCode;

            if (failure instanceof HttpMediaTypeNotSupportedException) {
                status = HttpStatus.UNSUPPORTED_MEDIA_TYPE;
                title = TITLE_UNSUPPORTED_MEDIA_TYPE;
                detail = DETAIL_UNSUPPORTED_MEDIA_TYPE;
                errorCode = ERROR_CODE_UNSUPPORTED_MEDIA_TYPE;
                response.setHeader(HttpHeaders.ACCEPT, SUPPORTED_REQUEST_MEDIA_TYPE.toString());
            } else if (failure instanceof HttpMediaTypeNotAcceptableException) {
                status = HttpStatus.NOT_ACCEPTABLE;
                title = TITLE_NOT_ACCEPTABLE;
                detail = DETAIL_NOT_ACCEPTABLE;
                errorCode = ERROR_CODE_NOT_ACCEPTABLE;
            } else if (failure instanceof HttpRequestMethodNotSupportedException wrongMethod) {
                status = HttpStatus.METHOD_NOT_ALLOWED;
                title = TITLE_METHOD_NOT_ALLOWED;
                detail = DETAIL_METHOD_NOT_ALLOWED;
                errorCode = ERROR_CODE_METHOD_NOT_ALLOWED;
                final Set<HttpMethod> allowed = wrongMethod.getSupportedHttpMethods();
                if (allowed != null && !allowed.isEmpty()) {
                    final StringJoiner methods = new StringJoiner(ALLOW_HEADER_SEPARATOR);
                    for (final HttpMethod method : allowed) {
                        methods.add(method.name());
                    }
                    response.setHeader(HttpHeaders.ALLOW, methods.toString());
                }
            } else if (failure instanceof NoResourceFoundException) {
                status = HttpStatus.NOT_FOUND;
                title = TITLE_RESOURCE_NOT_FOUND;
                detail = DETAIL_RESOURCE_NOT_FOUND;
                errorCode = ERROR_CODE_RESOURCE_NOT_FOUND;
            } else {
                return null;
            }

            final String correlationId = currentCorrelationId();
            // FINDING C-01, severity BLOCKER. The request method and URI used to be logged here. Both are
            // caller-chosen text on a boundary an unauthenticated caller reaches, so either can carry a card
            // number, a password, a customer name or a government identifier - and the masking in
            // src/main/resources/logback-spring.xml redacts LABELLED values, so a bare protected value in a
            // path segment or a query string survives verbatim. JSON encoding prevents a forged record; it
            // does not prevent disclosure.
            //
            // Every value below is drawn from a closed set: the status and the error code are constants of
            // this class, the condition is a framework exception's simple name, and the correlation
            // identifier is validated to [A-Za-z0-9_-] by CorrelationIdFilter. The correlation identifier is
            // what an operator joins to - the caller received the same value in the refusal envelope - so the
            // record stays actionable without echoing the request. No route template is logged either: at
            // this boundary the request matched no mapping, so there is no template to name, and the
            // exception's own type already says which of the five conditions fired.
            LOGGER.warn("Refused a request at the framework boundary with {}: errorCode {}, condition {},"
                            + " correlationId {}",
                    status.value(), errorCode, failure.getClass().getSimpleName(), correlationId);

            writeProblemEnvelope(response, status.value(), title, detail, errorCode, correlationId);

            return new ModelAndView();
        }
    }

    /**
     * Answers the one failure class no other layer answers: an exception no controller, no
     * {@code @ResponseStatus} and no framework resolver claimed.
     *
     * <p><strong>Finding, severity Major - remediated here.</strong> With the database stopped,
     * {@code POST /api/auth/signon} answered {@code 500} with {@code Content-Type: application/json} and
     * Spring's default error attributes - {@code timestamp}, {@code status}, {@code error}, {@code path} -
     * carrying <em>no</em> {@code errorCode} and <em>no</em> {@code correlationId}, and the only log record
     * for it was the container's own, emitted by {@code StandardWrapperValve} with an empty diagnostic
     * context. An operator could therefore not join the client-visible failure to any log record, for the
     * one failure class where that join matters most, and the body offered no identifier at all.
     *
     * <p><strong>Why the gap existed.</strong> {@link FrameworkBoundaryExceptionResolver} claims four
     * framework exception types and declines everything else, so a residual exception propagated out of the
     * {@code DispatcherServlet} to the container, which logged it - by then
     * {@code CorrelationIdFilter}'s {@code finally} had already restored the logging context, hence the
     * empty identifiers - and dispatched to Spring Boot's registered {@code /error} page. That page had
     * already produced a body by the time {@link ProblemJsonErrorReportValve} ran, and that valve correctly
     * keeps its hands off a response another layer has answered.
     *
     * <p><strong>Why answering it here fixes both halves at once.</strong> A resolver runs
     * <em>inside</em> the filter chain, so the diagnostic context is still installed: the record written
     * below carries {@code correlationId}, {@code traceId} and {@code spanId}, and because the exception is
     * answered rather than rethrown, the container never writes its uncorrelated one and never dispatches to
     * {@code /error} at all. The body is the same envelope every other boundary publishes, so
     * {@code docs/api-contracts.md}'s statement that {@code errorCode} and {@code correlationId} are always
     * present, and that the identifier is echoed into every error body and every log line, becomes true of
     * this path too.
     *
     * <p><strong>Two exception families are deliberately NOT claimed, and that exclusion is load bearing.</strong>
     * {@link org.springframework.security.access.AccessDeniedException} and
     * {@link org.springframework.security.core.AuthenticationException} must keep propagating out of the
     * dispatcher so that Spring Security's {@code ExceptionTranslationFilter} reaches its own entry point
     * and access-denied handler - the two components that publish the {@code 401} and {@code 403} envelopes
     * configured in {@code com.cardemo.config.SecurityConfig}. Claiming them here would answer a denial with
     * {@code 500} and silently dismantle the authorisation boundary, so they are declined by type.
     *
     * <p><strong>Ordering is load bearing too.</strong> This resolver is appended at the very END of the
     * resolver list, after {@link DefaultHandlerExceptionResolver}, so every controller-local
     * {@code @ExceptionHandler}, every {@code @ResponseStatus} and every framework mapping keeps
     * precedence. It sees only what nothing else answered, which is precisely the set that used to escape.
     *
     * <p>It is not advice. It declares no {@code @ControllerAdvice}, no {@code @ExceptionHandler} and no
     * {@code @ResponseStatus}, so no operation's own refusal is centralised away from the controller that
     * owns it.
     */
    public static final class UnhandledFailureExceptionResolver implements HandlerExceptionResolver {

        /** Creates the resolver. Public because it is registered into the MVC resolver list. */
        public UnhandledFailureExceptionResolver() {
            // No state to establish.
        }

        /**
         * Answers an unclaimed failure with the {@code 500} refusal envelope, or declines a security denial.
         *
         * <p>Side effects: on a claimed exception it writes one ERROR log record and then the status, the
         * {@code Content-Type} and the body, and flushes. On a security denial it does nothing at all.
         *
         * @param request  the request being dispatched; never null
         * @param response the response to render onto; never null
         * @param handler  the handler, which is null when the failure preceded handler selection
         * @param failure  the exception raised; never null
         * @return an empty {@link ModelAndView} when this resolver answered, so that no view is rendered
         *         over the body it wrote; null for a security denial, which
         *         {@code ExceptionTranslationFilter} must still see
         */
        @Override
        public ModelAndView resolveException(final HttpServletRequest request,
                final HttpServletResponse response, final Object handler, final Exception failure) {

            if (failure instanceof AccessDeniedException || failure instanceof AuthenticationException) {
                return null;
            }

            final String correlationId = currentCorrelationId();

            // The throwable IS passed, deliberately, so the stack trace survives. Before this resolver
            // existed the container logged the same trace with an empty diagnostic context; logging it here
            // adds the correlation, trace and span identifiers and takes nothing away. Neither the request
            // method nor the request URI is logged - finding C-01 records why: both are caller-chosen text
            // on a boundary an unauthenticated caller reaches, so either can carry a card number, a
            // password or a government identifier, and the masking in logback-spring.xml redacts LABELLED
            // values only. The exception's own type and the correlation identifier are what make the record
            // actionable, and both are safe to publish.
            LOGGER.error("Answered an unhandled failure with 500: errorCode {}, condition {},"
                            + " correlationId {}",
                    ERROR_CODE_INTERNAL_FAILURE, failure.getClass().getName(), correlationId, failure);

            writeProblemEnvelope(response, HttpStatus.INTERNAL_SERVER_ERROR.value(), TITLE_INTERNAL_FAILURE,
                    DETAIL_INTERNAL_FAILURE, ERROR_CODE_INTERNAL_FAILURE, correlationId);

            return new ModelAndView();
        }
    }

    /**
     * Renders the container's own error reports as the application's refusal envelope instead of HTML.
     *
     * <p><strong>Finding, severity Medium - remediated here.</strong>
     * {@code GET /api/accounts/000000000%00} answered {@code 400 Bad Request} with
     * {@code Content-Type: text/html} and a page naming the servlet container and its version. The request
     * is rejected by the connector while the request target is still being parsed, so no filter, no servlet
     * and no Spring resolver ever runs: this valve is the only place the response can be shaped. The same is
     * true of any status the container sets with an empty body, {@code TRACE} being the other case this
     * application can reach.
     *
     * <p>What it does not do. It never writes HTML, under any circumstance - if the envelope cannot be
     * written the body stays empty, which is strictly better than disclosing the runtime. It writes nothing
     * when the response is already committed, when content has already been written, or when another layer
     * has already reported the error, which are the same three guards
     * {@link ErrorReportValve#report(Request, Response, Throwable)} applies; this preserves the ordinary
     * path, where Spring Boot's registered {@code /error} page has already produced a body and this valve
     * must keep its hands off it.
     *
     * <p>Correlation. A pre-servlet refusal has no correlation identifier, because the filter that mints one
     * never ran. Rather than publish nothing, one is minted here and written to the log alongside the status
     * and the request target, so the identifier in the client's hands does resolve to a log entry.
     *
     * <p><strong>Finding LOW-002, severity Low, resolved: this boundary is now proven rather than described.</strong>
     * It previously had no test reference anywhere in the suite, which is the worst place in the application
     * for that to be true - a regression here is silent, and what it silently restores is the container's own
     * report. {@code com.cardemo.unit.config.ProblemJsonErrorBoundaryTest} starts an embedded container
     * through the production customiser, speaks HTTP over a raw socket so no client library can normalise the
     * bytes, and drives the two refusals a caller can actually provoke - {@code TRACE}, and a malformed
     * request target. It asserts the media type and all six envelope members, screens the response for the
     * four disclosure classes the container's report carries, asserts the installation itself so a passing
     * body assertion cannot be a valve nothing installed, exercises the tolerated non-{@code StandardHost}
     * branch, and includes a control case against an uncustomised factory that demonstrates the disclosure
     * being removed. Removing the {@code setErrorReportValveClass} call fails six of its nine cases.
     *
     * <p>Thread safety: stateless beyond what {@link ErrorReportValve} itself holds, and reached on the
     * request thread only.
     */
    public static final class ProblemJsonErrorReportValve extends ErrorReportValve {

        /**
         * Creates the valve. Public and no-argument because Tomcat instantiates it reflectively from the
         * class name set on the {@code Host}.
         */
        public ProblemJsonErrorReportValve() {
            super();
        }

        /**
         * Writes the refusal envelope for a container-level error, or leaves the response alone.
         *
         * <p>Side effects: sets the {@code Content-Type} and writes the body when all three guards pass.
         *
         * @param request   the connector request; never null
         * @param response  the connector response; never null
         * @param throwable the failure, when the error came from an exception rather than a status; may be
         *                  null
         */
        @Override
        protected void report(final Request request, final Response response, final Throwable throwable) {

            final int status = response.getStatus();
            if (status < HttpServletResponse.SC_BAD_REQUEST || response.getContentWritten() > 0L
                    || response.isCommitted()) {
                return;
            }

            // The superclass has a third guard, "!response.setErrorReported()", and it is deliberately not
            // reproduced as a guard here - only as the side effect below. That flag is set by the first
            // sendError on the response, whether or not anything was subsequently written, so it does not
            // answer the question that matters, which is whether a BODY already exists. A TRACE request is
            // the case that proves it: the connector refuses the method with sendError, which sets the flag
            // and dispatches to the error page; the error dispatch is then refused as well, so nothing is
            // ever written, and honouring the flag would leave the caller with a bare status and no
            // envelope - which is exactly the gap being closed. getContentWritten() answers the real
            // question, and it is checked above. The flag is still set, so that nothing downstream reports
            // the same error a second time.
            response.setErrorReported();

            final String mdcCorrelationId = MDC.get(CorrelationIdFilter.MDC_KEY_CORRELATION_ID);
            final String correlationId = mdcCorrelationId == null || mdcCorrelationId.isEmpty()
                    ? UUID.randomUUID().toString()
                    : mdcCorrelationId;

            LOGGER.warn("Container refused a request with {} before it reached the application: "
                            + "correlationId {}, errorCode {}", status, correlationId,
                    containerErrorCode(status));
            if (throwable != null) {
                LOGGER.debug("Cause of the container-level refusal for correlationId {}", correlationId,
                        throwable);
            }

            try {
                // Character encoding is left alone for the reason given in the resolver: the document is
                // ASCII by construction, so every encoding the container can choose produces identical
                // bytes, and declaring a charset parameter would make this refusal's Content-Type differ
                // from the one the controllers publish.
                response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
                final Writer reporter = response.getReporter();
                if (reporter != null) {
                    reporter.write(renderProblemEnvelope(status, containerTitle(status),
                            containerDetail(status), containerErrorCode(status), correlationId));
                    reporter.flush();
                }
            } catch (final IOException | IllegalStateException unwritable) {
                // The response cannot be written to. The status the container set still reaches the client;
                // only the body is lost. Deliberately not escalated - and deliberately never falling back
                // to super.report(), which would write the HTML page this class exists to remove.
                LOGGER.warn("Could not write the container-level refusal envelope for correlationId {}: {}",
                        correlationId, unwritable.getMessage());
            }
        }
    }

    /**
     * Answers a Spring Security HTTP-firewall rejection with the refusal envelope instead of a bare status.
     *
     * <p>It writes the body itself rather than delegating to {@code sendError}, and that is the whole point:
     * a {@code sendError} on a request that is already being dispatched to the error page commits the
     * response with nothing in it, which is precisely the gap
     * {@link WebConfig#problemJsonRequestRejectedHandler()} documents.
     *
     * <p>The status stays {@code 400 Bad Request}, matching the handler this replaces, and the detail is a
     * constant. Nothing about WHY the request was rejected is published: the exception's message names the
     * offending method or character, which is attacker-supplied, and echoing it would both reflect input and
     * describe the firewall's rules to whoever is probing them. The reason is written to the log instead,
     * against a correlation identifier the caller also receives.
     *
     * <p>Thread safety: stateless and immutable, so the single registered instance is safe for concurrent
     * dispatch.
     */
    public static final class ProblemJsonRequestRejectedHandler implements RequestRejectedHandler {

        /** Creates the handler. Public because it is installed on the security builder. */
        public ProblemJsonRequestRejectedHandler() {
            // No state to establish.
        }

        /**
         * Writes the refusal envelope for a firewall rejection.
         *
         * <p>Side effects: sets the status, the {@code Content-Type} and the body, then flushes. Any
         * {@code Allow} header an earlier layer added is left in place, so a rejected method keeps the one
         * piece of advice that makes the refusal actionable.
         *
         * @param request   the rejected request; never null
         * @param response  the response to render onto; never null
         * @param rejection the rejection, whose type is logged and whose message is neither logged nor
         *                  published, because the firewall quotes the offending request back in it; never null
         * @throws IOException when the response cannot be written
         */
        @Override
        public void handle(final HttpServletRequest request, final HttpServletResponse response,
                final RequestRejectedException rejection) throws IOException {

            final String correlationId = currentCorrelationId();
            // FINDING C-01, severity BLOCKER. The method, the URI and the firewall's own message used to be
            // logged here, and this site was the worst of the three: the firewall rejects a request BECAUSE
            // its URI is malformed, and its message quotes the offending value back. A caller who puts a card
            // number or a government identifier into a path segment therefore had it written to the log twice,
            // pre-authentication, past a masking layer that only redacts labelled values.
            //
            // The rejection's TYPE is logged in place of its message: RequestRejectedException is raised by
            // one firewall, so its simple name identifies the class of rejection without carrying any part of
            // the request. Everything else here is a constant of this class or the validated correlation
            // identifier the caller also received.
            LOGGER.warn("Firewall rejected a request with {}: errorCode {}, condition {}, correlationId {}",
                    HttpStatus.BAD_REQUEST.value(), ERROR_CODE_REQUEST_REJECTED,
                    rejection.getClass().getSimpleName(), correlationId);

            if (response.isCommitted()) {
                LOGGER.warn("Response for correlationId {} was already committed; the firewall refusal "
                        + "envelope could not be written", correlationId);
                return;
            }

            final byte[] body = renderProblemEnvelope(HttpStatus.BAD_REQUEST.value(), TITLE_REQUEST_REJECTED,
                    DETAIL_REQUEST_REJECTED, ERROR_CODE_REQUEST_REJECTED, correlationId)
                    .getBytes(StandardCharsets.UTF_8);

            response.setStatus(HttpStatus.BAD_REQUEST.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.setContentLength(body.length);
            response.getOutputStream().write(body);
            response.flushBuffer();
        }
    }
}
