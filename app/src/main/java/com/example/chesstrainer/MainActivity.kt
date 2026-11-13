package com.example.chesstrainer

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView

class MainActivity : Activity() {

    private class Snapshot(val pos: Position, val last: Move?)

    private lateinit var board: BoardView
    private lateinit var status: TextView
    private lateinit var levelSpinner: Spinner
    private lateinit var colorSpinner: Spinner
    private lateinit var engine: StockfishEngine

    private var engineReady = false
    private var engineError: String? = null
    private var pendingEngineMove = false
    private var thinking = false
    private var gen = 0

    private var position: Position = Position.fromFen(Position.START_FEN)
    private var lastMove: Move? = null
    private var playerWhite = true
    private var resultText: String? = null
    private val history = ArrayList<Snapshot>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val density = resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.parseColor("#262421"))
        root.setPadding(dp(8), dp(28), dp(8), dp(8))

        status = TextView(this)
        status.textSize = 18f
        status.setTextColor(Color.WHITE)
        status.gravity = Gravity.CENTER
        status.setPadding(0, dp(4), 0, dp(10))
        root.addView(
            status,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        board = BoardView(this)
        board.onMoveAttempt = { from, to -> onPlayerMove(from, to) }
        root.addView(
            board,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        levelSpinner = Spinner(this)
        val levelNames = StockfishEngine.Level.values().map { it.title }
        levelSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, levelNames).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        levelSpinner.setSelection(1)

        colorSpinner = Spinner(this)
        val colorNames = listOf("Белыми", "Чёрными")
        colorSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, colorNames).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        fun row(label: String, spinner: Spinner): LinearLayout {
            val r = LinearLayout(this)
            r.orientation = LinearLayout.HORIZONTAL
            r.gravity = Gravity.CENTER_VERTICAL
            val t = TextView(this)
            t.text = label
            t.textSize = 16f
            t.setTextColor(Color.WHITE)
            r.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            r.addView(spinner, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            return r
        }

        val rowParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        rowParams.topMargin = dp(8)
        root.addView(row("Сложность:", levelSpinner), rowParams)
        root.addView(row("Играть за (со след. партии):", colorSpinner), rowParams)

        val buttons = LinearLayout(this)
        buttons.orientation = LinearLayout.HORIZONTAL

        val newGameBtn = Button(this)
        newGameBtn.text = "Новая игра"
        newGameBtn.setOnClickListener { newGame() }

        val undoBtn = Button(this)
        undoBtn.text = "Отмена хода"
        undoBtn.setOnClickListener { undo() }

        buttons.addView(newGameBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(undoBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(buttons, rowParams)

        setContentView(root)

        engine = StockfishEngine(applicationContext)
        engine.start { ok, msg ->
            engineReady = ok
            if (!ok) engineError = msg
            if (ok && pendingEngineMove) engineMove() else refresh()
        }

        newGame()
    }

    override fun onDestroy() {
        super.onDestroy()
        engine.shutdown()
    }

    private fun newGame() {
        gen++
        thinking = false
        pendingEngineMove = false
        engineError = if (engineReady) null else engineError
        history.clear()
        position = Position.fromFen(Position.START_FEN)
        lastMove = null
        resultText = null
        playerWhite = colorSpinner.selectedItemPosition == 0
        refresh()
        if (!playerWhite) engineMove()
    }

    private fun undo() {
        if (history.isEmpty()) return
        gen++
        thinking = false
        pendingEngineMove = false
        resultText = null
        do {
            val snap = history.removeAt(history.size - 1)
            position = snap.pos
            lastMove = snap.last
        } while (history.isNotEmpty() && position.whiteToMove != playerWhite)
        refresh()
        if (position.whiteToMove != playerWhite) engineMove()
    }

    private fun onPlayerMove(from: Int, to: Int) {
        val moves = position.legalMoves().filter { it.from == from && it.to == to }
        if (moves.isEmpty()) return
        if (moves.size == 1) {
            applyMove(moves[0])
            return
        }
        val letters = charArrayOf('q', 'r', 'b', 'n')
        AlertDialog.Builder(this)
            .setTitle("Превращение пешки")
            .setCancelable(false)
            .setItems(arrayOf("Ферзь", "Ладья", "Слон", "Конь")) { _, which ->
                val m = moves.firstOrNull { it.promo == letters[which] } ?: moves[0]
                applyMove(m)
            }
            .show()
    }

    private fun applyMove(m: Move) {
        history.add(Snapshot(position, lastMove))
        position = position.make(m)
        lastMove = m
        resultText = computeResult()
        refresh()
        if (resultText == null && position.whiteToMove != playerWhite) engineMove()
    }

    private fun engineMove() {
        if (!engineReady) {
            pendingEngineMove = true
            refresh()
            return
        }
        pendingEngineMove = false
        thinking = true
        refresh()
        val myGen = gen
        val fen = position.toFen()
        val level = StockfishEngine.Level.values()[levelSpinner.selectedItemPosition]
        engine.requestMove(fen, level) { uci ->
            if (myGen != gen) return@requestMove
            thinking = false
            val m = position.legalMoves().firstOrNull { it.uci() == uci }
            if (m == null) {
                engineError = "движок не вернул ход"
                refresh()
            } else {
                applyMove(m)
            }
        }
    }

    private fun computeResult(): String? = when (position.status()) {
        GameStatus.CHECKMATE ->
            if (position.whiteToMove == playerWhite) "Мат. Вы проиграли." else "Мат! Вы победили!"
        GameStatus.STALEMATE -> "Пат. Ничья."
        GameStatus.DRAW_50 -> "Ничья по правилу 50 ходов."
        GameStatus.DRAW_MATERIAL -> "Ничья: недостаточно материала."
        GameStatus.PLAYING -> null
    }

    private fun refresh() {
        board.position = position
        board.legalMoves = position.legalMoves()
        board.lastMove = lastMove
        board.playerWhite = playerWhite
        board.flipped = !playerWhite
        board.interactive = resultText == null && !thinking && position.whiteToMove == playerWhite
        board.resetSelection()
        status.text = when {
            resultText != null -> resultText
            engineError != null -> "Ошибка движка: $engineError"
            thinking -> "Stockfish думает…"
            position.whiteToMove != playerWhite -> "Запуск движка…"
            position.inCheck() -> "Ваш ход — шах!"
            else -> "Ваш ход"
        }
        board.invalidate()
    }
}
