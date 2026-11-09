package dev.trufflecel;

import java.util.*;

/**
 * Immutable library versions and function restrictions, shared by checking and dynamic dispatch.
 */
final class LibraryPolicy {
  static final Map<String, Integer> LATEST =
      Map.ofEntries(
          Map.entry("math", 2),
          Map.entry("lists", 2),
          Map.entry("optional", 2),
          Map.entry("strings", 3),
          Map.entry("regex", 0),
          Map.entry("sets", 0),
          Map.entry("encoders", 0),
          Map.entry("json", 0),
          Map.entry("yaml", 0),
          Map.entry("net", 0),
          Map.entry("bindings", 0),
          Map.entry("protos", 0),
          Map.entry("comprehensions", 0));
  final Map<String, Integer> libraries;
  final Set<String> functions;
  private final boolean unrestrictedLibraries;

  LibraryPolicy(Map<String, Integer> libraries, Set<String> functions) {
    this.libraries = Map.copyOf(libraries);
    unrestrictedLibraries = this.libraries.equals(LATEST);
    this.functions = functions == null ? null : Set.copyOf(functions);
    this.libraries.forEach(
        (name, version) -> {
          Integer latest = LATEST.get(name);
          if (latest == null || version < 0 || version > latest)
            throw new IllegalArgumentException(
                "unsupported library version: " + name + ":" + version);
        });
  }

  void name(String name, int offset) {
    if (functions != null && !functions.contains(name)) throw rejection(name, offset);
  }

  void library(String name, CelType receiver, int offset) {
    if (unrestrictedLibraries) return;
    String library = library(name, receiver);
    if (library == null) return;
    int version = libraries.getOrDefault(library, -1);
    int required =
        switch (library) {
          case "math" ->
              name.equals("math.sqrt")
                  ? 2
                  : Set.of("math.greatest", "math.least").contains(name) ? 0 : 1;
          case "lists" -> name.equals("slice") ? 0 : name.equals("flatten") ? 1 : 2;
          case "optional" ->
              Set.of("first", "last").contains(name) ? 2 : name.equals("optFlatMap") ? 1 : 0;
          case "strings" ->
              name.equals("reverse")
                  ? 3
                  : name.equals("join")
                      ? 2
                      : Set.of("format", "strings.quote").contains(name) ? 1 : 0;
          default -> 0;
        };
    if (version < required) throw rejection(name, offset);
  }

  private String library(String name, CelType receiver) {
    if (name.startsWith("math.")) return "math";
    if (name.startsWith("regex.")) return "regex";
    if (name.startsWith("sets.")) return "sets";
    if (name.startsWith("base64.")) return "encoders";
    if (name.startsWith("json.")) return "json";
    if (name.startsWith("yaml.")) return "yaml";
    if (name.startsWith("optional.")
        || Set.of("value", "hasValue", "or", "orValue", "optMap", "optFlatMap", "first", "last")
            .contains(name)) return "optional";
    if (name.equals("reverse")) {
      if (receiver.isDyn()) return libraries.getOrDefault("strings", -1) >= 3 ? "strings" : "lists";
      return receiver.name().equals("list") ? "lists" : "strings";
    }
    if (name.equals("lists.range")
        || Set.of("slice", "flatten", "distinct", "sort", "sortBy", "@sortByAssociatedKeys")
            .contains(name)) return "lists";
    if (name.startsWith("strings.")
        || Set.of(
                "charAt",
                "indexOf",
                "lastIndexOf",
                "lowerAscii",
                "upperAscii",
                "replace",
                "split",
                "substring",
                "trim",
                "join",
                "format")
            .contains(name)) return "strings";
    if (NetworkValue.supports(name)) return "net";
    if (name.equals("cel.bind")
        || name.equals("cel.block")
        || name.equals("cel.index")
        || name.equals("cel.iterVar")) return "bindings";
    if (name.startsWith("proto.")) return "protos";
    if (name.startsWith("transform") || name.equals("cel.@mapInsert")) return "comprehensions";
    return null;
  }

  private static CelException rejection(String name, int offset) {
    return new CelException(CelException.Code.UNDECLARED, "function is disabled: " + name, offset);
  }
}
