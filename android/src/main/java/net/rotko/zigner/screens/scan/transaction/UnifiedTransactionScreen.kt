package net.rotko.zigner.screens.scan.transaction

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Tab
import androidx.compose.material.TabRow
import androidx.compose.material.TabRowDefaults
import androidx.compose.material.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.parity.signer.uniffi.CosmosMsgDisplay
import net.rotko.zigner.components.security.TapjackGuard
import net.rotko.zigner.components.base.PrimaryButtonWide
import net.rotko.zigner.components.base.ScreenHeader
import net.rotko.zigner.components.base.SecondaryButtonWide
import net.rotko.zigner.domain.Callback
import net.rotko.zigner.ui.theme.*

/**
 * Unified transaction signing screen.
 * Renders different content based on network type.
 * Simple tab: human-readable summary focused on what matters (recipient, amount, tx type).
 * Advanced tab: raw QR hex payload for verification.
 */
@Composable
fun UnifiedTransactionScreen(
	signRequest: SignRequest,
	onApprove: Callback,
	onDecline: Callback,
	modifier: Modifier = Modifier,
) {
	// Approval screen: reject touches while an overlay is up.
	TapjackGuard()

	var selectedTab by remember { mutableStateOf(0) }
	val tabs = listOf("Simple", "Advanced")

	Column(
		modifier = modifier
			.fillMaxSize()
			.background(MaterialTheme.colors.backgroundPrimary)
	) {
		ScreenHeader(
			title = "Sign Transaction",
			onBack = onDecline,
		)

		// Network badge
		Row(
			modifier = Modifier
				.padding(horizontal = 16.dp)
				.padding(bottom = 8.dp),
			horizontalArrangement = Arrangement.spacedBy(8.dp),
		) {
			NetworkBadge(signRequest.networkName)
			TxTypeBadge(signRequest)
		}

		// Tab row
		TabRow(
			selectedTabIndex = selectedTab,
			backgroundColor = MaterialTheme.colors.backgroundPrimary,
			contentColor = MaterialTheme.colors.primary,
			indicator = { tabPositions ->
				TabRowDefaults.Indicator(
					modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
					color = MaterialTheme.colors.primary,
				)
			},
			modifier = Modifier.padding(horizontal = 16.dp),
		) {
			tabs.forEachIndexed { index, title ->
				Tab(
					selected = selectedTab == index,
					onClick = { selectedTab = index },
					text = {
						Text(
							text = title,
							style = SignerTypeface.LabelM,
							color = if (selectedTab == index)
								MaterialTheme.colors.primary
							else
								MaterialTheme.colors.textTertiary,
						)
					}
				)
			}
		}

		// Tab content
		Column(
			modifier = Modifier
				.weight(1f)
				.verticalScroll(rememberScrollState())
				.padding(16.dp),
			verticalArrangement = Arrangement.spacedBy(12.dp),
		) {
			when (selectedTab) {
				0 -> SimpleTabContent(signRequest)
				1 -> AdvancedTabContent(signRequest)
			}
		}

		// Action buttons
		Column(
			modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
			verticalArrangement = Arrangement.spacedBy(8.dp),
		) {
			PrimaryButtonWide(
				label = "Approve & Sign",
				onClicked = onApprove,
			)
			SecondaryButtonWide(
				label = "Decline",
				onClicked = onDecline,
			)
		}
	}
}

// =============================================================================
// Network + TX type badges
// =============================================================================

@Composable
private fun NetworkBadge(name: String) {
	val color = when {
		name.contains("Penumbra") -> Color(0xFF8B5CF6)
		name.contains("Cosmos") || name.contains("Noble") || name.contains("Osmosis") -> Color(0xFF6366F1)
		name.contains("Zcash") -> Color(0xFFF5A623)
		else -> MaterialTheme.colors.primary
	}
	Text(
		text = name,
		style = SignerTypeface.LabelM.copy(fontWeight = FontWeight.Bold),
		color = color,
		modifier = Modifier
			.clip(RoundedCornerShape(6.dp))
			.background(color.copy(alpha = 0.15f))
			.padding(horizontal = 10.dp, vertical = 4.dp),
	)
}

@Composable
private fun TxTypeBadge(signRequest: SignRequest) {
	val txType = when (signRequest) {
		is SignRequest.Penumbra -> {
			val r = signRequest.request
			when {
				r.spendCount > 0u && r.voteCount == 0u -> "Spend"
				r.voteCount > 0u -> "Governance Vote"
				r.lqtVoteCount > 0u -> "LQT Vote"
				else -> "Transaction"
			}
		}
		is SignRequest.Cosmos -> {
			val msgs = signRequest.request.msgs
			when {
				msgs.size == 1 -> msgs.first().msgType
				msgs.size > 1 -> "${msgs.size} Messages"
				else -> "Transaction"
			}
		}
		is SignRequest.ZcashSimple -> "Orchard Spend"
		is SignRequest.ZcashPczt -> "PCZT"
	}

	Text(
		text = txType,
		style = SignerTypeface.LabelM,
		color = MaterialTheme.colors.textSecondary,
		modifier = Modifier
			.clip(RoundedCornerShape(6.dp))
			.background(MaterialTheme.colors.fill6)
			.padding(horizontal = 10.dp, vertical = 4.dp),
	)
}

// =============================================================================
// Simple tab — focused on what matters: recipient, amount, tx type
// =============================================================================

@Composable
private fun SimpleTabContent(signRequest: SignRequest) {
	when (signRequest) {
		is SignRequest.Penumbra -> PenumbraSimpleContent(signRequest)
		is SignRequest.Cosmos -> CosmosSimpleContent(signRequest)
		is SignRequest.ZcashSimple -> ZcashSimpleContent(signRequest)
		is SignRequest.ZcashPczt -> ZcashPcztSimpleContent(signRequest)
	}
}

@Composable
private fun PenumbraSimpleContent(req: SignRequest.Penumbra) {
	val r = req.request

	// Chain
	DetailRow(label = "Chain", value = r.chainId.ifEmpty { "penumbra-1" })

	// What this tx does
	if (r.spendCount > 0u) {
		HighlightCard(
			title = "Sending funds",
			subtitle = "${r.spendCount} spend action(s)",
			color = Color(0xFF8B5CF6),
		)
	}
	if (r.voteCount > 0u) {
		HighlightCard(
			title = "Governance Vote",
			subtitle = "${r.voteCount} delegator vote(s)",
			color = Color(0xFF3B82F6),
		)
	}
	if (r.lqtVoteCount > 0u) {
		HighlightCard(
			title = "Liquidity Tournament",
			subtitle = "${r.lqtVoteCount} LQT vote(s)",
			color = Color(0xFF10B981),
		)
	}

	// Signatures required
	val totalSigs = r.spendCount + r.voteCount + r.lqtVoteCount
	DetailRow(label = "Signatures required", value = "$totalSigs")

	// Effect hash (abbreviated)
	if (r.effectHashHex.isNotEmpty()) {
		DetailRow(
			label = "Effect hash",
			value = r.effectHashHex.take(8) + "..." + r.effectHashHex.takeLast(8),
		)
	}
}

@Composable
private fun CosmosSimpleContent(req: SignRequest.Cosmos) {
	val r = req.request

	// Chain
	DetailRow(label = "Chain", value = r.chainName.ifEmpty { r.chainId })

	// Blind signing warning
	if (r.msgs.any { it.blind }) {
		WarningCard("Unrecognized message types. Review the Advanced tab before signing.")
	}

	// Each message as a highlight card
	r.msgs.forEachIndexed { i, msg ->
		val prefix = if (r.msgs.size > 1) "${i + 1}/${r.msgs.size} " else ""
		CosmosMsgCard(prefix, msg)
	}

	// Fee
	if (r.fee.isNotEmpty()) {
		DetailRow(label = "Fee", value = r.fee)
	}

	// Memo
	if (r.memo.isNotEmpty()) {
		DetailRow(label = "Memo", value = r.memo)
	}
}

@Composable
private fun CosmosMsgCard(prefix: String, msg: CosmosMsgDisplay) {
	var showEncoded by remember { mutableStateOf(false) }
	val parts = msg.detail.split("\n@@encoded\n", limit = 2)
	val visible = parts[0]
	val encoded = parts.getOrNull(1)

	Column(
		modifier = Modifier
			.fillMaxWidth()
			.clip(RoundedCornerShape(12.dp))
			.background(MaterialTheme.colors.fill6)
			.padding(16.dp),
		verticalArrangement = Arrangement.spacedBy(8.dp),
	) {
		Text(
			text = "$prefix${msg.msgType}",
			style = SignerTypeface.LabelM.copy(fontWeight = FontWeight.Bold),
			color = MaterialTheme.colors.primary,
		)
		if (msg.amount.isNotEmpty()) {
			Text(
				text = msg.amount,
				style = SignerTypeface.TitleS,
				color = MaterialTheme.colors.primary,
			)
		}
		if (msg.recipient.isNotEmpty()) {
			Text(text = "To", style = SignerTypeface.CaptionM, color = MaterialTheme.colors.textTertiary)
			Text(
				text = msg.recipient,
				style = SignerTypeface.CaptionM.copy(fontFamily = FontFamily.Monospace),
				color = MaterialTheme.colors.textSecondary,
			)
		}
		if (visible.isNotEmpty()) {
			Text(
				text = visible,
				style = SignerTypeface.CaptionM,
				color = MaterialTheme.colors.textSecondary,
			)
		}
		if (!encoded.isNullOrEmpty()) {
			Text(
				text = if (showEncoded) "hide encoded" else "show encoded",
				style = SignerTypeface.CaptionM.copy(fontWeight = FontWeight.Bold),
				color = MaterialTheme.colors.primary,
				modifier = Modifier.clickable { showEncoded = !showEncoded },
			)
			if (showEncoded) {
				Text(
					text = encoded,
					style = SignerTypeface.CaptionM.copy(fontFamily = FontFamily.Monospace),
					color = MaterialTheme.colors.textSecondary,
				)
			}
		}
		if (msg.blind) {
			Text(
				text = "Blind signing — verify in Advanced tab",
				style = SignerTypeface.CaptionM,
				color = Color(0xFFE74C3C),
			)
		}
	}
}

@Composable
private fun ZcashSimpleContent(req: SignRequest.ZcashSimple) {
	val r = req.request

	// Summary from zafu — this is the most important info
	// Format: "Send X.XX ZEC to u1abc...\nFee: 0.0001 ZEC\nSpending 1 note(s)\nAccount #0: 1 signature required"
	if (r.summary.isNotEmpty()) {
		// Parse summary lines for better display
		val lines = r.summary.split("\n").filter { it.isNotBlank() }
		val sendLine = lines.firstOrNull { it.startsWith("Send ") }

		if (sendLine != null) {
			// Extract amount and recipient from "Send X.XX ZEC to ADDRESS"
			val parts = sendLine.removePrefix("Send ").split(" to ", limit = 2)
			val amount = parts.getOrNull(0) ?: ""
			val recipient = parts.getOrNull(1) ?: ""

			HighlightCard(
				title = amount,
				subtitle = if (recipient.isNotEmpty()) "To: $recipient" else "",
				color = Color(0xFFF5A623),
			)
		} else {
			// Fallback: show full summary
			HighlightCard(title = r.summary, subtitle = "", color = Color(0xFFF5A623))
		}

		// Show remaining lines as details
		lines.filter { !it.startsWith("Send ") }.forEach { line ->
			when {
				line.startsWith("Fee:") -> DetailRow(label = "Fee", value = line.removePrefix("Fee: "))
				line.startsWith("Spending") -> DetailRow(label = "Notes", value = line)
				line.startsWith("Account") -> DetailRow(label = "Account", value = line)
				else -> DetailRow(label = "", value = line)
			}
		}
	} else {
		// No summary — show raw fields
		DetailRow(label = "Actions", value = "${r.actionCount} orchard action(s)")
		DetailRow(label = "Account", value = "#${r.accountIndex}")
	}
}

@Composable
internal fun ZcashPcztSimpleContent(req: SignRequest.ZcashPczt) {
	val inspection = req.inspection
	if (inspection == null) {
		Text(
			text = "Inspecting transaction...",
			style = SignerTypeface.BodyL,
			color = MaterialTheme.colors.textTertiary,
		)
		return
	}

	// Per-spend label. The pczt crate hides per-spend value, so we tell dummies
	// from real unverified notes by the value balance (unverifiedSpendValueZat):
	// if zero, every unrecognized spend is 0-value Orchard padding (neutral);
	// if positive, real unverified value is present so unknowns are flagged.
	if (inspection.spends.isNotEmpty()) {
		val unverified = unverifiedSpendValueZat(inspection)
		inspection.spends.forEach { spend ->
			val zec = "%.8f".format(spend.value.toLong() / 100_000_000.0)
			val isDummy = !spend.known && unverified == 0L
			HighlightCard(
				title = "$zec ZEC",
				subtitle = when {
					spend.known -> "Verified spend"
					isDummy -> "Dummy (privacy padding)"
					else -> "Unknown spend — verify carefully"
				},
				color = when {
					spend.known -> Color(0xFFF5A623) // gold
					isDummy -> Color(0xFF6B7280) // neutral grey
					else -> Color(0xFFE74C3C) // red
				},
			)
		}
	}

	// Outputs
	if (inspection.outputs.isNotEmpty()) {
		inspection.outputs.forEach { output ->
			val zec = "%.8f".format(output.value.toLong() / 100_000_000.0)
			Column(
				modifier = Modifier
					.fillMaxWidth()
					.clip(RoundedCornerShape(12.dp))
					.background(MaterialTheme.colors.fill6)
					.padding(16.dp),
				verticalArrangement = Arrangement.spacedBy(4.dp),
			) {
				Text(text = "$zec ZEC", style = SignerTypeface.TitleS, color = MaterialTheme.colors.primary)
				if (output.recipient.isNotEmpty()) {
					Text(text = "To", style = SignerTypeface.CaptionM, color = MaterialTheme.colors.textTertiary)
					Text(
						text = output.recipient,
						style = SignerTypeface.CaptionM.copy(fontFamily = FontFamily.Monospace),
						color = MaterialTheme.colors.textSecondary,
					)
				}
			}
		}
	}

	// Fee (true fee across all bundles, not just Orchard value_sum)
	val feeZec = "%.8f".format(inspection.feeZat / 100_000_000.0)
	DetailRow(label = "Fee", value = "$feeZec ZEC")
}

// =============================================================================
// Advanced tab — raw payload as JSON-like display
// =============================================================================

@Composable
private fun AdvancedTabContent(signRequest: SignRequest) {
	Text(
		text = "Raw Payload",
		style = SignerTypeface.LabelM,
		color = MaterialTheme.colors.textTertiary,
		modifier = Modifier.padding(bottom = 4.dp),
	)

	val payload = signRequest.rawPayload
	if (payload.isEmpty()) {
		Text(
			text = "(no raw payload available)",
			style = SignerTypeface.CaptionM,
			color = MaterialTheme.colors.textTertiary,
		)
	} else {
		// For cosmos, try to show a more readable format
		val displayText = when (signRequest) {
			is SignRequest.Cosmos -> {
				// Build a readable JSON-like summary
				buildString {
					appendLine("chain: ${signRequest.request.chainId}")
					appendLine("fee: ${signRequest.request.fee}")
					if (signRequest.request.memo.isNotEmpty()) {
						appendLine("memo: ${signRequest.request.memo}")
					}
					appendLine("messages:")
					signRequest.request.msgs.forEachIndexed { i, msg ->
						appendLine("  [$i] type: ${msg.msgType}")
						if (msg.recipient.isNotEmpty()) appendLine("      to: ${msg.recipient}")
						if (msg.amount.isNotEmpty()) appendLine("      amount: ${msg.amount}")
						if (msg.detail.isNotEmpty()) appendLine("      detail: ${msg.detail}")
					}
					appendLine()
					appendLine("--- raw hex ---")
					append(payload)
				}
			}
			is SignRequest.Penumbra -> {
				buildString {
					appendLine("chain_id: ${signRequest.request.chainId}")
					appendLine("effect_hash: ${signRequest.request.effectHashHex}")
					appendLine("spend_count: ${signRequest.request.spendCount}")
					appendLine("vote_count: ${signRequest.request.voteCount}")
					appendLine("lqt_vote_count: ${signRequest.request.lqtVoteCount}")
					appendLine()
					appendLine("--- raw hex ---")
					append(payload)
				}
			}
			is SignRequest.ZcashSimple -> {
				buildString {
					appendLine("account: ${signRequest.request.accountIndex}")
					appendLine("sighash: ${signRequest.request.sighashHex}")
					appendLine("actions: ${signRequest.request.actionCount}")
					appendLine("mainnet: ${signRequest.request.mainnet}")
					if (signRequest.request.summary.isNotEmpty()) {
						appendLine("summary: ${signRequest.request.summary}")
					}
					appendLine()
					appendLine("--- raw hex ---")
					append(payload)
				}
			}
			is SignRequest.ZcashPczt -> {
				buildString {
					appendLine("UR parts: ${signRequest.urParts.size}")
					appendLine()
					append(payload)
				}
			}
		}

		Text(
			text = displayText,
			style = SignerTypeface.CaptionM.copy(
				fontFamily = FontFamily.Monospace,
				fontSize = 11.sp,
				lineHeight = 16.sp,
			),
			color = MaterialTheme.colors.textSecondary,
			modifier = Modifier
				.fillMaxWidth()
				.clip(RoundedCornerShape(8.dp))
				.background(MaterialTheme.colors.fill6)
				.padding(12.dp),
		)
	}
}

// =============================================================================
// Shared composables
// =============================================================================

/** Prominent card for the main action (amount + recipient) */
@Composable
internal fun HighlightCard(title: String, subtitle: String, color: Color) {
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.clip(RoundedCornerShape(12.dp))
			.background(color.copy(alpha = 0.1f))
			.padding(16.dp),
		verticalArrangement = Arrangement.spacedBy(4.dp),
	) {
		Text(
			text = title,
			style = SignerTypeface.TitleS,
			color = color,
		)
		if (subtitle.isNotEmpty()) {
			Text(
				text = subtitle,
				style = SignerTypeface.CaptionM,
				color = MaterialTheme.colors.textSecondary,
			)
		}
	}
}

/** Simple label: value row */
@Composable
internal fun DetailRow(label: String, value: String) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.padding(vertical = 4.dp),
		horizontalArrangement = Arrangement.SpaceBetween,
	) {
		if (label.isNotEmpty()) {
			Text(
				text = label,
				style = SignerTypeface.CaptionM,
				color = MaterialTheme.colors.textTertiary,
				modifier = Modifier.weight(0.35f),
			)
		}
		Text(
			text = value,
			style = SignerTypeface.CaptionM,
			color = MaterialTheme.colors.primary,
			textAlign = if (label.isNotEmpty()) TextAlign.End else TextAlign.Start,
			modifier = Modifier.weight(0.65f),
		)
	}
}

/** Warning card with red background */
@Composable
internal fun WarningCard(message: String) {
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.clip(RoundedCornerShape(8.dp))
			.background(Color(0x33E74C3C))
			.padding(12.dp),
	) {
		Text(text = message, style = SignerTypeface.CaptionM, color = Color(0xFFE74C3C))
	}
}
