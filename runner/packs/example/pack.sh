# shellcheck shell=bash
#
# packs/example/pack.sh — the smallest pack that loads, as a worked example.
#
# Copy this directory, change the five declarations, and the engine runs against
# your project instead. Nothing here mentions Log4j, which is the point: the
# engine does not know what it is testing.
#
#   BENCH_PACK=example oss run list
#
# It deliberately declares apps that do not exist on disk. `bench list` works --
# listing an axis reads no files -- while anything that builds fails with a
# missing module, which is the honest outcome for a pack with no content behind
# it, and is what makes this safe to ship as documentation.

PACK_NAME="example"
PACK_DESC="A worked example — copy this directory to point the engine at your own project"

PACK_CONFIGS_DIR="configs"
PACK_APPS_DIR="apps"

VERSIONS=(1.0.0 1.1.0)
DEFAULT_VERSION=1.1.0

APPS=(hello)
APPS_2X_ONLY=()

pack_module_path() {
  case "$1" in
    hello) echo "apps/hello" ;;
    *) return 1 ;;
  esac
}
