///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 18+

import com.sun.net.httpserver.SimpleFileServer;
import com.sun.net.httpserver.SimpleFileServer.OutputLevel;

import java.net.InetSocketAddress;
import java.nio.file.Path;

import static java.lang.System.*;

public class jdkfileserver {

    public static void main(String... args) {
        var server = SimpleFileServer.createFileServer(new InetSocketAddress(8080), Path.of(".").toAbsolutePath(), OutputLevel.VERBOSE);
        out.println("Starting server  on http://localhost:8080");
        server.start();
    }
}
