// Vendored from Mr-XiaoLiang/GrokBot at 2cd2e82f31cddcec7b825877830362b3ec364e33 (MIT, see
// third_party/grokbot/). Ported off Compose onto android.graphics: colours are ARGB ints and the
// forced size is in pixels, so no Compose `Dp`/`Color` takes part.

package com.lollipop.grokbot

import com.lollipop.grokbot.internal.GrokEngineOptions
import com.lollipop.grokbot.internal.GrokEyeOptions

/**
 * Everything that describes one [GrokBotView], the Kotlin side of the `opts` object of
 * `temp/replica/index.html`.
 *
 * The defaults reproduce the configuration the product ships with: a `blob` body in the light
 * `black` tint that runs the `idle` mood in the onboarding reel and follows the pointer.
 *
 * Immutable on purpose, so a host can keep a single instance in state and hand out modified copies
 * with [copy]. It carries no engine state; [GrokBotView] translates it once when the engine is built.
 *
 * @param shape body silhouette.
 * @param color body tint, resolved against [scheme].
 * @param scheme which gradient stop of [color] is painted.
 * @param mood mood to enter on start; ignored by the onboarding reel once it starts cycling.
 * @param mode whether the mood is driven by the reel or held by the host.
 * @param followPointer lets the eyes track the last touch position.
 * @param loginWrap keeps the login wording and the simplified eye topology of the shipped product.
 * @param emphasis draws the eyes with the emphasis pupil and lid tuning.
 * @param paused freezes the animation on the current frame.
 * @param reduceMotion stills the pulses and the blinking, for hosts that honour the OS setting.
 * @param sizePx forces the square size, in pixels; `null` lets the view fill its parent.
 * @param flatInk paints the body with a single flat colour instead of the [color] gradient.
 * @param eyeColor overrides the eye disk colour, as an ARGB int; `null` keeps the artwork default.
 * @param badgeColor colour of the badge blob inside the eye, `#1D9BF0` by default.
 */
public data class GrokBotConfig(
    public val shape: GrokShape = GrokShape.BLOB,
    public val color: GrokColor = GrokColor.BLACK,
    public val scheme: GrokScheme = GrokScheme.LIGHT,
    public val mood: GrokMood = GrokMood.IDLE,
    public val mode: GrokMode = GrokMode.ONBOARDING,
    public val followPointer: Boolean = true,
    public val loginWrap: Boolean = true,
    public val emphasis: Boolean = false,
    public val paused: Boolean = false,
    public val reduceMotion: Boolean = false,
    public val sizePx: Int? = null,
    public val flatInk: Int? = null,
    public val eyeColor: Int? = null,
    public val badgeColor: Int = GrokEyeOptions.DEFAULT_BADGE,
)

/**
 * Constructor options of the engine behind [config].
 *
 * `sizePx` is deliberately not forwarded: the engine never reads it, the size belongs to the view
 * that lays the canvas out.
 */
internal fun GrokBotConfig.toEngineOptions(): GrokEngineOptions = GrokEngineOptions(
    shape = shape.id,
    color = color.id,
    scheme = scheme.id,
    mode = mode.id,
    state = mood,
    loginWrap = loginWrap,
    emphasis = emphasis,
    followPointer = followPointer,
    paused = paused,
    reduceMotion = reduceMotion,
    inkFlat = flatInk,
    eyeColor = eyeColor,
    badgeColor = badgeColor,
)
