/*
 * ElevationListener.kt
 *
 * Copyright (C) 2022 https://www.ViliusSutkus89.com/i-am-speed/
 *
 * I am Speed is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.viliussutkus89.iamspeed.service

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import androidx.annotation.MainThread
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import kotlin.math.*


internal class ElevationListener(private val context: Context) : SensorEventListener {

    companion object {
        private const val TAG = "ElevationListener"
        private val elevationData_ = MutableLiveData<ElevationData?>(null)
        val elevationData: LiveData<ElevationData?> get() = elevationData_

        // Threshold for detecting significant elevation change (in degrees)
        private const val ELEVATION_THRESHOLD = 2.0f

        // Low-pass filter constant for smoothing accelerometer data
        private const val ALPHA = 0.8f

        // Minimum speed to consider elevation changes (m/s) - about 5 km/h
        private const val MIN_SPEED_FOR_ELEVATION = 1.4f
    }

    private val sensorManager: SensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magnetometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    // Raw sensor data
    private val gravity = FloatArray(3)
    private val geomagnetic = FloatArray(3)
    private val rotationMatrix = FloatArray(9)
    private val orientation = FloatArray(3)

    // Filtered data for smoothing
    private val filteredGravity = FloatArray(3)
    private var isCalibrated = false
    private var baselineAngle = 0f
    private var currentSpeed = 0f

    @MainThread
    fun start() {
        Log.d(TAG, "Starting elevation listener")
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        magnetometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }

        if (accelerometer == null) {
            Log.w(TAG, "Accelerometer not available")
        }
        if (magnetometer == null) {
            Log.w(TAG, "Magnetometer not available")
        }
    }

    @MainThread
    fun stop() {
        Log.d(TAG, "Stopping elevation listener")
        sensorManager.unregisterListener(this)
        elevationData_.value = null
        isCalibrated = false
    }

    fun updateSpeed(speedMs: Float) {
        currentSpeed = speedMs
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                // Apply low-pass filter to smooth the data
                filteredGravity[0] = ALPHA * filteredGravity[0] + (1 - ALPHA) * event.values[0]
                filteredGravity[1] = ALPHA * filteredGravity[1] + (1 - ALPHA) * event.values[1]
                filteredGravity[2] = ALPHA * filteredGravity[2] + (1 - ALPHA) * event.values[2]

                System.arraycopy(filteredGravity, 0, gravity, 0, 3)
                calculateElevation()
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                System.arraycopy(event.values, 0, geomagnetic, 0, 3)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Handle accuracy changes if needed
    }

    private fun calculateElevation() {
        // Only calculate elevation if we have sufficient speed
        if (currentSpeed < MIN_SPEED_FOR_ELEVATION) {
            elevationData_.postValue(ElevationData(ElevationType.FLAT, 0f, "Too slow for elevation detection"))
            return
        }

        // Get rotation matrix and orientation
        if (SensorManager.getRotationMatrix(rotationMatrix, null, gravity, geomagnetic)) {
            SensorManager.getOrientation(rotationMatrix, orientation)

            // Convert pitch from radians to degrees
            val pitchRad = orientation[1]
            val pitchDeg = Math.toDegrees(pitchRad.toDouble()).toFloat()

            // Calibrate baseline when starting (assuming level ground initially)
            if (!isCalibrated) {
                baselineAngle = pitchDeg
                isCalibrated = true
                elevationData_.postValue(ElevationData(ElevationType.FLAT, 0f, "Calibrating..."))
                return
            }

            // Calculate relative angle from baseline
            val relativeAngle = pitchDeg - baselineAngle

            // Determine elevation type based on angle
            val elevationType = when {
                relativeAngle > ELEVATION_THRESHOLD -> ElevationType.UPHILL
                relativeAngle < -ELEVATION_THRESHOLD -> ElevationType.DOWNHILL
                else -> ElevationType.FLAT
            }

            // Calculate grade percentage (rise/run * 100)
            val gradePercent = tan(Math.toRadians(relativeAngle.toDouble())).toFloat() * 100

            val description = when (elevationType) {
                ElevationType.UPHILL -> "Climbing ${String.format("%.1f", abs(gradePercent))}%"
                ElevationType.DOWNHILL -> "Descending ${String.format("%.1f", abs(gradePercent))}%"
                ElevationType.FLAT -> "Level road"
            }

            elevationData_.postValue(ElevationData(elevationType, relativeAngle, description))
        }
    }
}

enum class ElevationType {
    UPHILL, DOWNHILL, FLAT
}

data class ElevationData(
    val type: ElevationType,
    val angle: Float, // in degrees
    val description: String
)