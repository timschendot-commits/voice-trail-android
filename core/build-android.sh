#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to the NDK directory (r28 or newer)}"
case "$(uname -s)" in
  Linux) host=linux-x86_64 ;;
  Darwin) host=darwin-x86_64 ;;
  *) echo "Use Linux, macOS, or the project's GitHub Actions Android build." >&2; exit 1 ;;
esac

bin="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$host/bin"
api="${ANDROID_MIN_API:-31}"
output="${VOICE_TRAIL_JNI_OUT:-../app/src/main/jniLibs}"
if [[ ! -x "$bin/llvm-readelf" ]]; then
  echo "Android NDK toolchain not found at $bin" >&2
  exit 1
fi
go mod download github.com/JopenChen/dsh-go
go mod verify

for abi in arm64-v8a x86_64; do
  case "$abi" in
    arm64-v8a) arch=arm64; target=aarch64-linux-android ;;
    x86_64) arch=amd64; target=x86_64-linux-android ;;
  esac
  directory="$output/$abi"
  mkdir -p "$directory"
  so="$directory/libvoicetrail.so"
  GOOS=android GOARCH="$arch" CGO_ENABLED=1 CC="$bin/$target$api-clang" \
    go build -mod=mod -trimpath -buildvcs=false -buildmode=c-shared \
    -ldflags='-s -w -extldflags=-Wl,-z,max-page-size=16384' -o "$so" ./cmd/androidjni
  symbols="$("$bin/llvm-readelf" --dyn-syms --wide "$so")"
  for name in start event dispatch close; do
    if ! grep -q "Java_io_github_voicetrail_NativeCore_${name}$" <<< "$symbols"; then
      echo "Missing JNI symbol $name in $so" >&2; exit 1
    fi
  done
  rm -f "$directory/libvoicetrail.h"
done
