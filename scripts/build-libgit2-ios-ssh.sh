#!/usr/bin/env bash
set -euo pipefail

# Rebuild libgit2.xcframework for GitSync.md with SSH transport enabled.
#
# The app's Swift code already supplies SSH credentials to libgit2 via
# git_credential_ssh_key_memory_new. The shipped xcframework must therefore be
# built with libssh2 and memory-credential support, otherwise SSH remotes fail
# at runtime with libgit2's generic -1 error.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK_DIR="${WORK_DIR:-"$ROOT_DIR/.build/libgit2-ios-ssh"}"
OUT_DIR="$ROOT_DIR/libgit2.xcframework"

LIBGIT2_VERSION="${LIBGIT2_VERSION:-1.9.2}"
LIBSSH2_VERSION="${LIBSSH2_VERSION:-1.11.1}"
MBEDTLS_VERSION="${MBEDTLS_VERSION:-3.6.2}"
IOS_DEPLOYMENT_TARGET="${IOS_DEPLOYMENT_TARGET:-16.0}"
JOBS="${JOBS:-$(sysctl -n hw.ncpu)}"

mkdir -p "$WORK_DIR/src" "$WORK_DIR/build" "$WORK_DIR/install" "$WORK_DIR/out"

log() { printf '\n==> %s\n' "$*"; }

fetch_and_extract() {
  local name="$1"
  local url="$2"
  local archive="$WORK_DIR/src/$name.archive"
  local dest="$WORK_DIR/src/$name"

  if [[ -d "$dest" ]]; then
    return
  fi

  log "Downloading $name"
  curl -L --fail -o "$archive" "$url"

  log "Extracting $name"
  mkdir -p "$dest.tmp"
  case "$url" in
    *.tar.bz2) tar -xjf "$archive" -C "$dest.tmp" --strip-components=1 ;;
    *.tar.gz|*.tgz) tar -xzf "$archive" -C "$dest.tmp" --strip-components=1 ;;
    *) echo "Unsupported archive: $url" >&2; exit 1 ;;
  esac
  mv "$dest.tmp" "$dest"
}

common_cmake_flags() {
  local sysroot="$1"
  printf '%s\n' \
    "-DCMAKE_SYSTEM_NAME=iOS" \
    "-DCMAKE_OSX_SYSROOT=$sysroot" \
    "-DCMAKE_OSX_ARCHITECTURES=arm64" \
    "-DCMAKE_OSX_DEPLOYMENT_TARGET=$IOS_DEPLOYMENT_TARGET" \
    "-DCMAKE_BUILD_TYPE=Release" \
    "-DCMAKE_FIND_ROOT_PATH_MODE_LIBRARY=BOTH" \
    "-DCMAKE_FIND_ROOT_PATH_MODE_INCLUDE=BOTH" \
    "-DCMAKE_POLICY_DEFAULT_CMP0075=NEW"
}

build_slice() {
  local name="$1"
  local sysroot="$2"
  local platform_variant="$3"

  local mbed_build="$WORK_DIR/build/mbedtls-$name"
  local mbed_install="$WORK_DIR/install/mbedtls-$name"
  local ssh2_build="$WORK_DIR/build/libssh2-$name"
  local ssh2_install="$WORK_DIR/install/libssh2-$name"
  local git2_build="$WORK_DIR/build/libgit2-$name"
  local git2_install="$WORK_DIR/install/libgit2-$name"
  local slice_out="$WORK_DIR/out/$name"

  log "Building mbedTLS for $name"
  rm -rf "$mbed_build" "$mbed_install"
  cmake -S "$WORK_DIR/src/mbedtls" -B "$mbed_build" \
    $(common_cmake_flags "$sysroot") \
    -DCMAKE_INSTALL_PREFIX="$mbed_install" \
    -DENABLE_TESTING=OFF \
    -DENABLE_PROGRAMS=OFF \
    -DMBEDTLS_FATAL_WARNINGS=OFF \
    -DUSE_SHARED_MBEDTLS_LIBRARY=OFF \
    -DUSE_STATIC_MBEDTLS_LIBRARY=ON
  cmake --build "$mbed_build" --target install -j"$JOBS"

  log "Building libssh2 for $name"
  rm -rf "$ssh2_build" "$ssh2_install"
  cmake -S "$WORK_DIR/src/libssh2" -B "$ssh2_build" \
    $(common_cmake_flags "$sysroot") \
    -DCMAKE_INSTALL_PREFIX="$ssh2_install" \
    -DBUILD_SHARED_LIBS=OFF \
    -DBUILD_TESTING=OFF \
    -DBUILD_EXAMPLES=OFF \
    -DCRYPTO_BACKEND=mbedTLS \
    -DMBEDTLS_INCLUDE_DIR="$mbed_install/include" \
    -DMBEDCRYPTO_LIBRARY="$mbed_install/lib/libmbedcrypto.a"
  cmake --build "$ssh2_build" --target install -j"$JOBS"

  log "Building libgit2 for $name"
  rm -rf "$git2_build" "$git2_install"
  cmake -S "$WORK_DIR/src/libgit2" -B "$git2_build" \
    $(common_cmake_flags "$sysroot") \
    -DCMAKE_REQUIRED_LIBRARIES="$ssh2_install/lib/libssh2.a;$mbed_install/lib/libmbedcrypto.a;$mbed_install/lib/libeverest.a;$mbed_install/lib/libp256m.a" \
    -DCMAKE_INSTALL_PREFIX="$git2_install" \
    -DBUILD_SHARED_LIBS=OFF \
    -DBUILD_TESTS=OFF \
    -DBUILD_CLI=OFF \
    -DBUILD_EXAMPLES=OFF \
    -DUSE_SSH=libssh2 \
    -DUSE_HTTPS=SecureTransport \
    -DLIBSSH2_INCLUDE_DIR="$ssh2_install/include" \
    -DLIBSSH2_LIBRARY="$ssh2_install/lib/libssh2.a" \
    -DPKG_CONFIG_EXECUTABLE=/usr/bin/false
  cmake --build "$git2_build" --target install -j"$JOBS"

  if ! grep -q 'HAVE_LIBSSH2_MEMORY_CREDENTIALS:INTERNAL=1' "$git2_build/CMakeCache.txt"; then
    echo "libgit2 did not detect libssh2 memory credentials for $name" >&2
    exit 1
  fi

  log "Combining static libraries for $name"
  rm -rf "$slice_out"
  mkdir -p "$slice_out/Headers"
  /usr/bin/libtool -static -o "$slice_out/libgit2.a" \
    "$git2_install/lib/libgit2.a" \
    "$ssh2_install/lib/libssh2.a" \
    "$mbed_install/lib/libmbedcrypto.a" \
    "$mbed_install/lib/libeverest.a" \
    "$mbed_install/lib/libp256m.a"
  ranlib "$slice_out/libgit2.a"

  cp -R "$git2_install/include/"* "$slice_out/Headers/"
  cat > "$slice_out/Headers/module.modulemap" <<'MODULEMAP'
module libgit2 {
    umbrella header "git2.h"
    link "git2"
    link "z"
    link "iconv"
    export *
}
MODULEMAP

  if strings "$slice_out/libgit2.a" | grep -qi 'without SSH support'; then
    echo "Built $name archive still contains the no-SSH transport stub" >&2
    exit 1
  fi
  nm -gU "$slice_out/libgit2.a" > "$slice_out/nm.txt"
  if ! grep -q '_git_smart_subtransport_ssh_libssh2' "$slice_out/nm.txt"; then
    echo "Built $name archive does not expose libgit2 libssh2 transport symbols" >&2
    exit 1
  fi
  if ! grep -q '_libssh2_userauth_publickey_frommemory' "$slice_out/nm.txt"; then
    echo "Built $name archive does not include libssh2 memory-key auth" >&2
    exit 1
  fi
  rm -f "$slice_out/nm.txt"

  printf '%s\n' "$platform_variant" > "$slice_out/platform-variant.txt"
}

fetch_and_extract "libgit2" "https://github.com/libgit2/libgit2/archive/refs/tags/v$LIBGIT2_VERSION.tar.gz"
fetch_and_extract "libssh2" "https://github.com/libssh2/libssh2/releases/download/libssh2-$LIBSSH2_VERSION/libssh2-$LIBSSH2_VERSION.tar.gz"
fetch_and_extract "mbedtls" "https://github.com/Mbed-TLS/mbedtls/releases/download/mbedtls-$MBEDTLS_VERSION/mbedtls-$MBEDTLS_VERSION.tar.bz2"

build_slice "ios-arm64" "iphoneos" ""
build_slice "ios-arm64-simulator" "iphonesimulator" "simulator"

log "Creating libgit2.xcframework"
rm -rf "$WORK_DIR/libgit2.xcframework" "$OUT_DIR"
xcodebuild -create-xcframework \
  -library "$WORK_DIR/out/ios-arm64/libgit2.a" -headers "$WORK_DIR/out/ios-arm64/Headers" \
  -library "$WORK_DIR/out/ios-arm64-simulator/libgit2.a" -headers "$WORK_DIR/out/ios-arm64-simulator/Headers" \
  -output "$WORK_DIR/libgit2.xcframework"
mv "$WORK_DIR/libgit2.xcframework" "$OUT_DIR"

log "Done: $OUT_DIR"
strings "$OUT_DIR/ios-arm64/libgit2.a" | grep -qi 'without SSH support' && {
  echo "Verification failed: no-SSH string present" >&2
  exit 1
}
nm -gU "$OUT_DIR/ios-arm64/libgit2.a" > "$WORK_DIR/final-nm.txt"
grep '_git_smart_subtransport_ssh_libssh2' "$WORK_DIR/final-nm.txt" >/dev/null
grep '_libssh2_userauth_publickey_frommemory' "$WORK_DIR/final-nm.txt" >/dev/null
rm -f "$WORK_DIR/final-nm.txt"
