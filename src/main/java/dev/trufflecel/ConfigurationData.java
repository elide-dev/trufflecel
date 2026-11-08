package dev.trufflecel;

import java.util.*;

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
    return (String) EncoderFunctions.apply("yaml.encode", new Object[] {value}, context());
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
