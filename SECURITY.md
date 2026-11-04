# Security policy

## Reporting a vulnerability

Use GitHub's [private vulnerability reporting](https://github.com/elide-dev/trufflecel/security/advisories/new)
for suspected vulnerabilities. Do not publish exploit details in a public issue
or pull request before coordinated disclosure.

Include the affected version and module, JVM or Native Image version, operating
system, a minimal reproducer, expected and actual behavior, and the potential
impact. Remove credentials and personal data. Resource-limit bypasses, unexpected
host access, and crashes or denial of service from untrusted CEL input are useful
reports. Include any custom functions, host bindings, and configured limits needed
to reproduce the issue.

Maintainers will investigate privately and coordinate fixes and disclosure with
the reporter. There is no guaranteed response time or paid bug bounty.

## Supported versions

Security fixes target the latest stable release and the main branch. Older
releases and prereleases do not have a separate maintenance guarantee; upgrade
to the latest stable release. Release information is available on
[GitHub Releases](https://github.com/elide-dev/trufflecel/releases).

## Embedding responsibilities

Configure resource limits for untrusted expressions and inputs. Host functions,
objects, and permissions are supplied by the embedder; expose only the capabilities
an expression needs. CEL evaluation limits do not constrain arbitrary code inside
host callbacks. Keep TruffleData, GraalVM, and other runtime dependencies updated.
