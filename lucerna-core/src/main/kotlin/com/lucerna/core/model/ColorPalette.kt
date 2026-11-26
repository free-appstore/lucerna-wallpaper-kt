package com.lucerna.core.model

import kotlin.math.abs
import kotlin.random.Random

/**
 * 按色彩和谐规律随机生成一组新配色，类型保持不变。
 *
 * - 纯色：只换一个高饱和的中间调颜色
 * - 渐变：2–4 个颜色 + 8 个预设方向之一
 * - 色阶：2–5 个颜色 + 8 个预设方向之一 + 随机阶数（不少于颜色数量）
 *
 * 颜色不是完全乱撒：先随机选一种和谐方案（单色 / 邻近 / 互补 / 分裂互补 / 三角 / 四角），
 * 再沿方案给出的色相取色，并用一条从深到浅的明度渐变把颜色串起来。
 * 渐变只用单色 / 邻近色，避免互补色在中间插值成灰色；色阶可以用对比更强的方案。
 * 饱和度、明度都限制在中间偏上的区间，避免出现脏色、灰扑扑或刺眼的组合。
 */
fun ColorFill.randomized(random: Random = Random.Default): ColorFill = when (kind) {
    ColorKind.SOLID -> copy(colors = listOf(randomSolidColor(random)))

    ColorKind.GRADIENT -> copy(
        colors = randomGradientColors(random.nextInt(2, 5), random),
        angle = randomDirection(random),
    ).normalized()

    ColorKind.STEPS -> {
        val colors = randomStepColors(random.nextInt(2, 6), random)
        copy(
            colors = colors,
            angle = randomDirection(random),
            // 阶数不能少于颜色数量；3–12 阶既看得出硬边，又不会碎成噪点
            steps = random.nextInt(colors.size.coerceAtLeast(3), 13),
        ).normalized()
    }
}

/** 色相环上的常见和谐关系；偏移量单位是度。 */
private enum class Harmony(private val offsets: FloatArray) {
    MONOCHROMATIC(floatArrayOf(0f)),
    ANALOGOUS(floatArrayOf(-30f, 0f, 30f)),
    COMPLEMENTARY(floatArrayOf(0f, 180f)),
    SPLIT_COMPLEMENTARY(floatArrayOf(0f, 150f, 210f)),
    TRIADIC(floatArrayOf(0f, 120f, 240f)),
    TETRADIC(floatArrayOf(0f, 90f, 180f, 270f));

    fun hues(baseHue: Float): FloatArray = FloatArray(offsets.size) { index ->
        normalizeHue(baseHue + offsets[index])
    }
}

/**
 * 渐变配色：只用单色或邻近色。
 *
 * 渐变会把相邻颜色直接插值，互补色 / 三角色在中间会混出一段发灰的过渡；
 * 邻近色既能拉开色相，又能保持过渡干净。色相沿调色板连续变化，
 * 避免出现「转过去又转回来」的折返。
 */
private fun randomGradientColors(count: Int, random: Random): List<Rgb> {
    val monochrome = random.nextFloat() < 0.35f
    val baseHue = random.nextFloat() * 360f
    // 邻近色的跨度：太小像单色，太大就会开始发灰
    val hueSpread = if (monochrome) 0f else 18f + random.nextFloat() * 26f
    // 明度沿调色板单调变化，方向随机：同一组色相也能得到有层次的渐变
    val fromDark = random.nextBoolean()
    val darkValue = 0.38f + random.nextFloat() * 0.12f
    val lightValue = 0.88f + random.nextFloat() * 0.10f
    val baseSaturation = 0.60f + random.nextFloat() * 0.28f

    return List(count) { index ->
        val t = if (count <= 1) 0f else index / (count - 1f)
        val value = if (fromDark) lerp(darkValue, lightValue, t) else lerp(lightValue, darkValue, t)
        val hue = baseHue + lerp(-hueSpread, hueSpread, t) + random.nextFloat() * 6f - 3f
        val saturation = (baseSaturation + random.nextFloat() * 0.10f - 0.05f).coerceIn(0.5f, 0.92f)
        val jitteredValue = (value + random.nextFloat() * 0.03f - 0.015f).coerceIn(0.3f, 0.98f)
        hsvToRgb(hue, saturation, jitteredValue)
    }
}

/**
 * 色阶配色：可以放心使用互补 / 三角 / 四角等对比更强的方案。
 *
 * 色阶是硬边色块，不存在中间插值，所以饱和度和明度整体比渐变再高一档；
 * 色相按色环顺序排列、同一色相相邻成组，色块从暗到亮过渡，避免红绿相间那种刺眼排版。
 */
private fun randomStepColors(count: Int, random: Random): List<Rgb> {
    val harmony = Harmony.entries.random(random)
    val hues = harmony.hues(random.nextFloat() * 360f).sorted()
    val fromDark = random.nextBoolean()
    val darkValue = 0.48f + random.nextFloat() * 0.12f
    val lightValue = 0.86f + random.nextFloat() * 0.11f
    val baseSaturation = 0.68f + random.nextFloat() * 0.24f

    return List(count) { index ->
        val t = if (count <= 1) 0f else index / (count - 1f)
        val value = if (fromDark) lerp(darkValue, lightValue, t) else lerp(lightValue, darkValue, t)
        // 色相按比例分组，同一色相尽量连在一起；数量多于色相时自然重复
        val hue = hues[index * hues.size / count] + random.nextFloat() * 10f - 5f
        val saturation = (baseSaturation + random.nextFloat() * 0.12f - 0.06f).coerceIn(0.55f, 0.94f)
        val jitteredValue = (value + random.nextFloat() * 0.04f - 0.02f).coerceIn(0.42f, 0.97f)
        hsvToRgb(hue, saturation, jitteredValue)
    }
}

/** 纯色模式：只取一个颜色，饱和度与明度都落在“有颜色但不刺眼”的区间。 */
private fun randomSolidColor(random: Random): Rgb = hsvToRgb(
    hue = random.nextFloat() * 360f,
    saturation = 0.55f + random.nextFloat() * 0.35f,
    value = 0.62f + random.nextFloat() * 0.33f,
)

/** 只从界面九宫格的 8 个方向里选，和方向键一一对应。 */
private fun randomDirection(random: Random): Float = ColorFill.DIRECTIONS.random(random)

private fun normalizeHue(hue: Float): Float = (hue % 360f + 360f) % 360f

private fun lerp(start: Float, end: Float, t: Float): Float = start + (end - start) * t

/** HSV → RGB；色相以度为单位，三个分量都归一化到 0..1。 */
private fun hsvToRgb(hue: Float, saturation: Float, value: Float): Rgb {
    val h = normalizeHue(hue) / 60f
    val chroma = value * saturation
    val second = chroma * (1f - abs(h % 2f - 1f))
    val base = value - chroma
    val (r, g, b) = when {
        h < 1f -> Triple(chroma, second, 0f)
        h < 2f -> Triple(second, chroma, 0f)
        h < 3f -> Triple(0f, chroma, second)
        h < 4f -> Triple(0f, second, chroma)
        h < 5f -> Triple(second, 0f, chroma)
        else -> Triple(chroma, 0f, second)
    }
    return Rgb(r + base, g + base, b + base).normalized()
}
