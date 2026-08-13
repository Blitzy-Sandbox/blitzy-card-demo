package com.vsergeychik.carddemo.testdataset;

/**
 * The row-selection semantics a record-image relation answers with: the SQL {@code LIKE} matcher, the
 * ordering comparison, and the {@code NULL} rules that make an unreadable row visible or invisible to a
 * predicate.
 *
 * <h2>Why these three are together</h2>
 * <p>They are the only semantics {@link RecordImageDataSource} has to get right, and every one of them is
 * load-bearing for a gate this module is judged on. The {@code LIKE} matcher decides which record a keyed
 * read finds, so it decides {@code FileStatus} {@code '00'} against {@code '23'} (gate G47). The ordering
 * comparison decides what a browse returns next, so it decides whether a KSDS browse reads in key order.
 * The {@code NULL} rules decide whether a present-but-unreadable row is seen, which is the whole of the
 * proof every keyed reader must perform before reporting {@code NOTFND}.
 *
 * <h2>The NULL rules, stated once</h2>
 * <ul>
 *   <li>A comparison against {@code NULL} is unknown, so the row is <strong>not</strong> selected. This is
 *       exactly why {@code DatasetRelation} appends {@code OR <col> IS NULL} to its range predicates: the
 *       unreadable row would otherwise be skipped by a browse and never reported at all.</li>
 *   <li>{@code NULL LIKE ?} is unknown, so a keyed read never matches an unreadable row - which is why the
 *       keyed readers need a separate {@code IS NULL} probe rather than being able to see it directly.</li>
 *   <li>In an {@code ORDER BY}, {@code NULL} sorts low: first ascending, last descending. That is the
 *       default null ordering of the engine this store replaces, so a browse that seeds an unreadable row
 *       sees it in the same position it saw before.</li>
 * </ul>
 */
final class RecordImagePredicate {

    private RecordImagePredicate() {
    }

    /**
     * SQL {@code LIKE} with an explicit escape character.
     *
     * <p>{@code %} matches any sequence including the empty one, {@code _} matches exactly one character,
     * and the escape character makes the next character literal. {@code DatasetRelation.KeySpan.pattern}
     * composes patterns of the shape {@code ____KEY%} - one {@code _} per byte of offset, the key with its
     * own wildcards escaped, then {@code %} - so a keyed read is a fixed-position match, and getting the
     * single-character semantics of {@code _} wrong would turn it into a prefix match over more records
     * than the key names.
     *
     * <p>Iterative with a backtracking mark rather than recursive, so a 350-character record image against
     * a pattern carrying several {@code %} cannot exhaust the stack.
     *
     * @param value   the row's image, never {@code null} here - a {@code NULL} row is rejected by the
     *                caller before it reaches this method
     * @param pattern the composed pattern
     * @param escape  the escape character
     * @return whether the image matches
     */
    static boolean like(String value, String pattern, char escape) {
        int valueIndex = 0;
        int patternIndex = 0;
        int matchedUpTo = -1;
        int resumeAt = -1;

        while (valueIndex < value.length()) {
            if (patternIndex < pattern.length()) {
                char token = pattern.charAt(patternIndex);
                if (token == escape && patternIndex + 1 < pattern.length()) {
                    if (value.charAt(valueIndex) == pattern.charAt(patternIndex + 1)) {
                        valueIndex++;
                        patternIndex += 2;
                        continue;
                    }
                } else if (token == '%') {
                    resumeAt = patternIndex;
                    matchedUpTo = valueIndex;
                    patternIndex++;
                    continue;
                } else if (token == '_' || token == value.charAt(valueIndex)) {
                    valueIndex++;
                    patternIndex++;
                    continue;
                }
            }
            if (resumeAt < 0) {
                return false;
            }
            // Backtrack: let the last % absorb one more character and try again from there.
            matchedUpTo++;
            valueIndex = matchedUpTo;
            patternIndex = resumeAt + 1;
        }

        while (patternIndex < pattern.length() && pattern.charAt(patternIndex) == '%') {
            patternIndex++;
        }
        return patternIndex == pattern.length();
    }

    /**
     * The ordering comparison between two record images.
     *
     * <p>Character images compare by code unit, which is the comparison a {@code VARCHAR} column with no
     * declared collation performs, and it is the comparison a key order depends on. Binary images compare
     * unsigned byte by unsigned byte, then by length - the standard comparison for a binary string - so a
     * shorter image that is a prefix of a longer one sorts first.
     *
     * @param left  the first image
     * @param right the second image
     * @return negative, zero or positive as {@code left} sorts before, with, or after {@code right}
     * @throws IllegalStateException if the two images are of different forms, which means the relation was
     *                               mis-declared
     */
    static int compareImages(Object left, Object right) {
        if (left instanceof String first && right instanceof String second) {
            return first.compareTo(second);
        }
        if (left instanceof byte[] first && right instanceof byte[] second) {
            int shared = Math.min(first.length, second.length);
            for (int position = 0; position < shared; position++) {
                int difference = Byte.toUnsignedInt(first[position])
                        - Byte.toUnsignedInt(second[position]);
                if (difference != 0) {
                    return difference;
                }
            }
            return Integer.compare(first.length, second.length);
        }
        throw new IllegalStateException("Two record images of different forms cannot be ordered against "
                + "each other, so the relation holding them was declared in one form and seeded in the "
                + "other");
    }

    /**
     * Orders rows for an {@code ORDER BY} over the record-image column, {@code NULL} sorting low.
     *
     * @param left      the first image, possibly {@code null}
     * @param right     the second image, possibly {@code null}
     * @param ascending whether the ordering is ascending
     * @return the comparison result in the requested direction
     */
    static int order(Object left, Object right, boolean ascending) {
        int comparison;
        if (left == null && right == null) {
            comparison = 0;
        } else if (left == null) {
            comparison = -1;
        } else if (right == null) {
            comparison = 1;
        } else {
            comparison = compareImages(left, right);
        }
        return ascending ? comparison : -comparison;
    }
}
