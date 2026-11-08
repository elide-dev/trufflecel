package dev.trufflecel;

/** Receives one completion event per executed expression, in evaluation order. */
@FunctionalInterface
public interface TraceListener {
  void onEvent(TraceEvent event);
}
