package com.example.services

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.TimeUnit

object LocalLlmConfig {
    private const val PREFS_NAME = "local_llm_prefs"
    private const val KEY_HOST_URL = "host_url"
    private const val KEY_MODEL_NAME = "model_name"
    private const val KEY_THEME_MODE = "theme_mode" // 0: System, 1: Light, 2: Dark
    private const val KEY_LANGUAGE = "app_language"
    private const val KEY_EXPIRY_WARNING_DAYS = "expiry_warning_days"
    private const val KEY_PREMIUM_SUBSCRIBED = "is_premium_subscribed"

    const val DEFAULT_HOST = "http://10.0.2.2:11434"
    const val DEFAULT_MODEL = "llama3"
    const val DEFAULT_EXPIRY_WARNING_DAYS = 7

    private val _themeModeFlow = MutableStateFlow(0)
    val themeModeFlow: StateFlow<Int> = _themeModeFlow

    private val _expiryWarningDaysFlow = MutableStateFlow(DEFAULT_EXPIRY_WARNING_DAYS)
    val expiryWarningDaysFlow: StateFlow<Int> = _expiryWarningDaysFlow

    private val _premiumSubscribedFlow = MutableStateFlow(false)
    val premiumSubscribedFlow: StateFlow<Boolean> = _premiumSubscribedFlow

    internal fun syncInternalPremiumFlag(isPremium: Boolean) {
        _premiumSubscribedFlow.value = isPremium
    }

    fun getHostUrl(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_HOST_URL, DEFAULT_HOST) ?: DEFAULT_HOST
    }

    fun setHostUrl(context: Context, hostUrl: String) {
        val cleanUrl = hostUrl.trim().removeSuffix("/")
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_HOST_URL, cleanUrl)
            .apply()
    }

    fun getModelName(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_MODEL_NAME, DEFAULT_MODEL) ?: DEFAULT_MODEL
    }

    fun setModelName(context: Context, modelName: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MODEL_NAME, modelName.trim())
            .apply()
    }

    fun saveConfig(context: Context, hostUrl: String, modelName: String) {
        setHostUrl(context, hostUrl)
        setModelName(context, modelName)
    }

    fun getThemeMode(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val mode = prefs.getInt(KEY_THEME_MODE, 0)
        _themeModeFlow.value = mode
        return mode
    }

    fun hasThemeModeConfigured(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.contains(KEY_THEME_MODE)
    }

    fun setThemeMode(context: Context, mode: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_THEME_MODE, mode)
            .apply()
        _themeModeFlow.value = mode
    }

    fun getExpiryWarningDays(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val days = prefs.getInt(KEY_EXPIRY_WARNING_DAYS, DEFAULT_EXPIRY_WARNING_DAYS).coerceIn(1, 365)
        _expiryWarningDaysFlow.value = days
        return days
    }

    fun setExpiryWarningDays(context: Context, days: Int) {
        val validDays = days.coerceIn(1, 365)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_EXPIRY_WARNING_DAYS, validDays)
            .apply()
        _expiryWarningDaysFlow.value = validDays
    }

    fun isPremiumSubscribed(context: Context): Boolean {
        return PremiumEntitlementManager.isPremiumActive(context)
    }

    fun setPremiumSubscribed(context: Context, isPremium: Boolean) {
        if (isPremium) {
            PremiumEntitlementManager.subscribeToPlan(context, PremiumEntitlementManager.PLAN_ANNUAL_ID)
        } else {
            PremiumEntitlementManager.resetToFree(context)
        }
    }
}

sealed class LocalLlmDiagnosticException(
    val userFriendlyTitle: String,
    message: String
) : Exception(message) {
    class MissingModelException(val modelName: String) :
        LocalLlmDiagnosticException(
            userFriendlyTitle = "Missing Local Model",
            message = "Model '$modelName' is not installed on the local LLM server. Please pull or load '$modelName' first."
        )

    class InsufficientResourcesException(details: String) :
        LocalLlmDiagnosticException(
            userFriendlyTitle = "Insufficient Device Resources",
            message = "Insufficient device or server memory/resources to load the local model: $details"
        )

    class ModelLoadingFailureException(details: String) :
        LocalLlmDiagnosticException(
            userFriendlyTitle = "Model Loading Failure",
            message = "Failed to load the local LLM model: $details"
        )

    class UnsupportedConfigurationException(details: String) :
        LocalLlmDiagnosticException(
            userFriendlyTitle = "Unsupported Configuration",
            message = "Unsupported Local LLM configuration: $details"
        )
}

class LocalLlmService(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
) {

    fun validateConfiguration(hostUrl: String, modelName: String): Result<Pair<String, String>> {
        val cleanHost = hostUrl.trim().removeSuffix("/")
        val cleanModel = modelName.trim()
        if (cleanHost.isBlank()) {
            return Result.failure(
                LocalLlmDiagnosticException.UnsupportedConfigurationException("Host URL cannot be empty.")
            )
        }
        val uri = try {
            URI(cleanHost)
        } catch (e: Exception) {
            return Result.failure(
                LocalLlmDiagnosticException.UnsupportedConfigurationException("Invalid Host URL format: $cleanHost")
            )
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return Result.failure(
                LocalLlmDiagnosticException.UnsupportedConfigurationException("Unsupported protocol '$scheme'. Only http:// and https:// are supported.")
            )
        }
        if (cleanModel.isBlank()) {
            return Result.failure(
                LocalLlmDiagnosticException.MissingModelException("(empty model name)")
            )
        }
        return Result.success(cleanHost to cleanModel)
    }

    suspend fun checkConnection(hostUrl: String): Boolean = withContext(Dispatchers.IO) {
        val cleanHost = hostUrl.trim().removeSuffix("/")
        if (cleanHost.isBlank()) return@withContext false

        // 1. Try Ollama tags endpoint
        try {
            val ollamaRequest = Request.Builder()
                .url("$cleanHost/api/tags")
                .get()
                .build()
            client.newCall(ollamaRequest).execute().use { response ->
                if (response.isSuccessful) return@withContext true
            }
        } catch (_: Exception) {}

        // 2. Try OpenAI-compatible models endpoint (LM Studio / LocalAI / vLLM)
        try {
            val openAiRequest = Request.Builder()
                .url("$cleanHost/v1/models")
                .get()
                .build()
            client.newCall(openAiRequest).execute().use { response ->
                if (response.isSuccessful) return@withContext true
            }
        } catch (_: Exception) {}

        // 3. Fallback check base url
        try {
            val baseRequest = Request.Builder()
                .url(cleanHost)
                .get()
                .build()
            client.newCall(baseRequest).execute().use { response ->
                return@withContext response.isSuccessful || response.code < 500
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Generates a response from the Local LLM after enforcing the unified Premium entitlement.
     */
    suspend fun generateResponseForUser(
        context: Context,
        hostUrl: String,
        modelName: String,
        prompt: String,
        systemPrompt: String = DEFAULT_SYSTEM_PROMPT
    ): Result<String> {
        try {
            PremiumEntitlementManager.requirePremiumAccess(context, PremiumFeature.LOCAL_LLM)
        } catch (e: PremiumAccessRequiredException) {
            return Result.failure(e)
        }
        return generateResponse(hostUrl, modelName, prompt, systemPrompt)
    }

    suspend fun generateResponse(
        hostUrl: String,
        modelName: String,
        prompt: String,
        systemPrompt: String = DEFAULT_SYSTEM_PROMPT
    ): Result<String> = withContext(Dispatchers.IO) {
        val configValidation = validateConfiguration(hostUrl, modelName)
        if (configValidation.isFailure) {
            return@withContext Result.failure(configValidation.exceptionOrNull()!!)
        }
        val (cleanHost, cleanModel) = configValidation.getOrThrow()

        if (prompt.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Prompt cannot be empty."))
        }

        var lastDiagnosticError: Exception? = null

        // Attempt 1: Ollama chat format (/api/chat)
        try {
            val json = JSONObject().apply {
                put("model", cleanModel)
                put("stream", false)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", systemPrompt)
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    })
                })
            }

            val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url("$cleanHost/api/chat")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                val respStr = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    val respJson = JSONObject(respStr)
                    val message = respJson.optJSONObject("message")
                    val content = message?.optString("content")
                    if (!content.isNullOrBlank()) {
                        return@withContext Result.success(content.trim())
                    }
                } else {
                    classifyServerFailure(response.code, respStr, cleanModel)?.let {
                        return@withContext Result.failure(it)
                    }
                }
            }
        } catch (e: Exception) {
            lastDiagnosticError = e
        }

        // Attempt 2: Ollama generate format (/api/generate)
        try {
            val json = JSONObject().apply {
                put("model", cleanModel)
                put("stream", false)
                put("system", systemPrompt)
                put("prompt", prompt)
            }

            val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url("$cleanHost/api/generate")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                val respStr = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    val respJson = JSONObject(respStr)
                    val responseText = respJson.optString("response")
                    if (responseText.isNotBlank()) {
                        return@withContext Result.success(responseText.trim())
                    }
                } else {
                    classifyServerFailure(response.code, respStr, cleanModel)?.let {
                        return@withContext Result.failure(it)
                    }
                }
            }
        } catch (e: Exception) {
            lastDiagnosticError = e
        }

        // Attempt 3: OpenAI-compatible format (/v1/chat/completions - LM Studio, LocalAI, text-generation-webui)
        try {
            val json = JSONObject().apply {
                put("model", cleanModel)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", systemPrompt)
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    })
                })
            }

            val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url("$cleanHost/v1/chat/completions")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                val respStr = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    val respJson = JSONObject(respStr)
                    val choices = respJson.optJSONArray("choices")
                    val firstChoice = choices?.optJSONObject(0)
                    val msg = firstChoice?.optJSONObject("message")
                    val content = msg?.optString("content")
                    if (!content.isNullOrBlank()) {
                        return@withContext Result.success(content.trim())
                    }
                } else {
                    classifyServerFailure(response.code, respStr, cleanModel)?.let {
                        return@withContext Result.failure(it)
                    }
                }
            }
        } catch (e: Exception) {
            lastDiagnosticError = e
        }

        Result.failure(
            lastDiagnosticError ?: Exception(
                "Could not connect to Local LLM at $cleanHost. Please check if your local LLM server (e.g. Ollama or LM Studio) is running."
            )
        )
    }

    internal fun classifyServerFailure(
        httpCode: Int,
        responseBody: String,
        modelName: String
    ): LocalLlmDiagnosticException? {
        val lower = responseBody.lowercase()
        return when {
            httpCode == 404 && (lower.contains("model") || lower.contains("not found")) ->
                LocalLlmDiagnosticException.MissingModelException(modelName)
            lower.contains("memory") || lower.contains("oom") || lower.contains("insufficient") || lower.contains("vram") ->
                LocalLlmDiagnosticException.InsufficientResourcesException(responseBody.take(160))
            lower.contains("failed to load") || lower.contains("loading model") || lower.contains("runner process") ->
                LocalLlmDiagnosticException.ModelLoadingFailureException(responseBody.take(160))
            else -> null
        }
    }

    companion object {
        const val DEFAULT_SYSTEM_PROMPT =
            "You are an intelligent medical cabinet assistant. Provide clear, helpful, factual information about medicines, storage, and health in the user's language. Always advise consulting a doctor or pharmacist for medical decisions."
    }
}
