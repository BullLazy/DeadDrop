package com.bulllazy.deaddrop.transfer;

import android.content.Context;

import com.bulllazy.deaddrop.R;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

/** Resolves the Render API URL from user configuration or the build resource. */
public final class BackendConfig {
    private static final String PREFS = "deaddrop_config";
    private static final String BASE_URL = "backend_base_url";

    private BackendConfig() { }

    public static String getConfiguredBaseUrl(Context context) {
        String configured = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(BASE_URL, null);
        if (configured == null || configured.trim().isEmpty()) {
            configured = context.getString(R.string.backend_api_base_url);
        }
        return normalize(configured);
    }

    public static void saveBaseUrl(Context context, String value) throws IOException {
        String normalized = normalize(value);
        requireProductionUrl(normalized);
        context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(BASE_URL, normalized).commit();
    }

    public static String requireBaseUrl(Context context) throws IOException {
        String value = getConfiguredBaseUrl(context);
        requireProductionUrl(value);
        return value;
    }

    private static void requireProductionUrl(String value) throws IOException {
        if (!value.startsWith("https://") || value.indexOf("YOUR-RENDER-SERVICE") >= 0) {
            throw new IOException("Render HTTPS API URL is not configured");
        }
    }

    public static String normalize(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Backend URL must not be empty");
        }
        String normalized = value.trim();
        try {
            URI uri = new URI(normalized);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new IllegalArgumentException("Backend URL is not valid");
            }
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("Backend URL is not valid", exception);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
