//! Deskport の仮想カメラ(Windows 11 の Media Foundation 仮想カメラのメディアソース)。
//!
//! Windows の Frame Server(LOCAL SERVICE)が、この DLL を読み込んで、「Deskport Camera」というカメラとして、
//! Teams、Zoom、ブラウザなどに映像を渡す。映像は、ホスト(deskport-host)が書き出す共有ファイルから読む:
//!   C:\ProgramData\DeskportVCam\frame.bin
//!     [0]  u32 マジック 'DVCM'   [4] u32 幅   [8] u32 高さ   [12] u32 予約
//!     [16] u64 連番(書き込み中は奇数、書き終わると偶数)   [24] u64 最後に書いた時刻(Unix ミリ秒)
//!     [32..] BGRA の画素(上から下、幅×高さ×4 バイト)
//! 更新が1秒以上ない(スマホがつながっていない)ときは、暗い灰色の画面を出す。
//!
//! 登録: regsvr32(管理者権限。HKLM の InprocServer32 に、ThreadingModel=Both)。HKCU には登録できない(複数のプロセスが読み込むため)。
#![allow(non_snake_case, clippy::missing_safety_doc)]

mod frame;
mod source;

use std::ffi::c_void;
use std::sync::atomic::{AtomicIsize, Ordering};
use windows::core::*;
use windows::Win32::Foundation::*;
use windows::Win32::System::Com::*;
use windows::Win32::System::LibraryLoader::GetModuleFileNameW;
use windows::Win32::System::Registry::*;

/// 仮想カメラのメディアソースの CLSID(MFCreateVirtualCamera の sourceId に、この値を渡す)。
pub const CLSID_VCAM: GUID = GUID::from_u128(0x6d3a1c52_4b7e_4f0a_9c1d_5e8b2a7f10c3);
pub const CLSID_STR: &str = "{6D3A1C52-4B7E-4F0A-9C1D-5E8B2A7F10C3}";

/// 診断用のログ(C:\ProgramData\DeskportVCam\vcam.log)。書けなければ、何もしない。
pub fn log(msg: &str) {
    use std::io::Write;
    if !std::path::Path::new(r"C:\ProgramData\DeskportVCam\log.on").exists() { return; }
    if let Ok(mut f) = std::fs::OpenOptions::new().create(true).append(true).open(r"C:\ProgramData\DeskportVCam\vcam.log") {
        let exe = std::env::current_exe().ok().and_then(|p| p.file_name().map(|n| n.to_string_lossy().to_string())).unwrap_or_default();
        let _ = writeln!(f, "[v4 {} pid {}] {}", exe, std::process::id(), msg);
    }
}

static MODULE: AtomicIsize = AtomicIsize::new(0);

#[no_mangle]
pub extern "system" fn DllMain(hinst: HMODULE, reason: u32, _reserved: *mut c_void) -> BOOL {
    if reason == 1 { MODULE.store(hinst.0 as isize, Ordering::SeqCst); }   // DLL_PROCESS_ATTACH
    BOOL(1)
}

#[implement(IClassFactory)]
struct ClassFactory;

impl IClassFactory_Impl for ClassFactory_Impl {
    fn CreateInstance(&self, outer: Option<&IUnknown>, riid: *const GUID, ppv: *mut *mut c_void) -> Result<()> {
        crate::log("ClassFactory::CreateInstance");
        if outer.is_some() { return Err(CLASS_E_NOAGGREGATION.into()); }
        let act: IUnknown = source::Activator::new()?.into();
        unsafe { act.query(riid, ppv).ok() }
    }
    fn LockServer(&self, _lock: BOOL) -> Result<()> { Ok(()) }
}

#[no_mangle]
pub unsafe extern "system" fn DllGetClassObject(rclsid: *const GUID, riid: *const GUID, ppv: *mut *mut c_void) -> HRESULT {
    log("DllGetClassObject");
    if rclsid.is_null() || riid.is_null() || ppv.is_null() { return E_POINTER; }
    if *rclsid != CLSID_VCAM { return CLASS_E_CLASSNOTAVAILABLE; }
    let f: IClassFactory = ClassFactory.into();
    f.query(riid, ppv)
}

#[no_mangle]
pub extern "system" fn DllCanUnloadNow() -> HRESULT { S_FALSE }

fn key_path() -> String { format!("Software\\Classes\\CLSID\\{CLSID_STR}") }

#[no_mangle]
pub unsafe extern "system" fn DllRegisterServer() -> HRESULT {
    let mut path = [0u16; 520];
    let n = GetModuleFileNameW(HMODULE(MODULE.load(Ordering::SeqCst) as *mut c_void), &mut path) as usize;
    if n == 0 { return E_FAIL; }
    let sub = HSTRING::from(format!("{}\\InprocServer32", key_path()));
    let mut key = HKEY::default();
    if RegCreateKeyExW(HKEY_LOCAL_MACHINE, &sub, 0, PCWSTR::null(), REG_OPTION_NON_VOLATILE, KEY_WRITE, None, &mut key, None).is_err() { return E_ACCESSDENIED; }
    let bytes = std::slice::from_raw_parts(path.as_ptr() as *const u8, (n + 1) * 2);
    let r1 = RegSetValueExW(key, PCWSTR::null(), 0, REG_SZ, Some(bytes));
    let model: Vec<u16> = "Both\0".encode_utf16().collect();
    let mb = std::slice::from_raw_parts(model.as_ptr() as *const u8, model.len() * 2);
    let r2 = RegSetValueExW(key, w!("ThreadingModel"), 0, REG_SZ, Some(mb));
    let _ = RegCloseKey(key);
    if r1.is_err() || r2.is_err() { E_FAIL } else { S_OK }
}

#[no_mangle]
pub unsafe extern "system" fn DllUnregisterServer() -> HRESULT {
    let sub = HSTRING::from(key_path());
    let _ = RegDeleteTreeW(HKEY_LOCAL_MACHINE, &sub);
    S_OK
}
