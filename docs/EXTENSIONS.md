# CEL extension support

TruffleCEL implements the pinned upstream optional-value, two-variable comprehension,
string, list, math and network suites. These add 682 cases to the 1,905 core cases. The
independent parser, checker and Truffle evaluator handle these features directly; no CEL Java
frontend or interpreter is used. They are available in the core JAR with no new dependencies.

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
extension/version. Bindings, block, set and encoder suites, canonical protobuf AST interchange,
and proto2 extension registration remain outside this implementation scope.
