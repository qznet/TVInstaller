package com.tvig.installer.net;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import okhttp3.HttpUrl;

/** Builds transparent GitHub download routes and remembers the last successful one. */
public final class GitHubRouteManager {
    private static final String PREFERENCES = "github_routes";
    private static final String KEY_PREFERRED_ROUTE = "preferred_route";

    private static final String ROUTE_GITWARP = "gitwarp";
    private static final String ROUTE_GH_PROXY = "gh_proxy";
    private static final String ROUTE_GHFAST = "ghfast";
    private static final String ROUTE_GHPROXY_NET = "ghproxy_net";
    private static final String ROUTE_ORIGIN = "origin";

    private static final String[] DEFAULT_ORDER = {
            ROUTE_GITWARP,
            ROUTE_GH_PROXY,
            ROUTE_GHFAST,
            ROUTE_GHPROXY_NET,
            ROUTE_ORIGIN
    };

    private final SharedPreferences preferences;

    public GitHubRouteManager(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        Context applicationContext = context.getApplicationContext();
        Context safeContext = applicationContext != null ? applicationContext : context;
        preferences = safeContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    public List<String> candidates(String originalUrl) {
        if (!isSupportedGitHubUrl(originalUrl)) {
            List<String> unchanged = new ArrayList<>(1);
            unchanged.add(originalUrl);
            return unchanged;
        }

        Set<String> routes = new LinkedHashSet<>();
        String preferred = preferences.getString(KEY_PREFERRED_ROUTE, null);
        if (isKnownRoute(preferred)) {
            routes.add(preferred);
        }
        for (String route : DEFAULT_ORDER) {
            routes.add(route);
        }

        List<String> urls = new ArrayList<>(routes.size());
        for (String route : routes) {
            urls.add(buildRouteUrl(route, originalUrl));
        }
        return urls;
    }

    public void markSuccess(String candidateUrl) {
        String route = routeOf(candidateUrl);
        if (route != null) {
            preferences.edit().putString(KEY_PREFERRED_ROUTE, route).apply();
        }
    }

    public void markFailure(String candidateUrl) {
        String failedRoute = routeOf(candidateUrl);
        String preferred = preferences.getString(KEY_PREFERRED_ROUTE, null);
        if (failedRoute != null && failedRoute.equals(preferred)) {
            preferences.edit().remove(KEY_PREFERRED_ROUTE).apply();
        }
    }

    public static String routeName(String candidateUrl) {
        String route = routeOf(candidateUrl);
        return route == null ? ROUTE_ORIGIN : route;
    }

    static boolean isSupportedGitHubUrl(String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }
        HttpUrl parsed = HttpUrl.parse(url);
        if (parsed == null || !"https".equalsIgnoreCase(parsed.scheme())) {
            return false;
        }
        String host = parsed.host();
        return "github.com".equalsIgnoreCase(host)
                || "raw.githubusercontent.com".equalsIgnoreCase(host)
                || "objects.githubusercontent.com".equalsIgnoreCase(host)
                || "release-assets.githubusercontent.com".equalsIgnoreCase(host)
                || "github-releases.githubusercontent.com".equalsIgnoreCase(host);
    }

    static String buildRouteUrl(String route, String originalUrl) {
        if (ROUTE_GITWARP.equals(route)) {
            return "https://proxy.gitwarp.com/" + stripScheme(originalUrl);
        }
        if (ROUTE_GH_PROXY.equals(route)) {
            return "https://gh-proxy.com/" + originalUrl;
        }
        if (ROUTE_GHFAST.equals(route)) {
            return "https://ghfast.top/" + originalUrl;
        }
        if (ROUTE_GHPROXY_NET.equals(route)) {
            return "https://ghproxy.net/" + originalUrl;
        }
        return originalUrl;
    }

    private static String stripScheme(String url) {
        int separator = url == null ? -1 : url.indexOf("://");
        return separator >= 0 ? url.substring(separator + 3) : url;
    }

    private static boolean isKnownRoute(String route) {
        if (route == null) {
            return false;
        }
        for (String known : DEFAULT_ORDER) {
            if (known.equals(route)) {
                return true;
            }
        }
        return false;
    }

    private static String routeOf(String url) {
        if (url == null || url.isEmpty()) {
            return null;
        }
        HttpUrl parsed = HttpUrl.parse(url);
        if (parsed == null) {
            return null;
        }
        String host = parsed.host();
        if ("proxy.gitwarp.com".equalsIgnoreCase(host)) {
            return ROUTE_GITWARP;
        }
        if ("gh-proxy.com".equalsIgnoreCase(host)) {
            return ROUTE_GH_PROXY;
        }
        if ("ghfast.top".equalsIgnoreCase(host)) {
            return ROUTE_GHFAST;
        }
        if ("ghproxy.net".equalsIgnoreCase(host)) {
            return ROUTE_GHPROXY_NET;
        }
        return isSupportedGitHubUrl(url) ? ROUTE_ORIGIN : null;
    }
}
