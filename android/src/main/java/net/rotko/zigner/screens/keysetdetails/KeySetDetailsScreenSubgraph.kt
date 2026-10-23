package net.rotko.zigner.screens.keysetdetails

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import net.rotko.zigner.components.exposesecurity.ExposedAlert
import net.rotko.zigner.domain.Callback
import net.rotko.zigner.domain.submitErrorState
import net.rotko.zigner.screens.error.handleErrorAppState
import net.rotko.zigner.screens.initial.WaitingScreen
import net.rotko.zigner.screens.keysetdetails.backup.BackupMethodSelectBottomSheet
import net.rotko.zigner.screens.keysetdetails.backup.KeySetBackupFullOverlayBottomSheet
import net.rotko.zigner.screens.keysetdetails.backup.toSeedBackupModel
import net.rotko.zigner.screens.keysetdetails.empty.NoKeySetEmptyWelcomeScreen
import net.rotko.zigner.screens.keysetdetails.export.KeySetDetailsExportResultBottomSheet
import net.rotko.zigner.screens.keysetdetails.export.KeySetDetailsMultiselectBottomSheet
import net.rotko.zigner.screens.keysetdetails.exportroot.KeySetRootExportBottomSheet
import net.rotko.zigner.screens.keysetdetails.filtermenu.NetworkFilterMenu
import net.rotko.zigner.screens.keysetdetails.seedselectmenu.SeedSelectMenuFull
import net.rotko.zigner.ui.BottomSheetWrapperRoot
import net.rotko.zigner.ui.mainnavigation.CoreUnlockedNavSubgraph
import kotlinx.coroutines.launch

/**
 * @param seedName is optional - null when we need to open last one
 */
@Composable
fun KeySetDetailsScreenSubgraph(
	originalSeedName: String?,
	coreNavController: NavController,
) {
	val menuNavController = rememberNavController()
	val context = LocalContext.current
	val coroutineScope = rememberCoroutineScope()

	val keySetViewModel: KeySetDetailsViewModel = viewModel()
	val seedName = keySetViewModel.shownSeedName.collectAsStateWithLifecycle()

	LaunchedEffect(key1 = originalSeedName) {
		keySetViewModel.feedModelForSeed(originalSeedName)
	}
	LaunchedEffect(key1 = Unit) {
		//this is first screen - check db version here
		keySetViewModel.validateDbSchemaCorrect()
			.handleErrorAppState(coreNavController)
	}

	val filteredScreenModel =
		keySetViewModel.filteredScreenState.collectAsStateWithLifecycle()
	val networkState = keySetViewModel.networkState.collectAsStateWithLifecycle()

	when (val state =
		filteredScreenModel.value.handleErrorAppState(coreNavController)) {

		KeySetDetailsScreenState.NoKeySets -> {
			NoKeySetEmptyWelcomeScreen(
				onExposedShow = {
					menuNavController.navigate(KeySetDetailsMenuSubgraph.exposed_shield_alert) {
						popUpTo(KeySetDetailsMenuSubgraph.empty)
					}
				},
				onNewKeySet = {
					coreNavController.navigate(
						CoreUnlockedNavSubgraph.newKeySet
					)
				},
				onRecoverKeySet = {
					coreNavController.navigate(
						CoreUnlockedNavSubgraph.recoverKeySet
					)
				},
				networkState = networkState,
			)
		}

		KeySetDetailsScreenState.LoadingState, null -> WaitingScreen()
		is KeySetDetailsScreenState.Data -> {
			val terpGranter = state.filteredModel.keysAndNetwork
				.firstOrNull { it.key.base58.startsWith("terp1") && it.network.networkTitle.contains("terp", ignoreCase = true) }
				?.key?.base58
			LaunchedEffect(state.filteredModel.root.seedName, terpGranter) {
				if (!terpGranter.isNullOrBlank()) {
					net.rotko.zigner.screens.settings.bluetooth.SignerRuntime.setGranter(context, terpGranter)
				}
			}

			Box(Modifier.statusBarsPadding()) {
				KeySetDetailsScreenView(
					model = state.filteredModel,
					navController = coreNavController,
					networkState = networkState,
					fullModelWasEmpty = state.wasEmptyKeyset,
					onExposedClicked = {
						menuNavController.navigate(KeySetDetailsMenuSubgraph.exposed_shield_alert) {
							popUpTo(KeySetDetailsMenuSubgraph.empty)
						}
					},
					onMenu = {
						menuNavController.navigate(KeySetDetailsMenuSubgraph.keys_menu)
					},
					onAddNewDerivation = {
						coreNavController.navigate(
							CoreUnlockedNavSubgraph.NewDerivedKey.destination(
								seedName = state.filteredModel.root.seedName
							)
						)
					},
					onSeedSelect = {
						menuNavController.navigate(KeySetDetailsMenuSubgraph.select_keyset)
					},
					onFilterClicked = {
						menuNavController.navigate(KeySetDetailsMenuSubgraph.network_filter)
					},
					onShowRoot = {
						menuNavController.navigate(KeySetDetailsMenuSubgraph.show_root)
					},
					onOpenKey = { keyAddr: String, keySpecs: String ->
						if (keyAddr.startsWith("terp1")) {
							net.rotko.zigner.screens.settings.bluetooth.SignerRuntime.setGranter(context, keyAddr)
						}
						coreNavController.navigate(
							CoreUnlockedNavSubgraph.KeyDetails.destination(
								keyAddr = keyAddr,
								keySpec = keySpecs
							)
						)
					},
					getSeedPhrase = { seedName -> keySetViewModel.getSeedPhrase(seedName) },
				)
			}

			NavHost(
				navController = menuNavController,
				startDestination = KeySetDetailsMenuSubgraph.empty,
			) {
				val closeAction: Callback = {
					menuNavController.popBackStack()
				}
				composable(KeySetDetailsMenuSubgraph.empty) {
					//no menu - Spacer element so when other part shown there won't
					// be an appearance animation from top left part despite there shouldn't be
					Spacer(modifier = Modifier.fillMaxSize(1f))
				}
				composable(KeySetDetailsMenuSubgraph.keys_menu) {
					BottomSheetWrapperRoot(onClosedAction = closeAction) {
						KeyDetailsMenuGeneral(
							networkState = networkState,
							onSelectKeysClicked = {
								menuNavController.popBackStack()
								menuNavController.navigate(KeySetDetailsMenuSubgraph.export)
							},
							onBackupSeedClicked = {
								menuNavController.navigate(KeySetDetailsMenuSubgraph.backup_method_select) {
									popUpTo(KeySetDetailsMenuSubgraph.empty)
								}
							},
							onExportToDeviceClicked = {
								menuNavController.popBackStack()
								seedName.value?.let { name ->
									coreNavController.navigate(
										CoreUnlockedNavSubgraph.BackupExport.destination(name)
									)
								}
							},
							onBackupMultisigClicked = {
								menuNavController.popBackStack()
								coreNavController.navigate(CoreUnlockedNavSubgraph.frostBackupAll)
							},
							onSendMultisigToZafuClicked = {
								menuNavController.popBackStack()
								coreNavController.navigate(
									CoreUnlockedNavSubgraph.FrostSendToZafu.destination()
								)
							},
							onCancel = {
								menuNavController.popBackStack()
							},
							onDeleteClicked = {
								menuNavController.navigate(KeySetDetailsMenuSubgraph.keys_menu_delete_confirm) {
									popUpTo(KeySetDetailsMenuSubgraph.empty)
								}
							},
							exposeConfirmAction = {
								menuNavController.navigate(KeySetDetailsMenuSubgraph.exposed_shield_alert) {
									popUpTo(KeySetDetailsMenuSubgraph.empty)
								}
							}
						)
					}
				}
				composable(KeySetDetailsMenuSubgraph.backup_method_select) {
					BottomSheetWrapperRoot(onClosedAction = closeAction) {
						BackupMethodSelectBottomSheet(
							onManualBackup = {
								menuNavController.navigate(KeySetDetailsMenuSubgraph.backup) {
									popUpTo(KeySetDetailsMenuSubgraph.empty)
								}
							},
							onBananaSplit = {
								menuNavController.popBackStack()
								seedName.value?.let { name ->
									coreNavController.navigate(
										CoreUnlockedNavSubgraph.CreateBananaSplit.destination(name)
									)
								}
							},
							onCancel = closeAction
						)
					}
				}
				composable(KeySetDetailsMenuSubgraph.select_keyset) {
					SeedSelectMenuFull(
						coreNavController = coreNavController,
						selectedSeed = state.filteredModel.root.seedName,
						onSelectSeed = { newSeed ->
							keySetViewModel.viewModelScope.launch {
								keySetViewModel.feedModelForSeed(newSeed)
								closeAction()
							}
						},
						onClose = closeAction,
					)
				}
				composable(KeySetDetailsMenuSubgraph.keys_menu_delete_confirm) {
					BottomSheetWrapperRoot(onClosedAction = closeAction) {
						KeySetDeleteConfirmBottomSheet(
							onCancel = closeAction,
							onRemoveKeySet = {
								val root = state.filteredModel.root
								coroutineScope.launch {
									keySetViewModel.removeSeed(root)
										.handleErrorAppState(coreNavController)
									closeAction()
								}
							},
						)
					}
				}
				composable(
					route = KeySetDetailsMenuSubgraph.show_root,
				) { backStackEntry ->
					BottomSheetWrapperRoot(onClosedAction = closeAction) {
						KeySetRootExportBottomSheet(
							model = state.filteredModel.root,
							onClose = closeAction,
						)
					}
				}
				composable(KeySetDetailsMenuSubgraph.network_filter) {
					val initialSelection =
						keySetViewModel.filters.collectAsStateWithLifecycle()
					BottomSheetWrapperRoot(onClosedAction = closeAction) {
						NetworkFilterMenu(
							networks = keySetViewModel.getAllNetworks(),
							initialSelection = initialSelection.value,
							onConfirm = {
								keySetViewModel.setFilters(it)
								closeAction()
							},
							onCancel = closeAction,
						)
					}
				}
				composable(KeySetDetailsMenuSubgraph.backup) {
					//preconditions
					val backupModel = state.filteredModel.toSeedBackupModel()
					if (backupModel == null) {
						submitErrorState(
							"navigated to backup model but without root in KeySet " +
								"it's impossible to backup"
						)
						closeAction()
					} else {
						//content
						KeySetBackupFullOverlayBottomSheet(
							model = backupModel,
							getSeedPhraseForBackup = keySetViewModel::getSeedPhrase,
							onClose = closeAction,
						)
					}
				}
				composable(KeySetDetailsMenuSubgraph.export) {
					// Auto-select all keys and go directly to export result
					// This simplifies the backup flow - no wallet selection needed
					val allKeys = state.filteredModel.keysAndNetwork.map { it.key.addressKey }.toSet()

					BottomSheetWrapperRoot(onClosedAction = closeAction) {
						KeySetDetailsExportResultBottomSheet(
							model = state.filteredModel,
							selectedKeys = allKeys,
							onClose = closeAction,
						)
					}
				}
				composable(KeySetDetailsMenuSubgraph.exposed_shield_alert) {
					ExposedAlert(navigateBack = { menuNavController.popBackStack() })
				}
			}
		}
	}
}


private object KeySetDetailsMenuSubgraph {
	const val empty = "keys_menu_empty"
	const val select_keyset = "keys_menu_select_keyset"
	const val keys_menu = "keys_menu"
	const val keys_menu_delete_confirm = "keys_menu_delete_confirm"
	const val network_filter = "keys_network_filters"
	const val backup_method_select = "backup_method_select"
	const val backup = "keyset_details_backup"
	const val export = "export_multiselect"
	const val exposed_shield_alert = "keys_exposed_shield_alert"
	const val show_root = "keyset_root_export_and_base58"
}

