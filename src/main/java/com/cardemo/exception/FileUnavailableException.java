/*
 * ******************************************************************
 * Program     : FileUnavailableException.java
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 exception
 * Function    : Typed target of the design-level FILE STATUS '35' / DFHRESP(NOTOPEN)
 *               mapping - dataset or resource unavailable. Note that neither the
 *               literal '35' nor DFHRESP(NOTOPEN) occurs anywhere under app/; the
 *               mapping is established by the migration design rather than by a
 *               source comparison. See com.cardemo.model.enums.FileStatus for the
 *               provenance census.
 * Source      : app/cbl/CBTRN02C.cbl:L236-L252 @ 7756d89 - 0000-DALYTRAN-OPEN, the
 *               universal OPEN guard whose failure branch this type serves. The guard
 *               tests only for '00' and abends on anything else, so it names no
 *               unavailability status of its own.
 * Source      : app/jcl/OPENFIL.jcl @ 7756d89 and app/jcl/CLOSEFIL.jcl @ 7756d89 - the
 *               legacy online file-availability jobs.
 * Source      : app/csd/CARDDEMO.CSD @ 7756d89 - the eight-file online file control table.
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
package com.cardemo.exception;

import java.util.Optional;

/**
 * Reports that a backing store was not open, not defined or not reachable when a CardDemo operation needed it.
 *
 * <p>It is the typed target of the design-level mapping for {@code FILE STATUS '35'} and
 * {@code DFHRESP(NOTOPEN)}: the store itself was unavailable, so the request never reached the point of
 * succeeding or failing on its own merits. That mapping is <strong>specification-derived rather than
 * corpus-derived</strong>: the frozen corpus contains no literal {@code '35'} comparison and no
 * {@code DFHRESP(NOTOPEN)} handler anywhere under {@code app/}, and the universal OPEN guard cited in the banner
 * tests only for {@code '00'} and abends on anything else. What the corpus does establish is the
 * <em>condition</em> - {@code app/jcl/OPENFIL.jcl} and {@code app/jcl/CLOSEFIL.jcl} exist precisely because a
 * dataset could be unavailable to the online region. {@code com.cardemo.model.enums.FileStatus} carries the full
 * response-condition census behind this note. In the Java
 * target the same condition arises from a datasource that cannot hand out a connection, a schema whose
 * migrations have not been applied, an object-storage bucket that does not exist, and a queue whose endpoint
 * does not answer. One type covers all four, because from a caller's point of view they are the same fact -
 * <em>the thing I depend on is not there</em> - and they call for the same operator action.
 *
 * <p>This type <em>reports</em>. It never decides and never repairs. It performs no retry, no reconnect, no
 * probe and no circuit-breaking; it implements no health contract and it does not log.
 *
 * @see CardDemoException
 * @see FileAccessException
 * @see RecordNotFoundException
 */
public class FileUnavailableException extends CardDemoException {

    /**
     * Fixed serialization identity, on the contract described on {@link CardDemoException}.
     */
    private static final long serialVersionUID = 1L;

    /**
     * Logical name of the unavailable resource, or null when the throwing site did not identify one.
     */
    private final String resourceName;

    /**
     * Reports an unavailable resource without naming it and without an underlying throwable.
     *
     * @param message the detail message, retrievable through {@link Throwable#getMessage()}.
     */
    public FileUnavailableException(String message) {
        super(message);
        this.resourceName = null;
    }

    /**
     * Reports an unavailable resource, preserving the throwable that revealed it but without naming the
     * resource.
     *
     * @param message the detail message, retrievable through {@link Throwable#getMessage()}.
     * @param cause the underlying throwable - typically a connection failure, a missing bucket, an unreachable
     * queue endpoint or a failed datasource initialisation - retrievable through {@link Throwable#getCause()}.
     */
    public FileUnavailableException(String message, Throwable cause) {
        super(message, cause);
        this.resourceName = null;
    }

    /**
     * Reports an unavailable resource, naming it and preserving the throwable that revealed it.
     *
     * @param message the detail message, retrievable through {@link Throwable#getMessage()}.
     * @param resourceName the logical name of the unavailable resource - a legacy DD or CICS file name such as
     * {@code ACCTDAT}, or a Java-side table, bucket or queue name.
     * @param cause the underlying throwable, retrievable through {@link Throwable#getCause()}.
     */
    public FileUnavailableException(String message, String resourceName, Throwable cause) {
        super(message, cause);
        this.resourceName = normaliseResourceName(resourceName);
    }

    /**
     * Returns the logical name of the unavailable resource, if the throwing site supplied one.
     *
     * @return the stripped, case-preserved logical resource name, or {@link Optional#empty()} if none was
     * supplied or the supplied value was blank.
     */
    public Optional<String> resourceName() {
        return Optional.ofNullable(this.resourceName);
    }

    /**
     * Collapses a supplied resource name onto a single canonical representation.
     *
     * @param resourceName the value supplied by the throwing site.
     * @return the stripped value, or null if the input was null or contained no non-whitespace character
     */
    private static String normaliseResourceName(String resourceName) {
        if (resourceName == null || resourceName.isBlank()) {
            return null;
        }
        return resourceName.strip();
    }
}
