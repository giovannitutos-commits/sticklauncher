package br.com.minitv.launcher

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextClock
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class Item(
    val label: String,
    val icon: Drawable,
    val banner: Drawable?,
    val intent: Intent,
    val pkg: String?
) {
    val tint: Int by lazy { avgColor(banner ?: icon) }
}

private const val DEFAULT_TINT = 0xFF1E1E2E.toInt()
private const val BOTTOM = 0xFF05050A.toInt()

/** Cor média do banner/ícone, escurecida, usada como fundo ambiente. */
private fun avgColor(d: Drawable): Int {
    val src = d.constantState?.newDrawable()?.mutate() ?: d
    val bmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
    src.setBounds(0, 0, 16, 16)
    src.draw(Canvas(bmp))
    var r = 0L; var g = 0L; var b = 0L; var n = 0
    for (x in 0 until 16) for (y in 0 until 16) {
        val p = bmp.getPixel(x, y)
        if (Color.alpha(p) < 128) continue
        r += Color.red(p); g += Color.green(p); b += Color.blue(p); n++
    }
    if (n == 0) return DEFAULT_TINT
    val hsv = FloatArray(3)
    Color.RGBToHSV((r / n).toInt(), (g / n).toInt(), (b / n).toInt(), hsv)
    hsv[1] = minOf(1f, hsv[1] * 1.1f)
    hsv[2] = hsv[2].coerceIn(0.28f, 0.6f)
    return Color.HSVToColor(hsv)
}

class MainActivity : Activity() {

    companion object {
        private const val TILE_W = 144 // dp, proporção 16:9 como os banners do Android TV
        private const val TILE_H = 81
        private const val GAP = 14
    }

    private lateinit var list: RecyclerView
    private lateinit var title: TextView
    private val bgGrad = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(DEFAULT_TINT, BOTTOM))
    private var curColor = DEFAULT_TINT
    private var anim: ValueAnimator? = null
    private val tiles = TileAdapter()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this).apply {
            background = bgGrad
            clipChildren = false
        }

        // Nome grande do app focado (canto superior esquerdo)
        title = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 44f; maxLines = 1
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setShadowLayer(10f, 0f, 2f, 0x66000000)
        }
        root.addView(title, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP or Gravity.START)
            .apply { setMargins(dp(56), dp(110), dp(56), 0) })

        // Relógio em "pílula" translúcida (canto superior direito)
        root.addView(TextClock(this).apply {
            format24Hour = "HH:mm"; format12Hour = "h:mm"
            setTextColor(Color.WHITE); textSize = 18f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(18), dp(8), dp(18), dp(8))
            background = GradientDrawable().apply {
                setColor(0x40FFFFFF); cornerRadius = dp(22).toFloat()
            }
        }, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.END)
            .apply { setMargins(0, dp(32), dp(48), 0) })

        // Dock translúcido com a fileira de apps (parte inferior)
        list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            adapter = tiles
            clipToPadding = false
            clipChildren = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(dp(28), dp(24), dp(28), dp(24))
            background = GradientDrawable().apply {
                setColor(0x33FFFFFF); setStroke(dp(1), 0x22FFFFFF)
                cornerRadius = dp(36).toFloat()
            }
        }
        root.addView(list, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM)
            .apply { setMargins(dp(40), 0, dp(40), dp(70)) })

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        val fresh = loadItems()
        val same = fresh.map { it.pkg to it.label } == tiles.items.map { it.pkg to it.label }
        if (!same) {
            tiles.items = fresh
            tiles.notifyDataSetChanged()
            focusFirst()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        list.scrollToPosition(0)
        focusFirst()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { /* launcher não fecha com Voltar */ }

    private fun focusFirst() {
        list.postDelayed({ list.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }, 120)
    }

    private fun tintTo(c: Int) {
        anim?.cancel()
        anim = ValueAnimator.ofObject(ArgbEvaluator(), curColor, c).apply {
            duration = 350
            addUpdateListener {
                curColor = it.animatedValue as Int
                bgGrad.setColors(intArrayOf(curColor, BOTTOM))
            }
            start()
        }
    }

    private fun loadItems(): List<Item> {
        val pm = packageManager
        val seen = HashSet<String>()
        val out = ArrayList<Item>()
        for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val query = Intent(Intent.ACTION_MAIN).addCategory(cat)
            for (ri in pm.queryIntentActivities(query, 0)) {
                val ai = ri.activityInfo
                if (ai.packageName == packageName || !seen.add(ai.packageName)) continue
                val launch = Intent(Intent.ACTION_MAIN).addCategory(cat)
                    .setClassName(ai.packageName, ai.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                val banner = ai.loadBanner(pm) ?: ai.applicationInfo.loadBanner(pm)
                out.add(Item(ri.loadLabel(pm).toString(), ri.loadIcon(pm), banner, launch, ai.packageName))
            }
        }
        out.sortBy { it.label.lowercase() }
        out.add(
            Item(
                "Configurações",
                getDrawable(android.R.drawable.ic_menu_preferences)!!.mutate(),
                null,
                Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                null
            )
        )
        return out
    }

    private class VH(val img: ImageView) : RecyclerView.ViewHolder(img)

    private inner class TileAdapter : RecyclerView.Adapter<VH>() {
        var items: List<Item> = emptyList()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val img = ImageView(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(dp(TILE_W), dp(TILE_H))
                    .apply { setMargins(dp(GAP / 2), 0, dp(GAP / 2), 0) }
                background = GradientDrawable().apply {
                    cornerRadius = dp(16).toFloat(); setColor(0xFF2C2C34.toInt())
                }
                clipToOutline = true
                elevation = dp(2).toFloat()
                isFocusable = true
            }
            return VH(img)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val item = items[position]
            val img = h.img
            if (item.banner != null) {
                img.setPadding(0, 0, 0, 0)
                img.scaleType = ImageView.ScaleType.CENTER_CROP
                img.setImageDrawable(item.banner)
            } else {
                img.setPadding(dp(14), dp(14), dp(14), dp(14))
                img.scaleType = ImageView.ScaleType.FIT_CENTER
                img.setImageDrawable(item.icon)
            }
            img.setOnClickListener { startActivity(item.intent) }
            img.setOnLongClickListener {
                item.pkg?.let {
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$it"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
                true
            }
            img.setOnFocusChangeListener { v, focused ->
                val s = if (focused) 1.15f else 1f
                v.animate().scaleX(s).scaleY(s)
                    .translationZ(if (focused) dp(14).toFloat() else 0f)
                    .setDuration(160).start()
                if (focused) {
                    title.text = item.label
                    tintTo(item.tint)
                }
            }
        }
    }
}
