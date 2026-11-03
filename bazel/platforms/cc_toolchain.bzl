"""C++ tools for Bazel's own zipper, supplied by the digest-pinned RBE image."""
load("@rules_cc//cc:defs.bzl", "CcToolchainConfigInfo")
load("@rules_cc//cc:cc_toolchain_config_lib.bzl", "feature", "flag_group", "flag_set", "tool_path", "action_config", "tool")
load("@rules_cc//cc/common:cc_common.bzl", "cc_common")

def _config(ctx):
    return cc_common.create_cc_toolchain_config_info(
        ctx = ctx,
        toolchain_identifier = "trufflecel-rbe-image-gcc",
        host_system_name = "x86_64-linux-gnu",
        target_system_name = "x86_64-linux-gnu",
        target_cpu = "k8",
        target_libc = "glibc",
        compiler = "gcc",
        abi_version = "gcc",
        abi_libc_version = "glibc",
        cxx_builtin_include_directories = ["/usr/include", "/usr/lib/gcc", "/usr/local/include", "/usr/lib/x86_64-linux-gnu"],
        action_configs = [action_config(action_name = "c-compile", enabled = True, tools = [tool(path = "/usr/bin/gcc")])],
        tool_paths = [tool_path(name = name, path = "/usr/bin/" + path) for name, path in {"gcc": "g++", "ld": "ld", "ar": "ar", "cpp": "cpp", "gcov": "gcov", "nm": "nm", "objdump": "objdump", "strip": "strip", "objcopy": "objcopy"}.items()],
        features = [feature(name = "cxx17", enabled = True, flag_sets = [flag_set(actions = ["c++-compile", "c++-link-executable", "c++-link-dynamic-library"], flag_groups = [flag_group(flags = ["-std=c++17"])])])],
    )

rbe_cc_config = rule(implementation = _config, provides = [CcToolchainConfigInfo])
