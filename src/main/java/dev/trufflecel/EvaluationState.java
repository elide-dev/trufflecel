package dev.trufflecel;

import java.util.*;
import java.util.function.Function;

/** Optional observable execution state; ordinary calls allocate none of this state. */
final class EvaluationState {
  final EvaluationOptions.Control control;
  final TraceListener trace;
  final PartialState partial;
  final Map<String, Function<List<Object>, Object>> functions;

  EvaluationState(Environment environment, EvaluationOptions options) {
    control = options.control();
    trace = options.trace;
    partial = options.partial == null ? null : new PartialState(environment, options.partial);
    functions = options.functions;
    for (String id : functions.keySet()) {
      boolean declared = false;
      for (var signatures : environment.overloads.values())
        for (var signature : signatures)
          if (signature.id().equals(id) && signature.lateBound()) declared = true;
      if (!declared)
        throw new IllegalArgumentException("not a declared late-bound overload: " + id);
    }
  }
}
