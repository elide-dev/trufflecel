package dev.trufflecel.processor;

import java.io.IOException;
import java.util.*;
import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.*;
import javax.tools.Diagnostic;

/** Build-time generation only; emitted adapters contain direct Java access and no reflection. */
@SupportedAnnotationTypes({"dev.trufflecel.CelPojo", "dev.trufflecel.CelField"})
public final class PojoProcessor extends AbstractProcessor {
  private static final String POJO = "dev.trufflecel.CelPojo", FIELD = "dev.trufflecel.CelField";
  private boolean invalid;
  private int variable;

  @Override
  public SourceVersion getSupportedSourceVersion() {
    return SourceVersion.latestSupported();
  }

  @Override
  public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
    TypeElement annotation = processingEnv.getElementUtils().getTypeElement(POJO);
    if (annotation == null) return false;
    for (Element element : round.getElementsAnnotatedWith(annotation)) {
      var type = (TypeElement) element;
      generate(type);
    }
    return true;
  }

  private record Property(String name, String access, TypeMirror type, Element element) {}

  private record Conversion(String type, String expression) {}

  private void generate(TypeElement type) {
    invalid = false;
    variable = 0;
    if (type.getKind() != ElementKind.CLASS && type.getKind() != ElementKind.RECORD) {
      error(type, "@CelPojo requires a class or record");
      return;
    }
    if (!type.getTypeParameters().isEmpty()) {
      error(type, "generic POJO declarations are unsupported");
      return;
    }
    for (Element enclosing = type;
        enclosing instanceof TypeElement;
        enclosing = enclosing.getEnclosingElement()) {
      if (enclosing.getModifiers().contains(Modifier.PRIVATE)) {
        error(type, "POJO types must be accessible from a generated package adapter");
        return;
      }
    }
    String name = annotation(type, POJO).get("value").toString();
    if (name.isEmpty()) name = type.getQualifiedName().toString();
    if (!name.matches("[A-Za-z_][A-Za-z_0-9]*(\\.[A-Za-z_][A-Za-z_0-9]*)*")) {
      error(type, "invalid CEL object type name: " + name);
      return;
    }
    var properties = new LinkedHashMap<String, Property>();
    if (type.getKind() == ElementKind.RECORD) {
      for (RecordComponentElement component : type.getRecordComponents())
        property(
            properties,
            component,
            component.getSimpleName().toString(),
            component.getSimpleName() + "()",
            component.asType());
    } else {
      for (Element member : processingEnv.getElementUtils().getAllMembers(type)) {
        var configuration = annotation(member, FIELD);
        if (configuration != null && Boolean.TRUE.equals(configuration.get("ignore"))) continue;
        if (!member.getModifiers().contains(Modifier.PUBLIC)
            || member.getModifiers().contains(Modifier.STATIC)) {
          if (configuration != null)
            error(member, "@CelField requires a public instance field or getter");
          continue;
        }
        if (member.getKind() == ElementKind.FIELD) {
          property(
              properties,
              member,
              member.getSimpleName().toString(),
              member.getSimpleName().toString(),
              member.asType());
        } else if (member instanceof ExecutableElement method) {
          String getter = getter(method);
          if (getter != null)
            property(
                properties, member, getter, member.getSimpleName() + "()", method.getReturnType());
          else if (configuration != null)
            error(member, "@CelField requires a zero-argument non-void getter");
        }
      }
    }
    var fields = new ArrayList<String>();
    var copies = new StringBuilder();
    for (Property property : properties.values()) {
      Conversion conversion = convert(property.type, "value." + property.access, property.element);
      fields.add("java.util.Map.entry(\"" + property.name + "\", " + conversion.type + ")");
      copies
          .append("    fields.put(\"")
          .append(property.name)
          .append("\", ")
          .append(conversion.expression)
          .append(");\n");
    }
    if (invalid) return;
    String pkg = processingEnv.getElementUtils().getPackageOf(type).getQualifiedName().toString();
    String adapter = adapterName(type);
    String simple = adapter.substring(adapter.lastIndexOf('.') + 1);
    String source =
        (pkg.isEmpty() ? "" : "package " + pkg + ";\n")
            + "@javax.annotation.processing.Generated(\"dev.trufflecel.processor.PojoProcessor\")\n"
            + "public final class "
            + simple
            + " extends dev.trufflecel.PojoType<"
            + type.getQualifiedName()
            + "> {\n"
            + "  public static final "
            + simple
            + " INSTANCE = new "
            + simple
            + "();\n"
            + "  private "
            + simple
            + "() { super(\""
            + name
            + "\", java.util.Map.ofEntries("
            + String.join(", ", fields)
            + ")); }\n"
            + "  @Override protected java.util.Map<String, Object> snapshotFields("
            + type.getQualifiedName()
            + " value, SnapshotContext context) {\n"
            + "    var fields = new java.util.LinkedHashMap<String, Object>();\n"
            + copies
            + "    return fields;\n  }\n}\n";
    try (var writer = processingEnv.getFiler().createSourceFile(adapter, type).openWriter()) {
      writer.write(source);
    } catch (IOException failure) {
      error(type, "cannot generate adapter: " + failure.getMessage());
    }
  }

  private String getter(ExecutableElement method) {
    if (!method.getParameters().isEmpty() || method.getReturnType().getKind() == TypeKind.VOID)
      return null;
    if (method.getEnclosingElement().toString().equals("java.lang.Object")) return null;
    String name = method.getSimpleName().toString();
    String property = null;
    if (name.startsWith("get") && name.length() > 3) property = name.substring(3);
    else if (name.startsWith("is")
        && name.length() > 2
        && (method.getReturnType().getKind() == TypeKind.BOOLEAN
            || method.getReturnType().toString().equals("java.lang.Boolean")))
      property = name.substring(2);
    if (property == null) {
      if (annotation(method, FIELD) == null) return null;
      property = name;
    }
    if (!method.getThrownTypes().isEmpty()) {
      error(method, "POJO getter must not declare exceptions");
      return null;
    }
    return property.length() > 1
            && Character.isUpperCase(property.charAt(0))
            && Character.isUpperCase(property.charAt(1))
        ? property
        : Character.toLowerCase(property.charAt(0)) + property.substring(1);
  }

  private void property(
      Map<String, Property> fields, Element element, String name, String access, TypeMirror type) {
    var annotation = annotation(element, FIELD);
    if (annotation != null) {
      if (Boolean.TRUE.equals(annotation.get("ignore"))) return;
      String override = annotation.get("value").toString();
      if (!override.isEmpty()) name = override;
    }
    if (!name.matches("[A-Za-z_][A-Za-z_0-9]*")) error(element, "invalid CEL field name: " + name);
    else if (fields.putIfAbsent(name, new Property(name, access, type, element)) != null)
      error(element, "duplicate CEL field: " + name);
  }

  private Conversion convert(TypeMirror mirror, String access, Element element) {
    String scalar =
        switch (mirror.getKind()) {
          case BOOLEAN -> "BOOL";
          case BYTE, SHORT, INT, LONG -> "INT";
          case CHAR -> "STRING";
          case FLOAT, DOUBLE -> "DOUBLE";
          default -> null;
        };
    if (mirror instanceof ArrayType array && array.getComponentType().getKind() == TypeKind.BYTE)
      scalar = "BYTES";
    if (mirror instanceof DeclaredType declared) {
      TypeElement type = (TypeElement) declared.asElement();
      String name = type.getQualifiedName().toString();
      scalar =
          switch (name) {
            case "java.lang.Boolean" -> "BOOL";
            case "java.lang.Byte", "java.lang.Short", "java.lang.Integer", "java.lang.Long" ->
                "INT";
            case "java.lang.Float", "java.lang.Double" -> "DOUBLE";
            case "java.lang.String", "java.lang.Character" -> "STRING";
            case "dev.trufflecel.UInt" -> "UINT";
            case "dev.trufflecel.Bytes" -> "BYTES";
            case "java.time.Instant" -> "TIMESTAMP";
            case "java.time.Duration" -> "DURATION";
            default -> null;
          };
      if (type.getKind() == ElementKind.ENUM)
        return new Conversion(
            "dev.trufflecel.CelType.STRING", "context.enumeration(" + access + ")");
      var annotation = annotation(type, POJO);
      if (annotation != null) {
        String celName = annotation.get("value").toString();
        if (celName.isEmpty()) celName = name;
        return new Conversion(
            "dev.trufflecel.CelType.of(\"" + celName + "\")",
            "context.object(" + access + ", " + adapterName(type) + ".INSTANCE)");
      }
      if (name.equals("java.util.List") && declared.getTypeArguments().size() == 1) {
        String local = "item" + variable++;
        Conversion child = convert(declared.getTypeArguments().get(0), local, element);
        return new Conversion(
            "dev.trufflecel.CelType.list(" + child.type + ")",
            "context.list(" + access + ", " + local + " -> " + child.expression + ")");
      }
      if (name.equals("java.util.Map") && declared.getTypeArguments().size() == 2) {
        String key = "key" + variable++, value = "item" + variable++;
        Conversion keyConversion = convert(declared.getTypeArguments().get(0), key, element);
        Conversion valueConversion = convert(declared.getTypeArguments().get(1), value, element);
        if (!Set.of(
                "dev.trufflecel.CelType.STRING",
                "dev.trufflecel.CelType.INT",
                "dev.trufflecel.CelType.UINT",
                "dev.trufflecel.CelType.BOOL")
            .contains(keyConversion.type))
          error(element, "CEL map keys must be string, integer or boolean");
        return new Conversion(
            "dev.trufflecel.CelType.map(" + keyConversion.type + ", " + valueConversion.type + ")",
            "context.map("
                + access
                + ", "
                + key
                + " -> "
                + keyConversion.expression
                + ", "
                + value
                + " -> "
                + valueConversion.expression
                + ")");
      }
    }
    if (scalar != null)
      return new Conversion("dev.trufflecel.CelType." + scalar, "context.scalar(" + access + ")");
    error(element, "unsupported POJO field type: " + mirror);
    return new Conversion("dev.trufflecel.CelType.DYN", "null");
  }

  private String adapterName(TypeElement type) {
    String pkg = processingEnv.getElementUtils().getPackageOf(type).getQualifiedName().toString();
    String qualified = type.getQualifiedName().toString();
    String local = pkg.isEmpty() ? qualified : qualified.substring(pkg.length() + 1);
    return (pkg.isEmpty() ? "" : pkg + ".") + local.replace('.', '_') + "Cel";
  }

  private Map<String, Object> annotation(Element element, String name) {
    for (AnnotationMirror annotation : element.getAnnotationMirrors()) {
      if (!annotation.getAnnotationType().toString().equals(name)) continue;
      var result = new HashMap<String, Object>();
      processingEnv
          .getElementUtils()
          .getElementValuesWithDefaults(annotation)
          .forEach((key, value) -> result.put(key.getSimpleName().toString(), value.getValue()));
      return result;
    }
    return null;
  }

  private void error(Element element, String message) {
    invalid = true;
    processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, element);
  }
}
