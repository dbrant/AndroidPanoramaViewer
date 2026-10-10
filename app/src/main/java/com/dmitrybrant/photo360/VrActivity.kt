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
package com.dmitrybrant.photo360

import android.Manifest.permission
import android.content.Intent
import android.content.pm.PackageManager
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import androidx.annotation.MainThread
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.dmitrybrant.photo360.rendering.SceneRenderer
import com.google.cardboard.sdk.CardboardView
import com.google.cardboard.sdk.HeadTransform
import com.google.cardboard.sdk.Viewport
import kotlinx.coroutines.MainScope
import javax.microedition.khronos.egl.EGLConfig

/**
 * Cardboard Activity demonstrating a 360 video player.
 *
 * The default intent for this Activity will load a 360 placeholder panorama. For more options on
 * how to load other media using a custom Intent, see [MediaLoader].
 */
class VrActivity : AppCompatActivity() {
    private lateinit var cardboardView: CardboardView
    private lateinit var renderer: Renderer

    // Displays the controls for video playback.
    private lateinit var uiView: VideoUiView

    // Given an intent with a media file and format, this will load the file and generate the mesh.
    private lateinit var mediaLoader: MediaLoader

    /**
     * Configures the VR system.
     *
     * @param savedInstanceState unused in this sample but it could be used to track video position
     */
    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mediaLoader = MediaLoader(this)
        cardboardView = CardboardView(this)

        // Use the whole screen, without the system bars, and keep it on.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        // Standard CardboardView configuration
        renderer = Renderer(cardboardView)
        cardboardView.setEGLConfigChooser(
            8, 8, 8, 8,  // RGBA bits.
            16,  // Depth bits.
            0
        ) // Stencil bits.
        cardboardView.setRenderer(renderer)
        cardboardView.setStereoRenderMode(true)
        setContentView(cardboardView)

        // Handle the user clicking on the 'X' in the top left corner. Since this is done when the user
        // has taken the headset out of VR, it should launch the app's exit flow directly.
        cardboardView.setOnBackButtonClick { launch2dActivity() }
        cardboardView.setOnSettingsButtonClick { cardboardView.scanViewerQrCode() }

        // Cardboard viewers have no controller, so the viewer's trigger (or a tap on the screen)
        // clicks on whatever the user is looking at.
        cardboardView.setOnTriggerEvent { renderer.scene.handleClick() }

        checkPermissionAndInitialize()
    }

    /**
     * Normal apps don't need this. However, since we use adb to interact with this sample, we
     * want any new adb Intents to be routed to the existing Activity rather than launching a new
     * Activity.
     */
    override fun onNewIntent(intent: Intent) {
        // Save the new Intent which may contain a new Uri. Then tear down & recreate this Activity to
        // load that Uri.
        setIntent(intent)
        recreate()
    }

    /**
     * Launches the 2D app with the same extras and data.
     */
    private fun launch2dActivity() {
        startActivity(Intent(intent).setClass(this, MainActivity::class.java))
        // When launching the other Activity, it may be necessary to finish() this Activity in order to
        // free up the MediaPlayer resources. This sample doesn't call mediaPlayer.release() unless the
        // Activities are destroy()ed. This allows the video to be paused and resumed when another app
        // is in the foreground. However, most phones have trouble maintaining sufficient resources for
        // 2 4k videos in the same process. Large videos may fail to play in the second Activity if the
        // first Activity hasn't finish()ed.
        //
        // Alternatively, a single media player instance can be reused across multiple Activities in
        // order to conserve resources.
        finish()
    }

    /**
     * Initializes the Activity only if the permission has been granted.
     */
    private fun checkPermissionAndInitialize() {
        if (ContextCompat.checkSelfPermission(this, permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            // TODO: handle permissions
        }
        mediaLoader.loadFromIntent(intent, MainScope(), uiView)
    }

    override fun onResume() {
        super.onResume()
        cardboardView.onResume()
        mediaLoader.resume()
    }

    override fun onPause() {
        mediaLoader.pause()
        cardboardView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        mediaLoader.destroy()
        uiView.setMediaPlayer(null)
        cardboardView.onDestroy()
        super.onDestroy()
    }

    /**
     * Standard Cardboard renderer. Most of the real work is done by [SceneRenderer].
     */
    private inner class Renderer @MainThread constructor(parent: ViewGroup?) : CardboardView.Renderer {
        // Used by the trigger event handler to manipulate the scene.
        val scene: SceneRenderer
        private val headView = FloatArray(16)
        private val viewProjectionMatrix = FloatArray(16)
        private val eyeViewport = IntArray(4)

        init {
            val pair = SceneRenderer.createForVR(this@VrActivity, parent)
            scene = pair.first
            uiView = pair.second
            uiView.setVrIconClickListener { launch2dActivity() }
        }

        override fun onNewFrame(headTransform: HeadTransform) {
            headTransform.getHeadView(headView, 0)
            scene.setHeadView(headView)
        }

        override fun onDrawEye(eye: CardboardView.Eye) {
            // CardboardView draws both eyes into the same framebuffer, so restrict drawing (including
            // the glClear in SceneRenderer) to this eye's viewport.
            GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, eyeViewport, 0)
            GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
            GLES20.glScissor(eyeViewport[0], eyeViewport[1], eyeViewport[2], eyeViewport[3])

            eye.applyHeadView(headView)
            Matrix.multiplyMM(
                viewProjectionMatrix,
                0,
                eye.getPerspective(Z_NEAR, Z_FAR),
                0,
                eye.eyeView,
                0
            )
            scene.glDrawFrame(viewProjectionMatrix, eye.eyeType)
        }

        override fun onFinishFrame(viewport: Viewport) {}
        override fun onSurfaceCreated(config: EGLConfig) {
            scene.glInit()
            mediaLoader.onGlSceneReady(scene)
        }

        override fun onSurfaceChanged(width: Int, height: Int) {}
        override fun onRendererShutdown() {
            scene.glShutdown()
        }
    }

    companion object {
        private const val Z_NEAR = .1f
        private const val Z_FAR = 100f
    }
}
