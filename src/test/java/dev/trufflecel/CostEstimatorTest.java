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
    assertThrows(
        IllegalArgumentException.class, () -> CostEstimator.estimate(env, "1", Map.of("xs", -1L)));
    assertThrows(
        IllegalArgumentException.class, () -> CostEstimator.estimate(env, "1", Map.of("", 1L)));
    assertThrows(
        CelException.class,
        () -> CostEstimator.estimate(Cel.newBuilder().maxSourceLength(1).build(), "1+1"));
  }
}
