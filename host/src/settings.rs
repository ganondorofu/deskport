//! ホストの設定(%APPDATA%\Deskport\settings.json)。変えた値は、次の接続から効く。
use serde::{Deserialize, Serialize};
use std::sync::Mutex;

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct Settings {
    /// 映像のフレームレートの上限(30 / 60 / 120)
    pub fps: u32,
    /// ビットレートの上限(kbps)。0 なら、端末の要求のまま。
    pub max_kbps: u32,
    /// QR に加えるアドレス(IP や名前。VPN、DDNS など、自動では見つからないもの)
    #[serde(default)]
    pub extra_addresses: Vec<String>,
    /// 自動で見つかったもののうち、QR に入れないアドレス
    #[serde(default)]
    pub excluded_addresses: Vec<String>,
}

static CUR: Mutex<Settings> = Mutex::new(Settings { fps: 60, max_kbps: 0, extra_addresses: Vec::new(), excluded_addresses: Vec::new() });

fn path() -> std::path::PathBuf {
    crate::config::dir().join("settings.json")
}

pub fn load() {
    if let Ok(s) = std::fs::read_to_string(path()) {
        if let Ok(v) = serde_json::from_str::<Settings>(&s) {
            *CUR.lock().unwrap() = sanitize(v);
        }
    }
}

fn sanitize(mut s: Settings) -> Settings {
    if ![30, 60, 120].contains(&s.fps) { s.fps = 60; }
    s.max_kbps = s.max_kbps.min(200_000);
    // アドレスは、英数字と . - : _ だけ(長さも制限)。重複は除く
    let ok = |a: &String| !a.is_empty() && a.len() <= 253 && a.chars().all(|c| c.is_ascii_alphanumeric() || ".-:_".contains(c));
    s.extra_addresses = s.extra_addresses.iter().map(|a| a.trim().to_string()).filter(ok).collect();
    s.extra_addresses.dedup();
    s.excluded_addresses.retain(ok);
    s
}

pub fn get() -> Settings {
    CUR.lock().unwrap().clone()
}

pub fn set(s: Settings) -> std::io::Result<()> {
    let s = sanitize(s);
    *CUR.lock().unwrap() = s.clone();
    std::fs::create_dir_all(crate::config::dir())?;
    std::fs::write(path(), serde_json::to_string_pretty(&s).unwrap())
}
