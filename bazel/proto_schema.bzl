"""Embed pinned protobuf descriptors in Java; no runtime resource lookup."""
load("@protobuf//bazel/common:proto_info.bzl", "ProtoInfo")
load("@rules_java//java/common:java_info.bzl", "JavaInfo")

def _sources_impl(ctx):
    sources = []
    for i, jar in enumerate(ctx.attr.library[JavaInfo].source_jars):
        output = ctx.actions.declare_file(ctx.label.name + str(i) + ".srcjar")
        ctx.actions.symlink(output = output, target_file = jar)
        sources.append(output)
    return [DefaultInfo(files = depset(sources))]

# Recompile generated fixtures with the Maven runtime selected by their consumer.
# Do not pull protobuf's Bazel runtime (33.x) ahead of cel-java's newer gencode.
java_proto_sources = rule(
    implementation = _sources_impl,
    attrs = {"library": attr.label(providers = [JavaInfo])},
)

def _impl(ctx):
    output = ctx.actions.declare_file("CanonicalSchema.java")
    descriptors = ctx.attr.schema[ProtoInfo].transitive_descriptor_sets
    ctx.actions.run(
        executable = ctx.executable.generator,
        arguments = [output.path] + [f.path for f in descriptors.to_list()],
        inputs = descriptors,
        outputs = [output],
        mnemonic = "EmbedCelSchema",
    )
    return [DefaultInfo(files = depset([output]))]

proto_schema_java = rule(
    implementation = _impl,
    attrs = {
        "schema": attr.label(providers = [ProtoInfo]),
        "generator": attr.label(executable = True, cfg = "exec"),
    },
)
