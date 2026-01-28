import io.github.jdreioe.wingmate.domain.obf.ObfBoard
import io.github.jdreioe.wingmate.domain.obf.ObfButton
import io.github.jdreioe.wingmate.domain.obf.ObfGrid
import io.github.jdreioe.wingmate.domain.obf.ObfImage
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ObfImageLoadingTest {
    
    @Test
    fun testObfImageHasUrlField() {
        // Verify ObfImage model correctly stores URL for opensymbols.org images
        val image = ObfImage(
            id = "img1",
            url = "https://www.opensymbols.org/api/v2/images/12345/thumbnail.png",
            contentType = "image/png"
        )
        
        assertEquals("img1", image.id)
        assertEquals("https://www.opensymbols.org/api/v2/images/12345/thumbnail.png", image.url)
        assertEquals("image/png", image.contentType)
    }
    
    @Test
    fun testObfImageHasAllSourceOptions() {
        // Verify ObfImage model supports all image source types
        
        // URL-based image (opensymbols.org)
        val urlImage = ObfImage(
            id = "url-img",
            url = "https://www.opensymbols.org/api/v2/images/abc/thumbnail.png"
        )
        assertNotNull(urlImage.url)
        
        // Path-based image (OBZ archive)
        val pathImage = ObfImage(
            id = "path-img",
            path = "images/symbol.png"
        )
        assertNotNull(pathImage.path)
        
        // Base64 embedded image
        val dataImage = ObfImage(
            id = "data-img",
            data = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="
        )
        assertNotNull(dataImage.data)
    }
    
    @Test
    fun testObfButtonLinkingToImage() {
        // Verify buttons can properly reference images
        val image = ObfImage(
            id = "test-image",
            url = "https://www.opensymbols.org/images/test.png"
        )
        
        val button = ObfButton(
            id = "btn1",
            label = "Hello",
            vocalization = "Say hello",
            imageId = "test-image"
        )
        
        assertEquals("test-image", button.imageId)
        assertEquals(image.id, button.imageId)
    }
    
    @Test
    fun testObfBoardImagesAssociation() {
        // Verify board correctly associates buttons with images
        val images = listOf(
            ObfImage(id = "img1", url = "https://opensymbols.org/img1.png"),
            ObfImage(id = "img2", url = "https://opensymbols.org/img2.png")
        )
        
        val buttons = listOf(
            ObfButton(id = "btn1", label = "Word 1", imageId = "img1"),
            ObfButton(id = "btn2", label = "Word 2", imageId = "img2"),
            ObfButton(id = "btn3", label = "No Image") // No image
        )
        
        val board = ObfBoard(
            format = "open-board-0.1",
            id = "test-board",
            buttons = buttons,
            images = images,
            grid = ObfGrid(rows = 1, columns = 3, order = listOf(listOf("btn1", "btn2", "btn3")))
        )
        
        assertEquals(3, board.buttons.size)
        assertEquals(2, board.images.size)
        
        // Verify image lookup works
        val imagesById = board.images.associateBy { it.id }
        val btn1Image = board.buttons[0].imageId?.let { imagesById[it] }
        val btn3Image = board.buttons[2].imageId?.let { imagesById[it] }
        
        assertNotNull(btn1Image)
        assertEquals("https://opensymbols.org/img1.png", btn1Image.url)
        assertEquals(null, btn3Image) // Button 3 has no image
    }
    
    @Test
    fun testVocalizationDifferentFromLabel() {
        // Verify buttons can have different vocalization vs display label
        val button = ObfButton(
            id = "btn1",
            label = "🏠", // Shows emoji
            vocalization = "home" // But speaks "home"
        )
        
        assertEquals("🏠", button.label)
        assertEquals("home", button.vocalization)
        
        // The spoken text should prefer vocalization
        val spokenText = button.vocalization ?: button.label
        assertEquals("home", spokenText)
    }
}
