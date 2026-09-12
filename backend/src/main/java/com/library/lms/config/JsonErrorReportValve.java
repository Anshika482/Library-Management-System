package com.library.lms.config;

import java.io.IOException;
import java.time.LocalDateTime;

import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ErrorReportValve;

/**
 * Answers container-level failures with this API's JSON error shape instead of
 * Tomcat's HTML error page.
 *
 * <p>Some requests never reach Spring. A URL containing an encoded slash, a
 * malformed request line or an over-long URI is refused by the connector while
 * it is still parsing, long before any filter or controller exists to answer
 * it. Tomcat then renders its own page, which announces the exact server
 * version - {@code Apache Tomcat/10.1.55} - and an HTML description of what it
 * disliked. Both are free reconnaissance, and neither looks anything like the
 * JSON every other error in this API returns.</p>
 *
 * <p>This valve replaces that page. It reports the status code and nothing
 * else: one fixed sentence, no server version, no exception, no stack trace, no
 * description of the request. The {@link Throwable} argument is deliberately
 * never read.</p>
 *
 * <p><b>It does not touch the application's own errors.</b> Every response this
 * API writes itself - the 401 from the entry point, the 403 from the
 * access-denied handler, everything from the exception handler - has already
 * written a body, and {@code getContentWritten() > 0} makes this return
 * immediately. Only a failure that produced no body at all reaches the JSON
 * below.</p>
 *
 * <p>The JSON is assembled by hand rather than with Jackson. Tomcat builds this
 * valve reflectively from a class name, so it has no access to Spring's
 * configured {@code ObjectMapper}; the shape is three fixed fields and a status
 * number, so there is nothing to serialise that string concatenation cannot do
 * safely. The message is a constant, so no caller-supplied text can reach it.</p>
 */
public class JsonErrorReportValve extends ErrorReportValve {

    /** The only sentence this valve ever sends. */
    private static final String MESSAGE = "The request could not be processed.";

    /**
     * Writes the JSON body for a failed response that has no body yet.
     *
     * <p>The three guards are Tomcat's own, in its order: ignore anything that
     * is not an error, ignore a response that already carries content, and
     * claim the right to report exactly once.</p>
     *
     * @param request   the request that failed, deliberately never read
     * @param response  the response to write the error into
     * @param throwable the cause, deliberately never read
     */
    @Override
    protected void report(Request request, Response response, Throwable throwable) {
        int statusCode = response.getStatus();

        if (statusCode < 400 || response.getContentWritten() > 0 || !response.setErrorReported()) {
            return;
        }

        try {
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("{\"status\":" + statusCode
                    + ",\"message\":\"" + MESSAGE + "\""
                    + ",\"timestamp\":\"" + LocalDateTime.now() + "\"}");
            response.finishResponse();
        } catch (IOException | IllegalStateException exception) {
            // The client has gone, or the response was committed after the
            // guards above. There is nothing left to send it.
        }
    }
}
