#!/usr/bin/env bash
set -euo pipefail
out=evidence
mkdir -p "$out"
adb wait-for-device
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
adb shell settings put system screen_off_timeout 1800000
adb shell cmd statusbar collapse || true
adb shell input keyevent KEYCODE_HOME
adb shell getprop ro.build.fingerprint | tr -d '\r' > "$out/android-fingerprint.txt"
adb shell settings get secure tts_default_synth > "$out/stock-default-tts.txt"
adb shell cmd package query-services --brief -a android.intent.action.TTS_SERVICE > "$out/stock-tts-services.txt"
adb shell cmd package query-services --brief -a android.speech.RecognitionService > "$out/stock-asr-services.txt"

failed=0
diagnose() {
  local where=$1
  {
    adb shell dumpsys window | grep -E 'mCurrentFocus=|mFocusedApp=|showingAndNotOccluded=|isStatusBarKeyguard=' | head -12 || true
    adb shell dumpsys activity activities | grep -E 'topResumedActivity=|mResumedActivity:' | head -6 || true
    adb shell logcat -d -s ActivityTaskManager:I ActivityManager:I AndroidRuntime:E | grep -iE 'voicetrail|background activity|FATAL EXCEPTION' | tail -42 || true
    adb shell cmd package resolve-activity --brief --user 0 -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p io.github.voicetrail || true
    adb shell uiautomator dump /data/local/tmp/qa-visible.xml >/dev/null 2>&1 || true
    adb exec-out cat /data/local/tmp/qa-visible.xml | /usr/bin/python3 -c 'import sys,xml.etree.ElementTree as E; tree=E.parse(sys.stdin); print([(v.get("package"), (v.get("text") or v.get("content-desc") or "")[:140]) for v in tree.iter("node") if v.get("text") or v.get("content-desc")][:20])' || true
  } | tee "$out/$where-diagnostic.txt"
}
run_case() {
  local name=$1
  shift
  echo "::group::$name"
  if "$@"; then
    echo "$name: passed" | tee -a "$out/status.txt"
  else
    local result=$?
    echo "$name: exit $result" | tee -a "$out/status.txt"
    diagnose "$name"
    failed=1
  fi
  echo "::endgroup::"
}

suite=${QA_SUITE:-accessibility}
case "$suite" in
  accessibility) run_case stock /usr/bin/python3 tools/emulator-smoke.py --out "$out/stock" --apk release/VoiceTrail-debug.apk ;;
  reminder) adb install -r release/VoiceTrail-debug.apk ;;
  *) echo "Unknown device suite: $suite" >&2; exit 2 ;;
esac
if bash tools/emulator-voice.sh 2>&1 | tee "$out/voice-setup.txt"; then
  adb shell cmd connectivity airplane-mode enable
  adb shell svc wifi disable
  adb shell svc data disable
  adb shell settings get global airplane_mode_on > "$out/airplane-mode.txt"
  if [[ "$suite" == reminder ]]; then
    run_case reminder /usr/bin/python3 tools/emulator-reminder.py --out "$out/reminder"
  else
    run_case offline-ui /usr/bin/python3 tools/emulator-smoke.py --out "$out/offline" --require-camera --exercise-voice
    run_case screen-read /usr/bin/python3 tools/emulator-read.py --out "$out/read"
    run_case visual-ocr /usr/bin/python3 tools/emulator-vision.py --out "$out/vision"
  fi
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
