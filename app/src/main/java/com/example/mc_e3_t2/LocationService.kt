package com.example.mc_e3_t2

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Criteria
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
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
    private var currentProvider: String? = null

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_TRACKING -> {
                if (!isTracking) {
                    startLocationTracking()
                }
            }
            ACTION_STOP_TRACKING -> {
                stopLocationTracking()
            }
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
        // Check permissions first
        if (!hasLocationPermissions()) {
            Log.e(TAG, "Location permissions not granted")
            // Send a broadcast to notify about permission issue
            val intent = Intent("com.example.mc_e3_t2.LOCATION_ERROR").apply {
                putExtra("error", "Location permissions not granted")
                setPackage(packageName)
            }
            sendBroadcast(intent)
            stopSelf()
            return
        }

        // Create notification channel for Android O and above
        createNotificationChannel()

        // Start as a foreground service with notification
        startForeground(NOTIFICATION_ID, createNotification())

        // Initialize GPX file
        initializeGpxFile()

        // Request location updates
        if (requestLocationUpdates()) {
            isTracking = true
            Log.d(TAG, "Location tracking started successfully")

            // Send initial broadcast to confirm tracking started
            val intent = Intent("com.example.mc_e3_t2.TRACKING_STARTED").apply {
                putExtra("status", "started")
                setPackage(packageName)
            }
            sendBroadcast(intent)
        } else {
            Log.e(TAG, "Failed to start location tracking")
            // Send error broadcast
            val intent = Intent("com.example.mc_e3_t2.LOCATION_ERROR").apply {
                putExtra("error", "Failed to start location tracking")
                setPackage(packageName)
            }
            sendBroadcast(intent)
            stopSelf()
        }
    }

    private fun stopLocationTracking() {
        if (isTracking) {
            // Stop location updates
            try {
                locationManager.removeUpdates(this)
                Log.d(TAG, "Location updates removed")
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

            isTracking = false
            currentProvider = null
            Log.d(TAG, "Location tracking stopped")
        }

        stopSelf()
    }

    private fun hasLocationPermissions(): Boolean {
        val fineLocationGranted = ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val coarseLocationGranted = ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        return fineLocationGranted || coarseLocationGranted
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Location Tracking",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Used for tracking location in the background"
                setShowBadge(false)
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
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun requestLocationUpdates(): Boolean {
        try {
            // Check if location services are enabled
            val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            val isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

            Log.d(TAG, "GPS enabled: $isGpsEnabled, Network enabled: $isNetworkEnabled")

            if (!isGpsEnabled && !isNetworkEnabled) {
                Log.e(TAG, "No location providers are enabled")
                return false
            }

            // Start with multiple providers if available for better coverage
            var successCount = 0

            // Try GPS first (most accurate)
            if (isGpsEnabled) {
                try {
                    locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        UPDATE_INTERVAL,
                        MIN_DISTANCE,
                        this
                    )
                    currentProvider = LocationManager.GPS_PROVIDER
                    successCount++
                    Log.d(TAG, "GPS provider registered successfully")

                    // Get last known GPS location
                    locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { location ->
                        Log.d(TAG, "Got last known GPS location: ${location.latitude}, ${location.longitude}")
                        handleNewLocation(location)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error registering GPS provider", e)
                }
            }

            // Also try Network provider for faster initial fix
            if (isNetworkEnabled) {
                try {
                    locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        UPDATE_INTERVAL / 2, // More frequent for network
                        MIN_DISTANCE,
                        this
                    )
                    if (currentProvider == null) {
                        currentProvider = LocationManager.NETWORK_PROVIDER
                    }
                    successCount++
                    Log.d(TAG, "Network provider registered successfully")

                    // Get last known network location
                    locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)?.let { location ->
                        Log.d(TAG, "Got last known Network location: ${location.latitude}, ${location.longitude}")
                        handleNewLocation(location)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error registering Network provider", e)
                }
            }

            if (successCount == 0) {
                Log.e(TAG, "No location providers could be registered")
                return false
            }

            Log.d(TAG, "Successfully registered $successCount location providers")
            return true

        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission not granted", e)
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting location updates", e)
            return false
        }
    }

    // LocationListener implementation
    override fun onLocationChanged(location: Location) {
        Log.d(TAG, "onLocationChanged called - Provider: ${location.provider}, Lat: ${location.latitude}, Lon: ${location.longitude}, Accuracy: ${location.accuracy}")
        handleNewLocation(location)
    }

    override fun onProviderEnabled(provider: String) {
        Log.d(TAG, "Provider enabled: $provider")
        // If our current provider was disabled and now enabled, we might want to switch
        if (provider == LocationManager.GPS_PROVIDER && currentProvider != LocationManager.GPS_PROVIDER) {
            // GPS is usually more accurate, so switch to it if available
            switchToProvider(provider)
        }
    }

    override fun onProviderDisabled(provider: String) {
        Log.d(TAG, "Provider disabled: $provider")
        if (provider == currentProvider) {
            // Our current provider was disabled, try to find another
            findAlternativeProvider()
        }
    }

    @Deprecated("Deprecated in API level 29")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
        Log.d(TAG, "Provider status changed: $provider, status: $status")
    }

    private fun switchToProvider(newProvider: String) {
        try {
            // Remove updates from current provider
            locationManager.removeUpdates(this)

            // Request updates from new provider
            locationManager.requestLocationUpdates(
                newProvider,
                UPDATE_INTERVAL,
                MIN_DISTANCE,
                this
            )

            currentProvider = newProvider
            Log.d(TAG, "Switched to provider: $newProvider")

        } catch (e: SecurityException) {
            Log.e(TAG, "Error switching provider", e)
        }
    }

    private fun findAlternativeProvider() {
        try {
            val availableProviders = locationManager.getProviders(true)
            Log.d(TAG, "Available providers: $availableProviders")

            val alternativeProvider = when {
                availableProviders.contains(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                availableProviders.contains(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                availableProviders.contains(LocationManager.PASSIVE_PROVIDER) -> LocationManager.PASSIVE_PROVIDER
                else -> null
            }

            if (alternativeProvider != null && alternativeProvider != currentProvider) {
                switchToProvider(alternativeProvider)
            } else {
                Log.e(TAG, "No alternative location provider available")
                stopLocationTracking()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error finding alternative provider", e)
        }
    }

    private fun handleNewLocation(location: Location) {
        // Validate location
        if (!isValidLocation(location)) {
            Log.w(TAG, "Invalid location received, ignoring")
            return
        }

        // Add location to track points list
        trackPoints.add(location)

        // Append to GPX file
        appendLocationToGpx(location)

        // Broadcast the location update with explicit package
        val intent = Intent(ACTION_LOCATION_UPDATED).apply {
            putExtra(EXTRA_LOCATION, location)
            putExtra(EXTRA_GPX_FILE_PATH, gpxFilePath)
            // Add package to ensure broadcast is delivered
            setPackage(packageName)
            // Add flags for better delivery
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        }

        try {
            sendBroadcast(intent)
            Log.d(TAG, "Broadcast sent for location: ${location.latitude}, ${location.longitude}")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending broadcast", e)
        }

        Log.d(TAG, "New location processed: ${location.latitude}, ${location.longitude}, accuracy: ${location.accuracy}m")
    }

    private fun isValidLocation(location: Location): Boolean {
        // Basic validation
        if (location.latitude == 0.0 && location.longitude == 0.0) {
            return false
        }

        // Check if accuracy is reasonable (less than 100 meters for most use cases)
        if (location.hasAccuracy() && location.accuracy > 100) {
            Log.w(TAG, "Location accuracy is poor: ${location.accuracy}m")
            // Still return true, but log the warning
        }

        return true
    }

    private fun initializeGpxFile() {
        try {
            // Create a timestamp for the filename
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val filename = "track_$timestamp.gpx"

            // Get the directory for storing GPX files
            val directory = File(getExternalFilesDir(null), "tracks")
            if (!directory.exists()) {
                val created = directory.mkdirs()
                Log.d(TAG, "Tracks directory created: $created")
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
            val trackpoint = """      <trkpt lat="${location.latitude}" lon="${location.longitude}">
        <ele>${if (location.hasAltitude()) location.altitude else 0.0}</ele>
        <time>$timestamp</time>
        ${if (location.hasSpeed()) "<speed>${location.speed}</speed>" else ""}
        ${if (location.hasAccuracy()) "<hdop>${location.accuracy}</hdop>" else ""}
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
            if (gpxFilePath.isEmpty()) {
                Log.w(TAG, "No GPX file path to finalize")
                return
            }

            val gpxFile = File(gpxFilePath)
            if (!gpxFile.exists()) {
                Log.e(TAG, "GPX file does not exist: $gpxFilePath")
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