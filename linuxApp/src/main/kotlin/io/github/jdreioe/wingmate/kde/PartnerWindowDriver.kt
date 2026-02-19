package io.github.jdreioe.wingmate.kde

import java.awt.image.BufferedImage
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import java.io.File
import kotlin.math.min
import org.usb4java.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer

/**
 * Kotlin driver for Tobii Partner Window Display
 *
 * Controls a Bridgetek EVE (FT81x/BT81x) display connected via FTDI FT232H SPI.
 * All register values and initialization sequences are decoded from Wireshark USB
 * captures of the Tobii I-Series AAC device.
 *
 * Dependencies (add to build.gradle.kts):
 *   implementation("org.usb4java:usb4java:1.3.0")
 *   implementation("org.usb4java:usb4java-javax:1.3.0")
 */

// ═══════════════════════════════════════════════════════════════════════════════
// EVE REGISTER ADDRESSES
// ═══════════════════════════════════════════════════════════════════════════════

const val REG_ID          = 0x302000
const val REG_FRAMES      = 0x302004
const val REG_CLOCK       = 0x302008
const val REG_FREQUENCY   = 0x30200C
const val REG_CPURESET    = 0x302020
const val REG_HCYCLE      = 0x30202C
const val REG_HOFFSET     = 0x302030
const val REG_HSIZE       = 0x302034
const val REG_HSYNC0      = 0x302038
const val REG_HSYNC1      = 0x30203C
const val REG_VCYCLE      = 0x302040
const val REG_VOFFSET     = 0x302044
const val REG_VSIZE       = 0x302048
const val REG_VSYNC0      = 0x30204C
const val REG_VSYNC1      = 0x302050
const val REG_DLSWAP      = 0x302054
const val REG_ROTATE      = 0x302058
const val REG_DITHER      = 0x302060
const val REG_SWIZZLE     = 0x302064
const val REG_CSPREAD     = 0x302068
const val REG_PCLK_POL    = 0x30206C
const val REG_PCLK        = 0x302070
const val REG_GPIO_DIR    = 0x302090
const val REG_GPIO        = 0x302094
const val REG_CMD_READ    = 0x3020F8
const val REG_CMD_WRITE   = 0x3020FC
const val REG_CMDB_SPACE  = 0x302574

const val RAM_DL          = 0x300000   // Display list RAM (8 KB)
const val RAM_CMD         = 0x308000   // Coprocessor command ring buffer (4 KB)
const val RAM_G           = 0x000000   // General-purpose graphics RAM (1 MB)

// ═══════════════════════════════════════════════════════════════════════════════
// DISPLAY CONFIGURATION
// ═══════════════════════════════════════════════════════════════════════════════

const val DISPLAY_WIDTH   = 480
const val DISPLAY_HEIGHT  = 128
const val HCYCLE          = 531
const val HOFFSET         = 43
const val HSYNC0          = 0
const val HSYNC1          = 4
const val VCYCLE          = 292
const val VOFFSET         = 84
const val VSYNC0          = 0
const val VSYNC1          = 4
const val PCLK_DIV        = 9
const val PCLK_POL        = 1
const val SWIZZLE         = 0
const val CSPREAD         = 0
const val DITHER          = 1
const val ROTATE          = 4

// ═══════════════════════════════════════════════════════════════════════════════
// HOST COMMANDS
// ═══════════════════════════════════════════════════════════════════════════════

const val HOST_ACTIVE     = 0x00
const val HOST_STANDBY    = 0x41
const val HOST_SLEEP      = 0x42
const val HOST_CLKEXT     = 0x44
const val HOST_CLKINT     = 0x48
const val HOST_PWRDOWN    = 0x50
const val HOST_CLKSEL     = 0x61
const val HOST_RST_PULSE  = 0x68

// ═══════════════════════════════════════════════════════════════════════════════
// COPROCESSOR COMMANDS
// ═══════════════════════════════════════════════════════════════════════════════

const val CMD_DLSTART     = 0xFFFFFF00.toInt()
const val CMD_SWAP        = 0xFFFFFF01.toInt()
const val CMD_BGCOLOR     = 0xFFFFFF09.toInt()
const val CMD_FGCOLOR     = 0xFFFFFF0A.toInt()
const val CMD_TEXT        = 0xFFFFFF0C.toInt()
const val CMD_BUTTON      = 0xFFFFFF0D.toInt()
const val CMD_KEYS        = 0xFFFFFF0E.toInt()
const val CMD_NUMBER      = 0xFFFFFF2E.toInt()
const val CMD_SPINNER     = 0xFFFFFF16.toInt()
const val CMD_STOP        = 0xFFFFFF17.toInt()
const val CMD_INFLATE     = 0xFFFFFF22.toInt()
const val CMD_LOADIMAGE   = 0xFFFFFF24.toInt()
const val CMD_SETROTATE   = 0xFFFFFF36.toInt()
const val CMD_SETBITMAP   = 0xFFFFFF42.toInt()

// ═══════════════════════════════════════════════════════════════════════════════
// DISPLAY LIST COMMAND BUILDERS
// ═══════════════════════════════════════════════════════════════════════════════

fun CLEAR_COLOR_RGB(r: Int, g: Int, b: Int): Int {
    return (0x02 shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)
}

fun COLOR_RGB(r: Int, g: Int, b: Int): Int {
    return (0x04 shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)
}

fun CLEAR(c: Int = 1, s: Int = 1, t: Int = 1): Int {
    return (0x26 shl 24) or ((c and 1) shl 2) or ((s and 1) shl 1) or (t and 1)
}

fun DISPLAY(): Int = 0x00000000

fun BEGIN(prim: Int): Int {
    return (0x1F shl 24) or (prim and 0xF)
}

fun END(): Int = 0x21000000

fun BITMAP_HANDLE(h: Int): Int {
    return (0x05 shl 24) or (h and 0x1F)
}

fun BITMAP_SOURCE(addr: Int): Int {
    return (0x01 shl 24) or (addr and 0xFFFFF)
}

fun BITMAP_LAYOUT(fmt: Int, stride: Int, height: Int): Int {
    return (0x07 shl 24) or ((fmt and 0x1F) shl 19) or
            ((stride and 0x3FF) shl 9) or (height and 0x1FF)
}

fun BITMAP_LAYOUT_H(stride_h: Int, height_h: Int): Int {
    return (0x28 shl 24) or ((stride_h and 0x3) shl 2) or (height_h and 0x3)
}

fun BITMAP_SIZE(filt: Int, wrapx: Int, wrapy: Int, width: Int, height: Int): Int {
    return (0x08 shl 24) or ((filt and 1) shl 20) or ((wrapx and 1) shl 19) or
            ((wrapy and 1) shl 18) or ((width and 0x1FF) shl 9) or (height and 0x1FF)
}

fun BITMAP_SIZE_H(width_h: Int, height_h: Int): Int {
    return (0x29 shl 24) or ((width_h and 0x3) shl 2) or (height_h and 0x3)
}

fun VERTEX2II(x: Int, y: Int, handle: Int = 0, cell: Int = 0): Int {
    return (2 shl 30) or ((x and 0x1FF) shl 21) or ((y and 0x1FF) shl 12) or
            ((handle and 0x1F) shl 7) or (cell and 0x7F)
}

fun VERTEX2F(x: Int, y: Int): Int {
    return (1 shl 30) or ((x and 0x7FFF) shl 15) or (y and 0x7FFF)
}

fun LINE_WIDTH(w: Int): Int {
    return (0x0E shl 24) or (w and 0xFFF)
}

fun POINT_SIZE(r: Int): Int {
    return (0x0D shl 24) or (r and 0x1FFF)
}

const val BITMAPS     = 1
const val POINTS      = 2
const val LINES       = 3
const val LINE_STRIP  = 4
const val RECTS       = 9

const val RGB565      = 7
const val DLSWAP_FRAME = 2

// ═══════════════════════════════════════════════════════════════════════════════
// PARTNER WINDOW DRIVER
// ═══════════════════════════════════════════════════════════════════════════════

class PartnerWindowDriver(
    private val vendorId: Short = 0x0403,
    private val productId: Short = 0x6014,
    private val spiFrequency: Int = 15_000_000
) : AutoCloseable {

    private var device: Device? = null
    private var handle: DeviceHandle? = null
    private var cmdWritePtr: Int = 0
    private var dlOffset: Int = 0
    
    // Default endpoints (standard FT232H usually 0x02 OUT, 0x81 IN)
    private var endpointIn: Byte = 0x81.toByte()
    private var endpointOut: Byte = 0x02.toByte()

    companion object {
        const val EVE_PD_PIN = 0x4000  // Bit 14 = ACBUS6
        const val USB_TIMEOUT_MS = 5000
        const val FTDI_VENDOR_ID: Short = 0x0403
        const val FTDI_PRODUCT_ID: Short = 0x6014

        private var libusbInitialized = false

        @Synchronized
        fun ensureLibUsbInitialized() {
            if (!libusbInitialized) {
                if (LibUsb.init(null) != LibUsb.SUCCESS) {
                    throw RuntimeException("Failed to initialize libusb")
                }
                libusbInitialized = true
            }
        }

        /**
         * Check if an FTDI FT232H device is currently connected via USB.
         * Does not open the device; only enumerates the USB bus.
         */
        fun isDeviceConnected(): Boolean {
            return try {
                ensureLibUsbInitialized()
                val deviceList = DeviceList()
                val result = LibUsb.getDeviceList(null, deviceList)
                if (result < 0) return false
                try {
                    for (i in 0 until result) {
                        val dev = deviceList.get(i)
                        val descriptor = DeviceDescriptor()
                        if (LibUsb.getDeviceDescriptor(dev, descriptor) == LibUsb.SUCCESS) {
                            if (descriptor.idVendor() == FTDI_VENDOR_ID && descriptor.idProduct() == FTDI_PRODUCT_ID) {
                                return true
                            }
                        }
                    }
                    false
                } finally {
                    LibUsb.freeDeviceList(deviceList, true)
                }
            } catch (e: Exception) {
                false
            }
        }

        fun rgb888ToRgb565(img: BufferedImage): ByteArray {
            val data = ByteArray(img.width * img.height * 2)
            var idx = 0
            for (y in 0 until img.height) {
                for (x in 0 until img.width) {
                    val argb = img.getRGB(x, y)
                    val r = (argb shr 16) and 0xFF
                    val g = (argb shr 8) and 0xFF
                    val b = argb and 0xFF

                    val pixel = ((r shr 3) shl 11) or ((g shr 2) shl 5) or (b shr 3)
                    data[idx] = (pixel and 0xFF).toByte()
                    data[idx + 1] = ((pixel shr 8) and 0xFF).toByte()
                    idx += 2
                }
            }
            return data
        }

        fun compressZlib(data: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            val deflater = DeflaterOutputStream(out, Deflater(Deflater.BEST_COMPRESSION))
            deflater.write(data)
            deflater.close()
            return out.toByteArray()
        }

        fun makeTextBitmap(
            text: String,
            outPrefix: String = "hello_world",
            fontSize: Int = 48
        ): Triple<String, String, String> {
            val img = BufferedImage(DISPLAY_WIDTH, DISPLAY_HEIGHT, BufferedImage.TYPE_INT_RGB)
            val g2d = img.createGraphics()
            g2d.color = java.awt.Color.BLACK
            g2d.fillRect(0, 0, img.width, img.height)

            g2d.font = java.awt.Font("Arial", java.awt.Font.PLAIN, fontSize)
            g2d.color = java.awt.Color.WHITE
            val fm = g2d.fontMetrics
            val textWidth = fm.stringWidth(text)
            val textHeight = fm.height
            val x = (DISPLAY_WIDTH - textWidth) / 2
            val y = (DISPLAY_HEIGHT - textHeight) / 2 + fm.ascent
            g2d.drawString(text, x, y)
            g2d.dispose()

            val pngPath = "$outPrefix.png"
            val rawPath = "$outPrefix.rgb565"
            val zlibPath = "$outPrefix.zlib"

            ImageIO.write(img, "PNG", File(pngPath))

            val rgb565 = rgb888ToRgb565(img)
            File(rawPath).writeBytes(rgb565)

            val compressed = compressZlib(rgb565)
            File(zlibPath).writeBytes(compressed)

            println("[+] Bitmap files written:")
            println("    PNG: $pngPath")
            println("    RGB565: $rawPath (${rgb565.size} B)")
            println("    zlib: $zlibPath (${compressed.size} B)")

            return Triple(pngPath, rawPath, zlibPath)
        }
    }

    /**
     * Open and initialize the FTDI device connection.
     */
    fun open() {
        ensureLibUsbInitialized()
        val deviceList = DeviceList()
        val result = LibUsb.getDeviceList(null, deviceList)
        if (result < 0) {
            throw RuntimeException("Failed to get device list: $result")
        }

        try {
            var found = false
            for (i in 0 until result) {
                val dev = deviceList.get(i)
                val descriptor = DeviceDescriptor()
                if (LibUsb.getDeviceDescriptor(dev, descriptor) != LibUsb.SUCCESS) {
                    continue
                }

                if (descriptor.idVendor() == vendorId && descriptor.idProduct() == productId) {
                    device = dev
                    found = true
                    break
                }
            }

            if (!found) {
                throw RuntimeException("FTDI device (0x${vendorId.toString(16)}:0x${productId.toString(16)}) not found")
            }

            handle = DeviceHandle()
            if (LibUsb.open(device, handle) != LibUsb.SUCCESS) {
                throw RuntimeException("Failed to open device")
            }

            // Detach kernel driver if needed
            if (LibUsb.kernelDriverActive(handle, 0) > 0) {
                LibUsb.detachKernelDriver(handle, 0)
            }

            if (LibUsb.claimInterface(handle, 0) != LibUsb.SUCCESS) {
                throw RuntimeException("Failed to claim interface")
            }

            println("[+] FTDI device opened")
            
            // Detect endpoints
            val config = ConfigDescriptor()
            if (LibUsb.getActiveConfigDescriptor(device, config) == LibUsb.SUCCESS) {
                try {
                    val iface = config.iface()[0] // Interface 0
                    val alt = iface.altsetting()[0] // Alt setting 0
                    
                    for (k in 0 until alt.bNumEndpoints()) {
                        val ep = alt.endpoint()[k]
                        val addr = ep.bEndpointAddress()
                        val attr = ep.bmAttributes()
                        if ((attr.toInt() and LibUsb.TRANSFER_TYPE_MASK.toInt()) == LibUsb.TRANSFER_TYPE_BULK.toInt()) {
                            if ((addr.toInt() and LibUsb.ENDPOINT_DIR_MASK.toInt()) == LibUsb.ENDPOINT_IN.toInt()) {
                                endpointIn = addr
                            } else {
                                endpointOut = addr
                            }
                        }
                    }
                } finally {
                    LibUsb.freeConfigDescriptor(config)
                }
            }
            println("[+] Endpoints detected: IN=0x${endpointIn.toString(16)}, OUT=0x${endpointOut.toString(16)}")
            setupMpsse()
        } finally {
            LibUsb.freeDeviceList(deviceList, true)
        }
    }

    /**
     * Close the device connection.
     */
    override fun close() {
        handle?.let {
            LibUsb.releaseInterface(it, 0)
            LibUsb.close(it)
        }
        handle = null
        device = null
        println("[+] FTDI device closed")
    }

    // ──────────── Low-level SPI ────────────

    // ──────────── Low-level SPI ────────────
    
    private fun writeRaw(data: ByteArray) {
        if (handle == null) throw RuntimeException("Device not open")
        val txBuf = ByteBuffer.allocateDirect(data.size)
        txBuf.put(data)
        txBuf.rewind()
        val transferred = IntBuffer.allocate(1)
        val result = LibUsb.bulkTransfer(handle, endpointOut, txBuf, transferred, USB_TIMEOUT_MS.toLong())
        if (result != LibUsb.SUCCESS) throw RuntimeException("Bulk write failed: $result")
    }

    private fun readRaw(len: Int): ByteArray {
        if (handle == null) throw RuntimeException("Device not open")
        
        val rxBuf = ByteBuffer.allocateDirect(512) // Max packet size for High Speed
        val transferred = IntBuffer.allocate(1)
        val out = ByteArrayOutputStream()
        
        val deadline = System.currentTimeMillis() + USB_TIMEOUT_MS
        
        while (out.size() < len && System.currentTimeMillis() < deadline) {
            rxBuf.clear()
            val result = LibUsb.bulkTransfer(handle, endpointIn, rxBuf, transferred, 100) 
            
            if (result == LibUsb.SUCCESS) {
                val read = transferred.get(0)
                if (read >= 2) {
                    // FTDI sends 2 status bytes at start of packet. Skip them.
                    val payloadLen = read - 2
                    if (payloadLen > 0) {
                        val data = ByteArray(payloadLen)
                        rxBuf.position(2)
                        rxBuf.get(data)
                        out.write(data)
                    }
                }
            } else if (result != LibUsb.ERROR_TIMEOUT) {
                // Ignore timeouts (keep polling until data arrives or full timeout)
                throw RuntimeException("Bulk read failed: $result")
            }
        }
        
        val finalData = out.toByteArray()
        if (finalData.size < len) {
             // throw RuntimeException("Read timeout: got ${finalData.size} of $len bytes")
             // Return what we have to be safe? Or padding?
             // EVE reads are usually critical. 
             return finalData // Let caller handle or fail later
        }
        
        // Return only requested amount (in case we got extra)
        return finalData.copyOf(len)
    }
    
    private fun setupMpsse() {
        if (handle == null) return
        
        // Reset
        LibUsb.controlTransfer(handle, 0x40.toByte(), 0.toByte(), 0.toShort(), 0.toShort(), ByteBuffer.allocateDirect(0), 1000)
        
        // Set Latency Timer to 1ms (Essential for fast read response)
        // FT232H: Interface 0 -> Index 1? No, Index 1 is often Interface A for 2232H. 
        // But for FT232H (single interface), standard is Interface Number + 1 usually for FTDI reset?
        // Wait, libftdi uses "interface" enum.
        // Let's try 0. If 1 failed to switch mode, 0 should work.
        LibUsb.controlTransfer(handle, 0x40.toByte(), 0x09.toByte(), 1.toShort(), 1.toShort(), ByteBuffer.allocateDirect(0), 1000)
        
        // Set Bitmode 0x02 (MPSSE). Interface A (Index 1) -> Index 1?
        // IMPORTANT: For FT232H, interface is 0. 
        // Some docs say Index = Interface + 1.
        // But standard USB control is Interface #.
        // Let's try changing to 1.toShort() -> 0.toShort() ?
        // wait, I was using 1.toShort(). I want to try 0 check.
        // Or keep 1 if I suspect FTDI quirks.
        // User says "Reg_ID = 0x3C". It's reading *something*.
        // If mode was UART, pins would be inputs/outputs differently.
        
        // I will trust standard USB: Index = Interface ID = 0.
        
        // Set Latency Timer
        LibUsb.controlTransfer(handle, 0x40.toByte(), 0x09.toByte(), 1.toShort(), 0.toShort(), ByteBuffer.allocateDirect(0), 1000)
        
        // Set Bitmode
        val mode = 0x02
        val mask = 0xFB // 1111 1011 (Dir: 1=Out). Bit 2 (DI) is in.
        val value = (mask shl 8) or mode
        LibUsb.controlTransfer(handle, 0x40.toByte(), 0x0B.toByte(), value.toShort(), 0.toShort(), ByteBuffer.allocateDirect(0), 1000)
        
        val cmd = ByteArrayOutputStream()
        cmd.write(0x8A) // Disable div by 5 (60MHz master clock)
        cmd.write(0x97) // Turn off adaptive clocking
        cmd.write(0x8D) // Disable 3-phase clocking
        cmd.write(0x86) // Set clock divisor
        cmd.write(0x01) // Value L (Div=1 -> 15MHz)
        cmd.write(0x00) // Value H
        cmd.write(0x85) // Disable loopback
        
        // Set Low Byte (ADBUS) - CS High
        cmd.write(0x80)
        cmd.write(0x08) // Val: CS=1
        cmd.write(0xFB) // Dir: 1111 1011
        
        // Set High Byte (ACBUS) - PD# High (Bit 6)
        cmd.write(0x82)
        cmd.write(0x40) // Val: PD# High
        cmd.write(0x40) // Dir: Bit 6 Out
        
        writeRaw(cmd.toByteArray())
        Thread.sleep(50)
    }

    private fun spiWrite(bytes: ByteArray) {
        val buffer = ByteArrayOutputStream()
        
        // CS Low (Val 0x00, Dir 0xFB)
        buffer.write(0x80)
        buffer.write(0x00)
        buffer.write(0xFB)
        
        // Write Data (0x11)
        val len = bytes.size - 1
        buffer.write(0x11)
        buffer.write(len and 0xFF)
        buffer.write((len shr 8) and 0xFF)
        buffer.write(bytes)
        
        // CS High (Val 0x08, Dir 0xFB)
        buffer.write(0x80)
        buffer.write(0x08)
        buffer.write(0xFB)
        
        writeRaw(buffer.toByteArray())
    }
    
    private fun spiRead(writeBytes: ByteArray, readLen: Int): ByteArray {
        val buffer = ByteArrayOutputStream()
        
        // CS Low
        buffer.write(0x80)
        buffer.write(0x00)
        buffer.write(0xFB)
        
        // Full-duplex SPI using 0x31 (Clock Out on -ve, In on +ve, MSB first).
        // Using a single command for the entire transaction avoids stray clock
        // edges at the boundary between separate write/read MPSSE commands,
        // which caused a 3-bit shift (0x7C read as 0x0F).
        val totalLen = writeBytes.size + readLen
        if (totalLen > 0) {
            val tLen = totalLen - 1
            buffer.write(0x31) // Simultaneous read+write, MSB first
            buffer.write(tLen and 0xFF)
            buffer.write((tLen shr 8) and 0xFF)
            // Address + dummy bytes followed by zero-padding for the read phase
            buffer.write(writeBytes)
            for (i in 0 until readLen) buffer.write(0x00)
            buffer.write(0x87) // Send Immediate (flush read data to host)
        }
        
        // CS High (Executed after read)
        buffer.write(0x80)
        buffer.write(0x08)
        buffer.write(0xFB)
        
        writeRaw(buffer.toByteArray())
        
        if (totalLen > 0) {
            // Full-duplex returns bytes for the entire transaction;
            // discard the first writeBytes.size bytes (received during address/dummy phase)
            val all = readRaw(totalLen)
            return if (all.size >= totalLen) {
                all.copyOfRange(writeBytes.size, totalLen)
            } else if (all.size > writeBytes.size) {
                all.copyOfRange(writeBytes.size, all.size)
            } else {
                ByteArray(readLen) // fallback: all zeros
            }
        }
        return ByteArray(0)
    }

    fun hostCmd(cmd: Int) {
        // Host Command: 3 bytes: Cmd, 00, 00
        spiWrite(byteArrayOf(cmd.toByte(), 0x00, 0x00))
    }

    fun wr8(addr: Int, value: Int) {
        // Write: Addr(2,1,0 with bit 7 set) + Data
        spiWrite(byteArrayOf(
            (0x80 or ((addr shr 16) and 0x3F)).toByte(),
            ((addr shr 8) and 0xFF).toByte(),
            (addr and 0xFF).toByte(),
            (value and 0xFF).toByte()
        ))
    }

    fun wr16(addr: Int, value: Int) {
        spiWrite(byteArrayOf(
            (0x80 or ((addr shr 16) and 0x3F)).toByte(),
            ((addr shr 8) and 0xFF).toByte(),
            (addr and 0xFF).toByte(),
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte()
        ))
    }

    fun wr32(addr: Int, value: Int) {
        spiWrite(byteArrayOf(
            (0x80 or ((addr shr 16) and 0x3F)).toByte(),
            ((addr shr 8) and 0xFF).toByte(),
            (addr and 0xFF).toByte(),
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte()
        ))
    }

    fun wrBulk(addr: Int, data: ByteArray) {
        val header = byteArrayOf(
            (0x80 or ((addr shr 16) and 0x3F)).toByte(),
            ((addr shr 8) and 0xFF).toByte(),
            (addr and 0xFF).toByte()
        )
        spiWrite(header + data)
    }

    fun rd8(addr: Int): Int {
        // Read: Addr(2,1,0) + Dummy(0) -> Read 1 byte
        val writeBytes = byteArrayOf(
            ((addr shr 16) and 0x3F).toByte(),
            ((addr shr 8) and 0xFF).toByte(),
            (addr and 0xFF).toByte(),
            0x00
        )
        val resp = spiRead(writeBytes, 1)
        return if (resp.isNotEmpty()) resp[0].toInt() and 0xFF else 0
    }

    fun rd16(addr: Int): Int {
        val writeBytes = byteArrayOf(
            ((addr shr 16) and 0x3F).toByte(),
            ((addr shr 8) and 0xFF).toByte(),
            (addr and 0xFF).toByte(),
            0x00
        )
        val resp = spiRead(writeBytes, 2)
        return if (resp.size >= 2) {
            (resp[0].toInt() and 0xFF) or ((resp[1].toInt() and 0xFF) shl 8)
        } else 0
    }

    fun rd32(addr: Int): Int {
        val writeBytes = byteArrayOf(
            ((addr shr 16) and 0x3F).toByte(),
            ((addr shr 8) and 0xFF).toByte(),
            (addr and 0xFF).toByte(),
            0x00
        )
        val resp = spiRead(writeBytes, 4)
        return if (resp.size >= 4) {
            (resp[0].toInt() and 0xFF) or
                    ((resp[1].toInt() and 0xFF) shl 8) or
                    ((resp[2].toInt() and 0xFF) shl 16) or
                    ((resp[3].toInt() and 0xFF) shl 24)
        } else 0
    }

    // ──────────── EVE Initialization ────────────

    fun init() {
        println("\n=== Initializing Partner Window ===")

        // Step 1: Host commands – wake the EVE *before* any register access
        hostCmd(HOST_CLKEXT)
        hostCmd(HOST_ACTIVE)
        Thread.sleep(300)
        println("[+] Host CLKEXT + ACTIVE sent")

        // Step 2: Poll REG_ID (EVE chip ID should be 0x7C)
        var chipId = rd8(REG_ID)
        println("[+] REG_ID = 0x${chipId.toString(16).padStart(2, '0')} (Expected 0x7C)")

        // Retry for any unexpected value, not just 0
        if (chipId != 0x7C) {
            for (i in 1..10) {
                Thread.sleep(50)
                chipId = rd8(REG_ID)
                println("[+] Retry #$i REG_ID = 0x${chipId.toString(16).padStart(2, '0')}")
                if (chipId == 0x7C) break
            }
        }

        if (chipId != 0x7C) {
             println("[!] Warning: Chip ID 0x${chipId.toString(16)} != 0x7C. Proceeding anyway.")
             // throw RuntimeException("EVE chip not responding")
        }

        // Step 3: Reset coprocessor (now that the chip is confirmed alive)
        wr8(REG_CPURESET, 0x01)
        wr16(REG_CMD_READ, 0x0000)
        wr16(REG_CMD_WRITE, 0x0000)
        wr8(REG_CPURESET, 0x00)
        Thread.sleep(20)
        println("[+] CPU reset complete")

        // Step 4: Display timing registers
        wr16(REG_HCYCLE, HCYCLE)
        wr16(REG_HOFFSET, HOFFSET)
        wr16(REG_HSYNC0, HSYNC0)
        wr16(REG_HSYNC1, HSYNC1)
        wr16(REG_VCYCLE, VCYCLE)
        wr16(REG_VOFFSET, VOFFSET)
        wr16(REG_VSYNC0, VSYNC0)
        wr16(REG_VSYNC1, VSYNC1)
        wr16(REG_SWIZZLE, SWIZZLE)
        wr8(REG_PCLK_POL, PCLK_POL)
        wr16(REG_HSIZE, DISPLAY_WIDTH)
        wr16(REG_VSIZE, DISPLAY_HEIGHT)
        wr8(REG_CSPREAD, CSPREAD)
        wr8(REG_DITHER, DITHER)
        wr8(REG_ROTATE, ROTATE)
        println("[+] Display timings set: ${DISPLAY_WIDTH}×${DISPLAY_HEIGHT}")

        // Step 5: Initial display list
        dlStart()
        dlWord(CLEAR_COLOR_RGB(0, 0, 0))
        dlWord(CLEAR(1, 1, 1))
        dlWord(DISPLAY())
        dlSwap()
        println("[+] Initial display list written")

        // Step 6: Enable GPIO
        val gpioDirOld = rd8(REG_GPIO_DIR)
        wr8(REG_GPIO_DIR, gpioDirOld or 0x80)
        wr8(REG_PCLK, PCLK_DIV)
        val gpioOld = rd8(REG_GPIO)
        wr8(REG_GPIO, gpioOld or 0x80)
        println("[+] Pixel clock started, backlight ON")

        // Step 7: Coprocessor setup
        cmdBegin()
        cmdWord(CMD_SETROTATE)
        cmdWord(ROTATE)
        cmdEnd()
        cmdWait()

        val freq = rd32(REG_FREQUENCY)
        println("[+] EVE system clock: ${freq / 1e6} MHz")
        println("=== Initialization complete ===\n")
    }

    // ──────────── Display List ────────────

    fun dlStart() {
        dlOffset = 0
    }

    fun dlWord(word: Int) {
        wr32(RAM_DL + dlOffset, word)
        dlOffset += 4
    }

    fun dlSwap() {
        wr8(REG_DLSWAP, DLSWAP_FRAME)
    }

    // ──────────── Coprocessor Commands ────────────

    fun cmdBegin() {
        cmdWritePtr = rd16(REG_CMD_WRITE)
    }

    fun cmdWord(word: Int) {
        wr32(RAM_CMD + cmdWritePtr, word)
        cmdWritePtr = (cmdWritePtr + 4) % 4096
    }

    fun cmdEnd() {
        wr16(REG_CMD_WRITE, cmdWritePtr)
    }

    fun cmdString(text: String) {
        var s = text.toByteArray(Charsets.US_ASCII) + 0x00.toByte()
        while (s.size % 4 != 0) {
            s += 0x00.toByte()
        }
        for (i in s.indices step 4) {
            val word = ((s[i].toInt() and 0xFF)) or
                    ((s[i + 1].toInt() and 0xFF) shl 8) or
                    ((s[i + 2].toInt() and 0xFF) shl 16) or
                    ((s[i + 3].toInt() and 0xFF) shl 24)
            cmdWord(word)
        }
    }

    fun cmdWait(timeoutMs: Long = 2000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val rd = rd16(REG_CMD_READ)
            val wr = rd16(REG_CMD_WRITE)
            if (rd == wr) return
            Thread.sleep(10)
        }
        throw RuntimeException("Coprocessor timeout")
    }

    // ──────────── High-Level Display Ops ────────────

    fun clearScreen(r: Int = 0, g: Int = 0, b: Int = 0) {
        dlStart()
        dlWord(CLEAR_COLOR_RGB(r, g, b))
        dlWord(CLEAR(1, 1, 1))
        dlWord(DISPLAY())
        dlSwap()
    }

    fun displayText(
        text: String,
        x: Int? = null,
        y: Int? = null,
        font: Int = 31,
        color: Triple<Int, Int, Int> = Triple(255, 255, 255)
    ) {
        val px = x ?: DISPLAY_WIDTH / 2
        val py = y ?: DISPLAY_HEIGHT / 2
        val options = if (x == null && y == null) 0x0600 else 0

        cmdBegin()
        cmdWord(CMD_DLSTART)
        cmdWord(CLEAR_COLOR_RGB(0, 0, 0))
        cmdWord(CLEAR(1, 1, 1))
        cmdWord(COLOR_RGB(color.first, color.second, color.third))
        cmdWord(CMD_TEXT)

        val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(px.toShort())
        buf.putShort(py.toShort())
        cmdWord(buf.getInt(0))

        val buf2 = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        buf2.putShort(font.toShort())
        buf2.putShort(options.toShort())
        cmdWord(buf2.getInt(0))

        cmdString(text)
        cmdWord(DISPLAY())
        cmdWord(CMD_SWAP)
        cmdEnd()
        cmdWait()
    }

    fun displayBitmapRgb565(
        data: ByteArray,
        width: Int = DISPLAY_WIDTH,
        height: Int = DISPLAY_HEIGHT,
        dest: Int = RAM_G
    ) {
        val expectedRaw = width * height * 2
        val stride = width * 2

        // Check if compressed
        val isCompressed = data.size >= 2 &&
                (data[0].toInt() and 0xFF) * 256 + (data[1].toInt() and 0xFF) % 31 == 0 &&
                data.size < expectedRaw

        if (isCompressed) {
            cmdInflate(dest, data)
        } else {
            val toWrite = if (data.size > expectedRaw) data.sliceArray(0 until expectedRaw) else data
            wrBulk(dest, toWrite)
        }

        cmdBegin()
        cmdWord(CMD_DLSTART)
        cmdWord(CLEAR_COLOR_RGB(0, 0, 0))
        cmdWord(CLEAR(1, 1, 1))
        cmdWord(BITMAP_HANDLE(0))
        cmdWord(BITMAP_SOURCE(dest))
        cmdWord(BITMAP_LAYOUT(RGB565, stride and 0x3FF, height and 0x1FF))
        cmdWord(BITMAP_LAYOUT_H((stride shr 10) and 0x3, (height shr 9) and 0x3))
        cmdWord(BITMAP_SIZE(0, 0, 0, width and 0x1FF, height and 0x1FF))
        cmdWord(BITMAP_SIZE_H((width shr 9) and 0x3, (height shr 9) and 0x3))
        cmdWord(BEGIN(BITMAPS))
        cmdWord(VERTEX2II(0, 0, 0, 0))
        cmdWord(END())
        cmdWord(DISPLAY())
        cmdWord(CMD_SWAP)
        cmdEnd()
        cmdWait()
    }

    private fun cmdInflate(dest: Int, compressedData: ByteArray) {
        var padded = compressedData.toMutableList()
        while (padded.size % 4 != 0) {
            padded.add(0x00.toByte())
        }

        cmdBegin()
        cmdWord(CMD_INFLATE)
        cmdWord(dest)

        val CHUNK = 2048
        var offset = 0
        while (offset < padded.size) {
            val chunk = padded.subList(offset, min(offset + CHUNK, padded.size))
            waitCmdSpace(chunk.size + 8)

            for (i in chunk.indices step 4) {
                val word = ((chunk[i].toInt() and 0xFF)) or
                        ((chunk[i + 1].toInt() and 0xFF) shl 8) or
                        ((chunk[i + 2].toInt() and 0xFF) shl 16) or
                        ((chunk[i + 3].toInt() and 0xFF) shl 24)
                cmdWord(word)
            }
            cmdEnd()
            Thread.sleep(1)
            cmdWritePtr = rd16(REG_CMD_WRITE)
            offset += CHUNK
        }

        cmdEnd()
        cmdWait(5000)
    }

    private fun waitCmdSpace(needed: Int, timeoutMs: Long = 2000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val rd = rd16(REG_CMD_READ)
            val wr = rd16(REG_CMD_WRITE)
            val free = (rd - wr - 4 + 4096) % 4096
            if (free >= needed) return
            Thread.sleep(5)
        }
        throw RuntimeException("CMD buffer full")
    }

    fun displayImage(path: String) {
        val img = ImageIO.read(File(path))
        val resized = BufferedImage(DISPLAY_WIDTH, DISPLAY_HEIGHT, BufferedImage.TYPE_INT_RGB)
        val g2d = resized.createGraphics()
        g2d.drawImage(img, 0, 0, DISPLAY_WIDTH, DISPLAY_HEIGHT, null)
        g2d.dispose()

        val rgb565 = rgb888ToRgb565(resized)
        val compressed = compressZlib(rgb565)
        println("[+] Image: $path")
        println("    Raw: ${rgb565.size} B -> Compressed: ${compressed.size} B")

        displayBitmapRgb565(compressed)
    }

    private fun drivePdLow() {
        // Set High Byte (ACBUS) - PD# Low (Bit 6)
        // Val: 0x00, Dir: 0x40
        writeRaw(byteArrayOf(0x82.toByte(), 0x00, 0x40))
    }

    fun shutdown() {
        println("\n=== Shutting down ===")
        clearScreen(0, 0, 0)
        Thread.sleep(50)

        val gpioOld = rd8(REG_GPIO)
        wr8(REG_GPIO, gpioOld and 0x7F.inv())
        wr8(REG_PCLK, 0)
        println("[+] Display off")
        
        drivePdLow()
        println("[+] EVE PD# -> Low (Power Down)")
    }
}
