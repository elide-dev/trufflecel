package dev.trufflecel;

/** Owned public result or CEL failure for one expression; skipped subtrees emit no event. */
public record TraceEvent(
    long expressionId,
    int offset,
    int line,
    int column,
    String kind,
    Object value,
    CelException error) {}
