package com.zrlog.plugin.data.codec;

/** The admin appearance passed to a plugin's UI. */
public final class PluginAppearance {

    private final String theme;
    private final boolean dark;
    private final String colorPrimary;
    private final boolean compactMode;

    private PluginAppearance(String theme, boolean dark, String colorPrimary, boolean compactMode) {
        this.theme = theme;
        this.dark = dark;
        this.colorPrimary = colorPrimary;
        this.compactMode = compactMode;
    }

    public static PluginAppearance from(BaseHttpRequestInfo request) {
        if (request == null) {
            return new PluginAppearance(BaseHttpRequestInfo.DEFAULT_ADMIN_THEME, false,
                    BaseHttpRequestInfo.DEFAULT_ADMIN_COLOR_PRIMARY, false);
        }
        return new PluginAppearance(request.getAdminTheme(), request.isDarkMode(),
                request.getAdminColorPrimary(), request.isAdminCompactMode());
    }

    public String getTheme() {
        return theme;
    }

    public boolean isDark() {
        return dark;
    }

    public String getColorPrimary() {
        return colorPrimary;
    }

    public boolean isCompactMode() {
        return compactMode;
    }
}
