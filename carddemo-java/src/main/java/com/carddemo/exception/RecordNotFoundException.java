package com.carddemo.exception;

/**
 * Maps COBOL FILE STATUS {@code '23'} (record / key not found on an indexed read &mdash;
 * the {@code INVALID KEY} path) to an idiomatic Java exception. Modeled on the keyed
 * reads in {@code app/cbl/CBTRN02C.cbl} (XREF read ~L383, ACCOUNT read ~L395), source
 * commit {@code 27d6c6f}. FILE STATUS {@code '00'} (success) maps to no exception.
 *
 * <p>Thrown by repository and service code when a keyed lookup against a former VSAM
 * KSDS dataset (now a PostgreSQL table) finds no matching row &mdash; the JPA equivalent
 * of an empty {@code findById}. As a subclass of {@link CardDemoException} it is unchecked,
 * so propagating out of a Spring {@code @Transactional} boundary triggers rollback,
 * mirroring the original CICS transaction semantics.</p>
 *
 * <p>The enum&harr;exception mapping for the full set of FILE STATUS codes is owned by the
 * shared {@code FileStatusMapper}; this type deliberately stays standalone and free of any
 * Spring or web annotations.</p>
 */
public class RecordNotFoundException extends CardDemoException {

    private static final long serialVersionUID = 1L;

    /** COBOL FILE STATUS code represented by this exception. */
    public static final String FILE_STATUS = "23";

    /**
     * Creates an exception with the supplied detail message.
     *
     * @param message human-readable description of the missing record
     */
    public RecordNotFoundException(String message) {
        super(message);
    }

    /**
     * Creates an exception with the supplied detail message and underlying cause.
     *
     * @param message human-readable description of the missing record
     * @param cause   the underlying cause (for example, a data-access failure)
     */
    public RecordNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Builds an exception for a failed keyed lookup.
     *
     * @param entityName logical entity/dataset name (e.g. {@code "Account"})
     * @param key        the key value that was not found
     * @return a new {@code RecordNotFoundException} with a descriptive message
     */
    public static RecordNotFoundException forKey(String entityName, Object key) {
        return new RecordNotFoundException(entityName + " not found for key: " + String.valueOf(key));
    }

    /**
     * Returns the COBOL FILE STATUS code this exception represents.
     *
     * @return the two-character FILE STATUS code {@code "23"}
     */
    public String getFileStatus() {
        return FILE_STATUS;
    }
}
