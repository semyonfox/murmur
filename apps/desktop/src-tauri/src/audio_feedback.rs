use crate::settings::SoundTheme;
use crate::settings::{self, AppSettings};
use log::warn;
use std::path::{Path, PathBuf};
use std::thread;
use tauri::{AppHandle, Manager};

pub enum SoundType {
    Start,
    Stop,
}

fn resolve_sound_path(
    app: &AppHandle,
    settings: &AppSettings,
    sound_type: SoundType,
) -> Option<PathBuf> {
    let sound_file = get_sound_path(settings, sound_type);
    let base_dir = get_sound_base_dir(settings);
    match base_dir {
        tauri::path::BaseDirectory::AppData => {
            crate::portable::resolve_app_data(app, &sound_file).ok()
        }
        _ => app.path().resolve(&sound_file, base_dir).ok(),
    }
}

fn get_sound_path(settings: &AppSettings, sound_type: SoundType) -> String {
    match (settings.sound_theme, sound_type) {
        (SoundTheme::Custom, SoundType::Start) => "custom_start.wav".to_string(),
        (SoundTheme::Custom, SoundType::Stop) => "custom_stop.wav".to_string(),
        (_, SoundType::Start) => settings.sound_theme.to_start_path(),
        (_, SoundType::Stop) => settings.sound_theme.to_stop_path(),
    }
}

fn get_sound_base_dir(settings: &AppSettings) -> tauri::path::BaseDirectory {
    match settings.sound_theme {
        SoundTheme::Custom => tauri::path::BaseDirectory::AppData,
        _ => tauri::path::BaseDirectory::Resource,
    }
}

pub fn play_feedback_sound(app: &AppHandle, sound_type: SoundType) {
    let settings = settings::get_settings(app);
    if !settings.audio_feedback {
        return;
    }

    if let Some(path) = resolve_sound_path(app, &settings, sound_type) {
        play_sound_async(path);
    }
}

pub fn play_feedback_sound_blocking(app: &AppHandle, sound_type: SoundType) {
    let settings = settings::get_settings(app);
    if !settings.audio_feedback {
        return;
    }

    if let Some(path) = resolve_sound_path(app, &settings, sound_type) {
        play_sound_blocking(&path);
    }
}

pub fn play_test_sound(app: &AppHandle, sound_type: SoundType) {
    let settings = settings::get_settings(app);
    if let Some(path) = resolve_sound_path(app, &settings, sound_type) {
        play_sound_blocking(&path);
    }
}

fn play_sound_async(path: PathBuf) {
    thread::spawn(move || {
        if let Err(error) = play_system_audio_file(path.as_path()) {
            warn!(
                "Could not play system feedback sound '{}': {error}",
                path.display()
            );
        }
    });
}

fn play_sound_blocking(path: &Path) {
    if let Err(error) = play_system_audio_file(path) {
        warn!(
            "Could not play system feedback sound '{}': {error}",
            path.display()
        );
    }
}

#[cfg(target_os = "linux")]
fn play_system_audio_file(path: &Path) -> Result<(), String> {
    use std::io::ErrorKind;
    use std::process::Command;

    match Command::new("canberra-gtk-play")
        .arg("-f")
        .arg(path)
        .args(["-d", "Murmur dictation cue"])
        .status()
    {
        Ok(status) if status.success() => Ok(()),
        Ok(status) => Err(format!("canberra-gtk-play exited with {status}")),
        Err(error) if error.kind() == ErrorKind::NotFound => Command::new("paplay")
            .args([
                "--property=media.role=event",
                "--property=media.name=Murmur dictation cue",
            ])
            .arg(path)
            .status()
            .map_err(|fallback_error| {
                format!("canberra-gtk-play and paplay were unavailable: {fallback_error}")
            })
            .and_then(|status| {
                if status.success() {
                    Ok(())
                } else {
                    Err(format!("paplay exited with {status}"))
                }
            }),
        Err(error) => Err(format!("could not start canberra-gtk-play: {error}")),
    }
}

#[cfg(target_os = "macos")]
fn play_system_audio_file(path: &Path) -> Result<(), String> {
    use std::process::Command;

    let status = Command::new("afplay")
        .arg(path)
        .status()
        .map_err(|error| format!("afplay was unavailable: {error}"))?;

    if status.success() {
        Ok(())
    } else {
        Err(format!("afplay exited with {status}"))
    }
}

#[cfg(target_os = "windows")]
fn play_system_audio_file(path: &Path) -> Result<(), String> {
    const SND_NODEFAULT: u32 = 0x0000_0002;
    const SND_FILENAME: u32 = 0x0002_0000;

    #[link(name = "winmm")]
    unsafe extern "system" {
        fn PlaySoundW(sound: *const u16, module: *mut std::ffi::c_void, flags: u32) -> i32;
    }

    let sound = path
        .to_string_lossy()
        .encode_utf16()
        .chain(std::iter::once(0))
        .collect::<Vec<_>>();
    if unsafe {
        PlaySoundW(
            sound.as_ptr(),
            std::ptr::null_mut(),
            SND_FILENAME | SND_NODEFAULT,
        )
    } != 0
    {
        Ok(())
    } else {
        Err("PlaySoundW failed".to_string())
    }
}

#[cfg(not(any(target_os = "linux", target_os = "macos", target_os = "windows")))]
fn play_system_audio_file(_path: &Path) -> Result<(), String> {
    Err("system audio playback is not supported on this platform".to_string())
}
