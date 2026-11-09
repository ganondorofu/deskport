//! カーソルの形の取り出し。映像にはカーソルが写らないので、形を画像として取り出して端末へ送る。
//!
//! 取り出し方: カーソルを、黒い背景と白い背景のそれぞれに描き、2枚の差から透明度と色を求める。
//! これで、色つき(半透明)のカーソルも、白黒で反転するカーソル(文字入力の縦線など)も、同じ手順で扱える。
use std::ffi::c_void;
use windows::Win32::Foundation::HANDLE;
use windows::Win32::Graphics::Gdi::*;
use windows::Win32::UI::WindowsAndMessaging::*;

pub struct Shape {
    pub id: u32,
    pub w: u16,
    pub h: u16,
    pub hot_x: u16,
    pub hot_y: u16,
    pub rgba: Vec<u8>,
    /// 読み込み中(輪のカーソル)。画像は1コマしか取れないので、端末側で回転する輪を描く。
    pub busy: bool,
}

/// いまのカーソルのハンドルを返す(変化の検出用)。
pub fn current_handle() -> Option<isize> {
    unsafe {
        let mut ci = CURSORINFO { cbSize: std::mem::size_of::<CURSORINFO>() as u32, ..Default::default() };
        GetCursorInfo(&mut ci).ok()?;
        Some(ci.hCursor.0 as isize)
    }
}

fn is_busy(h: isize) -> bool {
    unsafe {
        [IDC_WAIT, IDC_APPSTARTING]
            .iter()
            .any(|id| LoadCursorW(None, *id).map_or(false, |c| c.0 as isize == h))
    }
}

/// ハンドルのカーソルを、画像として取り出す。
pub fn capture(handle: isize) -> Option<Shape> {
    unsafe {
        let hicon = HICON(handle as *mut c_void);
        let mut info = ICONINFO::default();
        GetIconInfo(hicon, &mut info).ok()?;
        // 大きさ(色つきなら色の画像、白黒なら、マスク画像の上半分)
        let (bm_src, halve) = if !info.hbmColor.is_invalid() { (info.hbmColor, false) } else { (info.hbmMask, true) };
        let mut bm = BITMAP::default();
        GetObjectW(HGDIOBJ(bm_src.0), std::mem::size_of::<BITMAP>() as i32, Some(&mut bm as *mut _ as *mut c_void));
        let w = bm.bmWidth.max(1);
        let h = (if halve { bm.bmHeight / 2 } else { bm.bmHeight }).max(1);
        let (hot_x, hot_y) = (info.xHotspot as u16, info.yHotspot as u16);
        if !info.hbmColor.is_invalid() { let _ = DeleteObject(HGDIOBJ(info.hbmColor.0)); }
        if !info.hbmMask.is_invalid() { let _ = DeleteObject(HGDIOBJ(info.hbmMask.0)); }
        if w > 256 || h > 256 { return None; }

        let hdc = CreateCompatibleDC(None);
        let mut bmi = BITMAPINFO::default();
        bmi.bmiHeader = BITMAPINFOHEADER {
            biSize: std::mem::size_of::<BITMAPINFOHEADER>() as u32,
            biWidth: w, biHeight: -h, biPlanes: 1, biBitCount: 32, biCompression: BI_RGB.0,
            ..Default::default()
        };
        let mut bits: *mut c_void = std::ptr::null_mut();
        let dib = CreateDIBSection(hdc, &bmi, DIB_RGB_COLORS, &mut bits, HANDLE::default(), 0).ok();
        let Some(dib) = dib else { let _ = DeleteDC(hdc); return None };
        if bits.is_null() { let _ = DeleteObject(HGDIOBJ(dib.0)); let _ = DeleteDC(hdc); return None; }
        let old = SelectObject(hdc, HGDIOBJ(dib.0));
        let n = (w * h * 4) as usize;
        let buf = std::slice::from_raw_parts_mut(bits as *mut u8, n);

        let mut render = |fill: u8| -> Vec<u8> {
            for px in buf.chunks_exact_mut(4) { px[0] = fill; px[1] = fill; px[2] = fill; px[3] = 255; }
            let _ = DrawIconEx(hdc, 0, 0, hicon, w, h, 0, None, DI_NORMAL);
            buf.to_vec()
        };
        let on_black = render(0);
        let on_white = render(255);
        SelectObject(hdc, old);
        let _ = DeleteObject(HGDIOBJ(dib.0));
        let _ = DeleteDC(hdc);

        // 2枚の差から、透明度と色を求める(BGRA → RGBA)
        let mut rgba = vec![0u8; n];
        for i in 0..(n / 4) {
            let (b0, w0) = (&on_black[i * 4..i * 4 + 3], &on_white[i * 4..i * 4 + 3]);
            let a = (0..3).map(|c| 255 - (w0[c] as i32 - b0[c] as i32)).sum::<i32>() / 3;
            let a = a.clamp(0, 255);
            if a == 0 { continue; }
            // b0 は「色 × 透明度」を黒に重ねたもの。色に戻す
            rgba[i * 4] = ((b0[2] as i32 * 255 / a).min(255)) as u8;
            rgba[i * 4 + 1] = ((b0[1] as i32 * 255 / a).min(255)) as u8;
            rgba[i * 4 + 2] = ((b0[0] as i32 * 255 / a).min(255)) as u8;
            rgba[i * 4 + 3] = a as u8;
        }
        // 同じ形なら同じ番号になるように、内容から番号を作る(ハンドルの使い回しで取り違えないため)
        let mut id: u32 = 0x811c9dc5;
        for b in [w as u8, (w >> 8) as u8, h as u8, (h >> 8) as u8, hot_x as u8, hot_y as u8].iter().chain(rgba.iter()) {
            id = (id ^ *b as u32).wrapping_mul(0x01000193);
        }
        Some(Shape { id, w: w as u16, h: h as u16, hot_x, hot_y, rgba, busy: is_busy(handle) })
    }
}
