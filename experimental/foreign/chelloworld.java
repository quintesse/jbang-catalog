///usr/bin/env jbang "$0" "$@" ; exit $?

//JAVA 16+
//JAVAC_OPTIONS --add-modules jdk.incubator.foreign
//JAVA_OPTIONS --add-modules jdk.incubator.foreign -Dforeign.restricted=permit

//SOURCES helloworld_api.java
///FILES libhelloworld.so

class chelloworld {

    public static void main(String... args) throws Throwable {
        HelloWorld.library().helloworld();
    }
}
