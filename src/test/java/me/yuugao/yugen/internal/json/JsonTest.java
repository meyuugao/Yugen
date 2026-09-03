package me.yuugao.yugen.internal.json;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonTest {
    @Test
    void parsesPrimitives() {
        assertThat(Json.parse("null")).isNull();
        assertThat(Json.parse("true")).isEqualTo(Boolean.TRUE);
        assertThat(Json.parse("false")).isEqualTo(Boolean.FALSE);
        assertThat(Json.parse("42")).isEqualTo(42L);
        assertThat(Json.parse("-7")).isEqualTo(-7L);
        assertThat(Json.parse("0")).isEqualTo(0L);
        assertThat(Json.parse("1.5")).isEqualTo(1.5);
        assertThat(Json.parse("1e3")).isEqualTo(1000.0);
        assertThat(Json.parse("-2.5E-1")).isEqualTo(-0.25);
        assertThat(Json.parse("\"hi\"")).isEqualTo("hi");
    }

    @Test
    void parsesEmptyContainers() {
        assertThat((Map<?, ?>) Json.parse("{}")).isEmpty();
        assertThat((List<?>) Json.parse("[]")).isEmpty();
    }

    @Test
    void parsesNestedStructure() {
        @SuppressWarnings("unchecked")
        Map<String, Object> root = (Map<String, Object>) Json.parse("{\"a\":[1,2,{\"b\":null}],\"c\":\"x\"}");
        assertThat(root).hasSize(2);
        List<?> a = (List<?>) root.get("a");
        assertThat(a).hasSize(3);
        assertThat(a.get(0)).isEqualTo(1L);
        assertThat(a.get(1)).isEqualTo(2L);
        @SuppressWarnings("unchecked")
        Map<String, Object> nested = (Map<String, Object>) a.get(2);
        assertThat(nested).containsOnlyKeys("b");
        assertThat(nested.get("b")).isNull();
        assertThat(root.get("c")).isEqualTo("x");
    }

    @Test
    void preservesObjectKeyOrder() {
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) Json.parse("{\"z\":1,\"a\":2,\"m\":3}");
        assertThat(map.keySet()).containsExactly("z", "a", "m");
    }

    @Test
    void duplicateKeysKeepLastValue() {
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) Json.parse("{\"k\":1,\"k\":2}");
        assertThat(map).hasSize(1).containsEntry("k", 2L);
    }

    @Test
    void ignoresSurroundingWhitespace() {
        assertThat(Json.parse("  \n\t 42 \r\n ")).isEqualTo(42L);
    }

    @Test
    void parsesEscapeSequences() {
        Object parsed = Json.parse("\"line\\nbreak \\\"quoted\\\" \\\\ backslash \\u0041\"");
        assertThat(parsed).isEqualTo("line\nbreak \"quoted\" \\ backslash A");
    }

    @Test
    void parsesNonAsciiText() {
        assertThat(Json.parse("\"幽玄 yugen\"")).isEqualTo("幽玄 yugen");
    }

    @Test
    void rejectsTrailingGarbage() {
        assertThatThrownBy(() -> Json.parse("1 2")).isInstanceOf(Json.JsonParseException.class);
        assertThatThrownBy(() -> Json.parse("{} {}")).isInstanceOf(Json.JsonParseException.class);
    }

    @Test
    void rejectsMalformedInput() {
        assertThatThrownBy(() -> Json.parse("{")).isInstanceOf(Json.JsonParseException.class);
        assertThatThrownBy(() -> Json.parse("[1,]")).isInstanceOf(Json.JsonParseException.class);
        assertThatThrownBy(() -> Json.parse("{\"k\" 1}")).isInstanceOf(Json.JsonParseException.class);
        assertThatThrownBy(() -> Json.parse("\"unterminated")).isInstanceOf(Json.JsonParseException.class);
        assertThatThrownBy(() -> Json.parse("tru")).isInstanceOf(Json.JsonParseException.class);
        assertThatThrownBy(() -> Json.parse("-")).isInstanceOf(Json.JsonParseException.class);
        assertThatThrownBy(() -> Json.parse("")).isInstanceOf(Json.JsonParseException.class);
    }

    @Test
    void rejectsUnescapedControlChars() {
        assertThatThrownBy(() -> Json.parse("\"a\nb\"")).isInstanceOf(Json.JsonParseException.class);
    }

    @Test
    void rejectsInvalidEscapes() {
        assertThatThrownBy(() -> Json.parse("\"\\x\"")).isInstanceOf(Json.JsonParseException.class);
        assertThatThrownBy(() -> Json.parse("\"\\u00ZZ\"")).isInstanceOf(Json.JsonParseException.class);
    }

    @Test
    void writesAndEscapesStrings() {
        assertThat(Json.write("a\"b\\c\nd\u0001")).isEqualTo("\"a\\\"b\\\\c\\nd\\u0001\"");
        assertThat(Json.write("tab\there")).isEqualTo("\"tab\\there\"");
    }

    @Test
    void writesRoundTrip() {
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("b", Arrays.asList(1L, 2.5, "three", Boolean.TRUE, null));
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("a", inner);
        root.put("unicode", "幽玄");

        String written = Json.write(root);
        Object reparsed = Json.parse(written);

        assertThat(reparsed).isEqualTo(root);
    }

    @Test
    void writesPrimitivesAndNulls() {
        assertThat(Json.write(null)).isEqualTo("null");
        assertThat(Json.write(Boolean.TRUE)).isEqualTo("true");
        assertThat(Json.write(42L)).isEqualTo("42");
        assertThat(Json.write(2.5)).isEqualTo("2.5");
        assertThat(Json.write(List.of())).isEqualTo("[]");
        assertThat(Json.write(Map.of())).isEqualTo("{}");
    }

    @Test
    void rejectsNaNAndInfinityOnWrite() {
        assertThatThrownBy(() -> Json.write(Double.NaN)).isInstanceOf(Json.JsonParseException.class);
        assertThatThrownBy(() -> Json.write(Double.POSITIVE_INFINITY)).isInstanceOf(Json.JsonParseException.class);
    }

    @Test
    void rejectsUnsupportedTypesOnWrite() {
        assertThatThrownBy(() -> Json.write(new Object())).isInstanceOf(Json.JsonParseException.class);
    }
}