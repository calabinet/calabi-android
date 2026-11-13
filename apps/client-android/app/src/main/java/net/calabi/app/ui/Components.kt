package net.calabi.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.calabi.app.R
import net.calabi.app.BuildConfig

@Composable
fun Glyph(@DrawableRes res: Int, tint: Color = Palette.ink, size: Dp = 20.dp) {
    Icon(painterResource(res), contentDescription = null, modifier = Modifier.size(size), tint = tint)
}

@Composable
fun Dot(color: Color, size: Dp = 8.dp) {
    Box(Modifier.size(size).background(color, CircleShape))
}

/** A white rounded group of rows, the design's list card. */
@Composable
fun Group(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Palette.surface),
        content = content,
    )
}

@Composable
fun RowDivider(start: Dp = 16.dp) {
    Box(Modifier.padding(start = start).fillMaxWidth().height(1.dp).background(Palette.divider))
}

@Composable
fun SectionLabel(text: String, hint: String? = null) {
    Column(Modifier.padding(start = 8.dp, top = 8.dp, end = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text, style = Styles.sectionLabel)
        hint?.let { Text(it, style = Styles.hint) }
    }
}

/** An empty ring, or a green disc with a check. */
@Composable
fun RadioMark(selected: Boolean) {
    if (selected) {
        Box(Modifier.size(22.dp).background(Palette.accent, CircleShape), contentAlignment = Alignment.Center) {
            Glyph(R.drawable.ic_check, Color.White, 14.dp)
        }
    } else {
        Box(Modifier.size(22.dp).border(2.dp, Palette.radioLine, CircleShape))
    }
}

/** One choice in a group: a title, an optional detail line, a radio mark. */
@Composable
fun ChoiceRow(title: String, selected: Boolean, detail: String? = null, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(selected = selected, enabled = enabled, onClick = onClick, role = Role.RadioButton)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = if (selected) Styles.rowTitle else Styles.row)
            detail?.let { Text(it, style = Styles.hint) }
        }
        RadioMark(selected)
    }
}

/** A row that performs an action: optional icon, a label, optional trailing content. */
@Composable
fun ActionRow(
    label: String,
    onClick: () -> Unit,
    @DrawableRes icon: Int? = null,
    color: Color = Palette.ink,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 54.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        icon?.let { Glyph(it, color, 18.dp) }
        Text(
            label, modifier = Modifier.weight(1f), style = Styles.row.copy(color = color),
            fontWeight = if (color == Palette.ink) FontWeight.Normal else FontWeight.SemiBold,
        )
        trailing()
    }
}

/** A pill-shaped secondary control. */
@Composable
fun Pill(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(Palette.surface)
            .border(1.dp, Palette.line, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

/** A row that opens something: title, hint, chevron. */
@Composable
fun LinkRow(title: String, hint: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = Styles.row)
            Text(hint, style = Styles.hint)
        }
        Glyph(R.drawable.ic_chevron_right, Palette.muted, 16.dp)
    }
}

/** A setting that is on or off: title, hint, switch. */
@Composable
fun SwitchRow(title: String, hint: String, checked: Boolean, enabled: Boolean = true, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = Styles.row)
            Text(hint, style = Styles.hint)
        }
        Switch(checked = checked, enabled = enabled, modifier = Modifier.semantics { contentDescription = title },
            colors = calabiSwitchColors(), onCheckedChange = onCheckedChange)
    }
}

@Composable
fun calabiSwitchColors(): SwitchColors = SwitchDefaults.colors(
    checkedThumbColor = Color.White,
    checkedTrackColor = Palette.accent,
    checkedBorderColor = Palette.accent,
    uncheckedThumbColor = Color.White,
    uncheckedTrackColor = Palette.avatar,
    uncheckedBorderColor = Palette.avatar,
)

@Composable
fun PrimaryButton(text: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 54.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Palette.accent, contentColor = Color.White,
            disabledContainerColor = Palette.accent.copy(alpha = 0.35f), disabledContentColor = Color.White,
        ),
    ) { Text(text, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
}

/** Label above, white box below; the box turns green while focused. */
@Composable
fun LabeledField(
    label: String?,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
    imeAction: ImeAction = ImeAction.Next,
    onImeAction: () -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        label?.let { Text(it, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Palette.label) }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(fontFamily = Fonts.display, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Palette.ink),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
            keyboardActions = KeyboardActions(onAny = { onImeAction() }),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            cursorBrush = SolidColor(Palette.accent),
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
            decorationBox = { inner ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .clip(shape)
                        .background(Palette.surface)
                        .border(if (focused) 2.dp else 1.dp, if (focused) Palette.accent else Palette.fieldLine, shape)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    contentAlignment = Alignment.CenterStart,
                ) { inner() }
            },
        )
    }
}

/** Equal-width tabs grow for larger text and long translations. */
@Composable
fun BottomNav(tab: Int, onTab: (Int) -> Unit) {
    val tabs = buildList {
        add(Triple(0, R.drawable.ic_mesh, R.string.tab_mesh))
        add(Triple(1, R.drawable.ic_tunnel, R.string.tab_tunnels))
        add(Triple(4, R.drawable.ic_monitor, R.string.remote_tab))
        if (BuildConfig.FILE_TRANSFER_ENABLED) add(Triple(3, R.drawable.ic_transfer, R.string.files_tab))
        add(Triple(2, R.drawable.ic_settings, R.string.tab_settings))
    }
    Column(Modifier.fillMaxWidth().background(Palette.ground)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.line))
        val largeText = LocalDensity.current.fontScale >= 1.3f && LocalConfiguration.current.screenWidthDp < 420
        if (largeText) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp)) {
                val columns = if (tabs.size > 4) 3 else 2
                tabs.chunked(columns).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        row.forEach { (index, icon, label) ->
                            Column(Modifier.weight(1f).heightIn(min = 56.dp).clip(RoundedCornerShape(12.dp))
                                .background(if (tab == index) Palette.accentSoft else Color.Transparent)
                                .selectable(selected = tab == index, onClick = { onTab(index) }, role = Role.Tab)
                                .padding(horizontal = 10.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Glyph(icon, if (tab == index) Palette.accent else Palette.muted, 20.dp)
                                Text(stringResource(label), fontSize = 12.sp, lineHeight = 16.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    color = if (tab == index) Palette.accent else Palette.muted)
                            }
                        }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        } else {
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            tabs.forEach { (index, icon, label) ->
                Column(Modifier.weight(1f).heightIn(min = 56.dp).clip(RoundedCornerShape(16.dp))
                    .selectable(selected = tab == index, onClick = { onTab(index) }, role = Role.Tab)
                    .padding(horizontal = 4.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Box(Modifier.background(if (tab == index) Palette.accentSoft else Color.Transparent, RoundedCornerShape(16.dp)).padding(horizontal = 16.dp, vertical = 3.dp)) {
                        Glyph(icon, if (tab == index) Palette.accent else Palette.muted, 20.dp)
                    }
                    Text(stringResource(label), fontSize = 12.sp, lineHeight = 16.sp, color = if (tab == index) Palette.accent else Palette.muted,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        }
    }
    }
}

/** A sub-screen's top row: a round back button, and room for actions on the right. */
@Composable
fun TopBar(onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CircleButton(R.drawable.ic_chevron_left, stringResource(R.string.back), onBack)
        Spacer(Modifier.weight(1f))
        actions()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CircleButton(@DrawableRes icon: Int, description: String, onClick: () -> Unit) {
    val context = LocalContext.current
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .combinedClickable(role = Role.Button, onClick = onClick,
                onLongClick = { Toast.makeText(context, description, Toast.LENGTH_SHORT).show() })
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Glyph(icon, Palette.ink, 20.dp) }
}

/** One choice of a small segmented control: an ink pill when selected. */
@Composable
fun Segment(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(if (selected) Palette.accentSoft else Palette.surface)
            .border(1.dp, if (selected) Palette.accent else Palette.line, shape)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (selected) Palette.accent else Palette.ink)
    }
}

fun copyToClipboard(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(text, text))
    Toast.makeText(context, context.getString(R.string.copied, text), Toast.LENGTH_SHORT).show()
}

fun toast(context: Context, text: String) {
    Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
}


fun matchesListSearch(query: String, vararg values: String): Boolean =
    query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.all { term -> values.any { it.contains(term, ignoreCase = true) } }

@Composable
fun ListSearchToolbar(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit = {},
) {
    var expanded by rememberSaveable { mutableStateOf(value.isNotEmpty()) }
    var requestFocus by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val closeSearch = {
        focusManager.clearFocus()
        keyboard?.hide()
        onValueChange("")
        requestFocus = false
        expanded = false
    }
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    BackHandler(enabled = expanded) {
        if (imeVisible) { focusManager.clearFocus(); keyboard?.hide() } else closeSearch()
    }
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        if (expanded) {
            androidx.compose.material3.IconButton(onClick = closeSearch, modifier = Modifier.size(48.dp)) {
                androidx.compose.material3.Icon(
                    painterResource(R.drawable.ic_chevron_left),
                    contentDescription = stringResource(R.string.list_search_close), tint = Palette.ink,
                    modifier = Modifier.size(20.dp),
                )
            }
            ListSearchField(value, onValueChange, placeholder, requestFocus, { requestFocus = false }, Modifier.weight(1f))
        } else {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, content = content)
            androidx.compose.material3.IconButton(onClick = { requestFocus = true; expanded = true }, modifier = Modifier.size(48.dp)) {
                androidx.compose.material3.Icon(
                    painterResource(R.drawable.ic_search), contentDescription = placeholder,
                    tint = Palette.muted, modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun ListSearchField(value: String, onValueChange: (String) -> Unit, placeholder: String,
    requestFocus: Boolean, onFocusRequested: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val requester = remember { FocusRequester() }
    LaunchedEffect(requestFocus) {
        if (requestFocus) {
            requester.requestFocus()
            keyboard?.show()
            onFocusRequested()
        }
    }
    val shape = RoundedCornerShape(8.dp)
    val fieldHeight = maxOf(48.dp, with(LocalDensity.current) { 22.sp.toDp() + 8.dp })
    val clearLabel = stringResource(R.string.list_search_clear)
    BasicTextField(
        value = value, onValueChange = onValueChange, singleLine = true,
        textStyle = TextStyle(fontFamily = Fonts.display, fontSize = 15.sp, color = Palette.ink),
        cursorBrush = SolidColor(Palette.accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus(); keyboard?.hide() }),
        modifier = modifier.fillMaxWidth().focusRequester(requester).onFocusChanged { focused = it.isFocused }.semantics { contentDescription = placeholder },
        decorationBox = { inner ->
            Box(Modifier.fillMaxWidth().heightIn(min = maxOf(48.dp, fieldHeight)), contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxWidth().height(fieldHeight).clip(shape).background(Palette.offRing)
                    .then(if (focused) Modifier.border(1.dp, Palette.accent, shape) else Modifier))
                Row(Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Glyph(R.drawable.ic_search, Palette.muted, 16.dp)
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) Text(placeholder, color = Palette.muted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        inner()
                    }
                    if (value.isNotEmpty()) androidx.compose.material3.IconButton(onClick = { onValueChange(""); requester.requestFocus(); keyboard?.show() }, modifier = Modifier.size(48.dp).semantics { contentDescription = clearLabel }) {
                        Glyph(R.drawable.ic_close, Palette.muted, 16.dp)
                    }
                }
            }
        },
    )
}

/** Persistent Home search; Back hides the IME before clearing a query. */
@Composable
fun ListSearchInput(value: String, onValueChange: (String) -> Unit, placeholder: String) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val ime = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    BackHandler(enabled = value.isNotEmpty() || ime) {
        focus.clearFocus(); keyboard?.hide()
        if (!ime) onValueChange("")
    }
    ListSearchField(value, onValueChange, placeholder, false, {})
}

/** Shared compact chrome for top-level and pushed phone pages. */
@Composable
fun PageHeading(title: String, modifier: Modifier = Modifier, onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (onBack != null) CircleButton(R.drawable.ic_chevron_left, stringResource(R.string.back), onBack)
        Text(title, style = Styles.screenTitle, modifier = Modifier.weight(1f))
        actions()
    }
}

@Composable
fun IconLinkRow(title: String, hint: String, @DrawableRes icon: Int, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 68.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 6.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(40.dp).background(Palette.accentSoft, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { Glyph(icon, Palette.accent) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = Styles.rowTitle)
            if (hint.isNotBlank()) Text(hint, style = Styles.hint)
        }
        Glyph(R.drawable.ic_chevron_right, Palette.muted, 16.dp)
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ListFilterTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth()) {
        labels.forEachIndexed { index, label ->
            Column(Modifier.widthIn(min = 72.dp).selectable(selected = selected == index, role = Role.Tab, onClick = { onSelect(index) }).padding(horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
                    Text(label, color = if (selected == index) Palette.accent else Palette.muted, fontSize = 13.sp,
                        fontWeight = if (selected == index) FontWeight.Bold else FontWeight.Normal)
                }
                Box(Modifier.size(width = 32.dp, height = 2.dp).background(if (selected == index) Palette.accent else Color.Transparent))
            }
        }
    }
}
