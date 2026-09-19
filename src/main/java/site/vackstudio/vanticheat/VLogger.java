package site.vackstudio.vanticheat;

import org.slf4j.Logger;

public final class VLogger {

    private static Logger logger;

    private VLogger() {}

    public static void setLogger(Logger logger) {
        VLogger.logger = logger;
    }

    public static void info(String msg, Object... args) {
        if (logger != null) {
            logger.info(format(msg, args));
        } else {
            System.out.println(format(msg, args));
        }
    }

    public static void warn(String msg, Object... args) {
        if (logger != null) {
            logger.warn(format(msg, args));
        } else {
            System.out.println("[WARN] " + format(msg, args));
        }
    }

    public static void error(String msg, Throwable t) {
        if (logger != null) {
            logger.error(format(msg), t);
        } else {
            System.err.println("[ERROR] " + format(msg));
            t.printStackTrace(System.err);
        }
    }

    public static void error(String msg, Object... args) {
        if (logger != null) {
            logger.error(format(msg, args));
        } else {
            System.err.println("[ERROR] " + format(msg, args));
        }
    }

    public static void debug(String msg, Object... args) {
        if (logger != null && logger.isDebugEnabled()) {
            logger.debug(format(msg, args));
        }
    }

    private static String format(String msg, Object... args) {
        if (args == null || args.length == 0) return msg;
        // Preferred slf4j-style "{}" placeholders, filled in order.
        for (Object arg : args) {
            int at = msg.indexOf("{}");
            if (at < 0) break;
            msg = msg.substring(0, at) + String.valueOf(arg) + msg.substring(at + 2);
        }
        // Legacy indexed "{0}" style retained for backward compatibility.
        for (int i = 0; i < args.length; i++) {
            msg = msg.replace("{" + i + "}", String.valueOf(args[i]));
        }
        return msg;
    }
}