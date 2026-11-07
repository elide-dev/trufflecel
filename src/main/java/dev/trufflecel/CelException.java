package dev.trufflecel;

/**
 * A deterministic compile or evaluation failure, with a source offset when available.
 *
 * <p>Evaluation errors are ordinary CEL values that compiled programs raise and absorb on hot
 * paths, so this type must stay outside the exception hierarchies that the Truffle partial
 * evaluator treats as slow paths ({@code IllegalArgumentException}, {@code IllegalStateException},
 * {@code ArithmeticException}, {@code IndexOutOfBoundsException}, {@code ClassCastException}): a
 * handler for one of those types is compiled as a deoptimization that recurs on every throw.
 */
public final class CelException extends RuntimeException {
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
