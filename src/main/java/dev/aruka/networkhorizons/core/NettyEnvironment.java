package dev.aruka.networkhorizons.core;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.security.CodeSource;
import java.util.Properties;

/**
 * Which Netty the game runs on. Stock 1.7.10 has 4.0.10.Final; a launcher or start script may swap in a newer
 * 4.0.x (see launcher/README.md). Nothing here loads Netty classes until a method needing them is called.
 */
public final class NettyEnvironment {

    public static final String NO_PREFER_DIRECT = "io.netty.noPreferDirect";
    /** Added after 4.0.10, so its presence means a newer Netty. */
    private static final String VERSION_CLASS = "io/netty/util/Version.class";

    private NettyEnvironment() {}

    /** True when the Netty on the classpath is newer than the stock 4.0.10. Does not initialize any Netty class. */
    public static boolean isNewerThanStock(ClassLoader loader) {
        return loader.getResource(VERSION_CLASS) != null;
    }

    /**
     * Newer 4.0.x Netty allocates direct buffers by default, and vanilla calls {@code array()} on buffers, so it
     * needs {@code -Dio.netty.noPreferDirect=true}. It must be on the command line: in GTNH, Netty reads it during
     * coremod discovery (FalsePattern's dependency loader uses Netty), before any mod code runs.
     */
    public static boolean missingNoPreferDirect() {
        return isNewerThanStock(NettyEnvironment.class.getClassLoader()) && directBufferPreferred();
    }

    /**
     * Netty version, e.g. 4.0.56.Final, read from the jar the classes were loaded from. Netty's own
     * {@code Version.identify()} merges the version files of every jar (Opis ships a 4.0.23 one), so it can lie.
     * 4.0.10 has no version file: reported as 4.0.10.Final.
     */
    public static String version() {
        URL jar = codeSource();
        if (jar == null) return "unknown";
        try {
            URL file = new URL("jar:" + jar.toExternalForm() + "!/META-INF/io.netty.versions.properties");
            Properties p = new Properties();
            try (InputStream in = file.openStream()) {
                p.load(in);
            }
            String v = p.getProperty("netty-common.version");
            return v != null ? v : "unknown";
        } catch (IOException e) {
            return "4.0.10.Final";
        }
    }

    private static URL codeSource() {
        CodeSource cs = io.netty.buffer.ByteBuf.class.getProtectionDomain()
            .getCodeSource();
        URL url = cs == null ? null : cs.getLocation();
        if (url == null) return null;
        // some class loaders report jar:file:...!/ instead of the jar itself
        String s = url.toExternalForm();
        if (s.startsWith("jar:")) {
            try {
                return new URL(s.substring(4, s.indexOf("!/")));
            } catch (IOException | RuntimeException e) {
                return null;
            }
        }
        return url;
    }

    /** File name of the jar Netty's classes were loaded from, to check which copy won on the classpath. */
    public static String source() {
        URL url = codeSource();
        return url == null ? "unknown" : new File(url.getPath()).getName();
    }

    public static boolean directBufferPreferred() {
        return io.netty.util.internal.PlatformDependent.directBufferPreferred();
    }

    /** One line for the log and the info commands. */
    public static String describe() {
        return "Netty " + version() + " from " + source() + ", direct buffers preferred: " + directBufferPreferred();
    }
}
