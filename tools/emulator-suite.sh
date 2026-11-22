#!/usr/bin/env bash
set -euo pipefail
out=evidence
mkdir -p "$out"
adb wait-for-device
adb shell getprop ro.build.fingerprint | tr -d '\r' > "$out/android-fingerprint.txt"
adb shell settings get secure tts_default_synth > "$out/stock-default-tts.txt"
adb shell cmd package query-services --brief -a android.intent.action.TTS_SERVICE > "$out/stock-tts-services.txt"
adb shell cmd package query-services --brief -a android.speech.RecognitionService > "$out/stock-asr-services.txt"

failed=0
run_case() {
  local name=$1
  shift
  echo "::group::$name"
  if "$@"; then
    echo "$name: passed" | tee -a "$out/status.txt"
  else
    local result=$?
    echo "$name: exit $result" | tee -a "$out/status.txt"
    failed=1
  fi
  echo "::endgroup::"
}

run_case stock /usr/bin/python3 tools/emulator-smoke.py --out "$out/stock" --apk release/VoiceTrail-debug.apk
if bash tools/emulator-voice.sh 2>&1 | tee "$out/voice-setup.txt"; then
  adb shell cmd connectivity airplane-mode enable
  adb shell svc wifi disable
  adb shell svc data disable
  adb shell settings get global airplane_mode_on > "$out/airplane-mode.txt"
  run_case offline-ui /usr/bin/python3 tools/emulator-smoke.py --out "$out/offline" --require-camera
  run_case screen-read /usr/bin/python3 tools/emulator-read.py --out "$out/read"
  run_case visual-ocr /usr/bin/python3 tools/emulator-vision.py --out "$out/vision"
else
  echo 'Offline TTS setup failed' | tee -a "$out/status.txt"
  failed=1
fi
adb logcat -b events -d -s tts_speak_success:I tts_speak_failure:I 2>/dev/null | tail -60 > "$out/tts-events.txt"
adb logcat -b crash -d -t 200 > "$out/crashes.txt" 2>/dev/null || true
echo 'Recent Android speech-engine events'
tail -5 "$out/tts-events.txt"
if [[ "$failed" != 0 ]]; then
  echo 'Visible controls at failed checks'
  for snap in "$out"/*/*failure.xml "$out"/*/last-observed-app-ui.xml; do
    if [[ -f "$snap" ]]; then head -c 6000 "$snap"; echo; fi
  done
  tail -40 "$out/crashes.txt"
fi
{
  echo '## Published APK device results'
  echo
  cat "$out/status.txt"
  for report in "$out"/*/*.json; do
    [[ -f "$report" ]] || continue
    echo
    printf '`%s`\n' "$report"
    echo '~~~json'
    cat "$report"
    echo '~~~'
  done
} >> "$GITHUB_STEP_SUMMARY"
exit "$failed"
