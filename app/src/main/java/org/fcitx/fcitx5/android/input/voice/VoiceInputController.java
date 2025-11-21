/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2025 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.voice;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.k2fsa.sherpa.onnx.SherpaOnnx;

import java.io.File;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import timber.log.Timber;

/**
 * Manual voice input controller: performs permission checks, prepares the model,
 * initializes the streaming recognizer, and streams microphone PCM into it.
 */
public final class VoiceInputController {

    public interface Callback {
        void onPartialResult(@NonNull String text);

        void onFinalResult(@NonNull String text);

        default void onPermissionDenied() {
        }

        default void onError(@NonNull Throwable throwable) {
        }
    }

    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_ENCODING = AudioFormat.ENCODING_PCM_16BIT;
    private static final int FEATURE_DIM = 80;

    private final Context appContext;
    private final Callback callback;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private SherpaOnnx recognizer;
    private AudioRecord audioRecord;
    private Thread audioThread;
    private final AtomicBoolean listening = new AtomicBoolean(false);
    private volatile String lastPartial = "";

    public VoiceInputController(@NonNull Context context, @NonNull Callback callback) {
        this.appContext = Objects.requireNonNull(context.getApplicationContext(), "context == null");
        this.callback = Objects.requireNonNull(callback, "callback == null");
    }

    /**
     * @return true if start initiated (permission OK, model copy queued), false otherwise.
     */
    @MainThread
    public boolean startVoiceInputIfAvailable() {
        if (listening.get()) return true;
        if (!hasRecordAudioPermission()) {
            callback.onPermissionDenied();
            return false;
        }

        SherpaModelManager.prepareModel(appContext, new SherpaModelManager.Callback() {
            @Override
            public void onModelReady(@NonNull File destinationDir) {
                worker.execute(() -> {
                    try {
                        ensureRecognizer(destinationDir);
                        startRecorder();
                    } catch (Exception e) {
                        dispatchError(e);
                    }
                });
            }

            @Override
            public void onError(@NonNull Exception error) {
                dispatchError(error);
            }
        });
        return true;
    }

    @MainThread
    public void stopVoiceInput() {
        listening.set(false);
        if (audioThread != null) {
            try {
                audioThread.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            audioThread = null;
        }
        if (recognizer != null) {
            try {
                String finalText = recognizer.getResult();
                if (finalText != null && !finalText.isEmpty()) {
                    dispatchFinal(finalText);
                }
                recognizer.reset();
            } catch (Exception e) {
                dispatchError(e);
            }
        }
    }

    private boolean hasRecordAudioPermission() {
        return ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED;
    }

    private void ensureRecognizer(@NonNull File modelDir) {
        if (recognizer != null) return;
        if (!modelDir.exists()) {
            throw new IllegalStateException("Model directory missing: " + modelDir.getAbsolutePath());
        }
        File encoder = new File(modelDir, "encoder-epoch-99-avg-1.int8.onnx");
        File decoder = new File(modelDir, "decoder-epoch-99-avg-1.int8.onnx");
        File joiner = new File(modelDir, "joiner-epoch-99-avg-1.int8.onnx");
        File tokens = new File(modelDir, "tokens.txt");
        if (!encoder.exists() || !decoder.exists() || !joiner.exists() || !tokens.exists()) {
            throw new IllegalStateException("Model files not found under " + modelDir.getAbsolutePath());
        }

        SherpaOnnx.OnlineTransducerModelConfig transducer = new SherpaOnnx.OnlineTransducerModelConfig();
        transducer.encoder = encoder.getAbsolutePath();
        transducer.decoder = decoder.getAbsolutePath();
        transducer.joiner = joiner.getAbsolutePath();

        SherpaOnnx.OnlineModelConfig modelConfig = new SherpaOnnx.OnlineModelConfig();
        modelConfig.transducer = transducer;
        modelConfig.tokens = tokens.getAbsolutePath();
        modelConfig.numThreads = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
        modelConfig.debug = false;

        SherpaOnnx.FeatureConfig featureConfig = new SherpaOnnx.FeatureConfig();
        featureConfig.sampleRate = SAMPLE_RATE;
        featureConfig.featureDim = FEATURE_DIM;

        SherpaOnnx.OnlineRecognizerConfig recognizerConfig = new SherpaOnnx.OnlineRecognizerConfig();
        recognizerConfig.modelConfig = modelConfig;
        recognizerConfig.featConfig = featureConfig;
        recognizerConfig.decodingMethod = "greedy_search";
        recognizerConfig.enableEndpoint = true;

        recognizer = new SherpaOnnx(appContext.getAssets(), recognizerConfig);
    }

    private void startRecorder() {
        int minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_ENCODING);
        if (minBuffer <= 0) {
            throw new IllegalStateException("Invalid buffer size: " + minBuffer);
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
            releaseAudioRecord();
            throw new IllegalStateException("AudioRecord failed to initialize");
        }

        listening.set(true);
        audioThread = new Thread(() -> recordLoop(bufferSizeInBytes), "SherpaVoiceInput");
        audioThread.start();
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
            if (read <= 0) continue;

            for (int i = 0; i < read; i++) {
                floatBuffer[i] = pcmBuffer[i] / 32768f;
            }
            Arrays.fill(floatBuffer, read, floatBuffer.length, 0f);

            try {
                recognizer.acceptWaveform(floatBuffer);
                String partial = recognizer.getResult();
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
        if (audioRecord == null) return;
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
            if (recognizer == null) return;
            String text = recognizer.getResult();
            lastPartial = "";
            if (text != null && !text.isEmpty()) {
                dispatchFinal(text);
            }
            try {
                recognizer.reset();
            } catch (Exception ignored) {
                // reset is optional on some builds
            }
        } catch (Exception e) {
            dispatchError(e);
        }
    }

    private void dispatchPartial(@NonNull String text) {
        mainHandler.post(() -> callback.onPartialResult(text));
    }

    private void dispatchFinal(@NonNull String text) {
        mainHandler.post(() -> callback.onFinalResult(text));
    }

    private void dispatchError(@NonNull Throwable throwable) {
        Timber.w(throwable, "Voice input error");
        mainHandler.post(() -> callback.onError(throwable));
    }

}
