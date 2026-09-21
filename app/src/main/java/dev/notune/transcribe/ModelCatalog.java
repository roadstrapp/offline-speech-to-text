package dev.notune.transcribe;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Offline catalog for exact model artifacts supported by transcribe.cpp 0.2.3.
 */
public final class ModelCatalog {
    private static final long MIB = 1024L * 1024L;
    private static final String RUNTIME = "transcribe.cpp 0.2.3";
    private static final LinkedHashSet<String> PARAKEET_V3_LANGUAGES =
            new LinkedHashSet<>(Arrays.asList(
                    "bg", "hr", "cs", "da", "nl", "en", "et", "fi", "fr", "de",
                    "el", "hu", "it", "lv", "lt", "mt", "pl", "pt", "ro", "sk",
                    "sl", "es", "sv", "ru", "uk"));
    private static final LinkedHashSet<String> WHISPER_LANGUAGES = new LinkedHashSet<>(
            Arrays.asList("en", "zh", "de", "es", "ru", "ko", "fr", "ja", "pt", "tr",
                    "pl", "ca", "nl", "ar", "sv", "it", "id", "hi", "fi", "vi",
                    "he", "uk", "el", "ms", "cs", "ro", "da", "hu", "ta", "no",
                    "th", "ur", "hr", "bg", "lt", "la", "mi", "ml", "cy", "sk",
                    "te", "fa", "lv", "bn", "sr", "az", "sl", "kn", "et", "mk",
                    "br", "eu", "is", "hy", "ne", "mn", "bs", "kk", "sq", "sw",
                    "gl", "mr", "pa", "si", "km", "sn", "yo", "so", "af", "oc",
                    "ka", "be", "tg", "sd", "gu", "am", "yi", "lo", "uz", "fo",
                    "ht", "ps", "tk", "nn", "mt", "sa", "lb", "my", "bo", "tl",
                    "mg", "as", "tt", "haw", "ln", "ha", "ba", "jw", "su"));
    private static final LinkedHashSet<String> WHISPER_V3_FILES =
            new LinkedHashSet<>(Arrays.asList(
                    "whisper-large-v3-Q4_K_M.gguf",
                    "whisper-large-v3-turbo-Q4_K_M.gguf"));
    private static final LinkedHashSet<String> WHISPER_MULTILINGUAL_FILES =
            new LinkedHashSet<>(Arrays.asList(
                    "whisper-tiny-Q4_K_M.gguf", "whisper-base-Q4_K_M.gguf",
                    "whisper-small-Q4_K_M.gguf", "whisper-medium-Q4_K_M.gguf",
                    "whisper-large-Q4_K_M.gguf", "whisper-large-v2-Q4_K_M.gguf",
                    "whisper-large-v3-Q4_K_M.gguf",
                    "whisper-large-v3-turbo-Q4_K_M.gguf"));
    private static final LinkedHashSet<String> WHISPER_TRANSLATING_FILES =
            new LinkedHashSet<>(Arrays.asList(
                    "whisper-tiny-Q4_K_M.gguf", "whisper-base-Q4_K_M.gguf",
                    "whisper-small-Q4_K_M.gguf", "whisper-medium-Q4_K_M.gguf",
                    "whisper-large-Q4_K_M.gguf", "whisper-large-v2-Q4_K_M.gguf",
                    "whisper-large-v3-Q4_K_M.gguf"));
    private static final LinkedHashSet<String> WHISPER_ENGLISH_FILES =
            new LinkedHashSet<>(Arrays.asList(
                    "whisper-tiny.en-Q4_K_M.gguf", "whisper-base.en-Q4_K_M.gguf",
                    "whisper-small.en-Q4_K_M.gguf", "whisper-medium.en-Q4_K_M.gguf"));

    private static final ModelDescriptor PARAKEET = new ModelDescriptor(
            "parakeet-tdt-0.6b-v3-q4km", "Parakeet TDT 0.6B v3 · Q4", "Parakeet",
            RUNTIME, DefaultModelContract.FILE_NAME,
            DefaultModelContract.BYTE_SIZE, DefaultModelContract.SHA256,
            DefaultModelContract.DOWNLOAD_URL,
            "https://huggingface.co/handy-computer/parakeet-tdt-0.6b-v3-gguf",
            "CC-BY-4.0", "https://creativecommons.org/licenses/by/4.0/",
            Collections.singleton("arm64-v8a"), false, false,
            PARAKEET_V3_LANGUAGES,
            ModelDescriptor.Capability.SUPPORTED, ModelDescriptor.Capability.UNSUPPORTED,
            ModelDescriptor.Capability.UNKNOWN, ModelDescriptor.Capability.UNSUPPORTED,
            2L * 1024L * MIB, DefaultModelContract.BYTE_SIZE + 64L * MIB,
            ModelDescriptor.Provenance.EXACT_PINNED);

    private static final ModelDescriptor PARAKEET_PRIMELINE = new ModelDescriptor(
            "parakeet-primeline-q4km", "Parakeet primeLine · Q4", "Parakeet",
            RUNTIME, "parakeet-primeline-Q4_K_M.gguf", 485425568L,
            "8b01f1893fa1db166d8b08375f8300c140fc7f110af7e9b68480a27b145dad7d",
            "https://huggingface.co/handy-computer/parakeet-primeline-gguf/resolve/"
                    + "7c63545b352e5d410ccc1ea1eb49aa751e65780b/"
                    + "parakeet-primeline-Q4_K_M.gguf?download=true",
            "https://huggingface.co/handy-computer/parakeet-primeline-gguf",
            "CC-BY-4.0", "https://creativecommons.org/licenses/by/4.0/",
            Collections.singleton("arm64-v8a"), false, false,
            PARAKEET_V3_LANGUAGES,
            ModelDescriptor.Capability.SUPPORTED, ModelDescriptor.Capability.UNSUPPORTED,
            ModelDescriptor.Capability.UNKNOWN, ModelDescriptor.Capability.UNSUPPORTED,
            2L * 1024L * MIB, 485425568L + 64L * MIB,
            ModelDescriptor.Provenance.EXACT_PINNED);

    private static final List<ModelDescriptor> DOWNLOADS;

    static {
        List<ModelDescriptor> models = new ArrayList<>();
        models.add(PARAKEET);
        models.add(PARAKEET_PRIMELINE);
        // One mobile-oriented quantization for every model variant in the
        // pinned runtime's model documentation. Larger variants remain visible
        // with their exact size instead of being silently hidden.
        add(models, "Canary", "canary-180m-flash-gguf", "canary-180m-flash-Q4_K_M.gguf", 139223744L, "c8ae5758d7d4dc59c48d816474a33544e362ca52c635f96ee0b4b95e1b48f90c");
        add(models, "Canary", "canary-1b-flash-gguf", "canary-1b-flash-Q4_K_M.gguf", 677141280L, "2521a615a2b04ab11900d894e1f5c5a70405d85aa74be0806b569b9cc311a707");
        add(models, "Canary", "canary-1b-gguf", "canary-1b-Q4_K_M.gguf", 729686848L, "ded40db7f9d25e992bf35a0c3c3743e1a7c85d0c30bdbbabd46bca932a47353d");
        add(models, "Canary", "canary-1b-v2-gguf", "canary-1b-v2-Q4_K_M.gguf", 735476448L, "49e0a67e219bec95a254c2348460b6350e75a7ac6f93a131e48244b4c7cb53b9");
        add(models, "Canary", "canary-qwen-2.5b-gguf", "canary-qwen-2.5b-Q4_K_M.gguf", 1737575808L, "db5162229d6fa22597d06a613bd9b543eddb3ee02e6afc5e759120fde02bebf7");
        add(models, "Cohere", "cohere-transcribe-03-2026-gguf", "cohere-transcribe-03-2026-Q4_K_M.gguf", 1558162944L, "0ea56826d8bd5d74b7143a4a04e022dc1bb75452cfae49d98b6acb0c1d16a1fb");
        add(models, "Fun-ASR", "Fun-ASR-MLT-Nano-2512-gguf", "Fun-ASR-MLT-Nano-2512-Q4_K_M.gguf", 556975168L, "9232584a27a7dcbcaf13640de8f9c2e7375178ea11e587f90a5328ecf06d58a1");
        add(models, "Fun-ASR", "Fun-ASR-Nano-2512-gguf", "Fun-ASR-Nano-2512-Q4_K_M.gguf", 556974848L, "444885fb7a16211a721e265dfe08c31b31e81fb9e2c4f21ab1079210cd038408");
        add(models, "GigaAM", "gigaam-v3-ctc-gguf", "gigaam-v3-ctc-Q4_K_M.gguf", 182150080L, "16b329fed3eaa197f2191506ac3cad72f46502297ed6a0f915c79d2ac08c404e");
        add(models, "GigaAM", "gigaam-v3-e2e-ctc-gguf", "gigaam-v3-e2e-ctc-Q4_K_M.gguf", 182497888L, "35581e11e048eef785657cff07e5fced794bba6d9c75143257f6452c8aeea655");
        add(models, "GigaAM", "gigaam-v3-e2e-rnnt-gguf", "gigaam-v3-e2e-rnnt-Q4_K_M.gguf", 183948704L, "7d69952fb431a8d7800ed9910dc61fea37d8406bfe96d10bf24c8bd4b7c68623");
        add(models, "GigaAM", "gigaam-v3-rnnt-gguf", "gigaam-v3-rnnt-Q4_K_M.gguf", 183246752L, "33b09558330ccfdc5c156e6d6b57c89759a0ac3b653bcdfcd5f1e13f20fc1e76");
        add(models, "Granite", "granite-4.0-1b-speech-gguf", "granite-4.0-1b-speech-Q4_K_M.gguf", 1602904800L, "eacd9bc06063a3fdb6bf904a2a82c2646d504922c6e4e5eb1988d61e780884d1");
        add(models, "Granite", "granite-speech-4.1-2b-gguf", "granite-speech-4.1-2b-Q4_K_M.gguf", 1602904800L, "3171de08a99315481f0eac1979cbc0684911631d59907b2fe1e189dbcdce135f");
        add(models, "Granite", "granite-speech-4.1-2b-nar-gguf", "granite-speech-4.1-2b-nar-Q4_K_M.gguf", 1560008832L, "68537a51d4d6d5a6be6e50f735a08af16646afba70fe8ab80dfe60ba7623325f");
        add(models, "Granite", "granite-speech-4.1-2b-plus-gguf", "granite-speech-4.1-2b-plus-Q4_K_M.gguf", 1489663424L, "5d52b6bb9bc2b504120448b4da61aeb383da3c1bcfe88b22c6ed667f2520206f");
        add(models, "MedASR", "medasr-gguf", "medasr-Q4_K_M.gguf", 83082432L, "b23b52db7e1e17a5a21c24f3b78add04e1055bbe026b7c7196324c34ab6f3e2f");
        add(models, "Moonshine", "moonshine-base-gguf", "moonshine-base-Q8_0.gguf", 77476480L, "7f0027dfd857d310b63a85ef57cadf183da712cc374f85a648f8bc18aaa2efc8");
        add(models, "Moonshine", "moonshine-streaming-medium-gguf", "moonshine-streaming-medium-Q8_0.gguf", 295793568L, "f7c9564249b508f6012927ec4f9e536087da53a7047f858ca9975bea5f75299e");
        add(models, "Moonshine", "moonshine-streaming-small-gguf", "moonshine-streaming-small-Q8_0.gguf", 198506848L, "d03670f69629b649085d0f44a63d97668b4119117cc9611a4e4ad94341713dfc");
        add(models, "Moonshine", "moonshine-streaming-tiny-gguf", "moonshine-streaming-tiny-Q8_0.gguf", 50462816L, "930e4622ad3a24158b91406c30c977fa6a26b34cb32d6ac3e57cfb23383a869e");
        add(models, "Moonshine", "moonshine-tiny-gguf", "moonshine-tiny-Q8_0.gguf", 35466912L, "2fd348d7b38f97d309cc3ec6848f3f57f537b80244950f07d2637e463f95a3a1");
        add(models, "Nemotron", "nemotron-3.5-asr-streaming-0.6b-gguf", "nemotron-3.5-asr-streaming-0.6b-Q4_K_M.gguf", 495831520L, "41c99fa5fb6f3d35f68e79adc3e755eca2232a8d921178bd647b71194792b8fd");
        add(models, "Nemotron", "nemotron-speech-streaming-en-0.6b-gguf", "nemotron-speech-streaming-en-0.6b-Q4_K_M.gguf", 475436032L, "dc959ca31499b114e395c44eb4f0778968f20e5cfb03305a08a39925b2da8e1e");
        add(models, "Parakeet", "parakeet-ctc-0.6b-gguf", "parakeet-ctc-0.6b-Q4_K_M.gguf", 469302464L, "2a0dc545d06184b30cd3441a38811f09867a1d6b7b89f008f16bd75c1030ccdb");
        add(models, "Parakeet", "parakeet-ctc-1.1b-gguf", "parakeet-ctc-1.1b-Q4_K_M.gguf", 818156576L, "9e4c104086067ee3afefbf7d41929024261682926cf0a57cc5a1f557fc5b69e5");
        add(models, "Parakeet", "parakeet-rnnt-0.6b-gguf", "parakeet-rnnt-0.6b-Q4_K_M.gguf", 476390816L, "70b32229e2b576a3be66176ebc484476f3d715d911c11a4e17167790ce378cf7");
        add(models, "Parakeet", "parakeet-rnnt-1.1b-gguf", "parakeet-rnnt-1.1b-Q4_K_M.gguf", 825244928L, "9e8636f491867751ca5b5c18ca91adf52014b1ae406b46516a9a8680ba1049a1");
        add(models, "Parakeet", "parakeet-tdt-0.6b-v2-gguf", "parakeet-tdt-0.6b-v2-Q4_K_M.gguf", 475491840L, "4853f9653f641d376e6f7de65d73c7a34a73677704a606727bf51acc83f999f3");
        add(models, "Parakeet", "parakeet-tdt-1.1b-gguf", "parakeet-tdt-1.1b-Q4_K_M.gguf", 825248416L, "38e85773763afe7b79c8538fbd091367b4644123358ea209eeb665879e3fcb13");
        add(models, "Parakeet", "parakeet-tdt_ctc-1.1b-gguf", "parakeet-tdt_ctc-1.1b-Q4_K_M.gguf", 825248000L, "5c27d9289e23019c459b1cef87baa5b672343733ccd3762796e68aec447169cd");
        add(models, "Parakeet", "parakeet-tdt_ctc-110m-gguf", "parakeet-tdt_ctc-110m-Q4_K_M.gguf", 89989600L, "486414fd90185a8c8a4ced7c123cfb133ff4f7958426c6b8bd9049946b56b448");
        add(models, "Parakeet", "parakeet-unified-en-0.6b-gguf", "parakeet-unified-en-0.6b-Q4_K_M.gguf", 477274496L, "a8bf3de2b393bd14ead5a858c3748d5e3b07a20fdeabdd3b498fba4f463fa929");
        add(models, "Qwen3-ASR", "Qwen3-ASR-0.6B-gguf", "Qwen3-ASR-0.6B-Q4_K_M.gguf", 589560480L, "5b58f32a58ffa2c8783e0b0963485623e286e6272d953dfc9e28bc3447dee0c0");
        add(models, "Qwen3-ASR", "Qwen3-ASR-1.7B-gguf", "Qwen3-ASR-1.7B-Q4_K_M.gguf", 1319830496L, "b7afe3674f653fa84f712ed2440353c6e7cf7f93697fef76b05a26538b24844e");
        add(models, "SenseVoice", "SenseVoiceSmall-gguf", "SenseVoiceSmall-Q4_K_M.gguf", 145738304L, "9d775303e6b4e793d049ef226214c400b2b1746c734ed017146a1a085b13d02b");
        add(models, "Voxtral", "Voxtral-Mini-3B-2507-gguf", "Voxtral-Mini-3B-2507-Q4_K_M.gguf", 2984721056L, "3a6717aa8f8989108d260cbd237584289eec43cc987e10133c33643515936205");
        add(models, "Voxtral", "Voxtral-Mini-4B-Realtime-2602-gguf", "Voxtral-Mini-4B-Realtime-2602-Q4_K_M.gguf", 2830493984L, "39dc1f65539373a406edea7490505822d77c12edff521744678717eef4da4723");
        add(models, "Voxtral", "Voxtral-Small-24B-2507-gguf", "Voxtral-Small-24B-2507-Q4_K_M.gguf", 14302261728L, "0da1b866c0eb2ce805678c18a7fb1e413320e4ad3ccbef7160707b65bae937a3");
        add(models, "Whisper", "whisper-tiny-gguf", "whisper-tiny-Q4_K_M.gguf", 43621792L, "86e1100470df173fa9079f941e33e4d52d11c03923407dd1ec51fd1937d8e609");
        add(models, "Whisper", "whisper-tiny.en-gguf", "whisper-tiny.en-Q4_K_M.gguf", 43545248L, "3bfa6200aa12a21409445401f7871b5c733546dc45a29eb4871fcb3c7954e08b");
        add(models, "Whisper", "whisper-base-gguf", "whisper-base-Q4_K_M.gguf", 58870848L, "78bc9a8cb7e16a3354b262a4c71c0ed4892f6a66b681068f474dfada0729c0ae");
        add(models, "Whisper", "whisper-base.en-gguf", "whisper-base.en-Q4_K_M.gguf", 58794272L, "fd19959ff81f96c2b66f26fad97aada090ed6b3af681cf5b569bef3d7a073da5");
        add(models, "Whisper", "whisper-small-gguf", "whisper-small-Q4_K_M.gguf", 171630656L, "b204d2005a3e5d4fe6153bd61e5e8b32e757ff7b017ac8f61c6f051c2f80e939");
        add(models, "Whisper", "whisper-small.en-gguf", "whisper-small.en-Q4_K_M.gguf", 171553856L, "7bf9ebce1e6332b3d18d4c27032e3097148fc124befed3e70022e0d70539a365");
        add(models, "Whisper", "whisper-medium-gguf", "whisper-medium-Q4_K_M.gguf", 504102848L, "6c9cad9c41d1fa0fcc624898f7ee3485b66127627878081a5655c6f6f3ce20ab");
        add(models, "Whisper", "whisper-medium.en-gguf", "whisper-medium.en-Q4_K_M.gguf", 504025856L, "8a0eb0bb20019af88fc0ccda06d97dc949a6a0cdf35d3f2c316b967e76cd4077");
        add(models, "Whisper", "whisper-large-gguf", "whisper-large-Q4_K_M.gguf", 996526048L, "7d7693afc06096de46f2a41309ac479909e131824e78dae4e205f35463aaec09");
        add(models, "Whisper", "whisper-large-v2-gguf", "whisper-large-v2-Q4_K_M.gguf", 996526080L, "76aa37b205abc1fb7a9e7aaf0655b8747995b81e6bb72c18f4b1acf59e222f79");
        add(models, "Whisper", "whisper-large-v3-gguf", "whisper-large-v3-Q4_K_M.gguf", 997303008L, "6fe933811cec4cd3159debc46520ecd3aac6c7e322ece2ac61fcfdab184e1fe0");
        add(models, "Whisper", "whisper-large-v3-turbo-gguf", "whisper-large-v3-turbo-Q4_K_M.gguf", 536069728L, "ecfe9b6beb4ab18fef49187cc968cc74b5168b94629c8830e2ca6b794c6e25ed");
        DOWNLOADS = Collections.unmodifiableList(models);
    }

    private ModelCatalog() {}

    public static ModelDescriptor verifiedDefault() { return PARAKEET; }

    /** Returns a specialized balanced model only when one matches the locale. */
    static ModelDescriptor localePreferredBalanced(String languageTag) {
        String language = Locale.forLanguageTag(languageTag == null ? "" : languageTag)
                .getLanguage();
        return "de".equals(language) ? PARAKEET_PRIMELINE : null;
    }

    /** Specialized locale variants should not become generic family highlights. */
    static boolean isGeneralFamilyChoice(ModelDescriptor model) {
        return model != null && model != PARAKEET_PRIMELINE;
    }

    public static List<ModelDescriptor> verifiedDownloads() { return DOWNLOADS; }

    public static ModelDescriptor findByFileName(String fileName) {
        if (fileName == null) return null;
        for (ModelDescriptor model : DOWNLOADS) {
            if (fileName.equals(model.fileName)) return model;
        }
        return null;
    }

    public static ModelDescriptor findById(String id) {
        if (id == null) return null;
        for (ModelDescriptor model : DOWNLOADS) {
            if (id.equals(model.id)) return model;
        }
        return null;
    }

    private static void add(List<ModelDescriptor> models, String family, String repository,
                            String fileName, long bytes, String sha256) {
        models.add(pinned(family, repository, fileName, bytes, sha256));
    }

    private static ModelDescriptor pinned(String family, String repository, String fileName,
                                          long bytes, String sha256) {
        String base = fileName.substring(0, fileName.length() - ".gguf".length())
                .replace("-Q4_K_M", " · Q4").replace("-Q5_K_M", " · Q5")
                .replace("-Q8_0", " · Q8").replace('_', ' ').replace('-', ' ');
        String display = base.substring(0, 1).toUpperCase(Locale.ROOT) + base.substring(1);
        String id = fileName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        String source = "https://huggingface.co/handy-computer/" + repository;
        LinkedHashSet<String> languages = new LinkedHashSet<>();
        ModelDescriptor.Capability detection = ModelDescriptor.Capability.UNKNOWN;
        ModelDescriptor.Capability translation = ModelDescriptor.Capability.UNKNOWN;
        ModelDescriptor.Capability streaming = ModelDescriptor.Capability.UNKNOWN;
        ModelDescriptor.Capability prompt = "Whisper".equals(family)
                ? ModelDescriptor.Capability.SUPPORTED
                : ModelDescriptor.Capability.UNSUPPORTED;
        if (WHISPER_MULTILINGUAL_FILES.contains(fileName)) {
            languages.addAll(WHISPER_LANGUAGES);
            if (WHISPER_V3_FILES.contains(fileName)) languages.add("yue");
            detection = ModelDescriptor.Capability.SUPPORTED;
            translation = WHISPER_TRANSLATING_FILES.contains(fileName)
                    ? ModelDescriptor.Capability.SUPPORTED
                    : ModelDescriptor.Capability.UNSUPPORTED;
            streaming = ModelDescriptor.Capability.UNSUPPORTED;
        } else if (WHISPER_ENGLISH_FILES.contains(fileName)) {
            languages.add("en");
            detection = ModelDescriptor.Capability.UNSUPPORTED;
            translation = ModelDescriptor.Capability.UNSUPPORTED;
            streaming = ModelDescriptor.Capability.UNSUPPORTED;
        } else if ("parakeet-unified-en-0.6b-Q4_K_M.gguf".equals(fileName)) {
            languages.add("en");
            streaming = ModelDescriptor.Capability.SUPPORTED;
            translation = ModelDescriptor.Capability.UNSUPPORTED;
        } else if ("Moonshine".equals(family)) {
            languages.add("en");
            streaming = fileName.contains("streaming")
                    ? ModelDescriptor.Capability.SUPPORTED
                    : ModelDescriptor.Capability.UNSUPPORTED;
            translation = ModelDescriptor.Capability.UNSUPPORTED;
        } else if ("GigaAM".equals(family)) {
            languages.add("ru");
            translation = ModelDescriptor.Capability.UNSUPPORTED;
        } else if ("MedASR".equals(family)) {
            languages.add("en");
            translation = ModelDescriptor.Capability.UNSUPPORTED;
        }
        return new ModelDescriptor(
                id, display, family, RUNTIME, fileName, bytes, sha256,
                source + "/resolve/main/" + fileName + "?download=true", source,
                "See source", source, Collections.singleton("arm64-v8a"), false, false,
                languages, detection, translation, streaming,
                prompt,
                Math.max(1024L * MIB, bytes * 2L + 512L * MIB), bytes + 64L * MIB,
                ModelDescriptor.Provenance.EXACT_PINNED);
    }
}
