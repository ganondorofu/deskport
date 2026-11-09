//! 共有ファイルから、最新の1枚を読む(読み手。lib.rs の冒頭に形式がある)。
use std::fs::File;
use std::os::windows::fs::FileExt;
use std::time::{SystemTime, UNIX_EPOCH};

pub const WIDTH: u32 = 1280;
pub const HEIGHT: u32 = 720;
pub const HEADER: usize = 32;
pub const PATH: &str = r"C:\ProgramData\DeskportVCam\frame.bin";
pub const MAGIC: u32 = 0x4D43_5644;

pub struct FrameReader {
    file: Option<File>,
    last_open_try: std::time::Instant,
}

impl FrameReader {
    pub fn new() -> Self { Self { file: None, last_open_try: std::time::Instant::now() - std::time::Duration::from_secs(10) } }

    /// 最新の1枚を、buf(幅×高さ×4 バイトの BGRA)へ書く。新しい映像がなければ、暗い灰色にして false を返す。
    pub fn read(&mut self, buf: &mut [u8]) -> bool {
        if self.file.is_none() && self.last_open_try.elapsed() > std::time::Duration::from_millis(500) {
            self.last_open_try = std::time::Instant::now();
            self.file = File::open(PATH).ok();
        }
        if let Some(f) = &self.file {
            if let Some(()) = Self::try_read(f, buf) { return true; }
        }
        for px in buf.chunks_exact_mut(4) { px[0] = 0x28; px[1] = 0x28; px[2] = 0x28; px[3] = 0xFF; }
        false
    }

    fn header(f: &File) -> Option<(u32, u32, u64, u64)> {
        let mut h = [0u8; HEADER];
        if f.seek_read(&mut h, 0).ok()? != HEADER { return None; }
        let magic = u32::from_le_bytes(h[0..4].try_into().ok()?);
        if magic != MAGIC { return None; }
        let w = u32::from_le_bytes(h[4..8].try_into().ok()?);
        let hh = u32::from_le_bytes(h[8..12].try_into().ok()?);
        let seq = u64::from_le_bytes(h[16..24].try_into().ok()?);
        let ts = u64::from_le_bytes(h[24..32].try_into().ok()?);
        Some((w, hh, seq, ts))
    }

    fn try_read(f: &File, buf: &mut [u8]) -> Option<()> {
        for _ in 0..3 {
            let (w, h, seq1, ts) = Self::header(f)?;
            if w != WIDTH || h != HEIGHT || seq1 % 2 == 1 { std::thread::sleep(std::time::Duration::from_millis(1)); continue; }
            let now = SystemTime::now().duration_since(UNIX_EPOCH).ok()?.as_millis() as u64;
            if now.saturating_sub(ts) > 1000 { return None; }   // 更新が止まっている
            let n = (WIDTH * HEIGHT * 4) as usize;
            if buf.len() < n { return None; }
            if f.seek_read(&mut buf[..n], HEADER as u64).ok()? != n { return None; }
            let (_, _, seq2, _) = Self::header(f)?;
            if seq1 == seq2 { return Some(()); }   // 読んでいる間に書き換わっていなければ、有効
        }
        None
    }
}
