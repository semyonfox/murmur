//! Translations for the tray and native startup dialogs, generated from the frontend locales.

use once_cell::sync::Lazy;
use std::collections::HashMap;

include!(concat!(env!("OUT_DIR"), "/tray_translations.rs"));
include!(concat!(env!("OUT_DIR"), "/startup_translations.rs"));

pub fn get_tray_translations(locale: Option<String>) -> TrayStrings {
    translations_for(&TRAY_TRANSLATIONS, locale.as_deref()).clone()
}

pub fn get_startup_translations(locale: Option<&str>) -> &'static StartupStrings {
    translations_for(&STARTUP_TRANSLATIONS, locale)
}

/// Lookup order: exact locale → Chinese script/region fallback → language code → English.
fn translations_for<'a, T>(translations: &'a HashMap<&str, T>, locale: Option<&str>) -> &'a T {
    let normalized = locale.unwrap_or("en").to_lowercase().replace('_', "-");
    let subtags: Vec<_> = normalized.split('-').collect();
    let language = subtags.first().copied().unwrap_or("en");
    let is_hant = subtags.contains(&"hant");
    let is_hans = subtags.contains(&"hans");
    let is_traditional_region = ["tw", "hk", "mo"]
        .iter()
        .any(|region| subtags.contains(region));

    let exact_match = translations
        .iter()
        .find_map(|(code, strings)| code.eq_ignore_ascii_case(&normalized).then_some(strings));
    let fallback = match language {
        "zh" if is_hant || (!is_hans && is_traditional_region) => "zh-TW",
        // Cantonese uses Traditional Chinese unless explicitly tagged as Hans.
        "yue" if is_hans => "zh",
        "yue" => "zh-TW",
        _ => language,
    };

    exact_match
        .or_else(|| translations.get(fallback))
        .or_else(|| translations.get("en"))
        .expect("English translations must exist")
}

#[cfg(test)]
mod tests {
    use super::{get_tray_translations, TRAY_TRANSLATIONS};

    #[test]
    fn resolves_locale_fallbacks() {
        for (locale, expected) in [
            ("zh-Hant-TW", "zh-TW"),
            ("zh-Hant-HK", "zh-TW"),
            ("zh-HK", "zh-TW"),
            ("zh-MO", "zh-TW"),
            ("ZH-TW", "zh-TW"),
            ("zh_Hant_TW", "zh-TW"),
            ("zh-Hans-CN", "zh"),
            ("yue-Hant-HK", "zh-TW"),
            ("yue-Hans-CN", "zh"),
            ("fr-FR", "fr"),
            ("xx-YY", "en"),
        ] {
            assert_eq!(
                format!("{:?}", get_tray_translations(Some(locale.into()))),
                format!("{:?}", TRAY_TRANSLATIONS[expected]),
                "{locale} should resolve to {expected}"
            );
        }
    }
}
