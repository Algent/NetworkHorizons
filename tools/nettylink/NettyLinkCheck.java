import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

import org.objectweb.asm.*;

/**
 * Checks every reference to io/netty/* in the given jars against a runtime Netty jar, like the JVM linker would:
 * missing classes, missing methods (searched in the class, its superclasses and superinterfaces) and missing fields.
 * Usage: NettyLinkCheck <runtime-netty.jar> <jar-or-dir>...
 */
public class NettyLinkCheck {

    static final class Info {
        String superName;
        String[] interfaces;
        final Set<String> methods = new HashSet<>();
        final Set<String> fields = new HashSet<>();
        /** Abstract methods (interfaces, abstract classes). */
        final Set<String> abstractMethods = new HashSet<>();
        boolean isInterface;
    }

    static final Map<String, Info> runtime = new HashMap<>();
    // missing ref -> set of "jar: class"
    static final Map<String, Set<String>> missing = new TreeMap<>();
    static int refs, classesScanned;

    public static void main(String[] args) throws Exception {
        indexJar(new ZipInputStream(new FileInputStream(args[0])));
        for (int i = 1; i < args.length; i++) {
            File f = new File(args[i]);
            if (f.isDirectory()) {
                File[] jars = f.listFiles((d, n) -> n.endsWith(".jar"));
                Arrays.sort(jars);
                for (File j : jars) scanJar(j.getName(), new ZipInputStream(new FileInputStream(j)));
            } else {
                scanJar(f.getName(), new ZipInputStream(new FileInputStream(f)));
            }
        }
        System.out.println("classes scanned " + classesScanned + ", netty refs " + refs + ", missing " + missing.size());
        for (Map.Entry<String, Set<String>> e : missing.entrySet()) {
            Set<String> users = e.getValue();
            Set<String> jars = new TreeSet<>();
            for (String u : users) jars.add(u.substring(0, u.indexOf(':')));
            System.out.println(e.getKey() + "   <- " + users.size() + " class(es) in " + jars);
        }
    }

    static byte[] read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) out.write(b, 0, n);
        return out.toByteArray();
    }

    static void indexJar(ZipInputStream zin) throws IOException {
        ZipEntry e;
        while ((e = zin.getNextEntry()) != null) {
            if (!e.getName().endsWith(".class") || !e.getName().startsWith("io/netty/")) continue;
            ClassReader cr = new ClassReader(read(zin));
            Info info = new Info();
            info.superName = cr.getSuperName();
            info.interfaces = cr.getInterfaces();
            info.isInterface = (cr.getAccess() & Opcodes.ACC_INTERFACE) != 0;
            cr.accept(new ClassVisitor(Opcodes.ASM9) {
                public MethodVisitor visitMethod(int a, String n, String d, String s, String[] x) {
                    info.methods.add(n + d);
                    if ((a & Opcodes.ACC_ABSTRACT) != 0) info.abstractMethods.add(n + d);
                    return null;
                }
                public FieldVisitor visitField(int a, String n, String d, String s, Object v) {
                    info.fields.add(n + ":" + d);
                    return null;
                }
            }, ClassReader.SKIP_CODE);
            runtime.put(cr.getClassName(), info);
        }
    }

    static void scanJar(String jarName, ZipInputStream zin) throws IOException {
        ZipEntry e;
        while ((e = zin.getNextEntry()) != null) {
            String n = e.getName();
            if (n.endsWith(".jar")) {
                scanJar(jarName + "!" + n, new ZipInputStream(new ByteArrayInputStream(read(zin))));
            } else if (n.endsWith(".class") && !n.startsWith("io/netty/") && !n.startsWith("META-INF/versions/")) {
                try {
                    scanClass(jarName, read(zin));
                } catch (RuntimeException ex) {
                    // unreadable class (obfuscated junk, newer class format): skip
                }
            }
        }
    }

    static void scanClass(String jar, byte[] bytes) {
        ClassReader cr = new ClassReader(bytes);
        classesScanned++;
        String self = cr.getClassName();
        String where = jar + ": " + self;
        checkClass(cr.getSuperName(), where);
        for (String i : cr.getInterfaces()) checkClass(i, where);
        boolean concrete = (cr.getAccess() & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE)) == 0;
        boolean nettySuper = netty(cr.getSuperName());
        for (String i : cr.getInterfaces()) nettySuper |= netty(i);
        if (concrete && nettySuper) {
            Set<String> own = new HashSet<>();
            cr.accept(new ClassVisitor(Opcodes.ASM9) {
                public MethodVisitor visitMethod(int a, String n, String d, String s, String[] x) {
                    if ((a & Opcodes.ACC_ABSTRACT) == 0) own.add(n + d);
                    return null;
                }
            }, ClassReader.SKIP_CODE);
            Set<String> required = new TreeSet<>();
            Set<String> provided = new HashSet<>(own);
            collectAbstract(cr.getSuperName(), required, provided, new HashSet<>());
            for (String i : cr.getInterfaces()) collectAbstract(i, required, provided, new HashSet<>());
            for (String m : required) {
                // only judged when the class's whole hierarchy is Netty or JDK: a mod superclass may implement it
                if (!provided.contains(m) && !isObjectMethod(m) && (netty(cr.getSuperName()) || cr.getSuperName().startsWith("java/"))) {
                    miss("unimplemented " + m + " (abstract in the Netty hierarchy)", where);
                }
            }
        }
        cr.accept(new ClassVisitor(Opcodes.ASM9) {
            public MethodVisitor visitMethod(int a, String n, String d, String s, String[] x) {
                return new MethodVisitor(Opcodes.ASM9) {
                    public void visitMethodInsn(int op, String owner, String name, String desc, boolean itf) {
                        checkMethod(owner, name, desc, where);
                    }
                    public void visitFieldInsn(int op, String owner, String name, String desc) {
                        checkField(owner, name, desc, where);
                    }
                    public void visitTypeInsn(int op, String type) {
                        checkClass(type, where);
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    }

    /** Walks a Netty hierarchy: concrete methods of classes count as provided, abstract ones as required. */
    static void collectAbstract(String name, Set<String> required, Set<String> provided, Set<String> seen) {
        if (name == null || !seen.add(name)) return;
        Info info = runtime.get(name);
        if (info == null) return;
        for (String m : info.methods) {
            if (info.abstractMethods.contains(m)) required.add(m);
            else if (!info.isInterface) provided.add(m);
        }
        collectAbstract(info.superName, required, provided, seen);
        for (String i : info.interfaces) collectAbstract(i, required, provided, seen);
    }

    static boolean netty(String owner) {
        return owner != null && owner.startsWith("io/netty/");
    }

    static void miss(String key, String where) {
        missing.computeIfAbsent(key, k -> new TreeSet<>()).add(where);
    }

    static void checkClass(String name, String where) {
        if (!netty(name)) return;
        refs++;
        if (!runtime.containsKey(name)) miss("class " + name, where);
    }

    static void checkMethod(String owner, String name, String desc, String where) {
        if (!netty(owner)) return;
        refs++;
        if (!runtime.containsKey(owner)) {
            miss("class " + owner, where);
            return;
        }
        if (!hasMethod(owner, name + desc, new HashSet<>()) && !isObjectMethod(name + desc)) {
            miss("method " + owner + "." + name + desc, where);
        }
    }

    static boolean isObjectMethod(String sig) {
        return sig.startsWith("equals(") || sig.startsWith("hashCode(") || sig.startsWith("toString(")
            || sig.startsWith("getClass(") || sig.startsWith("notify") || sig.startsWith("wait(");
    }

    static boolean hasMethod(String owner, String sig, Set<String> seen) {
        if (owner == null || !seen.add(owner)) return false;
        Info info = runtime.get(owner);
        if (info == null) return false; // left io/netty (e.g. java/lang/Object)
        if (info.methods.contains(sig)) return true;
        if (hasMethod(info.superName, sig, seen)) return true;
        for (String i : info.interfaces) if (hasMethod(i, sig, seen)) return true;
        return false;
    }

    static void checkField(String owner, String name, String desc, String where) {
        if (!netty(owner)) return;
        refs++;
        String key = name + ":" + desc;
        for (String c = owner; c != null && runtime.containsKey(c); c = runtime.get(c).superName) {
            if (runtime.get(c).fields.contains(key)) return;
        }
        miss("field " + owner + "." + key, where);
    }
}
