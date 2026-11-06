package dev.trufflecel;

import com.oracle.truffle.api.nodes.Node;

/** Internal built-in adapter capability. Plans retain schema metadata, never request values. */
interface FieldPlan {
  ReadNode instantiate();

  interface Provider {
    // Return null unless runtime adapter precedence and conversion are safe to specialize.
    FieldPlan prepareField(String name, ObjectType[] runtimeTypes);
  }

  abstract class ReadNode extends Node {
    abstract boolean accepts(Object value);

    abstract Object read(Object value);

    abstract boolean has(Object value);
  }

  static void prepare(ExprNode node, Environment environment) {
    for (ExprNode child : node.children) prepare(child, environment);
    if (node.kind != ExprNode.Kind.SELECT
        || node.extensionSelect
        || Boolean.TRUE.equals(node.value)) return;
    CelType type = node.children[0].checkedType;
    // Qualified constants/type literals can retain parsed children that were not checked.
    if (type != null && environment.objectTypes.get(type.name()) instanceof Provider provider)
      node.fieldPlan = provider.prepareField(node.name, environment.runtimeObjectTypes);
  }
}
