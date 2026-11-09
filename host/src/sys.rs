//! Windows まわりの小さな処理: 自動起動の登録、多重起動の防止、「窓を出して」の合図。
use std::os::windows::process::CommandExt;
use std::process::Command;
use windows::core::w;
use windows::Win32::Foundation::*;
use windows::Win32::System::Threading::*;

pub const NO_WINDOW: u32 = 0x0800_0000;
const RUN_KEY: &str = r"HKCU\Software\Microsoft\Windows\CurrentVersion\Run";
const RUN_NAME: &str = "DeskportHost";
/// 改名前(FoldRemote)の自動起動の登録名。インストールのときに外す。
const LEGACY_RUN_NAME: &str = "FoldRemoteHost";

// ---- 自動起動(レジストリの Run キー。reg コマンドで読み書きする) ----
pub fn autostart_on() -> bool {
    Command::new("reg").args(["query", RUN_KEY, "/v", RUN_NAME]).creation_flags(NO_WINDOW).output().map_or(false, |o| o.status.success())
}

/// 自動起動を入れる/外す。入れるときは exe の場所を登録する(窓は出さずに、トレイだけで始まる)。
pub fn set_autostart_for(on: bool, exe: &str) {
    let mut c = Command::new("reg");
    if on {
        c.args(["add", RUN_KEY, "/v", RUN_NAME, "/t", "REG_SZ", "/d", &format!("\"{exe}\" --no-pair"), "/f"]);
    } else {
        c.args(["delete", RUN_KEY, "/v", RUN_NAME, "/f"]);
    }
    let _ = c.creation_flags(NO_WINDOW).output();
}

/// 改名前の自動起動の登録を外す。
pub fn remove_legacy_autostart() {
    let _ = Command::new("reg").args(["delete", RUN_KEY, "/v", LEGACY_RUN_NAME, "/f"]).creation_flags(NO_WINDOW).output();
}

/// 同時に1つだけ動かす。再起動のときは、前のものが終わるのを少し待つ。取れたら true(ハンドルは、終了まで持ち続ける)。
pub fn single_instance(wait: bool) -> bool {
    for _ in 0..40 {
        unsafe {
            let Ok(h) = CreateMutexW(None, true, w!("Local\\DeskportHost")) else { return true };
            if GetLastError() != ERROR_ALREADY_EXISTS {
                return true;
            }
            let _ = CloseHandle(h);
        }
        if !wait { return false; }
        std::thread::sleep(std::time::Duration::from_millis(100));
    }
    false
}

/// すでに動いているものに、「窓を出して」と伝える(2つ目を起動したとき)。
pub fn signal_show() {
    unsafe {
        if let Ok(h) = CreateEventW(None, false, false, w!("Local\\DeskportShow")) {
            let _ = SetEvent(h);
            let _ = CloseHandle(h);
        }
    }
}

/// 「窓を出して」の合図を待ち続けて、来るたびに f を呼ぶ(別スレッド)。
pub fn watch_show(f: impl Fn() + Send + 'static) {
    let h = unsafe { CreateEventW(None, false, false, w!("Local\\DeskportShow")) };
    let Ok(h) = h else { return };
    let raw = h.0 as isize;
    std::thread::spawn(move || loop {
        let h = HANDLE(raw as *mut _);
        if unsafe { WaitForSingleObject(h, INFINITE) } == WAIT_OBJECT_0 { f(); } else { break; }
    });
}
