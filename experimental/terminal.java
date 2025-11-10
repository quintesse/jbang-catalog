///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS info.picocli:picocli:4.7.7
//DEPS org.jline:jline-terminal:3.22.0 org.jline:jline-terminal-jansi:3.22.0

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import org.jline.terminal.Attributes;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.AttributedString;
import org.jline.utils.Display;
import org.jline.utils.InfoCmp.Capability;

@Command(name="terminal", subcommands = {info.class, display.class, box.class, scroll.class, swap.class})
class terminal {
    public static void main(String... args) {
        int exitCode = new CommandLine(new terminal()).execute(args);
        System.exit(exitCode);
    }
}

@Command(name="info")
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
        return 0;
    }
}

@Command(name="display")
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

            display.update(lines1, 0);
            terminal.reader().read();

            display.update(lines2, 0);
            terminal.reader().read();

            terminal.setAttributes(savedAttributes);
            terminal.puts(Capability.exit_ca_mode);
            terminal.writer().flush();
            return 0;
        }
    }    
}

@Command(name="box")
class box implements Callable<Integer> {
 
    @Override
    public Integer call() throws Exception {

        try (Terminal terminal = TerminalBuilder.builder().build()) {
            Display display = new Display(terminal, true);
            Attributes savedAttributes = terminal.enterRawMode();
            terminal.puts(Capability.enter_ca_mode);
            terminal.writer().flush();

            Size size = new Size();
            size.copy(terminal.getSize());
            display.clear();
            display.reset();

            int w = size.getColumns();
            int h = size.getRows();

            display.resize(h, w);

            // Build Strings to displayed
            List<AttributedString> lines = createBox(w, h);

            display.update(lines, 0);
            terminal.reader().read();

            terminal.setAttributes(savedAttributes);
            terminal.puts(Capability.exit_ca_mode);
            terminal.writer().flush();

            return 0;
        }
    }

    public static List<AttributedString> createBox(int w, int h) {
        List<AttributedString> lines = new ArrayList<>();
        lines.add(new AttributedString(String.format("+%s+", "-".repeat(w - 2))));
        for (int i = 0; i < h-2; i++) {
            lines.add(new AttributedString(String.format("|%s|", " ".repeat(w - 2))));
        }
        lines.add(new AttributedString(String.format("+%s+", "-".repeat(w - 2))));
        return lines;
    }    
}

@Command(name="scroll")
class scroll implements Callable<Integer> {
 
    @Parameters(index = "0", arity = "1", description = "Size of scroll region in lines")
    int lines;

    @Override
    public Integer call() throws Exception {
        try (Terminal terminal = TerminalBuilder.builder().build()) {
            if (lines > 0 && lines < terminal.getHeight()) {
                terminal.enterRawMode();
            } else {
                lines = terminal.getHeight();
            }
            terminal.puts(Capability.change_scroll_region, 0, lines-1);
            terminal.flush();
            return 0;
        }
    }    
}

@Command(name="swap")
class swap implements Callable<Integer> {
 
    @Override
    public Integer call() throws Exception {

        try (Terminal terminal = TerminalBuilder.builder().build()) {
            Attributes savedAttributes = terminal.enterRawMode();
            Display display = new Display(terminal, false);
            Size size = new Size();
            size.copy(terminal.getSize());

            int w = size.getColumns();
            int h = size.getRows();

            display.resize(5, w);
            display.update(box.createBox(w, 5), 0);
            terminal.reader().read();

            display.resize(10, w);
            display.update(box.createBox(w, 10), 0);
            terminal.reader().read();

            terminal.puts(Capability.enter_ca_mode);
            terminal.writer().flush();

            display.clear();
            display.reset();
            display.resize(h, w);
            display.update(box.createBox(w, h), 0);
            terminal.reader().read();

            terminal.puts(Capability.exit_ca_mode);
            terminal.writer().flush();

            terminal.setAttributes(savedAttributes);

            return 0;
        }
    }
}
