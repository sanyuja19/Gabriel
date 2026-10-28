package com.gabriel.android.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Gabriel-specific color tokens that do not have a faithful Material 3 semantic role.
 *
 * Standard backgrounds, surfaces, text, outlines, primary/secondary accents, and errors belong
 * to [androidx.compose.material3.MaterialTheme.colorScheme]. Keeping only the extra app semantics
 * here lets Material components inherit correct defaults without losing Gabriel's identity.
 */
@Immutable
data class GabrielPalette(
    // MARK: - Form controls
    /**
     * Resting border for text inputs. Deliberately a neutral grey rather than the green-tinted
     * Material outline: the composer is the one surface the user stares at while typing.
     */
    val inputOutline: Color,
    /** Border for a focused text input. A step brighter, still neutral. */
    val inputOutlineFocused: Color,
    /**
     * Fill for text inputs. Near-black / near-white and completely untinted, for the same reason
     * as [inputOutline] — and because the composer sits on top of a green-tinted scrim, so any
     * tint of its own compounds into something muddy.
     */
    val inputSurface: Color,
    /** Fill for a focused text input. A barely perceptible lift. */
    val inputSurfaceFocused: Color,
    /** Resting disc behind the composer's action glyphs. Neutral grey. */
    val inputButton: Color,

    // MARK: - Extra semantics
    /** Timestamps, placeholders, section labels, disabled states. */
    val textTertiary: Color,
    /** Self, mentions targeting you, unread DMs. */
    val accentOrange: Color,
    /** Nostr reachability. */
    val accentPurple: Color,

    // MARK: - Deterministic peer colors
    /**
     * Saturation/value applied after deriving a peer's stable hue. Swap this when adding a
     * new theme — see [PeerColorStyle] for contrast guidelines.
     */
    val peerColors: PeerColorStyle,
)

val DarkGabrielPalette = GabrielPalette(
    inputOutline = Color(0xFF384649),
    inputOutlineFocused = Color(0xFF0F6E7C),
    inputSurface = Color(0xFF131B1E),
    inputSurfaceFocused = Color(0xFF1C272A),
    inputButton = Color(0xFF253134),
    textTertiary = Color(0xFFA0ADB0),
    accentOrange = Color(0xFFE0A100), // Amber
    accentPurple = Color(0xFFBF5AF2),
    peerColors = PeerColorStyle.Dark,
)

val LightGabrielPalette = GabrielPalette(
    inputOutline = Color(0xFFBCC6C9),
    inputOutlineFocused = Color(0xFF0F6E7C),
    inputSurface = Color(0xFFFFFFFF),
    inputSurfaceFocused = Color(0xFFE9EDEE),
    inputButton = Color(0xFFDCE2E4),
    textTertiary = Color(0xFF4C575A),
    accentOrange = Color(0xFFE0A100), // Amber
    accentPurple = Color(0xFFAF52DE),
    peerColors = PeerColorStyle.Light,
)

val LocalGabrielPalette = staticCompositionLocalOf { DarkGabrielPalette }

/**
 * Motion tokens. The redesign leans on short, snappy transitions: long durations read as
 * sluggish on a chat surface where the user is scanning quickly.
 */
object GabrielMotion {
    /** Icon tints, text colors, small fills. */
    const val QUICK_MS = 120

    /** Tab indicators, pill growth, chip reveals. */
    const val STANDARD_MS = 180

    /** Sheet-level fades and scroll-driven top bars. */
    const val EMPHASIZED_MS = 240
}
