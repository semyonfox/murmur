//! Transcribes an endpoint dictation in pieces while the user is still
//! speaking, so stopping only waits for the last few seconds instead of an
//! upload that grows with the whole take.
//!
//! The recorder's voice detector already drops silence, so a piece is cut when
//! the detector goes quiet after speech. No piece is ever only silence, and a
//! cut never lands inside a word unless the speaker goes 25 seconds without a
//! pause.

use crate::audio_toolkit::quietest_cut;
use crate::cloud_stt;
use anyhow::Result;
use log::{debug, warn};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use tauri::async_runtime::JoinHandle;
use tokio::sync::OnceCell;

const SAMPLE_RATE: usize = 16_000;
// most takes are a few seconds, so pieces can be short
const MIN_PIECE_SAMPLES: usize = 3 * SAMPLE_RATE;
const MAX_PIECE_SAMPLES: usize = 25 * SAMPLE_RATE;
// the detector holds 450 ms of hangover before it reports quiet, so this much
// more makes a pause of about 0.6 s
const PAUSE_SAMPLES: usize = SAMPLE_RATE * 15 / 100;
const SEARCH_SAMPLES: usize = 5 * SAMPLE_RATE;
const MIN_TAIL_SAMPLES: usize = SAMPLE_RATE / 5;

pub struct Endpoint {
    pub base_url: String,
    pub model: String,
    pub language: Option<String>,
    api_key: OnceCell<String>,
}

impl Endpoint {
    pub fn new(base_url: String, model: String, language: Option<String>) -> Self {
        Self {
            base_url,
            model,
            language,
            api_key: OnceCell::new(),
        }
    }
}

#[cfg(test)]
impl Endpoint {
    /// A test endpoint with its key already known, so nothing asks the keyring.
    fn local(base_url: String) -> Self {
        Self {
            base_url,
            model: "test-model".into(),
            language: Some("en".into()),
            api_key: OnceCell::new_with(Some(String::new())),
        }
    }
}

/// Decides where a take is cut. Kept apart from the uploads so it can be
/// tested on plain samples.
#[derive(Default)]
struct Cutter {
    buffer: Vec<f32>,
    quiet: usize,
}

impl Cutter {
    /// Audio the detector kept. Returns a piece only when the speaker never
    /// paused, cut at the quietest moment of its last five seconds.
    fn speech(&mut self, frame: &[f32]) -> Option<Vec<f32>> {
        self.quiet = 0;
        self.buffer.extend_from_slice(frame);
        if self.buffer.len() < MAX_PIECE_SAMPLES {
            return None;
        }
        let rest = self
            .buffer
            .split_off(quietest_cut(&self.buffer, SEARCH_SAMPLES));
        Some(std::mem::replace(&mut self.buffer, rest))
    }

    /// Audio the detector dropped as silence. Returns the piece that pause ends.
    fn silence(&mut self, samples: usize) -> Option<Vec<f32>> {
        if self.buffer.is_empty() {
            return None;
        }
        self.quiet += samples;
        if self.quiet < PAUSE_SAMPLES || self.buffer.len() < MIN_PIECE_SAMPLES {
            return None;
        }
        self.quiet = 0;
        Some(std::mem::take(&mut self.buffer))
    }
}

struct Session {
    endpoint: Arc<Endpoint>,
    cutter: Cutter,
    parts: Vec<JoinHandle<Result<String>>>,
}

/// The one live dictation. The recorder thread feeds it and the transcribe
/// action starts and finishes it, so it lives beside them as a single shared
/// value rather than being threaded through both.
pub struct LiveStt {
    active: AtomicBool,
    session: Mutex<Option<Session>>,
}

static LIVE: LiveStt = LiveStt {
    active: AtomicBool::new(false),
    session: Mutex::new(None),
};

pub fn live() -> &'static LiveStt {
    &LIVE
}

impl LiveStt {
    fn lock(&self) -> std::sync::MutexGuard<'_, Option<Session>> {
        self.session
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
    }

    /// Start cutting the recording that is about to begin.
    pub fn begin(&self, endpoint: Endpoint) {
        let previous = self.lock().replace(Session {
            endpoint: Arc::new(endpoint),
            cutter: Cutter::default(),
            parts: Vec::new(),
        });
        self.active.store(true, Ordering::Release);
        abort(previous);
    }

    /// Called on the recorder thread with each frame the detector kept. A
    /// single atomic load when no live dictation is running.
    pub fn feed(&self, frame: &[f32]) {
        if !self.active.load(Ordering::Relaxed) {
            return;
        }
        if let Some(session) = self.lock().as_mut() {
            if let Some(piece) = session.cutter.speech(frame) {
                session.send(piece);
            }
        }
    }

    /// Called on the recorder thread when the detector drops a frame as silence.
    pub fn silence(&self, samples: usize) {
        if !self.active.load(Ordering::Relaxed) {
            return;
        }
        if let Some(session) = self.lock().as_mut() {
            if let Some(piece) = session.cutter.silence(samples) {
                session.send(piece);
            }
        }
    }

    /// End the live dictation once capture has stopped. `None` means nothing
    /// was sent while recording, so the caller uploads the whole take as before.
    pub fn finish(&self) -> Option<LiveTake> {
        self.active.store(false, Ordering::Release);
        let mut session = self.lock().take()?;
        if session.parts.is_empty() {
            return None;
        }
        let tail = std::mem::take(&mut session.cutter.buffer);
        if tail.len() >= MIN_TAIL_SAMPLES {
            session.send(tail);
        }
        debug!(
            "Live endpoint transcription: {} pieces, the last sent at stop",
            session.parts.len()
        );
        Some(LiveTake {
            parts: session.parts,
        })
    }

    /// Drop the live dictation and stop its uploads.
    pub fn cancel(&self) {
        self.active.store(false, Ordering::Release);
        let session = self.lock().take();
        abort(session);
    }
}

impl Session {
    fn send(&mut self, samples: Vec<f32>) {
        let endpoint = Arc::clone(&self.endpoint);
        self.parts
            .push(tauri::async_runtime::spawn(transcribe_piece(
                endpoint, samples,
            )));
    }
}

fn abort(session: Option<Session>) {
    for part in session.into_iter().flat_map(|session| session.parts) {
        part.abort();
    }
}

async fn transcribe_piece(endpoint: Arc<Endpoint>, samples: Vec<f32>) -> Result<String> {
    let api_key = endpoint
        .api_key
        .get_or_init(|| cloud_stt::stored_api_key(&endpoint.base_url))
        .await;
    let wav = cloud_stt::encode_wav(&samples)?;
    cloud_stt::transcribe_wav_piece(
        &endpoint.base_url,
        &endpoint.model,
        api_key,
        wav,
        endpoint.language.as_deref(),
    )
    .await
}

/// The pieces of one finished take, in the order they were spoken.
pub struct LiveTake {
    parts: Vec<JoinHandle<Result<String>>>,
}

impl LiveTake {
    /// The joined transcript. `Ok(None)` asks the caller to send the whole
    /// recording instead, because a piece failed or nothing was recognised. An
    /// unreachable or stalled server is an error: the whole recording would
    /// only make the user wait a second time for the same failure.
    pub async fn text(mut self) -> Result<Option<String>> {
        let mut joined = String::new();
        while !self.parts.is_empty() {
            // an early return drops `self`, which stops the pieces still queued in it
            let piece = match self.parts.remove(0).await {
                Ok(Ok(piece)) => piece,
                Ok(Err(error)) if cloud_stt::is_unreachable(&error) => return Err(error),
                Ok(Err(error)) => {
                    warn!("A live piece failed ({error}); sending the whole take instead");
                    return Ok(None);
                }
                Err(error) => {
                    warn!("A live piece did not finish ({error}); sending the whole take instead");
                    return Ok(None);
                }
            };
            if !piece.trim().is_empty() {
                if !joined.is_empty() {
                    joined.push(' ');
                }
                joined.push_str(&piece);
            }
        }
        Ok((!joined.is_empty()).then_some(joined))
    }
}

impl Drop for LiveTake {
    fn drop(&mut self) {
        for part in &self.parts {
            part.abort();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn frames(seconds: f32, level: f32) -> Vec<f32> {
        vec![level; (seconds * SAMPLE_RATE as f32) as usize]
    }

    fn speak(cutter: &mut Cutter, seconds: f32) -> Vec<Vec<f32>> {
        frames(seconds, 0.3)
            .chunks(480)
            .filter_map(|frame| cutter.speech(frame))
            .collect()
    }

    fn pause(cutter: &mut Cutter, seconds: f32) -> Vec<Vec<f32>> {
        (0..(seconds * SAMPLE_RATE as f32) as usize / 480)
            .filter_map(|_| cutter.silence(480))
            .collect()
    }

    /// Answers each upload with its own size, so the reply shows which piece it was.
    async fn serve_upload_sizes(requests: usize) -> String {
        use tokio::io::{AsyncReadExt, AsyncWriteExt};

        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let base_url = format!("http://{}/v1", listener.local_addr().unwrap());
        tauri::async_runtime::spawn(async move {
            for _ in 0..requests {
                let (mut stream, _) = listener.accept().await.unwrap();
                tauri::async_runtime::spawn(async move {
                    let mut request = Vec::new();
                    let mut buffer = [0_u8; 16 * 1024];
                    let (body_start, length) = loop {
                        let read = stream.read(&mut buffer).await.unwrap();
                        request.extend_from_slice(&buffer[..read]);
                        if let Some(end) = request.windows(4).position(|w| w == b"\r\n\r\n") {
                            let headers = String::from_utf8_lossy(&request[..end]).to_lowercase();
                            let length: usize = headers
                                .lines()
                                .find_map(|line| line.strip_prefix("content-length:"))
                                .unwrap()
                                .trim()
                                .parse()
                                .unwrap();
                            break (end + 4, length);
                        }
                    };
                    while request.len() < body_start + length {
                        let read = stream.read(&mut buffer).await.unwrap();
                        request.extend_from_slice(&buffer[..read]);
                    }
                    let body = format!(r#"{{"text":"{length}"}}"#);
                    let response = format!(
                        "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
                        body.len()
                    );
                    stream.write_all(response.as_bytes()).await.unwrap();
                });
            }
        });
        base_url
    }

    // the one test that drives the shared live session, so it cannot race another
    #[test]
    fn pieces_go_out_while_recording_and_come_back_in_spoken_order() {
        tauri::async_runtime::block_on(async {
            let live = live();
            assert!(live.finish().is_none(), "nothing is live before begin");

            // a take with no pause long enough leaves the upload to the caller
            live.begin(Endpoint::local(serve_upload_sizes(0).await));
            frames(2.0, 0.3)
                .chunks(480)
                .for_each(|frame| live.feed(frame));
            assert!(live.finish().is_none());

            live.begin(Endpoint::local(serve_upload_sizes(2).await));
            frames(4.0, 0.3)
                .chunks(480)
                .for_each(|frame| live.feed(frame));
            (0..10).for_each(|_| live.silence(480));
            frames(2.0, 0.3)
                .chunks(480)
                .for_each(|frame| live.feed(frame));
            let text = live.finish().unwrap().text().await.unwrap().unwrap();

            let sizes: Vec<usize> = text.split(' ').map(|size| size.parse().unwrap()).collect();
            assert_eq!(sizes.len(), 2, "{text}");
            assert!(sizes[0] > 4 * SAMPLE_RATE * 2 && sizes[0] < 5 * SAMPLE_RATE * 2);
            assert!(sizes[1] > 2 * SAMPLE_RATE * 2 && sizes[1] < 3 * SAMPLE_RATE * 2);

            // frames after finish belong to no take
            live.feed(&frames(1.0, 0.3));
            assert!(live.finish().is_none());

            // a server that cannot be reached is an error, not a second full upload
            let closed = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
            let unreachable = format!("http://{}/v1", closed.local_addr().unwrap());
            drop(closed);
            live.begin(Endpoint::local(unreachable));
            frames(4.0, 0.3)
                .chunks(480)
                .for_each(|frame| live.feed(frame));
            (0..10).for_each(|_| live.silence(480));
            let error = live.finish().unwrap().text().await.unwrap_err();
            assert!(cloud_stt::is_unreachable(&error), "{error}");
        });
    }

    #[test]
    fn cuts_at_the_first_pause_after_three_seconds() {
        let mut cutter = Cutter::default();
        assert!(speak(&mut cutter, 2.0).is_empty());
        // a pause before the minimum is not a cut
        assert!(pause(&mut cutter, 1.0).is_empty());
        assert!(speak(&mut cutter, 2.0).is_empty());

        let pieces = pause(&mut cutter, 1.0);
        assert_eq!(pieces.len(), 1);
        assert_eq!(pieces[0].len(), 4 * SAMPLE_RATE);
        assert!(cutter.buffer.is_empty());
    }

    #[test]
    fn a_long_silence_sends_nothing_more() {
        let mut cutter = Cutter::default();
        speak(&mut cutter, 4.0);
        assert_eq!(pause(&mut cutter, 30.0).len(), 1);
        assert!(pause(&mut cutter, 30.0).is_empty());
    }

    #[test]
    fn a_short_gap_between_words_is_not_a_pause() {
        let mut cutter = Cutter::default();
        speak(&mut cutter, 4.0);
        assert!(pause(&mut cutter, 0.09).is_empty());
        assert!(speak(&mut cutter, 1.0).is_empty());
        assert_eq!(cutter.buffer.len(), 5 * SAMPLE_RATE);
    }

    #[test]
    fn speech_without_a_pause_is_cut_at_its_quietest_recent_moment() {
        let mut cutter = Cutter::default();
        assert!(speak(&mut cutter, 22.0).is_empty());
        frames(0.3, 0.0).chunks(480).for_each(|frame| {
            assert!(cutter.speech(frame).is_none());
        });
        let pieces = speak(&mut cutter, 3.0);

        assert_eq!(pieces.len(), 1);
        let seconds = pieces[0].len() as f32 / SAMPLE_RATE as f32;
        assert!((22.0..=22.3).contains(&seconds), "cut at {seconds} s");
        assert_eq!(
            pieces[0].len() + cutter.buffer.len(),
            25 * SAMPLE_RATE + 4_800
        );
    }
}
