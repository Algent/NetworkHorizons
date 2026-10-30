package dev.aruka.networkhorizons.core;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import net.minecraft.launchwrapper.IClassTransformer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Relinks code compiled against Netty 4.0.10 when a newer 4.0.x is on the classpath (see launcher/README.md).
 * Only registered when such a Netty is present. Scanning GTNH (vanilla, Forge and every mod) found two breaks:
 * <ul>
 * <li>{@code ChannelInboundInvoker} / {@code ChannelOutboundInvoker} are gone, so calls like
 * {@code ctx.read()} compiled with those return types (FML's {@code NetworkDispatcher}) do not link. The same
 * methods now return the receiver's own type, so the descriptor is rewritten to that. The verifier accepts it:
 * interface types are not checked on assignment.</li>
 * <li>Some {@code EmptyArrays} fields are gone (used by Forestry); they are redirected to {@link NettyCompat}.</li>
 * </ul>
 */
public class NettyLinkFixer implements IClassTransformer {

    private static final Logger LOG = LogManager.getLogger("networkhorizons");

    static final String OUTBOUND_INVOKER = "io/netty/channel/ChannelOutboundInvoker";
    static final String INBOUND_INVOKER = "io/netty/channel/ChannelInboundInvoker";
    static final String EMPTY_ARRAYS = "io/netty/util/internal/EmptyArrays";
    static final String COMPAT = "dev/aruka/networkhorizons/core/NettyCompat";
    static final Set<String> REMOVED_EMPTY_ARRAYS = new HashSet<>(
        Arrays.asList("EMPTY_BOOLEANS", "EMPTY_DOUBLES", "EMPTY_FLOATS", "EMPTY_INTS", "EMPTY_SHORTS", "EMPTY_LONGS"));

    private static final byte[][] MARKERS = { bytes("ChannelOutboundInvoker"), bytes("ChannelInboundInvoker"),
        bytes(EMPTY_ARRAYS) };

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        // our own classes hold the marker strings: transforming them would load this transformer's classes again
        if (basicClass == null || name.startsWith("io.netty.")
            || name.startsWith("dev.aruka.networkhorizons.")
            || !containsAny(basicClass)) return basicClass;
        ClassReader reader = new ClassReader(basicClass);
        ClassWriter writer = new ClassWriter(reader, 0);
        int[] fixes = { 0 };
        reader.accept(new ClassVisitor(Opcodes.ASM5, writer) {

            @Override
            public MethodVisitor visitMethod(int access, String mName, String mDesc, String signature,
                String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM5, super.visitMethod(access, mName, mDesc, signature, exceptions)) {

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String iName, String desc, boolean itf) {
                        String fixed = fixInvokerReturn(owner, desc);
                        if (fixed != null) {
                            desc = fixed;
                            fixes[0]++;
                        }
                        super.visitMethodInsn(opcode, owner, iName, desc, itf);
                    }

                    @Override
                    public void visitFieldInsn(int opcode, String owner, String fName, String desc) {
                        if (opcode == Opcodes.GETSTATIC && EMPTY_ARRAYS.equals(owner)
                            && REMOVED_EMPTY_ARRAYS.contains(fName)) {
                            owner = COMPAT;
                            fixes[0]++;
                        }
                        super.visitFieldInsn(opcode, owner, fName, desc);
                    }
                };
            }
        }, 0);
        if (fixes[0] == 0) return basicClass;
        LOG.info("Relinked {} Netty 4.0.10 reference(s) in {}", fixes[0], transformedName);
        return writer.toByteArray();
    }

    /** New descriptor returning the owner's type, or null when the call does not return a removed invoker type. */
    static String fixInvokerReturn(String owner, String desc) {
        if (!owner.startsWith("io/netty/")) return null;
        int close = desc.lastIndexOf(')');
        String ret = desc.substring(close + 1);
        if (!ret.equals("L" + OUTBOUND_INVOKER + ";") && !ret.equals("L" + INBOUND_INVOKER + ";")) return null;
        return desc.substring(0, close + 1) + "L" + owner + ";";
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** Cheap pre-check on the raw class bytes (constant pool strings), so most classes are not parsed. */
    static boolean containsAny(byte[] haystack) {
        for (byte[] needle : MARKERS) {
            outer: for (int i = 0, end = haystack.length - needle.length; i <= end; i++) {
                for (int j = 0; j < needle.length; j++) {
                    if (haystack[i + j] != needle[j]) continue outer;
                }
                return true;
            }
        }
        return false;
    }
}
