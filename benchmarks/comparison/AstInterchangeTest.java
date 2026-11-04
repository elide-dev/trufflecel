package dev.trufflecel;

import static org.junit.Assert.*;

import dev.cel.common.CelProtoAbstractSyntaxTree;
import dev.cel.expr.CheckedExpr;
import org.junit.Test;

/** Cross-engine wire compatibility, with cel-java confined to comparison-only test targets. */
public final class AstInterchangeTest {
  @Test
  public void canonicalAstInteroperatesInBothDirections() throws Exception {
    for (int i = 0; i < Fixtures.SOURCES.length; i++) {
      String source = Fixtures.SOURCES[i];
      var parsed = CanonicalAst.parse(Fixtures.Truffle.COMPILER, source);
      var checked = CanonicalAst.check(Fixtures.Truffle.COMPILER, parsed);
      var upstreamAst =
          CelProtoAbstractSyntaxTree.fromCheckedExpr(CheckedExpr.parseFrom(checked.toByteArray()))
              .getAst();
      var upstreamProgram = Fixtures.Java.RUNTIME.createProgram(upstreamAst);
      var upstreamChecked =
          CelProtoAbstractSyntaxTree.fromCelAst(Fixtures.Java.COMPILER.compile(source).getAst())
              .toCheckedExpr();
      var imported =
          CanonicalAst.decode("cel.expr.CheckedExpr", new Bytes(upstreamChecked.toByteArray()));
      var program = CanonicalAst.program(Fixtures.Truffle.COMPILER, imported);
      for (var input : Fixtures.inputs(i)) {
        assertEquals(
            Fixtures.Truffle.COMPILER.compile(source).evaluate(input), upstreamProgram.eval(input));
        assertEquals(
            Fixtures.Truffle.COMPILER.compile(source).evaluate(input), program.evaluate(input));
      }
    }
  }
}
