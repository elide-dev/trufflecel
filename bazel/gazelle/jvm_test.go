package gazelle

import (
	"github.com/bazelbuild/bazel-gazelle/language"
	"github.com/bazelbuild/bazel-gazelle/rule"
	"os"
	"path/filepath"
	"reflect"
	"testing"
)

func TestDependencies(t *testing.T) {
	got := dependencies("package sample;\nimport java.util.List;\nimport com.google.re2j.Pattern;\nimport static org.junit.Assert.assertTrue;\nimport org.junit.Test;")
	want := []string{"@maven//:com_google_re2j_re2j", "@maven//:junit_junit"}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("got %v, want %v", got, want)
	}
}
func TestGenerationAndRemoval(t *testing.T) {
	dir := t.TempDir()
	for name, text := range map[string]string{"NewTest.java": "package dev.trufflecel;\nimport org.junit.Test;", "Support.java": "package dev.trufflecel;", "ignored.txt": ""} {
		if err := os.WriteFile(filepath.Join(dir, name), []byte(text), 0600); err != nil {
			t.Fatal(err)
		}
	}
	old := rule.EmptyFile("BUILD.bazel", "")
	old.Rules = append(old.Rules, rule.NewRule("java_test", "old"))
	g := (&java{}).GenerateRules(language.GenerateArgs{Dir: dir, Rel: "src/test/java/dev/trufflecel", RegularFiles: []string{"NewTest.java", "Support.java", "ignored.txt"}, File: old})
	if len(g.Gen) != 2 || g.Gen[1].Name() != "new" || g.Gen[1].AttrString("test_class") != "dev.trufflecel.NewTest" {
		t.Fatalf("bad generation: %v", g.Gen)
	}
	if len(g.Empty) != 1 || g.Empty[0].Name() != "old" {
		t.Fatal("stale target was not removed")
	}
	if got := g.Gen[0].AttrStrings("srcs"); !reflect.DeepEqual(got, []string{"Support.java"}) {
		t.Fatal(got)
	}
}

func TestOptionalAdapterBoundaries(t *testing.T) {
	dir := t.TempDir()
	files := map[string]string{
		"Cel.java":          "package dev.trufflecel;\nimport dev.elide.truffledata.DataLibrary;",
		"ProtobufType.java": "package dev.trufflecel;\nimport dev.elide.truffledata.protobuf.ProtobufAdapter;",
		"CanonicalAst.java": "package dev.trufflecel;\nimport com.google.protobuf.Message;",
		"JsonValues.java":   "package dev.trufflecel;\nimport org.graalvm.shadowed.org.json.JSONObject;",
	}
	var names []string
	for name, source := range files {
		names = append(names, name)
		if err := os.WriteFile(filepath.Join(dir, name), []byte(source), 0600); err != nil {
			t.Fatal(err)
		}
	}
	generated := (&java{}).GenerateRules(language.GenerateArgs{Dir: dir, Rel: "src/main/java/dev/trufflecel", RegularFiles: names})
	if len(generated.Gen) != 3 {
		t.Fatal("expected core and two optional adapters")
	}
	for _, target := range generated.Gen {
		if target.Name() == "protobuf" {
			want := []string{"//third_party/cel-spec/proto:schema_java", "CanonicalAst.java", "ProtobufType.java"}
			if !reflect.DeepEqual(target.AttrStrings("srcs"), want) {
				t.Fatalf("canonical interchange must remain in the optional protobuf artifact: %v", target.AttrStrings("srcs"))
			}
		}
		if target.Name() == "trufflecel" {
			if !reflect.DeepEqual(target.AttrStrings("srcs"), []string{"Cel.java"}) {
				t.Fatal("optional source leaked into core")
			}
			if !reflect.DeepEqual(target.AttrStrings("deps"), []string{"@maven//:dev_elide_truffledata_core"}) {
				t.Fatal("optional dependency leaked into core")
			}
		}
	}
}
