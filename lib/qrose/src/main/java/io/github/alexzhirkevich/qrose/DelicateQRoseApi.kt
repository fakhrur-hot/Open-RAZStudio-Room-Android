package io.github.alexzhirkevich.qrose

import kotlin.annotation.AnnotationRetention
import kotlin.annotation.Retention

@RequiresOptIn(
    message = "This API may negatively impact QR code functionality",
    level = RequiresOptIn.Level.WARNING
)
@Retention(AnnotationRetention.BINARY)
annotation class DelicateQRoseApi