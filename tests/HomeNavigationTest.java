package dev.notune.transcribe;
public final class HomeNavigationTest {
    public static void main(String[] args) {
        assert "home".equals(HomeNavigation.restore(null));
        assert "home".equals(HomeNavigation.restore("unknown"));
        for (String page : HomeNavigation.PAGES) {
            assert page.equals(HomeNavigation.restore(page));
            String parent = HomeNavigation.parent(page);
            assert "home".equals(HomeNavigation.parent(parent));
            if (!"home".equals(page) && !"settings".equals(page))
                assert "settings".equals(parent);
        }
    }
}
