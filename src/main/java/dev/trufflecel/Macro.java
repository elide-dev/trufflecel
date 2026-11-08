package dev.trufflecel;

import java.util.*;

/** A compile-time syntax expansion; callbacks never run during CEL evaluation. */
public record Macro(
    String name, boolean receiver, int argumentCount, boolean variadic, Expander expander) {
  public static final Set<String> STANDARD =
      Set.of(
          "has",
          "all",
          "exists",
          "exists_one",
          "existsOne",
          "map",
          "filter",
          "transformList",
          "transformMap",
          "transformMapEntry",
          "optMap",
          "optFlatMap",
          "sortBy",
          "bind",
          "cel.bind",
          "cel.block",
          "cel.index",
          "cel.iterVar",
          "proto.getExt",
          "proto.hasExt");

  public Macro {
    if (Objects.requireNonNull(name).isEmpty() || argumentCount < 0)
      throw new IllegalArgumentException("invalid macro signature");
    Objects.requireNonNull(expander);
  }

  @FunctionalInterface
  public interface Expander {
    /** Target is null for global macros; argument count excludes the receiver. */
    Ast.Node expand(Context context, Ast.Node target, List<Ast.Node> arguments);
  }

  /** Fresh names cannot collide with source identifiers, including nested expansion names. */
  public static final class Context {
    private final int offset;
    private final java.util.function.LongSupplier names;

    Context(int offset, java.util.function.LongSupplier names) {
      this.offset = offset;
      this.names = names;
    }

    public String freshName() {
      return "@trufflecel_macro_" + names.getAsLong();
    }

    public CelException error(String message) {
      return new CelException(CelException.Code.SYNTAX, message, offset);
    }
  }

  public static Macro global(String name, int arguments, Expander expander) {
    return new Macro(name, false, arguments, false, expander);
  }

  public static Macro receiver(String name, int arguments, Expander expander) {
    return new Macro(name, true, arguments, false, expander);
  }

  public static Macro globalVarargs(String name, int minimum, Expander expander) {
    return new Macro(name, false, minimum, true, expander);
  }

  public static Macro receiverVarargs(String name, int minimum, Expander expander) {
    return new Macro(name, true, minimum, true, expander);
  }
}
