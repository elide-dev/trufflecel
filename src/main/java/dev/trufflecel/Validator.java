package dev.trufflecel;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Compile-time application validation over an immutable source tree and checked type map. */
@FunctionalInterface
public interface Validator {
  /** Return findings; an empty list accepts the expression. Implementations must be thread-safe. */
  List<CelException.Issue> validate(Context context);

  /** The source tree precedes constant substitution and folding; host objects never enter it. */
  record Context(String source, Ast.Node root, CelType resultType, Map<Long, CelType> types) {
    public Context {
      Objects.requireNonNull(source);
      Objects.requireNonNull(root);
      Objects.requireNonNull(resultType);
      types = Map.copyOf(types);
    }

    /** A resolved type, or dyn when a source-only node was removed by name resolution. */
    public CelType type(Ast.Node node) {
      return types.getOrDefault(node.id(), CelType.DYN);
    }

    /** Create a finding with this node's original offset units and available source location. */
    public CelException.Issue issue(Ast.Node node, CelException.Code code, String message) {
      return new CelException.Issue(
              code, message, node.offset(), node.line(), node.column(), node.offsetEncoding())
          .locate(source);
    }
  }
}
