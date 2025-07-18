package std.student.demo.privacySensors

import android.Manifest.permission.RECORD_AUDIO
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Process
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import org.lsposed.hiddenapibypass.HiddenApiBypass
import std.student.demo.privacySensors.ui.theme.DefaultTheme
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

// Main
class MainActivity : ComponentActivity() {
    private lateinit var audioSystemMonitor: AudioSystemMonitor
    private lateinit var recordingCallbackHandler: RecordingCallbackHandler

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val recordAudioGranted = permissions[RECORD_AUDIO] ?: false

        if (recordAudioGranted)
            Logger.permission("Audio recording permission granted - callback registered")
        else
            Logger.permission("Audio recording permission denied")

        setupRecordingCallback() // still catch external recording configurations
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

        initializeMonitor()

        enableEdgeToEdge()
        setContent {
            DefaultTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    RecordingMonitorScreen(
                        audioSystemMonitor = audioSystemMonitor,
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
        if (ContextCompat.checkSelfPermission(this, RECORD_AUDIO) != PERMISSION_GRANTED)
            requestPermissionLauncher.launch(arrayOf(RECORD_AUDIO))
        else {
            setupRecordingCallback()
            Logger.permission("Audio recording permission already granted - callback registered")
        }
    }

    private fun setupRecordingCallback() {
        recordingCallbackHandler.register()
    }

    override fun onDestroy() {
        super.onDestroy()
        recordingCallbackHandler.unregister()
        Process.killProcess(Process.myPid())
    }
}

// UI Components
@Composable
fun RecordingMonitorScreen(
    audioSystemMonitor: AudioSystemMonitor,
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

    Column(
        modifier = modifier.padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // System Status Card
        SystemStatusCard(
            audioSystemState = audioSystemState,
            onOpenPermissionsMenu = onOpenPermissionsMenu
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Test Recording Controls
        TestRecordingCard(
            mediaRecorderState = mediaRecorderState,
            audioRecordState = audioRecordState,

            onMediaRecorderToggle = {
                if (mediaRecorderState) {
                    mediaRecorderManager.stopRecording()
                } else {
                    mediaRecorderManager.startRecording()
                }
                mediaRecorderState = mediaRecorderManager.isRecording
            },

            onAudioRecordToggle = {
                if (audioRecordState) {
                    audioRecordManager.stopRecording()
                } else {
                    audioRecordManager.startRecording()
                }
                audioRecordState = audioRecordManager.isRecording
            },

            onShowTrackingInfo = {
                Logger.system(AppRecordingTracker.getDebugInfo())
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
}

@Composable
fun SystemStatusCard(
    audioSystemState: AudioSystemState,
    onOpenPermissionsMenu: () -> Unit
) {
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

            Spacer(modifier = Modifier.height(8.dp))

            Text(audioSystemState.audioMode)
            Text(audioSystemState.clientSilencedStatus)
            Text(audioSystemState.ownedRecordingsStatus)
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
                    text = "Test Recording (triggers callback)",
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
                    text = "Recording Activity Log",
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
                if (logMessages.isEmpty()) {
                    Text(
                        buildString {
                            appendLine("No recording activity detected yet.")
                            appendLine("Try:")
                            appendLine("- Starting test recording above")
                            appendLine("- Opening another app that records audio")
                            appendLine("- Making a phone call")
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
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
}