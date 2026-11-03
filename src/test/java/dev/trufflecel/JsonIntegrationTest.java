package dev.trufflecel;

import static org.junit.Assert.*;
import java.math.*;
import java.util.*;
import org.graalvm.shadowed.org.json.*;
import org.junit.Test;

public class JsonIntegrationTest {
  @Test
  public void oracleJsonSnapshot() {
    JSONObject source = new JSONObject("{\"x\":42,\"fraction\":0.1,\"list\":[true,null,\"ok\"]}");
    Object snapshot = JsonValues.from(source);
    source.put("x", 0);
    assertTrue(
        Cel.compile("j.x == 42 && j.fraction == 0.1 && j.list == [true,null,'ok']")
            .test(Map.of("j", snapshot)));
    assertEquals(42L, JsonValues.from(BigInteger.valueOf(42)));
    assertEquals(1.5, JsonValues.from(1.5f));
    assertEquals(1.5, JsonValues.from(1.5));
    assertEquals(1L, JsonValues.from((byte) 1));
    assertEquals(1L, JsonValues.from((short) 1));
    assertEquals(1L, JsonValues.from(1L));
    assertThrows(CelException.class, () -> JsonValues.from(Double.NaN));
    assertThrows(CelException.class, () -> JsonValues.from(Float.POSITIVE_INFINITY));
    assertThrows(
        CelException.class,
        () -> JsonValues.from(new java.util.concurrent.atomic.AtomicInteger(1)));
    assertEquals(CelNull.INSTANCE, JsonValues.from(null));
    assertThrows(CelException.class, () -> JsonValues.from(BigInteger.ONE.shiftLeft(64)));
    assertThrows(CelException.class, () -> JsonValues.from(new BigDecimal("1e999")));
    assertThrows(CelException.class, () -> JsonValues.from(new Object()));
    JSONArray cycle = new JSONArray();
    cycle.put(cycle);
    assertThrows(CelException.class, () -> JsonValues.from(cycle));
    JSONArray large = new JSONArray();
    for (int i = 0; i < 1_000_001; i++) large.put(0);
    assertThrows(CelException.class, () -> JsonValues.from(large));
  }
}
