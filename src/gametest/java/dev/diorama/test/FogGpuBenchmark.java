package dev.diorama.test;

import dev.diorama.Diorama;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;

/** Offscreen GPU timings of the production shader, with/without its deep-cloud optimization. */
final class FogGpuBenchmark {
    static void run() {
        int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int drawFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING), readFbo = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int vao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING), active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        int packBuffer = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING), unpackBuffer = GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
        int[] stores = {GL11.GL_PACK_ALIGNMENT, GL11.GL_PACK_ROW_LENGTH, GL11.GL_PACK_SKIP_ROWS, GL11.GL_PACK_SKIP_PIXELS,
            GL11.GL_UNPACK_ALIGNMENT, GL11.GL_UNPACK_ROW_LENGTH, GL11.GL_UNPACK_SKIP_ROWS, GL11.GL_UNPACK_SKIP_PIXELS};
        int[] storeValues = new int[stores.length];
        for (int i = 0; i < stores.length; i++) { storeValues[i] = GL11.glGetInteger(stores[i]); GL11.glPixelStorei(stores[i], i == 0 || i == 4 ? 4 : 0); }
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0); GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
        int[] viewport = new int[4], textures = new int[2], samplers = new int[2]; GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        int[] enables = {GL11.GL_BLEND, GL11.GL_DEPTH_TEST, GL11.GL_CULL_FACE, GL11.GL_SCISSOR_TEST, GL30.GL_FRAMEBUFFER_SRGB};
        boolean[] enabled = new boolean[enables.length];
        for (int i = 0; i < enables.length; i++) { enabled[i] = GL11.glIsEnabled(enables[i]); GL11.glDisable(enables[i]); }
        for (int i = 0; i < 2; i++) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + i); textures[i] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            samplers[i] = GL30.glGetIntegeri(GL33.GL_SAMPLER_BINDING, i); GL33.glBindSampler(i, 0);
        }
        int fbo = 0, target = 0, mask = 0, noise = 0, array = 0, vertex = 0, reference = 0, optimized = 0;
        try {
            String fragment;
            try (var input = Minecraft.getInstance().getResourceManager().open(Diorama.id("shaders/core/fog.fsh"))) {
                fragment = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
            fragment = fragment.replaceAll("layout\\(std140\\) uniform (Globals|DynamicTransforms) \\{[\\s\\S]*?\\};", "")
                .replace("#extension GL_ARB_separate_shader_objects : require", "#extension GL_ARB_separate_shader_objects : require\nuniform float GameTime;\nuniform vec4 ColorModulator;");
            vertex = shader(GL20.GL_VERTEX_SHADER, """
                #version 330
                #extension GL_ARB_separate_shader_objects : require
                uniform vec4 TestParams;
                layout(location=0) out vec2 maskUv;
                layout(location=1) out vec4 params;
                void main() {
                    vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0); maskUv = p; params = TestParams;
                }
                """);
            optimized = link(vertex, fragment);
            reference = link(vertex, fragment.replace("bool cacheDeep = deep && !fast;", "bool cacheDeep = false;"));
            array = GL30.glGenVertexArrays(); GL30.glBindVertexArray(array);
            target = texture(512, null, false);
            fbo = GL30.glGenFramebuffers(); GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, target, 0);
            if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) throw new AssertionError("Benchmark FBO incomplete");
            GL11.glViewport(0, 0, 512, 512);
            ByteBuffer pixels = MemoryUtil.memAlloc(256 * 256 * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            try {
                for (int i = 0; i < 256 * 256; i++) pixels.putInt(0x00ff0000); // mask r=0, g=0, b=255, a=0
                pixels.flip();
                GL13.glActiveTexture(GL13.GL_TEXTURE0); mask = texture(256, pixels, false);
                pixels.clear();
                // Same smooth tileable noise generator as the shipped fog texture.
                var method = dev.diorama.render.FogTextures.class.getDeclaredMethod("fractal", int.class, int.class, long.class);
                method.setAccessible(true);
                float[][] channels = new float[4][];
                for (int c = 0; c < 4; c++) channels[c] = (float[]) method.invoke(null, 256, 6 << c, (long)c + 1);
                for (int i = 0; i < 256 * 256; i++) for (int c = 0; c < 4; c++) pixels.put((byte)Math.round(channels[c][i] * 255));
                pixels.flip(); GL13.glActiveTexture(GL13.GL_TEXTURE1); noise = texture(256, pixels, true);
            } finally { MemoryUtil.memFree(pixels); }
            long[] before = new long[3], after = new long[3];
            for (int i = 0; i < 3; i++) {
                before[i] = time(reference); after[i] = time(optimized);
            }
            Arrays.sort(before); Arrays.sort(after);
            int[] original = image(reference), result = image(optimized);
            long error = 0; int maximum = 0;
            for (int i = 0; i < result.length; i++) for (int c = 0; c < 3; c++) {
                int difference = Math.abs((original[i] >> (c * 8) & 255) - (result[i] >> (c * 8) & 255));
                error += difference; maximum = Math.max(maximum, difference);
            }
            double mean = error / (double)(result.length * 3);
            if (mean > 2) throw new AssertionError("Deep cloud approximation drifted: " + mean);
            Diorama.LOGGER.info("FOG_GPU 512x512 deep Fancy: reference={} ms, optimized={} ms, ratio={}, mean RGB error={}, max={}",
                before[1] / 80e6, after[1] / 80e6, after[1] / (double)before[1], mean, maximum);
        } catch (Exception error) { throw new AssertionError(error); }
        finally {
            GL20.glUseProgram(program);
            GL30.glBindVertexArray(vao);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFbo); GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFbo);
            GL11.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            for (int i = 0; i < 2; i++) {
                GL13.glActiveTexture(GL13.GL_TEXTURE0 + i); GL11.glBindTexture(GL11.GL_TEXTURE_2D, textures[i]); GL33.glBindSampler(i, samplers[i]);
            }
            GL13.glActiveTexture(active);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, packBuffer); GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, unpackBuffer);
            for (int i = 0; i < stores.length; i++) GL11.glPixelStorei(stores[i], storeValues[i]);
            for (int i = 0; i < enables.length; i++) if (enabled[i]) GL11.glEnable(enables[i]);
            if (array != 0) GL30.glDeleteVertexArrays(array);
            if (target != 0) GL11.glDeleteTextures(target); if (mask != 0) GL11.glDeleteTextures(mask); if (noise != 0) GL11.glDeleteTextures(noise);
            if (fbo != 0) GL30.glDeleteFramebuffers(fbo);
            if (vertex != 0) GL20.glDeleteShader(vertex); if (reference != 0) GL20.glDeleteProgram(reference); if (optimized != 0) GL20.glDeleteProgram(optimized);
        }
    }
    private static int texture(int edge, ByteBuffer pixels, boolean repeat) {
        int id = GL11.glGenTextures(); GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, edge, edge, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, repeat ? GL11.GL_REPEAT : GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, repeat ? GL11.GL_REPEAT : GL12.GL_CLAMP_TO_EDGE);
        return id;
    }
    private static int shader(int type, String text) {
        int id = GL20.glCreateShader(type); GL20.glShaderSource(id, text); GL20.glCompileShader(id);
        if (GL20.glGetShaderi(id, GL20.GL_COMPILE_STATUS) == 0) throw new AssertionError(GL20.glGetShaderInfoLog(id));
        return id;
    }
    private static int link(int vertex, String fragment) {
        int pixel = shader(GL20.GL_FRAGMENT_SHADER, fragment), program = GL20.glCreateProgram();
        GL20.glAttachShader(program, vertex); GL20.glAttachShader(program, pixel); GL20.glLinkProgram(program); GL20.glDeleteShader(pixel);
        if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == 0) throw new AssertionError(GL20.glGetProgramInfoLog(program));
        return program;
    }
    private static void use(int program) {
        GL20.glUseProgram(program);
        GL20.glUniform1i(GL20.glGetUniformLocation(program, "Sampler0"), 0); GL20.glUniform1i(GL20.glGetUniformLocation(program, "Sampler1"), 1);
        GL20.glUniform1f(GL20.glGetUniformLocation(program, "GameTime"), .2f);
        GL20.glUniform4f(GL20.glGetUniformLocation(program, "ColorModulator"), 1, 1, 1, 1);
        GL20.glUniform4f(GL20.glGetUniformLocation(program, "TestParams"), 31 / 255f, 31 / 255f, 0, 3 / 255f);
    }
    private static long time(int program) {
        use(program);
        for (int i = 0; i < 20; i++) GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        int query = GL15.glGenQueries(); GL15.glBeginQuery(GL33.GL_TIME_ELAPSED, query);
        for (int i = 0; i < 80; i++) GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        GL15.glEndQuery(GL33.GL_TIME_ELAPSED);
        long nanos = GL33.glGetQueryObjectui64(query, GL15.GL_QUERY_RESULT); GL15.glDeleteQueries(query); return nanos;
    }
    private static int[] image(int program) {
        use(program); GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        ByteBuffer buffer = MemoryUtil.memAlloc(512 * 512 * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        try {
            GL11.glReadPixels(0, 0, 512, 512, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);
            int[] pixels = new int[512 * 512]; buffer.asIntBuffer().get(pixels); return pixels;
        } finally { MemoryUtil.memFree(buffer); }
    }
}
