package dev.trufflecel;

import com.google.protobuf.Int64Value;
import com.google.protobuf.Timestamp;
import dev.trufflecel.bench.proto.PolicySchema.Request;
import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeReflection;

/** Build-time-only configuration for cel-java's protobuf-java generated field accessors. */
public final class UpstreamProtobufFeature implements Feature {
  @Override
  public void beforeAnalysis(BeforeAnalysisAccess access) {
    for (Class<?> type :
        new Class<?>[] {
          Request.class,
          Request.Builder.class,
          Request.Principal.class,
          Request.Principal.Builder.class,
          Int64Value.class,
          Int64Value.Builder.class,
          Timestamp.class,
          Timestamp.Builder.class
        }) {
      RuntimeReflection.register(type);
      RuntimeReflection.register(type.getDeclaredMethods());
      RuntimeReflection.register(type.getDeclaredConstructors());
    }
  }
}
