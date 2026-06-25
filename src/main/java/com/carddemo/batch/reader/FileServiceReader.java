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
package com.carddemo.batch.reader;

import com.carddemo.entity.Account;
import com.carddemo.entity.CardXref;
import com.carddemo.entity.Customer;
import com.carddemo.entity.Transaction;
import com.carddemo.enums.FileStatusCode;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardXrefRepository;
import com.carddemo.repository.CustomerRepository;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.service.FileStatusMapper;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

/**
 * Callable file-service component for the statement-generation pipeline, the
 * Java translation of the COBOL batch subroutine {@code app/cbl/CBSTM03B.CBL}
 * (source commit {@code 27d6c6f}).
 *
 * <p>In the legacy system the statement generator {@code CBSTM03A} issued a
 * static {@code CALL 'CBSTM03B'} passing the {@code LK-M03B-AREA} linkage record
 * to open, read, and close four VSAM datasets: {@code TRNXFILE}, {@code XREFFILE},
 * {@code CUSTFILE}, and {@code ACCTFILE}. This component replaces that static
 * call with constructor-injected Spring Data repositories and exposes an
 * {@code open} / {@code read} / {@code readByKey} / {@code close} API:</p>
 *
 * <ul>
 *   <li>{@link ServiceFile#TRNXFILE} and {@link ServiceFile#XREFFILE} are
 *       <em>sequential</em> (COBOL {@code ACCESS MODE IS SEQUENTIAL}). They are
 *       read through a forward-only cursor established by {@link #open(ServiceFile)}
 *       and advanced one record per {@link #read(ServiceFile)} call, in VSAM key
 *       order ({@code (cardNum, tranId)} for transactions, {@code xrefCardNum}
 *       for cross-references).</li>
 *   <li>{@link ServiceFile#CUSTFILE} and {@link ServiceFile#ACCTFILE} are
 *       <em>keyed/random</em> (COBOL {@code ACCESS MODE IS RANDOM}). They are read
 *       by primary key through {@link #readByKey(ServiceFile, String)}; for these
 *       files {@link #open(ServiceFile)} is a no-op because no cursor is held.</li>
 * </ul>
 *
 * <p>The {@code WRITE} ({@code 'W'}) and {@code REWRITE} ({@code 'Z'}) operation
 * codes are declared in the COBOL linkage 88-levels but are never exercised by
 * any procedure; this service is therefore read-only and {@link #write(ServiceFile)}
 * / {@link #rewrite(ServiceFile)} throw {@link UnsupportedOperationException}.</p>
 *
 * <p>End-of-file on a sequential read (COBOL {@code FILE STATUS '10'}) and a
 * missing record on a keyed read (COBOL {@code FILE STATUS '23'}) are signalled
 * by an empty {@link Optional} and never raise an exception, matching the legacy
 * caller's status-driven loop control. Any hard data-access failure is routed
 * through {@link FileStatusMapper} so it surfaces as the application's typed
 * exception hierarchy, exactly as the legacy {@code 9x} file-status family did.</p>
 *
 * <p>The component is {@link StepScope step-scoped}: each Spring Batch step
 * execution receives a fresh instance, so the sequential cursors are confined to
 * a single execution and never shared across threads.</p>
 */
@Component
@StepScope
public class FileServiceReader {

    /**
     * Logical file identifiers dispatched by the legacy {@code LK-M03B-DD}
     * eight-character file name. The constant names match the COBOL DD names
     * verbatim.
     */
    public enum ServiceFile {

        /** Transaction file ({@code TRNXFILE}) — sequential by {@code (cardNum, tranId)}. */
        TRNXFILE(true),

        /** Card cross-reference file ({@code XREFFILE}) — sequential by {@code xrefCardNum}. */
        XREFFILE(true),

        /** Customer master file ({@code CUSTFILE}) — keyed/random by customer id. */
        CUSTFILE(false),

        /** Account master file ({@code ACCTFILE}) — keyed/random by account id. */
        ACCTFILE(false);

        private final boolean sequential;

        ServiceFile(boolean sequential) {
            this.sequential = sequential;
        }

        /**
         * Indicates whether the file is read sequentially through a cursor.
         *
         * @return {@code true} for {@link #TRNXFILE} and {@link #XREFFILE}
         */
        public boolean isSequential() {
            return sequential;
        }

        /**
         * Indicates whether the file is read by key (random access).
         *
         * @return {@code true} for {@link #CUSTFILE} and {@link #ACCTFILE}
         */
        public boolean isKeyed() {
            return !sequential;
        }

        /**
         * Resolves the legacy eight-character {@code LK-M03B-DD} file name to a
         * {@link ServiceFile}, preserving the COBOL {@code EVALUATE LK-M03B-DD}
         * dispatch order and its {@code WHEN OTHER} branch.
         *
         * @param ddName the eight-character DD file name; surrounding whitespace
         *               is ignored
         * @return the matching {@link ServiceFile}
         * @throws IllegalArgumentException when {@code ddName} is not one of the
         *                                  four supported files
         */
        public static ServiceFile fromDdName(String ddName) {
            String normalized = ddName == null ? "" : ddName.trim();
            return switch (normalized) {
                case "TRNXFILE" -> TRNXFILE;
                case "XREFFILE" -> XREFFILE;
                case "CUSTFILE" -> CUSTFILE;
                case "ACCTFILE" -> ACCTFILE;
                default -> throw new IllegalArgumentException("Unsupported file: " + ddName);
            };
        }
    }

    /**
     * Operation codes dispatched by the legacy {@code LK-M03B-OPER} one-character
     * field. The character codes match the COBOL 88-level condition names exactly.
     */
    public enum Operation {

        /** Open a file ({@code M03B-OPEN}, {@code 'O'}). */
        OPEN('O'),

        /** Close a file ({@code M03B-CLOSE}, {@code 'C'}). */
        CLOSE('C'),

        /** Sequential read of the next record ({@code M03B-READ}, {@code 'R'}). */
        READ('R'),

        /** Keyed read by primary key ({@code M03B-READ-K}, {@code 'K'}). */
        READ_K('K'),

        /** Write a record ({@code M03B-WRITE}, {@code 'W'}) — never implemented. */
        WRITE('W'),

        /** Rewrite a record ({@code M03B-REWRITE}, {@code 'Z'}) — never implemented. */
        REWRITE('Z');

        private final char code;

        Operation(char code) {
            this.code = code;
        }

        /**
         * Returns the single-character COBOL operation code.
         *
         * @return the operation code character
         */
        public char getCode() {
            return code;
        }

        /**
         * Resolves a single-character COBOL operation code to its constant,
         * mirroring the {@code LK-M03B-OPER} 88-level character tests.
         *
         * @param code the operation code character
         * @return the matching {@link Operation}
         * @throws IllegalArgumentException when {@code code} is not a recognized
         *                                  operation
         */
        public static Operation fromCode(char code) {
            for (Operation operation : values()) {
                if (operation.code == code) {
                    return operation;
                }
            }
            throw new IllegalArgumentException("Unsupported operation code: " + code);
        }

        /**
         * Resolves a one-character COBOL operation code to its constant.
         *
         * @param code a string holding exactly one operation code character
         * @return the matching {@link Operation}
         * @throws IllegalArgumentException when {@code code} is {@code null}, is
         *                                  not exactly one character, or is not a
         *                                  recognized operation
         */
        public static Operation fromCode(String code) {
            if (code == null || code.length() != 1) {
                throw new IllegalArgumentException(
                        "Operation code must be a single character: " + code);
            }
            return fromCode(code.charAt(0));
        }
    }

    /** Sequential read order for {@link ServiceFile#TRNXFILE}: the VSAM {@code (card, tranId)} key. */
    private static final Sort TRANSACTION_SORT = Sort.by("cardNum", "tranId");

    /** Sequential read order for {@link ServiceFile#XREFFILE}: the VSAM card-number key. */
    private static final Sort CARD_XREF_SORT = Sort.by("xrefCardNum");

    private final TransactionRepository transactionRepository;
    private final CardXrefRepository cardXrefRepository;
    private final CustomerRepository customerRepository;
    private final AccountRepository accountRepository;
    private final FileStatusMapper fileStatusMapper;

    private Iterator<Transaction> transactionCursor;
    private Iterator<CardXref> cardXrefCursor;

    /**
     * Creates the file-service reader with its backing repositories and the file
     * status mapper.
     *
     * @param transactionRepository repository backing {@link ServiceFile#TRNXFILE}
     * @param cardXrefRepository    repository backing {@link ServiceFile#XREFFILE}
     * @param customerRepository    repository backing {@link ServiceFile#CUSTFILE}
     * @param accountRepository     repository backing {@link ServiceFile#ACCTFILE}
     * @param fileStatusMapper      translator of file-status conditions into the
     *                              typed exception hierarchy
     */
    public FileServiceReader(TransactionRepository transactionRepository,
                             CardXrefRepository cardXrefRepository,
                             CustomerRepository customerRepository,
                             AccountRepository accountRepository,
                             FileStatusMapper fileStatusMapper) {
        this.transactionRepository = transactionRepository;
        this.cardXrefRepository = cardXrefRepository;
        this.customerRepository = customerRepository;
        this.accountRepository = accountRepository;
        this.fileStatusMapper = fileStatusMapper;
    }

    /**
     * Opens a file for reading, mirroring the COBOL {@code M03B-OPEN} operation.
     *
     * <p>For the sequential files {@link ServiceFile#TRNXFILE} and
     * {@link ServiceFile#XREFFILE} a fresh forward-only cursor is established in
     * VSAM key order. For the keyed files {@link ServiceFile#CUSTFILE} and
     * {@link ServiceFile#ACCTFILE} this is a no-op, because keyed reads hold no
     * cursor state.</p>
     *
     * <p>Any hard data-access failure is routed through {@link FileStatusMapper}
     * and surfaces as the application's typed file-access exception, mirroring
     * the legacy {@code 9x} file-status family.</p>
     *
     * @param file the file to open
     * @throws NullPointerException when {@code file} is {@code null}
     */
    public void open(ServiceFile file) {
        Objects.requireNonNull(file, "file must not be null");
        if (file == ServiceFile.TRNXFILE) {
            this.transactionCursor =
                    openSequentialCursor(() -> transactionRepository.findAll(TRANSACTION_SORT), file);
        } else if (file == ServiceFile.XREFFILE) {
            this.cardXrefCursor =
                    openSequentialCursor(() -> cardXrefRepository.findAll(CARD_XREF_SORT), file);
        }
    }

    /**
     * Closes a file, mirroring the COBOL {@code M03B-CLOSE} operation.
     *
     * <p>Releases the sequential cursor for {@link ServiceFile#TRNXFILE} and
     * {@link ServiceFile#XREFFILE}; a no-op for the keyed files. The call is
     * idempotent and safe to invoke on a file that was never opened.</p>
     *
     * @param file the file to close
     * @throws NullPointerException when {@code file} is {@code null}
     */
    public void close(ServiceFile file) {
        Objects.requireNonNull(file, "file must not be null");
        if (file == ServiceFile.TRNXFILE) {
            this.transactionCursor = null;
        } else if (file == ServiceFile.XREFFILE) {
            this.cardXrefCursor = null;
        }
    }

    /**
     * Reads the next sequential record, mirroring the COBOL {@code M03B-READ}
     * operation and preserving the {@code EVALUATE LK-M03B-DD} dispatch order.
     *
     * @param file a sequential file ({@link ServiceFile#TRNXFILE} or
     *             {@link ServiceFile#XREFFILE})
     * @return the next record in key order, or an empty {@link Optional} at
     *         end-of-data (COBOL {@code FILE STATUS '10'})
     * @throws NullPointerException     when {@code file} is {@code null}
     * @throws IllegalArgumentException when {@code file} is a keyed file
     *                                  ({@link ServiceFile#CUSTFILE} or
     *                                  {@link ServiceFile#ACCTFILE})
     * @throws IllegalStateException    when the file has not been opened
     */
    public Optional<?> read(ServiceFile file) {
        Objects.requireNonNull(file, "file must not be null");
        return switch (file) {
            case TRNXFILE -> readTransaction();
            case XREFFILE -> readCardXref();
            case CUSTFILE, ACCTFILE -> throw new IllegalArgumentException(
                    "Sequential read is not supported for keyed file " + file
                            + "; use readByKey instead");
        };
    }

    /**
     * Reads the next {@link Transaction} from the {@link ServiceFile#TRNXFILE}
     * cursor.
     *
     * @return the next transaction, or an empty {@link Optional} at end-of-data
     * @throws IllegalStateException when {@link ServiceFile#TRNXFILE} has not been
     *                               opened
     */
    public Optional<Transaction> readTransaction() {
        return nextFromCursor(transactionCursor, ServiceFile.TRNXFILE);
    }

    /**
     * Reads the next {@link CardXref} from the {@link ServiceFile#XREFFILE}
     * cursor.
     *
     * @return the next cross-reference, or an empty {@link Optional} at
     *         end-of-data
     * @throws IllegalStateException when {@link ServiceFile#XREFFILE} has not been
     *                               opened
     */
    public Optional<CardXref> readCardXref() {
        return nextFromCursor(cardXrefCursor, ServiceFile.XREFFILE);
    }

    /**
     * Reads a record by primary key, mirroring the COBOL {@code M03B-READ-K}
     * operation and preserving the {@code EVALUATE LK-M03B-DD} dispatch order.
     *
     * <p>The {@code key} is trimmed before conversion, honouring the legacy
     * {@code LK-M03B-KEY (1:LK-M03B-KEY-LN)} significant-length reference
     * modification.</p>
     *
     * @param file a keyed file ({@link ServiceFile#CUSTFILE} or
     *             {@link ServiceFile#ACCTFILE})
     * @param key  the primary key value
     * @return the matching record, or an empty {@link Optional} when no record
     *         exists for the key (COBOL {@code FILE STATUS '23'})
     * @throws NullPointerException     when {@code file} is {@code null}
     * @throws IllegalArgumentException when {@code file} is a sequential file, or
     *                                  when {@code key} is {@code null}, blank, or
     *                                  not a valid numeric identifier
     */
    public Optional<?> readByKey(ServiceFile file, String key) {
        Objects.requireNonNull(file, "file must not be null");
        return switch (file) {
            case CUSTFILE -> readCustomerByKey(key);
            case ACCTFILE -> readAccountByKey(key);
            case TRNXFILE, XREFFILE -> throw new IllegalArgumentException(
                    "Keyed read is not supported for sequential file " + file
                            + "; use read instead");
        };
    }

    /**
     * Reads a {@link Customer} by primary key from {@link ServiceFile#CUSTFILE}.
     *
     * @param key the customer identifier; trimmed before numeric conversion
     * @return the matching customer, or an empty {@link Optional} when absent
     * @throws IllegalArgumentException when {@code key} is {@code null}, blank, or
     *                                  not a valid numeric identifier
     */
    public Optional<Customer> readCustomerByKey(String key) {
        Long id = toNumericKey(key, ServiceFile.CUSTFILE);
        return findByKey(() -> customerRepository.findById(id), ServiceFile.CUSTFILE, id);
    }

    /**
     * Reads an {@link Account} by primary key from {@link ServiceFile#ACCTFILE}.
     *
     * @param key the account identifier; trimmed before numeric conversion
     * @return the matching account, or an empty {@link Optional} when absent
     * @throws IllegalArgumentException when {@code key} is {@code null}, blank, or
     *                                  not a valid numeric identifier
     */
    public Optional<Account> readAccountByKey(String key) {
        Long id = toNumericKey(key, ServiceFile.ACCTFILE);
        return findByKey(() -> accountRepository.findById(id), ServiceFile.ACCTFILE, id);
    }

    /**
     * Unsupported write operation. The COBOL {@code M03B-WRITE} ({@code 'W'})
     * condition name is declared but never exercised by any procedure, so this
     * file service is read-only.
     *
     * @param file the target file
     * @throws UnsupportedOperationException always
     */
    public void write(ServiceFile file) {
        throw new UnsupportedOperationException(
                "WRITE is not implemented by the read-only file service (file=" + file + ")");
    }

    /**
     * Unsupported rewrite operation. The COBOL {@code M03B-REWRITE} ({@code 'Z'})
     * condition name is declared but never exercised by any procedure, so this
     * file service is read-only.
     *
     * @param file the target file
     * @throws UnsupportedOperationException always
     */
    public void rewrite(ServiceFile file) {
        throw new UnsupportedOperationException(
                "REWRITE is not implemented by the read-only file service (file=" + file + ")");
    }

    /**
     * Dispatches a single linkage-style request on the supplied {@link Operation},
     * mirroring the COBOL {@code LK-M03B-OPER} handling: {@code O}/{@code C} open
     * and close, {@code R} reads sequentially, {@code K} reads by key, and
     * {@code W}/{@code Z} are unsupported.
     *
     * @param file      the target file
     * @param operation the operation to perform
     * @param key       the key for keyed reads; ignored for other operations and
     *                  may be {@code null}
     * @return the record produced by {@link Operation#READ} / {@link Operation#READ_K},
     *         otherwise an empty {@link Optional}
     * @throws NullPointerException          when {@code operation} is {@code null}
     * @throws UnsupportedOperationException when {@code operation} is
     *                                       {@link Operation#WRITE} or
     *                                       {@link Operation#REWRITE}
     */
    public Optional<?> execute(ServiceFile file, Operation operation, String key) {
        Objects.requireNonNull(operation, "operation must not be null");
        switch (operation) {
            case OPEN -> open(file);
            case CLOSE -> close(file);
            case READ -> {
                return read(file);
            }
            case READ_K -> {
                return readByKey(file, key);
            }
            case WRITE -> write(file);
            case REWRITE -> rewrite(file);
        }
        return Optional.empty();
    }

    /**
     * Establishes a forward-only cursor over a sequential file, translating any
     * data-access failure into the typed exception hierarchy via
     * {@link FileStatusMapper}.
     *
     * @param query the sorted query whose result backs the cursor
     * @param file  the file being opened, used for diagnostics
     * @param <T>   the record type
     * @return an iterator positioned before the first record
     */
    private <T> Iterator<T> openSequentialCursor(Supplier<List<T>> query, ServiceFile file) {
        try {
            return query.get().iterator();
        } catch (DataAccessException ex) {
            fileStatusMapper.check(FileStatusCode.LOGIC_ERROR, "OPEN " + file, null);
            throw ex;
        }
    }

    /**
     * Returns the next record from a sequential cursor, or an empty
     * {@link Optional} once the cursor is exhausted.
     *
     * @param cursor the open cursor, or {@code null} when the file is not open
     * @param file   the file being read, used for diagnostics
     * @param <T>    the record type
     * @return the next record, or an empty {@link Optional} at end-of-data
     * @throws IllegalStateException when {@code cursor} is {@code null}
     */
    private <T> Optional<T> nextFromCursor(Iterator<T> cursor, ServiceFile file) {
        if (cursor == null) {
            throw new IllegalStateException("File is not open for sequential read: " + file);
        }
        return cursor.hasNext() ? Optional.of(cursor.next()) : Optional.empty();
    }

    /**
     * Trims the supplied key (honouring the legacy significant-length reference
     * modification) and converts it to the numeric primary-key type.
     *
     * @param key  the raw key value
     * @param file the file being read, used for diagnostics
     * @return the numeric key
     * @throws IllegalArgumentException when {@code key} is {@code null}, blank, or
     *                                  not a valid numeric identifier
     */
    private Long toNumericKey(String key, ServiceFile file) {
        if (key == null) {
            throw new IllegalArgumentException("Key must not be null for keyed read on " + file);
        }
        String trimmed = key.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Key must not be blank for keyed read on " + file);
        }
        try {
            return Long.valueOf(trimmed);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(
                    "Key '" + key + "' is not a valid numeric identifier for " + file, ex);
        }
    }

    /**
     * Performs a keyed lookup, translating any data-access failure into the typed
     * exception hierarchy via {@link FileStatusMapper}. A missing record returns
     * an empty {@link Optional} (COBOL {@code FILE STATUS '23'}) and is never an
     * error.
     *
     * @param lookup the repository lookup to execute
     * @param file   the file being read, used for diagnostics
     * @param key    the resolved key, used for diagnostics
     * @param <T>    the record type
     * @return the looked-up record, or an empty {@link Optional} when absent
     */
    private <T> Optional<T> findByKey(Supplier<Optional<T>> lookup, ServiceFile file, Object key) {
        try {
            return lookup.get();
        } catch (DataAccessException ex) {
            fileStatusMapper.check(FileStatusCode.LOGIC_ERROR, "READ " + file, key);
            throw ex;
        }
    }
}
