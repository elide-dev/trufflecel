package dev.trufflecel;

import static dev.trufflecel.ConfigurationData.*;

import java.util.*;

/** Compiles CEL Policy match/aggregate rules into one bounded, reusable CEL program. */
public final class PolicyCompiler {
  private PolicyCompiler() {}

  public static Program compile(Environment environment, String yaml) {
    return prepare(environment, yaml).instantiate();
  }

  /** Policy parsing and type checking can run during Native Image construction. */
  public static PreparedProgram prepare(Environment environment, String yaml) {
    var data = ConfigurationData.read(yaml);
    keys(data, "name", "description", "imports", "rule", "verification");
    String name = string(data.get("name"));
    if (name.isBlank()) throw new IllegalArgumentException("policy name is empty");
    if (data.containsKey("description")) string(data.get("description"));
    var builder = environment.toBuilder();
    for (Object item : list(data, "imports")) {
      var imported = object(item);
      keys(imported, "name");
      String type = string(imported.get("name"));
      if (!environment.objectTypes.containsKey(type)
          && environment.constants.keySet().stream().noneMatch(key -> key.startsWith(type + ".")))
        throw failure("unregistered policy import: " + type);
      builder.abbreviations(type);
    }
    Environment configured = builder.build();
    var compiler = new Compiler(configured);
    Rule rule = compiler.rule(object(data.get("rule")), Map.of(), false, 0);
    ExprNode expression = rule.exhaustive ? call("value", true, rule.expression) : rule.expression;
    PreparedProgram result = configured.prepare("", expression, true);
    compiler.validate();
    if (data.containsKey("verification")) invariants(data);
    return result;
  }

  /** Declared properties are data. Compiling a policy does not prove its invariants. */
  public record Invariant(
      String id, String description, List<String> assumptions, List<String> assertions) {
    public Invariant {
      Objects.requireNonNull(id);
      Objects.requireNonNull(description);
      assumptions = List.copyOf(assumptions);
      assertions = List.copyOf(assertions);
      if (id.isBlank() || assertions.isEmpty())
        throw new IllegalArgumentException("invariant needs an id and assertions");
    }
  }

  public static List<Invariant> invariants(String yaml) {
    return invariants(ConfigurationData.read(yaml));
  }

  private static List<Invariant> invariants(Map<String, Object> data) {
    if (!data.containsKey("verification")) return List.of();
    var verification = object(data.get("verification"));
    keys(verification, "invariants");
    var result = new ArrayList<Invariant>();
    var ids = new HashSet<String>();
    for (Object item : list(verification, "invariants")) {
      var value = object(item);
      keys(value, "id", "description", "assume", "assert");
      String id = string(value.get("id"));
      if (!ids.add(id)) throw new IllegalArgumentException("duplicate invariant: " + id);
      result.add(
          new Invariant(
              id,
              string(value.getOrDefault("description", "")),
              list(value, "assume").stream().map(ConfigurationData::string).toList(),
              list(value, "assert").stream().map(ConfigurationData::string).toList()));
    }
    return List.copyOf(result);
  }

  private record Rule(ExprNode expression, boolean exhaustive) {}

  private record Outcome(ExprNode expression, boolean unwrap) {}

  private record Choice(
      ExprNode condition,
      ExprNode outcome,
      boolean unconditional,
      boolean nested,
      boolean exhaustive) {}

  private static final class Compiler {
    private final Environment environment;
    private final List<List<Outcome>> outputs = new ArrayList<>();
    private final List<ExprNode> conditions = new ArrayList<>(), explanations = new ArrayList<>();
    private final Set<String> ruleIds = new HashSet<>();
    private int nextBinding;

    Compiler(Environment environment) {
      this.environment = environment;
    }

    Rule rule(
        Map<String, Object> data, Map<String, String> outer, boolean insideAggregate, int depth) {
      if (depth > environment.maxDepth) throw failure("policy nesting limit exceeded");
      keys(data, "id", "description", "variables", "match", "aggregate");
      if (data.containsKey("description")) string(data.get("description"));
      if (data.containsKey("id") && !ruleIds.add(string(data.get("id"))))
        throw failure("duplicate rule id");
      boolean aggregate = data.containsKey("aggregate");
      if (aggregate == data.containsKey("match"))
        throw failure("rule requires exactly one of match or aggregate");
      if (aggregate && insideAggregate) throw failure("nested aggregate rules are not allowed");
      var scope = new LinkedHashMap<>(outer);
      var locals = new ArrayList<Map.Entry<String, ExprNode>>();
      var names = new HashSet<String>();
      for (Object item : list(data, "variables")) {
        var variable = object(item);
        keys(variable, "name", "expression", "description");
        String name = string(variable.get("name"));
        if (!name.matches("[A-Za-z_][A-Za-z_0-9]*") || !names.add(name))
          throw failure("invalid or duplicate policy variable: " + name);
        ExprNode initializer = expression(string(variable.get("expression")), scope);
        String internal = "$policy:" + nextBinding++;
        locals.add(Map.entry(internal, initializer));
        scope.put(name, internal);
      }
      List<?> entries = list(data, aggregate ? "aggregate" : "match");
      if (entries.isEmpty()) throw failure("policy rule has no outcomes");
      var choices = new ArrayList<Choice>();
      var outcomes = new ArrayList<Outcome>();
      outputs.add(outcomes);
      boolean exhaustive = aggregate;
      for (int i = 0; i < entries.size(); i++) {
        var entry = object(entries.get(i));
        keys(entry, "condition", "output", "rule", "explanation");
        if (entry.containsKey("output") == entry.containsKey("rule"))
          throw failure("choice requires exactly one output or rule");
        ExprNode condition = expression(string(entry.getOrDefault("condition", "true")), scope);
        conditions.add(condition);
        boolean unconditional =
            condition.kind == ExprNode.Kind.CONSTANT && Boolean.TRUE.equals(condition.value);
        if (entry.containsKey("explanation")) {
          ExprNode explanation = expression(string(entry.get("explanation")), scope);
          explanations.add(explanation);
          locals.add(Map.entry("$explanation:" + nextBinding++, explanation));
        }
        boolean nested = entry.containsKey("rule"), complete = true;
        ExprNode outcome;
        if (nested) {
          Rule child =
              rule(object(entry.get("rule")), scope, insideAggregate || aggregate, depth + 1);
          outcome = child.expression;
          complete = child.exhaustive;
          outcomes.add(new Outcome(outcome, true));
        } else {
          ExprNode output = expression(string(entry.get("output")), scope);
          outcomes.add(new Outcome(output, false));
          outcome = some(output);
        }
        if (!aggregate && unconditional && complete) {
          exhaustive = true;
          if (i != entries.size() - 1)
            throw failure("unreachable choice after an unconditional outcome");
        }
        choices.add(new Choice(condition, outcome, unconditional, nested, complete));
      }
      ExprNode result;
      if (aggregate) {
        var children = new ArrayList<ExprNode>();
        for (Choice choice : choices) {
          ExprNode selected = conditional(choice.condition, choice.outcome, none());
          selected.optionalEntry = true;
          children.add(selected);
        }
        result =
            some(new ExprNode(ExprNode.Kind.LIST, "", null, -1, children.toArray(ExprNode[]::new)));
      } else {
        result = none();
        for (int i = choices.size() - 1; i >= 0; i--) {
          Choice choice = choices.get(i);
          if (choice.unconditional && choice.nested && !choice.exhaustive)
            result = call("or", true, choice.outcome, result);
          else result = conditional(choice.condition, choice.outcome, result);
        }
      }
      for (int i = locals.size() - 1; i >= 0; i--) {
        var local = locals.get(i);
        result = bind(local.getKey(), local.getValue(), result);
      }
      return new Rule(result, exhaustive);
    }

    private ExprNode expression(String source, Map<String, String> variables) {
      if (source.length() > environment.maxSourceLength)
        throw failure("policy expression length limit exceeded");
      try {
        ExprNode parsed = new Parser(source, environment).parse();
        environment.checkDepth(parsed);
        return rewrite(parsed, variables, Set.of());
      } catch (CelException error) {
        throw error.locate(source);
      }
    }

    private ExprNode rewrite(ExprNode node, Map<String, String> variables, Set<String> bound) {
      if (node.kind == ExprNode.Kind.SELECT
          && !bound.contains("variables")
          && node.children[0].kind == ExprNode.Kind.IDENT
          && node.children[0].name.equals("variables")) {
        String name = variables.get(node.name);
        if (name == null) throw failure("undeclared policy variable: " + node.name);
        return new ExprNode(ExprNode.Kind.IDENT, name, null, node.offset);
      }
      if (node.kind == ExprNode.Kind.MACRO && !node.name.equals("has")) {
        node.children[0] = rewrite(node.children[0], variables, bound);
        var inner = new HashSet<>(bound);
        inner.addAll(Arrays.asList((String[]) node.value));
        for (int i = 1; i < node.children.length; i++)
          node.children[i] = rewrite(node.children[i], variables, inner);
      } else {
        for (int i = 0; i < node.children.length; i++)
          node.children[i] = rewrite(node.children[i], variables, bound);
      }
      return node;
    }

    void validate() {
      for (ExprNode condition : conditions) {
        boolean literal = condition.kind == ExprNode.Kind.CONSTANT && condition.value instanceof Boolean;
        if (!literal && !CelType.BOOL.accepts(condition.checkedType))
          throw failure("policy condition must be bool");
        if (literal && Boolean.FALSE.equals(condition.value)
            || condition.fold != null && Boolean.FALSE.equals(condition.fold.value()))
          throw failure("policy condition is statically false");
      }
      for (ExprNode explanation : explanations)
        if (!CelType.STRING.accepts(explanation.checkedType))
          throw failure("policy explanation must be string");
      for (List<Outcome> alternatives : outputs) {
        CelType expected = null;
        for (Outcome outcome : alternatives) {
          CelType type = outcome.expression.checkedType;
          if (outcome.unwrap) type = Checker.parameter(type, 0);
          if (expected != null && !expected.accepts(type))
            throw failure("incompatible policy output types: " + expected + " and " + type);
          expected = expected == null ? type : CelType.join(expected, type);
        }
      }
    }
  }

  private static ExprNode bind(String name, ExprNode initializer, ExprNode body) {
    var node =
        new ExprNode(ExprNode.Kind.MACRO, "bind", new String[] {name}, -1, initializer, body);
    node.bindings = new WriteNode[] {new WriteNode(name, -1, -1)};
    return node;
  }

  private static ExprNode call(String name, boolean receiver, ExprNode... arguments) {
    return new ExprNode(ExprNode.Kind.CALL, name, receiver, -1, arguments);
  }

  private static ExprNode some(ExprNode value) {
    return call("optional.of", false, value);
  }

  private static ExprNode none() {
    return call("optional.none", false);
  }

  private static ExprNode conditional(ExprNode condition, ExprNode yes, ExprNode no) {
    return new ExprNode(ExprNode.Kind.CONDITIONAL, "?:", null, -1, condition, yes, no);
  }

  private static CelException failure(String message) {
    return new CelException(CelException.Code.TYPE, message);
  }
}
