///usr/bin/env jbang "$0" "$@" ; exit $?

//DEPS org.aesh:terminal-tty:3.4-dev

import java.io.IOException;

import org.aesh.terminal.Attributes;
import org.aesh.terminal.formatting.Color;
import org.aesh.terminal.formatting.TerminalColor;
import org.aesh.terminal.formatting.TerminalString;
import org.aesh.terminal.tty.Signal;
import org.aesh.terminal.tty.Size;
import org.aesh.terminal.tty.TerminalConnection;
import org.aesh.terminal.utils.ANSI;

public class tanim {

    private static final String[] SPINNER = { "|", "/", "-", "\\" };

    private static final String[] ROCKET = {
            "    ^    ",
            "   / \\   ",
            "  /   \\  ",
            " |     | ",
            " |     | ",
            " |_____| ",
            "  | U |  ",
            " /|   |\\",
            "/ |   | \\",
            "  ^   ^  "
    };

    private static final String[][] FLAMES = {
            { "  * * *  ", "   ***   ", "    *    " },
            { "   ***   ", "  *****  ", "   * *   " },
            { "    *    ", "   ***   ", "  ** **  " }
    };

    private static final Color[] RAINBOW = {
            Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA
    };

    private static volatile boolean running = true;

    /**
     * Main entry point for the animated ASCII example.
     *
     * @param args command line arguments (not used)
     */
    public static void main(String[] args) {
        try {
            TerminalConnection connection = new TerminalConnection();
            Attributes savedAttributes = connection.enterRawMode();

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

            Size size = connection.size();
            int width = size.getWidth();
            int height = size.getHeight();

            // Hide cursor and clear screen
            connection.write(ANSI.CURSOR_HIDE);
            connection.stdoutHandler().accept(ANSI.CLEAR_SCREEN);

            // Ball animation variables
            int ballX = 5;
            int ballY = 5;
            int ballDx = 1;
            int ballDy = 1;

            // Rocket position (bottom center)
            int rocketX = width / 2 - 5;
            int rocketY = height - ROCKET.length - 5;

            int frame = 0;
            long lastTime = System.currentTimeMillis();

            // Reusable buffer for frame rendering
            StringBuilder buffer = new StringBuilder(4096);

            while (running) {
                long now = System.currentTimeMillis();
                if (now - lastTime < 80) {
                    Thread.sleep(10);
                    continue;
                }
                lastTime = now;

                // Clear buffer for new frame
                buffer.setLength(0);

                // Clear screen
                buffer.append("\u001B[2J");

                // Draw title with rainbow colors
                String title = "=== Animated ASCII Demo - Press any key to exit ===";
                moveCursor(buffer, 1, (width - title.length()) / 2);
                for (int i = 0; i < title.length(); i++) {
                    Color color = RAINBOW[(i + frame) % RAINBOW.length];
                    TerminalString ts = new TerminalString(String.valueOf(title.charAt(i)),
                            new TerminalColor(color, Color.DEFAULT, Color.Intensity.BRIGHT));
                    buffer.append(ts.toString());
                }

                // Draw spinner
                moveCursor(buffer, 3, 5);
                buffer.append("Loading: ");
                TerminalString spinner = new TerminalString(SPINNER[frame % SPINNER.length],
                        new TerminalColor(Color.CYAN, Color.DEFAULT, Color.Intensity.BRIGHT));
                buffer.append(spinner.toString());

                // Draw bouncing ball
                moveCursor(buffer, ballY, ballX);
                TerminalString ball = new TerminalString("O",
                        new TerminalColor(RAINBOW[frame % RAINBOW.length], Color.DEFAULT, Color.Intensity.BRIGHT));
                buffer.append(ball.toString());

                // Update ball position
                ballX += ballDx;
                ballY += ballDy;
                if (ballX <= 1 || ballX >= width - 2) {
                    ballDx = -ballDx;
                }
                if (ballY <= 3 || ballY >= rocketY - 2) {
                    ballDy = -ballDy;
                }

                // Draw rocket
                for (int i = 0; i < ROCKET.length; i++) {
                    moveCursor(buffer, rocketY + i, rocketX);
                    TerminalString rocketLine = new TerminalString(ROCKET[i],
                            new TerminalColor(Color.WHITE, Color.DEFAULT, Color.Intensity.BRIGHT));
                    buffer.append(rocketLine.toString());
                }

                // Draw animated flames
                String[] currentFlames = FLAMES[frame % FLAMES.length];
                for (int i = 0; i < currentFlames.length; i++) {
                    moveCursor(buffer, rocketY + ROCKET.length + i, rocketX);
                    Color flameColor = (frame + i) % 2 == 0 ? Color.RED : Color.YELLOW;
                    TerminalString flameLine = new TerminalString(currentFlames[i],
                            new TerminalColor(flameColor, Color.DEFAULT, Color.Intensity.BRIGHT));
                    buffer.append(flameLine.toString());
                }

                // Draw progress bar
                int barWidth = 30;
                int progress = frame % (barWidth + 1);
                moveCursor(buffer, height - 2, (width - barWidth - 12) / 2);
                buffer.append("Progress: [");
                for (int i = 0; i < barWidth; i++) {
                    if (i < progress) {
                        TerminalString block = new TerminalString("#",
                                new TerminalColor(Color.GREEN, Color.DEFAULT, Color.Intensity.BRIGHT));
                        buffer.append(block.toString());
                    } else {
                        buffer.append("-");
                    }
                }
                buffer.append("]");

                buffer.append(ANSI.RESET);

                // Write entire frame buffer to connection in one call
                connection.write(buffer.toString());

                frame++;
            }

            // Cleanup
            connection.write(ANSI.CURSOR_SHOW + "\u001B[2J\u001B[H" + "Goodbye!\n");
            connection.setAttributes(savedAttributes);
            connection.close();

        } catch (IOException | InterruptedException e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
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
}
