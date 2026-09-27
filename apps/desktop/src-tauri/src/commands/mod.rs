pub mod audio;
pub mod history;
pub mod lectures;
pub mod models;
pub mod openrouter_usage;
pub mod transcription;

#[cfg(target_os = "linux")]
use crate::settings::KeyboardImplementation;
use crate::settings::{
    get_settings, update_checks_forced_disabled, write_settings, AppSettings, LogLevel, SttSource,
};
use crate::utils::cancel_current_operation;
use tauri::{AppHandle, Manager};
use tauri_plugin_opener::OpenerExt;

#[tauri::command]
#[specta::specta]
pub fn cancel_operation(app: AppHandle) {
    cancel_current_operation(&app);
}

#[tauri::command]
#[specta::specta]
pub fn is_portable() -> bool {
    crate::portable::is_portable()
}

#[tauri::command]
#[specta::specta]
pub fn is_update_checks_locked() -> bool {
    update_checks_forced_disabled()
}

#[tauri::command]
#[specta::specta]
pub fn get_app_dir_path(app: AppHandle) -> Result<String, String> {
    let app_data_dir = crate::portable::app_data_dir(&app)
        .map_err(|e| format!("Failed to get app data directory: {}", e))?;

    Ok(app_data_dir.to_string_lossy().to_string())
}

#[tauri::command]
#[specta::specta]
pub fn get_app_settings(app: AppHandle) -> Result<AppSettings, String> {
    Ok(get_settings(&app))
}

#[tauri::command]
#[specta::specta]
pub fn get_default_settings() -> Result<AppSettings, String> {
    Ok(crate::settings::get_default_settings())
}

#[tauri::command]
#[specta::specta]
pub fn set_post_process_formality(app: AppHandle, formality: u8) -> Result<(), String> {
    if !(1..=5).contains(&formality) {
        return Err("Formality must be between 1 and 5".to_string());
    }
    let mut settings = get_settings(&app);
    settings.post_process_formality = formality;
    write_settings(&app, settings);
    Ok(())
}

#[specta::specta]
#[tauri::command]
pub fn set_stt_config(
    app: AppHandle,
    source: SttSource,
    base_url: String,
    model: String,
) -> Result<(), String> {
    let base_url = base_url.trim().to_string();
    let model = model.trim().to_string();
    if source == SttSource::Endpoint {
        crate::cloud_stt::transcription_url(&base_url).map_err(|error| error.to_string())?;
        if model.is_empty() || model.len() > 256 || model.chars().any(char::is_control) {
            return Err("Enter a valid speech model name".to_string());
        }
    }

    let mut settings = get_settings(&app);
    settings.stt_source = source;
    settings.stt_base_url = base_url;
    settings.stt_model = model;
    write_settings(&app, settings);
    Ok(())
}

#[specta::specta]
#[tauri::command]
pub async fn set_stt_api_key(app: AppHandle, api_key: String) -> Result<(), String> {
    let base_url = get_settings(&app).stt_base_url;
    crate::cloud_stt::transcription_url(&base_url).map_err(|error| error.to_string())?;
    let api_key = api_key.trim().to_string();

    tauri::async_runtime::spawn_blocking(move || {
        if api_key.is_empty() {
            crate::secrets::delete_stt_key(&base_url)
        } else {
            crate::secrets::set_stt_key(&base_url, &api_key)
        }
    })
    .await
    .map_err(|_| "Could not access the system credential store".to_string())?
}

#[specta::specta]
#[tauri::command]
pub async fn is_stt_api_key_configured(app: AppHandle) -> Result<bool, String> {
    let base_url = get_settings(&app).stt_base_url;
    crate::cloud_stt::transcription_url(&base_url).map_err(|error| error.to_string())?;

    tauri::async_runtime::spawn_blocking(move || {
        crate::secrets::get_stt_key(&base_url).map(|key| key.is_some())
    })
    .await
    .map_err(|_| "Could not access the system credential store".to_string())?
}

#[specta::specta]
#[tauri::command]
pub fn complete_onboarding(app: AppHandle) {
    let mut settings = get_settings(&app);
    settings.onboarding_completed = true;
    write_settings(&app, settings);
}

#[tauri::command]
#[specta::specta]
pub fn get_log_dir_path(app: AppHandle) -> Result<String, String> {
    let log_dir = crate::portable::app_log_dir(&app)
        .map_err(|e| format!("Failed to get log directory: {}", e))?;

    Ok(log_dir.to_string_lossy().to_string())
}

#[specta::specta]
#[tauri::command]
pub fn set_log_level(app: AppHandle, level: LogLevel) -> Result<(), String> {
    let tauri_log_level: tauri_plugin_log::LogLevel = level.into();
    let log_level: log::Level = tauri_log_level.into();
    // Update the file log level atomic so the filter picks up the new level
    crate::FILE_LOG_LEVEL.store(
        log_level.to_level_filter() as u8,
        std::sync::atomic::Ordering::Relaxed,
    );

    let mut settings = get_settings(&app);
    settings.log_level = level;
    write_settings(&app, settings);

    Ok(())
}

#[specta::specta]
#[tauri::command]
pub fn open_recordings_folder(app: AppHandle) -> Result<(), String> {
    let app_data_dir = crate::portable::app_data_dir(&app)
        .map_err(|e| format!("Failed to get app data directory: {}", e))?;

    let recordings_dir = app_data_dir.join("recordings");

    let path = recordings_dir.to_string_lossy().as_ref().to_string();
    app.opener()
        .open_path(path, None::<String>)
        .map_err(|e| format!("Failed to open recordings folder: {}", e))?;

    Ok(())
}

#[specta::specta]
#[tauri::command]
pub fn open_log_dir(app: AppHandle) -> Result<(), String> {
    let log_dir = crate::portable::app_log_dir(&app)
        .map_err(|e| format!("Failed to get log directory: {}", e))?;

    let path = log_dir.to_string_lossy().as_ref().to_string();
    app.opener()
        .open_path(path, None::<String>)
        .map_err(|e| format!("Failed to open log directory: {}", e))?;

    Ok(())
}

#[specta::specta]
#[tauri::command]
pub fn open_app_data_dir(app: AppHandle) -> Result<(), String> {
    let app_data_dir = crate::portable::app_data_dir(&app)
        .map_err(|e| format!("Failed to get app data directory: {}", e))?;

    let path = app_data_dir.to_string_lossy().as_ref().to_string();
    app.opener()
        .open_path(path, None::<String>)
        .map_err(|e| format!("Failed to open app data directory: {}", e))?;

    Ok(())
}

/// Check if Apple Intelligence is available on this device.
/// Called by the frontend when the user selects Apple Intelligence provider.
#[specta::specta]
#[tauri::command]
pub fn check_apple_intelligence_available() -> bool {
    #[cfg(all(target_os = "macos", target_arch = "aarch64"))]
    {
        crate::apple_intelligence::check_apple_intelligence_availability()
    }
    #[cfg(not(all(target_os = "macos", target_arch = "aarch64")))]
    {
        false
    }
}

/// Try to initialize Enigo (keyboard/mouse simulation).
/// On macOS, this will return an error if accessibility permissions are not granted.
#[specta::specta]
#[tauri::command]
pub fn initialize_enigo(app: AppHandle) -> Result<(), String> {
    use crate::input::EnigoState;

    // Check if already initialized
    if app.try_state::<EnigoState>().is_some() {
        log::debug!("Enigo already initialized");
        return Ok(());
    }

    // Try to initialize
    match EnigoState::new() {
        Ok(enigo_state) => {
            app.manage(enigo_state);
            log::info!("Enigo initialized successfully after permission grant");
            Ok(())
        }
        Err(e) => {
            if cfg!(target_os = "macos") {
                log::warn!(
                    "Failed to initialize Enigo: {} (accessibility permissions may not be granted)",
                    e
                );
            } else {
                log::warn!("Failed to initialize Enigo: {}", e);
            }
            Err(format!("Failed to initialize input system: {}", e))
        }
    }
}

/// Marker state to track if shortcuts have been initialized.
pub struct ShortcutsInitialized;

/// Initialize keyboard shortcuts.
/// On macOS, this should be called after accessibility permissions are granted.
/// This is idempotent - calling it multiple times is safe.
#[specta::specta]
#[tauri::command]
pub fn initialize_shortcuts(app: AppHandle) -> Result<(), String> {
    // Check if already initialized
    if app.try_state::<ShortcutsInitialized>().is_some() {
        log::debug!("Shortcuts already initialized");
        return Ok(());
    }

    #[cfg(target_os = "linux")]
    if std::env::var_os("WAYLAND_DISPLAY").is_some() {
        // Tauri's Linux hotkey backend registers against X11, so it cannot
        // receive keys from native Wayland windows. Only save this selection
        // after the evdev listener and the main binding have initialized.
        crate::shortcut::handy_keys::init_shortcuts(&app)?;
        let mut settings = get_settings(&app);
        if settings.keyboard_implementation != KeyboardImplementation::HandyKeys {
            settings.keyboard_implementation = KeyboardImplementation::HandyKeys;
            write_settings(&app, settings);
        }
    } else {
        crate::shortcut::init_shortcuts(&app);
    }

    #[cfg(not(target_os = "linux"))]
    crate::shortcut::init_shortcuts(&app);

    // Mark as initialized before reconciling the macOS Secure Input fallback.
    app.manage(ShortcutsInitialized);
    crate::secure_input::reconcile_fallback(&app);

    log::info!("Shortcuts initialized successfully");
    Ok(())
}
