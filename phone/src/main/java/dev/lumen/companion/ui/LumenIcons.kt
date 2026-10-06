package dev.lumen.companion.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Line icons on a 24-unit grid (the design's strokes), tinted by the Icon that draws them. */
object LumenIcons {
    val home = icon("home", "M3 11l9-7 9 7", "M5 10v10h14V10")
    val grid = icon(
        "grid",
        "M5.5 4h3A1.5 1.5 0 0 1 10 5.5v3A1.5 1.5 0 0 1 8.5 10h-3A1.5 1.5 0 0 1 4 8.5v-3A1.5 1.5 0 0 1 5.5 4z",
        "M15.5 4h3A1.5 1.5 0 0 1 20 5.5v3A1.5 1.5 0 0 1 18.5 10h-3A1.5 1.5 0 0 1 14 8.5v-3A1.5 1.5 0 0 1 15.5 4z",
        "M5.5 14h3A1.5 1.5 0 0 1 10 15.5v3A1.5 1.5 0 0 1 8.5 20h-3A1.5 1.5 0 0 1 4 18.5v-3A1.5 1.5 0 0 1 5.5 14z",
        "M15.5 14h3A1.5 1.5 0 0 1 20 15.5v3A1.5 1.5 0 0 1 18.5 20h-3A1.5 1.5 0 0 1 14 18.5v-3A1.5 1.5 0 0 1 15.5 14z",
    )
    val bell = icon("bell", "M6 16V11a6 6 0 0 1 12 0v5l2 2H4z", "M10 20a2 2 0 0 0 4 0")
    val gear = icon(
        "gear",
        "M15 12a3 3 0 1 1-6 0a3 3 0 1 1 6 0",
        "M12 3v3M12 18v3M3 12h3M18 12h3M5.6 5.6l2.1 2.1M16.3 16.3l2.1 2.1M5.6 18.4l2.1-2.1M16.3 7.7l2.1-2.1",
    )
    val glasses = icon(
        "glasses",
        "M10.5 14a3.5 3.5 0 1 1-7 0a3.5 3.5 0 1 1 7 0",
        "M20.5 14a3.5 3.5 0 1 1-7 0a3.5 3.5 0 1 1 7 0",
        "M10.5 14h3M3.5 14L5 7h2M20.5 14L19 7h-2",
    )
    val mic = icon("mic", "M12 3a3 3 0 0 1 3 3v5a3 3 0 0 1-6 0V6a3 3 0 0 1 3-3z", "M5 11a7 7 0 0 0 14 0", "M12 18v3")
    val refresh = icon("refresh", "M20 11a8 8 0 1 0-2.3 5.7", "M20 4v7h-7")
    val chevron = icon("chevron", "M9 6l6 6-6 6")
    val link = icon(
        "link",
        "M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1",
        "M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1",
    )
    val download = icon("download", "M12 4v11", "M7 10l5 5 5-5", "M5 20h14")
    val plus = icon("plus", "M12 5v14", "M5 12h14")
    val close = icon("close", "M6 6l12 12", "M18 6L6 18")
    val warning = icon("warning", "M12 9v4", "M12 17h.01", "M10.3 3.9L1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z")
    val eyeOff = icon(
        "eyeOff", "M3 3l18 18", "M10.6 10.6a2 2 0 0 0 2.8 2.8", "M9.9 5.1A10 10 0 0 1 12 5c5 0 9 4.5 10 7a13 13 0 0 1-3 4.2",
        "M6.1 6.1A13 13 0 0 0 2 12c1 2.5 5 7 10 7a10 10 0 0 0 4.2-.9",
    )
    val trash = icon("trash", "M4 7h16", "M10 11v6", "M14 11v6", "M6 7l1 13h10l1-13", "M9 7V4h6v3")
    /** Six dots: a row's drag handle (round caps on zero-length lines, drawn thicker). */
    val grip = icon("grip", "M9 6h.01", "M15 6h.01", "M9 12h.01", "M15 12h.01", "M9 18h.01", "M15 18h.01", width = 3.2f)
    val phone = icon("phone", "M9 3h6a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H9a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z", "M11 18h2")
    val back = icon("back", "M15 6l-6 6 6 6")
    val keyboard = icon(
        "keyboard",
        "M4 6h16a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1z",
        "M7 10h.01", "M11 10h.01", "M15 10h.01", "M7 14h10",
    )
    val check = icon("check", "M5 12.5l4.5 4.5L19 7.5", width = 2.4f)
    val wifi = icon("wifi", "M2 9a15 15 0 0 1 20 0", "M5.5 12.5a10 10 0 0 1 13 0", "M9 16a5 5 0 0 1 6 0", "M12 19.5h.01")
    val shield = icon("shield", "M12 3l7 3v6c0 4.5-3 7.5-7 9-4-1.5-7-4.5-7-9V6z", "M9 12l2 2 4-4")
    val band = icon("band", "M10 7h4a3 3 0 0 1 3 3v4a3 3 0 0 1-3 3h-4a3 3 0 0 1-3-3v-4a3 3 0 0 1 3-3z", "M9 7V3h6v4", "M9 17v4h6v-4")
    val laptop = icon("laptop", "M5.5 5h13A1.5 1.5 0 0 1 20 6.5V16H4V6.5A1.5 1.5 0 0 1 5.5 5z", "M2 19h20")
    val write = icon("write", "M3 17c3-6 5-6 6-3s3 3 5-2 4-5 7 0", "M3 21h18")
    val slides = icon("slides", "M4.5 4h15A1.5 1.5 0 0 1 21 5.5V16H3V5.5A1.5 1.5 0 0 1 4.5 4z", "M12 16v4", "M8 20h8")

    private fun icon(name: String, vararg paths: String, width: Float = 1.8f): ImageVector {
        val builder = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        paths.forEach { d ->
            builder.addPath(
                pathData = PathParser().parsePathString(d).toNodes(),
                stroke = SolidColor(Color.White),
                strokeLineWidth = width,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return builder.build()
    }
}
