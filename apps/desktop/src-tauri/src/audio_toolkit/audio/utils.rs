use anyhow::Result;
use hound::{WavReader, WavSpec, WavWriter};
use log::debug;
use std::path::Path;

/// Read a WAV file and return normalised f32 samples.
pub fn read_wav_samples<P: AsRef<Path>>(file_path: P) -> Result<Vec<f32>> {
    let reader = WavReader::open(file_path.as_ref())?;
    let samples = reader
        .into_samples::<i16>()
        .map(|s| s.map(|v| v as f32 / i16::MAX as f32))
        .collect::<Result<Vec<f32>, _>>()?;
    Ok(samples)
}

/// Verify a WAV file by reading it back and checking the sample count.
pub fn verify_wav_file<P: AsRef<Path>>(file_path: P, expected_samples: usize) -> Result<()> {
    let reader = WavReader::open(file_path.as_ref())?;
    let actual_samples = reader.len() as usize;
    if actual_samples != expected_samples {
        anyhow::bail!(
            "WAV sample count mismatch: expected {}, got {}",
            expected_samples,
            actual_samples
        );
    }
    Ok(())
}

/// Save audio samples as a WAV file
pub fn save_wav_file<P: AsRef<Path>>(file_path: P, samples: &[f32]) -> Result<()> {
    let spec = WavSpec {
        channels: 1,
        sample_rate: 16000,
        bits_per_sample: 16,
        sample_format: hound::SampleFormat::Int,
    };

    let mut writer = WavWriter::create(file_path.as_ref(), spec)?;

    // Convert f32 samples to i16 for WAV
    for sample in samples {
        let sample_i16 = (sample * i16::MAX as f32) as i16;
        writer.write_sample(sample_i16)?;
    }

    writer.finalize()?;
    debug!("Saved WAV file: {:?}", file_path.as_ref());
    Ok(())
}

/// Where to cut 16 kHz audio so a word is not split: the middle of the
/// quietest 20 ms within the last `search` samples.
pub fn quietest_cut(samples: &[f32], search: usize) -> usize {
    const FRAME: usize = 320;
    let mut cut = samples.len();
    let mut quietest = f32::MAX;
    let earliest = samples.len().saturating_sub(search).max(FRAME);
    let mut frame = samples.len().saturating_sub(FRAME);
    while frame >= earliest {
        let energy: f32 = samples[frame..frame + FRAME].iter().map(|s| s * s).sum();
        if energy < quietest {
            quietest = energy;
            cut = frame + FRAME / 2;
        }
        frame -= FRAME;
    }
    cut
}

#[cfg(test)]
mod tests {
    use super::quietest_cut;

    #[test]
    fn cuts_in_the_quiet_stretch_before_the_end() {
        let mut samples = vec![0.5_f32; 16_000 * 30];
        samples[16_000 * 27..16_000 * 27 + 4_800].fill(0.0);
        let cut = quietest_cut(&samples, 16_000 * 5);
        assert!(
            (16_000 * 27..16_000 * 27 + 4_800).contains(&cut),
            "cut at {cut}"
        );
        // a clip shorter than one frame is left whole
        assert_eq!(quietest_cut(&[0.1; 100], 16_000), 100);
    }
}
