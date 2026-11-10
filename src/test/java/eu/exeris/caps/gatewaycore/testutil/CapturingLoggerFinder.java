package eu.exeris.caps.gatewaycore.testutil;

import java.util.List;
import java.util.ResourceBundle;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A {@link System.LoggerFinder} that keeps every record in memory, keyed by logger name, so a test
 * can assert what a class logged through {@link System.Logger}. Registered through
 * {@code META-INF/services} on the test classpath; nothing is written anywhere else.
 */
public final class CapturingLoggerFinder extends System.LoggerFinder {

    /** One captured log call. */
    public record Record(System.Logger.Level level, String message, Throwable thrown) {
    }

    private static final ConcurrentHashMap<String, List<Record>> RECORDS = new ConcurrentHashMap<>();

    public static List<Record> records(String loggerName) {
        return List.copyOf(RECORDS.getOrDefault(loggerName, List.of()));
    }

    public static void clear() {
        RECORDS.clear();
    }

    @Override
    public System.Logger getLogger(String name, Module module) {
        return new System.Logger() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public boolean isLoggable(Level level) {
                return true;
            }

            @Override
            public void log(Level level, ResourceBundle bundle, String msg, Throwable thrown) {
                RECORDS.computeIfAbsent(name, k -> new CopyOnWriteArrayList<>()).add(new Record(level, msg, thrown));
            }

            @Override
            public void log(Level level, ResourceBundle bundle, String format, Object... params) {
                RECORDS.computeIfAbsent(name, k -> new CopyOnWriteArrayList<>())
                        .add(new Record(level, format, null));
            }
        };
    }
}
