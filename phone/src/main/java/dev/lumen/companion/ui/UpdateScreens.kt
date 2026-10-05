package dev.lumen.companion.ui

import android.text.format.DateUtils
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.lumen.companion.R
import dev.lumen.companion.update.Release
import dev.lumen.companion.update.UpdateManager
import dev.lumen.companion.update.UpdateManager.Problem
import dev.lumen.companion.update.UpdateManager.Step
import java.text.DateFormat
import java.time.Instant
import java.util.Date

/** The home's card while a newer version is on offer; opens its notes. */
@Composable
internal fun UpdateCard(release: Release, onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Lumen.radiusCard))
            .background(Lumen.surface)
            .border(BorderStroke(1.5.dp, Lumen.accent), RoundedCornerShape(Lumen.radiusCard))
            .clickable(onClick = onOpen)
            .padding(Lumen.spacingMedLg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Lumen.spacingSmMed),
    ) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(Lumen.accent), contentAlignment = Alignment.Center) {
            Icon(LumenIcons.download, contentDescription = null, modifier = Modifier.size(22.dp), tint = Lumen.textPrimary)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.update_card_title, release.version.toString()), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.update_card_subtitle), style = MaterialTheme.typography.bodySmall, color = Lumen.textSecondary)
        }
        Icon(LumenIcons.chevron, contentDescription = null, modifier = Modifier.size(20.dp), tint = Lumen.textSecondary)
    }
}

/** A page's title with a back arrow. */
@Composable
internal fun PageHeader(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        IconButton(onClick = onBack) { Icon(LumenIcons.back, contentDescription = stringResource(R.string.action_back), tint = Lumen.textPrimary) }
        Text(title, style = MaterialTheme.typography.headlineMedium)
    }
}

/** Settings › Updates: both apps' versions, the newest notes, the button, the preferences. */
@Composable
internal fun UpdatesPage(state: UpdateManager.State, actions: CompanionActions, onNotes: () -> Unit, onBack: () -> Unit) {
    PageHeader(stringResource(R.string.updates_title), onBack)
    if (state.busy || state.phoneStep != Step.Idle || state.glassesStep != Step.Idle) {
        ProgressSection(state, actions)
        return
    }
    val offered = state.offered
    SectionTitle(stringResource(R.string.updates_versions))
    Group {
        VersionRow(LumenIcons.phone, stringResource(R.string.updates_companion), state.phoneVersion, state.phoneUpdate)
        VersionRow(LumenIcons.glasses, stringResource(R.string.updates_glasses), state.glassesVersion, state.glassesUpdate)
    }
    if (offered != null) {
        Column(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(Lumen.radiusCard)).background(Lumen.surface).clickable(onClick = onNotes).padding(Lumen.spacingMedLg),
            verticalArrangement = Arrangement.spacedBy(Lumen.spacingSmall),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.updates_whats_new, offered.version.toString()), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Icon(LumenIcons.chevron, contentDescription = null, modifier = Modifier.size(18.dp), tint = Lumen.textSecondary)
            }
            ReleaseNotes(offered.notes, maxLines = 4)
        }
        val size = listOfNotNull(state.phoneUpdate?.companionApk?.size, state.glassesUpdate?.glassesApk?.size).sum()
        PillButton(
            stringResource(
                if (state.phoneUpdate != null && state.glassesUpdate != null) R.string.updates_update_both else R.string.updates_update_one,
                formatSize(size),
            ),
            primary = true, icon = LumenIcons.download, modifier = Modifier.fillMaxWidth(),
        ) { actions.updateAll() }
        if (state.glassesUpdate != null) Hint(LumenIcons.wifi, stringResource(R.string.updates_wifi_hint))
    } else {
        StateCard(LumenIcons.check, Lumen.positive, stringResource(R.string.updates_up_to_date), lastCheckText(state))
    }
    state.checkProblem?.let { Hint(LumenIcons.warning, stringResource(R.string.update_problem_offline)) }
    SectionTitle(stringResource(R.string.updates_preferences))
    Group {
        SwitchRow(stringResource(R.string.updates_auto), stringResource(R.string.updates_auto_hint), state.auto, actions::setAutoUpdate)
        SwitchRow(stringResource(R.string.updates_beta), stringResource(R.string.updates_beta_hint), state.beta, actions::setBetaUpdates)
        ListRow(
            title = stringResource(R.string.updates_check_now),
            subtitle = lastCheckText(state),
            trailing = {
                if (state.checking) CircularProgressIndicator(Modifier.size(22.dp), color = Lumen.accent, strokeWidth = 2.dp)
                else Icon(LumenIcons.refresh, contentDescription = null, modifier = Modifier.size(20.dp), tint = Lumen.textSecondary)
            },
            onClick = actions::checkUpdates,
        )
    }
}

@Composable
private fun lastCheckText(state: UpdateManager.State): String =
    if (state.lastCheck == 0L) stringResource(R.string.updates_never_checked)
    else stringResource(R.string.updates_last_check, DateUtils.getRelativeTimeSpanString(state.lastCheck, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString())

@Composable
private fun VersionRow(icon: ImageVector, name: String, installed: String, update: Release?) {
    ListRow(
        title = name,
        subtitle = stringResource(R.string.updates_installed, installed.ifEmpty { stringResource(R.string.updates_unknown) }),
        icon = icon,
        trailing = { if (update != null) StatusPill(update.version.toString(), Lumen.accent) },
    )
}

/** The notes of [release], and the earlier ones the cache holds. */
@Composable
internal fun NotesPage(state: UpdateManager.State, tag: String?, actions: CompanionActions, onPick: (String) -> Unit, onBack: () -> Unit) {
    val release = state.releases.firstOrNull { it.tag == tag } ?: state.offered ?: state.releases.firstOrNull()
    PageHeader(stringResource(R.string.notes_title), onBack)
    if (release == null) {
        Hint(LumenIcons.warning, stringResource(R.string.notes_none))
        return
    }
    Card {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Lumen.spacingSmall)) {
            Text(release.version.toString(), style = MaterialTheme.typography.titleLarge)
            if (release.prerelease) StatusPill(stringResource(R.string.notes_beta), Lumen.purple)
            when {
                release.tag == state.offered?.tag -> StatusPill(stringResource(R.string.notes_available), Lumen.accent)
                release.version.toString() == state.phoneVersion -> StatusPill(stringResource(R.string.notes_installed), Lumen.positive)
            }
        }
        publishedText(release)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Lumen.textPlaceholder) }
        ReleaseNotes(release.notes)
    }
    if (release.tag == state.offered?.tag && !state.busy) {
        PillButton(stringResource(R.string.notes_update_to, release.version.toString()), primary = true, icon = LumenIcons.download, modifier = Modifier.fillMaxWidth()) { actions.updateAll() }
    }
    val earlier = state.releases.filter { it.tag != release.tag && (state.beta || !it.prerelease || it.version.toString() == state.phoneVersion) }.take(8)
    if (earlier.isNotEmpty()) {
        SectionTitle(stringResource(R.string.notes_earlier))
        Group {
            earlier.forEach { other ->
                ListRow(
                    title = other.version.toString(),
                    subtitle = listOfNotNull(
                        stringResource(R.string.notes_installed).takeIf { other.version.toString() == state.phoneVersion },
                        publishedText(other),
                    ).joinToString(" · ").ifEmpty { null },
                    onClick = { onPick(other.tag) },
                )
            }
        }
    }
}

private fun publishedText(release: Release): String? = release.publishedAt.takeIf { it.isNotEmpty() }?.let {
    runCatching { DateFormat.getDateInstance(DateFormat.LONG).format(Date.from(Instant.parse(it))) }.getOrNull()
}

/** Where each app's update stands, glasses first. */
@Composable
private fun ProgressSection(state: UpdateManager.State, actions: CompanionActions) {
    if (state.glassesStep != Step.Idle) {
        Card {
            AppHeading(LumenIcons.glasses, stringResource(R.string.progress_glasses, (state.glassesUpdate?.version ?: state.offered?.version)?.toString().orEmpty()), stringResource(R.string.progress_glasses_order))
            val step = state.glassesStep
            val download = when (step) {
                Step.Waiting -> StepState.TODO
                is Step.Downloading -> StepState.NOW
                is Step.Failed -> StepState.TODO
                else -> StepState.DONE
            }
            val sending = when (step) {
                Step.Sending -> StepState.NOW
                Step.Rearming, Step.Done -> StepState.DONE
                else -> StepState.TODO
            }
            val rearm = when (step) {
                Step.Rearming -> StepState.NOW
                Step.Done -> StepState.DONE
                else -> StepState.TODO
            }
            StepLine(download, stringResource(R.string.progress_download), (step as? Step.Downloading)?.let { stringResource(R.string.progress_bytes, formatSize(it.done), formatSize(it.total)) })
            StepLine(if (download == StepState.DONE) StepState.DONE else StepState.TODO, stringResource(R.string.progress_verified), stringResource(R.string.progress_verified_hint))
            StepLine(sending, stringResource(R.string.progress_sending), stringResource(R.string.progress_sending_hint))
            StepLine(rearm, stringResource(R.string.progress_rearm), null)
            (step as? Step.Failed)?.let { ProblemLine(it.problem, actions) }
        }
    }
    if (state.phoneStep != Step.Idle) {
        Card {
            val step = state.phoneStep
            AppHeading(
                LumenIcons.phone, stringResource(R.string.progress_companion, state.phoneUpdate?.version?.toString().orEmpty()),
                when (step) {
                    Step.Waiting -> stringResource(R.string.progress_waiting)
                    is Step.Downloading -> stringResource(R.string.progress_downloading, formatSize(step.done), formatSize(step.total))
                    Step.Confirming -> stringResource(R.string.progress_confirm)
                    else -> null
                },
            )
            if (step is Step.Downloading) {
                LinearProgressIndicator(
                    progress = { if (step.total > 0) step.done.toFloat() / step.total else 0f },
                    modifier = Modifier.fillMaxWidth().clip(CircleShape),
                    color = Lumen.accent, trackColor = Lumen.elevation2,
                )
            }
            Text(stringResource(R.string.progress_confirm_hint), style = MaterialTheme.typography.bodySmall, color = Lumen.textPlaceholder)
            (step as? Step.Failed)?.let { ProblemLine(it.problem, actions) }
        }
    }
    if (state.busy) {
        PillButton(stringResource(R.string.action_cancel), primary = false, modifier = Modifier.fillMaxWidth()) { actions.cancelUpdate() }
    } else {
        PillButton(stringResource(R.string.action_done), primary = false, modifier = Modifier.fillMaxWidth()) { actions.dismissUpdate() }
    }
}

internal enum class StepState { DONE, NOW, TODO }

@Composable
private fun AppHeading(icon: ImageVector, title: String, subtitle: String?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Lumen.spacingSmMed)) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(Lumen.elevation1), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp), tint = Lumen.textPrimary)
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Lumen.textSecondary)
        }
    }
}

@Composable
internal fun StepLine(state: StepState, title: String, subtitle: String?) {
    Row(horizontalArrangement = Arrangement.spacedBy(Lumen.spacingSmMed), modifier = Modifier.padding(start = Lumen.spacingSmall)) {
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            when (state) {
                StepState.DONE -> Box(Modifier.size(28.dp).clip(CircleShape).background(Lumen.positive), contentAlignment = Alignment.Center) {
                    Icon(LumenIcons.check, contentDescription = null, modifier = Modifier.size(16.dp), tint = Lumen.textPrimary)
                }
                StepState.NOW -> CircularProgressIndicator(Modifier.size(24.dp), color = Lumen.accent, trackColor = Lumen.elevation1, strokeWidth = 3.dp)
                StepState.TODO -> Box(Modifier.size(28.dp).border(2.dp, Lumen.elevation2, CircleShape))
            }
        }
        Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = if (state == StepState.TODO) Lumen.textPlaceholder else Lumen.textPrimary)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Lumen.textSecondary)
        }
    }
}

/** What went wrong and, when there's one, the way out. */
@Composable
internal fun ProblemLine(problem: Problem, actions: CompanionActions, onRetry: () -> Unit = { actions.updateAll() }) {
    val (title, text) = when (problem) {
        Problem.NO_WIFI -> R.string.update_problem_wifi_title to R.string.update_problem_wifi
        Problem.GLASSES_OFFLINE -> R.string.update_problem_glasses_title to R.string.update_problem_glasses
        Problem.SIGNER -> R.string.update_problem_signer_title to R.string.update_problem_signer
        Problem.DIGEST, Problem.NO_DIGEST, Problem.PACKAGE -> R.string.update_problem_check_title to R.string.update_problem_check
        Problem.NO_PERMISSION -> R.string.update_problem_permission_title to R.string.update_problem_permission
        Problem.OFFLINE -> R.string.update_problem_offline_title to R.string.update_problem_offline
        Problem.INSTALL_FAILED -> R.string.update_problem_install_title to R.string.update_problem_install
    }
    val critical = problem == Problem.SIGNER || problem == Problem.DIGEST || problem == Problem.NO_DIGEST || problem == Problem.PACKAGE || problem == Problem.INSTALL_FAILED
    StateCard(if (critical) LumenIcons.shield else LumenIcons.warning, if (critical) Lumen.negative else Lumen.warning, stringResource(title), stringResource(text))
    when (problem) {
        Problem.NO_WIFI -> PillButton(stringResource(R.string.update_open_wifi), primary = true) { actions.openWifiSettings() }
        Problem.GLASSES_OFFLINE -> PillButton(stringResource(R.string.action_reconnect), primary = false) { actions.reconnect() }
        Problem.NO_PERMISSION -> PillButton(stringResource(R.string.update_allow_installs), primary = true) { actions.allowInstalls() }
        Problem.OFFLINE, Problem.INSTALL_FAILED -> PillButton(stringResource(R.string.update_try_again), primary = false, onClick = onRetry)
        else -> Unit
    }
}

@Composable
private fun StateCard(icon: ImageVector, color: Color, title: String, text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(Lumen.radiusCard)).background(Lumen.surface).padding(Lumen.spacingMedLg),
        horizontalArrangement = Arrangement.spacedBy(Lumen.spacingSmMed),
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(Lumen.elevation1), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp), tint = color)
        }
        Column(Modifier.padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(text, style = MaterialTheme.typography.bodySmall, color = Lumen.textSecondary)
        }
    }
}

@Composable
private fun Hint(icon: ImageVector, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(Lumen.spacingSmall), modifier = Modifier.padding(horizontal = Lumen.spacingSmall)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = Lumen.textPlaceholder)
        Text(text, style = MaterialTheme.typography.bodySmall, color = Lumen.textPlaceholder)
    }
}

/**
 * Release notes in the small Markdown the CHANGELOG uses: `##`/`###` headings, `-`/`*` bullets,
 * `**bold**` and `` `code` ``; links keep their text. [maxLines] cuts a preview to its first bullets.
 */
@Composable
internal fun ReleaseNotes(markdown: String, maxLines: Int = Int.MAX_VALUE) {
    val lines = markdown.lines().map { it.trimEnd() }.filter { it.isNotBlank() && !it.startsWith("<!--") }
    val shown = if (maxLines == Int.MAX_VALUE) lines else lines.filter { it.trimStart().startsWith("- ") || it.trimStart().startsWith("* ") }.take(maxLines)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        shown.forEach { line ->
            val trimmed = line.trimStart()
            when {
                trimmed.startsWith("#") -> Text(inline(trimmed.trimStart('#').trim()), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                trimmed.startsWith("- ") || trimmed.startsWith("* ") -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = if (line.startsWith("  ")) 16.dp else 0.dp)) {
                    Text("•", style = MaterialTheme.typography.bodyMedium, color = Lumen.textSecondary)
                    Text(inline(trimmed.substring(2)), style = MaterialTheme.typography.bodyMedium, color = Lumen.textSecondary)
                }
                else -> Text(inline(trimmed), style = MaterialTheme.typography.bodyMedium, color = Lumen.textSecondary)
            }
        }
    }
}

private val INLINE = Regex("""\*\*(.+?)\*\*|`([^`]+)`|\[([^\]]+)]\([^)]+\)""")

private fun inline(text: String): AnnotatedString = buildAnnotatedString {
    var at = 0
    INLINE.findAll(text).forEach { match ->
        append(text.substring(at, match.range.first))
        val (bold, code, link) = match.destructured
        when {
            bold.isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Lumen.textPrimary)) { append(bold) }
            code.isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(code) }
            else -> append(link)
        }
        at = match.range.last + 1
    }
    append(text.substring(at))
}

internal fun formatSize(bytes: Long): String = when {
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "%d KB".format(bytes / 1_000)
    else -> "$bytes B"
}
