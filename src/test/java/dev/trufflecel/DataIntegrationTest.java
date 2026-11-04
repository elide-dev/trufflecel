package dev.trufflecel;

import static org.junit.Assert.*;

import dev.elide.truffledata.*;
import com.oracle.truffle.api.interop.TruffleObject;
import dev.elide.truffledata.jdbc.JdbcSource;
import java.math.BigDecimal;
import java.util.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.Test;

public class DataIntegrationTest {
  @Test
  public void nativeRowsAndPresence() {
    Schema schema =
        Schema.of(
            Field.requiredLong("id"),
            new Field("optional", Field.Kind.STRING, true, true),
            new Field("flag", Field.Kind.BOOLEAN, false, false),
            new Field("ratio", Field.Kind.FLOAT64, false, false),
            new Field("items", Field.Kind.ANY, false, false));
    DataType type = new DataType("Row", schema);
    Object row =
        type.construct(
            Map.of(
                "id",
                7L,
                "flag",
                true,
                "ratio",
                2.5,
                "items",
                new DataArray(new Object[] {1L, DataNull.INSTANCE})));
    Environment env = Cel.newBuilder().objectType(type).variable("r", CelType.of("Row")).build();
    assertTrue(
        env.compile(
                "r.id == 7 && !has(r.optional) && r.flag && r.ratio == 2.5 && r.items[1] == null")
            .test(Map.of("r", row)));
    assertTrue(type.accepts(row));
    assertFalse(type.accepts(1L));
    assertFalse(
        type.accepts(
            Batch.copyOf(
                    Schema.of(Field.requiredLong("id")),
                    Collections.singletonList(new Object[] {7L}),
                    Limits.DEFAULT)
                .row(0)));
    assertThrows(CelException.class, () -> type.get(row, "optional"));
    assertThrows(CelException.class, () -> type.has(row, "absent"));
    assertThrows(CelException.class, () -> type.construct(Map.of("bad", 1L)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new DataType("Bad", Schema.of(Field.requiredLong("x"), Field.requiredLong("x"))));
    Object nullable =
        type.construct(
            Map.of(
                "id",
                7L,
                "optional",
                CelNull.INSTANCE,
                "flag",
                false,
                "ratio",
                0.0,
                "items",
                new DataArray(new Object[] {})));
    assertTrue(type.has(nullable, "optional"));
    assertEquals(CelNull.INSTANCE, type.get(nullable, "optional"));
    assertSame(row, DataValues.from(row));
    assertEquals(4L, DataValues.from(new ExactNumber(new BigDecimal("4.0"))));
    assertThrows(CelException.class, () -> DataValues.from(new ExactNumber(new BigDecimal("0.1"))));
    assertThrows(CelException.class, () -> DataValues.from(Missing.INSTANCE));
    assertEquals(2L, DataValues.from(2));
    assertEquals(CelNull.INSTANCE, DataValues.from(DataNull.INSTANCE));
  }

  @Test
  public void jdbcSnapshotSurvivesCursorClose() throws Exception {
    JdbcDataSource source = new JdbcDataSource();
    source.setURL("jdbc:h2:mem:cel");
    Object row;
    DataType type;
    try (var connection = source.getConnection();
        var cursor =
            JdbcSource.execute(
                connection,
                "select 42 as \"id\", cast(null as varchar) as \"name\"",
                List.of(),
                null,
                Limits.DEFAULT)) {
      type = new DataType("SqlRow", cursor.schema());
      row = cursor.nextBatch().row(0);
    }
    var env = Cel.newBuilder().objectType(type).variable("r", CelType.of("SqlRow")).build();
    assertTrue(env.compile("r.id == 42 && has(r.name) && r.name == null").test(Map.of("r", row)));
  }

  @Test
  public void polyglotNativeRowsKeepNamesAndPresence() throws Exception {
    Schema schema =
        Schema.of(Field.requiredLong("id"), new Field("absent", Field.Kind.STRING, false, true));
    var row =
        Batch.copyOf(
                schema,
                Collections.singletonList(new Object[] {7L, Missing.INSTANCE}),
                Limits.DEFAULT)
            .row(0);
    assertEquals(Map.of("id", 7L), GuestValue.unwrap(row, 0));
    assertEquals(4L, GuestValue.unwrap(new ExactNumber(new BigDecimal("4")), 0));
    assertThrows(CelException.class, () -> GuestValue.unwrap(Missing.INSTANCE, 0));
    Schema duplicates = Schema.of(Field.requiredLong("id"), Field.requiredLong("id"));
    var ambiguous =
        Batch.copyOf(duplicates, Collections.singletonList(new Object[] {1L, 2L}), Limits.DEFAULT)
            .row(0);
    assertThrows(CelException.class, () -> GuestValue.unwrap(ambiguous, 0));
    DataType type = new DataType("R", schema);
    Object same = type.construct(Map.of("id", 7L));
    assertTrue(type.equal(row, same));
    assertFalse(type.equal(row, type.construct(Map.of("id", 8L))));
    assertFalse(type.equal(row, type.construct(Map.of("id", 7L, "absent", "yes"))));

    assertThrows(CelException.class, () -> type.construct(Map.of()));
    assertThrows(
        ContractViolation.class, () -> new DataArray(new Object[] {new TruffleObject() {}}));
  }

  @Test
  public void alpha2SchemaConstraintsPreserveUnsignedIdentity() {
    Schema childSchema = new Schema("Child", List.of(Field.requiredLong("id")));
    var childType = new DataType("Child", childSchema);
    Object child = childType.construct(Map.of("id", 7L));
    Schema schema =
        Schema.of(
            Field.integer("u32", 32, false, false, false),
            Field.integer("u64", 64, false, false, false),
            Field.integer("signed", 32, true, false, false),
            new Field("array", Field.Kind.ARRAY, false, false),
            new Field(
                "record",
                Field.Kind.RECORD,
                false,
                false,
                Constraints.NONE.withRecordType("Child")),
            new Field("dynamicRecord", Field.Kind.RECORD, false, false),
            new Field("decimal", Field.Kind.EXACT, false, false),
            Field.integer("nullable", 64, false, true, false));
    var type = new DataType("Native", schema);
    var row =
        type.construct(
            Map.of(
                "u32",
                UInt.parse("4294967295"),
                "u64",
                new UInt(-1),
                "signed",
                -7L,
                "array",
                new DataArray(new Object[] {1L}),
                "record",
                child,
                "dynamicRecord",
                child,
                "decimal",
                new ExactNumber(new BigDecimal("4")),
                "nullable",
                CelNull.INSTANCE));
    assertEquals(CelType.UINT, type.fields().get("u64"));
    assertEquals(new UInt(-1), type.get(row, "u64"));
    assertEquals(CelNull.INSTANCE, type.get(row, "nullable"));
    var env =
        Cel.newBuilder()
            .objectType(childType)
            .objectType(type)
            .variable("r", CelType.of("Native"))
            .build();
    assertTrue(
        env.compile(
                "r.u32 == 4294967295u && r.u64 == 18446744073709551615u && r.signed == -7 && r.record.id == 7 && r.array == [1] && r.decimal == 4")
            .test(Map.of("r", row)));
  }

  @Test
  public void unsignedValuesAreRejectedBySignedColumns() {
    var type = new DataType("Signed", Schema.of(Field.requiredLong("value")));
    assertEquals(
        CelException.Code.INVALID_ARGUMENT,
        assertThrows(CelException.class, () -> type.construct(Map.of("value", new UInt(1))))
            .code());
  }
}
