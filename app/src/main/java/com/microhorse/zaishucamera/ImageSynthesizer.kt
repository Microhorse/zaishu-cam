package com.microhorse.zaishucamera

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.exifinterface.media.ExifInterface
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

object ImageSynthesizer {

    /**
     * フォルダ名として安全な文字列に整形する
     */
    fun sanitizeFolderName(name: String): String {
        val cleaned = name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
        return cleaned.ifEmpty { "標準" }
    }

    /**
     * ImageProxyから得たBitmapと回転角を正規化し、正立したBitmapを返す
     */
    fun normalizeRotation(source: Bitmap, rotationDegrees: Int): Bitmap {
        if (rotationDegrees == 0) return source
        val matrix = Matrix().apply {
            postRotate(rotationDegrees.toFloat())
        }
        val rotated = Bitmap.createBitmap(
            source, 0, 0, source.width, source.height, matrix, true
        )
        if (rotated != source) {
            source.recycle()
        }
        return rotated
    }

    /**
     * 元画像の下部にのみ白余白を追加し、撮影日時・フォルダタグ・メモを描画してMediaStoreに保存する
     *
     * @param folderName 案件・場所などのフォルダ名（DCIM/ZaishuMemo/<folderName> に保存）
     * @param alsoSaveToCameraRoll trueの場合、Googleフォト即時自動バックアップ対象である DCIM/Camera にも保存する
     */
    fun synthesizeAndSave(
        context: Context,
        originalBitmap: Bitmap,
        memoText: String,
        folderName: String,
        alsoSaveToCameraRoll: Boolean,
        capturedTimeMillis: Long,
        isChecked: Boolean = false,
        location: Location? = null
    ): Uri? {
        val w = originalBitmap.width
        val h = originalBitmap.height
        val safeFolder = sanitizeFolderName(folderName)

        // パディングと最小下部余白の計算
        val padding = w * 0.04f
        val contentWidth = max(1, (w - (padding * 2f)).toInt())
        val minBottomSpace = (h * 0.22f).toInt()

        // 1. 撮影日時＋フォルダタグ用のTextPaintとStaticLayout
        val dateFormat = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
        val checkSuffix = if (isChecked) "  [✓]" else ""
        val dateString = "${dateFormat.format(Date(capturedTimeMillis))}  [$safeFolder]$checkSuffix"

        val datePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.DKGRAY
            textSize = w * 0.028f
        }
        val dateLayout = StaticLayout.Builder
            .obtain(dateString, 0, dateString.length, datePaint, contentWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.0f)
            .setIncludePad(false)
            .build()

        // 2. メモ本文用のTextPaintとStaticLayout（空欄でない場合のみ）
        val trimmedMemo = memoText.trimEnd()
        val memoPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1A1A1A")
            textSize = w * 0.04f
        }
        val memoLayout: StaticLayout? = if (trimmedMemo.isNotEmpty()) {
            StaticLayout.Builder
                .obtain(trimmedMemo, 0, trimmedMemo.length, memoPaint, contentWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.15f)
                .setIncludePad(false)
                .build()
        } else {
            null
        }

        // 日時とメモの間の間隔
        val dateToMemoGap = if (memoLayout != null) (w * 0.015f).toInt() else 0
        val textHeight = dateLayout.height + dateToMemoGap + (memoLayout?.height ?: 0)

        // 実際の下部余白高さ（最小22% または テキスト全体＋上下パディングの大きい方）
        val bottomSpace = max(minBottomSpace, textHeight + (padding * 2f).toInt())

        // 3. 新規Bitmapの生成とCanvas描画
        val newWidth = w
        val newHeight = h + bottomSpace
        val combinedBitmap = Bitmap.createBitmap(newWidth, newHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(combinedBitmap)

        // 元画像を(0, 0)に描画（写真本体にはバッジを重ねず100%そのまま残す）
        canvas.drawBitmap(originalBitmap, 0f, 0f, null)

        // 下部領域(0, H)〜(W, H + bottomSpace)を白で塗りつぶす
        val bgPaint = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        canvas.drawRect(0f, h.toFloat(), newWidth.toFloat(), newHeight.toFloat(), bgPaint)

        // 日時の描画 (X: padding, Y: H + padding)
        canvas.save()
        canvas.translate(padding, h + padding)
        dateLayout.draw(canvas)

        // メモ本文の描画（日時の下）
        if (memoLayout != null) {
            canvas.translate(0f, (dateLayout.height + dateToMemoGap).toFloat())
            memoLayout.draw(canvas)
        }
        canvas.restore()

        // 下部白余白の右上にもチェックマークを描画（一覧・余白確認用）
        if (isChecked) {
            val subRadius = w * 0.032f
            drawCheckBadge(
                canvas = canvas,
                centerX = w - padding - subRadius,
                centerY = h + padding + subRadius,
                radius = subRadius
            )
        }

        // 4. MediaStoreへのJPEG保存（品質95% ＋ EXIF GPS・日時メタデータ書き込み）
        val prefixWithCheck = if (isChecked) "${safeFolder}_CHECK" else safeFolder
        // ① 案件・場所別の専用フォルダ (DCIM/ZaishuMemo/<safeFolder>) に保存
        val primaryRelativePath = "${Environment.DIRECTORY_DCIM}/ZaishuMemo/$safeFolder"
        val savedUri = saveToMediaStore(
            context = context,
            bitmap = combinedBitmap,
            relativePath = primaryRelativePath,
            folderPrefix = prefixWithCheck,
            capturedTimeMillis = capturedTimeMillis,
            location = location
        )

        // ② Googleフォト即時自動バックアップ連動がONの場合は DCIM/Camera にも保存
        if (alsoSaveToCameraRoll) {
            val cameraRollPath = "${Environment.DIRECTORY_DCIM}/Camera"
            saveToMediaStore(
                context = context,
                bitmap = combinedBitmap,
                relativePath = cameraRollPath,
                folderPrefix = prefixWithCheck,
                capturedTimeMillis = capturedTimeMillis,
                location = location
            )
        }

        // メモリ解放
        combinedBitmap.recycle()
        originalBitmap.recycle()

        return savedUri
    }

    /**
     * 指定座標に緑丸＋白枠＋白チェックマーク（✓）を描画する
     */
    private fun drawCheckBadge(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        radius: Float
    ) {
        // 千歳緑（#234F2E）の丸背景
        val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#234F2E")
            style = Paint.Style.FILL
        }
        canvas.drawCircle(centerX, centerY, radius, circlePaint)

        // 白い外枠
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = radius * 0.14f
        }
        canvas.drawCircle(centerX, centerY, radius, borderPaint)

        // 白いチェックマーク（✓）パス描画
        val checkPath = Path().apply {
            moveTo(centerX - radius * 0.42f, centerY + radius * 0.02f)
            lineTo(centerX - radius * 0.10f, centerY + radius * 0.34f)
            lineTo(centerX + radius * 0.45f, centerY - radius * 0.28f)
        }
        val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = radius * 0.22f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        canvas.drawPath(checkPath, checkPaint)
    }

    private fun saveToMediaStore(
        context: Context,
        bitmap: Bitmap,
        relativePath: String,
        folderPrefix: String,
        capturedTimeMillis: Long,
        location: Location?
    ): Uri? {
        val fileNameDate = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault())
            .format(Date(capturedTimeMillis))
        val fileName = "ZAISHU_${folderPrefix}_$fileNameDate.jpg"

        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.DATE_ADDED, capturedTimeMillis / 1000)
            put(MediaStore.Images.Media.DATE_TAKEN, capturedTimeMillis)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            ?: return null

        return try {
            resolver.openOutputStream(uri)?.use { outputStream ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)) {
                    throw IOException("Failed to compress bitmap")
                }
            } ?: throw IOException("Failed to open OutputStream")

            // JPEGファイルへEXIFメタデータ（撮影日時＋GPS位置情報）を書き込む
            try {
                resolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                    val exif = ExifInterface(pfd.fileDescriptor)
                    val exifDateFormat = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
                    val exifDateStr = exifDateFormat.format(Date(capturedTimeMillis))
                    exif.setAttribute(ExifInterface.TAG_DATETIME, exifDateStr)
                    exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, exifDateStr)
                    exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, exifDateStr)
                    if (location != null) {
                        exif.setGpsInfo(location)
                    }
                    exif.saveAttributes()
                }
            } catch (exifError: Exception) {
                exifError.printStackTrace()
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val updateValues = ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }
                resolver.update(uri, updateValues, null, null)
            }
            uri
        } catch (e: Exception) {
            e.printStackTrace()
            resolver.delete(uri, null, null)
            null
        }
    }

    /**
     * 指定フォルダ（DCIM/ZaishuMemo/<folderName>）内に保存されている画像のURI一覧を取得する
     */
    fun getFolderImageUris(context: Context, folderName: String): List<Uri> {
        val safeFolder = sanitizeFolderName(folderName)
        val targetPath = "${Environment.DIRECTORY_DCIM}/ZaishuMemo/$safeFolder/"
        val uris = mutableListOf<Uri>()

        val projection = arrayOf(MediaStore.Images.Media._ID)
        val selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf("$targetPath%")
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val contentUri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        id
                    )
                    uris.add(contentUri)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return uris
    }
}
