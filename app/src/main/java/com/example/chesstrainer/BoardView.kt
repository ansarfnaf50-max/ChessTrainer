package com.example.chesstrainer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View

class BoardView(context: Context) : View(context) {

    var position: Position = Position.fromFen(Position.START_FEN)
    var legalMoves: List<Move> = emptyList()
    var lastMove: Move? = null
    var flipped: Boolean = false
    var interactive: Boolean = false
    var playerWhite: Boolean = true
    var onMoveAttempt: ((Int, Int) -> Unit)? = null

    private val lightColor = Color.parseColor("#F0D9B5")
    private val darkColor = Color.parseColor("#B58863")
    private val lastMoveLight = Color.parseColor("#F7EC74")
    private val lastMoveDark = Color.parseColor("#DAC34B")

    private val squarePaint = Paint()
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        style = Paint.Style.STROKE
        color = Color.BLACK
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var selected = -1
    private var dragging = false
    private var dragX = 0f
    private var dragY = 0f
    private var downSquare = -1
    private var moved = false

    companion object {
        private val GLYPHS = mapOf(
            'k' to "\u265A", 'q' to "\u265B", 'r' to "\u265C",
            'b' to "\u265D", 'n' to "\u265E", 'p' to "\u265F"
        )
    }

    fun resetSelection() {
        selected = -1
        dragging = false
        moved = false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, w)
    }

    private fun toIndex(row: Int, col: Int): Int =
        if (flipped) (7 - row) * 8 + (7 - col) else row * 8 + col

    private fun toRow(index: Int): Int = if (flipped) 7 - index / 8 else index / 8
    private fun toCol(index: Int): Int = if (flipped) 7 - index % 8 else index % 8

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = width / 8f
        val kingSq = if (position.inCheck()) {
            position.board.indexOf(if (position.whiteToMove) 'K' else 'k')
        } else -1

        for (r in 0..7) {
            for (c in 0..7) {
                val idx = toIndex(r, c)
                val lightSquare = (r + c) % 2 == 0
                var color = if (lightSquare) lightColor else darkColor
                val lm = lastMove
                if (lm != null && (idx == lm.from || idx == lm.to)) {
                    color = if (lightSquare) lastMoveLight else lastMoveDark
                }
                if (idx == selected) color = Color.parseColor("#8BC34A")
                if (idx == kingSq) color = Color.parseColor("#E57373")
                squarePaint.color = color
                canvas.drawRect(c * s, r * s, (c + 1) * s, (r + 1) * s, squarePaint)
            }
        }

        // Подписи координат
        labelPaint.textSize = s * 0.18f
        for (c in 0..7) {
            val letter = if (flipped) 'h' - c else 'a' + c
            labelPaint.color = if ((7 + c) % 2 == 0) darkColor else lightColor
            canvas.drawText(letter.toString(), c * s + s * 0.84f, 8 * s - s * 0.06f, labelPaint)
        }
        for (r in 0..7) {
            val rank = if (flipped) r + 1 else 8 - r
            labelPaint.color = if (r % 2 == 0) darkColor else lightColor
            canvas.drawText(rank.toString(), s * 0.05f, r * s + s * 0.2f, labelPaint)
        }

        // Подсказки возможных ходов
        if (selected >= 0) {
            for (m in legalMoves) {
                if (m.from != selected) continue
                val cx = toCol(m.to) * s + s / 2
                val cy = toRow(m.to) * s + s / 2
                if (position.board[m.to] != ' ' || (m.to == position.ep && position.board[m.from].lowercaseChar() == 'p')) {
                    dotPaint.style = Paint.Style.STROKE
                    dotPaint.strokeWidth = s * 0.08f
                    dotPaint.color = Color.argb(140, 60, 60, 60)
                    canvas.drawCircle(cx, cy, s * 0.42f, dotPaint)
                } else {
                    dotPaint.style = Paint.Style.FILL
                    dotPaint.color = Color.argb(120, 60, 60, 60)
                    canvas.drawCircle(cx, cy, s * 0.14f, dotPaint)
                }
            }
        }

        // Фигуры
        for (idx in 0 until 64) {
            val p = position.board[idx]
            if (p == ' ') continue
            if (dragging && idx == selected) continue
            drawPiece(canvas, p, toCol(idx) * s + s / 2, toRow(idx) * s + s / 2, s, 1f)
        }
        if (dragging && selected >= 0) {
            drawPiece(canvas, position.board[selected], dragX, dragY - s * 0.4f, s, 1.25f)
        }
    }

    private fun drawPiece(canvas: Canvas, p: Char, cx: Float, cy: Float, sq: Float, scale: Float) {
        val glyph = (GLYPHS[p.lowercaseChar()] ?: return) + "\uFE0E"
        val size = sq * 0.82f * scale
        fillPaint.textSize = size
        strokePaint.textSize = size
        strokePaint.strokeWidth = sq * 0.025f
        val fm = fillPaint.fontMetrics
        val y = cy - (fm.ascent + fm.descent) / 2f
        if (p.isUpperCase()) {
            fillPaint.color = Color.WHITE
            canvas.drawText(glyph, cx, y, fillPaint)
            canvas.drawText(glyph, cx, y, strokePaint)
        } else {
            fillPaint.color = Color.rgb(25, 25, 25)
            canvas.drawText(glyph, cx, y, fillPaint)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!interactive) return true
        val s = width / 8f
        val col = (e.x / s).toInt().coerceIn(0, 7)
        val row = (e.y / s).toInt().coerceIn(0, 7)
        val idx = toIndex(row, col)

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                moved = false
                if (selected >= 0 && legalMoves.any { it.from == selected && it.to == idx }) {
                    val from = selected
                    resetSelection()
                    invalidate()
                    onMoveAttempt?.invoke(from, idx)
                    return true
                }
                val p = position.board[idx]
                if (p != ' ' && p.isUpperCase() == playerWhite && position.whiteToMove == playerWhite) {
                    selected = idx
                    downSquare = idx
                    dragging = true
                    dragX = e.x
                    dragY = e.y
                } else {
                    selected = -1
                    dragging = false
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging) {
                    dragX = e.x
                    dragY = e.y
                    if (idx != downSquare) moved = true
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    dragging = false
                    if (moved && idx != selected) {
                        val from = selected
                        selected = -1
                        if (legalMoves.any { it.from == from && it.to == idx }) {
                            invalidate()
                            onMoveAttempt?.invoke(from, idx)
                            return true
                        }
                    }
                    invalidate()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                resetSelection()
                invalidate()
            }
        }
        return true
    }
}
