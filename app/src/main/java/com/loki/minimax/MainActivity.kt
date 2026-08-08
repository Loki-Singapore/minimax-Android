package com.loki.minimax

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
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
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val api = MiniMaxApi()

    /** 选中的图片：uri 用于预览，dataUri 用于 Base64 上传。 */
    private data class ImageItem(val id: String, val uri: Uri, val dataUri: String)

    private val imageItems = mutableListOf<ImageItem>()
    private var exoPlayer: ExoPlayer? = null
    private var resultUrl: String? = null

    // 多选图片，上限按参考图最大数量 9。
    private val pickImages =
        registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(9)) { uris ->
            if (uris.isNullOrEmpty()) return@registerForActivityResult
            val maxItems = maxImagesForMode(currentImageMode())
            val remaining = maxItems - imageItems.size
            if (remaining <= 0) {
                toast("已达到本模式图片上限 ($maxItems 张)")
                return@registerForActivityResult
            }
            val toAdd = uris.take(remaining)
            if (toAdd.size < uris.size) {
                toast("仅添加前 $remaining 张，超出本模式上限")
            }
            toAdd.forEach { uri ->
                val dataUri = buildDataUri(uri) ?: run {
                    toast("无法读取其中一张图片，已跳过")
                    return@forEach
                }
                imageItems.add(ImageItem(UUID.randomUUID().toString(), uri, dataUri))
            }
            refreshImagesUi()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.apiKeyEdit.setText(getApiKey())
        binding.durationSpinner.setSelection(1)   // 默认 5 秒
        binding.resolutionSpinner.setSelection(1) // 默认 2K

        binding.pickImageButton.setOnClickListener {
            pickImages.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }
        binding.clearImagesButton.setOnClickListener {
            imageItems.clear()
            refreshImagesUi()
        }

        // 切换图片模式时清空已选图片（两种 role 互斥，避免混用）。
        binding.imageModeSpinner.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?, v: View?, p: Int, id: Long
                ) {
                    if (imageItems.isNotEmpty()) {
                        imageItems.clear()
                        refreshImagesUi()
                        toast("已切换图片模式，清空已选图片")
                    }
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
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

        refreshImagesUi()
    }

    /** 0=首帧/首尾帧（i2va，≤2），1=参考图（r2va，≤9）。 */
    private fun currentImageMode(): Int = binding.imageModeSpinner.selectedItemPosition
    private fun maxImagesForMode(mode: Int): Int = if (mode == 0) 2 else 9

    private fun generate() {
        val apiKey = binding.apiKeyEdit.text.toString().trim()
        val prompt = binding.promptEdit.text.toString().trim()
        if (apiKey.isEmpty()) { toast("请输入 API Key"); return }
        if (prompt.isEmpty()) { toast("请输入提示词"); return }

        saveApiKey(apiKey)

        val content = mutableListOf(ContentItem(type = "text", text = prompt))
        val hasImage = imageItems.isNotEmpty()
        if (hasImage) {
            val mode = currentImageMode()
            when (mode) {
                0 -> {
                    // 图生视频：1 张=first_frame；2 张=first_frame+last_frame
                    content.add(imageItem(imageItems[0], "first_frame"))
                    if (imageItems.size >= 2) {
                        content.add(imageItem(imageItems[1], "last_frame"))
                    }
                }
                1 -> {
                    // 多模态参考：全部 reference_image
                    imageItems.forEach { content.add(imageItem(it, "reference_image")) }
                }
            }
        }

        val resolution = binding.resolutionSpinner.selectedItem as String
        val duration = (binding.durationSpinner.selectedItem as String).toInt()
        // 文生视频 ratio 必填且不能为 adaptive；有图片时（两种模式）均按 adaptive 处理。
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

    private fun imageItem(item: ImageItem, role: String) = ContentItem(
        type = "image_url",
        imageUrl = ImageUrl(item.dataUri),
        role = role
    )

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

    /** 重新渲染缩略图列表、计数、宽高比可用性。 */
    private fun refreshImagesUi() {
        binding.imagesContainer.removeAllViews()
        val hasImage = imageItems.isNotEmpty()
        binding.imagesScroll.visibility = if (hasImage) View.VISIBLE else View.GONE
        binding.clearImagesButton.visibility = if (hasImage) View.VISIBLE else View.GONE
        binding.ratioSpinner.isEnabled = !hasImage

        val max = maxImagesForMode(currentImageMode())
        binding.imageCountText.text = if (hasImage) "${imageItems.size} / $max" else ""

        imageItems.forEach { item -> binding.imagesContainer.addView(buildThumbnail(item)) }
    }

    /** 构建一个缩略图项：图片预览 + 下方“删除”按钮。 */
    private fun buildThumbnail(item: ImageItem): View {
        val density = resources.displayMetrics.density
        val sizePx = (96 * density).toInt()
        val margin = (8 * density).toInt()

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = margin }
        }

        val thumb = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(sizePx, sizePx)
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageURI(item.uri)
        }

        val remove = TextView(this).apply {
            text = "删除"
            textSize = 12f
            setTextColor(0xFF1976D2.toInt())
            setPadding(0, margin / 2, 0, 0)
            setOnClickListener { removeImage(item.id) }
        }

        column.addView(thumb)
        column.addView(remove)
        return column
    }

    private fun removeImage(id: String) {
        imageItems.removeAll { it.id == id }
        refreshImagesUi()
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
