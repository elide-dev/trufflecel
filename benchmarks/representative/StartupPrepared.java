package dev.trufflecel;

import dev.trufflecel.bench.proto.PolicySchema.Request;
import java.util.*;
import org.graalvm.nativeimage.ImageInfo;

/** Only schemas/adapters and checked ASTs are embedded; runtime input fixtures stay cold. */
final class StartupPrepared {
  static final boolean BUILT_IN_IMAGE = ImageInfo.inImageBuildtimeCode();
  static final ProtobufType ADAPTER = new ProtobufType(Request.getDescriptor());
  static final Map<String, Object[]> AST = prepare();

  private static Map<String, Object[]> prepare() {
    var result = new LinkedHashMap<String, Object[]>();
    for (String engine :
        new String[] {"truffle-interpreter", "truffle-jit", "cel-java-optimized"}) {
      try (var e = new Engines(engine, false, false, ADAPTER)) {
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
