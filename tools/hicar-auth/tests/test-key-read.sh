#!/bin/sh
set -eu

tests=$(CDPATH= cd "$(dirname "$0")" && pwd)
source_root="$tests/../../../vendor/hicar-auth/sources"
auth="$source_root/deviceauth/frameworks/deviceauth_lite/source"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
case "$(uname -s)" in
    Darwin) discard_unused=-Wl,-dead_strip ;;
    *) discard_unused=-Wl,--gc-sections ;;
esac

# Link the production adapter with a single simulated keystore read failure.
"${CC:-cc}" -std=c11 -D_CUT_LOG_ -ffunction-sections -fdata-sections "$discard_unused" \
    -I"$source_root/huks/interfaces/innerkits/huks_lite" \
    -I"$source_root/deviceauth/interfaces/innerkits/deviceauth_lite" \
    -I"$source_root/securec/include" -I"$auth/base" -I"$auth/log" -I"$auth/json" -I"$auth/huks_adapter" \
    "$tests/key_read_status.c" "$auth/huks_adapter/huks_adapter.c" \
    "$source_root/securec/src/memset_s.c" "$source_root/securec/src/memcpy_s.c" -o "$work/test"
"$work/test"
