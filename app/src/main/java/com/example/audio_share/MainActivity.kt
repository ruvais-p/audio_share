package com.example.audio_share

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class MainActivity : AppCompatActivity() {
    private lateinit var btnHost: Button
    private lateinit var btnClient: Button
    private var isHosting = false
    private var isClientRunning = false
    private var hostSocket: DatagramSocket? = null
    private var clientSocket: DatagramSocket? = null
    private var audioTrack: AudioTrack? = null
    private var audioRecord: AudioRecord? = null

    companion object {
        private const val TAG = "AudioShare"
        private const val PERMISSION_REQUEST_CODE = 1001
        private const val SAMPLE_RATE = 44100
        private const val CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_MONO
        private const val CHANNEL_CONFIG_OUT = AudioFormat.CHANNEL_OUT_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_SIZE = 1024
        private const val PORT = 5000
        private const val HOST_IP = "10.0.2.2" // Use this for emulator networking
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            Log.d(TAG, "onCreate called")
            setContentView(R.layout.activity_main)

            initializeViews()
            setupClickListeners()

            Log.d(TAG, "MainActivity initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate: ${e.message}", e)
            Toast.makeText(this, "Error initializing app: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun initializeViews() {
        try {
            btnHost = findViewById(R.id.btnHost)
            btnClient = findViewById(R.id.btnClient)
            Log.d(TAG, "Views initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing views: ${e.message}", e)
            throw e
        }
    }

    private fun setupClickListeners() {
        try {
            btnHost.setOnClickListener {
                Log.d(TAG, "Host button clicked")
                if (!isHosting) {
                    if (checkPermissions()) {
                        startHost()
                    } else {
                        requestPermissions()
                    }
                } else {
                    stopHost()
                }
            }

            btnClient.setOnClickListener {
                Log.d(TAG, "Client button clicked")
                if (!isClientRunning) {
                    startClient()
                } else {
                    stopClient()
                }
            }
            Log.d(TAG, "Click listeners set up successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up click listeners: ${e.message}", e)
            throw e
        }
    }

    private fun checkPermissions(): Boolean {
        val hasPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        Log.d(TAG, "Permission check result: $hasPermission")
        return hasPermission
    }

    private fun requestPermissions() {
        Log.d(TAG, "Requesting permissions")
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.RECORD_AUDIO),
            PERMISSION_REQUEST_CODE
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Log.d(TAG, "Permission result received: requestCode=$requestCode")

        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Permission granted")
                Toast.makeText(this, "Permission granted", Toast.LENGTH_SHORT).show()
            } else {
                Log.d(TAG, "Permission denied")
                Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startHost() {
        Thread {
            try {
                // Double-check permission before proceeding
                if (!checkPermissions()) {
                    runOnUiThread {
                        Toast.makeText(this, "Audio recording permission not granted", Toast.LENGTH_LONG).show()
                    }
                    return@Thread
                }

                isHosting = true
                runOnUiThread {
                    btnHost.text = "Stop Host"
                    Toast.makeText(this, "Starting host...", Toast.LENGTH_SHORT).show()
                }

                // Initialize AudioRecord for capturing audio
                val minBufferSize = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    CHANNEL_CONFIG_IN,
                    AUDIO_FORMAT
                )

                try {
                    audioRecord = AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        SAMPLE_RATE,
                        CHANNEL_CONFIG_IN,
                        AUDIO_FORMAT,
                        minBufferSize
                    )
                } catch (e: SecurityException) {
                    runOnUiThread {
                        Toast.makeText(this, "Permission denied for microphone access", Toast.LENGTH_LONG).show()
                    }
                    return@Thread
                }

                // Check if AudioRecord was initialized properly
                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    runOnUiThread {
                        Toast.makeText(this, "Failed to initialize audio recording", Toast.LENGTH_LONG).show()
                    }
                    return@Thread
                }

                hostSocket = DatagramSocket()
                val ip = InetAddress.getByName(HOST_IP)

                try {
                    audioRecord?.startRecording()
                } catch (e: SecurityException) {
                    runOnUiThread {
                        Toast.makeText(this, "Permission denied for audio recording", Toast.LENGTH_LONG).show()
                    }
                    return@Thread
                }

                val buffer = ByteArray(BUFFER_SIZE)

                while (isHosting) {
                    try {
                        val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                        if (bytesRead > 0) {
                            val packet = DatagramPacket(buffer, bytesRead, ip, PORT)
                            hostSocket?.send(packet)
                        }
                        Thread.sleep(10) // Small delay to prevent overwhelming
                    } catch (e: SecurityException) {
                        runOnUiThread {
                            Toast.makeText(this, "Audio recording permission revoked", Toast.LENGTH_LONG).show()
                        }
                        break
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(this, "Host error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                stopHost()
            }
        }.start()
    }

    private fun stopHost() {
        isHosting = false
        try {
            audioRecord?.stop()
        } catch (e: SecurityException) {
            // Permission might have been revoked
        } catch (e: Exception) {
            // Handle other potential exceptions
        }

        try {
            audioRecord?.release()
        } catch (e: Exception) {
            // Handle release exceptions
        }

        audioRecord = null
        hostSocket?.close()
        hostSocket = null

        runOnUiThread {
            btnHost.text = "Start Host"
            Toast.makeText(this, "Host stopped", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startClient() {
        Thread {
            try {
                isClientRunning = true
                runOnUiThread {
                    btnClient.text = "Stop Client"
                    Toast.makeText(this, "Starting client...", Toast.LENGTH_SHORT).show()
                }

                clientSocket = DatagramSocket(PORT)

                val bufferSize = AudioTrack.getMinBufferSize(
                    SAMPLE_RATE,
                    CHANNEL_CONFIG_OUT,
                    AUDIO_FORMAT
                )

                try {
                    audioTrack = AudioTrack(
                        AudioManager.STREAM_MUSIC,
                        SAMPLE_RATE,
                        CHANNEL_CONFIG_OUT,
                        AUDIO_FORMAT,
                        bufferSize,
                        AudioTrack.MODE_STREAM
                    )
                } catch (e: SecurityException) {
                    runOnUiThread {
                        Toast.makeText(this, "Permission denied for audio playback", Toast.LENGTH_LONG).show()
                    }
                    return@Thread
                }

                // Check if AudioTrack was initialized properly
                if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                    runOnUiThread {
                        Toast.makeText(this, "Failed to initialize audio playback", Toast.LENGTH_LONG).show()
                    }
                    return@Thread
                }

                try {
                    audioTrack?.play()
                } catch (e: SecurityException) {
                    runOnUiThread {
                        Toast.makeText(this, "Permission denied for audio playback", Toast.LENGTH_LONG).show()
                    }
                    return@Thread
                }

                val buffer = ByteArray(BUFFER_SIZE)

                while (isClientRunning) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    clientSocket?.receive(packet)
                    try {
                        audioTrack?.write(packet.data, 0, packet.length)
                    } catch (e: SecurityException) {
                        runOnUiThread {
                            Toast.makeText(this, "Audio playback permission revoked", Toast.LENGTH_LONG).show()
                        }
                        break
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(this, "Client error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                stopClient()
            }
        }.start()
    }

    private fun stopClient() {
        isClientRunning = false
        try {
            audioTrack?.stop()
        } catch (e: SecurityException) {
            // Permission might have been revoked
        } catch (e: Exception) {
            // Handle other potential exceptions
        }

        try {
            audioTrack?.release()
        } catch (e: Exception) {
            // Handle release exceptions
        }

        audioTrack = null
        clientSocket?.close()
        clientSocket = null

        runOnUiThread {
            btnClient.text = "Start Client"
            Toast.makeText(this, "Client stopped", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopHost()
        stopClient()
    }
}