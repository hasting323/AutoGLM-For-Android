package com.kevinluo.autoglm.voice

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.ComponentActivity
import com.google.android.material.button.MaterialButton
import com.kevinluo.autoglm.R
import com.kevinluo.autoglm.util.Logger
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 语音输入对话框
 * 
 * 显示在最上层，带有实时波形显示
 * 识别完成后在同一对话框内展示结果和倒计时
 */
class VoiceRecordingDialog(
    context: Context,
    private val voiceInputManager: VoiceInputManager,
    private val onResult: (VoiceRecognitionResult) -> Unit,
    private val onError: (VoiceError) -> Unit
) : Dialog(context, R.style.VoiceRecordingDialogTheme) {
    
    companion object {
        private const val TAG = "VoiceRecordingDialog"
        private const val AUTO_CONFIRM_SECONDS = 5
    }
    
    private lateinit var waveformView: VoiceWaveformView
    private lateinit var tvStatus: TextView
    private lateinit var tvTime: TextView
    private lateinit var btnStop: MaterialButton
    private lateinit var ivMic: ImageView
    
    // 结果视图
    private lateinit var recordingContainer: LinearLayout
    private lateinit var resultContainer: LinearLayout
    private lateinit var tvResultText: TextView
    private lateinit var btnCancel: MaterialButton
    private lateinit var btnConfirm: MaterialButton
    
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val isRecording = AtomicBoolean(false)
    private var recordingStartTime = 0L
    private var timerJob: Job? = null
    private var countdownJob: Job? = null
    
    private var pendingResult: VoiceRecognitionResult? = null
    
    private var backPressedCallback: OnBackPressedCallback? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_voice_recording)
        
        setupWindow()
        initViews()
        setupBackPressedCallback()
        startRecording()
    }
    
    /**
     * 设置返回键回调
     */
    private fun setupBackPressedCallback() {
        val activity = context as? ComponentActivity ?: return
        
        backPressedCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (pendingResult != null) {
                    cancelResult()
                } else {
                    voiceInputManager.cancelRecording()
                    isRecording.set(false)
                    dismiss()
                }
            }
        }
        
        activity.onBackPressedDispatcher.addCallback(backPressedCallback!!)
    }
    
    private fun setupWindow() {
        window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setGravity(Gravity.CENTER)
            
            setType(WindowManager.LayoutParams.TYPE_APPLICATION_PANEL)
            
            val params = attributes
            params.width = (context.resources.displayMetrics.widthPixels * 0.85).toInt()
            params.height = WindowManager.LayoutParams.WRAP_CONTENT
            params.dimAmount = 0.5f
            attributes = params
            
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        
        setCancelable(false)
        setCanceledOnTouchOutside(false)
    }
    
    private fun initViews() {
        waveformView = findViewById(R.id.waveformView)
        tvStatus = findViewById(R.id.tvRecordingStatus)
        tvTime = findViewById(R.id.tvRecordingTime)
        btnStop = findViewById(R.id.btnStopRecording)
        ivMic = findViewById(R.id.ivMicIcon)
        
        recordingContainer = findViewById(R.id.recordingContainer)
        resultContainer = findViewById(R.id.resultContainer)
        tvResultText = findViewById(R.id.tvResultText)
        btnCancel = findViewById(R.id.btnCancel)
        btnConfirm = findViewById(R.id.btnConfirm)
        
        btnStop.setOnClickListener {
            stopRecording()
        }
        
        btnCancel.setOnClickListener {
            cancelResult()
        }
        
        btnConfirm.setOnClickListener {
            confirmResult()
        }
    }
    
    private fun startRecording() {
        isRecording.set(true)
        recordingStartTime = System.currentTimeMillis()
        
        startTimer()
        
        voiceInputManager.setListener(object : VoiceInputListener {
            override fun onRecordingStarted() {
                Logger.d(TAG, "Recording started")
            }
            
            override fun onRecordingStopped() {
                scope.launch {
                    tvStatus.text = context.getString(R.string.voice_recognizing_hint)
                }
            }
            
            override fun onPartialResult(text: String) {
                // Not used
            }
            
            override fun onFinalResult(result: VoiceRecognitionResult) {
                scope.launch {
                    isRecording.set(false)
                    timerJob?.cancel()
                    showResult(result)
                }
            }
            
            override fun onError(error: VoiceError) {
                scope.launch {
                    isRecording.set(false)
                    dismiss()
                    onError(error)
                }
            }
            
            override fun onAudioSamples(samples: ShortArray, readSize: Int) {
                waveformView.updateFromSamples(samples, readSize)
            }
        })
        
        voiceInputManager.startRecording(scope)
    }
    
    private fun stopRecording() {
        isRecording.set(false)
        tvStatus.text = context.getString(R.string.voice_recognizing_hint)
        btnStop.isEnabled = false
        voiceInputManager.stopRecording()
    }
    
    private fun showResult(result: VoiceRecognitionResult) {
        pendingResult = result
        
        // 如果没有识别到文字，直接关闭
        if (result.text.isBlank()) {
            dismiss()
            onResult(result)
            return
        }
        
        // 切换到结果视图
        recordingContainer.visibility = View.GONE
        resultContainer.visibility = View.VISIBLE
        
        tvStatus.text = context.getString(R.string.voice_input_title)
        tvResultText.text = result.text
        
        // 开始倒计时
        startCountdown()
    }
    
    private fun startCountdown() {
        var secondsLeft = AUTO_CONFIRM_SECONDS
        updateConfirmButton(secondsLeft)
        
        countdownJob = scope.launch {
            while (isActive && secondsLeft > 0) {
                delay(1000)
                secondsLeft--
                updateConfirmButton(secondsLeft)
            }
            
            if (isActive && secondsLeft == 0) {
                confirmResult()
            }
        }
    }
    
    private fun updateConfirmButton(secondsLeft: Int) {
        btnConfirm.text = if (secondsLeft > 0) {
            context.getString(R.string.voice_confirm_with_countdown, secondsLeft)
        } else {
            context.getString(R.string.voice_confirm)
        }
    }
    
    private fun cancelResult() {
        countdownJob?.cancel()
        pendingResult = null
        dismiss()
    }
    
    private fun confirmResult() {
        countdownJob?.cancel()
        val result = pendingResult
        pendingResult = null
        dismiss()
        
        if (result != null) {
            onResult(result)
        }
    }
    
    private fun startTimer() {
        timerJob = scope.launch {
            while (isActive && isRecording.get()) {
                val elapsed = System.currentTimeMillis() - recordingStartTime
                val seconds = (elapsed / 1000) % 60
                val minutes = (elapsed / 1000) / 60
                tvTime.text = String.format("%02d:%02d", minutes, seconds)
                delay(100)
            }
        }
    }
    
    override fun dismiss() {
        isRecording.set(false)
        timerJob?.cancel()
        countdownJob?.cancel()
        scope.cancel()
        backPressedCallback?.remove()
        super.dismiss()
    }
}
