@file:Suppress("unused")

package net.rotko.zigner.ui.theme

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Shapes
import androidx.compose.ui.unit.dp

// Zafu draws square corners everywhere.
val Shapes = Shapes(
	small = RoundedCornerShape(0.dp),
	medium = RoundedCornerShape(0.dp),
	large = RoundedCornerShape(0.dp)
)

val Shapes.modal: CornerBasedShape
	get() = RoundedCornerShape(0.dp)
