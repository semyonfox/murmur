use std::fs::{self, File, OpenOptions};
use std::io::{self, Read, Write};
use std::path::Path;
use tauri::{AppHandle, Manager};

const LEGACY_APP_ID: &str = "dev.local.murmur";
const MIGRATION_MARKER: &str = "migration-dev-local-murmur.complete";

pub fn migrate_legacy_data(app: &AppHandle) -> io::Result<()> {
    if crate::portable::is_portable() {
        return Ok(());
    }

    let current = app.path().app_data_dir().map_err(io::Error::other)?;
    if current.join(MIGRATION_MARKER).exists() {
        return Ok(());
    }
    let legacy = current
        .parent()
        .ok_or_else(|| io::Error::other("App data directory has no parent"))?
        .join(LEGACY_APP_ID);

    if !legacy.is_dir() || current == legacy {
        return Ok(());
    }

    copy_data_dir(&legacy, &current)?;
    fs::write(current.join(MIGRATION_MARKER), b"complete\n")?;
    log::info!("Migrated previous Murmur data to {}", current.display());
    Ok(())
}

fn copy_data_dir(source: &Path, target: &Path) -> io::Result<()> {
    fs::create_dir_all(target)?;

    for entry in fs::read_dir(source)? {
        let entry = entry?;
        let name = entry.file_name();

        // The new log file may already exist before Tauri calls setup.
        if name == "logs" {
            continue;
        }

        let source_path = entry.path();
        let target_path = target.join(&name);
        let file_type = entry.file_type()?;

        if file_type.is_dir() {
            if target_path.is_file() {
                return Err(io::Error::new(
                    io::ErrorKind::AlreadyExists,
                    format!("Migration destination is a file: {}", target_path.display()),
                ));
            }
            copy_data_dir(&source_path, &target_path)?;
        } else if file_type.is_file() {
            copy_file(&source_path, &target_path)?;
        } else {
            return Err(io::Error::other(format!(
                "Cannot migrate unsupported file type: {}",
                source_path.display()
            )));
        }
    }

    Ok(())
}

fn copy_file(source: &Path, target: &Path) -> io::Result<()> {
    if target.exists() {
        if files_match(source, target)? {
            return Ok(());
        }
        return Err(io::Error::new(
            io::ErrorKind::AlreadyExists,
            format!(
                "Migration would overwrite existing data: {}",
                target.display()
            ),
        ));
    }

    let temporary = target.with_extension(format!("murmur-migration-{}", std::process::id()));
    let result = (|| {
        let mut input = File::open(source)?;
        let mut output = OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&temporary)?;
        io::copy(&mut input, &mut output)?;
        output.flush()?;
        output.sync_all()?;
        fs::rename(&temporary, target)
    })();

    if result.is_err() {
        let _ = fs::remove_file(&temporary);
    }
    result
}

fn files_match(left: &Path, right: &Path) -> io::Result<bool> {
    if fs::metadata(left)?.len() != fs::metadata(right)?.len() {
        return Ok(false);
    }

    let mut left = File::open(left)?;
    let mut right = File::open(right)?;
    let mut left_chunk = [0; 8192];
    let mut right_chunk = [0; 8192];
    loop {
        let count = left.read(&mut left_chunk)?;
        if count == 0 {
            return Ok(true);
        }
        right.read_exact(&mut right_chunk[..count])?;
        if left_chunk[..count] != right_chunk[..count] {
            return Ok(false);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn migration_preserves_files_and_repeats_safely() {
        let root = std::env::temp_dir().join(format!("murmur-migration-{}", std::process::id()));
        let source = root.join("old");
        let target = root.join("new");
        fs::create_dir_all(source.join("recordings")).unwrap();
        fs::write(source.join("settings_store.json"), "settings").unwrap();
        fs::write(source.join("recordings/one.wav"), "audio").unwrap();

        copy_data_dir(&source, &target).unwrap();
        copy_data_dir(&source, &target).unwrap();
        assert_eq!(
            fs::read(target.join("settings_store.json")).unwrap(),
            b"settings"
        );
        assert_eq!(
            fs::read(target.join("recordings/one.wav")).unwrap(),
            b"audio"
        );
        assert!(source.join("settings_store.json").exists());

        fs::write(target.join("settings_store.json"), "new settings").unwrap();
        assert_eq!(
            copy_data_dir(&source, &target).unwrap_err().kind(),
            io::ErrorKind::AlreadyExists
        );
        assert_eq!(
            fs::read(target.join("settings_store.json")).unwrap(),
            b"new settings"
        );
        fs::remove_dir_all(root).unwrap();
    }
}
