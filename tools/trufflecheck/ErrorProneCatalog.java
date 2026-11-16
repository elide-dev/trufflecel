/* Copyright (c) 2026 Elide Technologies, Inc. and TruffleCEL contributors
 * SPDX-License-Identifier: Apache-2.0 OR MIT */
import com.google.errorprone.BugCheckerInfo;
import com.google.errorprone.scanner.BuiltInCheckerSuppliers;
import java.util.TreeSet;

/**
 * Prints the canonical names of the bundled Error Prone checks enabled by default at the given severity. Run by
 * list_default_warnings.sh against Bazel's JavaBuilder (a single-file source program, not a build target).
 */
public final class ErrorProneCatalog {
  private ErrorProneCatalog() {}

  public static void main(String[] args) {
    var names = new TreeSet<String>();
    for (BugCheckerInfo check : BuiltInCheckerSuppliers.defaultChecks().getEnabledChecks()) {
      if (check.defaultSeverity().name().equals(args[0])) {
        names.add(check.canonicalName());
      }
    }
    names.forEach(System.out::println);
  }
}
