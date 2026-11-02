package io.github.lucasclgibson.nimbus.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme
import io.github.lucasclgibson.nimbus.R

object C {
    val Bg0 = Color(0xFF02040A)
    val Bg1 = Color(0xFF060D22)
    val Bg2 = Color(0xFF0B1A3F)
    val Panel = Color(0xFF0D1730)
    val PanelHi = Color(0xFF16233F)
    val Glass = Color(0x1AFFFFFF)
    val GlassHi = Color(0x33FFFFFF)
    val Cell = Color(0xFF141F38)
    val CellPast = Color(0xFF0B1326)
    val CellNow = Color(0xFF1A2A4D)
    val Stroke = Color(0x22FFFFFF)
    val Accent = Color(0xFF2E8BFF)
    val Accent2 = Color(0xFF7A5CFF)
    val Live = Color(0xFFFF3B5C)
    val Catchup = Color(0xFF3DD6B4)
    val Gold = Color(0xFFFFC94A)
    val Text = Color(0xFFFFFFFF)
    val TextDim = Color(0xFFB8C3DA)
    val TextMute = Color(0xFF7D8BA8)
    val Focus = Color(0xFFFFFFFF)
    val OnFocus = Color(0xFF07102A)
    val Good = Color(0xFF34D399)
    val Bad = Color(0xFFFF6B7D)
}

object Brushes {
    /** The wordmark: white with a cool sheen (the colour is in the cloud mark beside it). */
    val Brand = Brush.verticalGradient(listOf(Color.White, Color(0xFFD6E2FF)))
    val Accent = Brush.horizontalGradient(listOf(C.Accent, C.Accent2))
    val Base = Brush.verticalGradient(listOf(C.Bg2, C.Bg1, C.Bg0))
}

val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
    Font(R.font.inter_extrabold, FontWeight.ExtraBold),
)

object T {
    val Display = TextStyle(fontFamily = Inter, fontWeight = FontWeight.ExtraBold, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-0.6).sp)
    val H1 = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 32.sp, letterSpacing = (-0.4).sp)
    val H2 = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.2).sp)
    val H3 = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 21.sp)
    val Body = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 19.sp)
    val BodyStrong = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 19.sp)
    val Small = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 15.sp)
    val Label = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp)
    val Tiny = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 9.sp, lineHeight = 12.sp, letterSpacing = 0.6.sp)
}

@Composable
fun NimbusTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.Accent,
            onPrimary = Color.White,
            secondary = C.Accent2,
            background = C.Bg0,
            onBackground = C.Text,
            surface = C.Panel,
            onSurface = C.Text,
            surfaceVariant = C.PanelHi,
            onSurfaceVariant = C.TextDim,
            border = C.Focus,
        ),
        typography = Typography(
            bodyLarge = T.H3,
            bodyMedium = T.Body,
            bodySmall = T.Small,
            titleLarge = T.H1,
            titleMedium = T.H2,
            titleSmall = T.H3,
            labelLarge = T.Label,
            labelMedium = T.Label,
            labelSmall = T.Small,
        ),
        content = content,
    )
}
