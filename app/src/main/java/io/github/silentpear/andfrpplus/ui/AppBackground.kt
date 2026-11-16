package io.github.silentpear.andfrpplus.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 应用背景：0 = 跟随主题（不绘制），1 = 纯色，2 = 自定义图片（叠一层压暗保证可读）。
 */
@Composable
fun AppBackground(
    mode: Int,
    color: Int,
    imageUri: String?,
    content: @Composable () -> Unit
) {
    if (mode == 0) {
        content()
        return
    }

    Box(Modifier.fillMaxSize()) {
        when (mode) {
            1 -> Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(color))
            )
            2 -> {
                val context = LocalContext.current
                var bitmap by remember(imageUri) { mutableStateOf<android.graphics.Bitmap?>(null) }
                LaunchedEffect(imageUri) {
                    bitmap = if (imageUri == null) null else withContext(Dispatchers.IO) {
                        runCatching {
                            val uri = android.net.Uri.parse(imageUri)
                            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            context.contentResolver.openInputStream(uri)?.use {
                                BitmapFactory.decodeStream(it, null, bounds)
                            }
                            var scale = 1
                            while (bounds.outWidth / scale > 1600 || bounds.outHeight / scale > 1600) scale *= 2
                            val opts = BitmapFactory.Options().apply { inSampleSize = scale }
                            context.contentResolver.openInputStream(uri)?.use {
                                BitmapFactory.decodeStream(it, null, opts)
                            }
                        }.getOrNull()
                    }
                }
                bitmap?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                    // 压暗一点，避免盖住上面的文字
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.55f))
                    )
                }
            }
        }
        content()
    }
}
