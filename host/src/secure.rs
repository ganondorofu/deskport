//! ロック画面・UAC・Ctrl+Alt+Del への対応(任意。PC で有効にしたときだけ)。
//!
//! 仕組み(Chrome リモートデスクトップと同じ考え方):
//!   1. Windows サービス「DeskportSecure」(SYSTEM 権限)を入れる。サービスは、いまログインしている画面(コンソールのセッション)に、
//!      補助プロセス「secure-host」を SYSTEM 権限で起動し、落ちたり、セッションが変わったりしたら、起動し直す。
//!   2. secure-host は、通常のホストと同じ配信(serve)を、別のポート(UDP 49001)・別の鍵で行う。
//!      違いは、入力を受けているデスクトップ(通常、ロック画面、UAC)にスレッドをつなぎ直す(desktop.rs)こと。
//!   3. Ctrl+Alt+Del は、secure-host がサービスに頼んで、サービスが SendSAS を呼ぶ(サービスだけが呼べる)。
//!
//! 守り:
//!   - 鍵は、通常のホストと同じもの(ユーザーの secret.txt)を使う。スマホは、登録し直さずに、ポートだけ変えてつなぐ。
//!     注意: secret.txt は、そのユーザーで動くプログラムなら読める。つまり、この対応を有効にしている間は、
//!     そのユーザーのプログラムが、鍵を使って 49001 につなぎ、UAC の確認を操作できてしまう。使わないときは、無効にしておく。
//!   - 実行ファイルは C:\Program Files\Deskport に置く(管理者だけが書ける)。ユーザーが書き換えられる場所のものを SYSTEM で動かさない。
//!   - 有効にする/無効にするは、PC で管理者権限の確認(UAC)を通ったときだけ。スマホからは変えられない。
//!   - 無効にすると、サービス、ファイル、ファイアウォールの規則、鍵を、すべて消す。Windows のポリシーなどは、一切変えない。
use anyhow::{bail, Context, Result};
use std::os::windows::process::CommandExt;
use std::path::PathBuf;
use std::process::Command;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::Duration;

pub const SERVICE: &str = "DeskportSecure";
pub const PORT: u16 = 49001;
const RULE: &str = "Deskport Secure";
const SAS_PIPE: &str = r"\\.\pipe\deskport-secure-sas";
const NO_WINDOW: u32 = 0x0800_0000;

fn program_dir() -> PathBuf {
    PathBuf::from(std::env::var("ProgramFiles").unwrap_or_else(|_| r"C:\Program Files".into())).join("Deskport")
}
fn program_exe() -> PathBuf { program_dir().join("deskport-host.exe") }
fn data_dir() -> PathBuf {
    PathBuf::from(std::env::var("ProgramData").unwrap_or_else(|_| r"C:\ProgramData".into())).join("Deskport")
}
/// 有効にした人の secret.txt の場所を書いておくファイル(管理者だけが書ける場所)。補助プロセスは、そこから鍵を読む。
fn path_file() -> PathBuf { data_dir().join("user-secret-path.txt") }

/// SYSTEM で動くプロセス(サービス、secure-host)では、設定とログの置き場を ProgramData にする。
pub fn use_system_dirs() {
    let pd = std::env::var("ProgramData").unwrap_or_else(|_| r"C:\ProgramData".into());
    // config::dir() は %APPDATA%\Deskport なので、親を ProgramData にする
    std::env::set_var("APPDATA", pd);
}

// ---- 状態(通常のユーザーでも調べられる)

#[derive(serde::Serialize, Clone, Default)]
pub struct Status {
    pub installed: bool,
    pub running: bool,
    pub port: u16,
}

pub fn status() -> Status {
    let out = Command::new("sc").args(["query", SERVICE]).creation_flags(NO_WINDOW).output();
    let text = out.as_ref().map(|o| String::from_utf8_lossy(&o.stdout).to_string()).unwrap_or_default();
    let installed = out.map_or(false, |o| o.status.success());
    Status { installed, running: installed && text.contains("RUNNING"), port: PORT }
}

fn ps(script: &str) -> bool {
    Command::new("powershell").args(["-NoProfile", "-NonInteractive", "-Command", script]).creation_flags(NO_WINDOW).output().map_or(false, |o| o.status.success())
}

/// このプログラムを、管理者権限(確認が1回出る)で、引数つきで実行して、終わるまで待つ。
pub fn run_elevated(args: &[&str]) -> bool {
    let exe = std::env::current_exe().map(|p| p.display().to_string()).unwrap_or_default();
    let list = args.iter().map(|a| format!("'{}'", a.replace('\'', "''"))).collect::<Vec<_>>().join(",");
    ps(&format!("Start-Process -FilePath '{}' -ArgumentList {} -Verb RunAs -Wait -WindowStyle Hidden", exe.replace('\'', "''"), list))
}

/// 有効にする(確認が1回出る)。
pub fn enable() -> Result<()> {
    if !run_elevated(&["secure-setup", "enable"]) { bail!("管理者権限の確認が通りませんでした"); }
    if !status().installed { bail!("有効にできませんでした(ログ: C:\\ProgramData\\Deskport\\host.log)"); }
    Ok(())
}

/// 無効にする(確認が1回出る)。サービス、ファイル、規則、鍵を、すべて消す。
pub fn disable() -> Result<()> {
    if !run_elevated(&["secure-setup", "disable"]) { bail!("管理者権限の確認が通りませんでした"); }
    Ok(())
}

// ---- 管理者権限で動く部分(secure-setup)

fn sc(args: &[&str]) -> bool {
    Command::new("sc").args(args).creation_flags(NO_WINDOW).output().map_or(false, |o| o.status.success())
}

fn icacls(args: &[&str]) {
    let _ = Command::new("icacls").args(args).creation_flags(NO_WINDOW).output();
}

fn netsh(args: &[&str]) {
    let _ = Command::new("netsh").args(["advfirewall", "firewall"]).args(args).creation_flags(NO_WINDOW).output();
}

pub fn setup(what: &str) -> Result<()> {
    // use_system_dirs の前に、このユーザーの鍵の場所を控える(管理者として動いていても、同じユーザーの APPDATA)
    let user_secret = crate::config::dir().join("secret.txt");
    use_system_dirs();
    match what {
        "enable" => {
            let me = std::env::current_exe()?;
            let (pd, pe) = (program_dir(), program_exe());
            // 古いサービスを止めて、取り除く(ファイルが使用中だと、上書きできないため)
            let _ = sc(&["stop", SERVICE]);
            for _ in 0..20 { if !status().running { break; } std::thread::sleep(Duration::from_millis(250)); }
            std::fs::create_dir_all(&pd).context("インストール先を作れません")?;
            if me != pe { std::fs::copy(&me, &pe).context("ファイルをコピーできません")?; }
            init_dir()?;
            let _ = std::fs::remove_file(data_dir().join("secure.key"));   // 旧版の専用の鍵(いまは使わない)
            std::fs::write(path_file(), user_secret.display().to_string()).context("鍵の場所を保存できません")?;
            let _ = sc(&["delete", SERVICE]);
            let bin = format!("\"{}\" secure-service", pe.display());
            if !sc(&["create", SERVICE, "binPath=", &bin, "start=", "auto", "obj=", "LocalSystem", "DisplayName=", "Deskport Secure Desktop Helper"]) {
                bail!("サービスを作れませんでした");
            }
            let _ = sc(&["description", SERVICE, "Deskport: lock screen / UAC remote control helper (optional)"]);
            netsh(&["delete", "rule", &format!("name={RULE}")]);
            netsh(&["add", "rule", &format!("name={RULE}"), "dir=in", "action=allow", "protocol=UDP", &format!("localport={PORT}"), &format!("program={}", pe.display()), "profile=any", &format!("remoteip={}", crate::config::REMOTE_IP)]);
            if !sc(&["start", SERVICE]) { bail!("サービスを開始できませんでした"); }
            Ok(())
        }
        "disable" => {
            let _ = sc(&["stop", SERVICE]);
            for _ in 0..20 { if !status().running { break; } std::thread::sleep(Duration::from_millis(250)); }
            let _ = sc(&["delete", SERVICE]);
            netsh(&["delete", "rule", &format!("name={RULE}")]);
            std::thread::sleep(Duration::from_millis(500));
            let _ = std::fs::remove_dir_all(program_dir());
            let _ = std::fs::remove_file(path_file());
            let _ = std::fs::remove_dir_all(data_dir());
            Ok(())
        }
        _ => bail!("不明な操作: {what}"),
    }
}

/// 置き場を作る。ログを、一般のユーザーも読めるようにする(書き込みは、管理者と SYSTEM だけ)。
fn init_dir() -> Result<()> {
    let dir = data_dir();
    std::fs::create_dir_all(&dir)?;
    icacls(&[&dir.display().to_string(), "/grant", "*S-1-5-32-545:(OI)(CI)RX"]);
    Ok(())
}

/// 通常のホストと同じ鍵(有効にした人の secret.txt)を読む。
pub fn read_key() -> Result<String> {
    let p = std::fs::read_to_string(path_file()).context("鍵の場所を読めません(有効になっていません)")?;
    let s = std::fs::read_to_string(p.trim()).context("鍵を読めません(通常のホストを一度起動してください)")?;
    let s = s.trim().to_string();
    if s.len() != 64 { bail!("鍵が壊れています"); }
    Ok(s)
}

/// 鍵が変わったら(「鍵を作り直す」)、このプロセスを終える。サービスが、新しい鍵で起動し直す。
pub fn exit_when_key_changes(key: String) {
    std::thread::spawn(move || loop {
        std::thread::sleep(Duration::from_secs(3));
        if read_key().map_or(true, |k| k != key) { crate::log::logln!("鍵が変わったので、補助プロセスを起動し直します"); std::process::exit(0); }
    });
}

// ---- Ctrl+Alt+Del

/// secure-host から、サービスに頼む。
pub fn request_sas() -> bool {
    use std::io::Write;
    match std::fs::OpenOptions::new().write(true).open(SAS_PIPE) {
        Ok(mut f) => f.write_all(&[1]).is_ok(),
        Err(_) => false,
    }
}

// ---- サービス本体

use std::ffi::{c_void, OsString};
use windows::core::{PCWSTR, PWSTR};
use windows::Win32::Foundation::{CloseHandle, HANDLE, WAIT_OBJECT_0};
use windows::Win32::Security::*;
use windows::Win32::Storage::FileSystem::{ReadFile, PIPE_ACCESS_INBOUND};
use windows::Win32::System::LibraryLoader::{GetProcAddress, LoadLibraryW};
use windows::Win32::System::Pipes::*;
use windows::Win32::System::RemoteDesktop::WTSGetActiveConsoleSessionId;
use windows::Win32::System::Threading::*;
use windows_service::service::{ServiceControl, ServiceControlAccept, ServiceExitCode, ServiceState, ServiceStatus, ServiceType};
use windows_service::service_control_handler::{self, ServiceControlHandlerResult};
use windows_service::{define_windows_service, service_dispatcher};

define_windows_service!(ffi_service_main, service_main);

/// サービスとして動く(サービスの管理画面から起動されたときだけ、ここに来る)。
pub fn run_service() -> Result<()> {
    use_system_dirs();
    service_dispatcher::start(SERVICE, ffi_service_main).map_err(|e| anyhow::anyhow!("サービスを開始できません: {e}"))
}

fn service_main(_args: Vec<OsString>) {
    if let Err(e) = service_run() { crate::log::logln!("サービスが止まりました: {e}"); }
}

fn wide(s: &str) -> Vec<u16> { s.encode_utf16().chain(std::iter::once(0)).collect() }

fn service_run() -> windows_service::Result<()> {
    let stop = Arc::new(AtomicBool::new(false));
    let s2 = stop.clone();
    let handle = service_control_handler::register(SERVICE, move |ev| match ev {
        ServiceControl::Stop | ServiceControl::Shutdown => { s2.store(true, Ordering::SeqCst); ServiceControlHandlerResult::NoError }
        ServiceControl::Interrogate => ServiceControlHandlerResult::NoError,
        _ => ServiceControlHandlerResult::NotImplemented,
    })?;
    let set = |state: ServiceState, accept: ServiceControlAccept| handle.set_service_status(ServiceStatus {
        service_type: ServiceType::OWN_PROCESS, current_state: state, controls_accepted: accept,
        exit_code: ServiceExitCode::Win32(0), checkpoint: 0, wait_hint: Duration::from_secs(5), process_id: None,
    });
    set(ServiceState::Running, ServiceControlAccept::STOP | ServiceControlAccept::SHUTDOWN)?;
    crate::log::logln!("サービスを開始しました");

    std::thread::spawn(sas_pipe_loop);

    let exe = std::env::current_exe().unwrap_or_else(|_| program_exe());
    let mut child: Option<(HANDLE, u32)> = None;   // (プロセス, 起動したセッション)
    while !stop.load(Ordering::SeqCst) {
        let sid = unsafe { WTSGetActiveConsoleSessionId() };
        if let Some((h, s)) = child {
            let dead = unsafe { WaitForSingleObject(h, 0) } == WAIT_OBJECT_0;
            if dead || sid != s {
                unsafe { if !dead { let _ = TerminateProcess(h, 0); } let _ = CloseHandle(h); }
                child = None;
            }
        }
        if child.is_none() && sid != 0xFFFF_FFFF {
            match unsafe { spawn_in_session(&exe, "secure-host", sid) } {
                Ok(h) => { crate::log::logln!("補助プロセスを起動しました(セッション {sid})"); child = Some((h, sid)); }
                Err(e) => { crate::log::logln!("補助プロセスを起動できません: {e}"); std::thread::sleep(Duration::from_secs(3)); }
            }
        }
        std::thread::sleep(Duration::from_secs(1));
    }
    if let Some((h, _)) = child { unsafe { let _ = TerminateProcess(h, 0); let _ = CloseHandle(h); } }
    crate::log::logln!("サービスを止めました");
    set(ServiceState::Stopped, ServiceControlAccept::empty())?;
    Ok(())
}

/// サービス自身の SYSTEM のトークンを、指定のセッション用に複製して、そのセッションで起動する。
unsafe fn spawn_in_session(exe: &std::path::Path, arg: &str, session: u32) -> Result<HANDLE> {
    let mut tok = HANDLE::default();
    OpenProcessToken(GetCurrentProcess(), TOKEN_ALL_ACCESS, &mut tok)?;
    let mut dup = HANDLE::default();
    let r = DuplicateTokenEx(tok, TOKEN_ALL_ACCESS, None, SecurityImpersonation, TokenPrimary, &mut dup);
    let _ = CloseHandle(tok);
    r?;
    let set = SetTokenInformation(dup, TokenSessionId, &session as *const u32 as *const c_void, 4);
    if let Err(e) = set { let _ = CloseHandle(dup); return Err(e.into()); }
    let mut cmd = wide(&format!("\"{}\" {}", exe.display(), arg));
    let mut desktop = wide("winsta0\\default");
    let si = STARTUPINFOW { cb: std::mem::size_of::<STARTUPINFOW>() as u32, lpDesktop: PWSTR(desktop.as_mut_ptr()), ..Default::default() };
    let mut pi = PROCESS_INFORMATION::default();
    let r = CreateProcessAsUserW(dup, PCWSTR::null(), PWSTR(cmd.as_mut_ptr()), None, None, false, CREATE_NO_WINDOW, None, PCWSTR::null(), &si, &mut pi);
    let _ = CloseHandle(dup);
    r?;
    let _ = CloseHandle(pi.hThread);
    Ok(pi.hProcess)
}

/// secure-host からの「Ctrl+Alt+Del を送って」を受ける。名前つきパイプは、既定の権限(SYSTEM と管理者だけが書ける)で作る。
fn sas_pipe_loop() {
    let name = wide(SAS_PIPE);
    loop {
        unsafe {
            let h = CreateNamedPipeW(PCWSTR(name.as_ptr()), PIPE_ACCESS_INBOUND, PIPE_TYPE_BYTE | PIPE_WAIT, 1, 16, 16, 0, None);
            if h.is_invalid() { std::thread::sleep(Duration::from_secs(2)); continue; }
            if ConnectNamedPipe(h, None).is_ok() || windows::Win32::Foundation::GetLastError() == windows::Win32::Foundation::ERROR_PIPE_CONNECTED {
                let mut b = [0u8; 1];
                let mut n = 0u32;
                if ReadFile(h, Some(&mut b), Some(&mut n), None).is_ok() && n == 1 && b[0] == 1 { send_sas(); }
            }
            let _ = DisconnectNamedPipe(h);
            let _ = CloseHandle(h);
        }
    }
}

/// Ctrl+Alt+Del(Secure Attention Sequence)を送る。サービスだけが呼べる(sas.dll の SendSAS)。
fn send_sas() {
    unsafe {
        let dll = wide("sas.dll");
        let Ok(m) = LoadLibraryW(PCWSTR(dll.as_ptr())) else { crate::log::logln!("sas.dll を読めません"); return };
        match GetProcAddress(m, windows::core::s!("SendSAS")) {
            Some(f) => {
                let f: extern "system" fn(i32) = std::mem::transmute(f);
                f(0);   // 0 = サービスから
                crate::log::logln!("Ctrl+Alt+Del を送りました");
            }
            None => crate::log::logln!("SendSAS が見つかりません"),
        }
    }
}
