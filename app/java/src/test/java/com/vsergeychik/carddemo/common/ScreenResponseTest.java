package com.vsergeychik.carddemo.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vsergeychik.carddemo.common.ScreenMetadata.FieldMetadata;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ScreenResponse}, the body envelope every online screen answers with.
 */
@DisplayName("ScreenResponse - the projected map flattened, with its metadata beside it")
class ScreenResponseTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private record StubScreen(String trnName, String errMsg, NavigationContext navigationContext) {
        static StubScreen painted() {
            return new StubScreen("CU02", "Press PF5 to delete", NavigationContext.empty());
        }
    }

    @Nested
    @DisplayName("The wire shape")
    class TheWireShape {
        @Test
        @DisplayName("the screen's members serialise at the top level, unwrapped and unrenamed")
        void theScreenIsFlattened() throws Exception {
            JsonNode body = mapper.valueToTree(
                    ScreenResponse.of(StubScreen.painted(), ScreenMetadata.empty()));

            assertThat(body.has("screen")).isFalse();
            assertThat(body.get("trnName").asText()).isEqualTo("CU02");
            assertThat(body.get("errMsg").asText()).isEqualTo("Press PF5 to delete");
            assertThat(body.get("navigationContext").isObject()).isTrue();
        }

        @Test
        @DisplayName("the metadata is one nested sibling member and never a top-level field")
        void theMetadataIsOneNestedMember() throws Exception {
            ScreenMetadata metadata = ScreenMetadata.of("USRIDIN",
                    BmsAttributes.DFHRED,
                    true,
                    Map.of("ERRMSG", FieldMetadata.of(BmsAttributes.DFHRED,
                            BmsAttributes.DFHBMPRO,
                            BmsAttributes.DFHBLINK,
                            BmsAttributes.DFHBMFSE)));

            JsonNode body = mapper.valueToTree(ScreenResponse.of(StubScreen.painted(), metadata));

            assertThat(body.has("cursorField")).isFalse();
            assertThat(body.has("messageColour")).isFalse();
            assertThat(body.has("resetAllOutputFields")).isFalse();

            JsonNode published = body.get("screenMetadata");
            assertThat(published.get("cursorField").asText()).isEqualTo("USRIDIN");
            assertThat(published.get("messageColour").asInt())
                    .isEqualTo(BmsAttributes.unsigned(BmsAttributes.DFHRED));
            assertThat(published.get("resetAllOutputFields").asBoolean()).isTrue();
            assertThat(published.get("fields").get("ERRMSG").get("colour").asInt())
                    .isEqualTo(BmsAttributes.unsigned(BmsAttributes.DFHRED));
        }

        @Test
        @DisplayName("a screen with no metadata still publishes the member, empty rather than absent")
        void anEmptyEnvelopeIsStillPublished() throws Exception {
            JsonNode body = mapper.valueToTree(ScreenResponse.of(StubScreen.painted()));

            assertThat(body.has("screenMetadata")).isTrue();
            assertThat(body.get("screenMetadata").get("fields").isEmpty()).isTrue();
            assertThat(body.get("screenMetadata").has("cursorField")).isFalse();
        }
    }

    @Nested
    @DisplayName("The two factories and their guards")
    class TheFactories {
        @Test
        @DisplayName("of(screen) carries empty metadata rather than none")
        void theSingleArgumentFactoryCarriesEmptyMetadata() {
            ScreenResponse<StubScreen> body = ScreenResponse.of(StubScreen.painted());

            assertThat(body.screenMetadata()).isEqualTo(ScreenMetadata.empty());
            assertThat(body.screen().trnName()).isEqualTo("CU02");
        }

        @Test
        @DisplayName("of(screen, metadata) keeps both components as supplied")
        void theTwoArgumentFactoryKeepsBoth() {
            ScreenMetadata metadata = ScreenMetadata.of("ERRMSG", BmsAttributes.DFHRED, false);

            ScreenResponse<StubScreen> body = ScreenResponse.of(StubScreen.painted(), metadata);

            assertThat(body.screenMetadata()).isSameAs(metadata);
            assertThat(body.screen()).isEqualTo(StubScreen.painted());
        }

        @Test
        @DisplayName("neither component may be null: a body always carries a screen and metadata")
        void neitherComponentMayBeNull() {
            assertThatNullPointerException()
                    .isThrownBy(() -> ScreenResponse.of(null, ScreenMetadata.empty()));
            assertThatNullPointerException()
                    .isThrownBy(() -> new ScreenResponse<>(StubScreen.painted(), null));
        }
    }
}
