/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2025 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.voice;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Copies Sherpa-ONNX model assets into the app's private storage so the native engine
 * can access them with absolute paths.
 */
public final class SherpaModelManager {

    public interface Callback {
        void onModelReady(@NonNull File destinationDir);

        default void onError(@NonNull Exception error) {
        }
    }

    public static final String ASSET_SUBDIR = "sherpa-onnx/streaming-zipformer/zh-en";
    public static final String DEST_SUBDIR = "sherpa-onnx/streaming-zipformer/zh-en";

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private SherpaModelManager() {
    }

    public static File getDestinationDir(@NonNull Context context) {
        return new File(context.getFilesDir(), DEST_SUBDIR);
    }

    public static void prepareModel(@NonNull Context context, @NonNull Callback callback) {
        final File targetDir = getDestinationDir(context);
        EXECUTOR.execute(() -> {
            try {
                copyAssetDir(context.getAssets(), ASSET_SUBDIR, targetDir);
                MAIN.post(() -> callback.onModelReady(targetDir));
            } catch (Exception e) {
                MAIN.post(() -> callback.onError(e));
            }
        });
    }

    private static void copyAssetDir(
            @NonNull AssetManager assetManager,
            @NonNull String assetPath,
            @NonNull File dest
    ) throws IOException {
        String[] children = assetManager.list(assetPath);
        // assetManager.list returns an empty array for files
        if (children == null || children.length == 0) {
            copyAssetFile(assetManager, assetPath, dest);
            return;
        }

        if (!dest.exists() && !dest.mkdirs()) {
            throw new IOException("Unable to create directory " + dest);
        }

        for (String child : children) {
            String childAssetPath = assetPath + "/" + child;
            File childDest = new File(dest, child);
            copyAssetDir(assetManager, childAssetPath, childDest);
        }
    }

    private static void copyAssetFile(
            @NonNull AssetManager assetManager,
            @NonNull String assetPath,
            @NonNull File destFile
    ) throws IOException {
        if (destFile.exists()) return;
        File parent = destFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Unable to create parent directory " + parent);
        }
        try (InputStream in = assetManager.open(assetPath);
             FileOutputStream out = new FileOutputStream(destFile)) {
            byte[] buffer = new byte[8 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            out.flush();
        }
    }
}
