package com.carddemo.exception;

/**
 * Base unchecked exception for every CardDemo domain/business error.
 *
 * <p>Root of the custom exception hierarchy that replaces the COBOL {@code FILE STATUS}
 * checking and implicit paragraph fall-through (see {@code app/cbl/CBTRN02C.cbl},
 * source commit {@code 27d6c6f}) with explicit, exception-driven error flow. All
 * domain exceptions in the service, batch, and controller layers extend this type.</p>
 *
 * <p>Extends {@link RuntimeException} (unchecked) so that any subclass propagating out
 * of a Spring {@code @Transactional} boundary triggers automatic rollback, mirroring the
 * sole {@code SYNCPOINT ROLLBACK} of the original COBOL system ({@code COACTUPC}).</p>
 */
public class CardDemoException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CardDemoException(String message) {
        super(message);
    }

    public CardDemoException(String message, Throwable cause) {
        super(message, cause);
    }
}
