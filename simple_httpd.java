//usr/bin/env jbang "$0" "$@" ; exit $?

import java.io.*;
import java.net.*;
import java.nio.charset.CharacterCodingException;
import java.nio.file.*;
import java.util.*;

import com.sun.net.httpserver.*;

import static java.net.HttpURLConnection.HTTP_OK;
import static java.net.HttpURLConnection.HTTP_BAD_METHOD;
import static java.net.HttpURLConnection.HTTP_FORBIDDEN;
import static java.net.HttpURLConnection.HTTP_INTERNAL_ERROR;
import static java.net.HttpURLConnection.HTTP_NOT_FOUND;

class simple_httpd implements HttpHandler {
    private boolean list;
    private List<String> indices;
    private boolean verbose;

    public simple_httpd(boolean list, List<String> indices, boolean verbose) {
        this.list = list;
        this.indices = indices;
        this.verbose = verbose;
    }

    public void handle(HttpExchange x) throws IOException {
        if (!"GET".equalsIgnoreCase(x.getRequestMethod())) {
            if (verbose) {
                System.out.println("405 Method Not Allowed: " + x.getRequestMethod());
            }
            sendError(x, HTTP_BAD_METHOD);
            return;
        }

        try {
            if (verbose) {
                System.out.print("GET " + x.getRequestURI() + " ");
            }
            String result = handleGet(x);
            if (verbose) {
                System.out.println(result);
            }
        } catch (Exception ex) {
            sendError(x, HTTP_INTERNAL_ERROR);
            if (verbose) {
                System.out.println("500 Internal Server Error");
                ex.printStackTrace();
            }
        }
    }

    private String handleGet(HttpExchange x) throws IOException {
        File root = new File(".");
        File file = uriToFile(x.getRequestURI());
        if (file.isHidden() || !file.exists() || childPath(root, file) == null) {
            sendError(x, HTTP_NOT_FOUND);
            return "404 Not Found";
        }

        if (file.isDirectory()) {
            File index = findIndex(file, indices);
            if (index != null) {
                file = index;
            }
        }
        if (file.isDirectory()) {
            if (!file.isDirectory() || !list) {
                sendError(x, HTTP_FORBIDDEN);
                return "403 Forbidden";
            }

            StringBuilder buf = new StringBuilder();
            buf.append("<html><head>");
            buf.append("<title>Contents of ...</title>");
            buf.append("</head><body>");

            File[] files = file.listFiles();
            if (files != null) {
                for (File f : files) {
                    String p = childPath(root, f);
                    if (!f.isHidden() && p != null) {
                        buf.append("<a href=\"");
                        buf.append(p.replace("\\", "/"));
                        buf.append("\">");
                        buf.append(f.getName());
                        buf.append("</a><br>");
                    }
                }
            }

            buf.append("</body></html>");

            sendHtmlResponse(x, buf.toString(), HTTP_OK);
        } else {
            String ct = guessContentType(file);
            String contentType = ct != null ? ct : "application/octet-stream";
            if (verbose) {
                System.out.print("(" + contentType + ") ");
            }
            RandomAccessFile raf;
            try {
                raf = new RandomAccessFile(file, "r");
            } catch (FileNotFoundException fnfe) {
                sendError(x, HTTP_NOT_FOUND);
                return "404 Not Found";
            }
            long fileLength = raf.length();

            Headers headers = x.getResponseHeaders();
            headers.set("Content-Type", contentType);
            x.sendResponseHeaders(200, fileLength);
            OutputStream os = x.getResponseBody();

            try {
                byte[] buf = new byte[1024];
                int size = raf.read(buf);
                while (size > 0) {
                    os.write(buf, 0, size);
                    size = raf.read(buf);
                }
            } finally {
                os.close();
                raf.close();
            }
        }
        return "200 OK";
    }

    void sendError(HttpExchange x, Integer status) throws IOException {
        sendHtmlResponse(x, wrapHtml("Failure: " + status, "HTTPD Error Page for " + status), status);
    }

    String wrapHtml(String body, String title) {
        return "<html><head><title>" + title + "</title></head><body>" + body + "</body></html>";
    }

    void sendHtmlResponse(HttpExchange x, String response, Integer status) throws IOException {
        Headers headers = x.getResponseHeaders();
        headers.set("Content-Type", "text/html; charset=UTF-8");
        x.sendResponseHeaders(status, response.length());
        OutputStream os = x.getResponseBody();
        os.write(response.getBytes("UTF-8"));
        os.close();
    }

    private File uriToFile(URI uri) throws IOException {
        File cwd = new File(System.getProperty("user.dir", ""));
        File result = new File(cwd, uri.getPath()).getCanonicalFile();
        if (!result.getPath().startsWith(cwd.getCanonicalPath())) {
            return cwd;
        }
        return result;
    }

    private String childPath(File parent, File child) throws IOException {
        String pp = parent.getCanonicalPath();
        String cp = child.getCanonicalPath();
        if (cp.startsWith(pp)) {
            return cp.substring(pp.length());
        } else {
            return null;
        }
    }

    private File findIndex(File dir, List<String> indices) {
        return indices.stream()
            .map((String idx) -> new File(dir, idx)) // Get the File for each index
            .filter((File f) -> f.isFile())          // Keep only existing indices
            .findFirst().orElse(null);                            // Return the first if any index was found
    }

    private String guessContentType(File file) throws IOException {
        String ct = Files.probeContentType(file.toPath());
        if (ct == null) {
            // The probe only seems to look at file names
            // Let's check if the file just contains text
            try {
                Files.lines(file.toPath()).forEach(ln -> {});
                // We could do even more but let's just say it's plain text
                ct = "text/plain";
            } catch (UncheckedIOException ex) {
                // Ignore and return null
            }
        }
        return ct;
    }

    public static void start(String iface, Integer port, Boolean list, List<String> indices, Boolean verbose) throws IOException {
        // Create server and bind the port to listen to
        InetSocketAddress addr = new InetSocketAddress(iface, port);
        HttpServer server = HttpServer.create(addr, 0);
        server.createContext("/", new simple_httpd(list, indices, verbose));
        server.setExecutor(null);
        // Start to accept incoming connections
        server.start();
        if (verbose) {
            System.out.println("Started server on port " + addr);
        }
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        String iface = "0.0.0.0";
        int port = 8080;
        boolean list = false;
        List<String> indices = new ArrayList<>();
        boolean verbose = false;

        int i = 0;
        while (i < args.length) {
            String arg = args[i++];
            switch (arg) {
                case "-f":
                case "--interface":
                    iface = args[i++];
                    break;
                case "-p":
                case "--port":
                    port = Integer.parseInt(args[i++]);
                    break;
                case "-l":
                case "--list":
                    list = true;
                    break;
                case "-i":
                case "--index":
                    indices.add(args[i++]);
                    break;
                case "-v":
                case "--verbose":
                    verbose = true;
                    break;
                case "-h":
                case "--help":
                    System.out.println("Usage: simple_httpd <options>");
                    System.out.println();
                    System.out.println("Where options can be zero or more of the following:");
                    System.out.println("   -i, --interface <ip> : IP address to bind to, default 0.0.0.0");
                    System.out.println("   -p, --port <nr>      : Port number to use for the server, default 8080");
                    System.out.println("   -l, --list           : Allows listing of directories, default false");
                    System.out.println("   -i, --index          : File to use instead of directory, default index.html");
                    System.out.println("   -v, --verbose        : Run in verbose mode, default false");
                    System.out.println("   -h, --help           : Shows this help message");
                    System.exit(1);
            }
        }
        if (indices.isEmpty()) {
            indices.add("index.html");
        }
        simple_httpd.start(iface, port, list, indices, verbose);
        Thread.currentThread().join();
    }
}

