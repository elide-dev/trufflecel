package dev.trufflecel;

import dev.cel.common.CelAbstractSyntaxTree;
import org.graalvm.nativeimage.ImageInfo;

/** This holder is explicitly initialized by Native Image, without creating runtime call targets. */
final class ImageExpressions {
  static final boolean BUILT_IN_IMAGE = ImageInfo.inImageBuildtimeCode();
  static final PreparedProgram[] TRUFFLE = new PreparedProgram[3];
  static final CelAbstractSyntaxTree[] JAVA = new CelAbstractSyntaxTree[3];
  static final long[] TRUFFLE_NS = new long[3], JAVA_NS = new long[3];

  static {
    try {
      // Initialize both compiler environments before timing either frontend.
      var truffle = Fixtures.TRUFFLE;
      var java = Fixtures.JAVA;
      var sources = Fixtures.SOURCES;
      for (int i = 0; i < 3; i++) {
        long start = System.nanoTime();
        TRUFFLE[i] = truffle.prepare(sources[i]);
        TRUFFLE_NS[i] = System.nanoTime() - start;
        start = System.nanoTime();
        JAVA[i] = java.compile(sources[i]).getAst();
        JAVA_NS[i] = System.nanoTime() - start;
      }
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }
}
