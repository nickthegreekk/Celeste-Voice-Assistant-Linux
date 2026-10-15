package com.nikosm.voiceassistant

import java.nio.file.Path

/**
 * The fifteenth seam. PLAN.md §5 lists fourteen and this is not among them, because the need for
 * it is invisible until the persona editor is ported: `SettingsManager`'s artwork path is built on
 * `android.graphics.BitmapFactory` and `Bitmap.compress`, and neither has a counterpart anywhere in
 * the list.
 *
 * It is a seam rather than a helper in `:platform` because `:core`'s settings manager is what calls
 * it — the rule is that the interface lives in `:core` and the implementation in `:platform`
 * (AGENTS.md §4).
 *
 * **File in, file out.** The phone's `decodeDownsampled` returns a `Bitmap` and its caller writes
 * it, which would drag a graphics type into `:core`'s signature. Nothing in the ported logic wants
 * the pixels; it wants a smaller file next to the original. So the seam is a conversion and
 * `:core` never sees an image type at all.
 *
 * ## What the implementation must preserve
 *
 * `decodeDownsampled` is not a resize; it encodes two rules that were arrived at by watching it
 * fail, and a naive `ImageIO.read` + scale would lose both:
 *
 *  1. **Do not materialise the full-resolution image.** A 12 MP phone photo is about 48 MB as an
 *     ARGB buffer, which is the classic picker-triggered OOM — upstream's own comment says so. The
 *     phone reads only the header to learn the dimensions, picks a power-of-two subsample, and
 *     decodes at that size. `ImageReadParam.setSourceSubsampling` is the JVM equivalent and the
 *     same discipline applies here.
 *  2. **The subsample is chosen so *both* edges stay at or above the target**, not just the long
 *     one, so a landscape photo is not over-shrunk on its short edge; and because subsampling only
 *     does powers of two, a result that still overshoots is then rescaled *exactly*, preserving
 *     the aspect ratio, and never upscaled.
 *
 * The failure contract is upstream's too: a source that cannot be read or decoded returns `false`
 * rather than throwing. The caller keeps the persona's previous artwork in that case, and a
 * corrupt file the user picked is not an error worth taking the application down for.
 */
interface ImageCodec {

    /**
     * Writes [source] to [target] as JPEG, downscaled so its longest edge is at most [maxDim] and
     * never enlarged, at [quality] on the 0–100 scale Android's `Bitmap.compress` uses.
     *
     * @return false when the source could not be read or decoded — never throws for that.
     */
    fun downscaleToJpeg(source: Path, target: Path, maxDim: Int, quality: Int): Boolean
}
