/*
 * Copyright 2019+ Dmitry Brant.
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

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaPlayer
import android.view.Surface
import android.widget.Toast
import com.dmitrybrant.photo360.rendering.Mesh
import com.dmitrybrant.photo360.rendering.PhotoSphereTools
import com.dmitrybrant.photo360.rendering.PhotoSphereTools.PhotoSphereData
import com.dmitrybrant.photo360.rendering.SceneRenderer
import com.dmitrybrant.photo360.rendering.Utils
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URLConnection
import java.security.InvalidParameterException
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MediaLoader(private val context: Context) {
    // This can be replaced by any media player that renders to a Surface. In a real app, this
    // media player would be separated from the rendering code. It is left in this class for
    // simplicity.
    // This should be set or cleared in a synchronized manner.
    private var mediaPlayer: MediaPlayer? = null

    // This sample also supports loading images.
    private var mediaImage: Bitmap? = null
    private var photoSphereData: PhotoSphereData? = null

    // Due to the slow loading media times, it's possible to tear down the app before mediaPlayer is
    // ready. In that case, abandon all the pending work.
    // This should be set or cleared in a synchronized manner.
    private var isDestroyed = false

    // The type of mesh created depends on the type of media.
    private var mesh: Mesh? = null

    // The sceneRenderer is set after GL initialization is complete.
    private var sceneRenderer: SceneRenderer? = null

    // The displaySurface is configured after both GL initialization and media loading.
    private var displaySurface: Surface? = null

    fun loadFromIntent(intent: Intent, coroutineScope: CoroutineScope, uiView: VideoUiView) {
        coroutineScope.launch (CoroutineExceptionHandler { _, throwable ->
            throwable.printStackTrace()
            Toast.makeText(context, throwable.message, Toast.LENGTH_SHORT).show()
        }) {
            uiView.showProgressBar(true)
            uiView.showControls(false)

            //String defaultUrl = "https://upload.wikimedia.org/wikipedia/commons/thumb/7/72/MK_30645-58_Stadtschloss_Wiesbaden.jpg/1280px-MK_30645-58_Stadtschloss_Wiesbaden.jpg";
            val defaultUrl = "http://rivendell.dmitrybrant.com/pano1.jpg"

            val uri = intent.data ?: defaultUrl.toUri()
            var stereoFormat = intent.getIntExtra(MEDIA_FORMAT_KEY, Mesh.MEDIA_MONOSCOPIC)
            if (stereoFormat != Mesh.MEDIA_STEREO_LEFT_RIGHT && stereoFormat != Mesh.MEDIA_STEREO_TOP_BOTTOM) {
                stereoFormat = Mesh.MEDIA_MONOSCOPIC
            }

            mesh = Mesh.createUvSphere(
                SPHERE_RADIUS_METERS.toFloat(),
                DEFAULT_SPHERE_ROWS,
                DEFAULT_SPHERE_COLUMNS,
                DEFAULT_SPHERE_VERTICAL_DEGREES.toFloat(),
                DEFAULT_SPHERE_HORIZONTAL_DEGREES.toFloat(),
                stereoFormat
            )

            var stream: InputStream? = null
            try {
                val type = URLConnection.guessContentTypeFromName(uri.path)
                if (type == null) {
                    throw InvalidParameterException("Unknown file type: $uri")
                } else if (type.startsWith("image")) {
                    // TODO: figure out how to NOT need to read the whole file at once.
                    withContext(Dispatchers.IO) {
                        var response: Response? = null
                        if ("http" == uri.scheme || "https" == uri.scheme) {
                            val client = OkHttpClient()
                            val request = Request.Builder().url(uri.toString()).build()
                            response = client.newCall(request).execute()
                        }
                        val bytes = response!!.body.bytes()
                        //stream = response.body().byteStream();
                        stream = ByteArrayInputStream(bytes)

                        mediaImage = BitmapFactory.decodeStream(stream)
                        photoSphereData = PhotoSphereTools.getPhotoSphereData(bytes)
                    }
                } else if (type.startsWith("video")) {
                    val mp = MediaPlayer.create(context, uri)
                    synchronized(this@MediaLoader) {
                        // This needs to be synchronized with the methods that could clear mediaPlayer.
                        mediaPlayer = mp
                    }
                }
            } finally {
                Utils.closeSilently(stream)
            }

            displayWhenReady()

            uiView.showProgressBar(false)
            // Set or clear the UI's mediaPlayer on the UI thread.
            if (mediaPlayer != null) {
                uiView.setMediaPlayer(mediaPlayer)
                uiView.showControls(true)
            } else {
                uiView.showControls(false)
            }
        }
    }

    /**
     * Notifies MediaLoader that GL components have initialized.
     */
    fun onGlSceneReady(sceneRenderer: SceneRenderer?) {
        this.sceneRenderer = sceneRenderer
        displayWhenReady()
    }

    private fun displayWhenReady() {
        if (isDestroyed) {
            // This only happens when the Activity is destroyed immediately after creation.
            if (mediaPlayer != null) {
                mediaPlayer!!.release()
                mediaPlayer = null
            }
            return
        }

        if (displaySurface != null) {
            // Avoid double initialization caused by sceneRenderer & mediaPlayer being initialized before
            // displayWhenReady is executed.
            return
        }

        if ((mediaImage == null && mediaPlayer == null) || sceneRenderer == null) {
            // Wait for everything to be initialized.
            return
        }

        // The important methods here are the setSurface & lockCanvas calls. These will have to happen
        // after the GLView is created.
        if (mediaPlayer != null) {
            // For videos, attach the displaySurface and mediaPlayer.
            displaySurface = sceneRenderer!!.createDisplay(mediaPlayer!!.videoWidth, mediaPlayer!!.videoHeight, mesh)
            mediaPlayer!!.setSurface(displaySurface)
            // Start playback.
            mediaPlayer!!.isLooping = true
            mediaPlayer!!.start()
        } else if (mediaImage != null) {
            // For images, acquire the displaySurface and draw the bitmap to it. Since our Mesh class uses
            // an GL_TEXTURE_EXTERNAL_OES texture, it's possible to perform this decoding and rendering of
            // a bitmap in the background without stalling the GL thread. If the Mesh used a standard
            // GL_TEXTURE_2D, then it's possible to stall the GL thread for 100+ ms during the
            // glTexImage2D call when loading 4k x 4k panoramas and copying the bitmap's data.

            if (photoSphereData == null && (mediaImage!!.getHeight() * 2 != mediaImage!!.getWidth())) {
                // If the image does not have an exact 2:1 aspect ratio, it likely means that it's a cropped
                // panorama, but unfortunately it's lacking the precise photosphere data. In this case,
                // let's build a fake photosphere object to make up for it, and place the image in the
                // center of it.
                photoSphereData = PhotoSphereData()
                photoSphereData!!.croppedAreaImageWidthPixels = mediaImage!!.getWidth()
                photoSphereData!!.croppedAreaImageHeightPixels = mediaImage!!.getHeight()
                if (mediaImage!!.getWidth() > mediaImage!!.getHeight() * 2) {
                    photoSphereData!!.fullPanoWidthPixels = mediaImage!!.getWidth()
                    photoSphereData!!.fullPanoHeightPixels =
                        photoSphereData!!.fullPanoWidthPixels / 2
                    photoSphereData!!.croppedAreaLeftPixels = 0
                    photoSphereData!!.croppedAreaTopPixels =
                        photoSphereData!!.fullPanoHeightPixels / 2 - mediaImage!!.getHeight() / 2
                } else {
                    photoSphereData!!.fullPanoHeightPixels = mediaImage!!.getHeight()
                    photoSphereData!!.fullPanoWidthPixels =
                        photoSphereData!!.fullPanoHeightPixels * 2
                    photoSphereData!!.croppedAreaTopPixels = 0
                    photoSphereData!!.croppedAreaLeftPixels =
                        photoSphereData!!.fullPanoWidthPixels / 2 - mediaImage!!.getWidth() / 2
                }
            }

            if (photoSphereData != null) {
                val maxWidth = 4096
                val scale = photoSphereData!!.fullPanoWidthPixels.toFloat() / maxWidth

                displaySurface = sceneRenderer!!.createDisplay(
                    (photoSphereData!!.fullPanoWidthPixels.toFloat() / scale).toInt(),
                    (photoSphereData!!.fullPanoHeightPixels.toFloat() / scale).toInt(), mesh
                )
                val c = displaySurface!!.lockCanvas(null)

                val src = Rect(0, 0, mediaImage!!.getWidth(), mediaImage!!.getHeight())
                val dst = Rect(
                    (photoSphereData!!.croppedAreaLeftPixels.toFloat() / scale).toInt(),
                    (photoSphereData!!.croppedAreaTopPixels.toFloat() / scale).toInt(),
                    ((photoSphereData!!.croppedAreaLeftPixels + photoSphereData!!.croppedAreaImageWidthPixels).toFloat() / scale).toInt(),
                    ((photoSphereData!!.croppedAreaTopPixels + photoSphereData!!.croppedAreaImageHeightPixels).toFloat() / scale).toInt()
                )
                c.drawBitmap(mediaImage!!, src, dst, null)

                displaySurface!!.unlockCanvasAndPost(c)
            } else {
                displaySurface = sceneRenderer!!.createDisplay(
                    mediaImage!!.getWidth(),
                    mediaImage!!.getHeight(),
                    mesh
                )
                val c = displaySurface!!.lockCanvas(null)
                c.drawBitmap(mediaImage!!, 0f, 0f, null)
                displaySurface!!.unlockCanvasAndPost(c)
            }
        } else {
            // Handle the error case by creating a placeholder panorama.
            mesh = Mesh.createUvSphere(
                SPHERE_RADIUS_METERS.toFloat(),
                DEFAULT_SPHERE_ROWS,
                DEFAULT_SPHERE_COLUMNS,
                DEFAULT_SPHERE_VERTICAL_DEGREES.toFloat(),
                DEFAULT_SPHERE_HORIZONTAL_DEGREES.toFloat(),
                Mesh.MEDIA_MONOSCOPIC
            )

            // 4k x 2k is a good default resolution for monoscopic panoramas.
            displaySurface = sceneRenderer!!.createDisplay(
                2 * DEFAULT_SURFACE_HEIGHT_PX, DEFAULT_SURFACE_HEIGHT_PX, mesh
            )
            // Render placeholder grid and error text.
            val c = displaySurface!!.lockCanvas(null)
            renderEquirectangularGrid(c, context.getString(R.string.error_message))
            displaySurface!!.unlockCanvasAndPost(c)
        }
    }

    fun pause() {
        if (mediaPlayer != null) {
            mediaPlayer!!.pause()
        }
    }

    fun resume() {
        if (mediaPlayer != null) {
            mediaPlayer!!.start()
        }
    }

    fun destroy() {
        if (mediaPlayer != null) {
            mediaPlayer!!.stop()
            mediaPlayer!!.release()
            mediaPlayer = null
        }
        isDestroyed = true
    }

    companion object {
        private const val TAG = "MediaLoader"

        const val MEDIA_FORMAT_KEY: String = "stereoFormat"
        private const val DEFAULT_SURFACE_HEIGHT_PX = 2048

        /**
         * A spherical mesh for video should be large enough that there are no stereo artifacts.
         */
        private const val SPHERE_RADIUS_METERS = 50

        /**
         * These should be configured based on the video type. But this sample assumes 360 video.
         */
        private const val DEFAULT_SPHERE_VERTICAL_DEGREES = 180
        private const val DEFAULT_SPHERE_HORIZONTAL_DEGREES = 360

        /**
         * The 360 x 180 sphere has 15 degree quads. Increase these if lines in your video look wavy.
         */
        private const val DEFAULT_SPHERE_ROWS = 32
        private const val DEFAULT_SPHERE_COLUMNS = 32

        /**
         * Renders a placeholder grid with optional error text.
         */
        private fun renderEquirectangularGrid(canvas: Canvas, message: String?) {
            // Configure the grid. Each square will be 15 x 15 degrees.
            val width = canvas.width
            val height = canvas.height
            // This assumes a 4k resolution.
            val majorWidth = width / 256
            val minorWidth = width / 1024
            val paint = Paint()

            // Draw a black ground & gray sky background
            paint.setColor(Color.BLACK)
            canvas.drawRect(0f, (height / 2).toFloat(), width.toFloat(), height.toFloat(), paint)
            paint.setColor(Color.GRAY)
            canvas.drawRect(0f, 0f, width.toFloat(), (height / 2).toFloat(), paint)

            // Render the grid lines.
            paint.setColor(Color.WHITE)

            for (i in 0..<DEFAULT_SPHERE_COLUMNS) {
                val x: Int = width * i / DEFAULT_SPHERE_COLUMNS
                paint.strokeWidth = (if (i % 3 == 0) majorWidth else minorWidth).toFloat()
                canvas.drawLine(x.toFloat(), 0f, x.toFloat(), height.toFloat(), paint)
            }

            for (i in 0..<DEFAULT_SPHERE_ROWS) {
                val y: Int = height * i / DEFAULT_SPHERE_ROWS
                paint.strokeWidth = (if (i % 3 == 0) majorWidth else minorWidth).toFloat()
                canvas.drawLine(0f, y.toFloat(), width.toFloat(), y.toFloat(), paint)
            }

            // Render optional text.
            if (message != null) {
                paint.textSize = (height / 64).toFloat()
                paint.setColor(Color.RED)
                val textWidth = paint.measureText(message)

                canvas.drawText(
                    message,
                    width / 2 - textWidth / 2,  // Horizontally center the text.
                    (9 * height / 16).toFloat(),  // Place it slightly below the horizon for better contrast.
                    paint
                )
            }
        }
    }
}
