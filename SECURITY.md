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

Maintainers investigate privately and coordinate fixes and disclosure with the
reporter. Aim to acknowledge reports within seven calendar days and always triage
an unanswered report before fourteen days have elapsed. Another repository
administrator should take over if the assigned maintainer is unavailable.
There is no paid bug bounty or contractual support SLA.

Prioritize confirmed critical vulnerabilities immediately, coordinate a fix or
mitigation promptly, and do not ship a release with an unresolved confirmed
critical issue. Confirmed medium-or-higher vulnerabilities must not remain
unpatched for more than sixty days after public disclosure. Apply this triage
policy to static-analysis and fuzzing findings as well as outside reports.
Release notes must identify publicly known vulnerabilities fixed in that release,
including assigned CVE/GHSA identifiers and upgrade or mitigation instructions.
Record acknowledgement and resolution dates privately so response claims can be
verified without exposing report contents.

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
