package dev.trufflecel;

import static org.junit.Assert.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.Test;

public class PartialEvaluationTest {
  private static final AttributePattern SECRET = AttributePattern.of("data").qualify("secret");

  private static PartialActivation data() {
    return PartialActivation.of(Map.of("data", Map.of("known", 3L)), SECRET);
  }

  @Test
  public void fieldsCanBeResolvedWithoutHidingKnownSiblings() {
    assertEquals(3L, Cel.compile("data.known").partialEvaluate(data()).value());
    var program = Cel.compile("data.known + data.secret");
    var partial = program.partialEvaluate(data());
    assertEquals(Set.of("data.secret"), partial.unknownAttributes());
    assertFalse(partial.isComplete());
    assertTrue(partial.actualCost() > 0);
    var complete = partial.advanceEvaluation(Map.of(Attribute.of("data").qualify("secret"), 4L));
    assertTrue(complete.isComplete());
    assertEquals(7L, complete.value());
    assertEquals(Set.of(), complete.unknownAttributes());
    assertEquals(Set.of("data.secret"), partial.unknownAttributes());
    assertEquals(
        8L,
        program
            .advanceEvaluation(
                partial, Map.of(Attribute.of("data"), Map.of("known", 2L, "secret", 6L)))
            .value());
    assertThrows(
        IllegalArgumentException.class,
        () -> Cel.compile("1").advanceEvaluation(partial, Map.of()));
    assertEquals(Unknown.of("data"), Cel.compile("data").partialEvaluate(data()).value());
    assertEquals(Unknown.of("data"), Cel.compile("size(data)").partialEvaluate(data()).value());
    assertEquals(3L, Cel.compile("dyn(data).known").partialEvaluate(data()).value());
  }

  @Test
  public void missingParentsPresenceAndOptionalAccessKeepTheirMeaning() {
    var activation = PartialActivation.of(Map.of(), SECRET);
    assertEquals(
        Unknown.of("data.secret"), Cel.compile("data.secret").partialEvaluate(activation).value());
    assertEquals(
        CelException.Code.UNDECLARED,
        Cel.compile("data.known").partialEvaluate(activation).error().code());
    assertEquals(
        CelException.Code.UNDECLARED,
        Cel.compile("other").partialEvaluate(activation).error().code());
    assertEquals(true, Cel.compile("has(data.known)").partialEvaluate(data()).value());
    assertEquals(false, Cel.compile("has(data.other)").partialEvaluate(data()).value());
    assertEquals(
        Unknown.of("data.secret"), Cel.compile("has(data.secret)").partialEvaluate(data()).value());
    var resolved = data().resolve(Map.of(Attribute.of("data").qualify("secret"), 4L));
    assertEquals(true, Cel.compile("has(data.secret)").partialEvaluate(resolved).value());
    assertEquals(
        OptionalValue.of(4L), Cel.compile("data.?secret").partialEvaluate(resolved).value());
    assertEquals(
        OptionalValue.of(4L), Cel.compile("data[?'secret']").partialEvaluate(resolved).value());
    var root = data().resolve(Map.of(Attribute.of("data"), Map.of()));
    assertEquals(false, Cel.compile("has(data.secret)").partialEvaluate(root).value());
    assertEquals(
        CelException.Code.NO_SUCH_KEY,
        Cel.compile("data.secret").partialEvaluate(root).error().code());
  }

  @Test
  public void wildcardPathsTypedKeysAndDynamicIndices() {
    var activation =
        PartialActivation.of(
            Map.of("rows", List.of(Map.of("name", "a"), Map.of("name", "b")), "idx", 1L),
            AttributePattern.of("rows").wildcard().qualify("secret"));
    assertEquals(
        List.of("a", "b"), Cel.compile("rows.map(r, r.name)").partialEvaluate(activation).value());
    assertEquals(
        Set.of("rows[0].secret", "rows[1].secret"),
        Cel.compile("rows.map(r, r.secret)").partialEvaluate(activation).unknownAttributes());
    assertEquals(
        Unknown.of("rows[1].secret"),
        Cel.compile("rows[idx].secret").partialEvaluate(activation).value());
    assertEquals(
        true,
        Cel.compile("rows.exists(r, r.secret == 1 || r.name == 'a')")
            .partialEvaluate(activation)
            .value());
    assertEquals(
        false,
        Cel.compile("rows.all(r, r.secret == 1 && r.name == 'a')")
            .partialEvaluate(activation)
            .value());
    assertEquals(
        new Unknown(Set.of("rows[0]", "rows[1]")),
        Cel.compile("rows.filter(r, true)").partialEvaluate(activation).value());
    var numeric =
        PartialActivation.of(
            Map.of("m", Map.of(1L, 3L), "k", 1L), AttributePattern.of("m").qualify(new UInt(1)));
    assertEquals(Unknown.of("m[1]"), Cel.compile("m[k]").partialEvaluate(numeric).value());
    assertEquals(
        4L,
        Cel.compile("m[1u]")
            .partialEvaluate(numeric.resolve(Map.of(Attribute.of("m").qualify(1), 4L)))
            .value());
    var bool = PartialActivation.of(Map.of("m", Map.of()), AttributePattern.of("m").qualify(true));
    assertEquals(Unknown.of("m[true]"), Cel.compile("m[true]").partialEvaluate(bool).value());
  }

  @Test
  public void aliasesAndCollectionBindersPreserveQualifiedPaths() {
    assertEquals(3L, Cel.compile("cel.bind(x, data, x.known)").partialEvaluate(data()).value());
    assertEquals(
        6L, Cel.compile("cel.bind(x, data, x.known + x.known)").partialEvaluate(data()).value());
    assertEquals(
        Unknown.of("data.secret"),
        Cel.compile("cel.bind(x, data, x.secret)").partialEvaluate(data()).value());
    assertEquals(
        6L,
        Cel.compile("cel.block([data], cel.index(0).known + cel.index(0).known)")
            .partialEvaluate(data())
            .value());
    assertEquals(3L, Cel.compile("(true ? data : {}).known").partialEvaluate(data()).value());
    var maps =
        PartialActivation.of(
            Map.of("m", Map.of("a", Map.of("known", 3L))),
            AttributePattern.of("m").wildcard().qualify("secret"));
    assertEquals(
        List.of(3L), Cel.compile("m.transformList(k, v, v.known)").partialEvaluate(maps).value());
    assertEquals(
        Unknown.of("m.a.secret"),
        Cel.compile("m.transformList(k, v, v.secret)").partialEvaluate(maps).value());
    assertEquals(List.of("a"), Cel.compile("m.map(k, k)").partialEvaluate(maps).value());
  }

  @Test
  public void partialEvaluationPreservesUnknownErrorPriorityAndBounds() {
    assertEquals(true, Cel.compile("data.secret == 1 || true").partialEvaluate(data()).value());
    assertEquals(false, Cel.compile("data.secret == 1 && false").partialEvaluate(data()).value());
    assertEquals(
        Unknown.of("data.secret"),
        Cel.compile("1/0 == data.secret").partialEvaluate(data()).value());
    var error = Cel.compile("1/0").partialEvaluate(data());
    assertFalse(error.isComplete());
    assertEquals(Set.of(), error.unknownAttributes());
    assertThrows(CelException.class, error::value);
    assertEquals(
        CelException.Code.RESOURCE_EXHAUSTED,
        Cel.compile("data.secret")
            .partialEvaluate(data(), EvaluationOptions.newBuilder().budget(1).build())
            .error()
            .code());
    assertEquals(
        CelException.Code.RESOURCE_EXHAUSTED,
        Cel.compile("data.secret")
            .partialEvaluate(data(), EvaluationOptions.newBuilder().cancelled(() -> true).build())
            .error()
            .code());
  }

  @Test
  public void activationAndResolutionSnapshotsAreIndependent() throws Exception {
    var bindings = new HashMap<String, Object>();
    bindings.put("data", new HashMap<>(Map.of("known", 3L)));
    var activation = PartialActivation.of(bindings, SECRET);
    bindings.clear();
    assertEquals(3L, Cel.compile("data.known").partialEvaluate(activation).value());
    var program = Cel.compile("data.secret");
    try (var executor = Executors.newFixedThreadPool(4)) {
      var work = new ArrayList<Callable<Object>>();
      for (long i = 0; i < 30; i++) {
        long n = i;
        work.add(
            () ->
                program
                    .partialEvaluate(activation)
                    .advanceEvaluation(Map.of(Attribute.of("data").qualify("secret"), n))
                    .value());
      }
      var results = executor.invokeAll(work);
      for (int i = 0; i < results.size(); i++) assertEquals((long) i, results.get(i).get());
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> activation.resolve(Map.of(Attribute.of("other"), 1L)));
    assertThrows(
        IllegalArgumentException.class,
        () -> activation.resolve(Map.of(Attribute.of("data"), Unknown.of("x"))));
    for (Object value :
        List.of(
            List.of(Unknown.of("nested")),
            Map.of("x", Unknown.of("nested")),
            OptionalValue.of(Unknown.of("nested"))))
      assertThrows(
          IllegalArgumentException.class,
          () -> activation.resolve(Map.of(Attribute.of("data"), value)));
    for (Object value : List.of(List.of(1L), OptionalValue.of(1L), OptionalValue.none()))
      assertEquals(
          value,
          program
              .partialEvaluate(activation)
              .advanceEvaluation(Map.of(Attribute.of("data").qualify("secret"), value))
              .value());
    assertEquals(List.of(SECRET), activation.unknownPatterns());
    assertThrows(UnsupportedOperationException.class, () -> activation.unknownPatterns().clear());
  }

  @Test
  public void repeatedAdvancesRefreshCallbacksAndCanResolveSeveralAttributes() {
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    var env =
        Cel.newBuilder()
            .variable("x", CelType.INT)
            .variable("y", CelType.INT)
            .function("count", List.of(), CelType.INT, args -> (long) calls.incrementAndGet())
            .build();
    var activation =
        PartialActivation.of(Map.of(), AttributePattern.of("x"), AttributePattern.of("y"));
    var result = env.compile("count() + x + y").partialEvaluate(activation);
    assertEquals(Set.of("x", "y"), result.unknownAttributes());
    result = result.advanceEvaluation(Map.of(Attribute.of("x"), 10L));
    assertEquals(Set.of("y"), result.unknownAttributes());
    result = result.advanceEvaluation(Map.of(Attribute.of("y"), 20L));
    assertEquals(33L, result.value());
    assertEquals(3, calls.get());
    var parent =
        data()
            .resolve(
                Map.of(Attribute.of("data"), Map.of("known", 2L, "secret", Map.of("deep", 1L))));
    var child = parent.resolve(Map.of(Attribute.of("data").qualify("secret").qualify("deep"), 4L));
    assertEquals(4L, Cel.compile("data.secret.deep").partialEvaluate(child).value());
    assertEquals(2L, Cel.compile("data.known").partialEvaluate(child).value());
    assertEquals(
        5L,
        Cel.compile("data.secret")
            .partialEvaluate(child.resolve(Map.of(Attribute.of("data").qualify("secret"), 5L)))
            .value());
    assertEquals(
        Unknown.of("data.secret"), Cel.compile("data['secret']").partialEvaluate(data()).value());
    assertEquals(
        Unknown.of("data.secret"),
        Cel.compile("has(data.secret)")
            .partialEvaluate(PartialActivation.of(Map.of(), SECRET))
            .value());
    var events = new ArrayList<TraceEvent>();
    assertEquals(
        Unknown.of("data.secret"),
        Cel.compile("data.secret")
            .partialEvaluate(
                PartialActivation.of(Map.of(), SECRET),
                EvaluationOptions.newBuilder().trace(events::add).build())
            .value());
    assertEquals(Unknown.of("data"), events.getFirst().value());
  }

  @Test
  public void resolvedParentsPreserveOptionalReadsAndIteratorOverrides() {
    var missing = data().resolve(Map.of(Attribute.of("data"), Map.of()));
    assertEquals(
        OptionalValue.none(), Cel.compile("data.?secret").partialEvaluate(missing).value());
    assertEquals(
        OptionalValue.none(), Cel.compile("data[?'secret']").partialEvaluate(missing).value());
    var rows =
        PartialActivation.of(
            Map.of("rows", List.of(Map.of("known", 1L))), AttributePattern.of("rows").wildcard());
    var resolved = rows.resolve(Map.of(Attribute.of("rows").qualify(0), Map.of("known", 4L)));
    assertEquals(
        List.of(4L), Cel.compile("rows.map(r, r.known)").partialEvaluate(resolved).value());
    assertEquals(
        List.of(Map.of("known", 4L)),
        Cel.compile("rows.filter(r, true)").partialEvaluate(resolved).value());
    var env = Cel.newBuilder().variable("rows", CelType.DYN).build();
    var canonical =
        CanonicalAst.program(
            env, CanonicalAst.check(env, CanonicalAst.parse(env, "rows.map(r, r.known)")));
    assertEquals(List.of(4L), canonical.partialEvaluate(resolved).value());
    var fields =
        PartialActivation.of(
            Map.of("rows", List.of(Map.of("known", 1L))),
            AttributePattern.of("rows").wildcard().qualify("secret"));
    assertEquals(List.of(1L), canonical.partialEvaluate(fields).value());
    assertEquals(
        Unknown.of("rows[0].secret"),
        CanonicalAst.program(
                env, CanonicalAst.check(env, CanonicalAst.parse(env, "rows.map(r, r.secret)")))
            .partialEvaluate(fields)
            .value());
  }

  @Test
  public void partialCollectionShapesRemainBoundedAndDynamicKeysRetainTypes() {
    var rows =
        PartialActivation.of(
            Map.of("rows", List.of(Map.of("known", 2L), Map.of("known", 1L))),
            AttributePattern.of("rows").wildcard().qualify("secret"));
    assertEquals(
        new Unknown(Set.of("rows[0]", "rows[1]")),
        Cel.compile("rows.sortBy(r, r.known)").partialEvaluate(rows).value());
    assertEquals(
        6L,
        Cel.compile("cel.block([data, data], cel.index(0).known + cel.index(1).known)")
            .partialEvaluate(data())
            .value());
    var missing =
        PartialActivation.of(Map.of(), AttributePattern.of("rows").wildcard().qualify("secret"));
    assertEquals(
        Unknown.of("rows"), Cel.compile("rows.map(r, r.known)").partialEvaluate(missing).value());
    var env = Cel.newBuilder().variable("rows", CelType.DYN).build();
    assertEquals(
        Unknown.of("rows"),
        CanonicalAst.program(
                env, CanonicalAst.check(env, CanonicalAst.parse(env, "rows.map(r, r.known)")))
            .partialEvaluate(missing)
            .value());
    assertEquals(
        CelException.Code.UNDECLARED,
        Cel.compile("rows[1]")
            .partialEvaluate(PartialActivation.of(Map.of(), AttributePattern.of("rows").qualify(0)))
            .error()
            .code());
    var overlap = PartialActivation.of(Map.of(), SECRET, SECRET.qualify("deep"));
    assertEquals(
        new Unknown(Set.of("data.secret", "data.secret.deep")),
        Cel.compile("data.secret.deep").partialEvaluate(overlap).value());
    for (Object key : List.of(1.0, 1.5, Double.POSITIVE_INFINITY, CelNull.INSTANCE)) {
      var input =
          PartialActivation.of(
              Map.of("xs", List.of(1L, 2L), "key", key), AttributePattern.of("xs").qualify(1));
      var result = Cel.compile("xs[key]").partialEvaluate(input);
      if (key.equals(1.0)) assertEquals(Unknown.of("xs[1]"), result.value());
      else assertNotNull(result.error());
    }
    var optional =
        PartialActivation.of(Map.of("data", OptionalValue.of(Map.of())), SECRET)
            .resolve(Map.of(Attribute.of("data").qualify("secret"), 8L));
    assertEquals(
        OptionalValue.of(8L), Cel.compile("data.secret").partialEvaluate(optional).value());
    assertEquals(
        OptionalValue.of(8L), Cel.compile("data['secret']").partialEvaluate(optional).value());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            data()
                .resolve(
                    Map.of(Attribute.of("data"), new CelException(CelException.Code.TYPE, "bad"))));
    var context = new EvalContext(Cel.newBuilder().build(), Map.of());
    context.configure(EvaluationOptions.newBuilder().build().withPartial(data()));
    context.attribute(Attribute.of("data"));
    context.push("local", context.store(Map.of("known", 3L)));
    assertEquals(Map.of("known", 3L), context.visibleBindings().get("local"));
    context.pop();
    assertTrue(context.visibleBindings().isEmpty());
  }

  @Test
  public void overriddenDynIsAnOrdinaryCallbackAndCannotCarryInputAttributes() {
    var env =
        Cel.newBuilder()
            .variable("data", CelType.DYN)
            .nonStrictFunction(
                "dyn",
                "custom_dyn",
                false,
                List.of(CelType.DYN),
                CelType.DYN,
                args -> Map.of("secret", 8L))
            .build();
    assertEquals(8L, env.compile("dyn(data).secret").partialEvaluate(data()).value());
  }

  @Test
  public void attributePatternsAreTypedImmutableAndValidated() {
    assertEquals(
        "v.a['x.y'][1][2u][false]",
        Attribute.of("v")
            .qualify("a")
            .qualify("x.y")
            .qualify(1)
            .qualify(new UInt(2))
            .qualify(false)
            .toString());
    assertEquals("v['a\\'b\\\\c\\n\\r\\t']", Attribute.of("v").qualify("a'b\\c\n\r\t").toString());
    assertThrows(IllegalArgumentException.class, () -> Attribute.of(""));
    assertThrows(IllegalArgumentException.class, () -> AttributePattern.of(""));
    assertThrows(IllegalArgumentException.class, () -> Attribute.of("v").qualify(1.5));
    assertThrows(IllegalArgumentException.class, () -> AttributePattern.of("v").qualify(null));
    assertFalse(SECRET.matches(Attribute.of("data")));
    assertTrue(SECRET.intersects(Attribute.of("data")));
    assertFalse(SECRET.intersects(Attribute.of("data").qualify("known")));
    assertFalse(SECRET.intersects(Attribute.of("other")));
    assertEquals(
        List.of(1L, 2L, 3L), new Attribute("v", List.of((byte) 1, (short) 2, 3)).qualifiers());
    for (Object left : List.of(-1L, 0L, new UInt(-1), new UInt(0), true, "x"))
      for (Object right : List.of(-1L, 0L, new UInt(-1), new UInt(0), false, "y")) {
        boolean expected =
            Objects.equals(left, right)
                || left.equals(0L) && right.equals(new UInt(0))
                || left.equals(new UInt(0)) && right.equals(0L);
        assertEquals(
            expected,
            AttributePattern.of("v").qualify(left).matches(Attribute.of("v").qualify(right)));
      }
  }
}
