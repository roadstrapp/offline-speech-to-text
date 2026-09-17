package dev.notune.transcribe;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** Explicit-tap-only HTTPS model downloader with safe resume and exact verification. */
final class ModelDownloadTask {
    private static final long STAGING_MAX_AGE_MILLIS = 24L * 60 * 60 * 1000;

    interface ConnectionFactory {
        HttpURLConnection open(URL url) throws IOException;
    }

    private static final ConnectionFactory NETWORK = url ->
            (HttpURLConnection) url.openConnection();

    interface Listener {
        void onStarted(Handle handle);
        void onProgress(long downloaded, long total);
        void onComplete(String installedName);
        void onFailure(String reason);
    }

    static final class Handle {
        private final AtomicBoolean cancelled;
        Handle(AtomicBoolean cancelled) { this.cancelled = cancelled; }
        void cancel() { cancelled.set(true); }
    }

    private ModelDownloadTask() {}

    static Handle start(File filesDir, ModelDescriptor descriptor, Listener listener) {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        Handle handle = new Handle(cancelled);
        listener.onStarted(handle);
        new Thread(() -> run(filesDir, descriptor, listener, cancelled, NETWORK),
                "model-download").start();
        return handle;
    }

    private static void run(File filesDir, ModelDescriptor descriptor, Listener listener,
                            AtomicBoolean cancelled, ConnectionFactory connections) {
        if (!descriptor.isVerifiedDownload()) {
            listener.onFailure("This model is not approved for automatic download.");
            return;
        }
        File downloads = new File(new File(filesDir, "models"), ".downloads");
        if (!downloads.isDirectory() && !downloads.mkdirs()) {
            listener.onFailure("Private model storage is unavailable.");
            return;
        }
        File part = new File(downloads, descriptor.id + ".part");
        File validatorFile = new File(downloads, descriptor.id + ".validator");
        File lockFile = new File(downloads, "download.lock");
        try (RandomAccessFile lockAccess = new RandomAccessFile(lockFile, "rw");
             FileChannel lockChannel = lockAccess.getChannel();
             FileLock downloadLock = tryExclusiveLock(lockChannel)) {
            if (downloadLock == null) {
                throw new IOException("Another model download is already running.");
            }
            try {
                sweepStaleDownloads(filesDir, System.currentTimeMillis());
                if (filesDir.getUsableSpace() < descriptor.minimumFreeBytes - part.length()) {
                    throw new IOException("Not enough free storage for this model.");
                }
                download(descriptor, part, validatorFile, listener, cancelled, connections);
                if (cancelled.get()) throw new IOException("Download cancelled.");
                String digest = sha256(part, cancelled);
                if (!ModelDownloadPolicy.acceptArtifact(part.length(), digest, descriptor)) {
                    Files.deleteIfExists(part.toPath());
                    Files.deleteIfExists(validatorFile.toPath());
                    throw new IOException("Downloaded file failed integrity verification.");
                }
                String installed = ModelInstaller.installVerified(
                        filesDir, part, descriptor, cancelled::get);
                Files.deleteIfExists(validatorFile.toPath());
                listener.onComplete(installed);
            } catch (IOException | RuntimeException error) {
                Exception reported = error;
                if (cancelled.get()) {
                    reported = new IOException("Download cancelled.");
                    try {
                        discardStagedDownload(filesDir, descriptor);
                    } catch (IOException cleanupError) {
                        reported = cleanupError;
                    }
                } else if (error.getMessage() != null
                        && error.getMessage().startsWith("Download exceeded")) {
                    try {
                        discardStagedDownload(filesDir, descriptor);
                        reported = new IOException(
                                "Downloaded file failed integrity verification.");
                    } catch (IOException cleanupError) {
                        reported = cleanupError;
                    }
                }
                listener.onFailure(safeReason(reported));
            }
        } catch (IOException | RuntimeException error) {
            // Lock acquisition failed, so this invocation must never touch shared staging.
            Exception reported = cancelled.get()
                    ? new IOException("Download cancelled.") : error;
            listener.onFailure(safeReason(reported));
        }
    }

    static void runForTest(File filesDir, ModelDescriptor descriptor, Listener listener,
                           AtomicBoolean cancelled, ConnectionFactory connections) {
        run(filesDir, descriptor, listener, cancelled, connections);
    }

    static void discardStagedDownload(File filesDir, ModelDescriptor descriptor)
            throws IOException {
        File downloads = new File(new File(filesDir, "models"), ".downloads");
        Files.deleteIfExists(new File(downloads, descriptor.id + ".part").toPath());
        Files.deleteIfExists(new File(downloads, descriptor.id + ".validator").toPath());
    }

    static void sweepStaleDownloads(File filesDir, long nowMillis) throws IOException {
        File downloads = new File(new File(filesDir, "models"), ".downloads");
        File[] staged = downloads.listFiles(file -> file.isFile()
                && (file.getName().endsWith(".part")
                    || file.getName().endsWith(".validator")));
        if (staged == null) return;
        for (File file : staged) {
            long modified = file.lastModified();
            if (modified > 0 && nowMillis - modified > STAGING_MAX_AGE_MILLIS) {
                Files.deleteIfExists(file.toPath());
            }
        }
    }

    private static FileLock tryExclusiveLock(FileChannel channel) throws IOException {
        try {
            return channel.tryLock();
        } catch (OverlappingFileLockException alreadyHeld) {
            return null;
        }
    }

    private static void download(ModelDescriptor descriptor, File part, File validatorFile,
                                 Listener listener, AtomicBoolean cancelled,
                                 ConnectionFactory connections) throws IOException {
        long existing = part.isFile() ? part.length() : 0;
        if (existing >= descriptor.byteSize) {
            if (existing == descriptor.byteSize) return;
            Files.deleteIfExists(part.toPath());
            Files.deleteIfExists(validatorFile.toPath());
            existing = 0;
        }
        String savedValidator = validatorFile.isFile()
                ? new String(Files.readAllBytes(validatorFile.toPath()),
                    StandardCharsets.UTF_8).trim() : "";
        HttpURLConnection connection = openHttps(
                descriptor.downloadUrl, existing, savedValidator, connections);
        int response = connection.getResponseCode();
        String responseValidator = validator(connection);
        ModelDownloadPolicy.ResumeAction action = existing > 0
                ? ModelDownloadPolicy.resumeAction(existing, response,
                    connection.getHeaderField("Content-Range"), savedValidator, responseValidator)
                : ModelDownloadPolicy.ResumeAction.RESTART;
        boolean append = action == ModelDownloadPolicy.ResumeAction.APPEND;
        if (!append && response != HttpURLConnection.HTTP_OK) {
            connection.disconnect();
            Files.deleteIfExists(part.toPath());
            Files.deleteIfExists(validatorFile.toPath());
            connection = openHttps(descriptor.downloadUrl, 0, "", connections);
            response = connection.getResponseCode();
            responseValidator = validator(connection);
        }
        if (response != HttpURLConnection.HTTP_OK
                && response != HttpURLConnection.HTTP_PARTIAL) {
            throw new IOException("Download server returned " + response + ".");
        }
        if (!responseValidator.isEmpty()) {
            try (FileOutputStream validatorOut = new FileOutputStream(validatorFile)) {
                validatorOut.write(responseValidator.getBytes(StandardCharsets.UTF_8));
                validatorOut.getChannel().force(true);
            }
        }
        long downloaded = append ? existing : 0;
        try (BufferedInputStream input = new BufferedInputStream(connection.getInputStream());
             FileOutputStream file = new FileOutputStream(part, append);
             BufferedOutputStream output = new BufferedOutputStream(file, 1024 * 1024)) {
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (cancelled.get()) throw new IOException("Download cancelled.");
                output.write(buffer, 0, read);
                downloaded += read;
                if (downloaded > descriptor.byteSize) {
                    throw new IOException("Download exceeded the expected size.");
                }
                listener.onProgress(downloaded, descriptor.byteSize);
            }
            output.flush();
            file.getChannel().force(true);
        } finally {
            connection.disconnect();
        }
    }

    private static String safeReason(Exception error) {
        String message = error.getMessage() == null ? "" : error.getMessage();
        for (String safePrefix : new String[] {
                "Not enough free storage", "Download cancelled", "Another model download",
                "Downloaded file failed integrity", "Download server returned",
                "Download host is not trusted", "Too many download redirects",
                "A different model already uses this filename"
        }) {
            if (message.startsWith(safePrefix)) return message;
        }
        return "Network or storage error. Tap Download to retry.";
    }

    private static HttpURLConnection openHttps(String url, long offset, String validator,
                                               ConnectionFactory connections) throws IOException {
        URL current = new URL(url);
        for (int redirects = 0; redirects <= 5; redirects++) {
            requireAllowedHttps(current);
            HttpURLConnection connection = connections.open(current);
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(20_000);
            connection.setReadTimeout(30_000);
            connection.setRequestProperty("Accept-Encoding", "identity");
            if (offset > 0) {
                connection.setRequestProperty("Range", "bytes=" + offset + "-");
                if (!validator.isEmpty()) connection.setRequestProperty("If-Range", validator);
            }
            int code = connection.getResponseCode();
            if (code < 300 || code > 399) return connection;
            String location = connection.getHeaderField("Location");
            connection.disconnect();
            if (location == null) throw new IOException("Download redirect was invalid.");
            current = new URL(current, location);
        }
        throw new IOException("Too many download redirects.");
    }

    static void requireAllowedHttps(URL url) throws IOException {
        String host = url.getHost().toLowerCase(Locale.ROOT);
        boolean allowed = host.equals("huggingface.co") || host.endsWith(".huggingface.co")
                || host.equals("hf.co") || host.endsWith(".hf.co");
        if (!"https".equalsIgnoreCase(url.getProtocol()) || !allowed) {
            throw new IOException("Download host is not trusted.");
        }
    }

    private static String validator(HttpURLConnection connection) {
        String etag = connection.getHeaderField("ETag");
        if (etag != null && !etag.trim().isEmpty()) return etag.trim();
        String modified = connection.getHeaderField("Last-Modified");
        return modified == null ? "" : modified.trim();
    }

    private static String sha256(File file, AtomicBoolean cancelled) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (FileInputStream input = new FileInputStream(file)) {
                byte[] buffer = new byte[1024 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (cancelled.get()) throw new IOException("Download cancelled.");
                    digest.update(buffer, 0, read);
                }
            }
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest.digest()) {
                result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            }
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 is unavailable.", impossible);
        }
    }
}
