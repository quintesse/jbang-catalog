///usr/bin/env jbang "$0" "$@" ; exit $?

//JAVA 16+
//JAVAC_OPTIONS --add-modules jdk.incubator.foreign
//JAVA_OPTIONS --add-modules jdk.incubator.foreign -Dforeign.restricted=permit

// Using manually created API wrapper
//SOURCES helloworld_api.java

class chelloworld16 {

    public static void main(String... args) throws Throwable {
        HelloWorld.library().helloworld();
    }
}
