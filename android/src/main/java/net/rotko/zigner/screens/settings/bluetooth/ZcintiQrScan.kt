package net.rotko.zigner.screens.settings.bluetooth

import android.util.Size
import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage

/** Reads one zcinti QR from the laptop page. */
@Composable
internal fun ZcintiQrScan(onCode: (String) -> Unit) = ZcintiQrStream { onCode(it); true }

/**
 * Keeps reading QR codes (animated zc-anim frames included) until onText
 * returns true. Repeats of the same text are skipped.
 */
@Composable
internal fun ZcintiQrStream(onText: (String) -> Boolean) {
	val context = LocalContext.current
	val lifecycleOwner = LocalLifecycleOwner.current
	val provider = remember { ProcessCameraProvider.getInstance(context) }
	val handed = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
	val last = remember { java.util.concurrent.atomic.AtomicReference("") }
	AndroidView(
		modifier = Modifier.fillMaxWidth().height(240.dp),
		factory = { viewContext ->
			val previewView = PreviewView(viewContext).apply {
				layoutParams = ViewGroup.LayoutParams(
					ViewGroup.LayoutParams.MATCH_PARENT,
					ViewGroup.LayoutParams.MATCH_PARENT,
				)
			}
			val executor = ContextCompat.getMainExecutor(viewContext)
			provider.addListener({
				val camera = provider.get()
				val preview = Preview.Builder().build().also {
					it.setSurfaceProvider(previewView.surfaceProvider)
				}
				val analysis = ImageAnalysis.Builder()
					.setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
					.setTargetResolution(Size(1280, 720))
					.build()
				val scanner = BarcodeScanning.getClient()
				analysis.setAnalyzer(executor) { frame ->
					val media = frame.image
					if (media == null || handed.get()) {
						frame.close()
						return@setAnalyzer
					}
					val image = InputImage.fromMediaImage(media, frame.imageInfo.rotationDegrees)
					scanner.process(image)
						.addOnSuccessListener { codes ->
							val text = codes.firstOrNull { it.format == Barcode.FORMAT_QR_CODE }?.rawValue
							if (!text.isNullOrBlank() && !handed.get() && last.getAndSet(text) != text) {
								if (onText(text)) handed.set(true)
							}
						}
						.addOnCompleteListener { frame.close() }
				}
				camera.unbindAll()
				camera.bindToLifecycle(
					lifecycleOwner,
					CameraSelector.DEFAULT_BACK_CAMERA,
					preview,
					analysis,
				)
			}, executor)
			previewView
		},
	)
}
