package dev.trufflecel;

import static dev.trufflecel.CanonicalAst.*;
import static org.junit.Assert.*;

import com.google.protobuf.*;
import java.util.*;
import org.jspecify.annotations.NullMarked;
import org.junit.Test;

/** Runtime container-qualified binding resolution for unchecked/parsed evaluation. */
@NullMarked
public final class ContainerResolutionTest {
  private static Program parsedProgram(String source, String container) {
    Environment environment = Cel.newBuilder().container(container).inferVariables(true).build();
    Message parsed = CanonicalAst.parse(environment, source);
    return CanonicalAst.program(environment, parsed);
  }

  @Test
  public void containerQualifiedBindingWinsOverBareForParsedIdent() {
    Program program = parsedProgram("y", "x");
    assertEquals(Boolean.TRUE, program.evaluateInternal(Map.of("x.y", true, "y", "false")));
    assertEquals(Boolean.TRUE, program.evaluate(Map.of("x.y", true, "y", "false")));
  }

  @Test
  public void containerQualifiedBindingAloneResolvesForParsedIdent() {
    Program program = parsedProgram("y", "x");
    assertEquals(Boolean.TRUE, program.evaluateInternal(Map.of("x.y", true)));
  }

  @Test
  public void bareBindingStillResolvesWhenNoContainerQualifiedKeyExists() {
    Program program = parsedProgram("y", "x");
    assertEquals("false", program.evaluateInternal(Map.of("y", "false")));
  }

  @Test
  public void genuinelyMissingIdentifierStillErrorsAfterContainerWalk() {
    Program program = parsedProgram("y", "x");
    assertEquals(
        CelException.Code.UNDECLARED,
        assertThrows(CelException.class, () -> program.evaluateInternal(Map.of("z", 1))).code());
  }

  @Test
  public void nestedContainerWalksInnermostFirst() {
    Program program = parsedProgram("y", "a.b");
    assertEquals(1L, program.evaluateInternal(Map.of("a.b.y", 1L, "a.y", 2L, "y", 3L)));
    assertEquals(2L, program.evaluateInternal(Map.of("a.y", 2L, "y", 3L)));
    assertEquals(3L, program.evaluateInternal(Map.of("y", 3L)));
    assertEquals(1L, program.evaluateInternal(Map.of("a.b.y", 1L, "y", 3L)));
  }

  @Test
  public void multipleRootsEachResolveThroughContainer() {
    Program program = parsedProgram("y + z", "x");
    assertEquals(3L, program.evaluateInternal(Map.of("x.y", 1L, "x.z", 2L, "y", 10L, "z", 20L)));
  }

  @Test
  public void absoluteReferenceSkipsContainerWalk() {
    Program program = parsedProgram(".y", "x");
    assertEquals(2L, program.evaluateInternal(Map.of("x.y", 1L, "y", 2L)));
  }

  @Test
  public void checkedPathRemainsUnchangedByContainerResolution() {
    Environment env =
        Cel.newBuilder()
            .container("x")
            .variable("x.y", CelType.BOOL)
            .variable("y", CelType.STRING)
            .build();
    Message parsed = CanonicalAst.parse(env, "y");
    Message checked = CanonicalAst.check(env, parsed);
    Program program = CanonicalAst.program(env, checked);
    assertEquals(Boolean.TRUE, program.evaluateInternal(Map.of("x.y", true, "y", "false")));
  }

  @Test
  public void emptyContainerFallsBackToBareNameOnly() {
    Program program = parsedProgram("y", "");
    assertEquals("false", program.evaluateInternal(Map.of("y", "false")));
    assertEquals(
        CelException.Code.UNDECLARED,
        assertThrows(CelException.class, () -> program.evaluateInternal(Map.of("x.y", true)))
            .code());
  }

  @Test
  public void singleEntryActivationResolvesContainerQualifiedKey() {
    Program program = parsedProgram("y", "x");
    assertEquals(Boolean.TRUE, program.evaluate(Activation.snapshot(Map.of("x.y", true))));
    assertEquals("false", program.evaluate(Activation.snapshot(Map.of("y", "false"))));
  }
}
