# The engine

Walks a version × configuration × application matrix, forks a real JVM per cell,
and reports what happened. It knows how to *run* things; it knows nothing about
what it is running.

What it is running is a **pack** — a directory somebody else owns, holding a
`pack.sh` and the applications and configurations that file names.

```bash
oss run --pack ~/apache/log4j2-workout list --apps
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

A pack declares these. Everything the engine reads, nothing it writes back:

| | |
|---|---|
| `PACK_NAME`, `PACK_DESC` | what this is |
| `VERSIONS`, `DEFAULT_VERSION` | the version axis |
| `APPS`, `pack_module_path` | the application axis, and where each one lives |
| `PACK_APPS_DIR`, `PACK_CONFIGS_DIR` | where to look, relative to the pack |
| `pack_build_flags`, `pack_gradle_version_flag` | how a version reaches the build |
| `pack_config_args` | **how an application is told where its configuration is** |
| `pack_main_class_for` | how to start one |
| `pack_jvm_args`, `pack_always_jvm_args` | flags an application or the project needs |
| `pack_skip_reason`, `pack_min_java_for`, `pack_min_version_for` | which cells are impossible, and why |
| `pack_source_clone`, `pack_modules`, `pack_modules_on_classpath` | for `coverage` |

`pack_config_args` is the one to get right. It is the only one that punishes a
guess *silently*: pass Log4j 2's property name to Log4j 3 and nothing errors —
the framework falls back to its default configuration and logs happily, and an
entire column passes while testing nothing at all.

`packs/example/pack.sh` is a working pack of about thirty lines. Copy it.

## POSIX only

This is bash, and it forks Maven, Gradle and JVMs. It runs on macOS and Linux.
On Windows use WSL — `oss run` says so rather than failing halfway through a
build with something confusing.
