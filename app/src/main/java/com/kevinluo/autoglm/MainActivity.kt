package com.kevinluo.autoglm

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.kevinluo.autoglm.action.ActionHandler
import com.kevinluo.autoglm.action.AgentAction
import com.kevinluo.autoglm.agent.PhoneAgent
import com.kevinluo.autoglm.agent.PhoneAgentListener
import com.kevinluo.autoglm.settings.SettingsActivity
import com.kevinluo.autoglm.ui.FloatingWindowService
import com.kevinluo.autoglm.ui.TaskStatus
import com.kevinluo.autoglm.util.KeepAliveManager
import com.kevinluo.autoglm.util.Logger
import com.kevinluo.autoglm.util.ServiceStateManager
import com.kevinluo.autoglm.voice.ContinuousListeningService
import com.kevinluo.autoglm.voice.VoiceInputManager
import com.kevinluo.autoglm.voice.VoiceInputListener
import com.kevinluo.autoglm.voice.VoiceRecognitionResult
import com.kevinluo.autoglm.voice.VoiceError
import com.kevinluo.autoglm.voice.VoiceModelManager
import com.kevinluo.autoglm.voice.VoiceModelDownloadListener
import com.kevinluo.autoglm.voice.VoiceRecordingDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/**
 * Main activity for the AutoGLM Phone Agent application.
 *
 * This activity serves as the primary entry point for the application,
 * providing the main user interface for:
 * - Shizuku permission management and service binding
 * - Task input and execution control
 * - Task status display and step tracking
 * - Navigation to settings and history
 * - Floating window management
 *
 * The activity implements [PhoneAgentListener] to receive callbacks
 * during task execution for UI updates.
 *
 */
class MainActivity : AppCompatActivity(), PhoneAgentListener {

    // Shizuku status views
    private lateinit var statusText: TextView
    private lateinit var shizukuStatusIndicator: View
    private lateinit var shizukuButtonsRow: View
    private lateinit var requestPermissionBtn: Button
    private lateinit var openShizukuBtn: Button
    private lateinit var settingsBtn: ImageButton

    // Overlay permission views
    private lateinit var overlayPermissionCard: View
    private lateinit var overlayStatusIcon: android.widget.ImageView
    private lateinit var overlayStatusText: TextView
    private lateinit var requestOverlayBtn: Button

    // Keyboard views
    private lateinit var keyboardCard: View
    private lateinit var keyboardStatusIcon: android.widget.ImageView
    private lateinit var keyboardStatusText: TextView
    private lateinit var enableKeyboardBtn: Button

    // Battery optimization views
    private var batteryOptCard: View? = null
    private var batteryStatusIcon: android.widget.ImageView? = null
    private var batteryStatusText: TextView? = null
    private var requestBatteryOptBtn: Button? = null

    // Task input views
    private lateinit var taskInputLayout: TextInputLayout
    private lateinit var taskInput: TextInputEditText
    private lateinit var startTaskBtn: MaterialButton
    private lateinit var cancelTaskBtn: MaterialButton
    private lateinit var btnSelectTemplate: ImageButton

    // Task status views
    private lateinit var taskStatusIndicator: View
    private lateinit var taskStatusText: TextView
    private lateinit var stepCounterText: TextView
    private lateinit var runningSection: View

    // Component manager for dependency injection
    private lateinit var componentManager: ComponentManager
    
    // Current step tracking for floating window
    private var currentStepNumber = 0
    private var currentThinking = ""

    // Voice input
    private lateinit var btnVoiceInput: ImageButton
    private var voiceInputManager: VoiceInputManager? = null
    private var isVoiceRecording = false

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            onVoiceButtonClick()
        } else {
            Toast.makeText(this, R.string.voice_permission_denied, Toast.LENGTH_SHORT).show()
        }
    }

    // Wake word broadcast receiver
    private val wakeWordReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ContinuousListeningService.ACTION_WAKE_WORD_DETECTED) {
                val wakeWord = intent.getStringExtra(ContinuousListeningService.EXTRA_WAKE_WORD)
                val recognizedText = intent.getStringExtra(ContinuousListeningService.EXTRA_RECOGNIZED_TEXT)
                Logger.i(TAG, "Wake word detected: $wakeWord, text: $recognizedText")
                onWakeWordDetected(wakeWord, recognizedText)
            }
        }
    }

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(
            BuildConfig.APPLICATION_ID,
            UserService::class.java.name
        )
    )
        .daemon(false)
        .processNameSuffix("user_service")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)

    private val userServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val userService = IUserService.Stub.asInterface(service)
            Logger.i(TAG, "UserService connected")
            
            // Notify ComponentManager
            componentManager.onServiceConnected(userService)
            
            runOnUiThread {
                Toast.makeText(this@MainActivity, R.string.toast_user_service_connected, Toast.LENGTH_SHORT).show()
                updateShizukuStatus()
                initializePhoneAgent()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Logger.i(TAG, "UserService disconnected")
            
            // Notify ComponentManager
            componentManager.onServiceDisconnected()
            
            runOnUiThread {
                Toast.makeText(this@MainActivity, R.string.toast_user_service_disconnected, Toast.LENGTH_SHORT).show()
                updateShizukuStatus()
                updateTaskButtonStates()
            }
        }
    }

    private val onRequestPermissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == SHIZUKU_PERMISSION_REQUEST_CODE) {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    updateShizukuStatus()
                    bindUserService()
                    Toast.makeText(this, R.string.toast_shizuku_permission_granted, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, R.string.toast_shizuku_permission_denied, Toast.LENGTH_SHORT).show()
                }
            }
        }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        updateShizukuStatus()
        if (hasShizukuPermission()) {
            bindUserService()
        }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Logger.w(TAG, "Shizuku binder died")
        componentManager.onServiceDisconnected()
        updateShizukuStatus()
        updateTaskButtonStates()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Initialize ComponentManager
        componentManager = ComponentManager.getInstance(this)
        Logger.i(TAG, "ComponentManager initialized")
        
        initViews()
        setupListeners()
        setupShizukuListeners()
        
        // Register wake word receiver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                wakeWordReceiver,
                IntentFilter(ContinuousListeningService.ACTION_WAKE_WORD_DETECTED),
                RECEIVER_NOT_EXPORTED
            )
        } else {
            registerReceiver(
                wakeWordReceiver,
                IntentFilter(ContinuousListeningService.ACTION_WAKE_WORD_DETECTED)
            )
        }
        
        updateShizukuStatus()
        updateOverlayPermissionStatus()
        updateKeyboardStatus()
        updateTaskStatus(TaskStatus.IDLE)
        
        // Restore continuous listening service if it was enabled
        restoreContinuousListeningService()
        
        // 检查是否是从唤醒词启动的
        handleWakeWordIntent(intent)
    }
    
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleWakeWordIntent(it) }
    }
    
    /**
     * 处理唤醒词 Intent
     */
    private fun handleWakeWordIntent(intent: Intent) {
        if (intent.action == ContinuousListeningService.ACTION_WAKE_WORD_DETECTED) {
            val wakeWord = intent.getStringExtra(ContinuousListeningService.EXTRA_WAKE_WORD)
            Logger.i(TAG, "Handling wake word intent: $wakeWord")
            
            // 延迟一点确保 Activity 完全显示
            android.os.Handler(mainLooper).postDelayed({
                onWakeWordDetected(wakeWord, null)
            }, 300)
        }
    }
    
    /**
     * Updates the overlay permission status display.
     *
     * Checks if the app has overlay permission and updates the UI
     * to show the current status and appropriate action button.
     */
    private fun updateOverlayPermissionStatus() {
        val hasPermission = FloatingWindowService.canDrawOverlays(this)
        
        if (hasPermission) {
            overlayStatusText.text = getString(R.string.overlay_permission_granted)
            overlayStatusIcon.setColorFilter(ContextCompat.getColor(this, R.color.status_running))
            requestOverlayBtn.visibility = View.GONE
        } else {
            overlayStatusText.text = getString(R.string.overlay_permission_denied)
            overlayStatusIcon.setColorFilter(ContextCompat.getColor(this, R.color.status_waiting))
            requestOverlayBtn.visibility = View.VISIBLE
        }
    }
    
    /**
     * Updates the keyboard status display.
     *
     * Checks if AutoGLM Keyboard is enabled and updates the UI accordingly.
     */
    private fun updateKeyboardStatus() {
        val status = com.kevinluo.autoglm.input.KeyboardHelper.getAutoGLMKeyboardStatus(this)
        
        when (status) {
            com.kevinluo.autoglm.input.KeyboardHelper.KeyboardStatus.ENABLED -> {
                keyboardStatusText.text = getString(R.string.keyboard_settings_subtitle)
                keyboardStatusIcon.setColorFilter(ContextCompat.getColor(this, R.color.status_running))
                enableKeyboardBtn.visibility = View.GONE
            }
            com.kevinluo.autoglm.input.KeyboardHelper.KeyboardStatus.NOT_ENABLED -> {
                keyboardStatusText.text = getString(R.string.keyboard_not_enabled)
                keyboardStatusIcon.setColorFilter(ContextCompat.getColor(this, R.color.status_waiting))
                enableKeyboardBtn.visibility = View.VISIBLE
                enableKeyboardBtn.text = getString(R.string.enable_keyboard)
            }
        }
    }
    
    /**
     * Updates the battery optimization status display.
     *
     * Checks if the app is ignoring battery optimizations and updates the UI accordingly.
     */
    private fun updateBatteryOptimizationStatus() {
        val isIgnoring = KeepAliveManager.isIgnoringBatteryOptimizations(this)
        
        batteryOptCard?.visibility = View.VISIBLE
        
        if (isIgnoring) {
            batteryStatusText?.text = getString(R.string.battery_opt_ignored)
            batteryStatusIcon?.setColorFilter(ContextCompat.getColor(this, R.color.status_running))
            requestBatteryOptBtn?.visibility = View.GONE
        } else {
            batteryStatusText?.text = getString(R.string.battery_opt_not_ignored)
            batteryStatusIcon?.setColorFilter(ContextCompat.getColor(this, R.color.status_waiting))
            requestBatteryOptBtn?.visibility = View.VISIBLE
        }
    }
    
    /**
     * Restores the continuous listening service if it was previously enabled.
     *
     * Checks if continuous listening is enabled in settings and the voice model
     * is downloaded, then starts the service if it's not already running.
     */
    private fun restoreContinuousListeningService() {
        val settingsManager = componentManager.settingsManager
        if (settingsManager.isContinuousListeningEnabled() && 
            settingsManager.isVoiceModelDownloaded() &&
            !ContinuousListeningService.isRunning()) {
            Logger.i(TAG, "Restoring continuous listening service")
            ContinuousListeningService.start(this)
        }
    }

    override fun onResume() {
        super.onResume()
        Logger.d(TAG, "onResume - checking for settings changes")
        
        // 同步修复状态
        KeepAliveManager.syncFixState(this)
        
        // Update Shizuku status and rebind if needed
        updateShizukuStatus()
        if (!componentManager.isServiceConnected && hasShizukuPermission()) {
            Logger.i(TAG, "Service not connected, attempting to rebind")
            bindUserService()
        }
        
        // Update overlay permission status (user may have granted it)
        updateOverlayPermissionStatus()
        
        // Update keyboard status (user may have enabled it)
        updateKeyboardStatus()
        
        // Update battery optimization status
        updateBatteryOptimizationStatus()
        
        // Re-setup floating window callbacks if service is running
        FloatingWindowService.getInstance()?.let { service ->
            service.setStopTaskCallback {
                Logger.d(TAG, "stopTaskCallback invoked from floating window (onResume)")
                cancelTask()
            }
            service.setStartTaskCallback { task ->
                startTaskFromFloatingWindow(task)
            }
            service.setResetAgentCallback {
                Logger.d(TAG, "resetAgentCallback invoked from floating window (onResume)")
                componentManager.phoneAgent?.reset()
            }
            service.setPauseTaskCallback {
                Logger.d(TAG, "pauseTaskCallback invoked from floating window (onResume)")
                pauseTask()
            }
            service.setResumeTaskCallback {
                Logger.d(TAG, "resumeTaskCallback invoked from floating window (onResume)")
                resumeTask()
            }
        }
        
        // Only reinitialize if service is connected and we need to refresh
        if (componentManager.isServiceConnected) {
            // Check if settings actually changed before reinitializing
            // But NEVER reinitialize while a task is running or paused - this would cancel the task!
            val isTaskActive = componentManager.phoneAgent?.let { 
                it.isRunning() || it.isPaused() 
            } ?: false
            
            if (!isTaskActive && componentManager.settingsManager.hasConfigChanged()) {
                componentManager.reinitializeAgent()
            }
            componentManager.setPhoneAgentListener(this)
            setupConfirmationCallback()
            updateTaskButtonStates()
        }
    }

    override fun onDestroy() {
        Logger.i(TAG, "onDestroy - cleaning up")
        
        // Release voice input manager
        voiceInputManager?.release()
        
        // Unregister wake word receiver
        try {
            unregisterReceiver(wakeWordReceiver)
        } catch (e: Exception) {
            Logger.e(TAG, "Error unregistering wake word receiver", e)
        }
        
        super.onDestroy()
        
        // Remove Shizuku listeners
        Shizuku.removeRequestPermissionResultListener(onRequestPermissionResultListener)
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)

        // Cancel any running task
        componentManager.phoneAgent?.cancel()
        
        // Unbind user service
        if (componentManager.isServiceConnected) {
            try {
                Shizuku.unbindUserService(userServiceArgs, userServiceConnection, true)
            } catch (e: Exception) {
                Logger.e(TAG, "Error unbinding user service", e)
            }
        }
        
        // Note: Don't stop FloatingWindowService here - it should run independently
        // The service will be stopped when user explicitly closes it or the app process is killed
    }

    /**
     * Initializes all view references from the layout.
     *
     * Binds all UI components to their corresponding view IDs.
     */
    private fun initViews() {
        // Shizuku status views
        statusText = findViewById(R.id.statusText)
        shizukuStatusIndicator = findViewById(R.id.shizukuStatusIndicator)
        shizukuButtonsRow = findViewById(R.id.shizukuButtonsRow)
        requestPermissionBtn = findViewById(R.id.requestPermissionBtn)
        openShizukuBtn = findViewById(R.id.openShizukuBtn)
        settingsBtn = findViewById(R.id.settingsBtn)

        // Overlay permission views
        overlayPermissionCard = findViewById(R.id.overlayPermissionCard)
        overlayStatusIcon = findViewById(R.id.overlayStatusIcon)
        overlayStatusText = findViewById(R.id.overlayStatusText)
        requestOverlayBtn = findViewById(R.id.requestOverlayBtn)

        // Keyboard views
        keyboardCard = findViewById(R.id.keyboardCard)
        keyboardStatusIcon = findViewById(R.id.keyboardStatusIcon)
        keyboardStatusText = findViewById(R.id.keyboardStatusText)
        enableKeyboardBtn = findViewById(R.id.enableKeyboardBtn)

        // Battery optimization views
        batteryOptCard = findViewById(R.id.batteryOptCard)
        batteryStatusIcon = findViewById(R.id.batteryStatusIcon)
        batteryStatusText = findViewById(R.id.batteryStatusText)
        requestBatteryOptBtn = findViewById(R.id.requestBatteryOptBtn)

        // Task input views
        taskInputLayout = findViewById(R.id.taskInputLayout)
        taskInput = findViewById(R.id.taskInput)
        startTaskBtn = findViewById(R.id.startTaskBtn)
        cancelTaskBtn = findViewById(R.id.cancelTaskBtn)
        btnSelectTemplate = findViewById(R.id.btnSelectTemplate)
        btnVoiceInput = findViewById(R.id.btnVoiceInput)

        // Task status views
        taskStatusIndicator = findViewById(R.id.taskStatusIndicator)
        taskStatusText = findViewById(R.id.taskStatusText)
        stepCounterText = findViewById(R.id.stepCounterText)
        runningSection = findViewById(R.id.runningSection)
    }

    /**
     * Sets up click listeners and text watchers for all interactive views.
     *
     * Configures button click handlers, navigation actions, and input monitoring.
     */
    private fun setupListeners() {
        // Shizuku permission button
        requestPermissionBtn.setOnClickListener {
            requestShizukuPermission()
        }

        // Open Shizuku button
        openShizukuBtn.setOnClickListener {
            openShizukuApp()
        }

        // Settings button
        settingsBtn.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // History button
        findViewById<View>(R.id.historyBtn).setOnClickListener {
            startActivity(Intent(this, com.kevinluo.autoglm.history.HistoryActivity::class.java))
        }

        // Floating window button - open floating window
        findViewById<ImageButton>(R.id.floatingWindowBtn).setOnClickListener {
            openFloatingWindow()
        }

        // Overlay permission button
        requestOverlayBtn.setOnClickListener {
            FloatingWindowService.requestOverlayPermission(this)
        }

        // Keyboard enable button
        enableKeyboardBtn.setOnClickListener {
            // Open input method settings to enable AutoGLM Keyboard
            com.kevinluo.autoglm.input.KeyboardHelper.openInputMethodSettings(this)
        }

        // Battery optimization button
        requestBatteryOptBtn?.setOnClickListener {
            KeepAliveManager.requestIgnoreBatteryOptimizations(this)
        }

        // Start task button
        startTaskBtn.setOnClickListener {
            startTask()
        }

        // Cancel task button
        cancelTaskBtn.setOnClickListener {
            cancelTask()
        }
        
        // Select template button
        btnSelectTemplate.setOnClickListener {
            showTemplateSelectionDialog()
        }

        // Voice input button
        btnVoiceInput.setOnClickListener {
            onVoiceButtonClick()
        }

        // Enable start button when task input has text
        taskInput.setOnFocusChangeListener { _, _ ->
            updateTaskButtonStates()
        }
        
        // Watch for text changes to enable/disable start button
        taskInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                updateTaskButtonStates()
            }
        })
    }

    /**
     * Opens the floating window for task input and control.
     *
     * Checks for overlay permission before starting the floating window service.
     * Sets up all necessary callbacks for task control and minimizes the app.
     */
    private fun openFloatingWindow() {
        if (!FloatingWindowService.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.toast_overlay_permission_required, Toast.LENGTH_LONG).show()
            FloatingWindowService.requestOverlayPermission(this)
            return
        }

        // 保存悬浮窗启用状态
        ServiceStateManager.setFloatingWindowEnabled(this, true)

        // Start floating window service (not as foreground service, overlay window keeps it alive)
        val serviceIntent = Intent(this, FloatingWindowService::class.java)
        startService(serviceIntent)

        // Setup callbacks after service starts
        android.os.Handler(mainLooper).postDelayed({
            FloatingWindowService.getInstance()?.let { service ->
                service.setStopTaskCallback {
                    Logger.d(TAG, "stopTaskCallback invoked from floating window (openFloatingWindow)")
                    cancelTask()
                }
                service.setStartTaskCallback { task ->
                    startTaskFromFloatingWindow(task)
                }
                service.setResetAgentCallback {
                    Logger.d(TAG, "resetAgentCallback invoked from floating window (openFloatingWindow)")
                    componentManager.phoneAgent?.reset()
                }
                service.setPauseTaskCallback {
                    Logger.d(TAG, "pauseTaskCallback invoked from floating window (openFloatingWindow)")
                    pauseTask()
                }
                service.setResumeTaskCallback {
                    Logger.d(TAG, "resumeTaskCallback invoked from floating window (openFloatingWindow)")
                    resumeTask()
                }
                service.show()
            }
        }, 100)

        // Minimize app to background
        moveTaskToBack(true)
    }

    /**
     * Starts a task from the floating window input.
     *
     * Validates the agent state, resets if necessary, and launches
     * the task execution in a coroutine.
     *
     * @param taskDescription The task description entered in the floating window
     */
    private fun startTaskFromFloatingWindow(taskDescription: String) {
        val agent = componentManager.phoneAgent
        if (agent == null) {
            Logger.e(TAG, "PhoneAgent not initialized")
            FloatingWindowService.getInstance()?.showResult("Agent 未初始化", false)
            return
        }

        // Reset agent if it was previously cancelled or in a bad state
        if (agent.getState() != com.kevinluo.autoglm.agent.AgentState.IDLE) {
            Logger.d(TAG, "Agent not in IDLE state (${agent.getState()}), resetting...")
            agent.reset()
        }

        if (agent.isRunning()) {
            Logger.w(TAG, "Task already running")
            return
        }

        // Ensure stopTaskCallback is set
        FloatingWindowService.getInstance()?.setStopTaskCallback {
            Logger.d(TAG, "stopTaskCallback invoked from floating window (startTaskFromFloatingWindow)")
            cancelTask()
        }

        // Update floating window status
        FloatingWindowService.getInstance()?.updateStatus(TaskStatus.RUNNING)

        // Update main activity UI
        runOnUiThread {
            taskInput.setText(taskDescription)
            updateTaskStatus(TaskStatus.RUNNING)
            updateTaskButtonStates()
        }

        Logger.i(TAG, "Starting task from floating window: $taskDescription")

        // Run task in coroutine
        lifecycleScope.launch {
            try {
                agent.setListener(this@MainActivity)
                val result = agent.run(taskDescription)

                withContext(Dispatchers.Main) {
                    if (result.success) {
                        updateTaskStatus(TaskStatus.COMPLETED)
                    } else {
                        updateTaskStatus(TaskStatus.FAILED)
                    }
                    updateTaskButtonStates()
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Task execution error", e)
                withContext(Dispatchers.Main) {
                    updateTaskStatus(TaskStatus.FAILED)
                    updateTaskButtonStates()
                    FloatingWindowService.getInstance()?.showResult("错误: ${e.message}", false)
                }
            }
        }
    }

    /**
     * Sets up Shizuku event listeners.
     *
     * Registers listeners for permission results, binder received, and binder dead events.
     */
    private fun setupShizukuListeners() {
        Shizuku.addRequestPermissionResultListener(onRequestPermissionResultListener)
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
    }
    
    /**
     * Opens the Shizuku app or Play Store if not installed.
     *
     * Attempts to launch Shizuku directly, or opens the Play Store
     * listing if Shizuku is not installed.
     */
    private fun openShizukuApp() {
        val shizukuPackage = "moe.shizuku.privileged.api"
        val launchIntent = packageManager.getLaunchIntentForPackage(shizukuPackage)
        if (launchIntent != null) {
            startActivity(launchIntent)
        } else {
            // Shizuku not installed, open Play Store
            try {
                val marketUri = android.net.Uri.parse("market://details?id=$shizukuPackage")
                startActivity(Intent(Intent.ACTION_VIEW, marketUri))
            } catch (e: Exception) {
                val webUri = android.net.Uri.parse(
                    "https://play.google.com/store/apps/details?id=$shizukuPackage"
                )
                startActivity(Intent(Intent.ACTION_VIEW, webUri))
            }
        }
    }

    /**
     * Initializes the PhoneAgent with all required dependencies.
     *
     * Called after UserService is connected. Sets up the agent listener
     * and confirmation callback for sensitive operations.
     *
     */
    private fun initializePhoneAgent() {
        if (!componentManager.isServiceConnected) {
            Logger.w(TAG, "Cannot initialize PhoneAgent: service not connected")
            return
        }
        
        // Set up listener
        componentManager.setPhoneAgentListener(this)
        
        // Setup confirmation callback
        setupConfirmationCallback()
        
        updateTaskButtonStates()
        Logger.i(TAG, "PhoneAgent initialized successfully")
    }
    
    /**
     * Sets up the confirmation callback for sensitive operations.
     *
     * Configures the ActionHandler to use the floating window for
     * user confirmations, takeover requests, and interaction options.
     */
    private fun setupConfirmationCallback() {
        componentManager.setConfirmationCallback(object : ActionHandler.ConfirmationCallback {
            override suspend fun onConfirmationRequired(message: String): Boolean {
                return withContext(Dispatchers.Main) {
                    // Use floating window for confirmation
                    var result = true
                    FloatingWindowService.getInstance()?.showConfirmation(message) { confirmed ->
                        result = confirmed
                    }
                    result
                }
            }

            override suspend fun onTakeOverRequested(message: String) {
                withContext(Dispatchers.Main) {
                    FloatingWindowService.getInstance()?.showTakeOver(message) {}
                }
            }

            override suspend fun onInteractionRequired(options: List<String>?): Int {
                return withContext(Dispatchers.Main) {
                    var selectedIndex = -1
                    options?.let {
                        FloatingWindowService.getInstance()?.showInteract(it) { index ->
                            selectedIndex = index
                        }
                    }
                    selectedIndex
                }
            }
        })
    }

    /**
     * Starts the task execution from the main activity input.
     *
     * Validates the task description, checks agent state, starts the
     * floating window service, and launches the task in a coroutine.
     *
     */
    private fun startTask() {
        val taskDescription = taskInput.text?.toString()?.trim() ?: ""
        
        // Validate task description
        if (taskDescription.isBlank()) {
            Toast.makeText(this, R.string.toast_task_empty, Toast.LENGTH_SHORT).show()
            taskInputLayout.error = getString(R.string.toast_task_empty)
            return
        }
        
        taskInputLayout.error = null
        
        val agent = componentManager.phoneAgent
        if (agent == null) {
            Logger.e(TAG, "PhoneAgent not initialized")
            return
        }
        
        // Check if already running
        if (agent.isRunning()) {
            Logger.w(TAG, "Task already running")
            return
        }
        
        // Start floating window service if overlay permission granted
        if (FloatingWindowService.canDrawOverlays(this)) {
            Logger.d(TAG, "startTask: Starting floating window service")
            val serviceIntent = Intent(this, FloatingWindowService::class.java)
            startService(serviceIntent)
        } else {
            Toast.makeText(this, R.string.toast_overlay_permission_required, Toast.LENGTH_LONG).show()
            FloatingWindowService.requestOverlayPermission(this)
            return
        }
        
        // Update UI state - manually set running state since agent.run() hasn't started yet
        updateTaskStatus(TaskStatus.RUNNING)
        // Manually update UI for running state
        startTaskBtn.visibility = View.GONE
        runningSection.visibility = View.VISIBLE
        cancelTaskBtn.isEnabled = true
        taskInput.isEnabled = false
        
        // 获取 WakeLock 保持任务执行
        KeepAliveManager.acquireTaskWakeLock(this)
        
        // 记录任务执行时间
        ServiceStateManager.recordTaskExecution(this)
        
        Logger.i(TAG, "Starting task: $taskDescription")
        
        // Run task in coroutine
        lifecycleScope.launch {
            // Set up callbacks immediately after service starts
            withContext(Dispatchers.Main) {
                // Wait a short time for service to initialize
                delay(100)
                val floatingWindow = FloatingWindowService.getInstance()
                Logger.d(TAG, "startTask: FloatingWindowService instance = $floatingWindow")
                floatingWindow?.setStopTaskCallback {
                    Logger.d(TAG, "stopTaskCallback invoked from floating window")
                    cancelTask()
                }
                floatingWindow?.setStartTaskCallback { task ->
                    startTaskFromFloatingWindow(task)
                }
                floatingWindow?.setResetAgentCallback {
                    Logger.d(TAG, "resetAgentCallback invoked from floating window (startTask)")
                    componentManager.phoneAgent?.reset()
                }
                floatingWindow?.setPauseTaskCallback {
                    Logger.d(TAG, "pauseTaskCallback invoked from floating window (startTask)")
                    pauseTask()
                }
                floatingWindow?.setResumeTaskCallback {
                    Logger.d(TAG, "resumeTaskCallback invoked from floating window (startTask)")
                    resumeTask()
                }
                Logger.d(TAG, "startTask: Calling updateStatus(RUNNING) on floating window")
                floatingWindow?.updateStatus(TaskStatus.RUNNING)
                floatingWindow?.show()
            }
            
            // Minimize app to let agent work
            withContext(Dispatchers.Main) {
                moveTaskToBack(true)
            }
            
            try {
                val result = agent.run(taskDescription)
                
                withContext(Dispatchers.Main) {
                    // 释放 WakeLock
                    KeepAliveManager.releaseTaskWakeLock()
                    
                    if (result.success) {
                        Logger.i(TAG, "Task completed: ${result.message}")
                        updateTaskStatus(TaskStatus.COMPLETED)
                    } else {
                        Logger.w(TAG, "Task failed: ${result.message}")
                        updateTaskStatus(TaskStatus.FAILED)
                    }
                    updateTaskButtonStates()
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Task error", e)
                withContext(Dispatchers.Main) {
                    // 释放 WakeLock
                    KeepAliveManager.releaseTaskWakeLock()
                    
                    updateTaskStatus(TaskStatus.FAILED)
                    updateTaskButtonStates()
                }
            }
        }
    }

    /**
     * Cancels the currently running task.
     *
     * Cancels the agent, resets its state, and updates the UI
     * to reflect the cancelled status.
     *
     */
    private fun cancelTask() {
        Logger.i(TAG, "Cancelling task")
        
        // 释放 WakeLock
        KeepAliveManager.releaseTaskWakeLock()
        
        // Cancel the agent - this will cancel any ongoing network requests
        componentManager.phoneAgent?.cancel()
        
        // Reset the agent state so it can accept new tasks
        componentManager.phoneAgent?.reset()
        
        Toast.makeText(this, R.string.toast_task_cancelled, Toast.LENGTH_SHORT).show()
        updateTaskStatus(TaskStatus.FAILED)
        updateTaskButtonStates()
        
        // Update floating window to show cancelled state
        // Use the same message as PhoneAgent for consistency
        val cancellationMessage = PhoneAgent.CANCELLATION_MESSAGE
        FloatingWindowService.getInstance()?.showResult(cancellationMessage, false)
    }
    
    /**
     * Pauses the currently running task.
     *
     * Requests the agent to pause and updates the UI status accordingly.
     */
    private fun pauseTask() {
        Logger.i(TAG, "Pausing task")
        
        val paused = componentManager.phoneAgent?.pause() == true
        if (paused) {
            updateTaskStatus(TaskStatus.PAUSED)
            FloatingWindowService.getInstance()?.updateStatus(TaskStatus.PAUSED)
        }
    }
    
    /**
     * Resumes a paused task.
     *
     * Requests the agent to resume and updates the UI status accordingly.
     */
    private fun resumeTask() {
        Logger.i(TAG, "Resuming task")
        
        val resumed = componentManager.phoneAgent?.resume() == true
        if (resumed) {
            updateTaskStatus(TaskStatus.RUNNING)
            FloatingWindowService.getInstance()?.updateStatus(TaskStatus.RUNNING)
        }
    }

    /**
     * Updates the task button states based on current conditions.
     *
     * Enables or disables buttons based on service connection status,
     * agent availability, task input, and running state.
     */
    private fun updateTaskButtonStates() {
        val hasService = componentManager.isServiceConnected
        val hasAgent = componentManager.phoneAgent != null
        val hasTaskText = !taskInput.text.isNullOrBlank()
        val isRunning = componentManager.phoneAgent?.isRunning() == true
        
        // Show/hide sections based on running state
        startTaskBtn.visibility = if (isRunning) View.GONE else View.VISIBLE
        runningSection.visibility = if (isRunning) View.VISIBLE else View.GONE
        
        startTaskBtn.isEnabled = hasService && hasAgent && hasTaskText && !isRunning
        cancelTaskBtn.isEnabled = isRunning
        taskInput.isEnabled = !isRunning
        
        Logger.d(
            TAG,
            "Button states updated: service=$hasService, agent=$hasAgent, " +
                "text=$hasTaskText, running=$isRunning"
        )
    }

    /**
     * Updates the task status display.
     *
     * Updates the status text, indicator color, and floating window
     * to reflect the current task status.
     *
     * @param status The new task status to display
     *
     */
    private fun updateTaskStatus(status: TaskStatus) {
        val (text, colorRes) = when (status) {
            TaskStatus.IDLE -> getString(R.string.task_status_idle) to R.color.status_idle
            TaskStatus.RUNNING -> getString(R.string.task_status_running) to R.color.status_running
            TaskStatus.PAUSED -> getString(R.string.task_status_paused) to R.color.status_paused
            TaskStatus.COMPLETED -> getString(R.string.task_status_completed) to R.color.status_completed
            TaskStatus.FAILED -> getString(R.string.task_status_failed) to R.color.status_failed
            TaskStatus.WAITING_CONFIRMATION -> "等待确认" to R.color.status_waiting
            TaskStatus.WAITING_TAKEOVER -> "等待接管" to R.color.status_waiting
        }
        
        taskStatusText.text = text
        
        val drawable = taskStatusIndicator.background as? GradientDrawable
            ?: GradientDrawable().also { taskStatusIndicator.background = it }
        drawable.shape = GradientDrawable.OVAL
        drawable.setColor(ContextCompat.getColor(this, colorRes))
        
        // Also update floating window
        FloatingWindowService.getInstance()?.updateStatus(status)
    }

    // region PhoneAgentListener Implementation

    /**
     * Called when a step starts.
     *
     * Updates the step counter and floating window with the new step number.
     *
     * @param stepNumber The current step number
     *
     */
    override fun onStepStarted(stepNumber: Int) {
        runOnUiThread {
            currentStepNumber = stepNumber
            currentThinking = ""
            stepCounterText.text = getString(R.string.step_counter_format, stepNumber)
            FloatingWindowService.getInstance()?.updateStepNumber(stepNumber)
        }
    }

    /**
     * Called when thinking is updated.
     *
     * Stores the current thinking text for display in the floating window.
     *
     * @param thinking The model's thinking text
     *
     */
    override fun onThinkingUpdate(thinking: String) {
        runOnUiThread {
            currentThinking = thinking
        }
    }

    /**
     * Called when an action is executed.
     *
     * Adds the step to the floating window waterfall display.
     *
     * @param action The action that was executed
     *
     */
    override fun onActionExecuted(action: AgentAction) {
        runOnUiThread {
            // Add step to floating window waterfall
            FloatingWindowService.getInstance()?.addStep(currentStepNumber, currentThinking, action)
        }
    }

    /**
     * Called when task completes successfully.
     *
     * Updates the UI to show completion status and displays the result
     * in the floating window.
     *
     * @param message The completion message
     *
     */
    override fun onTaskCompleted(message: String) {
        runOnUiThread {
            updateTaskStatus(TaskStatus.COMPLETED)
            FloatingWindowService.getInstance()?.showResult(message, true)
            updateTaskButtonStates()
            // Keep floating window open for user to see the result
        }
    }

    /**
     * Called when task fails.
     *
     * Updates the UI to show failure status and displays the error
     * in the floating window.
     *
     * @param error The error message
     *
     */
    override fun onTaskFailed(error: String) {
        runOnUiThread {
            updateTaskStatus(TaskStatus.FAILED)
            FloatingWindowService.getInstance()?.showResult(error, false)
            updateTaskButtonStates()
            // Keep floating window open for user to see the error
        }
    }

    /**
     * Called when screenshot capture starts.
     *
     * Note: Floating window hide is handled by ScreenshotService.
     *
     */
    override fun onScreenshotStarted() {
        // Floating window hide is handled by ScreenshotService
    }

    /**
     * Called when screenshot capture completes.
     *
     * Note: Floating window show is handled by ScreenshotService.
     *
     */
    override fun onScreenshotCompleted() {
        // Floating window show is handled by ScreenshotService
    }

    /**
     * Called when floating window needs to be refreshed.
     *
     * This happens after launching another app to ensure the overlay stays visible.
     */
    override fun onFloatingWindowRefreshNeeded() {
        Logger.d(TAG, "onFloatingWindowRefreshNeeded called")
        runOnUiThread {
            FloatingWindowService.getInstance()?.bringToFront()
        }
    }

    // endregion

    // region Shizuku Methods

    /**
     * Updates the Shizuku connection status display.
     *
     * Checks Shizuku binder status, permission, and service connection,
     * then updates the UI to reflect the current state.
     */
    private fun updateShizukuStatus() {
        val isBinderAlive = try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            false
        }

        val hasPermission = hasShizukuPermission()
        val serviceConnected = componentManager.isServiceConnected

        val statusMessage = when {
            !isBinderAlive -> getString(R.string.shizuku_status_not_running)
            !hasPermission -> getString(R.string.shizuku_status_no_permission)
            !serviceConnected -> getString(R.string.shizuku_status_connecting)
            else -> getString(R.string.shizuku_status_connected)
        }
        
        val statusColor = when {
            !isBinderAlive -> R.color.status_failed
            !hasPermission -> R.color.status_waiting
            !serviceConnected -> R.color.status_waiting
            else -> R.color.status_running
        }

        runOnUiThread {
            statusText.text = statusMessage
            shizukuStatusIndicator.background.setTint(ContextCompat.getColor(this, statusColor))
            
            // Show buttons based on Shizuku state
            if (serviceConnected) {
                // Connected - hide buttons row
                shizukuButtonsRow.visibility = View.GONE
            } else {
                // Not connected - show buttons row
                shizukuButtonsRow.visibility = View.VISIBLE
                // Open Shizuku button - always visible when not connected
                openShizukuBtn.visibility = View.VISIBLE
                // Permission button - always visible, but disabled when Shizuku not running
                requestPermissionBtn.visibility = View.VISIBLE
                requestPermissionBtn.isEnabled = isBinderAlive
            }
            
            updateTaskButtonStates()
        }
    }

    /**
     * Checks if Shizuku permission is granted.
     *
     * @return true if Shizuku is running and permission is granted, false otherwise
     */
    private fun hasShizukuPermission(): Boolean {
        return try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Requests Shizuku permission from the user.
     *
     * Validates Shizuku state before requesting permission and handles
     * various edge cases like Shizuku not running or old version.
     */
    private fun requestShizukuPermission() {
        if (!Shizuku.pingBinder()) {
            Toast.makeText(this, R.string.toast_shizuku_not_running, Toast.LENGTH_LONG).show()
            return
        }

        if (Shizuku.isPreV11()) {
            Toast.makeText(this, R.string.toast_shizuku_version_low, Toast.LENGTH_LONG).show()
            return
        }

        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, R.string.toast_shizuku_already_granted, Toast.LENGTH_SHORT).show()
            bindUserService()
            return
        }

        if (Shizuku.shouldShowRequestPermissionRationale()) {
            Toast.makeText(this, R.string.toast_shizuku_grant_in_app, Toast.LENGTH_LONG).show()
            return
        }

        Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
    }

    /**
     * Binds the Shizuku user service.
     *
     * Attempts to bind the user service if Shizuku permission is granted.
     */
    private fun bindUserService() {
        if (!hasShizukuPermission()) return
        try {
            Logger.i(TAG, "Binding user service")
            Shizuku.bindUserService(userServiceArgs, userServiceConnection)
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to bind service", e)
        }
    }

    // endregion

    // region Helper Methods

    /**
     * Formats an agent action for display.
     *
     * @param action The agent action to format
     * @return A human-readable string representation of the action
     */
    private fun formatAction(action: AgentAction): String = action.formatForDisplay()

    // endregion
    
    // region Task Templates
    
    /**
     * Shows a dialog to select a task template.
     *
     * Displays available task templates from settings and allows
     * the user to select one to populate the task input field.
     */
    private fun showTemplateSelectionDialog() {
        val templates = componentManager.settingsManager.getTaskTemplates()
        
        if (templates.isEmpty()) {
            Toast.makeText(this, R.string.settings_no_templates, Toast.LENGTH_SHORT).show()
            // Offer to go to settings to add templates
            AlertDialog.Builder(this)
                .setTitle(R.string.task_select_template)
                .setMessage(R.string.settings_no_templates)
                .setPositiveButton(R.string.settings_title) { _, _ ->
                    startActivity(Intent(this, SettingsActivity::class.java))
                }
                .setNegativeButton(R.string.dialog_cancel, null)
                .show()
            return
        }
        
        val templateNames = templates.map { it.name }.toTypedArray()
        
        AlertDialog.Builder(this)
            .setTitle(R.string.task_select_template)
            .setItems(templateNames) { _, which ->
                val selectedTemplate = templates[which]
                taskInput.setText(selectedTemplate.description)
                updateTaskButtonStates()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }
    
    // endregion

    // region Voice Input
    
    /**
     * Handles voice button click.
     * Checks permissions and model status before starting recording.
     */
    private fun onVoiceButtonClick() {
        // Check audio permission first
        if (!hasAudioPermission()) {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        
        // Initialize voice input manager if needed
        if (voiceInputManager == null) {
            voiceInputManager = VoiceInputManager(this)
        }
        
        // Check if model is downloaded
        if (!voiceInputManager!!.isModelReady()) {
            showModelDownloadDialog()
            return
        }
        
        // Toggle recording
        if (isVoiceRecording) {
            stopVoiceRecording()
        } else {
            startVoiceRecording()
        }
    }
    
    /**
     * Checks if audio recording permission is granted.
     */
    private fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * Shows dialog to confirm model download.
     */
    private fun showModelDownloadDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_voice_download_confirm, null)
        
        AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton(R.string.voice_download_confirm) { _, _ ->
                startModelDownload()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }
    
    /**
     * Starts the model download process.
     */
    private fun startModelDownload() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_voice_download_progress, null)
        val progressBar = dialogView.findViewById<android.widget.ProgressBar>(R.id.downloadProgressBar)
        val progressText = dialogView.findViewById<TextView>(R.id.downloadProgressText)
        
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .setNegativeButton(R.string.dialog_cancel) { _, _ ->
                voiceInputManager?.getModelManager()?.cancelDownload()
            }
            .create()
        
        dialog.show()
        
        lifecycleScope.launch {
            voiceInputManager?.getModelManager()?.downloadModel(object : VoiceModelDownloadListener {
                override fun onDownloadStarted() {
                    runOnUiThread {
                        progressText.text = getString(R.string.voice_downloading)
                    }
                }
                
                override fun onDownloadProgress(progress: Int, downloadedBytes: Long, totalBytes: Long) {
                    runOnUiThread {
                        progressBar.progress = progress
                        val downloadedMB = downloadedBytes / (1024 * 1024)
                        val totalMB = if (totalBytes > 0) totalBytes / (1024 * 1024) else VoiceModelManager.ESTIMATED_MODEL_SIZE_MB.toLong()
                        progressText.text = getString(R.string.voice_download_progress, downloadedMB, totalMB)
                    }
                }
                
                override fun onDownloadCompleted(modelPath: String) {
                    runOnUiThread {
                        dialog.dismiss()
                        Toast.makeText(this@MainActivity, R.string.voice_download_complete, Toast.LENGTH_SHORT).show()
                    }
                }
                
                override fun onDownloadFailed(error: String) {
                    runOnUiThread {
                        dialog.dismiss()
                        Toast.makeText(this@MainActivity, getString(R.string.voice_download_failed, error), Toast.LENGTH_LONG).show()
                    }
                }
                
                override fun onDownloadCancelled() {
                    runOnUiThread {
                        dialog.dismiss()
                    }
                }
            })
        }
    }
    
    /**
     * Starts voice recording.
     */
    private fun startVoiceRecording() {
        // 清空输入框
        taskInput.setText("")
        
        // 显示语音录音对话框
        val dialog = VoiceRecordingDialog(
            context = this,
            voiceInputManager = voiceInputManager!!,
            onResult = { result ->
                if (result.text.isNotBlank()) {
                    val currentText = taskInput.text?.toString() ?: ""
                    if (currentText.isBlank()) {
                        taskInput.setText(result.text)
                    } else {
                        taskInput.setText("$currentText ${result.text}")
                    }
                    taskInput.setSelection(taskInput.text?.length ?: 0)
                    
                    // 用户已在对话框内确认，直接运行任务
                    startTask()
                }
            },
            onError = { error ->
                val errorMsg = when (error) {
                    VoiceError.PermissionDenied -> getString(R.string.voice_permission_denied)
                    VoiceError.ModelNotDownloaded -> getString(R.string.voice_model_not_downloaded)
                    VoiceError.ModelLoadFailed -> getString(R.string.voice_model_load_failed)
                    VoiceError.RecordingFailed -> getString(R.string.voice_recording_failed)
                    VoiceError.RecognitionFailed -> getString(R.string.voice_recognition_failed)
                    VoiceError.NetworkError -> getString(R.string.voice_network_error)
                    is VoiceError.Unknown -> error.message
                }
                Toast.makeText(this, errorMsg, Toast.LENGTH_SHORT).show()
            }
        )
        dialog.show()
    }
    
    /**
     * Stops voice recording.
     */
    private fun stopVoiceRecording() {
        voiceInputManager?.stopRecording()
    }
    
    /**
     * Handles wake word detection.
     * Shows voice recording dialog for user to continue speaking.
     */
    private fun onWakeWordDetected(wakeWord: String?, recognizedText: String?) {
        runOnUiThread {
            Logger.i(TAG, "Wake word detected: $wakeWord, text: $recognizedText")
            
            // 播放提示音
            playWakeSound()
            
            // 显示 Toast 提示
            Toast.makeText(this, getString(R.string.voice_wake_word_detected, wakeWord), Toast.LENGTH_SHORT).show()
            
            // 弹出语音输入对话框
            showVoiceInputDialog()
        }
    }
    
    /**
     * 播放唤醒提示音
     */
    private fun playWakeSound() {
        try {
            val notification = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
            val ringtone = android.media.RingtoneManager.getRingtone(this, notification)
            ringtone?.play()
        } catch (e: Exception) {
            Logger.e(TAG, "Error playing wake sound", e)
        }
    }
    
    /**
     * 显示语音输入对话框
     */
    private fun showVoiceInputDialog() {
        // 初始化语音输入管理器
        if (voiceInputManager == null) {
            voiceInputManager = VoiceInputManager(this)
        }
        
        // 检查模型是否已下载
        if (!voiceInputManager!!.isModelReady()) {
            Toast.makeText(this, R.string.voice_model_not_downloaded, Toast.LENGTH_SHORT).show()
            return
        }
        
        // 清空输入框
        taskInput.setText("")
        
        // 显示语音录音对话框
        val dialog = VoiceRecordingDialog(
            context = this,
            voiceInputManager = voiceInputManager!!,
            onResult = { result ->
                if (result.text.isNotBlank()) {
                    val currentText = taskInput.text?.toString() ?: ""
                    if (currentText.isBlank()) {
                        taskInput.setText(result.text)
                    } else {
                        taskInput.setText("$currentText ${result.text}")
                    }
                    taskInput.setSelection(taskInput.text?.length ?: 0)
                    
                    // 用户已在对话框内确认，直接运行任务
                    startTask()
                }
            },
            onError = { error ->
                val errorMsg = when (error) {
                    VoiceError.PermissionDenied -> getString(R.string.voice_permission_denied)
                    VoiceError.ModelNotDownloaded -> getString(R.string.voice_model_not_downloaded)
                    VoiceError.ModelLoadFailed -> getString(R.string.voice_model_load_failed)
                    VoiceError.RecordingFailed -> getString(R.string.voice_recording_failed)
                    VoiceError.RecognitionFailed -> getString(R.string.voice_recognition_failed)
                    VoiceError.NetworkError -> getString(R.string.voice_network_error)
                    is VoiceError.Unknown -> error.message
                }
                Toast.makeText(this, errorMsg, Toast.LENGTH_SHORT).show()
            }
        )
        dialog.show()
    }
    
    // endregion

    companion object {
        private const val TAG = "MainActivity"
        private const val SHIZUKU_PERMISSION_REQUEST_CODE = 100
    }
}
