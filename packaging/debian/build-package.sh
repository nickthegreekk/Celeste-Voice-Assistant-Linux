#!/bin/sh
# build-package.sh — build the .deb from this tree, **on Debian**.
#
#   ./packaging/debian/build-package.sh [output-dir]     (the current directory by default)
#
# ## Why this script exists at all
#
# `dpkg-buildpackage` wants a *source tree*: a directory named `<package>-<version>` whose root holds
# `debian/`. This repository keeps its Debian files under `packaging/debian/` (PLAN.md §4's layout)
# and its build directory is the checkout itself, so the script stages a copy — no VCS metadata, no
# build output, no packaging output — puts `packaging/debian/` at `debian/` inside it, and builds
# there. The `.deb` is copied out and the staging directory is thrown away.
#
# ## Why it has to run on Debian
#
# The app image carries a jlink runtime and Skiko's native library, both linked against the *build
# host's* glibc — PLAN.md §12's risk row, which is why the `.deb` cannot be assembled on Arch
# however correct the file names would look. `packaging/debian/Containerfile` is the Debian 13
# environment this is meant to run in:
#
#   podman build -f packaging/debian/Containerfile -t celeste-linux-deb .
#   mkdir -p packaging/out
#   podman run --rm -v "$PWD:/src:ro" -v "$PWD/packaging/out:/out" celeste-linux-deb \
#     /src/packaging/debian/build-package.sh /out
set -eu

here=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repo=$(CDPATH= cd -- "$here/../.." && pwd)
out=$(CDPATH= cd -- "${1:-$(pwd)}" && pwd)

version=$(dpkg-parsechangelog -l "$here/changelog" -SVersion)
stage=$(mktemp -d)
trap 'rm -rf "$stage"' EXIT INT TERM
source_dir="$stage/celeste-linux-$version"
mkdir -p "$source_dir"

# `tar` rather than `cp -a`: it is in every environment this runs in, and its excludes are the point
# — a source package must not carry the build tree, and `.git` is not part of the source.
tar -C "$repo" \
    --exclude=./.git \
    --exclude=./.gradle \
    --exclude=./build \
    --exclude='./*/build' \
    --exclude=./packaging/out \
    --exclude='./packaging/arch/*.pkg.tar.*' \
    --exclude=./packaging/arch/pkg \
    --exclude=./packaging/arch/src \
    -cf - . | tar -C "$source_dir" -xf -

# `debian/` at the root of the staged tree, where dpkg expects it. The two files that are about
# *this* script rather than about the package ride along inside it: a Debian source package may keep
# helpers in `debian/`, and keeping them means the same tree rebuilds the same way.
cp -a "$here/." "$source_dir/debian/"

cd "$source_dir"
# `-b` binary only, `-us -uc` unsigned: this is a package for this machine, not an upload.
dpkg-buildpackage -b -us -uc

cp -v "$stage"/*.deb "$out/"
