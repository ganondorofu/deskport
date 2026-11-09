//! ログ: 直近の500行をメモリに持ち(窓のログ画面に出す)、ファイル(%APPDATA%\Deskport\host.log)にも書く。
use std::collections::VecDeque;
use std::io::Write;
use std::sync::Mutex;
use windows::Win32::System::SystemInformation::GetLocalTime;

static BUF: Mutex<VecDeque<String>> = Mutex::new(VecDeque::new());
static FILE: Mutex<Option<std::fs::File>> = Mutex::new(None);

pub fn write(msg: &str) {
    let t = unsafe { GetLocalTime() };
    let line = format!("{:02}:{:02}:{:02} {}", t.wHour, t.wMinute, t.wSecond, msg);
    println!("{line}");
    {
        let mut b = BUF.lock().unwrap();
        b.push_back(line.clone());
        while b.len() > 500 { b.pop_front(); }
    }
    let mut f = FILE.lock().unwrap();
    if f.is_none() {
        let p = crate::config::dir().join("host.log");
        let _ = std::fs::create_dir_all(crate::config::dir());
        // 大きくなりすぎたら、最初から書き直す
        let big = std::fs::metadata(&p).map_or(false, |m| m.len() > 1_000_000);
        let file = if big { std::fs::File::create(&p) } else { std::fs::OpenOptions::new().create(true).append(true).open(&p) };
        *f = file.ok();
    }
    if let Some(file) = f.as_mut() { let _ = writeln!(file, "{} {}", date(), line); }
}

fn date() -> String {
    let t = unsafe { GetLocalTime() };
    format!("{:04}-{:02}-{:02}", t.wYear, t.wMonth, t.wDay)
}

pub fn lines() -> Vec<String> {
    BUF.lock().unwrap().iter().cloned().collect()
}

macro_rules! logln {
    ($($a:tt)*) => { $crate::log::write(&format!($($a)*)) };
}
pub(crate) use logln;
