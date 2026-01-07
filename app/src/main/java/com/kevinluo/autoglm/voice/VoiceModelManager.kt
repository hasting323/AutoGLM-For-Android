package com.kevinluo.autoglm.voice

import android.content.Context
import com.kevinluo.autoglm.util.Logger
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 语音模型管理器
 * 
 * 负责语音识别模型的下载、解压、删除和状态管理
 * 
 * 性能优化点：
 * - 下载断点续传支持
 * - 优化解压性能（使用缓冲）
 * - 性能日志记录
 */
class VoiceModelManager(private val context: Context) {
    
    companion object {
        private const val TAG = "VoiceModelManager"
        
        // 模型下载地址 (ModelScope 中国镜像)
        private const val SENSE_VOICE_MODEL_URL = 
            "https://modelscope.cn/models/mariolux/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/master/model.int8.onnx"
        private const val SENSE_VOICE_TOKENS_URL = 
            "https://modelscope.cn/models/mariolux/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/master/tokens.txt"
        private const val VAD_MODEL_URL = 
            "https://modelscope.cn/models/pengzhendong/silero-vad/resolve/master/silero_vad.onnx"
        
        // 模型目录名
        private const val MODELS_DIR = "sherpa-onnx-models"
        private const val SENSE_VOICE_DIR = "sense-voice"
        private const val VAD_FILE = "silero_vad.onnx"
        
        // 模型文件名
        private const val ASR_MODEL_FILE = "model.int8.onnx"
        private const val TOKENS_FILE = "tokens.txt"
        
        // 预估模型大小（用于显示）
        const val ESTIMATED_MODEL_SIZE_MB = 230
        
        // 性能优化：缓冲区大小
        private const val DOWNLOAD_BUFFER_SIZE = 32 * 1024  // 32KB 下载缓冲区
        
        // 断点续传临时文件后缀
        private const val TEMP_FILE_SUFFIX = ".download"
    }
    
    private val modelsDir: File
        get() = File(context.filesDir, MODELS_DIR)
    
    private val senseVoiceDir: File
        get() = File(modelsDir, SENSE_VOICE_DIR)
    
    private val vadFile: File
        get() = File(modelsDir, VAD_FILE)
    
    private var downloadJob: Job? = null
    private val isCancelled = AtomicBoolean(false)
    
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
    
    /**
     * 检查模型是否已下载
     */
    fun isModelDownloaded(): Boolean {
        val asrModel = File(senseVoiceDir, ASR_MODEL_FILE)
        val tokens = File(senseVoiceDir, TOKENS_FILE)
        return asrModel.exists() && tokens.exists() && vadFile.exists()
    }
    
    /**
     * 获取模型路径
     */
    fun getModelPath(): String? {
        return if (isModelDownloaded()) senseVoiceDir.absolutePath else null
    }
    
    /**
     * 获取 VAD 模型路径
     */
    fun getVadModelPath(): String? {
        return if (vadFile.exists()) vadFile.absolutePath else null
    }
    
    /**
     * 获取已下载模型的大小（MB）
     */
    fun getDownloadedModelSizeMB(): Long {
        if (!isModelDownloaded()) return 0
        var totalSize = 0L
        senseVoiceDir.walkTopDown().forEach { file ->
            if (file.isFile) totalSize += file.length()
        }
        totalSize += vadFile.length()
        return totalSize / (1024 * 1024)
    }
    
    /**
     * 下载模型
     * 
     * 性能优化：
     * - 支持断点续传
     * - 使用优化的缓冲区大小
     * - 记录下载性能
     * 
     * @param listener 下载进度监听器
     */
    suspend fun downloadModel(listener: VoiceModelDownloadListener) {
        isCancelled.set(false)
        
        withContext(Dispatchers.IO) {
            val downloadStartTime = System.currentTimeMillis()
            
            try {
                listener.onDownloadStarted()
                Logger.i(TAG, "[Performance] Starting model download from hf-mirror.com")
                
                // 创建模型目录
                modelsDir.mkdirs()
                senseVoiceDir.mkdirs()
                
                // 下载 VAD 模型（较小，先下载）
                Logger.d(TAG, "[Performance] Downloading VAD model...")
                val vadStartTime = System.currentTimeMillis()
                downloadFileWithResume(VAD_MODEL_URL, vadFile, listener, 0, 5)
                val vadDownloadTime = System.currentTimeMillis() - vadStartTime
                Logger.d(TAG, "[Performance] VAD model downloaded in ${vadDownloadTime}ms")
                
                if (isCancelled.get()) {
                    listener.onDownloadCancelled()
                    return@withContext
                }
                
                // 下载 ASR 模型文件
                Logger.d(TAG, "[Performance] Downloading ASR model...")
                val asrStartTime = System.currentTimeMillis()
                val asrModelFile = File(senseVoiceDir, ASR_MODEL_FILE)
                downloadFileWithResume(SENSE_VOICE_MODEL_URL, asrModelFile, listener, 5, 85)
                val asrDownloadTime = System.currentTimeMillis() - asrStartTime
                Logger.d(TAG, "[Performance] ASR model downloaded in ${asrDownloadTime}ms (${asrModelFile.length() / 1024 / 1024}MB)")
                
                if (isCancelled.get()) {
                    listener.onDownloadCancelled()
                    return@withContext
                }
                
                // 下载 tokens 文件
                Logger.d(TAG, "[Performance] Downloading tokens file...")
                val tokensFile = File(senseVoiceDir, TOKENS_FILE)
                downloadFileWithResume(SENSE_VOICE_TOKENS_URL, tokensFile, listener, 85, 95)
                Logger.d(TAG, "[Performance] Tokens file downloaded")
                
                // 验证模型文件
                if (!isModelDownloaded()) {
                    throw IOException("Model files not found after download")
                }
                
                val totalTime = System.currentTimeMillis() - downloadStartTime
                Logger.i(TAG, "[Performance] Model download completed in ${totalTime}ms")
                
                listener.onDownloadProgress(100, 0, 100)
                listener.onDownloadCompleted(senseVoiceDir.absolutePath)
                
            } catch (e: CancellationException) {
                Logger.d(TAG, "Download cancelled")
                listener.onDownloadCancelled()
            } catch (e: Exception) {
                Logger.e(TAG, "Download failed", e)
                listener.onDownloadFailed(e.message ?: "Unknown error")
            }
        }
    }
    
    /**
     * 取消下载
     */
    fun cancelDownload() {
        Logger.d(TAG, "Cancelling download")
        isCancelled.set(true)
        downloadJob?.cancel()
    }
    
    /**
     * 删除模型
     */
    fun deleteModel(): Boolean {
        return try {
            Logger.i(TAG, "Deleting model")
            modelsDir.deleteRecursively()
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to delete model", e)
            false
        }
    }
    
    /**
     * 性能优化：支持断点续传的文件下载
     */
    private fun downloadFileWithResume(
        url: String,
        targetFile: File,
        listener: VoiceModelDownloadListener,
        progressStart: Int,
        progressEnd: Int
    ) {
        val tempFile = File(targetFile.absolutePath + TEMP_FILE_SUFFIX)
        var downloadedBytes = 0L
        
        // 检查是否有未完成的下载
        if (tempFile.exists()) {
            downloadedBytes = tempFile.length()
            Logger.d(TAG, "[Performance] Resuming download from ${downloadedBytes} bytes")
        }
        
        // 构建请求，支持断点续传
        val requestBuilder = Request.Builder().url(url)
        if (downloadedBytes > 0) {
            requestBuilder.addHeader("Range", "bytes=$downloadedBytes-")
        }
        
        val request = requestBuilder.build()
        val response = httpClient.newCall(request).execute()
        
        // 检查响应状态
        val isPartialContent = response.code == 206
        val isSuccess = response.isSuccessful || isPartialContent
        
        if (!isSuccess) {
            // 如果服务器不支持断点续传，重新开始下载
            if (response.code == 416) {
                Logger.d(TAG, "[Performance] Range not satisfiable, restarting download")
                tempFile.delete()
                downloadedBytes = 0
                downloadFile(url, targetFile, listener, progressStart, progressEnd)
                return
            }
            throw IOException("Download failed: ${response.code}")
        }
        
        val body = response.body ?: throw IOException("Empty response body")
        
        // 计算总大小
        val contentLength = body.contentLength()
        val totalBytes = if (isPartialContent) {
            downloadedBytes + contentLength
        } else {
            contentLength
        }
        
        targetFile.parentFile?.mkdirs()
        
        // 性能优化：使用更大的缓冲区
        val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
        
        body.byteStream().use { input ->
            // 使用追加模式写入临时文件
            FileOutputStream(tempFile, isPartialContent).use { output ->
                var bytesRead: Int
                
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    if (isCancelled.get()) {
                        throw CancellationException("Download cancelled")
                    }
                    
                    output.write(buffer, 0, bytesRead)
                    downloadedBytes += bytesRead
                    
                    val progress = if (totalBytes > 0) {
                        progressStart + ((downloadedBytes.toFloat() / totalBytes) * (progressEnd - progressStart)).toInt()
                    } else {
                        progressStart
                    }
                    listener.onDownloadProgress(progress, downloadedBytes, totalBytes)
                }
            }
        }
        
        // 下载完成，重命名临时文件
        if (targetFile.exists()) {
            targetFile.delete()
        }
        tempFile.renameTo(targetFile)
        
        Logger.d(TAG, "[Performance] Download completed: ${targetFile.length()} bytes")
    }
    
    /**
     * 下载文件（不支持断点续传的备用方法）
     */
    private fun downloadFile(
        url: String,
        targetFile: File,
        listener: VoiceModelDownloadListener,
        progressStart: Int,
        progressEnd: Int
    ) {
        val request = Request.Builder().url(url).build()
        val response = httpClient.newCall(request).execute()
        
        if (!response.isSuccessful) {
            throw IOException("Download failed: ${response.code}")
        }
        
        val body = response.body ?: throw IOException("Empty response body")
        val totalBytes = body.contentLength()
        var downloadedBytes = 0L
        
        targetFile.parentFile?.mkdirs()
        
        // 性能优化：使用更大的缓冲区
        val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
        
        body.byteStream().use { input ->
            FileOutputStream(targetFile).use { output ->
                var bytesRead: Int
                
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    if (isCancelled.get()) {
                        throw CancellationException("Download cancelled")
                    }
                    
                    output.write(buffer, 0, bytesRead)
                    downloadedBytes += bytesRead
                    
                    val progress = if (totalBytes > 0) {
                        progressStart + ((downloadedBytes.toFloat() / totalBytes) * (progressEnd - progressStart)).toInt()
                    } else {
                        progressStart
                    }
                    listener.onDownloadProgress(progress, downloadedBytes, totalBytes)
                }
            }
        }
    }
    
    /**
     * 获取断点续传进度（如果有）
     * 
     * @return 已下载的字节数，如果没有未完成的下载则返回 0
     */
    fun getResumeProgress(): Long {
        val asrTempFile = File(senseVoiceDir, "$ASR_MODEL_FILE$TEMP_FILE_SUFFIX")
        return if (asrTempFile.exists()) asrTempFile.length() else 0
    }
    
    /**
     * 清理未完成的下载
     */
    fun cleanupIncompleteDownload() {
        val asrTempFile = File(senseVoiceDir, "$ASR_MODEL_FILE$TEMP_FILE_SUFFIX")
        val tokensTempFile = File(senseVoiceDir, "$TOKENS_FILE$TEMP_FILE_SUFFIX")
        val vadTempFile = File(vadFile.absolutePath + TEMP_FILE_SUFFIX)
        
        listOf(asrTempFile, tokensTempFile, vadTempFile).forEach { file ->
            if (file.exists()) {
                file.delete()
                Logger.d(TAG, "Cleaned up incomplete download: ${file.name}")
            }
        }
    }
}
