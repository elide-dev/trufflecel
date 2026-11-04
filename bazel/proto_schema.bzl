"""Embed pinned protobuf descriptors in Java; no runtime resource lookup."""
load("@protobuf//bazel/common:proto_info.bzl", "ProtoInfo")

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
