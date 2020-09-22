///usr/bin/env jbang "$0" "$@" ; exit $?

//DEPS info.picocli:picocli:4.2.0
//DEPS info.picocli:picocli-codegen:4.2.0

import picocli.codegen.aot.graalvm.ReflectionConfigGenerator;

class picocliCodegen {

    public static void main(String... args) {
        ReflectionConfigGenerator.main(args);
    }
}
