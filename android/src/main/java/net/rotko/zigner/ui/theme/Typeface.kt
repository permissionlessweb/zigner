package net.rotko.zigner.ui.theme

import androidx.compose.material.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import net.rotko.zigner.R

/**
 * Zafu type: Iosevka Term for body and data, Shippori Mincho for titles.
 *
 * Iosevka ships one weight (400). Hierarchy comes from size and colour, never
 * from weight, so no style below asks it for bold: Compose would synthesise a
 * smeared faux-bold. Mincho has real 500 and 600 cuts.
 */
val IosevkaFontFamily = FontFamily(Font(R.font.iosevka_term_regular, FontWeight.Normal))
val MinchoFontFamily = FontFamily(
	Font(R.font.shippori_mincho_medium, FontWeight.Medium),
	Font(R.font.shippori_mincho_semibold, FontWeight.SemiBold),
)

private fun body(size: Int) = TextStyle(
	fontFamily = IosevkaFontFamily,
	fontWeight = FontWeight.Normal,
	fontSize = size.sp,
)

private fun title(size: Int, weight: FontWeight = FontWeight.SemiBold) = TextStyle(
	fontFamily = MinchoFontFamily,
	fontWeight = weight,
	fontSize = size.sp,
)

@Deprecated("use new SignerTypeface")
val Typography = Typography(
	defaultFontFamily = IosevkaFontFamily,
	h1 = title(19),
	h2 = title(19, FontWeight.Medium),
	h3 = body(16),
	h4 = body(16),
	body1 = body(16),
	body2 = body(15),
	button = body(16),
	subtitle1 = body(15),
	subtitle2 = body(13),
	overline = body(13),
)

val CryptoTypography = Typography(
	defaultFontFamily = IosevkaFontFamily,
	body1 = body(16).copy(lineHeight = 1.4.em),
	body2 = body(12).copy(lineHeight = 1.4.em),
)

/**
 * Our Typefase schema is shared with iOS and not mapped to android [androidx.compose.material.Typography] so mainly defined here
 */
object SignerTypeface {
	val TitleXl = title(30)
	val TitleL = title(24)
	val TitleM = title(21)
	val TitleS = title(17, FontWeight.Medium)
	val LabelL = body(17)
	val LabelM = body(16)
	val LabelS = body(14)
	val BodyL = body(16)
	val BodyM = body(14)
	val BodyS = body(12)
	val CaptionM = body(12)
	val CaptionS = body(10)
}
