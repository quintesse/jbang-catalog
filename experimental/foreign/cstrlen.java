///usr/bin/env jbang "$0" "$@" ; exit $?

//JAVA 18+
//JAVAC_OPTIONS --add-modules jdk.incubator.foreign
//JAVA_OPTIONS --add-modules jdk.incubator.foreign -Dforeign.restricted=permit --enable-native-access=ALL-UNNAMED

import jdk.incubator.foreign.CLinker;
import jdk.incubator.foreign.FunctionDescriptor;
import jdk.incubator.foreign.MemorySegment;
import jdk.incubator.foreign.ResourceScope;
import jdk.incubator.foreign.ValueLayout;

import java.lang.invoke.MethodHandle;

class cstrlen {

    public static void main(String... args) throws Throwable {
        String msg = args.length > 0 ? args[0] : "Hello";

        // Get a handle to the standard `strlen()` function
        CLinker ln = CLinker.systemCLinker();
        MethodHandle strlen = ln.downcallHandle(
                ln.lookup("strlen").get(),
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS)
        );

        try (var scope = ResourceScope.newConfinedScope()) {
            // We need to allocate some memory for our string
            // (because Java strings are not compatible with C strings)
            var msgs = MemorySegment.allocateNative(msg.length() + 1, scope);
            // And copy our message into it
            msgs.setUtf8String(0, msg);
            // And finally we call the strlen() C function
            long len = (long)strlen.invoke(msgs.address());
            System.out.println("Length of '" + msg + "' = " + len);
        }
    }
}
