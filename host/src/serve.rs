//! 配信サーバー(試作): 端末から UDP で接続要求と操作を受け、指定のディスプレイを符号化して送り返す。
//!
//! 通信の形式(UDP、数値はリトルエンディアン)
//!   全パケットは AES-256-GCM で暗号化する(形式は crypto.rs)。以下は復号した後の中身。
//!   端末→ホスト  [type:u8][seq:u32][内容]   復号できない(鍵が違う、改ざん)パケットは捨てる。seq は端末が増やし続ける(古いものは捨てる)。
//!     0x10 接続要求  [display:u8][kbps:u32][時刻ms:u64]   時刻が±60秒を外れたら拒否(使い回しの対策)
//!     (拒否の返事 0x16 [ホストの時刻ms:u64] を、ホストから返す)
//!     0x15 生きているかの問い合わせ(接続前に使う。ホストは 0x05 を1つ返す)
//!     0x11 生存通知(1秒ごと。3秒途絶えたら配信を止める)  0x12 キーフレーム要求  0x13 終了
//!     0x20 マウス相対移動 [dx:i16][dy:i16]   0x21 マウス絶対位置 [x:i16][y:i16](ディスプレイ内の座標)
//!     0x22 ボタン [btn:u8][down:u8]   0x23 スクロール [amount:i16]   0x24 キー [vk:u16][down:u8]   0x25 文字(UTF-8)
//!     0x22/0x24/0x25 は、取りこぼしに備えて端末が2回送る。ホストは seq の重複を捨てる。
//!   ホスト→端末  0x01 映像情報   [w:u16][h:u16][fps:u8][codec:u8 (1=HEVC)]
//!                0x02 映像データ [flags:u8 (bit0=キーフレーム)][frame:u32][index:u16][count:u16][payload]
//!                0x03 カーソル   [x:i16][y:i16][visible:u8][shape:u32]  (ディスプレイ内の座標と、形の番号)
//!                0x04 カーソルの形 [id:u32][w:u16][h:u16][hotX:u16][hotY:u16][flags:u8 (bit0=読み込み中)][index:u16][count:u16][RGBA]
//!   端末→ホスト  0x14 カーソルの形の再送要求 [id:u32]
use crate::{capture::Capturer, cursor, input, nvenc::Encoder};
use anyhow::{bail, Result};
use crate::crypto::{self, Sealer};
use aes_gcm::Aes256Gcm;
use std::collections::{HashMap, VecDeque};
use std::net::{SocketAddr, UdpSocket};
use serde::Serialize;
use std::sync::{Arc, Mutex, OnceLock};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

const MTU_PAYLOAD: usize = 1200;

/// 1つの配信(端末側のソケット1つに対応)。ディスプレイごとに別の配信を同時に持てる。
struct Session {
    display: usize,
    kbps: u32,
    last_seen: Instant,
    want_idr: bool,
    want_info: bool,       // 映像情報の再送を頼まれた(最初の情報を端末が受け損ねたとき)
    alive: bool,
    audio: bool,   // 端末が音声を求めている(接続要求の旗の bit0)
    opus: bool,    // 音声を Opus で受けられる(旗の bit1)。なければ、圧縮しない形で送る
    held_keys: Vec<u16>,   // いま押されているキー(配信が終わったら離す)
    held_btns: Vec<u8>,    // いま押されているマウスのボタン
    origin: (i32, i32), // 配信中のディスプレイの左上(仮想デスクトップ上の物理ピクセル)
    last_seq: u32,
    recent: VecDeque<u32>, // 重複を捨てるための、最近の seq
    want_shapes: Vec<u32>, // 端末が足りないと言ってきたカーソルの形
    // 窓に出す状態
    peer: String,
    width: u32,
    height: u32,
    fps_now: f32,
    mbps: f32,
    enc_ms: f32,
    since: u64, // 接続した時刻(UNIX 秒)
}

/// 窓に出す、接続中の配信1つぶんの状態。
#[derive(Serialize, Clone)]
pub struct SessionInfo {
    pub peer: String,
    pub display: usize,
    pub width: u32,
    pub height: u32,
    pub fps: f32,
    pub mbps: f32,
    pub enc_ms: f32,
    pub since: u64,
}

static REGISTRY: OnceLock<Sessions> = OnceLock::new();

/// 同時に配信できる数(ディスプレイ4枚 x 端末2台ぶん)。
const MAX_SESSIONS: usize = 8;

pub fn snapshot() -> Vec<SessionInfo> {
    let Some(reg) = REGISTRY.get() else { return Vec::new() };
    let mut v: Vec<SessionInfo> = reg.lock().unwrap().values().filter_map(|s| {
        let s = s.lock().unwrap();
        s.alive.then(|| SessionInfo { peer: s.peer.clone(), display: s.display, width: s.width, height: s.height, fps: s.fps_now, mbps: s.mbps, enc_ms: s.enc_ms, since: s.since })
    }).collect();
    v.sort_by(|a, b| (a.since, a.display).cmp(&(b.since, b.display)));
    v
}

/// 指定した配信(peer = 端末のアドレスとポート)を終わらせる。
pub fn disconnect(peer: &str) {
    if let Some(reg) = REGISTRY.get() {
        for s in reg.lock().unwrap().values() {
            let mut s = s.lock().unwrap();
            if s.peer == peer { s.alive = false; }
        }
    }
}

type Sessions = Arc<Mutex<HashMap<SocketAddr, Arc<Mutex<Session>>>>>;

pub fn run(bind: &str, token: &str) -> Result<()> {
    if token.is_empty() {
        bail!("合言葉が空です(第2引数、または環境変数 DESKPORT_TOKEN で指定してください)");
    }
    let cipher = crypto::key_from_secret(token);
    let sock = Arc::new(UdpSocket::bind(bind)?);
    // 受信バッファを大きくする(スマホのカメラ映像は、1枚が数十パケットで、一気に届く。小さいと、あふれて欠ける)
    unsafe {
        use std::os::windows::io::AsRawSocket;
        use windows::Win32::Networking::WinSock::{setsockopt, SOCKET, SOL_SOCKET, SO_RCVBUF};
        let v: i32 = 4 * 1024 * 1024;
        let _ = setsockopt(SOCKET(sock.as_raw_socket() as usize), SOL_SOCKET, SO_RCVBUF, Some(&v.to_ne_bytes()));
    }
    crate::log::logln!("待ち受け中: {bind}(合言葉あり)");
    let sessions: Sessions = Arc::new(Mutex::new(HashMap::new()));
    let _ = REGISTRY.set(sessions.clone());
    let mut bad_seen: HashMap<std::net::IpAddr, Instant> = HashMap::new();
    let mut hello_seen: HashMap<[u8; 12], Instant> = HashMap::new();
    let mut probe_tx =Sealer::new(cipher.clone(), crypto::DIR_TO_CLIENT);   // 問い合わせへの返事用

    // 受信: 接続要求・生存通知・操作(配信ごとのスレッドは、接続要求のたびに作る)
    let mut buf = [0u8; 2048];
    loop {
        let Ok((n, from)) = sock.recv_from(&mut buf) else { continue };
        let Some(plain) = crypto::open(&cipher, crypto::DIR_TO_HOST, &buf[..n]) else {
            // 復号できない(鍵が違う、壊れた、無関係なパケット)。診断のため、送り元ごとに30秒に1回だけ記録する
            if bad_seen.len() > 1024 { bad_seen.retain(|_, t| t.elapsed() < Duration::from_secs(30)); }
            let t = bad_seen.entry(from.ip()).or_insert_with(|| Instant::now() - Duration::from_secs(60));
            if t.elapsed() > Duration::from_secs(30) {
                *t = Instant::now();
                crate::log::logln!("復号できないパケット: {from}(鍵が違う可能性があります。QRで登録し直してください)");
            }
            continue;
        };
        if plain.len() < 5 { continue; }
        let ty = plain[0];
        let seq = u32::from_le_bytes([plain[1], plain[2], plain[3], plain[4]]);
        let body = &plain[5..];

        if ty == 0x15 {
            let _ = sock.send_to(&probe_tx.seal(&[0x05]), from);
            continue;
        }
        if ty == 0x10 {
            if body.len() < 13 { continue; }
            let ts = u64::from_le_bytes(body[5..13].try_into().unwrap()) as i64;
            let now = SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_millis() as i64;
            if (now - ts).abs() > 60_000 {
                crate::log::logln!("接続要求を拒否(時刻のずれ {}秒): {from}(PCかスマホの時計を合わせてください)", (now - ts) / 1000);
                // 理由を知らせる(0x16 [ホストの時刻ms:u64])。スマホは、ずれの大きさを表示できる
                let mut m = vec![0x16u8]; m.extend_from_slice(&(now as u64).to_le_bytes());
                let _ = sock.send_to(&probe_tx.seal(&m), from);
                continue;
            }
            // 同じ暗号文の再送(記録の再生)は受け付けない。正規の再送は、毎回ナンスが違う
            hello_seen.retain(|_, t| t.elapsed() < Duration::from_secs(120));
            let mut nonce = [0u8; 12]; nonce.copy_from_slice(&buf[..12]);
            if hello_seen.insert(nonce, Instant::now()).is_some() { continue; }
            let display = body[0] as usize;
            let kbps = u32::from_le_bytes([body[1], body[2], body[3], body[4]]);
            let want_audio = body.get(13).map_or(false, |f| f & 1 != 0);
            let want_opus = body.get(13).map_or(false, |f| f & 2 != 0);
            let mut map = sessions.lock().unwrap();
            if !map.contains_key(&from) && map.len() >= MAX_SESSIONS {
                crate::log::logln!("接続要求を拒否(同時接続の上限 {MAX_SESSIONS}): {from}");
                continue;
            }
            if let Some(old) = map.get(&from) {
                let mut o = old.lock().unwrap();
                if o.alive && o.display == display {
                    o.last_seen = Instant::now(); o.want_idr = true; o.want_info = true; o.last_seq = seq; // 再送された要求(映像情報も送り直す)
                    continue;
                }
                o.alive = false; // 別のディスプレイに変わった: 前の配信は終わらせる
            }
            crate::log::logln!("接続要求: {from} ディスプレイ{display} {kbps}kbps");
            let sess = Arc::new(Mutex::new(Session {
                display, kbps: kbps.max(1000), last_seen: Instant::now(), want_idr: true, want_info: false, alive: true, audio: want_audio, opus: want_opus, held_keys: Vec::new(), held_btns: Vec::new(),
                origin: (0, 0), last_seq: seq, recent: VecDeque::new(), want_shapes: Vec::new(),
                peer: from.to_string(), width: 0, height: 0, fps_now: 0.0, mbps: 0.0, enc_ms: 0.0,
                since: SystemTime::now().duration_since(UNIX_EPOCH).map_or(0, |d| d.as_secs()),
            }));
            map.insert(from, sess.clone());
            let (sock2, sessions2, cipher2) = (sock.clone(), sessions.clone(), cipher.clone());
            std::thread::spawn(move || {
                match stream(&sock2, &sess, from, cipher2) {
                    Ok(()) => crate::log::logln!("配信を終えました: {from}"),
                    Err(e) => crate::log::logln!("配信エラー({from}): {e:#}"),
                }
                release_held(&sess);
                let mut m = sessions2.lock().unwrap();
                if m.get(&from).map_or(false, |c| Arc::ptr_eq(c, &sess)) { m.remove(&from); }
            });
            continue;
        }

        let Some(sess) = sessions.lock().unwrap().get(&from).cloned() else { continue };
        let mut s = sess.lock().unwrap();
        // 重複(2回送られるもの)と古いものを捨てる
        let reliable = matches!(ty, 0x22 | 0x24 | 0x25 | 0x14 | 0x26);
        if reliable {
            if s.recent.contains(&seq) { continue; }
            if (seq.wrapping_sub(s.last_seq) as i32) < -256 { continue; }   // 窓より古い再送(記録の再生)
            s.recent.push_back(seq);
            if s.recent.len() > 64 { s.recent.pop_front(); }
        } else if ty != 0x11 && (seq.wrapping_sub(s.last_seq) as i32) <= 0 {
            continue;
        }
        if !reliable && (seq.wrapping_sub(s.last_seq) as i32) > 0 { s.last_seq = seq; }
        s.last_seen = Instant::now();
        if !s.alive { continue; }   // 終わった配信の入力は、受け付けない(押しっぱなしを作らないため)
        let origin = s.origin;
        if matches!(ty, 0x20..=0x25) { crate::desktop::attach_throttled(); }   // ロック画面・UAC のとき、入力を受けているデスクトップへつなぐ
        match ty {
            0x12 => s.want_idr = true,
            0x14 if body.len() >= 4 => s.want_shapes.push(u32::from_le_bytes([body[0], body[1], body[2], body[3]])),
            0x13 => { s.alive = false; crate::log::logln!("終了: {from}"); }
            0x20 if body.len() >= 4 => input::mouse_rel(i16::from_le_bytes([body[0], body[1]]) as i32, i16::from_le_bytes([body[2], body[3]]) as i32),
            0x21 if body.len() >= 4 => input::mouse_abs(origin.0 + i16::from_le_bytes([body[0], body[1]]) as i32, origin.1 + i16::from_le_bytes([body[2], body[3]]) as i32),
            0x22 if body.len() >= 2 => {
                let down = body[1] != 0;
                s.held_btns.retain(|b| *b != body[0]);
                if down { s.held_btns.push(body[0]); }
                input::button(body[0], down);
            }
            0x23 if body.len() >= 2 => input::scroll(i16::from_le_bytes([body[0], body[1]]) as i32),
            0x24 if body.len() >= 3 => {
                let vk = u16::from_le_bytes([body[0], body[1]]);
                let down = body[2] != 0;
                s.held_keys.retain(|k| *k != vk);
                if down { s.held_keys.push(vk); }
                input::key(vk, down);
            }
            0x25 => if let Ok(t) = std::str::from_utf8(body) { input::text(t) },
            0x30 => crate::camera::push(from.ip(), body),   // スマホのカメラ映像(組み立ては、端末単位)
            0x26 => if crate::desktop::enabled() { crate::secure::request_sas(); },   // Ctrl+Alt+Del(補助プロセスのときだけ)
            _ => {}
        }
    }
}

/// 配信が終わったとき(切断、タイムアウト、エラー)に、押されたままの入力をすべて離す。
fn release_held(sess: &Mutex<Session>) {
    let (keys, btns) = {
        let mut s = sess.lock().unwrap();
        s.alive = false;
        (std::mem::take(&mut s.held_keys), std::mem::take(&mut s.held_btns))
    };
    for b in btns { input::button(b, false); }
    for k in keys { input::key(k, false); }
}

fn stream(sock: &UdpSocket, sess: &Mutex<Session>, client: SocketAddr, cipher: Aes256Gcm) -> Result<()> {
    let mut tx = Sealer::new(cipher, crypto::DIR_TO_CLIENT);
    let (display, kbps, want_audio, want_opus) = { let s = sess.lock().unwrap(); (s.display, s.kbps, s.audio, s.opus) };
    let st = crate::settings::get();
    let fps = st.fps;
    let kbps = if st.max_kbps > 0 { kbps.min(st.max_kbps) } else { kbps };
    crate::desktop::attach();
    // 画面の取得と符号化。ロック画面や UAC で入力デスクトップが変わったら、作り直す(そのため Option で持つ)
    let mut cap_o = Some(Capturer::open(display)?);
    let mut enc_o: Option<Encoder> = { let c = cap_o.as_ref().unwrap(); Some(Encoder::new(&c.device, &c.frame, c.width, c.height, fps, kbps)?) };
    let (mut origin, mut dims) = { let c = cap_o.as_ref().unwrap(); ((c.origin_x, c.origin_y), (c.width, c.height)) };
    let mut last_desk = Instant::now();
    { let mut g = sess.lock().unwrap(); g.origin = (origin.0, origin.1); g.width = dims.0; g.height = dims.1; }
    crate::log::logln!("配信開始: ディスプレイ{display} {}x{} 位置({}, {}) → {client}", dims.0, dims.1, origin.0, origin.1);

    // 音声(端末が求めたときだけ。この変数が生きている間、取り込む)
    let _audio = if want_audio {
        sock.try_clone().ok().map(|s| crate::audio::start(s, client, Sealer::new(tx.cipher_clone(), crypto::DIR_TO_CLIENT), want_opus))
    } else { None };

    // 映像情報(取りこぼしに備えて数回送る)
    let make_info = |d: (u32, u32)| {
        let mut v = vec![0x01];
        v.extend_from_slice(&(d.0 as u16).to_le_bytes());
        v.extend_from_slice(&(d.1 as u16).to_le_bytes());
        v.push(fps as u8);
        v.push(1);
        v
    };
    let mut info = make_info(dims);
    for _ in 0..3 { sock.send_to(&tx.seal(&info), client)?; }

    let mut frame_id: u32 = 0;
    let mut last_sent = Instant::now();
    let (mut sent_frames, mut sent_bytes, mut stat_t, mut enc_ms) = (0u32, 0usize, Instant::now(), 0f64);
    let mut have_frame = false;
    let mut last_cursor = (i32::MIN, i32::MIN, false, 0u32);
    let mut shapes: HashMap<u32, cursor::Shape> = HashMap::new();
    let mut last_handle: isize = 0;
    let mut cur_shape: u32 = 0;
    let mut last_cursor_sent = Instant::now();
    loop {
        let (alive, want_idr) = {
            let mut s = sess.lock().unwrap();
            let alive = s.alive && s.last_seen.elapsed() < Duration::from_secs(3);
            (alive, std::mem::take(&mut s.want_idr))
        };
        if !alive { return Ok(()); }
        if std::mem::take(&mut sess.lock().unwrap().want_info) { sock.send_to(&tx.seal(&info), client)?; }

        // カーソルの形(映像には写らない)。変わったときに画像を取り出して送る。端末が足りないと言ってきた分も送り直す
        if let Some(hh) = cursor::current_handle() {
            if hh != last_handle && hh != 0 {
                last_handle = hh;
                if let Some(sh) = cursor::capture(hh) {
                    cur_shape = sh.id;
                    if !shapes.contains_key(&sh.id) {
                        send_shape(sock, client, &mut tx, &sh)?;
                        if shapes.len() > 64 { shapes.clear(); }
                        shapes.insert(sh.id, sh);
                    }
                } else {
                    cur_shape = 0;   // 取り出せない形は、端末では矢印にする(前の形のまま固まらないように)
                }
            }
        }
        let wanted = std::mem::take(&mut sess.lock().unwrap().want_shapes);
        for id in wanted {
            if let Some(sh) = shapes.get(&id) { send_shape(sock, client, &mut tx, sh)?; }
        }

        // カーソルの位置(映像には写らないので、別に送る。変化したとき、または1秒ごと)
        if let Some((x, y, vis)) = input::cursor() {
            let c = (x - origin.0, y - origin.1, vis, cur_shape);
            if c != last_cursor || last_cursor_sent.elapsed() > Duration::from_secs(1) {
                last_cursor = c; last_cursor_sent = Instant::now();
                let mut p = vec![0x03];
                p.extend_from_slice(&(c.0.clamp(-32768, 32767) as i16).to_le_bytes());
                p.extend_from_slice(&(c.1.clamp(-32768, 32767) as i16).to_le_bytes());
                p.push(c.2 as u8);
                p.extend_from_slice(&c.3.to_le_bytes());
                sock.send_to(&tx.seal(&p), client)?;
            }
        }

        // 送信は FPS を上限にする(240Hz のディスプレイでも無駄に送らない)。取る前に待つので、最新のフレームを符号化できる
        let min_gap = Duration::from_micros(1_000_000 / fps as u64);
        if have_frame {
            let e = last_sent.elapsed();
            if e < min_gap { std::thread::sleep(min_gap - e); }
        }
        // ロック画面・UAC への対応(SYSTEM のときだけ): 入力を受けているデスクトップが変わったら、画面の取得をやり直す
        let mut reopen = false;
        if crate::desktop::enabled() && last_desk.elapsed() > Duration::from_millis(150) {
            last_desk = Instant::now();
            if crate::desktop::attach() { reopen = true; }
        }
        let changed = match cap_o.as_mut().unwrap().next(8) {
            Ok(c) => c,
            Err(e) => { if crate::desktop::enabled() { reopen = true; false } else { return Err(e); } }
        };
        if reopen {
            drop(enc_o.take()); drop(cap_o.take());   // 古い複製を先に手放す(同じ画面を二重に複製できないため)
            let t0 = Instant::now();
            loop {
                crate::desktop::attach();
                let pair = Capturer::open(display).and_then(|c| Encoder::new(&c.device, &c.frame, c.width, c.height, fps, kbps).map(|e| (c, e)));
                match pair {
                    Ok((c, e)) => {
                        origin = (c.origin_x, c.origin_y); dims = (c.width, c.height);
                        { let mut g = sess.lock().unwrap(); g.origin = origin; g.width = dims.0; g.height = dims.1; g.want_idr = true; g.want_info = true; }
                        info = make_info(dims);
                        cap_o = Some(c); enc_o = Some(e); have_frame = false;
                        crate::log::logln!("画面の取得をやり直しました(デスクトップの切り替え) → {client}");
                        break;
                    }
                    Err(e) => {
                        if t0.elapsed() > Duration::from_secs(10) || !sess.lock().unwrap().alive { return Err(e); }
                        std::thread::sleep(Duration::from_millis(150));
                    }
                }
            }
            continue;
        }
        if changed { have_frame = true; }
        // 画面が静止していても、0.2秒ごとに送り直す(接続の維持と、欠けたときの復帰のため)
        let idle_resend = !changed && have_frame && last_sent.elapsed() > Duration::from_millis(200);
        if !changed && !idle_resend && !want_idr { continue; }
        if !have_frame { continue; }

        let t = Instant::now();
        let key = want_idr || frame_id == 0;
        let data = enc_o.as_mut().unwrap().encode(key)?;
        enc_ms += t.elapsed().as_secs_f64() * 1000.0;
        let count = (data.len() + MTU_PAYLOAD - 1) / MTU_PAYLOAD;
        for (i, chunk) in data.chunks(MTU_PAYLOAD).enumerate() {
            let mut p = Vec::with_capacity(chunk.len() + 10);
            p.push(0x02);
            p.push(key as u8);
            p.extend_from_slice(&frame_id.to_le_bytes());
            p.extend_from_slice(&(i as u16).to_le_bytes());
            p.extend_from_slice(&(count as u16).to_le_bytes());
            p.extend_from_slice(chunk);
            sock.send_to(&tx.seal(&p), client)?;
        }
        frame_id = frame_id.wrapping_add(1);
        last_sent = Instant::now();
        sent_frames += 1;
        sent_bytes += data.len();

        if stat_t.elapsed() >= Duration::from_secs(2) {
            let secs = stat_t.elapsed().as_secs_f64();
            { let mut g = sess.lock().unwrap(); g.fps_now = (sent_frames as f64 / secs) as f32; g.mbps = (sent_bytes as f64 * 8.0 / secs / 1e6) as f32; g.enc_ms = (enc_ms / sent_frames.max(1) as f64) as f32; }
            sent_frames = 0; sent_bytes = 0; enc_ms = 0.0; stat_t = Instant::now();
        }
    }
}

/// カーソルの形を、1200バイトずつに分けて送る。
fn send_shape(sock: &UdpSocket, client: SocketAddr, tx: &mut Sealer, sh: &cursor::Shape) -> Result<()> {
    let count = ((sh.rgba.len() + MTU_PAYLOAD - 1) / MTU_PAYLOAD).max(1);
    for (i, chunk) in sh.rgba.chunks(MTU_PAYLOAD).enumerate() {
        let mut p: Vec<u8> = Vec::with_capacity(chunk.len() + 19);
        p.push(0x04);
        p.extend_from_slice(&sh.id.to_le_bytes());
        p.extend_from_slice(&sh.w.to_le_bytes());
        p.extend_from_slice(&sh.h.to_le_bytes());
        p.extend_from_slice(&sh.hot_x.to_le_bytes());
        p.extend_from_slice(&sh.hot_y.to_le_bytes());
        p.push(sh.busy as u8);
        p.extend_from_slice(&(i as u16).to_le_bytes());
        p.extend_from_slice(&(count as u16).to_le_bytes());
        p.extend_from_slice(chunk);
        sock.send_to(&tx.seal(&p), client)?;
    }
    Ok(())
}
