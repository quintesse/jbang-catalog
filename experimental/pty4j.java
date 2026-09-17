//DEPS org.jetbrains.pty4j:pty4j:0.13.12

import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;

public class pty4j {
    public static void main(String[] args) throws Exception {
        Map<String, String> env = new HashMap<>(System.getenv());
        if (!env.containsKey("TERM")) env.put("TERM", "xterm");

        if (args.length == 0) {
            System.err.println("Usage: java pty4j <command> [args...]");
            System.exit(1);
        }
        
        PtyProcess process = new PtyProcessBuilder()
            .setCommand(args)
            .setEnvironment(env)
            .start();

        OutputStream os = process.getOutputStream();
        InputStream is = process.getInputStream();

        // TODO handle streams

        // wait until the PTY child process is terminated
        int result = process.waitFor();
    }
}
