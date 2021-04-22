
import jdk.incubator.foreign.CLinker;
import jdk.incubator.foreign.FunctionDescriptor;
import jdk.incubator.foreign.LibraryLookup;
import jdk.incubator.foreign.MemoryAddress;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.nio.file.Path;

class HelloWorld {
    private MethodHandle helloworld;

    private static LibraryLookup lib;

    public static HelloWorld library() {
        try {
            // Get a handle to the `helloworld()` function from the
            // locally available dll named `helloworld.so`
            lib = LibraryLookup.ofPath(Path.of("helloworld.so").toAbsolutePath());
        } catch (Exception ex) {
            // If we couldn't find the library in the curent directory
            // we tell the user how to compile it form source
            System.err.println("""
                    Couldn't locate helloworld library, did you compile it?
                    Use the following commands to do so:
                    $ gcc -c -fpic helloworld.c
                    $ gcc -shared -o helloworld.so helloworld.o
                    """);
        }

        HelloWorld api = new HelloWorld();

        var sym = lib.lookup("helloworld");
        api.helloworld = CLinker.getInstance().downcallHandle(
                sym.get(),
                MethodType.methodType(Void.TYPE),
                FunctionDescriptor.ofVoid()
        );

        return api;
    }

    public void helloworld() {
        try {
            helloworld.invokeExact();
        } catch (Throwable th) {
            if (th instanceof RuntimeException rte) {
                throw rte;
            } else if (th instanceof Error e) {
                throw e;
            } else {
                // Should not happen!
                throw new RuntimeException("Method threw unexpected exception", th);
            }
        }
    }
}
