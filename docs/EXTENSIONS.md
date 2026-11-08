# CEL extension support

TruffleCEL implements the pinned upstream optional-value, two-variable comprehension,
string, list, math, network, bindings, block, set, encoder, and proto2 extension suites.
All 2,771 cases in the pinned corpus execute, including plumbing tests. The
independent parser, checker and Truffle evaluator handle these features directly; no CEL Java
frontend or interpreter is used. Language extensions are available in the core JAR. Structured encoders use Gson streaming and SnakeYAML Engine data-only APIs; descriptor
registration for protobuf extensions uses the optional protobuf adapter.

## Bindings and blocks

`cel.bind(name, initializer, result)` binds a local variable within the result expression.
Bindings may nest and shadow outer variables; the initializer sees the outer scope.
TruffleCEL evaluates the initializer once before the result. Local writes and reads remain
visible to Truffle instrumentation.

`cel.block([slot0, slot1, ...], result)` memoizes lazily evaluated slots for one evaluation.
`cel.index(n)` reads a slot; a slot initializer may only reference earlier slots, and the
result may reference any slot. Unused slots are not evaluated. Values and errors are cached
per invocation, without leaking between evaluations or threads. Nested blocks have separate
slot scopes. `cel.iterVar(depth, index)` provides synthetic local identifiers for optimized
comprehensions. Both indices must be nonnegative integer literals.

Block slots preserve lexical bindings even when first read inside another comprehension.
Slot count and dependency depth are bounded by evaluation limits. Uninstrumented slot reads resolve directly to their initializer nodes and participate in guest compilation.
The JVM gate checks representative compilation and allocation budgets; source-backed tooling uses
boundaries to preserve debugger frames. These gates do not claim a particular production throughput.

## Sets and encoders

`sets.contains(a, b)` tests whether every element of `b` occurs in `a`;
`sets.equivalent(a, b)` checks containment in both directions;
`sets.intersects(a, b)` checks for any common element. Arguments are lists. Ordering and
duplicates do not affect the answer, and comparisons use CEL equality, including mixed
numeric types. Comparisons consume the evaluation budget.

`base64.encode(bytes)` produces standard padded Base64. `base64.decode(string)` accepts
standard Base64 with or without padding, ignores CR/LF line breaks, and rejects other
invalid characters or padding. These are
not the URL-safe alphabet. `base64.encodeUrl` and `base64.decodeUrl` use the URL-safe
alphabet with the same padding and CR/LF rules. Encoding output size is charged before allocation.

`json.encode(value)` and `yaml.encode(value)` serialize CEL scalars, lists, maps and explicitly
registered object fields. Bytes become standard Base64 strings; timestamps and durations become
strings. Integers outside the exact JSON double range become decimal strings. Non-finite numbers,
unsupported values and map keys that collide after string conversion fail explicitly.

`json.parse(text[, type])` and `yaml.parse(text[, type])` return optionals. Invalid syntax or type
mismatches produce none; resource exhaustion remains an error. Dynamic parsing preserves signed
and unsigned integer identity before falling back to doubles. Typed parsing supports scalar types,
bytes, timestamps, durations, typed collections and registered object fields. YAML accepts a single
data document, rejects custom Java tags and recursive/non-scalar keys, and bounds alias use.
Input is capped at 10 MiB, traversal uses the environment depth limit, and parsing/serialization
consume the evaluation budget. Object field conversion uses the registered CEL field names.

## Protobuf extensions

`proto.hasExt(message, extension.name)` tests presence and
`proto.getExt(message, extension.name)` reads a registered extension, including its protobuf
default when absent. The second argument is an identifier, not a string or computed value.
Names resolve using the environment container; a leading dot selects an absolute name.
Extensions must be explicitly registered for the receiving message type. See
[protobuf integration](INTEGRATION.md) for the native TruffleData adapter.

## Optional values

Use `optional.of(value)`, `optional.none()`, `optional.ofNonZeroValue(value)`, `.hasValue()`,
`.value()`, `.or(otherOptional)`, and `.orValue(fallback)`. An empty optional is distinct from
an optional containing null. Reading `.value()` on an empty optional is an evaluation error.
`or` and `orValue` evaluate their fallback only when the receiver is empty.

`object.?field` and `listOrMap[?key]` lift a successful lookup into an optional and return none
when the entry is absent. Subsequent ordinary selections/indexes on an optional propagate
absence. Invalid receiver types still fail. `has()` tests presence through optional chains.

`[?optionalElement]`, `{?key: optionalValue}`, and `Message{?field: optionalValue}` omit empty
entries and unwrap present entries once. `optMap(variable, expression)` wraps its result;
`optFlatMap(variable, optionalExpression)` requires an optional result. Both skip their body
for empty receivers. Optional equality uses CEL equality recursively, including numeric types
and NaN, rather than Java record equality.

## Two-variable macros and lists

`all(indexOrKey, value, predicate)`, `exists(...)`, and `existsOne(...)` support lists and
maps. `transformList(k, v, expression)` and `transformMap(k, v, expression)` also accept a
filter before the expression. List keys are zero-based ints; map keys preserve their types.
`transformMapEntry(k, v, mapExpression)` merges generated entries and rejects duplicate keys.
Nested bindings have lexical scope and are restored even on errors and early returns.

List extensions include `slice(start, end)`, `flatten([depth])`, `distinct()`, `reverse()`,
`sort()`, `sortBy(variable, keyExpression)`, and `lists.range(count)`. Slice ends are exclusive;
flattening defaults to one level. Distinct preserves first-occurrence order. Sorting requires
comparable keys of the same runtime type and computes each sortBy key once.

## Strings and math

String extensions include `charAt`, `indexOf`, `lastIndexOf`, `substring`, `lowerAscii`,
`upperAscii`, `replace`, `split`, `trim`, `reverse`, `strings.quote`, list `join`, and `format`.
Indexes and reversal use Unicode code points; ASCII casing leaves other characters unchanged.
Splitting and replacement use literal delimiters. Formatting implements CEL's `%s`, `%d`, `%b`,
`%o`, `%x`, `%X`, `%f`, `%e` and `%%`, with precision and locale-independent output.

`math.greatest` and `math.least` accept numeric arguments or a numeric list, preserving the
selected value's numeric type. Other functions are `ceil`, `floor`, `round`, `trunc`, `abs`,
`sign`, `isNaN`, `isInf`, `isFinite`, `bitAnd`, `bitOr`, `bitXor`, `bitNot`, `bitShiftLeft`, and
`bitShiftRight`. Negative shifts fail; shifts of 64 bits or more produce zero. Right shifts
are logical, including signed-int inputs. Math rounding uses ties away from zero, while fixed
formatting uses ties to even.

## Networks and execution limits

`ip`, `isIP`, `ip.isCanonical`, `cidr`, and `isCIDR` handle numeric IPv4/IPv6 addresses and
prefixes. Address methods include `family`, `isUnspecified`, `isLoopback`, `isGlobalUnicast`,
`isLinkLocalMulticast`, and `isLinkLocalUnicast`. CIDR methods include `containsIP`,
`containsCIDR`, `ip`, `masked`, and `prefixLength`. Host bits are preserved until `masked()`.
IPv4-mapped IPv6 addresses and zone identifiers are rejected. Parsing never resolves hostnames
or opens sockets, and the values require no reflection or resource files.

Collection traversal, sorting comparisons, range generation, and string expansion consume
the evaluation budget. Range has an additional one-million-element cap; flattening has depth
and size limits. Formatting precision is capped at 1,000. Budget exhaustion is never suppressed
by boolean macros or optional fallbacks. These limits intentionally reject resource-intensive
expressions even when their mathematical result would otherwise be valid.

The baseline is the revision in `third_party/cel-spec/REVISION`, not a claim of every CEL
extension/version. Canonical protobuf AST interchange and the standalone conformance service are described in
[interchange and RPC](INTERCHANGE.md).

## Host function overloads

Repeated `Builder.function(name, arguments, result, implementation)` calls retain distinct
signatures. Identical or overlapping declarations fail at registration instead of replacing
an earlier implementation. The legacy form continues to accept global and receiver syntax.

Use `function(name, overloadId, receiver, arguments, result, implementation)` to declare
an explicit call style and stable overload ID. Receiver signatures include the receiver as
argument zero. IDs are unique across the environment and appear in canonical checked AST
references. Dynamic arguments retain all candidate IDs and dispatch by runtime type.

Signatures cannot differ only in list/map element types: those types are erased at runtime.
Nullable wrappers overlap null. Ambiguity is rejected even when result types differ.
Explicit overloads can add signatures to standard function names; a matching custom signature
wins, while unmatched built-in signatures retain their normal behavior. Lazy control functions
(`&&`, `||`, `?:`, `or`, `orValue`, `@not_strictly_false`) cannot be overloaded.
Host functions remain impure for folding, receive owned normalized arguments, and preserve
ordinary error/unknown propagation. Prepared and concurrent programs share immutable signatures.

## Additional optional and regex functions

`optional.unwrap(list)` removes empty optionals and unwraps present values. `list.first()`
and `list.last()` return an optional element, including an empty optional for an empty list.
`math.sqrt(double)` returns a double; negative inputs produce NaN.

`regex.replace(text, pattern, replacement[, count])` replaces non-overlapping matches.
Negative counts replace all matches; zero returns the input without compiling the pattern.
Replacement escapes use a single backslash followed by a group digit (0 is the complete match),
or two backslashes for a literal backslash. Dollar signs are literal. Invalid replacement
escapes fail when a match needs replacement.

`regex.extract(text, pattern)` returns the first match as an optional string;
`regex.extractAll(text, pattern)` returns a list. With one capture group they return that group;
with none they return the complete match. Unmatched optional captures are omitted. More than
one capture group is rejected, except that `extract` returns none when there is no match.
The functions share the RE2 engine seam and bounded dynamic cache with `matches`.
Input traversal, each match and output expansion consume the evaluation budget.

## Library and function restrictions

`Builder.libraries(Map<String, Integer>)` replaces the enabled extension libraries.
The default enables every implemented library at its latest supported version; an empty
map disables them all. `Builder.allowedFunctions(Set<String>)` independently restricts
callable names, including registered host functions and macros. Both inputs are copied.
For example, `libraries(Map.of("math", 1)).allowedFunctions(Set.of("math.abs"))` exposes
only `math.abs` among callable functions. Core literals, selection, types and conditional
syntax remain available. Restrictions apply before folding, to unchecked programs and
when canonical ASTs are imported; dynamic extension dispatch rechecks library membership.

Supported library versions: `math`, `lists`, `optional` 0–2; `strings`, `regex`, `sets`,
`encoders`, `json`, `yaml`, `net`, `bindings`, `protos`, `comprehensions` 0. Unknown libraries
and versions fail at registration. Math 0 exposes extrema, 1 adds the other numeric and
bit functions, and 2 adds sqrt. Lists 0 exposes slice, 1 adds flatten, and 2 adds the
remaining list helpers. Optional 1 adds optFlatMap and 2 adds first/last. Host functions
are explicitly supplied capabilities and are not removed by library version restrictions;
the function allow-list still applies to them.

Declared variables, constants and lexical variables take precedence over extension
namespaces such as `math`. A dynamic `reverse` call checks the actual receiver so a
strings-only environment cannot use the list implementation. Canonical comprehensions
remain core AST syntax: restricting a macro does not prohibit equivalent explicit CEL.
