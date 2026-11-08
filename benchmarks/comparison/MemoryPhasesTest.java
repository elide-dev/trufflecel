package dev.trufflecel;

import static org.junit.Assert.*;

import java.lang.management.MemoryUsage;
import org.junit.Test;

public final class MemoryPhasesTest {
  @Test
  public void unavailableNonHeapCounterPreservesItsError() {
    var result =
        MemoryPhases.nonHeapMeasurement(
            () -> {
              throw new IllegalArgumentException("used parameter = -13896 is negative.");
            });
    assertNull(result.used());
    assertEquals(
        "java.lang.IllegalArgumentException: used parameter = -13896 is negative.", result.error());
  }

  @Test
  public void validZeroIsDistinctFromAnUnavailableCounter() {
    var result = MemoryPhases.nonHeapMeasurement(() -> new MemoryUsage(-1, 0, 0, -1));
    assertEquals(Long.valueOf(0), result.used());
    assertNull(result.error());
  }

  @Test
  public void unrelatedDiagnosticFailuresAreNotSuppressed() {
    assertThrows(
        IllegalStateException.class,
        () ->
            MemoryPhases.nonHeapMeasurement(
                () -> {
                  throw new IllegalStateException("broken management bean");
                }));
  }
}
