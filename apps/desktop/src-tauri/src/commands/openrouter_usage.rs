use crate::settings::{get_settings, SttSource};
use serde::Serialize;
use serde_json::Value;
use specta::Type;
use std::time::Duration;
use tauri::AppHandle;

const OPENROUTER_KEY_ENDPOINT: &str = "https://openrouter.ai/api/v1/key";
const REQUEST_TIMEOUT: Duration = Duration::from_secs(10);

#[derive(Clone, Debug, Serialize, Type)]
pub struct OpenRouterKeyUsage {
    pub key_count: u32,
    pub usage: f64,
    pub usage_daily: f64,
    pub usage_weekly: f64,
    pub usage_monthly: f64,
    pub byok_usage: f64,
    pub byok_usage_daily: f64,
    pub byok_usage_weekly: f64,
    pub byok_usage_monthly: f64,
}

impl OpenRouterKeyUsage {
    fn empty() -> Self {
        Self {
            key_count: 0,
            usage: 0.0,
            usage_daily: 0.0,
            usage_weekly: 0.0,
            usage_monthly: 0.0,
            byok_usage: 0.0,
            byok_usage_daily: 0.0,
            byok_usage_weekly: 0.0,
            byok_usage_monthly: 0.0,
        }
    }

    fn add(&mut self, usage: Self) -> Result<(), String> {
        self.usage = checked_sum(self.usage, usage.usage)?;
        self.usage_daily = checked_sum(self.usage_daily, usage.usage_daily)?;
        self.usage_weekly = checked_sum(self.usage_weekly, usage.usage_weekly)?;
        self.usage_monthly = checked_sum(self.usage_monthly, usage.usage_monthly)?;
        self.byok_usage = checked_sum(self.byok_usage, usage.byok_usage)?;
        self.byok_usage_daily = checked_sum(self.byok_usage_daily, usage.byok_usage_daily)?;
        self.byok_usage_weekly = checked_sum(self.byok_usage_weekly, usage.byok_usage_weekly)?;
        self.byok_usage_monthly = checked_sum(self.byok_usage_monthly, usage.byok_usage_monthly)?;
        self.key_count = self
            .key_count
            .checked_add(1)
            .ok_or_else(|| "OpenRouter key count is invalid".to_string())?;
        Ok(())
    }
}

/// returns aggregate usage for configured OpenRouter keys without exposing credentials
#[tauri::command]
#[specta::specta]
pub async fn get_openrouter_key_usage(app: AppHandle) -> Result<OpenRouterKeyUsage, String> {
    let settings = get_settings(&app);
    let stt_base_url = (settings.stt_source == SttSource::Endpoint
        && is_openrouter_stt_endpoint(&settings.stt_base_url))
    .then_some(settings.stt_base_url);

    let keys = tauri::async_runtime::spawn_blocking(move || {
        configured_openrouter_keys(stt_base_url.as_deref())
    })
    .await
    .map_err(|_| "Could not access the system credential store".to_string())??;

    if keys.is_empty() {
        return Ok(OpenRouterKeyUsage::empty());
    }

    let client = reqwest::Client::builder()
        .connect_timeout(REQUEST_TIMEOUT)
        .timeout(REQUEST_TIMEOUT)
        .redirect(reqwest::redirect::Policy::none())
        .build()
        .map_err(|_| "Could not create the OpenRouter usage client".to_string())?;

    let mut aggregate = OpenRouterKeyUsage::empty();
    for key in keys {
        aggregate.add(fetch_key_usage(&client, &key).await?)?;
    }

    Ok(aggregate)
}

fn configured_openrouter_keys(stt_base_url: Option<&str>) -> Result<Vec<String>, String> {
    let cleanup_key = crate::secrets::get_cleanup_key("openrouter")?;

    let stt_key = match stt_base_url {
        Some(base_url) => match crate::secrets::get_stt_key(base_url)? {
            Some(key) => Some(key),
            None => crate::secrets::stt_env_key(base_url),
        },
        None => None,
    };

    unique_openrouter_keys([cleanup_key, stt_key])
}

fn unique_openrouter_keys(keys: [Option<String>; 2]) -> Result<Vec<String>, String> {
    let mut unique = Vec::new();

    for key in keys.into_iter().flatten() {
        let key = key.trim();
        if key.is_empty() {
            continue;
        }
        if key.chars().any(char::is_control) {
            return Err("An OpenRouter API key is malformed".to_string());
        }
        if !unique.iter().any(|existing| existing == key) {
            unique.push(key.to_string());
        }
    }

    Ok(unique)
}

fn is_openrouter_stt_endpoint(base_url: &str) -> bool {
    let Ok(url) = reqwest::Url::parse(base_url) else {
        return false;
    };

    url.scheme() == "https"
        && url.host_str() == Some("openrouter.ai")
        && url.username().is_empty()
        && url.password().is_none()
}

async fn fetch_key_usage(
    client: &reqwest::Client,
    key: &str,
) -> Result<OpenRouterKeyUsage, String> {
    let response = client
        .get(OPENROUTER_KEY_ENDPOINT)
        .bearer_auth(key)
        .send()
        .await
        .map_err(|_| "OpenRouter usage request failed".to_string())?
        .error_for_status()
        .map_err(|_| "OpenRouter rejected the usage request".to_string())?;

    let response = response
        .json::<Value>()
        .await
        .map_err(|_| "OpenRouter returned an invalid usage response".to_string())?;

    parse_key_usage(&response)
}

fn parse_key_usage(response: &Value) -> Result<OpenRouterKeyUsage, String> {
    let data = response
        .get("data")
        .and_then(Value::as_object)
        .ok_or_else(|| "OpenRouter returned an invalid usage response".to_string())?;

    if data.get("is_management_key").and_then(Value::as_bool) != Some(false) {
        return Err(
            "OpenRouter account-management keys cannot be used for usage checks".to_string(),
        );
    }

    Ok(OpenRouterKeyUsage {
        key_count: 0,
        usage: usage_number(data, "usage")?,
        usage_daily: usage_number(data, "usage_daily")?,
        usage_weekly: usage_number(data, "usage_weekly")?,
        usage_monthly: usage_number(data, "usage_monthly")?,
        byok_usage: usage_number(data, "byok_usage")?,
        byok_usage_daily: usage_number(data, "byok_usage_daily")?,
        byok_usage_weekly: usage_number(data, "byok_usage_weekly")?,
        byok_usage_monthly: usage_number(data, "byok_usage_monthly")?,
    })
}

fn usage_number(data: &serde_json::Map<String, Value>, name: &str) -> Result<f64, String> {
    let value = data
        .get(name)
        .and_then(Value::as_f64)
        .ok_or_else(|| "OpenRouter returned an invalid usage response".to_string())?;

    if !value.is_finite() || value.is_sign_negative() {
        return Err("OpenRouter returned an invalid usage response".to_string());
    }

    Ok(value)
}

fn checked_sum(left: f64, right: f64) -> Result<f64, String> {
    let sum = left + right;
    if sum.is_finite() {
        Ok(sum)
    } else {
        Err("OpenRouter usage total is invalid".to_string())
    }
}

#[cfg(test)]
mod tests {
    use super::{is_openrouter_stt_endpoint, parse_key_usage, unique_openrouter_keys};
    use serde_json::json;

    fn valid_response() -> serde_json::Value {
        json!({
            "data": {
                "is_management_key": false,
                "usage": 1.25,
                "usage_daily": 2.5,
                "usage_weekly": 3.75,
                "usage_monthly": 5.0,
                "byok_usage": 6.25,
                "byok_usage_daily": 7.5,
                "byok_usage_weekly": 8.75,
                "byok_usage_monthly": 10.0
            }
        })
    }

    #[test]
    fn parses_only_the_current_key_usage_fields() {
        let usage = parse_key_usage(&valid_response()).unwrap();

        assert_eq!(usage.key_count, 0);
        assert_eq!(usage.usage, 1.25);
        assert_eq!(usage.usage_daily, 2.5);
        assert_eq!(usage.usage_weekly, 3.75);
        assert_eq!(usage.usage_monthly, 5.0);
        assert_eq!(usage.byok_usage, 6.25);
        assert_eq!(usage.byok_usage_daily, 7.5);
        assert_eq!(usage.byok_usage_weekly, 8.75);
        assert_eq!(usage.byok_usage_monthly, 10.0);
    }

    #[test]
    fn rejects_management_keys_and_invalid_usage_values() {
        let mut management_key = valid_response();
        management_key["data"]["is_management_key"] = json!(true);
        assert!(parse_key_usage(&management_key).is_err());

        let mut string_usage = valid_response();
        string_usage["data"]["usage"] = json!("1.25");
        assert!(parse_key_usage(&string_usage).is_err());

        let mut negative_usage = valid_response();
        negative_usage["data"]["byok_usage_daily"] = json!(-0.01);
        assert!(parse_key_usage(&negative_usage).is_err());
    }

    #[test]
    fn deduplicates_identical_keys_without_returning_them() {
        let keys = unique_openrouter_keys([
            Some("sk-or-v1-shared".to_string()),
            Some("  sk-or-v1-shared  ".to_string()),
        ])
        .unwrap();

        assert_eq!(keys.len(), 1);
        assert_eq!(
            unique_openrouter_keys([Some("cleanup-key".to_string()), Some("stt-key".to_string()),])
                .unwrap()
                .len(),
            2
        );
        assert!(unique_openrouter_keys([Some("key\nvalue".to_string()), None]).is_err());
    }

    #[test]
    fn only_accepts_the_openrouter_https_stt_endpoint() {
        assert!(is_openrouter_stt_endpoint("https://openrouter.ai/api/v1"));
        assert!(!is_openrouter_stt_endpoint("http://openrouter.ai/api/v1"));
        assert!(!is_openrouter_stt_endpoint(
            "https://api.openrouter.ai/api/v1"
        ));
        assert!(!is_openrouter_stt_endpoint(
            "https://openrouter.ai.example.test/api/v1"
        ));
        assert!(!is_openrouter_stt_endpoint(
            "https://user@openrouter.ai/api/v1"
        ));
    }
}
