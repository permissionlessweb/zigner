package net.rotko.zigner.components.base

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource

/**
 * A section of zafu's scroll painting (bamboo, samurai, enso, castle), the
 * same crops zafu's onboarding moves through. Fades into the page at the
 * bottom so text below sits on plain ground.
 */
@Composable
fun ScrollArt(
	@DrawableRes art: Int,
	modifier: Modifier = Modifier,
	alignment: Alignment = Alignment.Center,
) {
	val ground = MaterialTheme.colors.background
	Box(modifier = modifier.fillMaxWidth()) {
		Image(
			painter = painterResource(art),
			contentDescription = null,
			contentScale = ContentScale.Crop,
			alignment = alignment,
			modifier = Modifier.fillMaxSize(),
		)
		Box(
			modifier = Modifier
				.fillMaxSize()
				.background(
					Brush.verticalGradient(
						0.55f to Color.Transparent,
						1f to ground,
					)
				)
		)
	}
}
