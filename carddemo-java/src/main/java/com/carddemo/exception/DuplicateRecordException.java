package com.carddemo.exception;

/**
 * Maps COBOL FILE STATUS {@code '22'} (duplicate key on an indexed write) to a Java
 * exception. A VSAM {@code WRITE} against an indexed dataset whose {@code RECORD KEY}
 * already exists returns FILE STATUS {@code '22'}; the equivalent condition in the Java
 * target is an attempt to insert a row whose primary/unique key already exists.
 *
 * <p>Modeled on the indexed writes in {@code app/cbl/CBTRN02C.cbl}
 * (the {@code TRANSACT-FILE} write in {@code 2900-WRITE-TRANSACTION-FILE} ~L564 and the
 * {@code TCATBAL-FILE} write in {@code 2700-A-CREATE-TCATBAL-REC} ~L510), source commit
 * {@code 27d6c6f}. FILE STATUS {@code '00'} (success) maps to no exception.</p>
 *
 * <p>Thrown by repository/service code when a unique-key/primary-key constraint would be
 * violated (the JPA equivalent of a duplicate-key insert failure). Extends
 * {@link CardDemoException} (same package), so it is unchecked and propagates out of a
 * Spring {@code @Transactional} boundary to trigger rollback. Translating raw FILE STATUS
 * codes into this type is the responsibility of {@code FileStatusMapper}, not of this class.</p>
 */
public class DuplicateRecordException extends CardDemoException {

    private static final long serialVersionUID = 1L;

    /** COBOL FILE STATUS code represented by this exception. */
    public static final String FILE_STATUS = "22";

    /**
     * Creates an exception with a descriptive detail message.
     *
     * @param message human-readable description of the duplicate-key condition
     */
    public DuplicateRecordException(String message) {
        super(message);
    }

    /**
     * Creates an exception with a detail message and an underlying cause (for example, a
     * wrapped JPA/JDBC constraint-violation exception).
     *
     * @param message human-readable description of the duplicate-key condition
     * @param cause   the underlying throwable that triggered this exception
     */
    public DuplicateRecordException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Builds an exception for a write that collided with an existing key.
     *
     * @param entityName logical entity/dataset name (e.g. {@code "Transaction"})
     * @param key        the key value that already exists
     * @return a new {@code DuplicateRecordException} with a descriptive message
     */
    public static DuplicateRecordException forKey(String entityName, Object key) {
        return new DuplicateRecordException(entityName + " already exists for key: " + String.valueOf(key));
    }

    /**
     * Returns the COBOL FILE STATUS code ({@code "22"}) represented by this exception.
     *
     * @return the two-character FILE STATUS code
     */
    public String getFileStatus() {
        return FILE_STATUS;
    }
}
