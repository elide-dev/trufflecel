package dev.trufflecel;

/** Native allocation-only entry; recording/profiling APIs are deliberately unreachable. */
public final class IngestionNativeAllocation {
  public static void main(String[] args) throws Exception {
    if (args.length != 1)
      throw new IllegalArgumentException("ordinary|unknown64k|messageset|smoke");
    System.setProperty("polyglot.engine.Compilation", "false");
    if (args[0].equals("smoke")) IngestionProfile.smoke();
    else IngestionProfile.allocate(args[0]);
  }
}
