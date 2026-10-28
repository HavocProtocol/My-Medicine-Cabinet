package com.example.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.BuildConfig
import com.example.R
import com.example.services.LocalLlmConfig
import com.example.services.LocalLlmService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AISearchScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var responseText by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var useLocalLlm by remember { mutableStateOf(true) }

    val coroutineScope = rememberCoroutineScope()
    val localLlmService = remember { LocalLlmService() }
    val hostUrl = remember { LocalLlmConfig.getHostUrl(context) }
    val modelName = remember { LocalLlmConfig.getModelName(context) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                ),
                title = { Text(stringResource(R.string.ai_search_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            Text(
                stringResource(R.string.ai_search_subtitle),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(12.dp))

            // Toggle Local LLM vs Cloud AI
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = useLocalLlm,
                    onClick = { useLocalLlm = true },
                    label = { Text(stringResource(R.string.use_local_llm)) },
                    leadingIcon = { Icon(Icons.Default.Dns, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary
                    )
                )
                FilterChip(
                    selected = !useLocalLlm,
                    onClick = { useLocalLlm = false },
                    label = { Text(stringResource(R.string.use_cloud_ai)) },
                    leadingIcon = { Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary
                    )
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                    focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = Color.Transparent
                ),
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.ask_med_placeholder)) },
                maxLines = 3
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                shape = RoundedCornerShape(12.dp),
                onClick = {
                    if (query.isNotBlank()) {
                        isLoading = true
                        responseText = ""
                        coroutineScope.launch {
                            if (useLocalLlm) {
                                val res = localLlmService.generateResponse(
                                    hostUrl = hostUrl,
                                    modelName = modelName,
                                    prompt = query
                                )
                                responseText = res.getOrElse { err ->
                                    "${context.getString(R.string.connection_failed)}: ${err.message}"
                                }
                            } else {
                                responseText = performGeminiSearch(query, context)
                            }
                            isLoading = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                enabled = !isLoading,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text(stringResource(R.string.search_button), color = MaterialTheme.colorScheme.onPrimary)
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                if (responseText.isNotBlank()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Text(
                            text = responseText,
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        }
    }
}

private val geminiOkHttpClient = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .build()

suspend fun performGeminiSearch(query: String, context: Context): String = withContext(Dispatchers.IO) {
    try {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isEmpty()) return@withContext "API Key is missing."

        val jsonObj = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", query)
                        })
                    })
                })
            })
            put("tools", JSONArray().apply {
                put(JSONObject().apply {
                    put("googleSearch", JSONObject())
                })
            })
        }

        val requestBody = jsonObj.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey")
            .post(requestBody)
            .build()

        val response = geminiOkHttpClient.newCall(request).execute()
        val responseBody = response.body?.string()

        if (response.isSuccessful && responseBody != null) {
            val responseJson = JSONObject(responseBody)
            val candidates = responseJson.optJSONArray("candidates")
            val content = candidates?.optJSONObject(0)?.optJSONObject("content")
            val parts = content?.optJSONArray("parts")
            val text = parts?.optJSONObject(0)?.optString("text")

            val groundingMetadata = candidates?.optJSONObject(0)?.optJSONObject("groundingMetadata")
            val searchChunks = groundingMetadata?.optJSONArray("groundingChunks")
            var sourcesText = ""
            if (searchChunks != null && searchChunks.length() > 0) {
                sourcesText = "\n\n${context.getString(R.string.sources)}\n"
                for (i in 0 until searchChunks.length()) {
                    val chunk = searchChunks.optJSONObject(i)
                    val web = chunk?.optJSONObject("web")
                    if (web != null) {
                        val title = web.optString("title")
                        val uri = web.optString("uri")
                        sourcesText += "- $title\n  $uri\n"
                    }
                }
            }

            if (text != null) {
                text + sourcesText
            } else {
                "No response."
            }
        } else {
            if (response.code == 429) {
                "Quota Exceeded (429). Please verify your API Key or try again later."
            } else {
                try {
                    val errorJson = JSONObject(responseBody ?: "{}").optJSONObject("error")
                    val errorMessage = errorJson?.optString("message") ?: "Unknown error"
                    "Error (${response.code}): $errorMessage"
                } catch (e: Exception) {
                    "Error (${response.code}): $responseBody"
                }
            }
        }
    } catch (e: Exception) {
        "${context.getString(R.string.connection_failed)}: ${e.message}"
    }
}
