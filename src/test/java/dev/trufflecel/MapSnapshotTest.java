package dev.trufflecel;

import static org.junit.Assert.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class MapSnapshotTest {
  @Test
  public void snapshotsPreserveInsertionOrderAndNormalizedCollisions() {
    var input = new LinkedHashMap<Object, Object>();
    input.put(1, "first");
    input.put("middle", List.of(2));
    input.put(1L, "last");
    input.put(null, null);
    Map<?, ?> snapshot = (Map<?, ?>) Values.in(input);
    assertEquals(List.of(1L, "middle", CelNull.INSTANCE), new ArrayList<>(snapshot.keySet()));
    assertEquals("last", snapshot.get(1L));
    assertFalse(snapshot.containsKey(1));
    assertFalse(snapshot.containsKey("missing"));
    assertNull(snapshot.get("missing"));
    assertNull(snapshot.get(null));
    assertTrue(snapshot.containsKey(CelNull.INSTANCE));
    assertEquals(CelNull.INSTANCE, snapshot.get(CelNull.INSTANCE));
    assertEquals(List.of(2L), snapshot.get("middle"));
    var iteration = snapshot.entrySet().iterator();
    assertEquals(3, snapshot.entrySet().size());
    while (iteration.hasNext()) iteration.next();
    assertThrows(NoSuchElementException.class, iteration::next);
    input.clear();
    assertEquals(3, snapshot.size());
    assertThrows(UnsupportedOperationException.class, () -> snapshot.remove("absent"));
    assertThrows(UnsupportedOperationException.class, snapshot::clear);
    assertThrows(NullPointerException.class, () -> snapshot.forEach(null));
    assertEquals(snapshot, Values.in(snapshot));
    assertEquals(snapshot.hashCode(), Values.in(snapshot).hashCode());
    Map<?, ?> empty = (Map<?, ?>) Values.in(Map.of());
    assertTrue(empty.isEmpty());
    assertFalse(empty.containsKey(null));
    assertNull(empty.get(null));
    assertThrows(UnsupportedOperationException.class, empty::clear);
  }

  @Test
  public void snapshotsAllowSourceGrowthBetweenSizingAndTraversal() {
    var input =
        new LinkedHashMap<String, Object>() {
          @Override
          public void forEach(
              java.util.function.BiConsumer<? super String, ? super Object> consumer) {
            put("added", 2);
            super.forEach(consumer);
          }
        };
    input.put("original", 1);
    Map<?, ?> snapshot = (Map<?, ?>) Values.in(input);
    assertEquals(List.of("original", "added"), new ArrayList<>(snapshot.keySet()));
    assertEquals(Map.of("original", 1L, "added", 2L), snapshot);
  }

  @Test
  public void snapshotsUseReportedSizeOnlyAsAHint() {
    for (int reported : new int[] {-1, 0, 1, 10_000, Integer.MAX_VALUE}) {
      var input =
          new LinkedHashMap<String, Object>() {
            @Override
            public int size() {
              return reported;
            }
          };
      input.put("key", 1);
      assertEquals(Map.of("key", 1L), Values.in(input));
    }
  }

  @Test
  public void snapshotsValidateWholeNestedInputAndCountActualKeysAndValues() {
    int[] visited = {0};
    Map<?, ?> tooWide =
        new AbstractMap<>() {
          @Override
          public int size() {
            return Integer.MAX_VALUE;
          }

          @Override
          public void forEach(
              java.util.function.BiConsumer<? super Object, ? super Object> consumer) {
            for (int i = 0; i < 500_001; i++) {
              visited[0]++;
              consumer.accept("same", 1);
            }
          }

          @Override
          public Set<Entry<Object, Object>> entrySet() {
            throw new AssertionError("use the map's single traversal");
          }
        };
    assertEquals(
        CelException.Code.RESOURCE_EXHAUSTED,
        assertThrows(CelException.class, () -> Values.in(tooWide)).code());
    assertEquals(500_000, visited[0]);
    Object nested = 1;
    for (int i = 0; i < 101; i++) nested = Map.of("child", nested);
    Map<String, Object> input = Map.of("used", true, "unused", nested);
    assertEquals(
        CelException.Code.RESOURCE_EXHAUSTED,
        assertThrows(
                CelException.class,
                () -> Cel.compile("input.used").evaluate(Map.of("input", input)))
            .code());
  }

  @Test
  public void collidingHashesFallBackWithoutChangingOrderOrValues() {
    var keys = new ArrayList<String>();
    keys.add("");
    for (int bit = 0; bit < 5; bit++) {
      var next = new ArrayList<String>();
      for (String key : keys) {
        next.add(key + "Aa");
        next.add(key + "BB");
      }
      keys = next;
    }
    var input = new LinkedHashMap<String, Object>();
    for (int i = 0; i < keys.size(); i++) input.put(keys.get(i), (long) i);
    Map<?, ?> snapshot = (Map<?, ?>) Values.in(input);
    assertEquals(input, snapshot);
    assertEquals(new ArrayList<>(input.keySet()), new ArrayList<>(snapshot.keySet()));
    assertEquals(31L, snapshot.get(keys.get(31)));
    assertFalse(snapshot.containsKey("missing"));
  }

  @Test
  public void nestedSnapshotsAndPublicResultsRemainOwned() {
    var nested = new LinkedHashMap<String, Object>();
    byte[] bytes = {1};
    nested.put("bytes", bytes);
    nested.put("items", List.of(1, 2));
    var input = new LinkedHashMap<String, Object>();
    input.put("nested", nested);
    var activation = Activation.snapshot(Map.of("input", input));
    var program = Cel.compile("input");
    Map<?, ?> result = (Map<?, ?>) program.evaluate(activation);
    nested.clear();
    bytes[0] = 9;
    input.clear();
    Map<?, ?> outputNested = (Map<?, ?>) result.get("nested");
    assertEquals(List.of(1L, 2L), outputNested.get("items"));
    assertArrayEquals(new byte[] {1}, (byte[]) outputNested.get("bytes"));
    ((byte[]) outputNested.get("bytes"))[0] = 7;
    Map<?, ?> again = (Map<?, ?>) ((Map<?, ?>) program.evaluate(activation)).get("nested");
    assertArrayEquals(new byte[] {1}, (byte[]) again.get("bytes"));
    assertThrows(UnsupportedOperationException.class, outputNested::clear);
    assertThrows(
        UnsupportedOperationException.class,
        () -> outputNested.entrySet().iterator().next().setValue(null));
  }

  @Test
  public void nestedOneShotInputsAreConsumedOnceAndSnapshotsCanBeShared() throws Exception {
    var iterations = new AtomicInteger();
    Iterable<Integer> oneShot =
        () -> {
          assertEquals(1, iterations.incrementAndGet());
          return List.of(1, 2).iterator();
        };
    var activation = Activation.snapshot(Map.of("input", Map.of("items", oneShot)));
    var program = Cel.compile("input.items.map(v, v * 2)");
    try (var executor = Executors.newFixedThreadPool(4)) {
      var calls = new ArrayList<Callable<Object>>();
      for (int i = 0; i < 200; i++) calls.add(() -> program.evaluate(activation));
      for (var result : executor.invokeAll(calls)) assertEquals(List.of(2L, 4L), result.get());
    }
    assertEquals(1, iterations.get());
  }
}
