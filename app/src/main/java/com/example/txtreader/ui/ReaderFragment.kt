package com.example.txtreader.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.util.TypedValue
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResultListener
import androidx.lifecycle.lifecycleScope
import android.text.StaticLayout
import com.example.txtreader.core.BookFile
import com.example.txtreader.core.Bookmarks
import com.example.txtreader.core.Codec
import com.example.txtreader.core.PageEngine
import com.example.txtreader.core.ReaderPrefs
import com.example.txtreader.core.Rendered
import com.example.txtreader.core.pctText
import com.example.txtreader.data.Book
import com.example.txtreader.databinding.DialogSettingsBinding
import com.example.txtreader.databinding.FragmentReaderBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ReaderFragment : Fragment() {

    companion object {
        fun newInstance(bookId: Long) = ReaderFragment().apply { arguments = bundleOf("bookId" to bookId) }
    }

    private sealed class Cmd {
        object OpenSaved : Cmd()
        class Open(val pos: Long, val hl: String?) : Cmd()
        object Next : Cmd()
        object Prev : Cmd()
        object Reflow : Cmd()
        object Reload : Cmd()
    }

    private var _b: FragmentReaderBinding? = null
    private val b get() = _b!!
    private var bookId = 0L
    private var cmds: Channel<Cmd> = Channel(Channel.UNLIMITED)
    private var sizeReady = CompletableDeferred<Unit>()
    private var engine: PageEngine? = null
    private var file: BookFile? = null
    private var book: Book? = null
    private var curStart = 0L
    private var highlight: String? = null
    private var pageW = 0
    private var pageH = 0
    private var lastVolTurn = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bookId = requireArguments().getLong("bookId")
        parentFragmentManager.setFragmentResultListener("jump", this) { _, bundle ->
            val hl = bundle.getString("hl")
            cmds.trySend(Cmd.Open(bundle.getLong("pos"), if (hl.isNullOrEmpty()) null else hl))
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _b = FragmentReaderBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        cmds = Channel(Channel.UNLIMITED)
        sizeReady = CompletableDeferred()
        b.pageView.keepScreenOn = true
        b.pageView.onSize = { w, h ->
            pageW = w
            pageH = h
            if (!sizeReady.isCompleted) sizeReady.complete(Unit) else cmds.trySend(Cmd.Reflow)
        }
        b.pageView.onZone = { z ->
            when (z) {
                -1 -> cmds.trySend(Cmd.Prev)
                1 -> cmds.trySend(Cmd.Next)
                else -> toggleMenu()
            }
        }
        val act = requireActivity() as MainActivity
        b.btnBack.setOnClickListener { parentFragmentManager.popBackStack() }
        b.btnToc.setOnClickListener { hideMenu(); act.push(TocFragment.newInstance(bookId, curStart)) }
        b.btnMarks.setOnClickListener { hideMenu(); act.push(BookmarksFragment.newInstance(bookId, curStart)) }
        b.btnSearch.setOnClickListener { hideMenu(); act.push(SearchFragment.newInstance(bookId)) }
        b.btnMark.setOnClickListener { hideMenu(); addMark() }
        b.btnSettings.setOnClickListener { hideMenu(); showSettings() }

        cmds.trySend(Cmd.OpenSaved)
        viewLifecycleOwner.lifecycleScope.launch { loop() }
    }

    private suspend fun loop() {
        val db = app.db
        val first = db.bookDao().get(bookId)
        if (first == null) {
            parentFragmentManager.popBackStack()
            return
        }
        book = first
        b.tvBookTitle.text = first.title
        var f = BookFile(first)
        file = f
        var eng = PageEngine(f)
        engine = eng
        try {
            sizeReady.await()
            applyConfig(eng)
            for (cmd in cmds) {
                try {
                    when (cmd) {
                        is Cmd.OpenSaved -> {
                            val cf = f
                            val cb = book ?: first
                            val off = withContext(Dispatchers.IO) { resolveSavedOffset(cf, cb) }
                            show(eng.open(off))
                        }
                        is Cmd.Open -> {
                            highlight = cmd.hl
                            show(eng.open(cmd.pos))
                        }
                        is Cmd.Next -> {
                            val r = eng.next()
                            if (r == null) toast("已是最后一页") else { highlight = null; show(r) }
                        }
                        is Cmd.Prev -> {
                            val r = eng.prev()
                            if (r == null) toast("已是第一页") else { highlight = null; show(r) }
                        }
                        is Cmd.Reflow -> {
                            applyConfig(eng)
                            show(eng.open(curStart))
                        }
                        is Cmd.Reload -> {
                            f.close()
                            val nb = db.bookDao().get(bookId) ?: return
                            book = nb
                            f = BookFile(nb)
                            file = f
                            eng = PageEngine(f)
                            engine = eng
                            applyConfig(eng)
                            show(eng.open(nb.lastOffset))
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    toast("读取失败：${e.message}")
                }
            }
        } finally {
            f.close()
        }
    }

    /** Restores the saved position, verifying the stored text digest; falls back to the chapter start. */
    private suspend fun resolveSavedOffset(f: BookFile, bk: Book): Long {
        val off = bk.lastOffset.coerceIn(f.dataStart, maxOf(f.dataStart, f.size - 1))
        val fixed = f.verifyOrRelocate(off, bk.lastSnippet)
        if (fixed >= 0) return fixed
        return app.db.chapterDao().at(bookId, off)?.pos ?: f.dataStart
    }

    private suspend fun applyConfig(e: PageEngine) {
        val p = app.prefs
        val px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, p.fontSp.toFloat(), resources.displayMetrics)
        e.configure(pageW, pageH, px, p.textColor(), p.lineSpacing)
        val v = _b ?: return
        v.pageView.bgColor = p.bgColor()
        v.pageView.textColor = p.textColor()
    }

    private suspend fun show(r: Rendered?) {
        val v = _b ?: return
        if (r == null) {
            v.pageView.pageLayout = null
            v.pageView.invalidate()
            return
        }
        curStart = r.info.start
        val hl = highlight
        v.pageView.pageLayout = if (!hl.isNullOrEmpty()) highlighted(r, hl) else r.layout
        val bk = book ?: return
        val ch = app.db.chapterDao().at(bookId, r.info.start)
        val v2 = _b ?: return
        v2.pageView.statusLeft = ch?.title ?: bk.title
        v2.pageView.statusRight = pctText(r.info.start, bk.size)
        v2.pageView.invalidate()
        app.db.bookDao().savePosition(
            bookId, r.info.start, Codec.snippetOf(r.text, 20), System.currentTimeMillis()
        )
        viewLifecycleOwner.lifecycleScope.launch {
            try { engine?.preload() } catch (_: Exception) { }
        }
    }

    private fun highlighted(r: Rendered, q: String): StaticLayout {
        val sp = SpannableString(r.text)
        var i = r.text.indexOf(q, 0, true)
        while (i >= 0) {
            sp.setSpan(BackgroundColorSpan(0xFFFFE082.toInt()), i, i + q.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            i = r.text.indexOf(q, i + q.length, true)
        }
        return engine!!.buildLayout(sp)
    }

    // ---------- menu / actions ----------

    private fun toggleMenu() {
        val show = !b.topMenu.isVisible
        b.topMenu.isVisible = show
        b.bottomMenu.isVisible = show
    }

    private fun hideMenu() {
        b.topMenu.isVisible = false
        b.bottomMenu.isVisible = false
    }

    private fun addMark() {
        val bk = book ?: return
        val pos = curStart
        viewLifecycleOwner.lifecycleScope.launch {
            val ok = Bookmarks.add(app.db, bk, pos)
            toast(if (ok) "已添加书签" else "该位置已有书签")
        }
    }

    private fun showSettings() {
        val p = app.prefs
        val d = DialogSettingsBinding.inflate(layoutInflater)
        val density = resources.displayMetrics.density

        d.tvFont.text = "字号  ${p.fontSp}"
        d.sbFont.progress = p.fontSp - 12
        d.sbFont.onChange({ v -> p.fontSp = v + 12; d.tvFont.text = "字号  ${v + 12}" }, { cmds.trySend(Cmd.Reflow) })

        d.tvLine.text = "行距  ${p.lineSpacingPct}%"
        d.sbLine.progress = p.lineSpacingPct - 100
        d.sbLine.onChange({ v -> p.lineSpacingPct = v + 100; d.tvLine.text = "行距  ${v + 100}%" }, { cmds.trySend(Cmd.Reflow) })

        // background swatches
        val swatches = ArrayList<View>()
        fun paintSwatches() {
            swatches.forEachIndexed { i, v ->
                val g = GradientDrawable()
                g.shape = GradientDrawable.OVAL
                g.setColor(ReaderPrefs.BG_COLORS[i])
                val selected = i == p.bgIndex && !p.night
                g.setStroke((if (selected) 3 else 1) * density.toInt().coerceAtLeast(1),
                    if (selected) 0xFF1976D2.toInt() else 0xFFBBBBBB.toInt())
                v.background = g
            }
        }
        val sz = (36 * density).toInt()
        val mg = (6 * density).toInt()
        ReaderPrefs.BG_COLORS.forEachIndexed { i, _ ->
            val v = View(requireContext())
            v.layoutParams = LinearLayout.LayoutParams(sz, sz).apply { setMargins(mg, mg, mg, mg) }
            v.setOnClickListener {
                p.bgIndex = i
                p.night = false
                d.swNight.isChecked = false
                paintSwatches()
                cmds.trySend(Cmd.Reflow)
            }
            swatches.add(v)
            d.bgRow.addView(v)
        }
        paintSwatches()

        d.swNight.isChecked = p.night
        d.swNight.setOnCheckedChangeListener { _, c ->
            p.night = c
            paintSwatches()
            cmds.trySend(Cmd.Reflow)
        }

        d.cbBrightAuto.isChecked = p.brightness < 0
        d.sbBright.progress = if (p.brightness < 0) 50 else p.brightness
        d.sbBright.isEnabled = p.brightness >= 0
        d.sbBright.onChange({ v -> p.brightness = v; applyBrightness() }, {})
        d.cbBrightAuto.setOnCheckedChangeListener { _, c ->
            p.brightness = if (c) -1 else d.sbBright.progress
            d.sbBright.isEnabled = !c
            applyBrightness()
        }

        d.swVolume.isChecked = p.volumeKeys
        d.swVolume.setOnCheckedChangeListener { _, c -> p.volumeKeys = c; updateVolumeHandler() }
        d.swReverse.isChecked = p.volumeReverse
        d.swReverse.setOnCheckedChangeListener { _, c -> p.volumeReverse = c }

        d.btnEncoding.text = "编码：${book?.encoding ?: "-"}（点击切换并重建索引）"
        val dlg = AlertDialog.Builder(requireContext())
            .setTitle("阅读设置")
            .setView(d.root)
            .setPositiveButton("完成", null)
            .create()
        d.btnEncoding.setOnClickListener { dlg.dismiss(); chooseEncoding() }
        dlg.show()
    }

    private fun chooseEncoding() {
        val bk = book ?: return
        val cur = Codec.ENCODINGS.indexOf(bk.encoding)
        var sel = cur
        AlertDialog.Builder(requireContext())
            .setTitle("选择编码（将重建索引并回到开头）")
            .setSingleChoiceItems(Codec.ENCODINGS.toTypedArray(), cur) { _, w -> sel = w }
            .setPositiveButton("确定") { _, _ ->
                if (sel >= 0 && Codec.ENCODINGS[sel] != bk.encoding) {
                    val enc = Codec.ENCODINGS[sel]
                    viewLifecycleOwner.lifecycleScope.launch {
                        app.index.changeEncoding(bookId, enc)
                        cmds.trySend(Cmd.Reload)
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------- system integration ----------

    private fun applyBrightness() {
        val w = requireActivity().window
        val lp = w.attributes
        val br = app.prefs.brightness
        lp.screenBrightness =
            if (br < 0) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else (br / 100f).coerceIn(0.01f, 1f)
        w.attributes = lp
    }

    private fun updateVolumeHandler() {
        (activity as? MainActivity)?.volumeHandler = if (app.prefs.volumeKeys) this::onVolume else null
    }

    /** Volume key page turn; held keys are throttled to one turn per 150ms. */
    private fun onVolume(code: Int, repeat: Int) {
        val now = SystemClock.uptimeMillis()
        if (repeat > 0 && now - lastVolTurn < 150) return
        lastVolTurn = now
        var forward = code == KeyEvent.KEYCODE_VOLUME_DOWN
        if (app.prefs.volumeReverse) forward = !forward
        cmds.trySend(if (forward) Cmd.Next else Cmd.Prev)
    }

    override fun onResume() {
        super.onResume()
        val c = WindowInsetsControllerCompat(requireActivity().window, b.root)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsetsCompat.Type.systemBars())
        applyBrightness()
        updateVolumeHandler()
    }

    override fun onPause() {
        super.onPause()
        // restore normal volume-key behaviour, brightness and system bars when leaving the reader
        (activity as? MainActivity)?.volumeHandler = null
        val w = requireActivity().window
        val lp = w.attributes
        lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        w.attributes = lp
        _b?.let { WindowInsetsControllerCompat(w, it.root).show(WindowInsetsCompat.Type.systemBars()) }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cmds.close()
        engine = null
        _b = null
    }
}
