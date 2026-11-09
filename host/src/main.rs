// コンソールの窓を出さない(デバッグビルドも)。deskport-host serve などの、コマンドとして使うときは、main の最初で、呼び出し元のコンソールにつなぐ
#![windows_subsystem = "windows"]
mod capture;
mod commands;
mod config;
mod desktop;
mod crypto;
mod cursor;
mod audio;
mod camera;
mod files;
mod input;
mod install;
mod log;
mod nvenc;
mod secure;
mod serve;
mod settings;
mod sys;
mod update;

use anyhow::Result;
use std::io::Write;
use std::time::Instant;
use tauri::Manager;

fn main() -> Result<()> {
    // 座標を物理ピクセルで扱う(拡大率 150% のディスプレイでも、入力の位置がずれないように)
    unsafe {
        let _ = windows::Win32::UI::HiDpi::SetProcessDpiAwarenessContext(windows::Win32::UI::HiDpi::DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);
    }
    let args: Vec<String> = std::env::args().collect();
    let sub = args.get(1).map(|s| s.as_str());

    // コマンドとして使うとき(窓なしのビルドでも、呼び出し元の画面に出力できるようにする)
    if matches!(sub, Some("cursor-test" | "addrs" | "serve" | "capture-test" | "install" | "uninstall" | "keygen" | "release" | "secure-setup" | "vcam-setup")) {
        unsafe {
            let _ = windows::Win32::System::Console::AttachConsole(windows::Win32::System::Console::ATTACH_PARENT_PROCESS);
        }
    }
    match sub {
        // deskport-host cursor-test  … いまのカーソルの形を取り出して、内容を表示する(診断用)
        Some("cursor-test") => {
            let h = cursor::current_handle().unwrap_or(0);
            match cursor::capture(h) {
                Some(s) => {
                    let opaque = s.rgba.chunks_exact(4).filter(|p| p[3] > 0).count();
                    println!("handle=0x{:x} id={:08x} {}x{} hot=({},{}) busy={} 不透明な画素={}/{}", h, s.id, s.w, s.h, s.hot_x, s.hot_y, s.busy, opaque, s.w as usize * s.h as usize);
                }
                None => println!("取り出せませんでした(handle=0x{:x})", h),
            }
            return Ok(());
        }
        // deskport-host serve [待ち受けアドレス:ポート] [合言葉]  … 窓なしで配信する(合言葉は環境変数 DESKPORT_TOKEN でも指定できる)
        Some("serve") => {
            settings::load();
            let token = args.get(3).cloned().or_else(|| std::env::var("DESKPORT_TOKEN").ok()).unwrap_or_default();
            return serve::run(args.get(2).map(|s| s.as_str()).unwrap_or("0.0.0.0:49000"), &token);
        }
        // ロック画面・UAC への対応(任意)。secure-setup は、管理者権限で呼ばれる
        Some("secure-setup") => return secure::setup(args.get(2).map(|s| s.as_str()).unwrap_or("")),
        Some("secure-service") => return secure::run_service(),
        // スマホのカメラを PC のカメラにする(仮想カメラの DLL の登録。管理者権限で呼ばれる)
        Some("vcam-setup") => return camera::setup(args.get(2).map(|s| s.as_str()).unwrap_or("")),
        Some("secure-host") => {
            secure::use_system_dirs();
            desktop::enable();
            settings::load();
            let key = secure::read_key()?;
            secure::exit_when_key_changes(key.clone());
            log::logln!("補助プロセス(ロック画面・UAC 対応)を開始します");
            return serve::run(&format!("0.0.0.0:{}", secure::PORT), &key);
        }
        // deskport-host install / uninstall  … インストール(配置、自動起動、スタートメニュー、ファイアウォール)/ 取り除く
        Some("install") => { install::install()?; return install::launch_installed(); }
        Some("uninstall") => return install::uninstall(),
        // deskport-host addrs  … QR に入るアドレスを表示する(診断用)
        Some("addrs") => { for (n, ip, inc, ex) in config::addresses() { println!("{n}\t{ip}\t含む={inc}\t追加={ex}"); } return Ok(()); }
        // deskport-host keygen / release …  更新の署名(開発者用)
        Some("keygen") => return update::keygen(),
        Some("release") => return update::release(&args[2..]),
        // deskport-host capture-test [ディスプレイ番号]  … 符号化の試験
        Some("capture-test") => return capture_test(args.get(2).and_then(|s| s.parse().ok()).unwrap_or(0)),
        _ => {}
    }

    // 引数なし(ダブルクリック)、または --restart / --no-pair: 窓つきで動かす
    // まだインストールされていないなら、インストールするか尋ねる
    if args.len() == 1 && !install::is_installed_copy() && !install::installed_exe().exists() {
        use windows::Win32::UI::WindowsAndMessaging::{MessageBoxW, IDYES, MB_ICONQUESTION, MB_YESNO};
        let text: Vec<u16> = "Deskport ホストをインストールしますか?\n\n「はい」: Windows の起動時に自動で始まるようにして、スタートメニューに登録します\n「いいえ」: インストールせず、このまま動かします\0".encode_utf16().collect();
        let title: Vec<u16> = "Deskport\0".encode_utf16().collect();
        let r = unsafe { MessageBoxW(None, windows::core::PCWSTR(text.as_ptr()), windows::core::PCWSTR(title.as_ptr()), MB_YESNO | MB_ICONQUESTION) };
        if r == IDYES { install::install()?; return install::launch_installed(); }
    }
    run_gui(args.iter().any(|a| a == "--restart"), args.iter().any(|a| a == "--no-pair"))
}

fn run_gui(restarted: bool, hidden: bool) -> Result<()> {
    if !sys::single_instance(restarted) {
        sys::signal_show();   // すでに動いている: そちらの窓を出して、こちらは終わる
        return Ok(());
    }
    settings::load();
    let secret = config::secret()?;
    let secret2 = secret.clone();
    std::thread::spawn(move || {
        if let Err(e) = files::run(&format!("0.0.0.0:{}", config::PORT), &secret2) {
            log::logln!("ファイル転送が止まりました: {e:#}");
        }
    });
    std::thread::spawn(move || {
        if let Err(e) = serve::run(&format!("0.0.0.0:{}", config::PORT), &secret) {
            log::logln!("サーバーが止まりました: {e:#}");
        }
    });
    camera::start_background();   // カメラを有効にしているなら、仮想カメラを Windows に出す
    log::logln!("Deskport ホスト v{} を起動しました", update::VERSION);

    tauri::Builder::default()
        .invoke_handler(tauri::generate_handler![
            commands::info, commands::live, commands::pair_svg, commands::save_settings, commands::disconnect,
            commands::set_autostart, commands::regenerate_key, commands::check_update, commands::apply_update,
            commands::install_here, commands::get_log, commands::save_addresses,
            commands::secure_status, commands::secure_enable, commands::secure_disable,
            commands::camera_status, commands::camera_enable, commands::camera_disable,
            commands::files_info, commands::files_open_dir, commands::files_reveal, commands::files_delete, commands::files_add,
        ])
        .setup(move |app| {
            use tauri::menu::{Menu, MenuItem};
            use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
            let open = MenuItem::with_id(app, "open", "開く", true, None::<&str>)?;
            let quit = MenuItem::with_id(app, "quit", "終了", true, None::<&str>)?;
            let menu = Menu::with_items(app, &[&open, &quit])?;
            TrayIconBuilder::new()
                .icon(app.default_window_icon().unwrap().clone())
                .tooltip("Deskport ホスト")
                .menu(&menu)
                .show_menu_on_left_click(false)
                .on_menu_event(|app, ev| match ev.id.as_ref() {
                    "open" => show_main(app),
                    "quit" => app.exit(0),
                    _ => {}
                })
                .on_tray_icon_event(|tray, ev| {
                    if let TrayIconEvent::Click { button: MouseButton::Left, button_state: MouseButtonState::Up, .. } = ev {
                        show_main(tray.app_handle());
                    }
                })
                .build(app)?;
            // 手で起動したときだけ、窓を出す(自動起動と再起動のときは、トレイだけ)
            if !hidden { show_main(app.handle()); }
            // 2つ目を起動されたら、窓を出す
            let h = app.handle().clone();
            sys::watch_show(move || show_main(&h));
            // 更新の確認(起動の少しあとと、6時間ごと)。設定フォルダに no-update-check というファイルがあれば、確認しない
            std::thread::spawn(|| loop {
                std::thread::sleep(std::time::Duration::from_secs(15));
                if config::dir().join("no-update-check").exists() { std::thread::sleep(std::time::Duration::from_secs(6 * 3600)); continue; }
                if let Err(e) = update::check_and_store() { log::logln!("更新の確認: {e:#}"); }
                std::thread::sleep(std::time::Duration::from_secs(6 * 3600));
            });
            Ok(())
        })
        // 窓は、閉じると破棄する(隠すだけだと、窓の部品 WebView2 が、約 350MB を使い続ける)。トレイには残り、開くときに作り直す
        .build(tauri::generate_context!())?
        .run(|_app, ev| {
            if let tauri::RunEvent::ExitRequested { api, code, .. } = ev {
                if code.is_none() { api.prevent_exit(); }   // 窓がすべて閉じても、終わらない(「終了」を選んだときだけ終わる)
            }
        });
    Ok(())
}

fn show_main(app: &tauri::AppHandle) {
    if let Some(w) = app.get_webview_window("main") {
        let _ = w.show();
        let _ = w.unminimize();
        let _ = w.set_focus();
        return;
    }
    // 窓がなければ(閉じて、破棄してあるとき)、作る
    let built = tauri::WebviewWindowBuilder::new(app, "main", tauri::WebviewUrl::App("index.html".into()))
        .title("Deskport").inner_size(480.0, 780.0).min_inner_size(400.0, 560.0).center().build();
    match built {
        Ok(w) => { let _ = w.set_focus(); }
        Err(e) => log::logln!("窓を開けません: {e}"),
    }
}

/// 試作: 指定したディスプレイを最大300フレーム符号化して、時間を測り、out.h265 に書く。
fn capture_test(index: usize) -> Result<()> {
    let mut cap = capture::Capturer::open(index)?;
    println!("ディスプレイ {}: {} {}x{}", index, cap.name, cap.width, cap.height);
    let mut enc = nvenc::Encoder::new(&cap.device, &cap.frame, cap.width, cap.height, 60, 20_000)?;
    let mut file = std::fs::File::create("out.h265")?;
    let (mut frames, mut bytes) = (0u32, 0usize);
    let (mut sum, mut max) = (0f64, 0f64);
    let t0 = Instant::now();
    while frames < 300 && t0.elapsed().as_secs() < 20 {
        if !cap.next(50)? {
            continue;
        }
        let t = Instant::now();
        let data = enc.encode(frames % 120 == 0)?;
        let ms = t.elapsed().as_secs_f64() * 1000.0;
        sum += ms;
        if ms > max {
            max = ms;
        }
        bytes += data.len();
        file.write_all(&data)?;
        frames += 1;
    }
    let secs = t0.elapsed().as_secs_f64();
    println!("{} フレーム / {:.1} 秒 = {:.1} fps、{:.1} Mbps", frames, secs, frames as f64 / secs, bytes as f64 * 8.0 / secs / 1e6);
    println!("符号化: 平均 {:.2} ms、最大 {:.2} ms", sum / frames.max(1) as f64, max);
    Ok(())
}
