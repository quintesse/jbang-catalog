///usr/bin/env jbang "$0" "$@" ; exit $?

//DEPS org.aesh:terminal-tty:3.3

import java.io.IOException;

import org.aesh.terminal.tty.Signal;
import org.aesh.terminal.tty.TerminalConnection;

public class tiny {

    private static volatile boolean running = true;

    public static void main(String[] args) throws IOException {
        try (TerminalConnection connection = new TerminalConnection()) {
            connection.enterRawMode();

            // Handle Ctrl+C
            connection.setSignalHandler(signal -> {
                if (signal == Signal.INT) {
                    running = false;
                }
            });

            // Handle any key press to exit
            connection.setStdinHandler(input -> {
                if (input != null && input.length > 0) {
                    running = false;
                }
            });

            // Start input reading in background
            connection.openNonBlocking();

            while (running) {
            }

            connection.write("Goodbye!\n");
        }
    }
}
