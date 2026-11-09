//! ホストの設定: 鍵(合言葉)の保存と、端末に読ませるペアリング用の URL を作る。
//!
//! 保存先: %APPDATA%\Deskport\secret.txt(256 ビットのランダム値を 16 進で 64 文字)。なければ最初の起動で作る。
//! ペアリング URL: deskport://pair?n=<PC名>&p=<ポート>&k=<鍵>&a=<アドレス>&a=<アドレス>...
//!   QR コードにして画面に出し、端末が読む。鍵が入っているので、QR は他人に見せないこと。
use anyhow::{Context, Result};
use std::path::PathBuf;

pub const PORT: u16 = 49000;

/// ファイアウォールで受ける相手。同じネットワーク、Tailscale、ほかの VPN(プライベートアドレス)まで。
/// インターネット側のアドレスからは届かない。
pub const REMOTE_IP: &str = "LocalSubnet,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16,100.64.0.0/10,fe80::/10,fc00::/7";

pub fn dir() -> PathBuf {
    let base = PathBuf::from(std::env::var("APPDATA").unwrap_or_else(|_| ".".into()));
    let new = base.join("Deskport");
    // 改名前(FoldRemote)の保存先があれば、そのまま引き継ぐ(鍵も残る)
    let old = base.join("FoldRemote");
    if !new.exists() && old.exists() {
        let _ = std::fs::rename(&old, &new);
    }
    new
}

fn path() -> PathBuf {
    dir().join("secret.txt")
}

fn generate() -> Result<String> {
    let mut b = [0u8; 32];
    getrandom::getrandom(&mut b).map_err(|e| anyhow::anyhow!("乱数を取得できません: {e}"))?;
    let s: String = b.iter().map(|x| format!("{x:02x}")).collect();
    std::fs::create_dir_all(dir()).context("設定の保存先を作れません")?;
    std::fs::write(path(), &s).context("鍵を保存できません")?;
    Ok(s)
}

/// 保存された鍵。なければ作る。
pub fn secret() -> Result<String> {
    if let Ok(s) = std::fs::read_to_string(path()) {
        let s = s.trim().to_string();
        if s.len() == 64 && s.bytes().all(|c| c.is_ascii_hexdigit()) {
            return Ok(s);
        }
    }
    generate()
}

/// 鍵を作り直す(これまでに登録した端末は、つながらなくなる)。
pub fn regenerate() -> Result<String> {
    generate()
}

fn pc_name() -> String {
    std::env::var("COMPUTERNAME").unwrap_or_else(|_| "PC".into())
}

fn encode(s: &str) -> String {
    s.bytes()
        .map(|b| if b.is_ascii_alphanumeric() || b"-._~".contains(&b) { (b as char).to_string() } else { format!("%{b:02X}") })
        .collect()
}

/// このPCについている IPv4 アドレス(インターフェース名, IP)。仮想アダプタや自動割り当てのものは除く。
/// VPN の種類は問わない(Tailscale、WireGuard、ZeroTier など、何でも入る)。LAN のアドレスを先にする。
pub fn detected() -> Vec<(String, String)> {
    let mut out: Vec<(String, std::net::Ipv4Addr, String)> = Vec::new();
    for itf in if_addrs::get_if_addrs().unwrap_or_default() {
        let std::net::IpAddr::V4(ip) = itf.ip() else { continue };
        let name = itf.name.to_lowercase();
        if ip.is_loopback() || ip.is_link_local() || ["vethernet", "vmware", "virtualbox", "loopback", "wsl", "docker"].iter().any(|v| name.contains(v)) {
            continue;
        }
        out.push((itf.name.clone(), ip, ip.to_string()));
    }
    out.sort_by_key(|(_, ip, _)| !ip.is_private());   // LAN(プライベートアドレス)を先に
    out.into_iter().map(|(n, _, s)| (n, s)).collect()
}

/// 窓に出すアドレスの一覧: (名前, アドレス, QR に入れるか, 追加したものか)
pub fn addresses() -> Vec<(String, String, bool, bool)> {
    let st = crate::settings::get();
    let mut v: Vec<(String, String, bool, bool)> = detected().into_iter().map(|(n, ip)| { let inc = !st.excluded_addresses.contains(&ip); (n, ip, inc, false) }).collect();
    for a in st.extra_addresses { if !v.iter().any(|x| x.1 == a) { v.push(("追加".into(), a, true, true)); } }
    v
}

pub fn pairing_url(secret: &str) -> String {
    let mut u = format!("deskport://pair?n={}&p={}&d={}&k={}", encode(&pc_name()), PORT, crate::capture::count().min(4), secret);
    for (_, ip, included, _) in addresses() {
        if included {
            u.push_str("&a=");
            u.push_str(&ip);
        }
    }
    u
}
