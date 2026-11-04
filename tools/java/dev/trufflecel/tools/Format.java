package dev.trufflecel.tools;

import com.google.googlejavaformat.java.Formatter;
import java.nio.file.*;
import java.util.*;

/** Repository formatter, run under Bazel with --check for CI. */
public final class Format {
  public static void main(String[] args) throws Exception {
    boolean check = Arrays.asList(args).contains("--check");
    Path root = Path.of(System.getenv().getOrDefault("BUILD_WORKSPACE_DIRECTORY", "."));
    var formatter = new Formatter();
    int changed = 0;
    for (String directory :
        List.of(
            "src/main/java",
            "src/test/java",
            "tools/java",
            "tools/release",
            "tools/conformance",
            "benchmarks")) {
      try (var paths = Files.walk(root.resolve(directory))) {
        for (Path path : paths.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
          String before = Files.readString(path), after = formatter.formatSource(before);
          if (!before.equals(after)) {
            changed++;
            System.out.println(root.relativize(path));
            if (!check) Files.writeString(path, after);
          }
        }
      }
    }
    if (check && changed > 0)
      throw new IllegalStateException("Run bazel run //tools:format --config=local");
  }
}
