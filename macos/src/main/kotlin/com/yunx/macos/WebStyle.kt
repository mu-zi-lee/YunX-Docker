package com.yunx.macos

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown

data class WebColors(val bg: Color, val surface: Color, val raised: Color, val line: Color, val text: Color, val secondary: Color) {
    val red = Color(0xffd71921)
    companion object {
        val Light = WebColors(Color(0xfff5f5f5), Color.White, Color(0xffeeeeee), Color(0xffdedede), Color(0xff1a1a1a), Color(0xff606060))
        val Dark = WebColors(Color.Black, Color(0xff111111), Color(0xff1a1a1a), Color(0xff282828), Color(0xffe8e8e8), Color(0xffaaaaaa))
    }
}
val Colors = staticCompositionLocalOf { WebColors.Light }
val BodyFont = FontFamily(Font("fonts/space-grotesk.ttf"))
val MonoFont = FontFamily(Font("fonts/space-mono.ttf"))

@Composable
fun Label(text: String, modifier: Modifier = Modifier, size: Int = 14, muted: Boolean = false, mono: Boolean = false, lines: Int = Int.MAX_VALUE) {
    BasicText(text, modifier, style = TextStyle(
        color = if (muted) Colors.current.secondary else Colors.current.text,
        fontSize = size.sp, fontFamily = if (mono) MonoFont else BodyFont,
        fontWeight = FontWeight.Normal, lineHeight = (size * 1.5).sp
    ), maxLines = lines, overflow = TextOverflow.Ellipsis)
}

@Composable
fun Command(title: String, icon: ImageVector? = null, primary: Boolean = false, enabled: Boolean = true, action: () -> Unit) {
    val c = Colors.current
    Row(Modifier.alpha(if (enabled) 1f else .45f).heightIn(min = 40.dp).background(if (primary) c.text else Color.Transparent, RoundedCornerShape(6.dp))
        .border(1.dp, if (primary) c.text else c.line, RoundedCornerShape(6.dp))
        .clickable(enabled = enabled, onClick = action).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (icon != null) Icon(icon, title, Modifier.size(18.dp), tint = if (primary) c.bg else c.text)
        CompositionLocalProvider(Colors provides if (primary) c.copy(text = c.bg) else c.copy(text = if (enabled) c.text else c.secondary)) {
            Label(title, lines = 1)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Tool(title: String, icon: ImageVector, enabled: Boolean = true, action: () -> Unit) {
    TooltipArea(tooltip = {
        Box(Modifier.background(Colors.current.surface).border(1.dp, Colors.current.line).padding(8.dp)) { Label(title, size = 12) }
    }) {
        Box(Modifier.alpha(if (enabled) 1f else .45f).size(40.dp).clickable(enabled = enabled, onClick = action), contentAlignment = Alignment.Center) {
            Icon(icon, title, Modifier.size(19.dp), tint = if (enabled) Colors.current.text else Colors.current.secondary)
        }
    }
}

@Composable
fun Field(title: String, value: String, change: (String) -> Unit, modifier: Modifier = Modifier, multiline: Boolean = false, secret: Boolean = false, enabled: Boolean = true) {
    val c = Colors.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        if (title.isNotEmpty()) Label(title, size = 12, muted = true)
        BasicTextField(value, change, enabled = enabled, singleLine = !multiline,
            textStyle = TextStyle(color = c.text, fontSize = 14.sp, fontFamily = BodyFont),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            cursorBrush = androidx.compose.ui.graphics.SolidColor(c.text),
            modifier = Modifier.fillMaxWidth().heightIn(min = if (multiline) 84.dp else 40.dp)
                .border(1.dp, c.secondary, RoundedCornerShape(6.dp)).padding(12.dp))
    }
}

@Composable
fun Rule() = Box(Modifier.fillMaxWidth().height(1.dp).background(Colors.current.line))

@Composable
fun Choice(value: String, options: List<Pair<String, String>>, enabled: Boolean = true, select: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val c = Colors.current
    Box {
        Command(options.firstOrNull { it.first == value }?.second ?: "选择账号", Icons.Outlined.ArrowDropDown,
            enabled = enabled && options.isNotEmpty()) { expanded = !expanded }
        if (expanded) Popup(alignment = Alignment.TopStart, offset = IntOffset(0, 44),
            onDismissRequest = { expanded = false }, properties = PopupProperties(focusable = true)) {
            Column(Modifier.widthIn(min = 160.dp, max = 300.dp).heightIn(max = 320.dp)
                .background(c.surface, RoundedCornerShape(6.dp)).border(1.dp, c.line, RoundedCornerShape(6.dp))
                .verticalScroll(rememberScrollState()).padding(4.dp)) {
                options.forEach { (key, title) ->
                    Label(title, Modifier.fillMaxWidth().background(if (key == value) c.raised else Color.Transparent, RoundedCornerShape(4.dp))
                        .clickable { expanded = false; select(key) }.padding(10.dp), lines = 1)
                }
            }
        }
    }
}

fun sizeText(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KiB", "MiB", "GiB", "TiB")
    var value = bytes / 1024.0
    var index = 0
    while (value >= 1024 && index < units.lastIndex) { value /= 1024; index++ }
    return "%.1f %s".format(value, units[index])
}
