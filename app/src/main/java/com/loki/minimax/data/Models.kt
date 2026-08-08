package com.loki.minimax.data

import com.google.gson.annotations.SerializedName

/**
 * 请求体：POST /v2/video_generation
 * https://platform.minimaxi.com/docs/api-reference/video-generation-v2-create
 */
data class VideoGenerationRequest(
    val model: String = "MiniMax-H3",
    val content: List<ContentItem>,
    val resolution: String,
    val duration: Int,
    val ratio: String? = null,
    @SerializedName("aigc_watermark") val aigcWatermark: Boolean = false
)

data class ContentItem(
    val type: String,                       // text / image_url / video_url / audio_url
    val text: String? = null,
    @SerializedName("image_url") val imageUrl: ImageUrl? = null,
    val role: String? = null                // first_frame / last_frame / reference_image ...
)

/** image_url.url 支持 data:image/<格式>;base64,<Base64> data URI */
data class ImageUrl(val url: String)

/** 创建任务响应：{ "task_id": "..." } */
data class CreateTaskResponse(
    @SerializedName("task_id") val taskId: String?
)

/** 查询任务响应：{ "task": { ... } } */
data class QueryTaskResponse(val task: TaskDetail?)

data class TaskDetail(
    val id: String? = null,
    val model: String? = null,
    val status: String? = null,            // queued / running / succeeded / failed / cancelled
    @SerializedName("created_at") val createdAt: Long? = null,
    @SerializedName("updated_at") val updatedAt: Long? = null,
    val content: TaskContent? = null,
    val error: TaskError? = null
)

data class TaskContent(
    val url: String? = null,               // 视频产物限时下载链接
    val prompt: String? = null
)

data class TaskError(
    val code: String? = null,
    val message: String? = null
)
