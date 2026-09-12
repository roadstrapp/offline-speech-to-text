package dev.notune.transcribe;

/** Immutable identity of the app's recommended default speech model. */
public final class DefaultModelContract {
    public static final String FILE_NAME = "parakeet-tdt-0.6b-v3-Q4_K_M.gguf";
    public static final long BYTE_SIZE = 485425504L;
    public static final String SHA256 =
            "b68557be1e3c40207fd7c4bd9d63f1d3316b963f15325bfb0cc16a8bb0ffd181";
    public static final String DOWNLOAD_URL =
            "https://huggingface.co/handy-computer/parakeet-tdt-0.6b-v3-gguf/resolve/"
                    + "85ac09ea12fc4b1112fa76810059364bc6adc9de/"
                    + FILE_NAME + "?download=true";

    private DefaultModelContract() {
    }

    public static boolean matches(String fileName, long byteSize, String sha256) {
        return FILE_NAME.equals(fileName)
                && BYTE_SIZE == byteSize
                && SHA256.equalsIgnoreCase(sha256);
    }
}
