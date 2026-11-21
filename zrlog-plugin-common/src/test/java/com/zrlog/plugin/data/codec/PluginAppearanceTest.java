package com.zrlog.plugin.data.codec;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class PluginAppearanceTest {

    private final Gson gson = new Gson();

    @Test
    public void shouldUseDefaultsForMissingAndBlankValues() {
        String defaults = "{\"theme\":\"default\",\"dark\":false,\"colorPrimary\":\"#1677ff\",\"compactMode\":false}";
        HttpRequestInfo request = gson.fromJson("{\"adminTheme\":\" \",\"header\":{\"Admin-Color-Primary\":\" \"}}",
                HttpRequestInfo.class);

        assertAppearance(defaults, PluginAppearance.from(null));
        assertAppearance(defaults, PluginAppearance.from(request));
    }

    @Test
    public void shouldReadLegacyHeadersWhenTypedValuesAreAbsent() {
        HttpRequestInfo request = gson.fromJson("{\"header\":{"
                + "\"Admin-Theme\":\" \",\"admin-theme\":\" desk \","
                + "\"DARK_MODE\":\" TRUE \",\"ADMIN_COLOR_PRIMARY\":\"#00875a\",\"ADMIN-COMPACT-MODE\":\"true\"}}",
                HttpRequestInfo.class);

        assertAppearance("{\"theme\":\"desk\",\"dark\":true,\"colorPrimary\":\"#00875a\",\"compactMode\":true}",
                PluginAppearance.from(request));
    }

    @Test
    public void shouldKeepExplicitFalseAndOtherTypedValuesOverHeaders() {
        HttpRequestInfo request = gson.fromJson("{\"adminTheme\":\"antd\",\"darkMode\":false,"
                + "\"adminColorPrimary\":\"#52c41a\",\"adminCompactMode\":false,\"header\":{"
                + "\"Admin-Theme\":\"desk\",\"Dark-Mode\":\"true\","
                + "\"Admin-Color-Primary\":\"#00875a\",\"Admin-Compact-Mode\":\"true\"}}",
                HttpRequestInfo.class);

        assertAppearance("{\"theme\":\"antd\",\"dark\":false,\"colorPrimary\":\"#52c41a\",\"compactMode\":false}",
                PluginAppearance.from(request));
    }

    private void assertAppearance(String expected, PluginAppearance appearance) {
        assertEquals(JsonParser.parseString(expected), gson.toJsonTree(appearance));
    }
}
