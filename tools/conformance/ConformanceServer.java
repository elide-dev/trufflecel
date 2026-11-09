package dev.trufflecel;

import static dev.trufflecel.CanonicalAst.*;

import com.google.protobuf.*;
import com.google.protobuf.Descriptors.*;
import io.grpc.*;
import io.grpc.MethodDescriptor;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.ServerCalls;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;

/** Standalone implementation of the pinned cel.expr.conformance.ConformanceService gRPC API. */
public final class ConformanceServer implements BindableService {
  private static final String SERVICE = "cel.expr.conformance.ConformanceService";
  private static final String PREFIX = "cel.expr.conformance.";
  private final Map<String, ProtobufType> objectTypes;
  private final ProtobufType registry;

  public ConformanceServer() {
    this(List.of(), List.of());
  }

  public ConformanceServer(List<Descriptor> roots, List<FieldDescriptor> extensions) {
    registry =
        roots.isEmpty()
            ? null
            : new ProtobufType(
                roots.get(0),
                extensions,
                roots.subList(1, roots.size()).toArray(Descriptor[]::new));
    var types = new LinkedHashMap<String, ProtobufType>();
    if (registry != null) {
      Environment registered = registry.register(Cel.newBuilder()).build();
      for (ObjectType type : registered.runtimeObjectTypes)
        if (type instanceof ProtobufType proto) types.put(type.name(), proto);
    }
    objectTypes = Map.copyOf(types);
  }

  private Environment.Builder environment(String container) {
    var builder = Cel.newBuilder().container(container);
    if (registry != null) registry.register(builder);
    return builder;
  }

  public Message parse(Message request) {
    try {
      var env = environment("").macros(!(Boolean) field(request, "disable_macros")).build();
      Message parsed = CanonicalAst.parse(env, text(request, "cel_source"));
      Message info = (Message) field(parsed, "source_info");
      info =
          info.toBuilder()
              .setField(
                  info.getDescriptorForType().findFieldByName("location"),
                  text(request, "source_location"))
              .build();
      parsed =
          parsed.toBuilder()
              .setField(parsed.getDescriptorForType().findFieldByName("source_info"), info)
              .build();
      return message(PREFIX + "ParseResponse", "parsed_expr", parsed);
    } catch (CelException | IllegalArgumentException e) {
      return issues("ParseResponse", e);
    }
  }

  public Message check(Message request) {
    try {
      var builder = environment(text(request, "container"));
      var declaredFunctions = new HashSet<String>();
      for (Message declaration : repeated(request, "type_env")) {
        String name = text(declaration, "name");
        if (present(declaration, "ident")) {
          Message ident = nested(declaration, "ident");
          if (present(ident, "value")) builder.constant(name, constant(nested(ident, "value")));
          else builder.variable(name, CanonicalAst.type(nested(ident, "type")));
        } else if (present(declaration, "function")) {
          declaredFunctions.add(name);
          int generatedIds = 0;
          for (Message overload : repeated(nested(declaration, "function"), "overloads")) {
            List<CelType> args =
                repeated(overload, "params").stream().map(CanonicalAst::type).toList();
            String overloadId = text(overload, "overload_id");
            boolean instance = (Boolean) field(overload, "is_instance_function");
            builder.function(
                name,
                overloadId == null || overloadId.isEmpty()
                    ? name + "#" + generatedIds++
                    : overloadId,
                instance,
                args,
                CanonicalAst.type(nested(overload, "result_type")),
                unused -> {
                  throw new IllegalArgumentException(
                      "Conformance declarations have no host implementation");
                });
          }
        }
      }
      Message parsed = nested(request, "parsed_expr");
      if ((Boolean) field(request, "no_std_env"))
        checkNoStandardFunctions(nested(parsed, "expr"), declaredFunctions, 0);
      return message(
          PREFIX + "CheckResponse", "checked_expr", CanonicalAst.check(builder.build(), parsed));
    } catch (CelException | IllegalArgumentException e) {
      return issues("CheckResponse", e);
    }
  }

  private static void checkNoStandardFunctions(Message expr, Set<String> functions, int depth) {
    if (depth > 256) throw new IllegalArgumentException("AST depth exceeded");
    if (present(expr, "call_expr")
        && !functions.contains(text(nested(expr, "call_expr"), "function")))
      throw new IllegalArgumentException("Standard declarations disabled");
    for (var entry : expr.getAllFields().entrySet()) {
      if (entry.getKey().getJavaType() != FieldDescriptor.JavaType.MESSAGE) continue;
      if (entry.getKey().isRepeated()) {
        for (Object item : (List<?>) entry.getValue())
          checkNoStandardFunctions((Message) item, functions, depth + 1);
      } else checkNoStandardFunctions((Message) entry.getValue(), functions, depth + 1);
    }
  }

  public Message eval(Message request) {
    try {
      var builder = environment(text(request, "container")).inferVariables(true);
      Environment env = builder.build();
      var bindings = new LinkedHashMap<String, Object>();
      for (Message entry : repeated(request, "bindings")) {
        Message exprValue = nested(entry, "value");
        Object value;
        if (present(exprValue, "value")) value = value(nested(exprValue, "value"), 0);
        else if (present(exprValue, "unknown")) {
          var attributes = new HashSet<String>();
          for (Object id : (List<?>) field(nested(exprValue, "unknown"), "exprs"))
            attributes.add(id.toString());
          value = new Unknown(attributes);
        } else value = new CelException(CelException.Code.INVALID_ARGUMENT, "Input binding error");
        bindings.put(text(entry, "key"), value);
      }
      // Eval has no declaration environment. Use parsed evaluation for both wire forms; names in
      // CheckedExpr are already resolved, while runtime functions still validate their operands.
      Message ast =
          present(request, "checked_expr")
              ? nested(request, "checked_expr")
              : nested(request, "parsed_expr");
      Message parsed =
          message(
              "cel.expr.ParsedExpr",
              "expr",
              field(ast, "expr"),
              "source_info",
              field(ast, "source_info"));
      Object result;
      try {
        result = CanonicalAst.program(env, parsed).evaluateInternal(bindings);
      } catch (CelException e) {
        return message(
            PREFIX + "EvalResponse", "result", message("cel.expr.ExprValue", "error", errors(e)));
      }
      Message output;
      if (result instanceof Unknown unknown) {
        var ids = new ArrayList<Long>();
        for (String attribute : unknown.attributes()) ids.add(Long.parseLong(attribute));
        output =
            message("cel.expr.ExprValue", "unknown", message("cel.expr.UnknownSet", "exprs", ids));
      } else output = message("cel.expr.ExprValue", "value", value(result, 0));
      return message(PREFIX + "EvalResponse", "result", output);
    } catch (CelException | IllegalArgumentException e) {
      return issues("EvalResponse", e);
    }
  }

  private Object value(Message value, int depth) {
    if (depth > 100) throw new IllegalArgumentException("Value depth exceeded");
    for (var entry : value.getAllFields().entrySet()) {
      Object data = entry.getValue();
      return switch (entry.getKey().getName()) {
        case "null_value" -> CelNull.INSTANCE;
        case "uint64_value" -> new UInt((Long) data);
        case "bytes_value" -> new Bytes(((ByteString) data).toByteArray());
        case "type_value" -> CelType.of((String) data);
        case "enum_value" -> ((Integer) field((Message) data, "value")).longValue();
        case "list_value" -> {
          var list = new ArrayList<Object>();
          for (Message item : repeated((Message) data, "values")) list.add(value(item, depth + 1));
          yield List.copyOf(list);
        }
        case "map_value" -> {
          var map = new LinkedHashMap<Object, Object>();
          for (Message item : repeated((Message) data, "entries")) {
            Object key = value(nested(item, "key"), depth + 1);
            if (map.containsKey(key)) throw new IllegalArgumentException("Duplicate value key");
            map.put(key, value(nested(item, "value"), depth + 1));
          }
          yield Collections.unmodifiableMap(map);
        }
        case "object_value" -> {
          Message any = (Message) data;
          String name = text(any, "type_url");
          name = name.substring(name.lastIndexOf('/') + 1);
          if (name.equals("google.protobuf.Any")) {
            try {
              Any unpacked = Any.parseFrom((ByteString) field(any, "value"));
              yield value(
                  message(
                      "cel.expr.Value",
                      "object_value",
                      message(
                          "google.protobuf.Any",
                          "type_url",
                          unpacked.getTypeUrl(),
                          "value",
                          unpacked.getValue())),
                  depth + 1);
            } catch (InvalidProtocolBufferException e) {
              throw new IllegalArgumentException("Invalid Any payload", e);
            }
          }
          ProtobufType match = objectTypes.get(name);
          if (match == null) {
            Descriptor descriptor =
                switch (name) {
                  case "google.protobuf.Timestamp" -> Timestamp.getDescriptor();
                  case "google.protobuf.Duration" -> Duration.getDescriptor();
                  case "google.protobuf.BoolValue" -> BoolValue.getDescriptor();
                  case "google.protobuf.Int32Value" -> Int32Value.getDescriptor();
                  case "google.protobuf.Int64Value" -> Int64Value.getDescriptor();
                  case "google.protobuf.UInt32Value" -> UInt32Value.getDescriptor();
                  case "google.protobuf.UInt64Value" -> UInt64Value.getDescriptor();
                  case "google.protobuf.FloatValue" -> FloatValue.getDescriptor();
                  case "google.protobuf.DoubleValue" -> DoubleValue.getDescriptor();
                  case "google.protobuf.StringValue" -> StringValue.getDescriptor();
                  case "google.protobuf.BytesValue" -> BytesValue.getDescriptor();
                  case "google.protobuf.Empty" -> Empty.getDescriptor();
                  case "google.protobuf.Struct" -> Struct.getDescriptor();
                  case "google.protobuf.Value" -> Value.getDescriptor();
                  case "google.protobuf.ListValue" -> ListValue.getDescriptor();
                  case "google.protobuf.FieldMask" -> FieldMask.getDescriptor();
                  default -> throw new IllegalArgumentException("Unregistered Any type: " + name);
                };
            match = new ProtobufType(descriptor);
          }
          ProtobufType.Value decoded =
              (ProtobufType.Value)
                  match.decode(new Bytes(((ByteString) field(any, "value")).toByteArray()));
          yield match.input(decoded.row());
        }
        default -> data;
      };
    }
    throw new IllegalArgumentException("Missing value kind");
  }

  private static Object constant(Message value) {
    if (present(value, "null_value")) return CelNull.INSTANCE;
    if (present(value, "uint64_value")) return new UInt((Long) field(value, "uint64_value"));
    if (present(value, "bytes_value"))
      return new Bytes(((ByteString) field(value, "bytes_value")).toByteArray());
    if (value.getAllFields().isEmpty()) throw new IllegalArgumentException("Missing constant");
    return value.getAllFields().values().iterator().next();
  }

  private static Message value(Object value, int depth) {
    if (depth > 100) throw new IllegalArgumentException("Value depth exceeded");
    String kind;
    Object data = value;
    if (value == CelNull.INSTANCE) {
      kind = "null_value";
      data = "NULL_VALUE";
    } else if (value instanceof Boolean) kind = "bool_value";
    else if (value instanceof Long) kind = "int64_value";
    else if (value instanceof UInt u) {
      kind = "uint64_value";
      data = u.bits();
    } else if (value instanceof Double) kind = "double_value";
    else if (value instanceof String) kind = "string_value";
    else if (value instanceof Bytes b) {
      kind = "bytes_value";
      data = ByteString.copyFrom(b.toByteArray());
    } else if (value instanceof CelType t) {
      kind = "type_value";
      data = t.name();
    } else if (value instanceof List<?> list) {
      kind = "list_value";
      var values = new ArrayList<Message>();
      for (Object item : list) values.add(value(item, depth + 1));
      data = message("cel.expr.ListValue", "values", values);
    } else if (value instanceof Map<?, ?> map) {
      kind = "map_value";
      var entries = new ArrayList<Message>();
      for (var entry : map.entrySet())
        entries.add(
            message(
                "cel.expr.MapValue.Entry",
                "key",
                value(entry.getKey(), depth + 1),
                "value",
                value(entry.getValue(), depth + 1)));
      data = message("cel.expr.MapValue", "entries", entries);
    } else {
      kind = "object_value";
      String name;
      byte[] bytes;
      if (value instanceof ProtobufType.Value proto) {
        name = proto.message().getDescriptorForType().getFullName();
        bytes = proto.toBytes().toByteArray();
      } else if (value instanceof java.time.Instant time) {
        name = "google.protobuf.Timestamp";
        bytes =
            Timestamp.newBuilder()
                .setSeconds(time.getEpochSecond())
                .setNanos(time.getNano())
                .build()
                .toByteArray();
      } else if (value instanceof java.time.Duration duration) {
        name = "google.protobuf.Duration";
        long seconds = duration.getSeconds();
        int nanos = duration.getNano();
        if (seconds < 0 && nanos != 0) {
          seconds++;
          nanos -= 1_000_000_000;
        }
        bytes = Duration.newBuilder().setSeconds(seconds).setNanos(nanos).build().toByteArray();
      } else throw new IllegalArgumentException("Cannot serialize result");
      data =
          message(
              "google.protobuf.Any",
              "type_url",
              "type.googleapis.com/" + name,
              "value",
              ByteString.copyFrom(bytes));
    }
    return message("cel.expr.Value", kind, data);
  }

  private static Message issues(String response, RuntimeException error) {
    return message(PREFIX + response, "issues", errors(error));
  }

  private static Message errors(RuntimeException error) {
    int code =
        error instanceof CelException cel && cel.code() == CelException.Code.RESOURCE_EXHAUSTED
            ? 8
            : 3;
    return message(
        "cel.expr.ErrorSet",
        "errors",
        List.of(
            message(
                "cel.expr.Status",
                "code",
                code,
                "message",
                error.getMessage() == null
                    ? error.getClass().getSimpleName()
                    : error.getMessage())));
  }

  private static Message nested(Message value, String field) {
    return (Message) field(value, field);
  }

  private static String text(Message value, String field) {
    return (String) field(value, field);
  }

  private static boolean present(Message value, String field) {
    FieldDescriptor descriptor = value.getDescriptorForType().findFieldByName(field);
    return descriptor != null && value.hasField(descriptor);
  }

  @SuppressWarnings("unchecked")
  private static List<Message> repeated(Message value, String field) {
    return (List<Message>) field(value, field);
  }

  public static MethodDescriptor<Message, Message> method(String name) {
    return MethodDescriptor.<Message, Message>newBuilder()
        .setType(MethodDescriptor.MethodType.UNARY)
        .setFullMethodName(SERVICE + "/" + name)
        .setRequestMarshaller(ProtoUtils.marshaller(prototype(PREFIX + name + "Request")))
        .setResponseMarshaller(ProtoUtils.marshaller(prototype(PREFIX + name + "Response")))
        .build();
  }

  @Override
  public ServerServiceDefinition bindService() {
    var service = ServerServiceDefinition.builder(SERVICE);
    for (String name : List.of("Parse", "Check", "Eval"))
      service.addMethod(
          method(name),
          ServerCalls.asyncUnaryCall(
              (request, observer) -> {
                Message response =
                    switch (name) {
                      case "Parse" -> parse(request);
                      case "Check" -> check(request);
                      default -> eval(request);
                    };
                observer.onNext(response);
                observer.onCompleted();
              }));
    return service.build();
  }

  /** Read an explicit protoc --include_imports descriptor set for the host conformance tool. */
  public static ConformanceServer fromDescriptors(java.nio.file.Path path)
      throws java.io.IOException, DescriptorValidationException {
    if (java.nio.file.Files.size(path) > 8 * 1024 * 1024)
      throw new IllegalArgumentException("Descriptor set too large");
    var set = DescriptorProtos.FileDescriptorSet.parseFrom(java.nio.file.Files.readAllBytes(path));
    var pending = new ArrayList<>(set.getFileList());
    var files = new LinkedHashMap<String, FileDescriptor>();
    while (!pending.isEmpty()) {
      int before = pending.size();
      var iterator = pending.iterator();
      while (iterator.hasNext()) {
        var file = iterator.next();
        if (!files.keySet().containsAll(file.getDependencyList())) continue;
        var dependencies =
            file.getDependencyList().stream().map(files::get).toArray(FileDescriptor[]::new);
        files.put(file.getName(), FileDescriptor.buildFrom(file, dependencies));
        iterator.remove();
      }
      if (pending.size() == before)
        throw new IllegalArgumentException(
            "Unresolved descriptor dependencies; use --include_imports");
    }
    var roots = new ArrayList<Descriptor>();
    var extensions = new ArrayList<FieldDescriptor>();
    for (FileDescriptor file : files.values()) {
      roots.addAll(file.getMessageTypes());
      extensions.addAll(file.getExtensions());
      for (Descriptor root : file.getMessageTypes()) extensions(root, extensions);
    }
    return new ConformanceServer(roots, extensions);
  }

  private static void extensions(Descriptor descriptor, List<FieldDescriptor> result) {
    result.addAll(descriptor.getExtensions());
    for (Descriptor nested : descriptor.getNestedTypes()) extensions(nested, result);
  }

  public static void main(String[] args) throws Exception {
    int port = args.length == 0 ? 50051 : Integer.parseInt(args[0]);
    ConformanceServer service =
        args.length < 2 ? new ConformanceServer() : fromDescriptors(java.nio.file.Path.of(args[1]));
    var executor =
        new ThreadPoolExecutor(
            2,
            Math.max(2, Runtime.getRuntime().availableProcessors()),
            30,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(256),
            new ThreadPoolExecutor.AbortPolicy());
    Server server =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", port))
            .maxInboundMessageSize(8 * 1024 * 1024)
            .executor(executor)
            .addService(service)
            .build()
            .start();
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  server.shutdownNow();
                  executor.shutdownNow();
                }));
    System.out.println("TruffleCEL conformance service listening on 127.0.0.1:" + server.getPort());
    server.awaitTermination();
  }
}
