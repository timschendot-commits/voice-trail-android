package io.github.voicetrail;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/** Activity-scoped, main-thread voice I/O. Each request replaces the previous request. */
public final class VoiceIO {
    private final Activity activity;
    private final Locale locale;
    private final boolean zh;
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextToSpeech tts;
    private SpeechRecognizer recognizer;
    private ToneGenerator cues;
    private boolean initialized;
    private boolean offlineVoice;
    private boolean destroyed;
    private long generation;
    private float rate = 0.88f;
    private float volume = 1f;
    private Consumer<String> speechError;
    private String remaining = "";
    private String utteranceId;
    private Runnable afterSpeech;
    private Runnable timeout;
    private long utteranceNumber;

    public VoiceIO(Activity activity, boolean zh) {
        this.activity = activity;
        this.zh = zh;
        locale = zh ? Locale.SIMPLIFIED_CHINESE : Locale.US;
        speechError = msg -> Toast.makeText(activity, msg, Toast.LENGTH_LONG).show();
        try {
            tts = new TextToSpeech(activity.getApplicationContext(),
                    status -> main.post(() -> initializeTts(status)));
        } catch (RuntimeException unavailable) {
            initialized = true;
        }
    }

    public void setOnSpeechError(Consumer<String> listener) {
        if (listener != null) speechError = listener;
    }

    /** Includes TTS initialization and pending chunks; heartbeats must yield to speech continuations. */
    public boolean isSpeaking() {
        return utteranceId != null || !remaining.isEmpty();
    }

    private String tr(String chinese, String english) {
        return zh ? chinese : english;
    }

    private void initializeTts(int status) {
        if (destroyed) return;
        initialized = true;
        if (status == TextToSpeech.SUCCESS && tts != null) {
            try {
                Voice best = selectInstalledVoice(tts.getVoices());
                offlineVoice = best != null && tts.setVoice(best) == TextToSpeech.SUCCESS;
                tts.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
                tts.setSpeechRate(rate);
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String id) {}
                    @Override public void onDone(String id) {
                        main.post(() -> finishChunk(id));
                    }
                    @Override public void onError(String id) {
                        main.post(() -> {
                            if (id.equals(utteranceId)) failSpeech();
                        });
                    }
                });
            } catch (RuntimeException unavailable) {
                offlineVoice = false;
            }
        }
        if (!remaining.isEmpty()) speakNext();
    }

    private Voice selectInstalledVoice(Set<Voice> voices) {
        if (voices == null) return null;
        Voice best = null;
        int bestScore = Integer.MIN_VALUE;
        for (Voice voice : voices) {
            if (voice.isNetworkConnectionRequired()
                    || !locale.getLanguage().equals(voice.getLocale().getLanguage())
                    || voice.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)) continue;
            int score = voice.getQuality();
            if (locale.getCountry().equals(voice.getLocale().getCountry())) score += 1000;
            if (score > bestScore) {
                bestScore = score;
                best = voice;
            }
        }
        return best;
    }

    public void setRate(float value) {
        if (!Float.isFinite(value)) return;
        rate = Math.max(0.55f, Math.min(1.6f, value));
        if (initialized && offlineVoice && !destroyed) tts.setSpeechRate(rate);
    }

    /** Per-utterance gain in addition to the system accessibility volume. */
    public void setVolume(float value) {
        if (!Float.isFinite(value)) return;
        volume = Math.max(0.1f, Math.min(1f, value));
        if (cues != null) {
            cues.release();
            cues = null;
        }
    }

    /** Continue only after successful playback of every chunk. Cancellation discards the continuation. */
    public void say(String text, Runnable after) {
        if (destroyed) return;
        stop();
        if (text == null || text.trim().isEmpty()) return;
        remaining = text;
        afterSpeech = after;
        if (initialized) speakNext();
        else armTimeout(8000, this::failSpeech);
    }

    private void speakNext() {
        clearTimeout();
        if (!offlineVoice) {
            failSpeech();
            return;
        }
        // Bounded chunks let long pages respect the engine's maximum, even with emoji.
        int end = Math.min(remaining.length(), Math.min(800, TextToSpeech.getMaxSpeechInputLength()));
        if (end < remaining.length()) {
            for (int i = end - 1; i >= end / 2; i--) {
                char c = remaining.charAt(i);
                if (Character.isWhitespace(c) || "。！？.!?".indexOf(c) >= 0) {
                    end = i + 1;
                    break;
                }
            }
            if (Character.isHighSurrogate(remaining.charAt(end - 1))) end--;
        }
        String chunk = remaining.substring(0, end);
        remaining = remaining.substring(end);
        String id = Long.toString(++utteranceNumber);
        utteranceId = id;
        Bundle params = new Bundle();
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume);
        try {
            if (tts.speak(chunk, TextToSpeech.QUEUE_FLUSH, params, id) == TextToSpeech.SUCCESS) {
                armTimeout(10000L + (long) (chunk.length() * 650 / rate), this::failSpeech);
                return;
            }
        } catch (RuntimeException unavailable) {
            // Fail closed. A pre-action message must finish before the action can proceed.
        }
        failSpeech();
    }

    private void finishChunk(String id) {
        if (destroyed || !id.equals(utteranceId)) return;
        clearTimeout();
        utteranceId = null;
        if (!remaining.isEmpty()) {
            speakNext();
            return;
        }
        Runnable completed = afterSpeech;
        afterSpeech = null;
        if (completed != null) completed.run();
    }

    private void failSpeech() {
        stop();
        speechError.accept(tr("朗读已暂停。请在系统的文字转语音设置中安装中文离线语音，然后重试。",
                "Speech paused. Install an English offline voice in system text-to-speech settings, then retry."));
    }

    /** Single local-only dictation. Call from a foreground activity with microphone permission. */
    public void listen(Consumer<String> result, Consumer<String> error) {
        if (destroyed) return;
        stop();
        final long request = generation;
        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            error.accept(tr("请允许麦克风权限，或使用文字输入。", "Allow microphone access or use text input."));
            return;
        }
        try {
            if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(activity)) {
                error.accept(languageError());
                return;
            }
            recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(activity);
            recognizer.setRecognitionListener(new RecognitionListener() {
                private boolean ended;
                @Override public void onReadyForSpeech(Bundle params) {
                    if (current(request)) cue(ToneGenerator.TONE_PROP_BEEP);
                }
                @Override public void onBeginningOfSpeech() {}
                @Override public void onRmsChanged(float value) {}
                @Override public void onBufferReceived(byte[] data) {}
                @Override public void onEndOfSpeech() {
                    if (!current(request)) return;
                    ended = true;
                    cue(ToneGenerator.TONE_PROP_ACK);
                    armTimeout(7000, () -> recognitionFailed(request, SpeechRecognizer.ERROR_SPEECH_TIMEOUT, error));
                }
                @Override public void onError(int code) {
                    recognitionFailed(request, code, error);
                }
                @Override public void onResults(Bundle results) {
                    if (!current(request)) return;
                    ArrayList<String> words = results == null ? null
                            : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    float[] scores = results == null ? null
                            : results.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES);
                    if (words == null || words.isEmpty() || words.get(0) == null || words.get(0).trim().isEmpty()
                            || (scores != null && scores.length > 0 && scores[0] >= 0 && scores[0] < 0.45f)) {
                        recognitionFailed(request, SpeechRecognizer.ERROR_NO_MATCH, error);
                        return;
                    }
                    if (!ended) cue(ToneGenerator.TONE_PROP_ACK);
                    String phrase = words.get(0).trim();
                    stop();
                    result.accept(phrase);
                }
                @Override public void onPartialResults(Bundle partial) {}
                @Override public void onEvent(int event, Bundle params) {}
            });
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
            armTimeout(45000, () -> recognitionFailed(request, SpeechRecognizer.ERROR_SPEECH_TIMEOUT, error));
            recognizer.startListening(intent);
        } catch (RuntimeException unavailable) {
            if (current(request)) {
                stop();
                error.accept(languageError());
            }
        }
    }

    private boolean current(long request) {
        return !destroyed && generation == request;
    }

    private String languageError() {
        return tr("中文离线识别不可用。请在系统语音设置中安装中文离线包，或使用文字输入。",
                "English offline recognition is unavailable. Install an English offline pack in system speech settings or use text input.");
    }

    private void recognitionFailed(long request, int code, Consumer<String> error) {
        if (!current(request)) return;
        stop();
        String message;
        switch (code) {
            case SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED:
            case SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE:
                message = languageError();
                break;
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                message = tr("请允许麦克风权限，然后重试。", "Allow microphone access, then retry.");
                break;
            case SpeechRecognizer.ERROR_NO_MATCH:
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                message = tr("我没听清。请点说话，再用一句短话说一次。", "I didn't catch that. Activate Speak and try a short phrase again.");
                break;
            case SpeechRecognizer.ERROR_AUDIO:
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                message = tr("麦克风正忙。请结束其他录音后重试。", "The microphone is busy. End any other recording and retry.");
                break;
            default:
                message = tr("离线识别中断了。请重试，或使用文字输入。", "Offline recognition stopped. Retry or use text input.");
        }
        error.accept(message);
    }

    private void cue(int tone) {
        try {
            if (cues == null) cues = new ToneGenerator(AudioManager.STREAM_ACCESSIBILITY, Math.round(volume * 55));
            cues.startTone(tone, 90);
        } catch (RuntimeException unavailable) {
            // A device with no sound-effect channel can still provide speech recognition.
        }
    }

    private void clearTimeout() {
        if (timeout != null) main.removeCallbacks(timeout);
        timeout = null;
    }

    private void armTimeout(long milliseconds, Runnable action) {
        clearTimeout();
        timeout = action;
        main.postDelayed(action, milliseconds);
    }

    public void stop() {
        generation++;
        clearTimeout();
        utteranceId = null;
        remaining = "";
        afterSpeech = null;
        if (tts != null && initialized) tts.stop();
        if (recognizer != null) {
            SpeechRecognizer previous = recognizer;
            recognizer = null;
            previous.cancel();
            previous.destroy();
        }
    }

    public void destroy() {
        if (destroyed) return;
        destroyed = true;
        stop();
        if (tts != null) tts.shutdown();
        if (cues != null) cues.release();
        cues = null;
    }
}
