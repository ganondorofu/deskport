//! インストール/アンインストール。exe を1つ渡すだけで、配置・自動起動・スタートメニュー・ファイアウォールの設定まで行う。
//!  - 配置先: %LOCALAPPDATA%\Programs\Deskport\deskport-host.exe(管理者権限は要らない)
//!  - ファイアウォール: UDP 49000(映像と操作)と TCP 49000(ファイル転送とクリップボード)の受信を、このプログラムだけ許可する(相手のアドレスは限らない。VPN の種類を問わないため。通信は暗号化と認証で守る)。ここだけ管理者権限が要る(確認が1回出る)
use crate::sys::{set_autostart_for, NO_WINDOW};
use anyhow::{bail, Context, Result};
use std::os::windows::process::CommandExt;
use std::path::PathBuf;
use std::process::Command;

const RULE: &str = "Deskport";
const RULE_TCP: &str = "Deskport TCP";
/// 改名前(FoldRemote)のファイアウォールの規則名。インストールのときに消す。
const LEGACY_RULE: &str = "FoldRemote";

pub fn install_dir() -> PathBuf {
    PathBuf::from(std::env::var("LOCALAPPDATA").unwrap_or_else(|_| ".".into())).join("Programs").join("Deskport")
}

pub fn installed_exe() -> PathBuf {
    install_dir().join("deskport-host.exe")
}

/// いま動いているのが、インストール済みの場所のものか。
pub fn is_installed_copy() -> bool {
    std::env::current_exe().map_or(false, |p| p == installed_exe())
}

fn shortcut_path() -> PathBuf {
    PathBuf::from(std::env::var("APPDATA").unwrap_or_default()).join(r"Microsoft\Windows\Start Menu\Programs\Deskport.lnk")
}

fn ps(script: &str) -> bool {
    Command::new("powershell").args(["-NoProfile", "-NonInteractive", "-Command", script]).creation_flags(NO_WINDOW).output().map_or(false, |o| o.status.success())
}

/// 管理者権限で、netsh の命令をまとめて実行する(確認は1回だけ出る)。
fn netsh_admin(cmds: &[Vec<String>]) -> bool {
    let line = cmds.iter().map(|a| format!("netsh {}", a.join(" "))).collect::<Vec<_>>().join(" & ");
    ps(&format!("Start-Process cmd -Verb RunAs -Wait -WindowStyle Hidden -ArgumentList @('/C','{}')", line.replace('\'', "''")))
}

fn rule_args(verb: &str, extra: &[String]) -> Vec<String> {
    let mut v: Vec<String> = vec!["advfirewall".into(), "firewall".into(), verb.into(), "rule".into()];
    v.extend_from_slice(extra);
    v
}

fn stop_others() {
    let me = std::process::id();
    let _ = Command::new("taskkill").args(["/F", "/FI", &format!("PID ne {me}"), "/IM", "deskport-host.exe"]).creation_flags(NO_WINDOW).output();
    std::thread::sleep(std::time::Duration::from_millis(500));
}

pub fn install() -> Result<()> {
    let src = std::env::current_exe()?;
    let dst = installed_exe();
    if src != dst {
        stop_others();
        std::fs::create_dir_all(install_dir()).context("インストール先を作れません")?;
        std::fs::copy(&src, &dst).context("ファイルをコピーできません")?;
    }
    let exe = dst.display().to_string();
    set_autostart_for(true, &exe);
    let lnk = shortcut_path();
    ps(&format!(
        "$s=(New-Object -ComObject WScript.Shell).CreateShortcut('{}'); $s.TargetPath='{}'; $s.Save()",
        lnk.display(), exe
    ));
    // ファイアウォール(改名前と古い規則を消してから、作り直す)。管理者権限の確認は1回
    crate::sys::remove_legacy_autostart();
    let del = |name: &str| rule_args("delete", &[format!("name=\"{name}\"")]);
    let add = |name: &str, proto: &str| rule_args("add", &[
        format!("name=\"{name}\""), "dir=in".into(), "action=allow".into(), format!("protocol={proto}"),
        format!("localport={}", crate::config::PORT), format!("program=\"{exe}\""), "profile=any".into(),
        // 同じネットワーク・Tailscale・VPN(プライベートアドレス)からだけ受ける。インターネット側からは届かない
        format!("remoteip={}", crate::config::REMOTE_IP),
    ]);
    if !netsh_admin(&[del(LEGACY_RULE), del(RULE), del(RULE_TCP), add(RULE, "UDP"), add(RULE_TCP, "TCP")]) {
        eprintln!("ファイアウォールの設定は、行われませんでした(あとで、Windows の確認画面で許可してください)");
    }
    Ok(())
}

/// インストール済みのものを起動する。
pub fn launch_installed() -> Result<()> {
    Command::new(installed_exe()).arg("--restart").spawn().context("起動できません")?;   // 今のものが終わるのを待って始まる
    Ok(())
}

pub fn uninstall() -> Result<()> {
    // ロック画面・UAC 対応(サービス)が入っていれば、一緒に取り除く(管理者権限の確認が出る)
    if crate::secure::status().installed { let _ = crate::secure::disable(); }
    stop_others();
    set_autostart_for(false, "");
    let _ = std::fs::remove_file(shortcut_path());
    let del = |name: &str| rule_args("delete", &[format!("name=\"{name}\"")]);
    let _ = netsh_admin(&[del(RULE), del(RULE_TCP), del(LEGACY_RULE)]);
    // 自分自身は消せないので、終わってから消す
    let dir = install_dir();
    if !dir.exists() { bail!("インストールされていません"); }
    let _ = Command::new("cmd").args(["/C", &format!("ping -n 3 127.0.0.1 >nul & rmdir /S /Q \"{}\"", dir.display())]).creation_flags(NO_WINDOW).spawn();
    Ok(())
}
