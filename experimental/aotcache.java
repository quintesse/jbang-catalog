///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS com.github.lalyos:jfiglet:0.0.9
//RUNTIME_OPTIONS -XX:AOTCache=app.aot -Xlog:aot
//JAVA 25+ // Use final version, many EA won't work!

import com.github.lalyos.jfiglet.FigletFont;

class aotcache {
    public static void main(String... args) throws Exception {
        System.out.println(FigletFont.convertOneLine(
               "Hello " + ((args.length>0)?args[0]:"jbang")));
    }
}

