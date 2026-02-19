//! Partner Window Display Driver — Bridgetek EVE over FTDI FT232H SPI
//!
//! Controls a Bridgetek EVE (FT81x/BT81x) display connected via FTDI FT232H SPI.
//! All register values and initialization sequences decoded from Wireshark USB
//! captures of the Tobii I-Series AAC device.
//!
//! Hardware chain:
//!   PC ──USB──▶ FTDI FT232H (0403:6014) ──SPI 15MHz──▶ Bridgetek EVE GPU ──RGB──▶ 480×128 LCD
//!
//! This is a direct Rust port of the working Python reference (`reference.py`),
//! using `ftdi-embedded-hal` which handles all MPSSE setup, CS, purge, and sync
//! correctly out of the box.

use embedded_hal::spi::{Operation, SpiDevice};
use embedded_hal::digital::OutputPin;
use ftdi_embedded_hal as hal;
use std::thread;
use std::time::{Duration, Instant};

// ═══════════════════════════════════════════════════════════════════════════════
// EVE REGISTER ADDRESSES
// ═══════════════════════════════════════════════════════════════════════════════

pub const REG_ID: u32         = 0x302000;
pub const REG_FRAMES: u32     = 0x302004;
pub const REG_CLOCK: u32      = 0x302008;
pub const REG_FREQUENCY: u32  = 0x30200C;
pub const REG_CPURESET: u32   = 0x302020;
pub const REG_HCYCLE: u32     = 0x30202C;
pub const REG_HOFFSET: u32    = 0x302030;
pub const REG_HSIZE: u32      = 0x302034;
pub const REG_HSYNC0: u32     = 0x302038;
pub const REG_HSYNC1: u32     = 0x30203C;
pub const REG_VCYCLE: u32     = 0x302040;
pub const REG_VOFFSET: u32    = 0x302044;
pub const REG_VSIZE: u32      = 0x302048;
pub const REG_VSYNC0: u32     = 0x30204C;
pub const REG_VSYNC1: u32     = 0x302050;
pub const REG_DLSWAP: u32     = 0x302054;
pub const REG_ROTATE: u32     = 0x302058;
pub const REG_DITHER: u32     = 0x302060;
pub const REG_SWIZZLE: u32    = 0x302064;
pub const REG_CSPREAD: u32    = 0x302068;
pub const REG_PCLK_POL: u32   = 0x30206C;
pub const REG_PCLK: u32       = 0x302070;
pub const REG_GPIO_DIR: u32   = 0x302090;
pub const REG_GPIO: u32       = 0x302094;
pub const REG_CMD_READ: u32   = 0x3020F8;
pub const REG_CMD_WRITE: u32  = 0x3020FC;
pub const REG_CMDB_SPACE: u32 = 0x302574;

pub const RAM_DL: u32  = 0x300000; // Display list RAM (8 KB)
pub const RAM_CMD: u32 = 0x308000; // Coprocessor command ring buffer (4 KB)
pub const RAM_G: u32   = 0x000000; // General-purpose graphics RAM (1 MB)

// ═══════════════════════════════════════════════════════════════════════════════
// DISPLAY PANEL CONFIGURATION (decoded from pcap captures)
// ═══════════════════════════════════════════════════════════════════════════════

pub const DISPLAY_WIDTH: u16  = 480;
pub const DISPLAY_HEIGHT: u16 = 128;
pub const HCYCLE: u16         = 531;
pub const HOFFSET: u16        = 43;
pub const HSYNC0: u16         = 0;
pub const HSYNC1: u16         = 4;
pub const VCYCLE: u16         = 292;
pub const VOFFSET: u16        = 84;
pub const VSYNC0: u16         = 0;
pub const VSYNC1: u16         = 4;
pub const PCLK_DIV: u8        = 9;
pub const PCLK_POL_VAL: u8    = 1;
pub const SWIZZLE_VAL: u16    = 0;
pub const CSPREAD_VAL: u8     = 0;
pub const DITHER_VAL: u8      = 1;
pub const ROTATE_VAL: u32     = 4; // Mirrored landscape (partner sees correct text)

// ═══════════════════════════════════════════════════════════════════════════════
// EVE HOST COMMANDS
// ═══════════════════════════════════════════════════════════════════════════════

pub const HOST_ACTIVE: u8  = 0x00;
pub const HOST_STANDBY: u8 = 0x41;
pub const HOST_SLEEP: u8   = 0x42;
pub const HOST_CLKEXT: u8  = 0x44;
pub const HOST_CLKINT: u8  = 0x48;
pub const HOST_PWRDOWN: u8 = 0x50;
pub const HOST_CLKSEL: u8  = 0x61;
pub const HOST_RST_PULSE: u8 = 0x68;

// ═══════════════════════════════════════════════════════════════════════════════
// COPROCESSOR COMMANDS
// ═══════════════════════════════════════════════════════════════════════════════

pub const CMD_DLSTART: u32   = 0xFFFFFF00;
pub const CMD_SWAP: u32      = 0xFFFFFF01;
pub const CMD_BGCOLOR: u32   = 0xFFFFFF09;
pub const CMD_FGCOLOR: u32   = 0xFFFFFF0A;
pub const CMD_TEXT: u32      = 0xFFFFFF0C;
pub const CMD_BUTTON: u32    = 0xFFFFFF0D;
pub const CMD_KEYS: u32     = 0xFFFFFF0E;
pub const CMD_NUMBER: u32    = 0xFFFFFF2E;
pub const CMD_SPINNER: u32   = 0xFFFFFF16;
pub const CMD_STOP: u32     = 0xFFFFFF17;
pub const CMD_INFLATE: u32   = 0xFFFFFF22;
pub const CMD_LOADIMAGE: u32 = 0xFFFFFF24;
pub const CMD_SETROTATE: u32 = 0xFFFFFF36;
pub const CMD_SETBITMAP: u32 = 0xFFFFFF42;

// ═══════════════════════════════════════════════════════════════════════════════
// DISPLAY LIST COMMAND BUILDERS
// ═══════════════════════════════════════════════════════════════════════════════

#[inline]
pub fn clear_color_rgb(r: u8, g: u8, b: u8) -> u32 {
    (0x02 << 24) | ((r as u32) << 16) | ((g as u32) << 8) | (b as u32)
}

#[inline]
pub fn color_rgb(r: u8, g: u8, b: u8) -> u32 {
    (0x04 << 24) | ((r as u32) << 16) | ((g as u32) << 8) | (b as u32)
}

#[inline]
pub fn color_a(a: u8) -> u32 {
    (0x10 << 24) | (a as u32)
}

#[inline]
pub fn clear(c: bool, s: bool, t: bool) -> u32 {
    (0x26 << 24) | ((c as u32) << 2) | ((s as u32) << 1) | (t as u32)
}

#[inline]
pub fn display() -> u32 {
    0x00000000
}

#[inline]
pub fn begin(prim: u8) -> u32 {
    (0x1F << 24) | ((prim as u32) & 0xF)
}

#[inline]
pub fn end() -> u32 {
    0x21000000
}

#[inline]
pub fn vertex2ii(x: u16, y: u16, handle: u8, cell: u8) -> u32 {
    (2 << 30)
        | (((x as u32) & 0x1FF) << 21)
        | (((y as u32) & 0x1FF) << 12)
        | (((handle as u32) & 0x1F) << 7)
        | ((cell as u32) & 0x7F)
}

#[inline]
pub fn vertex2f(x: i32, y: i32) -> u32 {
    (1 << 30) | (((x as u32) & 0x7FFF) << 15) | ((y as u32) & 0x7FFF)
}

#[inline]
pub fn line_width(w: u16) -> u32 {
    (0x0E << 24) | ((w as u32) & 0xFFF)
}

#[inline]
pub fn point_size(r: u16) -> u32 {
    (0x0D << 24) | ((r as u32) & 0x1FFF)
}

#[inline]
pub fn bitmap_handle(h: u8) -> u32 {
    (0x05 << 24) | ((h as u32) & 0x1F)
}

#[inline]
pub fn bitmap_source(addr: u32) -> u32 {
    (0x01 << 24) | (addr & 0xFFFFF)
}

#[inline]
pub fn bitmap_layout(fmt: u8, stride: u16, height: u16) -> u32 {
    (0x07 << 24)
        | (((fmt as u32) & 0x1F) << 19)
        | (((stride as u32) & 0x3FF) << 9)
        | ((height as u32) & 0x1FF)
}

#[inline]
pub fn bitmap_layout_h(stride_h: u8, height_h: u8) -> u32 {
    (0x28 << 24) | (((stride_h as u32) & 0x3) << 2) | ((height_h as u32) & 0x3)
}

#[inline]
pub fn bitmap_size(filt: bool, wrapx: bool, wrapy: bool, width: u16, height: u16) -> u32 {
    (0x08 << 24)
        | ((filt as u32) << 20)
        | ((wrapx as u32) << 19)
        | ((wrapy as u32) << 18)
        | (((width as u32) & 0x1FF) << 9)
        | ((height as u32) & 0x1FF)
}

#[inline]
pub fn bitmap_size_h(width_h: u8, height_h: u8) -> u32 {
    (0x29 << 24) | (((width_h as u32) & 0x3) << 2) | ((height_h as u32) & 0x3)
}

#[inline]
pub fn scissor_xy(x: u16, y: u16) -> u32 {
    (0x1B << 24) | (((x as u32) & 0x7FF) << 11) | ((y as u32) & 0x7FF)
}

#[inline]
pub fn scissor_size(w: u16, h: u16) -> u32 {
    (0x1C << 24) | (((w as u32) & 0xFFF) << 12) | ((h as u32) & 0xFFF)
}

// Primitive types
pub const BITMAPS: u8    = 1;
pub const POINTS: u8     = 2;
pub const LINES: u8      = 3;
pub const LINE_STRIP: u8 = 4;
pub const RECTS: u8      = 9;

// Bitmap formats
pub const RGB565: u8 = 7;

// DL swap modes
pub const DLSWAP_FRAME: u8 = 2;

// SPI frequency
pub const SPI_FREQ: u32 = 15_000_000; // 15 MHz

// ═══════════════════════════════════════════════════════════════════════════════
// PARTNER WINDOW DRIVER
// ═══════════════════════════════════════════════════════════════════════════════

/// Error type for partner window operations.
#[derive(Debug)]
pub enum PwError {
    /// FTDI/SPI communication error
    Spi(String),
    /// GPIO error
    Gpio(String),
    /// EVE chip not responding
    ChipNotResponding(u8),
    /// Coprocessor timeout
    CoprocTimeout { rd: u16, wr: u16 },
    /// Command buffer full
    CmdBufferFull { needed: usize, available: usize },
}

impl std::fmt::Display for PwError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            PwError::Spi(e) => write!(f, "SPI error: {e}"),
            PwError::Gpio(e) => write!(f, "GPIO error: {e}"),
            PwError::ChipNotResponding(id) => {
                write!(f, "EVE chip not responding (REG_ID=0x{id:02X}, expected 0x7C)")
            }
            PwError::CoprocTimeout { rd, wr } => {
                write!(f, "Coprocessor timeout (RD=0x{rd:04X}, WR=0x{wr:04X})")
            }
            PwError::CmdBufferFull { needed, available } => {
                write!(f, "CMD buffer full (need {needed}, have {available})")
            }
        }
    }
}

impl std::error::Error for PwError {}

/// Partner Window display driver.
///
/// Uses `ftdi-embedded-hal` for proper FTDI MPSSE/SPI handling — all the
/// gnarly USB control transfers, buffer purges, MPSSE sync, and CS management
/// are handled correctly by the HAL layer.
pub struct PartnerWindow<Spi, PdPin> {
    spi: Spi,
    pd_pin: PdPin,
    cmd_write_ptr: u16,
    dl_offset: u32,
}

impl<Spi, PdPin, SpiErr, PinErr> PartnerWindow<Spi, PdPin>
where
    Spi: SpiDevice<u8, Error = SpiErr>,
    PdPin: OutputPin<Error = PinErr>,
    SpiErr: std::fmt::Debug,
    PinErr: std::fmt::Debug,
{
    /// Create a new PartnerWindow from pre-configured SPI device and PD# GPIO pin.
    pub fn new(spi: Spi, pd_pin: PdPin) -> Self {
        Self {
            spi,
            pd_pin,
            cmd_write_ptr: 0,
            dl_offset: 0,
        }
    }

    // ─── Low-level SPI ──────────────────────────────────────────────────

    /// Send a 3-byte EVE host command.
    pub fn host_cmd(&mut self, cmd: u8) -> Result<(), PwError> {
        self.spi
            .write(&[cmd, 0x00, 0x00])
            .map_err(|e| PwError::Spi(format!("{e:?}")))
    }

    /// Write 8-bit value to EVE register/memory address.
    pub fn wr8(&mut self, addr: u32, value: u8) -> Result<(), PwError> {
        self.spi
            .write(&[
                0x80 | ((addr >> 16) as u8 & 0x3F),
                (addr >> 8) as u8,
                addr as u8,
                value,
            ])
            .map_err(|e| PwError::Spi(format!("{e:?}")))
    }

    /// Write 16-bit value (little-endian) to EVE register/memory.
    pub fn wr16(&mut self, addr: u32, value: u16) -> Result<(), PwError> {
        self.spi
            .write(&[
                0x80 | ((addr >> 16) as u8 & 0x3F),
                (addr >> 8) as u8,
                addr as u8,
                value as u8,
                (value >> 8) as u8,
            ])
            .map_err(|e| PwError::Spi(format!("{e:?}")))
    }

    /// Write 32-bit value (little-endian) to EVE register/memory.
    pub fn wr32(&mut self, addr: u32, value: u32) -> Result<(), PwError> {
        self.spi
            .write(&[
                0x80 | ((addr >> 16) as u8 & 0x3F),
                (addr >> 8) as u8,
                addr as u8,
                value as u8,
                (value >> 8) as u8,
                (value >> 16) as u8,
                (value >> 24) as u8,
            ])
            .map_err(|e| PwError::Spi(format!("{e:?}")))
    }

    /// Write a block of bytes to EVE memory starting at addr.
    pub fn wr_bulk(&mut self, addr: u32, data: &[u8]) -> Result<(), PwError> {
        let mut buf = Vec::with_capacity(3 + data.len());
        buf.push(0x80 | ((addr >> 16) as u8 & 0x3F));
        buf.push((addr >> 8) as u8);
        buf.push(addr as u8);
        buf.extend_from_slice(data);
        self.spi
            .write(&buf)
            .map_err(|e| PwError::Spi(format!("{e:?}")))
    }

    /// Read 8-bit value from EVE register/memory.
    pub fn rd8(&mut self, addr: u32) -> Result<u8, PwError> {
        let write_buf = [
            (addr >> 16) as u8 & 0x3F,
            (addr >> 8) as u8,
            addr as u8,
            0x00, // dummy byte
        ];
        let mut read_buf = [0u8; 1];
        self.spi
            .transaction(&mut [
                Operation::Write(&write_buf),
                Operation::Read(&mut read_buf),
            ])
            .map_err(|e| PwError::Spi(format!("{e:?}")))?;
        Ok(read_buf[0])
    }

    /// Read 16-bit value (little-endian) from EVE register/memory.
    pub fn rd16(&mut self, addr: u32) -> Result<u16, PwError> {
        let write_buf = [
            (addr >> 16) as u8 & 0x3F,
            (addr >> 8) as u8,
            addr as u8,
            0x00,
        ];
        let mut read_buf = [0u8; 2];
        self.spi
            .transaction(&mut [
                Operation::Write(&write_buf),
                Operation::Read(&mut read_buf),
            ])
            .map_err(|e| PwError::Spi(format!("{e:?}")))?;
        Ok(u16::from_le_bytes(read_buf))
    }

    /// Read 32-bit value (little-endian) from EVE register/memory.
    pub fn rd32(&mut self, addr: u32) -> Result<u32, PwError> {
        let write_buf = [
            (addr >> 16) as u8 & 0x3F,
            (addr >> 8) as u8,
            addr as u8,
            0x00,
        ];
        let mut read_buf = [0u8; 4];
        self.spi
            .transaction(&mut [
                Operation::Write(&write_buf),
                Operation::Read(&mut read_buf),
            ])
            .map_err(|e| PwError::Spi(format!("{e:?}")))?;
        Ok(u32::from_le_bytes(read_buf))
    }

    // ─── EVE Power Control ──────────────────────────────────────────────

    /// Power on the EVE chip by driving PD_N pin HIGH.
    pub fn power_on(&mut self) -> Result<(), PwError> {
        self.pd_pin
            .set_high()
            .map_err(|e| PwError::Gpio(format!("{e:?}")))?;
        thread::sleep(Duration::from_millis(50));
        println!("[+] EVE PD_N → HIGH (powered on)");
        Ok(())
    }

    /// Power off the EVE chip by driving PD_N pin LOW.
    pub fn power_off(&mut self) -> Result<(), PwError> {
        self.pd_pin
            .set_low()
            .map_err(|e| PwError::Gpio(format!("{e:?}")))?;
        thread::sleep(Duration::from_millis(20));
        println!("[+] EVE PD_N → LOW (powered off)");
        Ok(())
    }

    // ─── EVE Initialization ─────────────────────────────────────────────

    /// Full EVE initialization sequence — matches the working Python reference exactly.
    pub fn init(&mut self) -> Result<(), PwError> {
        println!("\n=== Initializing Partner Window ===");

        // Step 1: Power on
        self.power_on()?;

        // Step 2: Reset EVE CPU + coprocessor ring buffer pointers
        self.wr8(REG_CPURESET, 0x01)?;
        self.wr16(REG_CMD_READ, 0x0000)?;
        self.wr16(REG_CMD_WRITE, 0x0000)?;
        self.wr8(REG_CPURESET, 0x00)?;
        thread::sleep(Duration::from_millis(20));
        println!("[+] CPU reset complete");

        // Step 3: Host commands — external clock + wake up
        self.host_cmd(HOST_CLKEXT)?;
        self.host_cmd(HOST_ACTIVE)?;
        thread::sleep(Duration::from_millis(300));

        // Step 4: Poll REG_ID — should read 0x7C when chip is alive
        let chip_id = self.poll_reg_id(Duration::from_secs(2))?;
        println!(
            "[+] REG_ID = 0x{:02X} {}",
            chip_id,
            if chip_id == 0x7C { "✓" } else { "✗ UNEXPECTED" }
        );
        if chip_id != 0x7C {
            return Err(PwError::ChipNotResponding(chip_id));
        }

        // Step 5: Program display timing registers
        self.wr16(REG_HCYCLE, HCYCLE)?;
        self.wr16(REG_HOFFSET, HOFFSET)?;
        self.wr16(REG_HSYNC0, HSYNC0)?;
        self.wr16(REG_HSYNC1, HSYNC1)?;
        self.wr16(REG_VCYCLE, VCYCLE)?;
        self.wr16(REG_VOFFSET, VOFFSET)?;
        self.wr16(REG_VSYNC0, VSYNC0)?;
        self.wr16(REG_VSYNC1, VSYNC1)?;
        self.wr16(REG_SWIZZLE, SWIZZLE_VAL)?;
        self.wr8(REG_PCLK_POL, PCLK_POL_VAL)?;
        self.wr16(REG_HSIZE, DISPLAY_WIDTH)?;
        self.wr16(REG_VSIZE, DISPLAY_HEIGHT)?;
        self.wr8(REG_CSPREAD, CSPREAD_VAL)?;
        self.wr8(REG_DITHER, DITHER_VAL)?;
        self.wr8(REG_ROTATE, ROTATE_VAL as u8)?;
        println!(
            "[+] Display timings: {}×{}, PCLK=sys/{}, rotate={}",
            DISPLAY_WIDTH, DISPLAY_HEIGHT, PCLK_DIV, ROTATE_VAL
        );

        // Step 6: Write initial display list (clear screen to black)
        self.dl_start();
        self.dl_word(clear_color_rgb(0, 0, 0))?;
        self.dl_word(clear(true, true, true))?;
        self.dl_word(display())?;
        self.dl_swap()?;
        println!("[+] Initial display list written (black screen)");

        // Step 7: Enable GPIO for display backlight
        let gpio_dir = self.rd8(REG_GPIO_DIR)?;
        self.wr8(REG_GPIO_DIR, gpio_dir | 0x80)?;
        self.wr8(REG_PCLK, PCLK_DIV)?;
        let gpio_val = self.rd8(REG_GPIO)?;
        self.wr8(REG_GPIO, gpio_val | 0x80)?;
        println!("[+] Pixel clock started, backlight ON");

        // Step 8: Tell coprocessor about the rotation
        self.cmd_begin()?;
        self.cmd_word(CMD_SETROTATE)?;
        self.cmd_word(ROTATE_VAL)?;
        self.cmd_end()?;
        self.cmd_wait(Duration::from_secs(2))?;

        // Confirm
        let freq = self.rd32(REG_FREQUENCY)?;
        println!("[+] EVE system clock: {:.1} MHz", freq as f64 / 1e6);
        println!("=== Initialization complete ===\n");
        Ok(())
    }

    /// Poll REG_ID until it returns 0x7C or timeout.
    fn poll_reg_id(&mut self, timeout: Duration) -> Result<u8, PwError> {
        let deadline = Instant::now() + timeout;
        while Instant::now() < deadline {
            match self.rd8(REG_ID) {
                Ok(0x7C) => return Ok(0x7C),
                Ok(_) => {}
                Err(_) => {}
            }
            thread::sleep(Duration::from_millis(50));
        }
        self.rd8(REG_ID)
    }

    // ─── Display List Direct Writes ─────────────────────────────────────

    /// Reset display list write offset to beginning of RAM_DL.
    pub fn dl_start(&mut self) {
        self.dl_offset = 0;
    }

    /// Write a 32-bit display list command word.
    pub fn dl_word(&mut self, word: u32) -> Result<(), PwError> {
        self.wr32(RAM_DL + self.dl_offset, word)?;
        self.dl_offset += 4;
        Ok(())
    }

    /// Trigger a display list swap (present the new frame).
    pub fn dl_swap(&mut self) -> Result<(), PwError> {
        self.wr8(REG_DLSWAP, DLSWAP_FRAME)
    }

    // ─── Coprocessor Command Ring Buffer ────────────────────────────────

    /// Start building a coprocessor command sequence.
    pub fn cmd_begin(&mut self) -> Result<(), PwError> {
        self.cmd_write_ptr = self.rd16(REG_CMD_WRITE)?;
        Ok(())
    }

    /// Append a 32-bit word to the coprocessor command buffer.
    pub fn cmd_word(&mut self, word: u32) -> Result<(), PwError> {
        self.wr32(RAM_CMD + self.cmd_write_ptr as u32, word)?;
        self.cmd_write_ptr = (self.cmd_write_ptr + 4) % 4096;
        Ok(())
    }

    /// Finalize and submit the coprocessor command sequence.
    pub fn cmd_end(&mut self) -> Result<(), PwError> {
        self.wr16(REG_CMD_WRITE, self.cmd_write_ptr)
    }

    /// Wait for the coprocessor to finish processing all commands.
    pub fn cmd_wait(&mut self, timeout: Duration) -> Result<(), PwError> {
        let deadline = Instant::now() + timeout;
        while Instant::now() < deadline {
            let rd = self.rd16(REG_CMD_READ)?;
            let wr = self.rd16(REG_CMD_WRITE)?;
            if rd == wr {
                return Ok(());
            }
            thread::sleep(Duration::from_millis(10));
        }
        let rd = self.rd16(REG_CMD_READ)?;
        let wr = self.rd16(REG_CMD_WRITE)?;
        Err(PwError::CoprocTimeout { rd, wr })
    }

    /// Write a NUL-terminated, 4-byte-aligned string to cmd buffer.
    pub fn cmd_string(&mut self, text: &str) -> Result<(), PwError> {
        let mut bytes: Vec<u8> = text.as_bytes().to_vec();
        bytes.push(0x00);
        while bytes.len() % 4 != 0 {
            bytes.push(0x00);
        }
        for chunk in bytes.chunks(4) {
            let word = u32::from_le_bytes([chunk[0], chunk[1], chunk[2], chunk[3]]);
            self.cmd_word(word)?;
        }
        Ok(())
    }

    /// Wait until there's enough space in the command ring buffer.
    fn wait_cmd_space(&mut self, needed: usize, timeout: Duration) -> Result<(), PwError> {
        let deadline = Instant::now() + timeout;
        loop {
            let rd = self.rd16(REG_CMD_READ)? as usize;
            let wr = self.rd16(REG_CMD_WRITE)? as usize;
            let free = (rd.wrapping_sub(wr).wrapping_sub(4)) % 4096;
            if free >= needed {
                return Ok(());
            }
            if Instant::now() >= deadline {
                return Err(PwError::CmdBufferFull {
                    needed,
                    available: free,
                });
            }
            thread::sleep(Duration::from_millis(5));
        }
    }

    // ─── High-Level Display Operations ──────────────────────────────────

    /// Clear the display to a solid color.
    pub fn clear_screen(&mut self, r: u8, g: u8, b: u8) -> Result<(), PwError> {
        self.dl_start();
        self.dl_word(clear_color_rgb(r, g, b))?;
        self.dl_word(clear(true, true, true))?;
        self.dl_word(display())?;
        self.dl_swap()
    }

    /// Display text using the EVE coprocessor's built-in ROM fonts.
    pub fn display_text(
        &mut self,
        text: &str,
        x: Option<i16>,
        y: Option<i16>,
        font: i16,
        color: (u8, u8, u8),
    ) -> Result<(), PwError> {
        let px = x.unwrap_or(DISPLAY_WIDTH as i16 / 2);
        let py = y.unwrap_or(DISPLAY_HEIGHT as i16 / 2);
        let options: u16 = if x.is_none() && y.is_none() {
            0x0600 // OPT_CENTER
        } else {
            0
        };

        self.cmd_begin()?;
        self.cmd_word(CMD_DLSTART)?;
        self.cmd_word(clear_color_rgb(0, 0, 0))?;
        self.cmd_word(clear(true, true, true))?;
        self.cmd_word(color_rgb(color.0, color.1, color.2))?;
        // CMD_TEXT: x(16), y(16), font(16), options(16), string
        self.cmd_word(CMD_TEXT)?;
        self.cmd_word(u32::from_le_bytes([
            px as u8,
            (px >> 8) as u8,
            py as u8,
            (py >> 8) as u8,
        ]))?;
        self.cmd_word(u32::from_le_bytes([
            font as u8,
            (font >> 8) as u8,
            options as u8,
            (options >> 8) as u8,
        ]))?;
        self.cmd_string(text)?;
        self.cmd_word(display())?;
        self.cmd_word(CMD_SWAP)?;
        self.cmd_end()?;
        self.cmd_wait(Duration::from_secs(2))
    }

    /// Display a number using the EVE coprocessor.
    pub fn display_number(
        &mut self,
        number: u32,
        x: Option<i16>,
        y: Option<i16>,
        font: i16,
        color: (u8, u8, u8),
    ) -> Result<(), PwError> {
        let px = x.unwrap_or(DISPLAY_WIDTH as i16 / 2);
        let py = y.unwrap_or(DISPLAY_HEIGHT as i16 / 2);
        let options: u16 = 0x0600;

        self.cmd_begin()?;
        self.cmd_word(CMD_DLSTART)?;
        self.cmd_word(clear_color_rgb(0, 0, 0))?;
        self.cmd_word(clear(true, true, true))?;
        self.cmd_word(color_rgb(color.0, color.1, color.2))?;
        self.cmd_word(CMD_NUMBER)?;
        self.cmd_word(u32::from_le_bytes([
            px as u8,
            (px >> 8) as u8,
            py as u8,
            (py >> 8) as u8,
        ]))?;
        self.cmd_word(u32::from_le_bytes([
            font as u8,
            (font >> 8) as u8,
            options as u8,
            (options >> 8) as u8,
        ]))?;
        self.cmd_word(number)?;
        self.cmd_word(display())?;
        self.cmd_word(CMD_SWAP)?;
        self.cmd_end()?;
        self.cmd_wait(Duration::from_secs(2))
    }

    /// Upload and display an RGB565 bitmap (raw or zlib-compressed).
    pub fn display_bitmap_rgb565(
        &mut self,
        data: &[u8],
        width: u16,
        height: u16,
        dest: u32,
    ) -> Result<(), PwError> {
        let expected_raw = (width as usize) * (height as usize) * 2;
        let stride = width * 2;

        // Detect zlib compression (check zlib header)
        let is_compressed = data.len() >= 2
            && ((data[0] as u16) * 256 + data[1] as u16) % 31 == 0
            && data.len() < expected_raw;

        if is_compressed {
            self.cmd_inflate(dest, data)?;
        } else {
            let to_write = if data.len() > expected_raw {
                &data[..expected_raw]
            } else {
                data
            };
            self.wr_bulk(dest, to_write)?;
        }

        // Build display list to show the bitmap fullscreen
        self.cmd_begin()?;
        self.cmd_word(CMD_DLSTART)?;
        self.cmd_word(clear_color_rgb(0, 0, 0))?;
        self.cmd_word(clear(true, true, true))?;
        self.cmd_word(bitmap_handle(0))?;
        self.cmd_word(bitmap_source(dest))?;
        self.cmd_word(bitmap_layout(RGB565, stride & 0x3FF, height & 0x1FF))?;
        self.cmd_word(bitmap_layout_h(
            ((stride >> 10) & 0x3) as u8,
            ((height >> 9) & 0x3) as u8,
        ))?;
        self.cmd_word(bitmap_size(
            false,
            false,
            false,
            width & 0x1FF,
            height & 0x1FF,
        ))?;
        self.cmd_word(bitmap_size_h(
            ((width >> 9) & 0x3) as u8,
            ((height >> 9) & 0x3) as u8,
        ))?;
        self.cmd_word(begin(BITMAPS))?;
        self.cmd_word(vertex2ii(0, 0, 0, 0))?;
        self.cmd_word(end())?;
        self.cmd_word(display())?;
        self.cmd_word(CMD_SWAP)?;
        self.cmd_end()?;
        self.cmd_wait(Duration::from_secs(2))
    }

    /// Send CMD_INFLATE to decompress zlib data into EVE RAM_G.
    fn cmd_inflate(&mut self, dest: u32, compressed_data: &[u8]) -> Result<(), PwError> {
        // Pad to 4-byte alignment
        let mut padded = compressed_data.to_vec();
        while padded.len() % 4 != 0 {
            padded.push(0x00);
        }

        // Write CMD_INFLATE command + destination address
        self.cmd_begin()?;
        self.cmd_word(CMD_INFLATE)?;
        self.cmd_word(dest)?;

        // Write compressed data in chunks that fit the 4KB ring buffer
        const CHUNK: usize = 2048;
        let mut offset = 0;
        while offset < padded.len() {
            let end = (offset + CHUNK).min(padded.len());
            let chunk = &padded[offset..end];
            self.wait_cmd_space(chunk.len() + 8, Duration::from_secs(2))?;
            for word_bytes in chunk.chunks(4) {
                let word = u32::from_le_bytes([
                    word_bytes[0],
                    word_bytes[1],
                    word_bytes[2],
                    word_bytes[3],
                ]);
                self.cmd_word(word)?;
            }
            self.cmd_end()?;
            thread::sleep(Duration::from_millis(1));
            self.cmd_write_ptr = self.rd16(REG_CMD_WRITE)?;
            offset += CHUNK;
        }

        self.cmd_end()?;
        self.cmd_wait(Duration::from_secs(5))
    }

    // ─── Shutdown ───────────────────────────────────────────────────────

    /// Cleanly shut down the partner window display.
    pub fn shutdown(&mut self) -> Result<(), PwError> {
        println!("\n=== Shutting down ===");

        // Clear screen
        self.clear_screen(0, 0, 0)?;
        thread::sleep(Duration::from_millis(50));

        // Turn off backlight
        let gpio_val = self.rd8(REG_GPIO)?;
        self.wr8(REG_GPIO, gpio_val & 0x7F)?;

        // Stop pixel clock
        self.wr8(REG_PCLK, 0)?;
        println!("[+] Pixel clock stopped, backlight off");

        // Power down EVE
        self.power_off()?;
        println!("=== Shutdown complete ===\n");
        Ok(())
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// CONVENIENCE CONSTRUCTOR (ftdi backend)
// ═══════════════════════════════════════════════════════════════════════════════

/// Open the FTDI FT232H device and return a ready-to-use PartnerWindow.
///
/// Uses the open-source `ftdi` (libftdi) backend.
/// CS is on D3, PD_N is on ADBUS6 (D6).
pub fn open_ftdi() -> Result<
    PartnerWindow<
        hal::SpiDevice<ftdi::Device>,
        hal::OutputPin<ftdi::Device>,
    >,
    Box<dyn std::error::Error>,
> {
    let device = ftdi::find_by_vid_pid(0x0403, 0x6014)
        .interface(ftdi::Interface::A)
        .open()?;

    let hal = hal::FtHal::init_freq(device, SPI_FREQ)?;
    let spi = hal.spi_device(3)?; // D3 = CS0
    let pd_pin = hal.ad6()?;       // ADBUS6 = PD_N (D6)

    println!("[+] FTDI FT232H connected via libftdi");
    println!("    SPI frequency: {:.1} MHz", SPI_FREQ as f64 / 1e6);

    Ok(PartnerWindow::new(spi, pd_pin))
}

/// Check if an FTDI FT232H device is currently connected.
pub fn is_device_connected() -> bool {
    ftdi::find_by_vid_pid(0x0403, 0x6014)
        .interface(ftdi::Interface::A)
        .open()
        .is_ok()
}

// ═══════════════════════════════════════════════════════════════════════════════
// TEST FUNCTIONS
// ═══════════════════════════════════════════════════════════════════════════════

/// Cycle through solid colors.
pub fn test_clear_colors<S, P, Se, Pe>(pw: &mut PartnerWindow<S, P>) -> Result<(), PwError>
where
    S: SpiDevice<u8, Error = Se>,
    P: OutputPin<Error = Pe>,
    Se: std::fmt::Debug,
    Pe: std::fmt::Debug,
{
    println!("\n--- Test: Color Cycle ---");
    let colors: &[(u8, u8, u8, &str)] = &[
        (255, 0, 0, "Red"),
        (0, 255, 0, "Green"),
        (0, 0, 255, "Blue"),
        (255, 255, 255, "White"),
        (255, 255, 0, "Yellow"),
        (255, 0, 255, "Magenta"),
        (0, 255, 255, "Cyan"),
        (0, 0, 0, "Black"),
    ];
    for &(r, g, b, name) in colors {
        println!("  Showing: {name} ({r}, {g}, {b})");
        pw.clear_screen(r, g, b)?;
        thread::sleep(Duration::from_secs(1));
    }
    println!("  Done!");
    Ok(())
}

/// Display text using EVE ROM fonts.
pub fn test_text<S, P, Se, Pe>(
    pw: &mut PartnerWindow<S, P>,
    message: &str,
) -> Result<(), PwError>
where
    S: SpiDevice<u8, Error = Se>,
    P: OutputPin<Error = Pe>,
    Se: std::fmt::Debug,
    Pe: std::fmt::Debug,
{
    println!("\n--- Test: Text Display ---");
    println!("  Showing: \"{message}\"");
    pw.display_text(message, None, None, 31, (255, 255, 255))?;
    thread::sleep(Duration::from_secs(2));

    let tests: &[(i16, (u8, u8, u8), &str)] = &[
        (28, (200, 200, 200), "Font 28: Small text"),
        (30, (0, 255, 128), "Font 30: Green text"),
        (31, (255, 200, 0), "Font 31: Yellow!"),
    ];
    for &(font, color, text) in tests {
        println!("  Font {font}: \"{text}\"");
        pw.display_text(text, None, None, font, color)?;
        thread::sleep(Duration::from_millis(1500));
    }
    Ok(())
}

/// Draw geometric shapes using display list primitives.
pub fn test_geometry<S, P, Se, Pe>(pw: &mut PartnerWindow<S, P>) -> Result<(), PwError>
where
    S: SpiDevice<u8, Error = Se>,
    P: OutputPin<Error = Pe>,
    Se: std::fmt::Debug,
    Pe: std::fmt::Debug,
{
    println!("\n--- Test: Geometry ---");

    pw.cmd_begin()?;
    pw.cmd_word(CMD_DLSTART)?;
    pw.cmd_word(clear_color_rgb(0, 0, 0))?;
    pw.cmd_word(clear(true, true, true))?;

    // White border rectangle
    pw.cmd_word(color_rgb(255, 255, 255))?;
    pw.cmd_word(line_width(16))?;
    pw.cmd_word(begin(RECTS))?;
    pw.cmd_word(vertex2f(2 * 16, 2 * 16))?;
    pw.cmd_word(vertex2f(478 * 16, 126 * 16))?;
    pw.cmd_word(end())?;

    // Red circle
    pw.cmd_word(color_rgb(255, 0, 0))?;
    pw.cmd_word(point_size(40 * 16))?;
    pw.cmd_word(begin(POINTS))?;
    pw.cmd_word(vertex2f(120 * 16, 64 * 16))?;
    pw.cmd_word(end())?;

    // Green circle
    pw.cmd_word(color_rgb(0, 255, 0))?;
    pw.cmd_word(point_size(40 * 16))?;
    pw.cmd_word(begin(POINTS))?;
    pw.cmd_word(vertex2f(240 * 16, 64 * 16))?;
    pw.cmd_word(end())?;

    // Blue circle
    pw.cmd_word(color_rgb(0, 0, 255))?;
    pw.cmd_word(point_size(40 * 16))?;
    pw.cmd_word(begin(POINTS))?;
    pw.cmd_word(vertex2f(360 * 16, 64 * 16))?;
    pw.cmd_word(end())?;

    pw.cmd_word(display())?;
    pw.cmd_word(CMD_SWAP)?;
    pw.cmd_end()?;
    pw.cmd_wait(Duration::from_secs(2))?;
    thread::sleep(Duration::from_secs(3));
    Ok(())
}

/// Display a test pattern (vertical color bars).
pub fn test_pattern<S, P, Se, Pe>(pw: &mut PartnerWindow<S, P>) -> Result<(), PwError>
where
    S: SpiDevice<u8, Error = Se>,
    P: OutputPin<Error = Pe>,
    Se: std::fmt::Debug,
    Pe: std::fmt::Debug,
{
    println!("\n--- Test: Color Bar Pattern ---");

    let w = DISPLAY_WIDTH as usize;
    let h = DISPLAY_HEIGHT as usize;
    let mut data = vec![0u8; w * h * 2];
    let bar_width = w / 8;

    let colors_565: &[u16] = &[
        0xF800, // Red
        0x07E0, // Green
        0x001F, // Blue
        0xFFFF, // White
        0xFFE0, // Yellow
        0xF81F, // Magenta
        0x07FF, // Cyan
        0x0000, // Black
    ];

    for y in 0..h {
        for x in 0..w {
            let bar = (x / bar_width).min(7);
            let pixel = colors_565[bar];
            let idx = (y * w + x) * 2;
            data[idx] = pixel as u8;
            data[idx + 1] = (pixel >> 8) as u8;
        }
    }

    let compressed = miniz_oxide::deflate::compress_to_vec_zlib(&data, 9);
    println!(
        "  Pattern: {w}x{h}, {} B -> {} B compressed",
        data.len(),
        compressed.len()
    );
    pw.display_bitmap_rgb565(&compressed, DISPLAY_WIDTH, DISPLAY_HEIGHT, RAM_G)?;
    thread::sleep(Duration::from_secs(3));
    Ok(())
}

/// Simple animation — scrolling text.
pub fn test_animation<S, P, Se, Pe>(pw: &mut PartnerWindow<S, P>) -> Result<(), PwError>
where
    S: SpiDevice<u8, Error = Se>,
    P: OutputPin<Error = Pe>,
    Se: std::fmt::Debug,
    Pe: std::fmt::Debug,
{
    println!("\n--- Test: Animation ---");
    let msg = "Tobii Partner Window";
    let mut x = DISPLAY_WIDTH as i16;
    let end_x = -(msg.len() as i16 * 20);
    while x > end_x {
        pw.display_text(msg, Some(x), Some(DISPLAY_HEIGHT as i16 / 2), 31, (255, 255, 255))?;
        thread::sleep(Duration::from_millis(30));
        x -= 4;
    }
    Ok(())
}

/// Run all tests.
pub fn run_all_tests<S, P, Se, Pe>(pw: &mut PartnerWindow<S, P>) -> Result<(), PwError>
where
    S: SpiDevice<u8, Error = Se>,
    P: OutputPin<Error = Pe>,
    Se: std::fmt::Debug,
    Pe: std::fmt::Debug,
{
    println!("\n+---------------------------------------+");
    println!("|  Partner Window Full Test Suite       |");
    println!("+---------------------------------------+");

    test_clear_colors(pw)?;
    test_text(pw, "Hello from Linux!")?;
    test_geometry(pw)?;
    test_pattern(pw)?;
    test_animation(pw)?;

    println!("\n[+] All tests complete!");
    Ok(())
}
