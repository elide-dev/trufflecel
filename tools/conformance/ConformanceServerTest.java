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

  @Test
  public void checkExportsDeclaredOverloadIdFromTypeEnv() {
    var service = new ConformanceServer();
    Message parsed =
        nested(service.parse(message(P + "ParseRequest", "cel_source", "g(1)")), "parsed_expr");
    Message decl =
        message(
            "cel.expr.Decl",
            "name",
            "g",
            "function",
            message(
                "cel.expr.Decl.FunctionDecl",
                "overloads",
                List.of(
                    message(
                        "cel.expr.Decl.FunctionDecl.Overload",
                        "overload_id",
                        "g_int_int",
                        "params",
                        List.of(type(CelType.INT)),
                        "result_type",
                        type(CelType.INT),
                        "is_instance_function",
                        false))));
    Message response =
        service.check(
            message(P + "CheckRequest", "parsed_expr", parsed, "type_env", List.of(decl)));
    assertTrue(has(response, "checked_expr"));
    var ids = overloadIds(nested(response, "checked_expr"));
    assertTrue(ids.contains("g_int_int"));
    assertFalse(ids.contains("g#0"));
  }

  @Test
  public void checkMixedStyleOverloadsNotAmbiguous() {
    var service = new ConformanceServer();
    Message globalParsed =
        nested(service.parse(message(P + "ParseRequest", "cel_source", "f(1, 2)")), "parsed_expr");
    Message memberParsed =
        nested(service.parse(message(P + "ParseRequest", "cel_source", "x.f(2)")), "parsed_expr");
    Message xDecl =
        message(
            "cel.expr.Decl",
            "name",
            "x",
            "ident",
            message("cel.expr.Decl.IdentDecl", "type", type(CelType.INT)));
    Message fDecl =
        message(
            "cel.expr.Decl",
            "name",
            "f",
            "function",
            message(
                "cel.expr.Decl.FunctionDecl",
                "overloads",
                List.of(
                    message(
                        "cel.expr.Decl.FunctionDecl.Overload",
                        "overload_id",
                        "f_global",
                        "params",
                        List.of(type(CelType.INT), type(CelType.INT)),
                        "result_type",
                        type(CelType.INT),
                        "is_instance_function",
                        false),
                    message(
                        "cel.expr.Decl.FunctionDecl.Overload",
                        "overload_id",
                        "f_member",
                        "params",
                        List.of(type(CelType.INT), type(CelType.INT)),
                        "result_type",
                        type(CelType.INT),
                        "is_instance_function",
                        true))));
    Message globalResponse =
        service.check(
            message(P + "CheckRequest", "parsed_expr", globalParsed, "type_env", List.of(fDecl)));
    assertTrue(has(globalResponse, "checked_expr"));
    var globalIds = overloadIds(nested(globalResponse, "checked_expr"));
    assertTrue(globalIds.contains("f_global"));
    assertFalse(globalIds.contains("f#0"));
    Message memberResponse =
        service.check(
            message(
                P + "CheckRequest",
                "parsed_expr",
                memberParsed,
                "type_env",
                List.of(xDecl, fDecl)));
    assertTrue(has(memberResponse, "checked_expr"));
    assertTrue(overloadIds(nested(memberResponse, "checked_expr")).contains("f_member"));
  }

  @Test
  public void checkRejectsGlobalCallOnInstanceOnlyOverload() {
    var service = new ConformanceServer();
    Message parsed =
        nested(service.parse(message(P + "ParseRequest", "cel_source", "f(1)")), "parsed_expr");
    Message decl =
        message(
            "cel.expr.Decl",
            "name",
            "f",
            "function",
            message(
                "cel.expr.Decl.FunctionDecl",
                "overloads",
                List.of(
                    message(
                        "cel.expr.Decl.FunctionDecl.Overload",
                        "overload_id",
                        "f_member",
                        "params",
                        List.of(type(CelType.INT)),
                        "result_type",
                        type(CelType.INT),
                        "is_instance_function",
                        true))));
    Message response =
        service.check(
            message(P + "CheckRequest", "parsed_expr", parsed, "type_env", List.of(decl)));
    assertTrue(has(response, "issues"));
    assertFalse(has(response, "checked_expr"));
    assertTrue(firstErrorMessage(response).contains("no matching overload"));
  }

  @Test
  public void checkRejectsMemberCallOnGlobalOnlyOverload() {
    var service = new ConformanceServer();
    Message parsed =
        nested(service.parse(message(P + "ParseRequest", "cel_source", "x.g(1)")), "parsed_expr");
    Message xDecl =
        message(
            "cel.expr.Decl",
            "name",
            "x",
            "ident",
            message("cel.expr.Decl.IdentDecl", "type", type(CelType.INT)));
    Message gDecl =
        message(
            "cel.expr.Decl",
            "name",
            "g",
            "function",
            message(
                "cel.expr.Decl.FunctionDecl",
                "overloads",
                List.of(
                    message(
                        "cel.expr.Decl.FunctionDecl.Overload",
                        "overload_id",
                        "g_int",
                        "params",
                        List.of(type(CelType.INT)),
                        "result_type",
                        type(CelType.INT),
                        "is_instance_function",
                        false))));
    Message response =
        service.check(
            message(P + "CheckRequest", "parsed_expr", parsed, "type_env", List.of(xDecl, gDecl)));
    assertTrue(has(response, "issues"));
    assertFalse(has(response, "checked_expr"));
    assertTrue(firstErrorMessage(response).contains("no matching overload"));
  }

  @Test
  public void checkFallsBackToGeneratedIdWhenOverloadIdMissing() {
    var service = new ConformanceServer();
    Message parsed =
        nested(service.parse(message(P + "ParseRequest", "cel_source", "g(1)")), "parsed_expr");
    Message decl =
        message(
            "cel.expr.Decl",
            "name",
            "g",
            "function",
            message(
                "cel.expr.Decl.FunctionDecl",
                "overloads",
                List.of(
                    message(
                        "cel.expr.Decl.FunctionDecl.Overload",
                        "params",
                        List.of(type(CelType.INT)),
                        "result_type",
                        type(CelType.INT)))));
    Message response =
        service.check(
            message(P + "CheckRequest", "parsed_expr", parsed, "type_env", List.of(decl)));
    assertTrue(has(response, "checked_expr"));
  }

  @SuppressWarnings("unchecked")
  private static List<String> overloadIds(Message checked) {
    var ids = new ArrayList<String>();
    for (Object entry : (List<?>) field(checked, "reference_map"))
      for (Object id : (List<?>) field(nested((Message) entry, "value"), "overload_id"))
        ids.add((String) id);
    return ids;
  }

  private static String firstErrorMessage(Message response) {
    var errors = (List<?>) field(nested(response, "issues"), "errors");
    return errors.isEmpty() ? null : (String) field((Message) errors.get(0), "message");
  }
}
