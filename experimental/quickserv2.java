///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS info.picocli:picocli:4.5.0
//DEPS org.slf4j:slf4j-api:2.0.0-alpha5 org.slf4j:slf4j-simple:2.0.0-alpha5
//JAVA 9+

import java.io.*;
import java.lang.ProcessBuilder.Redirect;
import java.net.*;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.sun.net.httpserver.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import static java.net.HttpURLConnection.HTTP_OK;
import static java.net.HttpURLConnection.HTTP_BAD_METHOD;
import static java.net.HttpURLConnection.HTTP_INTERNAL_ERROR;
import static java.net.HttpURLConnection.HTTP_NOT_FOUND;

@Command(name = "quickserv", mixinStandardHelpOptions = true, version = "quickserv 0.1",
        description = "quickserv made with jbang")
class quickserv2 implements Callable<Integer> {
    private final Logger logger = LoggerFactory.getLogger(quickserv2.class);

    @Option(names = {"--directory", "-d"}, description = "The directory to serve files from", defaultValue = "")
    private Path directory;

    @Option(names = {"--interface", "-i"}, description = "The interface to use for the server", defaultValue = "0.0.0.0", required = true)
    private String iface;

    @Option(names = {"--port", "-p"}, description = "The port to use for the server", defaultValue = "8080", required = true)
    private Integer port;

    @Option(names = {"--verbose", "-v"}, description = "Be verbose about what's happening")
    private boolean verbose;

    public static void main(String... args) {
        int exitCode = new CommandLine(new quickserv2()).execute(args);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    @Override
    public Integer call() throws Exception {
        // Create server and bind the port to listen to
        InetSocketAddress addr = new InetSocketAddress(iface, port);
        HttpServer server = HttpServer.create(addr, 0);
        server.createContext("/", this::handleRequest);
        server.setExecutor(null);
        // Start to accept incoming connections
        server.start();
        logger.info("Server started on {}:{}", iface, port);
        Thread.currentThread().join();
        return 0;
    }

    public void handleRequest(HttpExchange x) throws IOException {
        if (!"GET".equalsIgnoreCase(x.getRequestMethod())) {
            logger.debug("405 Method Not Allowed: {}", x.getRequestMethod());
            sendError(x, HTTP_BAD_METHOD);
            return;
        }

        try {
            logger.debug("GET {}", x.getRequestURI());
            int result = handleGet(x);
            logger.debug("Status: {}", result);
        } catch (Exception ex) {
            sendError(x, HTTP_INTERNAL_ERROR);
            logger.debug("500 Internal Server Error");
            if (logger.isDebugEnabled()) {
                logger.debug("Status 500", ex);
            }
        }
    }

    private int handleGet(HttpExchange x) throws IOException {
        Path reqPath = uriToPath(x.getRequestURI());

        if (Files.isDirectory(reqPath)) {
            Path index = findIndex(reqPath);
            if (index != null) {
                reqPath = index;
            }
        }

        if (Files.isDirectory(reqPath)) {
            return sendDirectoryIndexResponse(x, reqPath);
        } else if (Files.isRegularFile(reqPath) && !Files.isHidden(reqPath)) {
            if (isExecutable(reqPath)) {
                return execute(x, reqPath);
            } else {
                return sendFileResponse(x, reqPath);
            }
        } else {
            return sendError(x, HTTP_NOT_FOUND);
        }
    }

    private static boolean isExecutable(Path file) {
        if (Files.isRegularFile(file) && Files.isReadable(file)) {
            String os = System.getProperty("os.name").toLowerCase(Locale.ENGLISH);
            if (os.startsWith("win")) {
                String name = file.getFileName().toString();
                return name.endsWith(".exe") || name.endsWith(".cmd") || name.endsWith(".ps1");
            } else {
                return Files.isExecutable(file);
            }
        }
        return false;
    }

    private int execute(HttpExchange x, Path reqPath) throws IOException {
        var cmd = new ArrayList<String>();
        cmd.add(reqPath.toAbsolutePath().toString());

        // Add all parameters as "--param value" arguments
        parameters(x).entrySet().forEach(e -> {
            for (String v : e.getValue()) {
                cmd.add("--" + e.getKey());
                cmd.add(v);
            }
        });

        String cmdStr = String.join(" ", cmd);
        var shCmd = new ArrayList<String>();
        shCmd.add("sh");
        shCmd.add("-c");
        shCmd.add(cmdStr);

        logger.info("Executing: sh -c " + cmdStr + "\"");

        var pb = new ProcessBuilder(cmd)
            .directory(reqPath.getParent().toAbsolutePath().toFile())
            .redirectError(Redirect.INHERIT);

        // Set REQUEST_METHOD env var, just like CGI
        pb.environment().put("REQUEST_METHOD", x.getRequestMethod());

        // Add all request headers as env vars, just like CGI
        for (String name : x.getRequestHeaders().keySet()) {
            String hdr = "HEADER_" + name.replace("-", "_");
            List<String> values = x.getRequestHeaders().get(name);
            String value = String.join(",", values);
            pb.environment().put(hdr, value);
        }
        
        Process process = pb.start();

        //x.getResponseHeaders().add("Transfer-Encoding", "chunked");
        //x.getResponseHeaders().add("Content-Disposition", "inline; filename=\"" + reqPath.getFileName().toString() + "\"");
        Headers headers = x.getResponseHeaders();
        headers.set("Content-Type", "text/plain; charset=UTF-8");
        x.sendResponseHeaders(200, 0);

        // Copy input/output to/from command to request/response
        var rin = x.getRequestBody();
        var pout = process.getOutputStream();
        var inAlive = new AtomicBoolean();
        var inc = acopy(rin, pout, inAlive);
        inc.thenRun(() -> {
            close(pout);
            close(rin);
        });
        var pin = process.getInputStream();
        var rout = x.getResponseBody();
        var outAlive = new AtomicBoolean();
        var outc = acopy(pin, rout, outAlive);
        outc.thenRun(() -> {
                if (process.isAlive()) {
                    logger.warn("Terminating command: " + cmdStr);
                    process.destroyForcibly();
                }
                close(pin);
                close(rout);
            });

        try {
            // We wait for the command to finish, checking
            // every 15s if any output was written
            boolean terminated;
            while (!(terminated = process.waitFor(15, TimeUnit.SECONDS)) && outAlive.get()) {
                logger.debug("Command still alive: " + cmdStr);
                outAlive.set(false);
            }
            // .. and if not, we terminate the command
            if (!terminated) {
                logger.warn("Terminating command: " + cmdStr);
                process.destroyForcibly();
            }
            // Wait for the in/out copiers to finish their work
            inc.join();
            outc.join();
            logger.info("Done");
        } catch (InterruptedException e) {
            logger.info("Command was interrupted");
        }
        return HTTP_OK;
    }

    private Map<String, List<String>> parameters(HttpExchange x) {
        return Collections.emptyMap();
    }
    
    private int sendDirectoryIndexResponse(HttpExchange x, Path folder) throws IOException {
        sendHtmlResponse(x, HTTP_OK, writer -> {
            writer.accept("<html><head>");
            writer.accept("<title>Directory of: /");
            writer.accept(directory.relativize(folder).toString());
            writer.accept("</title>");
            writer.accept("</head><body>");
            writer.accept("<h1>Directory of: ");
            writer.accept(directory.relativize(folder).toString());
            writer.accept("</h1>");
    
            if (!folder.equals(directory)) {
                Path rel = directory.relativize(folder);
                String p = rel.getParent() != null ? rel.getParent().toString() : "";
                writer.accept("<a href=\"/");
                writer.accept(p.replace("\\", "/"));
                writer.accept("\">../</a><br>");
            }
            Files.list(folder).sorted().forEach(p -> {
                try {
                    Path rel = directory.relativize(p);
                    if (!Files.isHidden(p)) {
                        writer.accept("<a href=\"");
                        writer.accept(rel.toString().replace("\\", "/"));
                        writer.accept("\">");
                        writer.accept(rel.toString());
                        if (Files.isDirectory(rel)) {
                            writer.accept("/");
                        }
                        writer.accept("</a><br>");
                    }
                } catch (IOException e) {
                    // Ignore
                }
            });
    
            writer.accept("</body></html>");    
        });
        return HTTP_OK;
    }

    private int sendFileResponse(HttpExchange x, Path file) throws IOException {
        String ct = guessContentType(file);
        String contentType = ct != null ? ct : "application/octet-stream";
        logger.debug("Detected Content-Type: {}", contentType);

        Headers headers = x.getResponseHeaders();
        headers.set("Content-Type", contentType);
        long fileLength = Files.size(file);
        x.sendResponseHeaders(200, fileLength);

        try (InputStream in = Files.newInputStream(file);
                OutputStream out = x.getResponseBody()) {
            in.transferTo(out);
        } catch (FileNotFoundException fnfe) {
            return sendError(x, HTTP_NOT_FOUND);
        }

        return HTTP_OK;
    }

    private String guessContentType(Path file) throws IOException {
        String ct = Files.probeContentType(file);
        if (ct == null) {
            // The probe only seems to look at file names
            // Let's check if the file just contains text
            try {
                Files.lines(file).forEach(ln -> {});
                // We could do even more but let's just say it's plain text
                ct = "text/plain";
            } catch (UncheckedIOException ex) {
                // Ignore and return null
            }
        }
        return ct;
    }

    private int sendError(HttpExchange x, int status) throws IOException {
        sendHtmlResponse(x, status, wrapHtml("Failure: " + status, "HTTPD Error Page for " + status));
        return status;
    }

    private String wrapHtml(String body, String title) {
        return "<html><head><title>" + title + "</title></head><body>" + body + "</body></html>";
    }

    private void sendHtmlResponse(HttpExchange x, int status, String response) throws IOException {
        sendHtmlHeaders(x, status, response.length());
        try (OutputStream os = x.getResponseBody()) {
            os.write(response.getBytes("UTF-8"));
        }
    }

    private void sendHtmlResponse(HttpExchange x, Integer status, CheckedConsumer<CheckedConsumer<String>> y) throws IOException {
        sendHtmlHeaders(x, status, 0);
        try (OutputStream os = x.getResponseBody()) {
            y.accept(s -> os.write(s.getBytes("UTF-8")));
        }
    }

    private void sendHtmlHeaders(HttpExchange x, int status, long length) throws IOException {
        Headers headers = x.getResponseHeaders();
        headers.set("Content-Type", "text/html; charset=UTF-8");
        x.sendResponseHeaders(status, length);
    }

    @FunctionalInterface
    public interface CheckedConsumer<T> {
       void accept(T t) throws IOException;
    }

    private Path uriToPath(URI uri) throws IOException {
        String path = uri.getPath();
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        Path result = directory.resolve(path).normalize();
        if (!result.toAbsolutePath().startsWith(directory.toAbsolutePath())) {
            return directory;
        }
        return result;
    }

    private Path findIndex(Path dir) throws IOException {
        return Files.find(dir, 1, (path, attrs) -> {
            String nm = path.getFileName().toString();
            return nm.equals("index.html")
                || nm.equals("index.htm")
                || nm.startsWith("index.") && isExecutable(path);
        })
        .findFirst()
        .orElse(dir);
    }

    private static CompletableFuture<Void> acopy(InputStream source, OutputStream target, AtomicBoolean alive) {
        return CompletableFuture.runAsync(() -> {
            try {
                copy(source, target, alive);                
            } catch (IOException e) {
                // Ignore
            }
        });
    }

    // Copy all bytes from input stream to output stream
    // while flushing the output stream each time we
    // encounter an EOL character.
    private static void copy(InputStream in, OutputStream out, AtomicBoolean alive) throws IOException {
        int c;
        while ((c = in.read()) >= 0) {
            out.write(c);
            if (c == '\n' || c == '\r') {
                out.flush();
                alive.set(true);
            }
        }
    }

    private static void close(Closeable c) {
        try {
            c.close();
        } catch (IOException e) {
            // Ignore
        }
    }
}
