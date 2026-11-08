package dev.trufflecel;

import static org.junit.Assert.*;
import java.util.*;
import org.junit.Test;

public class ExtensionsTest {
  private static void yes(String expression) {
    assertTrue(expression, Cel.compile(expression).test());
  }

  private static void invalid(String expression) {
    assertThrows(
        expression,
        CelException.class,
        () -> Cel.newBuilder().build().parse(expression).evaluate());
  }

  @Test
  public void optionalListAndSquareRootExtensions() {
    yes("math.sqrt(9.0) == 3.0 && math.isNaN(math.sqrt(-1.0))");
    yes("optional.unwrap([optional.of(1), optional.none(), optional.of(2)]) == [1, 2]");
    yes("optional.unwrap([]) == []");
    yes("[null].first() == optional.of(null)");
    yes("[1, 2].first().value() == 1 && [1, 2].last().value() == 2");
    yes("![].first().hasValue() && ![].last().hasValue()");
    invalid("optional.unwrap(dyn([1]))");
    invalid("math.sqrt(dyn(1))");
    for (String expression : List.of("math.sqrt(1)", "optional.unwrap([1])", "1.first()"))
      assertThrows(CelException.class, () -> Cel.compile(expression));
  }

  @Test
  public void regexReplacementAndExtraction() {
    yes("regex.replace('ab12cd34', '[0-9]+', 'x') == 'abxcdx'");
    yes("regex.replace('aaaa', 'a', 'b', 2) == 'bbaa'");
    yes("regex.replace('aaaa', 'a', 'b', -2) == 'bbbb'");
    yes("regex.replace('x', '[', 'bad', 0) == 'x'");
    yes("regex.replace('ab', '(a)(b)', r'\\2\\1-\\0-$1') == 'ba-ab-$1'");
    yes("regex.replace('b', '(a)?b', r'\\1') == ''");
    yes("regex.replace('x', 'x', r'\\\\') == '\\\\'");
    yes("regex.replace('abc', 'z', r'\\q') == 'abc'");
    yes("regex.replace('ab', '', '-') == '-a-b-'");
    yes("regex.extract('abc12', '[0-9]+') == optional.of('12')");
    yes("regex.extract('abc12', '([0-9]+)') == optional.of('12')");
    yes("!regex.extract('b', '(a)?b').hasValue()");
    yes("!regex.extract('z', '(a)(b)').hasValue()");
    yes("regex.extractAll('a1b22', '[0-9]+') == ['1', '22']");
    yes("regex.extractAll('a1b22', '([0-9]+)') == ['1', '22']");
    yes("regex.extractAll('bb', '(a)?b') == []");
    yes("regex.extractAll('ab', '') == ['', '', '']");
    yes("regex.extractAll('z', 'x') == []");
    for (String expression :
        List.of(
            "regex.replace('x', '[', 'y')",
            "regex.extract('x', '[')",
            "regex.replace('x', 'x', r'\\q')",
            "regex.replace('x', 'x', r'\\2')",
            "regex.replace('x', 'x', r'\\')",
            "regex.extract('ab', '(a)(b)')",
            "regex.extractAll('z', '(a)(b)')")) invalid(expression);
    assertThrows(
        CelException.class,
        () ->
            Cel.newBuilder()
                .variable("r", CelType.STRING)
                .build()
                .compile("regex.replace('x', 'x', r)")
                .evaluate(Map.of("r", "\\")));
    var env = Cel.newBuilder().variable("s", CelType.STRING).evaluationBudget(80).build();
    var error =
        assertThrows(
            CelException.class,
            () ->
                env.compile("regex.replace(s, '(x)', r'\\1\\1\\1\\1\\1\\1\\1\\1')")
                    .evaluate(Map.of("s", "xxxxxxxxxx")));
    assertEquals(CelException.Code.RESOURCE_EXHAUSTED, error.code());
  }

  @Test
  public void optionalLazinessPresenceAndLiteralValidation() {
    yes("optional.of(1).or(optional.of(1/0)).value() == 1");
    yes("optional.of(1).orValue(1/0) == 1");
    yes("[?optional.none(), ?optional.of(null)] == [null]");
    yes("{?'a': optional.none(), 'a': 2} == {'a': 2}");
    yes(".optional.of(1).value() == 1");
    yes("optional.ofNonZeroValue(b'').hasValue() == false");
    yes("optional.ofNonZeroValue(duration('0s')).hasValue() == false");
    yes("optional.ofNonZeroValue(timestamp(0)).hasValue() == false");
    yes("[1][?-1] == optional.none()");
    yes("[1][?dyn(18446744073709551615u)] == optional.none()");
    for (String expr :
        List.of(
            "optional.none().value()",
            "dyn(1).hasValue()",
            "dyn(1).orValue(2)",
            "optional.none().or(dyn(1))",
            "[?dyn(1)]",
            "{?'x': dyn(1)}",
            "dyn(1)[?0]",
            "optional.of(1).x",
            "optional.of(1).optFlatMap(x,dyn(x))")) invalid(expr);
    for (String expr :
        List.of(
            "[?1]",
            "{?'a':1}",
            "1.hasValue()",
            "optional.of(1).orValue('x')",
            "optional.of(1).or(2)",
            "1.optMap(x,x)",
            "optional.of(1).optFlatMap(x,x)"))
      assertThrows(expr, CelException.class, () -> Cel.newBuilder().build().compile(expr));
    Environment e = Cel.newBuilder().variable("x", CelType.DYN).build();
    assertEquals(Unknown.of("x"), e.compile("x.orValue(1)").evaluate(Map.of("x", Unknown.of("x"))));
    assertEquals(
        Unknown.of("x"),
        e.compile("optional.of(1).optMap(v,x)").evaluate(Map.of("x", Unknown.of("x"))));
    assertEquals(
        Set.of("external"),
        Cel.compile("[1].transformList(i,v, optional.of(v).optMap(i,i+external).value())")
            .variables());
  }

  @Test
  public void twoVariableScopesTransformsAndErrorSuppression() {
    yes("[2,3].transformMap(i,v,i+v) == {0:2,1:4}");
    yes("{'a':1,'b':2}.transformMapEntry(k,v,{v:k}) == {1:'a',2:'b'}");
    yes("{'a':1,'b':2}.transformMapEntry(k,v,v==2,{v:k}) == {2:'b'}");
    yes("[1,2].transformList(i,v,[3,4].transformList(i,v,i+v)) == [[3,5],[3,5]]");
    yes("[1,2].all(i,v,i==0 || [2,3].exists(i,v,i==1 && v==3))");
    yes("[1,2].existsOne(i,v,i+v==3)");
    yes("[1,2].existsOne(v,v==2)");
    invalid("{'a':1,'b':2}.transformMapEntry(k,v,{0:v})");
    invalid("{'a':1}.transformMapEntry(k,v,dyn(v))");
    for (String expr :
        List.of(
            "[1].all(i,i,true)",
            "[1].transformList(1,v,v)",
            "[1].transformList(i,v)",
            "[1].transformMapEntry(i,v,v)",
            "{}.sortBy(v,v)",
            "[[1]].sort()",
            "[1].sortBy(v,[v])"))
      assertThrows(expr, CelException.class, () -> Cel.newBuilder().build().compile(expr));
    Environment env = Cel.newBuilder().variable("u", CelType.DYN).build();
    for (String expr :
        List.of(
            "[1].transformList(i,v,u)",
            "[1].transformList(i,v,true,u)",
            "[1].sortBy(v,u)",
            "[1].existsOne(i,v,u)"))
      assertEquals(Unknown.of("u"), env.compile(expr).evaluate(Map.of("u", Unknown.of("u"))));
  }

  @Test
  public void sortByRejectsDynamicMapReceiversAfterEvaluatingKeys() {
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    Environment env =
        Cel.newBuilder()
            .variable("xs", CelType.DYN)
            .variable("key", CelType.DYN)
            .function("key", List.of(), CelType.INT, args -> (long) calls.incrementAndGet())
            .build();
    String source = "xs.sortBy(x, x)";
    var parsed = CanonicalAst.parse(env, source);
    for (Program program :
        List.of(
            env.compile(source),
            env.parse(source),
            env.compile("dyn(xs).sortBy(x, x)"),
            CanonicalAst.program(env, parsed),
            CanonicalAst.program(env, CanonicalAst.check(env, parsed)))) {
      for (var receiver : List.of(Map.of(), Map.of("a", 1L, "b", 2L))) {
        var input = Map.of("xs", receiver);
        assertEquals(
            CelException.Code.NO_OVERLOAD,
            assertThrows(CelException.class, () -> program.evaluate(input)).code());
        assertEquals(
            CelException.Code.NO_OVERLOAD,
            assertThrows(CelException.class, () -> program.trace(event -> {}).evaluate(input))
                .code());
      }
      assertEquals(List.of(1L, 2L), program.evaluate(Map.of("xs", List.of(2L, 1L))));
      assertEquals(List.of(), program.evaluate(Map.of("xs", List.of())));
    }
    assertEquals(
        CelException.Code.NO_OVERLOAD,
        assertThrows(CelException.class, () -> env.parse("{}.sortBy(x, x)").evaluate()).code());
    var keys = env.compile("xs.sortBy(x, key())");
    assertEquals(
        CelException.Code.NO_OVERLOAD,
        assertThrows(
                CelException.class, () -> keys.evaluate(Map.of("xs", Map.of("a", 1L, "b", 2L))))
            .code());
    assertEquals(2, calls.get());
    for (String expression : List.of("xs.sortBy(x, key)", "xs.sortBy(x, 1 / key)")) {
      for (Program program :
          List.of(
              env.compile(expression),
              CanonicalAst.program(env, CanonicalAst.parse(env, expression)))) {
        assertEquals(
            Unknown.of("key"),
            program.evaluate(Map.of("xs", Map.of("a", 1L), "key", Unknown.of("key"))));
        if (expression.contains("/"))
          assertEquals(
              CelException.Code.DIVIDE_BY_ZERO,
              assertThrows(
                      CelException.class,
                      () -> program.evaluate(Map.of("xs", Map.of("a", 1L), "key", 0L)))
                  .code());
      }
    }
  }

  @Test
  public void unicodeStringsFormattingAndLimits() {
    yes("'a😀b'.substring(1,2) == '😀' && 'a😀b'.indexOf('b') == 2");
    yes("'\\u001ctext\\u001f'.trim() == '\\u001ctext\\u001f'");
    yes("'a😀b'.reverse() == 'b😀a'");
    yes("'😀a'.split('') == ['😀','a'] && ''.split('') == []");
    yes("'abc'.split('',2) == ['a','bc']");
    yes("'ab'.replace('','x') == 'xaxbx'");
    yes("'ab'.replace('','x',2) == 'xaxb'");
    yes("'ab'.replace('a','x',0) == 'ab'");
    yes("strings.quote('\\u0001') == '\"\\\\u0001\"'");
    yes("'%.0f'.format([2.5]) == '2'");
    yes("'%.0e'.format([2.5]) == '2e+00'");
    yes("'%.0e'.format([9.5]) == '1e+01'");
    yes("'%.2e'.format([-0.00125]) == '-1.25e-03'");
    yes("'%.1e %.1f'.format([-0.0,-0.0]) == '-0.0e+00 -0.0'");
    yes("'%x'.format([18446744073709551615u]) == 'ffffffffffffffff'");
    for (String expr :
        List.of(
            "'%'.format([])",
            "'%.f'.format([1.0])",
            "'%.1001f'.format([1.0])",
            "'%d'.format([1.5])",
            "dyn([1]).join()",
            "'a'.charAt(9223372036854775807)",
            "'a'.split('a',9223372036854775807)",
            "'a'.replace('a','b',9223372036854775807)",
            "[1].slice(0,9223372036854775807)")) invalid(expr);
    for (String expr :
        List.of(
            "lists.range(100)",
            "'abc'.replace('','012345678901234567890123456789')",
            "['longstring','longstring'].join('longseparator')",
            "[1,2,3,4,5].distinct()",
            "[[1,2],[3,4]].flatten()")) {
      CelException ex =
          assertThrows(
              expr,
              CelException.class,
              () -> Cel.newBuilder().evaluationBudget(10).build().compile(expr).evaluate());
      assertEquals(expr, CelException.Code.RESOURCE_EXHAUSTED, ex.code());
    }
  }

  @Test
  public void nestedFormattingConsumesBudgetBeforeBuildingOutput() {
    var env = Cel.newBuilder().variable("items", CelType.DYN).evaluationBudget(100).build();
    var program = env.compile("'%s'.format([items])");
    CelException e =
        assertThrows(
            CelException.class, () -> program.evaluate(Map.of("items", List.of("x".repeat(200)))));
    assertEquals(CelException.Code.RESOURCE_EXHAUSTED, e.code());
  }

  @Test
  public void numericBoundariesAndOverloadValidation() {
    yes("math.greatest(9223372036854775807,9223372036854775808.0) == 9223372036854775808.0");
    yes("math.least(9223372036854775807,9223372036854775808.0) == 9223372036854775807");
    yes("math.round(4503599627370497.0) == 4503599627370497.0");
    yes("math.isNaN(math.greatest([1.0,0.0/0.0]))");
    yes("math.greatest([1.0,1.0/0.0]) == 1.0/0.0");
    yes("math.bitShiftRight(-1,63) == 1 && math.bitShiftLeft(1u,63) == 9223372036854775808u");
    for (String expr :
        List.of(
            "math.greatest([])",
            "math.least(dyn(['x']))",
            "math.greatest()",
            "math.bitAnd(1,1u)",
            "math.bitShiftLeft(1,1u)",
            "math.ceil(1)",
            "math.abs(true)",
            "math.sign(1,2)",
            "[1,1u].sort()")) invalid(expr);
    for (String expr :
        List.of(
            "math.greatest()",
            "math.ceil(1)",
            "math.bitAnd(1,1u)",
            "math.abs(true)",
            "'a'.split(1)",
            "'a'.replace(1,2)",
            "[1].join()",
            "lists.range('1')",
            "cidr(1)",
            "ip(1)",
            "1.family()"))
      assertThrows(expr, CelException.class, () -> Cel.newBuilder().build().compile(expr));
  }

  @Test
  public void networkParsingNeverResolvesNamesAndKeepsAddressBits() {
    yes("ip('ff12::1').isLinkLocalMulticast() && !ip('ff01::1').isLinkLocalMulticast()");
    yes("!isCIDR('::/01') && !isCIDR('1.2.3.4/+24')");
    yes("!isIP('example.com') && !isIP('127.1') && !isIP('012.0.0.1') && !isIP('1.2.3.256')");
    yes("!isIP('') && !isIP('1:2:3:4:5:6:7') && !isIP('1:2:3:4:5:6:7:8:9')");
    yes("!isIP('1::2::3') && !isIP('1:2:3:4:5:6:7:') && !isIP('[]')");
    yes("string(ip('1:0:0:2:0:0:3:4')) == '1::2:0:0:3:4'");
    yes("string(ip('1:2:3:4:5:6:0:0')) == '1:2:3:4:5:6::'");
    yes("string(ip('1:2:3:4:5:6:7:8')) == '1:2:3:4:5:6:7:8'");
    yes("string(ip('::192.0.2.1')) == '::c000:201'");
    yes(
        "isCIDR('1.2.3.4/0') && !isCIDR('1.2.3.4/33') && !isCIDR('::/129') && !isCIDR('::') && !isCIDR('::/-1')");
    yes("cidr('1.2.3.4/0').masked() == cidr('0.0.0.0/0') && cidr('::/0').containsIP('ffff::')");
    yes("cidr('1.2.3.4/24') != cidr('1.2.3.0/24') && cidr('1.2.3.4/24').ip() == ip('1.2.3.4')");
    invalid("cidr('1.2.3.0/24').containsIP('example.com')");
    invalid("cidr('1.2.3.0/24').containsCIDR('example.com')");
    yes("!isIP('123456789012345678901234567890123456789012345678901234567890')");
  }

  @Test
  public void setsAndBase64ValidationAndBudgets() {
    yes("sets.equivalent([1,1,2u], [2.0,1])");
    yes("sets.contains([[1], {'a': 2}], [{'a': 2u}, [1.0]])");
    yes("!sets.intersects([1], [])");
    yes("base64.encodeUrl(b'\\xfb\\xff') == '-_8='");
    yes("base64.decodeUrl('-_8=') == b'\\xfb\\xff'");
    yes("base64.decodeUrl('-_8') == b'\\xfb\\xff'");
    yes("base64.decodeUrl('-_\\r\\n8=') == b'\\xfb\\xff'");
    yes("base64.encodeUrl(b'') == '' && base64.decodeUrl('') == b''");
    for (String text : List.of("+/8=", "x", "x===", "-_8==", "-_ 8="))
      invalid("base64.decodeUrl('" + text + "')");
    yes("base64.encode(b'') == '' && base64.decode('') == b''");
    yes("base64.decode('aG\\r\\nVsbG8=') == b'hello'");
    for (String expr :
        List.of(
            "sets.contains(1, [])",
            "sets.equivalent([], 1)",
            "sets.intersects([], [], [])",
            "base64.encode('x')",
            "base64.decode(b'x')")) {
      assertThrows(CelException.class, () -> Cel.compile(expr));
      invalid(expr);
    }
    for (String text : List.of("!", "a", "aGVsbG8===", "aG Vs", "_-=="))
      invalid("base64.decode('" + text + "')");
    assertEquals(
        CelException.Code.RESOURCE_EXHAUSTED,
        assertThrows(
                CelException.class,
                () ->
                    Cel.newBuilder()
                        .evaluationBudget(8)
                        .build()
                        .compile("base64.encode(b'1234567')")
                        .evaluate())
            .code());
    assertEquals(
        CelException.Code.RESOURCE_EXHAUSTED,
        assertThrows(
                CelException.class,
                () ->
                    Cel.newBuilder()
                        .evaluationBudget(40)
                        .build()
                        .compile("sets.contains([1,2,3,4,5], [5,5,5,5,5,5,5,5])")
                        .evaluate())
            .code());
  }

  @Test
  public void blockLazinessCachingAndLexicalScopes() {
    yes("cel.block([1/0], true)");
    yes("cel.block([1/0], true || cel.index(0) == 1)");
    yes("cel.block([1/0], (cel.index(0) == 1 || true) && (cel.index(0) == 1 || true))");
    yes("cel.block([], 3) == 3");
    yes(".cel.bind(x, 3, .cel.block([x + 1], cel.index(0))) == 4");
    yes("cel.block([2], cel.block([3], cel.index(0)) + cel.index(0)) == 5");
    yes("cel.bind(x, 5, cel.block([x], [1,2].map(x, cel.index(0) + x))) == [6,7]");
    yes("cel.block([[1,2].map(x,x+1)], [3,4].map(x, cel.index(0)[0]+x)) == [5,6]");
    yes("cel.block([[1,2].map(x,x+1), [3,4].map(x,cel.index(0)[0]+x)], cel.index(1)) == [5,6]");
    yes("cel.bind(x, 2, cel.bind(x, x+1, x)) == 3");
    assertEquals(
        Set.of("external"),
        Cel.compile("cel.bind(x, external, cel.block([x], cel.index(0)))").variables());
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    Environment env =
        Cel.newBuilder()
            .function("count", List.of(), CelType.INT, args -> (long) calls.incrementAndGet())
            .build();
    var program = env.compile("cel.block([count()], cel.index(0) + cel.index(0))");
    assertEquals(2L, program.evaluate());
    assertEquals(4L, program.evaluate());
    assertEquals(2, calls.get());
    assertEquals(CelType.INT, program.resultType());
    assertEquals(
        CelException.Code.RESOURCE_EXHAUSTED,
        assertThrows(
                CelException.class,
                () ->
                    Cel.newBuilder()
                        .evaluationBudget(5)
                        .build()
                        .compile("cel.block([1,2,3,4,5], true)")
                        .evaluate())
            .code());
    assertEquals(
        CelException.Code.RESOURCE_EXHAUSTED,
        assertThrows(
                CelException.class,
                () ->
                    Cel.newBuilder()
                        .maxDepth(5)
                        .build()
                        .compile(
                            "cel.block([1,cel.index(0),cel.index(1),cel.index(2),cel.index(3),cel.index(4),cel.index(5)], cel.index(6))")
                        .evaluate())
            .code());
  }

  @Test
  public void malformedBindingAndBlockSyntax() {
    for (String expr :
        List.of(
            "cel.other()",
            "proto.other()",
            ".proto.getExt(1, foo)",
            "cel.bind(.x, 1, 2)",
            "cel.bind()",
            "cel.bind(1, 2, 3)",
            "cel.bind(x.y, 2, 3)",
            "cel.block(1, 2)",
            "cel.block([])",
            "cel.block([?optional.of(1)], 1)",
            "cel.index()",
            "cel.index(-1)",
            "cel.index(1u)",
            "cel.index(2147483648)",
            "cel.index(0)",
            "cel.block([], cel.index(0))",
            "cel.block([cel.index(0)], 1)",
            "cel.block([cel.index(1), 2], cel.index(0))",
            "cel.iterVar(0)",
            "cel.iterVar(0, -1)",
            "proto.getExt(1)",
            "proto.hasExt(1, 2)"))
      assertThrows(expr, CelException.class, () -> Cel.newBuilder().build().compile(expr));
    invalid("cel.bind(x, 1, x/0)");
    invalid("cel.block([1/0], cel.index(0))");
  }

  @Test
  public void blockCachesErrorsAndKeepsConcurrentEvaluationsSeparate() throws Exception {
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    var env =
        Cel.newBuilder()
            .function(
                "fail",
                List.of(),
                CelType.INT,
                args -> {
                  calls.incrementAndGet();
                  throw new CelException(CelException.Code.INVALID_ARGUMENT, "failure");
                })
            .build();
    assertTrue(
        env.compile(
                "cel.block([fail()], (cel.index(0) == 1 || true) && (cel.index(0) == 2 || true))")
            .test());
    assertEquals(1, calls.get());
    var program = Cel.compile("cel.block([x + 1, cel.index(0) * 2], cel.index(1) + cel.index(0))");
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(4)) {
      var tasks = new ArrayList<java.util.concurrent.Callable<Object>>();
      for (long i = 0; i < 100; i++) {
        long input = i;
        tasks.add(() -> program.evaluate(Map.of("x", input)));
      }
      var results = executor.invokeAll(tasks);
      for (int i = 0; i < results.size(); i++) assertEquals(3L * (i + 1), results.get(i).get());
    }
  }
}
