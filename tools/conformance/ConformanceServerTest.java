package dev.trufflecel;

import static dev.trufflecel.CanonicalAst.*;
import static org.junit.Assert.*;

import com.google.protobuf.*;
import com.google.protobuf.Descriptors.*;
import io.grpc.*;
import io.grpc.netty.shaded.io.grpc.netty.*;
import io.grpc.stub.ClientCalls;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public final class ConformanceServerTest {
  private static final String P = "cel.expr.conformance.";

  private static Message nested(Message m, String name) {
    return (Message) field(m, name);
  }

  private static Message invoke(Channel channel, String method, Message request) {
    return ClientCalls.blockingUnaryCall(
        channel,
        ConformanceServer.method(method),
        CallOptions.DEFAULT.withDeadlineAfter(10, TimeUnit.SECONDS),
        request);
  }

  @Test
  public void actualGrpcParseCheckEvalWithErrorsUnknownsAndConcurrentCalls() throws Exception {
    Server server =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .addService(new ConformanceServer())
            .build()
            .start();
    ManagedChannel channel =
        NettyChannelBuilder.forAddress("127.0.0.1", server.getPort()).usePlaintext().build();
    try {
      Message parsed =
          nested(
              invoke(
                  channel,
                  "Parse",
                  message(
                      P + "ParseRequest", "cel_source", "x + 1", "source_location", "policy.cel")),
              "parsed_expr");
      assertEquals("policy.cel", field(nested(parsed, "source_info"), "location"));
      Message declaration =
          message(
              "cel.expr.Decl",
              "name",
              "x",
              "ident",
              message("cel.expr.Decl.IdentDecl", "type", type(CelType.INT)));
      Message checked =
          nested(
              invoke(
                  channel,
                  "Check",
                  message(
                      P + "CheckRequest", "parsed_expr", parsed, "type_env", List.of(declaration))),
              "checked_expr");
      Message binding =
          message("cel.expr.ExprValue", "value", message("cel.expr.Value", "int64_value", 41L));
      Message request =
          message(P + "EvalRequest", "checked_expr", checked, "bindings", Map.of("x", binding));
      assertEquals(
          42L,
          field(
              nested(nested(invoke(channel, "Eval", request), "result"), "value"), "int64_value"));
      try (var pool = java.util.concurrent.Executors.newFixedThreadPool(4)) {
        var futures = new ArrayList<java.util.concurrent.Future<Message>>();
        for (int i = 0; i < 20; i++)
          futures.add(pool.submit(() -> invoke(channel, "Eval", request)));
        for (var future : futures)
          assertEquals(42L, field(nested(nested(future.get(), "result"), "value"), "int64_value"));
      }
      Message badParse = invoke(channel, "Parse", message(P + "ParseRequest", "cel_source", "1 +"));
      assertFalse(((List<?>) field(nested(badParse, "issues"), "errors")).isEmpty());
      Message badCheck =
          invoke(channel, "Check", message(P + "CheckRequest", "parsed_expr", parsed));
      assertFalse(((List<?>) field(nested(badCheck, "issues"), "errors")).isEmpty());
      Message noStd =
          invoke(
              channel,
              "Check",
              message(
                  P + "CheckRequest",
                  "parsed_expr",
                  parsed,
                  "type_env",
                  List.of(declaration),
                  "no_std_env",
                  true));
      assertFalse(((List<?>) field(nested(noStd, "issues"), "errors")).isEmpty());
      Message errorParsed =
          nested(
              invoke(channel, "Parse", message(P + "ParseRequest", "cel_source", "1/0")),
              "parsed_expr");
      Message error =
          invoke(channel, "Eval", message(P + "EvalRequest", "parsed_expr", errorParsed));
      assertFalse(((List<?>) field(nested(nested(error, "result"), "error"), "errors")).isEmpty());
      Message unknown =
          message(
              "cel.expr.ExprValue",
              "unknown",
              message("cel.expr.UnknownSet", "exprs", List.of(7L)));
      Message result =
          invoke(
              channel,
              "Eval",
              message(P + "EvalRequest", "parsed_expr", parsed, "bindings", Map.of("x", unknown)));
      assertEquals(List.of(7L), field(nested(nested(result, "result"), "unknown"), "exprs"));
      Message macro =
          nested(
              invoke(
                  channel,
                  "Parse",
                  message(P + "ParseRequest", "cel_source", "[1,2,3].all(v, v > 0)")),
              "parsed_expr");
      assertEquals(
          true,
          field(
              nested(
                  nested(
                      invoke(channel, "Eval", message(P + "EvalRequest", "parsed_expr", macro)),
                      "result"),
                  "value"),
              "bool_value"));
    } finally {
      channel.shutdownNow().awaitTermination(10, TimeUnit.SECONDS);
      server.shutdownNow().awaitTermination(10, TimeUnit.SECONDS);
    }
  }

  @Test
  public void explicitDescriptorSetAndObjectWireValues() throws Exception {
    var proto =
        DescriptorProtos.FileDescriptorProto.newBuilder()
            .setName("fixture.proto")
            .setPackage("fixture")
            .addMessageType(
                DescriptorProtos.DescriptorProto.newBuilder()
                    .setName("Record")
                    .addField(
                        DescriptorProtos.FieldDescriptorProto.newBuilder()
                            .setName("value")
                            .setNumber(1)
                            .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_INT64)))
            .build();
    var path = java.nio.file.Files.createTempFile("trufflecel-descriptors", ".pb");
    try {
      java.nio.file.Files.write(
          path,
          DescriptorProtos.FileDescriptorSet.newBuilder().addFile(proto).build().toByteArray());
      var service = ConformanceServer.fromDescriptors(path);
      Message parsed =
          nested(
              service.parse(message(P + "ParseRequest", "cel_source", "fixture.Record{value:42}")),
              "parsed_expr");
      Message result =
          nested(
              nested(service.eval(message(P + "EvalRequest", "parsed_expr", parsed)), "result"),
              "value");
      Message any = nested(result, "object_value");
      assertEquals("type.googleapis.com/fixture.Record", field(any, "type_url"));
      Message roundTrip =
          nested(
              service.parse(message(P + "ParseRequest", "cel_source", "m.value")), "parsed_expr");
      Message response =
          service.eval(
              message(
                  P + "EvalRequest",
                  "parsed_expr",
                  roundTrip,
                  "bindings",
                  Map.of("m", message("cel.expr.ExprValue", "value", result))));
      assertEquals(42L, field(nested(nested(response, "result"), "value"), "int64_value"));
      var timestamp = Timestamp.newBuilder().setSeconds(1).build();
      Message time =
          message(
              "cel.expr.Value",
              "object_value",
              message(
                  "google.protobuf.Any",
                  "type_url",
                  "type.googleapis.com/google.protobuf.Timestamp",
                  "value",
                  timestamp.toByteString()));
      Message identity =
          nested(service.parse(message(P + "ParseRequest", "cel_source", "m")), "parsed_expr");
      response =
          service.eval(
              message(
                  P + "EvalRequest",
                  "parsed_expr",
                  identity,
                  "bindings",
                  Map.of("m", message("cel.expr.ExprValue", "value", time))));
      assertEquals(
          timestamp.toByteString(),
          field(nested(nested(nested(response, "result"), "value"), "object_value"), "value"));
    } finally {
      java.nio.file.Files.deleteIfExists(path);
    }
  }
}
