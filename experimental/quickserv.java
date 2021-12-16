///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS info.picocli:picocli:4.5.0
//DEPS org.eclipse.jetty:jetty-server:11.0.7
//DEPS org.slf4j:slf4j-api:2.0.0-alpha5 org.eclipse.jetty:jetty-slf4j-impl:11.0.7
//JAVA 9+

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ProcessBuilder.Redirect;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.util.resource.PathResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.MultipartConfigElement;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "quickserv", mixinStandardHelpOptions = true, version = "quickserv 0.1",
        description = "quickserv made with jbang")
class quickserv implements Callable<Integer> {
    private final Logger logger = LoggerFactory.getLogger(quickserv.class);

    @Option(names = {"--directory", "-d"}, description = "The greeting to print", defaultValue = "")
    private Path directory;

    public static void main(String... args) {
        int exitCode = new CommandLine(new quickserv()).execute(args);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    @Override
    public Integer call() throws Exception {
        System.out.println("Executable files found:");
        Files.walk(directory, FileVisitOption.FOLLOW_LINKS)
            .filter(p -> isExecutable(p))
            .forEach(p -> System.out.println("   " + p));

        var server = new Server(8080);

        Handler execHandler = new AbstractHandler() {
            @Override
            public void handle(String target, Request baseReq, HttpServletRequest req, HttpServletResponse res) throws IOException, ServletException {
                Path reqPath = directory.resolve(target.substring(1));
                logger.info("Incoming request for: " + reqPath);
                if (Files.isDirectory(reqPath)) {
                    reqPath = Files.find(reqPath, 1, (path, attrs) -> {
                            return path.getFileName().toString().startsWith("index.")
                                && isExecutable(path);
                        })
                        .findFirst()
                        .orElse(reqPath);
                }
                if (isExecutable(reqPath)) {
                    req.setAttribute(Request.__MULTIPART_CONFIG_ELEMENT, new MultipartConfigElement((String)null));
                    boolean noExec = req.getParameterMap().size() == 1 && req.getParameterMap().containsKey("__noexec__");
                    if (!noExec) {
                        execute(reqPath, req, res);
                        baseReq.setHandled(true);
                    }
                }
            }
        };
        
        final var filesHandler = new ResourceHandler();
        filesHandler.setBaseResource(new PathResource(directory));
        filesHandler.setDirAllowed(true);
        
        server.setHandler(new HandlerList(execHandler, filesHandler));
        server.start();
        server.join();
        
        return 0;
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

    private void execute(Path reqPath, HttpServletRequest req, HttpServletResponse res) throws IOException {
        res.setHeader("Transfer-Encoding", "chunked");
        res.setHeader("Content-Disposition", "inline; filename=\"" + reqPath.getFileName().toString() + "\"");
        res.setStatus(HttpServletResponse.SC_OK);

        var cmd = new ArrayList<String>();
        cmd.add(reqPath.toAbsolutePath().toString());

        // Add all parameters as "--param value" arguments
        req.getParameterMap().entrySet().forEach(e -> {
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
        pb.environment().put("REQUEST_METHOD", req.getMethod());

        // Add all request headers as env vars, just like CGI
        for (String name : Collections.list(req.getHeaderNames())) {
            String hdr = "HEADER_" + name.replace("-", "_");
            String value = String.join(",", Collections.list(req.getHeaders(name)));
            pb.environment().put(hdr, value);
        }
        
        Process process = pb.start();
        acopy(req.getInputStream(), process.getOutputStream())
            .thenRun(() -> close(process.getOutputStream()));
        try {
            copy(process.getInputStream(), res.getOutputStream());
            if (!process.waitFor(50, TimeUnit.SECONDS)) {
                logger.warn("Terminating command: " + cmdStr);
                process.destroyForcibly();
            }
            logger.info("Done");
        } catch (InterruptedException e) {
            logger.warn("Terminating command: " + cmdStr);
            process.destroyForcibly();
        }
    }

    private static CompletableFuture<Void> acopy(InputStream source, OutputStream target) {
        return CompletableFuture.runAsync(() -> {
            try {
                copy(source, target);                
            } catch (IOException e) {
                // Ignore
            }
        });
    }

    // Copy all bytes from input stream to output stream
    // while flushing the output stream each time we
    // encounter an EOL character.
    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer, 0, 8192)) >= 0) {
            int start = 0;
            int length = read;
            int pos;
            while ((pos = findEOL(buffer, start, length)) >= 0) {
                int wlen = pos - start + 1;
                out.write(buffer, start, wlen);
                out.flush();
                start = pos + 1;
                length -= wlen;
            }
            out.write(buffer, start, length);
        }
        out.flush();
    }

    private static int findEOL(byte[] buffer, int start, int size) {
        for (int i=start; i<size; i++) {
            byte c = buffer[i];
            if (c == '\n') {
                if ((i+1) < size && buffer[i+1] == '\r') {
                    i++;
                }
                return i;
            } else if (c == '\r') {
                return i;
            }
        }
        return -1;
    }

    private static void close(Closeable c) {
        try {
            c.close();
        } catch (IOException e) {
            // Ignore
        }
    }
}
