/*
 * ******************************************************************
 * Program     : TemplatedUriObservationConvention.java
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 server observation
 *               convention
 * Function    : Replaces the concrete request path in the exported
 *               http.url span attribute with the route TEMPLATE, so a
 *               user identifier, account identifier or any other
 *               business value carried in a path segment never reaches
 *               the trace store.
 * Capability  : NEW - additive trace hygiene, not a translation. The
 *               frozen corpus exports no spans at all: its entire
 *               instrumentation is DISPLAY to SYSOUT plus the status
 *               renderer at app/cbl/CBTRN02C.cbl:L714-L731, so there is
 *               no COBOL paragraph to cite for this behaviour. Mandated
 *               by Rule 1 Clause D, principle of least privilege, and by
 *               Clause A's requirement for security-conscious defaults.
 * Source      : app/cpy/CSUSR01Y.cpy:L18 @ 7756d89 (SEC-USR-ID PIC X(08)
 *               - the identifier that PUT and DELETE /api/admin/users/
 *               {userId} carries in its path)
 * Source      : app/cpy/CVACT01Y.cpy:L18 @ 7756d89 (ACCT-ID PIC 9(11) -
 *               the identifier that GET /api/accounts/{accountId}
 *               carries in its path)
 * Source      : app/cbl/COUSR02C.cbl, app/cbl/COUSR03C.cbl @ 7756d89
 *               (the two administrative programs whose REST successors
 *               place the user identifier in the URL rather than in a
 *               COMMAREA field)
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
package com.cardemo.observability;

import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import org.springframework.http.server.observation.DefaultServerRequestObservationConvention;
import org.springframework.http.server.observation.ServerHttpObservationDocumentation;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.stereotype.Component;

/**
 * Emits {@code http.url} as the matched route TEMPLATE instead of the concrete request path, so that a
 * business identifier appearing in a path segment never leaves the process inside a span.
 *
 * <h2>What it does</h2>
 *
 * <p>Two routes in this application carry a business identifier in the URL rather than in a body or a query
 * parameter:
 *
 * <ul>
 *   <li>{@code PUT /api/admin/users/{userId}} and {@code DELETE /api/admin/users/{userId}} carry
 *       {@code SEC-USR-ID}, the eight-character identifier of {@code app/cpy/CSUSR01Y.cpy:L18}; and</li>
 *   <li>{@code GET /api/accounts/{accountId}} carries {@code ACCT-ID}, the eleven-digit identifier of
 *       {@code app/cpy/CVACT01Y.cpy:L18} - the same value that
 *       {@code com.cardemo.batch.jobs.DailyTransactionPostingJob} is forbidden to write to an operational
 *       log.</li>
 * </ul>
 *
 * <p>Spring's {@link DefaultServerRequestObservationConvention} attaches the full concrete request path as
 * the single high-cardinality key value {@code http.url}. Every observation is exported to the trace
 * collector, so those identifiers were being published verbatim into a store whose retention, access control
 * and network exposure are properties of the collector rather than of this application. Suppressing the
 * identifier <em>at the point it is attached</em> is the only remedy that does not depend on how the
 * collector happens to be deployed.
 *
 * <h2>Why the template, rather than removing the attribute</h2>
 *
 * <p>Returning nothing would have removed a diagnostic that consumers may select on. Emitting the template
 * keeps {@code http.url} present and keeps it useful - {@code /api/admin/users/&#123;userId&#125;} still says
 * which route ran - while carrying no value that identifies a user, account, card, customer or transaction.
 *
 * <p>The value is taken from the inherited {@link #uri(ServerRequestObservationContext)} key value rather
 * than from the context's path pattern directly. That is deliberate: {@code uri} is the low-cardinality tag
 * the framework already computes and already guarantees to be safe - it yields the pattern when one matched,
 * and the constants {@code UNKNOWN}, {@code NOT_FOUND} or {@code REDIRECTION} when none did, never the raw
 * path. Deriving {@code http.url} from it means the two attributes agree by construction and that an
 * unmatched request - a probe for {@code /api/admin/users/USER0001/../..}, say - cannot fall through to the
 * concrete path, because no branch of this method can produce it.
 *
 * <h2>What it deliberately does not change</h2>
 *
 * <ul>
 *   <li><strong>The span name.</strong> {@code getContextualName} already renders
 *       {@code http put /api/admin/users/&#123;userId&#125;} from the pattern, not from the path, so it needs
 *       no override. Overriding it would restate framework behaviour, which Rule 1 Clause C forbids as
 *       duplication.</li>
 *   <li><strong>The low-cardinality tags.</strong> {@code method}, {@code status}, {@code uri},
 *       {@code exception} and {@code outcome} are all bounded and identifier-free already. These are the
 *       values the metrics registry is dimensioned by, and changing them would alter the four bounded
 *       instruments {@code MetricsConfig} owns.</li>
 *   <li><strong>Query parameters.</strong> The framework's {@code http.url} is built from the request URI
 *       alone and never included a query string, so none is being suppressed here and none can reappear.</li>
 * </ul>
 *
 * <h2>How it is registered</h2>
 *
 * <p>A {@code @Component}, discovered by the scan of {@code com.cardemo}, exactly as its three sibling
 * classes in this package register themselves. Boot's
 * {@code WebMvcObservationAutoConfiguration#webMvcObservationFilter} takes an
 * {@code ObjectProvider<ServerRequestObservationConvention>} and prefers a user-supplied bean over its own
 * default, so no explicit wiring and no {@code @Bean} method is required. It is deliberately NOT declared in
 * {@code com.cardemo.config.ObservabilityConfig}: that class declares exactly one {@code @Bean} method and
 * that count is asserted, because a second {@code Clock} there would be an order-dependent startup failure.
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <dl>
 *   <dt>A span shows {@code http.url} as {@code UNKNOWN}</dt>
 *   <dd>Expected, and not a defect. No handler pattern matched the request - a rejected path, or a request
 *       refused by the security chain before dispatch. The {@code uri} tag reads the same, and the status
 *       tag says what happened.</dd>
 *   <dt>A span still shows a concrete identifier after a route is added</dt>
 *   <dd>The new route places the identifier somewhere this convention does not reach - most likely a query
 *       parameter, which the framework does not attach to {@code http.url} but which a hand-written
 *       {@code Observation} elsewhere could. Search for observations created outside the servlet filter.</dd>
 *   <dt>This convention appears not to run</dt>
 *   <dd>Confirm exactly one bean of type {@code ServerRequestObservationConvention} exists. Boot's
 *       {@code ObjectProvider} resolution treats two as ambiguous and falls back to neither.</dd>
 * </dl>
 *
 * @see com.cardemo.observability.CorrelationIdFilter for the correlation identifier that spans do carry
 */
@Component
public class TemplatedUriObservationConvention extends DefaultServerRequestObservationConvention {

    /** The high-cardinality key whose value this convention replaces, as the framework names it. */
    private static final String HTTP_URL_KEY =
            ServerHttpObservationDocumentation.HighCardinalityKeyNames.HTTP_URL.asString();

    /**
     * Creates the convention.
     *
     * <p>Declared explicitly rather than left implicit so that its one contract is stated: it holds no
     * state, takes no collaborator, and is therefore safe to share across every request thread.
     */
    public TemplatedUriObservationConvention() {
        super();
    }

    /**
     * Returns {@code http.url} carrying the route template rather than the concrete request path.
     *
     * <p>This is the whole purpose of the class. The superclass would return the request URI verbatim, which
     * for {@code PUT /api/admin/users/USER0001} publishes the administered user's identifier into every
     * exported span.
     *
     * <p><strong>Error modes.</strong> None. {@link #uri(ServerRequestObservationContext)} is total: it
     * returns a bounded constant when no pattern matched, so there is no null case and no branch that can
     * yield the raw path.
     *
     * @param context the observation context for the request being recorded; must not be {@code null}
     * @return exactly one key value, {@code http.url}, whose value is the matched route template or a
     *     bounded constant, never {@code null}
     */
    @Override
    public KeyValues getHighCardinalityKeyValues(final ServerRequestObservationContext context) {
        final KeyValue safeUri = uri(context);
        return KeyValues.of(KeyValue.of(HTTP_URL_KEY, safeUri.getValue()));
    }
}
