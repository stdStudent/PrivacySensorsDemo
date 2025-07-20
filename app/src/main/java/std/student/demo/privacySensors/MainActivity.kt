package std.student.demo.privacySensors

import android.Manifest.permission.CAMERA
import android.Manifest.permission.RECORD_AUDIO
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.hardware.SensorPrivacyManager
import android.hardware.SensorPrivacyManager.Sensors
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.Image
import android.media.ImageReader
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicYuvToRGB
import android.renderscript.Type
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import compose.icons.LineAwesomeIcons
import compose.icons.lineawesomeicons.Android
import compose.icons.lineawesomeicons.CameraRetroSolid
import compose.icons.lineawesomeicons.CameraSolid
import compose.icons.lineawesomeicons.FilmSolid
import compose.icons.lineawesomeicons.Image
import compose.icons.lineawesomeicons.MicrochipSolid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import org.lsposed.hiddenapibypass.HiddenApiBypass
import std.student.demo.privacySensors.ui.theme.DefaultTheme
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.lang.reflect.Executable
import kotlin.io.path.createTempFile

// Global log state
val logMessages = mutableStateListOf<String>()

// Global tracking for own app's recording sessions
object AppRecordingTracker {
    private val ownSessionIds = mutableSetOf<Int>()
    private val ownPortIds = mutableSetOf<Int>()
    var ownPackageName: String = ""

    fun addSessionId(sessionId: Int) {
        ownSessionIds.add(sessionId)
        Logger.system("Tracked own session ID: $sessionId")
    }

    fun addPortId(portId: Int) {
        ownPortIds.add(portId)
        Logger.system("Tracked own port ID: $portId")
    }

    fun removeSessionId(sessionId: Int) {
        ownSessionIds.remove(sessionId)
        Logger.system("Untracked own session ID: $sessionId")
    }

    fun removePortId(portId: Int) {
        ownPortIds.remove(portId)
        Logger.system("Untracked own port ID: $portId")
    }

    fun isOwnConfig(sessionId: Int, portId: String, packageName: String): Boolean {
        val isOwnSession = sessionId in ownSessionIds
        val isOwnPort = portId.toIntOrNull()?.let { it in ownPortIds } ?: false
        val isOwnPackage = packageName == ownPackageName && packageName.isNotEmpty()

        return isOwnSession || isOwnPort || isOwnPackage
    }

    fun getDebugInfo(): String = buildString {
        appendLine("Own tracking info:")
        appendLine("  Package: $ownPackageName")
        appendLine("  Session IDs: $ownSessionIds")
        appendLine("  Port IDs: $ownPortIds")
    }
}

// Log utility with standardized tags
object Logger {
    private fun log(tag: String, message: String) {
        val formattedMessage = "[$tag] $message"
        Log.d("RecordingMonitor", formattedMessage)
        logMessages.add(0, formattedMessage)

        // Keep only last 50 messages
        if (logMessages.size > 50)
            logMessages.removeAt(logMessages.size - 1)
    }

    fun error(message: String) = log("x", message)
    fun success(message: String) = log("+", message)
    fun mediaRecorder(message: String) = log("MR", message)
    fun audioRecord(message: String) = log("AR", message)
    fun camera(message: String) = log("CAM", message)
    fun camera2(message: String) = log("CAM2", message)
    fun system(message: String) = log("SYS", message)
    fun permission(message: String) = log("PERM", message)
    fun callback(message: String) = log("CB", message)
    fun info(message: String) = log("INFO", message)
}

// Audio system state data
data class AudioSystemState(
    val audioMode: String,
    val clientSilencedStatus: String,
    val ownedRecordingsStatus: String
)

// Audio system monitor
class AudioSystemMonitor(context: Context) {
    init {
        val status = HiddenApiBypass.setHiddenApiExemptions("Landroid/media/AudioRecordingConfiguration;")
        Logger.system("ARC API exemption status: " + if (status) "OK" else "Error")
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun observeAudioSystemState(): Flow<AudioSystemState> = callbackFlow {
        val callback = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>?) {
                super.onRecordingConfigChanged(configs)
                // Emit new state when recording configurations change
                val state = getCurrentAudioSystemState()
                trySend(state)
            }
        }

        // Register callback
        audioManager.registerAudioRecordingCallback(callback, Handler(Looper.getMainLooper()))

        // Emit initial state
        val initialState = getCurrentAudioSystemState()
        trySend(initialState)

        awaitClose {
            audioManager.unregisterAudioRecordingCallback(callback)
        }
    }.distinctUntilChanged()

    private fun getCurrentAudioSystemState(): AudioSystemState {
        return AudioSystemState(
            audioMode = getAudioModeDescription(),
            clientSilencedStatus = getClientSilencedStatus(),
            ownedRecordingsStatus = getOwnedRecordingsStatus()
        )
    }

    private fun getAudioModeDescription() =
        when (val mode = audioManager.mode) {
            AudioManager.MODE_NORMAL -> "Audio Mode: Normal ($mode)"
            AudioManager.MODE_RINGTONE -> "Audio Mode: Ringtone ($mode)"
            AudioManager.MODE_IN_CALL -> "Audio Mode: In Call ($mode)"
            AudioManager.MODE_IN_COMMUNICATION -> "Audio Mode: Communication ($mode)"
            else -> "Audio Mode: Unknown ($mode)"
        }

    private fun getClientSilencedStatus(): String {
        val configurations = audioManager.activeRecordingConfigurations
        return if (configurations.isEmpty())
            "No active recordings"
        else {
            val silencedCount = configurations.count { it.isClientSilenced }
            val totalCount = configurations.size
            when (silencedCount) {
                0          -> "All $totalCount clients not silenced"
                totalCount -> "All $totalCount clients silenced"
                else       -> "$silencedCount/$totalCount clients silenced"
            }
        }
    }

    private fun getOwnedRecordingsStatus(): String {
        val configurations = audioManager.activeRecordingConfigurations
        val totalCount = configurations.size

        if (totalCount == 0)
            return "0/0 owned recordings"

        val ownedCount = configurations.count { config ->
            val sessionId = config.clientAudioSessionId
            val portId = getClientPortId(config)
            val packageName = getClientPackageName(config)
            AppRecordingTracker.isOwnConfig(sessionId, portId, packageName)
        }

        return "$ownedCount/$totalCount owned recordings"
    }

    private fun getClientPortId(config: AudioRecordingConfiguration): String {
        try {
            val methods = HiddenApiBypass.getDeclaredMethods(config.javaClass)
            val getPortIdMethod = methods.find { it.name == "getClientPortId" }
            if (getPortIdMethod == null)
                return "N/R" // not retrievable

            val portId = HiddenApiBypass.invoke(
                config.javaClass,
                config,
                getPortIdMethod.name
            ) as Int

            return if (portId != -1)
                portId.toString()
            else
                "N/A"
        } catch (e: Exception) {
            return "N/R" // not retrievable
        }
    }

    private fun getClientPackageName(config: AudioRecordingConfiguration): String {
        try {
            val methods = HiddenApiBypass.getDeclaredMethods(config.javaClass)
            val getPackageNameMethod = methods.find { it.name == "getClientPackageName" }
            if (getPackageNameMethod == null)
                return "N/R" // not retrievable

            val packageName = HiddenApiBypass.invoke(
                config.javaClass,
                config,
                getPackageNameMethod.name
            ) as String

            return if (packageName.isEmpty().not())
                packageName
            else
                "N/A"
        } catch (e: Exception) {
            return "N/R" // not retrievable
        }
    }
}

// Audio recording callback handler
class RecordingCallbackHandler(private val audioManager: AudioManager) {
    companion object {
        private const val TAG = "RecordingCallbackHandler"
    }

    private var callback: AudioManager.AudioRecordingCallback? = null

    fun register() {
        if (callback != null) return

        callback = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>?) {
                super.onRecordingConfigChanged(configs)
                handleRecordingConfigChange(configs)
            }
        }

        try {
            audioManager.registerAudioRecordingCallback(callback!!, Handler(Looper.getMainLooper()))
            Logger.callback("AudioRecordingCallback registered successfully")
        } catch (e: Exception) {
            Logger.error("Failed to register callback: ${e.message}")
        }
    }

    fun unregister() {
        callback?.let {
            audioManager.unregisterAudioRecordingCallback(it)
            Logger.callback("AudioRecordingCallback unregistered")
            callback = null
        }
    }

    private fun handleRecordingConfigChange(configs: MutableList<AudioRecordingConfiguration>?) {
        if (configs.isNullOrEmpty())
            Logger.info("All recordings stopped\n")
        else {
            Logger.callback("Recording configs changed: ${configs.size} active")

            var ownConfigsCount = 0
            var externalConfigsCount = 0

            configs.forEachIndexed { index, config ->
                logRecordingConfiguration(index + 1, config)

                val sessionId = config.clientAudioSessionId
                val portId = getClientPortId(config)
                val packageName = getClientPackageName(config)

                val isOwn = AppRecordingTracker.isOwnConfig(sessionId, portId, packageName)

                if (isOwn) ownConfigsCount++ else externalConfigsCount++
            }

            Logger.callback("Summary: $ownConfigsCount own, $externalConfigsCount external")
        }
    }

    private fun logRecordingConfiguration(configNumber: Int, config: AudioRecordingConfiguration) {
        val log = getToLogFriendlyString(config)
        Log.d(TAG, "Recording Config #$configNumber: $log")

        val sessionId = config.clientAudioSessionId
        val portId = getClientPortId(config)
        val packageName = getClientPackageName(config)

        val isOwn = AppRecordingTracker.isOwnConfig(sessionId, portId, packageName)

        val details = buildString {
            appendLine()
            appendLine("Config #$configNumber:")
            appendLine("    Session ID: $sessionId")
            appendLine("    Port ID: $portId")
            appendLine("    Package Name: $packageName")
            appendLine("    Audio Source: ${getAudioSourceName(config.clientAudioSource)}")
            appendLine("    Sample Rate: ${config.format.sampleRate} Hz")
            appendLine("    Silenced: ${config.isClientSilenced}")
            appendLine("    Is own: $isOwn")
        }

        Logger.info(details)
    }

    private fun getClientPortId(config: AudioRecordingConfiguration): String {
        try {
            val methods = HiddenApiBypass.getDeclaredMethods(config.javaClass)
            val getPortIdMethod = methods.find { it.name == "getClientPortId" }
            if (getPortIdMethod == null)
                return "N/R" // not retrievable

            val portId = HiddenApiBypass.invoke(
                config.javaClass,
                config,
                getPortIdMethod.name
            ) as Int

            return if (portId != -1)
                portId.toString()
            else
                "N/A"
        } catch (e: Exception) {
            Logger.error("Failed to get client port ID: ${e.message}")
            return "N/R" // not retrievable
        }
    }

    private fun getClientPackageName(config: AudioRecordingConfiguration): String {
        try {
            val methods = HiddenApiBypass.getDeclaredMethods(config.javaClass)
            val getPackageNameMethod = methods.find { it.name == "getClientPackageName" }
            if (getPackageNameMethod == null)
                return "N/R" // not retrievable

            val packageName = HiddenApiBypass.invoke(
                config.javaClass,
                config,
                getPackageNameMethod.name
            ) as String

            return if (packageName.isEmpty().not())
                packageName
            else
                "N/A"
        } catch (e: Exception) {
            Logger.error("Failed to get client package name: ${e.message}")
            return "N/R" // not retrievable
        }
    }

    private fun getToLogFriendlyString(config: AudioRecordingConfiguration): String {
        try {
            val methods = HiddenApiBypass.getDeclaredMethods(config.javaClass)
            val toLogFriendlyStringMethod = methods.find { it.name == "toLogFriendlyString" }
            if (toLogFriendlyStringMethod == null)
                return "N/R" // not retrievable

            val friendlyLog = HiddenApiBypass.invoke(
                config.javaClass,
                null, // static method
                toLogFriendlyStringMethod.name,
                config // argument
            ) as String

            return if (friendlyLog.isEmpty().not())
                friendlyLog
            else
                "N/A"
        } catch (e: Exception) {
            Logger.error("Failed to get to log friendly string: ${e.message}")
            return "N/R" // not retrievable
        }
    }

    private fun getAudioSourceName(source: Int): String {
        return when (source) {
            MediaRecorder.AudioSource.DEFAULT             -> "DEFAULT"
            MediaRecorder.AudioSource.MIC                 -> "MIC"
            MediaRecorder.AudioSource.VOICE_UPLINK        -> "VOICE_UPLINK"
            MediaRecorder.AudioSource.VOICE_DOWNLINK      -> "VOICE_DOWNLINK"
            MediaRecorder.AudioSource.VOICE_CALL          -> "VOICE_CALL"
            MediaRecorder.AudioSource.CAMCORDER           -> "CAMCORDER"
            MediaRecorder.AudioSource.VOICE_RECOGNITION   -> "VOICE_RECOGNITION"
            MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
            MediaRecorder.AudioSource.UNPROCESSED         -> "UNPROCESSED"
            else -> "UNKNOWN ($source)"
        }
    }
}

// Test recordings
class MediaRecorderManager(private val context: Context) {
    init {
        val status = HiddenApiBypass.setHiddenApiExemptions("Landroid/media/MediaRecorder;")
        Logger.mediaRecorder("API exemption status: " + if (status) "OK" else "Error")
    }

    private var mediaRecorder: MediaRecorder? = null
    private var tempOutputFile: String? = null
    private var currentPortId: Int? = null

    val isRecording: Boolean get() = mediaRecorder != null

    fun startRecording(): Boolean {
        if (isRecording) return false

        return try {
            val recorder = MediaRecorder()
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)

            val tempFile = runCatching {
                createTempFile(
                    directory = context.cacheDir.toPath(),
                    prefix = "test_recording_",
                    suffix = ".3gp"
                ).toFile()
            }.getOrNull()

            if (tempFile == null) {
                Logger.error("Failed to create temp file for MediaRecorder")
                return false
            }

            recorder.setOutputFile(tempFile.absolutePath)
            tempOutputFile = tempFile.absolutePath
            recorder.prepare()
            recorder.start()
            mediaRecorder = recorder

            val portId = getPortId(recorder)
            Logger.mediaRecorder("Port ID: $portId")

            // Track own port ID if it's valid
            portId.toIntOrNull()?.let { id ->
                if (id != -1) {
                    currentPortId = id
                    AppRecordingTracker.addPortId(id)
                }
            }

            Logger.mediaRecorder("Started recording")
            true
        } catch (e: Exception) {
            Logger.error("Failed to start MediaRecorder: ${e.message}")
            false
        }
    }

    fun stopRecording() {
        if (!isRecording) return

        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
            Logger.mediaRecorder("Stopped recording")

            // Untrack own port ID
            currentPortId?.let { portId ->
                AppRecordingTracker.removePortId(portId)
                currentPortId = null
            }
        } catch (e: Exception) {
            Logger.error("Failed to stop MediaRecorder: ${e.message}")
        } finally {
            mediaRecorder = null
            tempOutputFile?.let {
                kotlin.runCatching { java.io.File(it).delete() }
            }
            tempOutputFile = null
        }
    }

    private fun getPortId(recorder: MediaRecorder): String {
        try {
            val methods: List<Executable> = HiddenApiBypass.getDeclaredMethods(MediaRecorder::class.java)
            val getPortIdMethod = methods.find { it.name == "getPortId" }

            if (getPortIdMethod == null)
                return "N/R" // not retrievable

            val portId = HiddenApiBypass.invoke(
                MediaRecorder::class.java,
                recorder,
                getPortIdMethod.name ?: "getPortId"
            ) as Int

            return if (portId != -1)
                portId.toString()
            else
                "N/A"
        } catch (e: Exception) {
            Logger.error("Could not get port ID: ${e.message}")
            return "N/R" // not retrievable
        }
    }
}

class AudioRecordManager {
    private var audioRecord: AudioRecord? = null
    private var currentSessionId: Int? = null

    val isRecording: Boolean get() = audioRecord != null

    @SuppressLint("MissingPermission")
    fun startRecording(): Boolean {
        if (isRecording) return false

        return try {
            val sampleRate = 44100
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

            if (bufferSize == AudioRecord.ERROR_BAD_VALUE) {
                Logger.error("Invalid AudioRecord configuration")
                return false
            }

            val record = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Logger.error("AudioRecord initialization failed")
                record.release()
                return false
            }

            record.startRecording()
            audioRecord = record

            val sessionId = record.audioSessionId
            currentSessionId = sessionId
            AppRecordingTracker.addSessionId(sessionId) // track own session ID

            Logger.audioRecord("Started recording, Session ID: $sessionId")
            true
        } catch (e: Exception) {
            Logger.error("Failed to start AudioRecord: ${e.message}")
            false
        }
    }

    fun stopRecording() {
        audioRecord?.let { record ->
            try {
                record.stop()
                record.release()
                Logger.audioRecord("Stopped recording")

                // Untrack own session ID
                currentSessionId?.let { sessionId ->
                    AppRecordingTracker.removeSessionId(sessionId)
                    currentSessionId = null
                }
            } catch (e: Exception) {
                Logger.error("Failed to stop AudioRecord: ${e.message}")
            } finally {
                audioRecord = null
            }
        }
    }
}

// Camera managers
class Camera1Manager(context: Context) {
    private val photoFile = File(context.filesDir, "camera1_photo.webp")

    private val cameraBlockDetector = CameraBlockDetector()

    @SuppressLint("MissingPermission")
    fun takePhoto(onPhotoTaken: (isBlocked: Boolean, blockResult: CameraBlockDetector.BlockResult?) -> Unit): Boolean {
        return try {
            val numberOfCameras = Camera.getNumberOfCameras()
            if (numberOfCameras == 0) {
                Logger.camera("No cameras available")
                return false
            }

            Logger.camera("Taking photo with Camera1 API")
            val camera = Camera.open(0)

            // Set up parameters for photo capture
            val parameters = camera.parameters
            parameters.setPictureFormat(ImageFormat.YUY2) // raw

            // Set valid picture size, get smallest one
            val pictureSizes = parameters.supportedPictureSizes
            val smallestSize = pictureSizes.minByOrNull { it.width * it.height } ?: pictureSizes[0]
            parameters.setPictureSize(smallestSize.width, smallestSize.height)

            camera.parameters = parameters

            // Create a dummy surface texture for preview
            val surfaceTexture = SurfaceTexture(0)
            camera.setPreviewTexture(surfaceTexture)
            camera.startPreview()

            camera.takePicture(null, null) { data, _ ->
                try {
                    if (data != null && data.isNotEmpty()) {
                        // Convert data to Bitmap and save as raw bitmap
                        val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
                        if (bitmap != null) {
                            //logUniquePixels(bitmap)

                            savePhotoFile(bitmap, photoFile)

                            // Detect if camera is blocked
                            val blockResult = cameraBlockDetector.isDisabled(bitmap)

                            if (blockResult.isDisabled)
                                Logger.camera("Camera1 API: Camera appears to be blocked by privacy toggle - repeated pixels detected")
                            else
                                Logger.success("Camera1 API: Photo saved (${bitmap.width}x${bitmap.height})")

                            onPhotoTaken(blockResult.isDisabled, blockResult)
                        } else {
                            Logger.error("Camera1 API: Failed to decode photo data")
                            onPhotoTaken(false, null)
                        }
                    } else {
                        Logger.error("Camera1 API: Photo data is null or empty (privacy toggle may be enabled)")
                        onPhotoTaken(true, null) // Likely blocked if no data
                    }
                } catch (e: Exception) {
                    Logger.error("Camera1 API: Failed to save photo: ${e.message}")
                    onPhotoTaken(false, null)
                } finally {
                    try {
                        camera.stopPreview()
                        camera.release()
                        surfaceTexture.release()
                    } catch (e: Exception) {
                        Logger.error("Camera1 API: Error releasing camera: ${e.message}")
                    }
                }
            }

            true
        } catch (e: RuntimeException) {
            when (e.message) {
                "Fail to connect to camera service" -> {
                    Logger.camera("Camera1 API: Failed to connect to camera service - possibly blocked")
                    onPhotoTaken(true, null)
                }

                else -> {
                    Logger.error("Camera1 API: ${e.message}")
                    onPhotoTaken(false, null)
                }
            }
            false
        } catch (e: Exception) {
            Logger.error("Camera1 API: ${e.message}")
            onPhotoTaken(false, null)
            false
        }
    }

    fun hasPhoto(): Boolean = photoFile.exists()

    fun getPhotoBitmap(): Bitmap? = if (hasPhoto()) {
        try {
            BitmapFactory.decodeFile(photoFile.absolutePath)
        } catch (e: Exception) {
            Logger.error("Camera1 API: Failed to load photo: ${e.message}")
            null
        }
    } else
        null

    fun deletePhoto() {
        if (photoFile.exists()) {
            photoFile.delete()
            Logger.camera("Camera1 API: Photo deleted")
        }
    }
}

class Camera2Manager(private val context: Context) {
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val photoFile = File(context.filesDir, "camera2_photo.webp")
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private fun Bitmap.isSamePixelRepeated(): Boolean {
        if (width == 0 || height == 0) return false

        val pixels = IntArray(width * height)
        this.getPixels(pixels, 0, width, 0, 0, width, height)

        val firstPixel = pixels[0]
        for (pixel in pixels)
            if (pixel != firstPixel)
                return false

        return true
    }

    @SuppressLint("MissingPermission")
    fun takePhoto(onPhotoTaken: () -> Unit): Boolean {
        return try {
            startBackgroundThread()

            val cameraIdList = cameraManager.cameraIdList
            if (cameraIdList.isEmpty()) {
                Logger.camera2("No cameras available")
                return false
            }

            val cameraId = cameraIdList[0]
            Logger.camera2("Taking photo with Camera2 API")

            // Set up ImageReader for capturing YUV (raw) format
            val reader = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 1)
            reader.setOnImageAvailableListener({ rawImage ->
                val image = rawImage.acquireLatestImage()
                try {
                    if (image != null) {
                        // Convert YUV image to bitmap
                        val bitmap = yuv420ToBitmap(image)
                        if (bitmap != null) {
                            //logUniquePixels(bitmap)

                            val isCameraBlocked = bitmap.isSamePixelRepeated()
                            if (isCameraBlocked)
                                Logger.camera2("Camera2 API: Camera appears to be blocked by privacy toggle - repeated pixels detected")

                            savePhotoFile(bitmap, photoFile)
                            Logger.success("Camera2 API: Raw photo saved (${bitmap.width}x${bitmap.height})")
                            onPhotoTaken()
                        } else
                            Logger.error("Camera2 API: Failed to convert YUV to bitmap")
                    } else
                        Logger.error("Camera2 API: Image is null (privacy toggle may be enabled)")
                } catch (e: Exception) {
                    Logger.error("Camera2 API: Failed to process image: ${e.message}")
                } finally {
                    image?.close()
                }
            }, backgroundHandler)

            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    try {
                        // Create capture session with ImageReader surface
                        camera.createCaptureSession(
                            listOf(reader.surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: CameraCaptureSession) {
                                    try {
                                        val captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                                        captureBuilder.addTarget(reader.surface)

                                        session.capture(captureBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                                            override fun onCaptureCompleted(
                                                session: CameraCaptureSession,
                                                request: CaptureRequest,
                                                result: TotalCaptureResult
                                            ) {
                                                Logger.camera2("Capture completed")

                                                // Clean up
                                                session.close()
                                                camera.close()
                                                reader.close()

                                                stopBackgroundThread()
                                            }
                                        }, backgroundHandler)
                                    } catch (e: Exception) {
                                        Logger.error("Camera2 API: Failed to capture: ${e.message}")
                                        camera.close()
                                        reader.close()
                                        stopBackgroundThread()
                                    }
                                }

                                override fun onConfigureFailed(session: CameraCaptureSession) {
                                    Logger.error("Camera2 API: Failed to configure capture session")
                                    camera.close()
                                    reader.close()
                                    stopBackgroundThread()
                                }
                            },
                            backgroundHandler
                        )
                    } catch (e: Exception) {
                        Logger.error("Camera2 API: Failed to create session: ${e.message}")
                        camera.close()
                        reader.close()
                        stopBackgroundThread()
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    Logger.camera2("Camera disconnected")
                    camera.close()
                    reader.close()
                    stopBackgroundThread()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    val errorMessage = when (error) {
                        ERROR_CAMERA_IN_USE -> "Camera in use"
                        ERROR_MAX_CAMERAS_IN_USE -> "Max cameras in use"
                        ERROR_CAMERA_DISABLED -> "Camera disabled by system (privacy toggle?)"
                        ERROR_CAMERA_DEVICE -> "Camera device error"
                        ERROR_CAMERA_SERVICE -> "Camera service error"
                        else -> "Unknown error ($error)"
                    }
                    Logger.error("Camera2 API: $errorMessage")
                    camera.close()
                    reader.close()
                    stopBackgroundThread()
                }
            }, backgroundHandler)

            true
        } catch (e: CameraAccessException) {
            val reason = when (e.reason) {
                CameraAccessException.CAMERA_DISABLED -> "Camera disabled by system (privacy toggle?)"
                CameraAccessException.CAMERA_DISCONNECTED -> "Camera disconnected"
                CameraAccessException.CAMERA_ERROR -> "Camera error"
                CameraAccessException.CAMERA_IN_USE -> "Camera in use"
                CameraAccessException.MAX_CAMERAS_IN_USE -> "Max cameras in use"
                else -> "Unknown reason (${e.reason})"
            }

            Logger.error("Camera2 API: CameraAccessException - $reason")
            stopBackgroundThread()

            false
        } catch (e: SecurityException) {
            Logger.error("Camera2 API: SecurityException - ${e.message}")
            stopBackgroundThread()

            false
        } catch (e: Exception) {
            Logger.error("Camera2 API: ${e.message}")
            stopBackgroundThread()

            false
        }
    }

    fun hasPhoto(): Boolean = photoFile.exists()

    fun getPhotoBitmap(): Bitmap? = if (hasPhoto()) {
        try {
            BitmapFactory.decodeFile(photoFile.absolutePath)
        } catch (e: Exception) {
            Logger.error("Camera2 API: Failed to load photo: ${e.message}")
            null
        }
    } else
        null

    fun deletePhoto() {
        if (photoFile.exists()) {
            photoFile.delete()
            Logger.camera2("Photo deleted")
        }
    }

    /**
     * Don't expect it to work as expected. The colours are distorted.
     * I feel retarted not being able to figure it out, but oh well...
     */
    private fun yuv420ToBitmap(image: Image): Bitmap? {
        return try {
            val renderScript = RenderScript.create(context)

            val planes = image.planes
            val yPlane = planes[0]
            val uPlane = planes[1]
            val vPlane = planes[2]

            val yBuffer = yPlane.buffer
            val uBuffer = uPlane.buffer
            val vBuffer = vPlane.buffer

            val ySize = image.width * image.height
            val uvSize = ySize / 4

            // Create YUV420 data
            val yuvData = ByteArray(ySize + uvSize * 2)

            // Copy Y plane (should be packed?)
            if (yPlane.pixelStride == 1)
                yBuffer.get(yuvData, 0, ySize)
            else
                for (i in 0 until image.height) // handle non-packed Y plane (wtf?)
                    yBuffer.apply {
                        position(i * yPlane.rowStride)
                        get(yuvData, i * image.width, image.width)
                    }

            // Copy U and V planes (semi-planar format)
            var uvIndex = ySize
            val uPixelStride = uPlane.pixelStride
            val vPixelStride = vPlane.pixelStride

            if (uPixelStride == 1 && vPixelStride == 1) {
                // Packed UV planes
                uBuffer.get(yuvData, uvIndex, uvSize)
                vBuffer.get(yuvData, uvIndex + uvSize, uvSize)
            } else {
                // Semi-planar format - interleaved UV
                uBuffer.rewind()
                vBuffer.rewind()

                for (i in 0 until uvSize) {
                    yuvData[uvIndex++] = uBuffer.get(i * uPixelStride)
                    yuvData[uvIndex++] = vBuffer.get(i * vPixelStride)
                }
            }

            // Create YUV allocation
            val yuvTypeBuilder = Type.Builder(renderScript, Element.U8(renderScript)).setX(yuvData.size)
            val allocationInYUV = Allocation.createTyped(renderScript, yuvTypeBuilder.create())
            allocationInYUV.copyFrom(yuvData)

            // Create RGB output allocation
            val allocationOutRGB = Allocation.createTyped(
                renderScript,
                Type.createXY(renderScript, Element.RGBA_8888(renderScript), image.width, image.height),
                Allocation.USAGE_SCRIPT
            )

            // Create and run conversion script
            val scriptIntrinsicYuvToRGB = ScriptIntrinsicYuvToRGB.create(renderScript, Element.U8_4(renderScript))
            scriptIntrinsicYuvToRGB.setInput(allocationInYUV)
            scriptIntrinsicYuvToRGB.forEach(allocationOutRGB)

            // Create bitmap
            val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
            allocationOutRGB.copyTo(bitmap)

            // Clean up
            allocationInYUV.destroy()
            allocationOutRGB.destroy()
            scriptIntrinsicYuvToRGB.destroy()
            renderScript.destroy()

            bitmap
        } catch (e: Exception) {
            Logger.error("Camera2 API: Failed to convert YUV to bitmap with RenderScript: ${e.message}")
            null
        }
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Logger.error("Camera2 API: Error stopping background thread: ${e.message}")
        }
    }
}

fun savePhotoFile(bitmap: Bitmap, file: File) {
    FileOutputStream(file).use { fos ->
        bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 0, fos)
    }
}

// Only useful for Camera1
class CameraBlockDetector {
    data class BlockResult(
        val isDisabled: Boolean,
        val confidence: Float
    )

    fun isDisabled(bitmap: Bitmap): BlockResult {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        val uniquePixels = mutableSetOf<Int>()
        var singleChannelCount = 0
        var maxChannelValue = 0

        for (pixel in pixels) {
            uniquePixels.add(pixel)

            val r = android.graphics.Color.red(pixel)
            val g = android.graphics.Color.green(pixel)
            val b = android.graphics.Color.blue(pixel)

            maxChannelValue = maxOf(maxChannelValue, r, g, b)

            // Count pixels with only one channel active
            val activeChannels = listOf(r > 0, g > 0, b > 0).count { it }
            if (activeChannels == 1)
                singleChannelCount++
        }

        val uniquePixelRatio = uniquePixels.size.toFloat() / pixels.size
        val singleChannelRatio = singleChannelCount.toFloat() / pixels.size

        // Disabled camera characteristics:
        // 1. Very few unique pixels (typically 40-50, or 0%, out of thousands)
        // 2. High single-channel pixel ratio (70%+)
        // 3. Very low max channel values (not exceeding 12 for me)

        val isDisabled = uniquePixelRatio < 0.01f // Less than 1% unique pixels
            && singleChannelRatio > 0.6f          // More than 60% single-channel
            &&  maxChannelValue <= 12             // Max RGB value <= 12

        val confidence = if (isDisabled) {
            var conf = 0.7f // Base confidence

            // Boost confidence for stronger indicators
            if (uniquePixelRatio < 0.005f) conf += 0.1f    // Extremely few unique pixels
            if (singleChannelRatio > 0.8f) conf += 0.1f    // Very high single-channel ratio
            if (maxChannelValue <= 8) conf += 0.1f         // Very low max values

            minOf(conf, 0.95f) // Cap at 95%
        } else
            // Low confidence when not disabled
            maxOf(0.1f, 0.9f - uniquePixelRatio * 10f)

        return BlockResult(isDisabled, confidence)
    }
}

fun logUniquePixels(bitmap: Bitmap) {
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

    val rgbList = mutableSetOf<String>()
    for (pixel in pixels) {
        val r: Int = android.graphics.Color.red(pixel)
        val g: Int = android.graphics.Color.green(pixel)
        val b: Int = android.graphics.Color.blue(pixel)

        rgbList.add("R: $r, G: $g, B: $b")
    }

    Log.d("RGB", rgbList.joinToString("\n"))
}

// Sensors
class PrivacySensors(context: Context) {
    init {
        val status = HiddenApiBypass.setHiddenApiExemptions("Landroid/hardware/SensorPrivacyManager;")
        Logger.system("SPM API exemption status: " + if (status) "OK" else "Error")
    }

    private val sensorPrivacyManager =
        context.getSystemService(SensorPrivacyManager::class.java) as SensorPrivacyManager

    sealed class MicTogglePresent {
        data object HARDWARE : MicTogglePresent()
        data object SOFTWARE : MicTogglePresent()
        data object ANY      : MicTogglePresent() // API < 33, fallback option
    }

    sealed class CamTogglePresent {
        data object HARDWARE : CamTogglePresent()
        data object SOFTWARE : CamTogglePresent()
        data object ANY      : CamTogglePresent() // API < 33, fallback option
    }

    fun isMicrophoneSupported(): List<MicTogglePresent> {
        if (Build.VERSION.SDK_INT < 33) {
            val isSupported = sensorPrivacyManager.supportsSensorToggle(Sensors.MICROPHONE)
            return if (isSupported) {
                listOf(MicTogglePresent.ANY)
            } else {
                emptyList()
            }
        }

        // For Android 13 and above, use the new API
        val isHardwareSupported =
            sensorPrivacyManager.supportsSensorToggle(
                SensorPrivacyManager.TOGGLE_TYPE_HARDWARE,
                Sensors.MICROPHONE
            )

        val isSoftwareSupported =
            sensorPrivacyManager.supportsSensorToggle(
                SensorPrivacyManager.TOGGLE_TYPE_SOFTWARE,
                Sensors.MICROPHONE
            )

        return listOfNotNull(
            if (isHardwareSupported) MicTogglePresent.HARDWARE else null,
            if (isSoftwareSupported) MicTogglePresent.SOFTWARE else null
        )
    }

    fun isCameraSupported(): List<CamTogglePresent> {
        if (Build.VERSION.SDK_INT < 33) {
            val isSupported = sensorPrivacyManager.supportsSensorToggle(Sensors.CAMERA)
            return if (isSupported) {
                listOf(CamTogglePresent.ANY)
            } else {
                emptyList()
            }
        }

        // For Android 13 and above, use the new API
        val isHardwareSupported =
            sensorPrivacyManager.supportsSensorToggle(
                SensorPrivacyManager.TOGGLE_TYPE_HARDWARE,
                Sensors.CAMERA
            )

        val isSoftwareSupported =
            sensorPrivacyManager.supportsSensorToggle(
                SensorPrivacyManager.TOGGLE_TYPE_SOFTWARE,
                Sensors.CAMERA
            )

        return listOfNotNull(
            if (isHardwareSupported) CamTogglePresent.HARDWARE else null,
            if (isSoftwareSupported) CamTogglePresent.SOFTWARE else null
        )
    }
}

class LogcatListener {
    private val cameraKeywords = listOf(
        "camera2",
        "CameraDevice",
        "CameraHandler",
        "android.hardware.camera"
    )

    private val targetKeywords = listOf(
        "MessageQueue",
        "Handler",
        "dead thread",
        "IllegalStateException"
    )

    /**
     * Creates a Flow that monitors logcat and emits true when target errors are detected
     */
    fun monitorCameraErrors(): Flow<Boolean> = flow {
        Runtime.getRuntime().exec("logcat -c").waitFor()

        val process = ProcessBuilder("logcat", "-v", "long")
            .redirectErrorStream(true)
            .start()

        val reader = BufferedReader(InputStreamReader(process.inputStream))
        val logBuffer = StringBuilder()
        var isInErrorBlock = false
        var errorStartTime = 0L
        var hasEmittedForCurrentBlock = false

        try {
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                line?.let { currentLine ->
                    // Yep, this is not valid and will backfire. Do I care? Nope.
                    val isLogEntryStart = currentLine.startsWith("[ ")

                    if (isLogEntryStart) {
                        // Reset for new log entry
                        logBuffer.clear()
                        isInErrorBlock = false
                        hasEmittedForCurrentBlock = false
                        errorStartTime = System.currentTimeMillis()
                    }

                    // Add current line to buffer
                    logBuffer.appendLine(currentLine)

                    // Check if this line indicates start of error tracking
                    if (isInErrorBlock.not() && containsAnyKeyword(currentLine, targetKeywords)) {
                        isInErrorBlock = true
                        hasEmittedForCurrentBlock = false
                    }

                    // Check for target error immediately when in error block
                    if (isInErrorBlock && !hasEmittedForCurrentBlock) {
                        val errorText = logBuffer.toString()
                        if (isTargetError(errorText)) {
                            emit(true)
                            hasEmittedForCurrentBlock = true
                        }
                    }

                    // Stop accumulating after half a second to prevent memory issues
                    if (isInErrorBlock && System.currentTimeMillis() - errorStartTime > 1500)
                        isInErrorBlock = false
                }
            }
        } catch (e: Exception) {
            Logger.error("Logcat monitoring error: ${e.message}")
        } finally {
            try {
                process.destroyForcibly()
                reader.close()
            } catch (e: Exception) {
                Logger.error("Failed to close logcat reader: ${e.message}")
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Determines if the accumulated error text matches all criteria
     */
    private fun isTargetError(errorText: String): Boolean {
        val lowercaseError = errorText.lowercase()

        // Check if contains all required keywords
        val hasAllTargetKeywords = targetKeywords.all { keyword ->
            lowercaseError.contains(keyword.lowercase())
        }

        // Check if contains at least one camera-related keyword
        val hasCameraKeyword = cameraKeywords.any { keyword ->
            lowercaseError.contains(keyword.lowercase())
        }

        return hasAllTargetKeywords && hasCameraKeyword
    }

    /**
     * Helper function to check if text contains any of the provided keywords
     */
    private fun containsAnyKeyword(text: String, keywords: List<String>): Boolean {
        val lowercaseText = text.lowercase()
        return keywords.any { keyword ->
            lowercaseText.contains(keyword.lowercase())
        }
    }
}

// Main
class MainActivity : ComponentActivity() {
    private lateinit var audioSystemMonitor: AudioSystemMonitor
    private lateinit var recordingCallbackHandler: RecordingCallbackHandler
    private lateinit var camera1Manager: Camera1Manager
    private lateinit var camera2Manager: Camera2Manager

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val recordAudioGranted = permissions[RECORD_AUDIO] ?: false
        val cameraGranted = permissions[CAMERA] ?: false

        if (recordAudioGranted)
            Logger.permission("Audio recording permission granted - callback registered")
        else
            Logger.permission("Audio recording permission denied")

        if (cameraGranted)
            Logger.permission("Camera permission granted")
        else
            Logger.permission("Camera permission denied")

        setupRecordingCallback() // still catch external recording configurations
    }

    private val logcatListener = LogcatListener()
    private val logScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private fun startLogMonitoring() {
        logScope.launch {
            logcatListener.monitorCameraErrors().collect { hasError ->
                if (hasError)
                    Logger.error("Camera disabled by privacy toggle detected in logcat")
            }
        }
    }

    private fun stopLogMonitoring() {
        logScope.cancel()
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        startActivity(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize own package name for tracking
        AppRecordingTracker.ownPackageName = packageName
        Logger.system("Own package name: $packageName")

        audioSystemMonitor = AudioSystemMonitor(this)
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        recordingCallbackHandler = RecordingCallbackHandler(audioManager)
        camera1Manager = Camera1Manager(this)
        camera2Manager = Camera2Manager(this)

        initializeMonitor()
        startLogMonitoring()

        enableEdgeToEdge()
        setContent {
            DefaultTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    RecordingMonitorScreen(
                        audioSystemMonitor = audioSystemMonitor,
                        camera1Manager = camera1Manager,
                        camera2Manager = camera2Manager,
                        modifier = Modifier
                            .padding(innerPadding)
                            .fillMaxSize(),
                        onOpenPermissionsMenu = { openAppSettings() }
                    )
                }
            }
        }
    }

    private fun initializeMonitor() {
        val audioPermissionGranted = ContextCompat.checkSelfPermission(this, RECORD_AUDIO) == PERMISSION_GRANTED
        val cameraPermissionGranted = ContextCompat.checkSelfPermission(this, CAMERA) == PERMISSION_GRANTED

        if (audioPermissionGranted.not() || cameraPermissionGranted.not()) {
            val permissionsToRequest = mutableListOf<String>()
            if (audioPermissionGranted.not()) permissionsToRequest.add(RECORD_AUDIO)
            if (cameraPermissionGranted.not()) permissionsToRequest.add(CAMERA)

            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            setupRecordingCallback()
            Logger.permission("All permissions already granted - callback registered")
        }
    }

    private fun setupRecordingCallback() {
        recordingCallbackHandler.register()
    }

    override fun onDestroy() {
        super.onDestroy()
        recordingCallbackHandler.unregister()

        // Clean up photos
        camera1Manager.deletePhoto()
        camera2Manager.deletePhoto()

        // Stop logcat monitoring
        stopLogMonitoring()

        // Stop background recordings (yeah, this is ultimate shitcode, don't blame me, idc rn)
        Process.killProcess(Process.myPid())
    }
}

// UI Components
@Composable
fun RecordingMonitorScreen(
    audioSystemMonitor: AudioSystemMonitor,
    camera1Manager: Camera1Manager,
    camera2Manager: Camera2Manager,
    modifier: Modifier = Modifier,
    onOpenPermissionsMenu: () -> Unit
) {
    val context = LocalContext.current
    val audioSystemState by audioSystemMonitor.observeAudioSystemState().collectAsState(
        initial = AudioSystemState("Loading...", "Loading...", "Loading...")
    )

    val mediaRecorderManager = remember { MediaRecorderManager(context) }
    val audioRecordManager = remember { AudioRecordManager() }

    var mediaRecorderState by remember { mutableStateOf(false) }
    var audioRecordState by remember { mutableStateOf(false) }
    var camera1HasPhoto by remember { mutableStateOf(camera1Manager.hasPhoto()) }
    var camera2HasPhoto by remember { mutableStateOf(camera2Manager.hasPhoto()) }
    var showCamera1Photo by remember { mutableStateOf(false) }
    var showCamera2Photo by remember { mutableStateOf(false) }

    // PrivacySensors info
    val privacySensors = remember { PrivacySensors(context) }
    val micSupport = remember { privacySensors.isMicrophoneSupported() }
    val camSupport = remember { privacySensors.isCameraSupported() }

    Column(
        modifier = modifier.padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // System Status Card
        SystemStatusCard(
            audioSystemState = audioSystemState,
            onOpenPermissionsMenu = onOpenPermissionsMenu,
            micSupport = micSupport,
            camSupport = camSupport
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Test Recording Controls
        TestRecordingCard(
            mediaRecorderState = mediaRecorderState,
            audioRecordState = audioRecordState,

            onMediaRecorderToggle = {
                if (mediaRecorderState)
                    mediaRecorderManager.stopRecording()
                else
                    mediaRecorderManager.startRecording()

                mediaRecorderState = mediaRecorderManager.isRecording
            },

            onAudioRecordToggle = {
                if (audioRecordState)
                    audioRecordManager.stopRecording()
                else
                    audioRecordManager.startRecording()

                audioRecordState = audioRecordManager.isRecording
            },

            onShowTrackingInfo = {
                Logger.system(AppRecordingTracker.getDebugInfo())
            }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Camera Test Controls
        CameraTestCard(
            camera1HasPhoto = camera1HasPhoto,
            camera2HasPhoto = camera2HasPhoto,

            onCamera1TakePhoto = {
                camera1Manager.takePhoto { isBlocked, blockResult ->
                    if (isBlocked)
                        if (blockResult != null)
                            Logger.camera("Camera appears to be blocked by privacy toggle: ${blockResult.confidence * 100}% confidence.")
                        else
                            Logger.camera("Camera access is restricted.")

                    camera1HasPhoto = camera1Manager.hasPhoto()
                }
            },

            onCamera2TakePhoto = {
                camera2Manager.takePhoto {
                    camera2HasPhoto = camera2Manager.hasPhoto()
                }
            },

            onCamera1ShowPhoto = {
                showCamera1Photo = true
            },

            onCamera2ShowPhoto = {
                showCamera2Photo = true
            }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Log Display with integrated clear button
        LogDisplayCard(
            logMessages = logMessages,
            onClearLogs = { logMessages.clear() },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        )
    }

    // Photo dialogs
    if (showCamera1Photo)
        camera1Manager.getPhotoBitmap()?.let { bitmap ->
            Dialog(onDismissRequest = { showCamera1Photo = false }) {
                Card {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Camera1 API Photo", style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "Camera1 API photo",
                            modifier = Modifier.size(300.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = { showCamera1Photo = false },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Close")
                        }
                    }
                }
            }
        }

    if (showCamera2Photo)
        camera2Manager.getPhotoBitmap()?.let { bitmap ->
            Dialog(onDismissRequest = { showCamera2Photo = false }) {
                Card {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Camera2 API Photo", style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "Camera2 API photo",
                            modifier = Modifier.size(300.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = { showCamera2Photo = false },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Close")
                        }
                    }
                }
            }
        }
}

@Composable
fun SystemStatusCard(
    audioSystemState: AudioSystemState,
    onOpenPermissionsMenu: () -> Unit,
    micSupport: List<PrivacySensors.MicTogglePresent>,
    camSupport: List<PrivacySensors.CamTogglePresent>
) {
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "System Status",
                    style = MaterialTheme.typography.titleMedium
                )
                IconButton(
                    onClick = onOpenPermissionsMenu,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Open app settings",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Supported toggles:", style = MaterialTheme.typography.bodySmall)

                PrivacySensorStatusIcon(
                    label = "Mic",
                    support = micSupport,
                    toggleTypeIcons = mapOf(
                        PrivacySensors.MicTogglePresent.HARDWARE to LineAwesomeIcons.MicrochipSolid,
                        PrivacySensors.MicTogglePresent.SOFTWARE to LineAwesomeIcons.Android
                    ),
                    onClick = {
                        val message = when {
                            micSupport.isEmpty() ->
                                "Microphone toggle not supported"

                            micSupport.contains(PrivacySensors.MicTogglePresent.HARDWARE) && micSupport.contains(PrivacySensors.MicTogglePresent.SOFTWARE) ->
                                "Microphone toggle: hardware & software supported"

                            micSupport.contains(PrivacySensors.MicTogglePresent.HARDWARE) ->
                                "Microphone toggle: hardware supported"

                            micSupport.contains(PrivacySensors.MicTogglePresent.SOFTWARE) ->
                                "Microphone toggle: software supported"

                            micSupport.contains(PrivacySensors.MicTogglePresent.ANY) ->
                                "Microphone toggle: supported (type unknown)"

                            else ->
                                "Microphone toggle: supported"
                        }

                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    }
                )

                PrivacySensorStatusIcon(
                    label = "Cam",
                    support = camSupport,
                    toggleTypeIcons = mapOf(
                        PrivacySensors.CamTogglePresent.HARDWARE to LineAwesomeIcons.MicrochipSolid,
                        PrivacySensors.CamTogglePresent.SOFTWARE to LineAwesomeIcons.Android
                    ),
                    onClick = {
                        val message = when {
                            camSupport.isEmpty() ->
                                "Camera toggle not supported"

                            camSupport.contains(PrivacySensors.CamTogglePresent.HARDWARE) && camSupport.contains(PrivacySensors.CamTogglePresent.SOFTWARE) ->
                                "Camera toggle: hardware & software supported"

                            camSupport.contains(PrivacySensors.CamTogglePresent.HARDWARE) ->
                                "Camera toggle: hardware supported"

                            camSupport.contains(PrivacySensors.CamTogglePresent.SOFTWARE) ->
                                "Camera toggle: software supported"

                            camSupport.contains(PrivacySensors.CamTogglePresent.ANY) ->
                                "Camera toggle: supported (type unknown)"

                            else ->
                                "Camera toggle: supported"
                        }

                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(audioSystemState.audioMode)
            Text(audioSystemState.clientSilencedStatus)
            Text(audioSystemState.ownedRecordingsStatus)
        }
    }
}

// Compact icon row for sensor status
@Composable
fun <T> PrivacySensorStatusIcon(
    label: String,
    support: List<T>,
    toggleTypeIcons: Map<T, ImageVector>,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        if (support.isEmpty()) {
            IconButton(
                onClick = onClick,
                modifier = Modifier.size(18.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "$label toggle not supported",
                    tint = Color.Red,
                    modifier = Modifier.size(18.dp)
                )
            }
        } else {
            IconButton(
                onClick = onClick,
                modifier = Modifier.size(18.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "$label toggle supported",
                    tint = Color(0xFF4CAF50),
                    modifier = Modifier.size(18.dp)
                )
            }
            support.forEach { type ->
                Icon(
                    imageVector = toggleTypeIcons[type] ?: Icons.Default.CheckCircle,
                    contentDescription = when (type) {
                        is PrivacySensors.MicTogglePresent.HARDWARE, is PrivacySensors.CamTogglePresent.HARDWARE -> "$label hardware toggle"
                        is PrivacySensors.MicTogglePresent.SOFTWARE, is PrivacySensors.CamTogglePresent.SOFTWARE -> "$label software toggle"
                        is PrivacySensors.MicTogglePresent.ANY,      is PrivacySensors.CamTogglePresent.ANY      -> "$label (any type) toggle"
                        else -> "$label toggle"
                    },
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
fun TestRecordingCard(
    mediaRecorderState: Boolean,
    audioRecordState: Boolean,
    onMediaRecorderToggle: () -> Unit,
    onAudioRecordToggle: () -> Unit,
    onShowTrackingInfo: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Test Audio Recording",
                    style = MaterialTheme.typography.titleMedium
                )

                IconButton(
                    onClick = onShowTrackingInfo,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "Show tracking info",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onMediaRecorderToggle) {
                    Text(if (mediaRecorderState) "Stop MediaRecorder" else "Start MediaRecorder")
                }

                Button(onClick = onAudioRecordToggle) {
                    Text(if (audioRecordState) "Stop AudioRecord" else "Start AudioRecord")
                }
            }
        }
    }
}

@Composable
fun CameraTestCard(
    camera1HasPhoto: Boolean,
    camera2HasPhoto: Boolean,
    onCamera1TakePhoto: () -> Unit,
    onCamera2TakePhoto: () -> Unit,
    onCamera1ShowPhoto: () -> Unit,
    onCamera2ShowPhoto: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Camera1 API section
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Test Camera1 API",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onCamera1TakePhoto) {
                            Icon(
                                imageVector = LineAwesomeIcons.CameraRetroSolid,
                                contentDescription = "Take photo with Camera1 API",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }

                        if (camera1HasPhoto)
                            IconButton(onClick = onCamera1ShowPhoto) {
                                Icon(
                                    imageVector = LineAwesomeIcons.FilmSolid,
                                    contentDescription = "View Camera1 API photo",
                                    tint = MaterialTheme.colorScheme.secondary
                                )
                            }
                    }
                }

                // Visual separator
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(48.dp)
                        .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                )

                // Camera2 API section
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Test Camera2 API",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onCamera2TakePhoto) {
                            Icon(
                                imageVector = LineAwesomeIcons.CameraSolid,
                                contentDescription = "Take photo with Camera2 API",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }

                        if (camera2HasPhoto)
                            IconButton(onClick = onCamera2ShowPhoto) {
                                Icon(
                                    imageVector = LineAwesomeIcons.Image,
                                    contentDescription = "View Camera2 API photo",
                                    tint = MaterialTheme.colorScheme.secondary
                                )
                            }
                    }
                }
            }
        }
    }
}

@Composable
fun LogDisplayCard(
    logMessages: List<String>,
    onClearLogs: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    // Automatically scroll to the top on new logs
    LaunchedEffect(logMessages.size) {
        scrollState.scrollTo(0)
    }

    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header with title and clear button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Activity Log",
                    style = MaterialTheme.typography.titleMedium
                )

                IconButton(
                    onClick = onClearLogs,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription = "Clear logs",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
            ) {
                if (logMessages.isEmpty())
                    Text(
                        buildString {
                            appendLine("No activity detected yet.")
                            appendLine("Try:")
                            appendLine("- Taking photos with camera buttons above")
                            appendLine("- Starting test recording above")
                            appendLine("- Opening another app that records audio/video")
                            appendLine("- Making a phone call")
                            appendLine("- Toggle privacy sensors in system settings")
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                else
                    logMessages.forEach { message ->
                        Text(
                            text = message,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
            }
        }
    }
}
