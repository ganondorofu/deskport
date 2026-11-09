//! 仮想カメラのメディアソース。構成(Microsoft の仮想カメラの作法):
//!   Activator(IMFActivate)  … Frame Server が CLSID から作る。ActivateObject で MediaSource を返す。
//!   MediaSource(IMFMediaSourceEx) … ストリームを1本持つ。
//!   MediaStream(IMFMediaStream2)  … 30fps で、共有ファイルの最新の1枚を、RGB32 のサンプルにして渡す。
use crate::frame::*;
use crate::log;
use std::collections::VecDeque;
use std::ffi::c_void;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use windows::core::*;
use windows::Win32::Foundation::*;
use windows::Win32::Media::KernelStreaming::*;
use windows::Win32::Media::MediaFoundation::*;

const ERROR_SET_NOT_FOUND: HRESULT = HRESULT(0x8007_0492_u32 as i32);

fn opt<T>(p: *mut T) -> Option<*mut T> { if p.is_null() { None } else { Some(p) } }

unsafe fn new_attrs() -> Result<IMFAttributes> {
    let mut a: Option<IMFAttributes> = None;
    MFCreateAttributes(&mut a, 16)?;
    a.ok_or_else(|| E_FAIL.into())
}

/// MFSetAttributeSize / MFSetAttributeRatio(上位32ビット=幅/分子、下位32ビット=高さ/分母)
fn pack(a: u32, b: u32) -> u64 { ((a as u64) << 32) | b as u64 }

unsafe fn media_type() -> Result<IMFMediaType> {
    let t = MFCreateMediaType()?;
    t.SetGUID(&MF_MT_MAJOR_TYPE, &MFMediaType_Video)?;
    t.SetGUID(&MF_MT_SUBTYPE, &MFVideoFormat_RGB32)?;
    t.SetUINT64(&MF_MT_FRAME_SIZE, pack(WIDTH, HEIGHT))?;
    t.SetUINT64(&MF_MT_FRAME_RATE, pack(30, 1))?;
    t.SetUINT64(&MF_MT_PIXEL_ASPECT_RATIO, pack(1, 1))?;
    t.SetUINT32(&MF_MT_INTERLACE_MODE, MFVideoInterlace_Progressive.0 as u32)?;
    t.SetUINT32(&MF_MT_ALL_SAMPLES_INDEPENDENT, 1)?;
    t.SetUINT32(&MF_MT_DEFAULT_STRIDE, WIDTH * 4)?;   // 正の値 = 上から下
    t.SetUINT32(&MF_MT_SAMPLE_SIZE, WIDTH * HEIGHT * 4)?;
    t.SetUINT32(&MF_MT_AVG_BITRATE, WIDTH * HEIGHT * 4 * 8 * 30)?;
    Ok(t)
}

// ================= 属性の転送 =================

/// IMFAttributes の呼び出しを、内部の属性の入れ物(self.attrs)へ、そのまま渡す。
macro_rules! forward_attributes {
    ($t:ty) => {
        impl IMFAttributes_Impl for $t {
            fn GetItem(&self, k: *const GUID, v: *mut PROPVARIANT) -> Result<()> { unsafe { self.attrs.GetItem(k, opt(v)) } }
            fn GetItemType(&self, k: *const GUID) -> Result<MF_ATTRIBUTE_TYPE> { unsafe { self.attrs.GetItemType(k) } }
            fn CompareItem(&self, k: *const GUID, v: *const PROPVARIANT) -> Result<BOOL> { unsafe { self.attrs.CompareItem(k, v) } }
            fn Compare(&self, t: Option<&IMFAttributes>, m: MF_ATTRIBUTES_MATCH_TYPE) -> Result<BOOL> { unsafe { self.attrs.Compare(t, m) } }
            fn GetUINT32(&self, k: *const GUID) -> Result<u32> {
                let r = unsafe { self.attrs.GetUINT32(k) };
                r
            }
            fn GetUINT64(&self, k: *const GUID) -> Result<u64> { unsafe { self.attrs.GetUINT64(k) } }
            fn GetDouble(&self, k: *const GUID) -> Result<f64> { unsafe { self.attrs.GetDouble(k) } }
            fn GetGUID(&self, k: *const GUID) -> Result<GUID> {
                let r = unsafe { self.attrs.GetGUID(k) };
                r
            }
            fn GetStringLength(&self, k: *const GUID) -> Result<u32> { unsafe { self.attrs.GetStringLength(k) } }
            fn GetString(&self, k: *const GUID, s: PWSTR, n: u32, len: *mut u32) -> Result<()> {
                unsafe { self.attrs.GetString(k, std::slice::from_raw_parts_mut(s.0, n as usize), opt(len)) }
            }
            fn GetAllocatedString(&self, k: *const GUID, s: *mut PWSTR, len: *mut u32) -> Result<()> { unsafe { self.attrs.GetAllocatedString(k, s, len) } }
            fn GetBlobSize(&self, k: *const GUID) -> Result<u32> { unsafe { self.attrs.GetBlobSize(k) } }
            fn GetBlob(&self, k: *const GUID, b: *mut u8, n: u32, sz: *mut u32) -> Result<()> {
                unsafe { self.attrs.GetBlob(k, std::slice::from_raw_parts_mut(b, n as usize), opt(sz)) }
            }
            fn GetAllocatedBlob(&self, k: *const GUID, b: *mut *mut u8, sz: *mut u32) -> Result<()> { unsafe { self.attrs.GetAllocatedBlob(k, b, sz) } }
            fn GetUnknown(&self, k: *const GUID, riid: *const GUID, ppv: *mut *mut c_void) -> Result<()> {
                let r = unsafe { (Interface::vtable(&self.attrs).GetUnknown)(Interface::as_raw(&self.attrs), k, riid, ppv).ok() };
                r
            }
            fn SetItem(&self, k: *const GUID, v: *const PROPVARIANT) -> Result<()> { unsafe { self.attrs.SetItem(k, v) } }
            fn DeleteItem(&self, k: *const GUID) -> Result<()> { unsafe { self.attrs.DeleteItem(k) } }
            fn DeleteAllItems(&self) -> Result<()> { unsafe { self.attrs.DeleteAllItems() } }
            fn SetUINT32(&self, k: *const GUID, v: u32) -> Result<()> {
                unsafe { self.attrs.SetUINT32(k, v) }
            }
            fn SetUINT64(&self, k: *const GUID, v: u64) -> Result<()> { unsafe { self.attrs.SetUINT64(k, v) } }
            fn SetDouble(&self, k: *const GUID, v: f64) -> Result<()> { unsafe { self.attrs.SetDouble(k, v) } }
            fn SetGUID(&self, k: *const GUID, v: *const GUID) -> Result<()> {
                unsafe { self.attrs.SetGUID(k, v) }
            }
            fn SetString(&self, k: *const GUID, v: &PCWSTR) -> Result<()> {
                unsafe { self.attrs.SetString(k, *v) }
            }
            fn SetBlob(&self, k: *const GUID, b: *const u8, n: u32) -> Result<()> {
                unsafe { self.attrs.SetBlob(k, std::slice::from_raw_parts(b, n as usize)) }
            }
            fn SetUnknown(&self, k: *const GUID, u: Option<&IUnknown>) -> Result<()> {
                unsafe { self.attrs.SetUnknown(k, u) }
            }
            fn LockStore(&self) -> Result<()> { unsafe { self.attrs.LockStore() } }
            fn UnlockStore(&self) -> Result<()> { unsafe { self.attrs.UnlockStore() } }
            fn GetCount(&self) -> Result<u32> { unsafe { self.attrs.GetCount() } }
            fn GetItemByIndex(&self, i: u32, k: *mut GUID, v: *mut PROPVARIANT) -> Result<()> { unsafe { self.attrs.GetItemByIndex(i, k, opt(v)) } }
            fn CopyAllItems(&self, d: Option<&IMFAttributes>) -> Result<()> {
                unsafe { self.attrs.CopyAllItems(d) }
            }
        }
    };
}

// ================= Activator =================

#[implement(IMFActivate)]
pub struct Activator {
    attrs: IMFAttributes,
    source: Mutex<Option<IMFMediaSourceEx>>,
}

impl Activator {
    pub fn new() -> Result<Self> {
        Ok(Self { attrs: unsafe { new_attrs()? }, source: Mutex::new(None) })
    }
}

forward_attributes!(Activator_Impl);

impl IMFActivate_Impl for Activator_Impl {
    fn ActivateObject(&self, riid: *const GUID, ppv: *mut *mut c_void) -> Result<()> {
        log("ActivateObject");
        let src: IMFMediaSourceEx = match MediaSource::create(&self.attrs) {
            Ok(s) => s,
            Err(e) => { log(&format!("MediaSource::create 失敗 {e:?}")); return Err(e); }
        };
        *self.source.lock().unwrap() = Some(src.clone());
        unsafe { src.query(riid, ppv).ok() }
    }
    fn ShutdownObject(&self) -> Result<()> {
        log("ShutdownObject");
        if let Some(s) = self.source.lock().unwrap().take() { let _ = unsafe { s.Shutdown() }; }
        Ok(())
    }
    fn DetachObject(&self) -> Result<()> {
        log("DetachObject");
        self.source.lock().unwrap().take();
        Ok(())
    }
}

// ================= MediaSource =================

#[implement(IMFMediaSourceEx, IMFGetService, IKsControl, IMFSampleAllocatorControl, IMFAttributes)]
pub struct MediaSource {
    queue: IMFMediaEventQueue,
    attrs: IMFAttributes,
    stream_attrs: IMFAttributes,
    pd: IMFPresentationDescriptor,
    stream: ComObject<MediaStream>,
    started: AtomicBool,
    shut: AtomicBool,
}

forward_attributes!(MediaSource_Impl);

impl MediaSource {
    fn create(init: &IMFAttributes) -> Result<IMFMediaSourceEx> {
        unsafe {
            let attrs = new_attrs()?;
            init.CopyAllItems(&attrs)?;
            // センサープロファイルは付けない(Windows 11 25H2 では、付けると IMFVirtualCamera::Start が MF_E_NOT_FOUND で失敗した)

            let mt = media_type()?;
            let sd = MFCreateStreamDescriptor(0, &[Some(mt.clone())])?;
            sd.GetMediaTypeHandler()?.SetCurrentMediaType(&mt)?;
            sd.SetGUID(&MF_DEVICESTREAM_STREAM_CATEGORY, &PINNAME_VIDEO_CAPTURE)?;
            sd.SetUINT32(&MF_DEVICESTREAM_STREAM_ID, 0)?;
            sd.SetUINT32(&MF_DEVICESTREAM_FRAMESERVER_SHARED, 1)?;
            sd.SetUINT32(&MF_DEVICESTREAM_ATTRIBUTE_FRAMESOURCE_TYPES, MFFrameSourceTypes_Color.0 as u32)?;
            let pd = MFCreatePresentationDescriptor(Some(&[Some(sd.clone())]))?;
            pd.SelectStream(0)?;

            let stream_attrs = new_attrs()?;
            stream_attrs.SetGUID(&MF_DEVICESTREAM_STREAM_CATEGORY, &PINNAME_VIDEO_CAPTURE)?;
            stream_attrs.SetUINT32(&MF_DEVICESTREAM_STREAM_ID, 0)?;
            stream_attrs.SetUINT32(&MF_DEVICESTREAM_FRAMESERVER_SHARED, 1)?;
            stream_attrs.SetUINT32(&MF_DEVICESTREAM_ATTRIBUTE_FRAMESOURCE_TYPES, MFFrameSourceTypes_Color.0 as u32)?;

            let stream = ComObject::new(MediaStream::new(mt, sd, stream_attrs.clone())?);
            let src = ComObject::new(MediaSource {
                queue: MFCreateEventQueue()?, attrs, stream_attrs, pd, stream: stream.clone(),
                started: AtomicBool::new(false), shut: AtomicBool::new(false),
            });
            let as_source: IMFMediaSource = src.to_interface::<IMFMediaSourceEx>().cast()?;
            stream.set_source(as_source);
            Ok(src.to_interface())
        }
    }

    fn check(&self) -> Result<()> {
        if self.shut.load(Ordering::SeqCst) { Err(MF_E_SHUTDOWN.into()) } else { Ok(()) }
    }
}

impl IMFMediaEventGenerator_Impl for MediaSource_Impl {
    fn GetEvent(&self, flags: MEDIA_EVENT_GENERATOR_GET_EVENT_FLAGS) -> Result<IMFMediaEvent> {
        log("source GetEvent");
        self.check()?;
        unsafe { self.queue.GetEvent(flags.0 as u32) }
    }
    fn BeginGetEvent(&self, cb: Option<&IMFAsyncCallback>, state: Option<&IUnknown>) -> Result<()> {
        log("source BeginGetEvent");
        self.check()?;
        unsafe { self.queue.BeginGetEvent(cb, state) }
    }
    fn EndGetEvent(&self, r: Option<&IMFAsyncResult>) -> Result<IMFMediaEvent> { self.check()?; unsafe { self.queue.EndGetEvent(r) } }
    fn QueueEvent(&self, met: u32, ext: *const GUID, hr: HRESULT, v: *const PROPVARIANT) -> Result<()> { self.check()?; unsafe { self.queue.QueueEventParamVar(met, ext, hr, v) } }
}

impl IMFMediaSource_Impl for MediaSource_Impl {
    fn GetCharacteristics(&self) -> Result<u32> { log("GetCharacteristics"); self.check()?; Ok(MFMEDIASOURCE_IS_LIVE.0 as u32) }
    fn CreatePresentationDescriptor(&self) -> Result<IMFPresentationDescriptor> { log("CreatePresentationDescriptor"); self.check()?; unsafe { self.pd.Clone() } }
    fn Start(&self, pd: Option<&IMFPresentationDescriptor>, fmt: *const GUID, _pos: *const PROPVARIANT) -> Result<()> {
        log("MediaSource::Start");
        self.check()?;
        if pd.is_none() { return Err(E_POINTER.into()); }
        if !fmt.is_null() && unsafe { *fmt } != GUID::zeroed() { return Err(E_INVALIDARG.into()); }
        unsafe {
            self.pd.SelectStream(0)?;
            let first = !self.started.swap(true, Ordering::SeqCst);
            let stream_unk: IUnknown = self.stream.to_interface::<IMFMediaStream2>().cast()?;
            let met = if first { MENewStream } else { MEUpdatedStream };
            self.queue.QueueEventParamUnk(met.0 as u32, &GUID::zeroed(), S_OK, &stream_unk)?;
            self.stream.start()?;
            self.queue.QueueEventParamVar(MESourceStarted.0 as u32, &GUID::zeroed(), S_OK, &PROPVARIANT::default())?;
        }
        Ok(())
    }
    fn Stop(&self) -> Result<()> {
        log("MediaSource::Stop");
        self.check()?;
        self.stream.stop();
        unsafe { self.queue.QueueEventParamVar(MESourceStopped.0 as u32, &GUID::zeroed(), S_OK, &PROPVARIANT::default()) }
    }
    fn Pause(&self) -> Result<()> { Err(MF_E_INVALID_STATE_TRANSITION.into()) }
    fn Shutdown(&self) -> Result<()> {
        log("MediaSource::Shutdown");
        if self.shut.swap(true, Ordering::SeqCst) { return Ok(()); }
        self.stream.shutdown();
        unsafe { let _ = self.queue.Shutdown(); }
        Ok(())
    }
}

impl IMFMediaSourceEx_Impl for MediaSource_Impl {
    fn GetSourceAttributes(&self) -> Result<IMFAttributes> { log("GetSourceAttributes"); self.check()?; Ok(self.attrs.clone()) }
    fn GetStreamAttributes(&self, _id: u32) -> Result<IMFAttributes> { log("GetStreamAttributes"); self.check()?; Ok(self.stream_attrs.clone()) }
    fn SetD3DManager(&self, _m: Option<&IUnknown>) -> Result<()> { log("SetD3DManager"); Ok(()) }   // システムメモリのサンプルを使う
}

impl IMFGetService_Impl for MediaSource_Impl {
    fn GetService(&self, _s: *const GUID, _riid: *const GUID, _ppv: *mut *mut c_void) -> Result<()> { log("GetService"); Err(MF_E_UNSUPPORTED_SERVICE.into()) }
}

/// カメラの制御(プライバシー、明るさなど)には対応しない。Frame Server は、「未対応」と答えれば、そのまま動く。
impl IKsControl_Impl for MediaSource_Impl {
    fn KsProperty(&self, _p: *const KSIDENTIFIER, _pl: u32, _d: *mut c_void, _dl: u32, _r: *mut u32) -> Result<()> { Err(ERROR_SET_NOT_FOUND.into()) }
    fn KsMethod(&self, _p: *const KSIDENTIFIER, _pl: u32, _d: *mut c_void, _dl: u32, _r: *mut u32) -> Result<()> { Err(ERROR_SET_NOT_FOUND.into()) }
    fn KsEvent(&self, _p: *const KSIDENTIFIER, _pl: u32, _d: *mut c_void, _dl: u32, _r: *mut u32) -> Result<()> { Err(ERROR_SET_NOT_FOUND.into()) }
}

impl IMFSampleAllocatorControl_Impl for MediaSource_Impl {
    fn SetDefaultAllocator(&self, _id: u32, allocator: Option<&IUnknown>) -> Result<()> {
        log("SetDefaultAllocator");
        self.check()?;
        self.stream.set_allocator(allocator.and_then(|a| a.cast::<IMFVideoSampleAllocator>().ok()));
        Ok(())
    }
    fn GetAllocatorUsage(&self, id: u32, input: *mut u32, usage: *mut MFSampleAllocatorUsage) -> Result<()> {
        log("GetAllocatorUsage");
        if input.is_null() || usage.is_null() { return Err(E_POINTER.into()); }
        unsafe { *input = id; *usage = MFSampleAllocatorUsage_UsesProvidedAllocator; }
        Ok(())
    }
}

// ================= MediaStream =================

struct Token(Option<IUnknown>);
unsafe impl Send for Token {}

struct StreamState {
    source: Option<IMFMediaSource>,
    allocator: Option<IMFVideoSampleAllocator>,
    running: bool,
    stop: Option<Arc<AtomicBool>>,
    tokens: VecDeque<Token>,
}
unsafe impl Send for StreamState {}

#[implement(IMFMediaStream2, IKsControl, IMFAttributes)]
pub struct MediaStream {
    attrs: IMFAttributes,
    queue: IMFMediaEventQueue,
    sd: IMFStreamDescriptor,
    mt: IMFMediaType,
    st: Arc<Mutex<StreamState>>,
    state: Mutex<i32>,
}

forward_attributes!(MediaStream_Impl);

struct SendQueue(IMFMediaEventQueue);
unsafe impl Send for SendQueue {}
struct SendAlloc(IMFVideoSampleAllocator);
unsafe impl Send for SendAlloc {}

impl MediaStream {
    fn new(mt: IMFMediaType, sd: IMFStreamDescriptor, attrs: IMFAttributes) -> Result<Self> {
        unsafe {
            Ok(Self {
                attrs, queue: MFCreateEventQueue()?, sd, mt,
                st: Arc::new(Mutex::new(StreamState { source: None, allocator: None, running: false, stop: None, tokens: VecDeque::new() })),
                state: Mutex::new(MF_STREAM_STATE_STOPPED.0),
            })
        }
    }
    fn set_source(&self, s: IMFMediaSource) { self.st.lock().unwrap().source = Some(s); }
    fn set_allocator(&self, a: Option<IMFVideoSampleAllocator>) { self.st.lock().unwrap().allocator = a; }

    fn start(&self) -> Result<()> {
        let mut g = self.st.lock().unwrap();
        if g.running { return Ok(()); }
        let alloc = g.allocator.clone().ok_or_else(|| Error::from(MF_E_SHUTDOWN))?;
        unsafe { alloc.InitializeSampleAllocator(10, &self.mt)?; }
        let stop = Arc::new(AtomicBool::new(false));
        g.stop = Some(stop.clone());
        g.running = true;
        *self.state.lock().unwrap() = MF_STREAM_STATE_RUNNING.0;
        let st = self.st.clone();
        // 30fps で、要求されたぶんだけサンプルを返す(要求が速くても、30fps を超えない)
        let q = SendQueue(self.queue.clone());
        let a = SendAlloc(alloc);
        std::thread::spawn(move || { let q = q; let a = a; worker(st, q.0, a, stop); });
        unsafe { self.queue.QueueEventParamVar(MEStreamStarted.0 as u32, &GUID::zeroed(), S_OK, &PROPVARIANT::default())?; }
        Ok(())
    }

    fn stop(&self) {
        let mut g = self.st.lock().unwrap();
        if let Some(s) = g.stop.take() { s.store(true, Ordering::SeqCst); }
        if g.running {
            g.running = false;
            g.tokens.clear();
            if let Some(a) = &g.allocator { unsafe { let _ = a.UninitializeSampleAllocator(); } }
            *self.state.lock().unwrap() = MF_STREAM_STATE_STOPPED.0;
            unsafe { let _ = self.queue.QueueEventParamVar(MEStreamStopped.0 as u32, &GUID::zeroed(), S_OK, &PROPVARIANT::default()); }
        }
    }

    fn shutdown(&self) {
        self.stop();
        unsafe { let _ = self.queue.Shutdown(); }
        let mut g = self.st.lock().unwrap();
        g.source = None;   // 循環参照を切る
        g.allocator = None;
    }
}

fn worker(st: Arc<Mutex<StreamState>>, queue: IMFMediaEventQueue, alloc: SendAlloc, stop: Arc<AtomicBool>) {
    // このスレッドから、COM のオブジェクトを使う(MTA)
    unsafe { let _ = windows::Win32::System::Com::CoInitializeEx(None, windows::Win32::System::Com::COINIT_MULTITHREADED); }
    let mut reader = FrameReader::new();
    let mut buf = vec![0u8; (WIDTH * HEIGHT * 4) as usize];
    let mut next = std::time::Instant::now();
    let mut delivered = 0u64;
    let mut idle = 0u64;
    log("worker 開始");
    while !stop.load(Ordering::SeqCst) {
        let token = { st.lock().unwrap().tokens.pop_front() };
        let Some(token) = token else {
            idle += 1;
            if idle % 500 == 0 { log("worker: 要求を待っています"); }
            std::thread::sleep(std::time::Duration::from_millis(2));
            continue;
        };
        let now = std::time::Instant::now();
        if now < next { std::thread::sleep(next - now); }
        next = std::time::Instant::now() + std::time::Duration::from_micros(33_333);
        let ok = reader.read(&mut buf);
        if delivered < 3 { log(&format!("フレーム読み込み: 映像あり={ok}")); }
        unsafe {
            let t0 = std::time::Instant::now();
            let Ok(sample) = alloc.0.AllocateSample() else { log(&format!("AllocateSample 失敗 ({}ms)", t0.elapsed().as_millis())); continue };
            if t0.elapsed().as_millis() > 50 { log(&format!("AllocateSample が遅い: {}ms", t0.elapsed().as_millis())); }
            let Ok(mb) = sample.GetBufferByIndex(0) else { log("GetBufferByIndex 失敗"); continue };
            let (mut data, mut max, mut cur) = (std::ptr::null_mut::<u8>(), 0u32, 0u32);
            if mb.Lock(&mut data, Some(&mut max), Some(&mut cur)).is_err() { log("Lock 失敗"); continue; }
            let n = ((WIDTH * HEIGHT * 4) as usize).min(max as usize);
            std::ptr::copy_nonoverlapping(buf.as_ptr(), data, n);
            let _ = mb.Unlock();
            let _ = mb.SetCurrentLength(n as u32);
            let _ = sample.SetSampleTime(MFGetSystemTime());
            let _ = sample.SetSampleDuration(333_333);
            if let Some(t) = &token.0 { let _ = sample.SetUnknown(&MFSampleExtension_Token, t); }
            if let Ok(unk) = sample.cast::<IUnknown>() {
                let r = queue.QueueEventParamUnk(MEMediaSample.0 as u32, &GUID::zeroed(), S_OK, &unk);
                delivered += 1;
                if delivered <= 10 || delivered % 90 == 0 { log(&format!("サンプルを返しました #{delivered} {}バイト -> {:?}", n, r)); }
            }
        }
    }
}

impl IMFMediaEventGenerator_Impl for MediaStream_Impl {
    fn GetEvent(&self, flags: MEDIA_EVENT_GENERATOR_GET_EVENT_FLAGS) -> Result<IMFMediaEvent> { unsafe { self.queue.GetEvent(flags.0 as u32) } }
    fn BeginGetEvent(&self, cb: Option<&IMFAsyncCallback>, state: Option<&IUnknown>) -> Result<()> { unsafe { self.queue.BeginGetEvent(cb, state) } }
    fn EndGetEvent(&self, r: Option<&IMFAsyncResult>) -> Result<IMFMediaEvent> { unsafe { self.queue.EndGetEvent(r) } }
    fn QueueEvent(&self, met: u32, ext: *const GUID, hr: HRESULT, v: *const PROPVARIANT) -> Result<()> { unsafe { self.queue.QueueEventParamVar(met, ext, hr, v) } }
}

impl IMFMediaStream_Impl for MediaStream_Impl {
    fn GetMediaSource(&self) -> Result<IMFMediaSource> {
        log("stream GetMediaSource");
        self.st.lock().unwrap().source.clone().ok_or_else(|| MF_E_SHUTDOWN.into())
    }
    fn GetStreamDescriptor(&self) -> Result<IMFStreamDescriptor> { log("stream GetStreamDescriptor"); Ok(self.sd.clone()) }
    fn RequestSample(&self, token: Option<&IUnknown>) -> Result<()> {
        log("RequestSample");
        let mut g = self.st.lock().unwrap();
        if !g.running { return Err(MF_E_INVALIDREQUEST.into()); }
        g.tokens.push_back(Token(token.cloned()));
        Ok(())
    }
}

impl IMFMediaStream2_Impl for MediaStream_Impl {
    fn SetStreamState(&self, value: MF_STREAM_STATE) -> Result<()> {
        log(&format!("SetStreamState {}", value.0));
        if value == MF_STREAM_STATE_RUNNING { self.start() } else if value == MF_STREAM_STATE_STOPPED { self.stop(); Ok(()) } else { Ok(()) }
    }
    fn GetStreamState(&self) -> Result<MF_STREAM_STATE> { Ok(MF_STREAM_STATE(*self.state.lock().unwrap())) }
}

impl IKsControl_Impl for MediaStream_Impl {
    fn KsProperty(&self, _p: *const KSIDENTIFIER, _pl: u32, _d: *mut c_void, _dl: u32, _r: *mut u32) -> Result<()> { log("stream KsProperty"); Err(ERROR_SET_NOT_FOUND.into()) }
    fn KsMethod(&self, _p: *const KSIDENTIFIER, _pl: u32, _d: *mut c_void, _dl: u32, _r: *mut u32) -> Result<()> { Err(ERROR_SET_NOT_FOUND.into()) }
    fn KsEvent(&self, _p: *const KSIDENTIFIER, _pl: u32, _d: *mut c_void, _dl: u32, _r: *mut u32) -> Result<()> { Err(ERROR_SET_NOT_FOUND.into()) }
}
