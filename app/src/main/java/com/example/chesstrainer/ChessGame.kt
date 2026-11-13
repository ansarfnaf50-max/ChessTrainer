package com.example.chesstrainer

import kotlin.math.abs

data class Move(val from: Int, val to: Int, val promo: Char = ' ') {
    fun uci(): String {
        val base = Position.squareName(from) + Position.squareName(to)
        return if (promo == ' ') base else base + promo.lowercaseChar()
    }
}

enum class GameStatus { PLAYING, CHECKMATE, STALEMATE, DRAW_50, DRAW_MATERIAL }

/**
 * Индекс клетки: 0 = a8, 7 = h8, 56 = a1, 63 = h1.
 * Белые фигуры - заглавные буквы, чёрные - строчные, пусто - ' '.
 */
class Position(
    val board: CharArray,
    val whiteToMove: Boolean,
    val castling: String,
    val ep: Int,
    val halfmove: Int,
    val fullmove: Int
) {
    companion object {
        const val START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

        private val ROOK_DIRS = arrayOf(intArrayOf(-1, 0), intArrayOf(1, 0), intArrayOf(0, -1), intArrayOf(0, 1))
        private val BISHOP_DIRS = arrayOf(intArrayOf(-1, -1), intArrayOf(-1, 1), intArrayOf(1, -1), intArrayOf(1, 1))
        private val QUEEN_DIRS = ROOK_DIRS + BISHOP_DIRS
        private val KNIGHT_JUMPS = arrayOf(
            intArrayOf(-2, -1), intArrayOf(-2, 1), intArrayOf(-1, -2), intArrayOf(-1, 2),
            intArrayOf(1, -2), intArrayOf(1, 2), intArrayOf(2, -1), intArrayOf(2, 1)
        )

        fun squareName(i: Int): String = "${'a' + i % 8}${8 - i / 8}"

        fun fromFen(fen: String): Position {
            val parts = fen.trim().split(" ")
            val board = CharArray(64) { ' ' }
            var idx = 0
            for (ch in parts[0]) {
                when {
                    ch == '/' -> {}
                    ch.isDigit() -> idx += ch - '0'
                    else -> {
                        board[idx] = ch
                        idx++
                    }
                }
            }
            val white = parts.getOrElse(1) { "w" } == "w"
            val cast = parts.getOrElse(2) { "-" }.let { if (it == "-") "" else it }
            val epStr = parts.getOrElse(3) { "-" }
            val ep = if (epStr == "-") -1 else (epStr[0] - 'a') + (8 - (epStr[1] - '0')) * 8
            val half = parts.getOrElse(4) { "0" }.toIntOrNull() ?: 0
            val full = parts.getOrElse(5) { "1" }.toIntOrNull() ?: 1
            return Position(board, white, cast, ep, half, full)
        }
    }

    fun toFen(): String {
        val sb = StringBuilder()
        for (r in 0..7) {
            var empty = 0
            for (c in 0..7) {
                val p = board[r * 8 + c]
                if (p == ' ') {
                    empty++
                } else {
                    if (empty > 0) {
                        sb.append(empty)
                        empty = 0
                    }
                    sb.append(p)
                }
            }
            if (empty > 0) sb.append(empty)
            if (r < 7) sb.append('/')
        }
        sb.append(if (whiteToMove) " w " else " b ")
        sb.append(if (castling.isEmpty()) "-" else castling)
        sb.append(' ')
        sb.append(if (ep < 0) "-" else squareName(ep))
        sb.append(' ').append(halfmove).append(' ').append(fullmove)
        return sb.toString()
    }

    /** Атакована ли клетка sq фигурами белых (byWhite = true) или чёрных. */
    fun isAttacked(sq: Int, byWhite: Boolean): Boolean {
        val r = sq / 8
        val c = sq % 8

        val pawn = if (byWhite) 'P' else 'p'
        val pr = if (byWhite) r + 1 else r - 1
        if (pr in 0..7) {
            if (c > 0 && board[pr * 8 + c - 1] == pawn) return true
            if (c < 7 && board[pr * 8 + c + 1] == pawn) return true
        }

        val knight = if (byWhite) 'N' else 'n'
        for (d in KNIGHT_JUMPS) {
            val nr = r + d[0]
            val nc = c + d[1]
            if (nr in 0..7 && nc in 0..7 && board[nr * 8 + nc] == knight) return true
        }

        val king = if (byWhite) 'K' else 'k'
        for (d in QUEEN_DIRS) {
            val nr = r + d[0]
            val nc = c + d[1]
            if (nr in 0..7 && nc in 0..7 && board[nr * 8 + nc] == king) return true
        }

        val rook = if (byWhite) 'R' else 'r'
        val bishop = if (byWhite) 'B' else 'b'
        val queen = if (byWhite) 'Q' else 'q'

        for (d in ROOK_DIRS) {
            var nr = r + d[0]
            var nc = c + d[1]
            while (nr in 0..7 && nc in 0..7) {
                val p = board[nr * 8 + nc]
                if (p != ' ') {
                    if (p == rook || p == queen) return true
                    break
                }
                nr += d[0]
                nc += d[1]
            }
        }
        for (d in BISHOP_DIRS) {
            var nr = r + d[0]
            var nc = c + d[1]
            while (nr in 0..7 && nc in 0..7) {
                val p = board[nr * 8 + nc]
                if (p != ' ') {
                    if (p == bishop || p == queen) return true
                    break
                }
                nr += d[0]
                nc += d[1]
            }
        }
        return false
    }

    fun inCheck(): Boolean {
        val ksq = board.indexOf(if (whiteToMove) 'K' else 'k')
        return ksq >= 0 && isAttacked(ksq, !whiteToMove)
    }

    private fun addPawnMove(out: MutableList<Move>, from: Int, to: Int, promotes: Boolean) {
        if (promotes) {
            for (pc in charArrayOf('q', 'r', 'b', 'n')) out.add(Move(from, to, pc))
        } else {
            out.add(Move(from, to))
        }
    }

    private fun pseudoMoves(): List<Move> {
        val out = ArrayList<Move>()
        val white = whiteToMove
        for (i in 0 until 64) {
            val p = board[i]
            if (p == ' ' || p.isUpperCase() != white) continue
            val r = i / 8
            val c = i % 8
            val type = p.lowercaseChar()
            when (type) {
                'p' -> {
                    val dir = if (white) -1 else 1
                    val startRow = if (white) 6 else 1
                    val promoRow = if (white) 0 else 7
                    val nr = r + dir
                    if (nr in 0..7) {
                        if (board[nr * 8 + c] == ' ') {
                            addPawnMove(out, i, nr * 8 + c, nr == promoRow)
                            val dr = r + 2 * dir
                            if (r == startRow && board[dr * 8 + c] == ' ') {
                                out.add(Move(i, dr * 8 + c))
                            }
                        }
                        for (dc in intArrayOf(-1, 1)) {
                            val nc = c + dc
                            if (nc !in 0..7) continue
                            val t = nr * 8 + nc
                            val target = board[t]
                            if (target != ' ' && target.isUpperCase() != white) {
                                addPawnMove(out, i, t, nr == promoRow)
                            } else if (target == ' ' && t == ep) {
                                out.add(Move(i, t))
                            }
                        }
                    }
                }
                'n' -> {
                    for (d in KNIGHT_JUMPS) {
                        val nr = r + d[0]
                        val nc = c + d[1]
                        if (nr !in 0..7 || nc !in 0..7) continue
                        val t = board[nr * 8 + nc]
                        if (t == ' ' || t.isUpperCase() != white) out.add(Move(i, nr * 8 + nc))
                    }
                }
                'k' -> {
                    for (d in QUEEN_DIRS) {
                        val nr = r + d[0]
                        val nc = c + d[1]
                        if (nr !in 0..7 || nc !in 0..7) continue
                        val t = board[nr * 8 + nc]
                        if (t == ' ' || t.isUpperCase() != white) out.add(Move(i, nr * 8 + nc))
                    }
                    if (white && i == 60) {
                        if ('K' in castling && board[61] == ' ' && board[62] == ' ' && board[63] == 'R' &&
                            !isAttacked(60, false) && !isAttacked(61, false) && !isAttacked(62, false)
                        ) out.add(Move(60, 62))
                        if ('Q' in castling && board[59] == ' ' && board[58] == ' ' && board[57] == ' ' && board[56] == 'R' &&
                            !isAttacked(60, false) && !isAttacked(59, false) && !isAttacked(58, false)
                        ) out.add(Move(60, 58))
                    }
                    if (!white && i == 4) {
                        if ('k' in castling && board[5] == ' ' && board[6] == ' ' && board[7] == 'r' &&
                            !isAttacked(4, true) && !isAttacked(5, true) && !isAttacked(6, true)
                        ) out.add(Move(4, 6))
                        if ('q' in castling && board[3] == ' ' && board[2] == ' ' && board[1] == ' ' && board[0] == 'r' &&
                            !isAttacked(4, true) && !isAttacked(3, true) && !isAttacked(2, true)
                        ) out.add(Move(4, 2))
                    }
                }
                else -> {
                    val dirs = when (type) {
                        'b' -> BISHOP_DIRS
                        'r' -> ROOK_DIRS
                        else -> QUEEN_DIRS
                    }
                    for (d in dirs) {
                        var nr = r + d[0]
                        var nc = c + d[1]
                        while (nr in 0..7 && nc in 0..7) {
                            val t = board[nr * 8 + nc]
                            if (t == ' ') {
                                out.add(Move(i, nr * 8 + nc))
                            } else {
                                if (t.isUpperCase() != white) out.add(Move(i, nr * 8 + nc))
                                break
                            }
                            nr += d[0]
                            nc += d[1]
                        }
                    }
                }
            }
        }
        return out
    }

    /** Возвращает новую позицию после хода (исходная не меняется). */
    fun make(m: Move): Position {
        val nb = board.copyOf()
        val piece = nb[m.from]
        val captured = nb[m.to]
        var newEp = -1
        var epCapture = false

        nb[m.to] = if (m.promo != ' ') {
            if (whiteToMove) m.promo.uppercaseChar() else m.promo.lowercaseChar()
        } else piece
        nb[m.from] = ' '

        if (piece.lowercaseChar() == 'p') {
            if (m.to == ep && captured == ' ' && m.from % 8 != m.to % 8) {
                nb[if (whiteToMove) m.to + 8 else m.to - 8] = ' '
                epCapture = true
            }
            if (abs(m.to - m.from) == 16) newEp = (m.from + m.to) / 2
        }

        if (piece.lowercaseChar() == 'k' && abs(m.to - m.from) == 2) {
            if (m.to > m.from) {
                nb[m.to - 1] = nb[m.to + 1]
                nb[m.to + 1] = ' '
            } else {
                nb[m.to + 1] = nb[m.to - 2]
                nb[m.to - 2] = ' '
            }
        }

        var cr = castling
        for (s in intArrayOf(m.from, m.to)) {
            when (s) {
                60 -> cr = cr.replace("K", "").replace("Q", "")
                4 -> cr = cr.replace("k", "").replace("q", "")
                63 -> cr = cr.replace("K", "")
                56 -> cr = cr.replace("Q", "")
                7 -> cr = cr.replace("k", "")
                0 -> cr = cr.replace("q", "")
            }
        }

        val reset = piece.lowercaseChar() == 'p' || captured != ' ' || epCapture
        return Position(
            nb,
            !whiteToMove,
            cr,
            newEp,
            if (reset) 0 else halfmove + 1,
            if (whiteToMove) fullmove else fullmove + 1
        )
    }

    fun legalMoves(): List<Move> {
        val king = if (whiteToMove) 'K' else 'k'
        return pseudoMoves().filter { m ->
            val np = make(m)
            val ksq = np.board.indexOf(king)
            ksq >= 0 && !np.isAttacked(ksq, !whiteToMove)
        }
    }

    private fun insufficientMaterial(): Boolean {
        var minors = 0
        for (p in board) {
            when (p.lowercaseChar()) {
                'p', 'r', 'q' -> return false
                'n', 'b' -> minors++
            }
        }
        return minors <= 1
    }

    fun status(): GameStatus {
        if (legalMoves().isEmpty()) {
            return if (inCheck()) GameStatus.CHECKMATE else GameStatus.STALEMATE
        }
        if (halfmove >= 100) return GameStatus.DRAW_50
        if (insufficientMaterial()) return GameStatus.DRAW_MATERIAL
        return GameStatus.PLAYING
    }
}
