use std::io::Cursor;
use std::net::IpAddr;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use anyhow::{anyhow, Result};
use reqwest::header::{CONTENT_LENGTH, CONTENT_TYPE};
use reqwest::{Client, StatusCode, Url};
use serde::Deserialize;

const MAX_AUDIO_BYTES: usize = 25_000_000;
const MAX_RESPONSE_BYTES: usize = 1_048_576;
const REQUEST_TIMEOUT: Duration = Duration::from_secs(180);
const CONNECT_TIMEOUT: Duration = Duration::from_secs(10);
static NEXT_BOUNDARY: AtomicU64 = AtomicU64::new(0);

#[derive(Deserialize)]
struct TranscriptionResponse {
    text: String,
}

/// Transcribe WAV audio through an OpenAI-compatible audio transcription endpoint.
///
/// `base_url` may be a `/v1` API base or the full `/audio/transcriptions` URL.
/// An empty `api_key` is allowed for a local server that does not need auth.
/// Remote endpoints require HTTPS because this request may contain private audio
/// and an API key. The caller chooses this provider explicitly; no fallback runs.
pub async fn transcribe_wav(
    base_url: &str,
    model: &str,
    api_key: &str,
    wav: Vec<u8>,
    language: Option<&str>,
) -> Result<String> {
    let url = transcription_url(base_url)?;
    let model = validate_field("model", model, 256)?;
    let language = language
        .map(|value| validate_field("language", value, 64))
        .transpose()?;
    let api_key = api_key.trim();
    if api_key.chars().any(char::is_control) {
        return Err(anyhow!("API key contains invalid characters"));
    }
    validate_wav(&wav)?;

    let boundary = new_boundary(&wav);
    let body = multipart_body(&boundary, model, language, &wav);
    let client = Client::builder()
        .connect_timeout(CONNECT_TIMEOUT)
        .timeout(REQUEST_TIMEOUT)
        .redirect(reqwest::redirect::Policy::none())
        .build()
        .map_err(|_| anyhow!("Could not create the speech HTTP client"))?;

    let mut request = client
        .post(url)
        .header(
            CONTENT_TYPE,
            format!("multipart/form-data; boundary={boundary}"),
        )
        .body(body);
    if !api_key.is_empty() {
        request = request.bearer_auth(api_key);
    }

    let mut response = request.send().await.map_err(request_error)?;
    if !response.status().is_success() {
        return Err(status_error(response.status()));
    }

    if response
        .headers()
        .get(CONTENT_LENGTH)
        .and_then(|value| value.to_str().ok())
        .and_then(|value| value.parse::<usize>().ok())
        .is_some_and(|length| length > MAX_RESPONSE_BYTES)
    {
        return Err(anyhow!("Speech server response is too large"));
    }

    let mut response_bytes = Vec::new();
    while let Some(chunk) = response
        .chunk()
        .await
        .map_err(|_| anyhow!("Could not read speech server response"))?
    {
        if chunk.len() > MAX_RESPONSE_BYTES.saturating_sub(response_bytes.len()) {
            return Err(anyhow!("Speech server response is too large"));
        }
        response_bytes.extend_from_slice(&chunk);
    }

    let parsed: TranscriptionResponse = serde_json::from_slice(&response_bytes)
        .map_err(|_| anyhow!("Speech server returned an invalid transcription response"))?;
    let text = parsed.text.trim();
    if text.is_empty() {
        return Err(anyhow!("Speech server returned an empty transcript"));
    }
    Ok(text.to_owned())
}

pub(crate) fn transcription_url(base_url: &str) -> Result<Url> {
    let mut url = Url::parse(base_url.trim()).map_err(|_| anyhow!("Invalid speech server URL"))?;
    if url.host().is_none() || !matches!(url.scheme(), "http" | "https") {
        return Err(anyhow!("Speech server URL must use HTTP or HTTPS"));
    }
    if !url.username().is_empty()
        || url.password().is_some()
        || url.query().is_some()
        || url.fragment().is_some()
    {
        return Err(anyhow!(
            "Speech server URL cannot contain credentials, query parameters, or a fragment"
        ));
    }
    if url.scheme() == "http" && !is_loopback(&url) {
        return Err(anyhow!("Remote speech server URLs must use HTTPS"));
    }

    let path = url.path().trim_end_matches('/');
    let path = if path.ends_with("/audio/transcriptions") {
        path.to_owned()
    } else if path.ends_with("/v1") {
        format!("{path}/audio/transcriptions")
    } else if path.is_empty() {
        "/v1/audio/transcriptions".to_owned()
    } else {
        return Err(anyhow!(
            "Speech server URL must end in /v1 or /audio/transcriptions"
        ));
    };
    url.set_path(&path);
    Ok(url)
}

fn is_loopback(url: &Url) -> bool {
    let Some(host) = url.host_str() else {
        return false;
    };
    host == "localhost"
        || host
            .trim_start_matches('[')
            .trim_end_matches(']')
            .parse::<IpAddr>()
            .is_ok_and(|address| address.is_loopback())
}

fn validate_field<'a>(name: &str, value: &'a str, max_len: usize) -> Result<&'a str> {
    let value = value.trim();
    if value.is_empty() || value.len() > max_len || value.chars().any(char::is_control) {
        return Err(anyhow!("Invalid {name} for speech transcription"));
    }
    Ok(value)
}

fn validate_wav(wav: &[u8]) -> Result<()> {
    if wav.len() > MAX_AUDIO_BYTES {
        return Err(anyhow!("Recording is too large for this speech endpoint"));
    }
    let reader = hound::WavReader::new(Cursor::new(wav))
        .map_err(|_| anyhow!("Recording is not a valid WAV file"))?;
    if reader.duration() == 0 {
        return Err(anyhow!("Recording contains no audio"));
    }
    Ok(())
}

fn new_boundary(wav: &[u8]) -> String {
    let sequence = NEXT_BOUNDARY.fetch_add(1, Ordering::Relaxed);
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_nanos();
    let mut suffix = 0u64;
    loop {
        let boundary = format!("murmur-{nanos:x}-{sequence:x}-{suffix:x}");
        let marker = format!("\r\n--{boundary}");
        if !wav
            .windows(marker.len())
            .any(|window| window == marker.as_bytes())
        {
            return boundary;
        }
        suffix += 1;
    }
}

fn multipart_body(boundary: &str, model: &str, language: Option<&str>, wav: &[u8]) -> Vec<u8> {
    let mut body = Vec::with_capacity(wav.len() + 512);
    append_field(&mut body, boundary, "model", model);
    if let Some(language) = language {
        append_field(&mut body, boundary, "language", language);
    }
    append_field(&mut body, boundary, "response_format", "json");
    body.extend_from_slice(
        format!(
            "--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"recording.wav\"\r\nContent-Type: audio/wav\r\n\r\n"
        )
        .as_bytes(),
    );
    body.extend_from_slice(wav);
    body.extend_from_slice(format!("\r\n--{boundary}--\r\n").as_bytes());
    body
}

fn append_field(body: &mut Vec<u8>, boundary: &str, name: &str, value: &str) {
    body.extend_from_slice(
        format!(
            "--{boundary}\r\nContent-Disposition: form-data; name=\"{name}\"\r\n\r\n{value}\r\n"
        )
        .as_bytes(),
    );
}

fn request_error(error: reqwest::Error) -> anyhow::Error {
    if error.is_timeout() {
        anyhow!("Speech server request timed out")
    } else if error.is_connect() {
        anyhow!("Could not connect to speech server")
    } else {
        anyhow!("Speech server request failed")
    }
}

fn status_error(status: StatusCode) -> anyhow::Error {
    match status.as_u16() {
        400 => anyhow!("Speech server rejected the request (HTTP 400); check the model and audio"),
        401 | 403 => anyhow!("Speech server authentication failed (HTTP {status})"),
        404 => anyhow!("Speech endpoint or model was not found (HTTP 404)"),
        413 => anyhow!("Recording exceeds the speech server upload limit (HTTP 413)"),
        429 => anyhow!("Speech server rate limit or quota reached (HTTP 429)"),
        500..=599 => anyhow!("Speech server is unavailable (HTTP {status})"),
        _ => anyhow!("Speech server rejected the request (HTTP {status})"),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    use tokio::net::TcpListener;

    fn sample_wav() -> Vec<u8> {
        let mut output = Cursor::new(Vec::new());
        {
            let spec = hound::WavSpec {
                channels: 1,
                sample_rate: 16_000,
                bits_per_sample: 16,
                sample_format: hound::SampleFormat::Int,
            };
            let mut writer = hound::WavWriter::new(&mut output, spec).unwrap();
            writer.write_sample(10i16).unwrap();
            writer.finalize().unwrap();
        }
        output.into_inner()
    }

    #[test]
    fn joins_supported_endpoint_forms() {
        assert_eq!(
            transcription_url("https://api.groq.com/openai/v1/")
                .unwrap()
                .as_str(),
            "https://api.groq.com/openai/v1/audio/transcriptions"
        );
        assert_eq!(
            transcription_url("https://api.openai.com/v1/audio/transcriptions")
                .unwrap()
                .as_str(),
            "https://api.openai.com/v1/audio/transcriptions"
        );
        assert_eq!(
            transcription_url("http://127.0.0.1:8000/v1")
                .unwrap()
                .as_str(),
            "http://127.0.0.1:8000/v1/audio/transcriptions"
        );
    }

    #[test]
    fn rejects_unsafe_or_ambiguous_urls() {
        assert!(transcription_url("http://api.example.com/v1").is_err());
        assert!(transcription_url("https://example.com/v1?token=secret").is_err());
        assert!(transcription_url("https://user:pass@example.com/v1").is_err());
        assert!(transcription_url("https://example.com/unknown").is_err());
    }

    #[test]
    fn validates_audio_and_multipart_fields() {
        assert!(validate_wav(&sample_wav()).is_ok());
        assert!(validate_wav(b"not a wav").is_err());
        assert!(validate_field("model", "bad\r\nHeader: injected", 256).is_err());
        let wav = sample_wav();
        let body = multipart_body("test-boundary", "whisper-1", Some("en"), &wav);
        assert!(body.windows(wav.len()).any(|window| window == wav));
        assert!(body
            .windows(b"name=\"model\"".len())
            .any(|window| window == b"name=\"model\""));
        assert!(body
            .windows(b"name=\"language\"".len())
            .any(|window| window == b"name=\"language\""));
        assert!(body.ends_with(b"\r\n--test-boundary--\r\n"));
    }

    async fn serve_once(
        status: &'static str,
        payload: Vec<u8>,
    ) -> (String, tokio::task::JoinHandle<Vec<u8>>) {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let url = format!("http://{}/v1", listener.local_addr().unwrap());
        let task = tokio::spawn(async move {
            let (mut stream, _) = listener.accept().await.unwrap();
            let mut request = Vec::new();
            let mut buffer = [0u8; 4096];
            loop {
                let count = stream.read(&mut buffer).await.unwrap();
                if count == 0 {
                    break;
                }
                request.extend_from_slice(&buffer[..count]);
                if let Some(end) = request.windows(4).position(|bytes| bytes == b"\r\n\r\n") {
                    let head = String::from_utf8_lossy(&request[..end]);
                    let content_length = head
                        .lines()
                        .find_map(|line| {
                            line.strip_prefix("content-length: ")
                                .or_else(|| line.strip_prefix("Content-Length: "))
                        })
                        .unwrap()
                        .parse::<usize>()
                        .unwrap();
                    if request.len() >= end + 4 + content_length {
                        break;
                    }
                }
            }
            let response = format!(
                "HTTP/1.1 {status}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
                payload.len()
            );
            stream.write_all(response.as_bytes()).await.unwrap();
            let _ = stream.write_all(&payload).await;
            request
        });
        (url, task)
    }

    #[tokio::test]
    async fn uploads_wav_and_reads_transcript() {
        let (url, server) = serve_once("200 OK", br#"{"text":"  hello world  "}"#.to_vec()).await;
        let transcript = transcribe_wav(&url, "whisper-1", "test-key", sample_wav(), Some("en"))
            .await
            .unwrap();
        assert_eq!(transcript, "hello world");
        let request = server.await.unwrap();
        let header_end = request
            .windows(4)
            .position(|bytes| bytes == b"\r\n\r\n")
            .unwrap();
        let headers = String::from_utf8_lossy(&request[..header_end]);
        assert!(headers.starts_with("POST /v1/audio/transcriptions HTTP/1.1"));
        assert!(headers
            .to_ascii_lowercase()
            .contains("authorization: bearer test-key"));
        assert!(headers
            .to_ascii_lowercase()
            .contains("content-type: multipart/form-data; boundary="));
        let body = &request[header_end + 4..];
        assert!(body
            .windows(b"name=\"model\"".len())
            .any(|window| window == b"name=\"model\""));
        assert!(body
            .windows(b"name=\"language\"".len())
            .any(|window| window == b"name=\"language\""));
        assert!(body.windows(b"RIFF".len()).any(|window| window == b"RIFF"));
    }

    #[tokio::test]
    async fn provider_errors_do_not_echo_response_or_key() {
        let (url, server) = serve_once(
            "401 Unauthorized",
            br#"{"error":"private recording secret"}"#.to_vec(),
        )
        .await;
        let error = transcribe_wav(&url, "whisper-1", "test-key", sample_wav(), None)
            .await
            .unwrap_err()
            .to_string();
        server.await.unwrap();
        assert!(error.contains("authentication failed"));
        assert!(!error.contains("private recording secret"));
        assert!(!error.contains("test-key"));
    }

    #[tokio::test]
    async fn local_server_can_run_without_a_key() {
        let (url, server) = serve_once("200 OK", br#"{"text":"local result"}"#.to_vec()).await;
        let transcript = transcribe_wav(&url, "local-whisper", "", sample_wav(), None)
            .await
            .unwrap();
        assert_eq!(transcript, "local result");
        let request = server.await.unwrap();
        let headers_end = request
            .windows(4)
            .position(|bytes| bytes == b"\r\n\r\n")
            .unwrap();
        let headers = String::from_utf8_lossy(&request[..headers_end]);
        assert!(!headers.to_ascii_lowercase().contains("authorization:"));
    }

    #[tokio::test]
    async fn refuses_oversized_responses() {
        let (url, server) = serve_once("200 OK", vec![b'x'; MAX_RESPONSE_BYTES + 1]).await;
        let error = transcribe_wav(&url, "whisper-1", "", sample_wav(), None)
            .await
            .unwrap_err()
            .to_string();
        server.await.unwrap();
        assert!(error.contains("too large"));
    }
}
