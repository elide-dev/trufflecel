// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.trufflecel;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

/** Bounded, structured inputs checked against independent Java evaluation. */
public final class EvaluationFuzzTarget {
  private static final Environment ENVIRONMENT =
      Cel.newBuilder()
          .inferVariables(true)
          .maxSourceLength(4096)
          .maxAstNodes(2048)
          .maxDepth(64)
          .evaluationBudget(10000)
          .build();
  private static final Program ARITHMETIC = ENVIRONMENT.compile("(a + b) * (a - b)");
  private static final Program LOGIC = ENVIRONMENT.compile("a < b ? flag : !flag");
  private static final Program COLLECTION =
      ENVIRONMENT.compile("xs.filter(x, x < a).map(x, x + b)");
  private static final Program MEMBERSHIP = ENVIRONMENT.compile("a in xs");
  private static final Program GUARDED_DIVISION = ENVIRONMENT.compile("b == 0 || a / b == q");

  public static void fuzzerTestOneInput(FuzzedDataProvider data) {
    long a = data.consumeInt(-10000, 10000);
    long b = data.consumeInt(-10000, 10000);
    boolean flag = data.consumeBoolean();
    int length = data.consumeInt(0, 32);
    var values = new ArrayList<Long>(length);
    for (int i = 0; i < length; i++) values.add((long) data.consumeInt(-10000, 10000));
    verify(a, b, flag, values);
  }

  private static void verify(long a, long b, boolean flag, List<Long> values) {
    var bindings =
        Map.<String, Object>of(
            "a", a, "b", b, "flag", flag, "xs", values, "q", b == 0 ? 0L : a / b);
    Activation snapshot = Activation.snapshot(bindings);
    var filtered = new ArrayList<Long>();
    for (long value : values) if (value < a) filtered.add(value + b);
    check(ARITHMETIC, bindings, snapshot, (a + b) * (a - b));
    check(LOGIC, bindings, snapshot, a < b ? flag : !flag);
    check(COLLECTION, bindings, snapshot, filtered);
    check(MEMBERSHIP, bindings, snapshot, values.contains(a));
    // When b is zero, CEL must short circuit without a division error.
    check(GUARDED_DIVISION, bindings, snapshot, true);
  }

  private static void check(
      Program program, Map<String, Object> bindings, Activation snapshot, Object expected) {
    Object borrowed = program.evaluate(bindings);
    Object owned = program.evaluate(snapshot);
    if (!Objects.equals(expected, borrowed) || !Objects.equals(expected, owned)) {
      throw new AssertionError(
          "Evaluation mismatch: expected="
              + expected
              + ", borrowed="
              + borrowed
              + ", snapshot="
              + owned
              + ", bindings="
              + bindings);
    }
  }

  public static void main(String[] args) {
    for (long a : new long[] {-10000, -1, 0, 1, 10000}) {
      for (long b : new long[] {-10000, -1, 0, 1, 10000}) {
        for (boolean flag : new boolean[] {false, true}) {
          verify(a, b, flag, List.of());
          verify(a, b, flag, List.of(-10000L, a, b, 0L, 10000L));
        }
      }
    }
    var random = new Random(20261109);
    for (int i = 0; i < 1000; i++) {
      var values = new ArrayList<Long>();
      int length = random.nextInt(33);
      for (int j = 0; j < length; j++) values.add((long) random.nextInt(-10000, 10001));
      verify(
          random.nextInt(-10000, 10001),
          random.nextInt(-10000, 10001),
          random.nextBoolean(),
          values);
    }
    System.out.println("Structured evaluation oracle regressions passed");
  }
}
