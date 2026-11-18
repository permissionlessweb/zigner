package net.rotko.zigner.screens.keysetdetails

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.MaterialTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.QrCode
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.rotko.zigner.R
import net.rotko.zigner.components.base.BottomSheetConfirmDialog
import net.rotko.zigner.components.base.SecondaryButtonWide
import net.rotko.zigner.domain.Callback
import net.rotko.zigner.domain.FeatureFlags
import net.rotko.zigner.domain.FeatureOption
import net.rotko.zigner.domain.NetworkState
import net.rotko.zigner.screens.keydetails.MenuItemForBottomSheet
import net.rotko.zigner.ui.theme.SignerNewTheme
import net.rotko.zigner.ui.theme.red400


@Composable
fun KeySetDeleteConfirmBottomSheet(
	onCancel: Callback,
	onRemoveKeySet: Callback,
) {
	BottomSheetConfirmDialog(
		title = stringResource(R.string.remove_key_set_confirm_title),
		message = stringResource(R.string.remove_key_set_confirm_text),
		ctaLabel = stringResource(R.string.remove_key_set_confirm_cta),
		onCancel = onCancel,
		onCta = onRemoveKeySet,
	)
}

@Composable
fun KeyDetailsMenuGeneral(
	networkState: State<NetworkState?>,
	onSelectKeysClicked: Callback,
	onEverydayWalletClicked: Callback,
	onAddDerivedKeyClicked: Callback,
	onBackupSeedClicked: Callback,
	onExportToDeviceClicked: Callback,
	onBackupMultisigClicked: Callback,
	onSendMultisigToZafuClicked: Callback,
	onDeleteClicked: Callback,
	exposeConfirmAction: Callback,//also called shield
	onCancel: Callback,
) {
	val sidePadding = 24.dp
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.padding(start = sidePadding, end = sidePadding, top = 8.dp),
	) {

		// Export Keys — parity-signer SCALE-encoded multipart QR (magic prefix
		// 0x53 0xff 0xde) for Substrate address discovery in hot wallets like
		// Polkadot.js. Substrate-only: address encoding only handles SS58 /
		// eth-pubkey, no Zcash/Penumbra support. No current zafu/zcli consumer.
		// Hidden until substrate use returns or protocol extends upstream.
		// MenuItemForBottomSheet(
		// 	Icons.Outlined.FileUpload,
		// 	label = stringResource(R.string.menu_option_export_keys),
		// 	onclick = onSelectKeysClicked
		// )

		MenuItemForBottomSheet(
			Icons.Outlined.QrCode2,
			label = stringResource(R.string.key_set_menu_option_everyday_wallet),
			onclick = onEverydayWalletClicked,
		)

		MenuItemForBottomSheet(
			Icons.Outlined.Add,
			label = stringResource(R.string.key_set_menu_option_add_key),
			onclick = onAddDerivedKeyClicked,
		)

		MenuItemForBottomSheet(
			iconId = R.drawable.ic_settings_backup_restore_28,
			label = stringResource(R.string.key_set_menu_option_backup_seed),
			onclick = {
				if (networkState.value == NetworkState.None)
					onBackupSeedClicked()
				else
					exposeConfirmAction()
			}
		)

		// Export to New Device — animated backup QR (exportBackupQr) carrying
		// account metadata only, no seed. Scanning it on a fresh phone errors
		// "hot wallet not found - import the seed phrase first via Banana Split
		// or manual entry", which is confusing. Banana Split is now the sole
		// keyset backup/transfer path. Hidden (dormant, not deleted); the
		// BackupExport destination + onExportToDeviceClicked wiring remain so
		// this is trivially reversible.
		// MenuItemForBottomSheet(
		// 	Icons.Outlined.QrCode,
		// 	label = stringResource(R.string.key_set_menu_option_export_wallet),
		// 	onclick = onExportToDeviceClicked
		// )

		MenuItemForBottomSheet(
			Icons.Outlined.FileDownload,
			label = stringResource(R.string.key_set_menu_option_backup_multisig),
			onclick = onBackupMultisigClicked,
		)

		MenuItemForBottomSheet(
			Icons.Outlined.QrCode2,
			label = stringResource(R.string.key_set_menu_option_send_multisig_to_zafu),
			onclick = onSendMultisigToZafuClicked,
		)

		MenuItemForBottomSheet(
			iconId = R.drawable.ic_backspace_28,
			label = stringResource(R.string.menu_option_forget_delete_key),
			tint = MaterialTheme.colors.red400,
			onclick = onDeleteClicked
		)
		Spacer(modifier = Modifier.padding(bottom = 8.dp))
		SecondaryButtonWide(
			label = stringResource(R.string.generic_cancel),
			onClicked = onCancel
		)
		Spacer(modifier = Modifier.padding(bottom = 16.dp))
	}
}


@Preview(
	name = "light", group = "general", uiMode = Configuration.UI_MODE_NIGHT_NO,
	showBackground = true, backgroundColor = 0xFFFFFFFF,
)
@Preview(
	name = "dark", group = "general",
	uiMode = Configuration.UI_MODE_NIGHT_YES,
	showBackground = true, backgroundColor = 0xFF000000,
)
@Composable
private fun PreviewKeyDetailsMenu() {
	SignerNewTheme {
		val state = remember { mutableStateOf(NetworkState.None) }
		KeyDetailsMenuGeneral(
			state, {}, {}, {}, {}, {}, {}, {}, {}, {}, {},
		)
	}
}

@Preview(
	name = "light", group = "general", uiMode = Configuration.UI_MODE_NIGHT_NO,
	showBackground = true, backgroundColor = 0xFFFFFFFF,
)
@Preview(
	name = "dark", group = "general",
	uiMode = Configuration.UI_MODE_NIGHT_YES,
	showBackground = true, backgroundColor = 0xFF000000,
)
@Composable
private fun PreviewKeyDetailsMenuConfirm() {
	SignerNewTheme {
		KeySetDeleteConfirmBottomSheet(
			{}, {},
		)
	}
}
