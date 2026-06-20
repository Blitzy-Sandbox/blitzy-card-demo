package com.carddemo.service.shared;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/**
 * Reference-data lookups for NANPA telephone area codes, US state codes, and
 * state&nbsp;+&nbsp;ZIP-prefix combos, replacing the {@code CSLKPCDY} copybook's
 * {@code 88}-level {@code VALUE} condition-name lists (source commit {@code 27d6c6f};
 * COBOL not copied).
 *
 * <p>In the source these three editable fields each carried a large {@code 88}-level
 * {@code VALUE} list and were tested inline by the account-update program {@code COACTUPC}
 * (area-code edit &rarr; {@code VALID-GENERAL-PURP-CODE}; {@code 1270-EDIT-US-STATE-CD}
 * &rarr; {@code VALID-US-STATE-CODE}; {@code 1280-EDIT-US-STATE-ZIP-CD} &rarr;
 * {@code VALID-US-STATE-ZIP-CD2-COMBO}). Here the reference data is externalized to
 * classpath JSON resources and loaded once into immutable {@link Set}s for O(1) lookups.
 *
 * <p>The {@code 88}-level semantics are preserved exactly: a value that is not present in
 * the list makes the condition FALSE ("invalid"), which maps to {@link Set#contains(Object)}
 * returning {@code false}. A non-matching (including {@code null} or blank) value therefore
 * yields {@code false} and never raises an exception. Only a missing or malformed mandatory
 * resource at startup fails fast with an {@link IllegalStateException}.
 *
 * <p>Each backing JSON resource is a top-level array of strings (for example
 * {@code ["201","202","203"]}, {@code ["AL","AK","AZ"]}, {@code ["AK99","AL35","CA90"]}).
 * Values are normalized on load by trimming surrounding whitespace and upper-casing with
 * {@link Locale#ROOT}; lookup arguments are normalized the same way so comparisons are
 * case-insensitive and padding-insensitive.
 */
@Service
public class ValidationLookupService {

    /** Classpath location of the NANPA area-code reference array (3-character strings). */
    private static final String AREA_CODES = "validation/nanpa-area-codes.json";

    /** Classpath location of the US state-code reference array (2-character strings). */
    private static final String STATE_CODES = "validation/us-state-codes.json";

    /** Classpath location of the state&nbsp;+&nbsp;first-2-of-ZIP combo array (4-character strings). */
    private static final String STATE_ZIP = "validation/state-zip-prefixes.json";

    /** Immutable set of valid area codes, normalized to trimmed upper case. */
    private final Set<String> areaCodes;

    /** Immutable set of valid US state codes, normalized to trimmed upper case. */
    private final Set<String> stateCodes;

    /** Immutable set of valid {@code state + first-2-of-ZIP} combos, normalized to trimmed upper case. */
    private final Set<String> stateZipPrefixes;

    /**
     * Eagerly loads the three reference-data sets from the classpath, failing fast if any
     * mandatory resource is missing or malformed.
     *
     * @param objectMapper Spring Boot's auto-configured Jackson {@link ObjectMapper}
     * @throws IllegalStateException if a required JSON resource cannot be read or parsed
     */
    public ValidationLookupService(ObjectMapper objectMapper) {
        this.areaCodes = load(objectMapper, AREA_CODES);
        this.stateCodes = load(objectMapper, STATE_CODES);
        this.stateZipPrefixes = load(objectMapper, STATE_ZIP);
    }

    /**
     * Reads a top-level JSON array of strings from the given classpath resource into a
     * normalized {@link HashSet}. Each non-null element is trimmed and upper-cased with
     * {@link Locale#ROOT} (area codes are digits, so upper-casing is a no-op while trimming
     * removes any padding; state codes and ZIP combos are upper-cased).
     *
     * @param mapper the Jackson {@link ObjectMapper} used to deserialize the array
     * @param path   the classpath location of the JSON resource
     * @return an immutable-by-convention set of normalized values
     * @throws IllegalStateException if the resource cannot be read or parsed (fail fast)
     */
    private static Set<String> load(ObjectMapper mapper, String path) {
        ClassPathResource resource = new ClassPathResource(path);
        try (InputStream in = resource.getInputStream()) {
            List<String> values = mapper.readValue(in, new TypeReference<List<String>>() { });
            Set<String> set = new HashSet<>();
            for (String value : values) {
                if (value != null) {
                    set.add(value.trim().toUpperCase(Locale.ROOT));
                }
            }
            return set;
        } catch (IOException e) {
            throw new IllegalStateException("Unable to load validation lookup resource: " + path, e);
        }
    }

    /**
     * Validates a North American telephone area code against the general-purpose code list
     * (mirrors {@code COACTUPC}'s {@code VALID-GENERAL-PURP-CODE} test).
     *
     * @param areaCode the candidate 3-character area code; may be {@code null}
     * @return {@code true} if the normalized value is a recognized area code, else {@code false}
     */
    public boolean isValidAreaCode(String areaCode) {
        return contains(areaCodes, areaCode);
    }

    /**
     * Validates a US state code (mirrors {@code COACTUPC}'s {@code VALID-US-STATE-CODE} test).
     *
     * @param stateCode the candidate 2-character state code; may be {@code null}
     * @return {@code true} if the normalized value is a recognized state code, else {@code false}
     */
    public boolean isValidStateCode(String stateCode) {
        return contains(stateCodes, stateCode);
    }

    /**
     * Validates a {@code state + first-2-of-ZIP} combination, reproducing {@code COACTUPC}'s
     * {@code 1280-EDIT-US-STATE-ZIP-CD} which builds the 4-character key via
     * {@code STRING state, zip(1:2) DELIMITED BY SIZE} and tests
     * {@code VALID-US-STATE-ZIP-CD2-COMBO}.
     *
     * @param stateCode the 2-character state code; a {@code null} value yields {@code false}
     * @param zipCode   the ZIP code whose first two characters are used; {@code null} or fewer
     *                  than two characters yields {@code false}
     * @return {@code true} if the assembled {@code state + zip(1:2)} combo is recognized,
     *         else {@code false}
     */
    public boolean isValidStateZip(String stateCode, String zipCode) {
        if (stateCode == null || zipCode == null) {
            return false;
        }
        String st = stateCode.trim();
        String zip = zipCode.trim();
        if (st.isEmpty() || zip.length() < 2) {
            return false;
        }
        String combo = (st + zip.substring(0, 2)).toUpperCase(Locale.ROOT);
        return stateZipPrefixes.contains(combo);
    }

    /**
     * Validates an already-assembled 4-character {@code state + first-2-of-ZIP} combo directly
     * (convenience counterpart to {@link #isValidStateZip(String, String)}).
     *
     * @param combo the candidate 4-character combo; may be {@code null}
     * @return {@code true} if the normalized combo is recognized, else {@code false}
     */
    public boolean isValidStateZipCombo(String combo) {
        return contains(stateZipPrefixes, combo);
    }

    /**
     * Null-safe, normalized membership test mirroring the COBOL {@code 88}-level semantics: a
     * value that is {@code null} or, after normalization, blank or absent from the set yields
     * {@code false} and never throws.
     *
     * @param set   the reference set to test against
     * @param value the candidate value; may be {@code null}
     * @return {@code true} only if the normalized, non-blank value is present in the set
     */
    private static boolean contains(Set<String> set, String value) {
        if (value == null) {
            return false;
        }
        String key = value.trim().toUpperCase(Locale.ROOT);
        return !key.isEmpty() && set.contains(key);
    }
}
