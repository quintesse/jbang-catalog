///usr/bin/env jbang "$0" "$@" ; exit $?

//DEPS org.aesh:terminal-tty:3.4-dev
//DEPS org.codejive.twinkle:twinkle-shapes:1.0-SNAPSHOT
//DEPS com.github.lalyos:jfiglet:0.0.9

import java.io.IOException;

import org.aesh.terminal.tty.Signal;
import org.aesh.terminal.tty.Size;
import org.aesh.terminal.tty.TerminalConnection;
import org.aesh.terminal.utils.ANSI;

import org.codejive.twinkle.ansi.Ansi;
import org.codejive.twinkle.text.Buffer;
import org.codejive.twinkle.shapes.Borders;

public class test {

    private static volatile boolean running = true;

    /**
     * Main entry point for the animated ASCII example.
     *
     * @param args command line arguments (not used)
     * @throws IOException 
     */
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

            //connection.setSizeHandler(size -> initScreen(size));

            // Start input reading in background
            connection.openNonBlocking();

            Size size = connection.size();

            // Hide cursor and clear screen
            connection.write(ANSI.CURSOR_HIDE);
            connection.stdoutHandler().accept(ANSI.CLEAR_SCREEN);

            // Reusable buffer for frame rendering
            Buffer buffer = Buffer.of(size.getWidth(), size.getHeight());
            Borders.ascii().render(buffer);

            while (running) {
                // Clear buffer for new frame
                buffer.clear();

                // Write entire frame buffer to connection in one call
                connection.write(Ansi.cursorHome() + buffer.toAnsi());
            }

            connection.write(ANSI.CURSOR_SHOW + "\u001B[2J\u001B[H" + "Goodbye!\n");
        }
    }
}
