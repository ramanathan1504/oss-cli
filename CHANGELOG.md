# Changelog

## 1.7.0

_2026-08-12_

- Stop one unavailable runner blocking the whole release (#31)
- jlink: --compress=2, not zip-6 (#30)
- Apply the formatter, and stop merging red (#29)
- Ship a self-contained oss for every platform (#28)
- Embeddings that run inside the process, with no server (#27)
- Keep the service running on Linux and Windows too, not only macOS (#26)
- Search your own data with no model at all (#25)
- search needs Ollama; the table said it did not (#24)
- Move pr and issue into the core too (#23)
- Move followup into the core, where it never needed a bench (#22)
- Stop bumping the tap from the release workflow (#21)


## 1.6.1

_2026-08-11_

- Call it oss (#20)
- Let people name this command themselves (#19)
- Drop the local docs pane; documentation belongs on the site (#18)
- deps: bump com.diffplug.spotless:spotless-maven-plugin from 2.43.0 to 3.9.0 (#17)
- Apache 2.0 headers on every source file, contributing rules, dependency alerts (#12)


## 1.6.0

_2026-08-11_

- Call them runners and memories, not benches and kbs
- Offer what is worth doing next, where the last thing finished
- Show what a command is doing while it does it
- Serve: keep it running if asked, and read attached extensions' docs
- Add an installation guide, and say where everything ends up


## 1.5.1

_2026-08-11_

- Detect a drifted manifest instead of silently acting on the snapshot
- Correct two comments the approval redesign left stale


## 1.5.0

_2026-08-11_

- Reframe serve: the page is not the point, the open set of benches is
- Serve on :1504 with a palette you attach benches to
- Upstream writes: refused by default, approved by name, confirmed every time
- Extensions: a bench that runs, a kb that remembers, gated when they write


## 1.4.1

_2026-08-10_

- SETUP: align the markdown tables
- README: say where OSS-CLI sits, of the three
- Publish the site from CI (#10)


## 1.4.0

_2026-08-02_

- Make the release publish step repeatable (#9)
- Build, verify and smoke-test on every push (#8)
- Detect the toolchain Gradle projects declare (#7)
- Add onboard, the contributor's view of a repository profile (#6)
- Cut a release with one command (#5)
- Escalate a review when the diff is larger than the local model can read (#4)
- Add the single-page site, deployable to Cloudflare Pages (#3)
- Let review consult the reviewer's own notes (#2)
- Keep generated notes out of the folders holding hand-written ones (#1)
- Report what actually happened, index every repo, and close the loop
- Remove a merge-conflict marker left in .gitignore


## 1.3.1

_2026-08-02_

- Remove a merge-conflict marker left in .gitignore

## 1.3.0

_2026-08-01_

- Tell users where their data is, and stop naming dead binaries
- Release the tree the jar was built from

<!--
Entries above 1.3.1 are historical, reconstructed from tags.

From 1.3.2 onward this file is written by ./release.sh, which lists every
non-merge commit since the previous tag. Edit an entry before releasing if a
commit subject does not read well out of context; the release workflow copies
the section for the version being tagged into the GitHub release notes.
-->
