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
development build come from the same pom by the same command, so nothing else
tells them apart at runtime — and the schema migrations are one-way. Pointing a
dev build at your real data is not recoverable by re-running it.

`doctor` exits non-zero when an *optional* prerequisite is missing (no local
model running, a token in the Keychain rather than the environment). That is a
report, not a failed install — read the lines, not the exit code.

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
