package dev.trufflecel;

import static org.junit.Assert.*;

import java.util.*;
import java.time.Duration;
import java.time.Instant;
import org.junit.Test;

/**
 * Direct record construction must normalize payloads the same way the {@code of}/{@code none}
 * factories do, so that host-built optionals are canonical at every binding entry point.
 */
public class OptionalValueTest {
  @Test
  public void directPresentConstructionNormalizesScalarPayloads() {
    assertEquals(Long.class, new OptionalValue(true, 1).value().getClass());
    assertEquals(1L, new OptionalValue(true, 1).value());
    assertEquals(1L, new OptionalValue(true, (byte) 1).value());
    assertEquals(1L, new OptionalValue(true, (short) 1).value());
    assertEquals(1L, new OptionalValue(true, Integer.valueOf(1)).value());
    assertEquals(Double.class, new OptionalValue(true, 1.0f).value().getClass());
    assertEquals(1.0, new OptionalValue(true, 1.0f).value());
    assertEquals(new Bytes(new byte[] {1}), new OptionalValue(true, new byte[] {1}).value());
  }

  @Test
  public void directAbsentConstructionNormalizesToCelNull() {
    assertEquals(CelNull.INSTANCE, new OptionalValue(false, null).value());
    assertEquals(CelNull.INSTANCE, new OptionalValue(false, "anything").value());
    assertEquals(OptionalValue.none(), new OptionalValue(false, null));
    assertEquals(OptionalValue.none(), new OptionalValue(false, "anything"));
  }

  @Test
  public void directPresentConstructionWithNullPayloadStoresCelNull() {
    assertEquals(CelNull.INSTANCE, Values.in(null));
    assertEquals(CelNull.INSTANCE, OptionalValue.of(null).value());
    assertEquals(CelNull.INSTANCE, new OptionalValue(true, null).value());
  }

  @Test
  public void recordEqualityAgreesAcrossConstructionPaths() {
    assertEquals(OptionalValue.of(1L), new OptionalValue(true, 1));
    assertEquals(OptionalValue.of(1L), new OptionalValue(true, Integer.valueOf(1)));
    assertEquals(OptionalValue.of(1.0), new OptionalValue(true, 1.0f));
    assertEquals(OptionalValue.none(), new OptionalValue(false, null));
    assertEquals(
        OptionalValue.of(new Bytes(new byte[] {1})), new OptionalValue(true, new byte[] {1}));
  }

  @Test
  public void celEqualityIsConsistentAcrossConstructionPaths() {
    var factory = OptionalValue.of(1L);
    var raw = new OptionalValue(true, 1);
    assertEquals(true, Cel.compile("o == optional.of(1)").evaluate(Map.of("o", factory)));
    assertEquals(true, Cel.compile("o == optional.of(1)").evaluate(Map.of("o", raw)));
    assertEquals(
        true, Cel.compile("o == optional.of(1)").test(Activation.snapshot(Map.of("o", raw))));
  }

  @Test
  public void celArithmeticOnInnerValueWorksForBothConstructionPaths() {
    var raw = new OptionalValue(true, 1);
    assertEquals(2L, Cel.compile("o.value() + 1").evaluate(Map.of("o", OptionalValue.of(1))));
    assertEquals(2L, Cel.compile("o.value() + 1").evaluate(Map.of("o", raw)));
    assertEquals(2L, Cel.compile("o.value() + 1").evaluate(Activation.snapshot(Map.of("o", raw))));
  }

  @Test
  public void activationSnapshotSnapshotsMutableMapInsideRawOptional() {
    var mutable = new HashMap<String, Object>();
    mutable.put("secret", 1);
    var raw = new OptionalValue(true, mutable);
    var activation = Activation.snapshot(Map.of("data", raw));
    mutable.put("secret", 999);
    OptionalValue data = (OptionalValue) Cel.compile("data").evaluate(activation);
    assertTrue(data.value() instanceof Map);
    assertEquals(1L, ((Map<?, ?>) data.value()).get("secret"));
  }

  @Test
  public void activationSnapshotRejectsOversizedDataInsideRawOptional() {
    var oversized = Collections.nCopies(1_000_001, 1L);
    assertEquals(
        CelException.Code.RESOURCE_EXHAUSTED,
        assertThrows(
                CelException.class,
                () -> Activation.snapshot(Map.of("data", new OptionalValue(true, oversized))))
            .code());
  }

  @Test
  public void partialActivationResolveNormalizesRawOptionalPayload() {
    var pattern = AttributePattern.of("data").qualify("secret");
    var base = PartialActivation.of(Map.of("data", Map.of("known", 3L)), pattern);
    var mutable = new HashMap<String, Object>();
    mutable.put("deep", 1);
    var raw = new OptionalValue(true, mutable);
    var resolved = base.resolve(Map.of(Attribute.of("data").qualify("secret"), raw));
    mutable.put("deep", 999);
    OptionalValue data =
        (OptionalValue) Cel.compile("data.secret").partialEvaluate(resolved).value();
    assertEquals(1L, ((Map<?, ?>) data.value()).get("deep"));
  }

  @Test
  public void partialActivationResolveRejectsOversizedDataInRawOptional() {
    var pattern = AttributePattern.of("data").qualify("secret");
    var base = PartialActivation.of(Map.of("data", Map.of("known", 3L)), pattern);
    var oversized = Collections.nCopies(1_000_001, 1L);
    assertEquals(
        CelException.Code.RESOURCE_EXHAUSTED,
        assertThrows(
                CelException.class,
                () ->
                    base.resolve(
                        Map.of(
                            Attribute.of("data").qualify("secret"),
                            new OptionalValue(true, oversized))))
            .code());
  }

  @Test
  public void alreadyCanonicalPayloadsPassThroughWithoutReWrapping() {
    assertSame(1L, new OptionalValue(true, 1L).value());
    assertSame("hello", new OptionalValue(true, "hello").value());
    assertSame(true, new OptionalValue(true, true).value());
    assertEquals(1.0, new OptionalValue(true, 1.0).value());
    var bytes = new Bytes(new byte[] {1});
    assertSame(bytes, new OptionalValue(true, bytes).value());
    var uint = new UInt(7);
    assertSame(uint, new OptionalValue(true, uint).value());
    assertSame(CelType.INT, new OptionalValue(true, CelType.INT).value());
    assertSame(Instant.EPOCH, new OptionalValue(true, Instant.EPOCH).value());
    assertSame(Duration.ZERO, new OptionalValue(true, Duration.ZERO).value());
    var inner = OptionalValue.of(1L);
    assertSame(inner, new OptionalValue(true, inner).value());
    var canonical = OptionalValue.of(Map.of("key", 1L));
    assertSame(canonical, OptionalValue.of(canonical).value());
  }
}
