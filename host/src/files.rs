//! ファイル転送とクリップボード(TCP、暗号化つき)。映像の UDP と同じ番号のポートで待ち受ける。
//!
//! 接続の手順
//!   1. 端末が乱数 16 バイト(cr)を送る → ホストが乱数 16 バイト(sr)を返す。
//!   2. 両方が、接続ごとの鍵 = SHA-256("deskport-tcp-v1" + 共有鍵 + cr + sr) を作る(別の接続の記録を再送しても通らない)。
//!   3. 以降は、フレーム [長さ u32][AES-256-GCM の暗号文] だけをやり取りする。ナンス = [方向 1 バイト][0 x3][フレーム番号 u64]。
//!      番号は数えているので、順序の入れ替え、再送、削除はすべて復号の失敗になり、接続を切る。
//!   4. ホストが最初に [0x80][PC 名] を送る。端末はこれを復号できて初めて、相手が本物のホストだと分かる。
//! 要求(端末→ホスト)  どれも、返事の先頭は [0x00=成功 | 0x01=失敗(続きは理由の文字列)]
//!   0x01 クリップボードを読む            → [0][UTF-8 の文字列]
//!   0x02 クリップボードに書く [文字列]    → [0]
//!   0x03 ファイルの一覧                  → [0][JSON {"files":[{"name","size","mtime"}]}]
//!   0x04 ファイルを置く [名前長 u16][名前][大きさ u64] → [0](準備できた)→ 端末がデータのフレームを送る → [0][保存した名前]
//!   0x05 ファイルを取る [名前]           → [0][大きさ u64] → ホストがデータのフレームを送る
//!   0x06 ファイルを置く(圧縮)  0x04 と同じ形。ただし、データのフレームは、deflate(zlib なし)の連続した流れを、区切って送ったもの。
//!        大きさは、元のファイルの大きさ。最後に、中身が空のフレームを1つ送って終わりを知らせる。ホストが、元の大きさ分だけ展開できたか確かめる
//!   0x07 ファイルを取る(圧縮)  0x05 と同じ形。ホストが圧縮して送り、最後に、空のフレームを1つ送る
//!   0x08 PC のエクスプローラーで表示 [名前]  → [0]
//!   0x09 ファイルを消す [名前]            → [0]
//!   (一覧の JSON には、置き場所の絶対パス "dir" も入る。スマホの画面に、保存先として見せる)
//! ファイルの置き場所は %USERPROFILE%\Downloads\Deskport だけ。ここの外は、読み書きできない。
use crate::crypto;
use aes_gcm::aead::{Aead, KeyInit};
use aes_gcm::{Aes256Gcm, Nonce};
use anyhow::Result;
use flate2::{Compress, Compression, Decompress, FlushCompress, FlushDecompress};
use sha2::{Digest, Sha256};
use std::io::{Read, Write};
use std::net::{TcpListener, TcpStream};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Arc;
use std::time::Duration;

const MAX_FRAME: usize = 1 << 20;            // 1 フレームの中身の上限
const CHUNK: usize = 256 * 1024;
const MAX_FILE: u64 = 16 * 1024 * 1024 * 1024;
const MAX_CLIP: usize = 512 * 1024;
const MAX_CONN: usize = 48;
const MAX_PER_IP: usize = 16;   // クリップボード同期・一覧・転送が重なっても足りる数。握り続ける相手は、全体の上限で止める

/// ファイルの置き場所。
pub fn dir() -> PathBuf {
    PathBuf::from(std::env::var("USERPROFILE").unwrap_or_else(|_| ".".into())).join("Downloads").join("Deskport")
}

pub fn run(bind: &str, secret: &str) -> Result<()> {
    let key = crypto::key_bytes(secret);
    let l = TcpListener::bind(bind)?;
    crate::log::logln!("ファイル転送・クリップボード: {bind}(TCP)");
    let count = Arc::new(AtomicUsize::new(0));
    // 同じ相手からの接続数(認証前の接続を握り続けて、他の人が使えなくなるのを防ぐ)
    let per_ip: Arc<std::sync::Mutex<std::collections::HashMap<std::net::IpAddr, usize>>> = Default::default();
    for s in l.incoming() {
        let Ok(s) = s else { continue };
        if count.load(Ordering::SeqCst) >= MAX_CONN { continue; }   // 多すぎるときは、そのまま閉じる
        let Ok(peer) = s.peer_addr() else { continue };
        {
            let mut m = per_ip.lock().unwrap();
            let n = m.entry(peer.ip()).or_insert(0);
            if *n >= MAX_PER_IP { continue; }
            *n += 1;
        }
        count.fetch_add(1, Ordering::SeqCst);
        let c = count.clone();
        let pi = per_ip.clone();
        std::thread::spawn(move || {
            let _ = handle(s, &key);
            c.fetch_sub(1, Ordering::SeqCst);
            let mut m = pi.lock().unwrap();
            if let Some(n) = m.get_mut(&peer.ip()) { *n -= 1; if *n == 0 { m.remove(&peer.ip()); } }
        });
    }
    Ok(())
}

struct Chan {
    s: TcpStream,
    cipher: Aes256Gcm,
    send_n: u64,
    recv_n: u64,
}

fn nonce(dir: u8, n: u64) -> [u8; 12] {
    let mut x = [0u8; 12];
    x[0] = dir;
    x[4..12].copy_from_slice(&n.to_le_bytes());
    x
}

impl Chan {
    fn send(&mut self, data: &[u8]) -> std::io::Result<()> {
        let ct = self.cipher.encrypt(Nonce::from_slice(&nonce(crypto::DIR_TO_CLIENT, self.send_n)), data).map_err(|_| std::io::Error::other("暗号化"))?;
        self.send_n += 1;
        let mut out = Vec::with_capacity(4 + ct.len());
        out.extend_from_slice(&(ct.len() as u32).to_le_bytes());
        out.extend_from_slice(&ct);
        self.s.write_all(&out)
    }

    fn recv(&mut self) -> std::io::Result<Vec<u8>> {
        let mut h = [0u8; 4];
        self.s.read_exact(&mut h)?;
        let n = u32::from_le_bytes(h) as usize;
        if n < 16 || n > MAX_FRAME + 16 { return Err(std::io::Error::other("フレームの大きさが不正")); }
        let mut ct = vec![0u8; n];
        self.s.read_exact(&mut ct)?;
        let pt = self.cipher.decrypt(Nonce::from_slice(&nonce(crypto::DIR_TO_HOST, self.recv_n)), ct.as_slice()).map_err(|_| std::io::Error::other("復号できません"))?;
        self.recv_n += 1;
        Ok(pt)
    }

    fn ok(&mut self, body: &[u8]) -> std::io::Result<()> {
        let mut v = vec![0u8];
        v.extend_from_slice(body);
        self.send(&v)
    }

    fn err(&mut self, msg: &str) -> std::io::Result<()> {
        let mut v = vec![1u8];
        v.extend_from_slice(msg.as_bytes());
        self.send(&v)
    }
}

fn handle(mut s: TcpStream, key: &[u8; 32]) -> std::io::Result<()> {
    s.set_nodelay(true).ok();
    s.set_read_timeout(Some(Duration::from_secs(5)))?;
    s.set_write_timeout(Some(Duration::from_secs(30)))?;
    let mut cr = [0u8; 16];
    s.read_exact(&mut cr)?;
    let mut sr = [0u8; 16];
    getrandom::getrandom(&mut sr).map_err(|e| std::io::Error::other(e.to_string()))?;
    s.write_all(&sr)?;
    let mut h = Sha256::new();
    h.update(b"deskport-tcp-v1");
    h.update(key);
    h.update(cr);
    h.update(sr);
    let cipher = Aes256Gcm::new_from_slice(&h.finalize()).unwrap();
    let mut ch = Chan { s, cipher, send_n: 0, recv_n: 0 };
    let mut hello = vec![0x80u8];
    hello.extend_from_slice(std::env::var("COMPUTERNAME").unwrap_or_else(|_| "PC".into()).as_bytes());
    ch.send(&hello)?;
    // 最初の要求を復号できなければ(鍵が違う)、何も返さず切る。復号できたら、あとは待ち時間を長くする
    let first = ch.recv()?;
    ch.s.set_read_timeout(Some(Duration::from_secs(30)))?;
    let mut req = first;
    loop {
        if let Err(e) = dispatch(&mut ch, &req) {
            // 通信の失敗(切断など)は終わり。要求の失敗は dispatch が返事を送ってある
            return Err(e);
        }
        req = ch.recv()?;
    }
}

fn dispatch(ch: &mut Chan, req: &[u8]) -> std::io::Result<()> {
    let Some((&op, body)) = req.split_first() else { return ch.err("空の要求") };
    match op {
        0x01 => match clip_get() {
            Some(t) => ch.ok(t.as_bytes()),
            None => ch.err("クリップボードを読めません"),
        },
        0x02 => {
            if body.len() > MAX_CLIP { return ch.err("大きすぎます"); }
            let Ok(t) = std::str::from_utf8(body) else { return ch.err("文字列が不正") };
            if clip_set(t) { ch.ok(&[]) } else { ch.err("クリップボードに書けません") }
        }
        0x03 => ch.ok(list().to_string().as_bytes()),
        0x04 => put(ch, body, false),
        0x05 => get(ch, body, false),
        0x06 => put(ch, body, true),
        0x07 => get(ch, body, true),
        0x08 => match exact_file(&String::from_utf8_lossy(body)) { Some(p) => if reveal(&p) { ch.ok(&[]) } else { ch.err("開けません") }, None => ch.err("見つかりません") },
        0x09 => match exact_file(&String::from_utf8_lossy(body)) { Some(p) => if std::fs::remove_file(&p).is_ok() { crate::log::logln!("ファイルを消しました: {}", p.display()); ch.ok(&[]) } else { ch.err("消せません") }, None => ch.err("見つかりません") },
        _ => ch.err("未対応の要求"),
    }
}

// ---- ファイル ----

/// 保存する名前に直す: フォルダの区切りより前を捨て、Windows で使えない文字を置き換え、予約名と長さを避ける。
fn safe_name(raw: &str) -> String {
    let base = raw.rsplit(['/', '\\']).next().unwrap_or("");
    let mut n: String = base.chars().map(|c| if c.is_control() || "<>:\"|?*".contains(c) { '_' } else { c }).collect();
    n = n.trim_matches(|c| c == ' ' || c == '.').to_string();
    if n.chars().count() > 150 { n = n.chars().take(150).collect(); }
    let stem = n.split('.').next().unwrap_or("").to_ascii_uppercase();
    let reserved = ["CON", "PRN", "AUX", "NUL"].contains(&stem.as_str())
        || ((stem.starts_with("COM") || stem.starts_with("LPT")) && stem.len() == 4 && stem.as_bytes()[3].is_ascii_digit());
    if n.is_empty() { return "file".into(); }
    if reserved { n.insert(0, '_'); }
    n
}

fn unique_path(d: &Path, name: &str) -> PathBuf {
    let p = Path::new(name);
    let stem = p.file_stem().map(|s| s.to_string_lossy().to_string()).unwrap_or_else(|| name.into());
    let ext = p.extension().map(|s| format!(".{}", s.to_string_lossy())).unwrap_or_default();
    let mut c = d.join(name);
    let mut i = 1;
    while c.exists() {
        c = d.join(format!("{stem} ({i}){ext}"));
        i += 1;
    }
    c
}

fn list() -> serde_json::Value {
    let mut v: Vec<(u64, serde_json::Value)> = Vec::new();
    if let Ok(rd) = std::fs::read_dir(dir()) {
        for e in rd.flatten() {
            let Ok(m) = e.metadata() else { continue };
            let name = e.file_name().to_string_lossy().to_string();
            if !m.is_file() || name.ends_with(".part") { continue; }
            let t = m.modified().ok().and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok()).map_or(0, |d| d.as_secs());
            v.push((t, serde_json::json!({ "name": name, "size": m.len(), "mtime": t })));
        }
    }
    v.sort_by(|a, b| b.0.cmp(&a.0));
    serde_json::json!({ "dir": dir().display().to_string(), "files": v.into_iter().map(|x| x.1).collect::<Vec<_>>() })
}

/// 置き場所の中の、名前が完全に一致するファイル(../ などが入り込む余地を作らない)。
pub fn exact_file(want: &str) -> Option<PathBuf> {
    std::fs::read_dir(dir()).ok()?.flatten()
        .find(|e| e.file_name().to_string_lossy() == want && e.metadata().map_or(false, |m| m.is_file()))
        .map(|e| e.path())
}

/// PC のエクスプローラーで、そのファイルを選んだ状態にして開く。
pub fn reveal(path: &Path) -> bool {
    use std::os::windows::process::CommandExt;
    std::process::Command::new("explorer").raw_arg(format!("/select,\"{}\"", path.display())).spawn().is_ok()
}

/// PC のエクスプローラーで、置き場所のフォルダを開く。
pub fn open_dir() -> bool {
    use std::os::windows::process::CommandExt;
    let d = dir();
    let _ = std::fs::create_dir_all(&d);
    std::process::Command::new("explorer").raw_arg(format!("\"{}\"", d.display())).spawn().is_ok()
}

/// PC のファイルを、置き場所へコピーする(スマホに送るため)。保存した名前を返す。
pub fn add_copy(src: &Path) -> Option<String> {
    let name = safe_name(&src.file_name()?.to_string_lossy());
    let d = dir();
    std::fs::create_dir_all(&d).ok()?;
    let dest = unique_path(&d, &name);
    std::fs::copy(src, &dest).ok()?;
    Some(dest.file_name()?.to_string_lossy().to_string())
}

/// 一覧(窓の「ファイル」タブ用): (名前, 大きさ, 更新時刻の秒)。新しい順。
pub fn entries() -> Vec<(String, u64, u64)> {
    let mut v = Vec::new();
    if let Ok(rd) = std::fs::read_dir(dir()) {
        for e in rd.flatten() {
            let Ok(m) = e.metadata() else { continue };
            let name = e.file_name().to_string_lossy().to_string();
            if !m.is_file() || name.ends_with(".part") { continue; }
            let t = m.modified().ok().and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok()).map_or(0, |d| d.as_secs());
            v.push((name, m.len(), t));
        }
    }
    v.sort_by(|a, b| b.2.cmp(&a.2));
    v
}

fn put(ch: &mut Chan, body: &[u8], compressed: bool) -> std::io::Result<()> {
    if body.len() < 2 { return ch.err("要求が不正"); }
    let nl = u16::from_le_bytes([body[0], body[1]]) as usize;
    if body.len() != 2 + nl + 8 { return ch.err("要求が不正"); }
    let name = safe_name(&String::from_utf8_lossy(&body[2..2 + nl]));
    let size = u64::from_le_bytes(body[2 + nl..].try_into().unwrap());
    if size > MAX_FILE { return ch.err("大きすぎます"); }
    let d = dir();
    if std::fs::create_dir_all(&d).is_err() { return ch.err("保存先を作れません"); }
    let dest = unique_path(&d, &name);
    let tmp = PathBuf::from(format!("{}.part", dest.display()));
    let Ok(mut f) = std::fs::File::create(&tmp) else { return ch.err("ファイルを作れません") };
    ch.ok(&[])?;
    let mut got = 0u64;
    if compressed {
        // 圧縮: 空のフレームが来るまで受けて、展開して書く。元の大きさを超えたら(展開爆弾)、打ち切る
        let mut dec = Decompress::new(false);
        let mut out = vec![0u8; 1 << 20];
        loop {
            let chunk = match ch.recv() {
                Ok(c) => c,
                Err(e) => { drop(f); let _ = std::fs::remove_file(&tmp); return Err(e); }
            };
            if chunk.is_empty() { break; }
            let mut input: &[u8] = &chunk;
            loop {
                let (ti, to) = (dec.total_in(), dec.total_out());
                let st = dec.decompress(input, &mut out, FlushDecompress::Sync);
                let used = (dec.total_in() - ti) as usize;
                let produced = (dec.total_out() - to) as usize;
                if st.is_err() || got + produced as u64 > size || f.write_all(&out[..produced]).is_err() {
                    drop(f);
                    let _ = std::fs::remove_file(&tmp);
                    return ch.err("データが不正、または書き込めません");
                }
                got += produced as u64;
                input = &input[used..];
                if input.is_empty() && produced < out.len() { break; }
                if used == 0 && produced == 0 { break; }
            }
        }
        if got != size {
            drop(f);
            let _ = std::fs::remove_file(&tmp);
            return ch.err("データの大きさが合いません");
        }
    } else {
        while got < size {
            let chunk = match ch.recv() {
                Ok(c) => c,
                Err(e) => { drop(f); let _ = std::fs::remove_file(&tmp); return Err(e); }
            };
            got += chunk.len() as u64;
            if got > size || f.write_all(&chunk).is_err() {
                drop(f);
                let _ = std::fs::remove_file(&tmp);
                return ch.err("データが不正、または書き込めません");
            }
        }
    }
    drop(f);
    if std::fs::rename(&tmp, &dest).is_err() {
        let _ = std::fs::remove_file(&tmp);
        return ch.err("保存できません");
    }
    let saved = dest.file_name().unwrap().to_string_lossy().to_string();
    crate::log::logln!("ファイルを受け取りました: {saved}({size} バイト)");
    ch.ok(saved.as_bytes())
}

fn get(ch: &mut Chan, body: &[u8], compressed: bool) -> std::io::Result<()> {
    let want = String::from_utf8_lossy(body).to_string();
    // 名前の加工はせず、置き場所の中のファイルと完全に一致するものだけを出す(../ などが入り込む余地を作らない)
    let Some(path) = std::fs::read_dir(dir()).ok().and_then(|rd| rd.flatten().find(|e| e.file_name().to_string_lossy() == want && e.metadata().map_or(false, |m| m.is_file())).map(|e| e.path())) else {
        return ch.err("見つかりません");
    };
    let Ok(mut f) = std::fs::File::open(&path) else { return ch.err("開けません") };
    let size = f.metadata().map(|m| m.len()).unwrap_or(0);
    ch.ok(&size.to_le_bytes())?;
    let mut buf = vec![0u8; CHUNK];
    let mut sent = 0u64;
    let mut comp = Compress::new(Compression::new(3), false);
    let mut out = vec![0u8; CHUNK + CHUNK / 8 + 1024];
    while sent < size {
        let want_n = ((size - sent) as usize).min(CHUNK);
        f.read_exact(&mut buf[..want_n])?;
        if compressed {
            // 区切りごとに Sync で出し切って、端末が、届いた分から展開できるようにする
            let mut input: &[u8] = &buf[..want_n];
            loop {
                let (ti, to) = (comp.total_in(), comp.total_out());
                comp.compress(input, &mut out, FlushCompress::Sync).map_err(|e| std::io::Error::other(e.to_string()))?;
                let used = (comp.total_in() - ti) as usize;
                let produced = (comp.total_out() - to) as usize;
                if produced > 0 { ch.send(&out[..produced])?; }
                input = &input[used..];
                if input.is_empty() && produced < out.len() { break; }
            }
        } else {
            ch.send(&buf[..want_n])?;
        }
        sent += want_n as u64;
    }
    if compressed { ch.send(&[])?; }   // 終わりの印
    crate::log::logln!("ファイルを送りました: {want}({size} バイト)");
    Ok(())
}

// ---- クリップボード(文字列) ----

fn open_clipboard() -> bool {
    use windows::Win32::Foundation::HWND;
    use windows::Win32::System::DataExchange::OpenClipboard;
    for _ in 0..10 {
        if unsafe { OpenClipboard(HWND::default()) }.is_ok() { return true; }
        std::thread::sleep(Duration::from_millis(20));   // 他のアプリが開いている間は、少し待つ
    }
    false
}

fn clip_get() -> Option<String> {
    use windows::Win32::Foundation::HGLOBAL;
    use windows::Win32::System::DataExchange::{CloseClipboard, GetClipboardData};
    use windows::Win32::System::Memory::{GlobalLock, GlobalUnlock};
    if !open_clipboard() { return None; }
    let text = unsafe {
        let r = (|| {
            let h = GetClipboardData(13).ok()?;   // CF_UNICODETEXT
            let hg = HGLOBAL(h.0);
            let p = GlobalLock(hg) as *const u16;
            if p.is_null() { return None; }
            let mut n = 0;
            while *p.add(n) != 0 && n < MAX_CLIP { n += 1; }
            let s = String::from_utf16_lossy(std::slice::from_raw_parts(p, n));
            let _ = GlobalUnlock(hg);
            Some(s)
        })();
        let _ = CloseClipboard();
        r
    };
    Some(text.unwrap_or_default())
}

fn clip_set(text: &str) -> bool {
    use windows::Win32::Foundation::{GlobalFree, HANDLE};
    use windows::Win32::System::DataExchange::{CloseClipboard, EmptyClipboard, SetClipboardData};
    use windows::Win32::System::Memory::{GlobalAlloc, GlobalLock, GlobalUnlock, GMEM_MOVEABLE};
    let mut w: Vec<u16> = text.encode_utf16().collect();
    w.push(0);
    if !open_clipboard() { return false; }
    unsafe {
        let ok = (|| {
            let hg = GlobalAlloc(GMEM_MOVEABLE, w.len() * 2).ok()?;
            let p = GlobalLock(hg) as *mut u16;
            if p.is_null() { let _ = GlobalFree(hg); return None; }
            std::ptr::copy_nonoverlapping(w.as_ptr(), p, w.len());
            let _ = GlobalUnlock(hg);
            let _ = EmptyClipboard();
            // 成功すると、メモリの持ち主は Windows に移る
            if SetClipboardData(13, HANDLE(hg.0)).is_err() { let _ = GlobalFree(hg); return None; }
            Some(())
        })()
        .is_some();
        let _ = CloseClipboard();
        ok
    }
}
