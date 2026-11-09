# Changelog

## [0.10.0](https://github.com/elide-dev/trufflecel/compare/v0.9.0...v0.10.0) (2026-11-09)


### ⚠ BREAKING CHANGES

* support TruffleRegex-only execution with optional RE2/J ([#64](https://github.com/elide-dev/trufflecel/issues/64))
* Maven compatibility-mode regex consumers must declare com.google.re2j:re2j:1.8 explicitly. TruffleRegex-only consumers can omit it.

### Features

* add opt-in lazy common-subexpression elimination ([620a3f7](https://github.com/elide-dev/trufflecel/commit/620a3f78ea40bdcfaf0a2cff95cba96fb65965f7))
* add source-level tracing and asynchronous evaluation ([5c92f3a](https://github.com/elide-dev/trufflecel/commit/5c92f3ac4921abe321d0968ec1fc950c66e6e57e))
* estimate conservative charged operation bounds ([cce0367](https://github.com/elide-dev/trufflecel/commit/cce03674550a7d2323710cddddaf8cb00d98019d))
* evaluate cel.bind initializers lazily with lexical memoization ([710b3e6](https://github.com/elide-dev/trufflecel/commit/710b3e63567978750634bb89bf53207b453f1299))
* expose editable syntax trees and custom macro expansion ([2234f01](https://github.com/elide-dev/trufflecel/commit/2234f01b59116dfce0152660e7c9f7b4b7d9ad72))
* expose protobuf contexts and typed field-mask projections ([11e6b50](https://github.com/elide-dev/trufflecel/commit/11e6b50e2b83fe38fa8ccddf9075d93f4bd44913))
* expose regex-only language options and qualify absent-JAR consumers ([ce196bf](https://github.com/elide-dev/trufflecel/commit/ce196bf917c9ecaeb069884ada94fb645c376f63))
* gate 0.10.0 on TruffleData and fix release staging paths ([1c1e7d0](https://github.com/elide-dev/trufflecel/commit/1c1e7d02f5ce494933ac4308bfb34bce339ee816))
* gate next release on TruffleData 0.10.0 ([deffb8d](https://github.com/elide-dev/trufflecel/commit/deffb8de39ab5c02c95fb1bcbcd8248673c6112d))
* gate next release on TruffleData 0.10.0 ([5beb225](https://github.com/elide-dev/trufflecel/commit/5beb225a5d3e23eb5c698998ff59828dc6e655e3))
* generate reflection-free POJO snapshot adapters ([265a064](https://github.com/elide-dev/trufflecel/commit/265a064ed32f5b6abca866329de135ff54995601))
* import environment YAML and compile policy rules ([1f3d028](https://github.com/elide-dev/trufflecel/commit/1f3d0284bc18a6b81a5e831b0d28d0dd1add522c))
* integrate 0.10.0 functionality and qualified performance improvements ([ecb07bf](https://github.com/elide-dev/trufflecel/commit/ecb07bfeadfa18dbd6dc5f11c30eb98cc4e628fe))
* preserve offsets for embedded CEL expressions ([175c2cc](https://github.com/elide-dev/trufflecel/commit/175c2ccca76e86b8faad034ef4ebaaf006667302))
* recover checker diagnostics and copy compiler environments ([23d2b86](https://github.com/elide-dev/trufflecel/commit/23d2b860007a0dd827c0d4b0b2d1e1bbca179b5c))
* resolve qualified unknown attributes across partial evaluations ([7f84d1d](https://github.com/elide-dev/trufflecel/commit/7f84d1dd2a3d5efcd645355891a7f99c6e1f7ec9))
* round-trip macro source and legacy AST schemas with parser recovery ([aa684cc](https://github.com/elide-dev/trufflecel/commit/aa684ccd92f9c3842eff566e8ad916a6d9d38200))
* support application validators over immutable syntax trees ([aa53b07](https://github.com/elide-dev/trufflecel/commit/aa53b07e77dd9a1fd7bc56ce048b95f5c7016069))
* support per-call and non-strict function bindings ([987c8f2](https://github.com/elide-dev/trufflecel/commit/987c8f2b67e5820e3b8bdf8d4788700f102887e5))
* support TruffleRegex-only execution with optional RE2/J ([#64](https://github.com/elide-dev/trufflecel/issues/64)) ([86a169c](https://github.com/elide-dev/trufflecel/commit/86a169c18852ac04690750efaad29554758b67ea))
* support TruffleRegex-only operation with optional RE2/J ([c0370cb](https://github.com/elide-dev/trufflecel/commit/c0370cb0a171389b833da26cd88525f84b0556f7))


### Bug Fixes

* accept bounded long unary runs without consuming AST depth ([727f423](https://github.com/elide-dev/trufflecel/commit/727f4237af9271d970393eb1f3207e78e8902ed2))
* **bench:** construct startup driver provenance with paths ([1f7d9e5](https://github.com/elide-dev/trufflecel/commit/1f7d9e5eef01715bdee7fb1a0552b410dafc78bf))
* bound CSE guest compilation and preserve cache costs ([0ce9855](https://github.com/elide-dev/trufflecel/commit/0ce9855e89993d96a6d0943cf7ef174010f8e50f))
* bound policy condition analysis and preserve embedded source limits ([48e6b33](https://github.com/elide-dev/trufflecel/commit/48e6b33f53a633fb394112315c95a81bd27e9166))
* bound sorting costs and isolate lexical size guarantees ([30c5580](https://github.com/elide-dev/trufflecel/commit/30c558010be72ce8da5c543e8d5068316e96a9f9))
* bound unary runs by source length rather than AST depth ([d791633](https://github.com/elide-dev/trufflecel/commit/d79163302fedc95d63dadfa0f22c3a47f4b673df))
* bound unary runs by source length rather than AST depth ([fb6cd79](https://github.com/elide-dev/trufflecel/commit/fb6cd798ade0cc2784cf96cef927a13dc8b201b3))
* encode lazy bindings explicitly in canonical expressions ([d803c75](https://github.com/elide-dev/trufflecel/commit/d803c75bfd88a4360f991ef4b5dc417a04a20f42))
* enforce string extension introduction versions ([793c756](https://github.com/elide-dev/trufflecel/commit/793c7561e03c8f525716f8968570db5c90bfe1c4))
* expose source offset encoding on trace events ([8883446](https://github.com/elide-dev/trufflecel/commit/88834468c43e47e2a94442f8dbfb2ab3e63b592b))
* include release gate in CI test runfiles ([2c02d93](https://github.com/elide-dev/trufflecel/commit/2c02d934e5c2101f58272ce044bb0286fb64417b))
* include release gate in CI test runfiles ([e1c11a2](https://github.com/elide-dev/trufflecel/commit/e1c11a2ca7dfe380bec5fb7fa70b63393260f65e))
* initialize canonical protobuf descriptors for native qualification ([e4c7891](https://github.com/elide-dev/trufflecel/commit/e4c7891a2fcc2cca964a08fc392b2d869a34ccdd))
* isolate lazy initializer locals from active body scopes ([337724f](https://github.com/elide-dev/trufflecel/commit/337724f7e70c188a6f2c552d2d57b5d95b5c435e))
* isolate partial attribute allocation from guest compilation ([fb0466e](https://github.com/elide-dev/trufflecel/commit/fb0466e6b09a9abf7d4fe33c054e72eb8f1dce03))
* keep absolute-name slicing outside native guest compilation ([4b882d7](https://github.com/elide-dev/trufflecel/commit/4b882d7e2c84d07fd0715145093d526280456105))
* plan builtin overload fallbacks before guest compilation ([a3c9e09](https://github.com/elide-dev/trufflecel/commit/a3c9e09986a3f8f896acff783a2cd179a8d63231))
* preserve diagnostic offset encoding across canonical ASTs ([5421c33](https://github.com/elide-dev/trufflecel/commit/5421c33f13b9180417a29a26b43f8b766d06e0ee))
* preserve environment declarations and exact configuration limits ([f3a070a](https://github.com/elide-dev/trufflecel/commit/f3a070ab6726e5b5fb7bd5479cbe681e4f8edaa0))
* preserve policy results and source locations across configured environments ([93cf82a](https://github.com/elide-dev/trufflecel/commit/93cf82a988e049ec65099574e1e8b86fb6b88dcd))
* preserve registered extensions in protobuf wildcard views ([8dc76e9](https://github.com/elide-dev/trufflecel/commit/8dc76e966c5145cedbb0d51ad6e92d4b44acf0c5))
* preserve source budget failures when replaying CSE costs ([818579a](https://github.com/elide-dev/trufflecel/commit/818579ad320a779ca1e859cb2c08bb44b2c0673b))
* preserve unavailable native diagnostic counters explicitly ([092d3b1](https://github.com/elide-dev/trufflecel/commit/092d3b1132260564ba3e6060f94d5b6c7ac6cb2b))
* recover regex acceleration and bound cache eviction and failures ([6be8d41](https://github.com/elide-dev/trufflecel/commit/6be8d419c1446ab495399213ab6a40b1e0adce43))
* recover regex acceleration and harden bounded caches ([#65](https://github.com/elide-dev/trufflecel/issues/65)) ([92a0944](https://github.com/elide-dev/trufflecel/commit/92a0944c18f64e65e46b06e9077fdb19ad6a3f9d))
* reject lossy AST source and type constant exports ([8ca6e61](https://github.com/elide-dev/trufflecel/commit/8ca6e6104bf38382d579106370d617d3c316ed0f))
* reject map receivers in dynamic list sorting ([18c1000](https://github.com/elide-dev/trufflecel/commit/18c1000a074c8cc203742926839201e0307af0e9))
* resolve closed policy condition bindings before analysis ([474c5ab](https://github.com/elide-dev/trufflecel/commit/474c5abdbed8f408646e1a9f2f184ba671eecf09))
* resolve partial attributes in cached expressions ([99e3a2f](https://github.com/elide-dev/trufflecel/commit/99e3a2fbec143d9bc4b5808ac33f0641aef7ff0d))
* retain implicit policy condition findings after lowering ([42d69c5](https://github.com/elide-dev/trufflecel/commit/42d69c516d9a45e67da8295cb9c111cfbade4e37))
* retain source positions for exported CSE initializers ([9daf6aa](https://github.com/elide-dev/trufflecel/commit/9daf6aa4ced58933cbda8d36fe696ce52d22ba93))
* retain source provenance in editable ASTs ([0d750b5](https://github.com/elide-dev/trufflecel/commit/0d750b5e682ddb849834853d69a36374ca339b80))
* validate edited macro shapes and preserve expansion source identity ([f1859a3](https://github.com/elide-dev/trufflecel/commit/f1859a368321f340de1c1de6a7b6adb9419cac36))
* validate inherited POJO properties and owned snapshot bounds ([5e48904](https://github.com/elide-dev/trufflecel/commit/5e48904fa976b2f947068cc7cc9392337d45dabc))


### Performance Improvements

* accelerate scoped constant matches with TruffleRegex ([bf991bf](https://github.com/elide-dev/trufflecel/commit/bf991bf100aca77fed882c7465688697f3372b2f))
* avoid unused parser allocations and planning walks ([547c9fc](https://github.com/elide-dev/trufflecel/commit/547c9fccaba28882dc4a2a01ef69fee386d70360))
* cache scoped regex dispatch without retaining program contexts ([17dc72f](https://github.com/elide-dev/trufflecel/commit/17dc72fecdd3a4ca2181cbfe3132d012bff8b6c7))
* compact ordinary evaluation contexts ([8a73718](https://github.com/elide-dev/trufflecel/commit/8a7371846311224dea703bfbe658edd30f0cec68))
* decode owned protobuf bytes without cloning the wire buffer ([67ab33c](https://github.com/elide-dev/trufflecel/commit/67ab33cf89e0f06ad71b0b3c49d32fab53adf2d1))
* preserve scalar allocation budgets with lazy lexical scopes ([3f02a19](https://github.com/elide-dev/trufflecel/commit/3f02a19616f8540ad0f05df1af3de4a0a5f4d64f))
* **protobuf:** check ordinary message defaults in native storage ([b1c0cc1](https://github.com/elide-dev/trufflecel/commit/b1c0cc195348002ca0e0bac8da30a4cc33ece0f0))
* **protobuf:** check ordinary message defaults in native storage ([28211ae](https://github.com/elide-dev/trufflecel/commit/28211ae408d31042fc721c059ce59fe083ecad4c))
* **protobuf:** check ordinary message defaults in native storage ([71f3d52](https://github.com/elide-dev/trufflecel/commit/71f3d52f215a70753497275d88840745cd21717c))
* reduce host map snapshot allocations ([5fbc06b](https://github.com/elide-dev/trufflecel/commit/5fbc06bafd204c62d8930d9e8e875dd767f4b0ed))
* reduce host map snapshot allocations ([d2344d5](https://github.com/elide-dev/trufflecel/commit/d2344d5fd2bee793d4e41b97e291e51a43aaef54))
* reduce host map snapshot allocations ([ddfc673](https://github.com/elide-dev/trufflecel/commit/ddfc673cda28f373e9786bc6263cda47488380d1))
* **runtime:** release planning metadata from cached programs ([1ff2d77](https://github.com/elide-dev/trufflecel/commit/1ff2d77b2e3355be5cc33366b3e763880cf02246))
* share immutable binding layouts across program instances ([f35e2f0](https://github.com/elide-dev/trufflecel/commit/f35e2f06bfa616496ef55f42e574e5d31f043cbf))
* size binding conversion caches by referenced variables ([b81fa2f](https://github.com/elide-dev/trufflecel/commit/b81fa2fb04de5c23a43c441456559c6e64e1833f))


### Reverts

* remove cached regex adapter after performance qualification ([8ceb58b](https://github.com/elide-dev/trufflecel/commit/8ceb58b4933a337972b0d0f5220691b4c6d42949))


### Documentation

* document explicit dependency for compatibility regex ([66e9150](https://github.com/elide-dev/trufflecel/commit/66e9150f9150ff6a4374d94842492023ee6c7150))

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
