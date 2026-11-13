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

  /**
   * Returns true if a custom macro's signature would match a call that the named standard macro
   * also expands, shadowing the built-in expansion. The standard macro name is one from {@link
   * #STANDARD}; the check accounts for receiver style, arity and variadic minimums, including the
   * namespace-qualified matching used for {@code cel.*} and {@code proto.*} macros.
   */
  static boolean shadows(String standardMacro, Macro custom) {
    return switch (standardMacro) {
      case "has" -> conflicts(custom, "has", false, Set.of(1));
      case "all", "exists", "existsOne" -> conflicts(custom, standardMacro, true, Set.of(2, 3));
      case "exists_one" -> conflicts(custom, "exists_one", true, Set.of(2));
      case "map" -> conflicts(custom, "map", true, Set.of(2, 3));
      case "filter" -> conflicts(custom, "filter", true, Set.of(2));
      case "transformList", "transformMap", "transformMapEntry" ->
          conflicts(custom, standardMacro, true, Set.of(3, 4));
      case "optMap", "optFlatMap", "sortBy" -> conflicts(custom, standardMacro, true, Set.of(2));
      case "cel.bind" ->
          conflicts(custom, "bind", true, Set.of(3))
              || conflicts(custom, "cel.bind", false, Set.of(3));
      case "cel.block" ->
          conflicts(custom, "block", true, Set.of(2))
              || conflicts(custom, "cel.block", false, Set.of(2));
      case "cel.index" ->
          conflicts(custom, "index", true, Set.of(1))
              || conflicts(custom, "cel.index", false, Set.of(1));
      case "cel.iterVar" ->
          conflicts(custom, "iterVar", true, Set.of(2))
              || conflicts(custom, "cel.iterVar", false, Set.of(2));
      case "proto.getExt" ->
          conflicts(custom, "getExt", true, Set.of(2))
              || conflicts(custom, "proto.getExt", false, Set.of(2));
      case "proto.hasExt" ->
          conflicts(custom, "hasExt", true, Set.of(2))
              || conflicts(custom, "proto.hasExt", false, Set.of(2));
      default -> false;
    };
  }

  private static boolean conflicts(
      Macro custom, String name, boolean receiver, Set<Integer> arities) {
    return custom.receiver() == receiver
        && custom.name().equals(name)
        && arityOverlaps(custom, arities);
  }

  private static boolean arityOverlaps(Macro custom, Set<Integer> arities) {
    if (custom.variadic()) {
      for (int arity : arities) if (arity >= custom.argumentCount()) return true;
      return false;
    }
    return arities.contains(custom.argumentCount());
  }
}
