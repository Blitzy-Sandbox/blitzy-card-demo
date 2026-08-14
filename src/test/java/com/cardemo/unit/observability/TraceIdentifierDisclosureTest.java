/*
 * ******************************************************************
 * Program     : TraceIdentifierDisclosureTest.java
 * Application : CardDemo
 * Type        : Java 25 / JUnit 5 unit test
 * Function    : Proves that no business identifier reaches an exported
 *               span - not through the span name, not through a tag and
 *               not through the http.url attribute - for the three
 *               routes that carry an identifier in a path segment.
 * Capability  : NEW - guards additive trace hygiene. The frozen corpus
 *               exports no spans, so there is no parity behaviour to
 *               assert here; what is asserted is the Rule 1 Clause D
 *               least-privilege property that the exported surface
 *               identifies nobody.
 * Source      : app/cpy/CSUSR01Y.cpy:L18 @ 7756d89 (SEC-USR-ID PIC X(08))
 * Source      : app/cpy/CVACT01Y.cpy:L18 @ 7756d89 (ACCT-ID PIC 9(11))
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
package com.cardemo.unit.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.cardemo.observability.TemplatedUriObservationConvention;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.observation.DefaultServerRequestObservationConvention;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.filter.ServerHttpObservationFilter;

/**
 * F-S07. Verifies that a business identifier carried in a URL path segment never reaches an exported span.
 *
 * <h2>What this proves, and why it is driven through the real filter</h2>
 *
 * <p>The finding is that concrete administrative URLs reach the trace store. What an exporter reads from an
 * observation is exactly four things: the observation name, the contextual name that becomes the span name,
 * the low-cardinality key values and the high-cardinality key values. Every one of those is produced by the
 * convention, so the assertions below build a real {@link ServerRequestObservationContext} for a concrete
 * request, hand it to the convention, and assert over all four together rather than over one method's return
 * value.
 *
 * <p><strong>Why the context is built directly rather than by dispatching a request.</strong> The route
 * pattern is published onto the context by {@code AbstractUrlHandlerMapping} during MVC dispatch - not by
 * {@link ServerHttpObservationFilter}, which merely creates the context and reads nothing from the request
 * attributes. Driving the filter alone therefore produces an unmatched context whose pattern is {@code null},
 * which would make every assertion here pass for the wrong reason: a suppressed identifier and an
 * unresolvable route look identical. Setting the pattern explicitly is what models a matched request
 * faithfully. {@link FilterIntegration} separately confirms the filter does invoke the injected convention,
 * so the two halves together cover creation and content.
 *
 * <p><strong>No new test dependency was added.</strong> Micrometer's {@code TestObservationRegistry} is not
 * on this project's classpath and pinning a new artefact for one test would contradict section 0.6.1's
 * pinning discipline. A four-line {@link ObservationHandler} collects completed contexts instead, which is
 * both sufficient and closer to what the exporter does.
 *
 * <h2>The control matters as much as the assertion</h2>
 *
 * <p>{@link Regression#theFrameworkDefaultWouldHaveLeakedTheIdentifier()} runs the identical request through
 * Spring's own {@link DefaultServerRequestObservationConvention} and asserts that it DOES disclose the
 * identifier. Without that control, every assertion here would still pass against a convention that did
 * nothing, because a mock request whose path happened not to be attached would look identical to a request
 * whose path was deliberately suppressed. The control is what proves the remediation is load-bearing.
 */
@DisplayName("F-S07: no business identifier reaches an exported span")
class TraceIdentifierDisclosureTest {

    /** The administered user identifier, shaped like {@code SEC-USR-ID} at {@code app/cpy/CSUSR01Y.cpy:L18}. */
    private static final String USER_ID = "USER0001";

    /** The account identifier, shaped like {@code ACCT-ID} at {@code app/cpy/CVACT01Y.cpy:L18}. */
    private static final String ACCOUNT_ID = "00000000001";

    /** The administrative route template, as {@code AdminController} declares it. */
    private static final String ADMIN_PATTERN = "/api/admin/users/{userId}";

    /** The account route template, as {@code AccountController} declares it. */
    private static final String ACCOUNT_PATTERN = "/api/accounts/{accountId}";

    /**
     * Collects every observation context that completes, which is what an exporter would receive.
     *
     * <p>Deliberately not a mock: the assertions concern what the framework hands an exporter, and a stub
     * that recorded a call would prove the call happened rather than what it carried.
     */
    private static final class CapturingHandler implements ObservationHandler<Observation.Context> {

        /** Contexts seen at {@code onStop}, in completion order. */
        private final List<Observation.Context> stopped = new ArrayList<>();

        @Override
        public boolean supportsContext(final Observation.Context context) {
            return true;
        }

        @Override
        public void onStop(final Observation.Context context) {
            this.stopped.add(context);
        }
    }

    /**
     * Builds the observation context a matched request would carry.
     *
     * @param method  the HTTP method; must not be {@code null}
     * @param path    the CONCRETE request path, identifier included; must not be {@code null}
     * @param pattern the route template the handler mapping publishes, or {@code null} for an unmatched
     *                request
     * @return the context, never {@code null}
     */
    private static ServerRequestObservationContext context(
            final String method, final String path, final String pattern) {

        final MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRequestURI(path);
        request.setServletPath(path);
        final MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);

        final ServerRequestObservationContext context =
                new ServerRequestObservationContext(request, response);
        // What AbstractUrlHandlerMapping publishes once it has matched a route. Set explicitly because the
        // observation filter does not derive it, as this class's documentation records.
        context.setPathPattern(pattern);
        return context;
    }

    /**
     * Every string an exporter would read: the observation name, the span name and every key value.
     *
     * @param convention the convention producing them; must not be {@code null}
     * @param context    the context to render; must not be {@code null}
     * @return the exported strings, never {@code null}
     */
    private static List<String> exportedStrings(
            final DefaultServerRequestObservationConvention convention,
            final ServerRequestObservationContext context) {

        final List<String> exported = new ArrayList<>();
        exported.add(convention.getName());
        exported.add(convention.getContextualName(context));
        for (final KeyValue keyValue : convention.getLowCardinalityKeyValues(context)) {
            exported.add(keyValue.getKey() + '=' + keyValue.getValue());
        }
        for (final KeyValue keyValue : convention.getHighCardinalityKeyValues(context)) {
            exported.add(keyValue.getKey() + '=' + keyValue.getValue());
        }
        return exported.stream().filter(value -> value != null).collect(Collectors.toList());
    }

    /**
     * The two administrative routes the finding names.
     */
    @Nested
    @DisplayName("the administrative routes export no user identifier")
    class AdministrativeRoutes {

        @Test
        @DisplayName("PUT /api/admin/users/{userId} exports the template, never the identifier")
        void putExportsNoIdentifier() {
            final List<String> exported = exportedStrings(new TemplatedUriObservationConvention(),
                    context("PUT", "/api/admin/users/" + USER_ID, ADMIN_PATTERN));

            assertThat(exported)
                    .as("not one exported string may contain the administered user's identifier: every one "
                            + "of them is published to the trace store, whose retention and access control "
                            + "are properties of the collector rather than of this application")
                    .noneMatch(value -> value.contains(USER_ID));

            assertThat(exported)
                    .as("and the route must still be identifiable, or the remediation would have been a "
                            + "loss of diagnosability rather than a removal of disclosure")
                    .anyMatch(value -> value.contains(ADMIN_PATTERN));
        }

        @Test
        @DisplayName("DELETE /api/admin/users/{userId} exports the template, never the identifier")
        void deleteExportsNoIdentifier() {
            final List<String> exported = exportedStrings(new TemplatedUriObservationConvention(),
                    context("DELETE", "/api/admin/users/" + USER_ID, ADMIN_PATTERN));

            assertThat(exported)
                    .as("the delete route discloses the identifier of a principal being removed, which is "
                            + "if anything more sensitive than the update route's")
                    .noneMatch(value -> value.contains(USER_ID));
            assertThat(exported).anyMatch(value -> value.contains(ADMIN_PATTERN));
        }

        @Test
        @DisplayName("http.url specifically carries the template and no path segment value")
        void httpUrlCarriesTheTemplate() {
            final List<KeyValue> high = new ArrayList<>();
            new TemplatedUriObservationConvention()
                    .getHighCardinalityKeyValues(
                            context("PUT", "/api/admin/users/" + USER_ID, ADMIN_PATTERN))
                    .forEach(high::add);

            assertThat(high)
                    .as("exactly one high-cardinality key value is expected, http.url; a second would be an "
                            + "attribute this convention has not reasoned about")
                    .hasSize(1);
            assertThat(high.get(0).getKey()).isEqualTo("http.url");
            assertThat(high.get(0).getValue())
                    .as("http.url is the single attribute that carried the concrete path, and it is the one "
                            + "the convention replaces")
                    .isEqualTo(ADMIN_PATTERN);
        }
    }

    /**
     * The account route carries the same eleven-digit identifier that the posting job is forbidden to write
     * to an operational log, so leaving it in a span would have been inconsistent with F-S03.
     */
    @Nested
    @DisplayName("the account route exports no account identifier either")
    class AccountRoute {

        @Test
        @DisplayName("GET /api/accounts/{accountId} exports the template, never the account identifier")
        void getExportsNoAccountIdentifier() {
            final List<String> exported = exportedStrings(new TemplatedUriObservationConvention(),
                    context("GET", "/api/accounts/" + ACCOUNT_ID, ACCOUNT_PATTERN));

            assertThat(exported)
                    .as("this is the same ACCT-ID that DailyTransactionPostingJob must not write to a log; "
                            + "suppressing it there and exporting it here would have been incoherent")
                    .noneMatch(value -> value.contains(ACCOUNT_ID));
            assertThat(exported).anyMatch(value -> value.contains(ACCOUNT_PATTERN));
        }
    }

    /**
     * Boundary behaviour: an unmatched request must not fall through to the raw path. This is precisely where
     * an implementation that fell back to {@code getRequestURI()} would reintroduce the finding.
     */
    @Nested
    @DisplayName("an unmatched request falls back to a bounded constant, never to the raw path")
    class UnmatchedRequests {

        @Test
        @DisplayName("with no matched pattern, nothing exported contains the identifier or the raw path")
        void unmatchedRequestExportsNoPath() {
            final String probe = "/api/admin/users/" + USER_ID + "/../../secrets";
            final List<String> exported = exportedStrings(
                    new TemplatedUriObservationConvention(), context("GET", probe, null));

            assertThat(exported)
                    .as("an unmatched path can carry an identifier just as a matched one can")
                    .noneMatch(value -> value.contains(USER_ID));
            assertThat(exported)
                    .as("and the raw path must not appear in any form")
                    .noneMatch(value -> value.contains("secrets"));
        }

        @Test
        @DisplayName("http.url is the framework's bounded UNKNOWN constant when no route matched")
        void unmatchedRequestExportsBoundedConstant() {
            final List<KeyValue> high = new ArrayList<>();
            new TemplatedUriObservationConvention()
                    .getHighCardinalityKeyValues(context("GET", "/nothing/declared", null))
                    .forEach(high::add);

            assertThat(high).hasSize(1);
            assertThat(high.get(0).getValue())
                    .as("the value is taken from the framework's own safe uri tag, so an unmatched request "
                            + "yields its bounded constant rather than anything request-derived")
                    .isEqualTo("UNKNOWN");
        }
    }

    /**
     * The control. Without this, none of the assertions above would distinguish a working remediation from
     * an inert one.
     */
    @Nested
    @DisplayName("control: the framework default would have leaked the identifier")
    class Regression {

        @Test
        @DisplayName("Spring's own convention DOES export the concrete identifier, proving the fix acts")
        void theFrameworkDefaultWouldHaveLeakedTheIdentifier() {
            final List<String> leaked = exportedStrings(new DefaultServerRequestObservationConvention(),
                    context("PUT", "/api/admin/users/" + USER_ID, ADMIN_PATTERN));

            assertThat(leaked)
                    .as("this is the defect F-S07 reported, reproduced against the unmodified framework "
                            + "default. If this assertion ever fails, the framework changed its behaviour "
                            + "and TemplatedUriObservationConvention should be re-justified rather than "
                            + "silently kept")
                    .anyMatch(value -> value.contains(USER_ID));

            assertThat(exportedStrings(new TemplatedUriObservationConvention(),
                    context("PUT", "/api/admin/users/" + USER_ID, ADMIN_PATTERN)))
                    .as("and the same request through the project's convention discloses nothing, which is "
                            + "the difference the remediation makes")
                    .noneMatch(value -> value.contains(USER_ID));
        }

        @Test
        @DisplayName("the low-cardinality tags were already safe and are left untouched")
        void lowCardinalityTagsAreUnchanged() {
            final ServerRequestObservationContext context =
                    context("PUT", "/api/admin/users/" + USER_ID, ADMIN_PATTERN);
            final TemplatedUriObservationConvention convention = new TemplatedUriObservationConvention();

            final List<String> keys = new ArrayList<>();
            convention.getLowCardinalityKeyValues(context)
                    .forEach(keyValue -> keys.add(keyValue.getKey()));

            assertThat(keys)
                    .as("these five are what the metrics registry is dimensioned by, so widening or "
                            + "narrowing them would change the four bounded instruments MetricsConfig owns. "
                            + "The convention deliberately does not override them: uri already yields the "
                            + "template, never the path")
                    .containsExactlyInAnyOrder("method", "status", "uri", "exception", "outcome");

            assertThat(convention.getLowCardinalityKeyValues(context))
                    .as("uri must be the template and method the verb, both identifier-free")
                    .contains(KeyValue.of("uri", ADMIN_PATTERN),
                            KeyValue.of("method", "PUT".toUpperCase(Locale.ROOT)));

            assertThat(convention.getContextualName(context))
                    .as("the span name already renders from the pattern, which is why it is not overridden")
                    .contains(ADMIN_PATTERN)
                    .doesNotContain(USER_ID);
        }
    }

    /**
     * Confirms the other half of the wiring: the observation filter really does invoke the injected
     * convention, so the content assertions above describe a value that reaches an exporter rather than one
     * that merely could.
     */
    @Nested
    @DisplayName("the observation filter invokes the injected convention")
    class FilterIntegration {

        /** Collects every observation context that completes, which is what an exporter would receive. */
        private final class CapturingHandler implements ObservationHandler<Observation.Context> {

            /** Contexts seen at {@code onStop}, in completion order. */
            private final List<Observation.Context> stopped = new ArrayList<>();

            @Override
            public boolean supportsContext(final Observation.Context observationContext) {
                return true;
            }

            @Override
            public void onStop(final Observation.Context observationContext) {
                this.stopped.add(observationContext);
            }
        }

        @Test
        @DisplayName("a dispatched request completes one observation carrying this convention's http.url")
        void filterUsesTheInjectedConvention() throws Exception {
            final ObservationRegistry registry = ObservationRegistry.create();
            final CapturingHandler handler = new CapturingHandler();
            registry.observationConfig().observationHandler(handler);

            final String path = "/api/admin/users/" + USER_ID;
            final MockHttpServletRequest request = new MockHttpServletRequest("PUT", path);
            request.setRequestURI(path);
            request.setServletPath(path);

            new ServerHttpObservationFilter(registry, new TemplatedUriObservationConvention())
                    .doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

            assertThat(handler.stopped)
                    .as("the filter must complete exactly one observation")
                    .hasSize(1);

            final List<String> exported = new ArrayList<>();
            exported.add(handler.stopped.get(0).getContextualName());
            handler.stopped.get(0).getLowCardinalityKeyValues()
                    .forEach(keyValue -> exported.add(keyValue.getValue()));
            handler.stopped.get(0).getHighCardinalityKeyValues()
                    .forEach(keyValue -> exported.add(keyValue.getValue()));

            assertThat(exported)
                    .as("no handler mapping runs in this harness, so the route is unmatched - and an "
                            + "unmatched request is exactly the case in which the concrete path must still "
                            + "not be exported")
                    .noneMatch(value -> value != null && value.contains(USER_ID));

            final HttpServletRequest served = request;
            assertThat(ServerHttpObservationFilter.findObservationContext(served))
                    .as("and the filter must have published its context on the request, which is the hook "
                            + "MVC dispatch uses to set the pattern in production")
                    .isPresent();
        }
    }

    /**
     * The convention must be discoverable by Boot's {@code ObjectProvider} resolution, which requires it to
     * be a component and to implement the interface the auto-configuration looks up.
     */
    @Test
    @DisplayName("the convention is a scanned component implementing the framework's convention interface")
    void conventionIsRegisteredByComponentScan() {
        assertThat(TemplatedUriObservationConvention.class
                .getAnnotation(org.springframework.stereotype.Component.class))
                .as("Boot's WebMvcObservationAutoConfiguration resolves an ObjectProvider of this type; "
                        + "without the annotation the bean never exists and the default convention - the "
                        + "one that leaks - is used instead, silently")
                .isNotNull();

        assertThat(org.springframework.http.server.observation.ServerRequestObservationConvention.class)
                .as("it must implement the interface the auto-configuration looks up, not merely extend a "
                        + "class with a similar shape")
                .isAssignableFrom(TemplatedUriObservationConvention.class);
    }
}
