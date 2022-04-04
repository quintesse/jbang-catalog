///usr/bin/env jbang "$0" "$@" ; exit $?

//JAVA 18+
//JAVAC_OPTIONS --add-modules jdk.incubator.foreign
//JAVA_OPTIONS --add-modules jdk.incubator.foreign -Dforeign.restricted=permit --enable-native-access=ALL-UNNAMED -Djava.library.path=.

// NB: sources are generated using: jextract -d echo_api --source -l echo echo.h
//SOURCES echo_api/*.java

import jdk.incubator.foreign.MemorySegment;
import jdk.incubator.foreign.ResourceScope;

class echo {

    public static void main(String... args) throws Throwable {
        String msg = args.length > 0 ? args[0] : "Echo";
        int rep = args.length > 1 ? Integer.parseInt(args[1]) : 3;

        // Method 1 : direct
        System.out.println("Method 1:");
        try (var scope = ResourceScope.newConfinedScope()) {
            var echo = Echo.allocate(scope);
            //byte[] msgb = msg.getBytes("utf8");
            MemorySegment msgs = MemorySegment.allocateNative(msg.length() + 1, scope);
            msgs.setUtf8String(0, msg);
            Echo.message$set(echo, msgs.address());
            Echo.repeat$set(echo, rep);
            echo_h.echo(echo);
        }

        // Method 2 : wrapped
        System.out.println("Method 2:");
        var jecho = new JEcho(msg, rep);
        jecho.echo();
    }
}

class JEcho extends Echo {
    private final ResourceScope scope;
    private final MemorySegment echo;

    public JEcho(String msg, int rep) {
        scope =  ResourceScope.newImplicitScope();
        echo = Echo.allocate(scope);
        MemorySegment msgs = MemorySegment.allocateNative(msg.length() + 1, scope);
        msgs.setUtf8String(0, msg);
        Echo.message$set(echo, msgs.address());
        Echo.repeat$set(echo, rep);
    }

    public void echo() {
        echo_h.echo(echo);
    }
}