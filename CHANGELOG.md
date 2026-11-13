# Changelog

## [0.12.1](https://github.com/elide-dev/trufflecel/compare/v0.12.0...v0.12.1) (2026-11-13)


### Bug Fixes

* bound macro-expanded constant bytes in canonical AST import ([461df2a](https://github.com/elide-dev/trufflecel/commit/461df2aed8737b8a6ff8a3513006b1086cf720d4))
* bound regex nesting depth and convert StackOverflowError to CelException ([a5c2384](https://github.com/elide-dev/trufflecel/commit/a5c2384597675bcd839db5463d3e4d9c6c9d2650))
* bound yaml.encode re-parse and convert engine exceptions to CEL errors ([9742c0a](https://github.com/elide-dev/trufflecel/commit/9742c0a2ecdb83343d7e5362d2f2efe010b425bb))
* charge and cap comprehension snapshot copies ([c47dd25](https://github.com/elide-dev/trufflecel/commit/c47dd25106b2d222efe196697cd94ef89469d87b))
* charge string and bytes concatenation proportionally to result length ([86fb114](https://github.com/elide-dev/trufflecel/commit/86fb114699983ff48ba66721aec4cc9bd3153d8c))
* classify polyglot guest exceptions by CelException code, not throw site ([3d19d11](https://github.com/elide-dev/trufflecel/commit/3d19d119a25ee488080bfb7707a49a552cdf6d01))
* convert host Instant/Duration in polyglot bindings ([ef105c2](https://github.com/elide-dev/trufflecel/commit/ef105c2b154cd48f3df9db7626fbe617573ed3ad))
* delegate protobuf extension presence checks to the producing adapter ([fab3495](https://github.com/elide-dev/trufflecel/commit/fab349527de20f0cb21e3918603e5d08f3b08e24))
* gate cel.block/cel.index/cel.iterVar through LibraryPolicy ([2b55594](https://github.com/elide-dev/trufflecel/commit/2b555943a3dcc977c0245753037655ce703812b9))
* merge unknowns and defer errors in INDEX evaluation ([b70e2bf](https://github.com/elide-dev/trufflecel/commit/b70e2bfee2fe7b46c582ae87a33ed99eebd1640d))
* mirror runtime dispatch precedence for colliding host function names in checker ([96cdf92](https://github.com/elide-dev/trufflecel/commit/96cdf9243174ff15d4304616788a3b553127828b))
* normalize canonical operator names in YAML function declarations ([aca07d4](https://github.com/elide-dev/trufflecel/commit/aca07d4be5a0a5495d6413ee0130ea8331f5c89c))
* normalize OptionalValue payloads in the canonical constructor ([775e92b](https://github.com/elide-dev/trufflecel/commit/775e92b426292b6c7887254e782763fb9891b1e2))
* normalize polyglot-bound host byte arrays to CEL bytes ([4131b8d](https://github.com/elide-dev/trufflecel/commit/4131b8d91d09b7da0dc5871f6d03cfbb12bd8804))
* preserve IEEE NaN equality for POJO snapshots ([13aca08](https://github.com/elide-dev/trufflecel/commit/13aca08831933a27d8c9a8fe5d214dcca27700a7))
* preserve overload_id and call style in conformance Check ([b9b8f54](https://github.com/elide-dev/trufflecel/commit/b9b8f54fcdf164203c453c0ea2a4e22f2898d123))
* preserve policy source locations for multi-line plain YAML scalars ([70a2427](https://github.com/elide-dev/trufflecel/commit/70a24273a711a2e52c268f05fba0b07a758a7540))
* preserve unknown-over-error precedence in object, list, and map literals ([fa9c5ec](https://github.com/elide-dev/trufflecel/commit/fa9c5ecd55178085110073319bb7157d4fdde803))
* preserve unsigned identity for native ARRAY element constraints ([1c4d5dd](https://github.com/elide-dev/trufflecel/commit/1c4d5dde36eca3a8ef45755ce46e22a6bd93aaca))
* reject custom macros that shadow enabled standard macros ([927dfc4](https://github.com/elide-dev/trufflecel/commit/927dfc4f9615c0cbaea3c15f15968f095661a6e1))
* reject inconsistent declared generic host function bindings ([eb7ea27](https://github.com/elide-dev/trufflecel/commit/eb7ea277db3ac0b16e528ecba60415fea0aee9d1))
* **release:** pin Release Please baseline after history rewrite ([53fd130](https://github.com/elide-dev/trufflecel/commit/53fd130b21febfef4d4c9aaff1b596a00c3e825c))
* **release:** set last-release-sha at the manifest root ([b486e68](https://github.com/elide-dev/trufflecel/commit/b486e686fef7d3f56b31bab4d717d536b65d1bc6))
* report constant-aggregate validator findings once at the defect ([b47ae2d](https://github.com/elide-dev/trufflecel/commit/b47ae2d676331a488933dafa84b5cd79094d560b))
* resolve related attributes independent of map iteration order ([522ad34](https://github.com/elide-dev/trufflecel/commit/522ad342ce1eb56989d462ad0ca790ab1cf520b6))
* sign release provenance under a project predicate type ([#151](https://github.com/elide-dev/trufflecel/issues/151)) ([1ce877a](https://github.com/elide-dev/trufflecel/commit/1ce877a95b1029467b7c4c5830245fb8b5afb47c))
* type nullable schema columns as wrappers instead of dyn ([175b0d2](https://github.com/elide-dev/trufflecel/commit/175b0d26e3749145307bcd03be24a0850fa41933))
* unify mixed numeric equality with ordering ([01d994c](https://github.com/elide-dev/trufflecel/commit/01d994c957379d830a1472ffb19ba26e52362b1a))
* validate has macro argument is a field selection ([8005753](https://github.com/elide-dev/trufflecel/commit/800575344e046889fdce1a6c3c36aacabe843407))
* validate protobuf Timestamp seconds range in WellKnownTypes.time ([66ba9a0](https://github.com/elide-dev/trufflecel/commit/66ba9a0c1e288c289b4f08c1f8702e93491e0b64))

## [0.12.0](https://github.com/elide-dev/trufflecel/compare/v0.11.0...v0.12.0) (2026-11-12)


### Features

* **ci:** connect correctness builds to shared GCP cache ([#144](https://github.com/elide-dev/trufflecel/issues/144)) ([9705809](https://github.com/elide-dev/trufflecel/commit/9705809176f53c61a7fd8e91d2501a9df50af192))
* **deps:** adopt TruffleData 0.12.0 ([#148](https://github.com/elide-dev/trufflecel/issues/148)) ([c7156ce](https://github.com/elide-dev/trufflecel/commit/c7156ceff282a909782987ef16dc62eeb81033bb))


### Bug Fixes

* make instrumented WriteNode scope pushes re-entrant safe ([#123](https://github.com/elide-dev/trufflecel/issues/123)) ([7049577](https://github.com/elide-dev/trufflecel/commit/70495778e81c93bcfa996fb7264b563c02a7da30)), closes [#95](https://github.com/elide-dev/trufflecel/issues/95)

## [0.11.0](https://github.com/elide-dev/trufflecel/compare/v0.10.0...v0.11.0) (2026-11-10)

TruffleCEL 0.11.0 adopts TruffleData 0.11.0 and Elide GraalVM 25.5.5-elide.28.
Java 25 remains required. All four Maven modules release together: core, protobuf,
JSON, and the generated-adapter annotation processor.

TruffleData 0.11.0 was built with Myna 0.16.0. TruffleCEL has no direct Myna
artifact dependency and does not enable TruffleData's optional Myna cells routes.
Use the matching GraalVM .28 Maven repository and runtime when upgrading.

The benchmark charts and native-profile investigations retained in this release
were measured with their recorded earlier dependency versions. They are not
performance measurements of the new 0.11.0 dependency combination.



### Features

* **deps:** adopt TruffleData 0.11.0 and GraalVM elide.28 ([fd7bbaa](https://github.com/elide-dev/trufflecel/commit/fd7bbaa1a698c86e6edd3297457d9af47a1cf9e7))


### Benchmark documentation

* generate validated native profile comparison tables ([21db3c9](https://github.com/elide-dev/trufflecel/commit/21db3c92d0459a9d64dccf154fd8f3cd2d4efe75))
* qualify bounded native young generation and chart memory tradeoffs ([bfebb00](https://github.com/elide-dev/trufflecel/commit/bfebb00c9383d323167fca5b23da4fb011d143ca))
* qualify native compiler and direct API startup profiles ([9abd196](https://github.com/elide-dev/trufflecel/commit/9abd196e448c06f89299dfd451760de4d01db5f4))
* qualify native heap profiles and cumulative evaluation cost ([3f9d5ae](https://github.com/elide-dev/trufflecel/commit/3f9d5aec8156e543166ad33fcc46716592d7761d))
* qualify native heap profiles and direct Java startup ([#73](https://github.com/elide-dev/trufflecel/issues/73)) ([d7c6a8f](https://github.com/elide-dev/trufflecel/commit/d7c6a8f73f5e4116692a9ae06771cf5f76a66ece))
* retain current native phase and startup baselines ([435d0ae](https://github.com/elide-dev/trufflecel/commit/435d0aefe1b311d1b1d13789a81e02e92934d319))
* retain native memory startup and cumulative-cost qualification ([4a26a36](https://github.com/elide-dev/trufflecel/commit/4a26a363c0a8887c801e674961824bec7bd04519))

## [0.10.0](https://github.com/elide-dev/trufflecel/compare/v0.9.0...v0.10.0) (2026-11-09)

TruffleCEL 0.10.0 adopts published TruffleData 0.10.0 and completes this wave of
CEL tooling, embedding APIs, allocation reductions and regex hardening. Java 25
and Elide GraalVM 25.5.5-elide.27 remain the runtime baseline. All four Maven modules
release together: core, protobuf, JSON and the generated-adapter annotation processor.

### Compatibility

RE2/J is now an **optional Maven dependency**. Compatibility-mode consumers must
explicitly declare `com.google.re2j:re2j:1.8`. Applications that accept the supported
TruffleRegex subset can opt into `Cel.newBuilder().truffleRegexOnly(limit)` or the
matching Polyglot options and omit RE2/J entirely. JVM and Native Image packaged
consumers verify matching and guest compilation with the JAR physically absent.
See [integration and regex policy](https://github.com/elide-dev/trufflecel/blob/v0.10.0/docs/INTEGRATION.md).

### Functionality and fixes

- Editable syntax trees, custom macros, parser/checker recovery, canonical AST
  interchange, copied compiler environments and application validators.
- Lazy lexical bindings, opt-in common-subexpression elimination, conservative
  charged-operation bounds, field-level partial evaluation and qualified unknowns.
- Environment YAML, CEL Policy compilation, embedded source offsets, source-level
  tracing, asynchronous evaluation and per-call/non-strict function bindings.
- Reflection-free generated POJO/JavaBean/record snapshots, protobuf contexts and
  typed field-mask projections, with bounded owned snapshots and preserved extensions.
- Correctness fixes for diagnostic/source provenance, sorting budgets, policy
  conditions, AST round trips, long unary runs and native guest-compilation boundaries.
- Constant regex acceleration recovers after weak-handle collection in its owner
  context. Bounded caches evict one entry at a time and cache syntax-failure data
  without retaining exception stacks or weakening per-caller limits.

### Performance and qualification

Evaluation contexts, binding conversion caches, shared layouts, cached-program
metadata, map snapshots and protobuf wire ingestion use less allocation or retained
storage in their documented comparisons. Scoped TruffleRegex accelerates constant
matches while retaining the tested compatibility fallback.

[Fresh release measurements and charts](https://github.com/elide-dev/trufflecel/blob/v0.10.0/docs/benchmarks/release-0.10.0-20261109/README.md)
cover JVM/native evaluation, optimized cel-java, prepared-input controls, ingestion
allocation, and the separate compilation/image-AST comparison. Raw observations,
source/runtime/artifact identities, observed ranges and remaining memory gaps are
preserved. Timing wins do not establish universal optimality or smaller process RSS.

All 75 qualification targets pass, including conformance, packaged consumers,
allocation and native guest-JIT gates. Exact-version staging passes for all four
modules. Full-source coverage meets the unchanged 99.8% line/branch floors, and
CodSpeed classifies all 21 cases as unchanged against pre-adoption main.

## [0.9.0](https://github.com/elide-dev/trufflecel/compare/v0.8.0...v0.9.0) (2026-11-08)


### ⚠ BREAKING CHANGES

* CelException no longer extends IllegalArgumentException; callers that caught IllegalArgumentException to observe CEL failures must catch CelException (or RuntimeException).

### Features

* integrate CEL parity and TruffleData 0.9.0 ([d0ef064](https://github.com/elide-dev/trufflecel/commit/d0ef064663b6a30b52054bcca6ef332e84ada741))


### Performance Improvements

* keep compiled error paths compiled ([#46](https://github.com/elide-dev/trufflecel/issues/46)) ([41f889b](https://github.com/elide-dev/trufflecel/commit/41f889b94f10735674d34aeed7c968edaa7abebf))

## [0.8.0](https://github.com/elide-dev/trufflecel/compare/v0.7.0...v0.8.0) (2026-11-07)


### Build System

* upgrade TruffleData to 0.8.0 and refresh dependencies ([#21](https://github.com/elide-dev/trufflecel/issues/21)) ([0cf2b4d](https://github.com/elide-dev/trufflecel/commit/0cf2b4d46ab817c423008d30aa4b8e62ea97d6f0))

## [0.7.0](https://github.com/elide-dev/trufflecel/compare/v0.4.1...v0.7.0) (2026-11-06)


### ⚠ BREAKING CHANGES

* Ordinary `Program.evaluate(Map)` / `test(Map)` now borrow inputs. Callers must keep
  bindings and reachable data stable until evaluation returns, including during callbacks.
  Normalization and bounds checks happen when a binding is read; unused bindings are no
  longer eagerly traversed or rejected. Use `Activation.snapshot` for eager validation
  and isolation. See [the input contract](docs/INTEGRATION.md#borrowed-input-bindings).

### Features

* add reusable activation snapshots with independent evaluation state ([9e28a45](https://github.com/elide-dev/trufflecel/commit/9e28a45b3f7f788f796d12f61bbe5e54d8f3ac8a))
* prepare protobuf evaluation and borrow stable inputs ([#16](https://github.com/elide-dev/trufflecel/issues/16)) ([995c9cb](https://github.com/elide-dev/trufflecel/commit/995c9cb10b15b222795c2babf1320e50802d69e9))


### Bug Fixes

* recognize all numeric wrappers in standard Any payloads ([4fe7b23](https://github.com/elide-dev/trufflecel/commit/4fe7b23c89c39807b2351ba8c586883b9e9b7829))
* validate production protobuf against the complete CEL corpus ([0746920](https://github.com/elide-dev/trufflecel/commit/07469207f895d7e41971cd41a2aaabbb12e80e54))


### Performance Improvements

* add representative CEL comparisons and cold evaluation curves ([b707757](https://github.com/elide-dev/trufflecel/commit/b707757a2901bd45bdf122592ca369bd8efcc7f6))
* borrow stable input bindings during evaluation ([4b24ef8](https://github.com/elide-dev/trufflecel/commit/4b24ef8883c71e7754c1ddce736f0acea614a8ac))
* bound native comparison heaps and retain warmup follow-ups ([d4f96c3](https://github.com/elide-dev/trufflecel/commit/d4f96c3685cddf6deeb5229a1bb7d1f906a811de))
* expand opt-in activation benchmarks to all representative workloads ([e457bf0](https://github.com/elide-dev/trufflecel/commit/e457bf087a5f2e463368368401f1d668bcea2e4e))
* prepare nested protobuf and well-known field access ([c669099](https://github.com/elide-dev/trufflecel/commit/c6690994d7583eff6b98cd8bed7d79fd47143d06))
* prepare protobuf scalar reads and own collection storage ([5dd051f](https://github.com/elide-dev/trufflecel/commit/5dd051f9bbe071ff88a6b92b3b1b5a1286e7198b))
* retain process ranges and validate complete measurement matrices ([aa3b5eb](https://github.com/elide-dev/trufflecel/commit/aa3b5ebe0ae4cb85e85b85d454e8f1ef25589526))
* reuse exported protobuf messages during CEL equality ([943b7c5](https://github.com/elide-dev/trufflecel/commit/943b7c573b8898871100177c37862007bf634758))


### Build System

* upgrade TruffleData to 0.7.0 ([c4f5665](https://github.com/elide-dev/trufflecel/commit/c4f5665892f1c941f88b16cce4c73e7fdc0b91e5))

## [0.4.1](https://github.com/elide-dev/trufflecel/compare/v0.4.0...v0.4.1) (2026-11-05)


### Bug Fixes

* isolate source lookup and upgrade TruffleData to 0.6.0 ([#14](https://github.com/elide-dev/trufflecel/issues/14)) ([cbfbb22](https://github.com/elide-dev/trufflecel/commit/cbfbb22254bee5eba1844af527a342eb34074e23))

## [0.4.0](https://github.com/elide-dev/trufflecel/compare/v0.3.0...v0.4.0) (2026-11-04)


### Features

* complete supported README capabilities ([#12](https://github.com/elide-dev/trufflecel/issues/12)) ([224d464](https://github.com/elide-dev/trufflecel/commit/224d4643222cfcad8a94e0155e86a3987a06d248))
* dual-license TruffleCEL and establish community policies ([e508358](https://github.com/elide-dev/trufflecel/commit/e50835802bb3ee7d30e46a98eb58d1ad02fe8811))
* support the complete CEL conformance corpus with TruffleData 0.4.0 ([9b423d3](https://github.com/elide-dev/trufflecel/commit/9b423d3c30ea8b673397f61032b711f5c153e24a))


### Bug Fixes

* align Go SDK with updated Gazelle dependencies ([334f2c6](https://github.com/elide-dev/trufflecel/commit/334f2c61b1feedcf960dcecfdac4d44c403539a6))

## [0.3.0](https://github.com/elide-dev/trufflecel/compare/v0.2.0...v0.3.0) (2026-11-04)


### Features

* adopt TruffleData 0.3.0 and GraalVM .24 ([dde52b4](https://github.com/elide-dev/trufflecel/commit/dde52b44e68b0aead8cc530410ccc4aa0e349ed0))

## [0.2.0](https://github.com/elide-dev/trufflecel/compare/v0.1.0...v0.2.0) (2026-11-04)


### Features

* **benchmarks:** compare allocation costs and fixed-work peak RSS ([a44e273](https://github.com/elide-dev/trufflecel/commit/a44e273857efd3c4437ea04389650dfa28e483a5))
* **benchmarks:** measure interpreter execution and per-process peak RSS ([ad3fdcb](https://github.com/elide-dev/trufflecel/commit/ad3fdcbe6366425ff61dfe070754bf65340654dd))
* compare CEL compilation and evaluation on JVM and Native Image ([c4f0f20](https://github.com/elide-dev/trufflecel/commit/c4f0f203d295ba2975b61a14df89decef3810cfa))
* expose Truffle instrumentation and publish memory benchmarks ([76f2851](https://github.com/elide-dev/trufflecel/commit/76f28514d87494cedd10d95fa73f6fe8eed2418d))


### Bug Fixes

* **benchmarks:** exclude environment setup from image frontend timings ([f243780](https://github.com/elide-dev/trufflecel/commit/f2437800a29dd618d8b9cc597c8305d8989dd8ba))
* **benchmarks:** initialize upstream constants for image-build ASTs ([e3df286](https://github.com/elide-dev/trufflecel/commit/e3df286ada04fe052f5ab2d6e732b0ee893bd732))
* **benchmarks:** materialize runfiles for local timing after RBE builds ([64abff6](https://github.com/elide-dev/trufflecel/commit/64abff68e40a56993266af1c555c0a2e6e3e6f19))
* **release:** keep Bazel module version synchronized ([d3a0c42](https://github.com/elide-dev/trufflecel/commit/d3a0c42bbb5954b32ab74905e89cddd47c92fd8e))
* **release:** keep Maven versions out of Bazel dependency resolution ([aa7eab5](https://github.com/elide-dev/trufflecel/commit/aa7eab5801616515d79d3ec57a11c333728c11f2))


### Performance Improvements

* allocate only the collections each comprehension needs ([456e1bc](https://github.com/elide-dev/trufflecel/commit/456e1bcd67d821a192c6e4db9857672f562cd0dc))
* avoid argument arrays for string predicates ([5bdd9be](https://github.com/elide-dev/trufflecel/commit/5bdd9be291d45cc606b94e8b353cd52c6a55139d))
* bound compilation work for scoped evaluations ([325e7cb](https://github.com/elide-dev/trufflecel/commit/325e7cbf6fb8f76c36228088b2b46907c820adfe))
* count comprehension results without retaining lists ([63e1139](https://github.com/elide-dev/trufflecel/commit/63e1139173f3a8d52f20c770b6f43f165e17b772))
* eliminate eligible comprehension scope allocations ([83d8ffc](https://github.com/elide-dev/trufflecel/commit/83d8ffc85a5a49261d884c05202def0408c8e109))
* let guest compilation eliminate evaluation contexts ([78a4c2c](https://github.com/elide-dev/trufflecel/commit/78a4c2c7bcc08e7031dcfd8f3679dffc1036afda))
* lower constant-right integer arithmetic chains ([a44e445](https://github.com/elide-dev/trufflecel/commit/a44e445372367a8d701196dc083294f099e2521b))
* reduce integer operator and input normalization allocations ([300a638](https://github.com/elide-dev/trufflecel/commit/300a63870744e4b4022d5afaf93e33af2577cddb))
* reduce scalar interpreter dispatch and activation allocations ([94f39d7](https://github.com/elide-dev/trufflecel/commit/94f39d7ab00b9972a899085bf234e2c8b369a367))
* resolve local reads to lexical scope slots ([252466e](https://github.com/elide-dev/trufflecel/commit/252466ee59d82ae0a8eb445240ad474ebd94a9a3))
* reuse immutable compiler declarations ([385d755](https://github.com/elide-dev/trufflecel/commit/385d755a5b8c96b85d2378145aeb49d185422fe4))
* reuse immutable scalar binding snapshots ([d415645](https://github.com/elide-dev/trufflecel/commit/d415645e4e4b699dc8650f45db2bde9bbd144eb8))
* reuse lexer operator spellings ([ede695a](https://github.com/elide-dev/trufflecel/commit/ede695a90b28fa454cf76450f0e60a3b6119f8b4))
* reuse owned snapshots for comprehension iteration ([75d0941](https://github.com/elide-dev/trufflecel/commit/75d09415ba2bc3c9b17cf133747cafba437ebe77))
* **runtime:** avoid map iteration during object type dispatch ([4e9d35a](https://github.com/elide-dev/trufflecel/commit/4e9d35ae1bcf5d6185e582979d734a94ff19dfb7))
* **runtime:** compact small activations and reuse default compiler environment ([c7b6b6a](https://github.com/elide-dev/trufflecel/commit/c7b6b6a814aa4ae6ab5643f4c59c4422287836af))
* **runtime:** reduce field dispatch and numeric equality overhead ([cd3befb](https://github.com/elide-dev/trufflecel/commit/cd3befb985e6ebef4d9b1081cd0f7751d4f395cd))
* **runtime:** reuse local binding slots and defer scope allocation ([b489810](https://github.com/elide-dev/trufflecel/commit/b489810b0f17cbf79688674a87a3b91761e4b474))
* skip constructor-only adapters during runtime dispatch ([b4a0150](https://github.com/elide-dev/trufflecel/commit/b4a0150eb79126fd211c6a97ce33870093ea2389))
* use checked identifiers for single-binding lookup ([65c9089](https://github.com/elide-dev/trufflecel/commit/65c9089d0667b032c19443e03f90327bbac11b3e))
* validate small scalar activations without iterators ([04d83d6](https://github.com/elide-dev/trufflecel/commit/04d83d6c7f5e1a75636d5ad30e5d98e240c35568))

## [0.1.0](https://github.com/elide-dev/trufflecel/compare/v0.1.0-alpha.2...v0.1.0) (2026-11-03)


### Features

* adopt TruffleData 0.1.0 native protobuf rows and codec ([5e053f5](https://github.com/elide-dev/trufflecel/commit/5e053f538156e4c9a03a378fa51ce19e8a89f85f))


### Bug Fixes

* **deps:** align GraalVM toolchains and libraries with elide.23 ([a42b848](https://github.com/elide-dev/trufflecel/commit/a42b84854ab991abfd8042cde791969bbc93868e))

## [0.1.0-alpha.2](https://github.com/elide-dev/trufflecel/compare/v0.1.0-alpha.1...v0.1.0-alpha.2) (2026-11-03)


### Bug Fixes

* enforce guest JIT compilation and allocation gates ([eeb1876](https://github.com/elide-dev/trufflecel/commit/eeb18768249abc92fdf245e457bcaec9b4fb1699))

## [0.1.0-alpha.1](https://github.com/elide-dev/trufflecel/compare/v0.1.0-alpha.0...v0.1.0-alpha.1) (2026-11-03)


### Features

* implement optional values and CEL comprehension and library extensions ([0c7221b](https://github.com/elide-dev/trufflecel/commit/0c7221b98aaf80695dbb549d355df9bfba08187b))
* introduce TruffleCEL with Bazel CI and native benchmarks ([6d38bcb](https://github.com/elide-dev/trufflecel/commit/6d38bcbadf9acd89321c1908b9b66c2195f44c64))


### Bug Fixes

* adopt TruffleData alpha.3 and align protobuf publication dependencies ([feae81a](https://github.com/elide-dev/trufflecel/commit/feae81a44e9fdd91220c39b690f47dcda4a6d699))
* **ci:** use built-in token for Release Please and dispatch release checks ([0d3cbb2](https://github.com/elide-dev/trufflecel/commit/0d3cbb284d81fd8a1c3a6b6c8ecdfc7eb217967f))
* **deps:** upgrade TruffleData to 0.1.0-alpha.4 ([ea7252b](https://github.com/elide-dev/trufflecel/commit/ea7252b0c54868e1bf5c5b9ba8059df63560d3f7))


### Performance Improvements

* resolve extension dispatch while compiling CEL expressions ([18e5d7b](https://github.com/elide-dev/trufflecel/commit/18e5d7b9ace103460109a7f6718ae57452c9f21c))

## Changelog

Release Please maintains this file from conventional commits. No TruffleCEL release has
been published by this repository setup.
