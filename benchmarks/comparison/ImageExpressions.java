package dev.trufflecel;

import dev.cel.common.CelAbstractSyntaxTree;
import org.graalvm.nativeimage.ImageInfo;

/** Image-build AST holders, split so JVM processes initialize only the selected engine. */
final class ImageExpressions {
  static final boolean BUILT_IN_IMAGE = ImageInfo.inImageBuildtimeCode();

  static final class Truffle {
    static final PreparedProgram[] AST = new PreparedProgram[3];
    static final long[] NS = new long[3];

    static {
      var compiler = Fixtures.Truffle.COMPILER;
      var sources = Fixtures.SOURCES;
      for (int i = 0; i < sources.length; i++) {
        long start = System.nanoTime();
        AST[i] = compiler.prepare(sources[i]);
        NS[i] = System.nanoTime() - start;
      }
    }
  }

  static final class Java {
    static final CelAbstractSyntaxTree[] AST = new CelAbstractSyntaxTree[3];
    static final long[] NS = new long[3];

    static {
      try {
        var compiler = Fixtures.Java.COMPILER;
        var sources = Fixtures.SOURCES;
        for (int i = 0; i < sources.length; i++) {
          long start = System.nanoTime();
          AST[i] = compiler.compile(sources[i]).getAst();
          NS[i] = System.nanoTime() - start;
        }
      } catch (Exception e) {
        throw new ExceptionInInitializerError(e);
      }
    }
  }
}
