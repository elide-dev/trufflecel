package dev.trufflecel;

import com.oracle.truffle.api.interop.InteropException;
import dev.elide.truffledata.*;

/** Conversion at the ingestion boundary; native rows remain native and schema-addressable. */
public final class DataValues {
  private DataValues() {}

  public static Object from(Object value) {
    if (value instanceof AbstractRow) return value;
    if (value == Missing.INSTANCE)
      throw Values.error(CelException.Code.NO_SUCH_KEY, "Missing data value");
    if (value instanceof ExactNumber n) {
      try {
        return n.decimal().longValueExact();
      } catch (ArithmeticException e) {
        throw Values.error(
            CelException.Code.INVALID_ARGUMENT,
            "Exact decimal has no lossless CEL int representation; use an explicit conversion");
      }
    }
    if (value instanceof DataArray || value == DataNull.INSTANCE) {
      try {
        return GuestValue.unwrap(value, 0);
      } catch (InteropException e) {
        throw Values.error(CelException.Code.INVALID_ARGUMENT, "Unsupported native data value");
      }
    }
    return Values.in(value);
  }
}
