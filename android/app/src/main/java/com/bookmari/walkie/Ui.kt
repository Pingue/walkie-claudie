package com.bookmari.walkie

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.style.ReplacementSpan
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** The pager/calculator look (docs/design/tokens.md): a dark body, green LCDs, rubber keys. */
object Ui {
    const val BODY = 0xFF262626.toInt()
    const val KEY = 0xFF3A3A3A.toInt()
    const val KEY_SHADOW = 0xFF111111.toInt()
    const val KEY_TEXT = 0xFFD6D6D6.toInt()
    const val LCD = 0xFF9CA88A.toInt()
    const val INK = 0xFF1F2818.toInt()
    const val GHOST = 0x1F1F2818 // ink at 12%
    const val LED_OK = 0xFF7BD36B.toInt()
    const val LED_REC = 0xFFC0392B.toInt()
    const val LED_DARK = 0xFF1C1C1C.toInt() // an unlit key LED
    const val L = 24f
    const val M = 15f
    const val S = 12f

    private var font: Typeface? = null
    fun font(ctx: Context) = font ?: Typeface.createFromAsset(ctx.assets, "fonts/NeoDunggeunmoPro-Regular.ttf").also { font = it }
}

fun Context.dp(v: Number) = v.toFloat() * resources.displayMetrics.density
fun Context.dpi(v: Number) = dp(v).toInt()
fun Int.alpha(a: Float) = (this and 0xFFFFFF) or ((a * 255).toInt() shl 24)

fun Context.lcdText(size: Float = Ui.M, color: Int = Ui.INK) = TextView(this).apply {
    typeface = Ui.font(this@lcdText)
    textSize = size
    setTextColor(color)
}

/** LCD panel: ink-on-green with a 3dp pixel grid and an inner shadow; [inverted] is green-on-ink without the grid. */
class LcdDrawable(ctx: Context, private val inverted: Boolean = false) : Drawable() {
    private val radius = ctx.dp(6)
    private val shadowY = ctx.dp(2)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (inverted) Ui.INK else Ui.LCD }
    private val grid = Paint().apply {
        val n = ctx.dpi(3).coerceAtLeast(2)
        val tile = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        for (i in 0 until n) { tile.setPixel(i, 0, Ui.GHOST); tile.setPixel(0, i, Ui.GHOST) }
        shader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x73000000 // black 45%
        maskFilter = BlurMaskFilter(ctx.dp(5), BlurMaskFilter.Blur.NORMAL)
    }
    private val inner = Path()
    private val frame = Path()

    override fun onBoundsChange(b: android.graphics.Rect) {
        inner.reset()
        inner.addRoundRect(RectF(b), radius, radius, Path.Direction.CW)
        frame.reset()
        frame.addRect(RectF(b).apply { inset(-radius * 4, -radius * 4) }, Path.Direction.CW)
        frame.op(inner, Path.Op.DIFFERENCE)
    }

    override fun draw(c: Canvas) {
        c.save()
        c.clipPath(inner)
        c.drawPath(inner, fill)
        if (!inverted) c.drawRect(bounds, grid)
        c.translate(0f, shadowY)
        c.drawPath(frame, shadow)
        c.restore()
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** Rubber key: a 3dp shadow under the face; pressed, the face sinks 2dp onto it. */
class KeyDrawable(ctx: Context) : Drawable() {
    private val r = ctx.dp(9)
    private val sh = ctx.dp(3)
    private val sink = ctx.dp(2)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var pressed = false
    var face = Ui.KEY
        set(v) { field = v; invalidateSelf() }
    /** A small LED on the face's top-right (the power key: lit while the walkie is on); 0 = none. */
    var led = 0
        set(v) { field = v; invalidateSelf() }
    private val ledR = ctx.dp(3)
    private val ledInset = ctx.dp(9)

    override fun isStateful() = true
    override fun onStateChange(state: IntArray): Boolean {
        val p = android.R.attr.state_pressed in state
        if (p == pressed) return false
        pressed = p
        invalidateSelf()
        return true
    }

    override fun draw(c: Canvas) {
        val b = RectF(bounds)
        if (!pressed) {
            paint.color = Ui.KEY_SHADOW
            c.drawRoundRect(b.left, b.top + sh, b.right, b.bottom, r, r, paint)
        }
        paint.color = face
        val dy = if (pressed) sink else 0f
        c.drawRoundRect(b.left, b.top + dy, b.right, b.bottom - sh + dy, r, r, paint)
        if (led != 0) {
            paint.color = led
            c.drawCircle(b.right - ledInset, b.top + ledInset + dy, ledR, paint)
        }
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/**
 * Dot-art icon from rows of '#'/'.' in [color]: one cell = [cell]dp with a 1px gap between dots (an LCD's dot
 * seams; 10/12), centred on the key face and sinking with it when pressed. Strokes are 2 dots (the sliders' lines excepted).
 */
class PixelIcon(ctx: Context, val rows: List<String>, color: Int, cell: Float) : Drawable() {
    private val px = ctx.dpi(cell) // whole pixels, so every gap is the same
    private val sh = ctx.dpi(3)
    private val sink = ctx.dpi(2)
    private val paint = Paint().apply { this.color = color }
    private var pressed = false

    override fun isStateful() = true
    override fun onStateChange(state: IntArray): Boolean {
        val p = android.R.attr.state_pressed in state
        if (p == pressed) return false
        pressed = p
        invalidateSelf()
        return true
    }

    override fun draw(c: Canvas) {
        val x0 = bounds.centerX() - rows[0].length * px / 2
        val y0 = bounds.top + (bounds.height() - sh - rows.size * px) / 2 + if (pressed) sink else 0
        rows.forEachIndexed { y, row -> row.forEachIndexed { x, ch ->
            if (ch == '#') c.drawRect((x0 + x * px).toFloat(), (y0 + y * px).toFloat(), (x0 + (x + 1) * px - 1).toFloat(), (y0 + (y + 1) * px - 1).toFloat(), paint)
        } }
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        val MIC = listOf( // a wide head with short arms read as a trophy (10/12): narrow head, tall arms
            ".....##.....",
            "....####....",
            "....####....",
            "....####....",
            "##..####..##",
            "##..####..##",
            "##..####..##",
            "##...##...##",
            ".##......##.",
            "..########..",
            ".....##.....",
            ".....##.....",
            "..########..",
            "..########..",
        )
        val SLIDERS = listOf( // settings: a gear read as a bug (10/12). Thin lines so the knobs stand out.
            "..###.......",
            "############",
            "..###.......",
            "............",
            "............",
            ".......###..",
            "############",
            ".......###..",
            "............",
            "............",
            "....###.....",
            "############",
            "....###.....",
        )
        val POWER = listOf( // a gently rounded bottom: narrowing row by row read as a point, flat as a bucket (10/12)
            ".....##.....",
            "..#..##..#..",
            ".##..##..##.",
            "##...##...##",
            "##...##...##",
            "##...##...##",
            "##........##",
            "##........##",
            "##........##",
            ".##......##.",
            "..########..",
            "...######...",
        )
        val CANCEL = listOf(
            "##........##",
            "###......###",
            ".###....###.",
            "..###..###..",
            "...######...",
            "....####....",
            "....####....",
            "...######...",
            "..###..###..",
            ".###....###.",
            "###......###",
            "##........##",
        )
    }
}

/** A key that shows a dot icon instead of its label; the label stays as the screen-reader name. App keys use 2.5dp cells. */
fun TextView.icon(rows: List<String>, label: Int, color: Int = Ui.KEY_TEXT, cell: Float = 2.5f) {
    text = ""
    contentDescription = context.getString(label)
    if ((foreground as? PixelIcon)?.rows !== rows) foreground = PixelIcon(context, rows, color, cell) // render() calls this on every change
}

fun Context.key(label: Int, onClick: () -> Unit) = TextView(this).apply {
    setText(label)
    typeface = Ui.font(this@key)
    textSize = 13f
    setTextColor(Ui.KEY_TEXT)
    gravity = Gravity.CENTER
    background = KeyDrawable(this@key)
    setPadding(0, 0, 0, dpi(3)) // centre the label on the face, not face + shadow
    isClickable = true
    setOnClickListener { onClick() }
}

/** Wrap Korean at spaces, not mid-word (Android 13+; older phones keep per-character wrapping). */
fun TextView.wrapByWord() {
    if (android.os.Build.VERSION.SDK_INT >= 33) lineBreakWordStyle = android.graphics.text.LineBreakConfig.LINE_BREAK_WORD_STYLE_PHRASE
}

/** Antenna: 4 bars (3dp wide, 4/7/10/13dp tall); unlit bars are ghost. */
class Antenna(ctx: Context) : View(ctx) {
    var bars = 0
        set(v) { field = v; invalidate() }
    private val paint = Paint()
    private val w = ctx.dp(3)
    private val gap = ctx.dp(2)

    override fun onMeasure(wSpec: Int, hSpec: Int) = setMeasuredDimension((w * 4 + gap * 3).toInt(), context.dpi(13))

    override fun onDraw(c: Canvas) {
        for (i in 0 until 4) {
            paint.color = if (i < bars) Ui.INK else Ui.GHOST
            val x = i * (w + gap)
            c.drawRect(x, height - context.dp(4 + 3 * i), x + w, height.toFloat(), paint)
        }
    }
}

/** 10-cell meter, 7dp tall, 2dp gaps. */
class Cells(ctx: Context) : View(ctx) {
    var lit = 0
        set(v) { field = v; invalidate() }
    private val paint = Paint()
    private val gap = ctx.dp(2)

    override fun onMeasure(wSpec: Int, hSpec: Int) = setMeasuredDimension(MeasureSpec.getSize(wSpec), context.dpi(7))

    override fun onDraw(c: Canvas) {
        val cw = (width - gap * 9) / 10
        for (i in 0 until 10) {
            paint.color = if (i < lit) Ui.INK else Ui.GHOST
            val x = i * (cw + gap)
            c.drawRect(x, 0f, x + cw, height.toFloat(), paint)
        }
    }
}

/** 8×14dp block cursor sitting 2dp below the baseline; [on] = false leaves its space blank. */
class CursorSpan(ctx: Context) : ReplacementSpan() {
    var on = true
    private val w = ctx.dp(8)
    private val h = ctx.dp(14)
    private val gap = ctx.dp(2)
    private val drop = ctx.dp(2)

    override fun getSize(p: Paint, t: CharSequence?, s: Int, e: Int, fm: Paint.FontMetricsInt?) = (gap + w).toInt()

    override fun draw(c: Canvas, t: CharSequence?, s: Int, e: Int, x: Float, top: Int, y: Int, bottom: Int, p: Paint) {
        if (on) c.drawRect(x + gap, y + drop - h, x + gap + w, y + drop, p)
    }
}

/** LCD menu (settings, voice, button test): a small section title. */
fun LinearLayout.menuTitle(label: String) = addView(context.lcdText(Ui.S).apply { text = label; alpha = 0.7f },
    LinearLayout.LayoutParams(-2, -2).apply { topMargin = if (childCount == 0) 0 else context.dpi(12); bottomMargin = context.dpi(2) })

/** LCD menu row: label (+ dim [sub]) on the left, a word or chip on the right, dotted rule below. */
fun LinearLayout.menuRow(label: String, sub: String?, right: View?, onClick: (() -> Unit)?) {
    val c = context
    val left = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        addView(c.lcdText().apply { text = label; wrapByWord() })
        if (sub != null) addView(c.lcdText(11f).apply { text = sub; alpha = 0.65f; wrapByWord() }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = c.dpi(3) })
    }
    addView(LinearLayout(c).apply {
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = c.dpi(44) // comfortable tap target
        setPadding(0, c.dpi(7), 0, c.dpi(7))
        background = DottedLine(c)
        if (onClick != null) setOnClickListener { onClick() }
        addView(left, LinearLayout.LayoutParams(0, -2, 1f))
        if (right != null) addView(right, LinearLayout.LayoutParams(-2, -2).apply { marginStart = c.dpi(8) })
    })
}

/** A tappable word at the right of a menu row. */
fun Context.lcdAction(label: String, onClick: (() -> Unit)? = null) = lcdText().apply {
    text = label
    if (onClick != null) {
        setPadding(dpi(8), dpi(8), 0, dpi(8)) // bigger tap target than the word
        setOnClickListener { onClick() }
    }
}

/** Dotted rule under a menu row. */
class DottedLine(ctx: Context) : Drawable() {
    private val paint = Paint().apply {
        color = Ui.INK.alpha(0.35f)
        strokeWidth = ctx.dp(1)
        pathEffect = DashPathEffect(floatArrayOf(ctx.dp(1), ctx.dp(2)), 0f)
    }

    override fun draw(c: Canvas) {
        val y = bounds.bottom - paint.strokeWidth / 2
        c.drawLine(bounds.left.toFloat(), y, bounds.right.toFloat(), y, paint)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/**
 * The machine body filling the screen: [column] for LCDs (10dp apart), a row of rubber [keys],
 * the engraved name, and a small LED top-right. Unfolded (wide) it keeps the mock's 330:720
 * proportions, centred. [tick] fires every 0.5 s for blinking.
 */
class Body(a: Activity, tick: (on: Boolean) -> Unit) {
    val column = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
    val keys = LinearLayout(a)
    private val ledShape = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL }
    private val led = View(a).apply { background = ledShape }
    private var ledColor = Ui.KEY
    private var ledBlink = false
    private var on = true

    init {
        val gutter = a.dpi(16)
        val inner = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(gutter, gutter, gutter, a.dpi(6))
            addView(column, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(keys, LinearLayout.LayoutParams(-1, a.dpi(47)).apply { topMargin = a.dpi(10) })
            addView(TextView(a).apply {
                text = "WALKIE-CLAUDIE"
                typeface = Ui.font(a)
                textSize = 11f
                setTextColor(0xFF777777.toInt())
                letterSpacing = 0.18f
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dpi(8) })
        }
        val cfg = a.resources.configuration
        val width = if (cfg.screenWidthDp >= 600) a.dpi(cfg.screenHeightDp * 330 / 720) else -1
        val machine = FrameLayout(a).apply {
            addView(inner, FrameLayout.LayoutParams(-1, -1))
            addView(led, FrameLayout.LayoutParams(a.dpi(7), a.dpi(7), Gravity.TOP or Gravity.END).apply {
                topMargin = a.dpi(5); marginEnd = a.dpi(28)
            })
        }
        val root = FrameLayout(a).apply {
            setBackgroundColor(Ui.BODY)
            addView(machine, FrameLayout.LayoutParams(width, -1, Gravity.CENTER_HORIZONTAL))
            setOnApplyWindowInsetsListener { v, insets ->
                val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(b.left, b.top, b.right, b.bottom)
                insets
            }
        }
        a.setContentView(root)
        root.post(object : Runnable {
            override fun run() {
                on = !on
                ledShape.setColor(if (ledBlink && !on) Ui.KEY else ledColor)
                tick(on)
                root.postDelayed(this, 500)
            }
        })
        led(Ui.KEY)
    }

    fun led(color: Int, blink: Boolean = false) {
        ledColor = color
        ledBlink = blink
        ledShape.setColor(color)
    }

    /** One key per call, equal widths, 8dp apart. */
    fun key(v: View) = keys.addView(v, LinearLayout.LayoutParams(0, -1, 1f).apply {
        if (keys.childCount > 0) marginStart = v.context.dpi(8)
    })

    fun lcd(v: View, inverted: Boolean = false, weight: Float = 0f): View {
        v.background = LcdDrawable(v.context, inverted)
        v.setPadding(v.context.dpi(12), v.context.dpi(10), v.context.dpi(12), v.context.dpi(10))
        column.addView(v, LinearLayout.LayoutParams(-1, if (weight > 0) 0 else -2, weight).apply {
            if (column.childCount > 0) topMargin = v.context.dpi(10)
        })
        return v
    }
}
