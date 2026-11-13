package dev.trufflecel;

import static org.junit.Assert.*;

import dev.elide.truffledata.*;
import java.util.*;
import org.junit.Test;

public class NullableTypingTest {

  private static Environment dataTypeEnv() {
    Schema schema =
        Schema.of(
            Field.requiredLong("id"),
            new Field("age", Field.Kind.INT64, true, true),
            new Field("name", Field.Kind.STRING, true, true),
            new Field("flag", Field.Kind.BOOLEAN, true, true),
            new Field("ratio", Field.Kind.FLOAT64, true, true));
    DataType type = new DataType("Native", schema);
    return Cel.newBuilder().objectType(type).variable("r", CelType.of("Native")).build();
  }

  @Test
  public void nullableFieldsAreTypeChecked() {
    var env = dataTypeEnv();
    for (String expr :
        List.of(
            "r.age > 'x'",
            "r.age == 'x'",
            "r.age + 'x'",
            "r.name.contains(1)",
            "Native{age: 'x'}",
            "Native{name: 1}")) {
      assertEquals(
          expr,
          CelException.Code.TYPE,
          assertThrows(CelException.class, () -> env.compile(expr)).code());
    }
  }

  @Test
  public void nullableFieldsAcceptNullAndValidOperations() {
    var env = dataTypeEnv();
    for (String expr :
        List.of(
            "Native{age: null}",
            "r.age == null",
            "null == r.age",
            "r.age > 5",
            "r.age + 1",
            "r.name + 'suffix'",
            "r.name.contains('x')",
            "has(r.age)")) {
      assertNotNull(expr, env.compile(expr).resultType());
    }
  }

  @Test
  public void nullableFieldTypesArePreciseWrappers() {
    var env = dataTypeEnv();
    var fields = env.objectTypes.get("Native").fields();
    assertEquals(CelType.wrapper(CelType.INT), fields.get("age"));
    assertEquals(CelType.wrapper(CelType.STRING), fields.get("name"));
    assertEquals(CelType.wrapper(CelType.BOOL), fields.get("flag"));
    assertEquals(CelType.wrapper(CelType.DOUBLE), fields.get("ratio"));
    assertEquals(CelType.INT, fields.get("id"));
    assertEquals(
        CelType.wrapper(CelType.UINT),
        new DataType("Native", Schema.of(Field.integer("u64", 64, false, true, false)))
            .fields()
            .get("u64"));
  }

  @Test
  public void runtimeBehaviorUnchangedForNullableFields() {
    var env = dataTypeEnv();
    var type = env.objectTypes.get("Native");
    Object nullRow =
        type.construct(
            Map.of(
                "id", 7L,
                "age", CelNull.INSTANCE,
                "name", CelNull.INSTANCE,
                "flag", CelNull.INSTANCE,
                "ratio", CelNull.INSTANCE));
    assertTrue(
        env.compile("r.id == 7 && r.age == null && has(r.age) && r.name == null")
            .test(Map.of("r", nullRow)));
    Object presentRow =
        type.construct(Map.of("id", 7L, "age", 42L, "name", "bob", "flag", true, "ratio", 2.5));
    assertTrue(
        env.compile(
                "r.id == 7 && r.age == 42 && r.name == 'bob' && r.flag && r.ratio == 2.5 && r.age > 5")
            .test(Map.of("r", presentRow)));
  }
}
