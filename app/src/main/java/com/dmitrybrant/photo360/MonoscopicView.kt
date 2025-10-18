/*
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
import android.graphics.PointF
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.annotation.AnyThread
import androidx.annotation.BinderThread
import androidx.annotation.UiThread
import com.dmitrybrant.photo360.rendering.SceneRenderer
import com.google.vr.sdk.base.Eye
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.concurrent.Volatile
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Renders a GL scene in a non-VR Activity that is affected by phone orientation and touch input.
 *
 *
 * The two input components are the TYPE_GAME_ROTATION_VECTOR Sensor and a TouchListener. The GL
 * renderer combines these two inputs to render a scene with the appropriate camera orientation.
 *
 *
 * The primary complexity in this class is related to the various rotations. It is important to
 * apply the touch and sensor rotations in the correct order or the user's touch manipulations won't
 * match what they expect.
 */
class MonoscopicView(context: Context?, attributeSet: AttributeSet?) :
    GLSurfaceView(context, attributeSet) {
    // We handle all the sensor orientation detection ourselves.
    private var sensorManager: SensorManager? = null
    private var orientationSensor: Sensor? = null
    private var phoneOrientationListener: PhoneOrientationListener? = null

    private var mediaLoader: MediaLoader? = null
    private var renderer: Renderer? = null
    private var touchTracker: TouchTracker? = null
    private var uiView: VideoUiView? = null

    /** Inflates a standard GLSurfaceView.  */
    init {
        preserveEGLContextOnPause = true
    }

    /**
     * Finishes initialization. This should be called immediately after the View is inflated.
     *
     * @param uiView the video UI that should be bound to the underlying SceneRenderer
     */
    fun initialize(uiView: VideoUiView) {
        this.uiView = uiView
        mediaLoader = MediaLoader(context)

        // Configure OpenGL.
        renderer = Renderer(uiView, mediaLoader!!)
        setEGLContextClientVersion(2)
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY

        // Configure sensors and touch.
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        // TYPE_GAME_ROTATION_VECTOR is the easiest sensor since it handles all the complex math for
        // fusion. It's used instead of TYPE_ROTATION_VECTOR since the latter uses the mangetometer on
        // devices. When used indoors, the magnetometer can take some time to settle depending on the
        // device and amount of metal in the environment.
        orientationSensor = sensorManager!!.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        phoneOrientationListener = PhoneOrientationListener()

        touchTracker = TouchTracker(renderer!!)
        setOnTouchListener(touchTracker)
    }

    /** Starts the sensor & video only when this View is active.  */
    override fun onResume() {
        super.onResume()
        // Use the fastest sensor readings.
        sensorManager!!.registerListener(
            phoneOrientationListener, orientationSensor, SensorManager.SENSOR_DELAY_FASTEST
        )
        mediaLoader!!.resume()
    }

    /** Stops the sensors & video when the View is inactive to avoid wasting battery.  */
    override fun onPause() {
        mediaLoader!!.pause()
        sensorManager!!.unregisterListener(phoneOrientationListener)
        super.onPause()
    }

    /** Destroys the underlying resources. If this is not called, the MediaLoader may leak.  */
    fun destroy() {
        uiView!!.setMediaPlayer(null)
        mediaLoader!!.destroy()
    }

    /** Parses the Intent and loads the appropriate media.  */
    fun loadMedia(intent: Intent?) {
        mediaLoader!!.handleIntent(intent, uiView!!)
    }

    /** Detects sensor events and saves them as a matrix.  */
    private inner class PhoneOrientationListener : SensorEventListener {
        private val phoneInWorldSpaceMatrix = FloatArray(16)
        private val remappedPhoneMatrix = FloatArray(16)
        private val angles = FloatArray(3)

        @BinderThread
        override fun onSensorChanged(event: SensorEvent) {
            SensorManager.getRotationMatrixFromVector(phoneInWorldSpaceMatrix, event.values)

            // Extract the phone's roll and pass it on to touchTracker & renderer. Remapping is required
            // since we need the calculated roll of the phone to be independent of the phone's pitch &
            // yaw. Any operation that decomposes rotation to Euler angles needs to be performed
            // carefully.
            SensorManager.remapCoordinateSystem(
                phoneInWorldSpaceMatrix,
                SensorManager.AXIS_X, SensorManager.AXIS_MINUS_Z,
                remappedPhoneMatrix
            )
            SensorManager.getOrientation(remappedPhoneMatrix, angles)
            val roll = angles[2]
            touchTracker!!.setRoll(roll)

            // Rotate from Android coordinates to OpenGL coordinates. Android's coordinate system
            // assumes Y points North and Z points to the sky. OpenGL has Y pointing up and Z pointing
            // toward the user.
            Matrix.rotateM(phoneInWorldSpaceMatrix, 0, 90f, 1f, 0f, 0f)
            renderer!!.setDeviceOrientation(phoneInWorldSpaceMatrix, roll)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    internal class TouchTracker(private val renderer: Renderer) : OnTouchListener {
        // With every touch event, update the accumulated degrees offset by the new pixel amount.
        private val previousTouchPointPx = PointF()
        private val accumulatedTouchOffsetDegrees = PointF()

        // The conversion from touch to yaw & pitch requires compensating for device roll. This is set
        // on the sensor thread and read on the UI thread.
        @Volatile
        private var roll = 0f

        override fun onTouch(v: View?, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    // Initialize drag gesture.
                    previousTouchPointPx.set(event.x, event.y)
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    // Calculate the touch delta in screen space.
                    val touchX = (event.x - previousTouchPointPx.x) / PX_PER_DEGREES
                    val touchY = (event.y - previousTouchPointPx.y) / PX_PER_DEGREES
                    previousTouchPointPx.set(event.x, event.y)

                    val r = roll // Copy volatile state.
                    val cr = cos(r.toDouble()).toFloat()
                    val sr = sin(r.toDouble()).toFloat()
                    // To convert from screen space to the 3D space, we need to adjust the drag vector based
                    // on the roll of the phone. This is standard rotationMatrix(roll) * vector math but has
                    // an inverted y-axis due to the screen-space coordinates vs GL coordinates.
                    // Handle yaw.
                    accumulatedTouchOffsetDegrees.x -= cr * touchX - sr * touchY
                    // Handle pitch and limit it to 45 degrees.
                    accumulatedTouchOffsetDegrees.y += sr * touchX + cr * touchY
                    accumulatedTouchOffsetDegrees.y = max(
                        -MAX_PITCH_DEGREES,
                        min(MAX_PITCH_DEGREES, accumulatedTouchOffsetDegrees.y)
                    )

                    renderer.setPitchOffset(accumulatedTouchOffsetDegrees.y)
                    renderer.setYawOffset(accumulatedTouchOffsetDegrees.x)
                    return true
                }

                else -> return false
            }
        }

        @BinderThread
        fun setRoll(roll: Float) {
            // We compensate for roll by rotating in the opposite direction.
            this.roll = -roll
        }

        companion object {
            // Arbitrary touch speed number. This should be tweaked so the scene smoothly follows the
            // finger or derived from DisplayMetrics.
            const val PX_PER_DEGREES: Float = 25f

            // Touch input won't change the pitch beyond +/- 45 degrees. This reduces awkward situations
            // where the touch-based pitch and gyro-based pitch interact badly near the poles.
            const val MAX_PITCH_DEGREES: Float = 45f
        }
    }

    /**
     * Standard GL Renderer implementation. The notable code is the matrix multiplication in
     * onDrawFrame and updatePitchMatrix.
     */
    internal class Renderer(uiView: VideoUiView?, mediaLoader: MediaLoader) :
        GLSurfaceView.Renderer {
        private val scene: SceneRenderer = SceneRenderer.createFor2D()

        private val projectionMatrix = FloatArray(16)

        // There is no model matrix for this scene so viewProjectionMatrix is used for the mvpMatrix.
        private val viewProjectionMatrix = FloatArray(16)

        // Device orientation is derived from sensor data. This is accessed in the sensor's thread and
        // the GL thread.
        private val deviceOrientationMatrix = FloatArray(16)

        // Optional pitch and yaw rotations are applied to the sensor orientation. These are accessed on
        // the UI, sensor and GL Threads.
        private val touchPitchMatrix = FloatArray(16)
        private val touchYawMatrix = FloatArray(16)
        private var touchPitch = 0f
        private var deviceRoll = 0f

        // viewMatrix = touchPitch * deviceOrientation * touchYaw.
        private val viewMatrix = FloatArray(16)
        private val tempMatrix = FloatArray(16)

        private val uiView: VideoUiView?
        private val mediaLoader: MediaLoader

        init {
            Matrix.setIdentityM(deviceOrientationMatrix, 0)
            Matrix.setIdentityM(touchPitchMatrix, 0)
            Matrix.setIdentityM(touchYawMatrix, 0)
            this.uiView = uiView
            this.mediaLoader = mediaLoader
        }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            scene.glInit()
            if (uiView != null) {
                scene.setVideoFrameListener(uiView.frameListener)
            }
            mediaLoader.onGlSceneReady(scene)
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)
            Matrix.perspectiveM(
                projectionMatrix,
                0,
                FIELD_OF_VIEW_DEGREES.toFloat(),
                width.toFloat() / height,
                Z_NEAR,
                Z_FAR
            )
        }

        override fun onDrawFrame(gl: GL10?) {
            // Combine touch & sensor data.
            // Orientation = pitch * sensor * yaw since that is closest to what most users expect the
            // behavior to be.
            synchronized(this) {
                Matrix.multiplyMM(tempMatrix, 0, deviceOrientationMatrix, 0, touchYawMatrix, 0)
                Matrix.multiplyMM(viewMatrix, 0, touchPitchMatrix, 0, tempMatrix, 0)
            }

            Matrix.multiplyMM(viewProjectionMatrix, 0, projectionMatrix, 0, viewMatrix, 0)
            scene.glDrawFrame(viewProjectionMatrix, Eye.Type.MONOCULAR)
        }

        /** Adjusts the GL camera's rotation based on device rotation. Runs on the sensor thread.  */
        @BinderThread
        @Synchronized
        fun setDeviceOrientation(matrix: FloatArray, deviceRoll: Float) {
            System.arraycopy(matrix, 0, deviceOrientationMatrix, 0, deviceOrientationMatrix.size)
            this.deviceRoll = -deviceRoll
            updatePitchMatrix()
        }

        /**
         * Updates the pitch matrix after a physical rotation or touch input. The pitch matrix rotation
         * is applied on an axis that is dependent on device rotation so this must be called after
         * either touch or sensor update.
         */
        @AnyThread
        private fun updatePitchMatrix() {
            // The camera's pitch needs to be rotated along an axis that is parallel to the real world's
            // horizon. This is the <1, 0, 0> axis after compensating for the device's roll.
            Matrix.setRotateM(
                touchPitchMatrix,
                0,
                -touchPitch,
                cos(deviceRoll.toDouble()).toFloat(),
                sin(deviceRoll.toDouble()).toFloat(),
                0f
            )
        }

        /** Set the pitch offset matrix.  */
        @UiThread
        @Synchronized
        fun setPitchOffset(pitchDegrees: Float) {
            touchPitch = pitchDegrees
            updatePitchMatrix()
        }

        /** Set the yaw offset matrix.  */
        @UiThread
        @Synchronized
        fun setYawOffset(yawDegrees: Float) {
            Matrix.setRotateM(touchYawMatrix, 0, -yawDegrees, 0f, 1f, 0f)
        }

        companion object {
            // Arbitrary vertical field of view. Adjust as desired.
            private const val FIELD_OF_VIEW_DEGREES = 90
            private const val Z_NEAR = .1f
            private const val Z_FAR = 100f
        }
    }
}
