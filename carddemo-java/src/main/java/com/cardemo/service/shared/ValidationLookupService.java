package com.cardemo.service.shared;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/**
 * Consolidated CardDemo lookup-validation service: the Java 25 realization of the high-fan-in COBOL
 * lookup-code repository copybook {@code app/cpy/CSLKPCDY.cpy}. That single copybook centralised
 * three reference tables for the whole estate &mdash; the North-America (NANPA) phone area codes,
 * the United-States state codes, and the state&harr;ZIP-prefix combinations &mdash; each declared
 * as a level&nbsp;{@code 88} {@code VALUES} set against an editable field (AAP &sect;0.4.1 /
 * tech-spec L639, and the resources table at tech-spec L691&ndash;L693).
 *
 * <p>Wherever a legacy program performed {@code COPY CSLKPCDY} followed by an
 * {@code IF VALID-...}&nbsp;{@code 88}-level membership test, the migrated Java collaborator instead
 * {@code @Autowired}s this component and calls the matching boolean method (AAP &sect;0.4.2). The
 * canonical consumer is the account/customer update program {@code app/cbl/COACTUPC.cbl}, whose edit
 * paragraphs ({@code EDIT-AREA-CODE}, {@code 1270-EDIT-US-STATE-CD} and
 * {@code 1280-EDIT-US-STATE-ZIP-CD}) define the exact semantics reproduced here.</p>
 *
 * <h2>Migration provenance</h2>
 * <p>Behaviour is translated from the frozen AWS CardDemo COBOL estate at commit SHA
 * {@code 27d6c6f}. The COBOL source is <em>never copied</em> into this repository; only its
 * observable behaviour &mdash; the same valid/invalid membership outcomes &mdash; is reproduced
 * (AAP &sect;0.4.1 and the &sect;0.7.2 preservation requirements).</p>
 *
 * <h2>{@code 88}-level tables &rarr; {@code classpath:validation/*.json} substitution</h2>
 * <p>COBOL substitution (AAP &sect;0.7.1): the three {@code 88}-level {@code VALUES} sets embedded in
 * {@code CSLKPCDY} are <em>externalised</em> to JSON resources under
 * {@code src/main/resources/validation/} (authored by the resources migration step) and loaded here
 * once at construction. The JSON is the externalised source of truth &mdash; the exact set of valid
 * codes and the exact state&harr;ZIP-prefix pairings are preserved verbatim and are neither
 * hard-coded nor trimmed in this class:</p>
 * <ul>
 *   <li>{@code validation/nanpa-area-codes.json} &mdash; JSON array of 3-digit area-code strings,
 *       loaded into {@link #areaCodes}. <strong>Parity decision:</strong> this file carries the
 *       <em>general-purpose</em> code set ({@code VALID-GENERAL-PURP-CODE}, ~410 codes), because
 *       {@code COACTUPC}'s {@code EDIT-AREA-CODE} paragraph tests {@code IF VALID-GENERAL-PURP-CODE}
 *       &mdash; <em>not</em> the broader {@code VALID-PHONE-AREA-CODE} (~490) set, which is dead for
 *       this validation path. See {@link #isValidAreaCode(String)}.</li>
 *   <li>{@code validation/us-state-codes.json} &mdash; JSON array of the 56 two-letter state codes
 *       ({@code VALID-US-STATE-CODE}, incl. DC and the five territories), loaded into
 *       {@link #stateCodes}.</li>
 *   <li>{@code validation/state-zip-prefixes.json} &mdash; JSON object mapping each state code to its
 *       list of valid first-two-of-ZIP prefixes (the decomposed
 *       {@code VALID-US-STATE-ZIP-CD2-COMBO} 4-byte combinations), loaded into
 *       {@link #stateZipPrefixes}.</li>
 * </ul>
 *
 * <h2>Flag-not-throw fidelity</h2>
 * <p>Faithful to the COBOL idiom {@code IF VALID-... CONTINUE ELSE SET INPUT-ERROR TO TRUE}, every
 * public method <em>returns a boolean</em> and never throws for an invalid or absent lookup value.
 * Callers decide whether to translate a {@code false} outcome into a {@code ValidationException}
 * (HTTP&nbsp;400). Consequently this class imports no {@code com.cardemo.exception} type and no
 * sibling service, repository, entity, controller or batch type; its only collaborators are the JDK,
 * Jackson and the Spring {@code @Service}/{@code ClassPathResource} infrastructure (its
 * depends-on-files set is the three JSON resources only). The single exception to the never-throw
 * rule is a <em>fatal startup misconfiguration</em>: if a required JSON resource is missing, empty
 * or unparseable the constructor fails fast with {@link IllegalStateException}, which is a
 * deployment error rather than a business-validation outcome.</p>
 *
 * <h2>Thread-safety</h2>
 * <p>Effectively immutable after construction and therefore singleton-safe for unrestricted
 * concurrent reads: the three lookup collections are {@code final}, populated exactly once in the
 * constructor, and wrapped as unmodifiable (the nested ZIP-prefix sets are unmodifiable too). No
 * method mutates any shared state, and there is no per-call mutable field.</p>
 *
 * @see java.util.Collections#unmodifiableSet(Set)
 * @see org.springframework.core.io.ClassPathResource
 */
@Service
public class ValidationLookupService {

    /**
     * Classpath location of the externalised NANPA <em>general-purpose</em> area-code set
     * (the {@code CSLKPCDY} {@code VALID-GENERAL-PURP-CODE} {@code 88}-level table).
     */
    private static final String AREA_CODES_RESOURCE = "validation/nanpa-area-codes.json";

    /**
     * Classpath location of the externalised US state-code set
     * (the {@code CSLKPCDY} {@code VALID-US-STATE-CODE} {@code 88}-level table).
     */
    private static final String STATE_CODES_RESOURCE = "validation/us-state-codes.json";

    /**
     * Classpath location of the externalised state&harr;ZIP-prefix map
     * (the decomposed {@code CSLKPCDY} {@code VALID-US-STATE-ZIP-CD2-COMBO} {@code 88}-level table).
     */
    private static final String STATE_ZIP_RESOURCE = "validation/state-zip-prefixes.json";

    /**
     * Immutable set of valid 3-digit NANPA general-purpose area codes
     * (COBOL substitution for {@code VALID-GENERAL-PURP-CODE}).
     */
    private final Set<String> areaCodes;

    /**
     * Immutable set of valid 2-letter US state codes
     * (COBOL substitution for {@code VALID-US-STATE-CODE}).
     */
    private final Set<String> stateCodes;

    /**
     * Immutable map of state code &rarr; immutable set of valid first-two-of-ZIP prefixes
     * (COBOL substitution for {@code VALID-US-STATE-ZIP-CD2-COMBO}, with the 4-byte
     * {@code state + zip(1:2)} combination decomposed into a keyed prefix lookup).
     */
    private final Map<String, Set<String>> stateZipPrefixes;

    /**
     * Loads the three externalised lookup tables from the classpath exactly once.
     *
     * <p>COBOL substitution: this constructor replaces the compile-time {@code COPY CSLKPCDY}
     * inclusion of the {@code 88}-level {@code VALUES} tables with a one-time deserialization of the
     * equivalent {@code classpath:validation/*.json} resources via Jackson. The resulting
     * collections are wrapped unmodifiable so the bean is immutable after construction.</p>
     *
     * @throws IllegalStateException if any required validation resource is missing, empty or
     *                               unparseable &mdash; a fatal deployment misconfiguration, not a
     *                               business-validation outcome
     */
    public ValidationLookupService() {
        final ObjectMapper mapper = new ObjectMapper();
        this.areaCodes = loadStringSet(mapper, AREA_CODES_RESOURCE);
        this.stateCodes = loadStringSet(mapper, STATE_CODES_RESOURCE);
        this.stateZipPrefixes = loadStateZipPrefixes(mapper, STATE_ZIP_RESOURCE);
    }

    /**
     * Deserializes a JSON array of strings into an unmodifiable {@link Set} for O(1) membership.
     *
     * @param mapper       the Jackson {@link ObjectMapper} to use
     * @param resourcePath the classpath location of the JSON array resource
     * @return an unmodifiable, de-duplicated set of the array values
     * @throws IllegalStateException if the resource is missing, empty or unparseable
     */
    private static Set<String> loadStringSet(final ObjectMapper mapper, final String resourcePath) {
        try (InputStream in = openResource(resourcePath)) {
            final List<String> values = mapper.readValue(in, new TypeReference<List<String>>() { });
            if (values == null || values.isEmpty()) {
                // Guard against a truncated/empty table that would silently weaken validation.
                throw new IllegalStateException(
                        "Validation lookup resource is empty (truncated table?): " + resourcePath);
            }
            return Collections.unmodifiableSet(new HashSet<>(values));
        } catch (IOException ex) {
            throw new IllegalStateException(
                    "Unable to load validation lookup resource: " + resourcePath, ex);
        }
    }

    /**
     * Deserializes a JSON object of {@code state -> [prefix, ...]} into an unmodifiable map whose
     * nested sets are themselves unmodifiable.
     *
     * @param mapper       the Jackson {@link ObjectMapper} to use
     * @param resourcePath the classpath location of the JSON object resource
     * @return an unmodifiable map of state code to its unmodifiable set of ZIP prefixes
     * @throws IllegalStateException if the resource is missing, empty or unparseable
     */
    private static Map<String, Set<String>> loadStateZipPrefixes(final ObjectMapper mapper,
            final String resourcePath) {
        try (InputStream in = openResource(resourcePath)) {
            final Map<String, List<String>> raw =
                    mapper.readValue(in, new TypeReference<Map<String, List<String>>>() { });
            if (raw == null || raw.isEmpty()) {
                // Guard against a truncated/empty table that would silently weaken validation.
                throw new IllegalStateException(
                        "Validation lookup resource is empty (truncated table?): " + resourcePath);
            }
            final Map<String, Set<String>> result = new HashMap<>();
            for (final Map.Entry<String, List<String>> entry : raw.entrySet()) {
                result.put(entry.getKey(), Collections.unmodifiableSet(new HashSet<>(entry.getValue())));
            }
            return Collections.unmodifiableMap(result);
        } catch (IOException ex) {
            throw new IllegalStateException(
                    "Unable to load validation lookup resource: " + resourcePath, ex);
        }
    }

    /**
     * Opens a required classpath resource, failing fast if it is absent.
     *
     * @param resourcePath the classpath location to open
     * @return an open {@link InputStream} the caller must close
     * @throws IllegalStateException if the resource does not exist on the classpath
     * @throws IOException           if the resource cannot be opened
     */
    private static InputStream openResource(final String resourcePath) throws IOException {
        final ClassPathResource resource = new ClassPathResource(resourcePath);
        if (!resource.exists()) {
            throw new IllegalStateException(
                    "Required validation lookup resource not found on classpath: " + resourcePath);
        }
        return resource.getInputStream();
    }

    /**
     * Validates a US phone area code against the NANPA general-purpose set.
     *
     * <p>COBOL parity ({@code COACTUPC} {@code EDIT-AREA-CODE}, ~L2298): the legacy edit moved the
     * trimmed area code into {@code WS-US-PHONE-AREA-CODE-TO-EDIT} and tested
     * {@code IF VALID-GENERAL-PURP-CODE} &mdash; i.e. membership in the general-purpose set
     * ({@link #areaCodes}), not the broader {@code VALID-PHONE-AREA-CODE} set.</p>
     *
     * <p><strong>Optional-field semantics:</strong> the telephone area code is an optional input;
     * the COBOL edit is skipped when the field is blank, so a {@code null}, empty or all-whitespace
     * value is treated as <em>valid</em> (returns {@code true}). Whether the field is independently
     * "required" is the caller's concern, exactly as COBOL separated the "supplied" check from the
     * "valid value" check. A non-blank value is trimmed (mirroring the COBOL {@code FUNCTION TRIM})
     * and then checked for membership.</p>
     *
     * @param areaCode the area code to validate; may be {@code null}, blank, or a 3-digit string
     * @return {@code true} if the value is blank (optional) or is a member of the general-purpose
     *         area-code set; {@code false} otherwise
     */
    public boolean isValidAreaCode(final String areaCode) {
        // COBOL substitution: optional phone area code => blank short-circuits to valid.
        if (areaCode == null || areaCode.isBlank()) {
            return true;
        }
        return areaCodes.contains(areaCode.trim());
    }

    /**
     * Validates a US state code against the {@code VALID-US-STATE-CODE} set.
     *
     * <p>COBOL parity ({@code COACTUPC} {@code 1270-EDIT-US-STATE-CD}, ~L2495): the legacy edit moved
     * the state code into {@code US-STATE-CODE-TO-EDIT} and tested {@code IF VALID-US-STATE-CODE}.
     * This is a <em>pure membership</em> check ({@link #stateCodes}); the COBOL performed no trim, so
     * none is applied here. A {@code null} or blank value cannot match any of the 56 codes and is
     * therefore not a valid value ({@code false}); requiredness of the field remains the caller's
     * concern, mirroring how COBOL separated the "supplied" check from the "valid value" check.</p>
     *
     * @param stateCode the 2-letter state code to validate; may be {@code null} or blank
     * @return {@code true} iff {@code stateCode} is a member of the valid state-code set
     */
    public boolean isValidStateCode(final String stateCode) {
        // COBOL substitution: pure 88-level membership; blank is simply not a member.
        if (stateCode == null || stateCode.isBlank()) {
            return false;
        }
        return stateCodes.contains(stateCode);
    }

    /**
     * Validates a state&harr;ZIP combination against the {@code VALID-US-STATE-ZIP-CD2-COMBO} set.
     *
     * <p>COBOL parity ({@code COACTUPC} {@code 1280-EDIT-US-STATE-ZIP-CD}, ~L2537): the legacy edit
     * built a 4-byte key with {@code STRING US-STATE-CODE, ZIP(1:2) DELIMITED BY SIZE INTO
     * US-STATE-AND-FIRST-ZIP2} and tested {@code IF VALID-US-STATE-ZIP-CD2-COMBO}. The exact key
     * construction is preserved &mdash; state first, then the first two characters of the ZIP &mdash;
     * but realised against the decomposed {@link #stateZipPrefixes} map: the state selects the set of
     * valid prefixes and the first-two-of-ZIP is the membership probe.</p>
     *
     * <p>If the state is {@code null}/blank or the ZIP is {@code null} or shorter than two
     * characters, the 4-byte combination cannot be formed, so the method returns {@code false},
     * matching the COBOL outcome (no combination &rArr; not valid).</p>
     *
     * @param stateCode the 2-letter state code component of the key; may be {@code null} or blank
     * @param zipCode   the ZIP code whose first two characters complete the key; may be {@code null}
     *                  or too short
     * @return {@code true} iff the {@code state + first-two-of-ZIP} combination is valid
     */
    public boolean isValidStateZip(final String stateCode, final String zipCode) {
        // COBOL substitution: STRING state, zip(1:2) => key; defensive false when key cannot form.
        if (stateCode == null || stateCode.isBlank() || zipCode == null || zipCode.length() < 2) {
            return false;
        }
        final String prefix = zipCode.substring(0, 2);
        final Set<String> prefixes = stateZipPrefixes.get(stateCode);
        return prefixes != null && prefixes.contains(prefix);
    }
}
