///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 11+
//DEPS org.eclipse.jetty:jetty-proxy:11.0.26
//DEPS org.eclipse.jetty:jetty-server:11.0.26
//DEPS org.eclipse.jetty:jetty-servlet:11.0.26
//DEPS org.eclipse.jetty:jetty-slf4j-impl:11.0.26
//FILES jetty-logging.properties=jetty.props

import org.eclipse.jetty.proxy.ProxyServlet;
import org.eclipse.jetty.proxy.ConnectHandler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

/**
 * A standard (forward) proxy server using Jetty 11.
 * Supports both HTTP and HTTPS (CONNECT requests).
 * Logs the target URL of each incoming request to a separate, randomly named file
 * in the 'request_logs' directory.
 * Includes JBang headers for direct execution (requires Java 11+).
 * Uses jakarta.servlet API as required by Jetty 11.
 */
public class StandardProxyServer {

    // Directory to store the log files
    private static final Path LOG_DIRECTORY = Paths.get("request_logs");

    /**
     * Helper method to write the URL to a unique file in the log directory.
     *
     * @param url The URL string to write.
     */
    private static void writeUrlToFile(String url) {
        try {
            // Ensure the log directory exists
            if (!Files.exists(LOG_DIRECTORY)) {
                Files.createDirectories(LOG_DIRECTORY);
                System.out.println("Created log directory: " + LOG_DIRECTORY.toAbsolutePath());
            }

            // Generate a random filename
            String randomFileName = UUID.randomUUID().toString() + ".url";
            Path logFilePath = LOG_DIRECTORY.resolve(randomFileName);

            // Write the URL to the file
            Files.writeString(logFilePath, url, StandardCharsets.UTF_8);
            // Optional: Log confirmation to console
            // System.out.println("Logged URL to: " + logFilePath);

        } catch (IOException e) {
            System.err.println("Error writing URL log file: " + e.getMessage());
            // Print stack trace for debugging, but don't stop the server
            e.printStackTrace();
        }
    }

    /**
     * Custom ConnectHandler that logs the target host:port before tunneling.
     */
    public static class LoggingConnectHandler extends ConnectHandler {
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
                throws ServletException, IOException {

            // The 'target' in ConnectHandler.handle is typically the host:port string
            // for the CONNECT request.
            String connectTarget = request.getRequestURI(); // URI contains the host:port
            System.out.println("CONNECT request received for: " + connectTarget); // Console log
            writeUrlToFile("CONNECT " + connectTarget); // Log CONNECT target to file

            // Proceed with the default ConnectHandler behavior (tunneling)
            super.handle(target, baseRequest, request, response);
        }
    }

    /**
     * Custom ProxyServlet that logs the full request URL before proxying.
     */
    public static class LoggingProxyServlet extends ProxyServlet {
        @Override
        protected void service(HttpServletRequest request, HttpServletResponse response)
                throws ServletException, IOException {

            // Reconstruct the full URL for standard HTTP requests
            StringBuffer urlBuffer = request.getRequestURL();
            String queryString = request.getQueryString();
            if (queryString != null) {
                urlBuffer.append('?').append(queryString);
            }
            String fullUrl = urlBuffer.toString();

            System.out.println(request.getMethod() + " request received for: " + fullUrl); // Console log
            writeUrlToFile(request.getMethod() + " " + fullUrl); // Log method + URL to file

            // Proceed with the default ProxyServlet behavior
            super.service(request, response);
        }
    }


    /**
     * The main method to start the proxy server.
     *
     * @param args Command line arguments (optional). The first argument can be the port number.
     * @throws Exception If there's an error starting the server.
     */
    public static void main(String[] args) throws Exception {
        // Default port for the proxy server
        int port = 8080;

        // Allow overriding the port via command line argument
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.err.println("Invalid port number provided. Using default port " + port);
            }
        }

        System.out.println("Starting Standard Proxy Server with URL logging on port " + port + "...");

        // Create a new Jetty server instance listening on the specified port
        Server server = new Server(port);

        // --- Handler Setup ---

        // 1. Use the LoggingConnectHandler for HTTPS (CONNECT requests)
        ConnectHandler connectHandler = new LoggingConnectHandler();
        // Optional: Configure ConnectHandler
        // connectHandler.setWhiteListHosts("*.example.com");

        // 2. ServletContextHandler for HTTP (regular requests)
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");

        // 3. Use the LoggingProxyServlet within the ServletHolder
        ServletHolder proxyServletHolder = new ServletHolder(new LoggingProxyServlet());
        // Optional: Configure proxy servlet init parameters
        // proxyServletHolder.setInitParameter("maxThreads", "200");
        // proxyServletHolder.setInitParameter("timeout", "120000");

        // Add the logging proxy servlet to the context
        context.addServlet(proxyServletHolder, "/*");

        // 4. HandlerCollection to chain handlers
        HandlerCollection handlers = new HandlerCollection();
        handlers.addHandler(connectHandler); // Handles CONNECT requests first (logs internally)
        handlers.addHandler(context);        // Handles standard HTTP requests (logs internally)

        // Set the chained handlers for the server
        server.setHandler(handlers);

        try {
            // Start the Jetty server
            server.start();
            System.out.println("Server started successfully on port " + port);
            System.out.println("Request URLs will be logged to files in the '" + LOG_DIRECTORY.toAbsolutePath() + "' directory.");
            System.out.println("Configure clients to use this server as their HTTP and HTTPS proxy.");

            // Keep the server running
            server.join();
        } catch (Exception e) {
            System.err.println("Error starting or running the server:");
            e.printStackTrace();
            if (server.isStarted()) {
                server.stop();
            }
        }
    }
}
