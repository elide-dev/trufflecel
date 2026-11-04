import com.google.protobuf.Int64Value;
import dev.elide.truffledata.Batch;
import dev.elide.truffledata.Field;
import dev.elide.truffledata.Limits;
import dev.elide.truffledata.Schema;
import dev.trufflecel.Cel;
import dev.trufflecel.CanonicalAst;
import dev.trufflecel.CelType;
import dev.trufflecel.DataType;
import dev.trufflecel.JsonValues;
import dev.trufflecel.ProtobufType;
import java.util.List;
import java.util.Map;
import org.graalvm.polyglot.Context;
import org.graalvm.shadowed.org.json.JSONObject;

/** JVM consumption test against publication jars, with no source-library dependencies. */
public final class Consumer {
  public static void main(String[] args) {
    var schema = Schema.of(Field.requiredLong("id"));
    var row = Batch.copyOf(schema, List.<Object[]>of(new Object[] {42L}), Limits.DEFAULT).row(0);
    var type = new DataType("Row", schema);
    var env = Cel.newBuilder().objectType(type).variable("r", CelType.of("Row")).build();
    if (!env.compile("r.id == 42").test(Map.of("r", row))) throw new AssertionError("native row");
    var protoType = new ProtobufType(Int64Value.getDescriptor());
    Object proto = protoType.input(protoType.plan().decode(new byte[] {8, 42}));
    Object json = JsonValues.from(new JSONObject("{\"id\":42}"));
    if (!Cel.compile("p == j.id").test(Map.of("p", proto, "j", json))) {
      throw new AssertionError("optional adapters");
    }
    var astEnv = Cel.newBuilder().variable("x", CelType.INT).build();
    var parsed = CanonicalAst.parse(astEnv, "x + 1");
    var checked = CanonicalAst.check(astEnv, parsed);
    if (!CanonicalAst.program(astEnv, checked).evaluate(Map.of("x", 41L)).equals(42L))
      throw new AssertionError("canonical AST publication");
    if (!Cel.compile("yaml.parse('a: 42').value().a == 42 && json.parse('[1]').value() == [1]")
        .test()) throw new AssertionError("structured encoder publications");
    try (var context =
        Context.newBuilder("cel").option("engine.WarnInterpreterOnly", "false").build()) {
      if (!context.eval("cel", "6 * 7 == 42").asBoolean())
        throw new AssertionError("language registration");
    }
  }
}
