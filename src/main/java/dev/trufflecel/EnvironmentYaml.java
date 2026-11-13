package dev.trufflecel;

import static dev.trufflecel.ConfigurationData.*;

import java.util.*;

/** Data-only CEL environment declarations; host implementations and adapters stay explicit. */
public final class EnvironmentYaml {
  private static final Map<String, Object> DEFAULT_CONSTANTS = Cel.newBuilder().build().constants;

  private EnvironmentYaml() {}

  public static Environment read(String yaml) {
    return read(yaml, Cel.newBuilder());
  }

  /** Add declarations to a builder whose object adapters have been explicitly registered. */
  public static Environment read(String yaml, Environment.Builder builder) {
    Map<String, Object> data = ConfigurationData.read(yaml);
    keys(
        data,
        "name",
        "description",
        "container",
        "context_variable",
        "variables",
        "functions",
        "extensions",
        "stdlib",
        "features",
        "limits",
        "result_type");
    for (String metadata : List.of("name", "description"))
      if (data.containsKey(metadata)) string(data.get(metadata));
    if (data.containsKey("container")) {
      Object value = data.get("container");
      if (value instanceof String name) builder.container(name);
      else {
        var container = object(value);
        keys(container, "name", "abbreviations", "aliases");
        builder.container(string(container.getOrDefault("name", "")));
        for (Object abbreviation : list(container, "abbreviations"))
          builder.abbreviations(string(abbreviation));
        for (Object valueAlias : list(container, "aliases")) {
          var alias = object(valueAlias);
          keys(alias, "alias", "qualified_name");
          builder.alias(string(alias.get("alias")), string(alias.get("qualified_name")));
        }
      }
    }
    var names = new HashSet<String>();
    for (Object item : list(data, "variables")) {
      var variable = object(item);
      keys(variable, "name", "description", "type", "type_name", "params", "is_type_param");
      if (variable.containsKey("description")) string(variable.get("description"));
      String name = string(variable.get("name"));
      if (!names.add(name)) throw new IllegalArgumentException("duplicate variable: " + name);
      var declaredType = new LinkedHashMap<>(variable);
      declaredType.remove("name");
      declaredType.remove("description");
      builder.variable(name, type(declaredType));
    }
    if (data.containsKey("context_variable")) {
      CelType contextType = type(data.get("context_variable"));
      ObjectType adapter = builder.build().objectTypes.get(contextType.name());
      if (adapter == null)
        throw new IllegalArgumentException("unregistered context type: " + contextType);
      adapter
          .fields()
          .forEach((name, fieldType) -> {
            if (!names.add(name))
              throw new IllegalArgumentException("duplicate context variable: " + name);
            builder.variable(name, fieldType);
          });
    }
    for (Object item : list(data, "functions")) {
      var function = object(item);
      keys(function, "name", "description", "overloads");
      if (function.containsKey("description")) string(function.get("description"));
      String name = operator(string(function.get("name")));
      List<?> overloads = list(function, "overloads");
      if (overloads.isEmpty())
        throw new IllegalArgumentException("function has no overloads: " + name);
      for (Object entry : overloads) {
        var overload = object(entry);
        keys(overload, "id", "target", "args", "return", "non_strict");
        var arguments = new ArrayList<CelType>();
        boolean receiver = overload.containsKey("target");
        if (receiver) arguments.add(type(overload.get("target")));
        for (Object arg : list(overload, "args")) arguments.add(type(arg));
        builder.lateFunction(
            name,
            string(overload.get("id")),
            receiver,
            arguments,
            type(overload.get("return")),
            bool(overload.getOrDefault("non_strict", false)));
      }
    }
    if (data.containsKey("extensions")) {
      var libraries = new LinkedHashMap<String, Integer>();
      for (Object item : list(data, "extensions")) {
        var library = object(item);
        keys(library, "name", "version");
        String name = string(library.get("name"));
        if (name.equals("two-var-comprehensions")) name = "comprehensions";
        Object version = library.getOrDefault("version", 0L);
        Integer number =
            version.equals("latest")
                ? LibraryPolicy.LATEST.get(name)
                : Integer.valueOf(integer(version));
        if (number == null || libraries.putIfAbsent(name, number) != null)
          throw new IllegalArgumentException("unknown or repeated extension: " + name);
      }
      builder.libraries(libraries);
    }
    if (data.containsKey("stdlib")) {
      var stdlib = object(data.get("stdlib"));
      keys(
          stdlib,
          "disabled",
          "disable_macros",
          "include_functions",
          "include_macros",
          "exclude_macros");
      boolean disabled = bool(stdlib.getOrDefault("disabled", false));
      if (stdlib.containsKey("include_functions") || disabled) {
        var functions = new LinkedHashSet<String>();
        for (Object entry : list(stdlib, "include_functions")) {
          var function = object(entry);
          keys(function, "name");
          functions.add(operator(string(function.get("name"))));
        }
        builder.allowedFunctions(functions);
      }
      boolean noMacros = bool(stdlib.getOrDefault("disable_macros", false));
      if (noMacros
          || stdlib.containsKey("include_macros")
          || stdlib.containsKey("exclude_macros")) {
        var macros = new LinkedHashSet<String>(Macro.STANDARD);
        if (stdlib.containsKey("include_macros")) {
          macros.removeAll(
              Set.of("has", "all", "exists", "exists_one", "existsOne", "map", "filter"));
          for (Object macro : list(stdlib, "include_macros")) macros.add(string(macro));
        }
        if (noMacros)
          macros.removeAll(
              Set.of("has", "all", "exists", "exists_one", "existsOne", "map", "filter"));
        for (Object macro : list(stdlib, "exclude_macros")) {
          String name = string(macro);
          if (!Macro.STANDARD.contains(name))
            throw new IllegalArgumentException("unknown standard macro: " + name);
          macros.remove(name);
        }
        builder.standardMacros(macros);
      }
    }
    for (Object item : list(data, "features")) {
      var feature = object(item);
      keys(feature, "name", "enabled");
      String name = string(feature.get("name"));
      boolean enabled = bool(feature.get("enabled"));
      switch (name) {
        case "trufflecel.macros" -> builder.macros(enabled);
        case "trufflecel.infer_variables" -> builder.inferVariables(enabled);
        case "trufflecel.homogeneous_literals" -> builder.homogeneousLiterals(enabled);
        case "trufflecel.common_subexpressions" -> builder.commonSubexpressions(enabled);
        case "trufflecel.strict_validation" ->
            builder.validation(
                enabled ? Environment.Validation.STRICT : Environment.Validation.DEFERRED);
        case "cel.feature.macro_call_tracking" -> {
          if (!enabled)
            throw new IllegalArgumentException("macro-call provenance is always retained");
        }
        default -> throw new IllegalArgumentException("unsupported environment feature: " + name);
      }
    }
    for (Object item : list(data, "limits")) {
      var limit = object(item);
      keys(limit, "name", "value");
      String name = string(limit.get("name"));
      if (name.equals("trufflecel.evaluation_budget")) {
        Object budget = limit.get("value");
        if (!(budget instanceof Long value) || value < 0)
          throw new IllegalArgumentException("invalid evaluation budget");
        builder.evaluationBudget(value);
        continue;
      }
      int value = integer(limit.get("value"));
      switch (name) {
        case "cel.limit.expression_code_points" -> builder.maxSourceLength(value);
        case "cel.limit.parse_recursion_depth" -> builder.maxDepth(value);
        case "trufflecel.ast_nodes" -> builder.maxAstNodes(value);
        case "trufflecel.regex_program_size" -> builder.maxRegexProgramSize(value);
        case "trufflecel.regex_parser_tree_size" -> builder.truffleRegexOnly(value);
        case "trufflecel.comprehension_nesting" -> builder.maxComprehensionNesting(value);
        default -> throw new IllegalArgumentException("unsupported environment limit: " + name);
      }
    }
    if (data.containsKey("result_type")) builder.expectedResultType(type(data.get("result_type")));
    return builder.build();
  }

  /** Export declarations, not executable callbacks, constants, or application object adapters. */
  public static String write(Environment environment) {
    if (!environment.constants.equals(DEFAULT_CONSTANTS))
      throw new IllegalArgumentException(
          "host constants cannot be exported as variable declarations");
    if (!environment.customMacros.isEmpty())
      throw new IllegalArgumentException(
          "custom macro callbacks require explicit host registration");
    if (!environment.validators.isEmpty())
      throw new IllegalArgumentException("custom validators require explicit host registration");
    var data = new LinkedHashMap<String, Object>();
    var aliases = new ArrayList<Object>();
    new TreeMap<>(environment.aliases)
        .forEach((alias, name) -> aliases.add(Map.of("alias", alias, "qualified_name", name)));
    data.put("container", Map.of("name", environment.container, "aliases", aliases));
    var variables = new ArrayList<Object>();
    new TreeMap<>(environment.variables)
        .forEach((name, type) -> variables.add(Map.of("name", name, "type", typeData(type))));
    data.put("variables", variables);
    var functions = new ArrayList<Object>();
    new TreeMap<>(environment.overloads)
        .forEach((name, overloads) -> {
          var signatures = new ArrayList<Object>();
          for (var overload : overloads) {
            if (overload.receiver() == null)
              throw new IllegalArgumentException(
                  "function export requires an explicit global/receiver signature: " + name);
            var signature = new LinkedHashMap<String, Object>();
            signature.put("id", overload.id());
            var types = new ArrayList<>(overload.binding().arguments());
            if (overload.receiver()) signature.put("target", typeData(types.removeFirst()));
            signature.put("args", types.stream().map(EnvironmentYaml::typeData).toList());
            signature.put("return", typeData(overload.binding().result()));
            signature.put("non_strict", overload.nonStrict());
            signatures.add(signature);
          }
          functions.add(Map.of("name", name, "overloads", signatures));
        });
    data.put("functions", functions);
    data.put(
        "extensions",
        new TreeMap<>(environment.libraryPolicy.libraries)
            .entrySet().stream()
                .map(entry ->
                    Map.<String, Object>of(
                        "name", entry.getKey(), "version", (long) entry.getValue()))
                .toList());
    var stdlib = new LinkedHashMap<String, Object>();
    // The independent trufflecel.macros feature preserves the global enable flag below.
    if (!environment.standardMacros.equals(Macro.STANDARD)) {
      stdlib.put("include_macros", new ArrayList<>(new TreeSet<>(environment.standardMacros)));
      var excluded = new TreeSet<>(Macro.STANDARD);
      excluded.removeAll(environment.standardMacros);
      stdlib.put("exclude_macros", new ArrayList<>(excluded));
    }
    if (environment.libraryPolicy.functions != null)
      stdlib.put(
          "include_functions",
          new TreeSet<>(environment.libraryPolicy.functions)
              .stream().map(name -> Map.of("name", name)).toList());
    data.put("stdlib", stdlib);
    data.put(
        "features",
        List.of(
            Map.of("name", "trufflecel.infer_variables", "enabled", environment.inferVariables),
            Map.of("name", "trufflecel.macros", "enabled", environment.macros),
            Map.of(
                "name",
                "trufflecel.common_subexpressions",
                "enabled",
                environment.commonSubexpressions),
            Map.of(
                "name",
                "trufflecel.homogeneous_literals",
                "enabled",
                environment.homogeneousLiterals),
            Map.of(
                "name",
                "trufflecel.strict_validation",
                "enabled",
                environment.validation == Environment.Validation.STRICT)));
    var limits =
        new ArrayList<>(
            List.of(
                limit("cel.limit.expression_code_points", environment.maxSourceLength),
                limit("cel.limit.parse_recursion_depth", environment.maxDepth),
                limit("trufflecel.ast_nodes", environment.maxAstNodes),
                environment.regexParserTreeLimit == 0
                    ? limit("trufflecel.regex_program_size", environment.maxRegexProgramSize)
                    : limit("trufflecel.regex_parser_tree_size", environment.regexParserTreeLimit),
                limit("trufflecel.evaluation_budget", environment.budget)));
    if (environment.comprehensionNesting > 0)
      limits.add(limit("trufflecel.comprehension_nesting", environment.comprehensionNesting));
    data.put("limits", limits);
    if (environment.expectedResultType != null)
      data.put("result_type", typeData(environment.expectedResultType));
    return ConfigurationData.write(data);
  }

  private static Map<String, Object> limit(String name, long value) {
    return Map.of("name", name, "value", value);
  }

  static CelType type(Object value) {
    if (value instanceof String text) return TypeSyntax.parse(text);
    var data = object(value);
    keys(data, "type", "type_name", "params", "is_type_param");
    if (data.containsKey("type")) {
      if (data.containsKey("type_name")
          || data.containsKey("params")
          || data.containsKey("is_type_param"))
        throw new IllegalArgumentException("type shorthand cannot be combined with a verbose type");
      return type(data.get("type"));
    }
    String name = string(data.get("type_name"));
    boolean parameter = bool(data.getOrDefault("is_type_param", false));
    var parameters = new ArrayList<CelType>();
    for (Object child : list(data, "params")) parameters.add(type(child));
    if (parameter) {
      if (!parameters.isEmpty() || !name.matches("[A-Za-z_][A-Za-z_0-9]*"))
        throw new IllegalArgumentException("invalid type parameter");
      return CelType.parameter(name);
    }
    if (parameters.isEmpty()) return TypeSyntax.parse(name);
    String rendered =
        name + "<" + String.join(",", parameters.stream().map(TypeSyntax::format).toList()) + ">";
    return TypeSyntax.parse(rendered);
  }

  private static Object typeData(CelType type) {
    return TypeSyntax.format(type);
  }

  private static String operator(String name) {
    return switch (name) {
      case "_+_" -> "+";
      case "_-_" -> "-";
      case "_*_" -> "*";
      case "_/_" -> "/";
      case "_%_" -> "%";
      case "_==_" -> "==";
      case "_!=_" -> "!=";
      case "_<_" -> "<";
      case "_<=_" -> "<=";
      case "_>_" -> ">";
      case "_>=_" -> ">=";
      case "_&&_" -> "&&";
      case "_||_" -> "||";
      case "!_" -> "!";
      case "-_" -> "neg";
      case "_?_:_" -> "?:";
      case "@in" -> "in";
      default -> name;
    };
  }
}
