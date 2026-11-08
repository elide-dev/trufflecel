package dev.trufflecel;

import java.util.*;
import java.io.StringWriter;
import org.snakeyaml.engine.v2.api.Dump;
import org.snakeyaml.engine.v2.api.DumpSettings;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.api.StreamDataWriter;
import org.snakeyaml.engine.v2.api.lowlevel.Compose;
import org.snakeyaml.engine.v2.nodes.*;

/** Shared bounded, data-only YAML handling for host configuration tools. */
final class ConfigurationData {
  private static final Environment ENVIRONMENT =
      Cel.newBuilder().maxDepth(64).evaluationBudget(1_000_000).build();

  private ConfigurationData() {}

  static Map<String, Object> read(String yaml) {
    Objects.requireNonNull(yaml);
    if (yaml.length() > 1_000_000)
      throw new IllegalArgumentException("configuration exceeds text limit");
    var parsed =
        (OptionalValue) EncoderFunctions.apply("yaml.parse", new Object[] {yaml}, context());
    if (!parsed.present()) throw new IllegalArgumentException("invalid YAML configuration");
    return object(parsed.value());
  }

  static String write(Map<String, Object> value) {
    var context = context();
    var json = new StringWriter();
    // Configuration integers are exact int64 values, not protobuf JSON string values.
    EncoderFunctions.writeJson(value, json, context);
    Object data = new Load(LoadSettings.builder().build()).loadFromString(json.toString());
    var output = new StringBuilder();
    new Dump(DumpSettings.builder().build())
        .dump(
            data,
            new StreamDataWriter() {
              public void write(String text) {
                write(text, 0, text.length());
              }

              public void write(String text, int offset, int length) {
                context.charge(length);
                output.append(text, offset, offset + length);
              }
            });
    return output.toString();
  }

  record Expression(String text, int offset) {}

  /** Preserve expression block scalars before YAML folding changes CEL comments or literals. */
  static Map<String, Object> policy(String yaml) {
    Map<String, Object> data = read(yaml); // Data-only tags, aliases, depth and work limits first.
    var settings = LoadSettings.builder().setCodePointLimit(1_000_000).build();
    Node syntax = new Compose(settings).composeString(yaml).orElseThrow();
    int[] offsets = new int[yaml.codePointCount(0, yaml.length()) + 1];
    for (int point = 0, character = 0; point < offsets.length; point++) {
      offsets[point] = character;
      if (character < yaml.length()) character += Character.charCount(yaml.codePointAt(character));
    }
    return object(policyValue(data, syntax, yaml, offsets, false));
  }

  private static Object policyValue(
      Object value, Node node, String yaml, int[] offsets, boolean expression) {
    if (node instanceof ScalarNode scalar && expression) {
      String text = string(value);
      int start = offsets[node.getStartMark().orElseThrow().getIndex()];
      int end = offsets[node.getEndMark().orElseThrow().getIndex()];
      var style = scalar.getScalarStyle();
      if (style == org.snakeyaml.engine.v2.common.ScalarStyle.FOLDED
          || style == org.snakeyaml.engine.v2.common.ScalarStyle.LITERAL) {
        int newline = yaml.indexOf('\n', start);
        start = newline < 0 ? end : newline + 1;
        text = yaml.substring(start, end);
      } else if (style == org.snakeyaml.engine.v2.common.ScalarStyle.SINGLE_QUOTED
          || style == org.snakeyaml.engine.v2.common.ScalarStyle.DOUBLE_QUOTED) start++;
      return new Expression(text, start);
    }
    if (node instanceof MappingNode mapping) {
      var result = object(value);
      for (NodeTuple entry : mapping.getValue()) {
        String key = ((ScalarNode) entry.getKeyNode()).getValue();
        result.put(
            key,
            policyValue(
                result.get(key),
                entry.getValueNode(),
                yaml,
                offsets,
                Set.of("expression", "condition", "output", "explanation").contains(key)));
      }
      return result;
    }
    if (node instanceof SequenceNode sequence) {
      var values = list(value);
      var result = new ArrayList<Object>();
      for (int i = 0; i < values.size(); i++)
        result.add(policyValue(values.get(i), sequence.getValue().get(i), yaml, offsets, false));
      return result;
    }
    return value;
  }

  private static EvalContext context() {
    return new EvalContext(ENVIRONMENT, Map.of());
  }

  static Map<String, Object> object(Object value) {
    if (!(value instanceof Map<?, ?> map))
      throw new IllegalArgumentException("expected a configuration mapping");
    var result = new LinkedHashMap<String, Object>();
    for (var entry : map.entrySet()) result.put(string(entry.getKey()), entry.getValue());
    return result;
  }

  static List<?> list(Object value) {
    if (!(value instanceof List<?> list))
      throw new IllegalArgumentException("expected a configuration list");
    return list;
  }

  static List<?> list(Map<String, Object> map, String key) {
    return map.containsKey(key) ? list(map.get(key)) : List.of();
  }

  static String string(Object value) {
    if (!(value instanceof String text))
      throw new IllegalArgumentException("expected a configuration string");
    return text;
  }

  static boolean bool(Object value) {
    if (!(value instanceof Boolean flag))
      throw new IllegalArgumentException("expected a configuration boolean");
    return flag;
  }

  static int integer(Object value) {
    if (!(value instanceof Long number) || number < 0 || number > Integer.MAX_VALUE)
      throw new IllegalArgumentException("expected a nonnegative configuration integer");
    return number.intValue();
  }

  static void keys(Map<String, Object> map, String... allowed) {
    Set<String> valid = Set.of(allowed);
    for (String key : map.keySet())
      if (!valid.contains(key))
        throw new IllegalArgumentException("unknown configuration field: " + key);
  }
}
