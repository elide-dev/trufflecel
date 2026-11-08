"""Thin Maven publications from the same JavaInfo used by Bazel consumers."""

load("@rules_java//java/common:java_info.bzl", "JavaInfo")

VersionInfo = provider(fields = ["value"])

def _version_impl(ctx):
    return [VersionInfo(value = ctx.build_setting_value)]

release_version = rule(implementation = _version_impl, build_setting = config.string(flag = True))

def _publication_impl(ctx):
    version = ctx.attr._version[VersionInfo].value
    if not version or any([c not in "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ.-" for c in version.elems()]):
        fail("Invalid Maven version")
    artifact = "trufflecel-" + ctx.attr.module
    prefix = "%s/dev/elide/%s/%s/%s-%s" % (ctx.label.name, artifact, version, artifact, version)
    dependencies = []
    graalvm_version = ctx.attr.graalvm_version
    for coordinate in ctx.attr.dependencies:
        group, name, dep_version = coordinate.split(":")
        dependencies.append("<dependency><groupId>%s</groupId><artifactId>%s</artifactId><version>%s</version><scope>compile</scope>%s</dependency>" % (group, name, dep_version.replace("{version}", version), "<optional>true</optional>" if coordinate in ctx.attr.optional_dependencies else ""))
    pom = ctx.actions.declare_file(prefix + ".pom")
    ctx.actions.write(pom, """<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>dev.elide</groupId><artifactId>{artifact}</artifactId><version>{version}</version>
  <name>TruffleCEL {module}</name>
  <description>CEL on Truffle: {module}</description>
  <url>https://github.com/elide-dev/trufflecel</url>
  <licenses>
    <license><name>MIT License</name><url>https://opensource.org/license/mit</url><distribution>repo</distribution><comments>MIT OR Apache-2.0; choose either license.</comments></license>
    <license><name>Apache License, Version 2.0</name><url>https://www.apache.org/licenses/LICENSE-2.0</url><distribution>repo</distribution><comments>MIT OR Apache-2.0; choose either license.</comments></license>
  </licenses>
  <properties><maven.compiler.release>25</maven.compiler.release></properties>
  <repositories><repository><id>elide</id><url>https://maven.elide.dev</url></repository><repository><id>elide-graalvm</id><url>https://maven.elide.dev/gvm-elide/{graalvm_version}</url></repository></repositories>
  <dependencies>{dependencies}</dependencies>
</project>
""".format(graalvm_version = graalvm_version, artifact = artifact, version = version, module = ctx.attr.module, dependencies = "\n    ".join(dependencies)))
    info = ctx.attr.library[JavaInfo]
    outputs = [pom]
    for suffix, source in [(".jar", info.runtime_output_jars[0]), ("-sources.jar", info.source_jars[0])]:
        output = ctx.actions.declare_file(prefix + suffix)
        args = ctx.actions.args()
        args.add_all(["--output", output.path, "--normalize", "--sources", source.path, "--resources"])
        args.add_all([file.path + ":META-INF/" + file.basename for file in ctx.files._legal])
        ctx.actions.run(
            executable = ctx.executable._singlejar,
            arguments = [args],
            inputs = [source] + ctx.files._legal,
            outputs = [output],
            mnemonic = "LicensedMavenJar",
        )
        outputs.append(output)
    return [DefaultInfo(files = depset(outputs)), OutputGroupInfo(binary = depset([outputs[1]]))]

maven_publication = rule(
    implementation = _publication_impl,
    attrs = {
        "library": attr.label(providers = [JavaInfo], mandatory = True),
        "module": attr.string(mandatory = True),
        "dependencies": attr.string_list(),
        "optional_dependencies": attr.string_list(),
        "graalvm_version": attr.string(default = "25.5.5-elide.27"),
        "_singlejar": attr.label(default = "@bazel_tools//tools/jdk:singlejar", executable = True, cfg = "exec"),
        "_legal": attr.label_list(default = ["//:LICENSE", "//:LICENSE-MIT", "//:LICENSE-APACHE", "//:NOTICE"], allow_files = True),
        "_version": attr.label(default = "//tools/release:version"),
    },
)
