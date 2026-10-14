// Vendored from Mr-XiaoLiang/GrokBot at 2cd2e82f31cddcec7b825877830362b3ec364e33 (MIT, see
// third_party/grokbot/). Ported off Compose onto android.graphics: colours are ARGB ints here.

package com.lollipop.grokbot

import com.lollipop.grokbot.internal.GrokGeo

/**
 * Body silhouettes of the artwork, the `shape` option of the web build.
 *
 * The identifiers are the same as `geo.shapes` keys, so a value of this enum can be turned into an
 * engine shape name through [id] and back through [fromId].
 */
public enum class GrokShape(public val id: String) {
    BLOB("blob"),
    PEBBLE("pebble"),
    BEAN("bean"),
    EGG("egg"),
    SQUIRCLE("squircle"),
    TABLET("tablet"),
    CAPSULE("capsule"),
    CYLINDER("cylinder"),
    HEX("hex"),
    GEM("gem"),
    CRYSTAL("crystal"),
    WEDGE("wedge"),
    SHIELD("shield"),
    DOME("dome"),
    ARCH("arch"),
    CLOUD("cloud"),
    TEARDROP("teardrop"),
    LEAF("leaf"),
    ;

    /** Human readable name, e.g. `"Blob"`. Read from the generated geometry data. */
    public val label: String get() = labels.getValue(id)

    public companion object {
        private val byId: Map<String, GrokShape> = entries.associateBy { it.id }
        private val labels: Map<String, String> = GrokGeo.SHAPES.associate { it.id to it.label }

        /** Looks a shape up by its web identifier, `null` when unknown. */
        public fun fromId(id: String): GrokShape? = byId[id]
    }
}

/**
 * Body tints of the artwork, the `color` option of the web build.
 *
 * Every entry carries the two gradient stops the renderer needs; use [toArgb] to resolve the stop
 * of a [GrokScheme], or the `*Hex` properties to hand the value to a surface of another kind.
 */
public enum class GrokColor(public val id: String) {
    BLACK("black"),
    BROWN("brown"),
    RED("red"),
    ORANGE("orange"),
    YELLOW("yellow"),
    GREEN("green"),
    CYAN("cyan"),
    BLUE("blue"),
    VIOLET("violet"),
    MAGENTA("magenta"),
    GRAY("gray"),
    ;

    /** Upper-left gradient stop, `#RRGGBB` of the web palette. */
    public val lightHex: String get() = GrokGeo.PALETTE.getValue(id).light

    /** Lower-right gradient stop, `#RRGGBB` of the web palette. */
    public val darkHex: String get() = GrokGeo.PALETTE.getValue(id).dark

    public companion object {
        private val byId: Map<String, GrokColor> = entries.associateBy { it.id }

        /** Looks a tint up by its web identifier, `null` when unknown. */
        public fun fromId(id: String): GrokColor? = byId[id]
    }
}

/** Which of the two gradient stops of a [GrokColor] is used, the `scheme` option of the web build. */
public enum class GrokScheme(public val id: String) {
    LIGHT("light"),
    DARK("dark"),
}

/** How the mood is driven, the `mode` option of the web build. */
public enum class GrokMode(public val id: String) {
    /** Runs the onboarding reel, alternating `idle` with one mood of the reel every beat. */
    ONBOARDING("onboarding"),

    /** Stays on the mood the host selected. */
    HOLD("hold"),
}

/** Body tint of [scheme] as an opaque ARGB int, the gradient stop the host should show. */
public fun GrokColor.toArgb(scheme: GrokScheme = GrokScheme.LIGHT): Int =
    parseHexColor(if (scheme == GrokScheme.DARK) darkHex else lightHex)

/**
 * Turns a `#RRGGBB` (or `#AARRGGBB`) string into an opaque ARGB int.
 *
 * The generated palette only carries opaque `#RRGGBB` values, so alpha defaults to `FF`.
 */
internal fun parseHexColor(hex: String): Int {
    val digits = hex.removePrefix("#")
    val value = digits.toLongOrNull(16) ?: return 0xFF000000.toInt()
    return when (digits.length) {
        6 -> (value or 0xFF000000L).toInt()
        8 -> value.toInt()
        else -> 0xFF000000.toInt()
    }
}
