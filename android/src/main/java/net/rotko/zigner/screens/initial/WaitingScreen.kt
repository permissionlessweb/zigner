package net.rotko.zigner.screens.initial

import android.content.res.Configuration
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.rotko.zigner.R
import net.rotko.zigner.ui.theme.SignerTypeface
import net.rotko.zigner.ui.theme.textTertiary

// one enso: the brush goes round, rests, fades, and is drawn again
private const val ENSO_MS = 3600
private const val ENSO_DRAWN = 0.62f
private const val ENSO_FADES = 0.82f
private const val ENSO_START = 215f
private const val ENSO_SWEEP = 336f
private const val ENSO_STEPS = 56

/**
 * Shown while the person waits: the seated samurai breathes, and an ink enso
 * is drawn around him. It claims no progress; [what] says what zigner is
 * doing, in the caller's words, never a bare "please wait".
 */
@Composable
fun WaitingScreen(what: String) {
	val loop = rememberInfiniteTransition(label = "waiting")
	val breath by loop.animateFloat(
		initialValue = 1f,
		targetValue = 1.05f,
		animationSpec = infiniteRepeatable(
			tween(4200, easing = FastOutSlowInEasing), RepeatMode.Reverse
		),
		label = "breath",
	)
	val t by loop.animateFloat(
		initialValue = 0f,
		targetValue = 1f,
		animationSpec = infiniteRepeatable(tween(ENSO_MS, easing = LinearEasing)),
		label = "enso",
	)
	val drawn = FastOutSlowInEasing.transform((t / ENSO_DRAWN).coerceAtMost(1f))
	val ink = MaterialTheme.colors.onBackground
	val fade = 1f - ((t - ENSO_FADES) / (1f - ENSO_FADES)).coerceIn(0f, 1f)

	Box(
		modifier = Modifier.fillMaxSize(1f),
		contentAlignment = Alignment.Center,
	) {
		Column(horizontalAlignment = Alignment.CenterHorizontally) {
			Box(modifier = Modifier.size(232.dp), contentAlignment = Alignment.Center) {
				Image(
					painter = painterResource(id = R.drawable.art_samurai),
					contentDescription = null,
					contentScale = ContentScale.Crop,
					alignment = BiasAlignment(0f, -0.15f),
					modifier = Modifier
						.size(184.dp)
						.clip(CircleShape)
						.graphicsLayer {
							scaleX = breath
							scaleY = breath
						},
				)
				// faded as one layer: per-segment alpha would bead where the caps overlap
				Canvas(modifier = Modifier.fillMaxSize().graphicsLayer { alpha = fade }) {
					// a brush stroke: loaded and wide where it lands, thin where it lifts
					val wide = 11.dp.toPx()
					val thin = 2.dp.toPx()
					val inset = wide / 2
					val arcSize = Size(size.width - wide, size.height - wide)
					val step = ENSO_SWEEP / ENSO_STEPS
					val upTo = (drawn * ENSO_STEPS).toInt()
					for (i in 0 until upTo) {
						val along = i.toFloat() / ENSO_STEPS
						drawArc(
							color = ink,
							startAngle = ENSO_START + i * step,
							// a hair of overlap, so the segments read as one stroke
							sweepAngle = step + 0.6f,
							useCenter = false,
							topLeft = Offset(inset, inset),
							size = arcSize,
							style = Stroke(
								width = wide + (thin - wide) * along * along,
								cap = StrokeCap.Round,
							),
						)
					}
				}
			}
			Spacer(modifier = Modifier.height(20.dp))
			Text(
				text = what,
				style = SignerTypeface.BodyM,
				color = MaterialTheme.colors.textTertiary,
			)
		}
	}
}

@Preview(
	name = "light", group = "themes", uiMode = Configuration.UI_MODE_NIGHT_NO,
	showBackground = true, backgroundColor = 0xFFFFFFFF,
)
@Preview(
	name = "dark", group = "themes", uiMode = Configuration.UI_MODE_NIGHT_YES,
	showBackground = true, backgroundColor = 0xFF000000,
)
@Composable
private fun PreviewWaitingScreen() {
	WaitingScreen("Loading keys")
}
