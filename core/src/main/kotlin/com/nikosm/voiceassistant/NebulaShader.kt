package com.nikosm.voiceassistant

import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder

/**
 * The Live HUD's nebula, rendered per-pixel by Skia's runtime shader.
 *
 * This is the one part of the HUD that wants a GPU: a volumetric cloud is a noise field, and
 * drawing it as thousands of Compose circles would cost more than one fragment shader. Skiko is
 * already on the classpath through Compose Desktop, so this adds no dependency.
 *
 * ## Failure is a fallback, not a crash
 *
 * A runtime shader is compiled by the Skia in the installed Compose runtime, not by this
 * project's compiler. A driver or a Skiko upgrade can reject the SkSL, so construction catches a
 * failed compile and [available] becomes false; [LiveCore] then draws its layered-gradient
 * fallback and the HUD still works.
 *
 * ## Scale
 *
 * The cloud is drawn only inside the core canvas (a few hundred points square), not over the
 * whole window. The backdrop is static Compose gradients. Combined with the HUD's 30/10 fps cap,
 * the shader's cost stays bounded even on an integrated GPU.
 */
internal class NebulaRenderer : AutoCloseable {

    private val effect: RuntimeEffect? = runCatching {
        RuntimeEffect.makeForShader(NEBULA_SKSL)
    }.getOrNull()

    private val builder: RuntimeShaderBuilder? = effect?.let { RuntimeShaderBuilder(it) }
    private val paint = Paint().apply { isAntiAlias = true }

    val available: Boolean get() = builder != null

    /**
     * Draws the cloud into [canvas]. Returns false when the shader is unavailable, so the caller
     * can paint its fallback; a successful draw returns true.
     */
    fun draw(
        canvas: Canvas,
        width: Float,
        height: Float,
        timeSeconds: Float,
        level: Float,
        energy: Float,
        primary: FloatArray,
        secondary: FloatArray,
        hot: FloatArray,
        deep: FloatArray,
    ): Boolean {
        val builder = builder ?: return false
        if (width <= 0f || height <= 0f) return false

        builder.uniform("u_resolution", width, height)
        builder.uniform("u_time", timeSeconds)
        builder.uniform("u_level", level.coerceIn(0f, 1f))
        builder.uniform("u_energy", energy.coerceIn(0f, 1f))
        builder.uniform("u_primary", primary[0], primary[1], primary[2])
        builder.uniform("u_secondary", secondary[0], secondary[1], secondary[2])
        builder.uniform("u_white", hot[0], hot[1], hot[2])
        builder.uniform("u_deep", deep[0], deep[1], deep[2])

        val shader = builder.makeShader()
        try {
            paint.shader = shader
            canvas.drawRect(Rect(0f, 0f, width, height), paint)
        } finally {
            shader.close()
        }
        return true
    }

    override fun close() {
        paint.close()
        builder?.close()
        effect?.close()
    }
}

/**
 * SkSL for the cloud. The colour is entirely uniform-driven; the shader only decides density and
 * where the hot centre sits. The noise is value noise with four octaves plus one detail octave —
 * enough structure for a nebula, cheap enough for a fragment shader at HUD sizes.
 */
internal val NEBULA_SKSL = """
    uniform float2 u_resolution;
    uniform float  u_time;
    uniform float  u_level;
    uniform float  u_energy;
    uniform float3 u_primary;
    uniform float3 u_secondary;
    uniform float3 u_white;
    uniform float3 u_deep;

    float hash21(float2 p) {
        p = fract(p * float2(123.34, 456.21));
        p += dot(p, p + 45.32);
        return fract(p.x * p.y);
    }

    float valueNoise(float2 p) {
        float2 i = floor(p);
        float2 f = fract(p);
        f = f * f * (3.0 - 2.0 * f);
        float a = hash21(i);
        float b = hash21(i + float2(1.0, 0.0));
        float c = hash21(i + float2(0.0, 1.0));
        float d = hash21(i + float2(1.0, 1.0));
        return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
    }

    float fbm4(float2 p) {
        float n = 0.0;
        float amp = 0.55;
        n += amp * valueNoise(p); p = p * 2.03 + float2(17.1, 9.7);  amp *= 0.5;
        n += amp * valueNoise(p); p = p * 2.03 + float2(-7.3, 13.9); amp *= 0.5;
        n += amp * valueNoise(p); p = p * 2.03 + float2(5.1, -11.7); amp *= 0.5;
        n += amp * valueNoise(p);
        return n;
    }

    half4 main(float2 fragCoord) {
        float2 res = max(u_resolution, float2(1.0));
        float2 p = (fragCoord - 0.5 * res) / min(res.x, res.y);
        float r = length(p);
        float level = clamp(u_level, 0.0, 1.0);
        float energy = clamp(u_energy, 0.0, 1.0);

        float2 drift = float2(
            u_time * (0.030 + 0.070 * energy),
            -u_time * (0.022 + 0.050 * energy)
        );
        float2 q = p * 3.2 + drift;
        float n1 = fbm4(q);
        float detail = valueNoise(q * 3.4 - float2(n1 * 0.85, n1 * 0.65) + drift * 2.1);
        float fine = valueNoise(q * 7.0 + float2(-n1 * 1.2, n1 * 1.1));

        float reach = 0.40 + 0.04 * level;
        float e = r / reach;
        float envelope = exp(-e * e);
        // The core canvas is square, so the cloud must fade out before r reaches the edge;
        // without this mask its own envelope would still be drawing when the square clipped it.
        // The noise on the boundary breaks the perfect circle the mask would otherwise reveal.
        float edgeJitter = (detail - 0.5) * 0.09 + (n1 - 0.5) * 0.05;
        float edgeMask = 1.0 - smoothstep(0.34, 0.49, r + edgeJitter);
        float density = (0.08 + 0.90 * n1 + 0.45 * detail + 0.15 * fine) * envelope;
        density = smoothstep(0.10, 0.90, density);
        // One offset lobe gives the cloud a direction instead of a centred balloon.
        float2 lobeP = p - float2(0.17, -0.12);
        float lobe = exp(-dot(lobeP, lobeP) / 0.055);
        density = clamp(density + lobe * (0.20 + 0.30 * detail), 0.0, 1.0);
        density *= (0.85 + 0.15 * level) * edgeMask;

        float c = r / (0.155 + 0.035 * level);
        float core = exp(-c * c) * (0.55 + 0.35 * level);

        float g = r / 1.05;
        float glow = exp(-g * g);

        float3 col = u_deep * (1.0 - density);
        col += u_primary * (density * 0.85);
        col += u_secondary * (0.30 * detail * density);
        col += u_white * core;
        col += u_primary * (0.10 * glow * edgeMask);

        float alpha = clamp(density * 1.25 + core * 0.55, 0.0, 1.0) * edgeMask;
        return half4(half3(col * alpha), half(alpha));
    }
""".trimIndent()
