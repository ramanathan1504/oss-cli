# Contributing

Contributions are welcome. This page is short on purpose — everything below is a
consequence of two rules.

## 1. Fork, then open a pull request

`main` is protected: it takes no direct pushes, no force-pushes, and cannot be
deleted. There is no write access to hand out, so the path is the same for
everyone including people who have been here a while:

```bash
gh repo fork ramanathan1504/oss-cli --clone
git switch -c what-it-does
# … change things …
gh pr create
```

Branch from `main`, name the branch after what it does, and open the pull
request against `main`.

Conversations on a pull request must be resolved before it merges. That is not
ceremony: an unresolved thread is usually a question nobody answered, and merging
past it is how the answer gets lost.

## 2. Say what you verified, not what you intended

The single most useful line in a pull request is what you ran and what it
printed. "Should fix the parsing" and "reproduced the crash on the attached
input, and it no longer occurs" cost the same to write and are worth very
different amounts to review.

If you could not verify something, say that too. A stated gap is reviewable; a
silent one is discovered later, by someone else.

## Building it

```bash
mvn package -DskipTests
OSS_CLI_HOME=~/.oss-cli-dev java -jar target/oss-cli-<version>.jar doctor
```

**Always set `OSS_CLI_HOME` for a development build.** A release and a
development build come from the same pom by the same command, so the version
string cannot tell them apart — and the schema migrations are one-way. Pointing a
dev build at your real data is not recoverable by re-running it.

Since 3.0 you are not relying on remembering. One thing *does* tell them apart at
runtime — where the jar is — and the build uses it: a jar under `target/`, opened
against the default store, with data already in it, **refuses** rather than
migrating, and prints both ways out. An installed release never asks, because
migrating your store is the point of upgrading. A dev build on a scratch
`OSS_CLI_HOME` never asks either.

```bash
# go ahead anyway, having read the above
OSS_ALLOW_SCHEMA_UPGRADE=1 java -jar target/oss-cli-<version>.jar doctor
```

This exists because it happened: on 2026-08-22 one command missing its
`OSS_CLI_HOME` took a 727 MB store from schema 14 to 15, and the installed `oss`
refused it until a release carrying 15 existed.

`doctor` exits non-zero when an *optional* prerequisite is missing (no local
model running, a token in the Keychain rather than the environment). That is a
report, not a failed install — read the lines, not the exit code.

## What your pull request has to pass

Every one of these runs on the pull request, and a red one blocks the merge:

| Check | What it is |
|---|---|
| `ubuntu · JDK 17` | the suite, and the smoke test against the built jar |
| `ubuntu · JDK 21` | the same — the shipped archives jlink a 21 runtime, so 21 is what most people execute |
| `macos-14 · JDK 17` | the same |
| `windows-latest · JDK 17` | the same. The first run of it found six defects, one of them in the product |
| `site` | the landing page's markup, and that no host outside the CSP is referenced |
| Distributions | only on a release pull request — the archives are proven **before** the tag exists |

`mvn verify` runs spotless as part of `verify`, so formatting is a build failure
rather than a review comment. Run it before you push; it is the same command CI
runs.

**Added or removed a command or a flag?** `release-surface.json` has to follow:

```bash
mvn test -Dtest=ReleaseSurfaceTest -Dsurface.update=true
```

Then commit it. `ReleaseSurfaceTest` fails the pull request otherwise, which is
the point — the record is updated by the person making the change.

Hiding a command from `oss --help` is not removing it, and the smoke test checks
both halves: that the everyday set is listed, and that everything else is still
reachable through `oss --help-all`.

## Licence

Apache 2.0. New source files carry the standard header; the build does not add
it for you, and a file without one will be asked about in review.

By opening a pull request you agree that your contribution is licensed under the
same terms.

## Reporting something insecure

Do not open a public issue for a vulnerability. Use GitHub's **Report a
vulnerability** button under the Security tab, which is private until there is a
fix worth publishing.

Dependency alerts and secret scanning are enabled on this repository, so an
automated pull request from Dependabot is expected traffic rather than a
compromise.
