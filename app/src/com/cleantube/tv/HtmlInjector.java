package com.cleantube.tv;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Puts our script at the very top of YouTube's page, before YouTube's own scripts. */
final class HtmlInjector {
    private HtmlInjector() {}

    private static final Pattern CSP_META = Pattern.compile(
            "<meta[^>]+http-equiv\\s*=\\s*[\"']?content-security-policy[\"']?[^>]*>",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern HEAD_OPEN = Pattern.compile("<head(\\s[^>]*)?>", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_OPEN = Pattern.compile("<html(\\s[^>]*)?>", Pattern.CASE_INSENSITIVE);

    static String inject(String html, String script) {
        // A page-level security policy would block our inline script; drop it.
        html = CSP_META.matcher(html).replaceAll("");

        String tag = "<script>" + script.replace("</script", "<\\/script") + "</script>";

        Matcher m = HEAD_OPEN.matcher(html);
        if (m.find()) return html.substring(0, m.end()) + tag + html.substring(m.end());
        m = HTML_OPEN.matcher(html);
        if (m.find()) return html.substring(0, m.end()) + tag + html.substring(m.end());
        return tag + html;
    }
}
