package net.calabi.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.calabi.app.BuildConfig
import net.calabi.app.R

/** Shared by the sign-in check and initial home data load, including activity recreation. */
@Composable
fun AppLoadingScreen() {
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.White).safeDrawingPadding()) {
        val compact = maxHeight < 400.dp
        val markSize = if (compact) 80.dp else 104.dp
        Box(
            Modifier.align(Alignment.TopCenter)
                .offset(y = maxHeight * (if (compact) 0.30f else 0.44f) - markSize / 2)
                .size(markSize),
            contentAlignment = Alignment.Center,
        ) {
            LoadingOrbit()
            Image(
                painterResource(R.drawable.ic_tile), contentDescription = null,
                modifier = Modifier.size(if (compact) 44.dp else 60.dp),
                colorFilter = ColorFilter.tint(Palette.brand),
            )
        }
        Column(
            modifier = Modifier.align(Alignment.BottomCenter)
                .padding(bottom = maxHeight * (if (compact) 0.05f else 0.085f))
                .widthIn(max = 440.dp).fillMaxWidth().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                buildAnnotatedString {
                    append("cala")
                    // Match the website's light-surface --c-accent-ink token.
                    withStyle(SpanStyle(color = Color(0xFF0E7490))) { append("b") }
                    append("i")
                },
                fontFamily = Fonts.display, fontSize = 24.sp, letterSpacing = (-0.5).sp,
                fontWeight = FontWeight.ExtraBold, color = Color(0xFF15171B),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "v${BuildConfig.VERSION_NAME}", fontFamily = Fonts.mono,
                fontSize = 11.sp, letterSpacing = 1.sp, color = Color(0xFF7A8595),
            )
            Spacer(Modifier.height(16.dp))
            SplashTagline()
        }
    }
}

@Composable
private fun LoadingOrbit() {
    val transition = rememberInfiniteTransition(label = "loading orbit")
    val angle by transition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(4500, easing = LinearEasing), RepeatMode.Restart),
        label = "orbit tick",
    )
    Canvas(Modifier.fillMaxSize()) {
        val inset = 2.dp.toPx()
        val origin = Offset(inset, inset)
        val bounds = Size(size.width - inset * 2, size.height - inset * 2)
        val stroke = Stroke(width = 1.dp.toPx(), cap = StrokeCap.Round)
        drawArc(Color(0xFFDFE6FF), 38f, 284f, false, origin, bounds, style = stroke)
        // Read animated state in the draw phase; the footer need not recompose each frame.
        val head = angle + 135f
        drawArc(Palette.brand.copy(alpha = 0.15f), head - 15f, 15f, false, origin, bounds, style = stroke)
        drawArc(Palette.brand.copy(alpha = 0.35f), head - 8f, 8f, false, origin, bounds, style = stroke)
        drawArc(Palette.brand, head - 3f, 3f, false, origin, bounds, style = Stroke(1.5.dp.toPx()))
    }
}

@Composable
private fun SplashTagline() {
    val tagline = stringResource(R.string.splash_tagline)
    val measurer = rememberTextMeasurer()
    val style = TextStyle(fontFamily = Fonts.display, fontSize = 11.sp, color = Color(0xFF999999))
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val availableWidth = with(LocalDensity.current) { maxWidth.toPx() } - 1f
        val naturalWidth = measurer.measure(tagline, style, softWrap = false).size.width.coerceAtLeast(1)
        Text(
            tagline, style = style.copy(fontSize = 11.sp * minOf(1f, availableWidth / naturalWidth)),
            maxLines = 1, softWrap = false, textAlign = TextAlign.Center,
        )
    }
}
