package com.example.audio_share

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.text.format.Formatter
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.Collections

class MainActivity : AppCompatActivity() {
    private lateinit var btnHost: Button
    private lateinit var btnClient: Button
    private lateinit var tvIpAddress: TextView
    private lateinit var etTargetIp: EditText
    private lateinit var btnSystemAudio: Button

    private var isHosting = false
    private var isClientRunning = false
    private var isSystemAudioMode = false
    private var hostSocket: DatagramSocket? = null
    private var clientSocket: DatagramSocket? = null
    private var audioTrack: AudioTrack? = null
    private var audioRecord: AudioRecord? = null
    private var hostThread: Thread? = null
    private var clientThread: Thread? = null

    // MediaProjection for system audio capture
    private var mediaProjection: MediaProjection? = null
    private var mediaProjectionManager: MediaProjectionManager? = null

    companion object {
        private const val TAG = "AudioShare"
        private const val PERMISSION_REQUEST_CODE = 1001
        private const val MEDIA_PROJECTION_REQUEST_CODE = 1002
        private const val SAMPLE_RATE = 44100
        // For widest compatibility, you can switch to mono
        private const val CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_STEREO
        private const val CHANNEL_CONFIG_OUT = AudioFormat.CHANNEL_OUT_STEREO
        // To use mono, uncomment below and comment above:
        // private const val CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_MONO
        // private const val CHANNEL_CONFIG_OUT = AudioFormat.CHANNEL_OUT_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private val BUFFER_SIZE = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            CHANNEL_CONFIG_IN,
            AUDIO_FORMAT
        ).coerceAtLeast(2048)
        private const val PORT = 5000
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initializeViews()
        setupClickListeners()
        displayIpAddress()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        }
    }

    private fun initializeViews() {
        btnHost = findViewById(R.id.btnHost)
        btnClient = findViewById(R.id.btnClient)
        tvIpAddress = findViewById(R.id.tvIpAddress)
        etTargetIp = findViewById(R.id.etTargetIp)
        btnSystemAudio = findViewById(R.id.btnSystemAudio)
    }

    private fun setupClickListeners() {
        btnHost.setOnClickListener {
            if (!isHosting) {
                if (isSystemAudioMode) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        requestMediaProjection()
                    } else {
                        Toast.makeText(this, "System audio capture requires Android 10+", Toast.LENGTH_LONG).show()
                    }
                } else {
                    if (checkPermissions()) {
                        startHost()
                    } else {
                        requestPermissions()
                    }
                }
            } else {
                stopHost()
            }
        }

        btnClient.setOnClickListener {
            if (!isClientRunning) {
                val targetIp = etTargetIp.text.toString().trim()
                if (targetIp.isNotEmpty()) {
                    startClient(targetIp)
                } else {
                    Toast.makeText(this, "Please enter target IP address", Toast.LENGTH_SHORT).show()
                }
            } else {
                stopClient()
            }
        }

        btnSystemAudio.setOnClickListener {
            isSystemAudioMode = !isSystemAudioMode
            updateSystemAudioButton()
            Toast.makeText(
                this,
                if (isSystemAudioMode) "System audio mode enabled" else "Microphone mode enabled",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun updateSystemAudioButton() {
        btnSystemAudio.text = if (isSystemAudioMode) "Mode: System Audio" else "Mode: Microphone"
        btnSystemAudio.setBackgroundColor(
            if (isSystemAudioMode)
                ContextCompat.getColor(this, android.R.color.holo_green_light)
            else
                ContextCompat.getColor(this, android.R.color.holo_blue_light)
        )
    }

    private fun displayIpAddress() {
        val ipAddress = getDeviceIpAddress()
        tvIpAddress.text = "Your IP: $ipAddress"
    }

    private fun getDeviceIpAddress(): String {
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_WIFI_STATE)
                == PackageManager.PERMISSION_GRANTED
            ) {
                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val wifiIp = Formatter.formatIpAddress(wifiManager.connectionInfo.ipAddress)
                if (wifiIp != "0.0.0.0") {
                    return wifiIp
                }
            }
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (networkInterface in interfaces) {
                val addresses = Collections.list(networkInterface.inetAddresses)
                for (address in addresses) {
                    if (!address.isLoopbackAddress && address.hostAddress?.contains(':') == false) {
                        return address.hostAddress ?: "Unknown"
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing required permission for getting IP address", e)
            runOnUiThread {
                Toast.makeText(this, "Permission denied for accessing IP address", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting IP address", e)
        }
        return "Unable to get IP"
    }

    private fun checkPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.RECORD_AUDIO),
            PERMISSION_REQUEST_CODE
        )
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private fun requestMediaProjection() {
        mediaProjectionManager?.let {
            startActivityForResult(it.createScreenCaptureIntent(), MEDIA_PROJECTION_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startHost()
            } else {
                Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == MEDIA_PROJECTION_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, data)
                startHost()
            } else {
                Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startHost() {
        hostThread = Thread {
            try {
                isHosting = true
                updateUiState()

                val minBufferSize = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    CHANNEL_CONFIG_IN,
                    AUDIO_FORMAT
                )

                audioRecord = try {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        Log.e(TAG, "RECORD_AUDIO permission not granted!")
                        runOnUiThread {
                            Toast.makeText(this, "Microphone permission required", Toast.LENGTH_LONG).show()
                        }
                        null
                    } else if (isSystemAudioMode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        createSystemAudioRecord(minBufferSize)
                    } else {
                        AudioRecord(
                            MediaRecorder.AudioSource.MIC,
                            SAMPLE_RATE,
                            CHANNEL_CONFIG_IN,
                            AUDIO_FORMAT,
                            minBufferSize
                        )
                    }
                } catch (e: SecurityException) {
                    Log.e(TAG, "SecurityException: Missing RECORD_AUDIO permission", e)
                    runOnUiThread {
                        Toast.makeText(this, "Permission denied for audio recording", Toast.LENGTH_LONG).show()
                    }
                    null
                } catch (e: Exception) {
                    Log.e(TAG, "Error creating AudioRecord", e)
                    runOnUiThread {
                        Toast.makeText(this, "Failed to create AudioRecord: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                    null
                }

                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    runOnUiThread {
                        Toast.makeText(this, "Failed to initialize audio recording", Toast.LENGTH_LONG).show()
                    }
                    return@Thread
                }

                hostSocket = DatagramSocket().apply {
                    broadcast = true
                }

                safeAudioRecordStart()

                val buffer = ByteArray(BUFFER_SIZE)
                val broadcastAddress = getBroadcastAddress()

                runOnUiThread {
                    Toast.makeText(
                        this,
                        if (isSystemAudioMode) "Broadcasting system audio..." else "Broadcasting microphone...",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                while (isHosting) {
                    try {
                        val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                        Log.d(TAG, "Audio bytes read: $bytesRead")
                        if (bytesRead > 0) {
                            val packet = DatagramPacket(buffer, bytesRead, broadcastAddress, PORT)
                            hostSocket?.send(packet)
                            Log.d(TAG, "Sent audio packet of $bytesRead bytes to $broadcastAddress:$PORT")
                        }
                    } catch (e: Exception) {
                        if (isHosting) {
                            Log.e(TAG, "Error in host thread", e)
                        }
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Host error", e)
                runOnUiThread {
                    Toast.makeText(this, "Host error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                stopHost()
            }
        }.apply { start() }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun createSystemAudioRecord(bufferSize: Int): AudioRecord? {
        // Check RECORD_AUDIO permission before proceeding
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "RECORD_AUDIO permission not granted for system audio capture")
            runOnUiThread {
                Toast.makeText(this, "Permission required to capture system audio", Toast.LENGTH_LONG).show()
            }
            return null
        }

        return try {
            mediaProjection?.let {
                val config = AudioPlaybackCaptureConfiguration.Builder(it)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()

                AudioRecord.Builder()
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AUDIO_FORMAT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(CHANNEL_CONFIG_IN)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .setAudioPlaybackCaptureConfig(config)
                    .build()
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException: Missing permission for system audio capture", e)
            runOnUiThread {
                Toast.makeText(this, "Permission denied for system audio capture", Toast.LENGTH_LONG).show()
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error creating system AudioRecord", e)
            runOnUiThread {
                Toast.makeText(this, "Failed to create system AudioRecord: ${e.message}", Toast.LENGTH_LONG).show()
            }
            null
        }
    }

    private fun getBroadcastAddress(): InetAddress {
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_WIFI_STATE)
                == PackageManager.PERMISSION_GRANTED
            ) {
                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val dhcp = wifiManager.dhcpInfo
                val broadcast = (dhcp.ipAddress and dhcp.netmask) or dhcp.netmask.inv()
                return InetAddress.getByName(
                    "${broadcast and 0xFF}.${broadcast shr 8 and 0xFF}." +
                            "${broadcast shr 16 and 0xFF}.${broadcast shr 24 and 0xFF}"
                )
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing required permission for getting broadcast address", e)
            runOnUiThread {
                Toast.makeText(this, "Permission denied for accessing broadcast address", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting broadcast address", e)
        }
        // Fallback to default broadcast address
        return InetAddress.getByName("255.255.255.255")
    }

    private fun safeAudioRecordStart() {
        try {
            audioRecord?.startRecording()
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord start failed", e)
            runOnUiThread {
                Toast.makeText(this, "Audio recording start failed", Toast.LENGTH_LONG).show()
            }
            stopHost()
        }
    }

    private fun stopHost() {
        isHosting = false

        hostThread?.interrupt()
        hostThread = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping audio record", e)
        }
        audioRecord = null

        try {
            hostSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing host socket", e)
        }
        hostSocket = null

        mediaProjection?.stop()
        mediaProjection = null

        updateUiState()
    }

    private fun startClient(targetIp: String) {
        clientThread = Thread {
            try {
                isClientRunning = true
                updateUiState()

                val socket = DatagramSocket(PORT).apply {
                    soTimeout = 1000
                    broadcast = true
                    receiveBufferSize = BUFFER_SIZE * 4
                }
                clientSocket = socket

                val bufferSize = AudioTrack.getMinBufferSize(
                    SAMPLE_RATE,
                    CHANNEL_CONFIG_OUT,
                    AUDIO_FORMAT
                ).coerceAtLeast(BUFFER_SIZE * 2)

                Log.d(TAG, "Client buffer size: $bufferSize")

                audioTrack = AudioTrack(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(AUDIO_FORMAT)
                        .setChannelMask(CHANNEL_CONFIG_OUT)
                        .build(),
                    bufferSize * 4,
                    AudioTrack.MODE_STREAM,
                    AudioManager.AUDIO_SESSION_ID_GENERATE
                ).apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        setVolume(AudioTrack.getMaxVolume())
                    }
                    play()
                }

                // Verify AudioTrack state
                if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                    throw IllegalStateException("AudioTrack initialization failed. State: ${audioTrack?.state}")
                }

                Log.d(TAG, "AudioTrack started playing. PlayState: ${audioTrack?.playState}")

                val buffer = ByteArray(BUFFER_SIZE)
                var consecutiveErrors = 0
                val maxConsecutiveErrors = 5

                while (isClientRunning) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        Log.d(TAG, "Received packet - Size: ${packet.length}, First byte: ${if (packet.data.isNotEmpty()) packet.data[0] else "none"}")
                        if (packet.length > 0) {
                            // Check if AudioTrack is still playing
                            if (audioTrack?.playState != AudioTrack.PLAYSTATE_PLAYING) {
                                Log.w(TAG, "AudioTrack stopped playing. Restarting...")
                                audioTrack?.play()
                            }
                            val written = audioTrack?.write(packet.data, 0, packet.length)
                            Log.d(TAG, "Written to AudioTrack: $written bytes")
                            when (written) {
                                AudioTrack.ERROR_BAD_VALUE -> throw IllegalArgumentException("Write failed - Bad Value")
                                AudioTrack.ERROR_INVALID_OPERATION -> throw IllegalStateException("Write failed - Invalid Operation")
                                AudioTrack.ERROR_DEAD_OBJECT -> throw IllegalStateException("Write failed - Dead Object")
                                else -> {
                                    if (written != null && written >= 0) {
                                        consecutiveErrors = 0  // Reset error counter on success
                                    }
                                }
                            }
                        }
                    } catch (e: SocketTimeoutException) {
                        Log.w(TAG, "Socket timeout - no data received")
                        continue
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing audio packet", e)
                        consecutiveErrors++
                        if (consecutiveErrors >= maxConsecutiveErrors) {
                            Log.e(TAG, "Too many consecutive errors, resetting audio track")
                            resetAudioTrack()
                            consecutiveErrors = 0
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Fatal client error", e)
                runOnUiThread {
                    Toast.makeText(this, "Client error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                stopClient()
            }
        }.apply { start() }
    }

    private fun resetAudioTrack() {
        try {
            audioTrack?.apply {
                stop()
                flush()
                play()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error resetting AudioTrack", e)
        }
    }

    private fun stopClient() {
        isClientRunning = false

        clientThread?.interrupt()
        clientThread = null

        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping audio track", e)
        }
        audioTrack = null

        try {
            clientSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing client socket", e)
        }
        clientSocket = null

        updateUiState()
    }

    private fun updateUiState() {
        runOnUiThread {
            btnHost.text = if (isHosting) "Stop Host" else "Start Host"
            btnClient.text = if (isClientRunning) "Stop Client" else "Start Client"
            etTargetIp.isEnabled = !isClientRunning
            btnSystemAudio.isEnabled = !isHosting && !isClientRunning
        }
    }

    override fun onPause() {
        super.onPause()
        if (isHosting || isClientRunning) {
            Toast.makeText(this, "Audio streaming in background", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        stopHost()
        stopClient()
        mediaProjection?.stop()
        mediaProjection = null
        super.onDestroy()
    }
}