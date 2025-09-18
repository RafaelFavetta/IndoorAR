package com.example.indoorar

import android.graphics.Rect
import android.media.Image
import com.google.zxing.PlanarYUVLuminanceSource
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min

object ImageUtils {

    /**
     * Converte o Y-plane do YUV_420_888 em um array de luminância (Y) respeitando rowStride/pixelStride
     * e recorta para o cropRect. Retorna um PlanarYUVLuminanceSource pronto pro ZXing.
     */
    fun getLuminanceSourceFromImage(image: Image, cropRect: Rect): PlanarYUVLuminanceSource {
        require(image.format == android.graphics.ImageFormat.YUV_420_888) {
            "Formato de imagem não suportado: ${image.format}"
        }

        val fullWidth = image.width
        val fullHeight = image.height

        // Garante recorte dentro dos limites
        val left = max(0, cropRect.left)
        val top = max(0, cropRect.top)
        val right = min(fullWidth, cropRect.right)
        val bottom = min(fullHeight, cropRect.bottom)

        val width = max(0, right - left)
        val height = max(0, bottom - top)

        val yPlane = image.planes[0]
        val yBuffer: ByteBuffer = yPlane.buffer
        val rowStride = yPlane.rowStride
        val pixelStride = yPlane.pixelStride // geralmente 1, mas tratamos genericamente

        // Copia qualquer Y para um array
        val ySize = yBuffer.remaining()
        val yBytes = ByteArray(ySize)
        yBuffer.get(yBytes, 0, ySize)

        // Cria um buffer compacto apenas do recorte, sem stride
        val out = ByteArray(width * height)
        var outPos = 0

        for (row in 0 until height) {
            var inPos = (top + row) * rowStride + (left * pixelStride)
            if (pixelStride == 1) {
                // bloco contínuo — rápido
                System.arraycopy(yBytes, inPos, out, outPos, width)
                outPos += width
            } else {
                // pixelStride > 1 — lê pixel a pixel
                var col = 0
                var pos = inPos
                while (col < width) {
                    out[outPos++] = yBytes[pos]
                    pos += pixelStride
                    col++
                }
            }
        }

        // Cria a luminance source com o recorte já “achatado”
        return PlanarYUVLuminanceSource(
            out,
            width,  // dataWidth
            height, // dataHeight
            0, 0,   // left, top dentro do array "out"
            width,  // width do crop dentro de "out"
            height, // height do crop dentro de "out"
            false
        )
    }
}