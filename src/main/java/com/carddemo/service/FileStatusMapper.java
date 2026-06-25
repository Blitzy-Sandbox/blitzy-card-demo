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
package com.carddemo.service;

import com.carddemo.enums.FileStatusCode;
import com.carddemo.exception.DuplicateRecordException;
import com.carddemo.exception.FileAccessException;
import com.carddemo.exception.RecordNotFoundException;
import org.springframework.stereotype.Component;

/**
 * Central translator of COBOL VSAM {@code FILE STATUS} codes into the typed
 * exception hierarchy used across the application.
 *
 * <p>The legacy programs under {@code app/cbl} (@ {@code 27d6c6f}) branch on a
 * two-character file-status value after every I/O verb: {@code CBACT01C} treats
 * {@code "00"} as success, {@code "10"} as end-of-file, and any other value as
 * an abend condition, while the CICS online programs such as {@code COACTVWC}
 * evaluate the response code into {@code NORMAL} (success), {@code NOTFND}
 * (record not found), and a catch-all error branch. This component maps each of
 * those conditions to a single, canonical outcome so callers never re-implement
 * the branch logic.</p>
 *
 * <p>The component is stateless and therefore thread-safe; it holds no fields
 * and performs no I/O, persistence, or business logic. It is registered as a
 * Spring bean so it can be constructor-injected into services and batch
 * components.</p>
 */
@Component
public class FileStatusMapper {

    /**
     * Operation label applied when a raw status is resolved outside an explicit
     * I/O operation, such as the {@link #isSuccess(String)} convenience check.
     */
    private static final String STATUS_CHECK_OPERATION = "FILE STATUS CHECK";

    /**
     * Translates a {@link FileStatusCode} into the matching outcome, returning
     * normally for benign statuses and throwing the corresponding typed
     * exception otherwise.
     *
     * <p>The status codes {@link FileStatusCode#SUCCESS},
     * {@link FileStatusCode#FILE_CREATED},
     * {@link FileStatusCode#DUPLICATE_ALTERNATE_KEY}, and
     * {@link FileStatusCode#END_OF_FILE} return without throwing. End-of-file is
     * a control signal rather than an error and is detected separately through
     * {@link #isEndOfFile(FileStatusCode)}.</p>
     *
     * @param status    the resolved file-status code; a {@code null} value is
     *                  treated as a data-access fault
     * @param operation the name of the failing operation used for diagnostics
     *                  (for example {@code "READ ACCTFILE"})
     * @param key       the record key associated with the operation, used to
     *                  build the not-found and duplicate messages
     * @throws RecordNotFoundException  when {@code status} is
     *                                  {@link FileStatusCode#RECORD_NOT_FOUND}
     * @throws DuplicateRecordException when {@code status} is
     *                                  {@link FileStatusCode#DUPLICATE_KEY}
     * @throws FileAccessException      when {@code status} is
     *                                  {@link FileStatusCode#RECORD_LENGTH_MISMATCH},
     *                                  {@link FileStatusCode#FILE_NOT_FOUND},
     *                                  {@link FileStatusCode#LOGIC_ERROR}, or
     *                                  {@code null}
     */
    public void check(FileStatusCode status, String operation, Object key) {
        switch (status) {
            case SUCCESS, FILE_CREATED, DUPLICATE_ALTERNATE_KEY, END_OF_FILE -> {
            }
            case RECORD_NOT_FOUND -> throw new RecordNotFoundException(operation, key);
            case DUPLICATE_KEY -> throw new DuplicateRecordException(operation, key);
            case RECORD_LENGTH_MISMATCH, FILE_NOT_FOUND, LOGIC_ERROR ->
                    throw new FileAccessException(operation, status.getCode(), null);
            case null, default ->
                    throw new FileAccessException(operation, status == null ? null : status.getCode(), null);
        }
    }

    /**
     * Resolves a raw two-character {@code FILE STATUS} value and delegates to
     * {@link #check(FileStatusCode, String, Object)}.
     *
     * @param twoCharStatus the raw two-character status value; the {@code 9x}
     *                      family resolves to {@link FileStatusCode#LOGIC_ERROR}
     * @param operation     the name of the failing operation used for
     *                      diagnostics
     * @param key           the record key associated with the operation
     * @throws RecordNotFoundException  when the resolved status is
     *                                  {@link FileStatusCode#RECORD_NOT_FOUND}
     * @throws DuplicateRecordException when the resolved status is
     *                                  {@link FileStatusCode#DUPLICATE_KEY}
     * @throws FileAccessException      when the resolved status is a logic or
     *                                  I/O error, or when {@code twoCharStatus}
     *                                  is {@code null}, blank, or not a
     *                                  recognized non-{@code 9x} code
     */
    public void check(String twoCharStatus, String operation, Object key) {
        check(resolve(twoCharStatus, operation), operation, key);
    }

    /**
     * Indicates whether the supplied status signals end-of-file.
     *
     * @param status the file-status code to test
     * @return {@code true} only when {@code status} is
     *         {@link FileStatusCode#END_OF_FILE}
     */
    public boolean isEndOfFile(FileStatusCode status) {
        return status == FileStatusCode.END_OF_FILE;
    }

    /**
     * Indicates whether the supplied status signals successful completion.
     *
     * @param status the file-status code to test
     * @return {@code true} only when {@code status} is non-{@code null} and is
     *         {@link FileStatusCode#SUCCESS}
     */
    public boolean isSuccess(FileStatusCode status) {
        return status != null && status.isSuccess();
    }

    /**
     * Indicates whether the supplied raw two-character status signals
     * successful completion.
     *
     * @param twoCharStatus the raw two-character status value
     * @return {@code true} only when the resolved status is
     *         {@link FileStatusCode#SUCCESS}
     * @throws FileAccessException when {@code twoCharStatus} is {@code null},
     *                             blank, or not a recognized non-{@code 9x} code
     */
    public boolean isSuccess(String twoCharStatus) {
        return isSuccess(resolve(twoCharStatus, STATUS_CHECK_OPERATION));
    }

    /**
     * Resolves a raw two-character {@code FILE STATUS} value into a
     * {@link FileStatusCode}, converting any unrecognized, {@code null}, or
     * blank value into a typed {@link FileAccessException}. This keeps every
     * file-status failure inside the typed exception hierarchy so callers never
     * encounter a raw {@link IllegalArgumentException} from status parsing.
     *
     * @param twoCharStatus the raw two-character status value; the {@code 9x}
     *                      family resolves to {@link FileStatusCode#LOGIC_ERROR}
     * @param operation     the name of the operation used for diagnostics
     * @return the resolved {@link FileStatusCode}
     * @throws FileAccessException when {@code twoCharStatus} is {@code null},
     *                             blank, or not a recognized non-{@code 9x} code
     */
    private static FileStatusCode resolve(String twoCharStatus, String operation) {
        try {
            return FileStatusCode.fromCode(twoCharStatus);
        } catch (IllegalArgumentException ex) {
            throw new FileAccessException(operation, twoCharStatus, ex);
        }
    }
}
