package io.github.jdreioe.wingmate.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import io.github.jdreioe.wingmate.domain.obf.ObfImage
import io.github.jdreioe.wingmate.domain.obf.PrintedCell
import io.github.jdreioe.wingmate.domain.obf.PrintedLink
import io.github.jdreioe.wingmate.domain.obf.PrintedPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

// A4 landscape in PDF points.
private const val SHEET_WIDTH = 842
private const val SHEET_HEIGHT = 595
private const val MARGIN = 28f
private const val TITLE_HEIGHT = 30f
private const val CELL_GAP = 4f
private const val MAX_IMAGE_PIXELS = 512

/**
 * Renders a printable Screen (#227) as a PDF with one A4-landscape sheet per Page.
 * [loadImage] returns a symbol's bytes, or null when it is not available offline;
 * such Buttons print their label only. [opensLabel] words a link for paper.
 */
internal suspend fun renderBoardPdf(
    pages: List<PrintedPage>,
    loadImage: suspend (ObfImage) -> ByteArray?,
    opensLabel: (PrintedLink) -> String,
): ByteArray = withContext(Dispatchers.Default) {
    val document = PdfDocument()
    try {
        for (page in pages) {
            val images = page.cells.mapNotNull { cell ->
                cell.image?.let { image -> loadImage(image)?.decodeSampledBitmap(MAX_IMAGE_PIXELS)?.let { cell to it } }
            }.toMap()
            val sheet = document.startPage(PdfDocument.PageInfo.Builder(SHEET_WIDTH, SHEET_HEIGHT, page.number).create())
            drawPage(sheet.canvas, page, pages.size, images, opensLabel)
            document.finishPage(sheet)
            images.values.forEach(Bitmap::recycle)
        }
        ByteArrayOutputStream().also(document::writeTo).toByteArray()
    } finally {
        document.close()
    }
}

private fun drawPage(
    canvas: Canvas,
    page: PrintedPage,
    pageCount: Int,
    images: Map<PrintedCell, Bitmap>,
    opensLabel: (PrintedLink) -> String,
) {
    val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 18f; typeface = Typeface.DEFAULT_BOLD }
    canvas.drawText(page.title, MARGIN, MARGIN + 18f, titlePaint)
    val numberPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f; textAlign = Paint.Align.RIGHT }
    canvas.drawText("${page.number} / $pageCount", SHEET_WIDTH - MARGIN, MARGIN + 18f, numberPaint)

    val top = MARGIN + TITLE_HEIGHT
    val cellWidth = (SHEET_WIDTH - 2 * MARGIN) / page.columns.coerceAtLeast(1)
    val cellHeight = (SHEET_HEIGHT - top - MARGIN) / page.rows.coerceAtLeast(1)
    for (cell in page.cells) {
        val rect = RectF(
            MARGIN + cell.column * cellWidth,
            top + cell.row * cellHeight,
            MARGIN + (cell.column + cell.columnSpan) * cellWidth,
            top + (cell.row + cell.rowSpan) * cellHeight,
        ).apply { inset(CELL_GAP / 2, CELL_GAP / 2) }
        drawCell(canvas, rect, cell, images[cell], cell.opens?.let(opensLabel))
    }
}

private fun drawCell(canvas: Canvas, rect: RectF, cell: PrintedCell, image: Bitmap?, opens: String?) {
    val background = parseObfColorOrNull(cell.backgroundColor)
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = background?.toArgb() ?: android.graphics.Color.WHITE }
    canvas.drawRoundRect(rect, 8f, 8f, fill)
    val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = android.graphics.Color.BLACK
    }
    canvas.drawRoundRect(rect, 8f, 8f, border)

    val textColor = if (background != null && background.luminance() < 0.4f) android.graphics.Color.WHITE else android.graphics.Color.BLACK
    val padding = 4f
    var bottom = rect.bottom - padding
    if (opens != null) {
        val linkPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10f; color = textColor; typeface = Typeface.DEFAULT_BOLD }
        val link = layout(opens, linkPaint, rect.width() - 2 * padding, maxLines = 1)
        bottom -= link.height
        drawLayout(canvas, link, rect.left + padding, bottom)
    }
    val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = (rect.height() / 6).coerceIn(7f, 16f); color = textColor }
    val label = layout(cell.label, labelPaint, rect.width() - 2 * padding, maxLines = if (image != null) 2 else 4)
    bottom -= label.height
    if (image == null) bottom = rect.top + (rect.height() - label.height) / 2
    drawLayout(canvas, label, rect.left + padding, bottom)

    if (image != null) {
        val area = RectF(rect.left + padding, rect.top + padding, rect.right - padding, bottom - padding)
        if (area.width() > 0 && area.height() > 0) {
            val scale = minOf(area.width() / image.width, area.height() / image.height)
            val width = image.width * scale
            val height = image.height * scale
            val target = RectF(area.centerX() - width / 2, area.centerY() - height / 2, area.centerX() + width / 2, area.centerY() + height / 2)
            canvas.drawBitmap(image, null, target, Paint(Paint.FILTER_BITMAP_FLAG))
        }
    }
}

private fun layout(text: String, paint: TextPaint, width: Float, maxLines: Int): StaticLayout =
    StaticLayout.Builder.obtain(text, 0, text.length, paint, width.toInt().coerceAtLeast(1))
        .setAlignment(Layout.Alignment.ALIGN_CENTER)
        .setMaxLines(maxLines)
        .setEllipsize(TextUtils.TruncateAt.END)
        .build()

private fun drawLayout(canvas: Canvas, layout: StaticLayout, left: Float, top: Float) {
    canvas.save()
    canvas.translate(left, top)
    layout.draw(canvas)
    canvas.restore()
}
