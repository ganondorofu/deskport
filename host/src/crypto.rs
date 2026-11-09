//! 通信の暗号化(AES-256-GCM)。全パケットを暗号化し、改ざんも検出する。
//!
//! パケットの形: [ナンス 12バイト][暗号文][認証タグ 16バイト]
//!   ナンス = [方向 1バイト (0=端末→ホスト, 1=ホスト→端末)][接続ID 7バイト (送る側が接続ごとにランダムに決める)][カウンタ u32]
//!   同じ鍵で同じナンスを二度使うと GCM は破れるので、接続IDをランダムにして、カウンタを必ず増やす。
//!   カウンタが最大に達したら、接続IDを引き直して 0 から数え直す(周回して同じナンスになることはない)。
//!   受け取る側は、方向のバイトを確認する(こちらが送ったパケットを折り返されても受け付けない)。
//! 鍵 = SHA-256(合言葉の文字列)。ペアリングで配る合言葉は 256 ビットのランダム値なので、鍵も 256 ビットになる。
use aes_gcm::aead::{Aead, KeyInit};
use aes_gcm::{Aes256Gcm, Nonce};
use sha2::{Digest, Sha256};

pub const OVERHEAD: usize = 12 + 16;
pub const DIR_TO_HOST: u8 = 0;
pub const DIR_TO_CLIENT: u8 = 1;

pub fn key_bytes(secret: &str) -> [u8; 32] {
    Sha256::digest(secret.as_bytes()).into()
}

pub fn key_from_secret(secret: &str) -> Aes256Gcm {
    Aes256Gcm::new_from_slice(&key_bytes(secret)).unwrap()
}

/// 暗号化して送る側。1つの送り手(接続)ごとに1つ作る。
pub struct Sealer {
    cipher: Aes256Gcm,
    dir: u8,
    conn: [u8; 7],
    counter: u32,
}

impl Sealer {
    pub fn new(cipher: Aes256Gcm, dir: u8) -> Self {
        let mut conn = [0u8; 7];
        getrandom::getrandom(&mut conn).expect("乱数を取得できません");
        Sealer { cipher, dir, conn, counter: 0 }
    }

    /// 同じ鍵の別の送り手(別スレッド用)を作るための複製。
    pub fn cipher_clone(&self) -> Aes256Gcm { self.cipher.clone() }

    pub fn seal(&mut self, plain: &[u8]) -> Vec<u8> {
        let mut nonce = [0u8; 12];
        nonce[0] = self.dir;
        nonce[1..8].copy_from_slice(&self.conn);
        nonce[8..12].copy_from_slice(&self.counter.to_le_bytes());
        // カウンタが一周する前に、接続IDを引き直す(同じ鍵・ID・カウンタの組み合わせを二度と作らない)
        if self.counter == u32::MAX {
            getrandom::getrandom(&mut self.conn).expect("乱数を取得できません");
            self.counter = 0;
        } else {
            self.counter += 1;
        }
        let ct = self.cipher.encrypt(Nonce::from_slice(&nonce), plain).expect("暗号化に失敗");
        let mut out = Vec::with_capacity(12 + ct.len());
        out.extend_from_slice(&nonce);
        out.extend_from_slice(&ct);
        out
    }
}

/// 受け取ったパケットを復号する。鍵が違う、改ざんされた、方向が違う、のどれかなら None。
pub fn open(cipher: &Aes256Gcm, expect_dir: u8, pkt: &[u8]) -> Option<Vec<u8>> {
    if pkt.len() < OVERHEAD || pkt[0] != expect_dir {
        return None;
    }
    cipher.decrypt(Nonce::from_slice(&pkt[..12]), &pkt[12..]).ok()
}
