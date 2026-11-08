package dev.trufflecel;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import java.util.*;

/** Shared declaration matching and bounded-by-registration host overload dispatch. */
final class FunctionOverloads {
  private FunctionOverloads() {}

  static boolean overlaps(List<CelType> a, List<CelType> b) {
    if (a.size() != b.size()) return false;
    for (int i = 0; i < a.size(); i++)
      if (!a.get(i).isDyn()
          && !b.get(i).isDyn()
          && !a.get(i).unwrapped().name().equals(b.get(i).unwrapped().name())
          && !a.get(i).accepts(b.get(i))
          && !b.get(i).accepts(a.get(i))) return false;
    return true;
  }

  static boolean matches(
      Environment.FunctionOverload signature, boolean receiver, List<CelType> types) {
    if (signature.receiver() != null && signature.receiver() != receiver) return false;
    var expected = signature.binding().arguments();
    if (expected.size() != types.size()) return false;
    for (int i = 0; i < types.size(); i++) if (!expected.get(i).accepts(types.get(i))) return false;
    return true;
  }

  @TruffleBoundary
  static Environment.FunctionOverload select(
      Environment env, String name, boolean receiver, Object[] arguments) {
    var types = new ArrayList<CelType>();
    for (Object argument : arguments) {
      CelType type = Values.type(argument);
      if (type.isDyn() && !(argument instanceof Unknown) && !(argument instanceof CelException))
        type = CelType.of("@unregistered");
      for (ObjectType adapter : env.runtimeObjectTypes)
        if (adapter.accepts(argument)) {
          type = CelType.of(adapter.name());
          break;
        }
      types.add(type);
    }
    Environment.FunctionOverload result = null;
    for (var signature : env.overloads.get(name))
      if (matches(signature, receiver, types)) {
        // Unknown/error arguments can match more than one concrete overload. Never guess.
        if (result != null) return null;
        result = signature;
      }
    return result;
  }

  @TruffleBoundary
  static Object invoke(
      java.util.function.Function<List<Object>, Object> implementation, Object[] arguments) {
    try {
      Object result = implementation.apply(List.of(arguments));
      if (result instanceof CelException error) throw error;
      return Values.in(result);
    } catch (CelException e) {
      throw e;
    } catch (ArithmeticException e) {
      throw Values.error(CelException.Code.OVERFLOW, "numeric overflow");
    } catch (IllegalArgumentException
        | IndexOutOfBoundsException
        | ClassCastException
        | java.time.DateTimeException e) {
      throw Values.error(
          CelException.Code.INVALID_ARGUMENT, "invalid function argument: " + e.getMessage());
    }
  }

  static boolean standard(String name) {
    return ExtensionFunctions.supports(name)
        || name.startsWith("get")
        || switch (name) {
          case "!",
              "neg",
              "hasValue",
              "value",
              "optional.of",
              "optional.none",
              "optional.ofNonZeroValue",
              "dyn",
              "type",
              "size",
              "int",
              "uint",
              "double",
              "bool",
              "string",
              "bytes",
              "timestamp",
              "duration",
              "==",
              "!=",
              "<",
              "<=",
              ">",
              ">=",
              "+",
              "-",
              "*",
              "/",
              "%",
              "in",
              "contains",
              "startsWith",
              "endsWith",
              "matches" ->
              true;
          default -> false;
        };
  }
}
