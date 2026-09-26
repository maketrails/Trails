package es.jvbabi.trails.utils

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

class HttpsVisualTransformation(
    private val prefixColor: androidx.compose.ui.graphics.Color,
) : VisualTransformation {

    private companion object {
        const val PREFIX = "https://"
    }

    override fun filter(text: AnnotatedString): TransformedText {
        val transformed = AnnotatedString.Builder().apply {
            pushStyle(
                SpanStyle(
                    color = prefixColor,
                )
            )
            append(PREFIX)
            pop()

            append(text)
        }.toAnnotatedString()

        return TransformedText(
            text = transformed,
            offsetMapping = object : OffsetMapping {
                override fun originalToTransformed(offset: Int): Int =
                    offset + PREFIX.length

                override fun transformedToOriginal(offset: Int): Int =
                    (offset - PREFIX.length).coerceIn(0, text.length)
            }
        )
    }
}