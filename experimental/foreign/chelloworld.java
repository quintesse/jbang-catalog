///usr/bin/env jbang "$0" "$@" ; exit $?

//JAVA 18+
//JAVAC_OPTIONS --add-modules jdk.incubator.foreign
//JAVA_OPTIONS --add-modules jdk.incubator.foreign -Dforeign.restricted=permit --enable-native-access=ALL-UNNAMED -Djava.library.path=.

// NB: sources are generated using: jextract -d helloworld_api --source -l helloworld helloworld.h
//SOURCES helloworld_api/*.java

class chelloworld {

    public static void main(String... args) throws Throwable {
        helloworld_h.helloworld();
    }
}
