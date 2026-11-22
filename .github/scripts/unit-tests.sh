#!/usr/bin/env bash
# Buddy's JVM tests (Robolectric), with the adb-key isolation test run for real: Buddy's own proot (third_party/proot,
# the source native/build.sh builds for the phone, built here for this machine) over a fixed Alpine minirootfs.
# Needs the third_party submodules, Java 21 and Android's SDK (as on GitHub's ubuntu runners); installs libtalloc-dev.
set -euo pipefail
cd "$(dirname "$0")/../.."
REPO=$PWD
W=${RUNNER_TEMP:-/tmp}/buddy-unit-tests
mkdir -p "$W"

ALPINE_URL=https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/x86_64/alpine-minirootfs-3.24.4-x86_64.tar.gz
ALPINE_SHA256=cdaa38cb8de3537967ee5b6c9b6cd00cc962da2b018e6eea48696bb274923012
PROOT_VERSION=$(sed -n 's/^PROOT_VERSION=\([^ ]*\).*/\1/p' native/build.sh)

[[ -f third_party/proot/src/GNUmakefile ]] || { echo "third_party/proot missing: git submodule update --init" >&2; exit 1; }

# Alpine, checked against its published SHA-256
curl -fsSL -o "$W/alpine.tar.gz" "$ALPINE_URL"
echo "$ALPINE_SHA256  $W/alpine.tar.gz" | sha256sum -c -

# Buddy's proot for this machine: as native/build.sh builds it, with the loader apart, minus Android's shared memory
sudo apt-get install -y -q libtalloc-dev >/dev/null
rm -rf "$W/proot"
cp -r third_party/proot "$W/proot"
rm -rf "$W/proot/.git"
CPPFLAGS="-DARG_MAX=131072 -DVERSION=\\\"$PROOT_VERSION\\\"" \
  make -s -C "$W/proot/src" proot loader/loader GIT=false HAS_LOADER_32BIT= PROOT_UNBUNDLE_LOADER=/nonexistent
"$W/proot/src/proot" --help 2>&1 | grep -q -- --kill-on-exit || { echo "not Buddy's proot (no --kill-on-exit)" >&2; exit 1; }

cd "$REPO"
./gradlew --no-daemon testFdroidDebugUnitTest -Pandroid.useAndroidX=true -Pbuddy.require=true \
  -Pbuddy.alpine="$W/alpine.tar.gz" -Pbuddy.proot="$W/proot/src/proot" -Pbuddy.proot.loader="$W/proot/src/loader/loader"
