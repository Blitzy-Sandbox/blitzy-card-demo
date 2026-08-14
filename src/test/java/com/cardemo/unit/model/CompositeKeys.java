/*
 * Test helper : CompositeKeys
 * Application : CardDemo
 * Type        : Shared JUnit 5 test fixture factory
 * Function    : Builds valid instances of the three embeddable composite identifiers derived from
 *               app/cpy/CVTRA01Y.cpy (TRANCAT-KEY, 17 bytes), app/cpy/CVTRA02Y.cpy
 *               (DIS-ACCT-GROUP-ID + TRAN-TYPE-CD + TRAN-CAT-CD, 16 bytes) and
 *               app/cpy/CVTRA04Y.cpy (TRAN-CAT-KEY, 6 bytes), so that the entity contract tests and
 *               the composite key contract tests share one definition of a valid key.
 *
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.cardemo.unit.model;

import com.cardemo.model.key.DisclosureGroupId;
import com.cardemo.model.key.TransactionCategoryBalanceId;
import com.cardemo.model.key.TransactionCategoryId;
import java.util.List;

/**
 * One shared definition of a valid composite identifier, for every test that needs one.
 *
 * <p>Two test classes need to build these keys: the entity contract test, because three entities carry
 * their identity in an {@code @EmbeddedId}, and the composite key contract test, because it exercises the
 * keys directly. Building them in both places would put the same widths and ranges in two files that
 * could then disagree, so the factory lives here instead and both call it.
 *
 * <p>Every string component is supplied at its exact declared width rather than merely within it. Two of
 * the three keys validate their type code with an exact-width check rather than a ceiling - a divergence
 * found while writing the composite key tests - so a shorter value would be refused by some keys and
 * accepted by others, and a factory that used a short value would work only by accident.
 */
final class CompositeKeys {

    /** The three embeddable identifier types, in copybook order. */
    private static final List<Class<?>> TYPES = List.of(
            TransactionCategoryBalanceId.class, DisclosureGroupId.class, TransactionCategoryId.class);

    /** {@code DIS-ACCT-GROUP-ID PIC X(10)}, at its exact width. */
    private static final String GROUP_ID = "ZEROBALANC";

    /** {@code TRAN-TYPE-CD PIC X(02)}, at its exact width. */
    private static final String TYPE_CD = "01";

    /** A {@code TRAN-CAT-CD PIC 9(04)} value comfortably inside 0 to 9999. */
    private static final int CAT_CD = 1000;

    private CompositeKeys() {
        throw new AssertionError("CompositeKeys is a static factory and is never instantiated");
    }

    /**
     * The three embeddable identifier types.
     *
     * @return an immutable list of the three key classes
     */
    static List<Class<?>> types() {
        return TYPES;
    }

    /**
     * Builds a valid instance of one of the three composite identifiers.
     *
     * <p>{@code variant} selects a distinct but equally valid key, which is what lets a caller assert
     * that two different identities compare unequal. It is varied in a numeric component wherever
     * possible so that the string components stay at their exact declared widths.
     *
     * @param keyType one of the three types returned by {@link #types()}
     * @param variant a small non-negative number selecting a distinct key
     * @return a fully populated, valid identifier
     * @throws AssertionError if {@code keyType} is not one of the three
     */
    static Object of(final Class<?> keyType, final int variant) {
        if (keyType.equals(TransactionCategoryBalanceId.class)) {
            return new TransactionCategoryBalanceId(1L + variant, TYPE_CD, CAT_CD);
        }
        if (keyType.equals(DisclosureGroupId.class)) {
            return new DisclosureGroupId(GROUP_ID, TYPE_CD, CAT_CD + variant);
        }
        if (keyType.equals(TransactionCategoryId.class)) {
            return new TransactionCategoryId(TYPE_CD, CAT_CD + variant);
        }
        throw new AssertionError(keyType.getName() + " is not one of the three composite identifiers; "
                + "extend CompositeKeys rather than building a key inline");
    }
}
