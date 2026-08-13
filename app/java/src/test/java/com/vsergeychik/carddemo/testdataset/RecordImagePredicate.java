package com.vsergeychik.carddemo.testdataset;

/**
 * The row-selection semantics a record-image relation answers with: the SQL {@code LIKE} matcher, the
 * ordering comparison, and the {@code NULL} rules that make an unreadable row visible or invisible to a
 * predicate.
 */
final class RecordImagePredicate {
    private RecordImagePredicate() {
    }

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
            matchedUpTo++;
            valueIndex = matchedUpTo;
            patternIndex = resumeAt + 1;
        }

        while (patternIndex < pattern.length() && pattern.charAt(patternIndex) == '%') {
            patternIndex++;
        }
        return patternIndex == pattern.length();
    }

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
