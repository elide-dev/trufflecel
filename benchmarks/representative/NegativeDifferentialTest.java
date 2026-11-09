package dev.trufflecel;

import static org.junit.Assert.*;

import com.google.protobuf.ByteString;
import com.google.protobuf.NullValue;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelContainer;
import dev.cel.common.CelErrorCode;
import dev.cel.common.CelFunctionDecl;
import dev.cel.common.CelOptions;
import dev.cel.common.CelOverloadDecl;
import dev.cel.common.CelValidationException;
import dev.cel.common.types.ListType;
import dev.cel.common.types.MapType;
import dev.cel.common.types.SimpleType;
import dev.cel.common.types.StructTypeReference;
import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerFactory;
import dev.cel.extensions.CelExtensions;
import dev.cel.optimizer.CelOptimizationException;
import dev.cel.optimizer.CelOptimizer;
import dev.cel.optimizer.CelOptimizerFactory;
import dev.cel.optimizer.optimizers.ConstantFoldingOptimizer;
import dev.cel.optimizer.optimizers.SubexpressionOptimizer;
import dev.cel.parser.CelStandardMacro;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.CelFunctionBinding;
import dev.cel.runtime.CelRuntime;
import dev.cel.runtime.CelRuntimeFactory;
import dev.trufflecel.NegativeCases.Case;
import dev.trufflecel.NegativeCases.Config;
import dev.trufflecel.NegativeCases.Phase;
import dev.trufflecel.NegativeCases.Upstream;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/**
 * The shared negative table against cel-java's default and Pratt parsers, with and without its AST
 * optimizers. Outcomes are compared by phase and {@link CelErrorCode}, never by message. This is
 * the only place cel-java is consulted; the table itself has no cel-java dependency.
 */
@RunWith(Parameterized.class)
public final class NegativeDifferentialTest {
  enum Stage {
    PARSE,
    CHECK,
    OPTIMIZE,
    EVALUATE,
    VALUE
  }

  record Engine(String name, boolean pratt, boolean optimized) {
    @Override
    public String toString() {
      return name;
    }
  }

  static final List<Engine> ENGINES =
      List.of(
          new Engine("cel-java", false, false),
          new Engine("cel-java-pratt", true, false),
          new Engine("cel-java-optimized", false, true),
          new Engine("cel-java-pratt-optimized", true, true));

  @Parameterized.Parameters(name = "{0}/{1}")
  public static Collection<Object[]> cases() {
    var result = new ArrayList<Object[]>();
    for (Case c : NegativeCases.all())
      if (c.upstream().applicable()) for (Engine e : ENGINES) result.add(new Object[] {c, e});
    return result;
  }

  private final Case testCase;
  private final Engine engine;

  public NegativeDifferentialTest(Case testCase, Engine engine) {
    this.testCase = testCase;
    this.engine = engine;
  }

  @Test
  public void upstreamOutcomeMatchesRecord() throws Exception {
    Upstream expected = testCase.upstream().forParser(engine.pratt());
    Toolchain tools = toolchain(testCase.config(), engine);
    CelAbstractSyntaxTree ast;
    try {
      ast = tools.compiler.parse(testCase.source()).getAst();
    } catch (CelValidationException e) {
      assertStage(Stage.PARSE, null);
      return;
    }
    try {
      ast = tools.compiler.check(ast).getAst();
    } catch (CelValidationException e) {
      assertStage(Stage.CHECK, null);
      return;
    }
    if (engine.optimized()) {
      try {
        ast = tools.optimizer.optimize(ast);
      } catch (CelOptimizationException e) {
        assertStage(Stage.OPTIMIZE, null);
        return;
      }
    }
    Object value;
    try {
      value = tools.runtime.createProgram(ast).eval(bindings());
    } catch (CelEvaluationException e) {
      assertStage(Stage.EVALUATE, e.getErrorCode());
      return;
    }
    assertStage(Stage.VALUE, null);
    if (expected.kind() == Upstream.Kind.ACCEPTS
        && !testCase.checked().isError()
        && testCase.checked().value() != NegativeCases.ANY)
      assertEquals(
          testCase.source() + " returned " + value.getClass().getName(),
          testCase.checked().value(),
          host(value));
  }

  private void assertStage(Stage actual, CelErrorCode code) {
    Upstream expected = testCase.upstream().forParser(engine.pratt());
    Stage wanted =
        switch (expected.kind()) {
          case ACCEPTS -> Stage.VALUE;
          case REJECTS ->
              expected.folds() && engine.optimized() ? Stage.OPTIMIZE : stage(expected.phase());
          case NOT_APPLICABLE -> throw new AssertionError();
        };
    String context = testCase.id() + " on " + engine + ": " + testCase.source();
    assertEquals(context + " (stage)", wanted, actual);
    if (actual == Stage.EVALUATE && expected.code() != null)
      assertEquals(context + " (category)", CelErrorCode.valueOf(expected.code()), code);
  }

  private static Stage stage(Phase phase) {
    return switch (phase) {
      case PARSE -> Stage.PARSE;
      case CHECK -> Stage.CHECK;
      case EVALUATE -> Stage.EVALUATE;
    };
  }

  private Map<String, Object> bindings() {
    var converted = new LinkedHashMap<String, Object>();
    testCase.bindings().forEach((k, v) -> converted.put(k, upstream(v)));
    return converted;
  }

  /** Convert table bindings to cel-java's native value forms. */
  static Object upstream(Object value) {
    if (value instanceof UInt u)
      return com.google.common.primitives.UnsignedLong.fromLongBits(u.bits());
    if (value instanceof Bytes b) return ByteString.copyFrom(b.toByteArray());
    if (value instanceof Instant t)
      return com.google.protobuf.Timestamp.newBuilder()
          .setSeconds(t.getEpochSecond())
          .setNanos(t.getNano())
          .build();
    if (value instanceof Duration d)
      return com.google.protobuf.Duration.newBuilder()
          .setSeconds(d.getSeconds())
          .setNanos(d.getNano())
          .build();
    if (value == CelNull.INSTANCE) return NullValue.NULL_VALUE;
    if (value instanceof ProtoSchema.Message m) return ProductionCorpus.message(m);
    if (value instanceof List<?> l)
      return l.stream().map(NegativeDifferentialTest::upstream).toList();
    if (value instanceof Map<?, ?> m) {
      var r = new LinkedHashMap<Object, Object>();
      m.forEach((k, v) -> r.put(upstream(k), upstream(v)));
      return r;
    }
    if (value instanceof Unknown) throw new AssertionError("unknowns are not differential");
    return value;
  }

  /** Normalize cel-java scalars to the host forms the table records. */
  static Object host(Object value) {
    if (value instanceof NullValue || value instanceof dev.cel.common.values.NullValue) return null;
    if (value instanceof com.google.common.primitives.UnsignedLong u)
      return new UInt(u.longValue());
    return value;
  }

  record Toolchain(CelCompiler compiler, CelRuntime runtime, CelOptimizer optimizer) {}

  private static final Map<String, Toolchain> TOOLCHAINS = new HashMap<>();

  static synchronized Toolchain toolchain(Config config, Engine engine) {
    return TOOLCHAINS.computeIfAbsent(
        config.name() + "|" + engine.name(), k -> build(config, engine));
  }

  // Preserve the pinned cel-java options contract in this comparison-only harness.
  @SuppressWarnings("deprecation")
  private static Toolchain build(Config config, Engine engine) {
    var options =
        CelOptions.current()
            .enableHeterogeneousNumericComparisons(true)
            .enableOptionalSyntax(true)
            .enablePrattParser(engine.pratt())
            .build();
    var compiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .setOptions(options)
            .setStandardMacros(
                config.macros() ? CelStandardMacro.values() : new CelStandardMacro[0])
            .addLibraries(
                CelExtensions.bindings(),
                CelExtensions.optional(),
                CelExtensions.lists(),
                CelExtensions.strings(),
                CelExtensions.regex(),
                CelExtensions.math(options),
                CelExtensions.sets(options),
                CelExtensions.comprehensions(),
                CelExtensions.protos())
            .setContainer(CelContainer.ofName(config.container()));
    var runtime =
        CelRuntimeFactory.plannerRuntimeBuilder()
            .setOptions(options)
            .addLibraries(
                CelExtensions.optional(),
                CelExtensions.lists(),
                CelExtensions.strings(),
                CelExtensions.regex(),
                CelExtensions.math(options),
                CelExtensions.sets(options),
                CelExtensions.comprehensions())
            .setContainer(CelContainer.ofName(config.container()));
    // Well-known types are always registered, as TruffleCEL always constructs them; the test
    // messages only when the configuration declares them.
    var descriptors =
        new ArrayList<>(
            List.of(
                com.google.protobuf.Int32Value.getDescriptor(),
                com.google.protobuf.UInt32Value.getDescriptor(),
                com.google.protobuf.Int64Value.getDescriptor(),
                com.google.protobuf.UInt64Value.getDescriptor(),
                com.google.protobuf.FloatValue.getDescriptor(),
                com.google.protobuf.DoubleValue.getDescriptor(),
                com.google.protobuf.BoolValue.getDescriptor(),
                com.google.protobuf.StringValue.getDescriptor(),
                com.google.protobuf.BytesValue.getDescriptor(),
                com.google.protobuf.Duration.getDescriptor(),
                com.google.protobuf.Timestamp.getDescriptor(),
                com.google.protobuf.Any.getDescriptor(),
                com.google.protobuf.Struct.getDescriptor(),
                com.google.protobuf.Value.getDescriptor(),
                com.google.protobuf.ListValue.getDescriptor()));
    if (config.protobuf()) {
      descriptors.add(dev.cel.expr.conformance.proto3.TestAllTypes.getDescriptor());
      descriptors.add(dev.cel.expr.conformance.proto2.TestAllTypes.getDescriptor());
    }
    compiler.addMessageTypes(descriptors);
    runtime.addMessageTypes(descriptors);
    for (var declaration : config.variables())
      compiler.addVar(declaration.name(), type(declaration.type()));
    for (var fn : config.functions()) {
      String overload = fn.name() + "_overload";
      compiler.addFunctionDeclarations(
          CelFunctionDecl.newFunctionDeclaration(
              fn.name(),
              CelOverloadDecl.newGlobalOverload(
                  overload,
                  type(fn.result()),
                  fn.arguments().stream().map(NegativeDifferentialTest::type).toList())));
      List<Class<?>> classes =
          fn.arguments().stream().map(NegativeDifferentialTest::hostClass).toList();
      NegativeCases.Behavior behavior = fn.behavior();
      runtime.addFunctionBindings(
          CelFunctionBinding.from(
              overload,
              classes,
              args ->
                  switch (behavior) {
                    case IDENTITY -> args[0];
                    case THROWS_ILLEGAL_ARGUMENT ->
                        throw new IllegalArgumentException("host rejected");
                    case THROWS_NO_SUCH_KEY -> throw new CelEvaluationException("host key missing");
                  }));
    }
    CelCompiler builtCompiler = compiler.build();
    CelRuntime builtRuntime = runtime.build();
    CelOptimizer optimizer =
        engine.optimized()
            ? CelOptimizerFactory.standardCelOptimizerBuilder(builtCompiler, builtRuntime)
                .addAstOptimizers(
                    ConstantFoldingOptimizer.getInstance(), SubexpressionOptimizer.getInstance())
                .build()
            : null;
    return new Toolchain(builtCompiler, builtRuntime, optimizer);
  }

  static dev.cel.common.types.CelType type(CelType type) {
    return switch (type.name()) {
      case "int" -> SimpleType.INT;
      case "uint" -> SimpleType.UINT;
      case "double" -> SimpleType.DOUBLE;
      case "string" -> SimpleType.STRING;
      case "bool" -> SimpleType.BOOL;
      case "bytes" -> SimpleType.BYTES;
      case "dyn" -> SimpleType.DYN;
      case "null_type" -> SimpleType.NULL_TYPE;
      case "google.protobuf.Timestamp" -> SimpleType.TIMESTAMP;
      case "google.protobuf.Duration" -> SimpleType.DURATION;
      case "list" -> ListType.create(type(type.parameters().get(0)));
      case "map" -> MapType.create(type(type.parameters().get(0)), type(type.parameters().get(1)));
      default -> StructTypeReference.create(type.name());
    };
  }

  static Class<?> hostClass(CelType type) {
    return switch (type.name()) {
      case "int" -> Long.class;
      case "string" -> String.class;
      default -> throw new IllegalArgumentException(type.toString());
    };
  }
}
