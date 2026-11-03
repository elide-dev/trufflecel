package dev.trufflecel;

/** A deterministic compile or evaluation failure, with a source offset when available. */
public final class CelException extends IllegalArgumentException {
  public enum Code {
    SYNTAX,
    TYPE,
    UNDECLARED,
    NO_SUCH_KEY,
    NO_SUCH_FIELD,
    INDEX,
    OVERFLOW,
    DIVIDE_BY_ZERO,
    NO_OVERLOAD,
    INVALID_ARGUMENT,
    RESOURCE_EXHAUSTED
  }

  private final Code code;
  private final int offset;

  public CelException(Code code, String message) {
    this(code, message, -1);
  }

  public CelException(Code code, String message, int offset) {
    super(message + (offset < 0 ? "" : " at offset " + offset));
    this.code = code;
    this.offset = offset;
  }

  public Code code() {
    return code;
  }

  public int offset() {
    return offset;
  }

  @Override
  public synchronized Throwable fillInStackTrace() {
    return this;
  }
}
