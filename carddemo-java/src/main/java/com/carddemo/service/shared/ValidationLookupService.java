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
 * state&nbsp;+&nbsp;ZIP-prefix combinations.
 *
 * <p>This service replaces the {@code CSLKPCDY} copybook's inline {@code 88}-level
 * condition-name {@code VALUE} lists (source commit {@code 27d6c6f}); the sole COBOL
 * consumer was {@code COACTUPC}'s account-address validation. In COBOL each lookup was
 * a long {@code VALUE} list tested inline against an editable field. Here the reference
 * data is externalized to classpath JSON resources and loaded once at construction into
 * immutable in-memory {@link Set}s, giving O(1) membership checks.</p>
 *
 * <p>The JSON resources are produced separately under {@code src/main/resources/validation/}
 * and are read from the classpath. Each resource is a top-level JSON array of strings.
 * They are loaded eagerly so that a missing or malformed resource fails fast at application
 * start-up rather than on first use.</p>
 *
 * <p>Lookup methods mirror the COBOL {@code 88}-level semantics: a value that is not present
 * in the corresponding list yields {@code false} ("invalid"); they are null-safe and never
 * throw for a non-matching input.</p>
 */
@Service
public class ValidationLookupService {

    /** Classpath location of the NANPA area-code reference list (3-character numeric strings). */
    private static final String AREA_CODES = "validation/nanpa-area-codes.json";

    /** Classpath location of the US state-code reference list (2-character strings). */
    private static final String STATE_CODES = "validation/us-state-codes.json";

    /** Classpath location of the state + first-two-ZIP combination list (4-character strings). */
    private static final String STATE_ZIP = "validation/state-zip-prefixes.json";

    /** Immutable, normalized set of valid telephone area codes. */
    private final Set<String> areaCodes;

    /** Immutable, normalized set of valid US state codes. */
    private final Set<String> stateCodes;

    /** Immutable, normalized set of valid {@code state + first-two-ZIP} combinations. */
    private final Set<String> stateZipPrefixes;

    /**
     * Loads all reference lists eagerly from the classpath.
     *
     * @param objectMapper Spring Boot's auto-configured Jackson {@link ObjectMapper}
     * @throws IllegalStateException if any required reference resource is missing or malformed
     */
    public ValidationLookupService(ObjectMapper objectMapper) {
        this.areaCodes = load(objectMapper, AREA_CODES);
        this.stateCodes = load(objectMapper, STATE_CODES);
        this.stateZipPrefixes = load(objectMapper, STATE_ZIP);
    }

    /**
     * Reads a JSON array of strings from the given classpath resource into a normalized set.
     * Each value is trimmed and upper-cased ({@link Locale#ROOT}) so that comparisons are
     * stable and case-insensitive; {@code null} elements are skipped.
     *
     * @param mapper the Jackson mapper used to deserialize the resource
     * @param path   the classpath location of the JSON array resource
     * @return an in-memory set of normalized lookup keys
     * @throws IllegalStateException if the resource cannot be read or parsed
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
     * Tests whether the supplied telephone area code is a valid NANPA general-purpose code.
     *
     * @param areaCode the 3-digit area code to validate (may be {@code null})
     * @return {@code true} if the (trimmed, upper-cased) value is present in the list; otherwise {@code false}
     */
    public boolean isValidAreaCode(String areaCode) {
        return contains(areaCodes, areaCode);
    }

    /**
     * Tests whether the supplied US state code is valid.
     *
     * @param stateCode the 2-letter state code to validate (may be {@code null})
     * @return {@code true} if the (trimmed, upper-cased) value is present in the list; otherwise {@code false}
     */
    public boolean isValidStateCode(String stateCode) {
        return contains(stateCodes, stateCode);
    }

    /**
     * Tests whether the supplied state code combined with the first two digits of the ZIP code
     * is a valid combination, reproducing {@code COACTUPC}'s {@code STRING state, zip(1:2)} edit.
     *
     * @param stateCode the 2-letter state code (may be {@code null})
     * @param zipCode   the ZIP code; only its first two characters are used (may be {@code null})
     * @return {@code true} if the {@code state + first-two-ZIP} combination is valid; otherwise {@code false}
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
     * Tests a pre-built 4-character {@code state + first-two-ZIP} combination directly.
     *
     * @param combo the 4-character combination key (may be {@code null})
     * @return {@code true} if the (trimmed, upper-cased) value is present in the list; otherwise {@code false}
     */
    public boolean isValidStateZipCombo(String combo) {
        return contains(stateZipPrefixes, combo);
    }

    /**
     * Null-safe membership check that normalizes the value (trim + upper-case, {@link Locale#ROOT})
     * before testing the set. A {@code null}, empty, or absent value yields {@code false}.
     *
     * @param set   the reference set to test against
     * @param value the candidate value (may be {@code null})
     * @return {@code true} only if the normalized value is non-empty and present in the set
     */
    private static boolean contains(Set<String> set, String value) {
        if (value == null) {
            return false;
        }
        String key = value.trim().toUpperCase(Locale.ROOT);
        return !key.isEmpty() && set.contains(key);
    }
}
