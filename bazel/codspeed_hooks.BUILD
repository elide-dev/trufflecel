load("@rules_cc//cc:defs.bzl", "cc_library")

cc_library(
    name = "hooks",
    srcs = ["dist/core.c"],
    hdrs = glob(["includes/*.h"]),
    copts = [
        "-x", "c", "-std=gnu11",
        "-O2",
        "-g",
        "-fPIC",
        "-fno-stack-protector",
        "-fomit-frame-pointer",
    ],
    includes = ["includes"],
    linkstatic = True,
    visibility = ["//visibility:public"],
)
