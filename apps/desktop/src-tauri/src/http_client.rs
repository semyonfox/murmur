//! One HTTP client for speech and cleanup requests.
//!
//! A client built per request throws its connections away with it, so every
//! dictation paid for a TCP and TLS handshake to the speech server and then
//! another to the cleanup provider. Sharing one keeps those connections open
//! between requests and between dictations.

use reqwest::{Client, Url};
use std::collections::HashSet;
use std::time::Duration;

const CONNECT_TIMEOUT: Duration = Duration::from_secs(10);
// hosted providers drop idle connections after a few minutes anyway
const POOL_IDLE_TIMEOUT: Duration = Duration::from_secs(300);
const WARM_UP_TIMEOUT: Duration = Duration::from_secs(5);

fn build() -> Result<Client, String> {
    Client::builder()
        .connect_timeout(CONNECT_TIMEOUT)
        .pool_idle_timeout(POOL_IDLE_TIMEOUT)
        .redirect(reqwest::redirect::Policy::none())
        .build()
        .map_err(|_| "Could not create the HTTP client".to_string())
}

/// Requests set their own deadline, the client only bounds the connect.
#[cfg(not(test))]
pub fn client() -> Result<Client, String> {
    static CLIENT: std::sync::OnceLock<Result<Client, String>> = std::sync::OnceLock::new();
    CLIENT.get_or_init(build).clone()
}

/// Each test runs on its own runtime, and a pooled connection must not outlive
/// the runtime that opened it.
#[cfg(test)]
pub fn client() -> Result<Client, String> {
    build()
}

/// Opens a connection to each remote host while the user is still speaking, so
/// the upload and the cleanup request that follow skip the handshake. The reply
/// does not matter: these endpoints answer HEAD with 404 and keep the connection.
pub fn warm_up<'a>(urls: impl IntoIterator<Item = &'a str>) {
    let Ok(client) = client() else {
        return;
    };
    let mut origins = HashSet::new();
    for url in urls {
        let Ok(url) = Url::parse(url.trim()) else {
            continue;
        };
        if url.scheme() != "https" || !origins.insert(url.origin().ascii_serialization()) {
            continue;
        }
        let client = client.clone();
        tauri::async_runtime::spawn(async move {
            let _ = client.head(url).timeout(WARM_UP_TIMEOUT).send().await;
        });
    }
}
