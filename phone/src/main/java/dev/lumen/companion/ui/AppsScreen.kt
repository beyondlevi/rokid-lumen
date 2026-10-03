package dev.lumen.companion.ui

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.lumen.companion.PackageShare
import dev.lumen.companion.R
import dev.lumen.protocol.AppConfigField
import dev.lumen.protocol.GridEvent
import dev.lumen.protocol.GridItem
import kotlin.math.roundToInt

/**
 * The glasses' apps, arranged from here: a small preview of the HUD's grid (pages of 3x3), the
 * grid as a list in the HUD's order (hold and drag to reorder), and the apps that can be added.
 * An app's details open in a sheet on a phone and beside the list on a wide screen.
 */
@Composable
internal fun AppsScreen(
    items: List<GridItem>,
    available: List<GridItem>,
    known: Boolean,
    icons: Map<String, Bitmap>,
    error: GridEvent.Result?,
    transfer: PackageShare.Transfer?,
    actions: CompanionActions,
) {
    var selected by remember { mutableStateOf<String?>(null) }
    var dialog by remember { mutableStateOf<AddDialog?>(null) }
    var confirmDelete by remember { mutableStateOf<GridItem?>(null) }
    var editing by remember { mutableStateOf<Pair<GridItem, AppConfigField>?>(null) }
    /** Naming a web app: renaming it (false) or a new copy of it (true). */
    var naming by remember { mutableStateOf<Pair<GridItem, Boolean>?>(null) }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wide = maxWidth >= WIDE
        Column(verticalArrangement = Arrangement.spacedBy(Lumen.spacingLarge)) {
            AppsHeader(items.size, available.size, onAdd = { dialog = it }, onAddFile = actions::pickPackageFile)
            transfer?.let { TransferCard(it, onDismiss = actions::dismissPackageTransfer) }
            if (!known) {
                Card {
                    Text(stringResource(R.string.band_waiting), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.apps_waiting_hint), style = MaterialTheme.typography.bodySmall, color = Lumen.textSecondary)
                }
                return@Column
            }
            if (error != null) {
                Text(
                    stringResource(R.string.band_refused, error.error),
                    style = MaterialTheme.typography.bodySmall,
                    color = Lumen.negative,
                    modifier = Modifier.padding(horizontal = Lumen.spacingSmall),
                )
            }
            val current = items.firstOrNull { it.id == selected }
            val onDetail: @Composable (GridItem) -> Unit = { item ->
                AppDetail(
                    item, icons[item.id], actions,
                    onClose = if (wide) null else ({ selected = null }),
                    onEdit = { editing = item to it },
                    onDelete = { confirmDelete = item },
                    onTakeOff = { selected = null; actions.hideGridItem(item.id) },
                    onRename = { naming = item to false },
                    onCopy = { naming = item to true },
                    originalName = items.plus(available).firstOrNull { it.id == item.copyOf }?.name,
                    busy = transfer?.busy == true,
                )
            }
            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(Lumen.spacingLarge), verticalAlignment = Alignment.Top) {
                    Column(Modifier.widthIn(max = LIST_MAX).weight(1f), verticalArrangement = Arrangement.spacedBy(Lumen.spacingLarge)) {
                        GridPreview(items, icons, selected)
                        GridList(items, icons, selected, actions) { selected = it }
                        AvailableList(available, icons, actions)
                    }
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(Lumen.radiusCard)).background(Lumen.surface).padding(Lumen.spacingMedLg),
                        verticalArrangement = Arrangement.spacedBy(Lumen.spacingMedLg),
                    ) {
                        if (current != null) onDetail(current)
                        else Text(stringResource(R.string.apps_pick_one), style = MaterialTheme.typography.bodyMedium, color = Lumen.textSecondary)
                    }
                }
            } else {
                GridPreview(items, icons, selected)
                GridList(items, icons, selected, actions) { selected = it }
                AvailableList(available, icons, actions)
                if (current != null) DetailSheet(onDismiss = { selected = null }) { onDetail(current) }
            }
        }
    }

    dialog?.let { kind ->
        UrlDialog(kind, onDismiss = { dialog = null }) { url, name ->
            dialog = null
            if (kind == AddDialog.WEB) actions.addWebApp(url, name) else actions.addPackage(url)
        }
    }
    editing?.let { (item, field) ->
        ConfigDialog(field, onDismiss = { editing = null }) { value ->
            editing = null
            actions.setGridConfig(item.id, field.key, value)
        }
    }
    naming?.let { (item, copy) ->
        NameDialog(item, copy, onDismiss = { naming = null }) { name ->
            naming = null
            if (copy) actions.copyWebApp(item.id, name) else actions.renameWebApp(item.id, name)
        }
    }
    confirmDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            containerColor = Lumen.elevation1,
            title = { Text(stringResource(R.string.apps_delete_title, item.name)) },
            text = { Text(stringResource(R.string.apps_delete_text), color = Lumen.textSecondary) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = null; selected = null; actions.deleteWebApp(item.id) }) {
                    Text(stringResource(R.string.apps_delete), color = Lumen.negative)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.cancel), color = Lumen.textPrimary) } },
        )
    }
}

private enum class AddDialog { WEB, PACKAGE }

private const val COLUMNS = 3
private const val ENGINE_GECKO = "GECKO"
private const val ENGINE_SYSTEM = "SYSTEM"
private val WIDE = 600.dp
private val LIST_MAX = 460.dp
private val ROW_HEIGHT = 64.dp
private val ROW_GAP = 4.dp
private val WARNING_TEXT = Color(0xFFF5C451)
private val WARNING_BACKGROUND = Color(0xFF3A2E12)
private val NEGATIVE_BACKGROUND = Color(0xFF3B1E23)

private val GridItem.needsSetup: Boolean get() = config.any { it.missing }

@Composable
private fun AppsHeader(onGrid: Int, available: Int, onAdd: (AddDialog) -> Unit, onAddFile: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(Lumen.spacingSmMed)) {
        Column(Modifier.weight(1f).padding(start = Lumen.spacingSmall), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.apps_title), style = MaterialTheme.typography.headlineMedium)
            Text(
                pluralStringResource(R.plurals.apps_count_grid, onGrid, onGrid) + " · " +
                    pluralStringResource(R.plurals.apps_count_available, available, available),
                style = MaterialTheme.typography.bodyMedium,
                color = Lumen.textSecondary,
            )
        }
        Box {
            PillButton(stringResource(R.string.apps_add_short), primary = true, icon = LumenIcons.plus) { menu = true }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Lumen.elevation1) {
                DropdownMenuItem(
                    text = { MenuText(R.string.apps_add_web, R.string.apps_add_web_hint) },
                    leadingIcon = { Icon(LumenIcons.link, contentDescription = null, tint = Lumen.textPrimary) },
                    onClick = { menu = false; onAdd(AddDialog.WEB) },
                )
                DropdownMenuItem(
                    text = { MenuText(R.string.apps_add_file, R.string.apps_add_file_hint) },
                    leadingIcon = { Icon(LumenIcons.download, contentDescription = null, tint = Lumen.textPrimary) },
                    onClick = { menu = false; onAddFile() },
                )
                DropdownMenuItem(
                    text = { MenuText(R.string.apps_add_package, R.string.apps_add_package_hint) },
                    leadingIcon = { Icon(LumenIcons.link, contentDescription = null, tint = Lumen.textPrimary) },
                    onClick = { menu = false; onAdd(AddDialog.PACKAGE) },
                )
            }
        }
    }
}

@Composable
private fun MenuText(title: Int, hint: Int) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium, color = Lumen.textPrimary)
        Text(stringResource(hint), style = MaterialTheme.typography.bodySmall, color = Lumen.textSecondary)
    }
}

/** The HUD's Apps tab, small: every app in rows of three, as the glasses scroll through them. */
@Composable
private fun GridPreview(items: List<GridItem>, icons: Map<String, Bitmap>, selected: String?) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Lumen.radiusCard)).background(Lumen.surface).padding(Lumen.spacingMedium),
        verticalArrangement = Arrangement.spacedBy(Lumen.spacingSmMed),
    ) {
        Text(stringResource(R.string.apps_preview), style = MaterialTheme.typography.labelMedium, color = Lumen.textSecondary)
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(Lumen.radiusRow)).background(Lumen.window).padding(Lumen.spacingSmMed),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val shown = items.filter { it.kind != GridItem.Kind.NOTIFICATIONS }
            shown.chunked(COLUMNS).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (0 until COLUMNS).forEach { column ->
                        val item = row.getOrNull(column)
                        Box(Modifier.weight(1f)) { if (item != null) MiniTile(item, icons[item.id], item.id == selected) }
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniTile(item: GridItem?, icon: Bitmap?, selected: Boolean) {
    val shape = RoundedCornerShape(10.dp)
    if (item == null) {
        Box(Modifier.fillMaxWidth().aspectRatio(1.25f).border(BorderStroke(1.dp, Lumen.elevation2), shape))
        return
    }
    Column(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1.25f)
            .clip(shape)
            .background(if (item.kind == GridItem.Kind.NOTIFICATIONS) Lumen.elevation2 else Lumen.elevation1)
            .let { if (selected) it.border(BorderStroke(2.dp, Lumen.accent), shape) else it }
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        AppIcon(item, icon, 18.dp)
        Text(item.name, style = MaterialTheme.typography.labelSmall, color = Lumen.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

/** An app's icon: its own image, the glasses' symbols, or its initial. */
@Composable
private fun AppIcon(item: GridItem, icon: Bitmap?, size: androidx.compose.ui.unit.Dp) {
    when {
        icon != null -> Image(icon.asImageBitmap(), contentDescription = null, modifier = Modifier.size(size).clip(RoundedCornerShape(size / 5)))
        item.kind == GridItem.Kind.NOTIFICATIONS -> Icon(LumenIcons.bell, contentDescription = null, tint = Lumen.textPrimary, modifier = Modifier.size(size))
        item.kind == GridItem.Kind.SETTINGS -> Icon(LumenIcons.gear, contentDescription = null, tint = Lumen.textPrimary, modifier = Modifier.size(size))
        else -> Text(item.name.take(1).uppercase(), style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun metaText(item: GridItem): String = when (item.kind) {
    GridItem.Kind.WEB -> stringResource(if (item.offline) R.string.apps_meta_web_offline else R.string.apps_meta_web_online)
    GridItem.Kind.NATIVE -> stringResource(R.string.apps_kind_native_short)
    GridItem.Kind.NOTIFICATIONS, GridItem.Kind.SETTINGS -> stringResource(R.string.apps_kind_glasses)
}

private fun metaDot(item: GridItem): Color? = when {
    item.kind == GridItem.Kind.WEB && item.offline -> Lumen.warning
    item.kind == GridItem.Kind.NATIVE -> Lumen.purple
    else -> null
}

/**
 * The grid in the HUD's order, reordered by drag and drop: the handle takes the finger at once,
 * the rest of the row after a hold. While a row is lifted the page doesn't scroll, the row follows
 * the finger and the others slide to make room; the new order shows on drop and goes to the
 * glasses (their answer replaces it).
 */
@Composable
private fun GridList(items: List<GridItem>, icons: Map<String, Bitmap>, selected: String?, actions: CompanionActions, onSelect: (String) -> Unit) {
    var order by remember(items) { mutableStateOf(items) }
    var dragging by remember { mutableStateOf<String?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    val lock = LocalScrollLock.current
    val haptics = LocalHapticFeedback.current
    val step = with(LocalDensity.current) { (ROW_HEIGHT + ROW_GAP).toPx() }
    val from = order.indexOfFirst { it.id == dragging }
    val target = if (from < 0) -1 else (from + (offset / step).roundToInt()).coerceIn(0, order.lastIndex)

    fun lift(id: String) {
        dragging = id
        offset = 0f
        lock.value = true
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    fun move(dy: Float) {
        val index = order.indexOfFirst { it.id == dragging }
        if (index < 0) return
        // Not past the list's ends.
        offset = (offset + dy).coerceIn(-index * step, (order.lastIndex - index) * step)
    }

    fun drop() {
        val index = order.indexOfFirst { it.id == dragging }
        if (index >= 0) {
            val to = (index + (offset / step).roundToInt()).coerceIn(0, order.lastIndex)
            if (to != index) {
                order = order.moved(index, to)
                actions.reorderGrid(order.map { it.id })
            }
        }
        dragging = null
        offset = 0f
        lock.value = false
    }

    Column(verticalArrangement = Arrangement.spacedBy(Lumen.spacingSmall)) {
        Row(Modifier.padding(horizontal = Lumen.spacingSmall), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.apps_on_grid).uppercase(), style = MaterialTheme.typography.labelMedium, color = Lumen.textPlaceholder, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.apps_drag_hint), style = MaterialTheme.typography.bodySmall, color = Lumen.textPlaceholder)
        }
        Column(verticalArrangement = Arrangement.spacedBy(ROW_GAP)) {
            order.forEachIndexed { index, item ->
                val lifted = item.id == dragging
                // The rows between the lifted one's place and where it would land step aside.
                val shift = when {
                    from < 0 || lifted -> 0f
                    index in (from + 1)..target -> -step
                    index in target until from -> step
                    else -> 0f
                }
                val slide by animateFloatAsState(shift, label = "slide")
                val handle = Modifier.pointerInput(item.id) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        lift(item.id)
                        drag(down.id) { change ->
                            move(change.positionChange().y)
                            change.consume()
                        }
                        drop()
                    }
                }
                val hold = Modifier.pointerInput(item.id) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { lift(item.id) },
                        onDragEnd = { drop() },
                        onDragCancel = { drop() },
                        onDrag = { change, amount -> change.consume(); move(amount.y) },
                    )
                }
                AppRow(
                    item, icons[item.id], selected = item.id == selected, lifted = lifted,
                    handle = handle, hold = hold,
                    modifier = Modifier
                        .zIndex(if (lifted) 1f else 0f)
                        .graphicsLayer {
                            translationY = if (lifted) offset else slide
                            if (lifted) {
                                scaleX = 1.02f
                                scaleY = 1.02f
                            }
                        },
                ) { onSelect(item.id) }
            }
        }
    }
}

@Composable
private fun AppRow(
    item: GridItem,
    icon: Bitmap?,
    selected: Boolean,
    lifted: Boolean,
    handle: Modifier,
    hold: Modifier,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(Lumen.radiusRow)
    Row(
        modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .shadow(if (lifted) 12.dp else 0.dp, shape)
            .clip(shape)
            .background(if (lifted) Lumen.elevation2 else if (selected) Lumen.elevation1 else Lumen.surface)
            .let { if (selected || lifted) it.border(BorderStroke(2.dp, Lumen.accent), shape) else it }
            .then(hold)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(end = Lumen.spacingMedium),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Lumen.spacingSmMed),
    ) {
        val reorder = stringResource(R.string.apps_reorder, item.name)
        Box(
            Modifier.size(width = 44.dp, height = ROW_HEIGHT).then(handle).semantics { contentDescription = reorder },
            contentAlignment = Alignment.Center,
        ) {
            Icon(LumenIcons.grip, contentDescription = null, tint = if (lifted) Lumen.textPrimary else Lumen.textPlaceholder, modifier = Modifier.size(18.dp))
        }
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Lumen.elevation1), contentAlignment = Alignment.Center) {
            AppIcon(item, icon, 22.dp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(item.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                metaDot(item)?.let { Box(Modifier.size(6.dp).clip(CircleShape).background(it)) }
                Text(metaText(item), style = MaterialTheme.typography.bodySmall, color = Lumen.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (item.needsSetup) SetupBadge()
        Icon(LumenIcons.chevron, contentDescription = null, modifier = Modifier.size(18.dp), tint = Lumen.textPlaceholder)
    }
}

@Composable
private fun SetupBadge() {
    Row(
        Modifier.clip(CircleShape).background(WARNING_BACKGROUND).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(LumenIcons.warning, contentDescription = null, tint = WARNING_TEXT, modifier = Modifier.size(12.dp))
        Text(stringResource(R.string.apps_needs_setup), style = MaterialTheme.typography.labelSmall, color = WARNING_TEXT)
    }
}

@Composable
private fun AvailableList(available: List<GridItem>, icons: Map<String, Bitmap>, actions: CompanionActions) {
    if (available.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(Lumen.spacingSmall)) {
        Text(
            stringResource(R.string.apps_available).uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = Lumen.textPlaceholder,
            modifier = Modifier.padding(horizontal = Lumen.spacingSmall),
        )
        Column(verticalArrangement = Arrangement.spacedBy(ROW_GAP)) {
            available.forEach { item ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = ROW_HEIGHT).clip(RoundedCornerShape(Lumen.radiusRow)).background(Lumen.surface)
                        .padding(horizontal = Lumen.spacingMedium, vertical = Lumen.spacingSmMed),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Lumen.spacingSmMed),
                ) {
                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Lumen.elevation1), contentAlignment = Alignment.Center) {
                        AppIcon(item, icons[item.id], 22.dp)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(item.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(kindLabel(item.kind)), style = MaterialTheme.typography.bodySmall, color = Lumen.textSecondary)
                    }
                    PillButton(stringResource(R.string.apps_add_short), primary = false, icon = LumenIcons.plus) { actions.addGridItem(item.id) }
                }
            }
        }
    }
}

private fun kindLabel(kind: GridItem.Kind) = when (kind) {
    GridItem.Kind.NATIVE -> R.string.apps_kind_native
    GridItem.Kind.WEB -> R.string.apps_kind_web
    GridItem.Kind.NOTIFICATIONS, GridItem.Kind.SETTINGS -> R.string.apps_kind_glasses
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailSheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Lumen.surface,
        shape = RoundedCornerShape(topStart = Lumen.radiusCard, topEnd = Lumen.radiusCard),
    ) {
        Column(
            Modifier.padding(horizontal = Lumen.spacingMedium).padding(bottom = Lumen.spacingLarge).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Lumen.spacingMedLg),
        ) { content() }
    }
}

/** Everything about one app: what it still needs, its settings, its engine, taking it off. */
@Composable
private fun AppDetail(
    item: GridItem,
    icon: Bitmap?,
    actions: CompanionActions,
    onClose: (() -> Unit)?,
    onEdit: (AppConfigField) -> Unit,
    onDelete: () -> Unit,
    onTakeOff: () -> Unit,
    onRename: () -> Unit,
    onCopy: () -> Unit,
    originalName: String?,
    busy: Boolean,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(Lumen.radiusRow)).background(Lumen.elevation2), contentAlignment = Alignment.Center) {
            AppIcon(item, icon, 30.dp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(item.name, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                metaDot(item)?.let { Box(Modifier.size(6.dp).clip(CircleShape).background(it)) }
                Text(metaText(item), style = MaterialTheme.typography.bodySmall, color = Lumen.textSecondary)
            }
        }
        if (onClose != null) {
            val close = stringResource(R.string.apps_close)
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(Lumen.elevation1).clickable(role = Role.Button, onClick = onClose)
                    .semantics { contentDescription = close },
                contentAlignment = Alignment.Center,
            ) { Icon(LumenIcons.close, contentDescription = null, tint = Lumen.textPrimary, modifier = Modifier.size(18.dp)) }
        }
    }
    if (item.copyOf.isNotEmpty()) {
        Text(
            stringResource(R.string.apps_copy_of, originalName ?: stringResource(R.string.apps_copy_of_unknown)),
            style = MaterialTheme.typography.bodySmall,
            color = Lumen.textSecondary,
            modifier = Modifier.padding(horizontal = Lumen.spacingSmall),
        )
    }
    val missing = item.config.filter { it.missing }
    if (missing.isNotEmpty()) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(Lumen.radiusRow)).background(WARNING_BACKGROUND).padding(horizontal = 14.dp, vertical = Lumen.spacingSmMed),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(LumenIcons.warning, contentDescription = null, tint = WARNING_TEXT, modifier = Modifier.size(18.dp))
            Text(
                stringResource(R.string.apps_missing_config, missing.joinToString(", ") { it.label }),
                style = MaterialTheme.typography.bodyMedium,
                color = WARNING_TEXT,
            )
        }
    }
    if (item.config.isNotEmpty()) {
        DetailSection(stringResource(R.string.apps_config), stringResource(R.string.apps_config_count, item.config.count { it.set }, item.config.size)) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Lumen.radiusRow)), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                item.config.forEach { field ->
                    val value = when {
                        !field.set && field.optional -> stringResource(R.string.apps_config_optional)
                        !field.set -> stringResource(R.string.apps_config_not_set)
                        field.secret -> stringResource(R.string.apps_config_set_secret)
                        else -> field.value
                    }
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = ROW_HEIGHT).background(Lumen.elevation1).clickable(role = Role.Button) { onEdit(field) }
                            .padding(horizontal = Lumen.spacingMedium, vertical = Lumen.spacingSmMed),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Lumen.spacingSmMed),
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(field.label, style = MaterialTheme.typography.titleMedium)
                            Text(value, style = MaterialTheme.typography.bodySmall, color = if (!field.missing) Lumen.textSecondary else WARNING_TEXT, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Icon(LumenIcons.chevron, contentDescription = null, modifier = Modifier.size(18.dp), tint = Lumen.textPlaceholder)
                    }
                }
            }
        }
    }
    if (item.kind == GridItem.Kind.WEB && item.offline) {
        DetailSection(
            stringResource(R.string.apps_package),
            if (item.version.isNotEmpty()) stringResource(R.string.apps_package_version, item.version) else stringResource(R.string.apps_package_no_version),
        ) {
            PillButton(
                stringResource(R.string.apps_replace_package),
                primary = false,
                modifier = Modifier.fillMaxWidth(),
                icon = LumenIcons.download,
                enabled = !busy,
            ) { actions.replacePackage(item.id) }
            Text(stringResource(R.string.apps_replace_hint), style = MaterialTheme.typography.bodySmall, color = Lumen.textPlaceholder, modifier = Modifier.padding(horizontal = Lumen.spacingSmall))
        }
    }
    if (item.kind == GridItem.Kind.WEB) {
        DetailSection(stringResource(R.string.apps_name), null) {
            Row(horizontalArrangement = Arrangement.spacedBy(Lumen.spacingSmall)) {
                PillButton(stringResource(R.string.apps_rename), primary = false, modifier = Modifier.weight(1f), onClick = onRename)
                PillButton(stringResource(R.string.apps_add_copy), primary = false, modifier = Modifier.weight(1f), icon = LumenIcons.plus, onClick = onCopy)
            }
            Text(stringResource(R.string.apps_copy_hint), style = MaterialTheme.typography.bodySmall, color = Lumen.textPlaceholder, modifier = Modifier.padding(horizontal = Lumen.spacingSmall))
        }
        DetailSection(stringResource(R.string.apps_engine), null) {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(Lumen.radiusRow)).background(Lumen.elevation1).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Segment(stringResource(R.string.apps_engine_gecko), item.engine != ENGINE_SYSTEM) { actions.setGridEngine(item.id, ENGINE_GECKO) }
                Segment(stringResource(R.string.apps_engine_system), item.engine == ENGINE_SYSTEM) { actions.setGridEngine(item.id, ENGINE_SYSTEM) }
            }
            Text(stringResource(R.string.apps_engine_hint), style = MaterialTheme.typography.bodySmall, color = Lumen.textPlaceholder, modifier = Modifier.padding(horizontal = Lumen.spacingSmall))
        }
    }
    if (item.removable || item.kind == GridItem.Kind.WEB) {
        Row(horizontalArrangement = Arrangement.spacedBy(Lumen.spacingSmall)) {
            if (item.removable) {
                PillButton(stringResource(R.string.apps_take_off), primary = false, modifier = Modifier.weight(1f), icon = LumenIcons.eyeOff, onClick = onTakeOff)
            }
            if (item.kind == GridItem.Kind.WEB) {
                androidx.compose.material3.Button(
                    onClick = onDelete,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    shape = CircleShape,
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = NEGATIVE_BACKGROUND, contentColor = Lumen.negative),
                ) {
                    Icon(LumenIcons.trash, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(Lumen.spacingSmall))
                    Text(stringResource(R.string.apps_delete), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

/** The package being handed over to the glasses: a spinner while it goes, then the outcome. */
@Composable
private fun TransferCard(transfer: PackageShare.Transfer, onDismiss: () -> Unit) {
    val update = transfer.replace.isNotEmpty()
    val (text, color) = when (transfer.phase) {
        PackageShare.Phase.COPYING -> stringResource(R.string.apps_file_copying) to Lumen.textPrimary
        PackageShare.Phase.SENDING -> stringResource(if (update) R.string.apps_file_updating else R.string.apps_file_sending, transfer.name) to Lumen.textPrimary
        PackageShare.Phase.DONE -> stringResource(if (update) R.string.apps_file_updated else R.string.apps_file_done, transfer.name) to Lumen.accent
        PackageShare.Phase.FAILED -> stringResource(R.string.apps_file_failed, transfer.detail) to Lumen.negative
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Lumen.radiusRow)).background(if (transfer.phase == PackageShare.Phase.FAILED) NEGATIVE_BACKGROUND else Lumen.surface)
            .padding(horizontal = 14.dp, vertical = Lumen.spacingSmMed),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Lumen.spacingSmMed),
    ) {
        if (transfer.busy) {
            androidx.compose.material3.CircularProgressIndicator(Modifier.size(20.dp), color = Lumen.accent, strokeWidth = 2.dp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
            if (transfer.phase == PackageShare.Phase.SENDING) {
                Text(stringResource(R.string.apps_file_sending_hint), style = MaterialTheme.typography.bodySmall, color = Lumen.textSecondary)
            }
        }
        if (!transfer.busy) {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.apps_file_ok), color = Lumen.textPrimary) }
        }
    }
}

@Composable
private fun DetailSection(title: String, trailing: String?, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Lumen.spacingSmall)) {
        Row(Modifier.padding(horizontal = Lumen.spacingSmall), verticalAlignment = Alignment.CenterVertically) {
            Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = Lumen.textPlaceholder, modifier = Modifier.weight(1f))
            if (trailing != null) Text(trailing, style = MaterialTheme.typography.bodySmall, color = Lumen.textPlaceholder)
        }
        content()
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Segment(text: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp)).background(if (on) Lumen.elevation2 else Color.Transparent)
            .clickable(role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (on) Lumen.textPrimary else Lumen.textSecondary, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 8.dp))
    }
}

/** [this] with the element at [from] moved to [to]. */
internal fun <T> List<T>.moved(from: Int, to: Int): List<T> {
    if (from !in indices || to !in indices || from == to) return this
    return toMutableList().apply { add(to, removeAt(from)) }
}

/** A web app's name: a new one for it ([copy] false), or the name of a second install of it. */
@Composable
private fun NameDialog(item: GridItem, copy: Boolean, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val start = if (copy) stringResource(R.string.apps_copy_default_name, item.name) else item.name
    var value by remember { mutableStateOf(TextFieldValue(start, TextRange(start.length))) }
    val colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Lumen.accent,
        unfocusedBorderColor = Lumen.border,
        focusedTextColor = Lumen.textPrimary,
        unfocusedTextColor = Lumen.textPrimary,
        cursorColor = Lumen.accent,
        focusedLabelColor = Lumen.accent,
        unfocusedLabelColor = Lumen.textPlaceholder,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Lumen.elevation1,
        title = { Text(stringResource(if (copy) R.string.apps_copy_title else R.string.apps_rename_title, item.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Lumen.spacingSmMed)) {
                OutlinedTextField(value, { value = it }, label = { Text(stringResource(R.string.apps_name)) }, singleLine = true, colors = colors)
                if (copy) Text(stringResource(R.string.apps_copy_dialog_hint), style = MaterialTheme.typography.bodySmall, color = Lumen.textSecondary)
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(value.text.trim()) }, enabled = value.text.isNotBlank()) {
                Text(stringResource(if (copy) R.string.apps_add_copy else R.string.apps_config_save), color = Lumen.accent)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = Lumen.textPrimary) } },
    )
}

/** Edits one configuration value; a secret starts empty (it never comes back from the glasses). */
@Composable
private fun ConfigDialog(field: AppConfigField, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var value by remember { mutableStateOf(if (field.secret) "" else field.value) }
    val colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Lumen.accent,
        unfocusedBorderColor = Lumen.border,
        focusedTextColor = Lumen.textPrimary,
        unfocusedTextColor = Lumen.textPrimary,
        cursorColor = Lumen.accent,
        focusedLabelColor = Lumen.accent,
        unfocusedLabelColor = Lumen.textPlaceholder,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Lumen.elevation1,
        title = { Text(field.label) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Lumen.spacingSmMed)) {
                OutlinedTextField(
                    value, { value = it },
                    label = { Text(field.label) },
                    singleLine = true,
                    colors = colors,
                    visualTransformation = if (field.secret) PasswordVisualTransformation() else VisualTransformation.None,
                    // A secret: no suggestions, no learning by the keyboard.
                    keyboardOptions = if (field.secret) KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false) else KeyboardOptions.Default,
                )
                if (field.secret) {
                    Text(stringResource(R.string.apps_config_secret_hint), style = MaterialTheme.typography.bodySmall, color = Lumen.textSecondary)
                }
            }
        },
        confirmButton = {
            Row {
                if (field.set) {
                    TextButton(onClick = { onSave("") }) { Text(stringResource(R.string.apps_config_clear), color = Lumen.negative) }
                }
                TextButton(onClick = { onSave(value.trim()) }, enabled = value.isNotBlank()) {
                    Text(stringResource(R.string.apps_config_save), color = Lumen.accent)
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = Lumen.textPrimary) } },
    )
}

@Composable
private fun UrlDialog(kind: AddDialog, onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    // The cursor starts after the prefix: typing appends to it (measured: it went before it).
    var url by remember { mutableStateOf(TextFieldValue("https://", TextRange(8))) }
    var name by remember { mutableStateOf("") }
    val colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Lumen.accent,
        unfocusedBorderColor = Lumen.border,
        focusedTextColor = Lumen.textPrimary,
        unfocusedTextColor = Lumen.textPrimary,
        cursorColor = Lumen.accent,
        focusedLabelColor = Lumen.accent,
        unfocusedLabelColor = Lumen.textPlaceholder,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Lumen.elevation1,
        title = { Text(stringResource(if (kind == AddDialog.WEB) R.string.apps_add_web else R.string.apps_add_package)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Lumen.spacingSmMed)) {
                OutlinedTextField(url, { url = it }, label = { Text(stringResource(R.string.apps_url)) }, singleLine = true, colors = colors)
                if (kind == AddDialog.WEB) {
                    OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.apps_name_optional)) }, singleLine = true, colors = colors)
                }
                Spacer(Modifier.size(2.dp))
            }
        },
        confirmButton = {
            val address = url.text.trim()
            TextButton(onClick = { onAdd(address, name.trim()) }, enabled = address.startsWith("https://") && address.length > 8) {
                Text(stringResource(R.string.apps_add_short), color = Lumen.accent)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = Lumen.textPrimary) } },
    )
}
