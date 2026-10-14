// Vendored from Mr-XiaoLiang/GrokBot at 2cd2e82f31cddcec7b825877830362b3ec364e33 (MIT, see
// third_party/grokbot/). Ported off Compose onto android.graphics: the state is a plain object whose
// mood is read through an observer, and the drawing host is a View with a Choreographer frame loop
// in place of the Canvas composable.

package com.lollipop.grokbot

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import com.lollipop.grokbot.internal.GrokEngine
import com.lollipop.grokbot.internal.GrokFrameStyle
import com.lollipop.grokbot.internal.GrokFrameTrace
import com.lollipop.grokbot.internal.GrokPoint
import com.lollipop.grokbot.internal.GrokRenderer

/**
 * Everything a host drives a [GrokBotView] with, plus the clock that keeps it running.
 *
 * The state owns the [GrokEngine] and the [GrokRenderer], so it has to outlive the view that draws
 * it: keep one per voice session and hand it to the view, or let the view create its own when none
 * is given.
 *
 * [mood] and [frameTick] are observable through [observers], so a host can highlight the mood it
 * selected in its own UI. Every other member is either immutable or a plain switch onto the engine.
 * All actions are safe to call at any time, including before the first frame.
 */
public class GrokBotState(@JvmField internal val initialConfig: GrokBotConfig) {

    internal val engine: GrokEngine = GrokEngine(initialConfig.toEngineOptions())

    /**
     * Only the draw pass needs this, so it is built on the first frame rather than with the state.
     *
     * It carries a path cache and scratch `Path`/`Matrix` instances, which are the one part of this
     * class that needs a live graphics stack; keeping it out of the constructor is what lets the
     * engine be driven - and diffed by [apply] - in a plain JVM test.
     */
    internal val renderer: GrokRenderer by lazy { GrokRenderer() }

    internal val style: GrokFrameStyle = GrokFrameStyle()

    /** Reused touch position; the engine only keeps the reference, it never owns the point. */
    private val pointer = GrokPoint()

    /** Configuration the engine currently reflects, the base of the diff in [apply]. */
    private var config: GrokBotConfig = initialConfig

    private var started: Boolean = false

    /**
     * Stamp of the last driven frame.
     *
     * The engine works on an absolute millisecond clock, so every call made from outside the frame
     * loop uses this value instead of a platform clock of its own.
     */
    private var clock: Float = 0f

    /** Reused by [frameTrace]; the record is only ever read as the string handed to the sink. */
    private val traceBuffer = StringBuilder(256)

    /** Frames driven so far, the `f=` field of the record. */
    private var frameIndex = 0

    /** Listeners told about a mood change and about every driven frame. */
    private val observers = ArrayList<Observer>(2)

    /**
     * Mood on screen.
     *
     * While [GrokBotConfig.mode] is [GrokMode.ONBOARDING] it advances on its own every
     * [GrokMood.onboardingIntervalMillis]; [show] parks it on one mood.
     */
    public var mood: GrokMood = initialConfig.mood
        private set

    /** Frames driven so far, the value a host watches to know the face is alive. */
    public val frameTick: Int get() = frameIndex

    /** Configuration the running engine was last told about. */
    public val currentConfig: GrokBotConfig get() = config

    /** Freezes the animation on the current frame; the face keeps following the pointer state. */
    public var paused: Boolean
        get() = engine.paused
        set(value) {
            engine.setPaused(value)
        }

    /** Whether the eyes track the touch position of [GrokBotView]. */
    public var followPointer: Boolean
        get() = engine.followPointer
        set(value) {
            engine.setFollowPointer(value)
        }

    /**
     * Sink for the per-frame curve record, handed one line after every driven frame; `null`, the
     * default, records nothing.
     *
     * This is the device half of the reference fixture test. It exists so a character that
     * misbehaves on a phone can be read off logcat in the vocabulary of `reference/frames.txt` - the
     * springs, the gaze, the blink, the eye morph, the composed transform - instead of in prints
     * written for the occasion. [GrokFrameTrace] documents the fields, and why the geometry is not
     * among them.
     *
     * Nothing is allocated while it is `null` beyond the counter increment, and the line is built in
     * a buffer that is reused, so a sink that writes to logcat is the only cost of turning it on.
     */
    public var frameTrace: ((String) -> Unit)? = null

    init {
        engine.build()
        engine.options.changeListener = { snapshot ->
            mood = snapshot.state
            for (slot in observers.indices) observers[slot].onGrokMoodChanged(snapshot.state)
        }
    }

    /** Adds [observer]; adding it twice has no further effect. */
    public fun addObserver(observer: Observer) {
        if (!observers.contains(observer)) observers.add(observer)
    }

    /** Removes [observer], which then hears nothing more. */
    public fun removeObserver(observer: Observer) {
        observers.remove(observer)
    }

    /** Enters [mood] and holds it, the `hold` mode of the web build. */
    public fun show(mood: GrokMood, resetEyes: Boolean = true) {
        engine.setMode(GrokMode.HOLD.id, clock)
        engine.setState(mood, clock, resetEyes)
    }

    /** Restarts the onboarding reel, which alternates `idle` with [GrokMood.onboarding]. */
    public fun playOnboarding() {
        engine.setMode(GrokMode.ONBOARDING.id, clock)
    }

    /** Spins the body by [turns] full turns. Ignored while a spin is already running. */
    public fun spin(turns: Float = 1f) {
        engine.spinOnce(turns)
    }

    /** Hops once. */
    public fun bounce() {
        engine.bounceOnce(clock)
    }

    /** Emits one particle burst; ignored when [GrokBotConfig.reduceMotion] is set. */
    public fun burst() {
        engine.burstOnce()
    }

    /**
     * Pushes [config] onto the engine, one changed option at a time.
     *
     * [GrokBotConfig.loginWrap] and [GrokBotConfig.reduceMotion] are read once when the engine is
     * created and cannot be switched afterwards; changing them here has no effect.
     */
    public fun apply(config: GrokBotConfig) {
        val previous = this.config
        if (previous == config) return
        if (previous.shape != config.shape) engine.setShape(config.shape.id, clock)
        if (previous.flatInk != config.flatInk) engine.setInk(config.flatInk)
        if (previous.color != config.color || previous.scheme != config.scheme) {
            engine.setColor(config.color.id, config.scheme.id)
        }
        if (previous.eyeColor != config.eyeColor) engine.setEyeColor(config.eyeColor)
        if (previous.badgeColor != config.badgeColor) engine.badgeColor = config.badgeColor
        if (previous.followPointer != config.followPointer) {
            engine.setFollowPointer(config.followPointer)
        }
        if (previous.emphasis != config.emphasis) engine.setEmphasis(config.emphasis)
        if (previous.paused != config.paused) engine.setPaused(config.paused)
        if (previous.mode != config.mode) engine.setMode(config.mode.id, clock)
        if (config.mode == GrokMode.HOLD && previous.mood != config.mood) {
            engine.setState(config.mood, clock)
        }
        this.config = config
        style.updateFrom(engine)
    }

    /**
     * Advances the engine to [now] and repaints the frame it owns.
     *
     * The clock starts at the first stamp, so the very first frame both starts the engine and enters
     * [currentConfig]'s mood, the two calls the web build makes from its constructor.
     */
    public fun step(now: Float) {
        clock = now
        if (!started) {
            started = true
            engine.start(now)
            engine.setState(config.mood, now, resetEyes = true)
            style.updateFrom(engine)
        }
        engine.tick(now)
        engine.paint(now)
        frameIndex++
        val trace = frameTrace
        if (trace != null) {
            traceBuffer.setLength(0)
            GrokFrameTrace.append(traceBuffer, frameIndex, engine)
            trace(traceBuffer.toString())
        }
    }

    /** Publishes the layout of the stage, in window pixels. */
    public fun setStage(left: Float, top: Float, width: Float, height: Float) {
        engine.setStage(left, top, width, height, clock)
    }

    /** Records a touch at ([x], [y]) of the stage, in stage pixels. */
    public fun pointerAt(x: Float, y: Float) {
        pointer.x = engine.stage.left + x
        pointer.y = engine.stage.top + y
        engine.pointerRaw = pointer
    }

    /** Drops the touch position, so the eyes ease back to the centre. */
    public fun pointerAway() {
        engine.pointerRaw = null
    }

    /** Told when the character enters a new mood. */
    public interface Observer {
        /** [mood] has just been entered, by the host or by the onboarding reel. */
        public fun onGrokMoodChanged(mood: GrokMood)
    }
}

/**
 * Draws the Grok character and keeps it animating.
 *
 * The artwork is a square: it fills the largest centred square the view has, and
 * [GrokBotConfig.sizePx] pins that square to a fixed size instead. Every frame the engine is ticked
 * with the Choreographer stamp and painted with the renderer, which caches the parsed contours, so
 * the view itself never allocates per frame.
 *
 * [config] is the declarative source of truth: a change to it is pushed onto the running engine
 * instead of rebuilding it, which is why the animation survives e.g. a colour switch.
 *
 * The frame loop runs only while the view is attached and visible, so a keyboard that is put away
 * costs nothing.
 */
public class GrokBotView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    state: GrokBotState? = null,
) : View(context, attrs, defStyleAttr) {

    /** Options of the character; a change is pushed onto the running engine. */
    public var config: GrokBotConfig = GrokBotConfig()
        set(value) {
            if (field == value) return
            val resize = field.sizePx != value.sizePx
            field = value
            botState.apply(value)
            // The forced size is a layout property, so a change has to ask the parent again.
            if (resize) requestLayout()
            invalidate()
        }

    /** State driving this view, created with [config] when the host does not supply one. */
    public var botState: GrokBotState = state ?: GrokBotState(config)
        set(value) {
            if (field === value) return
            field.removeObserver(moodObserver)
            field = value
            value.addObserver(moodObserver)
            value.apply(config)
            invalidate()
        }

    /** Called on every mood entry, including the ones the onboarding reel picks. */
    public var onMoodChange: ((GrokMood) -> Unit)? = null

    /** Last stamp of the frame loop, `-1` before the first one. */
    private var origin = -1L

    private var running = false

    /** Set once the first frame is painted, the paused case that still owes one repaint. */
    private var painted = false

    /** Reused by [refreshStage], so a frame does not allocate. */
    private val location = IntArray(2)

    private val moodObserver = object : GrokBotState.Observer {
        override fun onGrokMoodChanged(mood: GrokMood) {
            onMoodChange?.invoke(mood)
        }
    }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            val now = if (origin < 0L) {
                origin = frameTimeNanos
                0f
            } else {
                (frameTimeNanos - origin) / 1_000_000f
            }
            refreshStage()
            botState.step(now)
            // A paused face cannot change, so there is nothing to redraw after the first frame.
            if (!botState.paused || !painted) {
                painted = true
                invalidate()
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    init {
        botState.addObserver(moodObserver)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        start()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) start() else stop()
    }

    private fun start() {
        if (running || !isAttachedToWindow || visibility != VISIBLE) return
        running = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun stop() {
        if (!running) return
        running = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    /** Publishes where the view sits on the screen, the frame the gaze target is measured against. */
    private fun refreshStage() {
        getLocationInWindow(location)
        botState.setStage(location[0].toFloat(), location[1].toFloat(), width.toFloat(), height.toFloat())
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        refreshStage()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val forced = config.sizePx
        if (forced != null) {
            setMeasuredDimension(
                forced + paddingLeft + paddingRight,
                forced + paddingTop + paddingBottom,
            )
            return
        }
        // The artwork is a square, so the shorter side of the box is the one it takes.
        val side = minOf(
            MeasureSpec.getSize(widthMeasureSpec),
            MeasureSpec.getSize(heightMeasureSpec),
        )
        setMeasuredDimension(side, side)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // A finger that leaves the artwork drops the touch position, the `exit` of the
        // pointer in the Compose original; there is no such event for a view, so a move
        // outside the bounds stands in for it.
        val inside = event.x >= 0f && event.y >= 0f && event.x <= width && event.y <= height
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                if (!inside) {
                    botState.pointerAway()
                } else {
                    // The pointer is measured against the stage, so the stage has to be current.
                    refreshStage()
                    botState.pointerAt(event.x, event.y)
                }
            }

            MotionEvent.ACTION_CANCEL -> botState.pointerAway()
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        with(botState.renderer) {
            drawGrokFrame(
                canvas,
                width.toFloat(),
                height.toFloat(),
                botState.engine.frame,
                botState.style,
                botState.engine.poseScale,
            )
        }
    }
}
