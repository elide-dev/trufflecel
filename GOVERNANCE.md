# Project governance

TruffleCEL is maintained within the [Elide organization](https://github.com/elide-dev).
Participation is open; repository maintainers are responsible for reviews,
merges, releases, security response, and community moderation.

Discuss substantial changes in a GitHub issue before implementation. Decisions
should consider CEL compatibility, embedding needs, performance, maintainability,
and evidence from tests and benchmarks. Maintainers seek agreement through public
technical discussion and make the final decision when agreement is not possible.
Security reports and private community matters are handled confidentially.

Anyone may propose a change or ask for a decision to be reconsidered with new
evidence. Maintainers may invite sustained contributors to take on review or
maintenance responsibilities; organization owners manage repository access.
No contribution count automatically grants permissions.

## Responsibilities and continuity

Sam Gammon ([sgammon](https://github.com/sgammon)) leads the project and resolves
technical-direction disputes after discussion. Repository administrators are
[sgammon](https://github.com/sgammon), [darvld](https://github.com/darvld), and
[TravisCow](https://github.com/TravisCow); GitHub admin permissions were verified
on November 9, 2026. CODEOWNERS routes review requests to them.

Administrators handle issue triage, independent review, CI maintenance, release
qualification, private security reports, and community moderation. Review access
when responsibilities change. Use strong account authentication and keep recovery
material in the organization's protected systems, never in repository files.

If a maintainer becomes unavailable, another administrator should take over
triage and release coordination. Admin access alone does not establish recovery
of external Maven/R2 credentials; verify that capability privately before a release
depends on it. The public [OpenSSF evidence](docs/OPENSSF.md) distinguishes verified
access from unverified recovery exercises and other continuity claims.

Changes normally receive independent review and green required checks. Any
explicitly authorized administrative bypass must be recorded in the PR, with
follow-up review before release. A bypass does not count as independent approval.

See [contributing](CONTRIBUTING.md), the [code of conduct](CODE_OF_CONDUCT.md),
and the [security policy](SECURITY.md) for participation and reporting details.
