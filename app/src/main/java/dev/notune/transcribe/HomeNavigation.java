package dev.notune.transcribe;

/** Presentation-only destinations. Preferences and session state stay with their owners. */
final class HomeNavigation {
    static final String[] PAGES = {"home", "settings", "keyboard", "recording", "subtitles",
            "appearance", "privacy", "advanced", "about"};

    static String restore(String value) {
        for (String page : PAGES) if (page.equals(value)) return page;
        return "home";
    }

    static String parent(String page) {
        return "home".equals(page) || "settings".equals(page) ? "home" : "settings";
    }
}
