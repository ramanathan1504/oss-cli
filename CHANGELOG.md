# Changelog

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
