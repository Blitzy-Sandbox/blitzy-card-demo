/*
 * ******************************************************************
 * Program     : HarnessLifecycleRecord.java
 * Component   : Integration test support, resident at
 *               src/test/java/com/cardemo/integration/repository
 * Application : CardDemo
 * Type        : JUnit 5 test support - no assertion, no Spring context,
 *               no container declaration of its own
 * Function    : Carries the container identity and mapped port that the
 *               first repository harness subclass observed, so that the
 *               second sibling subclass can assert both are still the
 *               same. Two classes are required to detect a per-class
 *               container lifecycle, and the observation therefore has
 *               to outlive the first class - which is the one and only
 *               reason this holder exists.
 * Source      : app/cbl/CBACT04C.cbl:1-21 (banner convention),
 *               CONTRIBUTING.md:33-34 (repository hygiene) @ 7756d89
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
package com.cardemo.integration.repository;

/**
 * Cross-class record of the container identity the first repository harness subclass observed.
 *
 * <h2>What it does</h2>
 *
 * <p>{@link RepositoryHarnessLifecycleTest} writes the shared container's identifier and mapped port here;
 * {@link RepositoryHarnessSeedStateTest} reads them back and asserts that neither moved. That is the whole
 * surface, and it is the minimum needed to detect a per-class container lifecycle: the defect is that the
 * container is stopped after the first class and restarted for the second on a <em>different</em> mapped
 * port, so the evidence is precisely a value observed in one class and compared in another.
 *
 * <h2>Why it holds mutable static state, which the tier otherwise forbids</h2>
 *
 * <p>The repository package's rule is that {@link AbstractRepositoryIntegrationTest#POSTGRES} is its only
 * static field. This holder is the documented, narrow exception, and it is narrow in a way that matters:
 * it carries no database state, no entity, no connection and nothing a test could use to influence another
 * test's outcome. It carries two immutable observations about the container the whole JVM already shares.
 * A JUnit extension store cannot serve the purpose because its scope is the very thing under test - a
 * class-scoped store is discarded exactly when the container would be stopped - and an instance field
 * cannot, because the two observations are made by two different instances of two different classes.
 *
 * <p>The mutability is write-once by contract: {@link #record(String, Integer)} refuses a second write with
 * a different value, so an accidental third subclass cannot silently overwrite the observation the
 * assertion depends on.
 *
 * <h2>Thread safety and side effects</h2>
 *
 * <p>Both fields are {@code volatile} and every method is {@code static synchronized}, so a write is
 * visible to a later reader on any thread. There is no I/O, no logging and no allocation beyond the two
 * references. The values are never rendered into a message that could reach a log, and neither is a
 * credential or a piece of personal data: a container identifier and an ephemeral port carry no secret.
 */
final class HarnessLifecycleRecord {

    /** The container identifier observed by the first sibling, or {@code null} before it has run. */
    private static volatile String observedContainerId;

    /** The mapped port observed by the first sibling, or {@code null} before it has run. */
    private static volatile Integer observedMappedPort;

    /**
     * Not instantiable: this type is a holder, and an instance of it would have no meaning.
     */
    private HarnessLifecycleRecord() {
        throw new AssertionError("HarnessLifecycleRecord is a static holder and is never instantiated");
    }

    /**
     * Records the container identity observed by the first sibling, or confirms an identical re-observation.
     *
     * @param containerId the shared container's identifier, must not be {@code null} or blank
     * @param mappedPort  the shared container's mapped port, must not be {@code null}
     * @throws IllegalArgumentException if either argument is absent or blank
     * @throws IllegalStateException    if a different identity has already been recorded, which means the
     *                                  container was replaced between two subclasses - the exact defect the
     *                                  two-sibling proof exists to catch
     */
    static synchronized void record(final String containerId, final Integer mappedPort) {
        if (containerId == null || containerId.isBlank()) {
            throw new IllegalArgumentException("the observed container identifier must not be absent");
        }
        if (mappedPort == null) {
            throw new IllegalArgumentException("the observed mapped port must not be absent");
        }
        if (observedContainerId != null
                && (!observedContainerId.equals(containerId) || !observedMappedPort.equals(mappedPort))) {
            throw new IllegalStateException("the shared PostgreSQL container was replaced between two "
                    + "subclasses of AbstractRepositoryIntegrationTest, which means a per-class container "
                    + "lifecycle has been reintroduced. The container must be started once per JVM by the "
                    + "harness static initialiser and never stopped.");
        }
        observedContainerId = containerId;
        observedMappedPort = mappedPort;
    }

    /**
     * Returns the container identifier the first sibling observed.
     *
     * @return the identifier, or {@code null} if no sibling has recorded one yet
     */
    static String containerId() {
        return observedContainerId;
    }

    /**
     * Returns the mapped port the first sibling observed.
     *
     * @return the port, or {@code null} if no sibling has recorded one yet
     */
    static Integer mappedPort() {
        return observedMappedPort;
    }
}
