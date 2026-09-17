///usr/bin/env jbang "$0" "$@" ; exit $?

//DEPS org.aesh:terminal-tty:3.4-dev

import java.io.IOException;

import org.aesh.terminal.tty.Signal;
import org.aesh.terminal.tty.Size;
import org.aesh.terminal.tty.TerminalConnection;
import org.aesh.terminal.utils.ANSI;

public class border {

    private static volatile boolean running = true;

    private static String[] screen = null;

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

            connection.setSizeHandler(size -> initScreen(size));

            // Start input reading in background
            connection.openNonBlocking();

            Size size = connection.size();
            initScreen(size);

            // Hide cursor and clear screen
            connection.write(ANSI.CURSOR_HIDE);
            connection.stdoutHandler().accept(ANSI.CLEAR_SCREEN);

            // Reusable buffer for frame rendering
            StringBuilder buffer = new StringBuilder(4096);

            while (running) {
                // Clear buffer for new frame
                buffer.setLength(0);

                moveCursor(buffer, 1, 1);
                buffer.append(ANSI.GREEN_TEXT);
                for (int i = 0; i < screen.length; i++) {
                    if (i > 0) {
                        buffer.append('\n');
                    }
                    String line = screen[i];
                    buffer.append(line);
                }
                moveCursor(buffer, 1, 1);

                buffer.append(ANSI.RESET);

                // Write entire frame buffer to connection in one call
                connection.write(buffer.toString());
            }

            connection.write(ANSI.CURSOR_SHOW + "\u001B[2J\u001B[H" + "Goodbye!\n");
        }
    }

    /**
     * Appends an ANSI cursor movement escape sequence to the buffer.
     *
     * @param buffer the StringBuilder to append to
     * @param row the row position (1-based)
     * @param col the column position (1-based)
     */
    private static void moveCursor(StringBuilder buffer, int row, int col) {
        buffer.append("\u001B[").append(row).append(';').append(col).append('H');
    }

    private static void initScreen(Size size) {
        String topbot = "+" + repeat("-", size.getWidth() - 2) + "+";
        String inner = "+" + repeat(" ", size.getWidth() - 2) + "+";
        screen = new String[size.getHeight()];
        screen[0] = topbot;
        for (int i = 1; i < size.getHeight() - 1; i++) {
            screen[i] = inner;
        }
        screen[size.getHeight() - 1] = topbot;
    }

    private static String repeat(String str, int times) {
        StringBuilder sb = new StringBuilder(str.length() * times);
        for (int i = 0; i < times; i++) {
            sb.append(str);
        }
        return sb.toString();
    }
}