package dev.trufflecel;

/** CEL null at adapter/function boundaries; Program.evaluate converts it to Java null. */
public enum CelNull {
  INSTANCE
}
