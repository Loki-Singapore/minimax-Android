package com.loki.minimax.data

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * MiniMax 视频生成 V2 (Hailuo-03) 客户端。
 *
 * 创建任务为异步接口：先 POST /v2/video_generation 拿到 task_id，
 * 再轮询 GET /v2/query/video_generation/{task_id} 直到 status=succeeded/failed。
 *
 * @see <a href="https://platform.minimaxi.com/docs/api-reference/video-generation-v2-create">创建任务</a>
 * @see <a href="https://platform.minimaxi.com/docs/api-reference/video-generation-v2-query">查询任务</a>
 */
class MiniMaxApi {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val json = "application/json; charset=utf-8".toMediaType()
    private val baseUrl = "https://api.minimaxi.com"

    /** 创建视频生成任务，返回 task_id。 */
    suspend fun createTask(apiKey: String, request: VideoGenerationRequest): String =
        withContext(Dispatchers.IO) {
            val body = gson.toJson(request).toRequestBody(json)
            val req = Request.Builder()
                .url("$baseUrl/v2/video_generation")
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(body)
                .build()

            client.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw ApiException(resp.code, raw)
                val parsed = gson.fromJson(raw, CreateTaskResponse::class.java)
                parsed?.taskId
                    ?: throw ApiException(resp.code, "响应缺少 task_id：$raw")
            }
        }

    /** 查询任务状态与结果。 */
    suspend fun queryTask(apiKey: String, taskId: String): TaskDetail =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url("$baseUrl/v2/query/video_generation/$taskId")
                .header("Authorization", "Bearer $apiKey")
                .get()
                .build()

            client.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw ApiException(resp.code, raw)
                val parsed = gson.fromJson(raw, QueryTaskResponse::class.java)
                parsed?.task
                    ?: throw ApiException(resp.code, "响应缺少 task：$raw")
            }
        }
}

class ApiException(val code: Int, val body: String) : Exception("HTTP $code: $body")
