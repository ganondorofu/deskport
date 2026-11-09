//! ロック画面・UAC への対応: 入力を受けているデスクトップに、スレッドをつなぐ。
//!
//! Windows には「デスクトップ」が複数ある(通常の画面、ロック画面やログイン画面、UAC の確認画面)。
//! 画面の取得と入力の注入は、スレッドがつながっているデスクトップに対してだけ効く。
//! そのため、いま入力を受けているデスクトップを開いて、スレッドをつなぎ直す。
//! SYSTEM 権限の補助プロセス(secure-host)だけが、これに成功する。通常のホストでは何もしない。
use std::cell::RefCell;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant};
use windows::Win32::Foundation::HANDLE;
use windows::Win32::System::StationsAndDesktops::*;

static ENABLED: AtomicBool = AtomicBool::new(false);

/// 補助プロセス(secure-host)として動くときに呼ぶ。
pub fn enable() { ENABLED.store(true, Ordering::SeqCst); }
pub fn enabled() -> bool { ENABLED.load(Ordering::SeqCst) }

thread_local! {
    static CUR: RefCell<Option<(String, HDESK)>> = const { RefCell::new(None) };
    static LAST: RefCell<Option<Instant>> = const { RefCell::new(None) };
}

fn name_of(h: HDESK) -> String {
    unsafe {
        let mut buf = [0u16; 128];
        let mut need = 0u32;
        if GetUserObjectInformationW(HANDLE(h.0), UOI_NAME, Some(buf.as_mut_ptr() as *mut _), (buf.len() * 2) as u32, Some(&mut need)).is_err() {
            return String::new();
        }
        String::from_utf16_lossy(&buf).trim_end_matches('\0').to_string()
    }
}

/// いま入力を受けているデスクトップに、このスレッドをつなぐ。つなぎ先が変わったら true。
pub fn attach() -> bool {
    if !enabled() { return false; }
    unsafe {
        // 0x10000000 = GENERIC_ALL
        let Ok(h) = OpenInputDesktop(DESKTOP_CONTROL_FLAGS(0), false, DESKTOP_ACCESS_FLAGS(0x1000_0000)) else { return false };
        let name = name_of(h);
        let same = CUR.with(|c| c.borrow().as_ref().map_or(false, |(n, _)| *n == name));
        if same { let _ = CloseDesktop(h); return false; }
        if SetThreadDesktop(h).is_err() { let _ = CloseDesktop(h); return false; }
        CUR.with(|c| {
            if let Some((_, old)) = c.borrow_mut().replace((name, h)) { let _ = CloseDesktop(old); }
        });
        true
    }
}

/// 入力を注入する直前に呼ぶ。0.15秒より短い間隔では、確認しない(負荷を抑える)。
pub fn attach_throttled() {
    if !enabled() { return; }
    let due = LAST.with(|l| {
        let mut l = l.borrow_mut();
        let due = l.map_or(true, |t| t.elapsed() > Duration::from_millis(150));
        if due { *l = Some(Instant::now()); }
        due
    });
    if due { attach(); }
}
