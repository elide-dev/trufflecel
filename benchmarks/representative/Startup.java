package dev.trufflecel;

import com.google.gson.Gson;
import com.google.protobuf.Message;
import com.oracle.truffle.runtime.OptimizedCallTarget;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.trufflecel.bench.proto.PolicySchema.Request;
import java.util.*;
import org.graalvm.nativeimage.ImageInfo;

/** External timing ends at the flushed readiness marker, before reporting and context teardown. */
public final class Startup {
  private static final String READY = "TRUFFLECEL_STARTUP_READY";

  public static void main(String[] args) throws Exception {
    if (args.length == 1 && args[0].equals("control")) {
      System.out.println(READY);
      System.out.flush();
      System.out.println("{\"control\":true,\"verified\":true}");
      return;
    }
    if (args.length != 4)
      throw new IllegalArgumentException("engine workload runtime|image input-index");
    long start = System.nanoTime();
    String engine = args[0], workload = args[1], mode = args[2];
    if (!Set.of("truffle-interpreter", "truffle-jit", "cel-java-optimized").contains(engine)
        || !Set.of("runtime", "image").contains(mode)) throw new IllegalArgumentException();
    boolean ours = engine.startsWith("truffle-"), image = mode.equals("image");
    int inputIndex = Integer.parseInt(args[3]);
    if (inputIndex < 0 || inputIndex > 3) throw new IllegalArgumentException("input-index");
    if (ImageInfo.inImageRuntimeCode() && image != StartupImageMode.PREPARED)
      throw new IllegalArgumentException("Wrong native artifact for preparation mode");
    if (image && (!ImageInfo.inImageRuntimeCode() || !StartupPrepared.BUILT_IN_IMAGE))
      throw new IllegalArgumentException("Image preparation requires Native Image");
    String source = Workloads.source(workload);
    var input = new LinkedHashMap<>(Workloads.Raw.INPUTS.get(inputIndex));
    byte[] wire = ((Message) input.get("request")).toByteArray();
    Object expected = Workloads.expected(workload, inputIndex);
    long fixture = System.nanoTime();
    ProtobufType adapter =
        ours ? (image ? StartupPrepared.ADAPTER : new ProtobufType(Request.getDescriptor())) : null;
    long adapted = System.nanoTime();
    input.put("request", ours ? adapter.decode(new Bytes(wire)) : Request.parseFrom(wire));
    Map<String, Object> bindings = workload.equals("scalar") ? Map.of("x", input.get("x")) : input;
    long decoded = System.nanoTime();
    try (var e = new Engines(engine, true, image, adapter)) {
      long configured = System.nanoTime();
      Object ast =
          image
              ? StartupPrepared.AST.get(engine)[Arrays.asList(Workloads.NAMES).indexOf(workload)]
              : e.prepare(source);
      long prepared = System.nanoTime();
      Engines.Evaluation evaluation;
      if (ours) {
        e.truffle = ((PreparedProgram) ast).instantiate();
        evaluation = i -> e.truffle.evaluate(bindings);
      } else {
        var program = e.runtime.createProgram((CelAbstractSyntaxTree) ast);
        evaluation = i -> program.eval(bindings);
      }
      long planned = System.nanoTime();
      Object result = evaluation.run(inputIndex);
      if (!expected.equals(result)) throw new AssertionError("Wrong startup result: " + workload);
      e.verifyTier(false);
      boolean compiled =
          e.truffle != null
              && e.truffle.callTarget() instanceof OptimizedCallTarget t
              && t.isValidLastTier();
      long verified = System.nanoTime();
      System.out.println(READY);
      System.out.flush();
      var out = new LinkedHashMap<String, Object>();
      out.put("control", false);
      out.put("verified", true);
      out.put("runtime", ImageInfo.inImageRuntimeCode() ? "svm" : "jvm");
      out.put("engine", engine);
      out.put("workload", workload);
      out.put("mode", mode);
      out.put("input_index", inputIndex);
      out.put("image_prepared", image);
      out.put("guest_compiled_at_first_result", compiled);
      out.put("fixture_and_wire_ns", fixture - start);
      out.put("adapter_ns", adapted - fixture);
      out.put("decode_ns", decoded - adapted);
      out.put("configure_ns", configured - decoded);
      out.put("prepare_ns", prepared - configured);
      out.put("plan_ns", planned - prepared);
      out.put("evaluate_and_verify_ns", verified - planned);
      out.put("main_to_verified_ns", verified - start);
      System.out.println(new Gson().toJson(out));
    }
  }
}
