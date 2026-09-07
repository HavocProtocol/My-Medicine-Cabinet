package com.example.services

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object LocalLlmConfig {
    private const val PREFS_NAME = "local_llm_prefs"
    private const val KEY_HOST_URL = "host_url"
    private const val KEY_MODEL_NAME = "model_name"
    private const val KEY_THEME_MODE = "theme_mode" // 0: System, 1: Light, 2: Dark
    private const val KEY_LANGUAGE = "app_language"

    const val DEFAULT_HOST = "http://10.0.2.2:11434"
    const val DEFAULT_MODEL = "llama3"

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

    fun getThemeMode(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_THEME_MODE, 0)
    }

    fun setThemeMode(context: Context, mode: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_THEME_MODE, mode)
            .apply()
    }
}

class LocalLlmService {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun checkConnection(hostUrl: String): Boolean = withContext(Dispatchers.IO) {
        val cleanHost = hostUrl.trim().removeSuffix("/")
        
        // 1. Try Ollama tags endpoint
        val ollamaRequest = Request.Builder()
            .url("$cleanHost/api/tags")
            .get()
            .build()

        try {
            client.newCall(ollamaRequest).execute().use { response ->
                if (response.isSuccessful) return@withContext true
            }
        } catch (_: Exception) {}

        // 2. Try OpenAI-compatible models endpoint (LM Studio / LocalAI / vLLM)
        val openAiRequest = Request.Builder()
            .url("$cleanHost/v1/models")
            .get()
            .build()

        try {
            client.newCall(openAiRequest).execute().use { response ->
                if (response.isSuccessful) return@withContext true
            }
        } catch (_: Exception) {}

        // 3. Fallback check base url
        val baseRequest = Request.Builder()
            .url(cleanHost)
            .get()
            .build()

        try {
            client.newCall(baseRequest).execute().use { response ->
                return@withContext response.isSuccessful || response.code < 500
            }
        } catch (_: Exception) {
            false
        }
    }

    suspend fun generateResponse(
        hostUrl: String,
        modelName: String,
        prompt: String,
        systemPrompt: String = "You are an intelligent medical cabinet assistant. Provide clear, helpful, factual information about medicines, storage, and health in the user's language. Always advise consulting a doctor or pharmacist for medical decisions."
    ): Result<String> = withContext(Dispatchers.IO) {
        val cleanHost = hostUrl.trim().removeSuffix("/")

        // Attempt 1: Ollama chat format (/api/chat)
        try {
            val json = JSONObject().apply {
                put("model", modelName)
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
                if (response.isSuccessful) {
                    val respStr = response.body?.string().orEmpty()
                    val respJson = JSONObject(respStr)
                    val message = respJson.optJSONObject("message")
                    val content = message?.optString("content")
                    if (!content.isNullOrBlank()) {
                        return@withContext Result.success(content.trim())
                    }
                }
            }
        } catch (_: Exception) {}

        // Attempt 2: Ollama generate format (/api/generate)
        try {
            val json = JSONObject().apply {
                put("model", modelName)
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
                if (response.isSuccessful) {
                    val respStr = response.body?.string().orEmpty()
                    val respJson = JSONObject(respStr)
                    val responseText = respJson.optString("response")
                    if (responseText.isNotBlank()) {
                        return@withContext Result.success(responseText.trim())
                    }
                }
            }
        } catch (_: Exception) {}

        // Attempt 3: OpenAI-compatible format (/v1/chat/completions - LM Studio, LocalAI, text-generation-webui)
        try {
            val json = JSONObject().apply {
                put("model", modelName)
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
                if (response.isSuccessful) {
                    val respStr = response.body?.string().orEmpty()
                    val respJson = JSONObject(respStr)
                    val choices = respJson.optJSONArray("choices")
                    val firstChoice = choices?.optJSONObject(0)
                    val msg = firstChoice?.optJSONObject("message")
                    val content = msg?.optString("content")
                    if (!content.isNullOrBlank()) {
                        return@withContext Result.success(content.trim())
                    }
                }
            }
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }

        Result.failure(Exception("Could not connect to Local LLM at $cleanHost. Please check if your local LLM server (e.g. Ollama or LM Studio) is running."))
    }
}
