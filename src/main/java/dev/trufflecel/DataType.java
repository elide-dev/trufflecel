package dev.trufflecel;

import dev.elide.truffledata.*;
import java.util.*;

/** Explicit schema binding for native TruffleData rows, including JDBC batches. */
public final class DataType implements ObjectType {
  private final String name;
  private final Schema schema;
  private final Map<String, CelType> fields;

  public DataType(String name, Schema schema) {
    this.name = Objects.requireNonNull(name);
    this.schema = Objects.requireNonNull(schema);
    var types = new LinkedHashMap<String, CelType>();
    for (int i = 0; i < schema.size(); i++) {
      Field f = schema.field(i);
      if (schema.indexOf(f.name()) != i)
        throw new IllegalArgumentException("Ambiguous column: " + f.name());
      CelType type =
          switch (f.kind()) {
            case INT64 -> CelType.INT;
            case FLOAT64 -> CelType.DOUBLE;
            case BOOLEAN -> CelType.BOOL;
            case STRING -> CelType.STRING;
            case ARRAY -> CelType.list(CelType.DYN);
            case RECORD ->
                f.constraints().recordType() == null
                    ? CelType.DYN
                    : CelType.of(f.constraints().recordType());
            case EXACT, ANY -> CelType.DYN;
          };
      if (unsigned(f)) type = CelType.UINT;
      types.put(f.name(), f.nullable() ? CelType.DYN : type);
    }
    fields = Map.copyOf(types);
  }

  private static boolean unsigned(Field field) {
    return field.constraints().integer() != null && !field.constraints().integer().signed();
  }

  public String name() {
    return name;
  }

  public Map<String, CelType> fields() {
    return fields;
  }

  public boolean accepts(Object value) {
    return value instanceof AbstractRow row && row.schema() == schema;
  }

  @Override
  public boolean equal(Object a, Object b) {
    for (String field : fields.keySet()) {
      boolean present = has(a, field);
      if (present != has(b, field)) return false;
      if (present && !Values.equal(get(a, field), get(b, field))) return false;
    }
    return true;
  }

  private int index(String field) {
    int index = schema.indexOf(field);
    if (index < 0) throw Values.error(CelException.Code.NO_SUCH_KEY, field);
    return index;
  }

  public Object get(Object value, String field) {
    Object result = DataLibrary.factory().getUncached().read(value, index(field));
    if (result == Missing.INSTANCE) throw Values.error(CelException.Code.NO_SUCH_KEY, field);
    if (result != DataNull.INSTANCE && unsigned(schema.field(index(field))))
      return result instanceof ExactNumber number
          ? UInt.parse(number.decimal().toBigIntegerExact().toString())
          : new UInt((Long) result);
    return DataValues.from(result);
  }

  public boolean has(Object value, String field) {
    return DataLibrary.factory().getUncached().isPresent(value, index(field));
  }

  public Object construct(Map<String, Object> values) {
    for (String field : values.keySet()) index(field);
    Object[] row = new Object[schema.size()];
    for (int i = 0; i < row.length; i++) {
      String field = schema.field(i).name();
      Object value = values.getOrDefault(field, Missing.INSTANCE);
      if (value instanceof UInt u && unsigned(schema.field(i)))
        value =
            schema.field(i).kind() == Field.Kind.EXACT
                ? new ExactNumber(new java.math.BigDecimal(u.toBigInteger()))
                : u.bits();
      row[i] = value == CelNull.INSTANCE ? DataNull.INSTANCE : value;
    }
    try {
      return Batch.copyOf(schema, Collections.singletonList(row), Limits.DEFAULT).row(0);
    } catch (ContractViolation e) {
      throw Values.error(CelException.Code.INVALID_ARGUMENT, e.getMessage());
    }
  }
}
