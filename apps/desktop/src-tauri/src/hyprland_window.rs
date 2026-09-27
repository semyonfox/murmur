use serde::Deserialize;
use std::process::Command;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;

static MAIN_WINDOW_HANDLED: AtomicBool = AtomicBool::new(false);

#[derive(Deserialize)]
struct Client {
    pid: u32,
    class: String,
    floating: bool,
}

pub fn float_main_window() {
    if std::env::var_os("HYPRLAND_INSTANCE_SIGNATURE").is_none()
        || std::env::var_os("WAYLAND_DISPLAY").is_none()
        || MAIN_WINDOW_HANDLED.swap(true, Ordering::Relaxed)
    {
        return;
    }

    std::thread::spawn(|| {
        for _ in 0..20 {
            if let Some(client) = main_client() {
                if !client.floating && !set_compact_floating_window() {
                    log::warn!("Could not make the main window float on Hyprland");
                }
                return;
            }
            std::thread::sleep(Duration::from_millis(50));
        }
        log::warn!("Could not locate the main window in Hyprland clients");
    });
}

fn main_client() -> Option<Client> {
    let output = Command::new("hyprctl")
        .args(["-j", "clients"])
        .output()
        .ok()?;
    if !output.status.success() {
        return None;
    }

    serde_json::from_slice::<Vec<Client>>(&output.stdout)
        .ok()?
        .into_iter()
        .find(|client| {
            client.pid == std::process::id() && client.class.eq_ignore_ascii_case("murmur")
        })
}

fn set_compact_floating_window() -> bool {
    let selector = format!("pid:{}", std::process::id());
    // Hyprland 0.55 switched dispatchers from hyprlang to Lua.
    let lua = format!(
        "hl.dispatch(hl.dsp.window.float({{ action = \"set\", window = \"{selector}\" }})); \
         hl.dispatch(hl.dsp.window.resize({{ x = 680, y = 570, relative = false, window = \"{selector}\" }})); \
         hl.dispatch(hl.dsp.window.center({{ window = \"{selector}\" }}))"
    );
    if dispatch(&["eval", &lua]) {
        return true;
    }

    dispatch(&["dispatch", "setfloating", &selector])
        && dispatch(&[
            "dispatch",
            "resizewindowpixel",
            &format!("exact 680 570,{selector}"),
        ])
}

fn dispatch(args: &[&str]) -> bool {
    Command::new("hyprctl")
        .args(args)
        .output()
        .is_ok_and(|output| output.status.success())
}
