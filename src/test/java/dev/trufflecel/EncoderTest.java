package dev.trufflecel;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public final class EncoderTest {
  private static Object eval(String source) {
    return Cel.compile(source).evaluate();
  }

  private static void yes(String source) {
    assertEquals(source, true, eval(source));
  }

  @Test
  public void jsonAndYamlDataAndTypedParsing() {
    for (String format : List.of("json", "yaml")) {
      yes(format + ".parse('null').value() == null");
      yes(format + ".parse('true').value()");
      yes(format + ".parse('false', bool).value() == false");
      yes(format + ".parse('123').value() == 123");
      yes(format + ".parse('18446744073709551615').value() == 18446744073709551615u");
      yes(format + ".parse('1.5').value() == 1.5");
      yes(format + ".parse('123', double).value() == 123.0");
      yes(format + ".parse('123', uint).value() == 123u");
      yes(format + ".parse('123', int).value() == 123");
      yes(
          format
              + ".parse('"
              + (format.equals("json") ? "\\\"a\\\"" : "a")
              + "', string).value() == 'a'");
      yes(format + ".parse('[1,2]', list).value() == [1,2]");
      yes(format + ".parse('{\"a\":1}', map).value() == {'a':1}");
      yes(format + ".parse('null', null_type).value() == null");
      yes(format + ".parse('\"YQ==\"', bytes).value() == b'a'");
      yes(
          format
              + ".parse('\"2020-01-01T00:00:00Z\"', google.protobuf.Timestamp).value() == timestamp('2020-01-01T00:00:00Z')");
      yes(format + ".parse('\"1s\"', google.protobuf.Duration).value() == duration('1s')");
      yes(
          format
              + ".parse("
              + format
              + ".encode([null, true, 1, 2u, 1.5, 'a'])).value() == [null, true, 1, 2u, 1.5, 'a']");
      for (String invalid : List.of("", "[", "{a", "1 2", "NaN", "Infinity")) {
        if (format.equals("json") || !Set.of("1 2", "NaN", "Infinity").contains(invalid))
          yes("!" + format + ".parse('" + invalid + "', int).hasValue()");
      }
      yes("!" + format + ".parse('true', int).hasValue()");
      yes("!" + format + ".parse('18446744073709551615', int).hasValue()");
      yes("!" + format + ".parse('1e99999').hasValue()");
      yes("!" + format + ".parse('{\"a\":1,\"a\":2}').hasValue()");
    }
    yes("json.encode(9007199254740992) == '\"9007199254740992\"'");
    yes("json.encode(18446744073709551615u) == '\"18446744073709551615\"'");
    yes("json.encode(-9223372036854775808) == '\"-9223372036854775808\"'");
    yes("json.encode(b'a') == '\"YQ==\"'");
    yes("json.encode({'a':1, 'b':2}) == '{\"a\":1,\"b\":2}'");
    yes("json.encode({1: true, 2u: false}) == '{\"1\":true,\"2\":false}'");
    yes("yaml.parse('a: 1\\nb: [true, false]').value() == {'a':1, 'b':[true,false]}");
    yes("yaml.parse('1: true').value() == {'1':true}");
    assertEquals(
        CelException.Code.RESOURCE_EXHAUSTED,
        assertThrows(CelException.class, () -> eval("yaml.parse('a: &a [*a]')")).code());
    yes("!yaml.parse('---\\na\\n---\\nb').hasValue()");
    yes("!yaml.parse('!!java/object:java.lang.Runtime {}').hasValue()");
  }

  @Test
  public void encodersRejectTypesAndBoundWork() {
    for (String source :
        List.of(
            "json.encode(double('NaN'))",
            "json.encode(double('Infinity'))",
            "json.encode(optional.none())",
            "json.encode({1:1, '1':2})",
            "json.encode({b'a':1})")) assertThrows(source, CelException.class, () -> eval(source));
    for (String source :
        List.of(
            "json.parse(1)",
            "json.parse('{}', 1)",
            "json.encode()",
            "yaml.parse()",
            "yaml.encode(1,2)"))
      assertThrows(source, CelException.class, () -> Cel.compile(source));
    var env = Cel.newBuilder().maxDepth(8).inferVariables(true).build();
    for (String format : List.of("json", "yaml")) {
      var program = env.compile(format + ".parse(s)");
      assertEquals(
          CelException.Code.RESOURCE_EXHAUSTED,
          assertThrows(
                  CelException.class,
                  () -> program.evaluate(Map.of("s", "[".repeat(20) + "1" + "]".repeat(20))))
              .code());
      var limited = Cel.newBuilder().evaluationBudget(10).build();
      assertEquals(
          CelException.Code.RESOURCE_EXHAUSTED,
          assertThrows(
                  CelException.class,
                  () -> limited.compile(format + ".encode('abcdefghijklmnop')").evaluate())
              .code());
    }
  }

  @Test
  public void dynamicTypesObjectsAndMalformedValues() {
    var type =
        new ObjectType() {
          public String name() {
            return "Record";
          }

          public Map<String, CelType> fields() {
            return Map.of("count", CelType.INT, "absent", CelType.INT);
          }

          public Object construct(Map<String, Object> values) {
            return new Record(values);
          }

          public boolean accepts(Object value) {
            return value instanceof Record;
          }

          public boolean has(Object value, String name) {
            return ((Record) value).fields().containsKey(name);
          }

          public Object get(Object value, String name) {
            return ((Record) value).fields().getOrDefault(name, 0L);
          }
        };
    var env =
        Cel.newBuilder().objectType(type).inferVariables(true).evaluationBudget(30_000_000).build();
    assertEquals(
        true, env.compile("json.parse('{\"count\":1}', Record).value().count == 1").evaluate());
    assertEquals(true, env.compile("!json.parse('{\"unknown\":1}', Record).hasValue()").evaluate());
    assertEquals(true, env.compile("!json.parse('true', Record).hasValue()").evaluate());
    assertEquals(
        "{\"count\":1}",
        env.compile("json.encode(r)").evaluate(Map.of("r", new Record(Map.of("count", 1L)))));
    assertEquals("{}", env.compile("json.encode(r)").evaluate(Map.of("r", new Record(Map.of()))));
    assertThrows(
        CelException.class,
        () -> env.compile("json.encode(r)").evaluate(Map.of("r", new Object())));
    assertThrows(
        CelException.class,
        () ->
            env.compile("json.encode(r)")
                .evaluate(Map.of("r", Map.of(new Bytes(new byte[] {1}), 1L))));
    assertEquals("{\"true\":1}", env.compile("json.encode({true:1})").evaluate());
    for (String source :
        List.of("json.encode(timestamp('2020-01-01T00:00:00Z'))", "json.encode(duration('-1.5s'))"))
      assertTrue(eval(source) instanceof String);
    var parse = env.compile("json.parse(s, t)");
    for (var test :
        List.of(
            Map.entry("1", CelType.UINT),
            Map.entry("18446744073709551615", CelType.DOUBLE),
            Map.entry("18446744073709551615", CelType.INT),
            Map.entry("1.5", CelType.INT),
            Map.entry("-1", CelType.UINT),
            Map.entry("\"bad\"", CelType.TIMESTAMP),
            Map.entry("true", CelType.UINT),
            Map.entry("true", CelType.DOUBLE),
            Map.entry("1", CelType.BYTES),
            Map.entry("1", CelType.TIMESTAMP),
            Map.entry("1", CelType.DURATION),
            Map.entry("true", CelType.list(CelType.INT)),
            Map.entry("true", CelType.map(CelType.STRING, CelType.INT)),
            Map.entry("[1,2]", CelType.list(CelType.UINT)),
            Map.entry("{\"a\":1}", CelType.map(CelType.STRING, CelType.DOUBLE)))) {
      assertTrue(
          parse.evaluate(Map.of("s", test.getKey(), "t", test.getValue()))
              instanceof OptionalValue);
    }
    for (String text : List.of("[1,]", "true false", "{\"a\":1} {}", "1e99999", "null null", " "))
      assertEquals(OptionalValue.none(), env.compile("json.parse(s)").evaluate(Map.of("s", text)));
    for (String text :
        List.of("null: a", "? [a]\n: b", "!!set {a: null}", "1: a\n'1': b", "true: 1"))
      assertTrue(env.compile("yaml.parse(s)").evaluate(Map.of("s", text)) instanceof OptionalValue);
    assertEquals(
        CelException.Code.RESOURCE_EXHAUSTED,
        assertThrows(
                CelException.class,
                () ->
                    env.compile("json.parse(s)")
                        .evaluate(Map.of("s", " ".repeat(10 * 1024 * 1024 + 1))))
            .code());
    assertEquals(
        CelException.Code.RESOURCE_EXHAUSTED,
        assertThrows(
                CelException.class,
                () ->
                    Cel.newBuilder()
                        .maxDepth(2)
                        .inferVariables(true)
                        .build()
                        .compile("json.encode(x)")
                        .evaluate(Map.of("x", List.of(List.of(List.of(1))))))
            .code());
  }

  private record Record(Map<String, Object> fields) {}
}
