use crate::actions::process_transcription_output;
use crate::managers::{
    audio::AudioRecordingManager, history::HistoryManager, transcription::TranscriptionManager,
};
use crate::settings::SttSource;
use chrono::Local;
use rodio::{Decoder, Source};
use std::{
    fs::File,
    io::Cursor,
    path::{Path, PathBuf},
    sync::{
        atomic::{AtomicU64, Ordering},
        Arc, Mutex, OnceLock,
    },
};
use tauri::{AppHandle, Emitter, State};

#[derive(Clone, serde::Serialize)]
struct LectureProgress {
    id: i64,
    completed: usize,
    total: usize,
}

const BINDING: &str = "lecture";
const CHUNK_SAMPLES: usize = 16_000 * 120;
static SESSION: OnceLock<Mutex<Option<(i64, PathBuf)>>> = OnceLock::new();
static FILE_SEQUENCE: AtomicU64 = AtomicU64::new(0);

fn session() -> &'static Mutex<Option<(i64, PathBuf)>> {
    SESSION.get_or_init(|| Mutex::new(None))
}

fn new_file_name() -> String {
    let nanos = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_nanos();
    format!(
        "lecture-{nanos}-{}.wav",
        FILE_SEQUENCE.fetch_add(1, Ordering::Relaxed)
    )
}

#[tauri::command]
#[specta::specta]
pub fn start_lecture_recording(
    recording_manager: State<'_, Arc<AudioRecordingManager>>,
    history_manager: State<'_, Arc<HistoryManager>>,
) -> Result<i64, String> {
    let mut active = session().lock().map_err(|_| "Recorder unavailable")?;
    if active.is_some() {
        return Err("A lecture is already recording".into());
    }
    let name = new_file_name();
    let path = history_manager.get_audio_file_path(&name);
    recording_manager.try_start_recording_to_file(BINDING, &path)?;
    let title = format!("Lecture · {}", Local::now().format("%e %b %Y, %H:%M"));
    match history_manager.save_long_entry(name, title) {
        Ok(entry) => {
            *active = Some((entry.id, path));
            Ok(entry.id)
        }
        Err(error) => {
            let _ = recording_manager.stop_recording_file(BINDING);
            let _ = std::fs::remove_file(path);
            Err(format!("Could not save lecture: {error}"))
        }
    }
}

#[tauri::command]
#[specta::specta]
pub fn get_active_lecture() -> Result<Option<i64>, String> {
    Ok(session()
        .lock()
        .map_err(|_| "Recorder unavailable")?
        .as_ref()
        .map(|value| value.0))
}

#[tauri::command]
#[specta::specta]
pub async fn finish_lecture_recording(
    app: AppHandle,
    recording_manager: State<'_, Arc<AudioRecordingManager>>,
    history_manager: State<'_, Arc<HistoryManager>>,
    transcription_manager: State<'_, Arc<TranscriptionManager>>,
) -> Result<i64, String> {
    let (id, path) = session()
        .lock()
        .map_err(|_| "Recorder unavailable")?
        .take()
        .ok_or("No lecture is recording")?;
    let samples = recording_manager.stop_recording_file(BINDING)?;
    if samples == 0 {
        return Err("The recording contains no audio".into());
    }
    transcribe_long_audio(&app, &history_manager, &transcription_manager, id, path).await?;
    Ok(id)
}

#[tauri::command]
#[specta::specta]
pub async fn import_lecture_audio(
    app: AppHandle,
    history_manager: State<'_, Arc<HistoryManager>>,
    transcription_manager: State<'_, Arc<TranscriptionManager>>,
    source: String,
) -> Result<i64, String> {
    let source = PathBuf::from(source);
    if !source.is_absolute() || !source.is_file() {
        return Err("Choose an audio file".into());
    }
    let name = new_file_name();
    let destination = history_manager.get_audio_file_path(&name);
    let input = source.clone();
    let output = destination.clone();
    let conversion = tauri::async_runtime::spawn_blocking(move || convert_audio(&input, &output))
        .await
        .map_err(|error| format!("Could not import audio: {error}"))?;
    if let Err(error) = conversion {
        let _ = std::fs::remove_file(&destination);
        return Err(error);
    }
    let title = source
        .file_stem()
        .and_then(|stem| stem.to_str())
        .unwrap_or("Imported audio");
    let title = format!("Import · {}", title.chars().take(80).collect::<String>());
    let entry = history_manager
        .save_long_entry(name, title)
        .map_err(|error| format!("Could not save imported audio: {error}"))?;
    transcribe_long_audio(
        &app,
        &history_manager,
        &transcription_manager,
        entry.id,
        destination,
    )
    .await?;
    Ok(entry.id)
}

fn convert_audio(source: &Path, destination: &Path) -> Result<(), String> {
    let file = File::open(source).map_err(|error| format!("Could not open audio: {error}"))?;
    let decoder =
        Decoder::try_from(file).map_err(|error| format!("Unsupported audio file: {error}"))?;
    let channels = decoder.channels() as usize;
    let sample_rate = decoder.sample_rate() as usize;
    if channels == 0 || sample_rate == 0 {
        return Err("Invalid audio format".into());
    }
    let mut writer = hound::WavWriter::create(
        destination,
        hound::WavSpec {
            channels: 1,
            sample_rate: 16_000,
            bits_per_sample: 16,
            sample_format: hound::SampleFormat::Int,
        },
    )
    .map_err(|error| format!("Could not create recording: {error}"))?;
    let mut resampler = crate::audio_toolkit::audio::FrameResampler::new(
        sample_rate,
        16_000,
        std::time::Duration::from_millis(20),
    );
    let mut frame = Vec::with_capacity(channels);
    let mut mono_batch = Vec::with_capacity(4096);
    let mut write_error = None;
    let mut written = 0usize;
    let mut write = |samples: &[f32]| {
        for sample in samples {
            if write_error.is_none() {
                if let Err(error) =
                    writer.write_sample((sample.clamp(-1.0, 1.0) * i16::MAX as f32) as i16)
                {
                    write_error = Some(error.to_string());
                }
                written += 1;
            }
        }
    };
    for sample in decoder {
        frame.push(sample);
        if frame.len() == channels {
            let mono = frame.iter().sum::<f32>() / channels as f32;
            mono_batch.push(mono);
            if mono_batch.len() == 4096 {
                resampler.push(&mono_batch, &mut write);
                mono_batch.clear();
            }
            frame.clear();
        }
    }
    if !mono_batch.is_empty() {
        resampler.push(&mono_batch, &mut write);
    }
    resampler.finish(&mut write);
    drop(write);
    writer
        .finalize()
        .map_err(|error| format!("Could not finish recording: {error}"))?;
    if let Some(error) = write_error {
        return Err(format!("Could not save audio: {error}"));
    }
    if written == 0 {
        return Err("Audio file is empty".into());
    }
    Ok(())
}

pub async fn transcribe_long_audio(
    app: &AppHandle,
    history: &HistoryManager,
    transcription: &Arc<TranscriptionManager>,
    id: i64,
    path: PathBuf,
) -> Result<(), String> {
    let settings = crate::settings::get_settings(app);
    let mut reader = hound::WavReader::open(&path)
        .map_err(|error| format!("Could not open recording: {error}"))?;
    let spec = reader.spec();
    if spec.channels != 1 || spec.sample_rate != 16_000 || spec.bits_per_sample != 16 {
        return Err("Recording format is not 16 kHz mono WAV".into());
    }
    let total = (reader.duration() as usize).div_ceil(CHUNK_SAMPLES);
    let mut samples_iter = reader.samples::<i16>();
    let _ = app.emit(
        "lecture-progress",
        LectureProgress {
            id,
            completed: 0,
            total,
        },
    );
    let mut raw = String::new();
    let mut cleaned = String::new();
    let key = if settings.stt_source == SttSource::Endpoint {
        let url = settings.stt_base_url.clone();
        tauri::async_runtime::spawn_blocking(move || crate::secrets::get_stt_key(&url))
            .await
            .ok()
            .and_then(Result::ok)
            .flatten()
            .or_else(|| crate::secrets::stt_env_key(&settings.stt_base_url))
            .unwrap_or_default()
    } else {
        String::new()
    };
    if settings.stt_source != SttSource::Endpoint {
        transcription.initiate_model_load();
    }
    for index in 0.. {
        let mut samples = Vec::with_capacity(CHUNK_SAMPLES);
        for _ in 0..CHUNK_SAMPLES {
            match samples_iter.next() {
                Some(Ok(sample)) => samples.push(sample as f32 / i16::MAX as f32),
                Some(Err(error)) => return Err(format!("Could not read recording: {error}")),
                None => break,
            }
        }
        if samples.is_empty() {
            break;
        }
        let text = if settings.stt_source == SttSource::Endpoint {
            let bytes = encode_chunk(&samples)?;
            let language = (settings.selected_language != "auto")
                .then_some(settings.selected_language.as_str());
            crate::cloud_stt::transcribe_wav(
                &settings.stt_base_url,
                &settings.stt_model,
                &key,
                bytes,
                language,
            )
            .await
            .map_err(|error| error.to_string())?
        } else {
            let manager = Arc::clone(transcription);
            tauri::async_runtime::spawn_blocking(move || manager.transcribe(samples))
                .await
                .map_err(|error| format!("Transcription task failed: {error}"))?
                .map_err(|error| error.to_string())?
        };
        if text.trim().is_empty() {
            let _ = app.emit(
                "lecture-progress",
                LectureProgress {
                    id,
                    completed: index + 1,
                    total,
                },
            );
            continue;
        }
        let timestamp = format!("[{:02}:{:02}] ", index * 2, 0);
        raw.push_str(&timestamp);
        raw.push_str(text.trim());
        raw.push_str("\n\n");
        history
            .update_transcription(id, raw.clone(), None, None)
            .map_err(|error| error.to_string())?;
        let output = process_transcription_output(app, &text, settings.post_process_enabled).await;
        cleaned.push_str(&timestamp);
        cleaned.push_str(output.post_processed_text.as_deref().unwrap_or(text.trim()));
        cleaned.push_str("\n\n");
        history
            .update_transcription(
                id,
                raw.clone(),
                Some(cleaned.clone()),
                output.post_process_prompt,
            )
            .map_err(|error| error.to_string())?;
        let _ = app.emit(
            "lecture-progress",
            LectureProgress {
                id,
                completed: index + 1,
                total,
            },
        );
    }
    Ok(())
}

fn encode_chunk(samples: &[f32]) -> Result<Vec<u8>, String> {
    let mut cursor = Cursor::new(Vec::new());
    let mut writer = hound::WavWriter::new(
        &mut cursor,
        hound::WavSpec {
            channels: 1,
            sample_rate: 16_000,
            bits_per_sample: 16,
            sample_format: hound::SampleFormat::Int,
        },
    )
    .map_err(|error| error.to_string())?;
    for sample in samples {
        writer
            .write_sample((sample.clamp(-1.0, 1.0) * i16::MAX as f32) as i16)
            .map_err(|error| error.to_string())?;
    }
    writer.finalize().map_err(|error| error.to_string())?;
    Ok(cursor.into_inner())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn imported_stereo_wav_becomes_mono_16khz() {
        let directory = tempfile::tempdir().unwrap();
        let input = directory.path().join("input.wav");
        let output = directory.path().join("output.wav");
        let mut writer = hound::WavWriter::create(
            &input,
            hound::WavSpec {
                channels: 2,
                sample_rate: 16_000,
                bits_per_sample: 16,
                sample_format: hound::SampleFormat::Int,
            },
        )
        .unwrap();
        for _ in 0..16_000 {
            writer.write_sample(16_000i16).unwrap();
            writer.write_sample(0i16).unwrap();
        }
        writer.finalize().unwrap();
        convert_audio(&input, &output).unwrap();
        let mut reader = hound::WavReader::open(output).unwrap();
        assert_eq!(reader.spec().channels, 1);
        assert_eq!(reader.spec().sample_rate, 16_000);
        let samples: Vec<i16> = reader.samples().map(Result::unwrap).collect();
        assert!((samples.len() as i64 - 16_000).abs() <= 320);
        assert!(samples
            .iter()
            .take(100)
            .all(|sample| (*sample - 8_000).abs() < 10));
    }

    #[test]
    fn endpoint_chunk_has_valid_wav_header() {
        let bytes = encode_chunk(&vec![0.0; CHUNK_SAMPLES]).unwrap();
        assert!(bytes.len() < 25_000_000);
        let reader = hound::WavReader::new(Cursor::new(bytes)).unwrap();
        assert_eq!(reader.duration() as usize, CHUNK_SAMPLES);
    }
}
