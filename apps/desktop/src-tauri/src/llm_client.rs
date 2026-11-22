use crate::settings::PostProcessProvider;
use log::debug;
use reqwest::header::{HeaderMap, HeaderValue, AUTHORIZATION, CONTENT_TYPE, USER_AGENT};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::collections::{HashMap, HashSet};
use std::sync::{Mutex, OnceLock};
use std::time::{Duration, Instant};

#[derive(Debug, Serialize)]
struct ChatMessage {
    role: String,
    content: String,
}

#[derive(Debug, Serialize)]
struct JsonSchema {
    name: String,
    strict: bool,
    schema: Value,
}

#[derive(Debug, Serialize)]
struct ResponseFormat {
    #[serde(rename = "type")]
    format_type: String,
    json_schema: JsonSchema,
}

#[derive(Debug, Serialize, Clone, PartialEq)]
struct ReasoningConfig {
    enabled: bool,
}

/// Request fields used to ask an endpoint to skip reasoning/thinking.
/// Providers disagree on the field name and accepted values, so at most one of
/// these is set per request (see `reasoning_disable_params`).
#[derive(Debug, Serialize, Clone, Default, PartialEq)]
struct ReasoningParams {
    #[serde(skip_serializing_if = "Option::is_none")]
    reasoning_effort: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    reasoning: Option<ReasoningConfig>,
    #[serde(skip_serializing_if = "Option::is_none")]
    thinking: Option<Value>,
    #[serde(skip_serializing_if = "Option::is_none")]
    enable_thinking: Option<bool>,
}

impl ReasoningParams {
    fn is_empty(&self) -> bool {
        self.reasoning_effort.is_none()
            && self.reasoning.is_none()
            && self.thinking.is_none()
            && self.enable_thinking.is_none()
    }
}

fn is_siliconflow(provider: &PostProcessProvider) -> bool {
    provider.id == "siliconflow"
        || reqwest::Url::parse(&provider.base_url)
            .ok()
            .is_some_and(|url| {
                url.host_str().is_some_and(|host| {
                    host.eq_ignore_ascii_case("api.siliconflow.cn")
                        || host.eq_ignore_ascii_case("api.siliconflow.com")
                })
            })
}

fn require_non_thinking(provider: &PostProcessProvider, disable_reasoning: bool) -> bool {
    disable_reasoning && (is_siliconflow(provider) || provider.id == "openrouter")
}

/// Pick the reasoning-disable request fields an endpoint understands.
/// Unknown endpoints get the common OpenAI-style field; if they reject it,
/// the request is retried without it (see `send_chat_completion_with_schema`).
fn reasoning_disable_params(provider: &PostProcessProvider) -> ReasoningParams {
    let is_deepseek = reqwest::Url::parse(&provider.base_url)
        .is_ok_and(|url| url.host_str() == Some("api.deepseek.com"));
    if is_siliconflow(provider) {
        // SiliconFlow uses a top-level boolean for supported reasoning models.
        // https://docs.siliconflow.cn/docs/api/chat-completions-post
        ReasoningParams {
            enable_thinking: Some(false),
            ..Default::default()
        }
    } else if is_deepseek {
        // DeepSeek rejects reasoning_effort "none" and uses its own field:
        // https://api-docs.deepseek.com/guides/thinking_mode
        ReasoningParams {
            thinking: Some(serde_json::json!({ "type": "disabled" })),
            ..Default::default()
        }
    } else if provider.id == "openrouter" {
        // Excluding reasoning only hides it from the response; disabling it
        // asks the endpoint to skip reasoning and its billable tokens.
        // https://openrouter.ai/docs/guides/best-practices/reasoning-tokens
        ReasoningParams {
            reasoning: Some(ReasoningConfig { enabled: false }),
            ..Default::default()
        }
    } else {
        ReasoningParams {
            reasoning_effort: Some("none".to_string()),
            ..Default::default()
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
enum OptionalField {
    Reasoning,
    Temperature,
    ResponseFormat,
    OutputCap,
}

type EndpointModel = (String, String, String);

fn rejected_fields() -> &'static Mutex<HashMap<EndpointModel, HashSet<OptionalField>>> {
    static REJECTED: OnceLock<Mutex<HashMap<EndpointModel, HashSet<OptionalField>>>> =
        OnceLock::new();
    REJECTED.get_or_init(|| Mutex::new(HashMap::new()))
}

fn endpoint_key(provider: &PostProcessProvider, model: &str) -> EndpointModel {
    (
        provider.id.clone(),
        provider.base_url.trim_end_matches('/').to_string(),
        model.to_string(),
    )
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum CleanupFailure {
    Authentication,
    Credit,
    RateLimit,
    Server,
    InvalidOutput,
    Timeout,
    Reasoning,
    Configuration,
}

impl std::fmt::Display for CleanupFailure {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(match self {
            Self::Authentication => "Cleanup authentication failed",
            Self::Credit => "Cleanup provider requires payment",
            Self::RateLimit => "Cleanup provider rate limit reached",
            Self::Server => "Cleanup provider unavailable",
            Self::InvalidOutput => "Cleanup returned invalid output or reached its output limit",
            Self::Timeout => "Cleanup provider timed out",
            Self::Reasoning => "Cleanup provider rejected disabling reasoning",
            Self::Configuration => "Cleanup request or configuration was rejected",
        })
    }
}

impl CleanupFailure {
    fn http(status: reqwest::StatusCode) -> Self {
        match status.as_u16() {
            401 | 403 => Self::Authentication,
            402 => Self::Credit,
            408 | 504 => Self::Timeout,
            429 => Self::RateLimit,
            500..=599 => Self::Server,
            _ => Self::Configuration,
        }
    }

    fn transport(error: reqwest::Error) -> Self {
        if error.is_timeout() {
            Self::Timeout
        } else {
            Self::Server
        }
    }
}

#[derive(Debug)]
pub struct CleanupCompletion {
    pub text: Option<String>,
    pub structured: bool,
}

const MAX_RESPONSE_BYTES: usize = 1_048_576;
const MAX_SCHEMA_ERROR_BYTES: usize = 65_536;

async fn bounded_response(
    mut response: reqwest::Response,
    limit: usize,
) -> Result<Vec<u8>, CleanupFailure> {
    if response
        .content_length()
        .is_some_and(|length| length > limit as u64)
    {
        return Err(CleanupFailure::InvalidOutput);
    }
    let mut bytes = Vec::new();
    while let Some(chunk) = response.chunk().await.map_err(CleanupFailure::transport)? {
        if chunk.len() > limit - bytes.len() {
            return Err(CleanupFailure::InvalidOutput);
        }
        bytes.extend_from_slice(&chunk);
    }
    Ok(bytes)
}

fn schema_rejection(
    body: &[u8],
    request: &ChatCompletionRequest,
    cap_already_swapped: bool,
) -> Option<OptionalField> {
    let body = String::from_utf8_lossy(body).to_ascii_lowercase();
    if ![
        "unsupported",
        "not supported",
        "unknown",
        "unrecognized",
        "not allowed",
        "does not support",
        "not permitted",
        "extra inputs",
        "unexpected",
        "must be",
        "only supports",
        "invalid parameter",
    ]
    .iter()
    .any(|marker| body.contains(marker))
    {
        return None;
    }

    let mentions = |field: &str| {
        body.split(|c: char| !c.is_ascii_alphanumeric() && c != '_')
            .any(|word| word == field)
    };
    if !request.reasoning.is_empty()
        && [
            "reasoning",
            "reasoning_effort",
            "thinking",
            "enable_thinking",
        ]
        .iter()
        .any(|field| mentions(field))
    {
        Some(OptionalField::Reasoning)
    } else if request.temperature.is_some() && mentions("temperature") {
        Some(OptionalField::Temperature)
    } else if request.response_format.is_some()
        && (mentions("response_format") || mentions("json_schema"))
    {
        Some(OptionalField::ResponseFormat)
    } else if !cap_already_swapped
        && mentions(if request.output_cap.max_tokens.is_some() {
            "max_tokens"
        } else {
            "max_completion_tokens"
        })
    {
        Some(OptionalField::OutputCap)
    } else {
        None
    }
}

#[derive(Debug, Serialize)]
struct ChatCompletionRequest {
    model: String,
    messages: Vec<ChatMessage>,
    stream: bool,
    /// Cleanup should return the same text for the same dictation, and the
    /// model benchmark was run this way. Dropped when an endpoint rejects it.
    #[serde(skip_serializing_if = "Option::is_none")]
    temperature: Option<f32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    response_format: Option<ResponseFormat>,
    #[serde(flatten)]
    reasoning: ReasoningParams,
    #[serde(flatten)]
    output_cap: OutputCap,
}

/// Upper bound on generated tokens. A provider that ignores the reasoning-off
/// flag once ran single cleanups to its 262k output limit, so every request
/// carries a cap. OpenAI's newer models reject `max_tokens` and want
/// `max_completion_tokens`; everyone else takes `max_tokens`.
#[derive(Debug, Serialize, Clone, Default, PartialEq)]
struct OutputCap {
    #[serde(skip_serializing_if = "Option::is_none")]
    max_tokens: Option<u32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    max_completion_tokens: Option<u32>,
}

impl OutputCap {
    fn for_provider(provider: &PostProcessProvider, content_chars: usize) -> Self {
        let cap = Some(cleanup_output_cap(content_chars));
        if provider.id == "openai" {
            Self {
                max_completion_tokens: cap,
                ..Default::default()
            }
        } else {
            Self {
                max_tokens: cap,
                ..Default::default()
            }
        }
    }

    fn swap_fields(&mut self) {
        std::mem::swap(&mut self.max_tokens, &mut self.max_completion_tokens);
    }
}

/// Cleanup returns about as much text as it was given. At roughly four
/// characters a token, three times the input plus a floor leaves room for the
/// JSON wrapper and a little leaked reasoning. Hitting the cap ends in
/// `finish_reason: length`, which is rejected and falls back to the raw
/// transcript.
fn cleanup_output_cap(content_chars: usize) -> u32 {
    let input_tokens = content_chars.div_ceil(4);
    input_tokens
        .saturating_mul(3)
        .saturating_add(512)
        .min(32_768) as u32
}

#[derive(Debug, Deserialize)]
struct ChatCompletionResponse {
    choices: Vec<ChatChoice>,
}

#[derive(Debug, Deserialize)]
struct ChatChoice {
    message: ChatMessageResponse,
    finish_reason: Option<String>,
}

#[derive(Debug, Deserialize)]
struct ChatMessageResponse {
    content: Option<String>,
}

fn completion_content(
    completion: ChatCompletionResponse,
) -> Result<Option<String>, CleanupFailure> {
    let Some(choice) = completion.choices.into_iter().next() else {
        return Ok(None);
    };

    match choice.finish_reason.as_deref() {
        Some("length" | "content_filter") => Err(CleanupFailure::InvalidOutput),
        _ => Ok(choice.message.content),
    }
}

/// Build headers for API requests based on provider type
fn build_headers(provider: &PostProcessProvider, api_key: &str) -> Result<HeaderMap, String> {
    let mut headers = HeaderMap::new();

    // Common headers
    headers.insert(CONTENT_TYPE, HeaderValue::from_static("application/json"));
    headers.insert(USER_AGENT, HeaderValue::from_static("Murmur/0.1.0"));
    headers.insert("X-Title", HeaderValue::from_static("Murmur"));

    // Provider-specific auth headers
    if !api_key.is_empty() {
        if provider.id == "anthropic" {
            headers.insert(
                "x-api-key",
                HeaderValue::from_str(api_key)
                    .map_err(|e| format!("Invalid API key header value: {}", e))?,
            );
            headers.insert("anthropic-version", HeaderValue::from_static("2023-06-01"));
        } else {
            headers.insert(
                AUTHORIZATION,
                HeaderValue::from_str(&format!("Bearer {}", api_key))
                    .map_err(|e| format!("Invalid authorization header value: {}", e))?,
            );
        }
    }

    Ok(headers)
}

pub(crate) fn validate_provider_url(base_url: &str) -> Result<(), String> {
    parse_provider_url(base_url).map(|_| ())
}

pub(crate) fn parse_provider_url(base_url: &str) -> Result<reqwest::Url, String> {
    let url = reqwest::Url::parse(base_url.trim())
        .map_err(|_| "Invalid cleanup provider URL".to_string())?;
    let is_loopback = url.host_str().is_some_and(|host| {
        host == "localhost"
            || host
                .trim_start_matches('[')
                .trim_end_matches(']')
                .parse::<std::net::IpAddr>()
                .is_ok_and(|address| address.is_loopback())
    });
    if !matches!(url.scheme(), "http" | "https")
        || (url.scheme() == "http" && !is_loopback)
        || !url.username().is_empty()
        || url.password().is_some()
        || url.query().is_some()
        || url.fragment().is_some()
    {
        return Err(
            "Cleanup provider requires HTTPS or a loopback HTTP URL without embedded credentials"
                .to_string(),
        );
    }

    Ok(url)
}

/// The shared client and this provider's headers. The headers go on each
/// request so the connection pool can be shared across providers and keys.
fn client_and_headers(
    provider: &PostProcessProvider,
    api_key: &str,
) -> Result<(reqwest::Client, HeaderMap), String> {
    validate_provider_url(&provider.base_url)?;
    let headers = build_headers(provider, api_key)?;
    Ok((crate::http_client::client()?, headers))
}

/// The raw text is the fallback, so a stalled model should not hold it for long.
fn cleanup_timeout(content_chars: usize) -> Duration {
    Duration::from_millis(20_000 + 20 * content_chars as u64).min(Duration::from_secs(60))
}

/// Format a bounded error source chain.
///
fn sanitized_url(url: &reqwest::Url) -> String {
    let mut url = url.clone();

    // Custom endpoints should not contain credentials or query-string tokens,
    // but omit them from diagnostics in case one does.
    let _ = url.set_username("");
    let _ = url.set_password(None);
    url.set_query(None);
    url.set_fragment(None);

    url.to_string()
}

fn sanitized_url_for_log(url: &str) -> String {
    reqwest::Url::parse(url)
        .map(|url| sanitized_url(&url))
        // Do not echo an invalid URL: the parse failure might have been caused
        // by sensitive data entered in the custom endpoint field.
        .unwrap_or_else(|_| "<invalid URL>".to_string())
}

#[cfg(test)]
pub async fn send_chat_completion(
    provider: &PostProcessProvider,
    api_key: String,
    model: &str,
    prompt: String,
    disable_reasoning: bool,
) -> Result<Option<String>, String> {
    send_chat_completion_with_schema(
        provider,
        api_key,
        model,
        prompt,
        None,
        None,
        disable_reasoning,
    )
    .await
    .map(|completion| completion.text)
    .map_err(|error| error.to_string())
}

pub async fn send_chat_completion_with_schema(
    provider: &PostProcessProvider,
    api_key: String,
    model: &str,
    user_content: String,
    system_prompt: Option<String>,
    json_schema: Option<Value>,
    disable_reasoning: bool,
) -> Result<CleanupCompletion, CleanupFailure> {
    let base_url = provider.base_url.trim_end_matches('/');
    let url = format!("{}/chat/completions", base_url);

    debug!(
        "Sending chat completion request to: {}",
        sanitized_url_for_log(&url)
    );

    let (client, headers) =
        client_and_headers(provider, &api_key).map_err(|_| CleanupFailure::Configuration)?;
    let content_chars = user_content.chars().count();
    let timeout = cleanup_timeout(content_chars);

    // Build messages vector
    let mut messages = Vec::new();

    // Add system prompt if provided
    if let Some(system) = system_prompt {
        messages.push(ChatMessage {
            role: "system".to_string(),
            content: system,
        });
    }

    // Add user message
    messages.push(ChatMessage {
        role: "user".to_string(),
        content: user_content,
    });

    // Build response_format if schema is provided
    let response_format = json_schema.map(|schema| ResponseFormat {
        format_type: "json_schema".to_string(),
        json_schema: JsonSchema {
            name: "transcription_output".to_string(),
            strict: true,
            schema,
        },
    });

    let key = endpoint_key(provider, model);
    let strict_disable = require_non_thinking(provider, disable_reasoning);
    let mut rejected = rejected_fields()
        .lock()
        .unwrap()
        .get(&key)
        .cloned()
        .unwrap_or_default();
    let reasoning =
        if disable_reasoning && (strict_disable || !rejected.contains(&OptionalField::Reasoning)) {
            reasoning_disable_params(provider)
        } else {
            ReasoningParams::default()
        };
    let mut output_cap = OutputCap::for_provider(provider, content_chars);
    if rejected.contains(&OptionalField::OutputCap) {
        output_cap.swap_fields();
    }
    let mut request = ChatCompletionRequest {
        model: model.to_string(),
        messages,
        stream: false,
        temperature: (!rejected.contains(&OptionalField::Temperature)).then_some(0.0),
        response_format: response_format
            .filter(|_| !rejected.contains(&OptionalField::ResponseFormat)),
        reasoning,
        output_cap,
    };
    let deadline = Instant::now() + timeout;

    loop {
        let remaining = deadline.saturating_duration_since(Instant::now());
        if remaining.is_zero() {
            return Err(CleanupFailure::Timeout);
        }
        let response = client
            .post(&url)
            .headers(headers.clone())
            .timeout(remaining)
            .json(&request)
            .send()
            .await
            .map_err(CleanupFailure::transport)?;
        let status = response.status();

        if status.is_success() {
            let body = bounded_response(response, MAX_RESPONSE_BYTES).await?;
            let completion =
                serde_json::from_slice(&body).map_err(|_| CleanupFailure::InvalidOutput)?;
            let text = completion_content(completion)?;
            if !rejected.is_empty() {
                rejected_fields()
                    .lock()
                    .unwrap()
                    .entry(key)
                    .or_default()
                    .extend(rejected);
            }
            return Ok(CleanupCompletion {
                text,
                structured: request.response_format.is_some(),
            });
        }

        debug!("Cleanup request returned HTTP {status}");
        if matches!(status.as_u16(), 400 | 422) {
            // Provider text is read only to recognise a supported schema rejection.
            let body = match bounded_response(response, MAX_SCHEMA_ERROR_BYTES).await {
                Ok(body) => body,
                Err(CleanupFailure::Timeout) => return Err(CleanupFailure::Timeout),
                Err(_) => return Err(CleanupFailure::http(status)),
            };
            if let Some(field) = schema_rejection(
                &body,
                &request,
                rejected.contains(&OptionalField::OutputCap),
            ) {
                match field {
                    OptionalField::Reasoning if strict_disable => {
                        return Err(CleanupFailure::Reasoning)
                    }
                    OptionalField::Reasoning => request.reasoning = ReasoningParams::default(),
                    OptionalField::Temperature => request.temperature = None,
                    OptionalField::ResponseFormat => request.response_format = None,
                    OptionalField::OutputCap => request.output_cap.swap_fields(),
                }
                rejected.insert(field);
                continue;
            }
        }
        return Err(CleanupFailure::http(status));
    }
}

/// Fetch available models from an OpenAI-compatible API
/// Returns a list of model IDs
pub async fn fetch_models(
    provider: &PostProcessProvider,
    api_key: String,
) -> Result<Vec<String>, String> {
    let base_url = provider.base_url.trim_end_matches('/');
    let url = format!("{}/models", base_url);

    debug!("Fetching models from: {}", sanitized_url_for_log(&url));

    let (client, headers) = client_and_headers(provider, &api_key)?;

    let response = client
        .get(&url)
        .headers(headers)
        .timeout(Duration::from_secs(30))
        .send()
        .await
        .map_err(|error| CleanupFailure::transport(error).to_string())?;

    let status = response.status();
    if !status.is_success() {
        return Err(format!("Model list request failed (HTTP {status})"));
    }

    let body = bounded_response(response, MAX_RESPONSE_BYTES)
        .await
        .map_err(|error| error.to_string())?;
    let parsed: Value = serde_json::from_slice(&body)
        .map_err(|_| "Model list response was not valid JSON".to_string())?;

    let mut models = Vec::new();

    // Handle OpenAI format: { data: [ { id: "..." }, ... ] }
    if let Some(data) = parsed.get("data").and_then(|d| d.as_array()) {
        for entry in data {
            if let Some(id) = entry.get("id").and_then(|i| i.as_str()) {
                models.push(id.to_string());
            } else if let Some(name) = entry.get("name").and_then(|n| n.as_str()) {
                models.push(name.to_string());
            }
        }
    }
    // Handle array format: [ "model1", "model2", ... ]
    else if let Some(array) = parsed.as_array() {
        for entry in array {
            if let Some(model) = entry.as_str() {
                models.push(model.to_string());
            }
        }
    }

    Ok(models)
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    fn provider(id: &str, base_url: &str) -> PostProcessProvider {
        PostProcessProvider {
            id: id.to_string(),
            label: id.to_string(),
            base_url: base_url.to_string(),
            allow_base_url_edit: true,
            models_endpoint: None,
            supports_structured_output: false,
        }
    }

    fn request_json(reasoning: ReasoningParams) -> Value {
        let request = ChatCompletionRequest {
            model: "test-model".to_string(),
            messages: vec![ChatMessage {
                role: "user".to_string(),
                content: "hi".to_string(),
            }],
            stream: false,
            temperature: Some(0.0),
            response_format: None,
            reasoning,
            output_cap: OutputCap::default(),
        };
        serde_json::to_value(&request).unwrap()
    }

    async fn serve_one_response(status: &str, body: &str) -> String {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        let response = format!(
            "HTTP/1.1 {status}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
            body.len()
        );

        tokio::spawn(async move {
            let (mut stream, _) = listener.accept().await.unwrap();
            let mut request = [0_u8; 2048];
            let _ = stream.read(&mut request).await.unwrap();
            stream.write_all(response.as_bytes()).await.unwrap();
        });

        format!("http://{address}")
    }

    #[test]
    fn log_url_sanitization_removes_credentials_and_tokens() {
        let url = "https://user:password@example.com/v1/models?api_key=secret#private";
        assert_eq!(sanitized_url_for_log(url), "https://example.com/v1/models");
    }

    #[test]
    fn invalid_log_urls_are_not_echoed() {
        assert_eq!(
            sanitized_url_for_log("not a URL containing secret"),
            "<invalid URL>"
        );
    }

    #[tokio::test]
    async fn complete_response_preserves_final_punctuation_at_eof() {
        let base_url = serve_one_response(
            "200 OK",
            r#"{"choices":[{"message":{"content":"Keep this."},"finish_reason":"stop"}]}"#,
        )
        .await;

        let result = send_chat_completion(
            &provider("custom", &base_url),
            String::new(),
            "test-model",
            "input".to_string(),
            false,
        )
        .await
        .unwrap();

        assert_eq!(result.as_deref(), Some("Keep this."));
    }

    const SHORT_SUCCESS: &str =
        r#"{"choices":[{"message":{"content":"ok"},"finish_reason":"stop"}]}"#;

    async fn capture_requests(
        replies: Vec<(&'static str, &'static str)>,
    ) -> (String, tokio::task::JoinHandle<Vec<Value>>) {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        let handle = tokio::spawn(async move {
            let mut requests = Vec::new();
            for (status, reply) in replies {
                let (mut stream, _) = listener.accept().await.unwrap();
                let mut received = Vec::new();
                let mut buffer = [0_u8; 4096];
                let body = loop {
                    let read = stream.read(&mut buffer).await.unwrap();
                    received.extend_from_slice(&buffer[..read]);
                    let text = String::from_utf8_lossy(&received).into_owned();
                    if let Some((head, body)) = text.split_once("\r\n\r\n") {
                        let length = head
                            .lines()
                            .find_map(|line| {
                                let (name, value) = line.split_once(':')?;
                                name.eq_ignore_ascii_case("content-length")
                                    .then(|| value.trim().parse::<usize>().ok())?
                            })
                            .unwrap_or(0);
                        if body.len() >= length {
                            break body.to_string();
                        }
                    }
                    assert!(read > 0, "connection closed before the body arrived");
                };
                let response = format!(
                    "HTTP/1.1 {status}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{reply}",
                    reply.len()
                );
                stream.write_all(response.as_bytes()).await.unwrap();
                requests.push(serde_json::from_str(&body).unwrap());
            }
            requests
        });
        (format!("http://{address}"), handle)
    }

    #[tokio::test]
    async fn cleanup_requests_cap_output_in_the_field_each_provider_reads() {
        let transcript = "word ".repeat(400);
        for (id, field, other) in [
            ("openrouter", "max_tokens", "max_completion_tokens"),
            ("openai", "max_completion_tokens", "max_tokens"),
        ] {
            let (base_url, server) = capture_requests(vec![("200 OK", SHORT_SUCCESS)]).await;
            send_chat_completion(
                &provider(id, &base_url),
                String::new(),
                "test-model",
                transcript.clone(),
                true,
            )
            .await
            .unwrap();
            let body = server.await.unwrap().pop().unwrap();
            let cap = body[field].as_u64().expect("output cap missing");
            assert!(
                (500..=2_048).contains(&cap),
                "{id}: cap {cap} is not sized to a 2000 character transcript"
            );
            assert!(body.get(other).is_none(), "{id}: sent both cap fields");
        }
    }

    #[tokio::test]
    async fn schema_retry_keeps_the_cap_and_remembers_only_the_rejected_field() {
        let (base_url, received) = capture_requests(vec![
            (
                "400 Bad Request",
                r#"{"error":{"message":"Unsupported parameter: temperature"}}"#,
            ),
            ("200 OK", SHORT_SUCCESS),
            ("200 OK", SHORT_SUCCESS),
        ])
        .await;
        let provider = provider("custom", &base_url);
        for _ in 0..2 {
            let result =
                send_chat_completion(&provider, String::new(), "model", "input".into(), true)
                    .await
                    .unwrap();
            assert_eq!(result.as_deref(), Some("ok"));
        }
        let requests = received.await.unwrap();
        assert_eq!(requests[0]["temperature"], 0.0);
        for request in &requests[1..] {
            assert!(request.get("temperature").is_none());
        }
        for request in requests {
            assert!(request["max_tokens"].as_u64().is_some());
            assert_eq!(request["reasoning_effort"], "none");
        }
    }

    #[tokio::test]
    async fn length_limited_response_is_rejected() {
        let base_url = serve_one_response(
            "200 OK",
            r#"{"choices":[{"message":{"content":"Keep thi"},"finish_reason":"length"}]}"#,
        )
        .await;

        let error = send_chat_completion(
            &provider("custom", &base_url),
            String::new(),
            "test-model",
            "input".to_string(),
            false,
        )
        .await
        .unwrap_err();

        assert!(error.contains("output limit"));
        assert!(!error.contains("Keep thi"));
    }

    #[test]
    fn requests_explicitly_disable_streaming() {
        let json = request_json(ReasoningParams::default());
        assert_eq!(json["stream"], false);
        assert_eq!(json["temperature"], 0.0);
    }

    #[test]
    fn default_reasoning_params_serialize_to_no_fields() {
        let json = request_json(ReasoningParams::default());
        assert!(json.get("reasoning_effort").is_none());
        assert!(json.get("reasoning").is_none());
        assert!(json.get("thinking").is_none());
        assert!(json.get("enable_thinking").is_none());
    }

    #[test]
    fn custom_provider_uses_top_level_reasoning_effort() {
        let params = reasoning_disable_params(&provider("custom", "http://localhost:11434/v1"));
        let json = request_json(params);
        assert_eq!(json["reasoning_effort"], "none");
        assert!(json.get("reasoning").is_none());
        assert!(json.get("thinking").is_none());
        assert!(json.get("enable_thinking").is_none());
    }

    #[test]
    fn openrouter_uses_nested_reasoning_object() {
        let params =
            reasoning_disable_params(&provider("openrouter", "https://openrouter.ai/api/v1"));
        let json = request_json(params);
        assert!(json.get("reasoning_effort").is_none());
        assert_eq!(json["reasoning"], serde_json::json!({ "enabled": false }));
        assert!(json.get("thinking").is_none());
        assert!(json.get("enable_thinking").is_none());
    }

    #[test]
    fn deepseek_base_url_uses_thinking_disabled() {
        let params = reasoning_disable_params(&provider("custom", "https://api.deepseek.com"));
        let json = request_json(params);
        assert!(json.get("reasoning_effort").is_none());
        assert!(json.get("reasoning").is_none());
        assert_eq!(json["thinking"]["type"], "disabled");
        assert!(json.get("enable_thinking").is_none());
    }

    #[test]
    fn siliconflow_provider_uses_enable_thinking_false() {
        let params =
            reasoning_disable_params(&provider("siliconflow", "https://api.siliconflow.cn/v1"));
        let json = request_json(params);
        assert_eq!(json["enable_thinking"], false);
        assert!(json.get("reasoning_effort").is_none());
        assert!(json.get("reasoning").is_none());
        assert!(json.get("thinking").is_none());
    }

    #[test]
    fn custom_siliconflow_url_uses_enable_thinking_false() {
        let params =
            reasoning_disable_params(&provider("custom", "https://api.siliconflow.com/v1"));
        let json = request_json(params);
        assert_eq!(json["enable_thinking"], false);
        assert!(json.get("reasoning_effort").is_none());
    }

    #[test]
    fn provider_non_thinking_policy_is_strict_for_siliconflow_and_openrouter() {
        assert!(require_non_thinking(
            &provider("siliconflow", "https://api.siliconflow.com/v1"),
            true
        ));
        assert!(require_non_thinking(
            &provider("custom", "https://api.siliconflow.cn/v1"),
            true
        ));
        assert!(require_non_thinking(
            &provider("openrouter", "https://openrouter.ai/api/v1"),
            true
        ));
        assert!(!require_non_thinking(
            &provider("openrouter", "https://openrouter.ai/api/v1"),
            false
        ));
    }

    #[test]
    fn siliconflow_lookalike_host_keeps_generic_request_fields() {
        let params = reasoning_disable_params(&provider(
            "custom",
            "https://api.siliconflow.cn.example.com/v1",
        ));
        let json = request_json(params);
        assert_eq!(json["reasoning_effort"], "none");
        assert!(json.get("enable_thinking").is_none());
    }

    #[test]
    fn reasoning_params_is_empty_tracks_all_fields() {
        assert!(ReasoningParams::default().is_empty());
        assert!(!ReasoningParams {
            reasoning_effort: Some("none".to_string()),
            ..Default::default()
        }
        .is_empty());
        assert!(!ReasoningParams {
            thinking: Some(serde_json::json!({ "type": "disabled" })),
            ..Default::default()
        }
        .is_empty());
        assert!(!ReasoningParams {
            enable_thinking: Some(false),
            ..Default::default()
        }
        .is_empty());
    }

    #[tokio::test]
    async fn cleanup_error_reports_status_without_provider_text() {
        let base_url = serve_one_response("402 Payment Required", "private response text").await;
        let result = send_chat_completion(
            &provider("custom", &base_url),
            String::new(),
            "model",
            "input".into(),
            true,
        )
        .await
        .unwrap_err();
        assert_eq!(result, CleanupFailure::Credit.to_string());
    }

    #[tokio::test]
    async fn oversized_response_is_rejected() {
        let base_url = serve_one_response("200 OK", &"x".repeat(MAX_RESPONSE_BYTES + 1)).await;
        let result = send_chat_completion(
            &provider("custom", &base_url),
            String::new(),
            "model",
            "input".into(),
            true,
        )
        .await
        .unwrap_err();
        assert_eq!(result, CleanupFailure::InvalidOutput.to_string());
    }
}
