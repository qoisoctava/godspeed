/*
 * SpeedListener.kt
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

import android.content.SharedPreferences
import android.location.LocationManager
import androidx.annotation.MainThread
import androidx.core.location.LocationCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import androidx.core.os.CancellationSignal
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.viliussutkus89.iamspeed.AppSettings
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit


internal class SpeedListener(
    private val locationManager: LocationManager,
    private val executor: ScheduledExecutorService,
    private val sharedPreferences: SharedPreferences
) {

    companion object {
        private val speed_ = MutableLiveData<SpeedEntry?>(null)
        val speed: LiveData<SpeedEntry?> get() = speed_
    }

    private val speedUnit = SpeedUnit(sharedPreferences)

    private var speedEntryClearerFuture: ScheduledFuture<*> ? = null
    private val speedEntryClearerRunnable = {
        speed_.postValue(null)
    }

    private val getCurrentLocationCancellationSignal = CancellationSignal()

    private val gpsUpdateIntervalChangeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key: String? ->
            if (key == AppSettings.gpsUpdateInterval) {
                stopLocationUpdates()
                requestLocationUpdates()
            }
        }

    @MainThread
    fun start() {
        speedUnit.start()
        requestLocationUpdates()
        sharedPreferences.registerOnSharedPreferenceChangeListener(gpsUpdateIntervalChangeListener)
    }

    @MainThread
    fun stop() {
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(gpsUpdateIntervalChangeListener)
        getCurrentLocationCancellationSignal.cancel()
        stopLocationUpdates()
        speedUnit.stop()
        speed_.value = null
    }

    private val locationChangeListener = LocationListenerCompat { location ->
        if (location.hasSpeed() && SpeedListenerService.started.value == true) {
            val accuracy = if (LocationCompat.hasSpeedAccuracy(location)) {
                LocationCompat.getSpeedAccuracyMetersPerSecond(location)
            } else {
                null
            }
            onSpeed(location.speed, accuracy)
        }
    }

    private fun onSpeed(speed: Float, accuracy: Float?) {
        speedUnit.translate(speed, accuracy).let { speedEntry ->
            speedEntryClearerFuture?.cancel(false)
            speed_.postValue(speedEntry)
            speedEntryClearerFuture = executor.schedule(speedEntryClearerRunnable, AppSettings.speedEntryTotalTimeout, TimeUnit.MILLISECONDS)
        }
    }

    @MainThread
    private fun requestLocationUpdates() {
        val interval = AppSettings.get(sharedPreferences, AppSettings.gpsUpdateInterval)
            .removeSuffix("ms").toLong()

        val locationRequest = LocationRequestCompat
            .Builder(interval)
            .setQuality(LocationRequestCompat.QUALITY_HIGH_ACCURACY).build()

        try {
            LocationManagerCompat.requestLocationUpdates(
                locationManager,
                LocationManager.GPS_PROVIDER,
                locationRequest,
                executor,
                locationChangeListener,
            )

            LocationManagerCompat.getCurrentLocation(
                locationManager,
                LocationManager.GPS_PROVIDER,
                getCurrentLocationCancellationSignal,
                executor
            ) {
                // Ignore returned location.
                // requestLocationUpdates() will not deliver any updates until
                // someone (can be other app) calls getCurrentLocation.
            }
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    @MainThread
    private fun stopLocationUpdates() {
        try {
            LocationManagerCompat.removeUpdates(locationManager, locationChangeListener)
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }
}
