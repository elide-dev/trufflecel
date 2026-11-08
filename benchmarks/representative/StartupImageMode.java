package dev.trufflecel;

/** Captured at image construction; prevents mixing runtime and image-prepared artifacts. */
final class StartupImageMode {
  static final boolean PREPARED = Boolean.getBoolean("trufflecel.startup.imagePrepared");
}
