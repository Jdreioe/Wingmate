package io.github.jdreioe.wingmate.puck

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.ParcelUuid
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import io.github.jdreioe.wingmate.MainActivity
import io.github.jdreioe.wingmate.domain.PhraseRepository
import io.github.jdreioe.wingmate.domain.SpeechService
import kotlinx.coroutines.*
import org.json.JSONObject
import org.koin.core.context.GlobalContext
import java.util.UUID
import android.media.AudioTrack
import android.media.AudioFormat
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import kotlin.math.sin

/**
 * Android Foreground Service that connects to a Puck.js over BLE (Nordic UART),
 * receives button press events + accelerometer data, estimates movement intensity,
 * and triggers phrase playback via the existing SpeechService.
 *
 * The Puck.js sends: \nSTART{"history":[{x,y,z},...], "press":"short"|"double"|"triple"|"long", "angle":N}END\n
 */
class PuckJsService : Service() {

    companion object {
        private const val TAG = "PuckJsService"
        private const val CHANNEL_ID = "puck_js_channel"
        private const val NOTIFICATION_ID = 42

        // Nordic UART Service UUIDs
        private val UART_SERVICE_UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        private val UART_TX_CHAR_UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e") // Notifications FROM Puck
        private val CLIENT_CONFIG_DESC = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // Movement threshold: variance of accelerometer magnitude above this = "moving fast"
        // Tuned for wheelchair use — values below this are stationary or very slow.
        private const val MOVEMENT_VARIANCE_THRESHOLD = 15000.0

        /** Whether the service is currently running (observable from UI). */
        @Volatile
        var isRunning = false
            private set
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var bluetoothGatt: BluetoothGatt? = null
    private var scanning = false

    // Buffer for incoming BLE data (may arrive in chunks)
    private val rxBuffer = StringBuilder()

    // Keep-alive audio
    private var keepAliveTrack: AudioTrack? = null
    private var isKeepAliveRunning = false
    private var isPuckConnected = false

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // Re-evaluate keep-alive when Bluetooth devices change
            updateKeepAlive()
        }
    }

    // Resolve via Koin
    private val phraseRepository: PhraseRepository by lazy { GlobalContext.get().get() }
    private val speechService: SpeechService by lazy { GlobalContext.get().get() }
    private val settingsRepository: io.github.jdreioe.wingmate.domain.SettingsRepository by lazy { GlobalContext.get().get() }
    private val voiceRepository: io.github.jdreioe.wingmate.domain.VoiceRepository by lazy { GlobalContext.get().get() }

    // ─────────────── Lifecycle ───────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        isRunning = true
        
        val filter = IntentFilter().apply {
            addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        }
        registerReceiver(bluetoothReceiver, filter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification("Scanning for Puck.js…")
        startForeground(NOTIFICATION_ID, notification)
        startScan()
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        stopScan()
        disconnectGatt()
        stopKeepAlive()
        try { unregisterReceiver(bluetoothReceiver) } catch (_: Exception) {}
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─────────────── Notifications ───────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Puck.js Connection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows Puck.js BLE connection status"
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Wingmate Puck.js")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    // ─────────────── BLE Scanning ───────────────

    private fun startScan() {
        if (scanning) return
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: run {
            Log.e(TAG, "Bluetooth not available")
            stopSelf()
            return
        }
        if (!adapter.isEnabled) {
            Log.e(TAG, "Bluetooth not enabled")
            stopSelf()
            return
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Missing BLUETOOTH_SCAN permission")
            stopSelf()
            return
        }

        val scanner = adapter.bluetoothLeScanner ?: run {
            Log.e(TAG, "BLE scanner unavailable")
            stopSelf()
            return
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(null, settings, scanCallback)
        scanning = true
        Log.d(TAG, "BLE scan started")
    }

    private fun stopScan() {
        if (!scanning) return
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) return
        adapter.bluetoothLeScanner?.stopScan(scanCallback)
        scanning = false
        Log.d(TAG, "BLE scan stopped")
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            if (ActivityCompat.checkSelfPermission(this@PuckJsService, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
            
            val deviceName = device.name ?: result.scanRecord?.deviceName ?: ""
            if (deviceName.contains("Puck.js", ignoreCase = true)) {
                Log.d(TAG, "Found device: $deviceName (${device.address})")
                stopScan()
                connectToDevice(device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE scan failed with error: $errorCode")
        }
    }

    // ─────────────── BLE Connection ───────────────

    private fun connectToDevice(device: BluetoothDevice) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        updateNotification("Connecting to ${device.name ?: device.address}…")
        bluetoothGatt = device.connectGatt(this, true, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun disconnectGatt() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (ActivityCompat.checkSelfPermission(this@PuckJsService, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "Connected to GATT server")
                    updateNotification("Connected to Puck.js")
                    isPuckConnected = true
                    updateKeepAlive()
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Disconnected from GATT server")
                    updateNotification("Disconnected — reconnecting…")
                    isPuckConnected = false
                    updateKeepAlive()
                    bluetoothGatt?.close()
                    bluetoothGatt = null
                    // Auto-reconnect after a short delay
                    scope.launch {
                        delay(3000)
                        startScan()
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed: $status")
                return
            }
            val uartService = gatt.getService(UART_SERVICE_UUID)
            if (uartService == null) {
                Log.e(TAG, "Nordic UART service not found")
                return
            }
            val txChar = uartService.getCharacteristic(UART_TX_CHAR_UUID)
            if (txChar == null) {
                Log.e(TAG, "TX characteristic not found")
                return
            }
            if (ActivityCompat.checkSelfPermission(this@PuckJsService, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
            gatt.setCharacteristicNotification(txChar, true)
            val desc = txChar.getDescriptor(CLIENT_CONFIG_DESC)
            if (desc != null) {
                desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(desc)
            }
            Log.d(TAG, "Subscribed to UART TX notifications")
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == UART_TX_CHAR_UUID) {
                val chunk = characteristic.getStringValue(0)
                rxBuffer.append(chunk)
                processBuffer()
            }
        }
    }

    // ─────────────── Data Parsing ───────────────

    /**
     * Extract complete messages framed as START{json}END from the RX buffer.
     */
    private fun processBuffer() {
        while (true) {
            val raw = rxBuffer.toString()
            val startIdx = raw.indexOf("START")
            if (startIdx < 0) {
                // No START marker yet — clear garbage before it would appear
                rxBuffer.clear()
                return
            }
            val endIdx = raw.indexOf("END", startIdx + 5)
            if (endIdx < 0) {
                // Incomplete message — keep what we have from START onward
                if (startIdx > 0) {
                    rxBuffer.delete(0, startIdx)
                }
                return
            }
            val jsonStr = raw.substring(startIdx + 5, endIdx)
            // Consume the processed portion
            rxBuffer.delete(0, endIdx + 3)

            try {
                val json = JSONObject(jsonStr)
                val pressType = json.getString("press")
                val historyArr = json.getJSONArray("history")

                // Collect accel magnitudes for speed estimation
                val magnitudes = mutableListOf<Double>()
                for (i in 0 until historyArr.length()) {
                    val sample = historyArr.getJSONObject(i)
                    val x = sample.getDouble("x")
                    val y = sample.getDouble("y")
                    val z = sample.getDouble("z")
                    magnitudes.add(Math.sqrt(x * x + y * y + z * z))
                }

                val isMovingFast = isAboveSpeedThreshold(magnitudes)
                Log.d(TAG, "Press=$pressType movingFast=$isMovingFast magnitudes=$magnitudes")

                handlePress(pressType, isMovingFast)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse Puck.js JSON: $jsonStr", e)
            }
        }
    }

    // ─────────────── Speed Estimation ───────────────

    /**
     * Estimate whether the wheelchair is moving above ~5 km/h by looking at
     * variance in the accelerometer magnitude.  When stationary the magnitude
     * is ~1 g with very low variance.  Movement introduces vibration/change
     * that increases variance.
     */
    private fun isAboveSpeedThreshold(magnitudes: List<Double>): Boolean {
        if (magnitudes.size < 2) return false
        val mean = magnitudes.average()
        val variance = magnitudes.sumOf { (it - mean) * (it - mean) } / magnitudes.size
        
        val threshold = runCatching { 
            runBlocking { settingsRepository.get().puckSpeedThreshold }
        }.getOrDefault(MOVEMENT_VARIANCE_THRESHOLD)
        
        return variance > threshold
    }

    // ─────────────── Action Handling ───────────────

    private fun handlePress(pressType: String, isMovingFast: Boolean) {
        scope.launch {
            try {
                val actionKey = if (isMovingFast) "${pressType}_fast" else pressType
                val phrases = phraseRepository.getAll()
                
                // Try specific speed action first, then fall back to generic if not found
                var mapped = phrases.firstOrNull { it.puckAction == actionKey }
                if (mapped == null && isMovingFast) {
                    mapped = phrases.firstOrNull { it.puckAction == pressType }
                }

                if (mapped == null) {
                    Log.d(TAG, "No phrase mapped to action: $actionKey (nor fallback to $pressType)")
                    return@launch
                }

                // Determine what to speak: vocalization (name) takes precedence, then text
                val textToSpeak = mapped.name ?: mapped.text
                Log.d(TAG, "Speaking phrase for $actionKey (isMovingFast=$isMovingFast): $textToSpeak")

                // Fetch current user settings for voice and rate
                val settings = settingsRepository.get()
                val selectedVoice = voiceRepository.getSelected()

                speechService.speak(
                    text = textToSpeak,
                    voice = selectedVoice,
                    pitch = null, // Settings doesn't have pitch yet, it's usually inside voice if present
                    rate = settings.speechRate.toDouble()
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error handling press event", e)
            }
        }
    }

    // ─────────────── Keep-Alive Audio ───────────────

    private fun updateKeepAlive() {
        val shouldRun = isPuckConnected && isBluetoothAudioConnected()
        if (shouldRun && !isKeepAliveRunning) {
            startKeepAlive()
        } else if (!shouldRun && isKeepAliveRunning) {
            stopKeepAlive()
        }
    }

    private fun isBluetoothAudioConnected(): Boolean {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val devices = audioManager.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
        return devices.any { 
            it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || 
            it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO 
        }
    }

    private fun startKeepAlive() {
        if (isKeepAliveRunning) return
        Log.d(TAG, "Starting Bluetooth keep-alive audio")
        isKeepAliveRunning = true
        
        scope.launch(Dispatchers.Default) {
            val sampleRate = 44100
            val bufferSize = AudioTrack.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            
            val track = AudioTrack.Builder()
                .setAudioAttributes(android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build())
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            
            keepAliveTrack = track
            track.play()
            
            // Ultra low volume 20Hz sine wave (basically inaudible but keeps the data stream active)
            val samples = ShortArray(bufferSize)
            var angle = 0.0
            val increment = 2.0 * Math.PI * 20.0 / sampleRate
            val amplitude = 327.0 // ~1% of max volume
            
            while (isKeepAliveRunning) {
                for (i in samples.indices) {
                    samples[i] = (sin(angle) * amplitude).toInt().toShort()
                    angle += increment
                }
                track.write(samples, 0, samples.size)
                yield() // Cooperate
            }
            
            track.stop()
            track.release()
            keepAliveTrack = null
        }
    }

    private fun stopKeepAlive() {
        if (!isKeepAliveRunning) return
        Log.d(TAG, "Stopping Bluetooth keep-alive audio")
        isKeepAliveRunning = false
        keepAliveTrack?.stop()
    }
}
