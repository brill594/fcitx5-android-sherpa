/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2025 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.voice;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;

import androidx.annotation.NonNull;

import com.k2fsa.sherpa.onnx.SherpaOnnx;

import java.io.File;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import timber.log.Timber;

/**
 * Minimal voice input manager that streams microphone audio into SherpaOnnx and reports
 * recognized text back to the caller.
 */
public final class VoiceInputManager {

    public interface ResultCallback {
        void onFinalResult(@NonNull String text);

        default void onPartialResult(@NonNull String text) {
        }

        default void onError(@NonNull Throwable error) {
        }
    }

    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_ENCODING = AudioFormat.ENCODING_PCM_16BIT;
    private static final int FEATURE_DIM = 80;

    private final Context appContext;
    private final File modelDir;
    private final ResultCallback callback;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean listening = new AtomicBoolean(false);

    private SherpaOnnx sherpaOnnx;
    private AudioRecord audioRecord;
    private Thread audioThread;
    private volatile String lastPartial = "";

    public VoiceInputManager(
            @NonNull Context context,
            @NonNull File modelDir,
            @NonNull ResultCallback callback
    ) {
        this.appContext = context.getApplicationContext();
        this.modelDir = Objects.requireNonNull(modelDir, "modelDir == null");
        this.callback = Objects.requireNonNull(callback, "callback == null");
    }

    public boolean isListening() {
        return listening.get();
    }

    public synchronized void startListening() {
        if (listening.get()) {
            Timber.d("Voice input is already running");
            return;
        }

        try {
            ensureRecognizer();
        } catch (RuntimeException e) {
            dispatchError(e);
            return;
        }

        int minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_ENCODING);
        if (minBuffer <= 0) {
            dispatchError(new IllegalStateException("Invalid buffer size for AudioRecord: " + minBuffer));
            return;
        }

        int bufferSizeInBytes = Math.max(minBuffer, SAMPLE_RATE / 10 * 2);
        audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_ENCODING,
                bufferSizeInBytes
        );

        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            dispatchError(new IllegalStateException("AudioRecord failed to initialize"));
            releaseAudioRecord();
            return;
        }

        listening.set(true);
        audioThread = new Thread(() -> recordLoop(bufferSizeInBytes), "VoiceInputRecorder");
        audioThread.start();
    }

    public synchronized void stopListening() {
        listening.set(false);
        if (audioThread != null) {
            try {
                audioThread.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            audioThread = null;
        }
    }

    public synchronized void release() {
        stopListening();
        releaseAudioRecord();
        if (sherpaOnnx instanceof AutoCloseable) {
            try {
                ((AutoCloseable) sherpaOnnx).close();
            } catch (Exception e) {
                Timber.w(e, "Failed to close SherpaOnnx");
            }
        }
        sherpaOnnx = null;
    }

    private void ensureRecognizer() {
        if (sherpaOnnx != null) {
            return;
        }
        if (!modelDir.exists()) {
            throw new IllegalStateException("Model directory missing: " + modelDir.getAbsolutePath());
        }

        SherpaOnnx.OfflineTransducerModelConfig transducer = new SherpaOnnx.OfflineTransducerModelConfig();
        transducer.encoder = new File(modelDir, "encoder.onnx").getAbsolutePath();
        transducer.decoder = new File(modelDir, "decoder.onnx").getAbsolutePath();
        transducer.joiner = new File(modelDir, "joiner.onnx").getAbsolutePath();

        SherpaOnnx.OfflineModelConfig modelConfig = new SherpaOnnx.OfflineModelConfig();
        modelConfig.transducer = transducer;
        modelConfig.tokens = new File(modelDir, "tokens.txt").getAbsolutePath();
        modelConfig.numThreads = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
        modelConfig.debug = false;

        SherpaOnnx.FeatureConfig featureConfig = new SherpaOnnx.FeatureConfig();
        featureConfig.sampleRate = SAMPLE_RATE;
        featureConfig.featureDim = FEATURE_DIM;

        SherpaOnnx.OfflineRecognizerConfig recognizerConfig = new SherpaOnnx.OfflineRecognizerConfig();
        recognizerConfig.modelConfig = modelConfig;
        recognizerConfig.featConfig = featureConfig;
        recognizerConfig.decodingMethod = "greedy_search";

        sherpaOnnx = new SherpaOnnx(appContext.getAssets(), recognizerConfig);
    }

    private void recordLoop(int bufferSizeInBytes) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        short[] pcmBuffer = new short[bufferSizeInBytes / 2];
        float[] floatBuffer = new float[pcmBuffer.length];

        try {
            audioRecord.startRecording();
        } catch (IllegalStateException e) {
            dispatchError(e);
            listening.set(false);
            releaseAudioRecord();
            return;
        }

        while (listening.get()) {
            int read = audioRecord.read(pcmBuffer, 0, pcmBuffer.length);
            if (read <= 0) {
                continue;
            }

            for (int i = 0; i < read; i++) {
                floatBuffer[i] = pcmBuffer[i] / 32768f;
            }
            Arrays.fill(floatBuffer, read, floatBuffer.length, 0f);

            try {
                sherpaOnnx.acceptWaveform(floatBuffer);
                String partial = sherpaOnnx.getResult();
                if (partial != null && !partial.equals(lastPartial)) {
                    lastPartial = partial;
                    dispatchPartial(partial);
                }
            } catch (Exception e) {
                dispatchError(e);
                listening.set(false);
            }
        }

        stopRecorder();
        releaseAudioRecord();
        dispatchFinalResult();
    }

    private void stopRecorder() {
        if (audioRecord == null) {
            return;
        }
        if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
            try {
                audioRecord.stop();
            } catch (IllegalStateException e) {
                Timber.w(e, "Failed to stop AudioRecord cleanly");
            }
        }
    }

    private void releaseAudioRecord() {
        if (audioRecord != null) {
            audioRecord.release();
            audioRecord = null;
        }
    }

    private void dispatchFinalResult() {
        try {
            if (sherpaOnnx == null) {
                return;
            }
            String text = sherpaOnnx.getResult();
            lastPartial = "";
            if (text != null && !text.isEmpty()) {
                dispatchFinal(text);
            }
            try {
                sherpaOnnx.getClass().getMethod("reset").invoke(sherpaOnnx);
            } catch (Exception ignored) {
                // reset is optional on some builds
            }
        } catch (Exception e) {
            dispatchError(e);
        }
    }

    private void dispatchFinal(@NonNull String text) {
        mainHandler.post(() -> callback.onFinalResult(text));
    }

    private void dispatchPartial(@NonNull String text) {
        mainHandler.post(() -> callback.onPartialResult(text));
    }

    private void dispatchError(@NonNull Throwable throwable) {
        Timber.w(throwable, "VoiceInputManager error");
        mainHandler.post(() -> callback.onError(throwable));
    }
}
