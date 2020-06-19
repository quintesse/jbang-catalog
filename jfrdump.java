//usr/bin/env jbang "$0" "$@" ; exit $?

//DEPS info.picocli:picocli:4.2.0

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "jfrdump", mixinStandardHelpOptions = true, description = "Simplistic dump of JFR recording file")
class JfrDump implements Callable<Integer> {

    final static int MAGIC = 0x464C5200;
    final static int CHUNK_HEADER_SIZE = 68;
    final static int EVENT_HEADER_SIZE = 12;

    final static int BUFFER_SIZE = 8 * 1024;

    @Parameters(index = "0", description = "Path to JFR recording", arity = "1")
    protected Path file;

    @Option(names = { "--events", "-e" }, description = "Show event details")
    boolean showEvents;

    private RandomAccessFile jfrFile;
    private MappedByteBuffer currentBuffer;
    private long bufferStart;

    @Override
    public Integer call() throws Exception {
        int chunkCount = 0;
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            jfrFile = raf;
            long start = 0;
            while (start < raf.length()) {
                MappedByteBuffer mem = acquire(start, CHUNK_HEADER_SIZE);
                long chunkStart = currentOffset();
                int magic = mem.getInt();
                if (magic != MAGIC) {
                    System.err.println("Invalid chunk at byte #" + chunkStart);
                    return 1;
                }

                System.out.println();
                System.out.println("Chunk #" + ++chunkCount);
                System.out.println("---------");
                System.out.println(String.format("Position: @%08x", chunkStart));

                short majorVersion = mem.getShort();
                short minorVersion = mem.getShort();
                System.out.println("Version: " + majorVersion + "." + minorVersion);

                long chunkSize = mem.getLong();
                System.out.println(String.format("Size: %,d (%08x)", chunkSize, chunkSize));

                long poolPos = mem.getLong();
                System.out.println(String.format("Pool pos: @%08x", poolPos));

                long metaPos = mem.getLong();
                System.out.println(String.format("Metadata pos: @%08x", metaPos));

                long startNanos = mem.getLong();
                long durationNanos = mem.getLong();
                long startTicks = mem.getLong();
                long ticksPerSecond = mem.getLong();
                System.out.println(
                        String.format("Time: %d %d %d %d", startNanos, durationNanos, startTicks, ticksPerSecond));

                byte generation = mem.get();
                byte pad = mem.get();
                System.out.println(String.format("Generation (Pad): %d (%d)", generation, pad));

                short flags = mem.getShort();
                System.out.println(String.format("Flags: %s (%02x)", chunkFlags(flags), flags));

                long chunkEnd = chunkStart + chunkSize;

                int eventCount = 0;
                long eventStart = currentOffset();
                while (eventStart < chunkEnd) {
                    mem = acquire(eventStart, EVENT_HEADER_SIZE);
                    long eventSize = decode128(mem);
                    if (eventSize < 0 || eventStart + eventSize > chunkEnd) {
                        System.out.println(String.format("Invalid event size: %d", eventSize));
                        break;
                    }
                    long eventType = decode128(mem);
                    eventCount++;
                    long eventPos = eventStart - chunkStart;
                    boolean show = showEvents || eventPos == poolPos || eventPos == metaPos;
                    if (show) {
                        System.out.print(String.format("EVENT: @%08x %,d (%08x) %08x", eventStart, eventSize, eventSize, eventType));
                        if (eventPos == poolPos) {
                            System.out.print(" [Constant Pool]");
                        } else if (eventPos == metaPos) {
                            System.out.print(" [Metadata]");
                        }
                        System.out.println();
                    }
                    if (eventSize == 0) {
                        break;
                    }
                    eventStart += eventSize;
                }
                System.out.println(String.format("#Events: %d", eventCount));
                if (eventStart != chunkEnd) {
                    System.err.println("End of chunk does not coincide with end of event");
                }

                start = chunkEnd;
            }
            if (start != raf.length()) {
                System.err.println("End of file does not coincide with end of chunk");
                return 2;
            }
    }
        return 0;
    }

    private MappedByteBuffer acquire(long newStart, int newSize) throws IOException {
        if (currentBuffer == null || newStart < bufferStart || newStart + newSize > bufferStart + currentBuffer.capacity()) {
            long bufSize = Math.min(Math.max(newSize, BUFFER_SIZE), jfrFile.length() - newStart);
            currentBuffer = jfrFile.getChannel().map(FileChannel.MapMode.READ_ONLY, newStart, bufSize);
            bufferStart = newStart;
        } else {
            currentBuffer.position((int)(newStart - bufferStart));
        }
        return currentBuffer;
    }

    private long currentOffset() {
        return bufferStart + currentBuffer.position();
    }

    private static long decode128(ByteBuffer buf) {
        long result = 0;
        int shift = 0;
        byte chr;
        do {
            chr = buf.get();
            result |= (chr & 0x7f) << shift;
            shift += 7;
        } while ((chr & 0x80) != 0 && shift <= 56);
        return result;
    }

    private static String chunkFlags(short flags) {
        ArrayList<String> names = new ArrayList<>(2);
        if ((flags & 2) != 0) {
            names.add("FLUSH");
        }
        if ((flags & 1) != 0) {
            names.add("COMPRESSED_INTS");
        }
        return names.stream().collect(Collectors.joining(" | "));
    }
    
    public static void main(String... args) {
        JfrDump app = new JfrDump();
        int exitCode = new CommandLine(app).execute(args);
        System.exit(exitCode);
    }
}
