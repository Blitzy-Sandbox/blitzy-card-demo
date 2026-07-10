package com.carddemo.service;

import org.springframework.stereotype.Service;

/**
 * Centralized holder for the application's shared, cross-cutting user-facing
 * messages.
 *
 * <p>Migrated from the COBOL common-message copybook {@code CSMSG01Y}
 * ({@code CCDA-COMMON-MESSAGES}), which every CardDemo program included via
 * {@code COPY CSMSG01Y} to obtain the messages common across screens (for
 * example the sign-on program {@code COSGN00C} moves {@code CCDA-MSG-THANK-YOU}
 * on the exit key and {@code CCDA-MSG-INVALID-KEY} on an unmapped key). Screen
 * specific literals (such as the sign-on "Wrong Password. Try again ..." text)
 * intentionally remain private to their owning domain service, mirroring how
 * each COBOL program kept its own screen-specific messages in working storage.
 *
 * <p>The source fields are {@code PIC X(50)} and are right-padded with spaces to
 * fill the fixed-width 3270 display field. The trimmed visible text is stored
 * here (no trailing display padding) so REST/JSON responses carry the exact
 * visible characters, preserving external byte-parity with the legacy system.
 *
 * <p>Frozen COBOL reference SHA {@code 27d6c6f}. The class is stateless and
 * immutable; consumers may reference the public constants directly or obtain
 * them through the accessor methods for dependency-injection style use.
 */
@Service
public class MessageService {

    /**
     * Farewell message shown when a user exits the application.
     * <p>COBOL origin: {@code CCDA-MSG-THANK-YOU} (copybook {@code CSMSG01Y}).
     */
    public static final String THANK_YOU =
            "Thank you for using CardDemo application...";

    /**
     * Message shown when an unmapped or invalid function key is pressed.
     * <p>COBOL origin: {@code CCDA-MSG-INVALID-KEY} (copybook {@code CSMSG01Y}).
     */
    public static final String INVALID_KEY =
            "Invalid key pressed. Please see below...";

    /**
     * Creates the stateless message service. No initialization is required, as
     * the shared messages are exposed as immutable compile-time constants.
     */
    public MessageService() {
        // Intentionally empty: this service holds no mutable state.
    }

    /**
     * Returns the shared "thank you" farewell message.
     *
     * @return the trimmed visible text of {@code CCDA-MSG-THANK-YOU}
     */
    public String getThankYouMessage() {
        return THANK_YOU;
    }

    /**
     * Returns the shared "invalid key pressed" message.
     *
     * @return the trimmed visible text of {@code CCDA-MSG-INVALID-KEY}
     */
    public String getInvalidKeyMessage() {
        return INVALID_KEY;
    }
}
