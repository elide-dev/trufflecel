package dev.trufflecel;

public final class Smoke {
  public static void main(String[] args) throws Exception {
    for (String engine :
        new String[] {
          "truffle-interpreter",
          "truffle-jit",
          "cel-java",
          "cel-java-optimized",
          "cel-java-pratt-optimized"
        }) {
      try (var e = new Engines(engine)) {
        for (String workload : Workloads.NAMES) {
          var evaluate = e.evaluation(workload, e.prepare(Workloads.source(workload)));
          for (int i = 0; i < 4; i++) {
            Object actual = evaluate.run(i);
            if (!Workloads.expected(workload, i).equals(actual))
              throw new AssertionError(engine + "/" + workload + "/" + i + ": " + actual);
          }
          e.peak(evaluate);
          e.verifyTier(true);
        }
      }
    }
    System.out.println(
        "All 120 engine/workload/input combinations agree, including optimized ASTs.");
  }
}
