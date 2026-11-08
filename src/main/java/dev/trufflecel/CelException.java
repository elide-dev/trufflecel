package dev.trufflecel;

import java.util.List;
import java.util.Objects;

/**
 * A deterministic compile or evaluation failure. Compile-time failures carry a source offset and,
 * when the source is known, a one-based line and column; strict validation may report several
 * issues at once. {@link #code()} and {@link #offset()} describe the first issue.
 *
 * <p>Evaluation errors are ordinary CEL values that compiled programs raise and absorb on hot
 * paths, so this type must stay outside the exception hierarchies that the Truffle partial
 * evaluator treats as slow paths ({@code IllegalArgumentException}, {@code IllegalStateException},
 * {@code ArithmeticException}, {@code IndexOutOfBoundsException}, {@code ClassCastException}): a
 * handler for one of those types is compiled as a deoptimization that recurs on every throw.
 */
public final class CelException extends RuntimeException {
  /** Maximum findings collected by a compiler pass. */
  public static final int MAX_ISSUES = 100;

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

  /**
   * Units used by offsets and columns. Source parsing uses UTF-16; canonical ASTs use code points.
   */
  public enum OffsetEncoding {
    UTF16,
    CODE_POINT
  }

  /** One finding with explicit offset units; line and column are one-based or -1 when unknown. */
  public record Issue(
      Code code, String message, int offset, int line, int column, OffsetEncoding offsetEncoding) {
    public Issue {
      Objects.requireNonNull(code);
      Objects.requireNonNull(message);
      Objects.requireNonNull(offsetEncoding);
    }

    /** Source-compatible constructor for ordinary Java-char source offsets. */
    public Issue(Code code, String message, int offset, int line, int column) {
      this(code, message, offset, line, column, OffsetEncoding.UTF16);
    }

    private int sourceIndex(String source) {
      if (offset < 0) return -1;
      if (offsetEncoding == OffsetEncoding.UTF16) return offset > source.length() ? -1 : offset;
      return offset > source.codePointCount(0, source.length())
          ? -1
          : source.offsetByCodePoints(0, offset);
    }

    /** The issue located in {@code source}; unchanged when it is already located or unlocatable. */
    public Issue locate(String source) {
      if (line >= 0) return this;
      int index = sourceIndex(source);
      if (index < 0) return this;
      int line = 1, start = 0;
      for (int i = 0; i < index; i++)
        if (source.charAt(i) == '\n') {
          line++;
          start = i + 1;
        }
      int column =
          offsetEncoding == OffsetEncoding.UTF16
              ? index - start + 1
              : source.codePointCount(start, index) + 1;
      return new Issue(code, message, offset, line, column, offsetEncoding);
    }

    /** Render this finding with a bounded source excerpt and a caret. */
    public String format(String source) {
      Objects.requireNonNull(source);
      Issue located = locate(source);
      int index = sourceIndex(source);
      if (index < 0) return located.toString();
      int start = source.lastIndexOf('\n', index - 1) + 1;
      int end = source.indexOf('\n', index);
      if (end < 0) end = source.length();
      int left =
          source.offsetByCodePoints(index, -Math.min(80, source.codePointCount(start, index)));
      int right = source.offsetByCodePoints(index, Math.min(80, source.codePointCount(index, end)));
      String prefix = left > start ? "..." : "";
      String suffix = right < end ? "..." : "";
      String excerpt = source.substring(left, right).replace('\t', ' ').replace('\r', ' ');
      return located
          + "\n"
          + prefix
          + excerpt
          + suffix
          + "\n"
          + " ".repeat(prefix.length() + source.codePointCount(left, index))
          + "^";
    }

    @Override
    public String toString() {
      if (offset < 0) return message;
      return line < 0
          ? message + " at offset " + offset
          : message + " at offset " + offset + " (line " + line + ", column " + column + ")";
    }
  }

  // The first issue's fields are plain finals: compiled error handlers read the code on hot paths,
  // and an interface call into the issue list would not partially evaluate.
  private final long expressionId;
  private final Code code;
  private final int offset, line, column;
  private final List<Issue> issues;

  public CelException(Code code, String message) {
    this(code, message, -1);
  }

  public CelException(Code code, String message, int offset) {
    this(List.of(new Issue(code, message, offset, -1, -1)));
  }

  /** Report one or more issues; the first issue provides the code and offset. */
  public CelException(List<Issue> issues) {
    this(issues, -1);
  }

  private CelException(List<Issue> issues, long expressionId) {
    super(describe(issues));
    this.expressionId = expressionId;
    this.issues = List.copyOf(issues);
    Issue first = issues.get(0);
    code = first.code();
    offset = first.offset();
    line = first.line();
    column = first.column();
  }

  private static String describe(List<Issue> issues) {
    if (issues.isEmpty()) throw new IllegalArgumentException("at least one issue is required");
    var text = new StringBuilder(issues.get(0).toString());
    for (int i = 1; i < issues.size(); i++) text.append("; ").append(issues.get(i));
    return text.toString();
  }

  /** Stable expression ID within the program, or -1 for an unlocated failure. */
  public long expressionId() {
    return expressionId;
  }

  @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
  CelException at(int offset, long id, int line, int column, OffsetEncoding encoding) {
    if (expressionId >= 0) return this;
    Issue issue = issues.get(0);
    return new CelException(
        List.of(new Issue(code, issue.message(), offset, line, column, encoding)), id);
  }

  public Code code() {
    return code;
  }

  public int offset() {
    return offset;
  }

  /**
   * Offset and column units of the first issue; numeric values are never rewritten for formatting.
   */
  public OffsetEncoding offsetEncoding() {
    return issues.getFirst().offsetEncoding();
  }

  /** One-based line of the first issue, or -1 when the source position is unknown. */
  public int line() {
    return line;
  }

  /** One-based column of the first issue, or -1 when the source position is unknown. */
  public int column() {
    return column;
  }

  /** Every reported issue, in source order; never empty. */
  public List<Issue> issues() {
    return issues;
  }

  /** This failure with line and column resolved against {@code source}. */
  CelException locate(String source) {
    List<Issue> located = issues.stream().map(issue -> issue.locate(source)).toList();
    return located.equals(issues) ? this : new CelException(located, expressionId);
  }

  /** Render every finding with line/column, source excerpt and caret, without retaining source. */
  public String format(String source) {
    Objects.requireNonNull(source);
    var result = new StringBuilder();
    for (Issue issue : issues) {
      if (!result.isEmpty()) result.append("\n\n");
      result.append(issue.format(source));
    }
    return result.toString();
  }

  @Override
  public synchronized Throwable fillInStackTrace() {
    return this;
  }
}
