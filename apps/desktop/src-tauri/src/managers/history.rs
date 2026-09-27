use anyhow::{anyhow, Context, Result};
use chrono::{DateTime, Datelike, Duration, Local, NaiveDate, Utc};
use flate2::{write::GzEncoder, Compression};
use log::{debug, error, info};
use rusqlite::{params, Connection, OptionalExtension};
use rusqlite_migration::{Migrations, M};
use serde::{Deserialize, Serialize};
use specta::Type;
use std::collections::BTreeMap;
use std::fs;
use std::io::{ErrorKind, Read, Seek, Write};
use std::path::{Component, Path, PathBuf};
use tauri::AppHandle;
use tauri_specta::Event;

/// Database migrations for transcription history.
/// Each migration is applied in order. The library tracks which migrations
/// have been applied using SQLite's user_version pragma.
///
/// Note: For users upgrading from tauri-plugin-sql, migrate_from_tauri_plugin_sql()
/// converts the old _sqlx_migrations table tracking to the user_version pragma,
/// ensuring migrations don't re-run on existing databases.
static MIGRATIONS: &[M] = &[
    M::up(
        "CREATE TABLE IF NOT EXISTS transcription_history (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            file_name TEXT NOT NULL,
            timestamp INTEGER NOT NULL,
            saved BOOLEAN NOT NULL DEFAULT 0,
            title TEXT NOT NULL,
            transcription_text TEXT NOT NULL
        );",
    ),
    M::up("ALTER TABLE transcription_history ADD COLUMN post_processed_text TEXT;"),
    M::up("ALTER TABLE transcription_history ADD COLUMN post_process_prompt TEXT;"),
    M::up("ALTER TABLE transcription_history ADD COLUMN post_process_requested BOOLEAN NOT NULL DEFAULT 0;"),
];

#[derive(Clone, Debug, Serialize, Deserialize, Type)]
pub struct PaginatedHistory {
    pub entries: Vec<HistoryEntry>,
    pub has_more: bool,
}

#[derive(Clone, Debug, Serialize, Deserialize, Type, tauri_specta::Event)]
#[serde(tag = "action")]
pub enum HistoryUpdatePayload {
    #[serde(rename = "added")]
    Added { entry: HistoryEntry },
    #[serde(rename = "updated")]
    Updated { entry: HistoryEntry },
    #[serde(rename = "deleted")]
    Deleted { id: i64 },
    #[serde(rename = "toggled")]
    Toggled { id: i64 },
}

#[derive(Clone, Debug, Serialize, Deserialize, Type)]
pub struct HistoryEntry {
    pub id: i64,
    pub file_name: String,
    pub timestamp: i64,
    pub saved: bool,
    pub title: String,
    pub transcription_text: String,
    pub post_processed_text: Option<String>,
    pub post_process_prompt: Option<String>,
    pub post_process_requested: bool,
}

#[derive(Clone, Debug, Default, Serialize, Type)]
pub struct ArchiveExportSummary {
    pub entries: u64,
    pub audio_included: u64,
    pub audio_missing: u64,
}

#[derive(Serialize)]
struct HistoryArchiveEntry<'a> {
    #[serde(flatten)]
    entry: &'a HistoryEntry,
    audio: HistoryArchiveAudio,
}

#[derive(Serialize)]
#[serde(tag = "status", rename_all = "snake_case")]
enum HistoryArchiveAudio {
    Included { archive_path: String, bytes: u64 },
    Missing,
}

#[derive(Clone, Debug, Serialize, Deserialize, Type)]
pub struct HistoryWeekStats {
    /// monday in the device's local timezone, formatted as YYYY-MM-DD
    pub week_start: String,
    pub words: u64,
    pub recording_seconds: f64,
    /// raw transcript words per minute of recorded audio, including silence
    pub recorded_wpm: Option<f64>,
}

#[derive(Clone, Debug, Serialize, Deserialize, Type)]
pub struct HistoryStats {
    /// words in the final text, after cleanup when available
    pub total_words: u64,
    pub transcription_count: u64,
    pub recordings_with_duration: u64,
    pub recording_seconds: f64,
    /// raw transcript words per minute of recorded audio, including silence
    pub recorded_wpm: Option<f64>,
    pub this_week_words: u64,
    pub previous_week_words: u64,
    /// current week to date compared with the full previous calendar week
    pub week_over_week_percent: Option<f64>,
    pub weeks: Vec<HistoryWeekStats>,
}

#[derive(Default)]
struct StatsBucket {
    words: u64,
    spoken_words_with_audio: u64,
    recording_seconds: f64,
}

impl StatsBucket {
    fn recorded_wpm(&self) -> Option<f64> {
        (self.recording_seconds > 0.0)
            .then(|| self.spoken_words_with_audio as f64 * 60.0 / self.recording_seconds)
    }
}

pub struct HistoryManager {
    app_handle: AppHandle,
    recordings_dir: PathBuf,
    db_path: PathBuf,
}

impl HistoryManager {
    pub fn new(app_handle: &AppHandle) -> Result<Self> {
        // Create recordings directory in app data dir
        let app_data_dir = crate::portable::app_data_dir(app_handle)?;
        let recordings_dir = app_data_dir.join("recordings");
        let db_path = app_data_dir.join("history.db");

        // Ensure recordings directory exists
        if !recordings_dir.exists() {
            fs::create_dir_all(&recordings_dir)?;
            debug!("Created recordings directory: {:?}", recordings_dir);
        }

        let manager = Self {
            app_handle: app_handle.clone(),
            recordings_dir,
            db_path,
        };

        // Initialize database and run migrations synchronously
        manager.init_database()?;

        Ok(manager)
    }

    fn init_database(&self) -> Result<()> {
        info!("Initializing database at {:?}", self.db_path);

        let mut conn = Connection::open(&self.db_path)?;

        // Handle migration from tauri-plugin-sql to rusqlite_migration
        // tauri-plugin-sql used _sqlx_migrations table, rusqlite_migration uses user_version pragma
        self.migrate_from_tauri_plugin_sql(&conn)?;

        // Create migrations object and run to latest version
        let migrations = Migrations::new(MIGRATIONS.to_vec());

        // Validate migrations in debug builds
        #[cfg(debug_assertions)]
        migrations.validate().expect("Invalid migrations");

        // Get current version before migration
        let version_before: i32 =
            conn.pragma_query_value(None, "user_version", |row| row.get(0))?;
        debug!("Database version before migration: {}", version_before);

        // Apply any pending migrations
        migrations.to_latest(&mut conn)?;

        // Get version after migration
        let version_after: i32 = conn.pragma_query_value(None, "user_version", |row| row.get(0))?;

        if version_after > version_before {
            info!(
                "Database migrated from version {} to {}",
                version_before, version_after
            );
        } else {
            debug!("Database already at latest version {}", version_after);
        }

        Ok(())
    }

    /// Migrate from tauri-plugin-sql's migration tracking to rusqlite_migration's.
    /// tauri-plugin-sql used a _sqlx_migrations table, while rusqlite_migration uses
    /// SQLite's user_version pragma. This function checks if the old system was in use
    /// and sets the user_version accordingly so migrations don't re-run.
    fn migrate_from_tauri_plugin_sql(&self, conn: &Connection) -> Result<()> {
        // Check if the old _sqlx_migrations table exists
        let has_sqlx_migrations: bool = conn
            .query_row(
                "SELECT COUNT(*) > 0 FROM sqlite_master WHERE type='table' AND name='_sqlx_migrations'",
                [],
                |row| row.get(0),
            )
            .unwrap_or(false);

        if !has_sqlx_migrations {
            return Ok(());
        }

        // Check current user_version
        let current_version: i32 =
            conn.pragma_query_value(None, "user_version", |row| row.get(0))?;

        if current_version > 0 {
            // Already migrated to rusqlite_migration system
            return Ok(());
        }

        // Get the highest version from the old migrations table
        let old_version: i32 = conn
            .query_row(
                "SELECT COALESCE(MAX(version), 0) FROM _sqlx_migrations WHERE success = 1",
                [],
                |row| row.get(0),
            )
            .unwrap_or(0);

        if old_version > 0 {
            info!(
                "Migrating from tauri-plugin-sql (version {}) to rusqlite_migration",
                old_version
            );

            // Set user_version to match the old migration state
            conn.pragma_update(None, "user_version", old_version)?;

            // Optionally drop the old migrations table (keeping it doesn't hurt)
            // conn.execute("DROP TABLE IF EXISTS _sqlx_migrations", [])?;

            info!(
                "Migration tracking converted: user_version set to {}",
                old_version
            );
        }

        Ok(())
    }

    fn get_connection(&self) -> Result<Connection> {
        Ok(Connection::open(&self.db_path)?)
    }

    fn map_history_entry(row: &rusqlite::Row<'_>) -> rusqlite::Result<HistoryEntry> {
        Ok(HistoryEntry {
            id: row.get("id")?,
            file_name: row.get("file_name")?,
            timestamp: row.get("timestamp")?,
            saved: row.get("saved")?,
            title: row.get("title")?,
            transcription_text: row.get("transcription_text")?,
            post_processed_text: row.get("post_processed_text")?,
            post_process_prompt: row.get("post_process_prompt")?,
            post_process_requested: row.get("post_process_requested")?,
        })
    }

    pub fn recordings_dir(&self) -> &std::path::Path {
        &self.recordings_dir
    }

    /// exports retained rows and unchanged WAV bytes without deleting originals
    ///
    /// the gzip-compressed tar contains manifest.json and recordings/<entry-id>.wav
    /// manifest version 1 preserves every HistoryEntry field, uses Unix-second
    /// timestamps, and marks audio as included with its path/size or missing
    ///
    /// JSON and audio stream through temporary files; only a finished archive is
    /// published, and an existing destination is never replaced. Returns row and
    /// audio counts so callers can distinguish complete and partial archives
    ///
    /// rows come from one SQLite read snapshot. Audio is checked as it is read,
    /// so retention can leave a row marked missing. File checks do not protect
    /// against a hostile process swapping files between inspection and opening
    pub fn export_archive(&self, destination: &Path) -> Result<ArchiveExportSummary> {
        let conn = self.get_connection()?;
        Self::export_archive_with_conn(&conn, &self.recordings_dir, destination)
    }

    fn export_archive_with_conn(
        conn: &Connection,
        recordings_dir: &Path,
        destination: &Path,
    ) -> Result<ArchiveExportSummary> {
        let recordings_metadata =
            fs::symlink_metadata(recordings_dir).context("Cannot access recordings directory")?;
        if !recordings_metadata.file_type().is_dir() {
            return Err(anyhow!("Recordings path is not a directory"));
        }
        if destination.file_name().is_none() {
            return Err(anyhow!("Choose a file path for the history archive"));
        }
        match fs::symlink_metadata(destination) {
            Ok(_) => return Err(anyhow!("The archive destination already exists")),
            Err(error) if error.kind() == ErrorKind::NotFound => {}
            Err(error) => return Err(error).context("Cannot inspect archive destination"),
        }

        let parent = destination
            .parent()
            .filter(|path| !path.as_os_str().is_empty())
            .unwrap_or_else(|| Path::new("."));
        let mut temporary = tempfile::Builder::new()
            .prefix(".murmur-history-")
            .suffix(".tmp")
            .tempfile_in(parent)
            .context("Cannot create temporary history archive")?;
        let mut manifest =
            tempfile::tempfile_in(parent).context("Cannot create temporary history manifest")?;
        let exported_at = Utc::now().timestamp();
        manifest.write_all(
            b"{\"format\":\"murmur-history\",\"version\":1,\"timestamp_unit\":\"unix_seconds\",\"exported_at\":",
        )?;
        serde_json::to_writer(&mut manifest, &exported_at)?;
        manifest.write_all(b",\"entries\":[")?;

        let encoder = GzEncoder::new(temporary.as_file_mut(), Compression::default());
        let mut archive = tar::Builder::new(encoder);
        let mut statement = conn.prepare(
            "SELECT id, file_name, timestamp, saved, title, transcription_text,
                    post_processed_text, post_process_prompt, post_process_requested
             FROM transcription_history ORDER BY id ASC",
        )?;
        let rows = statement.query_map([], Self::map_history_entry)?;
        let mut summary = ArchiveExportSummary::default();
        for row in rows {
            let entry = row?;
            let audio = match open_archive_audio(recordings_dir, &entry.file_name)
                .with_context(|| format!("Cannot export audio for history entry {}", entry.id))?
            {
                Some(mut file) => {
                    // archive paths use row IDs so source names never become members
                    let archive_path = format!("recordings/{}.wav", entry.id);
                    let bytes = append_archive_file(&mut archive, &archive_path, &mut file)
                        .with_context(|| format!("Cannot archive history entry {}", entry.id))?;
                    summary.audio_included += 1;
                    HistoryArchiveAudio::Included {
                        archive_path,
                        bytes,
                    }
                }
                None => {
                    summary.audio_missing += 1;
                    HistoryArchiveAudio::Missing
                }
            };
            if summary.entries > 0 {
                manifest.write_all(b",")?;
            }
            serde_json::to_writer(
                &mut manifest,
                &HistoryArchiveEntry {
                    entry: &entry,
                    audio,
                },
            )?;
            summary.entries += 1;
        }
        manifest.write_all(b"]}")?;
        manifest.rewind()?;
        append_archive_file(&mut archive, "manifest.json", &mut manifest)?;
        let encoder = archive
            .into_inner()
            .context("Cannot finish history tar archive")?;
        encoder
            .finish()
            .context("Cannot finish history gzip stream")?;
        temporary.as_file().sync_all()?;
        // persist_noclobber also refuses a destination created while exporting
        temporary
            .persist_noclobber(destination)
            .map_err(|error| anyhow!("Cannot publish history archive: {}", error.error))?;
        Ok(summary)
    }

    /// statistics reflect retained history, since deleted entries cannot contribute
    pub fn get_stats(&self) -> Result<HistoryStats> {
        let conn = self.get_connection()?;
        Self::get_stats_with_conn(&conn, &self.recordings_dir, Local::now().date_naive())
    }

    fn get_stats_with_conn(
        conn: &Connection,
        recordings_dir: &Path,
        today: NaiveDate,
    ) -> Result<HistoryStats> {
        let current_week_start =
            today - Duration::days(today.weekday().num_days_from_monday() as i64);
        let previous_week_start = current_week_start - Duration::days(7);
        let mut weeks: BTreeMap<NaiveDate, StatsBucket> = BTreeMap::new();
        let mut total = StatsBucket::default();
        let mut transcription_count = 0;
        let mut recordings_with_duration = 0;

        let mut stmt = conn.prepare(
            "SELECT file_name, timestamp, transcription_text, post_processed_text
             FROM transcription_history
             WHERE title NOT LIKE 'Lecture · %' AND title NOT LIKE 'Import · %'",
        )?;
        let rows = stmt.query_map([], |row| {
            Ok((
                row.get::<_, String>(0)?,
                row.get::<_, i64>(1)?,
                row.get::<_, String>(2)?,
                row.get::<_, Option<String>>(3)?,
            ))
        })?;

        for row in rows {
            let (file_name, timestamp, raw_text, processed_text) = row?;
            let final_text = processed_text.as_deref().unwrap_or(&raw_text);
            let words = count_words(final_text);
            if words == 0 {
                continue;
            }

            let Some(local_date) = DateTime::<Utc>::from_timestamp(timestamp, 0)
                .map(|time| time.with_timezone(&Local).date_naive())
            else {
                continue;
            };
            let week_start =
                local_date - Duration::days(local_date.weekday().num_days_from_monday() as i64);
            let week = weeks.entry(week_start).or_default();
            week.words += words;
            total.words += words;
            transcription_count += 1;

            // audio may be absent after manual cleanup or migration, so only read headers
            if let Some(seconds) = recording_duration_seconds(recordings_dir, &file_name) {
                let raw_words = count_words(&raw_text);
                if raw_words > 0 {
                    week.spoken_words_with_audio += raw_words;
                    week.recording_seconds += seconds;
                    total.spoken_words_with_audio += raw_words;
                    total.recording_seconds += seconds;
                    recordings_with_duration += 1;
                }
            }
        }

        let this_week_words = weeks.get(&current_week_start).map_or(0, |week| week.words);
        let previous_week_words = weeks.get(&previous_week_start).map_or(0, |week| week.words);
        let week_over_week_percent = (previous_week_words > 0).then(|| {
            (this_week_words as f64 - previous_week_words as f64) * 100.0
                / previous_week_words as f64
        });

        Ok(HistoryStats {
            total_words: total.words,
            transcription_count,
            recordings_with_duration,
            recording_seconds: total.recording_seconds,
            recorded_wpm: total.recorded_wpm(),
            this_week_words,
            previous_week_words,
            week_over_week_percent,
            weeks: weeks
                .into_iter()
                .map(|(week_start, bucket)| HistoryWeekStats {
                    week_start: week_start.to_string(),
                    words: bucket.words,
                    recording_seconds: bucket.recording_seconds,
                    recorded_wpm: bucket.recorded_wpm(),
                })
                .collect(),
        })
    }

    /// Save a new history entry to the database.
    /// The WAV file should already have been written to the recordings directory.
    pub fn save_entry(
        &self,
        file_name: String,
        transcription_text: String,
        post_process_requested: bool,
        post_processed_text: Option<String>,
        post_process_prompt: Option<String>,
    ) -> Result<HistoryEntry> {
        let timestamp = Utc::now().timestamp();
        let title = self.format_timestamp_title(timestamp);

        let conn = self.get_connection()?;
        conn.execute(
            "INSERT INTO transcription_history (
                file_name,
                timestamp,
                saved,
                title,
                transcription_text,
                post_processed_text,
                post_process_prompt,
                post_process_requested
            ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)",
            params![
                &file_name,
                timestamp,
                false,
                &title,
                &transcription_text,
                &post_processed_text,
                &post_process_prompt,
                post_process_requested,
            ],
        )?;

        let entry = HistoryEntry {
            id: conn.last_insert_rowid(),
            file_name,
            timestamp,
            saved: false,
            title,
            transcription_text,
            post_processed_text,
            post_process_prompt,
            post_process_requested,
        };

        debug!("Saved history entry with id {}", entry.id);

        self.cleanup_old_entries()?;

        // Emit typed event for real-time frontend updates
        if let Err(e) = (HistoryUpdatePayload::Added {
            entry: entry.clone(),
        })
        .emit(&self.app_handle)
        {
            error!("Failed to emit history-updated event: {}", e);
        }

        Ok(entry)
    }

    pub fn save_long_entry(&self, file_name: String, title: String) -> Result<HistoryEntry> {
        let timestamp = Utc::now().timestamp();
        let conn = self.get_connection()?;
        conn.execute(
            "INSERT INTO transcription_history (file_name, timestamp, saved, title, transcription_text, post_process_requested) VALUES (?1, ?2, 0, ?3, '', 0)",
            params![&file_name, timestamp, &title],
        )?;
        let entry = HistoryEntry {
            id: conn.last_insert_rowid(),
            file_name,
            timestamp,
            saved: false,
            title,
            transcription_text: String::new(),
            post_processed_text: None,
            post_process_prompt: None,
            post_process_requested: false,
        };
        if let Err(error) = (HistoryUpdatePayload::Added {
            entry: entry.clone(),
        })
        .emit(&self.app_handle)
        {
            log::error!("Failed to emit history event: {error}");
        }
        Ok(entry)
    }

    /// Update an existing history entry with new transcription results (used by retry).
    pub fn update_transcription(
        &self,
        id: i64,
        transcription_text: String,
        post_processed_text: Option<String>,
        post_process_prompt: Option<String>,
    ) -> Result<HistoryEntry> {
        let conn = self.get_connection()?;
        let updated = conn.execute(
            "UPDATE transcription_history
             SET transcription_text = ?1,
                 post_processed_text = ?2,
                 post_process_prompt = ?3
             WHERE id = ?4",
            params![
                transcription_text,
                post_processed_text,
                post_process_prompt,
                id
            ],
        )?;

        if updated == 0 {
            return Err(anyhow!("History entry {} not found", id));
        }

        let entry = conn
            .query_row(
                "SELECT id, file_name, timestamp, saved, title, transcription_text, post_processed_text, post_process_prompt, post_process_requested
                 FROM transcription_history WHERE id = ?1",
                params![id],
                Self::map_history_entry,
            )?;

        debug!("Updated transcription for history entry {}", id);

        if let Err(e) = (HistoryUpdatePayload::Updated {
            entry: entry.clone(),
        })
        .emit(&self.app_handle)
        {
            error!("Failed to emit history-updated event: {}", e);
        }

        Ok(entry)
    }

    pub fn cleanup_old_entries(&self) -> Result<()> {
        let retention_period = crate::settings::get_recording_retention_period(&self.app_handle);

        match retention_period {
            crate::settings::RecordingRetentionPeriod::Never => {
                // Don't delete anything
                Ok(())
            }
            crate::settings::RecordingRetentionPeriod::PreserveLimit => {
                // Use the old count-based logic with history_limit
                let limit = crate::settings::get_history_limit(&self.app_handle);
                self.cleanup_by_count(limit)
            }
            _ => {
                // Use time-based logic
                self.cleanup_by_time(retention_period)
            }
        }
    }

    fn delete_entries_and_files(&self, entries: &[(i64, String)]) -> Result<usize> {
        if entries.is_empty() {
            return Ok(0);
        }

        let conn = self.get_connection()?;
        let mut deleted_count = 0;

        for (id, file_name) in entries {
            // Delete database entry
            conn.execute(
                "DELETE FROM transcription_history WHERE id = ?1",
                params![id],
            )?;

            // Delete WAV file
            let file_path = self.recordings_dir.join(file_name);
            if file_path.exists() {
                if let Err(e) = fs::remove_file(&file_path) {
                    error!("Failed to delete WAV file {}: {}", file_name, e);
                } else {
                    debug!("Deleted old WAV file: {}", file_name);
                    deleted_count += 1;
                }
            }
        }

        Ok(deleted_count)
    }

    fn cleanup_by_count(&self, limit: usize) -> Result<()> {
        let conn = self.get_connection()?;

        // Get all entries that are not saved, ordered by timestamp desc
        let mut stmt = conn.prepare(
            "SELECT id, file_name FROM transcription_history WHERE saved = 0 ORDER BY timestamp DESC"
        )?;

        let rows = stmt.query_map([], |row| {
            Ok((row.get::<_, i64>("id")?, row.get::<_, String>("file_name")?))
        })?;

        let mut entries: Vec<(i64, String)> = Vec::new();
        for row in rows {
            entries.push(row?);
        }

        if entries.len() > limit {
            let entries_to_delete = &entries[limit..];
            let deleted_count = self.delete_entries_and_files(entries_to_delete)?;

            if deleted_count > 0 {
                debug!("Cleaned up {} old history entries by count", deleted_count);
            }
        }

        Ok(())
    }

    fn cleanup_by_time(
        &self,
        retention_period: crate::settings::RecordingRetentionPeriod,
    ) -> Result<()> {
        let conn = self.get_connection()?;

        // Calculate cutoff timestamp (current time minus retention period)
        let now = Utc::now().timestamp();
        let cutoff_timestamp = match retention_period {
            crate::settings::RecordingRetentionPeriod::Days3 => now - (3 * 24 * 60 * 60), // 3 days in seconds
            crate::settings::RecordingRetentionPeriod::Weeks2 => now - (2 * 7 * 24 * 60 * 60), // 2 weeks in seconds
            crate::settings::RecordingRetentionPeriod::Months3 => now - (3 * 30 * 24 * 60 * 60), // 3 months in seconds (approximate)
            _ => unreachable!("Should not reach here"),
        };

        // Get all unsaved entries older than the cutoff timestamp
        let mut stmt = conn.prepare(
            "SELECT id, file_name FROM transcription_history WHERE saved = 0 AND timestamp < ?1",
        )?;

        let rows = stmt.query_map(params![cutoff_timestamp], |row| {
            Ok((row.get::<_, i64>("id")?, row.get::<_, String>("file_name")?))
        })?;

        let mut entries_to_delete: Vec<(i64, String)> = Vec::new();
        for row in rows {
            entries_to_delete.push(row?);
        }

        let deleted_count = self.delete_entries_and_files(&entries_to_delete)?;

        if deleted_count > 0 {
            debug!(
                "Cleaned up {} old history entries based on retention period",
                deleted_count
            );
        }

        Ok(())
    }

    pub async fn get_history_entries(
        &self,
        cursor: Option<i64>,
        limit: Option<usize>,
    ) -> Result<PaginatedHistory> {
        let conn = self.get_connection()?;
        let limit = limit.map(|l| l.min(100));

        let mut entries: Vec<HistoryEntry> = match (cursor, limit) {
            (Some(cursor_id), Some(lim)) => {
                let fetch_count = (lim + 1) as i64;
                let mut stmt = conn.prepare(
                    "SELECT id, file_name, timestamp, saved, title, transcription_text, post_processed_text, post_process_prompt, post_process_requested
                     FROM transcription_history
                     WHERE id < ?1
                     ORDER BY id DESC
                     LIMIT ?2",
                )?;
                let result = stmt
                    .query_map(params![cursor_id, fetch_count], Self::map_history_entry)?
                    .collect::<std::result::Result<Vec<_>, _>>()?;
                result
            }
            (None, Some(lim)) => {
                let fetch_count = (lim + 1) as i64;
                let mut stmt = conn.prepare(
                    "SELECT id, file_name, timestamp, saved, title, transcription_text, post_processed_text, post_process_prompt, post_process_requested
                     FROM transcription_history
                     ORDER BY id DESC
                     LIMIT ?1",
                )?;
                let result = stmt
                    .query_map(params![fetch_count], Self::map_history_entry)?
                    .collect::<std::result::Result<Vec<_>, _>>()?;
                result
            }
            (_, None) => {
                let mut stmt = conn.prepare(
                    "SELECT id, file_name, timestamp, saved, title, transcription_text, post_processed_text, post_process_prompt, post_process_requested
                     FROM transcription_history
                     ORDER BY id DESC",
                )?;
                let result = stmt
                    .query_map([], Self::map_history_entry)?
                    .collect::<std::result::Result<Vec<_>, _>>()?;
                result
            }
        };

        let has_more = limit.is_some_and(|lim| entries.len() > lim);
        if has_more {
            entries.pop();
        }

        Ok(PaginatedHistory { entries, has_more })
    }

    #[cfg(test)]
    fn get_latest_entry_with_conn(conn: &Connection) -> Result<Option<HistoryEntry>> {
        let mut stmt = conn.prepare(
            "SELECT
                id,
                file_name,
                timestamp,
                saved,
                title,
                transcription_text,
                post_processed_text,
                post_process_prompt,
                post_process_requested
             FROM transcription_history
             ORDER BY timestamp DESC
             LIMIT 1",
        )?;

        let entry = stmt.query_row([], Self::map_history_entry).optional()?;
        Ok(entry)
    }

    /// Get the latest entry with non-empty transcription text.
    pub fn get_latest_completed_entry(&self) -> Result<Option<HistoryEntry>> {
        let conn = self.get_connection()?;
        Self::get_latest_completed_entry_with_conn(&conn)
    }

    fn get_latest_completed_entry_with_conn(conn: &Connection) -> Result<Option<HistoryEntry>> {
        let mut stmt = conn.prepare(
            "SELECT
                id,
                file_name,
                timestamp,
                saved,
                title,
                transcription_text,
                post_processed_text,
                post_process_prompt,
                post_process_requested
             FROM transcription_history
             WHERE transcription_text != ''
               AND title NOT LIKE 'Lecture · %' AND title NOT LIKE 'Import · %'
             ORDER BY timestamp DESC
             LIMIT 1",
        )?;

        let entry = stmt.query_row([], Self::map_history_entry).optional()?;
        Ok(entry)
    }

    pub async fn toggle_saved_status(&self, id: i64) -> Result<()> {
        let conn = self.get_connection()?;

        // Get current saved status
        let current_saved: bool = conn.query_row(
            "SELECT saved FROM transcription_history WHERE id = ?1",
            params![id],
            |row| row.get("saved"),
        )?;

        let new_saved = !current_saved;

        conn.execute(
            "UPDATE transcription_history SET saved = ?1 WHERE id = ?2",
            params![new_saved, id],
        )?;

        debug!("Toggled saved status for entry {}: {}", id, new_saved);

        // Emit history updated event
        if let Err(e) = (HistoryUpdatePayload::Toggled { id }).emit(&self.app_handle) {
            error!("Failed to emit history-updated event: {}", e);
        }

        Ok(())
    }

    pub fn get_audio_file_path(&self, file_name: &str) -> PathBuf {
        self.recordings_dir.join(file_name)
    }

    pub async fn get_entry_by_id(&self, id: i64) -> Result<Option<HistoryEntry>> {
        let conn = self.get_connection()?;
        let mut stmt = conn.prepare(
            "SELECT
                id,
                file_name,
                timestamp,
                saved,
                title,
                transcription_text,
                post_processed_text,
                post_process_prompt,
                post_process_requested
             FROM transcription_history
             WHERE id = ?1",
        )?;

        let entry = stmt.query_row([id], Self::map_history_entry).optional()?;

        Ok(entry)
    }

    pub async fn delete_entry(&self, id: i64) -> Result<()> {
        let conn = self.get_connection()?;

        // Get the entry to find the file name
        if let Some(entry) = self.get_entry_by_id(id).await? {
            // Delete the audio file first
            let file_path = self.get_audio_file_path(&entry.file_name);
            if file_path.exists() {
                if let Err(e) = fs::remove_file(&file_path) {
                    error!("Failed to delete audio file {}: {}", entry.file_name, e);
                    // Continue with database deletion even if file deletion fails
                }
            }
        }

        // Delete from database
        conn.execute(
            "DELETE FROM transcription_history WHERE id = ?1",
            params![id],
        )?;

        debug!("Deleted history entry with id: {}", id);

        // Emit history updated event
        if let Err(e) = (HistoryUpdatePayload::Deleted { id }).emit(&self.app_handle) {
            error!("Failed to emit history-updated event: {}", e);
        }

        Ok(())
    }

    fn format_timestamp_title(&self, timestamp: i64) -> String {
        if let Some(utc_datetime) = DateTime::from_timestamp(timestamp, 0) {
            // Convert UTC to local timezone
            let local_datetime = utc_datetime.with_timezone(&Local);
            local_datetime.format("%B %e, %Y - %l:%M%p").to_string()
        } else {
            format!("Recording {}", timestamp)
        }
    }
}

fn open_archive_audio(recordings_dir: &Path, file_name: &str) -> Result<Option<fs::File>> {
    let mut components = Path::new(file_name).components();
    if !matches!(
        (components.next(), components.next()),
        (Some(Component::Normal(_)), None)
    ) || file_name
        .chars()
        .any(|character| character.is_control() || "/\\:<>\"|?*".contains(character))
        || !Path::new(file_name)
            .extension()
            .and_then(|extension| extension.to_str())
            .is_some_and(|extension| extension.eq_ignore_ascii_case("wav"))
    {
        return Err(anyhow!("Recording filename is not a safe WAV basename"));
    }

    let source = recordings_dir.join(file_name);
    let metadata = match fs::symlink_metadata(&source) {
        Ok(metadata) => metadata,
        Err(error) if error.kind() == ErrorKind::NotFound => return Ok(None),
        Err(error) => return Err(error.into()),
    };
    if !metadata.file_type().is_file() {
        return Err(anyhow!(
            "Recording must be a regular file, not a link or directory"
        ));
    }
    let file = match fs::File::open(&source) {
        Ok(file) => file,
        Err(error) if error.kind() == ErrorKind::NotFound => return Ok(None),
        Err(error) => return Err(error.into()),
    };
    if !file.metadata()?.is_file() {
        return Err(anyhow!(
            "Recording changed while opening the archive source"
        ));
    }
    Ok(Some(file))
}

fn append_archive_file<W: Write>(
    archive: &mut tar::Builder<W>,
    member: &str,
    file: &mut fs::File,
) -> Result<u64> {
    let size = file.metadata()?.len();
    let mut header = tar::Header::new_gnu();
    header.set_entry_type(tar::EntryType::Regular);
    header.set_mode(0o600);
    header.set_uid(0);
    header.set_gid(0);
    header.set_mtime(0);
    header.set_size(size);
    let mut contents = file.take(size);
    archive.append_data(&mut header, member, &mut contents)?;
    if contents.limit() != 0 || contents.get_ref().metadata()?.len() != size {
        return Err(anyhow!("Archive source changed size while reading"));
    }
    Ok(size)
}

fn count_words(text: &str) -> u64 {
    text.split_whitespace()
        .filter(|part| part.chars().any(char::is_alphanumeric))
        .count() as u64
}

fn recording_duration_seconds(recordings_dir: &Path, file_name: &str) -> Option<f64> {
    let mut components = Path::new(file_name).components();
    if !matches!(
        (components.next(), components.next()),
        (Some(Component::Normal(_)), None)
    ) {
        return None;
    }

    let reader = hound::WavReader::open(recordings_dir.join(file_name)).ok()?;
    let sample_rate = reader.spec().sample_rate;
    (sample_rate > 0 && reader.duration() > 0)
        .then(|| reader.duration() as f64 / sample_rate as f64)
}

#[cfg(test)]
mod tests {
    use super::*;
    use chrono::TimeZone;
    use hound::{SampleFormat, WavSpec, WavWriter};
    use rusqlite::{params, Connection};

    fn setup_conn() -> Connection {
        let conn = Connection::open_in_memory().expect("open in-memory db");
        conn.execute_batch(
            "CREATE TABLE transcription_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                file_name TEXT NOT NULL,
                timestamp INTEGER NOT NULL,
                saved BOOLEAN NOT NULL DEFAULT 0,
                title TEXT NOT NULL,
                transcription_text TEXT NOT NULL,
                post_processed_text TEXT,
                post_process_prompt TEXT,
                post_process_requested BOOLEAN NOT NULL DEFAULT 0
            );",
        )
        .expect("create transcription_history table");
        conn
    }

    fn insert_entry(conn: &Connection, timestamp: i64, text: &str, post_processed: Option<&str>) {
        conn.execute(
            "INSERT INTO transcription_history (
                file_name,
                timestamp,
                saved,
                title,
                transcription_text,
                post_processed_text,
                post_process_prompt,
                post_process_requested
            ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)",
            params![
                format!("handy-{}.wav", timestamp),
                timestamp,
                false,
                format!("Recording {}", timestamp),
                text,
                post_processed,
                Option::<String>::None,
                false,
            ],
        )
        .expect("insert history entry");
    }

    #[test]
    fn get_latest_entry_returns_none_when_empty() {
        let conn = setup_conn();
        let entry = HistoryManager::get_latest_entry_with_conn(&conn).expect("fetch latest entry");
        assert!(entry.is_none());
    }

    #[test]
    fn get_latest_entry_returns_newest_entry() {
        let conn = setup_conn();
        insert_entry(&conn, 100, "first", None);
        insert_entry(&conn, 200, "second", Some("processed"));

        let entry = HistoryManager::get_latest_entry_with_conn(&conn)
            .expect("fetch latest entry")
            .expect("entry exists");

        assert_eq!(entry.timestamp, 200);
        assert_eq!(entry.transcription_text, "second");
        assert_eq!(entry.post_processed_text.as_deref(), Some("processed"));
    }

    #[test]
    fn get_latest_completed_entry_skips_empty_entries() {
        let conn = setup_conn();
        insert_entry(&conn, 100, "completed", None);
        insert_entry(&conn, 200, "", None);

        let entry = HistoryManager::get_latest_completed_entry_with_conn(&conn)
            .expect("fetch latest completed entry")
            .expect("completed entry exists");

        assert_eq!(entry.timestamp, 100);
        assert_eq!(entry.transcription_text, "completed");
    }

    fn local_noon_timestamp(year: i32, month: u32, day: u32) -> i64 {
        let date = NaiveDate::from_ymd_opt(year, month, day).expect("valid date");
        Local
            .from_local_datetime(&date.and_hms_opt(12, 0, 0).expect("valid time"))
            .single()
            .expect("local noon")
            .timestamp()
    }

    fn write_wav(path: &Path, seconds: u32) {
        let spec = WavSpec {
            channels: 1,
            sample_rate: 16_000,
            bits_per_sample: 16,
            sample_format: SampleFormat::Int,
        };
        let mut writer = WavWriter::create(path, spec).expect("create wav");
        for _ in 0..(seconds * spec.sample_rate) {
            writer.write_sample(0_i16).expect("write sample");
        }
        writer.finalize().expect("finalize wav");
    }

    fn read_archive(path: &Path) -> BTreeMap<String, Vec<u8>> {
        let file = fs::File::open(path).expect("open history archive");
        let decoder = flate2::read::GzDecoder::new(file);
        let mut archive = tar::Archive::new(decoder);
        archive
            .entries()
            .expect("read tar entries")
            .map(|member| {
                let mut member = member.expect("read tar member");
                assert!(member.header().entry_type().is_file());
                let path = member
                    .path()
                    .expect("member path")
                    .to_str()
                    .expect("UTF-8 member path")
                    .to_owned();
                let mut bytes = Vec::new();
                member
                    .read_to_end(&mut bytes)
                    .expect("read member contents");
                (path, bytes)
            })
            .collect()
    }

    #[test]
    fn archive_preserves_all_history_fields_and_wav_bytes_and_marks_missing_audio() {
        let conn = setup_conn();
        let directory = tempfile::tempdir().expect("temporary export directory");
        let recordings = directory.path().join("recordings");
        fs::create_dir(&recordings).expect("create recordings directory");
        let raw = "um, Semyon said \"hello\"\nDia duit";
        insert_entry(&conn, 100, raw, Some("Semyon said hello.\nDia duit."));
        insert_entry(&conn, 200, "", None);
        conn.execute(
            "UPDATE transcription_history SET saved = 1, post_process_requested = 1,
             post_process_prompt = ?1 WHERE id = 1",
            ["preserve names, quotations and Unicode: é"],
        )
        .expect("set optional history fields");
        let wav = recordings.join("handy-100.wav");
        write_wav(&wav, 1);
        let original = fs::read(&wav).expect("read original WAV");
        let destination = directory.path().join("history.tar.gz");

        let summary = HistoryManager::export_archive_with_conn(&conn, &recordings, &destination)
            .expect("export history");

        assert_eq!(summary.entries, 2);
        assert_eq!(summary.audio_included, 1);
        assert_eq!(summary.audio_missing, 1);
        let members = read_archive(&destination);
        assert_eq!(members.len(), 2);
        assert_eq!(members["recordings/1.wav"], original);
        let manifest: serde_json::Value =
            serde_json::from_slice(&members["manifest.json"]).expect("valid JSON manifest");
        assert_eq!(manifest["version"], 1);
        assert_eq!(manifest["timestamp_unit"], "unix_seconds");
        let entries = manifest["entries"].as_array().expect("manifest entries");
        assert_eq!(entries.len(), 2);
        for exported in entries {
            let entry = conn
                .query_row(
                    "SELECT * FROM transcription_history WHERE id = ?1",
                    [exported["id"].as_i64().expect("entry ID")],
                    HistoryManager::map_history_entry,
                )
                .expect("original retained entry");
            let expected = serde_json::to_value(entry).expect("serialize expected entry");
            for (key, value) in expected.as_object().expect("entry object") {
                assert_eq!(&exported[key], value, "field {key} must be lossless");
            }
        }
        assert_eq!(entries[0]["audio"]["status"], "included");
        assert_eq!(entries[0]["audio"]["archive_path"], "recordings/1.wav");
        assert_eq!(entries[0]["audio"]["bytes"], original.len() as u64);
        assert_eq!(entries[1]["audio"]["status"], "missing");
        assert!(entries[1]["audio"].get("archive_path").is_none());
        assert_eq!(fs::read(wav).expect("original WAV remains"), original);
        assert_eq!(
            fs::read_dir(directory.path())
                .expect("directory contents")
                .count(),
            2
        );
    }

    #[test]
    fn archive_refuses_to_overwrite_existing_destination() {
        let conn = setup_conn();
        let directory = tempfile::tempdir().expect("temporary export directory");
        let destination = directory.path().join("history.tar.gz");
        fs::write(&destination, b"keep this existing file").expect("write existing destination");

        assert!(
            HistoryManager::export_archive_with_conn(&conn, directory.path(), &destination)
                .is_err()
        );
        assert_eq!(
            fs::read(destination).expect("existing destination remains"),
            b"keep this existing file"
        );
        assert_eq!(
            fs::read_dir(directory.path())
                .expect("directory contents")
                .count(),
            1
        );
    }

    #[test]
    fn archive_rejects_unsafe_source_names_and_removes_partial_output() {
        let conn = setup_conn();
        let directory = tempfile::tempdir().expect("temporary export directory");
        insert_entry(&conn, 100, "retained transcript", None);
        let destination = directory.path().join("history.tar.gz");
        for name in [
            "../outside.wav",
            "sub/audio.wav",
            "..\\outside.wav",
            "C:\\outside.wav",
            "audio.wav:stream",
            "",
            ".",
            "audio.txt",
        ] {
            conn.execute("UPDATE transcription_history SET file_name = ?1", [name])
                .expect("set unsafe source name");
            assert!(
                HistoryManager::export_archive_with_conn(&conn, directory.path(), &destination)
                    .is_err(),
                "accepted {name:?}"
            );
            assert!(!destination.exists());
            assert_eq!(
                fs::read_dir(directory.path())
                    .expect("directory contents")
                    .count(),
                0
            );
        }
        assert_eq!(
            conn.query_row("SELECT COUNT(*) FROM transcription_history", [], |row| row
                .get::<_, i64>(
                0
            ))
            .expect("retained row count"),
            1
        );
    }

    #[cfg(unix)]
    #[test]
    fn archive_rejects_recording_symlinks() {
        let conn = setup_conn();
        let directory = tempfile::tempdir().expect("temporary export directory");
        let recordings = directory.path().join("recordings");
        fs::create_dir(&recordings).expect("create recordings directory");
        let outside = directory.path().join("outside.wav");
        fs::write(&outside, b"must not enter the archive").expect("write outside file");
        std::os::unix::fs::symlink(&outside, recordings.join("handy-100.wav"))
            .expect("create recording symlink");
        insert_entry(&conn, 100, "retained transcript", None);
        let destination = directory.path().join("history.tar.gz");

        assert!(
            HistoryManager::export_archive_with_conn(&conn, &recordings, &destination).is_err()
        );
        assert!(!destination.exists());
        assert_eq!(
            fs::read(outside).expect("outside file remains"),
            b"must not enter the archive"
        );
        assert_eq!(
            fs::read_dir(directory.path())
                .expect("directory contents")
                .count(),
            2
        );
    }

    #[test]
    fn archive_handles_empty_retained_history() {
        let conn = setup_conn();
        let directory = tempfile::tempdir().expect("temporary export directory");
        let destination = directory.path().join("history.tar.gz");

        let summary =
            HistoryManager::export_archive_with_conn(&conn, directory.path(), &destination)
                .expect("export empty history");
        assert_eq!(summary.entries, 0);
        assert_eq!(summary.audio_included, 0);
        assert_eq!(summary.audio_missing, 0);
        let members = read_archive(&destination);
        assert_eq!(members.len(), 1);
        let manifest: serde_json::Value =
            serde_json::from_slice(&members["manifest.json"]).expect("valid manifest");
        assert_eq!(manifest["entries"], serde_json::json!([]));
    }

    #[test]
    fn archive_refuses_missing_recordings_directory() {
        let conn = setup_conn();
        insert_entry(&conn, 100, "retained transcript", None);
        let directory = tempfile::tempdir().expect("temporary export directory");
        let recordings = directory.path().join("missing-recordings");
        let destination = directory.path().join("history.tar.gz");

        let error = HistoryManager::export_archive_with_conn(&conn, &recordings, &destination)
            .expect_err("missing recordings directory must fail export");

        assert!(error
            .to_string()
            .contains("Cannot access recordings directory"));
        assert!(!destination.exists());
        assert_eq!(
            fs::read_dir(directory.path())
                .expect("directory contents")
                .count(),
            0
        );
    }

    #[test]
    fn archive_refuses_regular_file_as_recordings_directory() {
        let conn = setup_conn();
        let directory = tempfile::tempdir().expect("temporary export directory");
        let recordings = directory.path().join("recordings");
        fs::write(&recordings, b"preserve this file").expect("write invalid recordings path");
        let destination = directory.path().join("history.tar.gz");

        let error = HistoryManager::export_archive_with_conn(&conn, &recordings, &destination)
            .expect_err("non-directory recordings path must fail export");

        assert!(error.to_string().contains("not a directory"));
        assert!(!destination.exists());
        assert_eq!(
            fs::read(recordings).expect("original file remains"),
            b"preserve this file"
        );
        assert_eq!(
            fs::read_dir(directory.path())
                .expect("directory contents")
                .count(),
            1
        );
    }

    #[cfg(unix)]
    #[test]
    fn archive_refuses_symlink_as_recordings_directory() {
        let conn = setup_conn();
        let directory = tempfile::tempdir().expect("temporary export directory");
        let outside = tempfile::tempdir().expect("outside recordings directory");
        let recordings = directory.path().join("recordings");
        std::os::unix::fs::symlink(outside.path(), &recordings)
            .expect("create redirected recordings directory");
        let destination = directory.path().join("history.tar.gz");

        let error = HistoryManager::export_archive_with_conn(&conn, &recordings, &destination)
            .expect_err("redirected recordings directory must fail export");

        assert!(error.to_string().contains("not a directory"));
        assert!(!destination.exists());
        assert_eq!(
            fs::read_dir(directory.path())
                .expect("directory contents")
                .count(),
            1
        );
    }

    #[test]
    fn stats_count_final_words_and_use_raw_words_for_recorded_wpm() {
        let conn = setup_conn();
        let recordings = tempfile::tempdir().expect("temporary recordings directory");
        let today = NaiveDate::from_ymd_opt(2026, 9, 26).expect("valid date");
        let this_week = local_noon_timestamp(2026, 9, 22);
        let previous_week = local_noon_timestamp(2026, 9, 15);
        insert_entry(
            &conn,
            this_week,
            "hello world",
            Some("Hello, world and friends!"),
        );
        insert_entry(&conn, previous_week, "one two three four", None);
        insert_entry(&conn, local_noon_timestamp(2026, 9, 23), "", None);
        write_wav(&recordings.path().join(format!("handy-{this_week}.wav")), 1);
        write_wav(
            &recordings.path().join(format!("handy-{previous_week}.wav")),
            2,
        );

        let stats = HistoryManager::get_stats_with_conn(&conn, recordings.path(), today)
            .expect("history stats");
        assert_eq!(stats.total_words, 8);
        assert_eq!(stats.transcription_count, 2);
        assert_eq!(stats.recordings_with_duration, 2);
        assert_eq!(stats.this_week_words, 4);
        assert_eq!(stats.previous_week_words, 4);
        assert_eq!(stats.week_over_week_percent, Some(0.0));
        assert_eq!(stats.recording_seconds, 3.0);
        assert_eq!(stats.recorded_wpm, Some(120.0));
        assert_eq!(stats.weeks.len(), 2);
        assert_eq!(stats.weeks[1].week_start, "2026-09-21");
        assert_eq!(stats.weeks[1].recorded_wpm, Some(120.0));

        conn.execute(
            "DELETE FROM transcription_history WHERE timestamp = ?1",
            [previous_week],
        )
        .expect("delete older entry");
        let stats = HistoryManager::get_stats_with_conn(&conn, recordings.path(), today)
            .expect("history stats after deletion");
        assert_eq!(stats.total_words, 4);
        assert_eq!(stats.previous_week_words, 0);
        assert_eq!(stats.week_over_week_percent, None);
    }

    #[test]
    fn stats_skip_missing_audio_and_invalid_file_names() {
        let conn = setup_conn();
        let recordings = tempfile::tempdir().expect("temporary recordings directory");
        let today = NaiveDate::from_ymd_opt(2026, 9, 26).expect("valid date");
        let timestamp = local_noon_timestamp(2026, 9, 22);
        insert_entry(&conn, timestamp, "hello world", None);
        let stats = HistoryManager::get_stats_with_conn(&conn, recordings.path(), today)
            .expect("history stats");
        assert_eq!(stats.total_words, 2);
        assert_eq!(stats.recordings_with_duration, 0);
        assert_eq!(stats.recorded_wpm, None);
        assert_eq!(stats.recording_seconds, 0.0);
        assert_eq!(
            recording_duration_seconds(recordings.path(), "../private.wav"),
            None
        );
    }

    #[test]
    fn word_count_ignores_standalone_punctuation() {
        assert_eq!(count_words("  hello,   world! -- 42  "), 3);
        assert_eq!(count_words("... --"), 0);
    }

    #[test]
    fn empty_final_text_does_not_count_as_dictation() {
        let conn = setup_conn();
        let recordings = tempfile::tempdir().expect("temporary recordings directory");
        let timestamp = local_noon_timestamp(2026, 9, 22);
        insert_entry(&conn, timestamp, "raw words", Some(""));
        let stats = HistoryManager::get_stats_with_conn(
            &conn,
            recordings.path(),
            NaiveDate::from_ymd_opt(2026, 9, 26).expect("valid date"),
        )
        .expect("history stats");
        assert_eq!(stats.total_words, 0);
        assert_eq!(stats.transcription_count, 0);
        assert_eq!(stats.recorded_wpm, None);
    }
}
