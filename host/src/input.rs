//! 入力の注入(SendInput)。マウス、キー、文字、スクロール。
//! 管理者権限のウィンドウ(UAC の画面など)には届かない(通常権限で動くため)。
use windows::Win32::UI::Input::KeyboardAndMouse::*;
use windows::Win32::UI::WindowsAndMessaging::{GetCursorInfo, GetCursorPos, GetSystemMetrics, SetCursorPos, SM_CXVIRTUALSCREEN, SM_CYVIRTUALSCREEN, SM_XVIRTUALSCREEN, SM_YVIRTUALSCREEN, CURSORINFO, CURSOR_SHOWING};

fn send(inputs: &[INPUT]) {
    unsafe {
        SendInput(inputs, std::mem::size_of::<INPUT>() as i32);
    }
}

fn mouse(dx: i32, dy: i32, data: u32, flags: MOUSE_EVENT_FLAGS) -> INPUT {
    INPUT {
        r#type: INPUT_MOUSE,
        Anonymous: INPUT_0 { mi: MOUSEINPUT { dx, dy, mouseData: data, dwFlags: flags, time: 0, dwExtraInfo: 0 } },
    }
}

fn kbd(vk: u16, scan: u16, flags: KEYBD_EVENT_FLAGS) -> INPUT {
    INPUT {
        r#type: INPUT_KEYBOARD,
        Anonymous: INPUT_0 { ki: KEYBDINPUT { wVk: VIRTUAL_KEY(vk), wScan: scan, dwFlags: flags, time: 0, dwExtraInfo: 0 } },
    }
}

/// 相対移動。
pub fn mouse_rel(dx: i32, dy: i32) {
    send(&[mouse(dx, dy, 0, MOUSEEVENTF_MOVE)]);
}

/// 絶対位置(仮想デスクトップ上の物理ピクセル)。
pub fn mouse_abs(x: i32, y: i32) {
    unsafe {
        // SetCursorPos だけでは、タスクバーのアプリのホバー(プレビュー)など、本物のマウス入力を待つ部分が反応しない。
        // 本物の「絶対移動」で動かす(仮想デスクトップ全体を 0〜65535 に割り当てる形)。
        // 先に SetCursorPos で動かしてしまうと、そのあとの移動が「動いていない」ことになり、ホバーが起きないので、併用しない
        let vx = GetSystemMetrics(SM_XVIRTUALSCREEN); let vy = GetSystemMetrics(SM_YVIRTUALSCREEN);
        let vw = GetSystemMetrics(SM_CXVIRTUALSCREEN).max(1); let vh = GetSystemMetrics(SM_CYVIRTUALSCREEN).max(1);
        let nx = (((x - vx) as i64 * 65536 + vw as i64 - 1) / vw as i64).clamp(0, 65535) as i32;
        let ny = (((y - vy) as i64 * 65536 + vh as i64 - 1) / vh as i64).clamp(0, 65535) as i32;
        let n = SendInput(&[mouse(nx, ny, 0, MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE | MOUSEEVENTF_VIRTUALDESK)], std::mem::size_of::<INPUT>() as i32);
        // 0〜65535 への割り当ては、1 ピクセルずれることがある。ずれていたら、そのあと SetCursorPos で、ぴったりに直す
        // (本物の移動のあとなので、ホバーは保たれる。実測: SetCursorPos だけだとホバーが起きず、この順なら起きる)
        let mut p = windows::Win32::Foundation::POINT::default();
        if n == 0 || GetCursorPos(&mut p).is_err() || p.x != x || p.y != y { let _ = SetCursorPos(x, y); }
    }
}

/// ボタン: 1=左 2=中 3=右 4=戻る 5=進む。
pub fn button(btn: u8, down: bool) {
    let (flags, data) = match (btn, down) {
        (1, true) => (MOUSEEVENTF_LEFTDOWN, 0),
        (1, false) => (MOUSEEVENTF_LEFTUP, 0),
        (2, true) => (MOUSEEVENTF_MIDDLEDOWN, 0),
        (2, false) => (MOUSEEVENTF_MIDDLEUP, 0),
        (3, true) => (MOUSEEVENTF_RIGHTDOWN, 0),
        (3, false) => (MOUSEEVENTF_RIGHTUP, 0),
        (4, true) => (MOUSEEVENTF_XDOWN, 1),
        (4, false) => (MOUSEEVENTF_XUP, 1),
        (5, true) => (MOUSEEVENTF_XDOWN, 2),
        (5, false) => (MOUSEEVENTF_XUP, 2),
        _ => return,
    };
    send(&[mouse(0, 0, data, flags)]);
}

/// スクロール(120 で 1 ノッチ、正が上)。
pub fn scroll(amount: i32) {
    send(&[mouse(0, 0, amount as u32, MOUSEEVENTF_WHEEL)]);
}

/// 拡張キー(矢印、Home/End、PgUp/PgDn、Ins/Del、Win など)は、専用のフラグが要る。
fn is_extended(vk: u16) -> bool {
    matches!(vk, 0x21..=0x28 | 0x2D | 0x2E | 0x5B | 0x5C | 0x6F | 0x90 | 0xA3 | 0xA5)
}

pub fn key(vk: u16, down: bool) {
    let mut flags = KEYBD_EVENT_FLAGS(0);
    if is_extended(vk) {
        flags |= KEYEVENTF_EXTENDEDKEY;
    }
    if !down {
        flags |= KEYEVENTF_KEYUP;
    }
    send(&[kbd(vk, 0, flags)]);
}

/// 文字列をそのまま入力する(日本語を含む。キーの配列に依存しない)。
pub fn text(s: &str) {
    let mut v = Vec::new();
    for u in s.encode_utf16() {
        v.push(kbd(0, u, KEYEVENTF_UNICODE));
        v.push(kbd(0, u, KEYEVENTF_UNICODE | KEYEVENTF_KEYUP));
    }
    if !v.is_empty() {
        send(&v);
    }
}

/// 本物のカーソルの位置(仮想デスクトップの物理ピクセル)と、表示中かどうか。
pub fn cursor() -> Option<(i32, i32, bool)> {
    unsafe {
        let mut ci = CURSORINFO { cbSize: std::mem::size_of::<CURSORINFO>() as u32, ..Default::default() };
        GetCursorInfo(&mut ci).ok()?;
        Some((ci.ptScreenPos.x, ci.ptScreenPos.y, ci.flags.0 & CURSOR_SHOWING.0 != 0))
    }
}
