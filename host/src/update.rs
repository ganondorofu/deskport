//! 自動更新(PC側)と、配布物の署名ツール。
//!
//! 配布の形: GitHub Releases の最新版に、次の3つ以上を置く。
//!   manifest.json  … 更新情報 {"host":{"version","file","sha256"},"app":{"versionCode","versionName","file","sha256"}}
//!   manifest.sig   … manifest.json の ed25519 署名(64バイト)
//!   deskport-host.exe, deskport.apk
//! 更新の手順: manifest と署名を取る → 署名を、exe に埋め込んだ公開鍵で確かめる → 新しければ exe を取る
//!   → sha256 を manifest と照らす → 置き換えて再起動する。署名が合わなければ何もしない
//!   (配布元が乗っ取られても、偽の exe は入らない。秘密鍵は、署名する PC にだけ置く)。
use anyhow::{bail, Context, Result};
use ed25519_dalek::{Signature, Signer, SigningKey, Verifier, VerifyingKey};
use sha2::{Digest, Sha256};
use std::io::Read;
use std::path::PathBuf;

/// 配布元(GitHub の「ユーザー名/リポジトリ名」)。
pub const REPO: &str = "ganondorofu/deskport";
/// 署名の公開鍵(16進)。`deskport-host keygen` で作ったものを入れる。空の間は、更新の確認を行わない。
const PUBLIC_KEY_HEX: &str = "c8ce85c9096b59633bb1ef4098ba15735de2576fd27f67096c98e478d1c964e0";
pub const VERSION: &str = env!("CARGO_PKG_VERSION");

fn base_url() -> String {
    format!("https://github.com/{REPO}/releases/latest/download")
}

fn hex(b: &[u8]) -> String {
    b.iter().map(|x| format!("{x:02x}")).collect()
}

fn unhex(s: &str) -> Result<Vec<u8>> {
    if s.len() % 2 != 0 { bail!("16進の長さが奇数です"); }
    (0..s.len()).step_by(2).map(|i| u8::from_str_radix(&s[i..i + 2], 16).map_err(|e| anyhow::anyhow!("{e}"))).collect()
}

fn get(url: &str, limit: u64) -> Result<Vec<u8>> {
    let resp = ureq::get(url).timeout(std::time::Duration::from_secs(30)).call().with_context(|| format!("取得できません: {url}"))?;
    let mut out = Vec::new();
    resp.into_reader().take(limit).read_to_end(&mut out)?;
    Ok(out)
}

static AVAILABLE: std::sync::Mutex<Option<Available>> = std::sync::Mutex::new(None);

/// 見つかっている新しい版(なければ None)。
pub fn available() -> Option<Available> {
    AVAILABLE.lock().unwrap().clone()
}

/// 確認して、結果を覚える。
pub fn check_and_store() -> Result<Option<Available>> {
    let a = check()?;
    *AVAILABLE.lock().unwrap() = a.clone();
    Ok(a)
}

fn ver(s: &str) -> Vec<u32> {
    s.split('.').map(|p| p.parse().unwrap_or(0)).collect()
}

#[derive(Clone, Debug)]
pub struct Available {
    pub version: String,
    file: String,
    sha256: String,
}

/// 新しい版があれば返す。署名が合わないときは、エラー(何も信用しない)。
pub fn check() -> Result<Option<Available>> {
    if PUBLIC_KEY_HEX.is_empty() { return Ok(None); }
    let manifest = get(&format!("{}/manifest.json", base_url()), 64 * 1024)?;
    let sig = get(&format!("{}/manifest.sig", base_url()), 256)?;
    let key = VerifyingKey::from_bytes(&unhex(PUBLIC_KEY_HEX)?.try_into().map_err(|_| anyhow::anyhow!("公開鍵の長さが違います"))?)?;
    let sig = Signature::from_slice(&sig).context("署名の形式が違います")?;
    key.verify(&manifest, &sig).context("更新情報の署名が合いません(配布元が改ざんされた可能性があります)")?;
    let v: serde_json::Value = serde_json::from_slice(&manifest)?;
    let h = &v["host"];
    let version = h["version"].as_str().context("version がありません")?.to_string();
    if ver(&version) <= ver(VERSION) { return Ok(None); }
    Ok(Some(Available {
        version,
        file: h["file"].as_str().context("file がありません")?.to_string(),
        sha256: h["sha256"].as_str().context("sha256 がありません")?.to_lowercase(),
    }))
}

/// 新しい版を取って、置き換えて、再起動する(成功したら、このプロセスは終わる)。インストール済みのものでだけ行う。
pub fn apply(a: &Available) -> Result<()> {
    if !crate::install::is_installed_copy() { bail!("インストールされたもの以外は、自動では更新しません"); }
    if a.file.contains('/') || a.file.contains('\\') { bail!("ファイル名が不正です"); }
    let data = get(&format!("{}/{}", base_url(), a.file), 200 * 1024 * 1024)?;
    if hex(&Sha256::digest(&data)) != a.sha256 { bail!("ダウンロードしたファイルが、更新情報と合いません"); }
    let dir = crate::install::install_dir();
    let new = dir.join("deskport-host.new.exe");
    std::fs::write(&new, &data)?;
    // 動いている間は上書きできないので、終わってから置き換えて起動する
    let exe = crate::install::installed_exe();
    use std::os::windows::process::CommandExt;
    std::process::Command::new("cmd")
        .args(["/C", &format!("ping -n 3 127.0.0.1 >nul & move /Y \"{}\" \"{}\" & start \"\" \"{}\" --restart --no-pair", new.display(), exe.display(), exe.display())])
        .creation_flags(crate::sys::NO_WINDOW).spawn()?;
    std::process::exit(0);
}

// ---- 配布物の署名(開発者用) ----
fn key_path() -> PathBuf {
    PathBuf::from(std::env::var("DESKPORT_KEYS").unwrap_or_else(|_| "deskport-keys".into())).join("release.key")
}

/// deskport-host keygen … 署名用の鍵を作る。秘密鍵は保存し、公開鍵を表示する(update.rs の PUBLIC_KEY_HEX に入れる)。
pub fn keygen() -> Result<()> {
    let p = key_path();
    if p.exists() { bail!("すでにあります: {}(上書きしません)", p.display()); }
    let mut seed = [0u8; 32];
    getrandom::getrandom(&mut seed).map_err(|e| anyhow::anyhow!("{e}"))?;
    std::fs::create_dir_all(p.parent().unwrap())?;
    std::fs::write(&p, hex(&seed))?;
    let sk = SigningKey::from_bytes(&seed);
    println!("秘密鍵: {}(他人に渡さない。なくすと、更新を出せなくなる)", p.display());
    println!("公開鍵: {}", hex(sk.verifying_key().as_bytes()));
    Ok(())
}

/// deskport-host release <host.exe> <app.apk> <apkのversionCode> <apkのversionName> <出力先> … 更新情報を作って署名する。
pub fn release(args: &[String]) -> Result<()> {
    let [exe, apk, code, name, out] = args else { bail!("使い方: deskport-host release <host.exe> <app.apk> <versionCode> <versionName> <出力先>") };
    let seed: [u8; 32] = unhex(std::fs::read_to_string(key_path()).context("署名用の鍵がありません(先に keygen)")?.trim())?.try_into().map_err(|_| anyhow::anyhow!("鍵の長さが違います"))?;
    let sk = SigningKey::from_bytes(&seed);
    let out = PathBuf::from(out);
    std::fs::create_dir_all(&out)?;
    let (e, a) = (std::fs::read(exe)?, std::fs::read(apk)?);
    std::fs::write(out.join("deskport-host.exe"), &e)?;
    std::fs::write(out.join("deskport.apk"), &a)?;
    let manifest = serde_json::json!({
        "host": { "version": VERSION, "file": "deskport-host.exe", "sha256": hex(&Sha256::digest(&e)) },
        "app": { "versionCode": code.parse::<u32>()?, "versionName": name, "file": "deskport.apk", "sha256": hex(&Sha256::digest(&a)) },
    });
    let bytes = serde_json::to_vec_pretty(&manifest)?;
    std::fs::write(out.join("manifest.json"), &bytes)?;
    std::fs::write(out.join("manifest.sig"), sk.sign(&bytes).to_bytes())?;
    println!("作りました: {}(manifest.json, manifest.sig, deskport-host.exe, deskport.apk)", out.display());
    Ok(())
}
