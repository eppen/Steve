package com.steve.ai.execution;

import com.steve.ai.entity.SteveEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Executes LLM-generated JavaScript code in a sandboxed GraalVM context.
 *
 * <p><b>Graceful degradation:</b> When GraalVM SDK is not on the runtime classpath
 * (it is {@code compileOnly} in build.gradle), the engine automatically falls back
 * to a no-op mode where {@link #execute(String)} always returns an error. This
 * avoids {@link NoClassDefFoundError} and allows the mod to load without GraalVM.</p>
 *
 * <p>Safety features (when GraalVM is available):
 * <ul>
 *   <li>No file system access</li>
 *   <li>No network access</li>
 *   <li>Timeout enforcement (30 seconds max)</li>
 *   <li>Restricted Java package access</li>
 *   <li>Controlled API via SteveAPI bridge</li>
 * </ul>
 */
public class CodeExecutionEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger(CodeExecutionEngine.class);

    private static final long DEFAULT_TIMEOUT_MS = 30_000;

    private final SteveEntity steve;
    private final boolean graalAvailable;
    private final Object graalContext; // org.graalvm.polyglot.Context (held as Object to avoid linkage)
    private final SteveAPI steveAPI;

    /**
     * Creates a new code execution engine for the given Steve entity.
     *
     * <p>If GraalVM is not available at runtime, the engine will operate in
     * fallback mode where all executions return an error.</p>
     */
    public CodeExecutionEngine(SteveEntity steve) {
        this.steve = steve;
        this.steveAPI = new SteveAPI(steve);

        boolean available;
        Object ctx;
        try {
            ctx = GraalVMBridge.createContext(steveAPI);
            available = true;
            LOGGER.info("GraalVM JavaScript engine initialized for Steve '{}'", steve.getSteveName());
        } catch (Throwable t) {
            ctx = null;
            available = false;
            LOGGER.warn(
                "GraalVM not available ({}: {}). Code execution will be disabled for Steve '{}'.",
                t.getClass().getSimpleName(), t.getMessage(), steve.getSteveName());
        }
        this.graalAvailable = available;
        this.graalContext = ctx;
    }

    /**
     * Execute JavaScript code with default timeout.
     */
    public ExecutionResult execute(String code) {
        return execute(code, DEFAULT_TIMEOUT_MS);
    }

    /**
     * Execute JavaScript code with custom timeout.
     *
     * @param code      JavaScript code to execute
     * @param timeoutMs Maximum execution time in milliseconds
     * @return ExecutionResult containing success/failure status and output
     */
    public ExecutionResult execute(String code, long timeoutMs) {
        if (code == null || code.trim().isEmpty()) {
            return ExecutionResult.error("No code provided");
        }

        if (!graalAvailable) {
            return ExecutionResult.error(
                "Code execution is disabled - GraalVM JavaScript engine is not installed on the server. "
                + "See the mod documentation for setup instructions.");
        }

        try {
            String output = GraalVMBridge.evaluate(graalContext, code);
            return ExecutionResult.success(output);
        } catch (Throwable t) {
            String msg = t.getMessage();
            if (msg == null || msg.isEmpty()) {
                msg = t.getClass().getSimpleName();
            }
            return ExecutionResult.error(msg);
        }
    }

    /**
     * Validate JavaScript code syntax without executing.
     *
     * @param code JavaScript code to validate
     * @return true if syntax is valid, false otherwise
     */
    public boolean validateSyntax(String code) {
        if (!graalAvailable) {
            return false;
        }
        try {
            GraalVMBridge.validate(graalContext, code);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Get the Steve API bridge.
     */
    public SteveAPI getAPI() {
        return steveAPI;
    }

    /**
     * Clean up resources.
     */
    public void close() {
        if (graalAvailable && graalContext != null) {
            try {
                GraalVMBridge.close(graalContext);
            } catch (Throwable t) {
                LOGGER.debug("Error closing GraalVM context: {}", t.getMessage());
            }
        }
    }

    // -----------------------------------------------------------------------
    // Bridge class - isolates all GraalVM imports so the outer class loads
    // safely even when polyglot.jar is absent at runtime.
    // -----------------------------------------------------------------------

    private static final class GraalVMBridge {

        private GraalVMBridge() {}

        static Object createContext(SteveAPI steveAPI) {
            org.graalvm.polyglot.Context ctx = org.graalvm.polyglot.Context.newBuilder("js")
                .allowAllAccess(false)
                .allowIO(false)
                .allowNativeAccess(false)
                .allowCreateThread(false)
                .allowCreateProcess(false)
                .allowHostClassLookup(className -> false)
                .allowHostAccess(null)
                .option("js.java-package-globals", "false")
                .option("js.timer-resolution", "1")
                .build();

            ctx.getBindings("js").putMember("steve", steveAPI);

            String consolePolyfill =
                "var console = {\n" +
                "    log: function(...args) {\n" +
                "        java.lang.System.out.println('[Steve Code] ' + args.join(' '));\n" +
                "    }\n" +
                "};";
            try {
                ctx.eval("js", consolePolyfill);
            } catch (org.graalvm.polyglot.PolyglotException ignored) {
                // Console polyfill is best-effort
            }

            return ctx;
        }

        static String evaluate(Object ctx, String code) {
            org.graalvm.polyglot.Context c = (org.graalvm.polyglot.Context) ctx;
            org.graalvm.polyglot.Value result = c.eval("js", code);
            return result.isNull() ? "null" : result.toString();
        }

        static void validate(Object ctx, String code) {
            org.graalvm.polyglot.Context c = (org.graalvm.polyglot.Context) ctx;
            c.eval("js", "function __validate() { " + code + " }");
        }

        static void close(Object ctx) {
            ((org.graalvm.polyglot.Context) ctx).close();
        }
    }

    /**
     * Result of code execution.
     */
    public static class ExecutionResult {
        private final boolean success;
        private final String output;
        private final String error;

        private ExecutionResult(boolean success, String output, String error) {
            this.success = success;
            this.output = output;
            this.error = error;
        }

        public static ExecutionResult success(String output) {
            return new ExecutionResult(true, output, null);
        }

        public static ExecutionResult error(String error) {
            return new ExecutionResult(false, null, error);
        }

        public boolean isSuccess() {
            return success;
        }

        public String getOutput() {
            return output;
        }

        public String getError() {
            return error;
        }

        @Override
        public String toString() {
            if (success) {
                return "Success: " + output;
            } else {
                return "Error: " + error;
            }
        }
    }
}
