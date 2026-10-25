package net.rotko.zigner.screens.keydetails

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.AlertDialog
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Switch
import androidx.compose.material.SwitchDefaults
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.rotko.zigner.R
import net.rotko.zigner.components.networkicon.IdentIconWithNetwork
import net.rotko.zigner.components.NetworkLabelWithIcon
import net.rotko.zigner.components.base.ScreenHeaderClose
import net.rotko.zigner.components.base.SignerDivider
import net.rotko.zigner.components.sharedcomponents.ShowBase58Collapsible
import net.rotko.zigner.domain.Callback
import net.rotko.zigner.domain.EmptyNavigator
import net.rotko.zigner.domain.KeyDetailsModel
import net.rotko.zigner.domain.intoImageBitmap
import net.rotko.zigner.ui.helpers.PreviewData
import net.rotko.zigner.ui.theme.SignerNewTheme
import net.rotko.zigner.ui.theme.SignerTypeface
import net.rotko.zigner.ui.theme.appliedStroke
import net.rotko.zigner.ui.theme.fill12
import net.rotko.zigner.ui.theme.fill6
import net.rotko.zigner.ui.theme.pink500
import net.rotko.zigner.ui.theme.red500
import net.rotko.zigner.ui.theme.red500fill12
import net.rotko.zigner.ui.theme.textTertiary
import io.parity.signer.uniffi.Action
import io.parity.signer.uniffi.ZcashVerifiedNoteDisplay
import io.parity.signer.uniffi.encodeToQr
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Check if network uses export QR (Penumbra, Zcash, Cosmos) - these show export QR instead of address
 */
private fun isFvkNetwork(networkLogo: String): Boolean {
	val logo = networkLogo.lowercase()
	return logo.contains("penumbra") || logo.contains("zcash") ||
		isCosmosNetwork(networkLogo)
}

private fun isCosmosNetwork(networkLogo: String): Boolean {
	val logo = networkLogo.lowercase()
	return logo.contains("noble") || logo.contains("osmosis") ||
		logo.contains("celestia") || logo.contains("cosmos") || logo.contains("terp")
}

/**
 * Default main screen with list Seeds/root keys
 */
@Composable
fun KeyDetailsPublicKeyScreen(
	model: KeyDetailsModel,
	onBack: Callback,
	onMenu: Callback,
	fvkResult: FvkExportResult? = null,
	fvkLoading: Boolean = false,
	onRequestFvk: Callback = {},
	zcashDiversifiedAddress: String? = null,
	zcashDiversifiedLoading: Boolean = false,
	onRequestZcashDiversifiedAddress: (UInt) -> Unit = {},
	zcashTransparentAddress: String? = null,
	zcashTransparentLoading: Boolean = false,
	onRequestZcashTransparentAddress: Callback = {},
	zcashVerifiedBalance: ULong? = null,
	zcashVerifiedNotes: List<ZcashVerifiedNoteDisplay> = emptyList(),
	zcashSyncedAt: ULong? = null,
	zcashAnchorHeight: UInt? = null,
	zcashAnchorTime: UInt? = null,
) {
	val isFvkNetwork = isFvkNetwork(model.networkInfo.networkLogo)
	val isZcash = model.networkInfo.networkLogo.lowercase().contains("zcash")
	var showFvk by remember { mutableStateOf(true) }
	var showFvkCompatInfo by remember { mutableStateOf(false) }
	// Zcash diversified address state
	var diversifierIndex by remember { mutableIntStateOf(0) }
	var showTransparent by remember { mutableStateOf(false) }

	// Auto-request FVK for Penumbra/Zcash on first load
	LaunchedEffect(isFvkNetwork) {
		if (isFvkNetwork && fvkResult == null && !fvkLoading) {
			onRequestFvk()
		}
	}

	Column(Modifier.background(MaterialTheme.colors.background)) {
		ScreenHeaderClose(
			stringResource(id = R.string.key_details_public_export_title),
			if (model.isRootKey) {
				null
			} else {
				stringResource(id = R.string.key_details_public_export_derived_subtitle)
			},
			onClose = onBack,
			onMenu = onMenu,
		)
		Box(modifier = Modifier.weight(1f)) {
			Column(
				modifier = Modifier.verticalScroll(rememberScrollState())
			) {

				if (model.secretExposed) {
					ExposedKeyAlarm()
				}

				val plateShape =
					RoundedCornerShape(dimensionResource(id = R.dimen.qrShapeCornerRadius))
				Column(
					modifier = Modifier
						.padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 8.dp)
						.clip(plateShape)
						.border(
							BorderStroke(1.dp, MaterialTheme.colors.appliedStroke),
							plateShape
						)
						.background(MaterialTheme.colors.fill6, plateShape)
				) {
					Box(
						modifier = Modifier
							.fillMaxWidth(1f)
							.aspectRatio(1.1f)
							.background(
								Color.White,
								plateShape
							),
						contentAlignment = Alignment.Center,
					) {
						val isPreview = LocalInspectionMode.current

						when {
							// FVK networks: show FVK QR or Address QR based on toggle
							isFvkNetwork && showFvk && fvkLoading -> {
								CircularProgressIndicator(
									color = MaterialTheme.colors.pink500,
									modifier = Modifier.size(48.dp)
								)
							}
							isFvkNetwork && showFvk && fvkResult != null -> {
								val fvkQrImage = remember(fvkResult) {
									fvkResult.qrPng.intoImageBitmap()
								}
								Image(
									bitmap = fvkQrImage,
									contentDescription = "Full Viewing Key QR",
									contentScale = ContentScale.Fit,
									modifier = Modifier.size(264.dp)
								)
							}
							// Zcash Receive: transparent address
							isZcash && !showFvk && showTransparent -> {
								if (zcashTransparentLoading) {
									CircularProgressIndicator(
										color = MaterialTheme.colors.pink500,
										modifier = Modifier.size(48.dp)
									)
								} else if (zcashTransparentAddress != null) {
									val tAddrQr = remember(zcashTransparentAddress) {
										runBlocking {
											encodeToQr(
												zcashTransparentAddress.toByteArray(Charsets.UTF_8)
													.map { it.toUByte() },
												false
											)
										}
									}
									Image(
										bitmap = tAddrQr.intoImageBitmap(),
										contentDescription = "Transparent address QR",
										contentScale = ContentScale.Fit,
										modifier = Modifier.size(264.dp)
									)
								}
							}
							// Zcash Receive: diversified orchard address
							isZcash && !showFvk -> {
								if (zcashDiversifiedLoading) {
									CircularProgressIndicator(
										color = MaterialTheme.colors.pink500,
										modifier = Modifier.size(48.dp)
									)
								} else if (zcashDiversifiedAddress != null) {
									val divAddrQr = remember(zcashDiversifiedAddress) {
										runBlocking {
											encodeToQr(
												zcashDiversifiedAddress.toByteArray(Charsets.UTF_8)
													.map { it.toUByte() },
												false
											)
										}
									}
									Image(
										bitmap = divAddrQr.intoImageBitmap(),
										contentDescription = "Orchard address QR",
										contentScale = ContentScale.Fit,
										modifier = Modifier.size(264.dp)
									)
								} else {
									val qrImage = remember {
										if (isPreview) {
											PreviewData.exampleQRImage
										} else {
											runBlocking { encodeToQr(model.qrData, false) }
										}
									}
									Image(
										bitmap = qrImage.intoImageBitmap(),
										contentDescription = stringResource(R.string.qr_with_address_to_scan_description),
										contentScale = ContentScale.Fit,
										modifier = Modifier.size(264.dp)
									)
								}
							}
							// Address QR (for receiving funds) - all networks including FVK networks
							else -> {
								val qrImage = remember {
									if (isPreview) {
										PreviewData.exampleQRImage
									} else {
										runBlocking { encodeToQr(model.qrData, false) }
									}
								}
								Image(
									bitmap = qrImage.intoImageBitmap(),
									contentDescription = stringResource(R.string.qr_with_address_to_scan_description),
									contentScale = ContentScale.Fit,
									modifier = Modifier.size(264.dp)
								)
							}
						}
					}
					Row(
						Modifier.padding(16.dp),
						verticalAlignment = Alignment.CenterVertically,
					) {
						if (isFvkNetwork && showFvk && fvkResult != null) {
							val keyLabel = if (fvkResult.networkType == "cosmos") "PubKey" else "FVK"
							Text(
								text = "$keyLabel: ${fvkResult.displayKey.take(20)}...",
								style = SignerTypeface.CaptionM,
								color = MaterialTheme.colors.textTertiary,
								modifier = Modifier.weight(1f)
							)
						} else if (isZcash && !showFvk && showTransparent && zcashTransparentAddress != null) {
							Text(
								text = zcashTransparentAddress.take(12) + "..." + zcashTransparentAddress.takeLast(8),
								style = SignerTypeface.CaptionM,
								color = MaterialTheme.colors.textTertiary,
								modifier = Modifier.weight(1f)
							)
						} else if (isZcash && !showFvk && zcashDiversifiedAddress != null) {
							Text(
								text = zcashDiversifiedAddress.take(12) + "..." + zcashDiversifiedAddress.takeLast(8),
								style = SignerTypeface.CaptionM,
								color = MaterialTheme.colors.textTertiary,
								modifier = Modifier.weight(1f)
							)
						} else {
							ShowBase58Collapsible(
								base58 = model.address.cardBase.base58,
								modifier = Modifier
									.weight(1f)
							)
						}
						IdentIconWithNetwork(
							identicon = model.address.cardBase.identIcon,
							networkLogoName = model.address.network,
							size = 36.dp,
							modifier = Modifier.padding(start = 8.dp)
						)
					}
				}

				// Toggle buttons for FVK networks (FVK vs Address)
				if (isFvkNetwork) {
					Row(
						modifier = Modifier
							.fillMaxWidth()
							.padding(horizontal = 24.dp, vertical = 8.dp),
						horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center
					) {
						val selectedBg = MaterialTheme.colors.pink500
						val unselectedBg = MaterialTheme.colors.fill12

						// Export button
						val isCosmosNet = isCosmosNetwork(model.networkInfo.networkLogo)
						Text(
							text = if (isCosmosNet) "Export" else "FVK",
							style = SignerTypeface.LabelM,
							color = if (showFvk) Color.White else MaterialTheme.colors.primary,
							modifier = Modifier
								.clip(RoundedCornerShape(8.dp, 0.dp, 0.dp, 8.dp))
								.background(if (showFvk) selectedBg else unselectedBg)
								.clickable { showFvk = true }
								.padding(horizontal = 24.dp, vertical = 10.dp)
						)
						// Address button
						Text(
							text = "Receive",
							style = SignerTypeface.LabelM,
							color = if (!showFvk) Color.White else MaterialTheme.colors.primary,
							modifier = Modifier
								.clip(RoundedCornerShape(0.dp, 8.dp, 8.dp, 0.dp))
								.background(if (!showFvk) selectedBg else unselectedBg)
								.clickable { showFvk = false }
								.padding(horizontal = 24.dp, vertical = 10.dp)
						)
					}
					// Info text
					Row(
						modifier = Modifier
							.fillMaxWidth()
							.padding(horizontal = 24.dp, vertical = 4.dp),
						verticalAlignment = Alignment.CenterVertically,
					) {
						Text(
							text = if (showFvk) {
								"scan with Zafu or any Keystone-compatible wallet to import as watch-only"
							} else {
								"scan to send funds to this cold wallet"
							},
							style = SignerTypeface.CaptionM,
							color = MaterialTheme.colors.textTertiary,
							textAlign = TextAlign.Center,
							modifier = Modifier.weight(1f),
						)
						if (showFvk) {
							Icon(
								imageVector = Icons.Outlined.Info,
								contentDescription = "Compatibility info",
								tint = MaterialTheme.colors.primary,
								modifier = Modifier
									.padding(start = 6.dp)
									.size(16.dp)
									.clickable { showFvkCompatInfo = true }
							)
						}
					}

					val fvkCompatMessage = when {
						isZcash -> "Tested with Zafu and Zodl."
						isCosmosNetwork(model.networkInfo.networkLogo) -> "Tested with Zafu."
						else -> "Tested with Zafu." // Penumbra
					}
					if (showFvkCompatInfo) {
						AlertDialog(
							onDismissRequest = { showFvkCompatInfo = false },
							title = {
								Text(
									text = "Watch-only wallet compatibility",
									style = SignerTypeface.TitleS,
									color = MaterialTheme.colors.primary,
								)
							},
							text = {
								Text(
									text = fvkCompatMessage,
									style = SignerTypeface.BodyM,
									color = MaterialTheme.colors.textTertiary,
								)
							},
							confirmButton = {
								TextButton(onClick = { showFvkCompatInfo = false }) {
									Text("OK", color = MaterialTheme.colors.primary)
								}
							},
							backgroundColor = MaterialTheme.colors.background,
						)
					}
				}

				// Zcash Receive mode: New Address button + Transparent toggle
				if (isZcash && !showFvk) {
					// New Address button
					if (!showTransparent) {
						Box(
							modifier = Modifier
								.fillMaxWidth()
								.padding(horizontal = 24.dp, vertical = 4.dp),
							contentAlignment = Alignment.Center
						) {
							Text(
								text = "New Address",
								style = SignerTypeface.LabelM,
								color = Color.White,
								modifier = Modifier
									.clip(RoundedCornerShape(8.dp))
									.background(MaterialTheme.colors.pink500)
									.clickable {
										diversifierIndex++
										onRequestZcashDiversifiedAddress(diversifierIndex.toUInt())
									}
									.padding(horizontal = 24.dp, vertical = 10.dp)
							)
						}
					}

					// Transparent address toggle
					Row(
						modifier = Modifier
							.fillMaxWidth()
							.padding(horizontal = 24.dp, vertical = 8.dp),
						verticalAlignment = Alignment.CenterVertically,
					) {
						Text(
							text = "Transparent address",
							style = SignerTypeface.BodyL,
							color = MaterialTheme.colors.primary,
							modifier = Modifier.weight(1f)
						)
						Switch(
							checked = showTransparent,
							onCheckedChange = { checked ->
								showTransparent = checked
								if (checked && zcashTransparentAddress == null) {
									onRequestZcashTransparentAddress()
								}
							},
							colors = SwitchDefaults.colors(
								checkedThumbColor = MaterialTheme.colors.pink500,
								checkedTrackColor = MaterialTheme.colors.pink500.copy(alpha = 0.5f)
							)
						)
					}
					if (showTransparent) {
						Row(
							modifier = Modifier
								.fillMaxWidth()
								.padding(horizontal = 24.dp)
								.background(
									MaterialTheme.colors.red500fill12,
									RoundedCornerShape(8.dp)
								)
								.padding(12.dp),
							verticalAlignment = Alignment.CenterVertically,
						) {
							Icon(
								imageVector = Icons.Outlined.Info,
								contentDescription = null,
								tint = MaterialTheme.colors.red500,
								modifier = Modifier
									.size(20.dp)
									.padding(end = 4.dp)
							)
							Text(
								text = "Transparent addresses offer no privacy. Use only for exchange compatibility.",
								style = SignerTypeface.CaptionM,
								color = MaterialTheme.colors.primary,
							)
						}
					}
				}

				// Zcash verified balance + notes
				if (isZcash && zcashVerifiedBalance != null) {
					ZcashVerifiedBalanceSection(
						balance = zcashVerifiedBalance,
						notes = zcashVerifiedNotes,
						syncedAt = zcashSyncedAt,
						anchorHeight = zcashAnchorHeight,
						anchorTime = zcashAnchorTime,
					)
				}

				BottomKeyPlate(plateShape, model, isFvkNetwork)
			}
		}
	}
}

@Composable
private fun ZcashVerifiedBalanceSection(
	balance: ULong,
	notes: List<ZcashVerifiedNoteDisplay>,
	syncedAt: ULong? = null,
	anchorHeight: UInt? = null,
	anchorTime: UInt? = null,
) {
	val plateShape = RoundedCornerShape(dimensionResource(id = R.dimen.qrShapeCornerRadius))
	Column(
		modifier = Modifier
			.padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 8.dp)
			.clip(plateShape)
			.background(MaterialTheme.colors.fill6, plateShape)
			.padding(16.dp),
		verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
	) {
		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
			verticalAlignment = Alignment.CenterVertically,
		) {
			Text(
				text = "Verified Balance",
				style = SignerTypeface.LabelM,
				color = MaterialTheme.colors.textTertiary
			)
			if (syncedAt != null && syncedAt.toLong() > 0) {
				Text(
					text = formatTimeAgo(syncedAt.toLong()),
					style = SignerTypeface.CaptionM,
					color = MaterialTheme.colors.textTertiary
				)
			}
		}
		val zec = balance.toLong() / 100_000_000.0
		Text(
			text = "%.8f ZEC".format(zec),
			style = SignerTypeface.TitleL,
			color = MaterialTheme.colors.primary
		)
		// This balance is a snapshot, not a live figure — it is only true as of
		// the block the notes were verified against. State that block explicitly
		// so a passively-viewed balance never implies it is current. Tap to read
		// it as the block's real date (the block header's own time) when known.
		if (anchorHeight != null && anchorHeight.toLong() > 0) {
			var showAnchorDate by remember { mutableStateOf(false) }
			val hasAnchorDate = anchorTime != null && anchorTime.toLong() > 0
			Text(
				text = if (showAnchorDate && hasAnchorDate)
					"as of ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(anchorTime!!.toLong() * 1000L))}"
				else
					"as of block ${NumberFormat.getNumberInstance(Locale.US).format(anchorHeight.toLong())}",
				style = SignerTypeface.CaptionM,
				color = MaterialTheme.colors.textTertiary,
				modifier = if (hasAnchorDate)
					Modifier.clickable { showAnchorDate = !showAnchorDate }
				else
					Modifier
			)
		}
		if (notes.isNotEmpty()) {
			SignerDivider(sidePadding = 0.dp)
			Text(
				text = "${notes.size} note${if (notes.size == 1) "" else "s"}",
				style = SignerTypeface.CaptionM,
				color = MaterialTheme.colors.textTertiary
			)
			for (note in notes.take(10)) {
				val noteZec = note.value.toLong() / 100_000_000.0
				Row(
					modifier = Modifier.fillMaxWidth(),
					horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween
				) {
					Text(
						text = "%.8f ZEC".format(noteZec),
						style = SignerTypeface.CaptionM,
						color = MaterialTheme.colors.primary
					)
					Text(
						text = "height ${NumberFormat.getNumberInstance(Locale.US).format(note.blockHeight.toLong())}",
						style = SignerTypeface.CaptionM,
						color = MaterialTheme.colors.textTertiary
					)
				}
			}
			if (notes.size > 10) {
				Text(
					text = "...and ${notes.size - 10} more",
					style = SignerTypeface.CaptionM,
					color = MaterialTheme.colors.textTertiary
				)
			}
		}
	}
}

@Composable
private fun BottomKeyPlate(
	plateShape: RoundedCornerShape,
	model: KeyDetailsModel,
	isFvkNetwork: Boolean = false
) {
	Column(
		modifier = Modifier
			.padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 8.dp)
			.background(MaterialTheme.colors.fill6, plateShape)
			.padding(horizontal = 16.dp)
	) {
		Row(
			verticalAlignment = Alignment.CenterVertically,
			modifier = Modifier
				.defaultMinSize(minHeight = 48.dp)
				.padding(vertical = 8.dp)
		) {
			Text(
				text = stringResource(R.string.key_details_public_label_network),
				style = SignerTypeface.BodyL,
				color = MaterialTheme.colors.textTertiary
			)
			Spacer(modifier = Modifier.weight(1f))
			NetworkLabelWithIcon(
				model.networkInfo.networkTitle,
				model.networkInfo.networkLogo,
			)
		}
		SignerDivider(sidePadding = 0.dp)
		Row(
			verticalAlignment = Alignment.CenterVertically,
			modifier = Modifier
				.defaultMinSize(minHeight = 48.dp)
				.padding(vertical = 8.dp)
		) {
			Text(
				text = stringResource(R.string.key_details_public_label_path),
				style = SignerTypeface.BodyL,
				color = MaterialTheme.colors.textTertiary
			)
			Spacer(modifier = Modifier
				.padding(start = 16.dp)
				.weight(1f))
			val path = model.address.cardBase.path
			Text(
				text = if (path.isEmpty() && isFvkNetwork) {
					"Account 0"
				} else path.ifEmpty {
					stringResource(R.string.derivation_key_empty_path_placeholder)
				},
				style = SignerTypeface.BodyL,
				color = MaterialTheme.colors.primary,
				textAlign = TextAlign.End
			)
		}
		SignerDivider(sidePadding = 0.dp)
		Row(
			verticalAlignment = Alignment.CenterVertically,
			modifier = Modifier
				.defaultMinSize(minHeight = 48.dp)
				.padding(vertical = 8.dp)
		) {
			Text(
				text = stringResource(R.string.key_details_public_label_keyset),
				style = SignerTypeface.BodyL,
				color = MaterialTheme.colors.textTertiary
			)
			Spacer(modifier = Modifier.weight(1f))
			Text(
				text = model.address.cardBase.seedName,
				style = SignerTypeface.BodyL,
				color = MaterialTheme.colors.primary
			)
		}
	}
}


@Composable
private fun ExposedKeyAlarm() {
	val innerShape =
		RoundedCornerShape(dimensionResource(id = R.dimen.innerFramesCornerRadius))
	Row(
		modifier = Modifier
			.padding(vertical = 8.dp, horizontal = 24.dp)
			.border(
				BorderStroke(1.dp, MaterialTheme.colors.fill12),
				innerShape
			)
			.background(MaterialTheme.colors.red500fill12, innerShape)

	) {
		Text(
			text = stringResource(R.string.key_details_exposed_notification_label),
			color = MaterialTheme.colors.primary,
			style = SignerTypeface.LabelS,
			modifier = Modifier
				.weight(1f)
				.padding(start = 16.dp, top = 16.dp, bottom = 16.dp)
		)
		Icon(
			imageVector = Icons.Outlined.Info,
			contentDescription = null,
			tint = MaterialTheme.colors.red500,
			modifier = Modifier
				.align(Alignment.CenterVertically)
				.padding(start = 18.dp, end = 18.dp)
		)
	}
}


private fun formatTimeAgo(unixSeconds: Long): String {
	val now = System.currentTimeMillis() / 1000
	val diff = now - unixSeconds
	return when {
		diff < 60 -> "just now"
		diff < 3600 -> "${diff / 60}m ago"
		diff < 86400 -> "${diff / 3600}h ago"
		diff < 604800 -> "${diff / 86400}d ago"
		else -> "${diff / 604800}w ago"
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
private fun PreviewKeyDetailsScreenDerived() {
	val mockModel = KeyDetailsModel.createStubDerived()
	SignerNewTheme {
		Box(modifier = Modifier.size(350.dp, 700.dp)) {
			KeyDetailsPublicKeyScreen(mockModel, {}, {},)
		}
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
private fun PreviewKeyDetailsScreenRoot() {
	val mockModel = KeyDetailsModel.createStubRoot()
	SignerNewTheme {
		Box(modifier = Modifier.size(350.dp, 700.dp)) {
			KeyDetailsPublicKeyScreen(mockModel, {}, {},)
		}
	}
}
