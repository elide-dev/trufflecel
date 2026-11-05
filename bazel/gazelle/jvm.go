// Package gazelle generates TruffleCEL's Java libraries and one JVM target per test class.
// Like Komodo, generation is a repository-owned Gazelle extension. The extension only
// owns srcs/deps/test_class: annotation processors and runtime policy stay reviewable in BUILD.
package gazelle

import (
	"flag"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"

	"github.com/bazelbuild/bazel-gazelle/config"
	"github.com/bazelbuild/bazel-gazelle/label"
	"github.com/bazelbuild/bazel-gazelle/language"
	"github.com/bazelbuild/bazel-gazelle/repo"
	"github.com/bazelbuild/bazel-gazelle/resolve"
	"github.com/bazelbuild/bazel-gazelle/rule"
)

type java struct{}

func NewLanguage() language.Language                                              { return &java{} }
func (*java) Name() string                                                        { return "trufflecel_java" }
func (*java) RegisterFlags(*flag.FlagSet, string, *config.Config)                 {}
func (*java) CheckFlags(*flag.FlagSet, *config.Config) error                      { return nil }
func (*java) KnownDirectives() []string                                           { return nil }
func (*java) Configure(*config.Config, string, *rule.File)                        {}
func (*java) Fix(*config.Config, *rule.File)                                      {}
func (*java) Imports(*config.Config, *rule.Rule, *rule.File) []resolve.ImportSpec { return nil }
func (*java) Embeds(*rule.Rule, label.Label) []label.Label                        { return nil }
func (*java) Resolve(*config.Config, *resolve.RuleIndex, *repo.RemoteCache, *rule.Rule, interface{}, label.Label) {
}
func (*java) Loads() []rule.LoadInfo {
	return []rule.LoadInfo{{Name: "@rules_java//java:defs.bzl", Symbols: []string{"java_library", "java_test"}}}
}
func (*java) Kinds() map[string]rule.KindInfo {
	info := rule.KindInfo{MatchAttrs: []string{"name"}, NonEmptyAttrs: map[string]bool{"srcs": true}, MergeableAttrs: map[string]bool{"srcs": true, "deps": true, "test_class": true}}
	return map[string]rule.KindInfo{"java_library": info, "java_test": info}
}

var imports = regexp.MustCompile(`(?m)^\s*import\s+(?:static\s+)?([\w.]+)`)
var packages = regexp.MustCompile(`(?m)^\s*package\s+([\w.]+)\s*;`)
var external = map[string][]string{
	"dev.cel.expr.conformance.": {"//third_party/cel-spec/proto:fixtures_java"},
	"org.snakeyaml.engine.":       {"@maven//:org_snakeyaml_snakeyaml_engine"},
	"com.oracle.truffle.runtime.": {"@maven//:org_graalvm_truffle_truffle_runtime"},
	"dev.elide.truffledata.":      {"@maven//:dev_elide_truffledata_core"},
	// The bridge and runtime artifacts share this Java package.
	"dev.elide.truffledata.protobuf.": {"@maven//:dev_elide_truffledata_protobuf", "@maven//:dev_elide_truffledata_protobuf_runtime"},
	"dev.elide.truffledata.jdbc.":     {"@maven//:dev_elide_truffledata_jdbc"},
	"com.google.protobuf.":            {"@maven//:com_google_protobuf_protobuf_java"},
	"org.graalvm.shadowed.org.json.":  {"@maven//:org_graalvm_shadowed_json"},
	"org.h2.":                         {"@maven//:com_h2database_h2"},
	"com.oracle.truffle.":             {"@maven//:org_graalvm_truffle_truffle_api"},
	"org.graalvm.polyglot.":           {"@maven//:org_graalvm_polyglot_polyglot"},
	"com.google.re2j.":                {"@maven//:com_google_re2j_re2j"},
	"com.google.gson.":                {"@maven//:com_google_code_gson_gson"},
	"org.junit.":                      {"@maven//:junit_junit"},
}

func dependencies(text string) []string {
	found := map[string]bool{}
	for _, match := range imports.FindAllStringSubmatch(text, -1) {
		for prefix, deps := range external {
			if strings.HasPrefix(match[1], prefix) {
				for _, dep := range deps {
					found[dep] = true
				}
			}
		}
	}
	result := []string{}
	for dep := range found {
		result = append(result, dep)
	}
	sort.Strings(result)
	return result
}
func target(kind, name string, srcs, deps []string) *rule.Rule {
	r := rule.NewRule(kind, name)
	sort.Strings(srcs)
	sort.Strings(deps)
	r.SetAttr("srcs", srcs)
	if len(deps) > 0 {
		r.SetAttr("deps", deps)
	}
	return r
}
func (*java) GenerateRules(args language.GenerateArgs) language.GenerateResult {
	var result language.GenerateResult
	if args.Rel != "src/main/java/dev/trufflecel" && args.Rel != "src/test/java/dev/trufflecel" {
		return result
	}
	sources := []string{}
	texts := map[string]string{}
	for _, file := range args.RegularFiles {
		if !strings.HasSuffix(file, ".java") {
			continue
		}
		b, err := os.ReadFile(filepath.Join(args.Dir, file))
		if err != nil {
			panic(fmt.Errorf("read %s: %w", file, err))
		}
		sources = append(sources, file)
		texts[file] = string(b)
	}
	if strings.HasPrefix(args.Rel, "src/main/") {
		coreSources := []string{}
		coreText := ""
		adapters := map[string]string{"ProtobufType.java": "protobuf", "CanonicalAst.java": "protobuf", "JsonValues.java": "json"}
		adapterSources := map[string][]string{}
		adapterText := map[string]string{}
		for _, file := range sources {
			if name, ok := adapters[file]; ok {
				adapterSources[name] = append(adapterSources[name], file)
				adapterText[name] += texts[file] + "\n"
			} else {
				coreSources = append(coreSources, file)
				coreText += texts[file] + "\n"
			}
		}
		for name, srcs := range adapterSources {
			if name == "protobuf" {
				srcs = append(srcs, "//third_party/cel-spec/proto:schema_java")
			}
			r := target("java_library", name, srcs, append(dependencies(adapterText[name]), ":trufflecel"))
			r.SetAttr("visibility", []string{"//visibility:public"})
			result.Gen = append(result.Gen, r)
		}
		r := target("java_library", "trufflecel", coreSources, dependencies(coreText))
		r.SetAttr("visibility", []string{"//visibility:public"})
		result.Gen = append(result.Gen, r)
	} else {
		helpers := []string{}
		helperText := ""
		for _, file := range sources {
			if !strings.HasSuffix(file, "Test.java") {
				helpers = append(helpers, file)
				helperText += texts[file] + "\n"
			}
		}
		if len(helpers) > 0 {
			deps := append(dependencies(helperText), "//:trufflecel")
			if strings.Contains(helperText, "ProtobufType") { deps = append(deps, "//:trufflecel-protobuf") }
			r := target("java_library", "test_support", helpers, deps)
			r.SetAttr("testonly", true)
			result.Gen = append(result.Gen, r)
		}
		for _, file := range sources {
			if !strings.HasSuffix(file, "Test.java") {
				continue
			}
			stem := strings.TrimSuffix(file, ".java")
			name := strings.TrimSuffix(stem, "Test")
			name = strings.ToLower(name[:1]) + name[1:]
			deps := append(dependencies(texts[file]), "//:trufflecel")
			if strings.Contains(texts[file], "ProtobufType") || strings.Contains(texts[file], "CanonicalAst") {
				deps = append(deps, "//:trufflecel-protobuf")
			}
			if strings.Contains(texts[file], "JsonValues") {
				deps = append(deps, "//:trufflecel-json")
			}
			if len(helpers) > 0 {
				deps = append(deps, ":test_support")
			}
			r := target("java_test", name, []string{file}, deps)
			pkg := packages.FindStringSubmatch(texts[file])
			if len(pkg) != 2 {
				panic("missing Java package: " + file)
			}
			r.SetAttr("test_class", pkg[1]+"."+stem)
			result.Gen = append(result.Gen, r)
		}
	}
	live := map[string]bool{}
	for _, r := range result.Gen {
		live[r.Name()] = true
		result.Imports = append(result.Imports, nil)
	}
	if args.File != nil {
		for _, r := range args.File.Rules {
			if (r.Kind() == "java_library" || r.Kind() == "java_test") && !live[r.Name()] {
				result.Empty = append(result.Empty, rule.NewRule(r.Kind(), r.Name()))
			}
		}
	}
	return result
}
