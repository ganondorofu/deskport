//! 窓(ui/index.html)から呼ばれる命令。
use crate::{camera, files, capture, config, install, log, secure, serve, settings, sys, update};
use serde::Serialize;

#[derive(Serialize)]
pub struct Addr {
    label: String,
    ip: String,
    included: bool,
    extra: bool,
}

/// 変わらない(または、操作したときだけ変わる)情報。
#[derive(Serialize)]
pub struct Info {
    version: String,
    installed: bool,
    autostart: bool,
    addresses: Vec<Addr>,
    display_count: usize,
    port: u16,
    settings: settings::Settings,
}

/// 1秒ごとに読む、動的な状態。
#[derive(Serialize)]
pub struct Live {
    sessions: Vec<serve::SessionInfo>,
    update: Option<String>,
}

#[tauri::command]
pub fn info() -> Info {
    Info {
        version: update::VERSION.to_string(),
        installed: install::is_installed_copy(),
        autostart: sys::autostart_on(),
        addresses: config::addresses().into_iter().map(|(label, ip, included, extra)| Addr { label, ip, included, extra }).collect(),
        display_count: capture::count(),
        port: config::PORT,
        settings: settings::get(),
    }
}

#[tauri::command]
pub fn live() -> Live {
    Live { sessions: serve::snapshot(), update: update::available().map(|a| a.version) }
}

/// 登録用の QR コード(SVG)。鍵が入っているので、窓の外には出さない。
#[tauri::command]
pub fn pair_svg() -> Result<String, String> {
    let secret = config::secret().map_err(|e| format!("{e:#}"))?;
    let url = config::pairing_url(&secret);
    let code = qrcode::QrCode::new(url.as_bytes()).map_err(|e| e.to_string())?;
    Ok(code.render::<qrcode::render::svg::Color>().min_dimensions(260, 260).quiet_zone(true).build())
}

#[tauri::command]
pub fn secure_status() -> secure::Status { secure::status() }

/// ロック画面・UAC への対応を有効にする(管理者権限の確認が出る。待つ間も窓が固まらないよう、別のスレッドで)。
#[tauri::command]
pub async fn secure_enable() -> Result<(), String> {
    tauri::async_runtime::spawn_blocking(|| secure::enable().map_err(|e| format!("{e:#}"))).await.map_err(|e| e.to_string())?
}

#[tauri::command]
pub async fn secure_disable() -> Result<(), String> {
    tauri::async_runtime::spawn_blocking(|| secure::disable().map_err(|e| format!("{e:#}"))).await.map_err(|e| e.to_string())?
}

/// スマホのカメラを PC のカメラにする機能が、有効か(仮想カメラの DLL が登録されているか)。
#[tauri::command]
pub fn camera_status() -> bool { camera::installed() }

#[tauri::command]
pub async fn camera_enable() -> Result<(), String> {
    tauri::async_runtime::spawn_blocking(|| {
        if !secure::run_elevated(&["vcam-setup", "enable"]) { return Err("管理者権限の確認が通りませんでした".to_string()); }
        if camera::installed() { Ok(()) } else { Err("有効にできませんでした".to_string()) }
    }).await.map_err(|e| e.to_string())?
}

#[tauri::command]
pub async fn camera_disable() -> Result<(), String> {
    tauri::async_runtime::spawn_blocking(|| {
        if !secure::run_elevated(&["vcam-setup", "disable"]) { return Err("管理者権限の確認が通りませんでした".to_string()); }
        Ok(())
    }).await.map_err(|e| e.to_string())?
}

#[derive(Serialize)]
pub struct FileInfo { name: String, size: u64, mtime: u64 }
#[derive(Serialize)]
pub struct FilesInfo { dir: String, files: Vec<FileInfo> }

/// 窓の「ファイル」タブ: スマホとやりとりするファイルの置き場所と、その中のファイル。
#[tauri::command]
pub fn files_info() -> FilesInfo {
    FilesInfo {
        dir: files::dir().display().to_string(),
        files: files::entries().into_iter().map(|(name, size, mtime)| FileInfo { name, size, mtime }).collect(),
    }
}

#[tauri::command]
pub fn files_open_dir() -> bool { files::open_dir() }

#[tauri::command]
pub fn files_reveal(name: String) -> bool { files::exact_file(&name).map_or(false, |p| files::reveal(&p)) }

#[tauri::command]
pub fn files_delete(name: String) -> Result<(), String> {
    let p = files::exact_file(&name).ok_or("見つかりません")?;
    std::fs::remove_file(&p).map_err(|e| e.to_string())?;
    log::logln!("ファイルを消しました: {}", p.display());
    Ok(())
}

/// スマホに送るファイルを、PC で選んで、置き場所に追加する(選ぶ画面は、Windows 標準のもの)。追加した数を返す。
#[tauri::command]
pub async fn files_add() -> Result<usize, String> {
    tauri::async_runtime::spawn_blocking(|| {
        use std::os::windows::process::CommandExt;
        let script = "[Console]::OutputEncoding = [Text.Encoding]::UTF8; Add-Type -AssemblyName System.Windows.Forms; \
            $f = New-Object System.Windows.Forms.Form; $f.TopMost = $true; \
            $d = New-Object System.Windows.Forms.OpenFileDialog; $d.Multiselect = $true; $d.Title = 'Deskport'; \
            if ($d.ShowDialog($f) -eq 'OK') { $d.FileNames -join \"`n\" }";
        let out = std::process::Command::new("powershell").args(["-NoProfile", "-STA", "-Command", script]).creation_flags(crate::sys::NO_WINDOW).output().map_err(|e| e.to_string())?;
        let text = String::from_utf8_lossy(&out.stdout).to_string();
        let mut n = 0;
        for line in text.lines().map(str::trim).filter(|l| !l.is_empty()) {
            if files::add_copy(std::path::Path::new(line)).is_some() { n += 1; }
        }
        if n > 0 { log::logln!("スマホに送るファイルを {n} 件追加しました"); }
        Ok(n)
    }).await.map_err(|e| e.to_string())?
}

#[tauri::command]
pub fn save_settings(fps: u32, max_kbps: u32) -> Result<(), String> {
    let mut s = settings::get();
    s.fps = fps;
    s.max_kbps = max_kbps;
    settings::set(s).map_err(|e| e.to_string())?;
    log::logln!("設定を保存しました: {fps}fps、上限 {max_kbps}kbps(次の接続から)");
    Ok(())
}

/// QR に入れるアドレスの設定(追加したアドレスと、除くアドレス)。
#[tauri::command]
pub fn save_addresses(extra: Vec<String>, excluded: Vec<String>) -> Result<(), String> {
    let mut s = settings::get();
    s.extra_addresses = extra;
    s.excluded_addresses = excluded;
    settings::set(s).map_err(|e| e.to_string())
}

#[tauri::command]
pub fn disconnect(peer: String) {
    serve::disconnect(&peer);
}

#[tauri::command]
pub fn set_autostart(on: bool) {
    let exe = if on && install::installed_exe().exists() { install::installed_exe() } else { std::env::current_exe().unwrap_or_default() };
    sys::set_autostart_for(on, &exe.display().to_string());
}

/// 鍵を作り直して、新しい鍵で動かし直す(登録済みの端末は、つながらなくなる)。
#[tauri::command]
pub fn regenerate_key() -> Result<(), String> {
    config::regenerate().map_err(|e| format!("{e:#}"))?;
    log::logln!("鍵を作り直しました。再起動します");
    let exe = std::env::current_exe().map_err(|e| e.to_string())?;
    std::process::Command::new(exe).args(["--restart", "--no-pair"]).spawn().map_err(|e| e.to_string())?;
    std::process::exit(0);
}

#[tauri::command]
pub fn check_update() -> Result<Option<String>, String> {
    update::check_and_store().map(|a| a.map(|a| a.version)).map_err(|e| format!("{e:#}"))
}

#[tauri::command]
pub fn apply_update() -> Result<(), String> {
    let a = update::available().ok_or("更新はありません")?;
    update::apply(&a).map_err(|e| format!("{e:#}"))
}

/// このまま動かしているものを、インストールする(配置、自動起動、スタートメニュー、ファイアウォール)。
#[tauri::command]
pub fn install_here() -> Result<(), String> {
    install::install().map_err(|e| format!("{e:#}"))?;
    install::launch_installed().map_err(|e| format!("{e:#}"))?;
    // 窓に「完了」を見せてから終わる(新しく起動したものが、窓を出す)
    std::thread::spawn(|| { std::thread::sleep(std::time::Duration::from_millis(1800)); std::process::exit(0); });
    Ok(())
}

#[tauri::command]
pub fn get_log() -> Vec<String> {
    log::lines()
}
