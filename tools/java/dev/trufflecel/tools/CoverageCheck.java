package dev.trufflecel.tools;

import java.nio.file.*;
import java.util.*;

/** Checks that LCOV includes every owned production source and meets line/branch floors. */
public final class CoverageCheck {
  public static void main(String[] args) throws Exception {
    Path root = Path.of(System.getenv().getOrDefault("BUILD_WORKSPACE_DIRECTORY", "."));
    Path report =
        root.resolve(args.length == 0 ? "bazel-out/_coverage/_coverage_report.dat" : args[0]);
    Set<String> expected = new TreeSet<>(), observed = new TreeSet<>();
    try (var paths = Files.walk(root.resolve("src/main/java"))) {
      paths
          .filter(p -> p.toString().endsWith(".java"))
          .forEach(p -> expected.add(root.relativize(p).toString()));
    }
    long lines = 0, covered = 0, branches = 0, coveredBranches = 0;
    for (String line : Files.readAllLines(report)) {
      if (line.startsWith("SF:")) {
        String path = line.substring(3);
        if (!expected.contains(path))
          throw new IllegalStateException("Unexpected source in coverage: " + path);
        observed.add(path);
      } else if (line.startsWith("DA:")) {
        lines++;
        if (Long.parseLong(line.split(",")[1]) > 0) covered++;
      } else if (line.startsWith("BRDA:")) {
        branches++;
        String count = line.substring(line.lastIndexOf(',') + 1);
        if (!count.equals("-") && Long.parseLong(count) > 0) coveredBranches++;
      }
    }
    if (expected.isEmpty() || !expected.equals(observed)) {
      expected.removeAll(observed);
      throw new IllegalStateException("Unmeasured production sources: " + expected);
    }
    double linePercent = lines == 0 ? 0 : 100.0 * covered / lines;
    double branchPercent = branches == 0 ? 0 : 100.0 * coveredBranches / branches;
    System.out.printf(
        Locale.ROOT,
        "Production coverage: %.2f%% lines (%d/%d), %.2f%% branches (%d/%d)%n",
        linePercent,
        covered,
        lines,
        branchPercent,
        coveredBranches,
        branches);
    if (linePercent < 99.8 || branchPercent < 99.8)
      throw new IllegalStateException("Required coverage: 99.8% lines and 99.8% branches");
  }
}
