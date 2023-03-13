///usr/bin/env jbang "$0" "$@" ; exit $?

package scripts;

public class system_properties {
    public static void main(String[] args) {
        System.getProperties().forEach((key, value)-> System.out.println(key + "=" + value));
    }
}
