#!/usr/bin/env bash
# Deterministic localized App Store capture: 10 iPhone + 4 iPad images per locale.

set -euo pipefail

DEFAULT_LOCALES=(ar:ar-SA da:da de:de-DE en:en-US es:es-ES fi:fi fr:fr-FR he:he hi:hi hu:hu id:id it:it ja:ja ko:ko nb:no nl:nl-NL pl:pl pt-BR:pt-BR ru:ru sv:sv th:th tr:tr uk:uk vi:vi zh-Hans:zh-Hans zh-Hant:zh-Hant)
if [ -n "${MARKETING_LOCALE_PAIRS:-}" ]; then
    read -r -a LOCALES <<< "$MARKETING_LOCALE_PAIRS"
else
    LOCALES=("${DEFAULT_LOCALES[@]}")
fi
if [ -n "${MARKETING_FORM_FACTORS:-}" ]; then
    read -r -a FORM_FACTORS <<< "$MARKETING_FORM_FACTORS"
else
    FORM_FACTORS=(iphone ipad)
fi
BUNDLE_ID="bontecou.Sync-md"
SCHEME="Sync.md"
PROJECT="Sync.md.xcodeproj"
DERIVED="build/marketing-dd"
APP_PATH="$DERIVED/Build/Products/Debug-iphonesimulator/${SCHEME}.app"
OUT_ROOT="marketing"
TIMEOUT="${MARKETING_TIMEOUT:-180}"
RUNTIME_ID="com.apple.CoreSimulator.SimRuntime.iOS-26-5"
TEMP_DEVICE_IDS=()

cd "$(dirname "$0")/.."

cleanup_temp_devices() {
    local device_id
    for device_id in "${TEMP_DEVICE_IDS[@]}"; do
        xcrun simctl shutdown "$device_id" 2>/dev/null || true
        xcrun simctl delete "$device_id" 2>/dev/null || true
    done
}

trap cleanup_temp_devices EXIT INT TERM

create_capture_device() {
    local device_name="$1"
    local device_type="$2"
    local device_id
    device_id="$(xcrun simctl create "$device_name" "$device_type" "$RUNTIME_ID")"
    if [ -z "$device_id" ]; then
        echo "Could not create temporary simulator: $device_name" >&2
        exit 1
    fi
    TEMP_DEVICE_IDS+=("$device_id")
    CAPTURE_DEVICE_ID="$device_id"
}

capture_device() {
    local form_factor="$1"
    local device_name="$2"
    local device_type="$3"
    local expected_count="$4"
    local target_width="$5"
    local target_height="$6"
    local device_id
    create_capture_device "$device_name" "$device_type"
    device_id="$CAPTURE_DEVICE_ID"

    echo "==> Booting temporary $device_name ($form_factor)"
    xcrun simctl boot "$device_id"
    xcrun simctl bootstatus "$device_id" -b
    xcrun simctl install "$device_id" "$APP_PATH"

    for locale_pair in "${LOCALES[@]}"; do
        local runtime_locale="${locale_pair%%:*}"
        local asc_locale="${locale_pair#*:}"
        echo "==> Capturing $form_factor locale: $asc_locale (runtime $runtime_locale)"

        local sandbox locale_output sandbox_output locale_id sentinel waited count stdout_log stderr_log
        sandbox="$(xcrun simctl get_app_container "$device_id" "$BUNDLE_ID" data)"
        sandbox_output="$sandbox/Documents/marketing/$form_factor/$asc_locale"
        locale_output="$OUT_ROOT/$form_factor/$asc_locale"
        if [ -d "$sandbox_output" ]; then rm -rf "$sandbox_output"; fi
        if [ -d "$locale_output" ]; then rm -rf "$locale_output"; fi
        locale_id="${runtime_locale//-/_}"
        stdout_log="/tmp/gitsync-marketing-${form_factor}-${asc_locale}.stdout.log"
        stderr_log="/tmp/gitsync-marketing-${form_factor}-${asc_locale}.stderr.log"
        rm -f "$stdout_log" "$stderr_log"

        xcrun simctl launch \
            --terminate-running-process \
            --stdout="$stdout_log" \
            --stderr="$stderr_log" \
            "$device_id" "$BUNDLE_ID" \
            -MarketingCapture 1 \
            -MarketingLocale "$asc_locale" \
            -MarketingFormFactor "$form_factor" \
            -AppleLanguages "($runtime_locale)" \
            -AppleLocale "$locale_id" >/dev/null

        waited=0
        sentinel="$sandbox_output/_done"
        while [ ! -f "$sentinel" ]; do
            sleep 1
            waited=$((waited + 1))
            if [ "$waited" -gt "$TIMEOUT" ]; then
                echo "Timeout waiting for $sentinel after ${TIMEOUT}s" >&2
                if [ -d "$sandbox_output" ]; then
                    mkdir -p "$(dirname "$locale_output")"
                    cp -R "$sandbox_output" "$locale_output"
                    rm -f "$locale_output/_done"
                fi
                tail -80 "$stdout_log" >&2 || true
                tail -80 "$stderr_log" >&2 || true
                exit 1
            fi
        done

        mkdir -p "$(dirname "$locale_output")"
        cp -R "$sandbox_output" "$locale_output"
        rm -f "$locale_output/_done"
        while IFS= read -r screenshot; do
            sips -z "$target_height" "$target_width" "$screenshot" >/dev/null
        done < <(find "$locale_output" -maxdepth 1 -name '*.png' | sort)
        count="$(find "$locale_output" -maxdepth 1 -name '*.png' | wc -l | tr -d ' ')"
        if [ "$count" -ne "$expected_count" ]; then
            echo "$form_factor/$asc_locale produced $count PNGs; expected $expected_count" >&2
            exit 1
        fi
        echo "    -> $count PNGs"
    done

    xcrun simctl shutdown "$device_id" 2>/dev/null || true
}

echo "==> Building $SCHEME once for iOS Simulator"
xcodebuild \
    -project "$PROJECT" \
    -scheme "$SCHEME" \
    -configuration Debug \
    -destination "generic/platform=iOS Simulator" \
    -derivedDataPath "$DERIVED" \
    build \
    -quiet

if [ ! -d "$APP_PATH" ]; then
    echo "Build did not produce $APP_PATH" >&2
    exit 1
fi

mkdir -p "$OUT_ROOT/iphone" "$OUT_ROOT/ipad"
for form_factor in "${FORM_FACTORS[@]}"; do
    case "$form_factor" in
        iphone)
            capture_device \
                "iphone" \
                "GitSync Marketing iPhone" \
                "com.apple.CoreSimulator.SimDeviceType.iPhone-17-Pro-Max" \
                10 1320 2868
            ;;
        ipad)
            capture_device \
                "ipad" \
                "GitSync Marketing iPad" \
                "com.apple.CoreSimulator.SimDeviceType.iPad-Pro-13-inch-M5-16GB" \
                4 2048 2732
            ;;
        *)
            echo "Unsupported marketing form factor: $form_factor" >&2
            exit 1
            ;;
    esac
done

echo "Done. Localized captures are in $OUT_ROOT/{iphone,ipad}/<locale>/"
