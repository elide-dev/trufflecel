package dev.trufflecel;

import static org.junit.Assert.*;

import dev.trufflecel.processor.PojoProcessor;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import javax.tools.*;
import org.junit.Test;

public final class PojoProcessorTest {
  private record Compilation(boolean success, String diagnostics, Map<String, String> generated) {}

  private static Compilation compile(boolean core, String... sources) throws Exception {
    return compile(core, true, sources);
  }

  private static Compilation compile(boolean core, boolean explicit, String... sources)
      throws Exception {
    return compile(core, explicit, false, sources);
  }

  private static Compilation compile(
      boolean core, boolean explicit, boolean split, String... sources) throws Exception {
    Path directory = Files.createTempDirectory("trufflecel-pojo-");
    try {
      var compiler = ToolProvider.getSystemJavaCompiler();
      var diagnostics = new DiagnosticCollector<JavaFileObject>();
      try (var files = compiler.getStandardFileManager(diagnostics, null, null)) {
        var inputs = new ArrayList<JavaFileObject>();
        for (int i = 0; i < sources.length; i += 2) {
          String source = sources[i + 1];
          inputs.add(
              new SimpleJavaFileObject(
                  URI.create("string:///" + sources[i].replace('.', '/') + ".java"),
                  JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                  return source;
                }
              });
        }
        var options =
            List.of(
                "-proc:full",
                "-classpath",
                core
                    ? System.getProperty("java.class.path") + java.io.File.pathSeparator + directory
                    : directory.toString(),
                "-d",
                directory.toString(),
                "-s",
                directory.toString());
        if (split) {
          var prerequisite =
              compiler.getTask(
                  null, files, diagnostics, options, null, List.of(inputs.removeFirst()));
          prerequisite.setProcessors(List.of(new PojoProcessor()));
          assertTrue(diagnostics.getDiagnostics().toString(), prerequisite.call());
        }
        var task = compiler.getTask(null, files, diagnostics, options, null, inputs);
        if (explicit) task.setProcessors(List.of(new PojoProcessor()));
        boolean success = task.call();
        var generated = new TreeMap<String, String>();
        try (var paths = Files.walk(directory)) {
          for (Path path : paths.filter(path -> path.toString().endsWith(".java")).toList())
            generated.put(directory.relativize(path).toString(), Files.readString(path));
        }
        return new Compilation(success, diagnostics.getDiagnostics().toString(), generated);
      }
    } finally {
      try (var paths = Files.walk(directory)) {
        for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
      }
    }
  }

  @Test
  public void generatesStandaloneAndNestedAdaptersWithNoReflection() throws Exception {
    var result =
        compile(
            true,
            "sample.Bean",
            """
          package sample;
          import dev.trufflecel.*;
          @CelPojo("app.Bean") public class Bean {
            public String name;
            public java.util.List<Nested> values;
            @CelPojo public record Nested(int score) {}
            @CelField("custom") public long value() { return 1; }
          }
          """,
            "Plain",
            "@dev.trufflecel.CelPojo class Plain { public int x; }");
    assertTrue(result.diagnostics, result.success);
    assertEquals(3, result.generated.size());
    String source = result.generated.get("sample/BeanCel.java");
    assertTrue(source.contains("value.name"));
    assertTrue(source.contains("value.value()"));
    assertTrue(source.contains("sample.Bean_NestedCel.INSTANCE"));
    assertFalse(source.contains("reflect"));
    assertTrue(compile(false, "Empty", "class Empty {}").success);
    var discovery =
        compile(
            true,
            false,
            "Discovered",
            "@dev.trufflecel.CelPojo class Discovered { public int id; }");
    assertTrue(discovery.diagnostics, discovery.success);
    assertTrue(discovery.generated.containsKey("DiscoveredCel.java"));
  }

  @Test
  public void resolvesNestedSchemasFromAnAlreadyCompiledArtifact() throws Exception {
    var result =
        compile(
            true,
            true,
            true,
            "foreign.Child",
            "package foreign; @dev.trufflecel.CelPojo(\"external.Child\") public record Child(long id) {}",
            "local.Parent",
            "package local; @dev.trufflecel.CelPojo public record Parent(foreign.Child child) {}");
    assertTrue(result.diagnostics, result.success);
    assertTrue(result.generated.get("local/ParentCel.java").contains("external.Child"));
    assertTrue(result.generated.get("local/ParentCel.java").contains("foreign.ChildCel.INSTANCE"));
  }

  @Test
  public void supportsScalarGettersContainersEnumsAndRecordComponents() throws Exception {
    var result =
        compile(
            true,
            "fixture.All",
            """
        package fixture;
        import dev.trufflecel.*;
        import java.util.*;
        import java.time.*;
        @CelPojo class All {
          public byte a; public short b; public int c; public long d;
          public float e; public double f; public boolean g; public char h;
          public Byte i; public Short j; public Integer k; public Long l;
          public Float m; public Double n; public Boolean o; public Character p;
          public String q; public UInt r; public Bytes s; public Instant t; public Duration u;
          public byte[] bytes; public Choice choice;
          public List<Record> records; public Map<String, List<Integer>> groups;
          public Map<Boolean, Choice> choices; public Map<UInt, String> unsigned;
          public static Object staticValue;
          @CelField(ignore = true) private Object hidden;
          @CelField(ignore = true) public Object ignored;
          public void setName(String name) {}
          public int ordinary() {return 1;}
          public int get() {return 1;}
          public int is() {return 1;}
          public String isText() {return "x";}
          public boolean isGood() {return true;}
          public Boolean isReady() {return true;}
          public String getURL() {return "url";}
          public int getX() {return 1;}
          public String getlower() {return "lower";}
          @CelField public String getLabel() {return "label";}
          @CelField(ignore = true) public void ignore(int value) {}
          @CelField(ignore = true) private int privateMethod() {return 1;}
          @CelField(ignore = true) public Object getIgnored() {return null;}
          enum Choice { FIRST }
          @CelPojo("fixture.Child") public record Record(@CelField("number") int value,
              @CelField(ignore = true) Object ignored) {}
        }
        """);
    assertTrue(result.diagnostics, result.success);
    String source = result.generated.get("fixture/AllCel.java");
    assertTrue(source.contains("\"label\""));
    assertFalse(source.contains("staticValue"));
    assertFalse(source.contains("ignored"));
    assertTrue(source.contains("context.enumeration"));
    assertTrue(source.contains("context.map"));
  }

  @Test
  public void diagnosesInvalidDeclarationsMembersAndUnsupportedShapes() throws Exception {
    Map<String, String> invalid =
        Map.ofEntries(
            Map.entry("@CelPojo interface Bad {}", "class or record"),
            Map.entry(
                "@CelPojo class Bad { public int getFailure() throws Exception { return 0; } }",
                "must not declare exceptions"),
            Map.entry("@CelPojo class Bad<T> { public T value; }", "generic POJO"),
            Map.entry("class Bad { @CelPojo private static class Hidden {} }", "accessible"),
            Map.entry("@CelPojo(\"bad-name\") class Bad {}", "type name"),
            Map.entry("@CelPojo class Bad { @CelField private int x; }", "public instance"),
            Map.entry("@CelPojo class Bad { @CelField public static int x; }", "public instance"),
            Map.entry("@CelPojo class Bad { @CelField public void x() {} }", "zero-argument"),
            Map.entry(
                "@CelPojo class Bad { @CelField public int x(int a) {return a;} }",
                "zero-argument"),
            Map.entry(
                "@CelPojo class Bad { @CelField public int x() throws Exception {return 0;} }",
                "zero-argument"),
            Map.entry("@CelPojo class Bad { @CelField(\"bad-name\") public int x; }", "field name"),
            Map.entry(
                "@CelPojo class Bad { public int x; public int getX() {return x;} }",
                "duplicate CEL field"),
            Map.entry("@CelPojo class Bad { public Object x; }", "unsupported POJO field"),
            Map.entry("@CelPojo class Bad { public int[] x; }", "unsupported POJO field"),
            Map.entry("@CelPojo class Bad { public java.util.List x; }", "unsupported POJO field"),
            Map.entry("@CelPojo class Bad { public java.util.Map x; }", "unsupported POJO field"),
            Map.entry(
                "@CelPojo class Bad { public java.util.List<?> x; }", "unsupported POJO field"),
            Map.entry("@CelPojo class Bad { public java.util.Map<Double, String> x; }", "map keys"),
            Map.entry("@CelPojo class Bad {} class BadCel {}", "cannot generate adapter"));
    for (var entry : invalid.entrySet()) {
      var result = compile(true, "Bad", "import dev.trufflecel.*; " + entry.getKey());
      assertFalse(entry.getKey(), result.success);
      assertTrue(result.diagnostics, result.diagnostics.contains(entry.getValue()));
    }
  }
}
