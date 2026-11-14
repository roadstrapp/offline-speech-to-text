//! Extraction of the bundled speech model from APK assets into filesDir.

use jni::objects::JObject;
use jni::JNIEnv;
use std::path::{Path, PathBuf};

/// Asset directory (and filesDir subdirectory) holding the bundled GGUF.
const BUILTIN_MODEL_DIR: &str = "builtin-model";
/// Marker file written after a successful extraction. If this file is missing,
/// the directory is assumed to be incomplete (e.g. interrupted mid-extraction)
/// and the assets will be re-extracted.
const EXTRACTION_COMPLETE_MARKER: &str = ".extraction_complete";
/// Model directory of the pre-GGUF (ONNX) app versions; deleted on sight so
/// upgrades don't leave ~670 MB of dead files behind.
const LEGACY_MODEL_DIR: &str = "parakeet-tdt-0.6b-v3-int8";
pub(crate) const MISSING_BUNDLED_MODEL_ERROR: &str = "no bundled model is packaged in this build";

/// Resolves the app's `filesDir` via the given Context.
pub fn files_dir(env: &mut JNIEnv, context: &JObject) -> anyhow::Result<PathBuf> {
    let files_dir_obj = env
        .call_method(context, "getFilesDir", "()Ljava/io/File;", &[])?
        .l()?;
    let path_str_obj = env
        .call_method(
            &files_dir_obj,
            "getAbsolutePath",
            "()Ljava/lang/String;",
            &[],
        )?
        .l()?;
    let path_string: String = env.get_string(&path_str_obj.into())?.into();
    Ok(PathBuf::from(path_string))
}

/// Extracts the bundled model from APK assets (if not already done) and
/// returns the path of its GGUF file.
pub fn extract_builtin_model(env: &mut JNIEnv, context: &JObject) -> anyhow::Result<PathBuf> {
    let base_path = files_dir(env, context)?;
    let model_dir = base_path.join(BUILTIN_MODEL_DIR);
    let marker_file = model_dir.join(EXTRACTION_COMPLETE_MARKER);

    // The marker only ever appears together with a complete directory.
    if marker_file.exists() {
        if let Ok(model) = find_gguf(&model_dir) {
            return Ok(model);
        }
        log::warn!("Bundled-model marker exists without a GGUF; re-extracting");
    }

    let _lock = ExtractionLock::acquire(&base_path.join(EXTRACTION_LOCK))?;
    // Another process may have finished extracting while we waited.
    if marker_file.exists() {
        if let Ok(model) = find_gguf(&model_dir) {
            return Ok(model);
        }
        log::warn!("Bundled-model marker exists without a GGUF; re-extracting");
    }

    let legacy_dir = base_path.join(LEGACY_MODEL_DIR);
    if legacy_dir.exists() {
        log::info!("Removing legacy ONNX model directory");
        let _ = std::fs::remove_dir_all(&legacy_dir);
    }

    let tmp_root = base_path.join(EXTRACTION_TMP_DIR);
    if tmp_root.exists() {
        std::fs::remove_dir_all(&tmp_root)?;
    }
    std::fs::create_dir_all(&tmp_root)?;

    // Don't leave a partial copy (hundreds of MB) behind if this fails, e.g.
    // because storage is full.
    let extracted = extract_into(env, context, &tmp_root);
    if extracted.is_err() {
        if env.exception_check().unwrap_or(false) {
            let _ = env.exception_clear();
        }
        let _ = std::fs::remove_dir_all(&tmp_root);
    }
    let tmp_model_dir = extracted?;

    let published = publish_extracted_model(&tmp_model_dir, &model_dir);
    let _ = std::fs::remove_dir_all(&tmp_root);
    published
}

fn publish_extracted_model(staged: &Path, destination: &Path) -> anyhow::Result<PathBuf> {
    find_gguf(staged)?;
    if !staged.join(EXTRACTION_COMPLETE_MARKER).is_file() {
        anyhow::bail!("bundled model extraction is incomplete");
    }
    if destination.exists() {
        std::fs::remove_dir_all(destination)?;
    }
    std::fs::rename(staged, destination)?;
    find_gguf(destination)
}

/// Copies the bundled model into `tmp_root` and marks it complete; returns
/// the model directory inside it.
fn extract_into(env: &mut JNIEnv, context: &JObject, tmp_root: &Path) -> anyhow::Result<PathBuf> {
    let asset_manager_obj = env
        .call_method(
            context,
            "getAssets",
            "()Landroid/content/res/AssetManager;",
            &[],
        )?
        .l()?;
    if list_asset_children(env, &asset_manager_obj, BUILTIN_MODEL_DIR)?.is_empty() {
        anyhow::bail!(MISSING_BUNDLED_MODEL_ERROR);
    }
    copy_assets_recursively(env, &asset_manager_obj, BUILTIN_MODEL_DIR, tmp_root)?;

    // Only mark (and publish) the extraction if the model actually arrived.
    let tmp_model_dir = tmp_root.join(BUILTIN_MODEL_DIR);
    find_gguf(&tmp_model_dir)?;
    std::fs::write(tmp_model_dir.join(EXTRACTION_COMPLETE_MARKER), "ok")?;
    Ok(tmp_model_dir)
}

/// Lock file in filesDir serializing extraction across processes.
const EXTRACTION_LOCK: &str = ".builtin-model.lock";
/// Scratch directory in filesDir that extraction writes into.
const EXTRACTION_TMP_DIR: &str = ".builtin-model.tmp";

/// An exclusive `flock` on a file, released when dropped (or when the
/// process dies, so a killed extraction never leaves a stale lock).
struct ExtractionLock(std::fs::File);

impl ExtractionLock {
    fn acquire(path: &Path) -> anyhow::Result<ExtractionLock> {
        use std::os::unix::io::AsRawFd;
        let file = std::fs::OpenOptions::new()
            .create(true)
            .write(true)
            .truncate(false)
            .open(path)?;
        loop {
            if unsafe { libc::flock(file.as_raw_fd(), libc::LOCK_EX) } == 0 {
                return Ok(ExtractionLock(file));
            }
            let err = std::io::Error::last_os_error();
            if err.kind() != std::io::ErrorKind::Interrupted {
                return Err(err.into());
            }
        }
    }
}

impl Drop for ExtractionLock {
    fn drop(&mut self) {
        use std::os::unix::io::AsRawFd;
        unsafe { libc::flock(self.0.as_raw_fd(), libc::LOCK_UN) };
    }
}

/// Removes the extraction marker so the next load re-extracts the bundled
/// model — called when a load fails on (likely corrupt) extracted files.
pub fn invalidate_builtin_model(model_path: &Path) {
    if let Some(dir) = model_path.parent() {
        let marker = dir.join(EXTRACTION_COMPLETE_MARKER);
        if marker.exists() {
            log::warn!("Model load failed, removing extraction marker for re-extraction");
            let _ = std::fs::remove_file(&marker);
        }
    }
}

/// Returns the single `.gguf` file in `dir`.
fn find_gguf(dir: &Path) -> anyhow::Result<PathBuf> {
    for entry in std::fs::read_dir(dir)? {
        let path = entry?.path();
        if path.is_file()
            && path
                .extension()
                .is_some_and(|ext| ext.eq_ignore_ascii_case("gguf"))
        {
            return Ok(path);
        }
    }
    anyhow::bail!("no GGUF file found in {}", dir.display())
}

fn copy_assets_recursively(
    env: &mut JNIEnv,
    asset_manager: &JObject,
    path: &str,
    target_root: &Path,
) -> anyhow::Result<()> {
    let children = list_asset_children(env, asset_manager, path)?;
    if children.is_empty() {
        return copy_asset_file(env, asset_manager, path, target_root);
    }

    let target_dir = target_root.join(path);
    std::fs::create_dir_all(&target_dir)?;

    for file_name in children {
        let child_path = if path.is_empty() {
            file_name
        } else {
            format!("{}/{}", path, file_name)
        };

        copy_assets_recursively(env, asset_manager, &child_path, target_root)?;
    }
    Ok(())
}

fn list_asset_children(
    env: &mut JNIEnv,
    asset_manager: &JObject,
    path: &str,
) -> anyhow::Result<Vec<String>> {
    use jni::objects::JObjectArray;

    let path_jstring = env.new_string(path)?;
    let list_array_obj = env
        .call_method(
            asset_manager,
            "list",
            "(Ljava/lang/String;)[Ljava/lang/String;",
            &[(&path_jstring).into()],
        )?
        .l()?;
    let list_array: JObjectArray = list_array_obj.into();
    let len = env.get_array_length(&list_array)?;
    let mut children = Vec::with_capacity(len as usize);
    for i in 0..len {
        let file_name_obj = env.get_object_array_element(&list_array, i)?;
        children.push(env.get_string(&file_name_obj.into())?.into());
    }
    Ok(children)
}

fn copy_asset_file(
    env: &mut JNIEnv,
    asset_manager: &JObject,
    asset_path: &str,
    target_root: &Path,
) -> anyhow::Result<()> {
    let path_jstring = env.new_string(asset_path)?;
    let result = env.call_method(
        asset_manager,
        "open",
        "(Ljava/lang/String;)Ljava/io/InputStream;",
        &[(&path_jstring).into()],
    );

    match result {
        Ok(stream_val) => {
            let stream_obj = stream_val.l()?;
            let target_file_path = target_root.join(asset_path);

            let mut file = std::fs::File::create(&target_file_path)?;
            let mut buffer = [0u8; 8192];
            let buffer_j = env.new_byte_array(8192)?;

            loop {
                let bytes_read = env
                    .call_method(&stream_obj, "read", "([B)I", &[(&buffer_j).into()])?
                    .i()?;

                if bytes_read == -1 {
                    break;
                }

                let bytes_read_usize = bytes_read as usize;
                let buffer_slice = unsafe {
                    std::slice::from_raw_parts_mut(buffer.as_mut_ptr() as *mut i8, bytes_read_usize)
                };

                env.get_byte_array_region(&buffer_j, 0, buffer_slice)?;

                use std::io::Write;
                file.write_all(&buffer[0..bytes_read_usize])?;
            }

            env.call_method(&stream_obj, "close", "()V", &[])?;
            log::info!("Extracted: {:?}", target_file_path);
            Ok(())
        }
        Err(error) => {
            // A Java exception must never escape a JNI call as an unhandled
            // exception merely because a packaged asset is absent or corrupt.
            // Clear it before returning an ordinary Rust error to the loader.
            if env.exception_check().unwrap_or(false) {
                let _ = env.exception_clear();
            }
            anyhow::bail!("could not open packaged asset {asset_path}: {error}")
        }
    }
}

#[cfg(test)]
mod publication_tests {
    use super::*;
    fn fixture() -> PathBuf {
        let p = std::env::temp_dir().join(format!(
            "speech-extraction-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        std::fs::create_dir_all(&p).unwrap();
        p
    }
    #[test]
    fn incomplete_stage_does_not_replace_existing_model() {
        let base = fixture();
        let existing = base.join("existing");
        let staged = base.join("staged");
        std::fs::create_dir(&existing).unwrap();
        std::fs::create_dir(&staged).unwrap();
        std::fs::write(existing.join("model.gguf"), b"previous").unwrap();
        std::fs::write(staged.join("model.gguf"), b"partial").unwrap();
        assert!(publish_extracted_model(&staged, &existing).is_err());
        assert_eq!(
            std::fs::read(existing.join("model.gguf")).unwrap(),
            b"previous"
        );
        std::fs::remove_dir_all(base).unwrap();
    }
    #[test]
    fn complete_stage_is_published_with_its_marker() {
        let base = fixture();
        let destination = base.join("published");
        let staged = base.join("staged");
        std::fs::create_dir(&staged).unwrap();
        std::fs::write(staged.join("model.gguf"), b"complete").unwrap();
        std::fs::write(staged.join(EXTRACTION_COMPLETE_MARKER), b"ok").unwrap();
        assert_eq!(
            publish_extracted_model(&staged, &destination).unwrap(),
            destination.join("model.gguf")
        );
        assert!(destination.join(EXTRACTION_COMPLETE_MARKER).is_file());
        assert!(!staged.exists());
        std::fs::remove_dir_all(base).unwrap();
    }
    #[test]
    fn extraction_lock_excludes_another_writer_until_drop() {
        let base = fixture();
        let path = base.join("lock");
        let lock = ExtractionLock::acquire(&path).unwrap();
        let (tx, rx) = std::sync::mpsc::channel();
        let writer = std::thread::spawn(move || {
            let _lock = ExtractionLock::acquire(&path).unwrap();
            tx.send(()).unwrap();
        });
        assert!(rx
            .recv_timeout(std::time::Duration::from_millis(30))
            .is_err());
        drop(lock);
        rx.recv_timeout(std::time::Duration::from_secs(2)).unwrap();
        writer.join().unwrap();
        std::fs::remove_dir_all(base).unwrap();
    }
}
