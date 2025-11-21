/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2025 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.voice;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

/**
 * Transparent trampoline activity to request RECORD_AUDIO for voice input.
 */
public class VoicePermissionActivity extends Activity {

    public static final String ACTION_RESULT =
            "org.fcitx.fcitx5.android.voice.PERMISSION_RESULT";
    public static final String EXTRA_GRANTED = "granted";
    private static final int REQ_CODE = 0x5352;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (hasPermission()) {
            sendResult(true);
            finish();
            return;
        }
        ActivityCompat.requestPermissions(
                this,
                new String[]{Manifest.permission.RECORD_AUDIO},
                REQ_CODE
        );
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            @NonNull String[] permissions,
            @NonNull int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CODE) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            sendResult(granted);
        }
        finish();
    }

    private boolean hasPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void sendResult(boolean granted) {
        Intent intent = new Intent(ACTION_RESULT)
                .setPackage(getPackageName())
                .putExtra(EXTRA_GRANTED, granted);
        sendBroadcast(intent);
    }
}
