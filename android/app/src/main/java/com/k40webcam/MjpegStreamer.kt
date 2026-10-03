package com.k40webcam

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class MjpegStreamer(private var port: Int = 8080) {

    fun setPort(p: Int) {
        port = p
    }

    fun getPort(): Int = port

    private companion object {
        const val TAG = "MjpegStreamer"
        const val BOUNDARY = "mjpegframe"
    }

    private var serverSocket: ServerSocket? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var reusableBitmap: Bitmap? = null
    private var contiguousBuffer: ByteBuffer? = null
    private var tempRow: ByteArray? = null

    private val clients = CopyOnWriteArrayList<ClientHandler>()
    private val isRunning = AtomicBoolean(false)

    @Volatile
    private var width = 1920

    @Volatile
    private var height = 1080

    @Volatile
    private var quality = 75

    val surface: Surface?
        get() = imageReader?.surface

    fun prepare(w: Int, h: Int, q: Int = 75) {
        stop()
        width = w
        height = h
        quality = q.coerceIn(30, 95)

        val thread = HandlerThread("MjpegWorker")
        thread.start()
        handlerThread = thread
        val hdl = Handler(thread.looper)
        handler = hdl

        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        reader.setOnImageAvailableListener({ ir ->
            val image = ir.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                if (clients.isNotEmpty()) {
                    val bytes = rgbaToJpeg(image, quality)
                    if (bytes != null && bytes.isNotEmpty()) {
                        for (client in clients) {
                            client.sendFrame(bytes)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "MJPEG 帧处理异常: ${e.message}")
            } finally {
                image.close()
            }
        }, hdl)
        imageReader = reader
    }

    fun setQuality(q: Int) {
        quality = q.coerceIn(30, 95)
    }

    fun start() {
        if (isRunning.getAndSet(true)) return

        Thread({
            try {
                val srv = ServerSocket(port)
                srv.reuseAddress = true
                serverSocket = srv
                Log.i(TAG, "MJPEG HTTP 服务已启动: 端口 $port")

                while (isRunning.get()) {
                    val socket = srv.accept()
                    socket.tcpNoDelay = true
                    socket.sendBufferSize = 256 * 1024
                    val client = ClientHandler(socket) { c ->
                        clients.remove(c)
                    }
                    clients.add(client)
                    Thread(client, "MjpegClient-${socket.inetAddress.hostAddress}").start()
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.e(TAG, "MJPEG 监听异常: ${e.message}")
                }
            }
        }, "MjpegAcceptThread").start()
    }

    fun stop() {
        isRunning.set(false)
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null

        for (client in clients) {
            client.close()
        }
        clients.clear()

        try {
            imageReader?.close()
        } catch (_: Exception) {}
        imageReader = null

        handlerThread?.quitSafely()
        handlerThread = null
        handler = null

        reusableBitmap?.recycle()
        reusableBitmap = null
        contiguousBuffer = null
        tempRow = null
    }

    fun isStarted(): Boolean = isRunning.get()

    private fun rgbaToJpeg(image: Image, q: Int): ByteArray? {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val w = image.width
        val h = image.height

        var bmp = reusableBitmap
        if (bmp == null || bmp.width != w || bmp.height != h || bmp.isRecycled) {
            bmp?.recycle()
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            reusableBitmap = bmp
        }

        if (rowStride == w * 4) {
            buffer.rewind()
            bmp.copyPixelsFromBuffer(buffer)
        } else {
            var cBuf = contiguousBuffer
            val totalBytes = w * h * 4
            if (cBuf == null || cBuf.capacity() < totalBytes) {
                cBuf = ByteBuffer.allocateDirect(totalBytes)
                contiguousBuffer = cBuf
            }
            cBuf.clear()

            var rBytes = tempRow
            val rowSize = w * 4
            if (rBytes == null || rBytes.size < rowSize) {
                rBytes = ByteArray(rowSize)
                tempRow = rBytes
            }

            for (r in 0 until h) {
                buffer.position(r * rowStride)
                buffer.get(rBytes, 0, rowSize)
                cBuf.put(rBytes, 0, rowSize)
            }
            cBuf.rewind()
            bmp.copyPixelsFromBuffer(cBuf)
        }

        val out = ByteArrayOutputStream(w * h / 4)
        bmp.compress(Bitmap.CompressFormat.JPEG, q, out)
        return out.toByteArray()
    }

    private class ClientHandler(
        private val socket: Socket,
        private val onClosed: (ClientHandler) -> Unit
    ) : Runnable {

        private var output: OutputStream? = null
        private val alive = AtomicBoolean(true)
        private val frameQueue = ArrayBlockingQueue<ByteArray>(1)

        override fun run() {
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                while (true) {
                    val line = reader.readLine()
                    if (line == null || line.isEmpty()) break
                }

                output = socket.getOutputStream()
                val header = "HTTP/1.1 200 OK\r\n" +
                        "Server: K40WebCam-MJPEG\r\n" +
                        "Connection: close\r\n" +
                        "Cache-Control: no-cache, no-store, must-revalidate, pre-check=0, post-check=0, max-age=0\r\n" +
                        "Pragma: no-cache\r\n" +
                        "Expires: 0\r\n" +
                        "Access-Control-Allow-Origin: *\r\n" +
                        "Content-Type: multipart/x-mixed-replace; boundary=$BOUNDARY\r\n\r\n"
                output?.write(header.toByteArray())
                output?.flush()

                while (alive.get() && !socket.isClosed) {
                    val frame = frameQueue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                    val out = output ?: break
                    val frameHeader = "--$BOUNDARY\r\n" +
                            "Content-Type: image/jpeg\r\n" +
                            "Content-Length: ${frame.size}\r\n\r\n"
                    out.write(frameHeader.toByteArray())
                    out.write(frame)
                    out.write("\r\n".toByteArray())
                    out.flush()
                }
            } catch (_: Exception) {
            } finally {
                close()
            }
        }

        fun sendFrame(jpegBytes: ByteArray) {
            if (!alive.get()) return
            while (!frameQueue.offer(jpegBytes)) {
                frameQueue.poll()
            }
        }

        fun close() {
            if (!alive.getAndSet(false)) return
            try {
                socket.close()
            } catch (_: Exception) {}
            onClosed(this)
        }
    }
}
