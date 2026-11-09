//! 画面の取得: DXGI Desktop Duplication。ディスプレイを1枚指定して、フレームを GPU 上のテクスチャとして受け取る。
use anyhow::{anyhow, bail, Result};
use windows::core::Interface;
use windows::Win32::Foundation::HMODULE;
use windows::Win32::Graphics::Direct3D::D3D_DRIVER_TYPE_UNKNOWN;
use windows::Win32::Graphics::Direct3D11::*;
use windows::Win32::Graphics::Dxgi::Common::DXGI_FORMAT_B8G8R8A8_UNORM;
use windows::Win32::Graphics::Dxgi::*;

pub struct Capturer {
    pub device: ID3D11Device,
    pub ctx: ID3D11DeviceContext,
    dup: IDXGIOutputDuplication,
    /// エンコーダに渡す、こちらで持つテクスチャ(取得したフレームをここへ写す)
    pub frame: ID3D11Texture2D,
    pub width: u32,
    pub height: u32,
    pub name: String,
    /// このディスプレイの左上(仮想デスクトップ上の物理ピクセル)。入力の座標合わせに使う。
    pub origin_x: i32,
    pub origin_y: i32,
}

/// つながっているディスプレイの数(全アダプタの合計)。
pub fn count() -> usize {
    unsafe {
        let Ok(factory) = CreateDXGIFactory1::<IDXGIFactory1>() else { return 1 };
        let (mut n, mut a) = (0usize, 0u32);
        while let Ok(adapter) = factory.EnumAdapters1(a) {
            let mut o = 0u32;
            while adapter.EnumOutputs(o).is_ok() { n += 1; o += 1; }
            a += 1;
        }
        n.max(1)
    }
}

impl Capturer {
    /// index 番目のディスプレイ(全アダプタを通した通し番号)。
    pub fn open(index: usize) -> Result<Self> {
        unsafe {
            let factory: IDXGIFactory1 = CreateDXGIFactory1()?;
            let mut n = 0usize;
            let mut a = 0u32;
            while let Ok(adapter) = factory.EnumAdapters1(a) {
                let mut o = 0u32;
                while let Ok(output) = adapter.EnumOutputs(o) {
                    if n == index {
                        return Self::from_output(&adapter, &output);
                    }
                    n += 1; o += 1;
                }
                a += 1;
            }
            bail!("ディスプレイ {} が見つかりません(全部で {} 枚)", index, n)
        }
    }

    unsafe fn from_output(adapter: &IDXGIAdapter1, output: &IDXGIOutput) -> Result<Self> {
        let mut device: Option<ID3D11Device> = None;
        let mut ctx: Option<ID3D11DeviceContext> = None;
        D3D11CreateDevice(
            adapter, D3D_DRIVER_TYPE_UNKNOWN, HMODULE::default(), D3D11_CREATE_DEVICE_BGRA_SUPPORT,
            None, D3D11_SDK_VERSION, Some(&mut device), None, Some(&mut ctx),
        )?;
        let device = device.ok_or_else(|| anyhow!("D3D11 デバイスを作れません"))?;
        let ctx = ctx.ok_or_else(|| anyhow!("D3D11 コンテキストを作れません"))?;

        let desc = output.GetDesc()?;
        let name = String::from_utf16_lossy(&desc.DeviceName).trim_end_matches('\0').to_string();

        let output1: IDXGIOutput1 = output.cast()?;
        let dup = output1.DuplicateOutput(&device)?;
        // DesktopCoordinates は DPI の拡大率で縮んで見える(150% なら 1280x720)ので、実際の解像度は複製の情報から取る
        let md = dup.GetDesc().ModeDesc;
        let (width, height) = (md.Width, md.Height);

        let td = D3D11_TEXTURE2D_DESC {
            Width: width, Height: height, MipLevels: 1, ArraySize: 1,
            Format: DXGI_FORMAT_B8G8R8A8_UNORM,
            SampleDesc: windows::Win32::Graphics::Dxgi::Common::DXGI_SAMPLE_DESC { Count: 1, Quality: 0 },
            Usage: D3D11_USAGE_DEFAULT, BindFlags: 0, CPUAccessFlags: 0, MiscFlags: 0,
        };
        let mut frame: Option<ID3D11Texture2D> = None;
        device.CreateTexture2D(&td, None, Some(&mut frame))?;
        Ok(Self { device, ctx, dup, frame: frame.unwrap(), width, height, name, origin_x: desc.DesktopCoordinates.left, origin_y: desc.DesktopCoordinates.top })
    }

    /// 次のフレームを待つ。画面に変化があれば true(frame に写してある)、時間切れなら false。
    pub fn next(&mut self, timeout_ms: u32) -> Result<bool> {
        unsafe {
            let mut info = DXGI_OUTDUPL_FRAME_INFO::default();
            let mut res: Option<IDXGIResource> = None;
            match self.dup.AcquireNextFrame(timeout_ms, &mut info, &mut res) {
                Ok(()) => {}
                Err(e) if e.code() == DXGI_ERROR_WAIT_TIMEOUT => return Ok(false),
                Err(e) => return Err(anyhow!("画面を取得できません: {e}")),   // 例: ロック画面/UAC で DXGI_ERROR_ACCESS_LOST
            }
            let tex: ID3D11Texture2D = res.ok_or_else(|| anyhow!("空のフレーム"))?.cast()?;
            self.ctx.CopyResource(&self.frame, &tex);
            self.dup.ReleaseFrame()?;
            Ok(true)
        }
    }
}


