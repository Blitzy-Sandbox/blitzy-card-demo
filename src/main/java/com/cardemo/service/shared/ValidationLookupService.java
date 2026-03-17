package com.cardemo.service.shared;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Validation lookup service that replaces the COBOL CSLKPCDY.cpy 88-level condition tables
 * with immutable {@link Set}{@code <String>} lookups loaded from JSON resource files at startup.
 *
 * <p>This service provides O(1) validation lookups for:
 * <ul>
 *   <li><strong>NANPA area codes</strong> — 490 valid 3-digit North American phone area codes
 *       (replaces COBOL {@code VALID-PHONE-AREA-CODE} 88-level condition)</li>
 *   <li><strong>US state/territory codes</strong> — 56 valid 2-letter postal abbreviations
 *       (replaces COBOL {@code VALID-US-STATE-CODE} 88-level condition)</li>
 *   <li><strong>State+ZIP prefix combinations</strong> — 240 valid 4-character geographic
 *       consistency keys (replaces COBOL {@code VALID-US-STATE-ZIP-CD2-COMBO} 88-level condition)</li>
 * </ul>
 *
 * <p>All data is loaded once at application startup via {@code @PostConstruct} and is immutable
 * thereafter, ensuring thread safety without synchronization. The JSON resource files are
 * externalized from the original COBOL inline VALUE tables for maintainability while preserving
 * 100% behavioral parity with the COBOL validation logic.
 *
 * <p>Consumed by: AccountUpdateService, CardUpdateService, TransactionAddService, and batch
 * processors for customer/account data validation.
 *
 * @see <a href="https://nationalnanpa.com/nanp1/npa_report.csv">NANPA Area Code Source</a>
 */
@Service
public class ValidationLookupService {

    private static final Logger log = LoggerFactory.getLogger(ValidationLookupService.class);

    /** Resource path for NANPA area code validation data. */
    private static final String AREA_CODES_RESOURCE = "validation/nanpa-area-codes.json";

    /** Resource path for US state/territory code validation data. */
    private static final String STATE_CODES_RESOURCE = "validation/us-state-codes.json";

    /** Resource path for state+ZIP prefix combination validation data. */
    private static final String STATE_ZIP_RESOURCE = "validation/state-zip-prefixes.json";

    /** JSON array field name for area codes in nanpa-area-codes.json. */
    private static final String AREA_CODES_FIELD = "validAreaCodes";

    /** JSON array field name for state codes in us-state-codes.json. */
    private static final String STATE_CODES_FIELD = "validStateCodes";

    /** JSON array field name for state-ZIP prefixes in state-zip-prefixes.json. */
    private static final String STATE_ZIP_FIELD = "validStateZipPrefixes";

    /**
     * Immutable set of valid NANPA phone area codes (490 entries).
     * Each entry is a 3-character numeric string (e.g., "201", "212", "800").
     * Includes both general-purpose codes and easily-recognizable codes.
     */
    private Set<String> validAreaCodes = Collections.emptySet();

    /**
     * Immutable set of valid US state/territory postal abbreviations (56 entries).
     * Includes 50 US states + DC + 5 territories (AS, GU, MP, PR, VI).
     * Each entry is a 2-character uppercase string.
     */
    private Set<String> validStateCodes = Collections.emptySet();

    /**
     * Immutable set of valid state+ZIP prefix combinations (240 entries).
     * Each entry is a 4-character uppercase string: 2-letter state code + first 2 digits of ZIP.
     * Used for geographic consistency validation (e.g., "CA90" for California ZIP 90xxx).
     */
    private Set<String> validStateZipPrefixes = Collections.emptySet();

    /**
     * Initializes the validation lookup service by loading all three JSON resource files
     * from the classpath. This method is invoked automatically by the Spring container
     * after dependency injection is complete.
     *
     * <p>If any resource file fails to load or parse, an {@link IllegalStateException}
     * is thrown, preventing the application from starting in an invalid state. Validation
     * data is essential for all customer/account operations — the application cannot
     * function without it.
     *
     * <p>Replaces COBOL: {@code COPY CSLKPCDY} which embedded all validation data inline
     * within the WORKING-STORAGE SECTION as 88-level condition VALUE tables.
     *
     * @throws IllegalStateException if any validation data file cannot be loaded
     */
    @PostConstruct
    public void init() {
        log.info("Initializing ValidationLookupService — loading validation data from classpath resources");

        validAreaCodes = loadJsonArray(AREA_CODES_RESOURCE, AREA_CODES_FIELD);
        log.info("Loaded {} NANPA area codes from {}", validAreaCodes.size(), AREA_CODES_RESOURCE);

        validStateCodes = loadJsonArray(STATE_CODES_RESOURCE, STATE_CODES_FIELD);
        log.info("Loaded {} US state codes from {}", validStateCodes.size(), STATE_CODES_RESOURCE);

        validStateZipPrefixes = loadJsonArray(STATE_ZIP_RESOURCE, STATE_ZIP_FIELD);
        log.info("Loaded {} state-ZIP prefix combinations from {}",
                validStateZipPrefixes.size(), STATE_ZIP_RESOURCE);

        log.info("ValidationLookupService initialization complete — all validation data loaded successfully");
    }

    /**
     * Validates a phone area code against the NANPA area code table.
     *
     * <p>Equivalent to COBOL: {@code IF VALID-PHONE-AREA-CODE} — evaluates the 88-level
     * condition on {@code WS-US-PHONE-AREA-CODE-TO-EDIT} (PIC XXX). The COBOL condition
     * checks membership in the union of general-purpose codes (410) and easily-recognizable
     * codes (80) for a total of 490 valid values.
     *
     * @param areaCode 3-character area code string (e.g., "201", "800")
     * @return {@code true} if the area code is valid per the NANPA lookup table;
     *         {@code false} if null, blank, wrong length, or not in the table
     */
    public boolean isValidAreaCode(String areaCode) {
        if (areaCode == null || areaCode.isBlank()) {
            return false;
        }
        String trimmed = areaCode.trim();
        if (trimmed.length() != 3) {
            return false;
        }
        return validAreaCodes.contains(trimmed);
    }

    /**
     * Validates a US state/territory postal abbreviation.
     *
     * <p>Equivalent to COBOL: {@code IF VALID-US-STATE-CODE} — evaluates the 88-level
     * condition on {@code US-STATE-CODE-TO-EDIT} (PIC X(2)). The COBOL condition checks
     * membership against 56 valid state/territory codes.
     *
     * @param stateCode 2-character state code (case-insensitive; will be uppercased)
     * @return {@code true} if the state code is valid; {@code false} if null, blank,
     *         wrong length, or not in the table
     */
    public boolean isValidStateCode(String stateCode) {
        if (stateCode == null || stateCode.isBlank()) {
            return false;
        }
        String trimmed = stateCode.trim().toUpperCase();
        if (trimmed.length() != 2) {
            return false;
        }
        return validStateCodes.contains(trimmed);
    }

    /**
     * Validates a state+ZIP prefix combination for geographic consistency.
     *
     * <p>Equivalent to COBOL:
     * <pre>
     *   MOVE stateCode TO US-STATE-AND-FIRST-ZIP2(1:2)
     *   MOVE zipCode(1:2) TO US-STATE-AND-FIRST-ZIP2(3:2)
     *   IF VALID-US-STATE-ZIP-CD2-COMBO
     * </pre>
     * The COBOL code concatenates the 2-letter state code with the first 2 digits of the
     * ZIP code into a 4-character key ({@code US-STATE-AND-FIRST-ZIP2 PIC X(4)}) and checks
     * the 88-level condition for membership in the 240-entry lookup table.
     *
     * @param stateCode 2-character state code (case-insensitive)
     * @param zipCode full ZIP code string (only the first 2 digits are used for lookup)
     * @return {@code true} if the state/ZIP prefix combination is geographically valid;
     *         {@code false} if either parameter is null/blank, or the combination is not
     *         in the lookup table
     */
    public boolean isValidStateZipPrefix(String stateCode, String zipCode) {
        if (stateCode == null || stateCode.isBlank()) {
            return false;
        }
        if (zipCode == null || zipCode.isBlank()) {
            return false;
        }
        String trimmedState = stateCode.trim().toUpperCase();
        String trimmedZip = zipCode.trim();
        if (trimmedState.length() != 2) {
            return false;
        }
        if (trimmedZip.length() < 2) {
            return false;
        }
        // Extract first 2 digits of ZIP code — mirrors COBOL US-STATE-AND-FIRST-ZIP2 construction
        String zipPrefix = trimmedZip.substring(0, 2);
        String combinedKey = trimmedState + zipPrefix;
        return validStateZipPrefixes.contains(combinedKey);
    }

    /**
     * Validates a pre-combined state+ZIP prefix key.
     *
     * <p>This overload accepts a single 4-character key that has already been constructed
     * by concatenating the state code and first 2 ZIP digits. Useful when the caller has
     * already performed the concatenation.
     *
     * @param combinedKey 4-character combined key (stateCode + first 2 ZIP digits),
     *                    case-insensitive
     * @return {@code true} if the combination is valid; {@code false} if null, blank,
     *         wrong length, or not in the table
     */
    public boolean isValidStateZipPrefix(String combinedKey) {
        if (combinedKey == null || combinedKey.isBlank()) {
            return false;
        }
        String trimmed = combinedKey.trim().toUpperCase();
        if (trimmed.length() != 4) {
            return false;
        }
        return validStateZipPrefixes.contains(trimmed);
    }

    /**
     * Returns an unmodifiable view of all valid NANPA area codes.
     *
     * <p>Intended for diagnostic and reporting purposes. The returned set is immutable —
     * any attempt to modify it will throw {@link UnsupportedOperationException}.
     *
     * @return unmodifiable {@link Set} of valid 3-digit area code strings
     */
    public Set<String> getValidAreaCodes() {
        return validAreaCodes;
    }

    /**
     * Returns an unmodifiable view of all valid US state/territory codes.
     *
     * <p>Intended for diagnostic and reporting purposes. The returned set is immutable —
     * any attempt to modify it will throw {@link UnsupportedOperationException}.
     *
     * @return unmodifiable {@link Set} of valid 2-letter state/territory code strings
     */
    public Set<String> getValidStateCodes() {
        return validStateCodes;
    }

    /**
     * Returns an unmodifiable view of all valid state+ZIP prefix combinations.
     *
     * <p>Intended for diagnostic and reporting purposes. The returned set is immutable —
     * any attempt to modify it will throw {@link UnsupportedOperationException}.
     *
     * @return unmodifiable {@link Set} of valid 4-character state+ZIP prefix strings
     */
    public Set<String> getValidStateZipPrefixes() {
        return validStateZipPrefixes;
    }

    // ========================================================================
    // Private helper methods
    // ========================================================================

    /**
     * Loads a JSON array from a classpath resource file and returns its values as an
     * immutable {@link Set} of strings.
     *
     * <p>The JSON file is expected to contain a top-level object with a named array field.
     * Each element in the array is extracted as a text value and added to the resulting set.
     *
     * <p>Example JSON structure:
     * <pre>
     * {
     *   "source": "CSLKPCDY.cpy",
     *   "validAreaCodes": ["201", "202", "203", ...]
     * }
     * </pre>
     *
     * @param resourcePath classpath-relative path to the JSON resource file
     *                     (e.g., "validation/nanpa-area-codes.json")
     * @param arrayFieldName name of the JSON array field to extract
     *                       (e.g., "validAreaCodes")
     * @return immutable {@link Set} of string values from the JSON array
     * @throws IllegalStateException if the resource file cannot be found, read, or parsed,
     *         or if the expected array field is missing from the JSON
     */
    private Set<String> loadJsonArray(String resourcePath, String arrayFieldName) {
        ObjectMapper mapper = new ObjectMapper();
        ClassPathResource resource = new ClassPathResource(resourcePath);

        try (InputStream inputStream = resource.getInputStream()) {
            JsonNode rootNode = mapper.readTree(inputStream);
            JsonNode arrayNode = rootNode.get(arrayFieldName);

            if (arrayNode == null || !arrayNode.isArray()) {
                throw new IllegalStateException(
                        "Validation data file '" + resourcePath
                                + "' does not contain expected array field '" + arrayFieldName + "'");
            }

            HashSet<String> result = new HashSet<>();
            var iterator = arrayNode.iterator();
            while (iterator.hasNext()) {
                JsonNode element = iterator.next();
                String value = element.asText();
                if (value != null && !value.isBlank()) {
                    result.add(value);
                }
            }

            if (result.isEmpty()) {
                throw new IllegalStateException(
                        "Validation data file '" + resourcePath
                                + "' contains an empty array for field '" + arrayFieldName + "'");
            }

            return Collections.unmodifiableSet(result);

        } catch (IOException e) {
            log.error("Failed to load validation data from classpath resource '{}': {}",
                    resourcePath, e.getMessage());
            throw new IllegalStateException(
                    "Cannot load validation data from '" + resourcePath
                            + "' — application cannot start without validation data", e);
        }
    }
}
