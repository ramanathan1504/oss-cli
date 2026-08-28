# Changelog

## 4.9.0

_2026-08-28_

- A knowledge base from your own CLI sessions, and a note for every change that landed (#216)


## 4.8.0

_2026-08-26_

- Nothing waits in silence any more (#213, and the four commits after it)

  Every command that reads the whole store, and every call to a model, now says
  what it is doing and how long it has been. `oss claude review` on a large pull
  request used to print its facts and then show nothing for minutes while the
  model worked -- measured at four and a half with the process alive throughout,
  which cannot be told apart from a hang. The CLI path counts the answer as it
  arrives, because "10 KB so far" is worth watching and a spinner is not.

- The prompt at `oss ask` is a real one

  Arrow keys, ctrl-a, history across sessions, and tab completion that offers
  the choices when more than one matches. It was a raw read before, where the up
  arrow printed an escape sequence into the middle of the question being typed.

- Output is designed rather than accumulated

  One layer for everything a command prints, in the site's palette: brass for
  what you type, teal for a good outcome, rust for a bad one. A review's file
  churn, CI results and APPROVED/CHANGES_REQUESTED are coloured, because a
  column of forty identical grey lines is read rather than scanned. `NO_COLOR`
  turns it off, and it is off automatically when the output is not a terminal.

- A model that answers oddly no longer looks like a crash

  `oss claude --cli` printed a Jackson stack trace when the tool returned
  anything that was not the JSON it asks for -- a wrapper script on PATH, a
  login prompt, a changed envelope. It now says what came back and what to do.
  Reading the two pipes one after the other, which could deadlock against a tool
  that says much on stderr, is fixed at the same time.


## 4.7.0

_2026-08-25_

- Nobody had designed what this prints (#213)
- Six things that were wrong every time you used it (#212)


## 4.6.0

_2026-08-25_

- A pack could be described as data, but it could not be one (#210)

  The matrix engine offers eighteen hooks a pack may define, and a pack file
  could express two. Anyone whose project needed a third -- a JDK floor for one
  application, a config property spelled differently on one major version, a
  reason a cell cannot run -- wrote `pack.sh` by hand and kept it.

  Sixteen are fields now: `minJavaFor`, `minVersionFor`, `requiresConfigFor`,
  `requiresAppFor`, `interactiveApps`, `skipWhen`, `buildFlags` and
  `buildFlagsWhen`, `alwaysJvmArgs`, `jvmArgsFor`, `configArgs` and
  `configArgsAlso`, `gradleVersionFlag`, `upstreamRepo`, `sourceClone` and
  `sourceCloneHint`. Keys are glob patterns; `{app}`, `{config}`, `{version}`
  and `{java}` stand for what the rule was asked about.

  Two are not data and are not pretended to be: one lists a project's modules
  out of a checkout, the other filters a classpath on stdin. Both are
  pipelines, so `shell` carries them verbatim -- needing one costs a field
  rather than the whole file.

  Nothing about `pack.sh` changes, and a pack that has one is unaffected.

## 4.5.3

_2026-08-25_

- `oss run help` listed a verb that answers "moved", and setup pointed at it too (#208)

  The hub left the matrix engine: the read-only half is in the core, the half
  that could POST stayed with the pack that owned its review composer. Two
  places still advertised `oss run hub` as though it ran. The help block now
  says where it went and names `oss hub` and `oss serve`.

  The one that mattered is `oss setup`: its Upstream writes section is the only
  place a reader is told how to permit an outward write, and it demonstrated
  the flag on that dead verb. It reads `oss run <verb> --approve-upstream
  owner/name` now -- the flag lives on the dispatcher, and which verb writes is
  whatever the attached extension declared, so naming one was wrong regardless.

- The landing page answered `oss` convincingly, and could not (#207)

  The interactive shell on ubuos.com is replaced by a recorded session that
  plays. A page cannot run `oss`, so every answer it gave was a promise kept by
  hand against a switch statement, aimed at the one visitor who has installed
  nothing yet. 265 lines out, 152 in.

## 4.5.2

_2026-08-25_

- The list that decides what a browser may run was kept by hand (#204)

  `oss serve` may dispatch a command only because a test proves nothing on its
  table writes. That test read `Askable.WRITES` -- a list kept by hand -- and
  believed it, so a verb left off was silently treated as harmless. Five were
  missing, including `bug`, which posts a GitHub issue. Nothing on the page
  asked it, so nothing was wrong yet, but a button that did would have passed
  the test written to forbid exactly that.

  `Askable.READS` is added, and between the two lists every command the program
  has must now be classified against the recorded surface. A new command fails
  the build until somebody says which it is, and a classified name that no
  longer exists fails too.

- `oss ext list` said a pack gets attached, and `oss run --help` agreed (#205)

  A pack is data read off disk, with no program to call and nothing to
  register, so it cannot be attached. `oss run` describes itself as built in
  now, the way `oss memory` beside it already did.

## 4.5.1

_2026-08-25_

- Setup asked for a path to something it already had (#202)

  `oss setup` offered to register a "bench" and a "kb". Both names died in the
  rename, and both capabilities ship inside the tool -- the matrix engine is
  `oss run`, memory is `oss memory` -- so the prompt read as a missing
  dependency and invited a path where none was wanted. It now says what is
  built in before it asks, and says a pack does not belong there.

  The kind check behind the prompt could never pass: it compared a manifest
  kind, `runner` or `memory`, against the literal `bench` or `kb` the caller
  still passed, so a correct extension was always warned about being the wrong
  kind before being registered anyway. It compares the enum now.

## 4.5.0

_2026-08-24_

- `--pr` recorded whatever tree you were standing in, which is never the change (#199)

  `oss run --pr <n> --checkout <verb>` fetches the pull request's head into a
  throwaway git worktree, runs there, and removes it afterwards — so the result
  is about the change under review rather than about your own checkout. A pull
  request from a fork is refused unless `--allow-fork` is given: running one
  executes its author's build on your machine.


## 4.4.0

_2026-08-24_

- The runner answered the one question a model cannot, and nobody kept the answer (#197)

  `oss run --pr <n> --repo owner/name <verb>` records what the runner found
  against that pull request. `review`, `hub`, `followup` and the board all read
  it **before any model is consulted**. Both commits are stored — the pull
  request's head and the local `HEAD` the run happened on — so a pass from a
  tree that is not the change under review can never read like a pass.


## 4.3.0

_2026-08-24_

- The board showed a picture of an answer, and the manual was a different product (#195)

  Removes `/api/attach`, `/api/detach` and `/api/rows`. The board can no longer
  attach an extension — nothing it serves writes now. Use `oss ext add <path>`.


## 4.2.0

_2026-08-23_

- A newline where an escape was meant, and saying yes handed over nothing (#193)
- Rungs had no test, which is where the route becomes something you can call (#192)


## 4.1.1

_2026-08-23_

- Two more journeys, and the one call that had a host written into it (#190)
- Journey tests: extensions, memory, and the offline promise (#189)


## 4.1.0

_2026-08-23_

- A pack could describe every application it has and start none of them (#187)


## 4.0.2

_2026-08-23_

- Routine bumps: jackson-databind 2.22.2, spotless 3.10.0 (#185)
- Exit codes are half the interface and nothing checked them (#184)


## 4.0.1

_2026-08-23_

- The docs on localhost still taught the front door we stopped printing (#182)


## 4.0.0

_2026-08-23_

- The example pack's cache got committed by its own tests (#180)
- The engine did not know what it was walking, except for the six places it did (#179)
- The offline agent refused instead of answering, and 783 tests were happy (#178)
- The first thing on the page replayed the command --help stopped printing (#177)
- Naming somebody else's project in a commit message publishes it too (#176)
- The site check reported a page that documents the command it said was missing (#175)
- The landing page still opened with the product from three releases ago (#174)
- The other site does not follow this one, so stop hoping somebody remembers (#173)


## 3.3.0

_2026-08-23_

- The same page, saying something different to each reader (#171)
- Fourteen seconds before the question, and one project's name in everybody's migration (#170)


## 3.2.0

_2026-08-23_

- The documents still showed chat where ask now stands (#168)
- Five commands needed a model; one of them is enough (#167)
- A conversation, memory in front of every question, and skills you can edit (#166)


## 3.1.0

_2026-08-22_

- One local page: the board, the documentation, and a question about who answers (#164)
- Fifty-five seconds to be wrong about something oss answers in eight (#163)
- Three gaps closed with machinery this repository already had (#162)
- An edit you can see before it happens (#161)
- Three defects in the loop I shipped yesterday, found by auditing it rather than trusting the summary (#160)
- oss ask: one CLI that reads, recalls and runs — on whichever rung you have (#159)


## 3.0.1

_2026-08-22_

- The docs said fourteen, the code said fifteen, and one of them ships (#157)
- An unversioned store is still your store, and the guard was writing before it decided (#156)
- An older build refuses a newer store, loudly. A newer build took an older one in silence. (#155)
- The tests ran on one platform; the archives shipped for three (#154)
- The README and the landing page still described a forty-command menu (#153)


## 3.0.0

_2026-08-22_

- Thirty-eight commands in one flat list is an inventory, not a menu (#151)


## 2.4.0

_2026-08-22_

- Seventeen rows, three calls each, one at a time (#149)
- backlog died at the last line, after paying for every fetch (#148)
- 1,874 notes on this machine, and almost none of them are yours (#147)
- An engine you can reach, refused because you could only reach it one way (#146)


## 2.3.0

_2026-08-21_

- Four commands that could not print their own usage (#144)
- An error that was right, and sent you looking for a verb that does not exist (#142)


## 2.2.5

_2026-08-21_

- A colon in a filename cost the Windows build, and the tag was already pushed (#140)


## 2.2.4

_2026-08-21_

- Same model, different vectors: put the embedding runtime back (#138)
- onnxruntime 1.29 dropped Intel Macs, and the error said nothing about it (#137)
- ci: bump actions/download-artifact from 4 to 8 (#106)
- ci: bump actions/upload-artifact from 4 to 7 (#107)
- ci: bump actions/setup-java from 4 to 5 (#108)
- ci: bump actions/checkout from 4 to 7 (#109)
- deps: bump the routine group with 4 updates (#110)
- deps: bump org.junit.jupiter:junit-jupiter from 5.11.4 to 6.1.3 (#111)


## 2.2.3

_2026-08-21_

- The manual said three questions; there are four (#135)
- A warning that could never clear, over a gap that was real (#134)


## 2.2.2

_2026-08-21_

- Forty seconds of blank terminal, and an answer that was silence (#132)
- Nudged the site before the release was finished (#131)


## 2.2.1

_2026-08-21_

- The sweep: a crash, a hang, a silence, two documents, and the daily job that embedded nothing (#129)
- One slow question took the whole board down with it (#128)
- Push the release out, do not wait to be polled (#127)


## 2.2.0

_2026-08-21_

- The board leads the page, one theme across both, and the runner's verbs on 1504 (#125)
- A runner that answers with nothing attached, and fourteen repositories nobody asked for (#124)
- The knowledge base, the board and the daily job, all inside one install (#123)
- A pack is a file the tool reads, not a script it runs (#122)


## 2.1.2

_2026-08-19_

- One palette, and a light mode that separates (#120)


## 2.1.1

_2026-08-19_

- Read the examples, so a page cannot teach a flag that was removed (#118)


## 2.1.0

_2026-08-19_

- Run a local model in this process, and say what a small one cannot do (#116)


## 2.0.0

_2026-08-19_

- The engine goes in front of the command, and an idle daemon costs nothing (#114)


## 1.11.16

_2026-08-19_

- A dispatcher typed bare says what it can do, not what is missing (#112)


## 1.11.15

_2026-08-16_

- Say that the local page exists, and that it is not a second product (#104)


## 1.11.14

_2026-08-16_

- Say the command you can actually type (#102)


## 1.11.13

_2026-08-15_

- The local page on :1504 was a third palette (#100)
- Make the offline claim something you can flip (#100)
- Changelog dates are UTC, so the site and the repo agree (#99)

## 1.11.12

_2026-08-15_

- oss history browsed by resuming a conversation (#97)

## 1.11.11

_2026-08-15_

- The serve agent recorded a path brew deletes on the next upgrade (#96)

## 1.11.10

_2026-08-15_

- oss setup needs a terminal, and now says so (#94)
- Let backlog's own flags reach the report (#94)
- Make a pasted example run, and say which of four problems occurred (#94)
- Answer offline, and stop presenting noise as a search result (#94)

## 1.11.9

_2026-08-15_

- Run the commands, in the tests (#92)
- A closed issue could not be reached at all (#91)


## 1.11.8

_2026-08-14_

- Take file logging off the caller's thread, and only file logging (#89)


## 1.11.7

_2026-08-14_

- Stop reporting an extension's own flags as files it could not file (#87)


## 1.11.6

_2026-08-14_

- Do not load a model the machine cannot hold (#85)


## 1.11.5

_2026-08-14_

- A busy provider is not a broken one: retry what is retryable (#83)
- Stop publishing the page from here; ubuos.com serves it (#82)
- The Docs link went to a page that is not the docs (#81)


## 1.11.4

_2026-08-14_

- Compaction you can ask for, a page that shows its own numbers, and three gaps that stopped it working (#79)


## 1.11.3

_2026-08-14_

- A rejected key was sent three times before anyone was told why (#77)


## 1.11.2

_2026-08-14_

- Chat was gated on Ollama, on a tool whose rule is that nothing is (#75)
- Nobody could answer "does this work on a plane?" (#74)


## 1.11.1

_2026-08-14_

- An older build read a store it could not understand, in silence (#72)


## 1.11.0

_2026-08-14_

- A conversation that lived in one terminal's memory (#70)
- Write down what only gets learned by getting it wrong (#69)


## 1.10.1

_2026-08-13_

- A backup written into an indexed folder feeds itself (#67)
- Put the new page where it is actually served from (#66)
- The test isolation did nothing, and the test deleted a real database (#65)
- There are unit tests now, and CI already runs them (#64)


## 1.10.0

_2026-08-13_

- Document what four changes actually did (#61)
- There were no tests. Now there are eighty-four (#62)
- Keep the live line inside the terminal (#60)
- Tell what you worked out apart from what you collected (#59)
- Say what is happening while it happens (#58)
- Follow what people wrote down, not only what resembles what (#57)
- MACOS_AUTOMATION.md was missed by every earlier pass (#56)
- Describe the roles, not the one project that fills them (#55)
- Examples name a placeholder, not somebody else's project (#54)


## 1.9.1

_2026-08-13_

- One unreadable file stopped indexing the rest of its folder (#52)
- Call it oss in the docs, starting with the install command that fails (#51)


## 1.9.0

_2026-08-13_

- One embedder, in this process, and Ollama only for what it writes (#49)
- release.sh: wait for checks to exist before watching them (#48)


## 1.8.3

_2026-08-13_

- Packages runs after Distributions, ships an unversioned deb, and release.sh survives branch protection (#46)
- Bump com.fasterxml.jackson.core:jackson-databind from 2.20.0 to 2.21.5 (#11)
- ci: bump actions/setup-java from 4 to 5 (#13)
- deps: bump the routine group with 6 updates (#14)
- ci: bump actions/checkout from 4 to 7 (#15)
- ci: bump actions/upload-artifact from 4 to 7 (#16)


## 1.8.2

_2026-08-13_

- alias: refuse shell keywords and builtins, which no shim can ever be (#44)


## 1.7.1

_2026-08-12_

- Resolve relative symlinks against the link, not the working directory (#33)
- Point at the runner from the place the question arises (#32)


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
