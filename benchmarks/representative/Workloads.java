package dev.trufflecel;

import com.google.protobuf.*;
import dev.trufflecel.bench.proto.PolicySchema.Request;
import java.util.*;

/** Shared CEL source, wire inputs and independently calculated results for both engines. */
final class Workloads {
  static final String[] NAMES = {
    "scalar", "scalar-wide", "proto-policy", "proto-repeated", "map-regex", "optimizer-policy"
  };
  static final String[] SOURCES = {
    "x * 2 + 1",
    "x * 2 + 1",
    "has(request.principal.quota) && request.principal.quota >= 10 && request.principal.email.endsWith('@example.com') && request.labels['env'] == 'prod' && request.created < timestamp('2030-01-01T00:00:00Z')",
    "request.amounts.filter(v, v > 50).map(v, v * 2)",
    "labels['env'] == 'prod' && email.matches('^[a-z]+@example[.]com$') && xs.exists(v, v > 100)",
    "(request.principal.email.endsWith('@example.com') && request.principal.quota >= 2 * 5) || (request.principal.email.endsWith('@example.com') && request.labels['env'] in ['prod', 'stage'])"
  };

  static final class Native {
    static final ProtobufType ADAPTER = new ProtobufType(Request.getDescriptor());
    static final List<Map<String, Object>> INPUTS = inputs(true);
  }

  static final class Raw {
    static final List<Map<String, Object>> INPUTS = inputs(false);
  }

  static List<Map<String, Object>> inputs(boolean nativeData) {
    var result = new ArrayList<Map<String, Object>>();
    for (int i = 0; i < 4; i++) {
      var amounts = new ArrayList<Long>();
      for (long n = 0; n < 128; n++) amounts.add(n + i);
      var request =
          Request.newBuilder()
              .setPrincipal(
                  Request.Principal.newBuilder()
                      .setEmail(i % 2 == 0 ? "alice@example.com" : "bob@other.com")
                      .addRoles("reader")
                      .addRoles("operator")
                      .setQuota(Int64Value.of(10 + i * 10L)))
              .putLabels("env", i < 2 ? "prod" : "stage")
              .addAllAmounts(amounts)
              .setCreated(Timestamp.newBuilder().setSeconds(1_700_000_000L))
              .build();
      var labels = new LinkedHashMap<String, String>();
      for (int n = 0; n < 64; n++) labels.put("label" + n, "value" + n);
      labels.put("env", i < 2 ? "prod" : "stage");
      var input = new LinkedHashMap<String, Object>();
      for (int n = 0; n < 64; n++) input.put("unused" + n, (long) n);
      input.put("x", (long) i + 1);
      input.put("email", request.getPrincipal().getEmail());
      input.put("labels", Collections.unmodifiableMap(labels));
      input.put("xs", List.copyOf(amounts));
      input.put("request", nativeData ? Native.ADAPTER.input(request) : request);
      result.add(Collections.unmodifiableMap(input));
    }
    return List.copyOf(result);
  }

  static Map<String, Object> input(String workload, boolean ours, int index) {
    Map<String, Object> input = (ours ? Native.INPUTS : Raw.INPUTS).get(index & 3);
    return workload.equals("scalar") ? Map.of("x", input.get("x")) : input;
  }

  static String source(String name) {
    if (name.equals("map-regex-dynamic"))
      return "labels['env'] == 'prod' && email.matches(email) && xs.exists(v, v > 100)";
    int index = Arrays.asList(NAMES).indexOf(name);
    if (index < 0) throw new IllegalArgumentException(name);
    return SOURCES[index];
  }

  static Object expected(String workload, int index) {
    int i = index & 3;
    return switch (workload) {
      case "scalar", "scalar-wide" -> 2L * (i + 1) + 1;
      case "proto-policy" -> i == 0;
      case "proto-repeated" ->
          ((List<?>) Raw.INPUTS.get(i).get("xs"))
              .stream().map(v -> (Long) v).filter(v -> v > 50).map(v -> v * 2).toList();
      case "map-regex" -> i == 0;
      case "map-regex-dynamic" -> i < 2;
      case "optimizer-policy" -> i % 2 == 0;
      default -> throw new IllegalArgumentException(workload);
    };
  }
}
