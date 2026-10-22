package io.github.hellsong.composemonitor.gradle;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Maps a Kotlin version to the compiler plugin build for it. The table comes from compilerLines in build.gradle.kts. */
final class KotlinVersionSupport {
    private static final Pattern VERSION = Pattern.compile("^(\\d+)\\.(\\d+)(?:\\.(\\d+))?");

    static final class CompilerLine {
        final String minKotlin;
        final String artifactId;
        final boolean supportsPluginOrder;

        CompilerLine(String minKotlin, String artifactId) {
            this.minKotlin = minKotlin;
            this.artifactId = artifactId;
            this.supportsPluginOrder = compare(minKotlin, BuildConfig.PLUGIN_ORDER_KOTLIN_VERSION) >= 0;
        }
    }

    /** Ascending by minKotlin. */
    private static final List<CompilerLine> LINES = new ArrayList<>();

    static {
        for (String entry : BuildConfig.COMPILER_LINES.split(";")) {
            String[] parts = entry.split("=");
            LINES.add(new CompilerLine(parts[0], parts[1]));
        }
    }

    private KotlinVersionSupport() {
    }

    static String oldestSupported() {
        return LINES.get(0).minKotlin;
    }

    /** The newest line not newer than {@code kotlinVersion}, or null if it is older than every line. */
    static CompilerLine lineFor(String kotlinVersion) {
        CompilerLine result = null;
        for (CompilerLine line : LINES) {
            if (compare(kotlinVersion, line.minKotlin) >= 0) result = line;
        }
        return result;
    }

    /** True for a Kotlin minor version above the newest line, which may have changed the IR API. */
    static boolean isNewerThanTested(String kotlinVersion) {
        int[] newest = parse(LINES.get(LINES.size() - 1).minKotlin);
        int[] version = parse(kotlinVersion);
        return version[0] > newest[0] || (version[0] == newest[0] && version[1] > newest[1]);
    }

    /** Compares the numeric part only, so "2.1.20-RC" equals "2.1.20". */
    static int compare(String a, String b) {
        int[] x = parse(a);
        int[] y = parse(b);
        for (int i = 0; i < 3; i++) {
            if (x[i] != y[i]) return Integer.compare(x[i], y[i]);
        }
        return 0;
    }

    private static int[] parse(String version) {
        Matcher matcher = VERSION.matcher(version);
        if (!matcher.find()) throw new IllegalArgumentException("Unrecognized Kotlin version: " + version);
        String patch = matcher.group(3);
        return new int[] {
            Integer.parseInt(matcher.group(1)),
            Integer.parseInt(matcher.group(2)),
            patch == null ? 0 : Integer.parseInt(patch),
        };
    }
}
