///usr/bin/env jbang "$0" "$@" ; exit $?

//DEPS org.jmdns:jmdns:3.5.7

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceEvent;
import javax.jmdns.ServiceInfo;
import javax.jmdns.ServiceListener;

public class mdns {
    private static String format = "e a s h n";
    private static int timeout = 0;
    private static List<String> filters = new ArrayList<>();
    private static boolean caseInsensitive;
    private static String exec;

    private static class SampleListener implements ServiceListener {
        @Override
        public void serviceAdded(ServiceEvent event) {
            if (accept("ADD", event.getInfo())) {
                showInfo(format, "ADD", event.getInfo());
                execCmd(exec, "ADD", event.getInfo());
            }
        }

        @Override
        public void serviceRemoved(ServiceEvent event) {
            if (accept("REM", event.getInfo())) {
                showInfo(format, "REM", event.getInfo());
                execCmd(exec, "REM", event.getInfo());
            }
        }

        @Override
        public void serviceResolved(ServiceEvent event) {
            if (accept("RES", event.getInfo())) {
                showInfo(format, "RES", event.getInfo());
                execCmd(exec, "RES", event.getInfo());
            }
        }

        private static boolean accept(String eventType, ServiceInfo info) {
            return filters.stream().allMatch(filter -> accept(filter, eventType, info));
        }

        private static boolean accept(String filter, String eventType, ServiceInfo info) {
            String[] parts;
            BiFunction<String, String, Boolean> func = (a, b) -> caseInsensitive ? !a.equalsIgnoreCase(b) : !a.equals(b);
            parts = filter.split("!=");
            if (parts.length != 2) {
                func = (a, b) -> caseInsensitive ? a.equalsIgnoreCase(b) : a.equals(b);
                parts = filter.split("=");
            }
            if (parts.length != 2) {
                func = (a, b) -> caseInsensitive ? !a.toLowerCase().contains(b.toLowerCase()) : !a.contains(b);
                parts = filter.split("!~");
            }
            if (parts.length != 2) {
                func = (a, b) -> caseInsensitive ? a.toLowerCase().contains(b.toLowerCase()) : a.contains(b);
                parts = filter.split("~");
            }
            return parts.length == 2
                    && !parts[0].isEmpty()
                    && func.apply(getInfo(parts[0].charAt(0), eventType, info), parts[1]);
        }

        private static void showInfo(String fmt, String eventType, ServiceInfo info) {
            if (!fmt.isEmpty()) {
                String str = fmt.codePoints().mapToObj(ch -> getInfo(ch, eventType, info)).collect(Collectors.joining());
                System.out.println(str.toString());
            }
        }

        private static void execCmd(String exec, String eventType, ServiceInfo info) {
            if (exec != null) {
                exec = replace(exec, "%(.)", m -> getInfo(m.group(1).charAt(0), eventType, info));
                try {
                    ProcessBuilder pb = new ProcessBuilder();
                    if (System.getenv("SHELL") != null) {
                        pb.command("sh", "-c", exec);
                    } else {
                        pb.command("cmd.exe", "/c", exec);
                    }
                    pb.inheritIO().start();
                } catch (IOException e) {
                    System.err.println("Error executing command");
                    e.printStackTrace(System.err);
                }
            }
        }

        private static String getInfo(int ch, String eventType, ServiceInfo info) {
            switch (ch) {
                case 'a': return info.getApplication();
                case 'd': return info.getDomain();
                case 'e': return eventType;
                case 'h': return Arrays.toString(info.getHostAddresses());
                case 'i': return Arrays.toString(info.getInet4Addresses());
                case 'I': return Arrays.toString(info.getInet6Addresses());
                case 'k': return info.getKey();
                case 'n': return info.getName();
                case 'p': return info.getProtocol();
                case 'P': return Integer.toString(info.getPort());
                case 'q': return info.getQualifiedName();
                case 's': return info.getServer();
                case 'S': return info.getSubtype();
                case 't': return info.getType();
                case 'T': return info.getTypeWithSubtype();
                case 'u': return Arrays.toString(info.getURLs());
                case 'z': return info.getNiceTextString();
                case 'Z': return info.toString();
                default:
                    return Character.toString(ch);
            }
        }
    }

    private static String replace(String input, String regex, Function<Matcher, String> callback) {
        return replace(input, Pattern.compile(regex), callback);
    }

    private static String replace(String input, Pattern regex, Function<Matcher, String> callback) {
        StringBuffer resultString = new StringBuffer();
        Matcher regexMatcher = regex.matcher(input);
        while (regexMatcher.find()) {
            regexMatcher.appendReplacement(resultString, callback.apply(regexMatcher));
        }
        regexMatcher.appendTail(resultString);

        return resultString.toString();
    }

    public static void main(String... args) throws InterruptedException {
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "-t":
                case "--timeout":
                    timeout = Integer.parseInt(args[++i]);
                    break;
                case "-f":
                case "--filter":
                    filters.add(args[++i]);
                    break;
                case "-i":
                    caseInsensitive = true;
                    break;
                case "-x":
                case "--exec":
                    exec = args[++i];
                    break;
                case "-h":
                case "--help":
                    System.out.println("Usage: mdns [--timeout|-t <seconds>] [-i] [--filter|-f <info>(=|~)<text>]... [<info>]...");
                    System.out.println("Where <info> is any of the following characters:");
                    System.out.println("   a - Application");
                    System.out.println("   d - Domain");
                    System.out.println("   e - Event Type (ADD|REM|RES)");
                    System.out.println("   h - Host Addresses");
                    System.out.println("   i - Inet4 Addresses");
                    System.out.println("   I - Inet6 Addresses");
                    System.out.println("   k - Key");
                    System.out.println("   n - Name");
                    System.out.println("   p - Protocol");
                    System.out.println("   P - Port");
                    System.out.println("   q - Qualified Name");
                    System.out.println("   s - Server");
                    System.out.println("   S - Subtype");
                    System.out.println("   t - Type");
                    System.out.println("   T - Type with Subtype");
                    System.out.println("   u - URLs");
                    System.out.println("   z - as string");
                    System.out.println("   Z - as long string");
                    System.exit(1);
                default:
                    format = arg;
                    break;
            }
        }
        try (JmDNS jmdns = JmDNS.create(InetAddress.getLocalHost())) {
            // Add a service listener
            jmdns.addServiceListener("_http._tcp.local.", new SampleListener());

            // Wait for timeout or user interrupt
            if (timeout > 0) {
                Thread.sleep(timeout * 1000);
            } else {
                Thread.currentThread().join();
            }
        } catch (UnknownHostException e) {
            System.err.println(e.getMessage());
        } catch (IOException e) {
            System.err.println(e.getMessage());
        }
    }
}

