/*
 * Copyright 2019 Dmitry Brant.
 *
 * Based loosely on the Google VR SDK sample apps.
 * Copyright 2017 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.dmitrybrant.photo360;

import android.Manifest.permission;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;

import com.dmitrybrant.photo360.rendering.Mesh;
import com.google.vr.ndk.base.DaydreamApi;

/**
 * Basic Activity to hold {@link MonoscopicView} and render a 360 video in 2D.
 *
 * Most of this Activity's code is related to Android & VR permission handling. The real work is in
 * MonoscopicView.
 *
 * The default intent for this Activity will load a 360 placeholder panorama. For more options on
 * how to load other media using a custom Intent, see {@link MediaLoader}.
 */
public class MainActivity extends AppCompatActivity {
    private static final int READ_EXTERNAL_STORAGE_PERMISSION_ID = 1;
    private MonoscopicView mediaView;
    private View vrButton;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.video_activity);

        VideoUiView videoUi = findViewById(R.id.video_ui_view);
        videoUi.setVrIconClickListener(v -> startVrActivity());
        vrButton = findViewById(R.id.vr_fab);
        vrButton.setOnClickListener(v -> startVrActivity());

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.container_view), (v, insets) -> {
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) vrButton.getLayoutParams();
            params.topMargin = insets.getSystemWindowInsetTop();
            params.bottomMargin = insets.getSystemWindowInsetBottom();
            params.leftMargin = insets.getSystemWindowInsetLeft();
            params.rightMargin = insets.getSystemWindowInsetRight();
            return insets.consumeSystemWindowInsets();
        });

        mediaView = findViewById(R.id.media_view);
        mediaView.initialize(videoUi);

        // Boilerplate for checking runtime permissions in Android.
        if (ContextCompat.checkSelfPermission(this, permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            // TODO: ask for permission when necessary.
        }

        // Immediately pass the intent to the media loader.
        mediaView.loadMedia(getIntent());
    }

    private void startVrActivity() {
        // Convert the Intent used to launch the 2D Activity into one that can launch the VR
        // Activity. This flow preserves the extras and data in the Intent.
        DaydreamApi api = DaydreamApi.create(MainActivity.this);
        if (api != null) {
            // Launch the VR Activity with the proper intent.
            Intent intent = DaydreamApi.createVrIntent(
                    new ComponentName(MainActivity.this, VrActivity.class));
            intent.setData(getIntent().getData());
            intent.putExtra(
                    MediaLoader.MEDIA_FORMAT_KEY,
                    getIntent().getIntExtra(MediaLoader.MEDIA_FORMAT_KEY, Mesh.MEDIA_MONOSCOPIC));
            api.launchInVr(intent);
            api.close();
        } else {
            // Fall back for devices that don't have Google VR Services. This flow should only
            // be used for older Cardboard devices.
            Intent intent =
                    new Intent(getIntent()).setClass(MainActivity.this, VrActivity.class);
            intent.removeCategory(Intent.CATEGORY_LAUNCHER);
            intent.setFlags(0);  // Clear any flags from the previous intent.
            startActivity(intent);
        }

        // See VrVideoActivity's launch2dActivity() for more info about why this finish() call
        // may be required.
        finish();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] results) {
        if (requestCode == READ_EXTERNAL_STORAGE_PERMISSION_ID) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                // TODO
            }
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        recreate();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mediaView.onResume();
    }

    @Override
    protected void onPause() {
        // MonoscopicView is a GLSurfaceView so it needs to pause & resume rendering. It's also
        // important to pause MonoscopicView's sensors & the video player.
        mediaView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        mediaView.destroy();
        super.onDestroy();
    }
}
