#!/usr/bin/env bash
# Add a real, offline TTS engine to the disposable Android test device.
# Source and signed APK checksum: https://verification.f-droid.org/unsigned/com.reecedunn.espeak_22.apk.json
set -euo pipefail

serial=${ANDROID_SERIAL:-$(adb get-serialno)}
device=(adb -s "$serial")
qemu=$("${device[@]}" shell getprop ro.boot.qemu | tr -d '\r')
kernel_qemu=$("${device[@]}" shell getprop ro.kernel.qemu | tr -d '\r')
if [[ ! "$serial" =~ ^emulator-[0-9]+$ ]] || [[ "$qemu" != 1 && "$kernel_qemu" != 1 ]]; then
  echo "The voice fixture requires an Android emulator." >&2
  exit 2
fi

pkg=com.reecedunn.espeak
filename="${pkg}_22.apk"
sha=0a7822fec54d7f7ae759ffedc9afeaa77f809587b171fb2101998787821ec790
apk=$(mktemp --suffix=.apk)
trap 'rm -f "$apk"' EXIT
for repo in https://f-droid.org/repo https://ftp.fau.de/fdroid/repo https://mirror.cyberbits.eu/fdroid/repo; do
  if curl --fail --silent --show-error --location --proto '=https' --proto-redir '=https' \
      --connect-timeout 8 --max-time 55 "$repo/$filename" -o "$apk" &&
      printf '%s  %s\n' "$sha" "$apk" | sha256sum --check --status; then
    break
  fi
done
printf '%s  %s\n' "$sha" "$apk" | sha256sum --check --status || {
  echo "The pinned F-Droid voice APK could not be verified." >&2
  exit 1
}

"${device[@]}" install --no-streaming -r "$apk"
# 'DownloadVoiceData' is the upstream activity name. It extracts res/raw/espeakdata
# from inside the APK and finishes automatically when extraction has completed.
"${device[@]}" shell am start -W -n "$pkg/.DownloadVoiceData"
resumed=""
for ((n=0; n<45; ++n)); do
  resumed=$("${device[@]}" shell dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity' || true)
  if [[ -n "$resumed" && "$resumed" != *"$pkg/.DownloadVoiceData"* ]]; then break; fi
  sleep 1
done
if [[ -z "$resumed" || "$resumed" == *"$pkg/.DownloadVoiceData"* ]]; then
  echo "Timed out waiting for the voice data extraction activity to finish." >&2
  exit 1
fi

services=$("${device[@]}" shell cmd package query-services --brief -a android.intent.action.TTS_SERVICE -p "$pkg")
if [[ "$services" != *"$pkg"* ]]; then
  echo "Android did not expose the eSpeak TTS service." >&2
  exit 1
fi
"${device[@]}" shell settings put secure tts_default_synth "$pkg"
[[ $("${device[@]}" shell settings get secure tts_default_synth | tr -d '\r') == "$pkg" ]]
# New VoiceIO instances connect to the selected engine.
"${device[@]}" shell am force-stop io.github.voicetrail
echo "Offline voice fixture: eSpeak NG 1.52.0 (22), APK SHA-256 $sha, $serial."
echo "Android TTS service: $services"
