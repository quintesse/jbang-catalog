///usr/bin/env jbang "$0" "$@" ; exit $?

//JAVA 16+
//JAVAC_OPTIONS --add-modules jdk.incubator.foreign
//JAVA_OPTIONS --add-modules jdk.incubator.foreign -Dforeign.restricted=permit

//SOURCES helloworld_api.java

import jdk.incubator.foreign.CLinker;
import jdk.incubator.foreign.FunctionDescriptor;
import jdk.incubator.foreign.LibraryLookup;
import jdk.incubator.foreign.MemoryAddress;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.nio.file.Path;

class helloworld_test {

    public static void main(String... args) throws Throwable {
        HelloWorld.library().helloworld();
    }
}
