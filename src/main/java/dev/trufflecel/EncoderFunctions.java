package dev.trufflecel;

import com.google.gson.Strictness;
import com.google.gson.stream.*;
import java.io.*;
import java.math.*;
import java.util.*;
import org.snakeyaml.engine.v2.api.*;
import org.snakeyaml.engine.v2.api.lowlevel.Parse;
import org.snakeyaml.engine.v2.events.Event;

/** Bounded data-only encoders; never constructs Java beans or resolves application classes. */
final class EncoderFunctions {
  private static final int MAX_TEXT = 10 * 1024 * 1024;

  private EncoderFunctions() {}

  static Object apply(String name, Object[] args, EvalContext ctx) {
    boolean yaml = name.startsWith("yaml.");
    if (name.endsWith("encode")) {
      Object value = jsonValue(args[0], ctx, 0);
      var buffer = new StringWriter();
      writeJson(value, buffer, ctx);
      String json = buffer.toString();
      if (!yaml) return json;
      // Dump only the data model already validated for JSON. No bean representer is involved.
      var settings = LoadSettings.builder().setCodePointLimit(Integer.MAX_VALUE).build();
      try {
        Object data = new Load(settings).loadFromString(json);
        var yamlOutput = new StringBuilder();
        new Dump(DumpSettings.builder().build())
            .dump(
                data,
                new StreamDataWriter() {
                  public void write(String text) {
                    write(text, 0, text.length());
                  }

                  public void write(String text, int offset, int length) {
                    ctx.charge(length);
                    yamlOutput.append(text, offset, offset + length);
                  }
                });
        return yamlOutput.toString();
      } catch (org.snakeyaml.engine.v2.exceptions.YamlEngineException e) {
        throw Values.error(CelException.Code.RESOURCE_EXHAUSTED, "encoder limit exceeded");
      }
    }
    String text = (String) args[0];
    if (text.length() > MAX_TEXT)
      throw Values.error(CelException.Code.RESOURCE_EXHAUSTED, "encoder input size limit exceeded");
    try {
      Object value;
      if (yaml) {
        if (text.isBlank()) return OptionalValue.none();
        var settings =
            LoadSettings.builder()
                .setCodePointLimit(MAX_TEXT)
                .setAllowDuplicateKeys(false)
                .setAllowRecursiveKeys(false)
                .setAllowNonScalarKeys(false)
                .setMaxAliasesForCollections(50)
                .build();
        // Validate depth before the composer's recursive traversal or Java collection creation.
        int depth = 0;
        for (Event event : new Parse(settings).parseString(text)) {
          ctx.tick();
          if (event.getEventId() == Event.ID.MappingStart
              || event.getEventId() == Event.ID.SequenceStart) {
            if (++depth > ctx.environment.maxDepth)
              throw Values.error(
                  CelException.Code.RESOURCE_EXHAUSTED, "encoder depth limit exceeded");
          } else if (event.getEventId() == Event.ID.MappingEnd
              || event.getEventId() == Event.ID.SequenceEnd) depth--;
        }
        value = yamlValue(new Load(settings).loadFromString(text), ctx, 0);
      } else {
        var reader = new JsonReader(new StringReader(text));
        reader.setStrictness(Strictness.STRICT);
        value = read(reader, ctx, 0);
        var unused = reader.peek(); // Strict mode requires end-of-document after a complete value.
      }
      CelType target = args.length == 2 ? (CelType) args[1] : CelType.DYN;
      return OptionalValue.of(convert(value, target, ctx));
    } catch (CelException e) {
      if (e.code() == CelException.Code.RESOURCE_EXHAUSTED) throw e;
      return OptionalValue.none();
    } catch (IOException
        | IllegalArgumentException
        | IllegalStateException
        | ArithmeticException
        | org.snakeyaml.engine.v2.exceptions.YamlEngineException e) {
      return OptionalValue.none();
    }
  }

  private static void depth(int depth, EvalContext ctx) {
    ctx.tick();
    if (depth > ctx.environment.maxDepth)
      throw Values.error(CelException.Code.RESOURCE_EXHAUSTED, "encoder depth limit exceeded");
  }

  private static Object number(String text) {
    try {
      return Long.parseLong(text);
    } catch (NumberFormatException e) {
      /* try unsigned */
    }
    try {
      return new UInt(Long.parseUnsignedLong(text));
    } catch (NumberFormatException e) {
      /* try double */
    }
    double result = Double.parseDouble(text);
    if (!Double.isFinite(result)) throw new IllegalArgumentException("non-finite JSON number");
    return result;
  }

  private static Object read(JsonReader reader, EvalContext ctx, int depth) throws IOException {
    depth(depth, ctx);
    return switch (reader.peek()) {
      case NULL -> {
        reader.nextNull();
        yield CelNull.INSTANCE;
      }
      case BOOLEAN -> reader.nextBoolean();
      case STRING -> reader.nextString();
      case NUMBER -> number(reader.nextString());
      case BEGIN_ARRAY -> {
        reader.beginArray();
        var list = new ArrayList<Object>();
        while (reader.hasNext()) list.add(read(reader, ctx, depth + 1));
        reader.endArray();
        yield List.copyOf(list);
      }
      default -> {
        reader.beginObject();
        var map = new LinkedHashMap<Object, Object>();
        while (reader.hasNext()) {
          String key = reader.nextName();
          if (map.containsKey(key)) throw new IllegalArgumentException("duplicate JSON key");
          map.put(key, read(reader, ctx, depth + 1));
        }
        reader.endObject();
        yield Collections.unmodifiableMap(map);
      }
    };
  }

  private static Object yamlValue(Object value, EvalContext ctx, int depth) {
    depth(depth, ctx);
    if (value == null) return CelNull.INSTANCE;
    if (value instanceof Number n) return number(n.toString());
    if (value instanceof String || value instanceof Boolean) return value;
    if (value instanceof List<?> list) {
      var result = new ArrayList<Object>();
      for (Object item : list) result.add(yamlValue(item, ctx, depth + 1));
      return List.copyOf(result);
    }
    if (value instanceof Map<?, ?> map) {
      var result = new LinkedHashMap<Object, Object>();
      for (var entry : map.entrySet()) {
        Object key = entry.getKey();
        if (!(key instanceof String || key instanceof Number || key instanceof Boolean))
          throw new IllegalArgumentException("invalid YAML key");
        String name = key.toString();
        if (result.containsKey(name)) throw new IllegalArgumentException("duplicate YAML key");
        result.put(name, yamlValue(entry.getValue(), ctx, depth + 1));
      }
      return Collections.unmodifiableMap(result);
    }
    throw new IllegalArgumentException("invalid YAML value");
  }

  private static Object convert(Object value, CelType type, EvalContext ctx) {
    ctx.tick();
    if (type.isDyn()) return value;
    // Parsing emits UInt only for positive integers outside the signed-int range.
    if (type.equals(CelType.INT) && value instanceof UInt)
      throw new IllegalArgumentException("JSON integer exceeds the signed-int range");
    if (type.equals(CelType.UINT) && value instanceof Long n && n >= 0) return new UInt(n);
    if (type.equals(CelType.DOUBLE) && value instanceof Long n) return n.doubleValue();
    if (type.equals(CelType.DOUBLE) && value instanceof UInt u)
      return u.toBigInteger().doubleValue();
    if (type.equals(CelType.BYTES) && value instanceof String s)
      return new Bytes(Base64.getDecoder().decode(s.replace("\r", "").replace("\n", "")));
    if ((type.equals(CelType.TIMESTAMP) || type.equals(CelType.DURATION))
        && value instanceof String)
      return Functions.apply(
          type.equals(CelType.TIMESTAMP) ? "timestamp" : "duration",
          new Object[] {value},
          ctx.environment);
    if (type.name().equals("list") && value instanceof List<?> list) {
      var result = new ArrayList<Object>();
      for (Object item : list) result.add(convert(item, Checker.parameter(type, 0), ctx));
      return List.copyOf(result);
    }
    if (type.name().equals("map") && value instanceof Map<?, ?> map) {
      var result = new LinkedHashMap<Object, Object>();
      for (var item : map.entrySet())
        result.put(
            convert(item.getKey(), Checker.parameter(type, 0), ctx),
            convert(item.getValue(), Checker.parameter(type, 1), ctx));
      return Collections.unmodifiableMap(result);
    }
    ObjectType objectType = ctx.environment.objectTypes.get(type.name());
    if (objectType != null && value instanceof Map<?, ?> map) {
      var fields = new LinkedHashMap<String, Object>();
      for (var item : map.entrySet()) {
        String name = (String) item.getKey();
        CelType fieldType = objectType.fields().get(name);
        if (fieldType == null) throw new IllegalArgumentException("unknown object field");
        fields.put(name, convert(item.getValue(), fieldType, ctx));
      }
      return objectType.construct(fields);
    }
    if (type.accepts(Values.type(value))) return value;
    throw new IllegalArgumentException("JSON type mismatch");
  }

  private static Object jsonValue(Object value, EvalContext ctx, int depth) {
    depth(depth, ctx);
    if (value == CelNull.INSTANCE || value instanceof String || value instanceof Boolean)
      return value;
    if (value instanceof Long n)
      return n >= -9007199254740991L && n <= 9007199254740991L ? n : n.toString();
    if (value instanceof UInt u)
      return u.toBigInteger().bitLength() <= 53 ? u.toBigInteger() : u.toString();
    if (value instanceof Double d && Double.isFinite(d)) return d;
    if (value instanceof Bytes b) return Base64.getEncoder().encodeToString(b.toByteArray());
    if (value instanceof java.time.Instant || value instanceof java.time.Duration)
      return Functions.apply("string", new Object[] {value}, ctx.environment);
    if (value instanceof List<?> list) {
      var result = new ArrayList<Object>();
      for (Object item : list) result.add(jsonValue(item, ctx, depth + 1));
      return result;
    }
    if (value instanceof Map<?, ?> map) {
      var result = new TreeMap<String, Object>();
      for (var entry : map.entrySet()) {
        Object key = entry.getKey();
        if (!(key instanceof String
            || key instanceof Long
            || key instanceof UInt
            || key instanceof Boolean)) throw new IllegalArgumentException("invalid JSON map key");
        String name = key.toString();
        if (result.containsKey(name)) throw new IllegalArgumentException("duplicate JSON map key");
        result.put(name, jsonValue(entry.getValue(), ctx, depth + 1));
      }
      return result;
    }
    for (ObjectType type : ctx.environment.runtimeObjectTypes)
      if (type.accepts(value)) {
        var fields = new TreeMap<String, Object>();
        for (String field : type.fields().keySet())
          if (type.has(value, field))
            fields.put(field, jsonValue(type.get(value, field), ctx, depth + 1));
        return fields;
      }
    throw new IllegalArgumentException("value cannot be encoded as JSON");
  }

  /** Serialize the normalized data model, charging output before writing to the destination. */
  static void writeJson(Object value, Writer destination, EvalContext ctx) {
    try {
      var writer = new JsonWriter(new BudgetWriter(destination, ctx));
      write(value, writer);
      writer.flush();
      writer.close();
    } catch (IOException e) {
      throw new IllegalArgumentException(e);
    }
  }

  private static void write(Object value, JsonWriter writer) throws IOException {
    if (value == CelNull.INSTANCE) writer.nullValue();
    else if (value instanceof String s) writer.value(s);
    else if (value instanceof Boolean b) writer.value(b);
    else if (value instanceof Number n) writer.value(n);
    else if (value instanceof List<?> list) {
      writer.beginArray();
      for (Object item : list) write(item, writer);
      writer.endArray();
    } else {
      writer.beginObject();
      for (var entry : ((Map<?, ?>) value).entrySet()) {
        writer.name((String) entry.getKey());
        write(entry.getValue(), writer);
      }
      writer.endObject();
    }
  }

  private static final class BudgetWriter extends Writer {
    private final Writer target;
    private final EvalContext context;

    BudgetWriter(Writer target, EvalContext context) {
      this.target = target;
      this.context = context;
    }

    public void write(char[] chars, int offset, int count) throws IOException {
      context.charge(count);
      target.write(chars, offset, count);
    }

    public void flush() throws IOException {
      target.flush();
    }

    public void close() throws IOException {
      target.close();
    }
  }
}
