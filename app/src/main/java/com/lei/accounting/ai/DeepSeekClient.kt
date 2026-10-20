package com.lei.accounting.ai

import com.lei.accounting.core.ai.ChatPrompt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** DeepSeek 的 OpenAI 兼容接口：https://api.deepseek.com/chat/completions */
class DeepSeekClient(private val apiKey: String, private val model: String) {

    class ApiException(message: String, val retryable: Boolean, val code: Int = 0) : IOException(message) {
        /** 给用户看的说明（设置页显示） */
        val userMessage: String
            get() = when (code) {
                401 -> "API Key 无效"
                402 -> "DeepSeek 账户余额不足"
                else -> message.orEmpty().take(80)
            }
    }

    suspend fun chat(prompt: ChatPrompt, maxTokens: Int = 300): String = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("model", model)
            putJsonArray("messages") {
                addJsonObject { put("role", "system"); put("content", prompt.system) }
                addJsonObject { put("role", "user"); put("content", prompt.user) }
            }
            put("temperature", if (prompt.jsonOutput) 0.1 else 0.7)
            put("max_tokens", maxTokens)
            put("stream", false)
            if (prompt.jsonOutput) putJsonObject("response_format") { put("type", "json_object") }
        }.toString()

        val request = Request.Builder()
            .url(ENDPOINT)
            .header("Authorization", "Bearer $apiKey")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        http.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                // 401 Key 错误 / 402 余额不足 不需要重试；429 / 5xx 可以稍后重试
                val retryable = resp.code == 429 || resp.code >= 500
                throw ApiException("DeepSeek HTTP ${resp.code}: ${text.take(200)}", retryable, resp.code)
            }
            val root = Json.parseToJsonElement(text).jsonObject
            root["choices"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("message")
                ?.jsonObject?.get("content")
                ?.jsonPrimitive?.contentOrNull
                ?: throw ApiException("DeepSeek 返回内容为空", retryable = true)
        }
    }

    companion object {
        private const val ENDPOINT = "https://api.deepseek.com/chat/completions"

        private val http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
