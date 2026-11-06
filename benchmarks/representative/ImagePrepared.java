package dev.trufflecel;

import java.util.*;
import org.graalvm.nativeimage.ImageInfo;

/** Checked ASTs and explicit input snapshots survive construction; no guest code is embedded. */
final class ImagePrepared {
  static final boolean BUILT_IN_IMAGE = ImageInfo.inImageBuildtimeCode();
  static final Map<String, Object[]> AST = prepare();
  static final List<Long> MUTABLE_ITEMS = new ArrayList<>(List.of(2L));
  static final byte[] MUTABLE_BYTES = new byte[] {'a'};
  static final Map<String, Object> MUTABLE_INPUT =
      new HashMap<>(Map.of("x", 1L, "items", MUTABLE_ITEMS, "blob", MUTABLE_BYTES));
  static final Activation ACTIVATION = Activation.snapshot(MUTABLE_INPUT);
  static final PreparedProgram SNAPSHOT_PROGRAM =
      Cel.newBuilder()
          .variable("x", CelType.INT)
          .variable("items", CelType.list(CelType.INT))
          .variable("blob", CelType.BYTES)
          .build()
          .prepare("x + items[0] + (blob == b'a' ? 100 : 0)");

  static void verifySnapshot(Engines engine) throws Exception {
    if (!BUILT_IN_IMAGE || !ImageInfo.inImageRuntimeCode())
      throw new AssertionError("Snapshot must cross the image-build/runtime boundary");
    engine.truffle = SNAPSHOT_PROGRAM.instantiate();
    MUTABLE_INPUT.put("x", 10L);
    MUTABLE_ITEMS.set(0, 20L);
    MUTABLE_BYTES[0] = 'b';
    Activation changed = Activation.snapshot(MUTABLE_INPUT);
    // Warm both outcomes and each entry path before requiring valid compiled code.
    Engines.Evaluation evaluation =
        i -> {
          Object result =
              switch (i % 3) {
                case 0 -> engine.truffle.evaluate(ACTIVATION);
                case 1 -> engine.truffle.evaluate(MUTABLE_INPUT);
                default -> engine.truffle.evaluate(changed);
              };
          if (!Long.valueOf(i % 3 == 0 ? 103 : 30).equals(result))
            throw new AssertionError("Build-time activation ownership changed at runtime");
          return result;
        };
    engine.peak(evaluation);
    for (int i = 0; i < 3; i++) evaluation.run(i);
    engine.verifyTier(true);
  }

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
