//! スマホのカメラ映像を、PC のカメラ(仮想カメラ)として、他のアプリに見せる。
//!
//! 流れ: スマホが JPEG にして UDP で送る → ここで組み立てて、デコードして、回転・縮小して(1280x720)、
//!       共有ファイル(C:\ProgramData\DeskportVCam\frame.bin)へ書く → 仮想カメラの DLL(vcam/)が読んで、Windows のカメラとして渡す。
//! 仮想カメラ(Windows 11)は、カメラを有効にしている間、ホストが動いている限り、Windows に現れる(MFCreateVirtualCamera。このプロセスが生きている間だけ存在する)。
//! 映像が来ていないときは、灰色の画面。無効にする(または、ホストを終える)と、消える。Windows には何も残らない。
//!
//! パケット(端末→ホスト): 0x30 [frame:u32][index:u16][count:u16][flags:u8][JPEG の断片]
//!   flags の下位2ビット = 回転(0, 90, 180, 270 度。時計回り)
//! 有効にする/無効にする(DLL の登録。HKLM)は、管理者権限(UAC)が要る。secure.rs と同じ考え方で、PC で操作したときだけ。
use anyhow::{bail, Context, Result};
use std::os::windows::fs::FileExt;
use std::os::windows::process::CommandExt;
use std::sync::mpsc::{sync_channel, SyncSender, TrySendError};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Mutex, OnceLock};
use std::time::{Duration, SystemTime, UNIX_EPOCH};
use windows::core::*;
use windows::Win32::Graphics::Imaging::*;
use windows::Win32::Media::MediaFoundation::*;
use windows::Win32::System::Com::*;
use windows::Win32::System::Registry::*;

const NO_WINDOW: u32 = 0x0800_0000;
pub const WIDTH: u32 = 1280;
pub const HEIGHT: u32 = 720;
const HEADER: usize = 32;
const CLSID: &str = "{6D3A1C52-4B7E-4F0A-9C1D-5E8B2A7F10C3}";
const DATA_DIR: &str = r"C:\ProgramData\DeskportVCam";
const PROGRAM_DIR: &str = r"C:\Program Files\DeskportVCam";
const MAGIC: u32 = 0x4D43_5644;

/// 仮想カメラの DLL(vcam/ をビルドしたもの)。ホストに埋め込んで、有効にするときに取り出す。
static DLL: &[u8] = include_bytes!("../../vcam/target/release/deskport_vcam.dll");

// ---- 状態

/// 仮想カメラの DLL が登録されているか(有効になっているか)。
pub fn installed() -> bool {
    unsafe {
        let sub = HSTRING::from(format!("Software\\Classes\\CLSID\\{CLSID}\\InprocServer32"));
        let mut key = HKEY::default();
        let ok = RegOpenKeyExW(HKEY_LOCAL_MACHINE, &sub, 0, KEY_READ, &mut key).is_ok();
        if ok { let _ = RegCloseKey(key); }
        ok
    }
}

// ---- 管理者権限で動く部分(有効にする/無効にする)

fn cmd(program: &str, args: &[&str]) -> bool {
    std::process::Command::new(program).args(args).creation_flags(NO_WINDOW).output().map_or(false, |o| o.status.success())
}

pub fn setup(what: &str) -> Result<()> {
    let dll = std::path::Path::new(PROGRAM_DIR).join("deskport_vcam.dll");
    match what {
        "enable" => {
            std::fs::create_dir_all(PROGRAM_DIR).context("インストール先を作れません")?;
            let _ = cmd("regsvr32", &["/u", "/s", &dll.display().to_string()]);   // 古い登録を外す
            std::fs::write(&dll, DLL).context("DLL を書き出せません(使用中かもしれません。アプリを閉じてから、もう一度)")?;
            std::fs::create_dir_all(DATA_DIR)?;
            // 映像の置き場: 一般のユーザーが書けて(ホスト)、Frame Server(LOCAL SERVICE)が読める
            // 継承を切って、必要な相手だけに許す: SYSTEM と管理者(全権)、操作しているユーザー(書き込み)、Frame Server(LOCAL SERVICE。読み取り)
            let user = std::env::var("USERNAME").ok().filter(|u| !u.is_empty());
            let writer = user.map(|u| format!("{u}:(OI)(CI)M")).unwrap_or_else(|| "*S-1-5-32-545:(OI)(CI)M".into());
            let _ = cmd("icacls", &[DATA_DIR, "/remove:g", "*S-1-1-0", "*S-1-5-32-545"]);   // 旧版が付けた「全員」「Users」の許可を外す
            let _ = cmd("icacls", &[DATA_DIR, "/inheritance:r","/grant:r", "*S-1-5-18:(OI)(CI)F", "*S-1-5-32-544:(OI)(CI)F", &writer, "*S-1-5-19:(OI)(CI)R"]);
            if !cmd("regsvr32", &["/s", &dll.display().to_string()]) { bail!("DLL を登録できませんでした"); }
            Ok(())
        }
        "disable" => {
            let _ = cmd("regsvr32", &["/u", "/s", &dll.display().to_string()]);
            std::thread::sleep(Duration::from_millis(500));
            let _ = std::fs::remove_dir_all(PROGRAM_DIR);   // Frame Server が読み込んだままだと、DLL は残る(次の再起動で消える)
            let _ = std::fs::remove_dir_all(DATA_DIR);
            Ok(())
        }
        _ => bail!("不明な操作: {what}"),
    }
}

// ---- 受信(組み立て)

/// 統計(ログに出す): 完成した枚数、未完成のまま次の1枚が始まった枚数(欠けた)、デコードできた枚数
static GOT: AtomicU64 = AtomicU64::new(0);
static LOST: AtomicU64 = AtomicU64::new(0);
static DECODED: AtomicU64 = AtomicU64::new(0);
static DROPPED: AtomicU64 = AtomicU64::new(0);  // 処理が間に合わず、捨てた枚数

/// 組み立て(JPEG が複数のパケットに分かれて届く)。端末のアドレスごとに1つ持つ(下の push)。
/// ディスプレイを切り替えると、送ってくる配信(セッション)が変わるが、組み立ては端末単位なので、途切れない。
#[derive(Default)]
pub struct Assembler {
    frame: u32,
    flags: u8,
    parts: Vec<Option<Vec<u8>>>,
    got: usize,
    started: bool,
}

impl Assembler {
    /// 断片を1つ受け取る。1枚そろったら、(JPEG, flags) を返す。
    pub fn push(&mut self, body: &[u8]) -> Option<(Vec<u8>, u8)> {
        if body.len() < 9 { return None; }
        let frame = u32::from_le_bytes(body[0..4].try_into().ok()?);
        let idx = u16::from_le_bytes(body[4..6].try_into().ok()?) as usize;
        let count = u16::from_le_bytes(body[6..8].try_into().ok()?) as usize;
        let flags = body[8];
        if count == 0 || count > 400 || idx >= count { return None; }
        if !self.started || frame != self.frame {
            // 新しい1枚が始まった(前のが未完成なら、捨てる)
            if self.started { LOST.fetch_add(1, Ordering::Relaxed); }
            self.started = true; self.frame = frame; self.flags = flags;
            self.parts = vec![None; count]; self.got = 0;
        }
        if self.parts.len() != count { return None; }
        if self.parts[idx].is_none() { self.parts[idx] = Some(body[9..].to_vec()); self.got += 1; }
        if self.got == count {
            let mut jpeg = Vec::with_capacity(count * 1200);
            for p in self.parts.drain(..) { jpeg.extend_from_slice(&p.unwrap()); }
            self.started = false;
            GOT.fetch_add(1, Ordering::Relaxed);
            return Some((jpeg, self.flags));
        }
        None
    }
}

static ASSEMBLERS: Mutex<Option<std::collections::HashMap<std::net::IpAddr, (Assembler, std::time::Instant)>>> = Mutex::new(None);

/// スマホ(端末のアドレス)から、カメラ映像の断片が届いた。1枚そろったら、デコードに回す。
pub fn push(ip: std::net::IpAddr, body: &[u8]) {
    let done = {
        let mut g = ASSEMBLERS.lock().unwrap();
        let m = g.get_or_insert_with(Default::default);
        m.retain(|_, (_, t)| t.elapsed() < Duration::from_secs(60));   // 使われなくなったものは、捨てる
        let e = m.entry(ip).or_insert_with(|| (Assembler::default(), std::time::Instant::now()));
        e.1 = std::time::Instant::now();
        e.0.push(body)
    };
    if let Some((jpeg, flags)) = done { submit(jpeg, flags); }
}

// ---- デコードと書き出し(別のスレッド)

static TX: OnceLock<SyncSender<(Vec<u8>, u8)>> = OnceLock::new();

/// 映像が1枚そろったとき。仮想カメラが有効なときだけ、処理する(遅れたら、古いものを捨てる)。
pub fn submit(jpeg: Vec<u8>, flags: u8) {
    if !installed() { return; }
    let tx = TX.get_or_init(|| {
        let (tx, rx) = sync_channel::<(Vec<u8>, u8)>(2);
        std::thread::spawn(move || worker(rx));
        tx
    });
    if let Err(TrySendError::Full(_)) = tx.try_send((jpeg, flags)) { DROPPED.fetch_add(1, Ordering::Relaxed); /* 遅れている: この1枚は捨てる */ }
}

fn worker(rx: std::sync::mpsc::Receiver<(Vec<u8>, u8)>) {
    unsafe {
        let _ = CoInitializeEx(None, COINIT_MULTITHREADED);
        let _ = MFStartup(MF_VERSION, MFSTARTUP_FULL);
    }
    let mut file: Option<std::fs::File> = None;
    let mut seq: u64 = 0;
    let mut last_stat = std::time::Instant::now();
    loop {
        if last_stat.elapsed() > Duration::from_secs(10) {
            last_stat = std::time::Instant::now();
            let (g, l, d, dr) = (GOT.swap(0, Ordering::Relaxed), LOST.swap(0, Ordering::Relaxed), DECODED.swap(0, Ordering::Relaxed), DROPPED.swap(0, Ordering::Relaxed));
            // 問題があるときだけ記録する(パケットが欠けた、処理が間に合わず捨てた)
            if l > 5 || dr > 5 { crate::log::logln!("カメラ映像(10秒): 完成 {g} 枚、欠け {l} 枚、処理が遅れて捨てた {dr} 枚、表示 {d} 枚"); }
        }
        match rx.recv_timeout(Duration::from_secs(1)) {
            Ok((jpeg, flags)) => {
                if file.is_none() {
                    file = std::fs::OpenOptions::new().create(true).read(true).write(true).open(format!("{DATA_DIR}\\frame.bin")).ok();
                }
                let Some(f) = file.as_ref() else { continue };
                ensure_camera();
                match unsafe { decode(&jpeg, flags & 3) } {
                    Ok(pix) => { write_frame(f, &pix, &mut seq); DECODED.fetch_add(1, Ordering::Relaxed); }
                    Err(e) => crate::log::logln!("カメラ映像をデコードできません: {e}"),
                }
            }
            Err(std::sync::mpsc::RecvTimeoutError::Timeout) => {}
            Err(_) => return,
        }
    }
}

fn write_frame(f: &std::fs::File, pix: &[u8], seq: &mut u64) {
    let mut hdr = [0u8; HEADER];
    hdr[0..4].copy_from_slice(&MAGIC.to_le_bytes());
    hdr[4..8].copy_from_slice(&WIDTH.to_le_bytes());
    hdr[8..12].copy_from_slice(&HEIGHT.to_le_bytes());
    *seq += 1;   // 奇数 = 書き込み中
    hdr[16..24].copy_from_slice(&seq.to_le_bytes());
    let _ = f.seek_write(&hdr, 0);
    let _ = f.seek_write(pix, HEADER as u64);
    *seq += 1;
    let ts = SystemTime::now().duration_since(UNIX_EPOCH).map_or(0, |d| d.as_millis() as u64);
    hdr[16..24].copy_from_slice(&seq.to_le_bytes());
    hdr[24..32].copy_from_slice(&ts.to_le_bytes());
    let _ = f.seek_write(&hdr, 0);
}

/// JPEG → BGRA(1280x720。回転して、縦横比を保って収め、余白は黒)。
unsafe fn decode(jpeg: &[u8], rot: u8) -> Result<Vec<u8>> {
    let f: IWICImagingFactory = CoCreateInstance(&CLSID_WICImagingFactory, None, CLSCTX_INPROC_SERVER)?;
    let stream = f.CreateStream()?;
    stream.InitializeFromMemory(jpeg)?;
    let dec = f.CreateDecoderFromStream(&stream, std::ptr::null(), WICDecodeMetadataCacheOnDemand)?;
    let frame = dec.GetFrame(0)?;
    // まず BGRA にして、メモリに固定する(固定しないと、回転や縮小のたびに、JPEG を最初からデコードし直して、非常に遅い)
    let conv = f.CreateFormatConverter()?;
    conv.Initialize(&frame, &GUID_WICPixelFormat32bppBGRA, WICBitmapDitherTypeNone, None, 0.0, WICBitmapPaletteTypeCustom)?;
    let cached = f.CreateBitmapFromSource(&conv, WICBitmapCacheOnLoad)?;
    let mut conv: IWICBitmapSource = cached.cast()?;
    if rot != 0 {
        let r = f.CreateBitmapFlipRotator()?;
        r.Initialize(&conv, match rot { 1 => WICBitmapTransformRotate90, 2 => WICBitmapTransformRotate180, _ => WICBitmapTransformRotate270 })?;
        conv = r.cast()?;
    }
    let (mut w, mut h) = (0u32, 0u32);
    conv.GetSize(&mut w, &mut h)?;
    if w == 0 || h == 0 { bail!("空の画像"); }
    let scale = (WIDTH as f64 / w as f64).min(HEIGHT as f64 / h as f64);
    let (sw, sh) = (((w as f64 * scale) as u32).clamp(1, WIDTH), ((h as f64 * scale) as u32).clamp(1, HEIGHT));
    let scaler = f.CreateBitmapScaler()?;
    scaler.Initialize(&conv, sw, sh, WICBitmapInterpolationModeFant)?;
    let mut tmp = vec![0u8; (sw * sh * 4) as usize];
    scaler.CopyPixels(std::ptr::null(), sw * 4, &mut tmp)?;
    let mut out = vec![0u8; (WIDTH * HEIGHT * 4) as usize];
    for px in out.chunks_exact_mut(4) { px[3] = 0xFF; }
    let (ox, oy) = ((WIDTH - sw) / 2, (HEIGHT - sh) / 2);
    for y in 0..sh {
        let s = (y * sw * 4) as usize;
        let d = (((oy + y) * WIDTH + ox) * 4) as usize;
        out[d..d + (sw * 4) as usize].copy_from_slice(&tmp[s..s + (sw * 4) as usize]);
    }
    Ok(out)
}

/// 有効にしている間、仮想カメラを、Windows に出しておく(ホストの起動中)。無効にしたら、取り除く。
pub fn start_background() {
    std::thread::spawn(|| {
        unsafe {
            let _ = CoInitializeEx(None, COINIT_MULTITHREADED);
            let _ = MFStartup(MF_VERSION, MFSTARTUP_FULL);
        }
        loop {
            let on = installed();
            let has = CAM.lock().unwrap().is_some();
            if on && !has { ensure_camera(); }
            if !on && has { remove_camera(); }
            std::thread::sleep(Duration::from_secs(2));
        }
    });
}

// ---- 仮想カメラ本体(有効にしている間、ホストが動いている限り、存在する)

struct Cam(IMFVirtualCamera);
unsafe impl Send for Cam {}
static CAM: Mutex<Option<Cam>> = Mutex::new(None);

fn ensure_camera() {
    let mut g = CAM.lock().unwrap();
    if g.is_some() { return; }
    unsafe {
        match MFCreateVirtualCamera(MFVirtualCameraType_SoftwareCameraSource, MFVirtualCameraLifetime_Session, MFVirtualCameraAccess_CurrentUser, w!("Deskport Camera"), &HSTRING::from(CLSID), None) {
            Ok(c) => match c.Start(None) {
                Ok(()) => { crate::log::logln!("仮想カメラを作りました(Deskport Camera)"); *g = Some(Cam(c)); }
                Err(e) => crate::log::logln!("仮想カメラを開始できません: {e}"),
            },
            Err(e) => crate::log::logln!("仮想カメラを作れません: {e}"),
        }
    }
}

fn remove_camera() {
    if let Some(c) = CAM.lock().unwrap().take() {
        unsafe { let _ = c.0.Shutdown(); }
        crate::log::logln!("仮想カメラを取り除きました");
    }
}
