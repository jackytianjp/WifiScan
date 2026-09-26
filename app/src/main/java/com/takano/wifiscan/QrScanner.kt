package com.takano.wifiscan

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.Result
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 用相机抓帧 + zxing 解二维码（纯 Java，无 GMS 依赖）。
 * 不做预览画面（眼镜上预览要 GL 旋转，代价大且不必要）：HUD 只给提示语。
 */
class QrScanner(
    private val onResult: (Result) -> Unit,
    private val onLog: (String) -> Unit,
) {
    private var executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true,
            )
        )
    }
    private var provider: ProcessCameraProvider? = null
    var paused = false

    fun start(context: Context, owner: LifecycleOwner, previewView: androidx.camera.view.PreviewView? = null) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val p = future.get()
                provider = p
                val preview = Preview.Builder().build()
                previewView?.let { pv ->
                    preview.setSurfaceProvider(pv.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()
                analysis.setAnalyzer(executor) { proxy -> analyze(proxy) }
                p.unbindAll()
                p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                onLog("相机已就绪，对准二维码")
            } catch (t: Throwable) {
                onLog("相机启动失败: ${t.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() {
        runCatching { provider?.unbindAll() }
        runCatching { executor.shutdown() }
    }

    private fun analyze(proxy: ImageProxy) {
        if (paused || !busy.compareAndSet(false, true)) {
            proxy.close(); return
        }
        try {
            val plane = proxy.planes[0]
            val buf = plane.buffer
            val rowStride = plane.rowStride
            val w = proxy.width
            val h = proxy.height
            val compact = ByteArray(w * h)
            val row = ByteArray(rowStride)
            var out = 0
            for (y in 0 until h) {
                buf.position(y * rowStride)
                val n = minOf(rowStride, buf.remaining())
                buf.get(row, 0, n)
                System.arraycopy(row, 0, compact, out, w)
                out += w
            }
            val hit = tryDecode(compact, w, h)
            if (hit != null) onResult(hit)
        } catch (_: Throwable) {
        } finally {
            busy.set(false)
            proxy.close()
        }
    }

    /** zxing 不会自动转正，所以四个方向都试一遍（缓冲区不大，代价可接受） */
    private fun tryDecode(y: ByteArray, w: Int, h: Int): Result? {
        var (cur, cw, ch) = Triple(y, w, h)
        repeat(4) {
            val src = PlanarYUVLuminanceSource(cur, cw, ch, 0, 0, cw, ch, false)
            val res = runCatching {
                reader.decodeWithState(BinaryBitmap(HybridBinarizer(src)))
            }.getOrNull()
            if (res != null) return res
            val rotated = ByteArray(cur.size)
            for (row in 0 until ch) for (col in 0 until cw) {
                rotated[col * ch + (ch - 1 - row)] = cur[row * cw + col]
            }
            cur = rotated
            val t = cw; cw = ch; ch = t
        }
        return null
    }
}
