/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.carddemo.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Lookup-validation service translating the COBOL lookup repository
 * {@code app/cpy/CSLKPCDY.cpy} (@ {@code 27d6c6f}) into Java.
 *
 * <p>The legacy copybook declares three {@code 88}-level condition-name value
 * lists used by the account-update field edits
 * ({@code 1260-EDIT-US-PHONE-NUM}, {@code 1270-EDIT-US-STATE-CD},
 * {@code 1280-EDIT-US-STATE-ZIP-CD}):</p>
 * <ul>
 *   <li>{@code VALID-GENERAL-PURP-CODE} &mdash; the general-purpose subset of
 *       North-American Numbering Plan (NANPA) three-digit area codes accepted
 *       by phone validation. The legacy account-update edit
 *       ({@code COACTUPC} paragraph {@code 1265-EDIT-US-PHONE-NUM-A}) tests
 *       {@code IF VALID-GENERAL-PURP-CODE}, so easy-recognizable / special
 *       codes (for example {@code 200}, {@code 211}, {@code 911}) are rejected
 *       even though they are valid NANPA assignments.</li>
 *   <li>{@code VALID-US-STATE-CODE} &mdash; two-character US state codes.</li>
 *   <li>{@code VALID-US-STATE-ZIP-CD2-COMBO} &mdash; valid combinations of a
 *       state code with the first two digits of its ZIP code.</li>
 * </ul>
 *
 * <p>The value lists are not embedded in this class. They are sourced from the
 * classpath JSON resources under {@code validation/} so the data is owned in a
 * single place. The NANPA resource exposes the copybook lists as named arrays;
 * phone validation reads only the {@code validGeneralPurposeCodes} array so the
 * easy-recognizable codes (preserved alongside for copybook fidelity) never
 * enter the phone-validation lookup. The state-code and state/ZIP resources are
 * each a single named array (a bare array or an object whose field values are
 * arrays is also accepted), with the state/ZIP combinations expressed as
 * {@code "SSNN"} entries.</p>
 *
 * <p>All lookup data is loaded once in {@link #load()} and stored in unmodifiable
 * {@link Set} instances; the populated service is effectively immutable and the
 * read-only lookup methods are therefore thread-safe.</p>
 */
@Service
public class ValidationLookupService {

    private static final String AREA_CODES_RESOURCE = "validation/nanpa-area-codes.json";

    /**
     * The single NANPA array consumed by phone validation. Only the
     * general-purpose codes are loaded, mirroring the COBOL
     * {@code VALID-GENERAL-PURP-CODE} {@code 88}-level used by {@code COACTUPC};
     * easy-recognizable / special codes are intentionally excluded.
     */
    private static final String GENERAL_PURPOSE_CODES_FIELD = "validGeneralPurposeCodes";

    private static final String STATE_CODES_RESOURCE = "validation/us-state-codes.json";

    private static final String STATE_ZIP_RESOURCE = "validation/state-zip-prefixes.json";

    private final ObjectMapper objectMapper;

    private Set<String> areaCodes;

    private Set<String> stateCodes;

    private Set<String> stateZipCombos;

    /**
     * Creates the service with the Spring-managed Jackson mapper.
     *
     * @param objectMapper the auto-configured {@link ObjectMapper} used to parse
     *                     the classpath JSON lookup resources
     */
    public ValidationLookupService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Loads the three lookup resources from the classpath and freezes them into
     * unmodifiable sets.
     *
     * <p>A missing, empty, or malformed resource is a fatal misconfiguration and
     * fails fast rather than degrading to empty lookup tables: an
     * {@link UncheckedIOException} is raised for I/O failures and an
     * {@link IllegalStateException} for structurally unusable content.</p>
     */
    @PostConstruct
    void load() {
        Set<String> loadedAreaCodes = new HashSet<>();
        collectArrayStrings(
                requireArrayField(readResource(AREA_CODES_RESOURCE),
                        GENERAL_PURPOSE_CODES_FIELD, AREA_CODES_RESOURCE),
                loadedAreaCodes, false);
        requireNonEmpty(loadedAreaCodes, AREA_CODES_RESOURCE);
        this.areaCodes = Set.copyOf(loadedAreaCodes);

        Set<String> loadedStateCodes = new HashSet<>();
        collectArrayStrings(readResource(STATE_CODES_RESOURCE), loadedStateCodes, true);
        requireNonEmpty(loadedStateCodes, STATE_CODES_RESOURCE);
        this.stateCodes = Set.copyOf(loadedStateCodes);

        Set<String> loadedCombos = new HashSet<>();
        collectStateZip(readResource(STATE_ZIP_RESOURCE), null, loadedCombos);
        requireNonEmpty(loadedCombos, STATE_ZIP_RESOURCE);
        this.stateZipCombos = Set.copyOf(loadedCombos);
    }

    /**
     * Validates a three-digit NANPA telephone area code.
     *
     * <p>Mirrors the COBOL {@code 88}-level {@code VALID-GENERAL-PURP-CODE}
     * condition tested by {@code COACTUPC} phone validation. Easy-recognizable /
     * special codes (for example {@code 200}, {@code 211}, {@code 911}) are
     * rejected because they are not general-purpose codes.</p>
     *
     * @param areaCode the candidate area code; may be {@code null} or padded
     * @return {@code true} when the trimmed value is a known general-purpose
     *         area code
     */
    public boolean isValidAreaCode(String areaCode) {
        if (areaCode == null) {
            return false;
        }
        return areaCodes.contains(areaCode.trim());
    }

    /**
     * Validates a two-character US state code.
     *
     * <p>Mirrors the COBOL {@code 88}-level {@code VALID-US-STATE-CODE}
     * condition. Matching is case-insensitive and ignores surrounding
     * whitespace.</p>
     *
     * @param stateCode the candidate state code; may be {@code null} or padded
     * @return {@code true} when the normalized value is a known state code
     */
    public boolean isValidStateCode(String stateCode) {
        if (stateCode == null) {
            return false;
        }
        return stateCodes.contains(stateCode.trim().toUpperCase(Locale.ROOT));
    }

    /**
     * Validates the combination of a state code and the first two digits of a
     * ZIP code.
     *
     * <p>Mirrors the COBOL {@code 88}-level {@code VALID-US-STATE-ZIP-CD2-COMBO}
     * condition, which compares the four-character
     * {@code US-STATE-AND-FIRST-ZIP2} field (state plus the leading two ZIP
     * digits).</p>
     *
     * @param stateCode the candidate state code; may be {@code null} or padded
     * @param zipCode   the candidate ZIP code; may be {@code null} or padded
     * @return {@code true} when the state and the first two ZIP digits form a
     *         known combination, {@code false} for {@code null} or too-short input
     */
    public boolean isValidStateZipCombo(String stateCode, String zipCode) {
        if (stateCode == null || zipCode == null) {
            return false;
        }
        String state = stateCode.trim().toUpperCase(Locale.ROOT);
        String zip = zipCode.trim();
        if (state.length() < 2 || zip.length() < 2) {
            return false;
        }
        String comboKey = state.substring(0, 2) + zip.substring(0, 2);
        return stateZipCombos.contains(comboKey);
    }

    private JsonNode readResource(String resourcePath) {
        ClassPathResource resource = new ClassPathResource(resourcePath);
        try (InputStream inputStream = resource.getInputStream()) {
            JsonNode root = objectMapper.readTree(inputStream);
            if (root == null || root.isMissingNode() || root.isNull()) {
                throw new IllegalStateException(
                        "Validation resource is empty or not valid JSON: " + resourcePath);
            }
            return root;
        } catch (IOException ex) {
            throw new UncheckedIOException(
                    "Unable to load validation resource from classpath: " + resourcePath, ex);
        }
    }

    /**
     * Resolves a required named array field from a parsed JSON resource, failing
     * fast when the field is absent or is not a JSON array.
     *
     * <p>Used for the NANPA resource so phone validation consumes exactly one
     * named list ({@code validGeneralPurposeCodes}) instead of every array the
     * resource happens to contain.</p>
     *
     * @param root         the parsed resource root node
     * @param fieldName    the required array field name
     * @param resourcePath the resource path, for diagnostics
     * @return the array node for {@code fieldName}
     * @throws IllegalStateException when the field is missing or not an array
     */
    private static JsonNode requireArrayField(JsonNode root, String fieldName, String resourcePath) {
        JsonNode field = root.get(fieldName);
        if (field == null || !field.isArray()) {
            throw new IllegalStateException(
                    "Validation resource " + resourcePath
                            + " must contain the array field '" + fieldName + "'");
        }
        return field;
    }

    private static void collectArrayStrings(JsonNode node, Set<String> target, boolean upperCase) {
        if (node.isArray()) {
            for (int index = 0; index < node.size(); index++) {
                JsonNode child = node.get(index);
                if (child.isArray() || child.isObject()) {
                    collectArrayStrings(child, target, upperCase);
                } else if (!child.isNull()) {
                    String value = child.asText().trim();
                    if (upperCase) {
                        value = value.toUpperCase(Locale.ROOT);
                    }
                    if (!value.isEmpty()) {
                        target.add(value);
                    }
                }
            }
        } else if (node.isObject()) {
            for (Map.Entry<String, JsonNode> entry : node.properties()) {
                collectArrayStrings(entry.getValue(), target, upperCase);
            }
        }
    }

    private static void collectStateZip(JsonNode node, String stateKey, Set<String> target) {
        if (node.isArray()) {
            for (int index = 0; index < node.size(); index++) {
                JsonNode child = node.get(index);
                if (child.isArray() || child.isObject()) {
                    collectStateZip(child, stateKey, target);
                } else if (!child.isNull()) {
                    addStateZipCombo(target, stateKey, child.asText());
                }
            }
        } else if (node.isObject()) {
            for (Map.Entry<String, JsonNode> entry : node.properties()) {
                collectStateZip(entry.getValue(), entry.getKey(), target);
            }
        }
    }

    private static void addStateZipCombo(Set<String> target, String stateKey, String rawValue) {
        if (rawValue == null) {
            return;
        }
        String value = rawValue.replace(" ", "").toUpperCase(Locale.ROOT);
        if (isStateZipShape(value)) {
            target.add(value.substring(0, 4));
            return;
        }
        if (stateKey == null) {
            return;
        }
        String state = stateKey.replace(" ", "").toUpperCase(Locale.ROOT);
        if (isTwoLetters(state) && isTwoDigits(value)) {
            target.add(state.substring(0, 2) + value.substring(0, 2));
        }
    }

    private static void requireNonEmpty(Set<String> values, String resourcePath) {
        if (values.isEmpty()) {
            throw new IllegalStateException(
                    "Validation resource contained no usable entries: " + resourcePath);
        }
    }

    private static boolean isStateZipShape(String value) {
        return value.length() >= 4
                && Character.isLetter(value.charAt(0))
                && Character.isLetter(value.charAt(1))
                && Character.isDigit(value.charAt(2))
                && Character.isDigit(value.charAt(3));
    }

    private static boolean isTwoLetters(String value) {
        return value.length() >= 2
                && Character.isLetter(value.charAt(0))
                && Character.isLetter(value.charAt(1));
    }

    private static boolean isTwoDigits(String value) {
        return value.length() >= 2
                && Character.isDigit(value.charAt(0))
                && Character.isDigit(value.charAt(1));
    }
}
