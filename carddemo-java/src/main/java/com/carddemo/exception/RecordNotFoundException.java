package com.carddemo.exception;

/**
 * Maps COBOL FILE STATUS {@code '23'} (record / key not found on an indexed read -
 * the {@code INVALID KEY} path) to an idiomatic Java exception. Modeled on the keyed
 * reads in {@code app/cbl/CBTRN02C.cbl} (XREF read ~L383, ACCOUNT read ~L395), source
 * commit {@code 27d6c6f}. FILE STATUS {@code '00'} (success) maps to no exception.
 *
 * <p>Thrown by repository/service code when a keyed lookup finds no matching row - the
 * JPA equivalent of an empty {@code findById}. It belongs to the {@link CardDemoException}
 * hierarchy that replaces COBOL {@code FILE STATUS} checking and implicit paragraph
 * fall-through with explicit, exception-driven error flow.</p>
 *
 * <p>The mapping rationale is recorded in {@code DECISION_LOG.md}; the enum-to-exception
 * translation itself lives in {@code service/shared/FileStatusMapper}, so this type stays
 * standalone (no Spring/web annotations, no sibling-package imports).</p>
 */
public class RecordNotFoundException extends CardDemoException {

    private static final long serialVersionUID = 1L;

    /**
     * COBOL FILE STATUS code represented by this exception. Kept as a 2-character
     * {@code String} to preserve the exact COBOL 2-byte status representation,
     * including the leading zero in other status codes.
     */
    public static final String FILE_STATUS = "23";

    /**
     * Creates an exception with a human-readable detail message.
     *
     * @param message the detail message describing the failed keyed lookup
     */
    public RecordNotFoundException(String message) {
        super(message);
    }

    /**
     * Creates an exception with a detail message and an underlying cause.
     *
     * @param message the detail message describing the failed keyed lookup
     * @param cause   the underlying cause (for example, a data-access exception)
     */
    public RecordNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Builds an exception for a failed keyed lookup. Uses {@link String#valueOf(Object)}
     * so that {@code null}, {@code Long}, {@code String}, and composite-key objects
     * passed by repositories are all rendered null-safely.
     *
     * @param entityName logical entity/dataset name (for example, {@code "Account"})
     * @param key        the key value that was not found
     * @return a new {@code RecordNotFoundException} with a descriptive message
     */
    public static RecordNotFoundException forKey(String entityName, Object key) {
        return new RecordNotFoundException(entityName + " not found for key: " + String.valueOf(key));
    }

    /**
     * Returns the COBOL FILE STATUS code ({@code "23"}) that this exception represents.
     *
     * @return the 2-character FILE STATUS code
     */
    public String getFileStatus() {
        return FILE_STATUS;
    }
}
