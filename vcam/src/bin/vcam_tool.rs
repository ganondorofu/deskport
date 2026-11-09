//! 仮想カメラの試験用ツール(開発用)。
//!   vcam-tool create <秒>   … 仮想カメラを作って、その間、保持する(IMFVirtualCamera を作ったプロセスが生きている間だけ存在する)
//!   vcam-tool list          … カメラの一覧
//!   vcam-tool read <枚数>   … 「Deskport」カメラから、フレームを読んで、内容を表示する
//!   vcam-tool feed <秒>     … 共有ファイルへ、色が変わるテスト画面を書く
use std::os::windows::fs::FileExt;
use windows::core::*;
use windows::Win32::Foundation::E_FAIL;
use windows::Win32::Media::KernelStreaming::{KSCATEGORY_CAPTURE, KSCATEGORY_VIDEO, KSCATEGORY_VIDEO_CAMERA};
use windows::Win32::Media::MediaFoundation::*;
use windows::Win32::System::Com::*;

const CLSID: &str = "{6D3A1C52-4B7E-4F0A-9C1D-5E8B2A7F10C3}";

fn main() -> Result<()> {
    let args: Vec<String> = std::env::args().collect();
    unsafe {
        CoInitializeEx(None, COINIT_MULTITHREADED).ok()?;
        MFStartup(MF_VERSION, MFSTARTUP_FULL)?;
    }
    match args.get(1).map(|s| s.as_str()) {
        Some("create") => create(args.get(2).and_then(|s| s.parse().ok()).unwrap_or(30)),
        Some("list") => { list()?; Ok(()) }
        Some("read") => read(args.get(2).and_then(|s| s.parse().ok()).unwrap_or(5)),
        Some("feed") => feed(args.get(2).and_then(|s| s.parse().ok()).unwrap_or(10)),
        _ => { println!("使い方: vcam-tool create|list|read|feed"); Ok(()) }
    }
}

fn create(secs: u64) -> Result<()> {
    unsafe {
        let cam = match MFCreateVirtualCamera(MFVirtualCameraType_SoftwareCameraSource, MFVirtualCameraLifetime_Session, MFVirtualCameraAccess_CurrentUser, w!("Deskport Camera"), &HSTRING::from(CLSID), if std::env::var("VCAM_CATS").is_ok() { Some(&[KSCATEGORY_VIDEO_CAMERA, KSCATEGORY_VIDEO, KSCATEGORY_CAPTURE][..]) } else { None }) {
            Ok(c) => { println!("MFCreateVirtualCamera: 成功"); c }
            Err(e) => { println!("MFCreateVirtualCamera: 失敗 {e:?}"); return Err(e); }
        };
        if let Err(e) = cam.Start(None) { println!("Start: 失敗 {e:?}"); return Err(e); }
        println!("Start: 成功");
        println!("仮想カメラを作りました。{secs}秒間、保持します");
        std::thread::sleep(std::time::Duration::from_secs(secs));
        cam.Shutdown()?;
    }
    Ok(())
}

unsafe fn devices() -> Result<Vec<IMFActivate>> {
    let mut attrs: Option<IMFAttributes> = None;
    MFCreateAttributes(&mut attrs, 1)?;
    let attrs = attrs.unwrap();
    attrs.SetGUID(&MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE, &MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE_VIDCAP_GUID)?;
    let mut pp: *mut Option<IMFActivate> = std::ptr::null_mut();
    let mut n = 0u32;
    MFEnumDeviceSources(&attrs, &mut pp, &mut n)?;
    let mut out = Vec::new();
    for i in 0..n as usize { if let Some(a) = (*pp.add(i)).take() { out.push(a); } }
    CoTaskMemFree(Some(pp as *const _));
    Ok(out)
}

unsafe fn name_of(a: &IMFActivate) -> String {
    let mut p = PWSTR::null(); let mut len = 0u32;
    if a.GetAllocatedString(&MF_DEVSOURCE_ATTRIBUTE_FRIENDLY_NAME, &mut p, &mut len).is_err() { return String::new(); }
    let s = p.to_string().unwrap_or_default();
    CoTaskMemFree(Some(p.0 as *const _));
    s
}

fn list() -> Result<Vec<String>> {
    unsafe {
        let d = devices()?;
        let names: Vec<String> = d.iter().map(|a| name_of(a)).collect();
        for n in &names { println!("カメラ: {n}"); }
        if names.is_empty() { println!("カメラはありません"); }
        Ok(names)
    }
}

fn read(count: u32) -> Result<()> {
    unsafe {
        let d = devices()?;
        let Some(act) = d.iter().find(|a| name_of(a).contains("Deskport")) else { println!("Deskport のカメラが見つかりません"); return Ok(()) };
        println!("見つかりました: {}", name_of(act));
        let src: IMFMediaSource = act.ActivateObject()?;
        let reader = MFCreateSourceReaderFromMediaSource(&src, None)?;
        let t0 = std::time::Instant::now();
        let mut got = 0;
        for _ in 0..count * 4 {
            let (mut actual, mut flags, mut ts) = (0u32, 0u32, 0i64);
            let mut sample: Option<IMFSample> = None;
            reader.ReadSample(MF_SOURCE_READER_FIRST_VIDEO_STREAM.0 as u32, 0, Some(&mut actual), Some(&mut flags), Some(&mut ts), Some(&mut sample))?;
            if let Some(s) = sample {
                let b = s.ConvertToContiguousBuffer()?;
                let (mut p, mut max, mut cur) = (std::ptr::null_mut::<u8>(), 0u32, 0u32);
                b.Lock(&mut p, Some(&mut max), Some(&mut cur))?;
                let px = std::slice::from_raw_parts(p, cur as usize);
                let mid = (cur as usize / 2) & !3;
                println!("フレーム {got}: {}バイト 時刻={}ms 左上BGRA={:?} 中央BGRA={:?}", cur, ts / 10_000, &px[0..4], &px[mid..mid + 4]);
                b.Unlock()?;
                got += 1;
                if got >= count { break; }
            }
        }
        println!("{got} 枚を {:.1} 秒で読みました", t0.elapsed().as_secs_f32());
        let _ = src.Shutdown();
    }
    Ok(())
}

fn feed(secs: u64) -> Result<()> {
    let _ = std::fs::create_dir_all(r"C:\ProgramData\DeskportVCam");
    let f = std::fs::OpenOptions::new().create(true).read(true).write(true).open(r"C:\ProgramData\DeskportVCam\frame.bin")
        .map_err(|e| Error::new(E_FAIL, format!("共有ファイルを開けません: {e}")))?;
    let (w, h) = (1280u32, 720u32);
    let mut pix = vec![0u8; (w * h * 4) as usize];
    let mut seq: u64 = 0;
    let end = std::time::Instant::now() + std::time::Duration::from_secs(secs);
    let mut n = 0u32;
    while std::time::Instant::now() < end {
        // 色が変わる縦じま: 左半分は赤、右半分は、時間で変わる青〜緑
        let t = (n % 255) as u8;
        for y in 0..h { for x in 0..w {
            let i = ((y * w + x) * 4) as usize;
            if x < w / 2 { pix[i] = 0; pix[i + 1] = 0; pix[i + 2] = 255; } else { pix[i] = 255 - t; pix[i + 1] = t; pix[i + 2] = 0; }
            pix[i + 3] = 255;
        } }
        let mut hdr = [0u8; 32];
        hdr[0..4].copy_from_slice(&0x4D43_5644u32.to_le_bytes());
        hdr[4..8].copy_from_slice(&w.to_le_bytes()); hdr[8..12].copy_from_slice(&h.to_le_bytes());
        seq += 1; hdr[16..24].copy_from_slice(&seq.to_le_bytes());   // 奇数 = 書き込み中
        f.seek_write(&hdr, 0).ok();
        f.seek_write(&pix, 32).ok();
        seq += 1;
        let ts = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_millis() as u64;
        hdr[16..24].copy_from_slice(&seq.to_le_bytes()); hdr[24..32].copy_from_slice(&ts.to_le_bytes());
        f.seek_write(&hdr, 0).ok();
        n += 1;
        std::thread::sleep(std::time::Duration::from_millis(33));
    }
    println!("{n} 枚を書きました");
    Ok(())
}
