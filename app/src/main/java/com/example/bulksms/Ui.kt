package com.example.bulksms

import android.app.TimePickerDialog
import android.content.Intent
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

// Palette shared with SMS Relay.
val Green = Color(0xFF37715A)
val GreenLight = Color(0xFF9BB8AC)
val GreenSelected = Color(0xFFE1E8E5)
val Bg = Color(0xFFF8F8F8)
val Grey = Color(0xFF888888)
val Label = Color(0xFF666666)
val Divider = Color(0xFFDADADA)
val Border = Color(0xFFCCCCCC)
val HelpBlue = Color(0xFF0D6EFD)
val Orange = Color(0xFFEE9B00)
val Blue = Color(0xFF0077B6)
val Warn = Color(0xFFD19600)
val ErrRed = Color(0xFF960000)
val ErrBg = Color(0xFFFFD3D3)
val OkGreen = Color(0xFF1D873B)
val OkBg = Color(0xFFD6F0DD)
val DialogBg = Color(0xFFEBEBEB)
val ChipBg = Color(0xFFDADADA)

val Montserrat = FontFamily(
    Font(R.font.montserrat_light, FontWeight.Light), Font(R.font.montserrat_regular, FontWeight.Normal),
    Font(R.font.montserrat_medium, FontWeight.Medium), Font(R.font.montserrat_semibold, FontWeight.SemiBold),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val base = TextStyle(fontFamily = Montserrat, color = Color.Black)
    MaterialTheme(
        colorScheme = lightColorScheme(primary = Green, onPrimary = Color.White, background = Bg, surface = Color.White, onSurface = Color.Black, surfaceVariant = DialogBg),
        typography = Typography(
            bodyLarge = base.copy(fontSize = 17.sp), bodyMedium = base.copy(fontSize = 15.sp), bodySmall = base.copy(fontSize = 13.sp),
            titleLarge = base.copy(fontSize = 22.sp), titleMedium = base.copy(fontSize = 17.sp), labelLarge = base.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
        ),
    ) {
        Scaled { ProvideTextStyle(base.copy(fontSize = 17.sp), content) }
    }
}

// Everything was ~1.15x the original on device, so all dp/sp are scaled down uniformly.
const val UI_SCALE = 0.88f // ponytail: one knob for overall size; tune if it still feels off

/** Applies UI_SCALE from the display's real density, so wrapping twice is harmless. */
@Composable
fun Scaled(content: @Composable () -> Unit) {
    val res = LocalContext.current.resources
    CompositionLocalProvider(LocalDensity provides Density(res.displayMetrics.density * UI_SCALE, res.configuration.fontScale), content = content)
}

/** Dialogs and dropdowns open in their own window, which resets density, so they must re-apply the scale. */
@Composable
fun ScaledDialog(onDismiss: () -> Unit, properties: DialogProperties = DialogProperties(), content: @Composable () -> Unit) =
    Dialog(onDismiss, properties) { Scaled(content) }

@Composable
fun ScaledMenu(expanded: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) =
    DropdownMenu(expanded, onDismiss, containerColor = Color.White, shape = RoundedCornerShape(4.dp)) { val scope = this; Scaled { scope.content() } }

@Composable
fun T(text: String, size: TextUnit = 17.sp, color: Color = Color.Black, weight: FontWeight = FontWeight.Normal, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) =
    Text(text, modifier, color = color, fontSize = size, fontWeight = weight, fontFamily = Montserrat, maxLines = maxLines, lineHeight = size * 1.25f)

@Composable
fun AppBar(title: String, centered: Boolean = false, icon: ImageVector = Icons.AutoMirrored.Filled.ArrowBack, desc: String = "Navigate Back", onNav: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(64.dp).background(Green).shadow(2.dp)) { // 64dp x UI_SCALE = the original 56dp toolbar
        Box(Modifier.fillMaxSize().background(Green))
        IconButton(onNav, Modifier.align(Alignment.CenterStart).padding(start = 4.dp)) { Icon(icon, desc, tint = Color.White, modifier = Modifier.size(28.dp)) }
        T(title, 23.sp, Color.White, modifier = if (centered) Modifier.align(Alignment.Center) else Modifier.align(Alignment.CenterStart).padding(start = 80.dp))
    }
}

@Composable
fun HelpIcon(onClick: () -> Unit) = Icon(Icons.Outlined.HelpOutline, "help", tint = HelpBlue, modifier = Modifier.padding(start = 8.dp).size(24.dp).clip(CircleShape).clickable(onClick = onClick))

@Composable
fun Heading(text: String, size: TextUnit = 20.sp, weight: FontWeight = FontWeight.Normal, help: (() -> Unit)? = null) =
    Row(verticalAlignment = Alignment.CenterVertically) { T(text, size, weight = weight, modifier = Modifier.weight(1f, false)); help?.let { HelpIcon(it) } }

/** Info / help popup: title, message, Ok. */
@Composable
fun InfoDialog(title: String, text: String, bg: Color = Bg, onDismiss: () -> Unit) = PlainDialog(title, text, onDismiss, bg) { GreenButton("Ok", onDismiss) }

@Composable
fun PlainDialog(title: String, text: String, onDismiss: () -> Unit, bg: Color = Bg, buttons: @Composable RowScope.() -> Unit) = ScaledDialog(onDismiss) {
    Surface(shape = RoundedCornerShape(6.dp), color = bg) {
        Column(Modifier.padding(start = 24.dp, end = 16.dp, top = 24.dp, bottom = 16.dp)) {
            T(title, 20.sp, weight = FontWeight.Medium)
            Spacer(Modifier.height(16.dp))
            T(text, 16.sp, Color(0xFF555555), modifier = Modifier.padding(end = 8.dp))
            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End), content = buttons)
        }
    }
}

@Composable
fun ConfirmDialog(title: String, text: String, no: String, yes: String, onNo: () -> Unit, onYes: () -> Unit) =
    PlainDialog(title, text, onNo) { LightButton(no, onNo); GreenButton(yes, onYes) }

@Composable
fun GreenButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null, weight: FontWeight = FontWeight.SemiBold) =
    Button(onClick, modifier.heightIn(min = 40.dp), enabled = enabled, shape = RoundedCornerShape(4.dp),
        colors = ButtonDefaults.buttonColors(Green, Color.White, Divider, Grey), elevation = ButtonDefaults.buttonElevation(3.dp),
        contentPadding = PaddingValues(horizontal = 20.dp)) {
        icon?.let { Icon(it, null, Modifier.size(24.dp)); Spacer(Modifier.width(12.dp)) }
        T(text, 16.sp, if (enabled) Color.White else Grey, weight, maxLines = 1)
    }

@Composable
fun LightButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, bg: Color = Bg) =
    OutlinedButton(onClick, modifier.heightIn(min = 40.dp), shape = RoundedCornerShape(4.dp), border = BorderStroke(1.dp, Divider),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = bg), contentPadding = PaddingValues(horizontal = 20.dp)) {
        T(text, 16.sp, Green, FontWeight.SemiBold, maxLines = 1)
    }

@Composable
fun Field(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier, singleLine: Boolean = true, minHeight: Dp = 42.dp,
          error: Boolean = false, keyboard: KeyboardType = KeyboardType.Text, password: Boolean = false, trailing: (@Composable () -> Unit)? = null) {
    var focused by remember { mutableStateOf(false) }
    var show by remember { mutableStateOf(false) }
    BasicTextField(value, onChange, modifier.onFocusChanged { focused = it.isFocused }, singleLine = singleLine,
        textStyle = TextStyle(fontFamily = Montserrat, fontSize = 18.sp), keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        visualTransformation = if (password && !show) PasswordVisualTransformation() else VisualTransformation.None,
        decorationBox = { inner ->
            Row(Modifier.fillMaxWidth().heightIn(min = minHeight).border(if (focused || error) 1.5.dp else 1.dp, if (error) Color.Red else if (focused) Green else Border, RoundedCornerShape(3.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top) {
                Box(Modifier.weight(1f)) { if (value.isEmpty()) T(placeholder, 18.sp, Color(0xFFAAAAAA)); inner() }
                if (password) Icon(if (show) Icons.Default.VisibilityOff else Icons.Default.Visibility, "Show password", Modifier.size(28.dp).clickable { show = !show })
                if (error) Icon(Icons.Default.Error, "error", tint = Color.Red, modifier = Modifier.size(24.dp))
                trailing?.invoke()
            }
        })
}


/** Grey box with green label and ▼, opening a dropdown (the original's spinner look). */
@Composable
fun <T> DropBox(value: T, options: List<T>, label: (T) -> String, onPick: (T) -> Unit, modifier: Modifier = Modifier, desc: String = "Show Options", bg: Color = Bg) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(Modifier.height(46.dp).background(bg, RoundedCornerShape(3.dp)).border(1.dp, Divider, RoundedCornerShape(3.dp)).clickable { open = true }
            .padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            T(label(value), 17.sp, Green, FontWeight.SemiBold)
            Icon(Icons.Default.ArrowDropDown, desc, tint = Green, modifier = Modifier.padding(start = 8.dp))
        }
        Menu(open, { open = false }, options.map { label(it) to { onPick(it) } })
    }
}

@Composable
fun Menu(open: Boolean, onClose: () -> Unit, items: List<Pair<String, () -> Unit>>, icons: List<ImageVector?>? = null) =
    ScaledMenu(open, onClose) {
        items.forEachIndexed { i, (t, f) ->
            DropdownMenuItem({ T(t, if (icons != null) 18.sp else 17.sp, if (icons != null) Color(0xFF444444) else Color.Black) }, { onClose(); f() },
                leadingIcon = icons?.get(i)?.let { { Icon(it, null, tint = Color(0xFF555555), modifier = Modifier.size(28.dp)) } },
                contentPadding = PaddingValues(horizontal = if (icons != null) 16.dp else 24.dp, vertical = if (icons != null) 14.dp else 12.dp))
        }
    }

@Composable
fun Check(checked: Boolean, onChange: (Boolean) -> Unit) = Checkbox(checked, onChange,
    colors = CheckboxDefaults.colors(checkedColor = Green, uncheckedColor = Color(0xFF555555), checkmarkColor = Color.White))

@Composable
fun CheckRow(text: String, checked: Boolean, help: (() -> Unit)? = null, size: TextUnit = 17.sp, onChange: (Boolean) -> Unit) =
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) { T(text, size); help?.let { HelpIcon(it) } }
        Check(checked, onChange)
    }

/** Old Material switch look: thin track, overhanging thumb. */
@Composable
fun LegacySwitch(on: Boolean, onChange: (Boolean) -> Unit) =
    Box(Modifier.size(52.dp, 36.dp).clickable { onChange(!on) }, contentAlignment = Alignment.CenterStart) {
        Box(Modifier.padding(horizontal = 6.dp).fillMaxWidth().height(16.dp).background(if (on) GreenLight else Color(0xFF9E9E9E), CircleShape))
        Box(Modifier.align(if (on) Alignment.CenterEnd else Alignment.CenterStart).size(24.dp).shadow(2.dp, CircleShape).background(if (on) Green else Color(0xFFF1F1F1), CircleShape))
    }

@Composable
fun SwitchRow(text: String, on: Boolean, help: (() -> Unit)? = null, onChange: (Boolean) -> Unit) =
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) { T(text, 18.sp); help?.let { HelpIcon(it) } }
        LegacySwitch(on, onChange)
    }

@Composable
fun Section(label: String) = Column(Modifier.padding(top = 14.dp, bottom = 8.dp)) {
    HorizontalDivider(color = Divider, thickness = 1.dp)
    T(label, 13.sp, modifier = Modifier.padding(top = 6.dp))
}

@Composable
fun TemplateChip(text: String, add: Boolean, onClick: () -> Unit) =
    Row(Modifier.height(36.dp).background(ChipBg, CircleShape).clickable(onClick = onClick).padding(start = 5.dp, end = 15.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (add) Icons.Default.Add else Icons.Default.Remove, if (add) "Add" else "Remove Chip", tint = Color(0xFF646464), modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(11.dp))
        T(text, 15.5.sp, Color(0xFF1C1C1C))
    }

/** Previous ● ● ● Next footer of the campaign wizard. */
@Composable
fun WizardBar(step: Int, steps: Int, onPrev: () -> Unit, last: String = "Save", onNext: () -> Unit) =
    Row(Modifier.fillMaxWidth().background(Color.White).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        GreenButton("Previous", onPrev, Modifier.width(112.dp), enabled = step > 0, weight = FontWeight.Normal)
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            repeat(steps) { i -> Box(Modifier.padding(horizontal = 6.dp).size(if (i == step) 16.dp else 10.dp).background(if (i == step) Green else Grey, CircleShape)) }
        }
        GreenButton(if (step == steps - 1) last else "Next", onNext, Modifier.width(112.dp))
    }

@Composable
fun WhiteCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) =
    Card(modifier.fillMaxWidth(), shape = RoundedCornerShape(4.dp), colors = CardDefaults.cardColors(Color.White), elevation = CardDefaults.cardElevation(4.dp), content = content)
