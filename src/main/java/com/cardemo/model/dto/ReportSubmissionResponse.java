/*
 * ******************************************************************
 * Program     : ReportSubmissionResponse.java
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 data transfer object
 * Function    : The public HTTP body for a report submission: whether
 *               the job was published and the byte-exact screen notice
 *               that names it - and nothing about how a 3270 terminal
 *               would have painted either.
 * Source      : app/cbl/CORPT00C.cbl (649 lines) over mapset CORPT00
 *               :L447-L454 (INITIALIZE-ALL-FIELDS, the DFHGREEN
 *               success attribute and the STRING that composes the
 *               notice),
 *               :L480-L483 (the declined arm: clear, raise the flag,
 *               send - the queue write at :L498-L508 sits inside
 *               IF NOT ERR-FLG-ON so nothing is published),
 *               :L517-L523 (EXEC CICS WRITEQ TD QUEUE('JOBS'))
 *               @ 7756d89
 * Source      : app/cpy-bms/CORPT00.CPY (17 input fields) @ 7756d89
 * Source      : app/csd/CARDDEMO.CSD (DEFINE TDQUEUE(JOBS)
 *               RECORDSIZE(80) RECORDFORMAT(FIXED)) @ 7756d89
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
package com.cardemo.model.dto;

/**
 * The outcome of a report submission, as an HTTP response.
 *
 * <h2>What it does</h2>
 *
 * <p>It reports whether the report job was published and carries the source's own notice. It is not the
 * service's screen record, which additionally carries the whole submitted map area, an error flag, a
 * success-highlight flag standing in for {@code MOVE DFHGREEN TO ERRMSGC} at
 * {@code app/cbl/CORPT00C.cbl:L448}, the symbolic-map length field that received {@code -1}, and the program a
 * transfer of control would have reached. The last four describe a 3270 terminal and a CICS dispatch, and none
 * belongs on a JSON contract.</p>
 *
 * <h2>The two endings</h2>
 *
 * <ul>
 *   <li><b>Published.</b> {@code :L447-L454}: the deck was written to the queue, the fields were cleared and
 *       the success notice was composed. The batch tier produces the report asynchronously, so the instruction
 *       is accepted rather than completed.</li>
 *   <li><b>Declined.</b> {@code :L480-L483}: the confirmation was negative, so the fields were cleared and the
 *       error flag was raised. The queue write at {@code :L498-L508} sits inside {@code IF NOT ERR-FLG-ON}, so
 *       nothing was published. The request was understood and answered, which is why it is a successful
 *       no-write outcome rather than a rejection.</li>
 *   </ul>
 *
 * <p>Every other ending is a typed failure - the seventeen validation refusals and the publish failure - and
 * none of them produces this type.</p>
 *
 * <h2>Why the resolved period is not echoed</h2>
 *
 * <p>Deliberately, and the reason is source behaviour rather than an omission. On the published path
 * {@code :L447} runs {@code INITIALIZE-ALL-FIELDS} <b>before</b> the notice is composed, so the three period
 * selectors and the six custom-range components are blank by the time the turn ends - there is nothing on the
 * screen to echo. The notice itself names the report, because {@code :L449-L452} composes it by
 * {@code STRING WS-REPORT-NAME DELIMITED BY SPACE}, and that is the source's own way of telling the operator
 * which report was submitted. Reconstructing the resolved dates here would mean re-deriving the period
 * selection outside the service that owns it, which is the duplication the code-quality standard forbids.</p>
 *
 * <h2>Inputs, outputs, side effects, failure modes</h2>
 *
 * <p><b>Inputs.</b> The two components below, supplied by the operation. <b>Outputs.</b> Serialised by
 * Jackson; every component is a JSON property and there are no others. <b>Side effects.</b> None.
 * <b>Failure modes.</b> None: a failing turn raises a typed exception instead of producing this type.</p>
 *
 * @param published whether the report job reached the queue. False on the declined arm, where nothing was
 *     published and nothing failed
 * @param message the byte-exact screen notice from {@code WS-MESSAGE}, which names the report on the published
 *     path. Relayed unchanged, including the source's own spacing; may be null or blank on the declined arm,
 *     where {@code INITIALIZE-ALL-FIELDS} blanks the message and no literal replaces it
 */
public record ReportSubmissionResponse(
        boolean published,
        String message) {
}
