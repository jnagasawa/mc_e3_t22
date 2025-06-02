package com.example.mc_e3_t2

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Criteria
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A foreground service that tracks location and logs it to a GPX file.
 */
class LocationService : Service(), LocationListener {

    companion object {
        private const val TAG = "LocationService"
        private const val NOTIFICATION_ID = 12345
        private const val CHANNEL_ID = "location_channel"
        
        // Location update parameters
        private const val UPDATE_INTERVAL = 10000L // 10 seconds in milliseconds
        private const val MIN_DISTANCE = 5f // 5 meters
        
        // Intent actions
        const val ACTION_START_TRACKING = "com.example.mc_e3_t2.START_TRACKING"
        const val ACTION_STOP_TRACKING = "com.example.mc_e3_t2.STOP_TRACKING"
        
        // Broadcast action for location updates
        const val ACTION_LOCATION_UPDATED = "com.example.mc_e3_t2.LOCATION_UPDATED"
        const val EXTRA_LOCATION = "extra_location"
        const val EXTRA_GPX_FILE_PATH = "extra_gpx_file_path"
    }
    
    private lateinit var locationManager: LocationManager
    private var isTracking = false
    private var gpxFilePath: String = ""
    private var trackPoints = mutableListOf<Location>()
    
    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START_TRACKING && !isTracking) {
            startLocationTracking()
        } else if (intent?.action == ACTION_STOP_TRACKING) {
            stopLocationTracking()
        }
        
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
    
    override fun onDestroy() {
        stopLocationTracking()
        super.onDestroy()
    }
    
    private fun startLocationTracking() {
        // Create notification channel for Android O and above
        createNotificationChannel()
        
        // Start as a foreground service with notification
        startForeground(NOTIFICATION_ID, createNotification())
        
        // Initialize GPX file
        initializeGpxFile()
        
        // Request location updates
        requestLocationUpdates()
        
        isTracking = true
        Log.d(TAG, "Location tracking started")
    }
    
    private fun stopLocationTracking() {
        if (isTracking) {
            // Stop location updates
            try {
                locationManager.removeUpdates(this)
            } catch (e: SecurityException) {
                Log.e(TAG, "Error removing location updates", e)
            }
            
            // Finalize GPX file
            finalizeGpxFile()
            
            // Stop foreground service
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                stopForeground(Service.STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
            
            isTracking = false
            Log.d(TAG, "Location tracking stopped")
        }
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Location Tracking",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Used for tracking location in the background"
            }
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GPS Tracking Active")
            .setContentText("Recording your location track")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
    
    private fun requestLocationUpdates() {
        try {
            // Get the best provider based on criteria
            val criteria = Criteria().apply {
                accuracy = Criteria.ACCURACY_FINE
                powerRequirement = Criteria.POWER_HIGH
            }
            
            val provider = locationManager.getBestProvider(criteria, true) ?: LocationManager.GPS_PROVIDER
            
            // Request location updates
            locationManager.requestLocationUpdates(
                provider,
                UPDATE_INTERVAL,
                MIN_DISTANCE,
                this
            )
            
            // Get last known location if available
            locationManager.getLastKnownLocation(provider)?.let { location ->
                handleNewLocation(location)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission not granted", e)
        }
    }
    
    // LocationListener implementation
    override fun onLocationChanged(location: Location) {
        handleNewLocation(location)
    }
    
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
        // This method is required for older Android versions
    }
    
    @Deprecated("Deprecated in Java")
    override fun onProviderEnabled(provider: String) {
        Log.d(TAG, "Provider enabled: $provider")
    }
    
    @Deprecated("Deprecated in Java")
    override fun onProviderDisabled(provider: String) {
        Log.d(TAG, "Provider disabled: $provider")
    }
    
    private fun handleNewLocation(location: Location) {
        // Add location to track points list
        trackPoints.add(location)
        
        // Append to GPX file
        appendLocationToGpx(location)
        
        // Broadcast the location update
        val intent = Intent(ACTION_LOCATION_UPDATED).apply {
            putExtra(EXTRA_LOCATION, location)
            putExtra(EXTRA_GPX_FILE_PATH, gpxFilePath)
        }
        sendBroadcast(intent)
        
        Log.d(TAG, "New location: ${location.latitude}, ${location.longitude}")
    }
    
    private fun initializeGpxFile() {
        try {
            // Create a timestamp for the filename
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val filename = "track_$timestamp.gpx"
            
            // Get the directory for storing GPX files
            val directory = File(getExternalFilesDir(null), "tracks")
            if (!directory.exists()) {
                directory.mkdirs()
            }
            
            // Create the GPX file
            val gpxFile = File(directory, filename)
            gpxFilePath = gpxFile.absolutePath
            
            // Write GPX header
            FileOutputStream(gpxFile).use { fos ->
                val header = """<?xml version="1.0" encoding="UTF-8" standalone="no" ?>
<gpx xmlns="http://www.topografix.com/GPX/1/1" 
     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" 
     xsi:schemaLocation="http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd"
     version="1.1" 
     creator="GPS Tracker App">
  <metadata>
    <name>GPS Track ${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())}</name>
    <time>${SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date())}</time>
  </metadata>
  <trk>
    <name>Track ${timestamp}</name>
    <trkseg>
"""
                fos.write(header.toByteArray())
            }
            
            Log.d(TAG, "GPX file initialized at: $gpxFilePath")
        } catch (e: IOException) {
            Log.e(TAG, "Error initializing GPX file", e)
        }
    }
    
    private fun appendLocationToGpx(location: Location) {
        try {
            val gpxFile = File(gpxFilePath)
            if (!gpxFile.exists()) {
                Log.e(TAG, "GPX file does not exist")
                return
            }
            
            // Create trackpoint entry
            val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date(location.time))
            val trackpoint = """    <trkpt lat="${location.latitude}" lon="${location.longitude}">
      <ele>${if (location.hasAltitude()) location.altitude else 0.0}</ele>
      <time>$timestamp</time>
      <speed>${if (location.hasSpeed()) location.speed else 0.0}</speed>
    </trkpt>
"""
            
            // Append to file
            FileOutputStream(gpxFile, true).use { fos ->
                fos.write(trackpoint.toByteArray())
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error appending to GPX file", e)
        }
    }
    
    private fun finalizeGpxFile() {
        try {
            val gpxFile = File(gpxFilePath)
            if (!gpxFile.exists()) {
                Log.e(TAG, "GPX file does not exist")
                return
            }
            
            // Write GPX footer
            val footer = """    </trkseg>
  </trk>
</gpx>
"""
            
            // Append to file
            FileOutputStream(gpxFile, true).use { fos ->
                fos.write(footer.toByteArray())
            }
            
            Log.d(TAG, "GPX file finalized: $gpxFilePath")
        } catch (e: IOException) {
            Log.e(TAG, "Error finalizing GPX file", e)
        }
    }
}
