#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(CDPATH='' cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck disable=SC1091
source "$SCRIPT_DIR/_common.sh"

usage() {
    cat <<'EOF'
Usage: scripts/background-sync/audit-release-artifact.sh --output DIR [options]

Builds an unsigned Release app for the generic iOS Simulator destination, then
audits the bundle. This matches CI's privacy/.storekit resource gate and adds
exact Background Sync identifiers/modes, executable/config, privacy semantics,
and best-effort simulator code-sign/entitlement inspection.

Options:
  --output DIR          Operator-chosen evidence root (required).
  --app PATH            Audit an existing .app instead of building.
  --derived-data PATH   Build output root. Defaults to
                        BACKGROUND_SYNC_DERIVED_DATA or this run directory.
  --project PATH        Default: Sync.md.xcodeproj (repo-relative).
  --scheme NAME         Default: Sync.md.
  -h, --help            Show this help.

When LOOP_DIR is set, the xcodebuild invocation is run through the fleet thermal
guard. Exit 75 is propagated unchanged. Simulator output is unsigned/ad-hoc
configuration evidence only; it cannot prove device provisioning, production
APNs entitlements, installability on hardware, or iOS scheduling cadence.
EOF
}

OUTPUT_ROOT=""
APP_INPUT=""
DERIVED_DATA="${BACKGROUND_SYNC_DERIVED_DATA:-}"
PROJECT="Sync.md.xcodeproj"
SCHEME="Sync.md"
ORIGINAL_ARGS=("$@")
while [[ $# -gt 0 ]]; do
    case "$1" in
        --output)
            [[ $# -ge 2 ]] || bs_die '--output requires a directory'
            OUTPUT_ROOT="$2"
            shift 2
            ;;
        --app)
            [[ $# -ge 2 ]] || bs_die '--app requires a path'
            APP_INPUT="$2"
            shift 2
            ;;
        --derived-data)
            [[ $# -ge 2 ]] || bs_die '--derived-data requires a path'
            DERIVED_DATA="$2"
            shift 2
            ;;
        --project)
            [[ $# -ge 2 ]] || bs_die '--project requires a path'
            PROJECT="$2"
            shift 2
            ;;
        --scheme)
            [[ $# -ge 2 ]] || bs_die '--scheme requires a value'
            SCHEME="$2"
            shift 2
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            bs_die "unknown argument: $1"
            ;;
    esac
done
[[ -n "$OUTPUT_ROOT" ]] || { usage >&2; bs_die '--output is required'; }
[[ "$SCHEME" != *$'\n'* && -n "$SCHEME" ]] || bs_die 'invalid scheme'

bs_assert_darwin
bs_assert_repo_root
bs_require_command python3
bs_require_command plutil
bs_require_command find
bs_require_command codesign
bs_begin_receipt "$OUTPUT_ROOT" 'release-artifact' "$0" "${ORIGINAL_ARGS[@]}"
finish() {
    local exit_status=$?
    trap - EXIT
    bs_finish_receipt "$exit_status"
    printf 'Evidence: %s\n' "$BS_RUN_DIR" >&2
    exit "$exit_status"
}
trap finish EXIT

if [[ "$PROJECT" = /* ]]; then
    PROJECT_PATH="$PROJECT"
else
    PROJECT_PATH="$BS_REPO_ROOT/$PROJECT"
fi
[[ -f "$PROJECT_PATH/project.pbxproj" || -f "$PROJECT_PATH" ]] || bs_die "Xcode project not found: $PROJECT_PATH"

if [[ -n "$APP_INPUT" ]]; then
    [[ -d "$APP_INPUT" ]] || bs_die "app bundle not found: $APP_INPUT"
    APP_PATH="$(CDPATH='' cd -- "$APP_INPUT" && pwd -P)"
    bs_receipt_note 'build=not_run_existing_artifact'
else
    if [[ -z "$DERIVED_DATA" ]]; then
        DERIVED_DATA="$BS_RUN_DIR/DerivedData"
    fi
    [[ "$DERIVED_DATA" != *$'\n'* && -n "$DERIVED_DATA" ]] || bs_die 'invalid DerivedData path'
    if [[ -e "$DERIVED_DATA" && -L "$DERIVED_DATA" ]]; then
        bs_die "refusing symlink DerivedData root: $DERIVED_DATA"
    fi
    mkdir -p -- "$DERIVED_DATA"
    DERIVED_DATA="$(CDPATH='' cd -- "$DERIVED_DATA" && pwd -P)"
    BUILD_LOG="$BS_RUN_DIR/xcodebuild-release.log"
    set +e
    bs_run_xcodebuild build \
        -project "$PROJECT_PATH" \
        -scheme "$SCHEME" \
        -configuration Release \
        -destination 'generic/platform=iOS Simulator' \
        -derivedDataPath "$DERIVED_DATA" \
        -disableAutomaticPackageResolution \
        -onlyUsePackageVersionsFromResolvedFile \
        CODE_SIGNING_ALLOWED=NO \
        >"$BUILD_LOG" 2>&1
    build_status=$?
    set -e
    bs_receipt_note "xcodebuild_exit_status=$build_status"
    if [[ "$build_status" -eq 75 ]]; then
        exit 75
    fi
    [[ "$build_status" -eq 0 ]] || bs_die "Release simulator build failed; inspect $BUILD_LOG"

    PRODUCT_DIR="$DERIVED_DATA/Build/Products/Release-iphonesimulator"
    APP_PATH="$PRODUCT_DIR/Sync.md.app"
    [[ -d "$APP_PATH" ]] || bs_die "expected Release app is missing: $APP_PATH"
    bs_receipt_note 'build=release_ios_simulator_unsigned'
    bs_receipt_note "derived_data=$DERIVED_DATA"
fi

[[ -f "$APP_PATH/Info.plist" ]] || bs_die 'built app has no root Info.plist'
plutil -lint "$APP_PATH/Info.plist" >"$BS_RUN_DIR/info-plist-lint.txt" 2>&1
[[ -f "$APP_PATH/PrivacyInfo.xcprivacy" ]] || bs_die 'built app has no root PrivacyInfo.xcprivacy'
plutil -lint "$APP_PATH/PrivacyInfo.xcprivacy" >"$BS_RUN_DIR/privacy-manifest-lint.txt" 2>&1
plutil -convert xml1 -o "$BS_RUN_DIR/app-info.plist.xml" "$APP_PATH/Info.plist"
plutil -convert xml1 -o "$BS_RUN_DIR/privacy-manifest.plist.xml" "$APP_PATH/PrivacyInfo.xcprivacy"
plutil -convert xml1 -o "$BS_RUN_DIR/source-entitlements.plist.xml" "$BS_REPO_ROOT/Sync.md/Sync_md.entitlements"

CODESIGN_STDOUT="$BS_RUN_DIR/simulator-codesign-entitlements.raw"
CODESIGN_STDERR="$BS_RUN_DIR/simulator-codesign-diagnostics.txt"
set +e
codesign -d --entitlements :- "$APP_PATH" >"$CODESIGN_STDOUT" 2>"$CODESIGN_STDERR"
codesign_status=$?
set -e
bs_receipt_note "simulator_codesign_inspection_status=$codesign_status"
if [[ "$codesign_status" -eq 0 && -s "$CODESIGN_STDOUT" ]]; then
    if plutil -lint "$CODESIGN_STDOUT" >>"$CODESIGN_STDERR" 2>&1; then
        plutil -convert xml1 -o "$BS_RUN_DIR/simulator-codesign-entitlements.plist.xml" "$CODESIGN_STDOUT"
    fi
fi
rm -f -- "$CODESIGN_STDOUT"

semantic_status=0
python3 - "$BS_REPO_ROOT" "$APP_PATH" "$codesign_status" "$BS_RUN_DIR/artifact-audit.json" "$BS_RUN_DIR/report.txt" <<'PY' || semantic_status=$?
import json
import os
import plistlib
import sys
from pathlib import Path

root = Path(sys.argv[1])
app = Path(sys.argv[2])
codesign_status = int(sys.argv[3])
json_path = Path(sys.argv[4])
report_path = Path(sys.argv[5])
refresh = "com.bontecou.Sync-md.background-refresh"
processing = "com.bontecou.Sync-md.background-sync"
checks = []


def check(name, condition, detail):
    checks.append({"name": name, "pass": bool(condition), "detail": detail})


def load(path):
    with path.open("rb") as handle:
        return plistlib.load(handle)


info = load(app / "Info.plist")
privacy = load(app / "PrivacyInfo.xcprivacy")
source_privacy = load(root / "Sync.md/PrivacyInfo.xcprivacy")
source_entitlements = load(root / "Sync.md/Sync_md.entitlements")
project = (root / "Sync.md.xcodeproj/project.pbxproj").read_text(encoding="utf-8")
storekit_files = sorted(str(path.relative_to(app)) for path in app.rglob("*.storekit"))
executable_name = info.get("CFBundleExecutable")
executable = app / executable_name if isinstance(executable_name, str) else None
profile = app / "embedded.mobileprovision"

check("Release .app directory exists", app.is_dir(), str(app))
check(
    "Built Info.plist has exact permitted identifiers",
    info.get("BGTaskSchedulerPermittedIdentifiers") == [refresh, processing],
    repr(info.get("BGTaskSchedulerPermittedIdentifiers")),
)
check(
    "Built Info.plist has fetch + processing modes",
    info.get("UIBackgroundModes") == ["fetch", "processing"],
    repr(info.get("UIBackgroundModes")),
)
check(
    "Built platform is iOS Simulator",
    info.get("DTPlatformName") == "iphonesimulator",
    repr(info.get("DTPlatformName")),
)
check(
    "Bundle identifier is the app target",
    info.get("CFBundleIdentifier") == "bontecou.Sync-md",
    repr(info.get("CFBundleIdentifier")),
)
check(
    "Bundle executable exists",
    executable is not None and executable.is_file(),
    executable_name if executable_name else "CFBundleExecutable missing",
)
check("Root privacy manifest exists and parses", isinstance(privacy, dict), "PrivacyInfo.xcprivacy")
check(
    "Bundled privacy manifest matches source semantics",
    privacy == source_privacy,
    "plist object equality (format-independent)",
)
check(
    "Bundled privacy manifest declares no tracking",
    privacy.get("NSPrivacyTracking") is False and privacy.get("NSPrivacyTrackingDomains") == [],
    "NSPrivacyTracking=false; empty tracking domains",
)
check("No StoreKit configuration ships", not storekit_files, repr(storekit_files))
check(
    "Source entitlement inventory is separate Push Sync APNs only",
    source_entitlements == {"aps-environment": "development"},
    repr(source_entitlements),
)
check(
    "Project assigns the source entitlements file to app configurations",
    project.count('\"CODE_SIGN_ENTITLEMENTS\" = \"Sync.md/Sync_md.entitlements\";')
    + project.count("CODE_SIGN_ENTITLEMENTS = Sync.md/Sync_md.entitlements;") >= 2,
    "project configuration only; signed archive remains a separate gate",
)

passed = all(item["pass"] for item in checks)
payload = {
    "schema": "background-sync-release-artifact-audit-v1",
    "passed": passed,
    "artifact": str(app),
    "bundle_identifier": info.get("CFBundleIdentifier"),
    "platform": info.get("DTPlatformName"),
    "codesign_entitlement_inspection_exit": codesign_status,
    "embedded_mobileprovision_present": profile.is_file(),
    "checks": checks,
    "simulator_evidence": [
        "Release configuration built for iphonesimulator",
        "built Info.plist identifiers and modes",
        "privacy manifest presence/lint/source-semantic match",
        "absence of .storekit resources",
        "best-effort simulator signature/entitlement diagnostics",
    ],
    "not_proved": [
        "signed physical-device or App Store archive entitlements",
        "production provisioning profile or aps-environment rewrite",
        "BGTaskScheduler registration/submission/OS grant",
        "unforced execution cadence, completion, or expiration behavior",
    ],
}
json_path.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
with report_path.open("w", encoding="utf-8") as report:
    report.write("Background Sync Release simulator artifact audit\n")
    report.write("================================================\n\n")
    for item in checks:
        report.write(f"[{'PASS' if item['pass'] else 'FAIL'}] {item['name']}\n")
        report.write(f"       {item['detail']}\n")
    report.write("\nSIMULATOR EVIDENCE\n")
    for item in payload["simulator_evidence"]:
        report.write(f"- {item}\n")
    report.write("\nSIGNED PHYSICAL-DEVICE / ARCHIVE CHECKS NOT PROVED\n")
    for item in payload["not_proved"]:
        report.write(f"- {item}\n")

raise SystemExit(0 if passed else 1)
PY
bs_receipt_note "semantic_status=$semantic_status"
bs_receipt_note "app_path=$APP_PATH"
bs_receipt_note 'evidence_class=unsigned_or_adhoc_simulator_only'
[[ "$semantic_status" -eq 0 ]] || exit 1
