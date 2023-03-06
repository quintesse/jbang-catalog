///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS info.picocli:picocli:4.6.3
//DEPS org.jline:jline-terminal:3.22.0 org.jline:jline-terminal-jansi:3.22.0

import picocli.CommandLine;
import picocli.CommandLine.Command;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.AttributedString;
import org.jline.utils.Display;
import org.jline.utils.InfoCmp.Capability;

class terminal {
    public static void main(String... args) {
        int exitCode = new CommandLine(new box()).execute(args);
        System.exit(exitCode);
    }
}

@Command
class info implements Callable<Integer> {

    @Override
    public Integer call() throws Exception {
        Terminal terminal = TerminalBuilder.terminal();
        System.out.println("Terminal: " + terminal.getName());
        System.out.println("   size: " + terminal.getSize());
        System.out.println("   buffer: " + terminal.getBufferSize());
        System.out.println("   type: " + terminal.getType());
        System.out.println("   encoding: " + terminal.encoding());
        System.out.println("   palette: " + terminal.getPalette().getLength() + " colors");
        System.out.println("   canPauseResume: " + terminal.canPauseResume());
        System.out.println("   hasFocusSupport: " + terminal.hasFocusSupport());
        System.out.println("   hasMouseSupport: " + terminal.hasMouseSupport());
        System.out.println("   attributes: " + terminal.getAttributes());

        terminal.enterRawMode();
        terminal.reader().read();

        return 0;
    }
}

@Command
class display implements Callable<Integer> {
 
    @Override
    public Integer call() throws Exception {

        try (Terminal terminal = TerminalBuilder.builder().build()) {
            Attributes savedAttributes = terminal.enterRawMode();
            terminal.puts(Capability.enter_ca_mode);
            int height = terminal.getHeight();

            Display display = new Display(terminal, true);
            display.resize(height, terminal.getWidth());

            // Build Strings to displayed
            List<AttributedString> lines1 = new ArrayList<>();
            for (int i = 1; i < height + 1; i++) {
                lines1.add(new AttributedString(String.format("%03d: %s", i, "Chaine de test...")));
            }

            List<AttributedString> lines2 = new ArrayList<>();
            for (int i = 0; i < height; i++) {
                lines2.add(new AttributedString(String.format("%03d: %s", i, "Chaine de test...")));
            }

            // Display with tempo
            display.update(lines1, 0);
            Thread.sleep(3000);

            display.update(lines2, 0);
            Thread.sleep(3000);

            terminal.setAttributes(savedAttributes);
            terminal.puts(Capability.exit_ca_mode);
            return 0;
        }
    }    
}

@Command
class box implements Callable<Integer> {
 
    @Override
    public Integer call() throws Exception {

        try (Terminal terminal = TerminalBuilder.builder().build()) {
            Attributes savedAttributes = terminal.enterRawMode();
            terminal.puts(Capability.enter_ca_mode);
            int w = terminal.getWidth();
            int h = terminal.getHeight();

            Display display = new Display(terminal, true);
            display.resize(h, w);

            // Build Strings to displayed
            List<AttributedString> lines = new ArrayList<>();
            lines.add(new AttributedString(String.format("+%s+", "-".repeat(w - 2))));
            for (int i = 0; i < h-2; i++) {
                lines.add(new AttributedString(String.format("|%s|", " ".repeat(w - 2))));
            }
            lines.add(new AttributedString(String.format("+%s+", "-".repeat(w - 2))));

            // Display with tempo
            display.update(lines, 0);
            Thread.sleep(3000);

            terminal.setAttributes(savedAttributes);
            terminal.puts(Capability.exit_ca_mode);
            return 0;
        }
    }    
}

@Command
class scroll implements Callable<Integer> {
 
    @Override
    public Integer call() throws Exception {

        try (Terminal terminal = TerminalBuilder.builder().build()) {
            Attributes savedAttributes = terminal.enterRawMode();
            terminal.puts(Capability.change_scroll_region, 0, 5);
            terminal.flush();
            return 0;
        }
    }    
}
