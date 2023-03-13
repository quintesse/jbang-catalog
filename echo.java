///usr/bin/env jbang "$0" "$@" ; exit $?

package scripts;

public class echo {

    public static void main(String[] args) {
        System.out.println(String.join(" ", args));
    }
}
