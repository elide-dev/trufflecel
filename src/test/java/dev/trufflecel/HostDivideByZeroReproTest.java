package dev.trufflecel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.util.List;
import org.jspecify.annotations.NullUnmarked;
import org.junit.Test;

@NullUnmarked
public class HostDivideByZeroReproTest {

  private static CelException.Code singleOverload(String source) {
    var env =
        Cel.newBuilder()
            .function(
                "d",
                List.of(CelType.INT, CelType.INT),
                CelType.INT,
                a -> (long) a.get(0) / (long) a.get(1))
            .function(
                "m",
                List.of(CelType.INT, CelType.INT),
                CelType.INT,
                a -> (long) a.get(0) % (long) a.get(1))
            .function("overflow", List.of(), CelType.INT, a -> Math.addExact(Long.MAX_VALUE, 1L))
            .function(
                "bare",
                List.of(),
                CelType.INT,
                a -> {
                  throw new ArithmeticException();
                })
            .build();
    return assertThrows(CelException.class, () -> env.compile(source).evaluate()).code();
  }

  private static CelException.Code multiOverload(String source) {
    var env =
        Cel.newBuilder()
            .function(
                "d",
                List.of(CelType.INT, CelType.INT),
                CelType.INT,
                a -> (long) a.get(0) / (long) a.get(1))
            .function("d", List.of(CelType.STRING), CelType.STRING, a -> "x")
            .function(
                "m",
                List.of(CelType.INT, CelType.INT),
                CelType.INT,
                a -> (long) a.get(0) % (long) a.get(1))
            .function("m", List.of(CelType.STRING), CelType.STRING, a -> "x")
            .function("overflow", List.of(), CelType.INT, a -> Math.addExact(Long.MAX_VALUE, 1L))
            .function("overflow", List.of(CelType.STRING), CelType.STRING, a -> "x")
            .function(
                "bare",
                List.of(),
                CelType.INT,
                a -> {
                  throw new ArithmeticException();
                })
            .function("bare", List.of(CelType.STRING), CelType.STRING, a -> "x")
            .build();
    return assertThrows(CelException.class, () -> env.compile(source).evaluate()).code();
  }

  @Test
  public void singleOverloadHostDivideByZeroIsDivideByZero() {
    assertEquals(CelException.Code.DIVIDE_BY_ZERO, singleOverload("d(1, 0)"));
    assertEquals(CelException.Code.DIVIDE_BY_ZERO, singleOverload("m(1, 0)"));
  }

  @Test
  public void multiOverloadHostDivideByZeroIsDivideByZero() {
    assertEquals(CelException.Code.DIVIDE_BY_ZERO, multiOverload("d(1, 0)"));
    assertEquals(CelException.Code.DIVIDE_BY_ZERO, multiOverload("m(1, 0)"));
  }

  @Test
  public void builtinDivideAndModuloByZeroAreDivideByZero() {
    var env = Cel.newBuilder().build();
    assertEquals(
        CelException.Code.DIVIDE_BY_ZERO,
        assertThrows(CelException.class, () -> env.compile("1 / 0").evaluate()).code());
    assertEquals(
        CelException.Code.DIVIDE_BY_ZERO,
        assertThrows(CelException.class, () -> env.compile("1 % 0").evaluate()).code());
  }

  @Test
  public void singleOverloadMessagelessArithmeticExceptionStaysOverflow() {
    assertEquals(CelException.Code.OVERFLOW, singleOverload("bare()"));
    assertEquals(CelException.Code.OVERFLOW, singleOverload("overflow()"));
  }

  @Test
  public void multiOverloadMessagelessArithmeticExceptionStaysOverflow() {
    assertEquals(CelException.Code.OVERFLOW, multiOverload("bare()"));
    assertEquals(CelException.Code.OVERFLOW, multiOverload("overflow()"));
  }
}
