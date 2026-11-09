//! PC の再生音を端末へ送る(WASAPI ループバック)。
//!
//! Windows の設定は一切変えない: 既定の再生デバイスが鳴らしている音を、横から読むだけ。
//! 配信が終わる(または音声がオフ)と、取り込みを止めて、デバイスを手放す。
//! 音量は端末側で調整する(PC の音量は触らない)。
//!
//! パケット(ホスト→端末、暗号化は他のパケットと同じ):
//!   0x08 [seq:u32][Opus のフレーム]   48kHz ステレオ、10ms ごと(約 96 kbps)。端末が Opus に対応しているとき
//!   0x07 [seq:u32][サンプルレート:u32][チャンネル数:u8=2][16bit PCM(LR交互)…]   圧縮しない形(約 1.5 Mbps)。古い端末向け
//! 無音の間は送らない(端末側で、足りない分は無音にする)。
use crate::crypto::Sealer;
use std::net::{SocketAddr, UdpSocket};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::Duration;
use windows::core::Result;
use windows::Win32::Media::Audio::*;
use windows::Win32::System::Com::*;

/// PCM で送るときの、1パケットのフレーム数(48kHz なら5ms)。
const FRAMES_PER_PACKET: usize = 240;
/// Opus の1フレームの長さ(48kHz で 10ms)。
const OPUS_FRAME: usize = 480;
const OPUS_RATE: u32 = 48_000;

/// 取り込みスレッドを止めるための印。Drop で止まる。
pub struct Guard(Arc<AtomicBool>);
impl Drop for Guard {
    fn drop(&mut self) { self.0.store(true, Ordering::SeqCst); }
}

/// 取り込みを始める。返した Guard を手放す(または drop する)と止まる。opus=true なら Opus で送る。
pub fn start(sock: UdpSocket, client: SocketAddr, tx: Sealer, opus: bool) -> Guard {
    let stop = Arc::new(AtomicBool::new(false));
    let s2 = stop.clone();
    std::thread::spawn(move || run(sock, client, tx, opus, s2));
    Guard(stop)
}

/// デバイスの音を、48kHz に直す(線形補間)。Opus は、48kHz などの決まった周波数しか受けられないため。
struct Resampler {
    pos: f64,                 // 次に出す点の、入力上の位置
    frames: Vec<[i16; 2]>,    // まだ使い終わっていない入力
}
impl Resampler {
    fn new() -> Self { Self { pos: 0.0, frames: Vec::new() } }
    /// 入力(デバイスの周波数 from)を足して、48kHz のフレームを out に追加する。
    fn push(&mut self, input: &[[i16; 2]], from: u32, out: &mut Vec<i16>) {
        self.frames.extend_from_slice(input);
        let step = from as f64 / OPUS_RATE as f64;
        while (self.pos as usize) + 1 < self.frames.len() {
            let i = self.pos as usize;
            let f = self.pos - i as f64;
            for c in 0..2 {
                let a = self.frames[i][c] as f64;
                let b = self.frames[i + 1][c] as f64;
                out.push((a + (b - a) * f) as i16);
            }
            self.pos += step;
        }
        let used = self.pos as usize;
        if used > 0 { self.frames.drain(..used); self.pos -= used as f64; }
    }
}

fn run(sock: UdpSocket, client: SocketAddr, mut tx: Sealer, opus: bool, stop: Arc<AtomicBool>) {
    unsafe {
        if CoInitializeEx(None, COINIT_MULTITHREADED).is_err() { return; }
        let mut enc = if opus {
            match opus::Encoder::new(OPUS_RATE, opus::Channels::Stereo, opus::Application::Audio) {
                Ok(mut e) => { let _ = e.set_bitrate(opus::Bitrate::Bits(96_000)); Some(e) }
                Err(e) => { crate::log::logln!("Opus の符号化を始められません({e})。圧縮しない形で送ります"); None }
            }
        } else { None };
        let mut seq: u32 = 0;
        let mut warned = false;
        while !stop.load(Ordering::SeqCst) {
            // 既定のデバイスが変わった、デバイスがない、などで失敗したら、少し待ってやり直す
            if let Err(e) = capture(&sock, client, &mut tx, enc.as_mut(), &stop, &mut seq) {
                if !warned { crate::log::logln!("音声の取り込み: {e}(続けて試します)"); warned = true; }
                std::thread::sleep(Duration::from_secs(1));
            }
        }
        CoUninitialize();
    }
}

unsafe fn capture(sock: &UdpSocket, client: SocketAddr, tx: &mut Sealer, mut enc: Option<&mut opus::Encoder>, stop: &AtomicBool, seq: &mut u32) -> Result<()> {
    let en: IMMDeviceEnumerator = CoCreateInstance(&MMDeviceEnumerator, None, CLSCTX_ALL)?;
    let dev = en.GetDefaultAudioEndpoint(eRender, eConsole)?;
    let ac: IAudioClient = dev.Activate(CLSCTX_ALL, None)?;
    let fmt = ac.GetMixFormat()?;
    let f = *fmt;
    let (rate, ch, bits, tag) = (f.nSamplesPerSec, f.nChannels as usize, f.wBitsPerSample, f.wFormatTag);
    // 拡張形式のときは、サブ形式の先頭の4バイトが 3 なら浮動小数
    let is_float = tag == 3 || (tag == 0xFFFE && {
        let ext = fmt as *const WAVEFORMATEXTENSIBLE;
        std::ptr::read_unaligned(std::ptr::addr_of!((*ext).SubFormat.data1)) == 3
    });
    if !((is_float && bits == 32) || (!is_float && bits == 16)) || ch == 0 {
        CoTaskMemFree(Some(fmt as *const _));
        return Err(windows::core::Error::new(windows::core::HRESULT(0x8000FFFFu32 as i32), "対応していない音声形式です"));
    }
    let init = ac.Initialize(AUDCLNT_SHAREMODE_SHARED, AUDCLNT_STREAMFLAGS_LOOPBACK, 400_000, 0, fmt, None);
    CoTaskMemFree(Some(fmt as *const _));
    init?;
    let cap: IAudioCaptureClient = ac.GetService()?;
    ac.Start()?;
    let use_opus = enc.is_some();
    let mut pcm: Vec<i16> = Vec::with_capacity(OPUS_FRAME * 4);   // ステレオ、LR 交互(Opus なら 48kHz。PCM なら、デバイスの周波数)
    let mut rs = Resampler::new();
    let mut opus_buf = vec![0u8; 1500];
    let result = (|| -> Result<()> {
        while !stop.load(Ordering::SeqCst) {
            std::thread::sleep(Duration::from_millis(4));
            loop {
                if cap.GetNextPacketSize()? == 0 { break; }
                let mut data: *mut u8 = std::ptr::null_mut();
                let (mut frames, mut flags) = (0u32, 0u32);
                cap.GetBuffer(&mut data, &mut frames, &mut flags, None, None)?;
                let silent = flags & (AUDCLNT_BUFFERFLAGS_SILENT.0 as u32) != 0;
                if !silent && !data.is_null() {
                    let n = frames as usize;
                    // デバイスの音を、ステレオの 16bit に直す
                    let mut st: Vec<[i16; 2]> = Vec::with_capacity(n);
                    for i in 0..n {
                        let mut lr = [0i16; 2];
                        for c in 0..2usize {
                            let src = if c < ch { c } else { 0 };
                            let idx = i * ch + src;
                            lr[c] = if is_float {
                                let x = std::ptr::read_unaligned((data as *const f32).add(idx));
                                (x.clamp(-1.0, 1.0) * 32767.0) as i16
                            } else {
                                std::ptr::read_unaligned((data as *const i16).add(idx))
                            };
                        }
                        st.push(lr);
                    }
                    if use_opus && rate != OPUS_RATE { rs.push(&st, rate, &mut pcm); }
                    else { for lr in st { pcm.push(lr[0]); pcm.push(lr[1]); } }
                } else {
                    pcm.clear();   // 無音の間は送らない(前のかけらも捨てる)
                    rs = Resampler::new();
                }
                cap.ReleaseBuffer(frames)?;
                if let Some(e) = enc.as_deref_mut() {
                    while pcm.len() >= OPUS_FRAME * 2 {
                        let chunk: Vec<i16> = pcm.drain(..OPUS_FRAME * 2).collect();
                        if let Ok(len) = e.encode(&chunk, &mut opus_buf) {
                            let mut p = Vec::with_capacity(5 + len);
                            p.push(0x08);
                            p.extend_from_slice(&seq.to_le_bytes());
                            p.extend_from_slice(&opus_buf[..len]);
                            *seq = seq.wrapping_add(1);
                            let _ = sock.send_to(&tx.seal(&p), client);
                        }
                    }
                } else {
                    while pcm.len() >= FRAMES_PER_PACKET * 2 {
                        let mut p = Vec::with_capacity(10 + FRAMES_PER_PACKET * 4);
                        p.push(0x07);
                        p.extend_from_slice(&seq.to_le_bytes());
                        p.extend_from_slice(&rate.to_le_bytes());
                        p.push(2);
                        for s in pcm.drain(..FRAMES_PER_PACKET * 2) { p.extend_from_slice(&s.to_le_bytes()); }
                        *seq = seq.wrapping_add(1);
                        let _ = sock.send_to(&tx.seal(&p), client);
                    }
                }
            }
        }
        Ok(())
    })();
    let _ = ac.Stop();
    result
}
