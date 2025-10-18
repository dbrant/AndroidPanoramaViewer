/*
 * Copyright 2022+ Dmitry Brant.
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

import android.Manifest
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import androidx.core.view.ViewCompat
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import com.google.vr.ndk.base.DaydreamApi
import android.content.Intent
import android.content.ComponentName
import android.os.Build
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import com.dmitrybrant.photo360.databinding.VideoActivityBinding
import com.dmitrybrant.photo360.rendering.Mesh
import kotlin.math.max

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
    private lateinit var binding: VideoActivityBinding

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            binding.mediaView.loadMedia(intent)
        } else {
            Toast.makeText(this, R.string.permission_warning, Toast.LENGTH_SHORT).show()
        }
    }

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = VideoActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.mainToolbar)

        binding.videoUiContainer.videoUiView.setVrIconClickListener { startVrActivity() }

        binding.vrFab.setOnClickListener { startVrActivity() }

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val newStatusBarInsets = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val newNavBarInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val newCaptionBarInsets = insets.getInsets(WindowInsetsCompat.Type.captionBar())
            val newSystemBarInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val topInset = max(max(max(newStatusBarInsets.top, newCaptionBarInsets.top), newSystemBarInsets.top), newNavBarInsets.top)
            val bottomInset = max(max(max(newStatusBarInsets.bottom, newCaptionBarInsets.bottom), newSystemBarInsets.bottom), newNavBarInsets.bottom)
            binding.mainToolbarContainer.updatePadding(top = topInset)
            binding.vrFab.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = bottomInset
                leftMargin = newNavBarInsets.left
                rightMargin = newNavBarInsets.right
            }
            insets
        }

        binding.mediaView.initialize(binding.videoUiContainer.videoUiView)

        checkReadPermissionThenOpen()
    }

    private fun checkReadPermissionThenOpen() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)) {
            requestPermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        } else {
            binding.mediaView.loadMedia(intent)
        }
    }

    private fun startVrActivity() {
        // Convert the Intent used to launch the 2D Activity into one that can launch the VR
        // Activity. This flow preserves the extras and data in the Intent.
        val api = DaydreamApi.create(this)
        if (api != null) {
            // Launch the VR Activity with the proper intent.
            api.launchInVr(DaydreamApi.createVrIntent(ComponentName(this, VrActivity::class.java))
                .setData(intent.data)
                .putExtra(MediaLoader.MEDIA_FORMAT_KEY, intent.getIntExtra(MediaLoader.MEDIA_FORMAT_KEY, Mesh.MEDIA_MONOSCOPIC)))
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recreate()
    }

    override fun onResume() {
        super.onResume()
        binding.mediaView.onResume()
    }

    override fun onPause() {
        // MonoscopicView is a GLSurfaceView so it needs to pause & resume rendering. It's also
        // important to pause MonoscopicView's sensors & the video player.
        binding.mediaView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        binding.mediaView.destroy()
        super.onDestroy()
    }
}
