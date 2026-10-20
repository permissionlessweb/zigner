package net.rotko.zigner.components.base

import android.content.res.Configuration
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.rotko.zigner.ui.theme.SignerNewTheme
import net.rotko.zigner.ui.theme.fill30


@Composable
fun PageIndicatorLine(
	totalDots: Int,
	selectedIndex: Int,
	selectedColor: Color = MaterialTheme.colors.primary,
	unSelectedColor: Color = MaterialTheme.colors.fill30,
	modifier: Modifier = Modifier,
) {
	Row(
		modifier = modifier
	) {
		repeat(totalDots) { index ->
			Box(
				modifier = Modifier
					.weight(1f)
					.height(4.dp)
					.background(
						color = if (index + 1 <= selectedIndex) selectedColor else unSelectedColor,
						shape = RectangleShape
					)
			)
			if (index + 1 != totalDots) {
				Spacer(modifier = Modifier.padding(horizontal = 4.dp))
			}
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
private fun PreviewPageIndicator() {
	SignerNewTheme {
		Column(Modifier.width(80.dp)) {
			Spacer(modifier = Modifier.padding(top = 2.dp))
			PageIndicatorLine(4, 2)
			Spacer(modifier = Modifier.padding(top = 2.dp))
		}
	}
}
