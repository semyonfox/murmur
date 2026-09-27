// adapted from Voxtype's MIT-licensed modifier guard at pinned revision
// 9c35b72b4fa3635028dfe70b444ca0dc9dcfb389
use evdev::{AttributeSet, Device, KeyCode};
use std::fs::{self, File};
use std::io;
use std::thread;
use std::time::{Duration, Instant};

const POLL_INTERVAL: Duration = Duration::from_millis(15);
const MODIFIER_KEYS: [KeyCode; 8] = [
    KeyCode::KEY_LEFTCTRL,
    KeyCode::KEY_RIGHTCTRL,
    KeyCode::KEY_LEFTALT,
    KeyCode::KEY_RIGHTALT,
    KeyCode::KEY_LEFTSHIFT,
    KeyCode::KEY_RIGHTSHIFT,
    KeyCode::KEY_LEFTMETA,
    KeyCode::KEY_RIGHTMETA,
];

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum ModifierReadiness {
    Released,
    Held,
    Unavailable,
}

/// Check the kernel's current key state without reading from the input event stream.
///
/// Call this from a worker thread: a held shortcut can block for `timeout`.
pub(crate) fn wait_for_release(timeout: Duration) -> ModifierReadiness {
    let devices = readable_modifier_devices();
    if devices.is_empty() {
        return ModifierReadiness::Unavailable;
    }

    let started = Instant::now();
    loop {
        let readiness = classify_states(devices.iter().map(Device::get_key_state));
        if readiness != ModifierReadiness::Held || started.elapsed() >= timeout {
            return readiness;
        }

        thread::sleep(POLL_INTERVAL.min(timeout.saturating_sub(started.elapsed())));
    }
}

fn readable_modifier_devices() -> Vec<Device> {
    let Ok(entries) = fs::read_dir("/dev/input") else {
        return Vec::new();
    };

    entries
        .flatten()
        .filter(|entry| {
            entry
                .file_name()
                .to_str()
                .and_then(|name| name.strip_prefix("event"))
                .is_some_and(|suffix| {
                    !suffix.is_empty() && suffix.bytes().all(|byte| byte.is_ascii_digit())
                })
        })
        .filter_map(|entry| File::open(entry.path()).ok())
        .filter_map(|file| Device::from_fd(file.into()).ok())
        .filter(|device| {
            device.supported_keys().is_some_and(|keys| {
                MODIFIER_KEYS
                    .iter()
                    .copied()
                    .any(|modifier| keys.contains(modifier))
            })
        })
        .collect()
}

fn classify_states(
    states: impl IntoIterator<Item = io::Result<AttributeSet<KeyCode>>>,
) -> ModifierReadiness {
    let mut had_successful_query = false;
    for state in states {
        let Ok(keys) = state else {
            continue;
        };
        had_successful_query = true;
        if MODIFIER_KEYS
            .iter()
            .copied()
            .any(|modifier| keys.contains(modifier))
        {
            return ModifierReadiness::Held;
        }
    }

    if had_successful_query {
        ModifierReadiness::Released
    } else {
        ModifierReadiness::Unavailable
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn pressed(key: KeyCode) -> AttributeSet<KeyCode> {
        let mut keys = AttributeSet::new();
        keys.insert(key);
        keys
    }

    #[test]
    fn detects_either_side_of_each_modifier() {
        for modifier in MODIFIER_KEYS {
            assert_eq!(
                classify_states([Ok(pressed(modifier))]),
                ModifierReadiness::Held
            );
        }
    }

    #[test]
    fn ignores_non_modifier_keys() {
        assert_eq!(
            classify_states([Ok(pressed(KeyCode::KEY_A))]),
            ModifierReadiness::Released
        );
    }

    #[test]
    fn one_pressed_keyboard_overrides_released_and_failed_queries() {
        assert_eq!(
            classify_states([
                Ok(AttributeSet::new()),
                Err(io::Error::other("disconnected")),
                Ok(pressed(KeyCode::KEY_LEFTMETA)),
            ]),
            ModifierReadiness::Held
        );
    }

    #[test]
    fn unavailable_when_every_query_fails_or_there_are_no_devices() {
        assert_eq!(
            classify_states([Err(io::Error::other("inaccessible"))]),
            ModifierReadiness::Unavailable
        );
        assert_eq!(
            classify_states(std::iter::empty()),
            ModifierReadiness::Unavailable
        );
    }
}
