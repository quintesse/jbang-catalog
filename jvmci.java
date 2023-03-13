///usr/bin/env jbang "$0" "$@" ; exit $?
/*
 * From http://mail.openjdk.java.net/pipermail/graal-dev/2016-June/004434.html
 *
 * - Am I running a JDK that supports JVMCI?
 *   Look at the "java.vm.version" system property.
 *   On JDK 8, it will contain "jvmci-<version>" for jvmci-enabled builds.
 *   JDK 9 always has JVMCI built in.
 *
 * - Is JVMCI enabled and/or used?
 *   For that, you can query the VM options (EnableJVMCI and UseJVMCICompiler) using the management API.
 *
 * - What compiler is selected?
 *   That's in the "jvmci.Compiler" system property.
 */

package scripts;

import java.lang.management.ManagementFactory;
import com.sun.management.HotSpotDiagnosticMXBean;
import com.sun.management.VMOption;

class jvmci {
    public static void main(String[] args) {
        // Am I running on a JDK that supports JVMCI?
        String vm_version = System.getProperty("java.vm.version");
        System.out.printf("java.vm.version = %s%n", vm_version);

        HotSpotDiagnosticMXBean bean = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);

        // Is JVMCI enabled?
        VMOption enableJVMCI = bean.getVMOption("EnableJVMCI");
        System.out.println(enableJVMCI);

        // Is the system using the JVMCI compiler for normal compilations?
        VMOption useJVMCICompiler = bean.getVMOption("UseJVMCICompiler");
        System.out.println(useJVMCICompiler);

        // What compiler is selected?
        String compiler = System.getProperty("jvmci.Compiler");
        System.out.printf("jvmci.Compiler = %s%n", compiler);
    }
}

