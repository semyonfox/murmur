use keyring::{Entry, Error};
use sha2::{Digest, Sha256};

const CLEANUP_KEY_SERVICE: &str = "io.murmur.desktop.cleanup-api-key";
const STT_KEY_SERVICE: &str = "io.murmur.desktop.stt-api-key";

fn validate_provider_id(provider_id: &str) -> Result<(), String> {
    if provider_id.is_empty()
        || provider_id.len() > 64
        || !provider_id.bytes().all(|byte| {
            byte.is_ascii_lowercase() || byte.is_ascii_digit() || byte == b'_' || byte == b'-'
        })
    {
        return Err("Invalid provider ID".to_string());
    }

    Ok(())
}

pub(crate) fn cleanup_origin(base_url: &str) -> Result<String, String> {
    Ok(crate::llm_client::parse_provider_url(base_url)?
        .origin()
        .ascii_serialization())
}

fn cleanup_entry(provider_id: &str, custom_origin: Option<&str>) -> Result<Entry, String> {
    validate_provider_id(provider_id)?;
    let account = match custom_origin {
        Some(origin) => hashed_account_id(origin, "custom-origin"),
        None => provider_id.to_string(),
    };
    Entry::new(CLEANUP_KEY_SERVICE, &account).map_err(store_error)
}

pub(crate) fn cleanup_key_scope(
    settings: &crate::settings::AppSettings,
    provider: &crate::settings::PostProcessProvider,
) -> Result<Option<String>, String> {
    if provider.id != "custom" {
        return Ok(None);
    }

    let origin = cleanup_origin(&provider.base_url)?;
    // the old slot is reserved for the origin saved when settings were upgraded
    Ok((settings.legacy_custom_cleanup_origin.as_deref() != Some(&origin)).then_some(origin))
}

fn store_error(error: Error) -> String {
    match error {
        Error::NoStorageAccess(_) => {
            "System credential store is locked or access was denied".into()
        }
        Error::PlatformFailure(_) => "System credential store is unavailable".into(),
        _ => "System credential store could not complete the request".into(),
    }
}

pub fn set_cleanup_key(provider_id: &str, origin: Option<&str>, key: &str) -> Result<(), String> {
    if key.trim().is_empty() || key.chars().any(char::is_control) {
        return Err("API key must be non-empty and contain no control characters".to_string());
    }

    cleanup_entry(provider_id, origin)?
        .set_password(key)
        .map_err(store_error)
}

pub fn get_cleanup_key(provider_id: &str, origin: Option<&str>) -> Result<Option<String>, String> {
    match cleanup_entry(provider_id, origin)?.get_password() {
        Ok(key) => Ok(Some(key)),
        Err(Error::NoEntry) => Ok(None),
        Err(error) => Err(store_error(error)),
    }
}

pub fn delete_cleanup_key(provider_id: &str, origin: Option<&str>) -> Result<(), String> {
    match cleanup_entry(provider_id, origin)?.delete_credential() {
        Ok(()) | Err(Error::NoEntry) => Ok(()),
        Err(error) => Err(store_error(error)),
    }
}

fn stt_account_id(base_url: &str) -> Result<String, String> {
    if base_url.is_empty() || base_url.trim() != base_url || base_url.chars().any(char::is_control)
    {
        return Err("Invalid speech server URL".to_string());
    }

    // Keep the key tied to the exact configured endpoint, even when two URL forms resolve alike.
    Ok(hashed_account_id(base_url, "url"))
}

fn hashed_account_id(value: &str, scope: &str) -> String {
    let digest = Sha256::digest(value.as_bytes());
    let hex: String = digest.iter().map(|b| format!("{b:02x}")).collect();
    format!("{scope}-sha256-{hex}")
}

fn stt_entry(base_url: &str) -> Result<Entry, String> {
    let account_id = stt_account_id(base_url)?;
    Entry::new(STT_KEY_SERVICE, &account_id).map_err(store_error)
}

pub fn set_stt_key(base_url: &str, key: &str) -> Result<(), String> {
    if key.trim().is_empty() || key.chars().any(char::is_control) {
        return Err("API key must be non-empty and contain no control characters".to_string());
    }

    stt_entry(base_url)?.set_password(key).map_err(store_error)
}

pub fn get_stt_key(base_url: &str) -> Result<Option<String>, String> {
    match stt_entry(base_url)?.get_password() {
        Ok(key) => Ok(Some(key)),
        Err(Error::NoEntry) => Ok(None),
        Err(error) => Err(store_error(error)),
    }
}

pub fn delete_stt_key(base_url: &str) -> Result<(), String> {
    match stt_entry(base_url)?.delete_credential() {
        Ok(()) | Err(Error::NoEntry) => Ok(()),
        Err(error) => Err(store_error(error)),
    }
}

pub fn stt_env_key(base_url: &str) -> Option<String> {
    let configured_url = std::env::var("MURMUR_STT_API_BASE_URL").ok()?;
    let key = std::env::var("MURMUR_STT_API_KEY").ok()?;
    stt_env_key_for(base_url, &configured_url, &key)
}

fn stt_env_key_for(base_url: &str, configured_url: &str, key: &str) -> Option<String> {
    if configured_url.trim() != base_url {
        return None;
    }
    let key = key.trim();
    (!key.is_empty() && !key.chars().any(char::is_control)).then(|| key.to_string())
}

#[cfg(test)]
mod tests {
    use super::{stt_account_id, stt_env_key_for, validate_provider_id};

    #[test]
    fn provider_ids_use_a_small_case_sensitive_safe_alphabet() {
        for id in ["groq", "bedrock_mantle", "my-server2"] {
            assert!(validate_provider_id(id).is_ok());
        }

        for id in ["", "Groq", "a/b", "a.b", "a b", "a\n", "groqé"] {
            assert!(validate_provider_id(id).is_err());
        }

        assert!(validate_provider_id(&"a".repeat(64)).is_ok());
        assert!(validate_provider_id(&"a".repeat(65)).is_err());
    }

    #[test]
    fn legacy_cleanup_slot_is_only_used_for_its_original_origin() {
        let mut settings = crate::settings::get_default_settings();
        settings.legacy_custom_cleanup_origin = Some("https://example.com".into());
        let mut provider = settings.post_process_provider("custom").unwrap().clone();
        provider.base_url = "https://EXAMPLE.com:443/another/path".into();
        assert_eq!(
            super::cleanup_key_scope(&settings, &provider).unwrap(),
            None
        );

        provider.base_url = "https://example.com:444/v1".into();
        assert_eq!(
            super::cleanup_key_scope(&settings, &provider)
                .unwrap()
                .as_deref(),
            Some("https://example.com:444")
        );
    }

    #[test]
    fn stt_keys_are_scoped_to_the_configured_base_url() {
        let groq = stt_account_id("https://api.groq.com/openai/v1").unwrap();
        let other = stt_account_id("https://api.example.com/v1").unwrap();

        assert_ne!(groq, other);
        assert_eq!(
            groq,
            stt_account_id("https://api.groq.com/openai/v1").unwrap()
        );
        assert_eq!(groq.len(), "url-sha256-".len() + 64);
        assert!(stt_account_id("").is_err());
        assert!(stt_account_id(" https://api.groq.com/openai/v1").is_err());
    }

    #[test]
    fn environment_speech_key_requires_matching_endpoint() {
        let endpoint = "https://api.groq.com/openai/v1";
        assert_eq!(
            stt_env_key_for(endpoint, endpoint, " secret "),
            Some("secret".to_string())
        );
        assert_eq!(
            stt_env_key_for(endpoint, "https://api.openai.com/v1", "secret"),
            None
        );
        assert_eq!(stt_env_key_for(endpoint, endpoint, "  "), None);
        assert_eq!(stt_env_key_for(endpoint, endpoint, "bad\nkey"), None);
    }
}
