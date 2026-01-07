package com.kevinluo.autoglm.voice

/**
 * Configuration for voice input features.
 *
 * @property modelDownloaded Whether the voice model has been downloaded
 * @property modelPath Path to the downloaded model directory
 * @property continuousListening Whether continuous background listening is enabled
 * @property wakeWords List of wake words to detect
 * @property wakeWordSensitivity Sensitivity for wake word detection (0.0 to 1.0)
 */
data class VoiceConfig(
    val modelDownloaded: Boolean = false,
    val modelPath: String? = null,
    val continuousListening: Boolean = false,
    val wakeWords: List<String> = listOf("你好小智"),
    val wakeWordSensitivity: Float = 0.6f
)
