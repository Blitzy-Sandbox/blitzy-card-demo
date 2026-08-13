package com.vsergeychik.carddemo.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vsergeychik.carddemo.common.ScreenMetadata.FieldMetadata;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ScreenMetadata}, the presentation metadata every online response carries beside its
 * projected map.
 */
@DisplayName("ScreenMetadata - the metadata that is separate from the payload but present on the wire")
class ScreenMetadataTest {
    private static final byte RED = BmsAttributes.DFHRED;

    @Nested
    @DisplayName("The unsigned projection of an attribute byte")
    class UnsignedAttributes {
        @Test
        @DisplayName("a quad built from raw bytes reads back as four 0-255 values")
        void aQuadReadsBackUnsigned() {
            FieldMetadata quad = FieldMetadata.of(RED,
                    BmsAttributes.DFHBMPRO,
                    BmsAttributes.DFHBLINK,
                    BmsAttributes.DFHBMFSE);

            assertThat(quad.colour()).isEqualTo(BmsAttributes.unsigned(RED)).isBetween(0, 255);
            assertThat(quad.protection())
                    .isEqualTo(BmsAttributes.unsigned(BmsAttributes.DFHBMPRO));
            assertThat(quad.highlight()).isEqualTo(BmsAttributes.unsigned(BmsAttributes.DFHBLINK));
            assertThat(quad.validation()).isEqualTo(BmsAttributes.unsigned(BmsAttributes.DFHBMFSE));
        }

        @Test
        @DisplayName("DFHRED reaches the wire as 242 and never as -14")
        void redIsNotNegative() {
            assertThat(FieldMetadata.of(RED, RED, RED, RED).colour())
                    .isEqualTo(0xF2)
                    .isNotEqualTo((int) RED);
        }

        @Test
        @DisplayName("the message colour is projected unsigned too")
        void theMessageColourIsUnsigned() {
            assertThat(ScreenMetadata.of("ERRMSG", RED, false).messageColour())
                    .isEqualTo(BmsAttributes.unsigned(RED));
        }
    }

    @Nested
    @DisplayName("Absent metadata is reported as absent")
    class AbsentMetadata {
        @Test
        @DisplayName("empty() carries no cursor, no colour, no repaint and no quads")
        void emptyCarriesNothing() {
            ScreenMetadata empty = ScreenMetadata.empty();

            assertThat(empty.cursorField()).isNull();
            assertThat(empty.messageColour()).isNull();
            assertThat(empty.resetAllOutputFields()).isFalse();
            assertThat(empty.fields()).isEmpty();
            assertThat(empty.field("ERRMSG")).isNull();
        }

        @Test
        @DisplayName("a null cursor field is kept null, because no MOVE -1 ran")
        void aNullCursorStaysNull() {
            assertThat(ScreenMetadata.of(null, BmsAttributes.DFHDFCOL, true).cursorField()).isNull();
        }

        @Test
        @DisplayName("a null field map is normalised to an empty one")
        void aNullFieldMapBecomesEmpty() {
            assertThat(new ScreenMetadata("USRIDIN", 0, false, null, null).fields()).isEmpty();
        }

        @Test
        @DisplayName("a null non-display list is normalised to an empty one")
        void aNullNonDisplayListBecomesEmpty() {
            assertThat(new ScreenMetadata("USRIDIN", 0, false, null, null).nonDisplayFields())
                    .isEmpty();
        }

        @Test
        @DisplayName("the two of() forms agree on everything but the quads")
        void theTwoFactoriesAgree() {
            ScreenMetadata withoutQuads = ScreenMetadata.of("MONTHLY", RED, true);
            ScreenMetadata withQuads = ScreenMetadata.of("MONTHLY",
                    RED,
                    true,
                    Map.of("MONTHLY", FieldMetadata.of(RED, RED, RED, RED)));

            assertThat(withQuads.cursorField()).isEqualTo(withoutQuads.cursorField());
            assertThat(withQuads.messageColour()).isEqualTo(withoutQuads.messageColour());
            assertThat(withQuads.resetAllOutputFields()).isEqualTo(withoutQuads.resetAllOutputFields());
            assertThat(withoutQuads.fields()).isEmpty();
            assertThat(withQuads.field("MONTHLY")).isNotNull();
        }
    }

    @Nested
    @DisplayName("The field map is copied in and unmodifiable out")
    class TheFieldMap {
        @Test
        @DisplayName("mutating the source map afterwards does not change the metadata")
        void theSourceMapIsCopied() {
            Map<String, FieldMetadata> source = new HashMap<>();
            source.put("ACCTSID", FieldMetadata.of(RED, RED, RED, RED));

            ScreenMetadata metadata = ScreenMetadata.of("ACCTSID", RED, false, source);
            source.put("CARDSID", FieldMetadata.of(RED, RED, RED, RED));

            assertThat(metadata.fields()).containsOnlyKeys("ACCTSID");
        }

        @Test
        @DisplayName("the published map refuses a write")
        void thePublishedMapIsUnmodifiable() {
            ScreenMetadata metadata = ScreenMetadata.of("ACCTSID",
                    RED,
                    false,
                    Map.of("ACCTSID", FieldMetadata.of(RED, RED, RED, RED)));

            assertThatThrownBy(() -> metadata.fields().put("CARDSID", null))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("copybook order is preserved, so a reader meets the fields as the map declares them")
        void theInsertionOrderIsPreserved() {
            Map<String, FieldMetadata> ordered = new LinkedHashMap<>();
            ordered.put("TRNNAME", FieldMetadata.of(RED, RED, RED, RED));
            ordered.put("ACCTSID", FieldMetadata.of(RED, RED, RED, RED));
            ordered.put("CARDSID", FieldMetadata.of(RED, RED, RED, RED));

            assertThat(ScreenMetadata.of(null, RED, false, ordered).fields().keySet())
                    .containsExactly("TRNNAME", "ACCTSID", "CARDSID");
        }

        @Test
        @DisplayName("a quad without a label, or a label without a quad, is refused")
        void aHalfEntryIsRefused() {
            Map<String, FieldMetadata> nullValue = new HashMap<>();
            nullValue.put("ACCTSID", null);
            assertThatNullPointerException()
                    .isThrownBy(() -> ScreenMetadata.of(null, RED, false, nullValue));

            Map<String, FieldMetadata> nullKey = new HashMap<>();
            nullKey.put(null, FieldMetadata.of(RED, RED, RED, RED));
            assertThatNullPointerException()
                    .isThrownBy(() -> ScreenMetadata.of(null, RED, false, nullKey));
        }
    }

    @Nested
    @DisplayName("Composing the cursor with quads the response owns")
    class WithCursorField {
        @Test
        @DisplayName("withCursorField replaces only the cursor")
        void onlyTheCursorChanges() {
            ScreenMetadata quads = ScreenMetadata.of(null,
                    RED,
                    true,
                    Map.of("ERRMSG", FieldMetadata.of(RED, RED, RED, RED)));

            ScreenMetadata positioned = quads.withCursorField("ERRMSG");

            assertThat(positioned.cursorField()).isEqualTo("ERRMSG");
            assertThat(positioned.messageColour()).isEqualTo(quads.messageColour());
            assertThat(positioned.resetAllOutputFields()).isTrue();
            assertThat(positioned.fields()).isEqualTo(quads.fields());
            assertThat(quads.cursorField()).isNull();
        }

        @Test
        @DisplayName("withCursorField(null) clears a cursor request")
        void theCursorCanBeCleared() {
            assertThat(ScreenMetadata.of("ERRMSG", RED, false).withCursorField(null).cursorField())
                    .isNull();
        }
    }

    @Nested
    @DisplayName("The JSON shape")
    class TheJsonShape {
        private final ObjectMapper mapper = new ObjectMapper();

        @Test
        @DisplayName("an absent cursor and colour are omitted rather than serialised as null")
        void absentMembersAreOmitted() throws Exception {
            String json = mapper.writeValueAsString(ScreenMetadata.empty());

            assertThat(json)
                    .doesNotContain("cursorField")
                    .doesNotContain("messageColour")
                    .contains("\"resetAllOutputFields\":false")
                    .contains("\"fields\":{}");
        }

        @Test
        @DisplayName("a populated envelope names each field by its DFHMDF label")
        void aPopulatedEnvelopeIsKeyedByLabel() throws Exception {
            String json = mapper.writeValueAsString(ScreenMetadata.of("ACCTSID",
                    RED,
                    true,
                    Map.of("ACCTSID", FieldMetadata.of(RED,
                            BmsAttributes.DFHBMPRO,
                            BmsAttributes.DFHBLINK,
                            BmsAttributes.DFHBMFSE))));

            assertThat(json)
                    .contains("\"cursorField\":\"ACCTSID\"")
                    .contains("\"messageColour\":242")
                    .contains("\"resetAllOutputFields\":true")
                    .contains("\"ACCTSID\":{\"colour\":242,");
        }
    }
}
