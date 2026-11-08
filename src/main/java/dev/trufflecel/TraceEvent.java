package dev.trufflecel;

import java.util.Objects;

/**
 * Owned public result or CEL failure for one expression; skipped subtrees emit no event. Offset and
 * column units are identified by {@link #offsetEncoding()}.
 */
public record TraceEvent(
    long expressionId,
    int offset,
    int line,
    int column,
    String kind,
    Object value,
    CelException error,
    CelException.OffsetEncoding offsetEncoding) {
  public TraceEvent {
    Objects.requireNonNull(offsetEncoding);
  }

  /** Source-compatible constructor for ordinary Java-char source offsets. */
  public TraceEvent(
      long expressionId,
      int offset,
      int line,
      int column,
      String kind,
      Object value,
      CelException error) {
    this(expressionId, offset, line, column, kind, value, error, CelException.OffsetEncoding.UTF16);
  }
}
