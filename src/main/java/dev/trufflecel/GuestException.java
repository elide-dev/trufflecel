package dev.trufflecel;

import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.interop.*;
import com.oracle.truffle.api.library.*;

/** Guest failures are reported as CEL failures, not internal engine failures. */
@ExportLibrary(InteropLibrary.class)
final class GuestException extends AbstractTruffleException {
  private final ExceptionType type;

  GuestException(CelException failure, boolean parsing) {
    super(failure.getMessage());
    type = parsing ? ExceptionType.PARSE_ERROR : ExceptionType.RUNTIME_ERROR;
  }

  @ExportMessage
  ExceptionType getExceptionType() {
    return type;
  }
}
