///usr/bin/env jbang "$0" "$@" ; exit $?

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

public class memmap {
    public static void main(String... args) throws FileNotFoundException, IOException, InterruptedException {
        String fileName = args[0];
        File file = new File(fileName);
        file.delete();
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            long bufferSize = 8 * 1000;
            MappedByteBuffer mem = raf.getChannel().map(FileChannel.MapMode.READ_WRITE, 0, bufferSize);

            long startTime = System.currentTimeMillis();
            int startPos = 0;
            long HUNDREDK = 100000;
            long noOfMessage = HUNDREDK * 10 * 10;
            for (long counter = 0; counter < noOfMessage; counter++) {
                if (!mem.hasRemaining()) {
                    startPos += mem.position();
                    mem = raf.getChannel().map(FileChannel.MapMode.READ_WRITE, startPos, bufferSize);
                }
                mem.putLong(counter);
            }
            long endT = System.currentTimeMillis();
            long tot = endT - startTime;
            System.out.println(String.format("No Of Message %s , Time(ms) %s ", noOfMessage, tot));
        }
    }
}
