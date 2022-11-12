/*
 * Copyright 2022 Dmitry Brant.
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
package com.dmitrybrant.photo360

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import android.Manifest.permission
import android.content.pm.PackageManager
import com.google.vr.ndk.base.DaydreamApi
import android.content.Intent
import android.content.ComponentName
import android.view.View
import com.dmitrybrant.photo360.rendering.Mesh

/**
 * Basic Activity to hold [MonoscopicView] and render a 360 video in 2D.
 *
 * Most of this Activity's code is related to Android & VR permission handling. The real work is in
 * MonoscopicView.
 *
 * The default intent for this Activity will load a 360 placeholder panorama. For more options on
 * how to load other media using a custom Intent, see [MediaLoader].
 */
class MainActivity : AppCompatActivity() {
    private lateinit var mediaView: MonoscopicView
    private lateinit var vrButton: View

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.video_activity)

        val videoUi = findViewById<VideoUiView>(R.id.video_ui_view)
        videoUi.setVrIconClickListener { startVrActivity() }

        vrButton = findViewById(R.id.vr_fab)
        vrButton.setOnClickListener { startVrActivity() }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.container_view)) { _: View?, insets: WindowInsetsCompat ->
            val params = vrButton.layoutParams as FrameLayout.LayoutParams
            params.topMargin = insets.systemWindowInsetTop
            params.bottomMargin = insets.systemWindowInsetBottom
            params.leftMargin = insets.systemWindowInsetLeft
            params.rightMargin = insets.systemWindowInsetRight
            insets.consumeSystemWindowInsets()
        }

        mediaView = findViewById(R.id.media_view)
        mediaView.initialize(videoUi)

        // Boilerplate for checking runtime permissions in Android.
        if (ContextCompat.checkSelfPermission(this, permission.READ_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            // TODO: ask for permission when necessary.
        }

        // Immediately pass the intent to the media loader.
        mediaView.loadMedia(intent)
    }

    private fun startVrActivity() {
        // Convert the Intent used to launch the 2D Activity into one that can launch the VR
        // Activity. This flow preserves the extras and data in the Intent.
        val api = DaydreamApi.create(this)
        if (api != null) {
            // Launch the VR Activity with the proper intent.
            val intent = DaydreamApi.createVrIntent(
                ComponentName(this, VrActivity::class.java)
            )
            intent.data = getIntent().data
            intent.putExtra(
                MediaLoader.MEDIA_FORMAT_KEY,
                getIntent().getIntExtra(MediaLoader.MEDIA_FORMAT_KEY, Mesh.MEDIA_MONOSCOPIC)
            )
            api.launchInVr(intent)
            api.close()
        } else {
            // Fall back for devices that don't have Google VR Services. This flow should only
            // be used for older Cardboard devices.
            val intent = Intent(intent).setClass(this, VrActivity::class.java)
            intent.removeCategory(Intent.CATEGORY_LAUNCHER)
            intent.flags = 0 // Clear any flags from the previous intent.
            startActivity(intent)
        }

        // See VrVideoActivity's launch2dActivity() for more info about why this finish() call
        // may be required.
        finish()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        results: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == READ_EXTERNAL_STORAGE_PERMISSION_ID) {
            if (results.size > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                // TODO
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recreate()
    }

    override fun onResume() {
        super.onResume()
        mediaView.onResume()
    }

    override fun onPause() {
        // MonoscopicView is a GLSurfaceView so it needs to pause & resume rendering. It's also
        // important to pause MonoscopicView's sensors & the video player.
        mediaView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        mediaView.destroy()
        super.onDestroy()
    }

    companion object {
        private const val READ_EXTERNAL_STORAGE_PERMISSION_ID = 1
    }
}
