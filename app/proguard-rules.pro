# Rust calls Java callbacks by name and signature, including fork-specific
# diagnostics and session guards. Retain project classes until each JNI boundary
# has a release-mode coverage receipt; libraries/resources can still shrink.
-keep class dev.notune.transcribe.** { *; }
-dontobfuscate
