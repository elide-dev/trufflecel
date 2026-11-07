package dev.trufflecel;

import static org.junit.Assert.*;

import com.google.protobuf.Message;
import dev.trufflecel.NegativeCases.Case;
import dev.trufflecel.NegativeCases.Outcome;
import dev.trufflecel.NegativeCases.Phase;
import java.util.*;
import java.util.function.Supplier;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/**
 * Every negative case must report the same phase and category through the direct frontend, the
 * canonical ParsedExpr/CheckedExpr interchange (including a wire round trip), borrowed-map and
 * snapshot activations, and, for protobuf cases, both the test fixture adapter and the shipped
 * adapter. The strict-validation path records which evaluation failures move to compile time. Guest
 * compilation runs separately in {@code //benchmarks:negative_compiled}.
 */
@RunWith(Parameterized.class)
public class NegativeMatrixTest {
  @Parameterized.Parameters(name = "{0}/{1}")
  public static Collection<Object[]> cases() {
    var result = new ArrayList<Object[]>();
    for (Case c : NegativeCases.all()) {
      result.add(new Object[] {c, "fixture"});
      if (c.config().protobuf()) result.add(new Object[] {c, "production"});
    }
    return result;
  }

  private final Case testCase;
  private final boolean production;

  public NegativeMatrixTest(Case testCase, String adapter) {
    this.testCase = testCase;
    this.production = adapter.equals("production");
  }

  @Test
  public void directChecked() {
    Environment env = testCase.config().environment(production);
    check("direct", testCase.checked(), () -> env.compile(testCase.source()));
  }

  @Test
  public void strictChecked() {
    Environment env = testCase.config().environment(production, true);
    check("strict", testCase.strict(), () -> env.compile(testCase.source()));
  }

  @Test
  public void directUnchecked() {
    Environment env = testCase.config().environment(production);
    // Parse-phase failures and static message-literal validation surface from the unchecked
    // planner; ordinary type errors must not.
    check("direct-unchecked", testCase.unchecked(), () -> env.parse(testCase.source()));
  }

  @Test
  public void canonicalParsed() {
    Environment env = testCase.config().environment(production);
    Outcome expected = testCase.unchecked();
    check(
        "canonical-parsed",
        expected,
        () -> CanonicalAst.program(env, CanonicalAst.parse(env, testCase.source())));
  }

  @Test
  public void canonicalChecked() {
    Environment env = testCase.config().environment(production);
    check(
        "canonical-checked",
        testCase.checked(),
        () -> {
          Message parsed;
          try {
            parsed = CanonicalAst.parse(env, testCase.source());
          } catch (CelException e) {
            // Canonical parsing also plans unchecked, so static literal validation fires here.
            assertTrue(
                "canonical parse rejected a " + testCase.checked().phase() + " case: " + e,
                testCase.checked().phase() == Phase.PARSE
                    || testCase.unchecked().phase() == Phase.CHECK);
            throw e;
          }
          Message checked;
          try {
            checked = CanonicalAst.check(env, parsed);
          } catch (CelException e) {
            assertEquals("canonical check phase", Phase.CHECK, testCase.checked().phase());
            throw e;
          }
          assertFalse(
              "expected a frontend rejection, canonical check accepted " + testCase.source(),
              testCase.checked().phase() == Phase.PARSE
                  || testCase.checked().phase() == Phase.CHECK);
          Message wire =
              CanonicalAst.decode("cel.expr.CheckedExpr", new Bytes(checked.toByteArray()));
          assertEquals(checked, wire);
          return CanonicalAst.program(env, wire);
        });
  }

  @Test
  public void snapshotActivation() {
    Environment env = testCase.config().environment(production);
    Outcome expected = testCase.checked();
    if (expected.isError() && expected.phase() != Phase.EVALUATE) return;
    Program program = env.compile(testCase.source());
    Activation activation = Activation.snapshot(bindings());
    expected.verify("snapshot", Outcome.observe(() -> program.evaluate(activation)));
  }

  private Map<String, Object> bindings() {
    if (!production) return testCase.bindings();
    var converted = new LinkedHashMap<String, Object>();
    testCase.bindings().forEach((k, v) -> converted.put(k, ProductionCorpus.input(v)));
    return converted;
  }

  private void check(String path, Outcome expected, Supplier<Program> plan) {
    Program program;
    try {
      program = plan.get();
    } catch (CelException e) {
      assertTrue(path + " rejected unexpectedly: " + e.getMessage(), expected.isError());
      assertNotEquals(path + " failed before evaluation: " + e, Phase.EVALUATE, expected.phase());
      if (expected.code() != null)
        assertEquals(path + ": " + e.getMessage(), expected.code(), e.code());
      return;
    }
    assertTrue(
        path + " accepted a " + expected.phase() + " rejection: " + testCase.source(),
        !expected.isError() || expected.phase() == Phase.EVALUATE);
    expected.verify(path, Outcome.observe(() -> program.evaluate(bindings())));
    // A second evaluation of the same program must agree: no state leaks between activations.
    expected.verify(path + "/reuse", Outcome.observe(() -> program.evaluate(bindings())));
  }
}
