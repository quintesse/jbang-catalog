///usr/bin/env jbang "$0" "$@" ; exit $?

//JAVA 17+
//JAVAC_OPTIONS --add-modules jdk.incubator.foreign
//JAVA_OPTIONS --add-modules jdk.incubator.foreign -Dforeign.restricted=permit --enable-native-access=ALL-UNNAMED

import jdk.incubator.foreign.CLinker;
import jdk.incubator.foreign.FunctionDescriptor;
import jdk.incubator.foreign.MemoryAddress;
import jdk.incubator.foreign.ResourceScope;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;

class cstrlen {

    public static void main(String... args) throws Throwable {
        // Get a handle to the standard `strlen()` function
        MethodHandle strlen = CLinker.getInstance().downcallHandle(
                CLinker.systemLookup().lookup("strlen").get(),
                MethodType.methodType(long.class, MemoryAddress.class),
                FunctionDescriptor.of(CLinker.C_LONG, CLinker.C_POINTER)
        );

        // And call it
        try (var scope = ResourceScope.newConfinedScope()) {
            var cString = CLinker.toCString("Hello", scope);
            long len = (long)strlen.invokeExact(cString.address()); // 5
            System.out.println(len);
        }
    }
}
