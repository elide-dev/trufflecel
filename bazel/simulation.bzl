"""Strip unsupported DWARF for Valgrind, retaining symbols and identical code."""

load("@bazel_tools//tools/cpp:toolchain_utils.bzl", "find_cpp_toolchain")

def _simulation_impl(ctx):
    cc = find_cpp_toolchain(ctx)
    out = ctx.actions.declare_file("trufflecel-simulation")

    # LLVM's objcopy is a toolchain input. Preserve symbols and require byte-identical machine code.
    ctx.actions.run_shell(
        inputs = depset([ctx.file.image], transitive = [cc.all_files]),
        outputs = [out],
        arguments = [cc.objcopy_executable, ctx.file.image.path, out.path],
        command = """set -eu
"$1" --strip-debug --remove-section=.gnu_debuglink "$2" "$3"
"$1" --dump-section=.text="$3.before" "$2" "$3.original"
"$1" --dump-section=.text="$3.after" "$3" "$3.stripped"
cmp "$3.before" "$3.after"
rm "$3.before" "$3.after" "$3.original" "$3.stripped"
""",
        mnemonic = "BenchmarkSimulationImage",
    )
    return [DefaultInfo(files = depset([out]))]

simulation_image = rule(
    implementation = _simulation_impl,
    attrs = {
        "image": attr.label(mandatory = True, allow_single_file = True),
        "_cc_toolchain": attr.label(default = "@bazel_tools//tools/cpp:current_cc_toolchain"),
    },
    fragments = ["cpp"],
    toolchains = ["@bazel_tools//tools/cpp:toolchain_type"],
)
