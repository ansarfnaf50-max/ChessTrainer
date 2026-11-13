package com.example.chesstrainer

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.Executors

class StockfishEngine(private val context: Context) {

    /** Уровни сложности: Skill Level (0..20) + ограничение глубины или времени. */
    enum class Level(val title: String, val skill: Int, val goCommand: String) {
        EASY("Лёгкий", 3, "go depth 2"),
        MEDIUM("Средний", 10, "go movetime 500"),
        HARD("Сложный", 20, "go movetime 2000")
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null

    @Volatile
    private var ready = false

    private fun send(cmd: String) {
        val w = writer ?: throw IOException("Движок не запущен")
        w.write(cmd)
        w.write("\n")
        w.flush()
    }

    private fun waitFor(token: String) {
        val r = reader ?: throw IOException("Движок не запущен")
        while (true) {
            val line = r.readLine() ?: throw IOException("Движок завершился")
            if (line.startsWith(token)) return
        }
    }

    fun start(callback: (Boolean, String) -> Unit) {
        executor.execute {
            try {
                val file = File(context.applicationInfo.nativeLibraryDir, "libstockfish.so")
                if (!file.exists()) throw IOException("Не найден файл " + file.absolutePath)
                val p = ProcessBuilder(file.absolutePath).redirectErrorStream(true).start()
                process = p
                writer = BufferedWriter(OutputStreamWriter(p.outputStream))
                reader = BufferedReader(InputStreamReader(p.inputStream))

                send("uci")
                waitFor("uciok")
                send("setoption name Threads value 2")
                send("setoption name Hash value 32")
                send("isready")
                waitFor("readyok")
                ready = true
                mainHandler.post { callback(true, "") }
            } catch (e: Exception) {
                val msg = e.message ?: e.toString()
                mainHandler.post { callback(false, msg) }
            }
        }
    }

    /** Отправляет FEN, возвращает лучший ход в формате UCI (например "e2e4") или null. */
    fun requestMove(fen: String, level: Level, callback: (String?) -> Unit) {
        executor.execute {
            var result: String? = null
            try {
                if (!ready) throw IOException("Движок не готов")
                send("setoption name Skill Level value ${level.skill}")
                send("position fen $fen")
                send(level.goCommand)
                val r = reader ?: throw IOException("Нет потока чтения")
                while (true) {
                    val line = r.readLine() ?: break
                    if (line.startsWith("bestmove")) {
                        val parts = line.split(" ")
                        if (parts.size >= 2 && parts[1] != "(none)") result = parts[1]
                        break
                    }
                }
            } catch (e: Exception) {
                result = null
            }
            mainHandler.post { callback(result) }
        }
    }

    fun shutdown() {
        try {
            process?.destroy()
        } catch (_: Exception) {
        }
        executor.shutdownNow()
    }
}
