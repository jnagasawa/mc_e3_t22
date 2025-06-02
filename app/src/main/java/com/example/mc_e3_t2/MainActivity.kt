package com.example.mc_e3_t2

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.mc_e3_t2.ui.theme.Mc_e3_t2Theme
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    
    // Permission request launcher for location permissions
    private val requestLocationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        
        if (allGranted) {
            // Check if we need background permission on Android 10+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.ACCESS_BACKGROUND_LOCATION
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    // Request background permission separately
                    requestBackgroundPermissionLauncher.launch(
                        Manifest.permission.ACCESS_BACKGROUND_LOCATION
                    )
                } else {
                    // All permissions including background are granted
                    Toast.makeText(
                        this,
                        "すべての権限が許可されました。トラッキングを開始できます。",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } else {
                // All permissions granted on pre-Android 10
                Toast.makeText(
                    this,
                    "位置情報の権限が許可されました。トラッキングを開始できます。",
                    Toast.LENGTH_LONG
                ).show()
            }
        } else {
            // Show message to user about permissions
            Toast.makeText(
                this,
                "位置情報の権限が必要です",
                Toast.LENGTH_LONG
            ).show()
        }
    }
    
    // Permission request launcher for background location (Android 10+)
    private val requestBackgroundPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Toast.makeText(
                this,
                "バックグラウンド位置情報の権限が許可されました",
                Toast.LENGTH_LONG
            ).show()
        } else {
            Toast.makeText(
                this,
                "バックグラウンドでの位置情報取得には、常に許可が必要です",
                Toast.LENGTH_LONG
            ).show()
        }
    }
    
    // State variables
    private var isTracking = mutableStateOf(false)
    private var currentLocation = mutableStateOf<Location?>(null)
    private var gpxFilePath = mutableStateOf<String>("")
    private val locationHistory = mutableStateListOf<Location>()
    
    // Broadcast receiver for location updates
    private val locationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == LocationService.ACTION_LOCATION_UPDATED) {
                val location = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(LocationService.EXTRA_LOCATION, Location::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(LocationService.EXTRA_LOCATION)
                }
                
                val filePath = intent.getStringExtra(LocationService.EXTRA_GPX_FILE_PATH)
                
                location?.let {
                    currentLocation.value = it
                    locationHistory.add(it)
                }
                
                filePath?.let {
                    gpxFilePath.value = it
                }
            }
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        // Register broadcast receiver
        val filter = IntentFilter(LocationService.ACTION_LOCATION_UPDATED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(locationReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(locationReceiver, filter)
        }
        
        setContent {
            Mc_e3_t2Theme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    GPSTrackerApp(
                        isTracking = isTracking.value,
                        currentLocation = currentLocation.value,
                        locationHistory = locationHistory.toList(),
                        gpxFilePath = gpxFilePath.value,
                        onStartTracking = { startLocationTracking() },
                        onStopTracking = { stopLocationTracking() },
                        onShareGpx = { shareGpxFile() },
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
        
        // Check permissions when app starts
        checkAndRequestPermissions()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Unregister receiver
        unregisterReceiver(locationReceiver)
    }
    
    private fun checkAndRequestPermissions() {
        // 基本的な位置情報の権限
        val locationPermissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        
        // ストレージの権限（Android 10未満の場合）
        val storagePermissions = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            arrayOf(
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.READ_EXTERNAL_STORAGE
            )
        } else {
            emptyArray()
        }
        
        // 必要な権限を組み合わせる
        val requiredPermissions = locationPermissions + storagePermissions
        
        // 許可されていない権限をフィルタリング
        val permissionsToRequest = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
        
        if (permissionsToRequest.isNotEmpty()) {
            // 権限をリクエスト
            requestLocationPermissionLauncher.launch(permissionsToRequest)
        } else {
            // 基本的な権限は既に許可されている
            // Android 10以降の場合、バックグラウンド位置情報の権限をチェック
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.ACCESS_BACKGROUND_LOCATION
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    // バックグラウンド位置情報の権限をリクエスト
                    requestBackgroundPermissionLauncher.launch(
                        Manifest.permission.ACCESS_BACKGROUND_LOCATION
                    )
                }
            }
        }
    }
    
    private fun startLocationTracking() {
        // 必要な権限が許可されているか確認
        val hasLocationPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        
        if (!hasLocationPermission) {
            // 権限がない場合は権限リクエスト
            checkAndRequestPermissions()
            return
        }
        
        val intent = Intent(this, LocationService::class.java).apply {
            action = LocationService.ACTION_START_TRACKING
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        
        isTracking.value = true
    }
    
    private fun stopLocationTracking() {
        val intent = Intent(this, LocationService::class.java).apply {
            action = LocationService.ACTION_STOP_TRACKING
        }
        startService(intent)
        
        isTracking.value = false
    }
    
    private fun shareGpxFile() {
        if (gpxFilePath.value.isNotEmpty()) {
            val gpxFile = File(gpxFilePath.value)
            if (gpxFile.exists()) {
                val uri = FileProvider.getUriForFile(
                    this,
                    "${applicationContext.packageName}.provider",
                    gpxFile
                )
                
                val shareIntent = Intent().apply {
                    action = Intent.ACTION_SEND
                    putExtra(Intent.EXTRA_STREAM, uri)
                    type = "application/gpx+xml"
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                
                startActivity(Intent.createChooser(shareIntent, "Share GPX File"))
            } else {
                Toast.makeText(this, "GPX file not found", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "No GPX file available", Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
fun GPSTrackerApp(
    isTracking: Boolean,
    currentLocation: Location?,
    locationHistory: List<Location>,
    gpxFilePath: String,
    onStartTracking: () -> Unit,
    onStopTracking: () -> Unit,
    onShareGpx: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Title
        Text(
            text = "GPS Tracker",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        
        // Current status card
        StatusCard(
            isTracking = isTracking,
            currentLocation = currentLocation,
            gpxFilePath = gpxFilePath
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Control buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(
                onClick = onStartTracking,
                enabled = !isTracking
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Start")
                Spacer(modifier = Modifier.width(4.dp))
                Text("Start Tracking")
            }
            
            Button(
                onClick = onStopTracking,
                enabled = isTracking
            ) {
                Icon(Icons.Default.Close, contentDescription = "Stop")
                Spacer(modifier = Modifier.width(4.dp))
                Text("Stop Tracking")
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // Share GPX button
        Button(
            onClick = onShareGpx,
            enabled = gpxFilePath.isNotEmpty()
        ) {
            Text("Share GPX File")
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Location history
        Text(
            text = "Location History",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        
        LocationHistoryList(locationHistory = locationHistory)
    }
}

@Composable
fun StatusCard(
    isTracking: Boolean,
    currentLocation: Location?,
    gpxFilePath: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Status: ${if (isTracking) "Tracking" else "Not Tracking"}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            if (currentLocation != null) {
                Text("Latitude: ${currentLocation.latitude}")
                Text("Longitude: ${currentLocation.longitude}")
                Text("Accuracy: ${currentLocation.accuracy} meters")
                if (currentLocation.hasAltitude()) {
                    Text("Altitude: ${currentLocation.altitude} meters")
                }
                if (currentLocation.hasSpeed()) {
                    Text("Speed: ${currentLocation.speed * 3.6} km/h") // Convert m/s to km/h
                }
            } else {
                Text("No location data available")
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            if (gpxFilePath.isNotEmpty()) {
                val filename = gpxFilePath.split("/").last()
                Text("Recording to: $filename")
            }
        }
    }
}

@Composable
fun LocationHistoryList(locationHistory: List<Location>) {
    if (locationHistory.isEmpty()) {
        Text(
            text = "No location data recorded yet",
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        )
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxWidth()
        ) {
            items(locationHistory) { location ->
                LocationItem(location = location)
                Divider()
            }
        }
    }
}

@Composable
fun LocationItem(location: Location) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp, horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.LocationOn,
            contentDescription = "Location",
            tint = MaterialTheme.colorScheme.primary
        )
        
        Spacer(modifier = Modifier.width(16.dp))
        
        Column {
            Text(
                text = "Lat: ${location.latitude}, Lon: ${location.longitude}",
                style = MaterialTheme.typography.bodyMedium
            )
            
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            val timeString = dateFormat.format(Date(location.time))
            Text(
                text = timeString,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
fun PermissionRequestComposable() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    
    // Remember the permission request state
    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasLocationPermission = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            }
        }
        
        lifecycleOwner.lifecycle.addObserver(observer)
        
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    
    if (!hasLocationPermission) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "Location permission is required for this app to work properly.",
                textAlign = TextAlign.Center
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Button(
                onClick = {
                    val intent = Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null)
                    )
                    context.startActivity(intent)
                }
            ) {
                Text("Open Settings")
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun GPSTrackerAppPreview() {
    Mc_e3_t2Theme {
        GPSTrackerApp(
            isTracking = false,
            currentLocation = null,
            locationHistory = emptyList(),
            gpxFilePath = "",
            onStartTracking = {},
            onStopTracking = {},
            onShareGpx = {},
            modifier = Modifier.padding(16.dp)
        )
    }
}