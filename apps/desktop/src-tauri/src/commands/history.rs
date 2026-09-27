use crate::actions::process_transcription_output;
use crate::managers::{
    history::{ArchiveExportSummary, HistoryManager, HistoryStats, PaginatedHistory},
    transcription::TranscriptionManager,
};
use crate::settings::SttSource;
use std::path::PathBuf;
use std::sync::Arc;
use tauri::{AppHandle, State};

#[tauri::command]
#[specta::specta]
pub async fn get_history_entries(
    _app: AppHandle,
    history_manager: State<'_, Arc<HistoryManager>>,
    cursor: Option<i64>,
    limit: Option<usize>,
) -> Result<PaginatedHistory, String> {
    history_manager
        .get_history_entries(cursor, limit)
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
#[specta::specta]
pub async fn get_history_stats(
    history_manager: State<'_, Arc<HistoryManager>>,
) -> Result<HistoryStats, String> {
    let manager = Arc::clone(&history_manager);
    tauri::async_runtime::spawn_blocking(move || manager.get_stats())
        .await
        .map_err(|error| format!("Could not calculate statistics: {error}"))?
        .map_err(|error| error.to_string())
}

#[tauri::command]
#[specta::specta]
pub async fn export_history_archive(
    history_manager: State<'_, Arc<HistoryManager>>,
    destination: String,
) -> Result<ArchiveExportSummary, String> {
    let destination = PathBuf::from(destination);
    if !destination.is_absolute() {
        return Err("Choose an absolute archive path".to_string());
    }
    let manager = Arc::clone(&history_manager);
    tauri::async_runtime::spawn_blocking(move || manager.export_archive(&destination))
        .await
        .map_err(|_| "Archive export task failed".to_string())?
        .map_err(|error| error.to_string())
}

#[tauri::command]
#[specta::specta]
pub async fn toggle_history_entry_saved(
    _app: AppHandle,
    history_manager: State<'_, Arc<HistoryManager>>,
    id: i64,
) -> Result<(), String> {
    history_manager
        .toggle_saved_status(id)
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
#[specta::specta]
pub async fn get_audio_file_path(
    _app: AppHandle,
    history_manager: State<'_, Arc<HistoryManager>>,
    file_name: String,
) -> Result<String, String> {
    let path = history_manager.get_audio_file_path(&file_name);
    path.to_str()
        .ok_or_else(|| "Invalid file path".to_string())
        .map(|s| s.to_string())
}

#[tauri::command]
#[specta::specta]
pub async fn delete_history_entry(
    _app: AppHandle,
    history_manager: State<'_, Arc<HistoryManager>>,
    id: i64,
) -> Result<(), String> {
    history_manager
        .delete_entry(id)
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
#[specta::specta]
pub async fn retry_history_entry_transcription(
    app: AppHandle,
    history_manager: State<'_, Arc<HistoryManager>>,
    transcription_manager: State<'_, Arc<TranscriptionManager>>,
    id: i64,
) -> Result<(), String> {
    let entry = history_manager
        .get_entry_by_id(id)
        .await
        .map_err(|e| e.to_string())?
        .ok_or_else(|| format!("History entry {} not found", id))?;

    let audio_path = history_manager.get_audio_file_path(&entry.file_name);
    if entry.title.starts_with("Lecture · ") || entry.title.starts_with("Import · ") {
        return super::lectures::transcribe_long_audio(
            &app,
            &history_manager,
            &transcription_manager,
            id,
            audio_path,
        )
        .await;
    }
    let settings = crate::settings::get_settings(&app);
    let transcription = if settings.stt_source == SttSource::Endpoint {
        let wav = tokio::fs::read(&audio_path)
            .await
            .map_err(|_| "Could not read the saved recording".to_string())?;
        let key_url = settings.stt_base_url.clone();
        let stored_key =
            tauri::async_runtime::spawn_blocking(move || crate::secrets::get_stt_key(&key_url))
                .await;
        let key = match stored_key {
            Ok(Ok(Some(key))) if !key.trim().is_empty() => key,
            _ => crate::secrets::stt_env_key(&settings.stt_base_url).unwrap_or_default(),
        };
        let language =
            (settings.selected_language != "auto").then_some(settings.selected_language.as_str());
        crate::cloud_stt::transcribe_wav(
            &settings.stt_base_url,
            &settings.stt_model,
            &key,
            wav,
            language,
        )
        .await
        .map_err(|error| error.to_string())?
    } else {
        let samples = crate::audio_toolkit::read_wav_samples(&audio_path)
            .map_err(|e| format!("Failed to load audio: {}", e))?;
        if samples.is_empty() {
            return Err("Recording has no audio samples".to_string());
        }
        transcription_manager.initiate_model_load();
        let tm = Arc::clone(&transcription_manager);
        tauri::async_runtime::spawn_blocking(move || tm.transcribe(samples))
            .await
            .map_err(|e| format!("Transcription task panicked: {}", e))?
            .map_err(|e| e.to_string())?
    };

    if transcription.is_empty() {
        return Err("Recording contains no speech".to_string());
    }

    let processed =
        process_transcription_output(&app, &transcription, entry.post_process_requested).await;
    history_manager
        .update_transcription(
            id,
            transcription,
            processed.post_processed_text,
            processed.post_process_prompt,
        )
        .map(|_| ())
        .map_err(|e| e.to_string())
}

#[tauri::command]
#[specta::specta]
pub async fn update_history_limit(
    app: AppHandle,
    history_manager: State<'_, Arc<HistoryManager>>,
    limit: usize,
) -> Result<(), String> {
    let mut settings = crate::settings::get_settings(&app);
    settings.history_limit = limit;
    crate::settings::write_settings(&app, settings);

    history_manager
        .cleanup_old_entries()
        .map_err(|e| e.to_string())?;

    Ok(())
}

#[tauri::command]
#[specta::specta]
pub async fn update_recording_retention_period(
    app: AppHandle,
    history_manager: State<'_, Arc<HistoryManager>>,
    period: String,
) -> Result<(), String> {
    use crate::settings::RecordingRetentionPeriod;

    let retention_period = match period.as_str() {
        "never" => RecordingRetentionPeriod::Never,
        "preserve_limit" => RecordingRetentionPeriod::PreserveLimit,
        "days_3" | "days3" => RecordingRetentionPeriod::Days3,
        "weeks_2" | "weeks2" => RecordingRetentionPeriod::Weeks2,
        "months_3" | "months3" => RecordingRetentionPeriod::Months3,
        _ => return Err(format!("Invalid retention period: {}", period)),
    };

    let mut settings = crate::settings::get_settings(&app);
    settings.recording_retention_period = retention_period;
    crate::settings::write_settings(&app, settings);

    history_manager
        .cleanup_old_entries()
        .map_err(|e| e.to_string())?;

    Ok(())
}
