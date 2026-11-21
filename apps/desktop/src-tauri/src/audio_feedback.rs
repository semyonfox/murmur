use crate::settings::SoundTheme;
use crate::settings::{self, AppSettings};
use log::warn;
use std::path::{Path, PathBuf};
use std::sync::mpsc;
use std::thread;
use std::time::Duration;
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

/// Plays the cue and waits for it to finish, but for at most `max_wait`. The
/// player keeps running in the background if the wait gives up.
pub fn play_feedback_sound_bounded(app: &AppHandle, sound_type: SoundType, max_wait: Duration) {
    let settings = settings::get_settings(app);
    if !settings.audio_feedback {
        return;
    }

    if let Some(path) = resolve_sound_path(app, &settings, sound_type) {
        let (done_tx, done_rx) = mpsc::channel();
        thread::spawn(move || {
            play_sound_blocking(&path);
            let _ = done_tx.send(());
        });
        if done_rx.recv_timeout(max_wait).is_err() {
            warn!("Feedback cue still playing after {max_wait:?}; not waiting any longer");
        }
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

/// Whether sound is playing somewhere the microphone cannot hear it, such as
/// headphones or a headset. The start cue then needs no gap in the recording.
/// Anything unknown counts as speakers, which keeps the cue out of the audio.
#[cfg(target_os = "linux")]
pub fn output_is_private() -> bool {
    use std::process::Command;

    // the answer gates the start of capture, so a sound server that does not
    // reply is treated as speakers rather than waited for
    const MAX_WAIT: Duration = Duration::from_millis(200);

    let (answer_tx, answer_rx) = mpsc::channel();
    thread::spawn(move || {
        let run = |args: &[&str]| {
            Command::new("pactl")
                .args(args)
                .output()
                .ok()
                .filter(|output| output.status.success())
                .map(|output| String::from_utf8_lossy(&output.stdout).into_owned())
        };
        let private = run(&["get-default-sink"]).is_some_and(|default_sink| {
            run(&["--format=json", "list", "sinks"])
                .is_some_and(|sinks| sink_is_private(default_sink.trim(), &sinks))
        });
        let _ = answer_tx.send(private);
    });
    answer_rx.recv_timeout(MAX_WAIT).unwrap_or(false)
}

#[cfg(not(target_os = "linux"))]
pub fn output_is_private() -> bool {
    false
}

/// Reads `pactl --format=json list sinks`. A sink is private when its active
/// port is a headphone or headset port, or the device describes itself as one.
#[cfg(any(target_os = "linux", test))]
fn sink_is_private(default_sink: &str, sinks_json: &str) -> bool {
    let Ok(sinks) = serde_json::from_str::<serde_json::Value>(sinks_json) else {
        return false;
    };
    let Some(sink) = sinks
        .as_array()
        .and_then(|sinks| sinks.iter().find(|sink| sink["name"] == default_sink))
    else {
        return false;
    };
    let port_type = sink["ports"]
        .as_array()
        .and_then(|ports| {
            ports
                .iter()
                .find(|port| port["name"] == sink["active_port"])
        })
        .and_then(|port| port["type"].as_str())
        .unwrap_or_default();
    let form_factor = ["device.form_factor", "device.form-factor"]
        .iter()
        .find_map(|key| sink["properties"][*key].as_str())
        .unwrap_or_default();
    matches!(port_type, "Headphones" | "Headset" | "Handset" | "Earpiece")
        || matches!(
            form_factor,
            "headphone" | "headset" | "hands-free" | "handset"
        )
}

#[cfg(test)]
mod tests {
    use super::sink_is_private;

    // the speaker entry is what the laptop reports, the others follow the same shape
    const SINKS: &str = r#"[
        {"name": "alsa_output.pci-0000_00_1f.3.HiFi__Speaker__sink", "active_port": "[Out] Speaker",
         "ports": [{"name": "[Out] Speaker", "type": "Speaker"}],
         "properties": {"device.bus": "pci", "device.api": "alsa"}},
        {"name": "alsa_output.pci-0000_00_1f.3.HiFi__Headphones__sink", "active_port": "[Out] Headphones",
         "ports": [{"name": "[Out] Speaker", "type": "Speaker"}, {"name": "[Out] Headphones", "type": "Headphones"}],
         "properties": {"device.bus": "pci", "device.api": "alsa"}},
        {"name": "bluez_output.AC_BF_71_00_00_00.1", "active_port": null, "ports": [],
         "properties": {"device.api": "bluez5", "device.form_factor": "headset"}},
        {"name": "bluez_output.00_11_22_33_44_55.1", "active_port": null, "ports": [],
         "properties": {"device.api": "bluez5", "device.form_factor": "speaker"}}
    ]"#;

    #[test]
    fn only_headphones_and_headsets_keep_the_cue_out_of_the_microphone() {
        assert!(!sink_is_private(
            "alsa_output.pci-0000_00_1f.3.HiFi__Speaker__sink",
            SINKS
        ));
        assert!(sink_is_private(
            "alsa_output.pci-0000_00_1f.3.HiFi__Headphones__sink",
            SINKS
        ));
        assert!(sink_is_private("bluez_output.AC_BF_71_00_00_00.1", SINKS));
        assert!(!sink_is_private("bluez_output.00_11_22_33_44_55.1", SINKS));
        assert!(!sink_is_private("auto_null", SINKS));
        assert!(!sink_is_private("anything", "not json"));
    }
}
