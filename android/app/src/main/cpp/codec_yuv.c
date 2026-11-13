#include <jni.h>
#include <stdint.h>

static uint8_t *plane(JNIEnv *env, jobject buffer, jint w, jint h, jint row, jint pixel) {
    if (w <= 0 || h <= 0 || pixel <= 0 || row <= 0) return NULL;
    const int64_t useful = (int64_t)(w - 1) * pixel + 1;
    const int64_t end = (int64_t)(h - 1) * row + useful;
    if (row < useful || end > (*env)->GetDirectBufferCapacity(env, buffer)) return NULL;
    return (*env)->GetDirectBufferAddress(env, buffer);
}

JNIEXPORT jboolean JNICALL Java_com_hsd_universal_aa_CodecImageInput_convert(
        JNIEnv *env, jobject self, jint w, jint h, jobject rgba,
        jobject y_buf, jint yr, jint yp, jobject u_buf, jint ur, jint up,
        jobject v_buf, jint vr, jint vp) {
    (void)self;
    if (w <= 0 || h <= 0 || (int64_t)w * h > INT32_MAX / 4) return JNI_FALSE;
    if ((*env)->GetDirectBufferCapacity(env, rgba) < (int64_t)w * h * 4) return JNI_FALSE;
    const uint8_t *src = (*env)->GetDirectBufferAddress(env, rgba);
    const int cw = (w + 1) / 2, ch = (h + 1) / 2;
    uint8_t *y = plane(env, y_buf, w, h, yr, yp);
    uint8_t *u = plane(env, u_buf, cw, ch, ur, up);
    uint8_t *v = plane(env, v_buf, cw, ch, vr, vp);
    if (!src || !y || !u || !v) return JNI_FALSE;
    // GL rows start at the bottom. The encoded image uses top-down BT.601 limited-range YUV.
    for (int row = 0; row < h; row += 2) {
        for (int col = 0; col < w; col += 2) {
            int rs = 0, gs = 0, bs = 0, samples = 0;
            for (int dy = 0; dy < 2 && row + dy < h; ++dy) {
                const uint8_t *p = src + 4 * ((h - row - dy - 1) * w + col);
                uint8_t *luma = y + (int64_t)(row + dy) * yr + (int64_t)col * yp;
                for (int dx = 0; dx < 2 && col + dx < w; ++dx, p += 4) {
                    const int r = p[0], g = p[1], b = p[2];
                    luma[dx * yp] = 16 + ((66 * r + 129 * g + 25 * b + 128) >> 8);
                    rs += r; gs += g; bs += b; ++samples;
                }
            }
            const int divisor = 256 * samples;
            u[(int64_t)(row / 2) * ur + (int64_t)(col / 2) * up] =
                (128 * divisor - 38 * rs - 74 * gs + 112 * bs + divisor / 2) / divisor;
            v[(int64_t)(row / 2) * vr + (int64_t)(col / 2) * vp] =
                (128 * divisor + 112 * rs - 94 * gs - 18 * bs + divisor / 2) / divisor;
        }
    }
    return JNI_TRUE;
}
