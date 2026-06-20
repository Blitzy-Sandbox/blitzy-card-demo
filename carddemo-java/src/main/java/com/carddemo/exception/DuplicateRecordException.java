package com.carddemo.exception;

/**
 * Maps COBOL FILE STATUS {@code '22'} (duplicate key on an indexed {@code WRITE}) to a
 * Java exception.
 *
 * <p>In VSAM, writing a record to an indexed dataset whose {@code RECORD KEY} already
 * exists returns FILE STATUS {@code '22'} (duplicate key); FILE STATUS {@code '00'}
 * (success) maps to no exception. This type is the idiomatic Java equivalent of that
 * condition and the natural mapping for a JPA primary-key / unique-constraint insert
 * collision, so repository and service code can signal a duplicate-key insert failure
 * with a domain-specific exception rather than a raw persistence error.</p>
 *
 * <p>Modeled on the indexed creates in {@code app/cbl/CBTRN02C.cbl}: the transaction
 * write in {@code 2900-WRITE-TRANSACTION-FILE} (~L564) and the transaction-category
 * balance write in {@code 2700-A-CREATE-TCATBAL-REC} (~L510), source commit
 * {@code 27d6c6f}.</p>
 *
 * <p>Translation of a raw FILE STATUS string to this exception type is the
 * responsibility of {@code FileStatusMapper}; this class deliberately carries no
 * Spring or web annotations so it remains a standalone domain exception.</p>
 *
 * @see CardDemoException
 */
public class DuplicateRecordException extends CardDemoException {

    /** Serialization version identifier for this exception type. */
    private static final long serialVersionUID = 1L;

    /** COBOL FILE STATUS code represented by this exception. */
    public static final String FILE_STATUS = "22";

    /**
     * Creates an exception with the supplied detail message.
     *
     * @param message human-readable description of the duplicate-key condition
     */
    public DuplicateRecordException(String message) {
        super(message);
    }

    /**
     * Creates an exception with the supplied detail message and underlying cause.
     *
     * @param message human-readable description of the duplicate-key condition
     * @param cause   the underlying cause (for example, a persistence-layer
     *                constraint-violation exception)
     */
    public DuplicateRecordException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Builds an exception for a keyed write that collided with an existing key.
     *
     * <p>Provided as a static factory rather than a {@code (String, Object)}
     * constructor so callers can pass an arbitrary key object without colliding with
     * the {@code (String, Throwable)} constructor overload.</p>
     *
     * @param entityName logical entity/dataset name (for example {@code "Transaction"})
     * @param key        the key value that already exists; {@code null} is rendered
     *                   safely via {@link String#valueOf(Object)}
     * @return a new {@code DuplicateRecordException} with a descriptive message
     */
    public static DuplicateRecordException forKey(String entityName, Object key) {
        return new DuplicateRecordException(entityName + " already exists for key: " + String.valueOf(key));
    }

    /**
     * Returns the COBOL FILE STATUS code ({@code '22'}) represented by this exception.
     *
     * @return the two-character FILE STATUS code, preserving the COBOL 2-byte status form
     */
    public String getFileStatus() {
        return FILE_STATUS;
    }
}
