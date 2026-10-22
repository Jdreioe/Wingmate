package io.github.jdreioe.wingmate.ui

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jdreioe.wingmate.domain.obf.ObfImage
import io.github.jdreioe.wingmate.domain.obf.PrintedCell
import io.github.jdreioe.wingmate.domain.obf.PrintedLink
import io.github.jdreioe.wingmate.domain.obf.PrintedPage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class BoardPdfTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun everyPagePrintsOnItsOwnSheetAndLinksAreWorded() = runBlocking {
        val symbol = ObfImage(id = "img", data = "unused")
        val pages = listOf(
            PrintedPage(1, "Home", rows = 1, columns = 2, cells = listOf(
                PrintedCell(0, 0, 1, 1, "Hi", "rgb(255,255,0)", symbol, opens = null),
                PrintedCell(0, 1, 1, 1, "Food", null, null, opens = PrintedLink("Food", 2)),
            )),
            PrintedPage(2, "Food", rows = 1, columns = 1, cells = listOf(PrintedCell(0, 0, 1, 1, "Apple", null, null, null))),
        )
        val worded = mutableListOf<PrintedLink>()
        val pngSymbol = ByteArrayOutputStream().also {
            Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()

        val pdf = renderBoardPdf(pages, loadImage = { pngSymbol }, opensLabel = { worded += it; "→ ${it.title}" })

        val file = File(context.cacheDir, "board-pdf-test.pdf").apply { writeBytes(pdf) }
        try {
            PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)).use { renderer ->
                assertEquals(2, renderer.pageCount)
            }
        } finally {
            file.delete()
        }
        assertEquals(listOf(PrintedLink("Food", 2)), worded)
    }
}
