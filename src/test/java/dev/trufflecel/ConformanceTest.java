package dev.trufflecel;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import static org.junit.Assert.*;

/** Every case in the pinned upstream corpus executes; no per-case skips. */
@RunWith(Parameterized.class)
public class ConformanceTest {
  @Parameterized.Parameters(name = "{0}")
  public static Collection<Object[]> cases() throws Exception {
    var result = new ArrayList<Object[]>();
    for (JsonElement file :
        JsonParser.parseString(Files.readString(Path.of("third_party/cel-spec/corpus.json")))
            .getAsJsonArray()) {
      var f = file.getAsJsonObject();
      if (!f.has("section")) continue;
      for (JsonElement section : f.getAsJsonArray("section")) {
        var s = section.getAsJsonObject();
        if (!s.has("test")) continue;
        for (JsonElement test : s.getAsJsonArray("test")) {
          var t = test.getAsJsonObject();
          result.add(
              new Object[] {
                f.get("file").getAsString()
                    + "/"
                    + s.get("name").getAsString()
                    + "/"
                    + t.get("name").getAsString(),
                t
              });
        }
      }
    }
    assertEquals("complete pinned corpus case count", 2771, result.size());
    return result;
  }

  private final String name;
  private final JsonObject test;

  public ConformanceTest(String name, JsonObject test) {
    this.name = name;
    this.test = test;
  }

  @Test
  public void conforms() {
    conforms(false);
  }

  @Test
  public void canonicalConforms() {
    conforms(true);
  }

  private Program plan(Environment env, String source, boolean check, boolean canonical) {
    if (!canonical) return check ? env.compile(source) : env.parse(source);
    var parsed = CanonicalAst.parse(env, source);
    return CanonicalAst.program(env, check ? CanonicalAst.check(env, parsed) : parsed);
  }

  private void conforms(boolean canonical) {
    Environment.Builder builder = Cel.newBuilder();
    TestObjects.register(builder);
    if (test.has("container")) builder.container(test.get("container").getAsString());
    if (flag("disable_macros")) builder.macros(false);
    if (test.has("type_env"))
      for (JsonElement d : test.getAsJsonArray("type_env")) {
        var decl = d.getAsJsonObject();
        String id = decl.get("name").getAsString();
        if (decl.has("ident")) {
          var ident = decl.getAsJsonObject("ident");
          if (ident.has("value")) builder.constant(id, value(ident.getAsJsonObject("value")));
          else builder.variable(id, type(ident.getAsJsonObject("type")));
        }
        if (decl.has("function"))
          for (var overload : decl.getAsJsonObject("function").getAsJsonArray("overloads")) {
            var o = overload.getAsJsonObject();
            var args = new ArrayList<CelType>();
            if (o.has("params"))
              for (var p : o.getAsJsonArray("params")) args.add(type(p.getAsJsonObject()));
            builder.function(
                id,
                args,
                type(o.getAsJsonObject("result_type")),
                values -> {
                  throw Values.overload(id);
                });
          }
      }
    var bindings = new HashMap<String, Object>();
    if (test.has("bindings"))
      for (var binding : test.getAsJsonObject("bindings").entrySet()) {
        JsonObject v = binding.getValue().getAsJsonObject();
        if (v.has("value")) bindings.put(binding.getKey(), value(v.getAsJsonObject("value")));
        else if (v.has("unknown")) bindings.put(binding.getKey(), Unknown.of(binding.getKey()));
      }
    boolean error = test.has("eval_error") || test.has("any_eval_errors");
    Environment env = builder.build();
    Program program;
    try {
      program = plan(env, test.get("expr").getAsString(), !flag("disable_check"), canonical);
    } catch (CelException e) {
      // Some upstream eval_error cases describe a statically invalid overload. Keep the
      // checker rejection and also execute unchecked to verify the required runtime error.
      if (error && e.code() == CelException.Code.TYPE)
        program = plan(env, test.get("expr").getAsString(), false, canonical);
      else
        throw new AssertionError(
            name + " failed compilation: " + test.get("expr") + ": " + e.getMessage(), e);
    }
    if (test.has("typed_result"))
      assertEquals(
          "deduced type",
          type(test.getAsJsonObject("typed_result").getAsJsonObject("deduced_type")),
          program.resultType());
    if (flag("check_only")) return;
    Object actual;
    try {
      actual = program.evaluateInternal(bindings);
    } catch (CelException e) {
      if (error && e.code() != CelException.Code.RESOURCE_EXHAUSTED) return;
      throw new AssertionError(test.get("expr") + ": " + e.getMessage(), e);
    }
    if (error) fail("expected evaluation error, got " + actual);
    JsonObject expected =
        test.has("value")
            ? test.getAsJsonObject("value")
            : test.has("typed_result")
                ? test.getAsJsonObject("typed_result").getAsJsonObject("result")
                : null;
    Object wanted = expected == null ? true : value(expected);
    assertTrue(
        "expression=" + test.get("expr") + " expected=" + wanted + " actual=" + actual,
        same(wanted, actual));
  }

  private boolean flag(String name) {
    return test.has(name) && test.get(name).getAsBoolean();
  }

  static boolean same(Object a, Object b) {
    if (Values.numeric(a) && Values.numeric(b) && !a.getClass().equals(b.getClass())) return false;
    if (a instanceof List<?> x && b instanceof List<?> y) {
      if (x.size() != y.size()) return false;
      for (int i = 0; i < x.size(); i++) if (!same(x.get(i), y.get(i))) return false;
      return true;
    }
    if (a instanceof Map<?, ?> x && b instanceof Map<?, ?> y) {
      if (x.size() != y.size()) return false;
      for (var entry : x.entrySet()) {
        Object key =
            y.keySet().stream().filter(k -> same(entry.getKey(), k)).findFirst().orElse(null);
        if (key == null || !same(entry.getValue(), y.get(key))) return false;
      }
      return true;
    }
    if (a instanceof Double x && b instanceof Double y && x.isNaN() && y.isNaN()) return true;
    if (a instanceof ProtoSchema.Message x && b instanceof ProtoSchema.Message y)
      return TestObjects.same(x, y);
    if (a instanceof Bytes x && b instanceof Bytes y)
      return Arrays.equals(x.toByteArray(), y.toByteArray());
    return a.equals(b);
  }

  static Object value(JsonObject v) {
    if (v.has("null_value")) return CelNull.INSTANCE;
    if (v.has("bool_value")) return v.get("bool_value").getAsBoolean();
    if (v.has("int64_value")) return v.get("int64_value").getAsLong();
    if (v.has("uint64_value")) return UInt.parse(v.get("uint64_value").getAsString());
    if (v.has("double_value")) return v.get("double_value").getAsDouble();
    if (v.has("string_value")) return v.get("string_value").getAsString();
    if (v.has("bytes_value"))
      return new Bytes(Base64.getDecoder().decode(v.get("bytes_value").getAsString()));
    if (v.has("type_value")) return CelType.of(v.get("type_value").getAsString());
    if (v.has("list_value")) {
      var list = new ArrayList<Object>();
      var x = v.getAsJsonObject("list_value");
      if (x.has("values"))
        for (var item : x.getAsJsonArray("values")) list.add(value(item.getAsJsonObject()));
      return list;
    }
    if (v.has("map_value")) {
      var map = new LinkedHashMap<Object, Object>();
      var x = v.getAsJsonObject("map_value");
      if (x.has("entries"))
        for (var entry : x.getAsJsonArray("entries")) {
          var e = entry.getAsJsonObject();
          map.put(value(e.getAsJsonObject("key")), value(e.getAsJsonObject("value")));
        }
      return map;
    }
    if (v.has("object_value")) return TestObjects.object(v.getAsJsonObject("object_value"));
    throw new AssertionError("unhandled conformance value " + v);
  }

  static CelType type(JsonObject v) {
    if (v == null || v.has("dyn")) return CelType.DYN;
    if (v.has("type_param")) return CelType.parameter(v.get("type_param").getAsString());
    if (v.has("abstract_type")) {
      var t = v.getAsJsonObject("abstract_type");
      var p = new ArrayList<CelType>();
      if (t.has("parameter_types"))
        for (var item : t.getAsJsonArray("parameter_types")) p.add(type(item.getAsJsonObject()));
      return new CelType(t.get("name").getAsString(), p);
    }
    if (v.has("primitive"))
      return switch (v.get("primitive").getAsInt()) {
        case 1 -> CelType.BOOL;
        case 2 -> CelType.INT;
        case 3 -> CelType.UINT;
        case 4 -> CelType.DOUBLE;
        case 5 -> CelType.STRING;
        case 6 -> CelType.BYTES;
        default -> throw new AssertionError(v);
      };
    if (v.has("null")) return CelType.NULL;
    if (v.has("wrapper")) {
      var t = new JsonObject();
      t.add("primitive", v.get("wrapper"));
      return CelType.wrapper(type(t));
    }
    if (v.has("well_known"))
      return switch (v.get("well_known").getAsInt()) {
        case 1 -> CelType.DYN;
        case 2 -> CelType.TIMESTAMP;
        case 3 -> CelType.DURATION;
        default -> throw new AssertionError(v);
      };
    if (v.has("list_type"))
      return CelType.list(type(v.getAsJsonObject("list_type").getAsJsonObject("elem_type")));
    if (v.has("map_type")) {
      var m = v.getAsJsonObject("map_type");
      return CelType.map(
          type(m.getAsJsonObject("key_type")), type(m.getAsJsonObject("value_type")));
    }
    if (v.has("message_type")) return WellKnownTypes.mapped(v.get("message_type").getAsString());
    if (v.has("type")) {
      var t = v.getAsJsonObject("type");
      return t.size() == 0 ? CelType.TYPE : CelType.type(type(t));
    }
    throw new AssertionError("unhandled conformance type " + v);
  }
}
