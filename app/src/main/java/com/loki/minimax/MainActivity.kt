package com.loki.minimax

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.loki.minimax.data.ContentItem
import com.loki.minimax.data.ImageUrl
import com.loki.minimax.data.MiniMaxApi
import com.loki.minimax.data.VideoGenerationRequest
import com.loki.minimax.databinding.ActivityMainBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val api = MiniMaxApi()

    /** 选中的图片以 data:image/<fmt>;base64,... 形式存放，用于上传。 */
    private var imageDataUri: String? = null
    private var exoPlayer: ExoPlayer? = null
    private var resultUrl: String? = null

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri == null) return@registerForActivityResult
            val dataUri = buildDataUri(uri)
            if (dataUri == null) {
                toast("无法读取图片")
                return@registerForActivityResult
            }
            imageDataUri = dataUri
            binding.imagePreview.setImageURI(uri)
            binding.imagePreview.visibility = View.VISIBLE
            binding.removeImageButton.visibility = View.VISIBLE
            binding.ratioSpinner.isEnabled = false
            toast("已选择图片，切换为图生视频（首帧）")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.apiKeyEdit.setText(getApiKey())
        binding.durationSpinner.setSelection(1)   // 默认 5 秒
        binding.resolutionSpinner.setSelection(1) // 默认 2K

        binding.pickImageButton.setOnClickListener {
            pickImage.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }
        binding.removeImageButton.setOnClickListener {
            imageDataUri = null
            binding.imagePreview.setImageDrawable(null)
            binding.imagePreview.visibility = View.GONE
            binding.removeImageButton.visibility = View.GONE
            binding.ratioSpinner.isEnabled = true
        }

        binding.generateButton.setOnClickListener { generate() }

        binding.openUrlButton.setOnClickListener {
            resultUrl?.let { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) }
        }
        binding.copyUrlButton.setOnClickListener {
            resultUrl?.let {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("minimax_video", it))
                toast("已复制链接")
            }
        }
    }

    private fun generate() {
        val apiKey = binding.apiKeyEdit.text.toString().trim()
        val prompt = binding.promptEdit.text.toString().trim()
        if (apiKey.isEmpty()) { toast("请输入 API Key"); return }
        if (prompt.isEmpty()) { toast("请输入提示词"); return }

        saveApiKey(apiKey)

        val content = mutableListOf(ContentItem(type = "text", text = prompt))
        val hasImage = imageDataUri != null
        if (hasImage) {
            // 图生视频：图片以 Base64 data URI 上传，作为首帧
            content.add(
                ContentItem(
                    type = "image_url",
                    imageUrl = ImageUrl(imageDataUri!!),
                    role = "first_frame"
                )
            )
        }

        val resolution = binding.resolutionSpinner.selectedItem as String
        val duration = (binding.durationSpinner.selectedItem as String).toInt()
        // 文生视频 ratio 必填且不能为 adaptive；图生视频恒为 adaptive。
        val ratio = if (hasImage) "adaptive" else binding.ratioSpinner.selectedItem as String

        val request = VideoGenerationRequest(
            content = content,
            resolution = resolution,
            duration = duration,
            ratio = ratio
        )

        setBusy(true)
        hideResult()
        binding.statusText.text = "正在创建任务…"

        // 整个“创建 + 轮询”过程在协程中异步执行，不阻塞 UI 线程。
        lifecycleScope.launch {
            try {
                val taskId = api.createTask(apiKey, request)
                binding.statusText.text = "任务已创建（$taskId）\n排队中…"
                poll(apiKey, taskId)
            } catch (e: Exception) {
                fail("创建任务失败：${e.message}")
            }
        }
    }

    private suspend fun poll(apiKey: String, taskId: String) {
        while (true) {
            delay(POLL_INTERVAL_MS)
            try {
                val task = api.queryTask(apiKey, taskId)
                when (task.status) {
                    "queued" -> binding.statusText.text = "任务 $taskId：排队中…"
                    "running" -> binding.statusText.text = "任务 $taskId：生成中…"
                    "succeeded" -> {
                        val url = task.content?.url
                        if (url != null) succeed(url)
                        else fail("任务完成但未返回视频链接")
                        return
                    }
                    "failed", "cancelled" -> {
                        fail("任务${task.status}：${task.error?.message ?: "未知错误"}")
                        return
                    }
                }
            } catch (e: Exception) {
                // 单次查询失败时继续重试，避免网络抖动导致整体失败。
                binding.statusText.text = "查询失败，重试中：${e.message}"
            }
        }
    }

    private fun succeed(url: String) {
        resultUrl = url
        binding.statusText.text = "生成成功！"
        binding.resultUrlText.text = url
        binding.resultUrlText.visibility = View.VISIBLE
        binding.openUrlButton.visibility = View.VISIBLE
        binding.copyUrlButton.visibility = View.VISIBLE
        binding.playerView.visibility = View.VISIBLE

        exoPlayer?.release()
        exoPlayer = ExoPlayer.Builder(this).build().also { player ->
            binding.playerView.player = player
            player.setMediaItem(MediaItem.fromUri(url))
            player.prepare()
            player.playWhenReady = true
        }
        setBusy(false)
    }

    private fun fail(message: String) {
        binding.statusText.text = "失败：$message"
        setBusy(false)
    }

    private fun hideResult() {
        resultUrl = null
        binding.resultUrlText.visibility = View.GONE
        binding.openUrlButton.visibility = View.GONE
        binding.copyUrlButton.visibility = View.GONE
        binding.playerView.visibility = View.GONE
        exoPlayer?.release()
        exoPlayer = null
    }

    private fun setBusy(busy: Boolean) {
        binding.generateButton.isEnabled = !busy
        binding.progressBar.visibility = if (busy) View.VISIBLE else View.GONE
    }

    /** 读取选中图片字节并编码为 data:image/<格式>;base64,<Base64>。 */
    private fun buildDataUri(uri: Uri): String? = try {
        val mime = contentResolver.getType(uri) ?: "image/png"
        val format = when {
            mime.contains("png", ignoreCase = true) -> "png"
            mime.contains("jpeg", ignoreCase = true) || mime.contains("jpg", ignoreCase = true) -> "jpeg"
            mime.contains("webp", ignoreCase = true) -> "webp"
            else -> "png"
        }
        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
        if (bytes == null) null else "data:image/$format;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}"
    } catch (e: Exception) {
        null
    }

    private fun getApiKey(): String =
        getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_API_KEY, "").orEmpty()

    private fun saveApiKey(key: String) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_API_KEY, key).apply()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        super.onDestroy()
        exoPlayer?.release()
        exoPlayer = null
    }

    private companion object {
        const val PREFS = "minimax"
        const val KEY_API_KEY = "api_key"
        const val POLL_INTERVAL_MS = 5000L
    }
}
