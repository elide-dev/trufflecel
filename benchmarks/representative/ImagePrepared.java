package dev.trufflecel;

import java.util.*;
import org.graalvm.nativeimage.ImageInfo;

/** Only checked/optimized ASTs survive image construction; no guest machine code is embedded. */
final class ImagePrepared {
  static final boolean BUILT_IN_IMAGE = ImageInfo.inImageBuildtimeCode();
  static final Map<String, Object[]> AST = prepare();

  private static Map<String, Object[]> prepare() {
    var result = new LinkedHashMap<String, Object[]>();
    for (String engine :
        new String[] {
          "truffle-interpreter",
          "truffle-jit",
          "cel-java",
          "cel-java-optimized",
          "cel-java-pratt-optimized"
        }) {
      try (var e = new Engines(engine, false)) {
        var programs = new Object[Workloads.NAMES.length];
        for (int i = 0; i < programs.length; i++) programs[i] = e.prepare(Workloads.SOURCES[i]);
        result.put(engine, programs);
      } catch (Exception ex) {
        throw new ExceptionInInitializerError(ex);
      }
    }
    return Map.copyOf(result);
  }
}
