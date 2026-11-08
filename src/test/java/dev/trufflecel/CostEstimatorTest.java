package dev.trufflecel;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class CostEstimatorTest {
  @Test
  public void boundsCoverActualChargedOperations() {
    var env =
        Cel.newBuilder()
            .variable("x", CelType.INT)
            .variable("xs", CelType.list(CelType.INT))
            .build();
    var bindings = Map.<String, Object>of("x", 3L, "xs", List.of(1, 2, 3, 4));
    for (String source :
        List.of(
            "1+2",
            "x+2",
            "x > 0 ? x*2 : x/2",
            "xs.map(y, y+x)",
            "xs.filter(y,y>1).size()",
            "xs.all(y, y>0)",
            "xs.sortBy(y,-y)",
            "[x,1].all(y,y>0)",
            "{'a':x}.all(k,true)",
            "cel.bind(y,x+1,y*y)",
            "has({'a':x}.a)",
            "[x,1][0]",
            "optional.of(x).optMap(y, y+1)")) {
      var estimate = CostEstimator.estimate(env, source, Map.of("xs", 4L));
      var actual =
          env.compile(source).evaluateWithDetails(bindings, EvaluationOptions.newBuilder().build());
      assertTrue(source + ": " + estimate, estimate.maximum().isPresent());
      assertTrue(
          source + ": actual=" + actual.actualCost() + ", estimate=" + estimate,
          actual.actualCost() <= estimate.maximum().getAsLong());
      assertEquals(0, estimate.minimum());
      assertEquals(Map.of("xs", 4L), estimate.sizeBounds());
    }
  }

  @Test
  public void unknownWorkIsNotPresentedAsAFiniteUpperBound() {
    var env =
        Cel.newBuilder()
            .variable("xs", CelType.list(CelType.INT))
            .variable("s", CelType.STRING)
            .function("host", List.of(), CelType.INT, args -> 1L)
            .build();
    for (String source :
        List.of(
            "xs.map(x,x+1)",
            "host()",
            "[host(),1]",
            "s.trim()",
            "cel.block([1],cel.index(0))",
            "true ? host() : 1")) {
      var estimate = CostEstimator.estimate(env, source);
      assertFalse(source, estimate.maximum().isPresent());
      assertFalse(estimate.unknowns().isEmpty());
    }
    assertFalse(
        CostEstimator.estimate(env, "xs.map(x,x+1)", Map.of("xs", Long.MAX_VALUE))
            .maximum()
            .isPresent());
    assertFalse(
        CostEstimator.estimate(env, "xs.all(x,true)", Map.of("xs", Long.MAX_VALUE / 2))
            .maximum()
            .isPresent());
    var override =
        Cel.newBuilder().function("size", List.of(CelType.STRING), CelType.INT, a -> 1L).build();
    assertTrue(
        CostEstimator.estimate(override, "size('x')").unknowns().contains("host function: size"));
    assertThrows(
        IllegalArgumentException.class, () -> CostEstimator.estimate(env, "1", Map.of("xs", -1L)));
    assertThrows(
        IllegalArgumentException.class, () -> CostEstimator.estimate(env, "1", Map.of("", 1L)));
    assertThrows(
        CelException.class,
        () -> CostEstimator.estimate(Cel.newBuilder().maxSourceLength(1).build(), "1+1"));
  }

  @Test
  public void localBindingsCannotBorrowActivationSizeGuarantees() {
    var env = Cel.newBuilder().inferVariables(true).build();
    for (String source :
        List.of(
            "xs.map(xs,xs.all(x,true))",
            "rows.all(row,row.items.all(x,true))",
            "cel.bind(xs,[z,2,3],xs.all(x,true))")) {
      var estimate =
          CostEstimator.estimate(env, source, Map.of("xs", 1L, "rows", 1L, "row.items", 1L));
      assertFalse(source, estimate.maximum().isPresent());
      assertTrue(
          source,
          estimate.unknowns().stream().anyMatch(x -> x.startsWith("local collection size:")));
    }
    var outer = CostEstimator.estimate(env, "xs.all(x,ys.all(y,true))", Map.of("xs", 2L, "ys", 3L));
    assertTrue(outer.toString(), outer.maximum().isPresent());
  }

  @Test
  public void literalAndAttributeSizesAndArbitraryComprehensions() {
    Macro sum =
        Macro.global(
            "sum",
            1,
            (c, t, a) ->
                Ast.Node.comprehension(
                    "acc",
                    "item",
                    "",
                    a.get(0),
                    Ast.Node.literal(0),
                    Ast.Node.literal(true),
                    Ast.Node.call("+", Ast.Node.identifier("acc"), Ast.Node.identifier("item")),
                    Ast.Node.identifier("acc")));
    Environment env =
        Cel.newBuilder()
            .variable("x", CelType.INT)
            .variable("xs", CelType.list(CelType.INT))
            .variable("record", CelType.map(CelType.STRING, CelType.list(CelType.INT)))
            .constant("fixedList", List.of(1, 2))
            .constant("fixedMap", Map.of("a", 1))
            .macro(sum)
            .macro(
                Macro.global(
                    "emptyLoop",
                    0,
                    (c, t, a) ->
                        Ast.Node.comprehension(
                            "acc",
                            "item",
                            "",
                            Ast.Node.list(),
                            Ast.Node.identifier("x"),
                            Ast.Node.literal(true),
                            Ast.Node.identifier("acc"),
                            Ast.Node.identifier("acc"))))
            .function("host", List.of(), CelType.BOOL, a -> true)
            .build();
    for (String source :
        List.of(
            "fixedList.all(v,v>x)",
            "fixedMap.all(k,x>0)",
            "record.items.all(x,x>0)",
            "sum(xs)",
            "emptyLoop()",
            "[].all(x,host())",
            "optional.of(x).optFlatMap(v,optional.of(v))")) {
      var estimate = CostEstimator.estimate(env, source, Map.of("xs", 3L, "record.items", 3L));
      var result =
          env.compile(source)
              .evaluateWithDetails(
                  Map.of(
                      "x", 1L, "xs", List.of(1, 2, 3), "record", Map.of("items", List.of(1, 2, 3))),
                  EvaluationOptions.newBuilder().build());
      assertTrue(source + estimate, estimate.maximum().isPresent());
      assertTrue(source + estimate, result.actualCost() <= estimate.maximum().getAsLong());
    }
    for (String source :
        List.of(
            "google.protobuf.Int64Value{value:x}",
            "(x>0 ? xs : []).all(v,true)",
            "x>0 ? 1 : host()")) {
      Environment dynamic =
          Cel.newBuilder()
              .inferVariables(true)
              .function("host", List.of(), CelType.INT, a -> 1L)
              .build();
      assertFalse(source, CostEstimator.estimate(dynamic, source).maximum().isPresent());
    }
  }
}
