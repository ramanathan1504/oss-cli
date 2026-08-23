# The engine

Walks a version × configuration × application matrix, forks a real JVM per cell,
and reports what happened. It knows how to *run* things; it knows nothing about
what it is running.

What it is running is a **pack** — a directory somebody else owns, holding a
`pack.sh` and the applications and configurations that file names.

```bash
oss run --pack ~/my-project list --apps
oss run --pack ~/my-kafka-bench run consumer --config at-least-once
```

## Why the engine is here and the pack is not

Only you know what a real application of your project looks like. `oss` can read
a pull request and say which modules it lands in; it cannot tell you whether the
change works, because that needs your application, your configurations and your
versions.

So the split is: the core knows how to run a matrix, and your repository says
what the matrix is. Neither is useful alone, and neither has to know much about
the other — the whole contract is one file.

## The contract

### Required — five things, and the engine refuses to load without them

| | |
|---|---|
| `PACK_NAME` | what this is |
| `VERSIONS`, `DEFAULT_VERSION` | the version axis |
| `APPS` | the application axis |
| `pack_module_path` | where each application lives |

That is a pack that loads and lists. `packs/example/pack.sh` is one, in about
thirty lines. Copy it.

### Optional — everything else, and it already has an answer

| | |
|---|---|
| `PACK_DESC` | a sentence about the pack |
| `PACK_APPS_DIR`, `PACK_CONFIGS_DIR` | where to look, relative to the pack. Default `apps` and `configs` |
| `pack_build_flags`, `pack_gradle_version_flag` | how a version reaches the build. Default: no extra flags |
| `pack_config_args` | **how an application is told where its configuration is** |
| `pack_main_class_for` | how to start one |
| `pack_jvm_args`, `pack_always_jvm_args` | flags an application or the project needs |
| `pack_skip_reason`, `pack_min_java_for`, `pack_min_version_for` | which cells are impossible, and why. Default: none are |
| `pack_requires_config_for`, `pack_requires_app_for` | pairs that cannot go together |
| `pack_gradle_prereq` | what to install before a Gradle app in this pack can build |
| `pack_source_clone`, `pack_modules`, `pack_modules_on_classpath` | for `coverage` |

**Optional means there is already one.** Bash has no way to declare a hook
optional, so the engine defines every one of these before it sources your pack
and yours replaces it by existing. Until 4.0 it did not, and a pack that
declared only the required five printed five `command not found` lines *per
cell*, reported `FAIL` for every one, and exited 0.

`pack_config_args` is the one to get right. It is the only one that punishes a
guess *silently*: pass one major version's property name to the next and nothing
errors — the framework falls back to its default configuration and logs happily,
and an entire column passes while testing nothing at all.

### What the engine will not assume

There is no default pack, no default application and no default configuration.
Until 4.0 there were all three, and they were names out of the first pack this
engine ever walked: `oss run matrix` on anybody else's pack swept applications
that pack did not have, and every cell failed for a reason that was never its
fault. Defaults now come from your own `APPS` and your own `configs/`.

A sweep that produces **no cells** is an error, not a pass. `0 pass, 0 fail,
0 skip` and exit 0 is the shape of a clean run, and it was what an empty axis
printed.

## POSIX only

This is bash, and it forks Maven, Gradle and JVMs. It runs on macOS and Linux.
On Windows use WSL — `oss run` says so rather than failing halfway through a
build with something confusing.
