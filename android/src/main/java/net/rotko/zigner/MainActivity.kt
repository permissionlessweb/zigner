package net.rotko.zigner

import android.graphics.Color
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.runtime.collectAsState
import androidx.core.view.WindowCompat
import androidx.navigation.compose.rememberNavController
import net.rotko.zigner.dependencygraph.ServiceLocator
import net.rotko.zigner.domain.addVaultLogger
import net.rotko.zigner.ui.rootnavigation.RootNavigationGraph
import net.rotko.zigner.ui.theme.SignerNewTheme

@ExperimentalMaterialApi
@ExperimentalAnimationApi
class MainActivity : AppCompatActivity() {

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		ServiceLocator.initActivityDependencies(this)
		// Bind the owner account so create-validator cards can say whether the
		// operator is the owner this phone's slot belongs to.
		val ctx = applicationContext
		Thread {
			val owner = net.rotko.zigner.screens.settings.bluetooth.GranterAccount.lookup(ctx)
			net.rotko.zigner.screens.settings.bluetooth.GranterAccount.bindOperator(owner)
		}.start()
		if (net.rotko.zigner.screens.settings.bluetooth.SignerRuntime.isArmed(this)) {
			net.rotko.zigner.screens.settings.bluetooth.RemoteSignerService.resume(this)
		}

		//remove automatic insets so bottom sheet can dimm status bar, other views will add their paddings if needed.
		WindowCompat.setDecorFitsSystemWindows(window, false)
		window.statusBarColor = Color.TRANSPARENT;

		setContent {
			val lightTheme = ServiceLocator.preferencesRepository.lightThemeEnabled
				.collectAsState(initial = false)
			SignerNewTheme(darkTheme = !lightTheme.value) {
				RootNavigationGraph(
					navController = rememberNavController().apply {
						addVaultLogger(
							"Root Nav graph controller"
						)
					})
			}
		}
	}

	override fun onDestroy() {
		ServiceLocator.deinitActivityDependencies()
		super.onDestroy()
	}
}



