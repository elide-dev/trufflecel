package dev.trufflecel;

import com.google.common.collect.ImmutableMap;
import com.oracle.truffle.runtime.OptimizedCallTarget;
import com.oracle.truffle.runtime.OptimizedRuntimeOptions;
import dev.cel.common.CelOptions;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.types.*;
import dev.cel.compiler.*;
import dev.cel.runtime.*;
import dev.cel.parser.CelStandardMacro;
import dev.cel.optimizer.*;
import dev.cel.optimizer.optimizers.*;
import dev.cel.extensions.CelExtensions;
import dev.trufflecel.bench.proto.PolicySchema.Request;
import java.util.*;
import org.graalvm.polyglot.Context;

/** Runtime configurations are explicit; compiler/runtime objects are reused between expressions. */
final class Engines implements AutoCloseable {
  interface Evaluation {
    Object run(int input) throws Exception;
  }

  final String engine;
  final boolean ours;
  final boolean optimized;
  final Environment environment;
  final CelCompiler compiler;
  final CelRuntime runtime;
  final CelOptimizer optimizer;
  final Context context;
  Program truffle;

  Engines(String engine) {
    this(engine, true);
  }

  Engines(String engine, boolean enterContext) {
    this(engine, enterContext, false);
  }

  Engines(String engine, boolean enterContext, boolean imagePrepared) {
    this(engine, enterContext, imagePrepared, null);
  }

  Engines(String engine, boolean enterContext, boolean imagePrepared, ProtobufType adapter) {
    this.engine = engine;
    ours = engine.startsWith("truffle-");
    optimized = engine.contains("optimized");
    if (!List.of(
            "truffle-interpreter",
            "truffle-jit",
            "cel-java",
            "cel-java-optimized",
            "cel-java-prepared-map",
            "cel-java-optimized-prepared-map",
            "cel-java-immutable",
            "cel-java-optimized-immutable",
            "cel-java-pratt-optimized")
        .contains(engine)) throw new IllegalArgumentException(engine);
    if (imagePrepared && (engine.endsWith("-immutable") || engine.endsWith("-prepared-map")))
      throw new IllegalArgumentException("Input control requires runtime preparation");
    if (ours) {
      context =
          !enterContext
              ? null
              : Context.newBuilder("cel")
                  .allowExperimentalOptions(true)
                  .option("engine.Compilation", engine.equals("truffle-jit") ? "true" : "false")
                  .option("engine.BackgroundCompilation", "false")
                  .option("engine.CompilationFailureAction", "Throw")
                  .option("compiler.TreatPerformanceWarningsAsErrors", "all")
                  .option("compiler.MaximumGraalGraphSize", "10000")
                  .build();
      if (context != null) {
        context.initialize("cel");
        context.enter();
      }
      environment =
          imagePrepared
              ? null
              : (adapter == null ? Workloads.Native.ADAPTER : adapter)
                  .register(Cel.newBuilder())
                  .variable("x", CelType.INT)
                  .variable("email", CelType.STRING)
                  .variable("labels", CelType.map(CelType.STRING, CelType.STRING))
                  .variable("xs", CelType.list(CelType.INT))
                  .variable("request", CelType.of("benchmark.Request"))
                  .build();
      compiler = null;
      runtime = null;
      optimizer = null;
    } else {
      context = null;
      environment = null;
      var options =
          CelOptions.current()
              .enableHeterogeneousNumericComparisons(true)
              .enablePrattParser(engine.contains("pratt"))
              .build();
      compiler =
          imagePrepared
              ? null
              : CelCompilerFactory.standardCelCompilerBuilder()
                  .setOptions(options)
                  .setStandardMacros(CelStandardMacro.values())
                  .addLibraries(CelExtensions.bindings())
                  .addMessageTypes(Request.getDescriptor())
                  .addVar("x", SimpleType.INT)
                  .addVar("email", SimpleType.STRING)
                  .addVar("labels", MapType.create(SimpleType.STRING, SimpleType.STRING))
                  .addVar("xs", ListType.create(SimpleType.INT))
                  .addVar("request", StructTypeReference.create("benchmark.Request"))
                  .build();
      runtime =
          CelRuntimeFactory.plannerRuntimeBuilder()
              .setOptions(options)
              .addMessageTypes(Request.getDescriptor())
              .build();
      optimizer =
          imagePrepared
              ? null
              : CelOptimizerFactory.standardCelOptimizerBuilder(compiler, runtime)
                  .addAstOptimizers(
                      ConstantFoldingOptimizer.getInstance(), SubexpressionOptimizer.getInstance())
                  .build();
    }
  }

  Object prepare(String source) throws Exception {
    if (ours) return environment.prepare(source);
    var ast = compiler.compile(source).getAst();
    return optimized ? optimizer.optimize(ast) : ast;
  }

  Object compile(String source) throws Exception {
    Object ast = prepare(source);
    return ours
        ? ((PreparedProgram) ast).instantiate()
        : runtime.createProgram((CelAbstractSyntaxTree) ast);
  }

  Evaluation evaluation(String workload, Object ast) throws Exception {
    if (ours) {
      truffle = ((PreparedProgram) ast).instantiate();
      return i -> truffle.evaluate(Workloads.input(workload, true, i));
    }
    var program = runtime.createProgram((CelAbstractSyntaxTree) ast);
    if (engine.endsWith("-immutable") || engine.endsWith("-prepared-map")) {
      // Caller input preparation is outside steady-state timing and allocation intervals.
      // Preserve every binding and value; do not trim inputs to referenced variables.
      boolean immutable = engine.endsWith("-immutable");
      @SuppressWarnings("unchecked")
      Map<String, Object>[] inputs = (Map<String, Object>[]) new Map<?, ?>[4];
      for (int i = 0; i < 4; i++) {
        Map<String, Object> original = Workloads.input(workload, false, i);
        Map<String, Object> prepared = immutable ? ImmutableMap.copyOf(original) : original;
        if ((prepared instanceof ImmutableMap) != immutable
            || prepared.size() != 69
            || !prepared.equals(original)) throw new AssertionError("Changed prepared bindings");
        for (var entry : original.entrySet())
          if (prepared.get(entry.getKey()) != entry.getValue())
            throw new AssertionError("Prepared input changed a bound value");
        inputs[i] = prepared;
      }
      return i -> program.eval(inputs[i & 3]);
    }
    return i -> program.eval(Workloads.input(workload, false, i));
  }

  void peak(Evaluation evaluation) throws Exception {
    if (!engine.equals("truffle-jit")) return;
    for (int i = 0; i < 2000; i++) evaluation.run(i);
    var target = (OptimizedCallTarget) truffle.callTarget();
    target.compile(true);
    target.waitForCompilation();
    if (!target.isValidLastTier()) throw new AssertionError("Guest compilation failed");
  }

  void verifyTier(boolean peak) {
    if (truffle == null || !(truffle.callTarget() instanceof OptimizedCallTarget t)) return;
    if (t.getOptionValue(OptimizedRuntimeOptions.Compilation) != engine.equals("truffle-jit")
        || t.getOptionValue(OptimizedRuntimeOptions.BackgroundCompilation)
        || t.getOptionValue(OptimizedRuntimeOptions.CompilationFailureAction)
            != OptimizedRuntimeOptions.ExceptionAction.Throw)
      throw new AssertionError("Target uses incorrect benchmark engine options");
    if (!"all".equals(t.getCompilerOptions().get("compiler.TreatPerformanceWarningsAsErrors")))
      throw new AssertionError("Compiler performance warning gate is missing");
    if (engine.equals("truffle-interpreter") && t.isValid())
      throw new AssertionError("Unexpected guest JIT");
    if (peak && engine.equals("truffle-jit") && !t.isValidLastTier())
      throw new AssertionError("Guest code invalidated");
  }

  Map<String, Object> qualifyRegex(String workload) {
    return ours ? RegexQualification.verify(truffle, workload) : Map.of("backend", "cel-java");
  }

  public void close() {
    if (context != null) {
      context.leave();
      context.close();
    }
  }
}
