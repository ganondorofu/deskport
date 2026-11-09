//! NVENC(NVIDIA のハードウェア符号化)を直接呼ぶ。入力は D3D11 テクスチャ(BGRA)で、色変換は NVENC が行う。
//! 低遅延の設定: Bフレームなし、1フレーム分の VBV、ゴップ無限(キーフレームは要求したときだけ)。
use anyhow::{anyhow, bail, Result};
use libloading::Library;
use moq_nvenc::sys::nvEncodeAPI as nv;
use std::ffi::c_void;
use windows::core::Interface;
use windows::Win32::Graphics::Direct3D11::{ID3D11Device, ID3D11Texture2D};

const fn ver(v: u32) -> u32 {
    nv::NVENCAPI_VERSION | (v << 16) | (0x7 << 28)
}
const FUNCTION_LIST_VER: u32 = ver(2);
const OPEN_SESSION_VER: u32 = ver(1);
const INIT_VER: u32 = ver(6) | (1 << 31);
const CONFIG_VER: u32 = ver(8) | (1 << 31);
const PRESET_CONFIG_VER: u32 = ver(4) | (1 << 31);
const PIC_VER: u32 = ver(6) | (1 << 31);
const LOCK_BITSTREAM_VER: u32 = ver(1) | (1 << 31);
const CREATE_BITSTREAM_VER: u32 = ver(1);
const REGISTER_VER: u32 = ver(4);
const MAP_VER: u32 = ver(4);

const HEVC_GUID: nv::GUID = nv::GUID {
    Data1: 0x790cdc88,
    Data2: 0x4522,
    Data3: 0x4d7b,
    Data4: [0x94, 0x25, 0xbd, 0xa9, 0x97, 0x5f, 0x76, 0x03],
};
const PRESET_P1_GUID: nv::GUID = nv::GUID {
    Data1: 0xfc0a8d3e,
    Data2: 0x45f8,
    Data3: 0x4cf8,
    Data4: [0x80, 0xc7, 0x29, 0x88, 0x71, 0x59, 0x0e, 0xbf],
};

/// 全部 0 で初期化する(NVENC の構造体は、0 を持たない列挙型を含むので mem::zeroed は拒否される)。
/// 呼び出し側で必要な項目をすべて埋める、または NVENC が埋める前提で使う。
unsafe fn zero<T>() -> T {
    std::mem::MaybeUninit::<T>::zeroed().assume_init()
}

pub struct Encoder {
    _lib: Library,
    f: nv::NV_ENCODE_API_FUNCTION_LIST,
    enc: *mut c_void,
    out: nv::NV_ENC_OUTPUT_PTR,
    reg: nv::NV_ENC_REGISTERED_PTR,
    width: u32,
    height: u32,
    n: u64,
}

/// NVENC の関数を呼び、失敗なら Err を返す。
macro_rules! call {
    ($f:expr, $name:ident, $($a:expr),*) => {{
        let func = $f.$name.ok_or_else(|| anyhow!(concat!(stringify!($name), " がありません")))?;
        let st = func($($a),*);
        if st != nv::NVENCSTATUS::NV_ENC_SUCCESS {
            bail!("{} が失敗: {:?}", stringify!($name), st);
        }
    }};
}

impl Encoder {
    /// tex は取得側が持つテクスチャ(登録して、毎フレームそのまま読ませる)。
    pub fn new(device: &ID3D11Device, tex: &ID3D11Texture2D, width: u32, height: u32, fps: u32, kbps: u32) -> Result<Self> {
        unsafe {
            let lib = Library::new("nvEncodeAPI64.dll")
                .map_err(|e| anyhow!("NVENC のドライバが見つかりません(NVIDIA の GPU とドライバが必要): {e}"))?;
            type Create = unsafe extern "C" fn(*mut nv::NV_ENCODE_API_FUNCTION_LIST) -> nv::NVENCSTATUS;
            let create: libloading::Symbol<Create> = lib.get(b"NvEncodeAPICreateInstance\0")?;
            let mut f: nv::NV_ENCODE_API_FUNCTION_LIST = zero();
            f.version = FUNCTION_LIST_VER;
            let st = create(&mut f);
            if st != nv::NVENCSTATUS::NV_ENC_SUCCESS {
                bail!("NVENC の初期化に失敗: {:?}(ドライバが古い可能性)", st);
            }

            // セッション(D3D11 デバイスで開く)
            let mut sp: nv::NV_ENC_OPEN_ENCODE_SESSION_EX_PARAMS = zero();
            sp.version = OPEN_SESSION_VER;
            sp.deviceType = nv::NV_ENC_DEVICE_TYPE::NV_ENC_DEVICE_TYPE_DIRECTX;
            sp.device = device.as_raw();
            sp.apiVersion = nv::NVENCAPI_VERSION;
            let mut enc: *mut c_void = std::ptr::null_mut();
            call!(f, nvEncOpenEncodeSessionEx, &mut sp, &mut enc);

            let mut this = Self { _lib: lib, f, enc, out: std::ptr::null_mut(), reg: std::ptr::null_mut(), width, height, n: 0 };

            // 既定の設定(P1 + 超低遅延)から始めて、必要な所だけ変える
            let mut preset: nv::NV_ENC_PRESET_CONFIG = zero();
            preset.version = PRESET_CONFIG_VER;
            preset.presetCfg.version = CONFIG_VER;
            call!(
                this.f,
                nvEncGetEncodePresetConfigEx,
                enc,
                HEVC_GUID,
                PRESET_P1_GUID,
                nv::NV_ENC_TUNING_INFO::NV_ENC_TUNING_INFO_ULTRA_LOW_LATENCY,
                &mut preset
            );
            let mut cfg = preset.presetCfg;
            cfg.gopLength = 0xffff_ffff; // キーフレームは要求したときだけ
            cfg.frameIntervalP = 1; // Bフレームなし
            cfg.rcParams.rateControlMode = nv::NV_ENC_PARAMS_RC_MODE::NV_ENC_PARAMS_RC_CBR;
            cfg.rcParams.averageBitRate = kbps * 1000;
            cfg.rcParams.maxBitRate = kbps * 1000;
            cfg.rcParams.vbvBufferSize = kbps * 1000 / fps; // 1フレーム分
            cfg.rcParams.vbvInitialDelay = kbps * 1000 / fps;
            let hevc = &mut cfg.encodeCodecConfig.hevcConfig;
            hevc.idrPeriod = 0xffff_ffff;
            hevc.set_repeatSPSPPS(1); // キーフレームごとに SPS/PPS を付ける(途中参加・復帰のため)

            let mut ip: nv::NV_ENC_INITIALIZE_PARAMS = zero();
            ip.version = INIT_VER;
            ip.encodeGUID = HEVC_GUID;
            ip.presetGUID = PRESET_P1_GUID;
            ip.encodeWidth = width;
            ip.encodeHeight = height;
            ip.darWidth = width;
            ip.darHeight = height;
            ip.frameRateNum = fps;
            ip.frameRateDen = 1;
            ip.enableEncodeAsync = 0;
            ip.enablePTD = 1;
            ip.encodeConfig = &mut cfg;
            ip.maxEncodeWidth = width;
            ip.maxEncodeHeight = height;
            ip.tuningInfo = nv::NV_ENC_TUNING_INFO::NV_ENC_TUNING_INFO_ULTRA_LOW_LATENCY;
            ip.bufferFormat = nv::NV_ENC_BUFFER_FORMAT::NV_ENC_BUFFER_FORMAT_ARGB;
            call!(this.f, nvEncInitializeEncoder, enc, &mut ip);

            // 出力バッファ
            let mut bb: nv::NV_ENC_CREATE_BITSTREAM_BUFFER = zero();
            bb.version = CREATE_BITSTREAM_VER;
            call!(this.f, nvEncCreateBitstreamBuffer, enc, &mut bb);
            this.out = bb.bitstreamBuffer;

            // 入力テクスチャの登録(1回だけ)
            let mut rr: nv::NV_ENC_REGISTER_RESOURCE = zero();
            rr.version = REGISTER_VER;
            rr.resourceType = nv::NV_ENC_INPUT_RESOURCE_TYPE::NV_ENC_INPUT_RESOURCE_TYPE_DIRECTX;
            rr.width = width;
            rr.height = height;
            rr.pitch = 0;
            rr.subResourceIndex = 0;
            rr.resourceToRegister = tex.as_raw();
            rr.bufferFormat = nv::NV_ENC_BUFFER_FORMAT::NV_ENC_BUFFER_FORMAT_ARGB;
            rr.bufferUsage = nv::NV_ENC_BUFFER_USAGE::NV_ENC_INPUT_IMAGE;
            call!(this.f, nvEncRegisterResource, enc, &mut rr);
            this.reg = rr.registeredResource;
            Ok(this)
        }
    }

    /// 1フレームを符号化して、符号化データを返す。force_idr=true でキーフレームにする。
    pub fn encode(&mut self, force_idr: bool) -> Result<Vec<u8>> {
        unsafe {
            let mut map: nv::NV_ENC_MAP_INPUT_RESOURCE = zero();
            map.version = MAP_VER;
            map.registeredResource = self.reg;
            call!(self.f, nvEncMapInputResource, self.enc, &mut map);

            let mut pic: nv::NV_ENC_PIC_PARAMS = zero();
            pic.version = PIC_VER;
            pic.inputWidth = self.width;
            pic.inputHeight = self.height;
            pic.inputPitch = self.width;
            pic.inputBuffer = map.mappedResource;
            pic.bufferFmt = map.mappedBufferFmt;
            pic.outputBitstream = self.out;
            pic.pictureStruct = nv::NV_ENC_PIC_STRUCT::NV_ENC_PIC_STRUCT_FRAME;
            pic.inputTimeStamp = self.n;
            if force_idr || self.n == 0 {
                pic.encodePicFlags = nv::NV_ENC_PIC_FLAGS::NV_ENC_PIC_FLAG_FORCEIDR as u32
                    | nv::NV_ENC_PIC_FLAGS::NV_ENC_PIC_FLAG_OUTPUT_SPSPPS as u32;
            }
            let r = self.encode_inner(&mut pic);
            if let Some(unmap) = self.f.nvEncUnmapInputResource {
                unmap(self.enc, map.mappedResource);
            }
            self.n += 1;
            r
        }
    }

    unsafe fn encode_inner(&mut self, pic: &mut nv::NV_ENC_PIC_PARAMS) -> Result<Vec<u8>> {
        call!(self.f, nvEncEncodePicture, self.enc, pic);
        let mut lock: nv::NV_ENC_LOCK_BITSTREAM = zero();
        lock.version = LOCK_BITSTREAM_VER;
        lock.outputBitstream = self.out;
        call!(self.f, nvEncLockBitstream, self.enc, &mut lock);
        let data = std::slice::from_raw_parts(lock.bitstreamBufferPtr as *const u8, lock.bitstreamSizeInBytes as usize).to_vec();
        call!(self.f, nvEncUnlockBitstream, self.enc, self.out);
        Ok(data)
    }
}

impl Drop for Encoder {
    fn drop(&mut self) {
        unsafe {
            if self.enc.is_null() {
                return;
            }
            if let Some(f) = self.f.nvEncUnregisterResource {
                if !self.reg.is_null() {
                    f(self.enc, self.reg);
                }
            }
            if let Some(f) = self.f.nvEncDestroyBitstreamBuffer {
                if !self.out.is_null() {
                    f(self.enc, self.out);
                }
            }
            if let Some(f) = self.f.nvEncDestroyEncoder {
                f(self.enc);
            }
        }
    }
}

